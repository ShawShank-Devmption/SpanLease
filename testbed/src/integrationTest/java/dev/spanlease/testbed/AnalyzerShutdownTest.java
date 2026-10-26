package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.ContractJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class AnalyzerShutdownTest {
  @Test
  void gracefulStopCompletesTheFinalRecordedReportBeforeStdoutCloses() throws Exception {
    var plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    Path directory =
        Files.createDirectories(
            Path.of(System.getProperty("spanlease.integration.runs"))
                .resolve(plan.manifest().runId() + "-shutdown"));
    Path manifest = directory.resolve("manifest.json");
    var json = ContractJson.mapper();
    json.writeValue(manifest.toFile(), plan.manifest());
    var analyzer = AnalyzerProcess.start(manifest, ignored -> {});
    try (analyzer) {
      analyzer.awaitReady(Duration.ofSeconds(30));
      assertThat(analyzer.awaitReport(r -> analyzer.reports().size() >= 2, Duration.ofSeconds(5)))
          .isTrue();
    }
    assertThat(analyzer.gracefulShutdown()).isTrue();
    Path replayed = directory.resolve("replayed.jsonl");
    Process replay =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty(AnalyzerProcess.CLASSPATH_PROPERTY),
                "dev.spanlease.analyzer.ReplayMain",
                directory.resolve("observations.replay.json").toString(),
                replayed.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    assertThat(replay.waitFor(20, TimeUnit.SECONDS)).isTrue();
    assertThat(replay.exitValue()).isZero();
    List<String> observed = new ArrayList<>();
    for (var report : analyzer.reports()) observed.add(json.writeValueAsString(report.report()));
    assertThat(Files.readAllLines(replayed)).isEqualTo(observed);
  }

  @Test
  void outputListenerFailureCannotBeReportedAsCleanShutdown() throws Exception {
    var plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    Path directory =
        Files.createDirectories(
            Path.of(System.getProperty("spanlease.integration.runs"))
                .resolve(plan.manifest().runId() + "-listener-failure"));
    Path manifest = directory.resolve("manifest.json");
    ContractJson.mapper().writeValue(manifest.toFile(), plan.manifest());
    var analyzer =
        AnalyzerProcess.start(
            manifest,
            ignored -> {
              throw new IllegalStateException("test listener failure");
            });
    try {
      assertThatThrownBy(() -> analyzer.awaitReport(ignored -> false, Duration.ofSeconds(10)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("output failed");
    } finally {
      assertThatThrownBy(analyzer::close)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("did not shut down cleanly");
    }
    assertThat(analyzer.gracefulShutdown()).isFalse();
  }
}
