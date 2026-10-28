package dev.spanlease.analyzer;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Single-writer schema-v2 analyzer core for finite-run replay and MVP integration.
 *
 * <p>Ingress rejects events outside the immutable manifest and preserves conflicting duplicates as
 * coverage defects. Evaluation uses only checkpoint-certified contiguous prefixes, retracts causal
 * children whose parents are outside the cut, reconstructs explicit slot/wait state, performs
 * observed-progress reduction, and gates both confirmations on complete evidence.
 */
public final class AnalyzerEngine {
  private final RunManifest manifest;
  private final Map<String, TreeMap<Long, ObservationEnvelope>> streams = new LinkedHashMap<>();
  private final Set<String> invalidInstances = new TreeSet<>();

  public AnalyzerEngine(RunManifest manifest) {
    this.manifest = Objects.requireNonNull(manifest, "manifest");
    for (RunManifest.Instance instance : manifest.instances()) {
      streams.put(instance.instanceId(), new TreeMap<>());
    }
  }

  /** Must be called from the owning analyzer thread. Identical duplicates are idempotent. */
  public void ingest(ObservationEnvelope envelope) {
    ObservationEvent event = envelope.event();
    TreeMap<Long, ObservationEnvelope> stream = streams.get(event.instanceId());
    if (stream == null) {
      throw new IllegalArgumentException(
          "event instance is outside manifest: " + event.instanceId());
    }
    ObservationEnvelope existing = stream.putIfAbsent(event.localSeq(), envelope);
    if (existing != null && !existing.event().equals(event)) {
      invalidInstances.add(event.instanceId());
    }
  }

  /**
   * Synthetic-context convenience: receipt monotonic time must share the wall-millisecond origin.
   */
  public VerdictReport evaluate(long evaluationWallMs) {
    return evaluate(evaluationWallMs, Math.multiplyExact(evaluationWallMs, 1_000_000));
  }

  /**
   * Evaluates with explicit analyzer-local clocks; the monotonic origin must match all receipts.
   */
  public VerdictReport evaluate(long evaluationWallMs, long evaluationMonotonicNs) {
    if (evaluationWallMs < 0
        || evaluationMonotonicNs < 0
        || streams.values().stream()
            .flatMap(stream -> stream.values().stream())
            .anyMatch(envelope -> envelope.receiptMonotonicNs() > evaluationMonotonicNs)) {
      throw new IllegalArgumentException("evaluation must not precede an ingested receipt");
    }
    if (manifest.pools().stream().anyMatch(pool -> !pool.instrumented())) {
      return report(
          Verdict.INSUFFICIENT_OBSERVABILITY,
          evaluationWallMs,
          Map.of(),
          null,
          emptyCore(),
          coverage(
              List.of(
                  new VerdictReport.MissingEvidence(
                      "UNINSTRUMENTED_RESOURCE", firstUninstrumentedPool()))));
    }
    if (!invalidInstances.isEmpty()) {
      return report(
          Verdict.INSUFFICIENT_OBSERVABILITY,
          evaluationWallMs,
          Map.of(),
          null,
          emptyCore(),
          coverage(
              invalidInstances.stream()
                  .map(id -> new VerdictReport.MissingEvidence("INVALID_EVENT", id))
                  .toList()));
    }

    Certification certification = certify(evaluationMonotonicNs);
    if (!certification.missing().isEmpty()) {
      return report(
          Verdict.CANDIDATE_INCONCLUSIVE,
          evaluationWallMs,
          certification.cut(),
          null,
          emptyCore(),
          coverage(certification.missing()));
    }

    List<ObservationEvent> certifiedEvents = new ArrayList<>();
    for (String instanceId : new TreeSet<>(streams.keySet())) {
      streams
          .get(instanceId)
          .headMap(certification.cut().get(instanceId), true)
          .values()
          .forEach(envelope -> certifiedEvents.add(envelope.event()));
    }
    List<VerdictReport.MissingEvidence> invalidHistory =
        CausalHistoryValidator.validate(manifest, certifiedEvents);
    if (!invalidHistory.isEmpty()) {
      return report(
          Verdict.INSUFFICIENT_OBSERVABILITY,
          evaluationWallMs,
          certification.cut(),
          null,
          emptyCore(),
          coverage(invalidHistory));
    }

    CutResult cutResult = causalCut(certification.cut());
    if (!cutResult.missing().isEmpty()) {
      return report(
          Verdict.CANDIDATE_INCONCLUSIVE,
          evaluationWallMs,
          cutResult.cut(),
          null,
          emptyCore(),
          coverage(cutResult.missing()));
    }

    Reconstructed state = reconstruct(cutResult.cut());
    // Whole-scope eligibility precedes reduction: progress pruning cannot hide an unleased block.
    long endpoint = certifiedEndpoint(certification);
    long epsilon = manifest.parameters().epsilonMs();
    for (String executionId : state.blockedExecutions()) {
      if (state.executions.get(executionId).leaseCovering(endpoint, epsilon) == null) {
        state.missing.add(new VerdictReport.MissingEvidence("EXPIRED_LEASE", executionId));
      }
    }
    if (!state.missing.isEmpty()) {
      return report(
          state.missing.stream().anyMatch(item -> item.code().equals("INVALID_EVENT"))
              ? Verdict.INSUFFICIENT_OBSERVABILITY
              : Verdict.CANDIDATE_INCONCLUSIVE,
          evaluationWallMs,
          cutResult.cut(),
          null,
          diagnosticCore(state, state.blockedExecutions(), state.waitingInvocations()),
          coverage(state.missing));
    }

    Set<String> residual = reduce(state);
    if (residual.isEmpty()) {
      return report(
          Verdict.CONFIRMED_NO_DEADLOCK,
          evaluationWallMs,
          cutResult.cut(),
          null,
          emptyCore(),
          coverage(List.of()));
    }

    PersistenceResult persistence = qualifyPersistence(state, residual, certification);
    if (persistence.persistence() == null) {
      return report(
          Verdict.CANDIDATE_INCONCLUSIVE,
          evaluationWallMs,
          cutResult.cut(),
          null,
          diagnosticCore(state, executionsIn(residual, state), queuesIn(residual, state)),
          coverage(persistence.missing()));
    }
    return report(
        Verdict.CONFIRMED_DEADLOCK,
        evaluationWallMs,
        cutResult.cut(),
        persistence.persistence(),
        diagnosticCore(
            state,
            executionsIn(persistence.witness(), state),
            queuesIn(persistence.witness(), state)),
        coverage(List.of()));
  }

  private Certification certify(long evaluationMonotonicNs) {
    Map<String, Long> cut = new TreeMap<>();
    Map<String, ObservationEnvelope> checkpoints = new HashMap<>();
    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    // A fresh older checkpoint cannot conceal a known hole in the received suffix (§5.2).
    // Reordered delivery can fill it later; elapsed time or newer checkpoints cannot.
    for (VerdictReport.FrontierGap gap : sequenceGaps()) {
      missing.add(
          new VerdictReport.MissingEvidence(
              "SEQUENCE_GAP", gap.instanceId() + "#" + gap.fromSeq()));
    }
    for (Map.Entry<String, TreeMap<Long, ObservationEnvelope>> entry : streams.entrySet()) {
      String instanceId = entry.getKey();
      TreeMap<Long, ObservationEnvelope> stream = entry.getValue();
      ObservationEnvelope checkpoint = null;
      for (ObservationEnvelope envelope :
          stream.headMap(contiguousPrefix(stream), true).descendingMap().values()) {
        if (envelope.event().payload() instanceof EventPayload.InstanceCheckpoint) {
          checkpoint = envelope;
          break;
        }
      }
      if (checkpoint == null) {
        cut.put(instanceId, 0L);
        missing.add(new VerdictReport.MissingEvidence("MISSING_CHECKPOINT", instanceId));
        continue;
      }
      cut.put(instanceId, checkpoint.event().localSeq());
      checkpoints.put(instanceId, checkpoint);
      if (evaluationMonotonicNs - checkpoint.receiptMonotonicNs()
          > TimeUnit.MILLISECONDS.toNanos(manifest.parameters().checkpointFreshnessMs())) {
        missing.add(new VerdictReport.MissingEvidence("STALE_CHECKPOINT", instanceId));
      }
      if (!clockQualified(instanceId, checkpoint.event().wallTimeMs())) {
        missing.add(new VerdictReport.MissingEvidence("UNQUALIFIED_CLOCK", instanceId));
      }
    }
    return new Certification(Map.copyOf(cut), Map.copyOf(checkpoints), List.copyOf(missing));
  }

  /** Highest sequence n such that every sequence 1..n has been received. */
  private static long contiguousPrefix(TreeMap<Long, ObservationEnvelope> stream) {
    long expected = 1;
    for (long sequence : stream.keySet()) {
      if (sequence != expected) {
        break;
      }
      expected++;
    }
    return expected - 1;
  }

  private boolean clockQualified(String instanceId, long wallTimeMs) {
    RunManifest.Instance instance =
        manifest.instances().stream()
            .filter(candidate -> candidate.instanceId().equals(instanceId))
            .findFirst()
            .orElseThrow();
    return wallTimeMs >= instance.clockQualifiedFromMs()
        && wallTimeMs <= instance.clockQualifiedThroughMs();
  }

  private CutResult causalCut(Map<String, Long> certifiedCut) {
    Map<String, Long> cut = new TreeMap<>(certifiedCut);
    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    boolean changed;
    do {
      changed = false;
      for (Map.Entry<String, TreeMap<Long, ObservationEnvelope>> entry : streams.entrySet()) {
        String childInstance = entry.getKey();
        long selected = cut.getOrDefault(childInstance, 0L);
        for (ObservationEnvelope envelope : entry.getValue().headMap(selected, true).values()) {
          String parent = envelope.event().causalParent();
          if (parent.isEmpty()) {
            continue;
          }
          Optional<EventReference> reference = EventReference.parse(parent);
          if (reference.isEmpty() || !streams.containsKey(reference.orElseThrow().instanceId())) {
            missing.add(new VerdictReport.MissingEvidence("MISSING_CAUSAL_PARENT", parent));
            continue;
          }
          EventReference parsed = reference.orElseThrow();
          if (cut.getOrDefault(parsed.instanceId(), 0L) < parsed.sequence()) {
            cut.put(childInstance, envelope.event().localSeq() - 1);
            changed = true;
            break;
          }
        }
      }
    } while (changed);
    return new CutResult(Map.copyOf(cut), List.copyOf(missing));
  }

  private Reconstructed reconstruct(Map<String, Long> cut) {
    Reconstructed state = new Reconstructed();
    manifest
        .pools()
        .forEach(
            pool -> {
              state.poolUnits.put(pool.qualifiedId(), Set.copyOf(pool.unitIds()));
              pool.unitIds()
                  .forEach(
                      unit -> {
                        state.unitOwners.put(unit, null);
                        state.unitInstances.put(unit, pool.instanceId());
                        state.unitPools.put(unit, pool.qualifiedId());
                      });
            });

    for (String instanceId : new TreeSet<>(streams.keySet())) {
      long selected = cut.getOrDefault(instanceId, 0L);
      for (ObservationEnvelope envelope :
          streams.get(instanceId).headMap(selected, true).values()) {
        apply(state, envelope.event());
      }
    }

    for (RunManifest.Pool pool : manifest.pools()) {
      if (!state.initializedPools.contains(pool.qualifiedId())) {
        state.missing.add(new VerdictReport.MissingEvidence("MISSING_INIT", pool.qualifiedId()));
      }
    }
    for (ExecutionState execution : state.executions.values()) {
      if (execution.blockInvocation != null && execution.cancelPending) {
        // Cancellation while the helper is still blocked is unresolved eligibility (design §5.3).
        state.missing.add(new VerdictReport.MissingEvidence("CANCEL_PENDING", execution.id));
      }
      if (execution.blockInvocation != null && !execution.cancelPending) {
        if (!state.waiting.containsKey(execution.blockInvocation)
            && !state.executionByInvocation.containsKey(execution.blockInvocation)) {
          state.missing.add(
              new VerdictReport.MissingEvidence("UNKNOWN_COUNTERPART", execution.blockInvocation));
        }
      }
    }
    return state;
  }

  private void apply(Reconstructed state, ObservationEvent event) {
    EventPayload payload = event.payload();
    if (payload instanceof EventPayload.ResourceInit init) {
      String pool = event.instanceId() + ":" + init.resourceId();
      Set<String> declared = state.poolUnits.get(pool);
      if (declared == null
          || !declared.equals(Set.copyOf(init.unitIds()))
          || state.initializedPools.contains(pool)) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        state.initializedPools.add(pool);
      }
    } else if (payload instanceof EventPayload.ResourceWaitBegin wait) {
      String pool = event.instanceId() + ":" + wait.resourceId();
      if (!state.initializedPools.contains(pool)
          || state.waiting.containsKey(wait.invocationId())
          || state.executionByInvocation.containsKey(wait.invocationId())) {
        invalid(state, event);
        return;
      }
      state.waiting.put(
          wait.invocationId(),
          new WaitState(
              wait.invocationId(), event.instanceId(), wait.resourceId(), event.wallTimeMs()));
    } else if (payload instanceof EventPayload.ResourceAcquire acquire) {
      if (!event.instanceId().equals(state.unitInstances.get(acquire.resourceInstanceId()))
          || !state.initializedPools.contains(state.unitPools.get(acquire.resourceInstanceId()))
          || state.unitOwners.get(acquire.resourceInstanceId()) != null
          || state.executions.containsKey(acquire.executionId())
          || state.executionByInvocation.containsKey(acquire.invocationId())) {
        invalid(state, event);
        return;
      }
      WaitState queued = state.waiting.get(acquire.invocationId());
      if (queued != null
          && (!queued.instanceId.equals(event.instanceId())
              || !state
                  .poolUnits
                  .get(queued.instanceId + ":" + queued.resourceId)
                  .contains(acquire.resourceInstanceId()))) {
        invalid(state, event);
        return;
      }
      state.waiting.remove(acquire.invocationId());
      state.executionByInvocation.put(acquire.invocationId(), acquire.executionId());
      state.executions.put(
          acquire.executionId(),
          new ExecutionState(
              acquire.executionId(),
              event.instanceId(),
              acquire.invocationId(),
              acquire.resourceInstanceId(),
              event.eventReference(),
              event.wallTimeMs()));
      if (!state.unitOwners.containsKey(acquire.resourceInstanceId())
          || state.unitOwners.get(acquire.resourceInstanceId()) != null) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        state.unitOwners.put(acquire.resourceInstanceId(), acquire.executionId());
      }
    } else if (payload instanceof EventPayload.RpcBlockBegin block) {
      ExecutionState execution = state.executions.get(block.executionId());
      if (!activeHere(execution, event) || execution.ended || execution.blockInvocation != null) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.blockInvocation = block.invocationId();
        execution.blockRef = event.eventReference();
        execution.blockStartMs = event.wallTimeMs();
        execution.leases.clear();
      }
    } else if (payload instanceof EventPayload.ExecutionLease lease) {
      ExecutionState execution = state.executions.get(lease.executionId());
      if (!activeHere(execution, event)
          || execution.ended
          || !lease.blockRef().equals(execution.blockRef)
          || !lease.startRef().equals(execution.acquireRef)
          || !lease.invocationId().equals(execution.blockInvocation)
          || lease.leaseExpiryMs() <= event.wallTimeMs()) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.leases.add(
            new LeaseState(event.eventReference(), event.wallTimeMs(), lease.leaseExpiryMs()));
      }
    } else if (payload instanceof EventPayload.RpcBlockEnd end) {
      ExecutionState execution = state.executions.get(end.executionId());
      if (!activeHere(execution, event)
          || execution.ended
          || !end.invocationId().equals(execution.blockInvocation)) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.blockInvocation = null;
        execution.blockRef = null;
        execution.leases.clear();
      }
    } else if (payload instanceof EventPayload.ExecutionCancel cancel) {
      if (cancel.executionId().isEmpty()) {
        // Cancel won before assignment (or admission was rejected): no queue entry remains.
        state.waiting.remove(cancel.invocationId());
      } else {
        ExecutionState execution = state.executions.get(cancel.executionId());
        if (activeHere(execution, event)
            && !execution.ended
            && execution.invocationId.equals(cancel.invocationId())
            && !execution.cancelPending) {
          execution.cancelPending = true;
        } else {
          invalid(state, event);
        }
      }
    } else if (payload instanceof EventPayload.ExecutionEnd end) {
      ExecutionState execution = state.executions.get(end.executionId());
      if (activeHere(execution, event)
          && !execution.ended
          && execution.blockInvocation == null
          && execution.invocationId.equals(end.invocationId())) {
        execution.ended = true;
        execution.blockInvocation = null;
        execution.leases.clear();
      } else {
        invalid(state, event);
      }
    } else if (payload instanceof EventPayload.ResourceRelease release) {
      ExecutionState execution = state.executions.get(release.executionId());
      if (!activeHere(execution, event)
          || !execution.ended
          || !release.resourceInstanceId().equals(execution.unitId)
          || !release.executionId().equals(state.unitOwners.get(release.resourceInstanceId()))) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        state.unitOwners.put(release.resourceInstanceId(), null);
        execution.released = true;
      }
    } else if (payload instanceof EventPayload.RpcInvocationSent sent) {
      if (!sent.callerExecutionId().isEmpty()) {
        ExecutionState execution = state.executions.get(sent.callerExecutionId());
        if (!activeHere(execution, event)
            || execution.ended
            || !sent.invocationId().equals(execution.blockInvocation)) invalid(state, event);
      }
    } else if (payload instanceof EventPayload.RpcResponseSent response) {
      if (!state.responded.add(response.invocationId())) invalid(state, event);
      if (!response.executionId().isEmpty()) {
        ExecutionState execution = state.executions.get(response.executionId());
        if (!activeHere(execution, event)
            || execution.ended
            || !response.invocationId().equals(execution.invocationId)) invalid(state, event);
      }
    }
  }

  private static boolean activeHere(ExecutionState execution, ObservationEvent event) {
    return execution != null
        && !execution.released
        && execution.instanceId.equals(event.instanceId());
  }

  private static void invalid(Reconstructed state, ObservationEvent event) {
    state.missing.add(new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
  }

  private Set<String> reduce(Reconstructed state) {
    Set<String> candidates = new TreeSet<>();
    candidates.addAll(state.blockedExecutions());
    candidates.addAll(state.waitingInvocations());
    return reduce(state, candidates);
  }

  /**
   * Outside members are unavailable to this proposed witness; this also serves temporal pruning.
   */
  private Set<String> reduce(Reconstructed state, Set<String> initial) {
    Set<String> candidates = new TreeSet<>(initial);
    boolean changed;
    do {
      changed = false;
      for (String member : new ArrayList<>(candidates)) {
        ExecutionState execution = state.executions.get(member);
        if (execution != null) {
          String counterpart = state.executionByInvocation.get(execution.blockInvocation);
          if (counterpart == null) {
            counterpart = execution.blockInvocation;
          }
          if (!candidates.contains(counterpart)) {
            candidates.remove(member);
            changed = true;
          }
          continue;
        }

        WaitState wait = state.waiting.get(member);
        String pool = wait.instanceId + ":" + wait.resourceId;
        for (String unit : state.poolUnits.getOrDefault(pool, Set.of())) {
          String owner = state.unitOwners.get(unit);
          if (owner == null || !candidates.contains(owner)) {
            candidates.remove(member);
            changed = true;
            break;
          }
        }
      }
    } while (changed);
    return Set.copyOf(candidates);
  }

  private PersistenceResult qualifyPersistence(
      Reconstructed state, Set<String> residual, Certification certification) {
    long epsilon = manifest.parameters().epsilonMs();
    long end = certifiedEndpoint(certification);
    Set<String> eligible = new TreeSet<>();
    for (String member : residual) {
      if (assertionStart(state, member) + epsilon <= end - manifest.parameters().persistenceMs()) {
        eligible.add(member);
      }
    }
    // A young upstream waiter or disjoint component must not reset the old closed component.
    Set<String> witness = reduce(state, eligible);
    if (witness.isEmpty()) {
      return new PersistenceResult(
          null,
          Set.of(),
          List.of(new VerdictReport.MissingEvidence("PERSISTENCE_TOO_SHORT", "residual")));
    }
    long start = Long.MIN_VALUE;
    for (String member : witness) {
      start = Math.max(start, assertionStart(state, member) + epsilon);
    }

    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    long duration = end >= start ? end - start : 0;
    if (duration < manifest.parameters().persistenceMs()) {
      missing.add(new VerdictReport.MissingEvidence("PERSISTENCE_TOO_SHORT", "residual"));
    }
    if (!missing.isEmpty()) {
      return new PersistenceResult(null, Set.of(), List.copyOf(missing));
    }
    return new PersistenceResult(
        new VerdictReport.Persistence(start, end, duration, manifest.parameters().persistenceMs()),
        witness,
        List.of());
  }

  private static long assertionStart(Reconstructed state, String member) {
    ExecutionState execution = state.executions.get(member);
    return execution == null
        ? state.waiting.get(member).startMs
        : Math.max(execution.acquireStartMs, execution.blockStartMs);
  }

  private long certifiedEndpoint(Certification certification) {
    return certification.checkpoints().values().stream()
        .mapToLong(
            checkpoint -> checkpoint.event().wallTimeMs() - manifest.parameters().epsilonMs())
        .min()
        .orElseThrow();
  }

  private VerdictReport report(
      Verdict verdict,
      long evaluationWallMs,
      Map<String, Long> cut,
      VerdictReport.Persistence persistence,
      VerdictReport.Core core,
      VerdictReport.Coverage coverage) {
    List<String> scope =
        manifest.instances().stream().map(RunManifest.Instance::instanceId).toList();
    Map<String, Long> completeCut = new TreeMap<>();
    scope.forEach(instance -> completeCut.put(instance, cut.getOrDefault(instance, 0L)));
    Long horizon =
        cut.isEmpty()
            ? null
            : cut.entrySet().stream()
                .map(entry -> streams.get(entry.getKey()).get(entry.getValue()))
                .filter(Objects::nonNull)
                .map(envelope -> envelope.event().wallTimeMs())
                .min(Long::compareTo)
                .orElse(null);
    return new VerdictReport(
        ObservationEvent.SCHEMA_VERSION,
        manifest.runId(),
        manifest.manifestHash(),
        verdict,
        evaluationWallMs,
        new VerdictReport.Query(scope, completeCut, horizon),
        persistence,
        core,
        coverage);
  }

  private VerdictReport.Coverage coverage(List<VerdictReport.MissingEvidence> missing) {
    int total = manifest.pools().stream().mapToInt(pool -> pool.unitIds().size()).sum();
    return new VerdictReport.Coverage(
        total, missing.isEmpty() ? total : 0, List.of(), sequenceGaps(), List.of(), missing);
  }

  /** Known missing ranges through the greatest received sequence; never invents an unseen tail. */
  private List<VerdictReport.FrontierGap> sequenceGaps() {
    List<VerdictReport.FrontierGap> gaps = new ArrayList<>();
    for (Map.Entry<String, TreeMap<Long, ObservationEnvelope>> entry : streams.entrySet()) {
      long previous = 0;
      for (long sequence : entry.getValue().keySet()) {
        if (sequence - previous > 1) {
          gaps.add(new VerdictReport.FrontierGap(entry.getKey(), previous + 1, sequence - 1));
        }
        previous = sequence;
      }
    }
    return List.copyOf(gaps);
  }

  private static VerdictReport.Core emptyCore() {
    return new VerdictReport.Core(List.of(), List.of(), List.of(), List.of());
  }

  private VerdictReport.Core diagnosticCore(
      Reconstructed state, Set<String> executions, Set<String> queues) {
    List<VerdictReport.Execution> executionReports =
        executions.stream()
            .map(state.executions::get)
            .map(
                execution ->
                    new VerdictReport.Execution(
                        execution.id,
                        execution.invocationId,
                        execution.blockInvocation,
                        execution.blockRef))
            .toList();
    List<VerdictReport.QueueEntry> queueReports =
        queues.stream()
            .map(state.waiting::get)
            .map(
                wait ->
                    new VerdictReport.QueueEntry(
                        wait.invocationId, wait.instanceId, wait.resourceId))
            .toList();
    Set<String> owners = new HashSet<>(executions);
    List<VerdictReport.Unit> units =
        state.unitOwners.entrySet().stream()
            .filter(entry -> entry.getValue() != null && owners.contains(entry.getValue()))
            .map(
                entry -> {
                  ExecutionState owner = state.executions.get(entry.getValue());
                  return new VerdictReport.Unit(
                      entry.getKey(),
                      owner.id,
                      owner.acquireRef,
                      owner.leases.isEmpty() ? "" : owner.leases.getLast().reference());
                })
            .toList();
    List<VerdictReport.Edge> edges = new ArrayList<>();
    for (VerdictReport.Unit unit : units) {
      edges.add(new VerdictReport.Edge(unit.id(), unit.ownerExecutionId(), "own"));
    }
    for (String executionId : executions) {
      ExecutionState execution = state.executions.get(executionId);
      String counterpart = state.executionByInvocation.get(execution.blockInvocation);
      edges.add(
          new VerdictReport.Edge(
              executionId, counterpart == null ? execution.blockInvocation : counterpart, "wait"));
    }
    for (String queue : queues) {
      WaitState wait = state.waiting.get(queue);
      String pool = wait.instanceId + ":" + wait.resourceId;
      edges.add(new VerdictReport.Edge(queue, pool, "queue-wait"));
    }
    return new VerdictReport.Core(executionReports, queueReports, units, edges);
  }

  private String firstUninstrumentedPool() {
    return manifest.pools().stream()
        .filter(pool -> !pool.instrumented())
        .findFirst()
        .orElseThrow()
        .qualifiedId();
  }

  private static Set<String> executionsIn(Set<String> residual, Reconstructed state) {
    Set<String> result = new TreeSet<>(residual);
    result.retainAll(state.executions.keySet());
    return Set.copyOf(result);
  }

  private static Set<String> queuesIn(Set<String> residual, Reconstructed state) {
    Set<String> result = new TreeSet<>(residual);
    result.retainAll(state.waiting.keySet());
    return Set.copyOf(result);
  }

  private record Certification(
      Map<String, Long> cut,
      Map<String, ObservationEnvelope> checkpoints,
      List<VerdictReport.MissingEvidence> missing) {}

  private record CutResult(Map<String, Long> cut, List<VerdictReport.MissingEvidence> missing) {}

  private record PersistenceResult(
      VerdictReport.Persistence persistence,
      Set<String> witness,
      List<VerdictReport.MissingEvidence> missing) {}

  private record EventReference(String instanceId, long sequence) {
    private static Optional<EventReference> parse(String value) {
      int separator = value.lastIndexOf('#');
      if (separator <= 0 || separator == value.length() - 1) {
        return Optional.empty();
      }
      try {
        long sequence = Long.parseLong(value.substring(separator + 1));
        return sequence > 0
            ? Optional.of(new EventReference(value.substring(0, separator), sequence))
            : Optional.empty();
      } catch (NumberFormatException ignored) {
        return Optional.empty();
      }
    }
  }

  private static final class Reconstructed {
    private final Map<String, Set<String>> poolUnits = new HashMap<>();
    private final Map<String, String> unitOwners = new HashMap<>();
    private final Map<String, String> unitInstances = new HashMap<>();
    private final Map<String, String> unitPools = new HashMap<>();
    private final Set<String> initializedPools = new HashSet<>();
    private final Map<String, WaitState> waiting = new HashMap<>();
    private final Map<String, ExecutionState> executions = new HashMap<>();
    private final Map<String, String> executionByInvocation = new HashMap<>();
    private final Set<String> responded = new HashSet<>();
    private final List<VerdictReport.MissingEvidence> missing = new ArrayList<>();

    private Set<String> blockedExecutions() {
      Set<String> result = new TreeSet<>();
      executions.values().stream()
          .filter(execution -> execution.blockInvocation != null && !execution.cancelPending)
          .map(execution -> execution.id)
          .forEach(result::add);
      return Set.copyOf(result);
    }

    private Set<String> waitingInvocations() {
      return Set.copyOf(waiting.keySet());
    }
  }

  private static final class ExecutionState {
    private final String id;
    private final String instanceId;
    private final String invocationId;
    private final String unitId;
    private final String acquireRef;
    private final long acquireStartMs;
    private String blockInvocation;
    private String blockRef;
    private long blockStartMs;
    private final List<LeaseState> leases = new ArrayList<>();
    private boolean cancelPending;
    private boolean ended;
    private boolean released;

    private ExecutionState(
        String id,
        String instanceId,
        String invocationId,
        String unitId,
        String acquireRef,
        long acquireStartMs) {
      this.id = id;
      this.instanceId = instanceId;
      this.invocationId = invocationId;
      this.unitId = unitId;
      this.acquireRef = acquireRef;
      this.acquireStartMs = acquireStartMs;
    }

    /**
     * Any lease of the current block whose epsilon-narrowed validity [observed+ε, expiry−ε]
     * contains the endpoint; each lease asserts the block only from its emission onward.
     */
    private LeaseState leaseCovering(long endpointMs, long epsilonMs) {
      for (LeaseState lease : leases) {
        if (lease.observedMs() + epsilonMs <= endpointMs
            && endpointMs <= lease.expiryMs() - epsilonMs) {
          return lease;
        }
      }
      return null;
    }
  }

  private record LeaseState(String reference, long observedMs, long expiryMs) {}

  private record WaitState(
      String invocationId, String instanceId, String resourceId, long startMs) {}
}
