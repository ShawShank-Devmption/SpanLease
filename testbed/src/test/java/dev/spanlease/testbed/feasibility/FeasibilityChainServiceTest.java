package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ACQUIRE;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.CANCEL_REQUEST;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RELEASE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.Context;
import io.grpc.stub.ServerCallStreamObserver;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class FeasibilityChainServiceTest {
  private static final String INVOCATION = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

  @Test
  void cancellationDeliveredBeforeGateSubmitStillSignalsAssignedJob() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch allowExit = new CountDownLatch(1);
    AtomicReference<FeasibilityApplicationGate.CancellationToken> token = new AtomicReference<>();
    try (FeasibilityApplicationGate gate = new FeasibilityApplicationGate("svcA", 1, 1, journal);
        SdkTracerProvider provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(new FeasibilitySpanProcessor(journal))
                .build()) {
      FeasibilityChainService service =
          new FeasibilityChainService(
              journal,
              gate,
              provider,
              (request, observer, execution) -> {
                token.set(execution.cancellation());
                running.countDown();
                assertThat(allowExit.await(5, TimeUnit.SECONDS)).isTrue();
              });
      CallRequest request =
          CallRequest.newBuilder()
              .setRequestId("pre-submit-cancel")
              .addRemaining(Hop.newBuilder().setTargetService("svcA"))
              .build();
      try {
        Context.current()
            .withValue(
                FeasibilityMetadata.REQUEST_IDENTITY,
                new FeasibilityMetadata.RequestIdentity(INVOCATION, "client@probe#1"))
            .run(() -> service.call(request, new CancelAtRegistrationObserver()));
        journal.await(ACQUIRE, INVOCATION, Duration.ofSeconds(2));
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        journal.await(CANCEL_REQUEST, INVOCATION, Duration.ofSeconds(2));
        assertThat(token.get().requested()).isTrue();
        assertThat(gate.ownsSlot(INVOCATION)).isTrue();
      } finally {
        allowExit.countDown();
      }
      journal.await(RELEASE, INVOCATION, Duration.ofSeconds(2));
    }
  }

  private static final class CancelAtRegistrationObserver
      extends ServerCallStreamObserver<CallReply> {
    @Override
    public boolean isCancelled() {
      return true;
    }

    @Override
    public void setOnCancelHandler(Runnable handler) {
      handler.run();
    }

    @Override
    public void setCompression(String compression) {}

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnReadyHandler(Runnable handler) {}

    @Override
    public void request(int count) {}

    @Override
    public void disableAutoInboundFlowControl() {}

    @Override
    public void setMessageCompression(boolean enabled) {}

    @Override
    public void onNext(CallReply value) {}

    @Override
    public void onError(Throwable failure) {}

    @Override
    public void onCompleted() {}
  }
}
