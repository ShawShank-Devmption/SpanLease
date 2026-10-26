package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Blocking-call helper for generated blocking stubs invoked by an admitted execution.
 *
 * <p>Allocates the downstream invocation identity, then opens the execution's single logical block
 * and commits {@code rpc.block.begin} and {@code rpc.invocation.sent} before the stub dispatches
 * (I5). The send reference travels in request metadata. The block closes exactly once: with
 * completion kind {@code response} and the server's trailer reference as causal parent when one was
 * received, otherwise as a {@code local} completion carrying the client-observed status (deadline,
 * cancellation or transport failure). SpanLease servers attach the reference on every close they
 * perform, so a missing reference means no instrumented response reached this caller.
 */
public final class SpanLeaseClient {
  private final SlotTrackingExecutor gate;
  private final Supplier<String> invocationIds;

  public SpanLeaseClient(SlotTrackingExecutor gate, Supplier<String> invocationIds) {
    this.gate = Objects.requireNonNull(gate, "gate");
    this.invocationIds = Objects.requireNonNull(invocationIds, "invocationIds");
  }

  /**
   * Performs exactly one blocking unary call through {@code rpc} on the instrumented stub.
   *
   * @param execution the calling job; it keeps its slot for the whole call
   * @param targetService logical service the call is addressed to
   */
  public <S extends AbstractStub<S>, R> R call(
      SlotTrackingExecutor.ExecutionContext execution,
      String targetService,
      S stub,
      Function<S, R> rpc) {
    Objects.requireNonNull(stub, "stub");
    Objects.requireNonNull(rpc, "rpc");
    if (!gate.recorder().enabled()) return rpc.apply(stub);
    String invocationId = invocationIds.get();
    SlotTrackingExecutor.BlockHandle block = gate.openBlock(execution, invocationId, targetService);
    AtomicReference<Metadata> trailers = new AtomicReference<>();
    S instrumented =
        stub.withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(
                RpcMetadata.requestHeaders(invocationId, block.sentReference())),
            MetadataUtils.newCaptureMetadataInterceptor(new AtomicReference<>(), trailers));
    try {
      R reply = rpc.apply(instrumented);
      complete(block, trailers.get(), Status.OK);
      return reply;
    } catch (StatusRuntimeException failure) {
      Metadata received = trailers.get() != null ? trailers.get() : failure.getTrailers();
      complete(block, received, failure.getStatus());
      throw failure;
    } catch (RuntimeException failure) {
      block.completeLocal(EventPayload.Outcome.ERROR);
      throw failure;
    }
  }

  private static void complete(
      SlotTrackingExecutor.BlockHandle block, Metadata trailers, Status status) {
    Optional<String> response = RpcMetadata.responseReference(trailers);
    if (response.isPresent()) {
      block.completeResponse(response.orElseThrow(), RpcMetadata.outcome(status));
    } else {
      block.completeLocal(RpcMetadata.outcome(status));
    }
  }
}
