package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Sequence allocator and publication path for one process epoch ({@code instance_id}).
 *
 * <p>Every observed transition on the instance — gate decisions, RPC lifecycle records, leases and
 * checkpoints — allocates its {@code local_seq} and attempts publication while holding this
 * recorder's lock, so sequence order is transition order (I1). Publication is one immediate sink
 * offer; a refused or failing offer keeps its allocated sequence and increments {@code
 * dropped_total}, so the next checkpoint exposes the gap instead of hiding it (I4).
 */
public final class InstanceRecorder {
  private final ReentrantLock lock = new ReentrantLock();
  private final String instanceId;
  private final EventSink sink;
  private final Clock wallClock;
  private final LongSupplier monotonicClock;
  private long nextSequence = 1;
  private long droppedTotal;

  public InstanceRecorder(
      String instanceId, EventSink sink, Clock wallClock, LongSupplier monotonicClock) {
    if (instanceId == null || instanceId.isBlank() || instanceId.contains("#")) {
      throw new IllegalArgumentException("instanceId must be nonblank and must not contain '#'");
    }
    this.instanceId = instanceId;
    this.sink = Objects.requireNonNull(sink, "sink");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
  }

  public String instanceId() {
    return instanceId;
  }

  /** Publishes one record that is not part of a gate transition, such as a root caller's send. */
  public ObservationEvent publish(EventPayload payload, String causalParent) {
    lock.lock();
    try {
      return publishLocked(payload, causalParent);
    } finally {
      lock.unlock();
    }
  }

  /** Emits a complete-prefix boundary covering every previously allocated sequence. */
  public void checkpoint() {
    lock.lock();
    try {
      publishLocked(new EventPayload.InstanceCheckpoint(nextSequence - 1, droppedTotal), "");
    } finally {
      lock.unlock();
    }
  }

  public long droppedTotal() {
    lock.lock();
    try {
      return droppedTotal;
    } finally {
      lock.unlock();
    }
  }

  ReentrantLock lock() {
    return lock;
  }

  long wallMillis() {
    return wallClock.millis();
  }

  long monotonicNanos() {
    return monotonicClock.getAsLong();
  }

  long lastSequenceLocked() {
    return nextSequence - 1;
  }

  /** Allocates the next sequence and offers the record; the caller must hold {@link #lock()}. */
  ObservationEvent publishLocked(EventPayload payload, String causalParent) {
    if (!lock.isHeldByCurrentThread()) {
      throw new IllegalStateException("publication requires the instance lock");
    }
    ObservationEvent event =
        new ObservationEvent(
            ObservationEvent.SCHEMA_VERSION,
            instanceId,
            nextSequence++,
            causalParent,
            wallClock.millis(),
            monotonicClock.getAsLong(),
            "",
            "",
            payload);
    try {
      if (!sink.tryPublish(event)) {
        droppedTotal++;
      }
    } catch (RuntimeException ignored) {
      droppedTotal++;
    }
    return event;
  }
}
