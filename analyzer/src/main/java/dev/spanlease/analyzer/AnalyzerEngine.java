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
  private final long historyLimit;
  private long historySize;
  private boolean historyLimitExceeded;
  private final Map<String, TreeMap<Long, ObservationEnvelope>> streams = new LinkedHashMap<>();
  private final Map<String, String> sentIdentities = new HashMap<>();
  private final Map<String, String> arrivedIdentities = new HashMap<>();
  private final Map<String, String> acquiredInvocations = new HashMap<>();
  private final Map<String, String> acquiredExecutions = new HashMap<>();
  private final Set<String> invalidInstances = new TreeSet<>();

  public AnalyzerEngine(RunManifest manifest) {
    this(manifest, 1_000_000);
  }

  /** Finite-history budget; exhausting it permanently disables confirmation for this run. */
  public AnalyzerEngine(RunManifest manifest, long historyLimit) {
    if (historyLimit <= 0) throw new IllegalArgumentException("positive history limit required");
    this.historyLimit = historyLimit;
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
    if (!stream.containsKey(event.localSeq())) {
      if (historySize >= historyLimit) {
        historyLimitExceeded = true;
        return;
      }
      historySize++;
    }
    ObservationEnvelope existing = stream.putIfAbsent(event.localSeq(), envelope);
    if (existing != null && !existing.event().equals(event)) {
      invalidInstances.add(event.instanceId());
    }
    if (existing == null) {
      if (event.payload() instanceof EventPayload.RpcInvocationSent sent)
        claimIdentity(sentIdentities, sent.invocationId(), event);
      else if (event.payload() instanceof EventPayload.RpcInvocationArrived arrived)
        claimIdentity(arrivedIdentities, arrived.invocationId(), event);
      else if (event.payload() instanceof EventPayload.ResourceAcquire acquire) {
        claimIdentity(acquiredInvocations, acquire.invocationId(), event);
        claimIdentity(acquiredExecutions, acquire.executionId(), event);
      }
    }
  }

  private void claimIdentity(Map<String, String> identities, String id, ObservationEvent event) {
    String previous = identities.putIfAbsent(id, event.eventReference());
    if (previous != null && !previous.equals(event.eventReference())) {
      invalidInstances.add(event.instanceId());
      invalidInstances.add(EventReference.parse(previous).orElseThrow().instanceId());
    }
  }

  public VerdictReport evaluate(long evaluationWallMs) {
    if (historyLimitExceeded) {
      return report(
          Verdict.CANDIDATE_INCONCLUSIVE,
          evaluationWallMs,
          Map.of(),
          null,
          emptyCore(),
          coverage(List.of(new VerdictReport.MissingEvidence("HISTORY_LIMIT", manifest.runId()))));
    }
    if (manifest.instances().isEmpty() || manifest.pools().isEmpty()) {
      return report(
          Verdict.INSUFFICIENT_OBSERVABILITY,
          evaluationWallMs,
          Map.of(),
          null,
          emptyCore(),
          coverage(List.of(new VerdictReport.MissingEvidence("EMPTY_SCOPE", manifest.runId()))));
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
      List<VerdictReport.MissingEvidence> leases = new ArrayList<>();
      for (String id : state.blockedExecutions()) {
        ExecutionState execution = state.executions.get(id);
        String instance = EventReference.parse(execution.acquireRef).orElseThrow().instanceId();
        long endpoint =
            certification.checkpoints().get(instance).event().wallTimeMs()
                - manifest.parameters().epsilonMs();
        if (execution.leaseCovering(endpoint, manifest.parameters().epsilonMs()) == null) {
          leases.add(new VerdictReport.MissingEvidence("EXPIRED_LEASE", id));
        }
      }
      if (!leases.isEmpty()) {
        return report(
            Verdict.CANDIDATE_INCONCLUSIVE,
            evaluationWallMs,
            cutResult.cut(),
            null,
            diagnosticCore(state, state.blockedExecutions(), state.waitingInvocations()),
            coverage(leases));
      }
      return report(
          Verdict.CONFIRMED_NO_DEADLOCK,
          evaluationWallMs,
          cutResult.cut(),
          null,
          emptyCore(),
          coverage(List.of()));
    }

    PersistenceResult persistence =
        qualifyPersistence(state, residual, certification, cutResult.cut());
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
            executionsIn(persistence.members(), state),
            queuesIn(persistence.members(), state),
            persistence.persistence().lastMs()),
        coverage(List.of()));
  }

  private Certification certify(long evaluationWallMs) {
    Map<String, Long> cut = new TreeMap<>();
    Map<String, ObservationEnvelope> checkpoints = new HashMap<>();
    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    for (Map.Entry<String, TreeMap<Long, ObservationEnvelope>> entry : streams.entrySet()) {
      String instanceId = entry.getKey();
      if (manifest.instances().stream()
          .anyMatch(
              i ->
                  i.instanceId().equals(instanceId)
                      && i.epsilonMs() > manifest.parameters().epsilonMs())) {
        missing.add(new VerdictReport.MissingEvidence("UNQUALIFIED_CLOCK", instanceId));
      }
      TreeMap<Long, ObservationEnvelope> stream = entry.getValue();
      long prefix = contiguousPrefix(stream);
      if (!stream.isEmpty() && stream.lastKey() > prefix) {
        missing.add(
            new VerdictReport.MissingEvidence("SEQUENCE_GAP", instanceId + "#" + (prefix + 1)));
      }
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
      if (checkpoint.event().wallTimeMs() > evaluationWallMs + manifest.parameters().epsilonMs()) {
        missing.add(new VerdictReport.MissingEvidence("FUTURE_CHECKPOINT", instanceId));
      }
      if (evaluationWallMs - checkpoint.receiptWallMs()
          > manifest.parameters().checkpointFreshnessMs()) {
        missing.add(new VerdictReport.MissingEvidence("STALE_CHECKPOINT", instanceId));
      }
      if (evaluationWallMs - checkpoint.event().wallTimeMs()
          > manifest.parameters().checkpointFreshnessMs() + manifest.parameters().epsilonMs()) {
        missing.add(new VerdictReport.MissingEvidence("STALE_CHECKPOINT", instanceId));
      }
      if (!clockQualified(instanceId, checkpoint.event().wallTimeMs())
          || stream.headMap(checkpoint.event().localSeq(), true).values().stream()
              .anyMatch(e -> !clockQualified(instanceId, e.event().wallTimeMs()))) {
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
          if (cut.getOrDefault(parsed.instanceId(), 0L) >= parsed.sequence()
              && !validParent(
                  envelope.event(),
                  streams.get(parsed.instanceId()).get(parsed.sequence()).event())) {
            missing.add(
                new VerdictReport.MissingEvidence(
                    "INVALID_CAUSAL_PARENT", envelope.event().eventReference()));
          }
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

  private boolean validParent(ObservationEvent child, ObservationEvent parent) {
    if (child.payload() instanceof EventPayload.RpcInvocationArrived arrival) {
      String binding =
          manifest.instances().stream()
              .filter(i -> i.instanceId().equals(child.instanceId()))
              .findFirst()
              .orElseThrow()
              .serviceBinding();
      return parent.payload() instanceof EventPayload.RpcInvocationSent sent
          && sent.invocationId().equals(arrival.invocationId())
          && (sent.targetService().equals(binding)
              || sent.targetService().equals(child.instanceId()));
    }
    if (child.payload() instanceof EventPayload.RpcBlockEnd end) {
      return end.completionKind() == EventPayload.CompletionKind.RESPONSE
          && parent.payload() instanceof EventPayload.RpcResponseSent response
          && response.invocationId().equals(end.invocationId());
    }
    return false;
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
      if (execution.blockInvocation != null && execution.cancelPending) {
        // Cancellation while the helper is still blocked is unresolved eligibility (design §5.3).
        state.missing.add(new VerdictReport.MissingEvidence("CANCEL_PENDING", execution.id));
      }
      if (execution.blockInvocation != null && !execution.cancelPending) {
        if (!state.arrivedInvocations.contains(execution.blockInvocation)
            || (!state.waiting.containsKey(execution.blockInvocation)
                && !state.executionByInvocation.containsKey(execution.blockInvocation))) {
          state.missing.add(
              new VerdictReport.MissingEvidence("UNKNOWN_COUNTERPART", execution.blockInvocation));
        }
      }
    }
    return state;
  }

  private void apply(Reconstructed state, ObservationEvent event) {
    EventPayload payload = event.payload();
    String executionId =
        switch (payload) {
          case EventPayload.RpcBlockBegin e -> e.executionId();
          case EventPayload.RpcBlockEnd e -> e.executionId();
          case EventPayload.ExecutionLease e -> e.executionId();
          case EventPayload.ExecutionEnd e -> e.executionId();
          case EventPayload.ExecutionCancel e -> e.executionId();
          case EventPayload.ResourceRelease e -> e.executionId();
          case EventPayload.RpcResponseSent e -> e.executionId();
          case EventPayload.RpcInvocationSent e -> e.callerExecutionId();
          default -> "";
        };
    if (!executionId.isEmpty()) {
      ExecutionState local = state.executions.get(executionId);
      if (local == null
          || !EventReference.parse(local.acquireRef)
              .orElseThrow()
              .instanceId()
              .equals(event.instanceId())) {
        invalid(state, event);
        return;
      }
    }
    if (payload instanceof EventPayload.RpcInvocationSent sent) {
      if (!state.sentInvocations.add(sent.invocationId())) invalid(state, event);
      if (!sent.callerExecutionId().isEmpty()
          && !sent.invocationId()
              .equals(state.executions.get(sent.callerExecutionId()).blockInvocation))
        invalid(state, event);
    } else if (payload instanceof EventPayload.RpcInvocationArrived arrived) {
      if (!state.arrivedInvocations.add(arrived.invocationId())) invalid(state, event);
    } else if (payload instanceof EventPayload.ResourceInit init) {
      String pool = event.instanceId() + ":" + init.resourceId();
      Set<String> declared = state.poolUnits.get(pool);
      if (declared == null || !declared.equals(Set.copyOf(init.unitIds()))) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else if (!state.initializedPools.add(pool)) {
        invalid(state, event);
      }
    } else if (payload instanceof EventPayload.ResourceWaitBegin wait) {
      if (!state.poolUnits.containsKey(event.instanceId() + ":" + wait.resourceId())
          || state.waiting.containsKey(wait.invocationId())
          || state.executionByInvocation.containsKey(wait.invocationId())) invalid(state, event);
      state.waiting.put(
          wait.invocationId(),
          new WaitState(
              wait.invocationId(), event.instanceId(), wait.resourceId(), event.wallTimeMs()));
    } else if (payload instanceof EventPayload.ResourceAcquire acquire) {
      if (state.executions.containsKey(acquire.executionId())
          || state.executionByInvocation.containsKey(acquire.invocationId())
          || manifest.pools().stream()
              .noneMatch(
                  p ->
                      p.instanceId().equals(event.instanceId())
                          && p.unitIds().contains(acquire.resourceInstanceId())))
        invalid(state, event);
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
      if (execution == null || execution.blockInvocation != null || execution.ended) {
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
      if (execution == null
          || !lease.blockRef().equals(execution.blockRef)
          || !lease.startRef().equals(execution.acquireRef)
          || !lease.invocationId().equals(execution.blockInvocation)) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else {
        execution.leases.add(
            new LeaseState(event.eventReference(), event.wallTimeMs(), lease.leaseExpiryMs()));
      }
    } else if (payload instanceof EventPayload.RpcBlockEnd end) {
      ExecutionState execution = state.executions.get(end.executionId());
      if (execution == null || !end.invocationId().equals(execution.blockInvocation)) {
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
        if (execution != null) {
          execution.cancelPending = true;
        }
      }
    } else if (payload instanceof EventPayload.ExecutionEnd end) {
      ExecutionState execution = state.executions.get(end.executionId());
      if (execution != null) {
        execution.ended = true;
        execution.blockInvocation = null;
        execution.leases.clear();
      }
    } else if (payload instanceof EventPayload.ResourceRelease release) {
      if (!release.executionId().equals(state.unitOwners.get(release.resourceInstanceId()))) {
        state.missing.add(
            new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
      } else if (!state.executions.get(release.executionId()).ended) {
        invalid(state, event);
      } else {
        state.unitOwners.put(release.resourceInstanceId(), null);
      }
    }
  }

  private static void invalid(Reconstructed state, ObservationEvent event) {
    state.missing.add(new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference()));
  }

  private Set<String> reduce(Reconstructed state) {
    Set<String> candidates = new TreeSet<>();
    candidates.addAll(state.blockedExecutions());
    candidates.addAll(state.waitingInvocations());
    return pruneClosed(state, candidates);
  }

  /** Also used for temporal pruning: removal means unavailable to this witness, not progress. */
  private Set<String> pruneClosed(Reconstructed state, Set<String> initial) {
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
      Reconstructed state,
      Set<String> residual,
      Certification certification,
      Map<String, Long> cut) {
    long epsilon = manifest.parameters().epsilonMs();
    long tau = manifest.parameters().persistenceMs();
    Map<String, Long> starts = new TreeMap<>();
    Map<String, Long> ends = new TreeMap<>();
    TreeSet<Long> endpoints = new TreeSet<>(java.util.Comparator.reverseOrder());
    for (String member : residual) {
      ExecutionState execution = state.executions.get(member);
      String instance;
      long start;
      if (execution != null) {
        instance = EventReference.parse(execution.acquireRef).orElseThrow().instanceId();
        start = Math.max(execution.acquireStartMs, execution.blockStartMs) + epsilon;
      } else {
        WaitState wait = state.waiting.get(member);
        instance = wait.instanceId;
        start = wait.startMs + epsilon;
      }
      long end = certification.checkpoints().get(instance).event().wallTimeMs() - epsilon;
      // A causal retraction does not erase certified later closing observations. Never extend
      // the cut's current assertion through a later unblock, cancellation or ownership change.
      long certified = certification.cut().get(instance);
      for (ObservationEnvelope envelope :
          streams.get(instance).subMap(cut.get(instance), false, certified, true).values()) {
        if (closesAssertion(envelope.event().payload(), execution, member)) {
          // Terminal intervals are half open; endpoints use the contract's millisecond grid.
          end = Math.min(end, envelope.event().wallTimeMs() - epsilon - 1);
          break;
        }
        if (execution != null
            && envelope.event().payload() instanceof EventPayload.ExecutionLease lease
            && lease.executionId().equals(member)
            && lease.blockRef().equals(execution.blockRef)
            && lease.startRef().equals(execution.acquireRef)
            && lease.invocationId().equals(execution.blockInvocation)) {
          execution.leases.add(
              new LeaseState(
                  envelope.event().eventReference(),
                  envelope.event().wallTimeMs(),
                  lease.leaseExpiryMs()));
        }
      }
      starts.put(member, start);
      ends.put(member, end);
      endpoints.add(start + tau);
      endpoints.add(end);
      if (execution != null) {
        for (LeaseState lease : execution.leases) {
          endpoints.add(lease.observedMs() + epsilon);
          endpoints.add(Math.min(end, lease.expiryMs() - epsilon));
        }
      }
    }

    for (long endpoint : endpoints) {
      Set<String> eligible = new TreeSet<>();
      for (String member : residual) {
        ExecutionState execution = state.executions.get(member);
        if (starts.get(member) <= endpoint - tau
            && endpoint <= ends.get(member)
            && (execution == null || execution.leaseCovering(endpoint, epsilon) != null)) {
          eligible.add(member);
        }
      }
      Set<String> witness = pruneClosed(state, eligible);
      if (!witness.isEmpty()) {
        return new PersistenceResult(
            new VerdictReport.Persistence(endpoint - tau, endpoint, tau, tau), witness, List.of());
      }
    }

    List<VerdictReport.MissingEvidence> missing = new ArrayList<>();
    for (String member : new TreeSet<>(residual)) {
      ExecutionState execution = state.executions.get(member);
      long end = ends.get(member);
      boolean enoughHistory = starts.get(member) <= end - tau;
      if (!enoughHistory) {
        missing.add(new VerdictReport.MissingEvidence("PERSISTENCE_TOO_SHORT", member));
      }
      if (execution != null
          && execution.leases.stream()
              .noneMatch(
                  lease ->
                      Math.max(starts.get(member) + tau, lease.observedMs() + epsilon)
                          <= Math.min(end, lease.expiryMs() - epsilon))) {
        missing.add(new VerdictReport.MissingEvidence("EXPIRED_LEASE", member));
      }
    }
    if (missing.isEmpty()) {
      missing.add(new VerdictReport.MissingEvidence("NO_PERSISTENT_CLOSED_SET", "residual"));
    }
    return new PersistenceResult(null, Set.of(), List.copyOf(missing));
  }

  private static boolean closesAssertion(
      EventPayload payload, ExecutionState execution, String member) {
    if (execution == null) {
      return (payload instanceof EventPayload.ResourceAcquire acquire
              && acquire.invocationId().equals(member))
          || (payload instanceof EventPayload.ExecutionCancel cancel
              && cancel.invocationId().equals(member));
    }
    return (payload instanceof EventPayload.RpcBlockEnd end && end.executionId().equals(member))
        || (payload instanceof EventPayload.ExecutionCancel cancel
            && cancel.executionId().equals(member))
        || (payload instanceof EventPayload.ExecutionEnd end && end.executionId().equals(member))
        || (payload instanceof EventPayload.ResourceRelease release
            && release.executionId().equals(member));
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
                .map(
                    envelope ->
                        Math.max(
                            0, envelope.event().wallTimeMs() - manifest.parameters().epsilonMs()))
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
        observedCoverage(completeCut, coverage.missing()));
  }

  private VerdictReport.Coverage observedCoverage(
      Map<String, Long> cut, List<VerdictReport.MissingEvidence> missing) {
    List<String> unknown = new ArrayList<>();
    int total = 0;
    for (RunManifest.Pool pool : manifest.pools()) {
      total += pool.unitIds().size();
      boolean initialized =
          streams
              .get(pool.instanceId())
              .headMap(cut.getOrDefault(pool.instanceId(), 0L), true)
              .values()
              .stream()
              .anyMatch(
                  e ->
                      e.event().payload() instanceof EventPayload.ResourceInit init
                          && init.resourceId().equals(pool.resourceId())
                          && Set.copyOf(init.unitIds()).equals(Set.copyOf(pool.unitIds())));
      if (!initialized || invalidInstances.contains(pool.instanceId()))
        unknown.addAll(pool.unitIds());
    }
    List<VerdictReport.FrontierGap> gaps = new ArrayList<>();
    for (var stream : streams.entrySet()) {
      long expected = 1;
      for (long received : stream.getValue().keySet()) {
        if (received > expected)
          gaps.add(new VerdictReport.FrontierGap(stream.getKey(), expected, received - 1));
        expected = received + 1;
      }
    }
    List<String> unmatched =
        missing.stream()
            .filter(m -> m.code().equals("UNKNOWN_COUNTERPART"))
            .map(VerdictReport.MissingEvidence::subject)
            .distinct()
            .sorted()
            .toList();
    return new VerdictReport.Coverage(
        total, total - unknown.size(), unknown, gaps, unmatched, missing);
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
    return diagnosticCore(state, executions, queues, null);
  }

  private VerdictReport.Core diagnosticCore(
      Reconstructed state, Set<String> executions, Set<String> queues, Long endpoint) {
    List<VerdictReport.Execution> executionReports =
        executions.stream()
            .sorted()
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
            .sorted()
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
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry -> {
                  ExecutionState owner = state.executions.get(entry.getValue());
                  return new VerdictReport.Unit(
                      entry.getKey(),
                      owner.id,
                      owner.acquireRef,
                      endpoint == null
                          ? (owner.leases.isEmpty() ? "" : owner.leases.getLast().reference())
                          : owner
                              .leaseCovering(endpoint, manifest.parameters().epsilonMs())
                              .reference());
                })
            .toList();
    List<VerdictReport.Edge> edges = new ArrayList<>();
    for (VerdictReport.Unit unit : units) {
      edges.add(new VerdictReport.Edge(unit.id(), unit.ownerExecutionId(), "own"));
    }
    for (String executionId : new TreeSet<>(executions)) {
      ExecutionState execution = state.executions.get(executionId);
      String counterpart = state.executionByInvocation.get(execution.blockInvocation);
      edges.add(
          new VerdictReport.Edge(
              executionId, counterpart == null ? execution.blockInvocation : counterpart, "wait"));
    }
    for (String queue : new TreeSet<>(queues)) {
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
      Set<String> members,
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
    private final Set<String> sentInvocations = new HashSet<>();
    private final Set<String> arrivedInvocations = new HashSet<>();
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
    private final List<LeaseState> leases = new ArrayList<>();
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
