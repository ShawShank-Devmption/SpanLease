package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityApplicationGate.Admission.ASSIGNED;
import static dev.spanlease.testbed.feasibility.FeasibilityApplicationGate.Admission.QUEUED;
import static dev.spanlease.testbed.feasibility.FeasibilityApplicationGate.Admission.REJECTED;
import static dev.spanlease.testbed.feasibility.FeasibilityApplicationGate.CancelResult.QUEUED_REMOVED;
import static dev.spanlease.testbed.feasibility.FeasibilityApplicationGate.CancelResult.RUNNING_SIGNALLED;
import static dev.spanlease.testbed.feasibility.FeasibilityApplicationGate.JobState.CANCELLED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ACQUIRE;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.APPLICATION_SPAN_START;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.EXECUTION_END;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RELEASE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class FeasibilityApplicationGateTest {
  @Test
  void assignsOneJobQueuesOneAndRejectsOverflowWithoutEarlyAcquire() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    CountDownLatch firstRunning = new CountDownLatch(1);
    CountDownLatch finishFirst = new CountDownLatch(1);
    CountDownLatch secondRan = new CountDownLatch(1);
    try (FeasibilityApplicationGate gate = new FeasibilityApplicationGate("probe", 1, 1, journal)) {
      try {
        assertThat(
                gate.submit(
                    "inv-1",
                    execution -> {
                      firstRunning.countDown();
                      assertThat(finishFirst.await(2, TimeUnit.SECONDS)).isTrue();
                    }))
            .isEqualTo(ASSIGNED);
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(gate.submit("inv-2", execution -> secondRan.countDown())).isEqualTo(QUEUED);
        assertThat(gate.submit("inv-3", execution -> {})).isEqualTo(REJECTED);
        assertThat(gate.state("inv-2")).isEqualTo(FeasibilityApplicationGate.JobState.QUEUED);
        assertThat(gate.state("inv-3")).isEqualTo(FeasibilityApplicationGate.JobState.REJECTED);
        assertThat(hooksFor(journal, "inv-1")).contains(ACQUIRE);
        assertThat(hooksFor(journal, "inv-2")).containsExactly(FeasibilityJournal.Hook.QUEUED);
        assertThat(hooksFor(journal, "inv-3")).containsExactly(FeasibilityJournal.Hook.REJECTED);

        finishFirst.countDown();
        assertThat(secondRan.await(2, TimeUnit.SECONDS)).isTrue();
        journal.await(RELEASE, "inv-2", Duration.ofSeconds(2));
        assertThat(hooksFor(journal, "inv-2"))
            .containsSubsequence(FeasibilityJournal.Hook.QUEUED, ACQUIRE, RELEASE);
      } finally {
        finishFirst.countDown();
      }
    }
  }

  @Test
  void queuedCancellationRemovesJobWithoutAcquireOrApplicationSpan() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    CountDownLatch firstRunning = new CountDownLatch(1);
    CountDownLatch finishFirst = new CountDownLatch(1);
    try (FeasibilityApplicationGate gate = new FeasibilityApplicationGate("probe", 1, 1, journal)) {
      try {
        gate.submit(
            "inv-1",
            execution -> {
              firstRunning.countDown();
              assertThat(finishFirst.await(2, TimeUnit.SECONDS)).isTrue();
            });
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.submit("inv-2", execution -> {})).isEqualTo(QUEUED);

        assertThat(gate.cancel("inv-2")).isEqualTo(QUEUED_REMOVED);
        assertThat(gate.state("inv-2")).isEqualTo(CANCELLED);
        assertThat(hooksFor(journal, "inv-2")).doesNotContain(ACQUIRE, APPLICATION_SPAN_START);
      } finally {
        finishFirst.countDown();
      }
      journal.await(RELEASE, "inv-1", Duration.ofSeconds(2));
    }
  }

  @Test
  void runningCancellationSignalsTokenWithoutReleasingSlot() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicReference<FeasibilityApplicationGate.CancellationToken> tokenSeenByJob =
        new AtomicReference<>();
    try (FeasibilityApplicationGate gate = new FeasibilityApplicationGate("probe", 1, 1, journal)) {
      try {
        gate.submit(
            "inv-1",
            execution -> {
              tokenSeenByJob.set(execution.cancellation());
              running.countDown();
              assertThat(finish.await(2, TimeUnit.SECONDS)).isTrue();
            });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(gate.cancel("inv-1")).isEqualTo(RUNNING_SIGNALLED);
        assertThat(gate.ownsSlot("inv-1")).isTrue();
        assertThat(tokenSeenByJob.get().requested()).isTrue();
        assertThat(hooksFor(journal, "inv-1")).doesNotContain(RELEASE);

        finish.countDown();
        journal.await(RELEASE, "inv-1", Duration.ofSeconds(2));
        assertThat(gate.ownsSlot("inv-1")).isFalse();
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  void handlerExceptionEndsAndReleasesBeforeSlotReuse() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    CountDownLatch secondRan = new CountDownLatch(1);
    try (FeasibilityApplicationGate gate = new FeasibilityApplicationGate("probe", 1, 1, journal)) {
      assertThat(
              gate.submit(
                  "inv-1",
                  execution -> {
                    throw new IllegalStateException("probe");
                  }))
          .isEqualTo(ASSIGNED);
      journal.await(RELEASE, "inv-1", Duration.ofSeconds(2));
      assertThat(hooksFor(journal, "inv-1")).containsSubsequence(ACQUIRE, EXECUTION_END, RELEASE);

      assertThat(gate.submit("inv-2", execution -> secondRan.countDown())).isEqualTo(ASSIGNED);
      assertThat(secondRan.await(2, TimeUnit.SECONDS)).isTrue();
      journal.await(RELEASE, "inv-2", Duration.ofSeconds(2));
    }
  }

  private static java.util.List<FeasibilityJournal.Hook> hooksFor(
      FeasibilityJournal journal, String invocationId) {
    return journal.entriesFor(invocationId).stream().map(FeasibilityJournal.Entry::hook).toList();
  }
}
