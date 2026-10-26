package dev.spanlease.testbed;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.instrumentation.EventSink;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finite deterministic delivery-fault proxy, independent of physical truth. Scheduling is bounded
 * and never waits for queue capacity; identical instance/sequence/seed inputs choose identical
 * faults. A dropped transport observation still consumes its source sequence. This is not a process
 * crash.
 */
public final class ObservationFaults implements AutoCloseable {
  private final List<Rule> rules;
  private final long seed;
  private final ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor();
  private final Semaphore pending = new Semaphore(8192);
  private final AtomicLong lost = new AtomicLong();

  public ObservationFaults(List<Rule> rules, long seed) {
    this.rules = List.copyOf(rules);
    this.seed = seed;
  }

  public EventSink wrap(EventSink destination) {
    return original -> {
      ObservationEvent event = original;
      int copies = 1;
      long delayMs = 0;
      for (Rule rule : rules) {
        if (!rule.matches(event, seed)) continue;
        switch (rule.action()) {
          case DROP -> {
            lost.incrementAndGet();
            return true;
          }
          case DUPLICATE -> copies++;
          case DELAY -> delayMs = Math.max(delayMs, rule.amount());
          case SKEW -> event = skew(event, rule.amount());
        }
      }
      if (delayMs == 0) {
        boolean accepted = true;
        for (int i = 0; i < copies; i++) accepted &= destination.tryPublish(event);
        return accepted;
      }
      if (!pending.tryAcquire()) {
        lost.incrementAndGet();
        return true;
      }
      final ObservationEvent delivered = event;
      final int count = copies;
      delayed.schedule(
          () -> {
            try {
              for (int i = 0; i < count; i++)
                if (!destination.tryPublish(delivered)) lost.incrementAndGet();
            } finally {
              pending.release();
            }
          },
          delayMs,
          TimeUnit.MILLISECONDS);
      return true;
    };
  }

  private static ObservationEvent skew(ObservationEvent event, long offset) {
    EventPayload payload = event.payload();
    if (payload instanceof EventPayload.ExecutionLease lease)
      payload =
          new EventPayload.ExecutionLease(
              lease.executionId(),
              lease.invocationId(),
              lease.startRef(),
              lease.blockRef(),
              lease.observedAgeMs(),
              Math.addExact(lease.leaseExpiryMs(), offset));
    return new ObservationEvent(
        event.schemaVersion(),
        event.instanceId(),
        event.localSeq(),
        event.causalParent(),
        Math.addExact(event.wallTimeMs(), offset),
        event.monotonicTimeNs(),
        event.traceId(),
        event.spanId(),
        payload);
  }

  public long lost() {
    return lost.get();
  }

  @Override
  public void close() throws InterruptedException {
    delayed.shutdown();
    if (!delayed.awaitTermination(5, TimeUnit.SECONDS)) delayed.shutdownNow();
  }

  public enum Action {
    DROP,
    DUPLICATE,
    DELAY,
    SKEW
  }

  /** Empty service/type selectors match all; period=1 matches every selected sequence. */
  public record Rule(
      String service,
      String eventType,
      long fromSequence,
      long throughSequence,
      Action action,
      long amount,
      int period) {
    public Rule {
      if (fromSequence < 1
          || throughSequence < fromSequence
          || period < 1
          || (action == Action.DELAY && amount < 0))
        throw new IllegalArgumentException("invalid fault bounds");
    }

    boolean matches(ObservationEvent event, long seed) {
      return (service.isEmpty()
              || event.instanceId().startsWith(service + "-0@")
              || event.instanceId().equals(service))
          && (eventType.isEmpty() || event.eventType().wireName().equals(eventType))
          && event.localSeq() >= fromSequence
          && event.localSeq() <= throughSequence
          && Math.floorMod(event.localSeq() + seed, period) == 0;
    }
  }
}
