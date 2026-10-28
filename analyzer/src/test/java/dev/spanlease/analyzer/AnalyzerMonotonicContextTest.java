package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import org.junit.jupiter.api.Test;

/**
 * Receipt freshness uses the analyzer clock's elapsed time, never wall-clock adjustments (R9/R13).
 */
class AnalyzerMonotonicContextTest {
  @Test
  void wallClockJumpDoesNotExpireAFreshReceivedCheckpoint() {
    AnalyzerEngine engine = ring();
    assertThat(engine.evaluate(1_000_000, 3_100_000_000L).verdict())
        .isEqualTo(Verdict.CONFIRMED_DEADLOCK);
  }

  @Test
  void unchangedWallClockDoesNotRefreshAnOldReceivedCheckpoint() {
    VerdictReport report = ring().evaluate(3_100, 6_000_000_000L);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .containsOnly("STALE_CHECKPOINT");
  }

  @Test
  void evaluationCannotPrecedeAnAlreadyIngestedReceipt() {
    assertThatThrownBy(() -> ring().evaluate(3_100, 1_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AnalyzerEngine ring() {
    AnalyzerEngine engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    ProductionDemo.observations(false).forEach(engine::ingest);
    return engine;
  }
}
