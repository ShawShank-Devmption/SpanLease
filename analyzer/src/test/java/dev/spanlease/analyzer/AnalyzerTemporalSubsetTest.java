package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C-TIME: a young dependent or separate component never resets an old closed witness. */
class AnalyzerTemporalSubsetTest {
  @Test
  void youngUpstreamDependentDoesNotResetOldRing() {
    List<ObservationEnvelope> events = new ArrayList<>();
    for (ObservationEnvelope envelope : ProductionDemo.observations(false)) {
      ObservationEvent event = envelope.event();
      if (event.instanceId().equals("A") && event.localSeq() == 7) {
        events.add(
            event(
                "A",
                7,
                "",
                2_400,
                new EventPayload.ExecutionLease("a0", "inv-ab", "A#2", "A#3", 2_200, 5_000)));
      } else if (!(event.instanceId().equals("A") && event.localSeq() == 8)) {
        events.add(envelope);
      }
    }
    events.add(event("A", 8, "U#4", 2_700, new EventPayload.RpcInvocationArrived("inv-ua")));
    events.add(event("A", 9, "", 2_710, new EventPayload.ResourceWaitBegin("inv-ua", "main")));
    events.add(event("A", 10, "", 3_000, new EventPayload.InstanceCheckpoint(9, 0)));
    addCaller(events, false);

    VerdictReport report = evaluate(events);

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactly("a0", "b0", "c0");
    assertThat(report.core().queueEntries())
        .extracting(VerdictReport.QueueEntry::invocationId)
        .containsExactly("inv-ca");
  }

  @Test
  void youngDisjointCycleDoesNotResetOldRing() {
    List<ObservationEnvelope> events = new ArrayList<>(ProductionDemo.observations(false));
    addCaller(events, true);

    VerdictReport report = evaluate(events);

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactly("a0", "b0", "c0");
    assertThat(report.core().queueEntries())
        .extracting(VerdictReport.QueueEntry::invocationId)
        .containsExactly("inv-ca");
  }

  @Test
  void temporalPruningToEmptyIsNeverANegativeVerdict() {
    List<ObservationEnvelope> events = new ArrayList<>();
    for (ObservationEnvelope envelope : ProductionDemo.observations(false)) {
      ObservationEvent event = envelope.event();
      long time = event.wallTimeMs() == 0 ? 0 : Math.max(2_600, event.wallTimeMs());
      events.add(
          event(event.instanceId(), event.localSeq(), event.causalParent(), time, event.payload()));
    }
    AnalyzerEngine engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    events.forEach(engine::ingest);

    assertThat(engine.evaluate(3_100).verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  private static void addCaller(List<ObservationEnvelope> events, boolean selfCycle) {
    events.add(event("U", 1, "", 0, new EventPayload.ResourceInit("main", 1, List.of("U0"))));
    events.add(event("U", 2, "", 100, new EventPayload.ResourceAcquire("u0", "U0", "root-u")));
    events.add(event("U", 3, "", 2_600, new EventPayload.RpcBlockBegin("u0", "inv-ua")));
    events.add(
        event(
            "U",
            4,
            "",
            2_610,
            new EventPayload.RpcInvocationSent("inv-ua", "u0", selfCycle ? "svcU" : "svcA")));
    if (selfCycle) {
      events.add(event("U", 5, "U#4", 2_620, new EventPayload.RpcInvocationArrived("inv-ua")));
      events.add(event("U", 6, "", 2_630, new EventPayload.ResourceWaitBegin("inv-ua", "main")));
    }
    long next = selfCycle ? 7 : 5;
    events.add(
        event(
            "U",
            next,
            "",
            2_800,
            new EventPayload.ExecutionLease("u0", "inv-ua", "U#2", "U#3", 200, 5_000)));
    events.add(event("U", next + 1, "", 3_000, new EventPayload.InstanceCheckpoint(next, 0)));
  }

  private static VerdictReport evaluate(List<ObservationEnvelope> events) {
    RunManifest ring = ProductionDemo.manifest(false);
    List<RunManifest.Instance> instances = new ArrayList<>(ring.instances());
    instances.add(new RunManifest.Instance("U", "svcU", 0, 10_000, 10));
    List<RunManifest.Pool> pools = new ArrayList<>(ring.pools());
    pools.add(new RunManifest.Pool("U", "main", List.of("U0"), true));
    AnalyzerEngine engine =
        new AnalyzerEngine(
            new RunManifest(2, "temporal", "sha256:temporal", instances, pools, ring.parameters()));
    events.forEach(engine::ingest);
    return engine.evaluate(3_100);
  }

  private static ObservationEnvelope event(
      String instance, long seq, String parent, long time, EventPayload payload) {
    return new ObservationEnvelope(
        new ObservationEvent(2, instance, seq, parent, time, time * 1_000_000, "", "", payload),
        time,
        time * 1_000_000);
  }
}
