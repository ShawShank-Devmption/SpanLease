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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SlotTrackingExecutorTest {
  @Test
  @Timeout(10)
  void acquirePrecedesRunAndExitPrecedesReleaseForQueuedAndExceptionalJobs() throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch firstMayExit = new CountDownLatch(1);
    CountDownLatch secondExited = new CountDownLatch(1);

    try (SlotTrackingExecutor gate = gate(events::add)) {
      assertThat(
              gate.submit(
                  "inv-1",
                  () -> {
                    assertThat(lastType(events)).isEqualTo(EventType.RESOURCE_ACQUIRE);
                    firstStarted.countDown();
                    await(firstMayExit);
                  }))
          .isEqualTo(SlotTrackingExecutor.Admission.ACQUIRED);
      assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(
              gate.submit(
                  "inv-2",
                  () -> {
                    secondExited.countDown();
                    throw new IllegalStateException("expected test failure");
                  }))
          .isEqualTo(SlotTrackingExecutor.Admission.QUEUED);
      assertThat(gate.snapshot().waitingCount()).isEqualTo(1);

      firstMayExit.countDown();
      assertThat(secondExited.await(5, TimeUnit.SECONDS)).isTrue();
      awaitEventCount(events, 8);

      assertThat(events)
          .extracting(ObservationEvent::eventType)
          .containsExactly(
              EventType.RESOURCE_INIT,
              EventType.RESOURCE_ACQUIRE,
              EventType.RESOURCE_WAIT_BEGIN,
              EventType.EXECUTION_END,
              EventType.RESOURCE_RELEASE,
              EventType.RESOURCE_ACQUIRE,
              EventType.EXECUTION_END,
              EventType.RESOURCE_RELEASE);
      assertThat(((EventPayload.ExecutionEnd) events.get(6).payload()).outcome())
          .isEqualTo(EventPayload.Outcome.ERROR);
      assertThat(gate.snapshot().ownerByUnit().values()).containsOnlyNulls();
    }
  }

  @Test
  @Timeout(10)
  void overflowRejectsWithoutAcquiringAndFailedOfferLeavesCheckpointGap() throws Exception {
    List<ObservationEvent> accepted = new CopyOnWriteArrayList<>();
    AtomicLong offers = new AtomicLong();
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayExit = new CountDownLatch(1);
    EventSink sink =
        event -> {
          if (offers.incrementAndGet() == 3) {
            return false;
          }
          accepted.add(event);
          return true;
        };

    InstanceRecorder recorder = recorder(sink);
    try (SlotTrackingExecutor gate = gate(recorder, 1)) {
      assertThat(
              gate.submit(
                  "inv-1",
                  () -> {
                    running.countDown();
                    await(mayExit);
                  }))
          .isEqualTo(SlotTrackingExecutor.Admission.ACQUIRED);
      assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(gate.submit("inv-2", () -> {})).isEqualTo(SlotTrackingExecutor.Admission.QUEUED);
      assertThat(gate.submit("inv-3", () -> {})).isEqualTo(SlotTrackingExecutor.Admission.REJECTED);

      recorder.checkpoint();
      ObservationEvent checkpoint = accepted.get(accepted.size() - 1);
      assertThat(checkpoint.eventType()).isEqualTo(EventType.INSTANCE_CHECKPOINT);
      assertThat(checkpoint.localSeq()).isEqualTo(5);
      assertThat(((EventPayload.InstanceCheckpoint) checkpoint.payload()).throughSeq())
          .isEqualTo(4);
      assertThat(((EventPayload.InstanceCheckpoint) checkpoint.payload()).droppedTotal())
          .isEqualTo(1);
      assertThat(accepted).extracting(ObservationEvent::localSeq).doesNotContain(3L);
      assertThat(gate.snapshot().ownerByUnit()).containsValue("instance:exec-1");

      mayExit.countDown();
    }
  }

  private static SlotTrackingExecutor gate(EventSink sink) {
    return gate(recorder(sink), 4);
  }

  private static InstanceRecorder recorder(EventSink sink) {
    AtomicLong monotonic = new AtomicLong();
    return new InstanceRecorder(
        "instance",
        sink,
        Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
        monotonic::incrementAndGet);
  }

  private static SlotTrackingExecutor gate(InstanceRecorder recorder, int waitingCapacity) {
    return new SlotTrackingExecutor(
        recorder,
        "main",
        1,
        waitingCapacity,
        runnable -> {
          Thread thread = new Thread(runnable, "slot-test-worker");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static EventType lastType(List<ObservationEvent> events) {
    return events.get(events.size() - 1).eventType();
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

  private static void awaitEventCount(List<ObservationEvent> events, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (events.size() < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(events).hasSize(expected);
  }
}
