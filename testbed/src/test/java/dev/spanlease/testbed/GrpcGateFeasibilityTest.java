package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GrpcGateFeasibilityTest {
  private static final Metadata.Key<String> INVOCATION_ID =
      Metadata.Key.of("sl-invocation-id", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> RESPONSE_INSTANCE =
      Metadata.Key.of("sl-response-instance", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> RESPONSE_SEQ =
      Metadata.Key.of("sl-response-seq", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> INVOCATION_CONTEXT = Context.key("sl-invocation-id");

  @Test
  @Timeout(30)
  void realGrpcEstablishesRequiredGateAndRpcLifetimes() throws Exception {
    BlockingQueue<String> gateTransitions = new LinkedBlockingQueue<>();
    AtomicLong hookOrder = new AtomicLong();
    Map<String, Long> arrivals = new ConcurrentHashMap<>();
    Map<String, Long> callbacks = new ConcurrentHashMap<>();
    CountDownLatch firstMayRespond = new CountDownLatch(1);
    CountDownLatch firstMayExit = new CountDownLatch(1);
    CountDownLatch cancellationObserved = new CountDownLatch(1);
    CountDownLatch cancelMayExit = new CountDownLatch(1);
    AtomicReference<String> blockingHelperThread = new AtomicReference<>();
    AtomicReference<String> downstreamCallbackThread = new AtomicReference<>();

    ExecutorService downstreamCallbacks =
        Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "feasibility-downstream-callback"));
    Server downstream =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .executor(downstreamCallbacks)
            .addService(
                new ChainGrpc.ChainImplBase() {
                  @Override
                  public void call(CallRequest request, StreamObserver<CallReply> observer) {
                    downstreamCallbackThread.set(Thread.currentThread().getName());
                    observer.onNext(reply(request));
                    observer.onCompleted();
                  }
                })
            .build()
            .start();

    ManagedChannel downstreamChannel =
        NettyChannelBuilder.forAddress("127.0.0.1", downstream.getPort())
            .usePlaintext()
            .disableRetry()
            .build();
    BlockingQueue<String> responseCloses = new LinkedBlockingQueue<>();
    AtomicLong responseSequence = new AtomicLong();
    ExecutorService callbacksExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "feasibility-grpc-callback"));
    FeasibilityGate gate = new FeasibilityGate(1, 4, gateTransitions);
    FeasibilityService service =
        new FeasibilityService(
            gate,
            ChainGrpc.newBlockingStub(downstreamChannel),
            hookOrder,
            callbacks,
            firstMayRespond,
            firstMayExit,
            cancellationObserved,
            cancelMayExit,
            blockingHelperThread);

    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .executor(callbacksExecutor)
            .addStreamTracerFactory(arrivalTracer(hookOrder, arrivals))
            .addService(
                ServerInterceptors.intercept(
                    service, responseTrailers(responseSequence, responseCloses)))
            .build()
            .start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .disableRetry()
            .build();
    ExecutorService clients = Executors.newFixedThreadPool(2);

    try {
      AtomicReference<Metadata> firstTrailers = new AtomicReference<>();
      Future<CallReply> first = clients.submit(() -> blockingCall(channel, "first", firstTrailers));
      assertThat(take(gateTransitions)).isEqualTo("acquire:first");

      AtomicReference<Metadata> secondTrailers = new AtomicReference<>();
      Future<CallReply> second =
          clients.submit(() -> blockingCall(channel, "second", secondTrailers));
      assertThat(take(gateTransitions)).isEqualTo("wait:second");
      assertThat(gate.owner()).isEqualTo("first");
      assertThat(gate.waitingCount()).isEqualTo(1);

      firstMayRespond.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).getRequestId()).isEqualTo("first");
      assertThat(take(responseCloses)).isEqualTo("first");
      assertThat(firstTrailers.get().get(RESPONSE_INSTANCE)).isEqualTo("feasibility-instance");
      assertThat(firstTrailers.get().get(RESPONSE_SEQ)).isNotBlank();
      assertThat(gate.owner()).isEqualTo("first");
      assertThat(gate.waitingCount()).isEqualTo(1);

      firstMayExit.countDown();
      assertThat(take(gateTransitions)).isEqualTo("end:first");
      assertThat(take(gateTransitions)).isEqualTo("release:first");
      assertThat(take(gateTransitions)).isEqualTo("acquire:second");
      assertThat(second.get(5, TimeUnit.SECONDS).getRequestId()).isEqualTo("second");
      assertThat(take(responseCloses)).isEqualTo("second");
      assertThat(take(gateTransitions)).isEqualTo("end:second");
      assertThat(take(gateTransitions)).isEqualTo("release:second");

      AtomicReference<Metadata> blockingTrailers = new AtomicReference<>();
      assertThat(blockingCall(channel, "blocking", blockingTrailers).getRequestId())
          .isEqualTo("blocking");
      assertThat(take(gateTransitions)).isEqualTo("acquire:blocking");
      assertThat(take(responseCloses)).isEqualTo("blocking");
      assertThat(take(gateTransitions)).isEqualTo("end:blocking");
      assertThat(take(gateTransitions)).isEqualTo("release:blocking");
      assertThat(blockingHelperThread.get()).startsWith("feasibility-app-worker");
      assertThat(downstreamCallbackThread.get()).startsWith("feasibility-downstream-callback");

      ClientCall<CallRequest, CallReply> cancelled =
          channel.newCall(ChainGrpc.getCallMethod(), CallOptions.DEFAULT);
      Metadata cancelHeaders = new Metadata();
      cancelHeaders.put(INVOCATION_ID, "cancel");
      cancelled.start(new NoopClientListener(), cancelHeaders);
      cancelled.request(1);
      cancelled.sendMessage(request("cancel"));
      cancelled.halfClose();
      assertThat(take(gateTransitions)).isEqualTo("acquire:cancel");
      cancelled.cancel("feasibility cancellation", null);
      assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(gate.owner()).isEqualTo("cancel");
      cancelMayExit.countDown();
      assertThat(take(gateTransitions)).isEqualTo("end:cancel");
      assertThat(take(gateTransitions)).isEqualTo("release:cancel");

      assertThat(arrivals).containsKeys("first", "second", "blocking", "cancel");
      assertThat(callbacks).containsKeys("first", "second", "blocking", "cancel");
      arrivals.forEach(
          (id, arrivalOrder) -> assertThat(arrivalOrder).isLessThan(callbacks.get(id)));
      assertThat(callbacksExecutor.submit(() -> Thread.currentThread().getName()).get())
          .startsWith("feasibility-grpc-callback");
    } finally {
      firstMayRespond.countDown();
      firstMayExit.countDown();
      cancelMayExit.countDown();
      clients.shutdownNow();
      channel.shutdownNow();
      server.shutdownNow();
      gate.close();
      downstreamChannel.shutdownNow();
      downstream.shutdownNow();
      callbacksExecutor.shutdownNow();
      downstreamCallbacks.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(downstreamChannel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(downstream.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static ServerStreamTracer.Factory arrivalTracer(
      AtomicLong hookOrder, Map<String, Long> arrivals) {
    return new ServerStreamTracer.Factory() {
      @Override
      public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
        String id = headers.get(INVOCATION_ID);
        if (id != null) {
          arrivals.put(id, hookOrder.incrementAndGet());
        }
        return new ServerStreamTracer() {
          @Override
          public Context filterContext(Context context) {
            return id == null ? context : context.withValue(INVOCATION_CONTEXT, id);
          }
        };
      }
    };
  }

  private static ServerInterceptor responseTrailers(
      AtomicLong responseSequence, BlockingQueue<String> responseCloses) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String id = INVOCATION_CONTEXT.get();
        ServerCall<ReqT, RespT> wrapped =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
              @Override
              public void close(Status status, Metadata trailers) {
                trailers.put(RESPONSE_INSTANCE, "feasibility-instance");
                trailers.put(RESPONSE_SEQ, Long.toString(responseSequence.incrementAndGet()));
                responseCloses.add(id);
                super.close(status, trailers);
              }
            };
        return next.startCall(wrapped, headers);
      }
    };
  }

  private static CallReply blockingCall(
      ManagedChannel channel, String id, AtomicReference<Metadata> trailers) {
    Metadata requestHeaders = new Metadata();
    requestHeaders.put(INVOCATION_ID, id);
    return ChainGrpc.newBlockingStub(channel)
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(requestHeaders),
            MetadataUtils.newCaptureMetadataInterceptor(new AtomicReference<>(), trailers))
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .call(request(id));
  }

  private static CallRequest request(String id) {
    return CallRequest.newBuilder()
        .setRequestId(id)
        .addRemaining(Hop.newBuilder().setTargetService("feasibility"))
        .build();
  }

  private static CallReply reply(CallRequest request) {
    return CallReply.newBuilder().setRequestId(request.getRequestId()).setHopsCompleted(1).build();
  }

  private static String take(BlockingQueue<String> events) throws InterruptedException {
    String event = events.poll(5, TimeUnit.SECONDS);
    assertThat(event).as("expected lifecycle event").isNotNull();
    return event;
  }

  private static final class FeasibilityService extends ChainGrpc.ChainImplBase {
    private final FeasibilityGate gate;
    private final ChainGrpc.ChainBlockingStub downstream;
    private final AtomicLong hookOrder;
    private final Map<String, Long> callbacks;
    private final CountDownLatch firstMayRespond;
    private final CountDownLatch firstMayExit;
    private final CountDownLatch cancellationObserved;
    private final CountDownLatch cancelMayExit;
    private final AtomicReference<String> blockingHelperThread;

    private FeasibilityService(
        FeasibilityGate gate,
        ChainGrpc.ChainBlockingStub downstream,
        AtomicLong hookOrder,
        Map<String, Long> callbacks,
        CountDownLatch firstMayRespond,
        CountDownLatch firstMayExit,
        CountDownLatch cancellationObserved,
        CountDownLatch cancelMayExit,
        AtomicReference<String> blockingHelperThread) {
      this.gate = gate;
      this.downstream = downstream;
      this.hookOrder = hookOrder;
      this.callbacks = callbacks;
      this.firstMayRespond = firstMayRespond;
      this.firstMayExit = firstMayExit;
      this.cancellationObserved = cancellationObserved;
      this.cancelMayExit = cancelMayExit;
      this.blockingHelperThread = blockingHelperThread;
    }

    @Override
    public void call(CallRequest request, StreamObserver<CallReply> observer) {
      String id = INVOCATION_CONTEXT.get();
      callbacks.put(id, hookOrder.incrementAndGet());
      Context callContext = Context.current();
      if (id.equals("cancel")) {
        callContext.addListener(ignored -> cancellationObserved.countDown(), Runnable::run);
      }
      boolean accepted = gate.submit(id, () -> runJob(id, request, observer, callContext));
      if (!accepted) {
        observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());
      }
    }

    private void runJob(
        String id, CallRequest request, StreamObserver<CallReply> observer, Context callContext) {
      try {
        if (id.equals("first")) {
          await(firstMayRespond);
        }
        if (id.equals("blocking")) {
          blockingHelperThread.set(Thread.currentThread().getName());
          downstream.withDeadlineAfter(5, TimeUnit.SECONDS).call(request);
        }
        if (id.equals("cancel")) {
          await(cancelMayExit);
          return;
        }
        if (!callContext.isCancelled()) {
          observer.onNext(reply(request));
          observer.onCompleted();
        }
        if (id.equals("first")) {
          await(firstMayExit);
        }
      } catch (RuntimeException exception) {
        observer.onError(Status.INTERNAL.withCause(exception).asRuntimeException());
      }
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("feasibility latch timed out");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("feasibility job interrupted", exception);
      }
    }
  }

  private static final class NoopClientListener extends ClientCall.Listener<CallReply> {
    @Override
    public void onMessage(CallReply message) {}

    @Override
    public void onClose(Status status, Metadata trailers) {}
  }
}
