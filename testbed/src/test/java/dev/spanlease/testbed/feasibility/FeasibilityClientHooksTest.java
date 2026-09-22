package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.CALLER_INSTANCE;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.CALLER_SEQ;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.INVOCATION_ID;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.RESPONSE_INSTANCE;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.RESPONSE_SEQ;
import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class FeasibilityClientHooksTest {
  private static final String INVOCATION = "22222222-2222-4222-8222-222222222222";

  @Test
  void writesRequestMetadataAndSentMarkerBeforeDelegateStart() {
    FeasibilityJournal journal = new FeasibilityJournal();
    FeasibilityClientHooks.CallCapture capture = new FeasibilityClientHooks.CallCapture(INVOCATION);
    FakeChannel delegate = new FakeChannel(journal);
    Channel channel =
        io.grpc.ClientInterceptors.intercept(
            delegate, FeasibilityClientHooks.interceptor(journal, "client@probe", capture));
    ClientCall<CallRequest, CallReply> call =
        channel.newCall(ChainGrpc.getCallMethod(), CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<>() {}, new Metadata());

    assertThat(delegate.headers.get(INVOCATION_ID)).isEqualTo(INVOCATION);
    assertThat(delegate.headers.get(CALLER_INSTANCE)).isEqualTo("client@probe");
    assertThat(Long.parseLong(delegate.headers.get(CALLER_SEQ))).isPositive();
    assertThat(delegate.sentObservedAtStart).isTrue();
    assertThat(journal.entriesFor(INVOCATION).getFirst().hook())
        .isEqualTo(FeasibilityJournal.Hook.SENT);
  }

  @Test
  void capturesTerminalTrailersBeforeCallingDelegateListener() {
    FeasibilityJournal journal = new FeasibilityJournal();
    FeasibilityClientHooks.CallCapture capture = new FeasibilityClientHooks.CallCapture(INVOCATION);
    FakeChannel delegate = new FakeChannel(journal);
    Channel channel =
        io.grpc.ClientInterceptors.intercept(
            delegate, FeasibilityClientHooks.interceptor(journal, "client@probe", capture));
    ClientCall<CallRequest, CallReply> call =
        channel.newCall(ChainGrpc.getCallMethod(), CallOptions.DEFAULT);
    AtomicBoolean capturedBeforeDelegate = new AtomicBoolean();
    call.start(
        new ClientCall.Listener<>() {
          @Override
          public void onClose(Status status, Metadata trailers) {
            capturedBeforeDelegate.set(
                "server@probe#9".equals(capture.responseReference.get())
                    && Status.OK.equals(capture.terminalStatus.get())
                    && journal.entriesFor(INVOCATION).stream()
                        .anyMatch(
                            entry -> entry.hook() == FeasibilityJournal.Hook.RESPONSE_OBSERVED));
          }
        },
        new Metadata());
    Metadata trailers = new Metadata();
    trailers.put(RESPONSE_INSTANCE, "server@probe");
    trailers.put(RESPONSE_SEQ, "9");
    delegate.listener.onClose(Status.OK, trailers);

    assertThat(capturedBeforeDelegate).isTrue();
  }

  @Test
  void generatedStubClassifiesInstrumentedResponseAndExplicitLocalCancellation() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addStreamTracerFactory(FeasibilityServerHooks.arrivalTracerFactory(journal))
            .intercept(FeasibilityServerHooks.responseInterceptor(journal, "server@probe"))
            .addService(
                new ChainGrpc.ChainImplBase() {
                  @Override
                  public void call(CallRequest request, StreamObserver<CallReply> observer) {
                    if (request.getRequestId().equals("blocked")) {
                      blocked.countDown();
                      try {
                        release.await(2, TimeUnit.SECONDS);
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                      }
                    } else if (request.getRequestId().equals("remote-error")) {
                      observer.onError(Status.INTERNAL.asRuntimeException());
                      return;
                    } else {
                      observer.onNext(CallReply.newBuilder().setRequestId("ok").build());
                      observer.onCompleted();
                    }
                  }
                })
            .build()
            .start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .disableRetry()
            .build();
    try (FeasibilityBlockingHelper helper =
        new FeasibilityBlockingHelper(journal, "client@probe")) {
      ChainGrpc.ChainBlockingStub stub = ChainGrpc.newBlockingStub(channel);
      FeasibilityBlockingHelper.CallResult response =
          helper.start(stub, request("ok"), INVOCATION).await(Duration.ofSeconds(2));
      FeasibilityBlockingHelper.CallResult remoteInstrumented =
          helper
              .start(stub, request("remote-error"), "33333333-3333-4333-8333-333333333333")
              .await(Duration.ofSeconds(2));
      FeasibilityBlockingHelper.CallHandle local =
          helper.start(stub, request("blocked"), "44444444-4444-4444-8444-444444444444");
      assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
      local.cancelLocally(new java.util.concurrent.CancellationException("probe"));

      assertThat(response.completionKind())
          .isEqualTo(FeasibilityBlockingHelper.CompletionKind.RESPONSE);
      assertThat(response.responseReference()).isNotBlank();
      assertThat(remoteInstrumented.status().getCode()).isEqualTo(Status.Code.INTERNAL);
      assertThat(remoteInstrumented.completionKind())
          .isEqualTo(FeasibilityBlockingHelper.CompletionKind.RESPONSE);
      assertThat(local.await(Duration.ofSeconds(2)).completionKind())
          .isEqualTo(FeasibilityBlockingHelper.CompletionKind.LOCAL);
      for (String id :
          new String[] {
            INVOCATION,
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444"
          }) {
        assertThat(journal.entriesFor(id))
            .extracting(FeasibilityJournal.Entry::hook)
            .containsOnlyOnce(FeasibilityJournal.Hook.BLOCK_BEGIN)
            .containsOnlyOnce(FeasibilityJournal.Hook.BLOCK_END);
      }
    } finally {
      release.countDown();
      channel.shutdownNow();
      server.shutdownNow();
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void remoteErrorWithoutResponseTrailersRemainsUnknown() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(
                new ChainGrpc.ChainImplBase() {
                  @Override
                  public void call(CallRequest request, StreamObserver<CallReply> observer) {
                    observer.onError(Status.INTERNAL.asRuntimeException());
                  }
                })
            .build()
            .start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .disableRetry()
            .build();
    try (FeasibilityBlockingHelper helper =
        new FeasibilityBlockingHelper(journal, "client@probe")) {
      FeasibilityBlockingHelper.CallResult result =
          helper
              .start(ChainGrpc.newBlockingStub(channel), request("remote-error"), INVOCATION)
              .await(Duration.ofSeconds(2));
      assertThat(result.status().getCode()).isEqualTo(Status.Code.INTERNAL);
      assertThat(result.responseReference()).isEmpty();
      assertThat(result.completionKind())
          .isEqualTo(FeasibilityBlockingHelper.CompletionKind.UNKNOWN);
      assertThat(journal.entriesFor(INVOCATION))
          .extracting(FeasibilityJournal.Entry::hook)
          .containsOnlyOnce(FeasibilityJournal.Hook.BLOCK_END);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
      assertThat(channel.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      assertThat(server.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static CallRequest request(String requestId) {
    return CallRequest.newBuilder()
        .setRequestId(requestId)
        .addRemaining(Hop.newBuilder().setTargetService("svcA"))
        .build();
  }

  private static final class FakeChannel extends Channel {
    private final FeasibilityJournal journal;
    private Metadata headers;
    private ClientCall.Listener<CallReply> listener;
    private boolean sentObservedAtStart;

    private FakeChannel(FeasibilityJournal journal) {
      this.journal = journal;
    }

    @Override
    public String authority() {
      return "bfeas-fake";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options) {
      return new ClientCall<>() {
        @Override
        @SuppressWarnings("unchecked")
        public void start(Listener<RespT> responseListener, Metadata requestHeaders) {
          headers = new Metadata();
          headers.merge(requestHeaders);
          listener = (ClientCall.Listener<CallReply>) responseListener;
          sentObservedAtStart =
              requestHeaders.get(INVOCATION_ID) != null
                  && requestHeaders.get(CALLER_SEQ) != null
                  && journal.entriesFor(requestHeaders.get(INVOCATION_ID)).stream()
                      .anyMatch(entry -> entry.hook() == FeasibilityJournal.Hook.SENT);
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}
      };
    }
  }
}
