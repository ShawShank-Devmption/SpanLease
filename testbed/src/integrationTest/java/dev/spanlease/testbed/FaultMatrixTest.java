package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.Verdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class FaultMatrixTest {
  @Test
  void deliveryFaultsPreserveTruthAndReplayExactly() throws Exception {
    var workload = Workload.named("closed-ring");
    Path runs = Path.of(System.getProperty("spanlease.integration.runs"));
    List<List<ObservationFaults.Rule>> schedules =
        List.of(
            List.of(rule("resource.acquire", ObservationFaults.Action.DROP, 0)),
            List.of(rule("instance.checkpoint", ObservationFaults.Action.DROP, 0)),
            List.of(
                rule("resource.acquire", ObservationFaults.Action.DELAY, 350),
                rule("", ObservationFaults.Action.DUPLICATE, 0)),
            List.of(rule("", ObservationFaults.Action.SKEW, 10_000)));
    for (var schedule : schedules) {
      var result = ScenarioRunner.run(workload, true, runs, 7, schedule);
      assertThat(result.closedJobs()).hasSize(3);
      boolean supportedDelivery = schedule.getFirst().action() == ObservationFaults.Action.DELAY;
      assertThat(result.reports().stream().anyMatch(r -> r.verdict() == Verdict.CONFIRMED_DEADLOCK))
          .as(schedule.toString())
          .isEqualTo(supportedDelivery);
      long onset =
          result.truth().stream()
              .filter(e -> e.transition().equals("CALL_ENTER"))
              .mapToLong(TruthRecorder.Entry::wallMs)
              .max()
              .orElseThrow();
      assertThat(
              result.reports().stream()
                  .filter(
                      r ->
                          r.evaluatedAtWallMs() > onset + 1_000
                              && r.evaluatedAtWallMs() < result.measurementEndedMs())
                  .toList())
          .extracting(r -> r.verdict())
          .doesNotContain(Verdict.CONFIRMED_NO_DEADLOCK);
      Path directory = Path.of(result.directory());
      Path replayed = directory.resolve("replayed-reports.jsonl");
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty(AnalyzerProcess.CLASSPATH_PROPERTY),
                  "dev.spanlease.analyzer.ReplayMain",
                  directory.resolve("observations.replay.json").toString(),
                  replayed.toString())
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(Files.readAllLines(replayed))
          .isEqualTo(Files.readAllLines(directory.resolve("reports.jsonl")));
    }
  }

  private static ObservationFaults.Rule rule(
      String type, ObservationFaults.Action action, long amount) {
    return new ObservationFaults.Rule("A", type, 1, Long.MAX_VALUE, action, amount, 1);
  }
}
