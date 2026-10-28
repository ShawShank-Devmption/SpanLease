package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.RunManifest;
import io.grpc.Status;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** The killable workload JVM. All Chain services share this PID; the monitor/analyzer do not. */
public final class MonitorApplication {
  private MonitorApplication() {}

  public static void main(String[] args) throws Exception {
    var json = ContractJson.mapper();
    RunManifest manifest = json.readValue(Path.of(args[0]).toFile(), RunManifest.class);
    Scenario scenario = Scenario.named(args[1]);
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
    if (scenario.application() == Scenario.Application.BOUTIQUE) {
      runBoutique(plan, args[2], diagnostics);
      return;
    }
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
      boolean manual = scenario.rootWithoutDeadline();
      ChainDriver.RootCall root =
          manual
              ? deployment.driver().submitUntilStopped("root-1", scenario.rootHops())
              : deployment.driver().submit("root-1", scenario.rootHops(), scenario.rootDeadline());
      try {
        var reply = root.reply().get();
        System.out.println(
            "COMPLETE "
                + json.writeValueAsString(
                    new Completion("OK", reply.getHopsCompleted(), List.of())));
      } catch (java.util.concurrent.ExecutionException failure) {
        System.out.println(
            "COMPLETE "
                + json.writeValueAsString(
                    new Completion(root.outcome().orElseThrow().name(), 0, List.of())));
      }
    } finally {
      sampler.shutdownNow();
      sampler.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static void runBoutique(RunPlan plan, String endpoint, RuntimeDiagnostics diagnostics)
      throws Exception {
    var json = ContractJson.mapper();
    InetSocketAddress bind =
        edgeBind(System.getProperty("spanlease.boutique.edge", "127.0.0.1:9555"));
    var sampler = Executors.newSingleThreadScheduledExecutor();
    try (BoutiqueDeployment deployment =
        BoutiqueDeployment.start(plan, endpoint, bind, 42, diagnostics)) {
      sampler.scheduleAtFixedRate(
          () -> {
            try {
              System.out.println("DIAGNOSTICS " + json.writeValueAsString(diagnostics.sample()));
              System.out.println(
                  "STORE " + json.writeValueAsString(deployment.store(System.currentTimeMillis())));
            } catch (Exception failure) {
              System.err.println("Boutique sample unavailable: " + failure.getMessage());
            }
          },
          0,
          500,
          TimeUnit.MILLISECONDS);
      Thread controls =
          new Thread(
              () -> {
                try (var input =
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                  for (String line; (line = input.readLine()) != null; ) {
                    if (!line.equals("INJECT")) continue;
                    try {
                      var fault = deployment.inject();
                      System.out.println(
                          "INJECTED "
                              + json.writeValueAsString(
                                  Map.of("at_ms", System.currentTimeMillis())));
                      fault.whenComplete(
                          (reply, failure) -> {
                            try {
                              Completion completion =
                                  failure == null
                                      ? new Completion(
                                          "OK",
                                          0,
                                          reply.getAdsList().stream()
                                              .map(
                                                  ad ->
                                                      new StoreStats.AdView(
                                                          ad.getRedirectUrl(), ad.getText()))
                                              .toList())
                                      : new Completion(
                                          Status.fromThrowable(failure).getCode().name(),
                                          0,
                                          List.of());
                              System.out.println("COMPLETE " + json.writeValueAsString(completion));
                            } catch (Exception encoding) {
                              System.err.println(
                                  "Boutique completion unavailable: " + encoding.getMessage());
                            }
                          });
                    } catch (IllegalStateException refused) {
                      System.err.println("Fault injection refused: " + refused.getMessage());
                    }
                  }
                } catch (Exception failure) {
                  System.err.println("Boutique control unavailable: " + failure.getMessage());
                }
              },
              "boutique-controls");
      controls.setDaemon(true);
      controls.start();
      new CountDownLatch(1).await();
    } finally {
      sampler.shutdownNow();
      sampler.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  static InetSocketAddress edgeBind(String value) {
    int colon = value.lastIndexOf(':');
    if (colon <= 0) throw new IllegalArgumentException("edge bind must be host:port");
    return new InetSocketAddress(
        value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
  }

  public record Completion(String outcome, int hopsCompleted, List<StoreStats.AdView> ads) {}
}
