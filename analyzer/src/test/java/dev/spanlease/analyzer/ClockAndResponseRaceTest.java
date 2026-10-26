package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R7/R9/R10: clock qualification and response causality gate both confirmation directions. */
class ClockAndResponseRaceTest {
  @Test
  void responseParentMustNameTheMatchingResponseRatherThanAnAcquire() {
    var facts = new ArrayList<>(ProductionDemo.observations(false));
    facts.add(
        envelope(
            "A",
            9,
            "B#3",
            3_050,
            new EventPayload.RpcBlockEnd(
                "a0", "inv-ab", EventPayload.Outcome.OK, EventPayload.CompletionKind.RESPONSE)));
    facts.add(envelope("A", 10, "", 3_100, new EventPayload.InstanceCheckpoint(9, 0)));
    var report = evaluate(ProductionDemo.manifest(false), facts, 3_200);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .contains("INVALID_CAUSAL_PARENT");
  }

  @Test
  void responseOutsideServerCheckpointRetractsCallerReturnUntilParentIsCertified() {
    var facts = new ArrayList<>(ProductionDemo.observations(false));
    facts.add(
        envelope(
            "B",
            8,
            "",
            3_010,
            new EventPayload.RpcResponseSent("inv-ab", "b0", EventPayload.Outcome.OK)));
    facts.add(
        envelope(
            "A",
            9,
            "B#8",
            3_020,
            new EventPayload.RpcBlockEnd(
                "a0", "inv-ab", EventPayload.Outcome.OK, EventPayload.CompletionKind.RESPONSE)));
    facts.add(envelope("A", 10, "", 3_100, new EventPayload.InstanceCheckpoint(9, 0)));
    var oldCut = evaluate(ProductionDemo.manifest(false), facts, 3_200);
    assertThat(oldCut.query().cut().get("A")).isEqualTo(8);
    assertThat(oldCut.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    facts.add(envelope("B", 9, "", 3_100, new EventPayload.InstanceCheckpoint(8, 0)));
    var newCut = evaluate(ProductionDemo.manifest(false), facts, 3_200);
    assertThat(newCut.query().cut().get("A")).isEqualTo(10);
    assertThat(newCut.verdict()).isEqualTo(Verdict.CONFIRMED_NO_DEADLOCK);
  }

  @Test
  void expiredLeasesCannotDischargeBlockedOwnersInSpareCapacityNegative() {
    var facts =
        ProductionDemo.observations(true).stream()
            .map(
                e -> {
                  if (e.event().payload() instanceof EventPayload.ExecutionLease lease)
                    return envelope(
                        e.event().instanceId(),
                        e.event().localSeq(),
                        "",
                        e.event().wallTimeMs(),
                        new EventPayload.ExecutionLease(
                            lease.executionId(),
                            lease.invocationId(),
                            lease.startRef(),
                            lease.blockRef(),
                            lease.observedAgeMs(),
                            2_900));
                  return e;
                })
            .toList();
    var report = evaluate(ProductionDemo.manifest(true), facts, 3_100);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .containsOnly("EXPIRED_LEASE");
  }

  @Test
  void unqualifiedOrUnderestimatedClockErrorPreventsConfirmation() {
    var base = ProductionDemo.manifest(false);
    for (var replacement :
        List.of(
            new RunManifest.Instance("A", "svcA", 0, 2_000, 10),
            new RunManifest.Instance("A", "svcA", 0, 10_000, 11))) {
      var instances = new ArrayList<>(base.instances());
      instances.set(0, replacement);
      var manifest =
          new RunManifest(
              2, base.runId(), base.manifestHash(), instances, base.pools(), base.parameters());
      var report = evaluate(manifest, ProductionDemo.observations(false), 3_100);
      assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
      assertThat(report.coverage().missing())
          .extracting(VerdictReport.MissingEvidence::code)
          .contains("UNQUALIFIED_CLOCK");
    }
  }

  @Test
  void delayedReceiptDoesNotMakeAnOldCheckpointFresh() {
    var facts =
        ProductionDemo.observations(false).stream()
            .map(e -> new ObservationEnvelope(e.event(), 10_000, 10_000_000_000L))
            .toList();
    assertThat(evaluate(ProductionDemo.manifest(false), facts, 10_100).verdict())
        .isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  @Test
  void emptyScopeCannotCertifyAbsence() {
    var base = ProductionDemo.manifest(false);
    var empty =
        new RunManifest(2, "empty", "sha256:empty", List.of(), List.of(), base.parameters());
    assertThat(new AnalyzerEngine(empty).evaluate(3_100).verdict())
        .isEqualTo(Verdict.INSUFFICIENT_OBSERVABILITY);
  }

  @Test
  void historyBudgetExhaustionCannotTurnDiscardedEvidenceIntoProgress() {
    var engine = new AnalyzerEngine(ProductionDemo.manifest(false), 2);
    ProductionDemo.observations(false).forEach(engine::ingest);
    var report = engine.evaluate(3_100);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("HISTORY_LIMIT", "demo-deadlock"));
  }

  @Test
  void lostEndAndReleaseCannotHideBehindOldCheckpointOrLeaseExpiry() {
    var facts = new ArrayList<>(ProductionDemo.observations(false));
    facts.add(
        envelope(
            "A",
            9,
            "",
            3_010,
            new EventPayload.RpcBlockEnd(
                "a0", "inv-ab", EventPayload.Outcome.DEADLINE, EventPayload.CompletionKind.LOCAL)));
    // A#10 (actual end) and A#11 (release) are lost, while a later checkpoint arrives.
    facts.add(envelope("A", 12, "", 3_100, new EventPayload.InstanceCheckpoint(11, 0)));
    var engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    facts.forEach(engine::ingest);
    for (long at : List.of(3_200L, 6_000L)) {
      var report = engine.evaluate(at);
      assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
      assertThat(report.coverage().frontierGaps())
          .contains(new VerdictReport.FrontierGap("A", 10, 11));
    }
  }

  private static VerdictReport evaluate(
      RunManifest manifest, List<ObservationEnvelope> facts, long at) {
    var engine = new AnalyzerEngine(manifest);
    facts.forEach(engine::ingest);
    return engine.evaluate(at);
  }

  private static ObservationEnvelope envelope(
      String id, long seq, String parent, long time, EventPayload payload) {
    return new ObservationEnvelope(
        new ObservationEvent(2, id, seq, parent, time, seq * 1_000_000, "", "", payload),
        time,
        time * 1_000_000);
  }
}
