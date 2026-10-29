package dev.spanlease.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class RpcLifecycleTest {
  @Test
  @Timeout(10)
  void blockAndSendPrecedeTransportAndResponseParentPrecedesSingleBlockEnd() throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    AtomicReference<SlotTrackingExecutor.BlockHandle> handle = new AtomicReference<>();
    CountDownLatch sentCommitted = new CountDownLatch(1);
    CountDownLatch mayComplete = new CountDownLatch(1);

    try (SlotTrackingExecutor gate = gate(events)) {
      gate.submit(
          "inbound",
          execution -> {
            SlotTrackingExecutor.BlockHandle block =
                gate.openBlock(execution, "downstream", "svcB");
            handle.set(block);
            assertThat(events)
                .extracting(ObservationEvent::eventType)
                .endsWith(EventType.RPC_BLOCK_BEGIN, EventType.RPC_INVOCATION_SENT);
            sentCommitted.countDown();
            await(mayComplete);
            block.completeResponse("B#9", EventPayload.Outcome.OK);
            assertThatThrownBy(() -> block.completeLocal(EventPayload.Outcome.ERROR))
                .isInstanceOf(IllegalStateException.class);
            return EventPayload.Outcome.OK;
          });

      assertThat(sentCommitted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(handle.get().sentReference()).isEqualTo("A#4");
      mayComplete.countDown();
      awaitSize(events, 7);

      ObservationEvent blockEnd = events.get(4);
      assertThat(blockEnd.eventType()).isEqualTo(EventType.RPC_BLOCK_END);
      assertThat(blockEnd.causalParent()).isEqualTo("B#9");
      assertThat(events)
          .extracting(ObservationEvent::eventType)
          .endsWith(EventType.RPC_BLOCK_END, EventType.EXECUTION_END, EventType.RESOURCE_RELEASE);
    }
  }

  @Test
  @Timeout(10)
  void cancellationDoesNotReleaseBeforeActualTaskExit() throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch cancellationRecorded = new CountDownLatch(1);
    CountDownLatch mayExit = new CountDownLatch(1);

    try (SlotTrackingExecutor gate = gate(events)) {
      gate.submit(
          "inbound",
          execution -> {
            gate.requestCancellation(
                "inbound", EventPayload.CancelSource.CLIENT, EventPayload.CancelReason.REQUESTED);
            cancellationRecorded.countDown();
            await(mayExit);
            return EventPayload.Outcome.CANCELLED;
          });

      assertThat(cancellationRecorded.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(gate.snapshot().ownerByUnit()).containsValue("A:exec-1");
      assertThat(events)
          .extracting(ObservationEvent::eventType)
          .doesNotContain(EventType.RESOURCE_RELEASE);
      mayExit.countDown();
      awaitSize(events, 5);
      assertThat(events)
          .extracting(ObservationEvent::eventType)
          .endsWith(
              EventType.EXECUTION_CANCEL, EventType.EXECUTION_END, EventType.RESOURCE_RELEASE);
    }
  }

  private static SlotTrackingExecutor gate(List<ObservationEvent> events) {
    AtomicLong monotonic = new AtomicLong();
    return new SlotTrackingExecutor(
        new InstanceRecorder(
            "A",
            events::add,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            monotonic::incrementAndGet),
        "main",
        1,
        4,
        runnable -> new Thread(runnable, "rpc-lifecycle-worker"));
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

  private static void awaitSize(List<ObservationEvent> events, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (events.size() < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(events).hasSize(expected);
  }
}
