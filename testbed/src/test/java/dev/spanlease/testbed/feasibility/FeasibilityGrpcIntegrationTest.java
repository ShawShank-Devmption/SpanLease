package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ACQUIRE;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.APPLICATION_SPAN_START;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ARRIVAL;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.BLOCK_END;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.CANCEL_REQUEST;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.EXECUTION_END;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.INVALID_ARRIVAL;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.QUEUED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.READY;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.REJECTED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RELEASE;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RESPONSE_OBSERVED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RESPONSE_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class FeasibilityGrpcIntegrationTest {
  private static final String FIRST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String SECOND = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String THIRD = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

  @Test
  void saturatedDecodedRequestArrivesAndQueuesBeforeApplicationSpan() throws Exception {
    CountDownLatch firstRunning = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try (FeasibilityHarness harness =
        FeasibilityHarness.start(
            1,
            1,
            (request, observer, execution) -> {
              if (request.getRequestId().equals("first")) {
                firstRunning.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
              }
              observer.onNext(reply(request));
              observer.onCompleted();
            },
            true)) {
      try {
        FeasibilityBlockingHelper.CallHandle first =
            harness.helper().start(harness.stub(), request("first"), FIRST);
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();
        FeasibilityBlockingHelper.CallHandle second =
            harness.helper().start(harness.stub(), request("second"), SECOND);

        FeasibilityJournal.Entry arrival =
            harness.journal().await(ARRIVAL, SECOND, Duration.ofSeconds(2));
        FeasibilityJournal.Entry ready =
            harness.journal().await(READY, SECOND, Duration.ofSeconds(2));
        FeasibilityJournal.Entry queued =
            harness.journal().await(QUEUED, SECOND, Duration.ofSeconds(2));
        assertThat(arrival.ordinal()).isLessThan(ready.ordinal());
        assertThat(ready.ordinal()).isLessThan(queued.ordinal());
        assertThat(hooks(harness.journal(), SECOND))
            .doesNotContain(ACQUIRE, APPLICATION_SPAN_START);
        assertThat(harness.gate().ownsSlot(SECOND)).isFalse();

        releaseFirst.countDown();
        assertThat(first.await(Duration.ofSeconds(2)).status().getCode()).isEqualTo(Status.Code.OK);
        assertThat(second.await(Duration.ofSeconds(2)).status().getCode())
            .isEqualTo(Status.Code.OK);
        FeasibilityJournal.Entry acquired =
            harness.journal().await(ACQUIRE, SECOND, Duration.ofSeconds(2));
        FeasibilityJournal.Entry span =
            harness.journal().await(APPLICATION_SPAN_START, SECOND, Duration.ofSeconds(2));
        assertThat(acquired.ordinal()).isLessThan(span.ordinal());
      } finally {
        releaseFirst.countDown();
      }
    }
  }

  @Test
  void thirdRealRpcRejectsOverflowWithoutSlotOwnership() throws Exception {
    CountDownLatch firstRunning = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try (FeasibilityHarness harness =
        FeasibilityHarness.start(
            1,
            1,
            (request, observer, execution) -> {
              if (request.getRequestId().equals("first")) {
                firstRunning.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
              }
              observer.onNext(reply(request));
              observer.onCompleted();
            },
            true)) {
      try {
        FeasibilityBlockingHelper.CallHandle first =
            harness.helper().start(harness.stub(), request("first"), FIRST);
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();
        FeasibilityBlockingHelper.CallHandle second =
            harness.helper().start(harness.stub(), request("second"), SECOND);
        harness.journal().await(QUEUED, SECOND, Duration.ofSeconds(2));
        FeasibilityBlockingHelper.CallResult third =
            harness
                .helper()
                .start(harness.stub(), request("third"), THIRD)
                .await(Duration.ofSeconds(2));

        assertThat(third.status().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(hooks(harness.journal(), THIRD)).contains(REJECTED).doesNotContain(ACQUIRE);
        assertThat(harness.gate().ownsSlot(THIRD)).isFalse();
        releaseFirst.countDown();
        first.await(Duration.ofSeconds(2));
        second.await(Duration.ofSeconds(2));
      } finally {
        releaseFirst.countDown();
      }
    }
  }

  @Test
  void malformedRequestMetadataNeverReachesReadyOrGate() throws Exception {
    try (FeasibilityHarness harness =
        FeasibilityHarness.start(
            1,
            1,
            (request, observer, execution) -> {
              observer.onNext(reply(request));
              observer.onCompleted();
            },
            true)) {
      assertThatThrownBy(() -> ChainGrpc.newBlockingStub(harness.channel()).call(request("raw")))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(((StatusRuntimeException) error).getStatus().getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
      assertThat(harness.journal().snapshot())
          .extracting(FeasibilityJournal.Entry::hook)
          .contains(INVALID_ARRIVAL)
          .doesNotContain(READY, ACQUIRE);
    }
  }

  @Test
  void responseCanPrecedeExitAndRelease() throws Exception {
    CountDownLatch responseClosed = new CountDownLatch(1);
    CountDownLatch allowExit = new CountDownLatch(1);
    try (FeasibilityHarness harness =
        FeasibilityHarness.start(
            1,
            1,
            (request, observer, execution) -> {
              observer.onNext(reply(request));
              observer.onCompleted();
              responseClosed.countDown();
              assertThat(allowExit.await(5, TimeUnit.SECONDS)).isTrue();
            },
            true)) {
      try {
        FeasibilityBlockingHelper.CallHandle handle =
            harness.helper().start(harness.stub(), request("respond-then-exit"), FIRST);
        assertThat(responseClosed.await(2, TimeUnit.SECONDS)).isTrue();
        FeasibilityBlockingHelper.CallResult result = handle.await(Duration.ofSeconds(2));
        assertThat(result.completionKind())
            .isEqualTo(FeasibilityBlockingHelper.CompletionKind.RESPONSE);
        assertThat(result.responseReference()).isNotBlank();
        assertThat(harness.gate().ownsSlot(FIRST)).isTrue();
        assertThat(hooks(harness.journal(), FIRST)).doesNotContain(EXECUTION_END, RELEASE);

        allowExit.countDown();
        harness.journal().await(RELEASE, FIRST, Duration.ofSeconds(2));
        assertThat(hooks(harness.journal(), FIRST))
            .containsSubsequence(
                RESPONSE_RESERVED, RESPONSE_OBSERVED, BLOCK_END, EXECUTION_END, RELEASE);
      } finally {
        allowExit.countDown();
      }
    }
  }

  @Test
  void cancellationDoesNotReleaseAndCallbacksRemainIndependent() throws Exception {
    CountDownLatch applicationRunning = new CountDownLatch(1);
    CountDownLatch allowExit = new CountDownLatch(1);
    AtomicReference<String> applicationThread = new AtomicReference<>();
    AtomicReference<FeasibilityApplicationGate.CancellationToken> token = new AtomicReference<>();
    try (FeasibilityHarness harness =
        FeasibilityHarness.start(
            1,
            1,
            (request, observer, execution) -> {
              applicationThread.set(Thread.currentThread().getName());
              token.set(execution.cancellation());
              applicationRunning.countDown();
              assertThat(allowExit.await(5, TimeUnit.SECONDS)).isTrue();
            },
            true)) {
      try {
        FeasibilityBlockingHelper.CallHandle handle =
            harness.helper().start(harness.stub(), request("cancel-running"), FIRST);
        assertThat(applicationRunning.await(2, TimeUnit.SECONDS)).isTrue();
        handle.cancelLocally(new java.util.concurrent.CancellationException("probe"));
        FeasibilityJournal.Entry cancellation =
            harness.journal().await(CANCEL_REQUEST, FIRST, Duration.ofSeconds(2));
        assertThat(token.get().requested()).isTrue();
        assertThat(harness.gate().ownsSlot(FIRST)).isTrue();
        assertThat(hooks(harness.journal(), FIRST)).doesNotContain(EXECUTION_END, RELEASE);
        assertThat(cancellation.threadName()).startsWith("bfeas-grpc-callback-");
        assertThat(applicationThread.get()).startsWith("bfeas-app-");
        assertThat(cancellation.threadName()).isNotEqualTo(applicationThread.get());
        assertThat(handle.await(Duration.ofSeconds(2)).completionKind())
            .isEqualTo(FeasibilityBlockingHelper.CompletionKind.LOCAL);

        allowExit.countDown();
        harness.journal().await(RELEASE, FIRST, Duration.ofSeconds(2));
        assertThat(hooks(harness.journal(), FIRST))
            .containsSubsequence(CANCEL_REQUEST, EXECUTION_END, RELEASE);
      } finally {
        allowExit.countDown();
      }
    }
  }

  @Test
  void missingRemoteTrailersRemainUnknown() throws Exception {
    try (FeasibilityHarness harness =
        FeasibilityHarness.start(
            1,
            1,
            (request, observer, execution) ->
                observer.onError(Status.INTERNAL.asRuntimeException()),
            false)) {
      FeasibilityBlockingHelper.CallResult result =
          harness
              .helper()
              .start(harness.stub(), request("remote-error"), FIRST)
              .await(Duration.ofSeconds(2));
      assertThat(result.status().getCode()).isEqualTo(Status.Code.INTERNAL);
      assertThat(result.responseReference()).isEmpty();
      assertThat(result.completionKind())
          .isEqualTo(FeasibilityBlockingHelper.CompletionKind.UNKNOWN);
      assertThat(hooks(harness.journal(), FIRST)).containsOnlyOnce(BLOCK_END);
    }
  }

  private static CallRequest request(String requestId) {
    return CallRequest.newBuilder()
        .setRequestId(requestId)
        .addRemaining(Hop.newBuilder().setTargetService("svcA"))
        .build();
  }

  private static CallReply reply(CallRequest request) {
    return CallReply.newBuilder().setRequestId(request.getRequestId()).setHopsCompleted(1).build();
  }

  private static List<FeasibilityJournal.Hook> hooks(FeasibilityJournal journal, String id) {
    return journal.entriesFor(id).stream().map(FeasibilityJournal.Entry::hook).toList();
  }
}
