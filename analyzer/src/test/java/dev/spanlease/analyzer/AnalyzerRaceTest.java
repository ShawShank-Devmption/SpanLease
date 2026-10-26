package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** R5–R7/R10/R12/R13/R15 at fixed receipt/evaluation contexts; no wall-clock synchronization. */
class AnalyzerRaceTest {
  @Test
  void r5ReorderAndIdenticalDuplicatesPreserveExactReport() throws Exception {
    var facts = ProductionDemo.observations(false);
    var expected = run(facts, 3_100);
    for (int seed = 0; seed < 100; seed++) {
      var shuffled = new ArrayList<>(facts);
      shuffled.addAll(facts);
      Collections.shuffle(shuffled, new Random(seed));
      assertThat(ContractJson.mapper().writeValueAsString(run(shuffled, 3_100)))
          .isEqualTo(ContractJson.mapper().writeValueAsString(expected));
    }
  }

  @Test
  void r6LostAcquireAndMissingInitializationNeverBecomeFreeSlots() {
    for (Class<?> type :
        List.of(EventPayload.ResourceAcquire.class, EventPayload.ResourceInit.class)) {
      assertThat(
              run(
                      ProductionDemo.observations(false).stream()
                          .filter(
                              e ->
                                  !(e.event().instanceId().equals("A")
                                      && type.isInstance(e.event().payload())))
                          .toList(),
                      3_100)
                  .verdict())
          .isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    }
  }

  @Test
  void r7KnownTailGapCannotHideBehindOlderFreshCheckpoint() {
    var facts = new ArrayList<>(ProductionDemo.observations(false));
    long seq =
        facts.stream()
            .filter(e -> e.event().instanceId().equals("A"))
            .mapToLong(e -> e.event().localSeq())
            .max()
            .orElseThrow();
    facts.add(envelope("A", seq + 2, "", 3_050, new EventPayload.InstanceCheckpoint(seq + 1, 0)));
    var report = run(facts, 3_100);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .contains("SEQUENCE_GAP");
  }

  @Test
  void r10ArrivalCannotJoinAnUnrelatedSendWithTheSameReference() {
    var facts =
        ProductionDemo.observations(false).stream()
            .map(
                e -> {
                  if (e.event().payload() instanceof EventPayload.RpcInvocationArrived) {
                    return envelope(
                        e.event().instanceId(),
                        e.event().localSeq(),
                        e.event().causalParent(),
                        e.event().wallTimeMs(),
                        new EventPayload.RpcInvocationArrived("alias"));
                  }
                  return e;
                })
            .toList();
    assertThat(run(facts, 3_100).coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .contains("INVALID_CAUSAL_PARENT");
  }

  @Test
  void r12AbsentIdleCheckpointBlocksWholeScope() {
    var m = ProductionDemo.manifest(false);
    var instances = new ArrayList<>(m.instances());
    instances.add(new RunManifest.Instance("idle", "idle", 0, 10_000, 10));
    var engine =
        new AnalyzerEngine(
            new RunManifest(2, m.runId(), m.manifestHash(), instances, m.pools(), m.parameters()));
    ProductionDemo.observations(false).forEach(engine::ingest);
    assertThat(engine.evaluate(3_100).coverage().missing())
        .contains(new VerdictReport.MissingEvidence("MISSING_CHECKPOINT", "idle"));
  }

  @Test
  void r13CrashCannotAdvanceHorizonOrInferRelease() {
    assertThat(run(ProductionDemo.observations(false), 10_000).verdict())
        .isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  @Test
  void r15SequenceReuseConflictsAndNewEpochCannotEnterOldManifest() {
    var engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    var first = ProductionDemo.observations(false).getFirst();
    engine.ingest(first);
    engine.ingest(
        envelope(
            first.event().instanceId(),
            first.event().localSeq(),
            "",
            100,
            first.event().payload()));
    assertThat(engine.evaluate(3_100).verdict()).isEqualTo(Verdict.INSUFFICIENT_OBSERVABILITY);
    assertThatThrownBy(
            () ->
                engine.ingest(
                    envelope("A@restart", 1, "", 0, new EventPayload.InstanceCheckpoint(0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void replayRoundTripPreservesReceiptsEvaluationTimesAndReports() throws Exception {
    var session =
        new ReplaySession(
            1,
            ProductionDemo.manifest(false),
            List.of(
                new ReplaySession.Step(ProductionDemo.observations(false), 3_100L),
                new ReplaySession.Step(List.of(), 10_000L)));
    var json = ContractJson.mapper();
    var decoded = json.readValue(json.writeValueAsBytes(session), ReplaySession.class);
    assertThat(decoded.evaluate()).isEqualTo(session.evaluate());
    assertThat(decoded.evaluate())
        .extracting(VerdictReport::verdict)
        .containsExactly(Verdict.CONFIRMED_DEADLOCK, Verdict.CANDIDATE_INCONCLUSIVE);
    assertThatThrownBy(
            () ->
                new ReplaySession(
                        1,
                        session.manifest(),
                        List.of(new ReplaySession.Step(ProductionDemo.observations(false), 0L)))
                    .evaluate())
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static VerdictReport run(List<ObservationEnvelope> facts, long at) {
    var engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    facts.forEach(engine::ingest);
    return engine.evaluate(at);
  }

  private static ObservationEnvelope envelope(
      String id, long seq, String parent, long at, EventPayload payload) {
    return new ObservationEnvelope(
        new ObservationEvent(2, id, seq, parent, at, at * 1_000_000, "", "", payload),
        at,
        at * 1_000_000);
  }
}
