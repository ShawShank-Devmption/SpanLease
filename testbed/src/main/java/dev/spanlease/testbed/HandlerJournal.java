package dev.spanlease.testbed;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Application-boundary journal written by the Chain handler itself. It never reads SpanLease
 * events, sequences, leases or analyzer output, and the evaluator alone joins it to reports.
 *
 * <p>This compact journal supplies the human demo's handler intervals in both modes. The separate
 * {@link TruthRecorder} supplies full physical gate decisions and independent job/slot identities;
 * evaluator-only detector associations live in {@link EvaluationMappings}.
 */
public final class HandlerJournal {
  private final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();
  private final Clock clock;

  public HandlerJournal(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  void record(String service, String requestId, int remainingHops, Boundary boundary) {
    entries.add(new Entry(clock.millis(), service, requestId, remainingHops, boundary));
  }

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  public enum Boundary {
    RUN,
    CALL_ENTER,
    CALL_RETURN,
    HANDLER_RETURN
  }

  /** One boundary; {@code remainingHops} counts this hop, so a leaf has 1. */
  public record Entry(
      long wallMs, String service, String requestId, int remainingHops, Boundary boundary) {}
}
