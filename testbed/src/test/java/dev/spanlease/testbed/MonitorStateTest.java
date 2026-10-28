package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonitorStateTest {
  @Test
  void onlyOneRunMayOwnTheMonitorAndBeginningANewRunClearsOldData() {
    MonitorState state = new MonitorState(2);
    RunPlan plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    assertThat(state.begin(plan)).isTrue();
    assertThat(state.begin(plan)).isFalse();
    state.observe(event(1));
    state.fail("test stop");
    assertThat(state.begin(plan)).isTrue();
    assertThat(state.snapshot().observations()).isEmpty();
    assertThat(state.snapshot().error()).isEmpty();
  }

  @Test
  void displayTruncationIsBoundedAndVisible() {
    MonitorState state = new MonitorState(2);
    state.begin(RunPlan.create(Scenario.closedRing(), System.currentTimeMillis()));
    state.observe(event(1));
    state.observe(event(2));
    state.observe(event(3));
    assertThat(state.snapshot().observedTotal()).isEqualTo(3);
    assertThat(state.snapshot().discardedObservations()).isEqualTo(1);
    assertThat(state.snapshot().observations())
        .extracting(envelope -> envelope.event().localSeq())
        .containsExactly(2L, 3L);
  }

  @Test
  void laterNegativeReportDoesNotEraseHistoricalPositiveEvidence() {
    MonitorState state = new MonitorState(2);
    state.begin(RunPlan.create(Scenario.closedRing(), System.currentTimeMillis()));
    VerdictReport positive = report(Verdict.CONFIRMED_DEADLOCK);
    state.report(new AnalyzerProcess.TimedReport(3_000, positive));
    state.report(new AnalyzerProcess.TimedReport(3_100, report(Verdict.CONFIRMED_NO_DEADLOCK)));
    assertThat(state.snapshot().latestReport().verdict()).isEqualTo(Verdict.CONFIRMED_NO_DEADLOCK);
    assertThat(state.snapshot().firstDeadlock()).isEqualTo(positive);
  }

  @Test
  void applicationExitPreservesDeadlockWithoutInventingANegativeVerdict() {
    MonitorState state = new MonitorState(20);
    state.begin(RunPlan.create(Scenario.closedRing(), System.currentTimeMillis()));
    state.applicationStarted(12345);
    VerdictReport positive = report(Verdict.CONFIRMED_DEADLOCK);
    state.report(new AnalyzerProcess.TimedReport(3_000, positive));
    state.applicationExited(143);
    assertThat(state.snapshot().phase()).isEqualTo("STOPPED");
    assertThat(state.snapshot().applicationPid()).isEqualTo(12345);
    assertThat(state.snapshot().applicationAlive()).isFalse();
    assertThat(state.snapshot().exitCode()).isEqualTo(143);
    assertThat(state.snapshot().latestReport()).isEqualTo(positive);
    assertThat(state.snapshot().firstDeadlock()).isEqualTo(positive);
    assertThat(state.begin(RunPlan.create(Scenario.spareCapacity(), System.currentTimeMillis())))
        .isTrue();
    assertThat(state.snapshot().applicationPid()).isNull();
    assertThat(state.snapshot().firstDeadlock()).isNull();
  }

  @Test
  void aCompletedRootDoesNotAllowAnotherRunBeforeTheWorkloadHasExited() {
    MonitorState state = new MonitorState(20);
    RunPlan plan = RunPlan.create(Scenario.spareCapacity(), System.currentTimeMillis());
    state.begin(plan);
    state.applicationStarted(12345);
    state.applicationCompleted("OK", 4);
    assertThat(state.begin(plan)).isFalse();
    state.applicationExited(0);
    assertThat(state.snapshot().phase()).isEqualTo("COMPLETE");
    assertThat(state.begin(plan)).isTrue();
  }

  private static ObservationEnvelope event(long seq) {
    return new ObservationEnvelope(
        new ObservationEvent(
            2, "A", seq, "", 100, 100, "", "", new EventPayload.InstanceCheckpoint(seq - 1, 0)),
        100,
        100);
  }

  private static VerdictReport report(Verdict verdict) {
    return new VerdictReport(
        2,
        "test",
        "sha256:test",
        verdict,
        3_000,
        new VerdictReport.Query(List.of("A"), Map.of("A", 1L), 3_000L),
        verdict == Verdict.CONFIRMED_DEADLOCK
            ? new VerdictReport.Persistence(0, 2_000, 2_000, 2_000)
            : null,
        new VerdictReport.Core(List.of(), List.of(), List.of(), List.of()),
        new VerdictReport.Coverage(0, 0, List.of(), List.of(), List.of(), List.of()));
  }
}
