package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import io.grpc.Status;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real gRPC/OTLP/analyzer regressions; expectations come from topology and manifest. */
class LitmusTest {
  @TempDir Path runs;

  @Test
  @Timeout(90)
  void deterministicClosedWaitConfirmsTheExactCore() throws Exception {
    RunPlan plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    LiveRun.Result result = execute(plan);

    assertThat(result.first(Verdict.CONFIRMED_DEADLOCK)).isPresent();
    VerdictReport report = result.first(Verdict.CONFIRMED_DEADLOCK).orElseThrow().report();
    var core = report.core();
    assertThat(core.executions()).hasSize(3);
    assertThat(core.units())
        .extracting(VerdictReport.Unit::id)
        .containsExactlyInAnyOrder(
            plan.instanceByService().get("svcA") + ":app:slot-0",
            plan.instanceByService().get("svcB") + ":app:slot-0",
            plan.instanceByService().get("svcC") + ":app:slot-0");
    var executionIds = core.executions().stream().map(VerdictReport.Execution::id).toList();
    assertThat(core.units())
        .allSatisfy(unit -> assertThat(executionIds).contains(unit.ownerExecutionId()));
    assertThat(core.queueEntries())
        .singleElement()
        .satisfies(
            entry ->
                assertThat(entry.instanceId()).isEqualTo(plan.instanceByService().get("svcA")));
    assertThat(report.persistence().narrowedSpanMs())
        .isGreaterThanOrEqualTo(plan.manifest().parameters().persistenceMs());
  }

  @Test
  @Timeout(90)
  void spareCapacityCyclicPatternConfirmsNoDeadlock() throws Exception {
    RunPlan plan = RunPlan.create(Scenario.spareCapacity(), System.currentTimeMillis());
    LiveRun.Result result = execute(plan);

    assertThat(result.rootOutcome()).isEqualTo(Status.Code.OK);
    assertThat(result.rootHopsCompleted()).isEqualTo(4);
    assertThat(result.first(Verdict.CONFIRMED_DEADLOCK)).isEmpty();
    assertThat(
            result.certifiedWithin(Verdict.CONFIRMED_NO_DEADLOCK, result.leafRun().orElseThrow()))
        .isNotEmpty();
  }

  private LiveRun.Result execute(RunPlan plan) throws Exception {
    return LiveRun.execute(
        plan, runs, ignored -> () -> UUID.randomUUID().toString(), ignored -> {}, ignored -> {});
  }
}
