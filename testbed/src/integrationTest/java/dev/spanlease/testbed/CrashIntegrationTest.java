package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.Verdict;
import dev.spanlease.instrumentation.OtlpTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** R13: separate service JVM is actually killed while owning a worker and blocking downstream. */
@Timeout(90)
class CrashIntegrationTest {
  @Test
  void hardCrashStopsCheckpointHorizonWithoutFabricatedRelease() throws Exception {
    var workload = Workload.named("closed-ring");
    var plan = RunPlan.create(workload.scenario(), System.currentTimeMillis());
    Path directory =
        Files.createDirectories(
            Path.of(System.getProperty("spanlease.integration.runs"))
                .resolve(plan.manifest().runId() + "-crash"));
    Path manifest = directory.resolve("manifest.json");
    var json = ContractJson.mapper();
    json.writeValue(manifest.toFile(), plan.manifest());
    try (var analyzer = AnalyzerProcess.start(manifest, ignored -> {})) {
      String endpoint = "http://127.0.0.1:" + analyzer.awaitReady(Duration.ofSeconds(30));
      var configA =
          new NodeProcess.Configuration(
              "A",
              plan.instanceByService().get("A"),
              1,
              plan.manifest().parameters(),
              endpoint,
              directory.resolve("A-truth.json").toString());
      var configB =
          new NodeProcess.Configuration(
              "B",
              plan.instanceByService().get("B"),
              1,
              plan.manifest().parameters(),
              endpoint,
              directory.resolve("B-truth.json").toString());
      try (var a = new ForkedNode(configA, directory.resolve("A-config.json"));
          var b = new ForkedNode(configB, directory.resolve("B-config.json"));
          var telemetry = OtlpTelemetry.start(endpoint, plan.driverInstanceId(), 8192)) {
        Map<String, Integer> ports = Map.of("A", a.port(), "B", b.port());
        a.connect(ports);
        b.connect(ports);
        var ids = new AtomicInteger();
        try (var driver =
            new ChainDriver(
                plan.driverInstanceId(),
                plan.manifest().parameters(),
                telemetry.sink(),
                () -> "driver-call-" + ids.incrementAndGet())) {
          driver.connect(ports);
          var root =
              driver.submit(
                  "crash-root", workload.roots().getFirst().hops(), Duration.ofSeconds(30));
          try {
            assertThat(
                    analyzer.awaitReport(
                        r -> r.report().verdict() == Verdict.CONFIRMED_DEADLOCK,
                        Duration.ofSeconds(15)))
                .isTrue();
            long crashedAt = b.crash();
            json.writeValue(
                directory.resolve("crash.json").toFile(),
                Map.of(
                    "service",
                    "B",
                    "pid",
                    b.pid(),
                    "requested_at_ms",
                    crashedAt,
                    "truth_truncated",
                    true));
            long staleAfter =
                crashedAt + plan.manifest().parameters().checkpointFreshnessMs() + 500;
            assertThat(
                    analyzer.awaitReport(
                        r ->
                            r.report().evaluatedAtWallMs() > staleAfter
                                && r.report().coverage().missing().stream()
                                    .anyMatch(m -> m.code().equals("STALE_CHECKPOINT")),
                        Duration.ofSeconds(10)))
                .isTrue();
            assertThat(
                    analyzer.reports().stream()
                        .filter(r -> r.report().evaluatedAtWallMs() > staleAfter))
                .allMatch(r -> r.report().verdict() == Verdict.CANDIDATE_INCONCLUSIVE);
            var snapshot = json.readTree(directory.resolve("B-truth.json").toFile());
            assertThat(snapshot.get("scorable").asBoolean()).isTrue();
            assertThat(snapshot.get("entries").toString())
                .contains("CALL_ENTER")
                .doesNotContain("RELEASE");
          } finally {
            root.cancel();
          }
        }
      }
    }
  }
}
