package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzerEngineTest {
  @Test
  void certifiedClosedRingProducesExactEvidenceSet() {
    VerdictReport report = ProductionDemo.evaluate(false);

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactly("a0", "b0", "c0");
    assertThat(report.core().queueEntries())
        .extracting(VerdictReport.QueueEntry::invocationId)
        .containsExactly("inv-ca");
    assertThat(report.core().units())
        .extracting(VerdictReport.Unit::id)
        .containsExactly("A0", "B0", "C0");
    assertThat(report.persistence()).isNotNull();
    assertThat(report.persistence().narrowedSpanMs()).isGreaterThanOrEqualTo(2_000);
    assertThat(report.coverage().missing()).isEmpty();
  }

  @Test
  void certifiedSpareCapacityReducesToNoDeadlock() {
    VerdictReport report = ProductionDemo.evaluate(true);

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_NO_DEADLOCK);
    assertThat(report.core().executions()).isEmpty();
    assertThat(report.persistence()).isNull();
  }

  @Test
  void missingInteriorSequenceCannotBeRepairedByCheckpoint() {
    AnalyzerEngine analyzer = new AnalyzerEngine(ProductionDemo.manifest(false));
    List<ObservationEnvelope> observations = ProductionDemo.observations(false);
    observations.stream()
        .filter(
            envelope ->
                !(envelope.event().instanceId().equals("B") && envelope.event().localSeq() == 4))
        .forEach(analyzer::ingest);

    VerdictReport report = analyzer.evaluate(3_100);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .contains("MISSING_CHECKPOINT");
  }

  @Test
  void conflictingDuplicateInvalidatesAffectedRun() {
    AnalyzerEngine analyzer = new AnalyzerEngine(ProductionDemo.manifest(false));
    ObservationEnvelope original = ProductionDemo.observations(false).get(0);
    analyzer.ingest(original);
    analyzer.ingest(
        new ObservationEnvelope(
            new ObservationEvent(2, "A", 1, "", 1, 1, "", "", original.event().payload()), 1, 1));

    VerdictReport report = analyzer.evaluate(100);

    assertThat(report.verdict()).isEqualTo(Verdict.INSUFFICIENT_OBSERVABILITY);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .containsExactly("INVALID_EVENT");
  }
}
