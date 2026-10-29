package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Owns an analyzer and a separate killable workload. Process exit is never a detector verdict. */
final class MonitorRun implements AutoCloseable {
  private final Process application;
  private final AnalyzerProcess analyzer;
  private final MonitorState state;
  private final Path directory;
  private final Scenario scenario;
  private final InetSocketAddress edgeBind;
  private boolean injectRequested;

  private MonitorRun(
      Process application,
      AnalyzerProcess analyzer,
      MonitorState state,
      Path directory,
      Scenario scenario,
      InetSocketAddress edgeBind) {
    this.application = application;
    this.analyzer = analyzer;
    this.state = state;
    this.directory = directory;
    this.scenario = scenario;
    this.edgeBind = edgeBind;
  }

  static MonitorRun start(RunPlan plan, Path runs, MonitorState state) throws Exception {
    return start(plan, runs, state, new InetSocketAddress("127.0.0.1", 9555));
  }

  static MonitorRun start(RunPlan plan, Path runs, MonitorState state, InetSocketAddress edgeBind)
      throws Exception {
    Path directory =
        Files.createDirectories(runs.resolve(plan.manifest().runId())).toAbsolutePath();
    Path manifest = directory.resolve("manifest.json");
    ContractJson.mapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(manifest.toFile(), plan.manifest());
    state.artifacts(directory.toString());
    if (plan.scenario().application() == Scenario.Application.BOUTIQUE) {
      ContractJson.mapper()
          .writerWithDefaultPrettyPrinter()
          .writeValue(
              directory.resolve("boutique-config.json").toFile(),
              new BoutiqueConfig(
                  plan.scenario().name(),
                  42,
                  edgeBind.getHostString() + ":" + edgeBind.getPort(),
                  "v0.10.7",
                  "38e7348eb289eb5b87c0c6e8cb19ced0449dc389"));
    }
    AnalyzerProcess analyzer = AnalyzerProcess.start(manifest, state::report, state::observe);
    try {
      int port = analyzer.awaitReady(Duration.ofSeconds(30));
      Process application =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Dspanlease.boutique.edge="
                      + edgeBind.getHostString()
                      + ":"
                      + edgeBind.getPort(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  MonitorApplication.class.getName(),
                  manifest.toString(),
                  plan.scenario().name(),
                  "http://127.0.0.1:" + port)
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      state.applicationStarted(application.pid());
      state.progress(
          "Application PID "
              + application.pid()
              + "; monitor PID "
              + ProcessHandle.current().pid()
              + " (separate JVMs)");
      state.progress(
          plan.scenario().manualStop()
              ? "Manual workload: kill the application PID to stop."
              : "Finite control workload with a 30-second RPC deadline.");
      return new MonitorRun(application, analyzer, state, directory, plan.scenario(), edgeBind);
    } catch (Exception failure) {
      analyzer.close();
      throw failure;
    }
  }

  synchronized boolean inject() throws IOException {
    if (injectRequested || !application.isAlive() || !state.snapshot().injectable()) return false;
    application.getOutputStream().write("INJECT\n".getBytes(StandardCharsets.UTF_8));
    application.getOutputStream().flush();
    injectRequested = true;
    return true;
  }

  void awaitExit() throws Exception {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(application.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.startsWith("DIAGNOSTICS ")) {
          state.diagnostics(
              ContractJson.mapper()
                  .readValue(line.substring(12), RuntimeDiagnostics.Snapshot.class));
        } else if (line.startsWith("COMPLETE ")) {
          var completion =
              ContractJson.mapper()
                  .readValue(line.substring(9), MonitorApplication.Completion.class);
          if (scenario.application() == Scenario.Application.BOUTIQUE) {
            state.faultCompleted(completion.outcome(), completion.ads());
          } else {
            state.applicationCompleted(completion.outcome(), completion.hopsCompleted());
          }
        } else if (line.startsWith("STORE ")) {
          state.store(ContractJson.mapper().readValue(line.substring(6), StoreStats.class));
        } else if (line.startsWith("INJECTED ")) {
          state.injected(ContractJson.mapper().readTree(line.substring(9)).get("at_ms").asLong());
        }
      }
    }
    int exit = application.waitFor();
    analyzer.close();
    state.progress(
        "Application PID "
            + application.pid()
            + " exited (code "
            + exit
            + "). Historical evidence retained.");
    MonitorState.Snapshot completed;
    synchronized (state) {
      if (scenario.application() == Scenario.Application.BOUTIQUE
          && state.snapshot().store() == null
          && exit != 0) {
        state.fail(
            "Boutique workload failed to start on "
                + edgeBind.getHostString()
                + ":"
                + edgeBind.getPort());
      }
      state.applicationExited(exit);
      completed = state.snapshot();
    }
    archive(completed);
  }

  private record BoutiqueConfig(
      String scenario, long seed, String edgeBind, String imageTag, String upstreamCommit) {}

  private void archive(MonitorState.Snapshot snapshot) throws java.io.IOException {
    var json = ContractJson.mapper();
    Files.write(
        directory.resolve("reports.jsonl"),
        snapshot.reports().stream()
            .map(
                report -> {
                  try {
                    return json.writeValueAsString(report);
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                })
            .toList(),
        StandardCharsets.UTF_8);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("monitor-result.json").toFile(), snapshot);
    if (snapshot.firstDeadlock() != null) {
      json.writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("first-deadlock.json").toFile(), snapshot.firstDeadlock());
    }
  }

  @Override
  public void close() throws InterruptedException {
    application.destroy();
    if (!application.waitFor(5, TimeUnit.SECONDS)) {
      application.destroyForcibly();
      application.waitFor(5, TimeUnit.SECONDS);
    }
    analyzer.close();
  }
}
