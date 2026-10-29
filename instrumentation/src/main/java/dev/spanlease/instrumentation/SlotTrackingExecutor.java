package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity application gate with serialized state and telemetry transitions.
 *
 * <p>The instance recorder's lock owns admission, slot ownership, execution identity, open blocks,
 * local sequence allocation and publication attempts (I1). An acquire is published before
 * application code can run (I2). The worker's finally path publishes execution end before release
 * (I3). Publication is an immediate bounded offer; this class never waits for telemetry capacity or
 * I/O (I4). Queue-versus-assignment and queued-cancel-versus-assignment are single atomic decisions
 * (R1, R3); cancellation of an assigned job is recorded once and never releases its slot.
 */
public final class SlotTrackingExecutor implements AutoCloseable {
  private final InstanceRecorder recorder;
  private final ReentrantLock stateLock;
  private final SequencedMap<String, PendingJob> waiting = new LinkedHashMap<>();
  private final Map<String, Execution> ownerByUnit = new LinkedHashMap<>();
  private final Map<String, Execution> activeByInvocation = new LinkedHashMap<>();
  private final ExecutorService workers;
  private final String resourceId;
  private final List<String> unitIds;
  private final int waitingCapacity;
  private final AtomicBoolean closed = new AtomicBoolean();
  private long nextExecution = 1;

  public SlotTrackingExecutor(
      InstanceRecorder recorder,
      String resourceId,
      int capacity,
      int waitingCapacity,
      ThreadFactory threadFactory) {
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.stateLock = recorder.lock();
    this.resourceId = requireId(resourceId, "resourceId");
    if (capacity <= 0 || waitingCapacity <= 0) {
      throw new IllegalArgumentException("capacity and waitingCapacity must be positive");
    }
    this.waitingCapacity = waitingCapacity;
    this.workers = Executors.newFixedThreadPool(capacity, Objects.requireNonNull(threadFactory));

    List<String> units = new ArrayList<>();
    for (int index = 0; index < capacity; index++) {
      String unitId = recorder.instanceId() + ":" + resourceId + ":slot-" + index;
      units.add(unitId);
      ownerByUnit.put(unitId, null);
    }
    this.unitIds = List.copyOf(units);

    stateLock.lock();
    try {
      recorder.publishLocked(new EventPayload.ResourceInit(resourceId, capacity, unitIds), "");
    } finally {
      stateLock.unlock();
    }
  }

  InstanceRecorder recorder() {
    return recorder;
  }

  public String resourceId() {
    return resourceId;
  }

  public List<String> unitIds() {
    return unitIds;
  }

  /** Submits one decoded unary handler job; rejected jobs never acquire a slot. */
  public Admission submit(String invocationId, Runnable task) {
    Objects.requireNonNull(task, "task");
    return submit(
        invocationId,
        ignored -> {
          task.run();
          return EventPayload.Outcome.OK;
        });
  }

  /**
   * Submits a handler that receives the assigned execution and unit identities.
   *
   * @throws IllegalStateException if the invocation is already queued or executing here; one
   *     invocation identity maps to at most one current job on an instance
   */
  public Admission submit(String invocationId, ExecutionTask task) {
    requireId(invocationId, "invocationId");
    Objects.requireNonNull(task, "task");

    Execution assigned;
    stateLock.lock();
    try {
      if (waiting.containsKey(invocationId) || activeByInvocation.containsKey(invocationId)) {
        throw new IllegalStateException("invocation already admitted: " + invocationId);
      }
      if (closed.get()) {
        return rejectLocked(invocationId);
      }
      String freeUnit = firstFreeUnitLocked();
      if (freeUnit == null) {
        if (waiting.size() >= waitingCapacity) {
          return rejectLocked(invocationId);
        }
        waiting.put(invocationId, new PendingJob(invocationId, task));
        recorder.publishLocked(new EventPayload.ResourceWaitBegin(invocationId, resourceId), "");
        return Admission.QUEUED;
      }
      assigned = assignLocked(freeUnit, new PendingJob(invocationId, task));
    } finally {
      stateLock.unlock();
    }
    schedule(assigned);
    return Admission.ACQUIRED;
  }

  /**
   * Applies a cancellation request to the invocation's current gate state in one atomic decision. A
   * queued job is removed and never acquires (cancel won). An assigned job keeps its slot until
   * actual exit; only the request is recorded, once (assignment won). Invocations that are neither
   * queued nor assigned here are ignored.
   */
  public void requestCancellation(
      String invocationId, EventPayload.CancelSource source, EventPayload.CancelReason reason) {
    requireId(invocationId, "invocationId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(reason, "reason");
    stateLock.lock();
    try {
      if (waiting.remove(invocationId) != null) {
        recorder.publishLocked(
            new EventPayload.ExecutionCancel("", invocationId, source, reason), "");
        return;
      }
      Execution execution = activeByInvocation.get(invocationId);
      if (execution != null && !execution.cancelRecorded) {
        execution.cancelRecorded = true;
        recorder.publishLocked(
            new EventPayload.ExecutionCancel(execution.executionId, invocationId, source, reason),
            "");
      }
    } finally {
      stateLock.unlock();
    }
  }

  /**
   * Commits {@code rpc.response.sent} for an inbound invocation before transport close and returns
   * its event reference for the response trailers (I5). The execution is the invocation's current
   * job, or empty when the response precedes admission (rejection or invalid request).
   */
  public String responseSent(String invocationId, EventPayload.Outcome outcome) {
    requireId(invocationId, "invocationId");
    Objects.requireNonNull(outcome, "outcome");
    stateLock.lock();
    try {
      Execution execution = activeByInvocation.get(invocationId);
      return recorder
          .publishLocked(
              new EventPayload.RpcResponseSent(
                  invocationId, execution == null ? "" : execution.executionId, outcome),
              "")
          .eventReference();
    } finally {
      stateLock.unlock();
    }
  }

  /**
   * Opens one logical downstream blocking interval and commits its send reference before transport
   * dispatch. The caller propagates {@link BlockHandle#sentReference()} in request metadata.
   */
  public BlockHandle openBlock(
      ExecutionContext execution, String downstreamInvocationId, String targetService) {
    Objects.requireNonNull(execution, "execution");
    requireId(downstreamInvocationId, "downstreamInvocationId");
    requireId(targetService, "targetService");
    stateLock.lock();
    try {
      Execution owner = requireActiveExecutionLocked(execution);
      if (owner.block != null) {
        throw new IllegalStateException("execution already has an open downstream block");
      }
      ObservationEvent blockBegin =
          recorder.publishLocked(
              new EventPayload.RpcBlockBegin(owner.executionId, downstreamInvocationId), "");
      ObservationEvent sent =
          recorder.publishLocked(
              new EventPayload.RpcInvocationSent(
                  downstreamInvocationId, owner.executionId, targetService),
              "");
      OpenBlock block =
          new OpenBlock(
              owner,
              downstreamInvocationId,
              blockBegin.eventReference(),
              sent.eventReference(),
              blockBegin.monotonicTimeNs());
      owner.block = block;
      return new BlockHandle(block);
    } finally {
      stateLock.unlock();
    }
  }

  /**
   * Emits {@code execution.lease} for every open block whose age reached the policy threshold and
   * whose previous lease is at least one renewal interval old. Leases are generated from the same
   * serialized state as block closure, so a closed block is never leased (R8).
   */
  public void renewLeases(LeasePolicy policy) {
    Objects.requireNonNull(policy, "policy");
    stateLock.lock();
    try {
      long now = recorder.monotonicNanos();
      for (Execution execution : activeByInvocation.values()) {
        OpenBlock block = execution.block;
        if (block == null) {
          continue;
        }
        long ageMs = TimeUnit.NANOSECONDS.toMillis(now - block.openedNanos);
        boolean due =
            !block.leased
                || TimeUnit.NANOSECONDS.toMillis(now - block.lastLeaseNanos) >= policy.renewalMs();
        if (ageMs < policy.ageThresholdMs() || !due) {
          continue;
        }
        recorder.publishLocked(
            new EventPayload.ExecutionLease(
                execution.executionId,
                block.invocationId,
                execution.acquireReference,
                block.blockReference,
                ageMs,
                recorder.wallMillis() + policy.expiryMs()),
            "");
        block.leased = true;
        block.lastLeaseNanos = now;
      }
    } finally {
      stateLock.unlock();
    }
  }

  public Snapshot snapshot() {
    stateLock.lock();
    try {
      Map<String, String> owners = new LinkedHashMap<>();
      ownerByUnit.forEach(
          (unit, execution) -> owners.put(unit, execution == null ? null : execution.executionId));
      return new Snapshot(
          Collections.unmodifiableMap(owners),
          waiting.size(),
          recorder.droppedTotal(),
          recorder.lastSequenceLocked());
    } finally {
      stateLock.unlock();
    }
  }

  private Admission rejectLocked(String invocationId) {
    recorder.publishLocked(
        new EventPayload.ExecutionCancel(
            "", invocationId, EventPayload.CancelSource.SERVER, EventPayload.CancelReason.REJECTED),
        "");
    return Admission.REJECTED;
  }

  private Execution assignLocked(String unitId, PendingJob pending) {
    String executionId = recorder.instanceId() + ":exec-" + nextExecution++;
    ObservationEvent acquire =
        recorder.publishLocked(
            new EventPayload.ResourceAcquire(executionId, unitId, pending.invocationId()), "");
    Execution execution =
        new Execution(
            executionId, pending.invocationId(), unitId, acquire.eventReference(), pending.task());
    ownerByUnit.put(unitId, execution);
    activeByInvocation.put(pending.invocationId(), execution);
    return execution;
  }

  private void schedule(Execution execution) {
    try {
      workers.execute(() -> run(execution));
    } catch (RejectedExecutionException schedulingFailure) {
      finish(execution, EventPayload.Outcome.ERROR);
      throw schedulingFailure;
    }
  }

  private void run(Execution execution) {
    EventPayload.Outcome outcome = EventPayload.Outcome.ERROR;
    try {
      EventPayload.Outcome returned =
          execution.task.run(
              new ExecutionContext(
                  execution.executionId, execution.invocationId, execution.unitId));
      if (returned != null) {
        outcome = returned;
      }
    } finally {
      finish(execution, outcome);
    }
  }

  private void finish(Execution completed, EventPayload.Outcome outcome) {
    Execution next = null;
    stateLock.lock();
    try {
      if (ownerByUnit.get(completed.unitId) != completed) {
        throw new IllegalStateException("slot owner changed before task exit");
      }
      OpenBlock abandoned = completed.block;
      if (abandoned != null) {
        completed.block = null;
        recorder.publishLocked(
            new EventPayload.RpcBlockEnd(
                completed.executionId,
                abandoned.invocationId,
                EventPayload.Outcome.ERROR,
                EventPayload.CompletionKind.LOCAL),
            "");
      }
      recorder.publishLocked(
          new EventPayload.ExecutionEnd(completed.executionId, completed.invocationId, outcome),
          "");
      recorder.publishLocked(
          new EventPayload.ResourceRelease(completed.executionId, completed.unitId), "");
      ownerByUnit.put(completed.unitId, null);
      activeByInvocation.remove(completed.invocationId);
      if (!waiting.isEmpty() && !closed.get()) {
        next = assignLocked(completed.unitId, waiting.pollFirstEntry().getValue());
      }
    } finally {
      stateLock.unlock();
    }
    if (next != null) {
      schedule(next);
    }
  }

  private String firstFreeUnitLocked() {
    return ownerByUnit.entrySet().stream()
        .filter(entry -> entry.getValue() == null)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  private Execution requireActiveExecutionLocked(ExecutionContext context) {
    Execution owner = ownerByUnit.get(context.unitId());
    if (owner == null || !owner.executionId.equals(context.executionId())) {
      throw new IllegalStateException("execution does not own its declared unit");
    }
    return owner;
  }

  @Override
  public void close() throws InterruptedException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    stateLock.lock();
    try {
      for (PendingJob pending : waiting.values()) {
        recorder.publishLocked(
            new EventPayload.ExecutionCancel(
                "",
                pending.invocationId(),
                EventPayload.CancelSource.SERVER,
                EventPayload.CancelReason.REQUESTED),
            "");
      }
      waiting.clear();
    } finally {
      stateLock.unlock();
    }
    workers.shutdown();
    if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
      workers.shutdownNow();
      if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("application workers did not terminate");
      }
    }
  }

  private static String requireId(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be nonblank");
    }
    return value;
  }

  public enum Admission {
    ACQUIRED,
    QUEUED,
    REJECTED
  }

  public record Snapshot(
      Map<String, String> ownerByUnit, int waitingCount, long droppedTotal, long lastSequence) {}

  /** Execution identity supplied only after a slot has been acquired. */
  public record ExecutionContext(String executionId, String inboundInvocationId, String unitId) {}

  /** One admitted handler job; the returned outcome is recorded by {@code execution.end}. */
  @FunctionalInterface
  public interface ExecutionTask {
    EventPayload.Outcome run(ExecutionContext context);
  }

  /** One outstanding logical blocking RPC interval; completes exactly once. */
  public final class BlockHandle {
    private final OpenBlock block;

    private BlockHandle(OpenBlock block) {
      this.block = block;
    }

    public String invocationId() {
      return block.invocationId;
    }

    public String blockReference() {
      return block.blockReference;
    }

    public String sentReference() {
      return block.sentReference;
    }

    public void completeResponse(String responseReference, EventPayload.Outcome outcome) {
      requireId(responseReference, "responseReference");
      complete(responseReference, outcome, EventPayload.CompletionKind.RESPONSE);
    }

    public void completeLocal(EventPayload.Outcome outcome) {
      complete("", outcome, EventPayload.CompletionKind.LOCAL);
    }

    private void complete(
        String parent, EventPayload.Outcome outcome, EventPayload.CompletionKind kind) {
      Objects.requireNonNull(outcome, "outcome");
      stateLock.lock();
      try {
        if (block.owner.block != block) {
          throw new IllegalStateException("block already completed or no longer active");
        }
        recorder.publishLocked(
            new EventPayload.RpcBlockEnd(
                block.owner.executionId, block.invocationId, outcome, kind),
            parent);
        block.owner.block = null;
      } finally {
        stateLock.unlock();
      }
    }
  }

  private record PendingJob(String invocationId, ExecutionTask task) {}

  /** Mutable job state; every field is read and written only under the instance lock. */
  private static final class Execution {
    private final String executionId;
    private final String invocationId;
    private final String unitId;
    private final String acquireReference;
    private final ExecutionTask task;
    private OpenBlock block;
    private boolean cancelRecorded;

    private Execution(
        String executionId,
        String invocationId,
        String unitId,
        String acquireReference,
        ExecutionTask task) {
      this.executionId = executionId;
      this.invocationId = invocationId;
      this.unitId = unitId;
      this.acquireReference = acquireReference;
      this.task = task;
    }
  }

  /** Open block state; lease bookkeeping is read and written only under the instance lock. */
  private static final class OpenBlock {
    private final Execution owner;
    private final String invocationId;
    private final String blockReference;
    private final String sentReference;
    private final long openedNanos;
    private boolean leased;
    private long lastLeaseNanos;

    private OpenBlock(
        Execution owner,
        String invocationId,
        String blockReference,
        String sentReference,
        long openedNanos) {
      this.owner = owner;
      this.invocationId = invocationId;
      this.blockReference = blockReference;
      this.sentReference = sentReference;
      this.openedNanos = openedNanos;
    }
  }
}
