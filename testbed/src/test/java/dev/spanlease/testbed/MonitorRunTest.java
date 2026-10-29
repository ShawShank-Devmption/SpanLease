package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.Verdict;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonitorRunTest {
  @TempDir Path runs;

  @ParameterizedTest
  @ValueSource(strings = {"spare-capacity", "slow-progress"})
  @Timeout(30)
  void finiteControlsCompleteNormallyAndDoNotInventADeadlock(String selected) throws Exception {
    Scenario scenario =
        selected.equals("spare-capacity") ? Scenario.spareCapacity() : Scenario.slowProgress(3);
    RunPlan plan = RunPlan.create(scenario, System.currentTimeMillis());
    MonitorState state = new MonitorState(2_000);
    state.begin(plan);
    try (MonitorRun run = MonitorRun.start(plan, runs, state)) {
      run.awaitExit();
      var result = state.snapshot();
      assertThat(result.phase()).isEqualTo("COMPLETE");
      assertThat(result.rootOutcome()).isEqualTo("OK");
      assertThat(result.rootHopsCompleted()).isEqualTo(selected.equals("spare-capacity") ? 4 : 3);
      assertThat(result.applicationAlive()).isFalse();
      assertThat(result.exitCode()).isZero();
      assertThat(result.firstDeadlock()).isNull();
      assertThat(result.reports())
          .anySatisfy(
              report -> assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_NO_DEADLOCK));
    }
  }

  @Test
  @Timeout(50)
  void deadlockOutlivesTheOldObservationWindowAndKillingOnlyTheApplicationPreservesEvidence()
      throws Exception {
    MonitorState state = new MonitorState(2_000);
    RunPlan plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    state.begin(plan);
    try (MonitorRun run = MonitorRun.start(plan, runs, state)) {
      CompletableFuture<Void> finished =
          CompletableFuture.runAsync(
              () -> {
                try {
                  run.awaitExit();
                } catch (Exception failure) {
                  throw new RuntimeException(failure);
                }
              });
      awaitDeadlock(state);
      var snapshot = state.snapshot();
      long pid = snapshot.applicationPid();
      assertThat(pid).isNotEqualTo(ProcessHandle.current().pid());
      assertThat(snapshot.firstDeadlock().core().executions()).hasSize(3);
      assertThat(snapshot.diagnostics().workers()).hasSize(3);
      assertThat(snapshot.diagnostics().workers())
          .allSatisfy(
              worker -> {
                assertThat(worker.javaThreadId()).isPositive();
                assertThat(worker.stack()).isNotEmpty();
              });
      assertThatThrownBy(() -> finished.get(9, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);
      assertThat(state.snapshot().applicationAlive()).isTrue();
      assertThat(ProcessHandle.of(pid).orElseThrow().destroy()).isTrue();
      finished.get(10, TimeUnit.SECONDS);
      assertThat(state.snapshot().phase()).isEqualTo("STOPPED");
      assertThat(state.snapshot().applicationAlive()).isFalse();
      assertThat(state.snapshot().firstDeadlock().verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
      assertThat(state.snapshot().latestReport().verdict())
          .isNotEqualTo(Verdict.CONFIRMED_NO_DEADLOCK);
      assertThat(runs.resolve(plan.manifest().runId()).resolve("first-deadlock.json")).exists();
    }
  }

  private static void awaitDeadlock(MonitorState state) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    synchronized (state) {
      while (state.snapshot().firstDeadlock() == null) {
        long remaining = until - System.nanoTime();
        assertThat(remaining)
            .as("deadlock report arrives while the application is running")
            .isPositive();
        TimeUnit.NANOSECONDS.timedWait(state, remaining);
      }
    }
  }
}
