package dev.spanlease.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Executable pre-G0 model of conservative verdict selection.
 *
 * <p>This class is deliberately test-only. It demonstrates the proposed predicate and verdict
 * guards without defining the pending v2 wire/report contract or sharing an implementation with the
 * future production reducer.
 */
final class ModelDetectorMvp {
  private ModelDetectorMvp() {}

  static Report evaluate(Input input) {
    if (!input.supported()) {
      return Report.insufficient("UNSUPPORTED_DEPLOYMENT");
    }

    List<String> missing = new ArrayList<>();
    if (!input.completePrefix()) {
      missing.add("INCOMPLETE_PREFIX");
    }
    if (!input.clockQualified()) {
      missing.add("CLOCK_UNQUALIFIED");
    }
    if (!input.checkpointFresh()) {
      missing.add("STALE_CHECKPOINT");
    }
    if (!input.snapshot().blockedExecutions().isEmpty() && !input.leasesEligible()) {
      missing.add("INELIGIBLE_BLOCK_LEASE");
    }
    if (!missing.isEmpty()) {
      return Report.inconclusive(missing);
    }

    Set<String> residual = TruthClosureOracle.maximalClosedResidual(input.snapshot());
    if (residual.isEmpty()) {
      return new Report(Verdict.CONFIRMED_NO_DEADLOCK, Set.of(), Optional.empty(), List.of());
    }

    Long latestEndpoint = null;
    for (Set<String> subset : TruthClosureOracle.closedSubsets(input.snapshot())) {
      Optional<Interval> overlap = coveredInterval(input, subset);
      if (overlap.isPresent()
          && overlap.orElseThrow().durationMs() >= input.requiredPersistenceMs()) {
        long endpoint = overlap.orElseThrow().endExclusiveMs();
        latestEndpoint = latestEndpoint == null ? endpoint : Math.max(latestEndpoint, endpoint);
      }
    }

    if (latestEndpoint != null) {
      long start = Math.subtractExact(latestEndpoint, input.requiredPersistenceMs());
      Set<String> evidence = new TreeSet<>();
      for (Set<String> subset : TruthClosureOracle.closedSubsets(input.snapshot())) {
        if (covers(input, subset, start, latestEndpoint)) {
          evidence.addAll(subset);
        }
      }
      return new Report(
          Verdict.CONFIRMED_DEADLOCK,
          evidence,
          Optional.of(new Interval(start, latestEndpoint)),
          List.of());
    }

    for (String assertion : requiredAssertions(input.snapshot(), residual)) {
      if (!input.persistenceByAssertion().containsKey(assertion)) {
        missing.add("MISSING_PERSISTENCE:" + assertion);
      }
    }
    if (missing.isEmpty()) {
      missing.add("INSUFFICIENT_PERSISTENCE");
    } else {
      missing.sort(String::compareTo);
    }
    return new Report(
        Verdict.CANDIDATE_INCONCLUSIVE, residual, Optional.empty(), List.copyOf(missing));
  }

  static Set<String> requiredAssertions(
      TruthClosureOracle.Snapshot snapshot, Set<String> residual) {
    Set<String> assertions = new TreeSet<>();
    for (String member : residual) {
      if (snapshot.blockedExecutions().contains(member)) {
        assertions.add("block:" + member);
        assertions.add("dependency:" + member + "->" + snapshot.blockedCounterparts().get(member));
        continue;
      }

      assertions.add("queue:" + member);
      for (String unit : snapshot.eligibleUnitsByRequest().get(member)) {
        assertions.add("owner:" + unit + "->" + snapshot.ownerByUnit().get(unit));
      }
    }
    return Set.copyOf(assertions);
  }

  private static Optional<Interval> coveredInterval(Input input, Set<String> subset) {
    List<Interval> intervals = new ArrayList<>();
    for (String assertion : requiredAssertions(input.snapshot(), subset)) {
      Interval interval = input.persistenceByAssertion().get(assertion);
      if (interval == null) {
        return Optional.empty();
      }
      intervals.add(interval);
    }
    return Optional.of(intersect(intervals));
  }

  private static boolean covers(Input input, Set<String> subset, long start, long end) {
    Optional<Interval> interval = coveredInterval(input, subset);
    return interval.isPresent()
        && interval.orElseThrow().startInclusiveMs() <= start
        && interval.orElseThrow().endExclusiveMs() >= end;
  }

  private static Interval intersect(List<Interval> intervals) {
    if (intervals.isEmpty()) {
      throw new IllegalArgumentException("a closed residual must have persistence assertions");
    }
    long start = Long.MIN_VALUE;
    long end = Long.MAX_VALUE;
    for (Interval interval : intervals) {
      start = Math.max(start, interval.startInclusiveMs());
      end = Math.min(end, interval.endExclusiveMs());
    }
    return new Interval(start, Math.max(start, end));
  }

  enum Verdict {
    CONFIRMED_DEADLOCK,
    CONFIRMED_NO_DEADLOCK,
    CANDIDATE_INCONCLUSIVE,
    INSUFFICIENT_OBSERVABILITY
  }

  record Interval(long startInclusiveMs, long endExclusiveMs) {
    Interval {
      if (endExclusiveMs < startInclusiveMs) {
        throw new IllegalArgumentException("interval end precedes start");
      }
    }

    long durationMs() {
      return Math.subtractExact(endExclusiveMs, startInclusiveMs);
    }
  }

  record Input(
      boolean supported,
      boolean completePrefix,
      boolean clockQualified,
      boolean checkpointFresh,
      boolean leasesEligible,
      long requiredPersistenceMs,
      TruthClosureOracle.Snapshot snapshot,
      Map<String, Interval> persistenceByAssertion) {
    Input {
      if (requiredPersistenceMs <= 0) {
        throw new IllegalArgumentException("required persistence must be positive");
      }
      persistenceByAssertion = Map.copyOf(persistenceByAssertion);
    }
  }

  record Report(
      Verdict verdict,
      Set<String> evidenceSet,
      Optional<Interval> evidenceInterval,
      List<String> missingEvidence) {
    Report {
      evidenceSet = Set.copyOf(evidenceSet);
      missingEvidence = List.copyOf(missingEvidence);
    }

    private static Report inconclusive(List<String> missingEvidence) {
      return new Report(
          Verdict.CANDIDATE_INCONCLUSIVE, Set.of(), Optional.empty(), missingEvidence);
    }

    private static Report insufficient(String reason) {
      return new Report(
          Verdict.INSUFFICIENT_OBSERVABILITY, Set.of(), Optional.empty(), List.of(reason));
    }
  }
}
