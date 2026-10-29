package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ModelDetectorMvpTest {
  private static final long TAU_MS = 2_000;

  @Test
  void persistentClosedRingConfirmsWithExactEvidence() {
    TruthClosureOracle.Snapshot snapshot = closedRing();
    Map<String, ModelDetectorMvp.Interval> persistence =
        ModelDetectorMvp.requiredAssertions(snapshot, Set.of("a0", "b0", "c0", "a1")).stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    UnaryOperator.identity(), ignored -> interval(100, 2_200)));

    ModelDetectorMvp.Report report =
        ModelDetectorMvp.evaluate(completeInput(snapshot, persistence));

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.evidenceSet()).containsExactlyInAnyOrder("a0", "b0", "c0", "a1");
    assertThat(report.evidenceInterval()).contains(interval(200, 2_200));
    assertThat(report.missingEvidence()).isEmpty();
  }

  @Test
  void fullyObservedSpareCapacityConfirmsNoDeadlock() {
    TruthClosureOracle.Snapshot snapshot =
        new TruthClosureOracle.Snapshot(
            Set.of("a0", "b0", "c0"),
            Set.of("a1"),
            Map.of("a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0", "A1")),
            Map.of("A0", "a0", "B0", "b0", "C0", "c0"));

    ModelDetectorMvp.Report report = ModelDetectorMvp.evaluate(completeInput(snapshot, Map.of()));

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.CONFIRMED_NO_DEADLOCK);
    assertThat(report.evidenceSet()).isEmpty();
    assertThat(report.evidenceInterval()).isEmpty();
  }

  @Test
  void shortPersistenceRemainsInconclusive() {
    TruthClosureOracle.Snapshot snapshot = closedRing();
    Map<String, ModelDetectorMvp.Interval> persistence =
        ModelDetectorMvp.requiredAssertions(snapshot, Set.of("a0", "b0", "c0", "a1")).stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    UnaryOperator.identity(), ignored -> interval(1_000, 2_500)));

    ModelDetectorMvp.Report report =
        ModelDetectorMvp.evaluate(completeInput(snapshot, persistence));

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.evidenceSet()).containsExactlyInAnyOrder("a0", "b0", "c0", "a1");
    assertThat(report.missingEvidence()).containsExactly("INSUFFICIENT_PERSISTENCE");
  }

  @Test
  void newerUpstreamDependentDoesNotResetPersistentCycle() {
    TruthClosureOracle.Snapshot snapshot =
        new TruthClosureOracle.Snapshot(
            Set.of("u0", "a0", "b0", "c0"),
            Set.of("a1", "a2"),
            Map.of("u0", "a2", "a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0"), "a2", Set.of("A0")),
            Map.of("U0", "u0", "A0", "a0", "B0", "b0", "C0", "c0"));
    Set<String> oldCycle = Set.of("a0", "b0", "c0", "a1");
    Set<String> fullResidual = Set.of("u0", "a0", "b0", "c0", "a1", "a2");
    Map<String, ModelDetectorMvp.Interval> persistence = new java.util.HashMap<>();
    ModelDetectorMvp.requiredAssertions(snapshot, oldCycle)
        .forEach(assertion -> persistence.put(assertion, interval(100, 3_000)));
    ModelDetectorMvp.requiredAssertions(snapshot, fullResidual).stream()
        .filter(assertion -> !persistence.containsKey(assertion))
        .forEach(assertion -> persistence.put(assertion, interval(2_500, 3_000)));

    ModelDetectorMvp.Report report =
        ModelDetectorMvp.evaluate(completeInput(snapshot, persistence));

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.evidenceSet()).containsExactlyInAnyOrderElementsOf(oldCycle);
    assertThat(report.evidenceInterval()).contains(interval(1_000, 3_000));
  }

  @Test
  void incompletePrefixPreventsEitherConfirmation() {
    ModelDetectorMvp.Input input =
        new ModelDetectorMvp.Input(true, true, true, true, true, TAU_MS, closedRing(), Map.of());
    input =
        new ModelDetectorMvp.Input(
            input.supported(),
            false,
            input.clockQualified(),
            input.checkpointFresh(),
            input.leasesEligible(),
            input.requiredPersistenceMs(),
            input.snapshot(),
            input.persistenceByAssertion());

    ModelDetectorMvp.Report report = ModelDetectorMvp.evaluate(input);

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.missingEvidence()).containsExactly("INCOMPLETE_PREFIX");
  }

  @Test
  void expiredBlockLeasePreventsEitherConfirmation() {
    ModelDetectorMvp.Input input =
        new ModelDetectorMvp.Input(true, true, true, true, false, TAU_MS, closedRing(), Map.of());

    ModelDetectorMvp.Report report = ModelDetectorMvp.evaluate(input);

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.missingEvidence()).containsExactly("INELIGIBLE_BLOCK_LEASE");
  }

  @Test
  void unsupportedDeploymentIsInsufficientlyObservable() {
    ModelDetectorMvp.Input input =
        new ModelDetectorMvp.Input(
            false, false, false, false, false, TAU_MS, closedRing(), Map.of());

    ModelDetectorMvp.Report report = ModelDetectorMvp.evaluate(input);

    assertThat(report.verdict()).isEqualTo(ModelDetectorMvp.Verdict.INSUFFICIENT_OBSERVABILITY);
    assertThat(report.missingEvidence()).containsExactly("UNSUPPORTED_DEPLOYMENT");
  }

  private static ModelDetectorMvp.Input completeInput(
      TruthClosureOracle.Snapshot snapshot, Map<String, ModelDetectorMvp.Interval> persistence) {
    return new ModelDetectorMvp.Input(true, true, true, true, true, TAU_MS, snapshot, persistence);
  }

  private static TruthClosureOracle.Snapshot closedRing() {
    return new TruthClosureOracle.Snapshot(
        Set.of("a0", "b0", "c0"),
        Set.of("a1"),
        Map.of("a0", "b0", "b0", "c0", "c0", "a1"),
        Map.of("a1", Set.of("A0")),
        Map.of("A0", "a0", "B0", "b0", "C0", "c0"));
  }

  private static ModelDetectorMvp.Interval interval(long startMs, long endMs) {
    return new ModelDetectorMvp.Interval(startMs, endMs);
  }
}
