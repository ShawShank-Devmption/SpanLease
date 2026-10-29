package dev.spanlease.common;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic schema-v2 analysis report. */
public record VerdictReport(
    int schemaVersion,
    String runId,
    String manifestHash,
    Verdict verdict,
    long evaluatedAtWallMs,
    Query query,
    Persistence persistence,
    Core core,
    Coverage coverage) {
  public VerdictReport {
    if (schemaVersion != ObservationEvent.SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported report schema version");
    }
    runId = ContractValidation.id(runId, "runId");
    manifestHash = ContractValidation.id(manifestHash, "manifestHash");
    Objects.requireNonNull(verdict, "verdict");
    ContractValidation.nonnegative(evaluatedAtWallMs, "evaluatedAtWallMs");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(coverage, "coverage");
    if (verdict == Verdict.CONFIRMED_DEADLOCK && persistence == null) {
      throw new IllegalArgumentException("positive verdict requires persistence");
    }
    if (verdict == Verdict.CONFIRMED_NO_DEADLOCK && persistence != null) {
      throw new IllegalArgumentException("negative verdict cannot carry persistence");
    }
  }

  public record Query(
      List<String> scopeInstances, Map<String, Long> cut, Long certifiedThroughWallMs) {
    public Query {
      scopeInstances = ContractValidation.ids(scopeInstances, "scopeInstances");
      cut = Map.copyOf(cut);
      if (!cut.keySet().equals(SetSupport.copyOf(scopeInstances))) {
        throw new IllegalArgumentException("cut must name every scoped instance exactly once");
      }
      if (cut.values().stream().anyMatch(value -> value == null || value < 0)) {
        throw new IllegalArgumentException("cut positions must be nonnegative");
      }
      if (certifiedThroughWallMs != null && certifiedThroughWallMs < 0) {
        throw new IllegalArgumentException("certified horizon must be nonnegative");
      }
    }
  }

  public record Persistence(long firstMs, long lastMs, long narrowedSpanMs, long tauMs) {
    public Persistence {
      ContractValidation.nonnegative(firstMs, "firstMs");
      if (lastMs < firstMs || narrowedSpanMs != lastMs - firstMs) {
        throw new IllegalArgumentException("invalid persistence interval");
      }
      ContractValidation.positive(tauMs, "tauMs");
      if (narrowedSpanMs < tauMs) {
        throw new IllegalArgumentException("persistence interval is shorter than tau");
      }
    }
  }

  public record Core(
      List<Execution> executions,
      List<QueueEntry> queueEntries,
      List<Unit> units,
      List<Edge> edges) {
    public Core {
      executions = sorted(executions, Execution::id);
      queueEntries = sorted(queueEntries, QueueEntry::invocationId);
      units = sorted(units, Unit::id);
      edges =
          edges.stream()
              .sorted(
                  Comparator.comparing(Edge::from)
                      .thenComparing(Edge::to)
                      .thenComparing(Edge::kind))
              .toList();
    }

    private static <T> List<T> sorted(List<T> values, java.util.function.Function<T, String> id) {
      return values.stream().sorted(Comparator.comparing(id)).toList();
    }
  }

  public record Execution(
      String id, String invocationId, String blockedOnInvocation, String blockRef) {
    public Execution {
      id = ContractValidation.id(id, "execution.id");
      invocationId = ContractValidation.id(invocationId, "execution.invocationId");
      blockedOnInvocation = ContractValidation.id(blockedOnInvocation, "blockedOnInvocation");
      blockRef = ContractValidation.id(blockRef, "blockRef");
    }
  }

  public record QueueEntry(String invocationId, String instanceId, String resourceId) {
    public QueueEntry {
      invocationId = ContractValidation.id(invocationId, "queue.invocationId");
      instanceId = ContractValidation.id(instanceId, "queue.instanceId");
      resourceId = ContractValidation.id(resourceId, "queue.resourceId");
    }
  }

  public record Unit(String id, String ownerExecutionId, String acquireRef, String leaseRef) {
    public Unit {
      id = ContractValidation.id(id, "unit.id");
      ownerExecutionId = ContractValidation.id(ownerExecutionId, "ownerExecutionId");
      acquireRef = ContractValidation.id(acquireRef, "acquireRef");
      leaseRef = ContractValidation.optionalId(leaseRef, "leaseRef");
    }
  }

  public record Edge(String from, String to, String kind) {
    public Edge {
      from = ContractValidation.id(from, "edge.from");
      to = ContractValidation.id(to, "edge.to");
      if (!List.of("wait", "queue-wait", "own").contains(kind)) {
        throw new IllegalArgumentException("unsupported edge kind");
      }
    }
  }

  public record Coverage(
      int unitsTotal,
      int unitsWithObservedState,
      List<String> unaccountedUnits,
      List<FrontierGap> frontierGaps,
      List<String> unmatchedInvocations,
      List<MissingEvidence> missing) {
    public Coverage {
      if (unitsTotal < 0 || unitsWithObservedState < 0 || unitsWithObservedState > unitsTotal) {
        throw new IllegalArgumentException("invalid coverage unit counts");
      }
      unaccountedUnits = ContractValidation.ids(unaccountedUnits, "unaccountedUnits");
      frontierGaps =
          frontierGaps.stream()
              .sorted(
                  Comparator.comparing(FrontierGap::instanceId)
                      .thenComparingLong(FrontierGap::fromSeq))
              .toList();
      unmatchedInvocations = ContractValidation.ids(unmatchedInvocations, "unmatchedInvocations");
      missing =
          missing.stream()
              .sorted(
                  Comparator.comparing(MissingEvidence::code)
                      .thenComparing(MissingEvidence::subject))
              .toList();
    }
  }

  public record FrontierGap(String instanceId, long fromSeq, long toSeq) {
    public FrontierGap {
      instanceId = ContractValidation.id(instanceId, "gap.instanceId");
      ContractValidation.positive(fromSeq, "gap.fromSeq");
      if (toSeq < fromSeq) {
        throw new IllegalArgumentException("gap end precedes start");
      }
    }
  }

  public record MissingEvidence(String code, String subject) {
    public MissingEvidence {
      code = ContractValidation.id(code, "missing.code");
      subject = ContractValidation.id(subject, "missing.subject");
    }
  }

  private static final class SetSupport {
    private SetSupport() {}

    private static java.util.Set<String> copyOf(List<String> values) {
      return java.util.Set.copyOf(values);
    }
  }
}
