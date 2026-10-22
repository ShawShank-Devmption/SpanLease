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

  public VerdictReport evaluate(long evaluationWallMs) {
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

    Certification certification = certify(evaluationWallMs);
    if (!certification.missing().isEmpty()) {
      return report(
          Verdict.CANDIDATE_INCONCLUSIVE,
          evaluationWallMs,
          certification.cut(),
          null,
          emptyCore(),
          coverage(certification.missing()));
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
    if (!state.missing.isEmpty()) {
      return report(
          Verdict.CANDIDATE_INCONCLUSIVE,
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
        diagnosticCore(state, executionsIn(residual, state), queuesIn(residual, state)),
        coverage(List.of()));
  }

  private Certification certify(long evaluationWallMs) {
    Map<String, Long> cut = new TreeMap<>();
    Map<String, ObservationEnvelope> checkpoints = new HashMap<>();
    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    for (Map.Entry<String, TreeMap<Long, ObservationEnvelope>> entry : streams.entrySet()) {
      String instanceId = entry.getKey();
      TreeMap<Long, ObservationEnvelope> stream = entry.getValue();
      ObservationEnvelope checkpoint = null;
      for (ObservationEnvelope envelope : stream.values()) {
        if (envelope.event().payload() instanceof EventPayload.InstanceCheckpoint
            && contiguousThrough(stream, envelope.event().localSeq())) {
          checkpoint = envelope;
        }
      }
      if (checkpoint == null) {
        cut.put(instanceId, 0L);
        missing.add(new VerdictReport.MissingEvidence("MISSING_CHECKPOINT", instanceId));
        continue;
      }
      cut.put(instanceId, checkpoint.event().localSeq());
      checkpoints.put(instanceId, checkpoint);
      if (evaluationWallMs - checkpoint.receiptWallMs()
          > manifest.parameters().checkpointFreshnessMs()) {
        missing.add(new VerdictReport.MissingEvidence("STALE_CHECKPOINT", instanceId));
      }
      if (!clockQualified(instanceId, checkpoint.event().wallTimeMs())) {
        missing.add(new VerdictReport.MissingEvidence("UNQUALIFIED_CLOCK", instanceId));
      }
    }
    return new Certification(Map.copyOf(cut), Map.copyOf(checkpoints), List.copyOf(missing));
  }

  private static boolean contiguousThrough(
      TreeMap<Long, ObservationEnvelope> stream, long throughSequence) {
    for (long sequence = 1; sequence <= throughSequence; sequence++) {
      if (!stream.containsKey(sequence)) {
        return false;
      }
    }
    return true;
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
              pool.unitIds().forEach(unit -> state.unitOwners.put(unit, null));
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
      if (declared == null || !declared.equals(Set.copyOf(init.unitIds()))) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        state.initializedPools.add(pool);
      }
    } else if (payload instanceof EventPayload.ResourceWaitBegin wait) {
      state.waiting.put(
          wait.invocationId(),
          new WaitState(
              wait.invocationId(), event.instanceId(), wait.resourceId(), event.wallTimeMs()));
    } else if (payload instanceof EventPayload.ResourceAcquire acquire) {
      state.waiting.remove(acquire.invocationId());
      state.executionByInvocation.put(acquire.invocationId(), acquire.executionId());
      state.executions.put(
          acquire.executionId(),
          new ExecutionState(
              acquire.executionId(),
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
      if (execution == null || execution.blockInvocation != null) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.blockInvocation = block.invocationId();
        execution.blockRef = event.eventReference();
        execution.blockStartMs = event.wallTimeMs();
      }
    } else if (payload instanceof EventPayload.ExecutionLease lease) {
      ExecutionState execution = state.executions.get(lease.executionId());
      if (execution == null || !lease.blockRef().equals(execution.blockRef)) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.leaseRef = event.eventReference();
        execution.leaseObservedMs = event.wallTimeMs();
        execution.leaseExpiryMs = lease.leaseExpiryMs();
      }
    } else if (payload instanceof EventPayload.RpcBlockEnd end) {
      ExecutionState execution = state.executions.get(end.executionId());
      if (execution == null || !end.invocationId().equals(execution.blockInvocation)) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.blockInvocation = null;
        execution.blockRef = null;
      }
    } else if (payload instanceof EventPayload.ExecutionCancel cancel
        && !cancel.executionId().isEmpty()) {
      ExecutionState execution = state.executions.get(cancel.executionId());
      if (execution != null) {
        execution.cancelPending = true;
      }
    } else if (payload instanceof EventPayload.ExecutionEnd end) {
      ExecutionState execution = state.executions.get(end.executionId());
      if (execution != null) {
        execution.ended = true;
        execution.blockInvocation = null;
      }
    } else if (payload instanceof EventPayload.ResourceRelease release) {
      if (!release.executionId().equals(state.unitOwners.get(release.resourceInstanceId()))) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        state.unitOwners.put(release.resourceInstanceId(), null);
      }
    }
  }

  private Set<String> reduce(Reconstructed state) {
    Set<String> candidates = new TreeSet<>();
    candidates.addAll(state.blockedExecutions());
    candidates.addAll(state.waitingInvocations());
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
    long start = Long.MIN_VALUE;
    long end = Long.MAX_VALUE;
    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    for (String member : residual) {
      ExecutionState execution = state.executions.get(member);
      if (execution != null) {
        start = Math.max(start, execution.blockStartMs + epsilon);
        if (execution.leaseRef == null
            || execution.leaseObservedMs + epsilon > end
            || execution.leaseExpiryMs - epsilon < execution.leaseObservedMs + epsilon) {
          missing.add(new VerdictReport.MissingEvidence("EXPIRED_LEASE", member));
        }
        start = Math.max(start, execution.acquireStartMs + epsilon);
      } else {
        WaitState wait = state.waiting.get(member);
        start = Math.max(start, wait.startMs + epsilon);
      }
    }
    for (ObservationEnvelope checkpoint : certification.checkpoints().values()) {
      end = Math.min(end, checkpoint.event().wallTimeMs() - epsilon);
    }
    for (String member : residual) {
      ExecutionState execution = state.executions.get(member);
      if (execution != null
          && (execution.leaseRef == null
              || execution.leaseObservedMs + epsilon > end
              || execution.leaseExpiryMs - epsilon < end)) {
        missing.add(new VerdictReport.MissingEvidence("EXPIRED_LEASE", member));
      }
    }
    long duration = end >= start ? end - start : 0;
    if (duration < manifest.parameters().persistenceMs()) {
      missing.add(new VerdictReport.MissingEvidence("PERSISTENCE_TOO_SHORT", "residual"));
    }
    if (!missing.isEmpty()) {
      List<VerdictReport.MissingEvidence> sortedMissing =
          missing.stream()
              .distinct()
              .sorted(
                  java.util.Comparator.comparing(VerdictReport.MissingEvidence::code)
                      .thenComparing(VerdictReport.MissingEvidence::subject))
              .toList();
      return new PersistenceResult(null, sortedMissing);
    }
    return new PersistenceResult(
        new VerdictReport.Persistence(start, end, duration, manifest.parameters().persistenceMs()),
        List.of());
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
        total, missing.isEmpty() ? total : 0, List.of(), List.of(), List.of(), missing);
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
                      owner.leaseRef == null ? "" : owner.leaseRef);
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
      VerdictReport.Persistence persistence, List<VerdictReport.MissingEvidence> missing) {}

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
    private final Set<String> initializedPools = new HashSet<>();
    private final Map<String, WaitState> waiting = new HashMap<>();
    private final Map<String, ExecutionState> executions = new HashMap<>();
    private final Map<String, String> executionByInvocation = new HashMap<>();
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
    private final String invocationId;
    private final String unitId;
    private final String acquireRef;
    private final long acquireStartMs;
    private String blockInvocation;
    private String blockRef;
    private long blockStartMs;
    private String leaseRef;
    private long leaseObservedMs;
    private long leaseExpiryMs;
    private boolean cancelPending;
    private boolean ended;

    private ExecutionState(
        String id, String invocationId, String unitId, String acquireRef, long acquireStartMs) {
      this.id = id;
      this.invocationId = invocationId;
      this.unitId = unitId;
      this.acquireRef = acquireRef;
      this.acquireStartMs = acquireStartMs;
    }
  }

  private record WaitState(
      String invocationId, String instanceId, String resourceId, long startMs) {}
}
