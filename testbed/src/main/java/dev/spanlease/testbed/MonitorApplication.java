package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.RunManifest;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** The killable workload JVM. All Chain services share this PID; the monitor/analyzer do not. */
public final class MonitorApplication {
  private MonitorApplication() {}

  public static void main(String[] args) throws Exception {
    var json = ContractJson.mapper();
    RunManifest manifest = json.readValue(Path.of(args[0]).toFile(), RunManifest.class);
    Scenario scenario =
        switch (args[1]) {
          case "closed-ring" -> Scenario.closedRing();
          case "spare-capacity" -> Scenario.spareCapacity();
          case "slow-progress" -> Scenario.slowProgress(3);
          default -> throw new IllegalArgumentException("unknown monitor scenario");
        };
    Map<String, String> instances =
        manifest.instances().stream()
            .filter(instance -> !instance.serviceBinding().equals("driver"))
            .collect(
                Collectors.toMap(
                    RunManifest.Instance::serviceBinding, RunManifest.Instance::instanceId));
    String driver =
        manifest.instances().stream()
            .filter(instance -> instance.serviceBinding().equals("driver"))
            .findFirst()
            .orElseThrow()
            .instanceId();
    RunPlan plan = new RunPlan(scenario, driver, instances, manifest);
    RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();
    var sampler = Executors.newSingleThreadScheduledExecutor();
    try (ChainDeployment deployment =
        ChainDeployment.start(
            plan, args[2], service -> () -> UUID.randomUUID().toString(), diagnostics)) {
      sampler.scheduleAtFixedRate(
          () -> {
            try {
              System.out.println("DIAGNOSTICS " + json.writeValueAsString(diagnostics.sample()));
            } catch (Exception failure) {
              System.err.println("Diagnostic sample unavailable: " + failure.getMessage());
            }
          },
          0,
          500,
          TimeUnit.MILLISECONDS);
      boolean manual = scenario.name().equals("closed-ring");
      ChainDriver.RootCall root =
          manual
              ? deployment.driver().submitUntilStopped("root-1", scenario.rootHops())
              : deployment.driver().submit("root-1", scenario.rootHops(), scenario.rootDeadline());
      try {
        var reply = root.reply().get();
        System.out.println(
            "COMPLETE " + json.writeValueAsString(new Completion("OK", reply.getHopsCompleted())));
      } catch (java.util.concurrent.ExecutionException failure) {
        System.out.println(
            "COMPLETE "
                + json.writeValueAsString(new Completion(root.outcome().orElseThrow().name(), 0)));
      }
    } finally {
      sampler.shutdownNow();
      sampler.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  public record Completion(String outcome, int hopsCompleted) {}
}
