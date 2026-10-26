package dev.spanlease.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.EventType;
import dev.spanlease.common.ObservationEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GateCancellationAndLeaseTest {
  private static final LeasePolicy LEASES = new LeasePolicy(500, 500, 3);

  /** R3: a queued cancel wins atomically, so the job never acquires; assigned jobs keep slots. */
  @Test
  @Timeout(10)
  void queuedCancellationRemovesJobAndRunningCancellationIsRecordedOnceWithoutRelease()
      throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayExit = new CountDownLatch(1);
    try (SlotTrackingExecutor gate = gate(events, new AtomicLong())) {
      gate.submit(
          "inv-1",
          execution -> {
            running.countDown();
            await(mayExit);
            return EventPayload.Outcome.CANCELLED;
          });
      assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(gate.submit("inv-2", () -> {})).isEqualTo(SlotTrackingExecutor.Admission.QUEUED);

      gate.requestCancellation(
          "inv-2", EventPayload.CancelSource.CLIENT, EventPayload.CancelReason.REQUESTED);
      gate.requestCancellation(
          "inv-1", EventPayload.CancelSource.DEADLINE, EventPayload.CancelReason.DEADLINE);
      gate.requestCancellation(
          "inv-1", EventPayload.CancelSource.DEADLINE, EventPayload.CancelReason.DEADLINE);

      assertThat(gate.snapshot().waitingCount()).isZero();
      assertThat(gate.snapshot().ownerByUnit()).containsValue("A:exec-1");
      mayExit.countDown();
      awaitType(events, EventType.RESOURCE_RELEASE);
    }

    assertThat(events)
        .extracting(ObservationEvent::eventType)
        .containsExactly(
            EventType.RESOURCE_INIT,
            EventType.RESOURCE_ACQUIRE,
            EventType.RESOURCE_WAIT_BEGIN,
            EventType.EXECUTION_CANCEL,
            EventType.EXECUTION_CANCEL,
            EventType.EXECUTION_END,
            EventType.RESOURCE_RELEASE);
    assertThat((EventPayload.ExecutionCancel) events.get(3).payload())
        .isEqualTo(
            new EventPayload.ExecutionCancel(
                "",
                "inv-2",
                EventPayload.CancelSource.CLIENT,
                EventPayload.CancelReason.REQUESTED));
    assertThat(((EventPayload.ExecutionCancel) events.get(4).payload()).executionId())
        .isEqualTo("A:exec-1");
    assertThat(((EventPayload.ExecutionEnd) events.get(5).payload()).outcome())
        .isEqualTo(EventPayload.Outcome.CANCELLED);
  }

  @Test
  @Timeout(10)
  void responseNamesCurrentExecutionOrIsEmptyBeforeAdmission() throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    AtomicReference<String> inside = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    try (SlotTrackingExecutor gate = gate(events, new AtomicLong())) {
      String rejected = gate.responseSent("never-admitted", EventPayload.Outcome.ERROR);
      gate.submit(
          "inv-1",
          execution -> {
            inside.set(gate.responseSent("inv-1", EventPayload.Outcome.OK));
            done.countDown();
            return EventPayload.Outcome.OK;
          });
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(rejected).isEqualTo("A#2");
      assertThat(payload(events, "A#2"))
          .isEqualTo(
              new EventPayload.RpcResponseSent("never-admitted", "", EventPayload.Outcome.ERROR));
      assertThat(((EventPayload.RpcResponseSent) payload(events, inside.get())).executionId())
          .isEqualTo("A:exec-1");
    }
  }

  /** R8: leases start at the age threshold, respect renewal and never name a closed block. */
  @Test
  @Timeout(10)
  void leasesFollowAgeAndRenewalAndStopAtBlockClosure() throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    AtomicLong nanos = new AtomicLong(1_000_000_000L);
    AtomicReference<SlotTrackingExecutor.BlockHandle> handle = new AtomicReference<>();
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch mayExit = new CountDownLatch(1);
    try (SlotTrackingExecutor gate = gate(events, nanos)) {
      gate.submit(
          "inv-1",
          execution -> {
            handle.set(gate.openBlock(execution, "down-1", "svcB"));
            blocked.countDown();
            await(mayExit);
            return EventPayload.Outcome.OK;
          });
      assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();

      advance(nanos, 499);
      gate.renewLeases(LEASES);
      assertThat(leases(events)).isEmpty();

      advance(nanos, 1);
      gate.renewLeases(LEASES);
      advance(nanos, 499);
      gate.renewLeases(LEASES);
      assertThat(leases(events))
          .containsExactly(
              new EventPayload.ExecutionLease("A:exec-1", "down-1", "A#2", "A#3", 500, 2_500));

      advance(nanos, 1);
      gate.renewLeases(LEASES);
      assertThat(leases(events)).hasSize(2);

      handle.get().completeLocal(EventPayload.Outcome.DEADLINE);
      advance(nanos, 5_000);
      gate.renewLeases(LEASES);
      assertThat(leases(events)).hasSize(2);
      mayExit.countDown();
    }
  }

  private static SlotTrackingExecutor gate(List<ObservationEvent> events, AtomicLong nanos) {
    return new SlotTrackingExecutor(
        new InstanceRecorder(
            "A", events::add, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC), nanos::get),
        "main",
        1,
        4,
        runnable -> {
          Thread thread = new Thread(runnable, "gate-test-worker");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static List<EventPayload.ExecutionLease> leases(List<ObservationEvent> events) {
    return events.stream()
        .map(ObservationEvent::payload)
        .filter(EventPayload.ExecutionLease.class::isInstance)
        .map(EventPayload.ExecutionLease.class::cast)
        .toList();
  }

  private static EventPayload payload(List<ObservationEvent> events, String reference) {
    return events.stream()
        .filter(event -> event.eventReference().equals(reference))
        .findFirst()
        .orElseThrow()
        .payload();
  }

  private static void advance(AtomicLong nanos, long millis) {
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test latch timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static void awaitType(List<ObservationEvent> events, EventType type) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (events.stream().noneMatch(event -> event.eventType() == type)
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(events).extracting(ObservationEvent::eventType).contains(type);
  }
}
