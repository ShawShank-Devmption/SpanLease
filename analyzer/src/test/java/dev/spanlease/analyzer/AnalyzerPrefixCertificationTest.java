package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** R4/R5/R7/R12/R13: certification never hides received evidence of an incomplete prefix. */
class AnalyzerPrefixCertificationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gapAfterFreshCertifiedCheckpointBlocksBothConfirmations(boolean spareCapacity) {
    AnalyzerEngine engine = ring(spareCapacity);
    engine.ingest(checkpoint("A", 10, 3_200)); // A#9 has not arrived.

    VerdictReport report = engine.evaluate(3_300);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.query().cut()).containsEntry("A", 8L);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("SEQUENCE_GAP", "A#9"));
    assertThat(report.coverage().frontierGaps())
        .containsExactly(new VerdictReport.FrontierGap("A", 9, 9));
  }

  @Test
  void reportsEveryMissingRangeWithoutEnumeratingMissingPositions() {
    AnalyzerEngine engine = ring(false);
    engine.ingest(checkpoint("A", 11, 3_100));
    engine.ingest(checkpoint("A", 1_000_000, 3_200));

    VerdictReport report = engine.evaluate(3_300);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().frontierGaps())
        .containsExactly(
            new VerdictReport.FrontierGap("A", 9, 10),
            new VerdictReport.FrontierGap("A", 12, 999_999));
    assertThat(report.coverage().missing())
        .containsExactly(
            new VerdictReport.MissingEvidence("SEQUENCE_GAP", "A#12"),
            new VerdictReport.MissingEvidence("SEQUENCE_GAP", "A#9"));
  }

  @Test
  void lateMissingRecordRestoresCertificationWithoutStartingANewRun() {
    AnalyzerEngine engine = ring(false);
    engine.ingest(checkpoint("A", 10, 3_200));
    assertThat(engine.evaluate(3_300).verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);

    engine.ingest(checkpoint("A", 9, 3_100));
    VerdictReport report = engine.evaluate(3_300);

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.query().cut()).containsEntry("A", 10L);
    assertThat(report.coverage().frontierGaps()).isEmpty();
    assertThat(report.coverage().missing()).isEmpty();
  }

  @Test
  void elapsedDeltaAndRepeatedLaterCheckpointsNeverRepairPermanentGap() {
    AnalyzerEngine engine = ring(false);
    engine.ingest(checkpoint("A", 10, 3_200));
    engine.ingest(checkpoint("A", 11, 5_000));
    engine.ingest(checkpoint("B", 8, 5_000));
    engine.ingest(checkpoint("C", 8, 5_000));

    VerdictReport report = engine.evaluate(5_100);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.query().cut()).containsEntry("A", 8L);
    assertThat(report.coverage().frontierGaps())
        .containsExactly(new VerdictReport.FrontierGap("A", 9, 9));
    assertThat(report.coverage().missing())
        .contains(new VerdictReport.MissingEvidence("SEQUENCE_GAP", "A#9"));
  }

  @Test
  void missingStartupPrefixReportsGapAsWellAsMissingCheckpoint() {
    AnalyzerEngine engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    engine.ingest(checkpoint("A", 3, 3_000));

    VerdictReport report = engine.evaluate(3_100);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().frontierGaps())
        .containsExactly(new VerdictReport.FrontierGap("A", 1, 2));
    assertThat(report.coverage().missing())
        .contains(
            new VerdictReport.MissingEvidence("MISSING_CHECKPOINT", "A"),
            new VerdictReport.MissingEvidence("SEQUENCE_GAP", "A#1"));
  }

  @Test
  void identicalDuplicateDoesNotRefreshCheckpointReceipt() {
    AnalyzerEngine engine = ring(false);
    ObservationEvent original =
        ProductionDemo.observations(false).stream()
            .map(ObservationEnvelope::event)
            .filter(event -> event.instanceId().equals("A") && event.localSeq() == 8)
            .findFirst()
            .orElseThrow();
    engine.ingest(new ObservationEnvelope(original, 4_900, 4_900_000_000L));

    VerdictReport report = engine.evaluate(5_000);

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .contains(new VerdictReport.MissingEvidence("STALE_CHECKPOINT", "A"));
  }

  @Test
  void contiguousUncheckpointedSuffixDoesNotInventAGap() {
    AnalyzerEngine engine = ring(false);
    engine.ingest(
        new ObservationEnvelope(
            new ObservationEvent(
                2,
                "A",
                9,
                "",
                3_100,
                3_100_000_000L,
                "",
                "",
                new EventPayload.ExecutionLease("a0", "inv-ab", "A#2", "A#3", 2_900, 5_000)),
            3_100,
            3_100_000_000L));

    VerdictReport report = engine.evaluate(3_300);

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.query().cut()).containsEntry("A", 8L);
    assertThat(report.coverage().frontierGaps()).isEmpty();
  }

  private static AnalyzerEngine ring(boolean spareCapacity) {
    AnalyzerEngine engine = new AnalyzerEngine(ProductionDemo.manifest(spareCapacity));
    List<ObservationEnvelope> observations = ProductionDemo.observations(spareCapacity);
    observations.forEach(engine::ingest);
    return engine;
  }

  private static ObservationEnvelope checkpoint(String instance, long sequence, long wallMs) {
    return new ObservationEnvelope(
        new ObservationEvent(
            2,
            instance,
            sequence,
            "",
            wallMs,
            wallMs * 1_000_000,
            "",
            "",
            new EventPayload.InstanceCheckpoint(sequence - 1, 0)),
        wallMs,
        wallMs * 1_000_000);
  }
}
