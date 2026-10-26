package dev.spanlease.testbed;

import dev.spanlease.instrumentation.GateObserver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded independent physical journal. Job IDs, ordinals and epochs do not use detector facts. The
 * same callbacks run in bare mode. Overflow makes the journal unscorable, never negative.
 */
public final class TruthRecorder {
  private final Clock clock;
  private final int capacity;
  private final long clockErrorMs;
  private final String epoch = UUID.randomUUID().toString();
  private final AtomicLong jobs = new AtomicLong();
  private final List<Entry> entries = new ArrayList<>();
  private final Map<String, Long> ordinals = new HashMap<>();
  private long dropped;

  public TruthRecorder(Clock clock, int capacity) {
    this(clock, capacity, 50);
  }

  public TruthRecorder(Clock clock, int capacity, long clockErrorMs) {
    if (clockErrorMs < 0) throw new IllegalArgumentException("negative clock error");
    this.clockErrorMs = clockErrorMs;
    if (capacity <= 0) throw new IllegalArgumentException("positive truth capacity required");
    this.clock = clock;
    this.capacity = capacity;
  }

  public Job job(String service, String requestId, int remaining) {
    return new Job(epoch + ":job-" + jobs.incrementAndGet(), service, requestId, remaining);
  }

  public GateObserver gate(String service) {
    return (transition, job, slot) -> record(service, (Job) job, transition.name(), slot);
  }

  public void record(Job job, String transition) {
    record(job.service(), job, transition, -1);
  }

  private synchronized void record(String service, Job job, String transition, int slot) {
    long ordinal = ordinals.merge(service, 1L, Long::sum);
    if (entries.size() >= capacity) {
      dropped++;
      return;
    }
    long wall = clock.millis();
    entries.add(
        new Entry(
            epoch,
            service,
            ordinal,
            System.nanoTime(),
            wall,
            Math.max(0, wall - clockErrorMs),
            wall + clockErrorMs,
            job,
            transition,
            slot));
    notifyAll();
  }

  public synchronized List<Entry> entries() {
    return List.copyOf(entries);
  }

  public synchronized boolean scorable() {
    return dropped == 0;
  }

  public synchronized long dropped() {
    return dropped;
  }

  /** Bounded notification await, used to stage roots before opening further RPC dependencies. */
  public synchronized boolean await(
      java.util.function.Predicate<List<Entry>> predicate, java.time.Duration timeout)
      throws InterruptedException {
    long until = System.nanoTime() + timeout.toNanos();
    while (!predicate.test(List.copyOf(entries))) {
      long left = until - System.nanoTime();
      if (left <= 0) return false;
      java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return true;
  }

  /**
   * Retry-free workload identity is request ID plus remaining-hop count, independent of telemetry.
   */
  public record Job(String id, String service, String requestId, int remaining) {}

  public record Entry(
      String epoch,
      String service,
      long ordinal,
      long monotonicNs,
      long wallMs,
      long earliestReferenceMs,
      long latestReferenceMs,
      Job job,
      String transition,
      int slot) {}
}
