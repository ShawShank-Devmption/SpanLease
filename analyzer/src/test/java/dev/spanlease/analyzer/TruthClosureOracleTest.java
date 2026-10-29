package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TruthClosureOracleTest {
  @Test
  void serialSingleSlotRingIsClosed() {
    TruthClosureOracle.Snapshot snapshot =
        snapshot(
            Set.of("a0", "b0", "c0"),
            Set.of("a1"),
            Map.of("a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0")),
            Map.of("A0", "a0", "B0", "b0", "C0", "c0"));

    assertThat(TruthClosureOracle.maximalClosedResidual(snapshot))
        .containsExactlyInAnyOrder("a0", "b0", "c0", "a1");
  }

  @Test
  void spareEligibleSlotEliminatesTheRing() {
    TruthClosureOracle.Snapshot snapshot =
        snapshot(
            Set.of("a0", "b0", "c0"),
            Set.of("a1"),
            Map.of("a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0", "A1")),
            Map.of("A0", "a0", "B0", "b0", "C0", "c0"));

    assertThat(TruthClosureOracle.maximalClosedResidual(snapshot)).isEmpty();
  }

  @Test
  void maximalResidualIncludesUpstreamDependent() {
    TruthClosureOracle.Snapshot snapshot =
        snapshot(
            Set.of("u0", "a0", "b0", "c0"),
            Set.of("a1", "a2"),
            Map.of("u0", "a2", "a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0"), "a2", Set.of("A0")),
            Map.of("U0", "u0", "A0", "a0", "B0", "b0", "C0", "c0"));

    assertThat(TruthClosureOracle.maximalClosedResidual(snapshot))
        .containsExactlyInAnyOrder("u0", "a0", "b0", "c0", "a1", "a2");
  }

  @Test
  void maximalResidualIncludesAllDisjointClosedComponents() {
    TruthClosureOracle.Snapshot snapshot =
        snapshot(
            Set.of("a0", "b0", "x0", "y0"),
            Set.of("a1", "x1"),
            Map.of("a0", "b0", "b0", "a1", "x0", "y0", "y0", "x1"),
            Map.of("a1", Set.of("A0"), "x1", Set.of("X0")),
            Map.of("A0", "a0", "B0", "b0", "X0", "x0", "Y0", "y0"));

    assertThat(TruthClosureOracle.maximalClosedResidual(snapshot))
        .containsExactlyInAnyOrder("a0", "b0", "a1", "x0", "y0", "x1");
  }

  @Test
  void nonblockedSlotOwnerPreventsClosure() {
    TruthClosureOracle.Snapshot snapshot =
        snapshot(
            Set.of("a0"),
            Set.of("b1"),
            Map.of("a0", "b1"),
            Map.of("b1", Set.of("B0")),
            Map.of("B0", "progressing-b0"));

    assertThat(TruthClosureOracle.maximalClosedResidual(snapshot)).isEmpty();
  }

  private static TruthClosureOracle.Snapshot snapshot(
      Set<String> blockedExecutions,
      Set<String> waitingRequests,
      Map<String, String> blockedCounterparts,
      Map<String, Set<String>> eligibleUnitsByRequest,
      Map<String, String> ownerByUnit) {
    return new TruthClosureOracle.Snapshot(
        blockedExecutions,
        waitingRequests,
        blockedCounterparts,
        eligibleUnitsByRequest,
        ownerByUnit);
  }
}
