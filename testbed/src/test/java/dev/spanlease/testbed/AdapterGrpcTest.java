package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.EventType;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.instrumentation.InstanceRecorder;
import dev.spanlease.instrumentation.SlotTrackingExecutor;
import dev.spanlease.instrumentation.SpanLeaseClient;
import dev.spanlease.instrumentation.SpanLeaseServer;
import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** R2/R14/R16 against the reusable adapter and generated blocking stub on real loopback gRPC. */
@Timeout(30)
class AdapterGrpcTest {
  @Test
  void responseBeforeDeadlineHasOneBlockEndWithExactResponseParent() throws Exception {
    try (Fixture f = new Fixture()) {
      f.allowHandler.countDown();
      f.allowTransport.countDown();
      f.allowExit.countDown();
      assertThat(f.call(10_000).get(15, TimeUnit.SECONDS)).isEqualTo(Status.Code.OK);
      ObservationEvent end = f.only(f.aEvents, EventType.RPC_BLOCK_END);
      ObservationEvent response = f.only(f.bEvents, EventType.RPC_RESPONSE_SENT);
      assertThat(end.causalParent()).isEqualTo(response.eventReference());
      assertThat(((EventPayload.RpcBlockEnd) end.payload()).completionKind())
          .isEqualTo(EventPayload.CompletionKind.RESPONSE);
    }
  }

  @Test
  void responseCommittedButDeadlineWinsBeforeTransportHasOneLocalBlockEnd() throws Exception {
    try (Fixture f = new Fixture()) {
      f.allowHandler.countDown();
      CompletableFuture<Status.Code> call = f.call(2_000);
      await(f.responseCommitted);
      // The server committed its response, but the close wrapper withholds it until the client
      // deadline fires. No sleep or assumption about which network callback wins the race.
      assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
      ObservationEvent end = f.only(f.aEvents, EventType.RPC_BLOCK_END);
      assertThat(end.causalParent()).isEmpty();
      assertThat(((EventPayload.RpcBlockEnd) end.payload()).completionKind())
          .isEqualTo(EventPayload.CompletionKind.LOCAL);
      assertThat(((EventPayload.RpcBlockEnd) end.payload()).outcome())
          .isEqualTo(EventPayload.Outcome.DEADLINE);
      assertThat(f.bGate.snapshot().ownerByUnit()).containsValue("B:exec-1");
      f.allowTransport.countDown();
      f.allowExit.countDown();
      await(f.bReleased);
      assertThat(f.aEvents.stream().filter(e -> e.eventType() == EventType.RPC_BLOCK_END))
          .hasSize(1);
    }
  }

  @Test
  void responseAndCallerReturnPrecedeServerExitAndSlotRelease() throws Exception {
    try (Fixture f = new Fixture()) {
      f.allowHandler.countDown();
      f.allowTransport.countDown();
      assertThat(f.call(10_000).get(15, TimeUnit.SECONDS)).isEqualTo(Status.Code.OK);
      assertThat(f.bGate.snapshot().ownerByUnit()).containsValue("B:exec-1");
      assertThat(f.bEvents)
          .extracting(ObservationEvent::eventType)
          .contains(EventType.RPC_RESPONSE_SENT)
          .doesNotContain(EventType.EXECUTION_END, EventType.RESOURCE_RELEASE);
      f.allowExit.countDown();
      await(f.bReleased);
      assertThat(f.bEvents)
          .extracting(ObservationEvent::eventType)
          .endsWith(
              EventType.RPC_RESPONSE_SENT, EventType.EXECUTION_END, EventType.RESOURCE_RELEASE);
    }
  }

  @Test
  void cancellationReturnsCallerButDoesNotEndOrReleaseRunningServerJob() throws Exception {
    try (Fixture f = new Fixture()) {
      CompletableFuture<Status.Code> call = f.call(10_000);
      await(f.handlerEntered);
      f.callContext.cancel(null);
      assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(Status.Code.CANCELLED);
      await(f.bCancelled);
      assertThat(f.bGate.snapshot().ownerByUnit()).containsValue("B:exec-1");
      assertThat(f.bEvents)
          .extracting(ObservationEvent::eventType)
          .contains(EventType.EXECUTION_CANCEL)
          .doesNotContain(
              EventType.RPC_RESPONSE_SENT, EventType.EXECUTION_END, EventType.RESOURCE_RELEASE);
      f.allowHandler.countDown();
      await(f.bReleased);
      assertThat(f.bEvents)
          .extracting(ObservationEvent::eventType)
          .endsWith(
              EventType.EXECUTION_CANCEL, EventType.EXECUTION_END, EventType.RESOURCE_RELEASE);
    }
  }

  @Test
  void missingAndDuplicateHeaderIdsGetDistinctExternalIdentitiesWithoutArrivalJoins()
      throws Exception {
    try (Fixture f = new Fixture()) {
      f.allowHandler.countDown();
      f.allowTransport.countDown();
      f.allowExit.countDown();
      f.stub().call(CallRequest.getDefaultInstance());
      Metadata duplicate = headers("duplicate");
      duplicate.put(key("sl-invocation-id"), "duplicate");
      f.stub()
          .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(duplicate))
          .call(CallRequest.getDefaultInstance());
      assertThat(f.bEvents)
          .extracting(ObservationEvent::eventType)
          .doesNotContain(EventType.RPC_INVOCATION_ARRIVED);
      assertThat(
              f.bEvents.stream()
                  .map(ObservationEvent::payload)
                  .filter(EventPayload.ResourceAcquire.class::isInstance)
                  .map(EventPayload.ResourceAcquire.class::cast))
          .extracting(EventPayload.ResourceAcquire::invocationId)
          .containsExactly("external-1", "external-2");
    }
  }

  @Test
  void reusedInvocationIdCannotAcquireASecondSlotOrQueueASecondJob() throws Exception {
    try (Fixture f = new Fixture()) {
      CompletableFuture<Status.Code> first = f.call(10_000);
      await(f.handlerEntered);
      try {
        f.stub()
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers("downstream")))
            .call(CallRequest.getDefaultInstance());
        throw new AssertionError("duplicate invocation was admitted");
      } catch (StatusRuntimeException duplicate) {
        assertThat(duplicate.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
      }
      assertThat(f.bEvents.stream().filter(e -> e.eventType() == EventType.RESOURCE_ACQUIRE))
          .hasSize(1);
      assertThat(f.bGate.snapshot().waitingCount()).isZero();
      f.allowHandler.countDown();
      f.allowTransport.countDown();
      f.allowExit.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(Status.Code.OK);
    }
  }

  private static Metadata headers(String invocation) {
    Metadata headers = new Metadata();
    headers.put(key("sl-invocation-id"), invocation);
    headers.put(key("sl-caller-instance"), "A");
    headers.put(key("sl-caller-seq"), "4");
    return headers;
  }

  private static Metadata.Key<String> key(String name) {
    return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final List<ObservationEvent> aEvents = new CopyOnWriteArrayList<>();
    final List<ObservationEvent> bEvents = new CopyOnWriteArrayList<>();
    final CountDownLatch handlerEntered = new CountDownLatch(1);
    final CountDownLatch allowHandler = new CountDownLatch(1);
    final CountDownLatch responseCommitted = new CountDownLatch(1);
    final CountDownLatch allowTransport = new CountDownLatch(1);
    final CountDownLatch allowExit = new CountDownLatch(1);
    final CountDownLatch bReleased = new CountDownLatch(1);
    final CountDownLatch bCancelled = new CountDownLatch(1);
    final Context.CancellableContext callContext = Context.current().withCancellation();
    final SlotTrackingExecutor aGate = gate("A", aEvents, null, null);
    final SlotTrackingExecutor bGate = gate("B", bEvents, bReleased, bCancelled);
    final Server server;
    final ManagedChannel channel;

    Fixture() throws Exception {
      AtomicInteger external = new AtomicInteger();
      SpanLeaseServer adapter =
          new SpanLeaseServer(bGate, () -> "external-" + external.incrementAndGet());
      ChainGrpc.ChainImplBase service =
          new ChainGrpc.ChainImplBase() {
            @Override
            public void call(CallRequest request, StreamObserver<CallReply> response) {
              adapter.handleUnary(
                  request,
                  response,
                  (execution, ignored) -> {
                    handlerEntered.countDown();
                    await(allowHandler);
                    return CallReply.getDefaultInstance();
                  });
            }
          };
      ServerInterceptor closeBoundary =
          new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(
                ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
              return next.startCall(
                  new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                      // Runs after the adapter has published its response reference.
                      responseCommitted.countDown();
                      if (status.isOk()) await(allowTransport);
                      super.close(status, trailers);
                      if (status.isOk()) await(allowExit);
                    }
                  },
                  headers);
            }
          };
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .addStreamTracerFactory(adapter.streamTracerFactory())
              .addService(ServerInterceptors.intercept(adapter.intercept(service), closeBoundary))
              .build()
              .start();
      channel =
          NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
              .usePlaintext()
              .disableRetry()
              .build();
    }

    ChainGrpc.ChainBlockingStub stub() {
      return ChainGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    CompletableFuture<Status.Code> call(long deadlineMs) {
      CompletableFuture<Status.Code> result = new CompletableFuture<>();
      aGate.submit(
          "root",
          execution -> {
            Context previous = callContext.attach();
            try {
              new SpanLeaseClient(aGate, () -> "downstream")
                  .call(
                      execution,
                      "svcB",
                      stub().withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS),
                      stub -> stub.call(CallRequest.getDefaultInstance()));
              result.complete(Status.Code.OK);
            } catch (StatusRuntimeException failure) {
              result.complete(failure.getStatus().getCode());
            } catch (Throwable failure) {
              result.completeExceptionally(failure);
            } finally {
              callContext.detach(previous);
            }
            return EventPayload.Outcome.OK;
          });
      return result;
    }

    ObservationEvent only(List<ObservationEvent> events, EventType type) {
      List<ObservationEvent> matches = events.stream().filter(e -> e.eventType() == type).toList();
      assertThat(matches).hasSize(1);
      return matches.getFirst();
    }

    private static SlotTrackingExecutor gate(
        String instance,
        List<ObservationEvent> events,
        CountDownLatch released,
        CountDownLatch cancelled) {
      return new SlotTrackingExecutor(
          new InstanceRecorder(
              instance,
              event -> {
                events.add(event);
                if (released != null && event.eventType() == EventType.RESOURCE_RELEASE)
                  released.countDown();
                if (cancelled != null && event.eventType() == EventType.EXECUTION_CANCEL)
                  cancelled.countDown();
                return true;
              },
              Clock.systemUTC(),
              System::nanoTime),
          "app",
          1,
          4,
          runnable -> new Thread(runnable, instance + "-adapter-worker"));
    }

    @Override
    public void close() throws Exception {
      allowHandler.countDown();
      allowTransport.countDown();
      allowExit.countDown();
      callContext.cancel(null);
      channel.shutdownNow();
      server.shutdownNow();
      aGate.close();
      bGate.close();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
