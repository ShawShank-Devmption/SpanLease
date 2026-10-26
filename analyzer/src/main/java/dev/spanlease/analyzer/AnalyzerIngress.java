package dev.spanlease.analyzer;

import dev.spanlease.common.ObservationEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounded hand-off from receiver threads to the single analyzer-core thread, measured in records. A
 * batch is admitted whole or not at all, so the core never observes a partial export request (R11).
 * Capacity is returned only when the core removes a batch.
 */
final class AnalyzerIngress {
  private final Semaphore capacity;
  private final LinkedBlockingQueue<List<ObservationEnvelope>> batches =
      new LinkedBlockingQueue<>();

  AnalyzerIngress(int capacityRecords) {
    if (capacityRecords <= 0) {
      throw new IllegalArgumentException("ingress capacity must be positive");
    }
    this.capacity = new Semaphore(capacityRecords);
  }

  /** Producer side; never blocks. Returns false when the whole batch does not fit. */
  boolean tryAdmit(List<ObservationEnvelope> batch) {
    if (batch.isEmpty()) {
      return true;
    }
    if (!capacity.tryAcquire(batch.size())) {
      return false;
    }
    batches.add(List.copyOf(batch));
    return true;
  }

  /** Core side; waits up to the timeout for one batch, or returns null. */
  List<ObservationEnvelope> poll(long timeoutMs) throws InterruptedException {
    List<ObservationEnvelope> batch = batches.poll(timeoutMs, TimeUnit.MILLISECONDS);
    if (batch != null) {
      capacity.release(batch.size());
    }
    return batch;
  }

  /** Core side; removes every batch admitted so far without waiting. */
  List<List<ObservationEnvelope>> drain() {
    List<List<ObservationEnvelope>> drained = new ArrayList<>();
    batches.drainTo(drained);
    capacity.release(drained.stream().mapToInt(List::size).sum());
    return drained;
  }
}
