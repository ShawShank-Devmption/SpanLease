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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R7: lease eligibility covers every blocked execution before either confirmed verdict. */
class AnalyzerLeaseCoverageTest {
  @Test
  void positiveWitnessCannotHideMissingLeaseOnASeparateProgressingBranch() {
    RunManifest ring = ProductionDemo.manifest(false);
    List<RunManifest.Instance> instances = new ArrayList<>(ring.instances());
    instances.add(new RunManifest.Instance("D", "svcD", 0, 10_000, 10));
    instances.add(new RunManifest.Instance("E", "svcE", 0, 10_000, 10));
    List<RunManifest.Pool> pools = new ArrayList<>(ring.pools());
    pools.add(new RunManifest.Pool("D", "main", List.of("D0"), true));
    pools.add(new RunManifest.Pool("E", "main", List.of("E0"), true));
    AnalyzerEngine engine =
        new AnalyzerEngine(
            new RunManifest(2, "branch", "sha256:branch", instances, pools, ring.parameters()));
    ProductionDemo.observations(false).forEach(engine::ingest);
    engine.ingest(event("D", 1, "", 0, new EventPayload.ResourceInit("main", 1, List.of("D0"))));
    engine.ingest(event("D", 2, "", 100, new EventPayload.ResourceAcquire("d0", "D0", "root-d")));
    engine.ingest(event("D", 3, "", 200, new EventPayload.RpcBlockBegin("d0", "inv-de")));
    engine.ingest(
        event("D", 4, "", 210, new EventPayload.RpcInvocationSent("inv-de", "d0", "svcE")));
    engine.ingest(event("D", 5, "", 3_000, new EventPayload.InstanceCheckpoint(4, 0)));
    engine.ingest(event("E", 1, "", 0, new EventPayload.ResourceInit("main", 1, List.of("E0"))));
    engine.ingest(event("E", 2, "D#4", 220, new EventPayload.RpcInvocationArrived("inv-de")));
    engine.ingest(event("E", 3, "", 230, new EventPayload.ResourceAcquire("e0", "E0", "inv-de")));
    engine.ingest(event("E", 4, "", 3_000, new EventPayload.InstanceCheckpoint(3, 0)));

    VerdictReport report = engine.evaluate(3_100);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("EXPIRED_LEASE", "d0"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingBlockLeasePreventsEitherConfirmation(boolean spareCapacity) {
    VerdictReport report = evaluateWithLease(spareCapacity, null);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("EXPIRED_LEASE", "a0"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void expiredBlockLeasePreventsEitherConfirmation(boolean spareCapacity) {
    VerdictReport report =
        evaluateWithLease(
            spareCapacity,
            new EventPayload.ExecutionLease("a0", "inv-ab", "A#2", "A#3", 2_600, 2_900));

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("EXPIRED_LEASE", "a0"));
  }

  private static VerdictReport evaluateWithLease(
      boolean spareCapacity, EventPayload.ExecutionLease lease) {
    AnalyzerEngine engine = new AnalyzerEngine(ProductionDemo.manifest(spareCapacity));
    for (ObservationEnvelope envelope : ProductionDemo.observations(spareCapacity)) {
      ObservationEvent event = envelope.event();
      if (event.instanceId().equals("A")
          && event.payload() instanceof EventPayload.ExecutionLease) {
        // An idle checkpoint instead of a lease preserves contiguous sequence coverage.
        // The remaining current-block evidence alone must not authorize either confirmation.
        EventPayload payload =
            lease == null ? new EventPayload.InstanceCheckpoint(event.localSeq() - 1, 0) : lease;
        event =
            new ObservationEvent(
                2,
                event.instanceId(),
                event.localSeq(),
                event.causalParent(),
                event.wallTimeMs(),
                event.monotonicTimeNs(),
                event.traceId(),
                event.spanId(),
                payload);
      }
      engine.ingest(
          new ObservationEnvelope(event, envelope.receiptWallMs(), envelope.receiptMonotonicNs()));
    }
    return engine.evaluate(3_100);
  }

  private static ObservationEnvelope event(
      String instance, long sequence, String parent, long wallMs, EventPayload payload) {
    return new ObservationEnvelope(
        new ObservationEvent(
            2, instance, sequence, parent, wallMs, wallMs * 1_000_000, "", "", payload),
        wallMs,
        wallMs * 1_000_000);
  }
}
