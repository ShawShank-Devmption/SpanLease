package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Fixed-capacity application gate with serialized state and telemetry transitions.
 *
 * <p>The state lock owns admission, slot ownership, execution identity, local sequence allocation
 * and publication attempts (I1). An acquire is published before application code can run (I2). The
 * worker's finally path publishes execution end before release (I3). The supplied sink must perform
 * an immediate bounded offer; this class never waits for telemetry capacity or I/O (I4).
 */
public final class SlotTrackingExecutor implements AutoCloseable {
  private final ReentrantLock stateLock = new ReentrantLock();
  private final Deque<PendingJob> waiting = new ArrayDeque<>();
  private final Map<String, String> ownerByUnit = new LinkedHashMap<>();
  private final Map<String, OpenBlock> openBlocks = new LinkedHashMap<>();
  private final ExecutorService workers;
  private final String instanceId;
  private final String resourceId;
  private final List<String> unitIds;
  private final int waitingCapacity;
  private final EventSink sink;
  private final Clock wallClock;
  private final LongSupplier monotonicClock;
  private final AtomicBoolean closed = new AtomicBoolean();
  private long nextSequence = 1;
  private long nextExecution = 1;
  private long droppedTotal;

  public SlotTrackingExecutor(
      String instanceId,
      String resourceId,
      int capacity,
      int waitingCapacity,
      EventSink sink,
      Clock wallClock,
      LongSupplier monotonicClock,
      ThreadFactory threadFactory) {
    this.instanceId = requireId(instanceId, "instanceId");
    this.resourceId = requireId(resourceId, "resourceId");
    if (capacity <= 0 || waitingCapacity <= 0) {
      throw new IllegalArgumentException("capacity and waitingCapacity must be positive");
    }
    this.waitingCapacity = waitingCapacity;
    this.sink = Objects.requireNonNull(sink, "sink");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    this.workers = Executors.newFixedThreadPool(capacity, Objects.requireNonNull(threadFactory));

    List<String> units = new ArrayList<>();
    for (int index = 0; index < capacity; index++) {
      String unitId = instanceId + ":" + resourceId + ":slot-" + index;
      units.add(unitId);
      ownerByUnit.put(unitId, null);
    }
    this.unitIds = List.copyOf(units);

    stateLock.lock();
    try {
      publishLocked(new EventPayload.ResourceInit(resourceId, capacity, unitIds), "");
    } finally {
      stateLock.unlock();
    }
  }

  /** Submits one decoded unary handler job; rejected jobs never acquire a slot. */
  public Admission submit(String invocationId, Runnable task) {
    Objects.requireNonNull(task, "task");
    return submit(invocationId, ignored -> task.run());
  }

  /** Submits a handler that receives the assigned execution and unit identities. */
  public Admission submit(String invocationId, ExecutionTask task) {
    requireId(invocationId, "invocationId");
    Objects.requireNonNull(task, "task");

    AssignedJob assigned = null;
    stateLock.lock();
    try {
      if (closed.get()) {
        return rejectLocked(invocationId);
      }
      String freeUnit = firstFreeUnitLocked();
      if (freeUnit == null) {
        if (waiting.size() >= waitingCapacity) {
          return rejectLocked(invocationId);
        }
        waiting.addLast(new PendingJob(invocationId, task));
        publishLocked(new EventPayload.ResourceWaitBegin(invocationId, resourceId), "");
        return Admission.QUEUED;
      }
      assigned = assignLocked(freeUnit, new PendingJob(invocationId, task));
    } finally {
      stateLock.unlock();
    }
    schedule(assigned);
    return Admission.ACQUIRED;
  }

  /** Emits an idle or active complete-prefix boundary from the same serialized state. */
  public void checkpoint() {
    stateLock.lock();
    try {
      long checkpointSequence = nextSequence;
      publishLocked(new EventPayload.InstanceCheckpoint(checkpointSequence - 1, droppedTotal), "");
    } finally {
      stateLock.unlock();
    }
  }

  public Snapshot snapshot() {
    stateLock.lock();
    try {
      return new Snapshot(
          Collections.unmodifiableMap(new LinkedHashMap<>(ownerByUnit)),
          waiting.size(),
          droppedTotal,
          nextSequence - 1);
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
      requireActiveExecutionLocked(execution);
      if (openBlocks.containsKey(execution.executionId())) {
        throw new IllegalStateException("execution already has an open downstream block");
      }
      ObservationEvent blockBegin =
          publishLocked(
              new EventPayload.RpcBlockBegin(execution.executionId(), downstreamInvocationId), "");
      ObservationEvent sent =
          publishLocked(
              new EventPayload.RpcInvocationSent(
                  downstreamInvocationId, execution.executionId(), targetService),
              "");
      OpenBlock block =
          new OpenBlock(
              execution.executionId(),
              downstreamInvocationId,
              blockBegin.eventReference(),
              sent.eventReference());
      openBlocks.put(execution.executionId(), block);
      return new BlockHandle(block);
    } finally {
      stateLock.unlock();
    }
  }

  /** Commits the response reference before the generated service adapter closes transport. */
  public String responseSent(ExecutionContext execution, EventPayload.Outcome outcome) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(outcome, "outcome");
    stateLock.lock();
    try {
      requireActiveExecutionLocked(execution);
      return publishLocked(
              new EventPayload.RpcResponseSent(
                  execution.inboundInvocationId(), execution.executionId(), outcome),
              "")
          .eventReference();
    } finally {
      stateLock.unlock();
    }
  }

  /** Records a cancellation request without changing ownership or releasing the slot. */
  public void cancellationRequested(
      ExecutionContext execution,
      EventPayload.CancelSource source,
      EventPayload.CancelReason reason) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(reason, "reason");
    stateLock.lock();
    try {
      requireActiveExecutionLocked(execution);
      publishLocked(
          new EventPayload.ExecutionCancel(
              execution.executionId(), execution.inboundInvocationId(), source, reason),
          "");
    } finally {
      stateLock.unlock();
    }
  }

  private Admission rejectLocked(String invocationId) {
    publishLocked(
        new EventPayload.ExecutionCancel(
            "", invocationId, EventPayload.CancelSource.SERVER, EventPayload.CancelReason.REJECTED),
        "");
    return Admission.REJECTED;
  }

  private AssignedJob assignLocked(String unitId, PendingJob pending) {
    String executionId = instanceId + ":exec-" + nextExecution++;
    ownerByUnit.put(unitId, executionId);
    publishLocked(
        new EventPayload.ResourceAcquire(executionId, unitId, pending.invocationId()), "");
    return new AssignedJob(executionId, unitId, pending);
  }

  private void schedule(AssignedJob assigned) {
    try {
      workers.execute(() -> run(assigned));
    } catch (RuntimeException schedulingFailure) {
      finish(assigned, EventPayload.Outcome.ERROR);
      throw schedulingFailure;
    }
  }

  private void run(AssignedJob assigned) {
    EventPayload.Outcome outcome = EventPayload.Outcome.OK;
    try {
      assigned
          .pending()
          .task()
          .run(
              new ExecutionContext(
                  assigned.executionId(), assigned.pending().invocationId(), assigned.unitId()));
    } catch (RuntimeException | Error failure) {
      outcome = EventPayload.Outcome.ERROR;
      throw failure;
    } finally {
      finish(assigned, outcome);
    }
  }

  private void finish(AssignedJob completed, EventPayload.Outcome outcome) {
    AssignedJob next = null;
    stateLock.lock();
    try {
      String observedOwner = ownerByUnit.get(completed.unitId());
      if (!completed.executionId().equals(observedOwner)) {
        throw new IllegalStateException("slot owner changed before task exit");
      }
      OpenBlock abandoned = openBlocks.remove(completed.executionId());
      if (abandoned != null) {
        publishLocked(
            new EventPayload.RpcBlockEnd(
                completed.executionId(),
                abandoned.invocationId(),
                EventPayload.Outcome.ERROR,
                EventPayload.CompletionKind.LOCAL),
            "");
      }
      publishLocked(
          new EventPayload.ExecutionEnd(
              completed.executionId(), completed.pending().invocationId(), outcome),
          "");
      publishLocked(
          new EventPayload.ResourceRelease(completed.executionId(), completed.unitId()), "");
      ownerByUnit.put(completed.unitId(), null);
      if (!waiting.isEmpty() && !closed.get()) {
        next = assignLocked(completed.unitId(), waiting.removeFirst());
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

  private ObservationEvent publishLocked(EventPayload payload, String causalParent) {
    long sequence = nextSequence++;
    ObservationEvent event =
        new ObservationEvent(
            ObservationEvent.SCHEMA_VERSION,
            instanceId,
            sequence,
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

  private void requireActiveExecutionLocked(ExecutionContext execution) {
    if (!execution.executionId().equals(ownerByUnit.get(execution.unitId()))) {
      throw new IllegalStateException("execution does not own its declared unit");
    }
  }

  @Override
  public void close() throws InterruptedException {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    stateLock.lock();
    try {
      while (!waiting.isEmpty()) {
        PendingJob pending = waiting.removeFirst();
        publishLocked(
            new EventPayload.ExecutionCancel(
                "",
                pending.invocationId(),
                EventPayload.CancelSource.SERVER,
                EventPayload.CancelReason.REQUESTED),
            "");
      }
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

  @FunctionalInterface
  public interface ExecutionTask {
    void run(ExecutionContext context);
  }

  /** One outstanding logical blocking RPC interval. */
  public final class BlockHandle {
    private final OpenBlock block;
    private boolean completed;

    private BlockHandle(OpenBlock block) {
      this.block = block;
    }

    public String invocationId() {
      return block.invocationId();
    }

    public String blockReference() {
      return block.blockReference();
    }

    public String sentReference() {
      return block.sentReference();
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
        if (completed || openBlocks.get(block.executionId()) != block) {
          throw new IllegalStateException("block already completed or no longer active");
        }
        publishLocked(
            new EventPayload.RpcBlockEnd(block.executionId(), block.invocationId(), outcome, kind),
            parent);
        openBlocks.remove(block.executionId());
        completed = true;
      } finally {
        stateLock.unlock();
      }
    }
  }

  private record PendingJob(String invocationId, ExecutionTask task) {}

  private record AssignedJob(String executionId, String unitId, PendingJob pending) {}

  private record OpenBlock(
      String executionId, String invocationId, String blockReference, String sentReference) {}
}
