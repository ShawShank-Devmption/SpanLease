package dev.spanlease.instrumentation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled lease renewal and checkpoint emission for one instance (B-SCAN).
 *
 * <p>Checkpoints continue while the instance is idle, so a lost suffix becomes visible to the
 * analyzer instead of looking like quiescence. Leases come from each gate's serialized state (R8).
 * Scheduling delay is not bounded here; a delayed tick only delays evidence, which the analyzer
 * treats as missing rather than as progress.
 */
public final class PeriodicScanner implements AutoCloseable {
  private final ScheduledExecutorService scheduler;

  public PeriodicScanner(
      InstanceRecorder recorder,
      List<SlotTrackingExecutor> gates,
      LeasePolicy leases,
      Duration scannerTick,
      Duration checkpointInterval,
      String threadName) {
    Objects.requireNonNull(recorder, "recorder");
    List<SlotTrackingExecutor> scanned = List.copyOf(gates);
    Objects.requireNonNull(leases, "leases");
    long tickMs = positiveMillis(scannerTick, "scannerTick");
    long checkpointMs = positiveMillis(checkpointInterval, "checkpointInterval");
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, threadName);
              thread.setDaemon(true);
              return thread;
            });
    if (!scanned.isEmpty()) {
      scheduler.scheduleAtFixedRate(
          () -> scanned.forEach(gate -> gate.renewLeases(leases)),
          tickMs,
          tickMs,
          TimeUnit.MILLISECONDS);
    }
    scheduler.scheduleAtFixedRate(recorder::checkpoint, 0, checkpointMs, TimeUnit.MILLISECONDS);
  }

  private static long positiveMillis(Duration duration, String field) {
    if (duration == null || duration.toMillis() <= 0) {
      throw new IllegalArgumentException(field + " must be at least one millisecond");
    }
    return duration.toMillis();
  }

  @Override
  public void close() throws InterruptedException {
    scheduler.shutdownNow();
    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("scanner did not terminate");
    }
  }
}
