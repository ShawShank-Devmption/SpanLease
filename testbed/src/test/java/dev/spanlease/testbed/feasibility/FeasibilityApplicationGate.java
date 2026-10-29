package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ACQUIRE;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.CANCEL_REQUEST;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.EXECUTION_END;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.QUEUED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.REJECTED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RELEASE;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class FeasibilityApplicationGate implements AutoCloseable {
  enum Admission {
    ASSIGNED,
    QUEUED,
    REJECTED
  }

  enum CancelResult {
    QUEUED_REMOVED,
    RUNNING_SIGNALLED,
    NOT_FOUND
  }

  enum JobState {
    QUEUED,
    ASSIGNED,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLED,
    ENDED,
    RELEASED,
    REJECTED
  }

  @FunctionalInterface
  interface Job {
    void run(Execution execution) throws Exception;
  }

  record Execution(String executionId, String slotId, CancellationToken cancellation) {}

  static final class CancellationToken {
    private final AtomicBoolean requested = new AtomicBoolean();

    boolean requested() {
      return requested.get();
    }

    private void request() {
      requested.set(true);
    }
  }

  private final int waitingCapacity;
  private final FeasibilityJournal journal;
  private final ReentrantLock lock = new ReentrantLock();
  private final ArrayDeque<Slot> freeSlots = new ArrayDeque<>();
  private final ArrayDeque<TrackedJob> waiting = new ArrayDeque<>();
  private final Map<String, TrackedJob> jobs = new HashMap<>();
  private long nextExecution = 1;
  private boolean closed;

  FeasibilityApplicationGate(
      String gateName, int slotCount, int waitingCapacity, FeasibilityJournal journal) {
    if (gateName == null || gateName.isBlank()) {
      throw new IllegalArgumentException("gateName must not be blank");
    }
    if (slotCount <= 0) {
      throw new IllegalArgumentException("slotCount must be positive");
    }
    if (waitingCapacity <= 0) {
      throw new IllegalArgumentException("waitingCapacity must be positive");
    }
    this.waitingCapacity = waitingCapacity;
    this.journal = Objects.requireNonNull(journal, "journal");
    for (int index = 0; index < slotCount; index++) {
      String slotId = gateName + "-slot-" + index;
      ExecutorService executor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("bfeas-app-" + slotId);
                return thread;
              });
      freeSlots.addLast(new Slot(slotId, executor));
    }
  }

  Admission submit(String invocationId, Job job) {
    requireInvocationId(invocationId);
    Objects.requireNonNull(job, "job");
    Assignment assignment = null;
    Admission admission;
    lock.lock();
    try {
      ensureOpen();
      if (jobs.containsKey(invocationId)) {
        throw new IllegalArgumentException("duplicate invocation ID: " + invocationId);
      }
      TrackedJob tracked = new TrackedJob(invocationId, job);
      jobs.put(invocationId, tracked);
      if (!freeSlots.isEmpty()) {
        assignment = assign(tracked, freeSlots.removeFirst());
        admission = Admission.ASSIGNED;
      } else if (waiting.size() < waitingCapacity) {
        tracked.state = JobState.QUEUED;
        waiting.addLast(tracked);
        journal.append(QUEUED, invocationId, "", "", "", "");
        admission = Admission.QUEUED;
      } else {
        tracked.state = JobState.REJECTED;
        journal.append(REJECTED, invocationId, "", "", "", "queue-full");
        admission = Admission.REJECTED;
      }
    } finally {
      lock.unlock();
    }
    if (assignment != null) {
      dispatch(assignment);
    }
    return admission;
  }

  CancelResult cancel(String invocationId) {
    requireInvocationId(invocationId);
    lock.lock();
    try {
      TrackedJob tracked = jobs.get(invocationId);
      if (tracked == null) {
        return CancelResult.NOT_FOUND;
      }
      if (tracked.state == JobState.QUEUED) {
        waiting.remove(tracked);
        tracked.cancellation.request();
        tracked.state = JobState.CANCELLED;
        journal.append(CANCEL_REQUEST, invocationId, "", "", "", "queued-removed");
        return CancelResult.QUEUED_REMOVED;
      }
      if (tracked.state == JobState.ASSIGNED || tracked.state == JobState.RUNNING) {
        tracked.cancellation.request();
        tracked.state = JobState.CANCEL_REQUESTED;
        journal.append(
            CANCEL_REQUEST,
            invocationId,
            tracked.executionId,
            tracked.slot.id(),
            "",
            "running-signalled");
        return CancelResult.RUNNING_SIGNALLED;
      }
      if (tracked.state == JobState.CANCEL_REQUESTED) {
        return CancelResult.RUNNING_SIGNALLED;
      }
      return CancelResult.NOT_FOUND;
    } finally {
      lock.unlock();
    }
  }

  JobState state(String invocationId) {
    lock.lock();
    try {
      TrackedJob tracked = jobs.get(invocationId);
      return tracked == null ? null : tracked.state;
    } finally {
      lock.unlock();
    }
  }

  boolean ownsSlot(String invocationId) {
    lock.lock();
    try {
      TrackedJob tracked = jobs.get(invocationId);
      return tracked != null && tracked.slot != null && tracked.state != JobState.RELEASED;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws InterruptedException {
    Slot[] slots;
    lock.lock();
    try {
      closed = true;
      slots =
          jobs.values().stream()
              .map(tracked -> tracked.slot)
              .filter(Objects::nonNull)
              .toArray(Slot[]::new);
      for (TrackedJob tracked : waiting) {
        tracked.cancellation.request();
        tracked.state = JobState.CANCELLED;
      }
      waiting.clear();
    } finally {
      lock.unlock();
    }

    java.util.LinkedHashSet<ExecutorService> executors = new java.util.LinkedHashSet<>();
    for (Slot slot : freeSlotsSnapshot()) {
      executors.add(slot.executor());
    }
    for (Slot slot : slots) {
      executors.add(slot.executor());
    }
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
    for (ExecutorService executor : executors) {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("application slot executor did not terminate");
      }
    }
  }

  private Assignment assign(TrackedJob tracked, Slot slot) {
    tracked.executionId = "bfeas-execution-" + nextExecution++;
    tracked.slot = slot;
    tracked.state = JobState.ASSIGNED;
    journal.append(ACQUIRE, tracked.invocationId, tracked.executionId, slot.id(), "", "");
    return new Assignment(tracked, slot);
  }

  private void dispatch(Assignment assignment) {
    assignment.slot().executor().execute(() -> runAssigned(assignment));
  }

  private void runAssigned(Assignment assignment) {
    TrackedJob tracked = assignment.tracked();
    lock.lock();
    try {
      if (tracked.state == JobState.ASSIGNED) {
        tracked.state = JobState.RUNNING;
      }
    } finally {
      lock.unlock();
    }

    String outcome = "completed";
    try {
      tracked.job.run(
          new Execution(tracked.executionId, assignment.slot().id(), tracked.cancellation));
    } catch (Exception failure) {
      outcome = "failure:" + failure.getClass().getSimpleName();
    } finally {
      Assignment next = finish(tracked, outcome);
      if (next != null) {
        dispatch(next);
      }
    }
  }

  private Assignment finish(TrackedJob tracked, String outcome) {
    lock.lock();
    try {
      tracked.state = JobState.ENDED;
      journal.append(
          EXECUTION_END, tracked.invocationId, tracked.executionId, tracked.slot.id(), "", outcome);
      Slot released = tracked.slot;
      journal.append(
          RELEASE, tracked.invocationId, tracked.executionId, released.id(), "", outcome);
      tracked.state = JobState.RELEASED;
      if (waiting.isEmpty()) {
        freeSlots.addLast(released);
        return null;
      }
      return assign(waiting.removeFirst(), released);
    } finally {
      lock.unlock();
    }
  }

  private Slot[] freeSlotsSnapshot() {
    lock.lock();
    try {
      return freeSlots.toArray(Slot[]::new);
    } finally {
      lock.unlock();
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("gate is closed");
    }
  }

  private static void requireInvocationId(String invocationId) {
    if (invocationId == null || invocationId.isBlank()) {
      throw new IllegalArgumentException("invocationId must not be blank");
    }
  }

  private record Slot(String id, ExecutorService executor) {}

  private record Assignment(TrackedJob tracked, Slot slot) {}

  private static final class TrackedJob {
    private final String invocationId;
    private final Job job;
    private final CancellationToken cancellation = new CancellationToken();
    private JobState state;
    private String executionId = "";
    private Slot slot;

    private TrackedJob(String invocationId, Job job) {
      this.invocationId = invocationId;
      this.job = job;
    }
  }
}
