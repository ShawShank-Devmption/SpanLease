package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Server-side SpanLease hooks binding incoming unary gRPC requests to one application gate.
 *
 * <p>Install {@link #streamTracerFactory()} on the server builder, wrap each service with {@link
 * #intercept}, and dispatch every unary method through {@link #handleUnary}. The stream tracer
 * records {@code rpc.invocation.arrived} with the caller's send reference before the call is
 * dispatched (an ordinary interceptor is too late for arrival). The unary adapter submits exactly
 * one job to the gate and returns, so gRPC callback threads never run blocking application work.
 * The response-close wrapper commits {@code rpc.response.sent} and puts its reference in trailers
 * before delegating close, once per call (I5). Cancellation is cooperative: it removes a queued job
 * or is recorded for a running one, which keeps its slot until it actually exits.
 *
 * <p>A request without valid SpanLease identity comes from outside the observed deployment: it gets
 * a local invocation identity and no arrival record, so it can never be joined to a caller's wait.
 */
public final class SpanLeaseServer {
  private static final Context.Key<String> INBOUND_INVOCATION =
      Context.key("spanlease-inbound-invocation");

  private final java.util.function.BiConsumer<Object, String> bindings;
  private final InstanceRecorder recorder;
  private final SlotTrackingExecutor gate;
  private final Supplier<String> localInvocationIds;
  private final ServerInterceptor responseClose = new ResponseCloseInterceptor();

  public SpanLeaseServer(SlotTrackingExecutor gate, Supplier<String> localInvocationIds) {
    this(gate, localInvocationIds, (job, invocation) -> {});
  }

  public SpanLeaseServer(
      SlotTrackingExecutor gate,
      Supplier<String> localInvocationIds,
      java.util.function.BiConsumer<Object, String> bindings) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.recorder = gate.recorder();
    this.localInvocationIds = Objects.requireNonNull(localInvocationIds, "localInvocationIds");
  }

  public ServerStreamTracer.Factory streamTracerFactory() {
    return new ServerStreamTracer.Factory() {
      @Override
      public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
        String invocationId = recordArrival(headers);
        return new ServerStreamTracer() {
          @Override
          public Context filterContext(Context context) {
            return context.withValue(INBOUND_INVOCATION, invocationId);
          }
        };
      }
    };
  }

  public ServerServiceDefinition intercept(BindableService service) {
    return ServerInterceptors.intercept(service, responseClose);
  }

  /**
   * Admits one decoded unary request to the gate. Must be called from the generated service method
   * on the gRPC callback thread; returns as soon as the job is assigned, queued or rejected.
   */
  public <Q, R> void handleUnary(
      Q request, StreamObserver<R> responseObserver, UnaryHandler<Q, R> handler) {
    handleUnary(request, responseObserver, handler, null);
  }

  public <Q, R> void handleUnary(
      Q request,
      StreamObserver<R> responseObserver,
      UnaryHandler<Q, R> handler,
      Object applicationJob) {
    Objects.requireNonNull(handler, "handler");
    ServerCallStreamObserver<R> call = (ServerCallStreamObserver<R>) responseObserver;
    String invocationId = INBOUND_INVOCATION.get();
    if (invocationId == null) {
      call.onError(
          Status.INTERNAL
              .withDescription("SpanLease stream tracer is not installed")
              .asRuntimeException());
      return;
    }
    bindings.accept(applicationJob, invocationId);
    Context callContext = Context.current();
    call.setOnCancelHandler(
        () ->
            gate.requestCancellation(
                invocationId,
                deadlineExpired(callContext)
                    ? EventPayload.CancelSource.DEADLINE
                    : EventPayload.CancelSource.CLIENT,
                deadlineExpired(callContext)
                    ? EventPayload.CancelReason.DEADLINE
                    : EventPayload.CancelReason.REQUESTED));

    SlotTrackingExecutor.Admission admission;
    try {
      admission =
          gate.submit(
              invocationId,
              execution -> run(execution, request, call, callContext, handler),
              applicationJob);
    } catch (IllegalStateException duplicate) {
      call.onError(
          Status.INTERNAL.withDescription("duplicate invocation identity").asRuntimeException());
      return;
    }
    // Cancellation can arrive before registration/submission, when the gate has no job to find.
    // Recheck after the atomic admission decision so that a pre-cancelled queue entry cannot leak.
    if (call.isCancelled() || callContext.isCancelled()) {
      gate.requestCancellation(
          invocationId,
          deadlineExpired(callContext)
              ? EventPayload.CancelSource.DEADLINE
              : EventPayload.CancelSource.CLIENT,
          deadlineExpired(callContext)
              ? EventPayload.CancelReason.DEADLINE
              : EventPayload.CancelReason.REQUESTED);
    }
    if (admission == SlotTrackingExecutor.Admission.REJECTED) {
      call.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("application gate queue is full")
              .asRuntimeException());
    }
  }

  private String recordArrival(Metadata headers) {
    if (!recorder.enabled()) return localInvocationIds.get();
    Optional<String> invocationId = RpcMetadata.invocationId(headers);
    Optional<String> requestReference = RpcMetadata.requestReference(headers);
    if (invocationId.isEmpty() || requestReference.isEmpty()) {
      return localInvocationIds.get();
    }
    recorder.publish(
        new EventPayload.RpcInvocationArrived(invocationId.orElseThrow()),
        requestReference.orElseThrow());
    return invocationId.orElseThrow();
  }

  private static <Q, R> EventPayload.Outcome run(
      SlotTrackingExecutor.ExecutionContext execution,
      Q request,
      ServerCallStreamObserver<R> call,
      Context callContext,
      UnaryHandler<Q, R> handler) {
    Context previous = callContext.attach();
    try {
      R reply = handler.handle(execution, request);
      if (call.isCancelled()) {
        return cancelledOutcome(callContext);
      }
      call.onNext(reply);
      call.onCompleted();
      return EventPayload.Outcome.OK;
    } catch (StatusRuntimeException failure) {
      if (call.isCancelled()) {
        return cancelledOutcome(callContext);
      }
      // Status only: forwarding downstream trailers would forward its response reference too.
      call.onError(failure.getStatus().asRuntimeException());
      return RpcMetadata.outcome(failure.getStatus());
    } catch (RuntimeException failure) {
      if (!call.isCancelled()) {
        call.onError(Status.INTERNAL.withCause(failure).asRuntimeException());
      }
      return EventPayload.Outcome.ERROR;
    } finally {
      callContext.detach(previous);
    }
  }

  private static EventPayload.Outcome cancelledOutcome(Context callContext) {
    return deadlineExpired(callContext)
        ? EventPayload.Outcome.DEADLINE
        : EventPayload.Outcome.CANCELLED;
  }

  private static boolean deadlineExpired(Context callContext) {
    Deadline deadline = callContext.getDeadline();
    return deadline != null && deadline.isExpired();
  }

  /** Blocking application handler run on an admitted worker that owns its slot. */
  @FunctionalInterface
  public interface UnaryHandler<Q, R> {
    R handle(SlotTrackingExecutor.ExecutionContext execution, Q request);
  }

  private final class ResponseCloseInterceptor implements ServerInterceptor {
    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
        ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
      String invocationId = INBOUND_INVOCATION.get();
      if (invocationId == null) {
        return next.startCall(call, headers);
      }
      AtomicBoolean responded = new AtomicBoolean();
      ServerCall<Q, R> recorded =
          new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
              if (responded.compareAndSet(false, true)) {
                String reference = gate.responseSent(invocationId, RpcMetadata.outcome(status));
                if (recorder.enabled()) RpcMetadata.putResponse(trailers, reference);
              }
              super.close(status, trailers);
            }
          };
      return next.startCall(recorded, headers);
    }
  }
}
