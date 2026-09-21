package dev.spanlease.testbed.feasibility;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class FeasibilityJournal {
  enum Hook {
    BLOCK_BEGIN,
    SENT,
    ARRIVAL,
    INVALID_ARRIVAL,
    READY,
    QUEUED,
    ACQUIRE,
    APPLICATION_SPAN_START,
    APPLICATION_SPAN_END,
    RESPONSE_RESERVED,
    RESPONSE_OBSERVED,
    BLOCK_END,
    CANCEL_REQUEST,
    REJECTED,
    EXECUTION_END,
    RELEASE
  }

  record Entry(
      long ordinal,
      Hook hook,
      String invocationId,
      String executionId,
      String slotId,
      String reference,
      String outcome,
      String threadName) {}

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition appended = lock.newCondition();
  private final List<Entry> entries = new ArrayList<>();
  private long nextOrdinal = 1;

  Entry append(
      Hook hook,
      String invocationId,
      String executionId,
      String slotId,
      String reference,
      String outcome) {
    Objects.requireNonNull(hook, "hook");
    lock.lock();
    try {
      Entry entry =
          new Entry(
              nextOrdinal++,
              hook,
              Objects.requireNonNull(invocationId, "invocationId"),
              Objects.requireNonNull(executionId, "executionId"),
              Objects.requireNonNull(slotId, "slotId"),
              Objects.requireNonNull(reference, "reference"),
              Objects.requireNonNull(outcome, "outcome"),
              Thread.currentThread().getName());
      entries.add(entry);
      appended.signalAll();
      return entry;
    } finally {
      lock.unlock();
    }
  }

  Entry await(Hook hook, String invocationId, Duration timeout)
      throws InterruptedException, TimeoutException {
    Objects.requireNonNull(hook, "hook");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(timeout, "timeout");
    long remainingNanos = timeout.toNanos();
    lock.lockInterruptibly();
    try {
      while (true) {
        for (Entry entry : entries) {
          if (entry.hook() == hook && entry.invocationId().equals(invocationId)) {
            return entry;
          }
        }
        if (remainingNanos <= 0) {
          throw new TimeoutException("Timed out awaiting " + hook + " for " + invocationId);
        }
        remainingNanos = appended.awaitNanos(remainingNanos);
      }
    } finally {
      lock.unlock();
    }
  }

  List<Entry> entriesFor(String invocationId) {
    Objects.requireNonNull(invocationId, "invocationId");
    lock.lock();
    try {
      return entries.stream().filter(entry -> entry.invocationId().equals(invocationId)).toList();
    } finally {
      lock.unlock();
    }
  }

  List<Entry> snapshot() {
    lock.lock();
    try {
      return List.copyOf(entries);
    } finally {
      lock.unlock();
    }
  }
}
