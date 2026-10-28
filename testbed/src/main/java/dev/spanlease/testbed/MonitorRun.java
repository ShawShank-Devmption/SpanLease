package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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

  private MonitorRun(
      Process application, AnalyzerProcess analyzer, MonitorState state, Path directory) {
    this.application = application;
    this.analyzer = analyzer;
    this.state = state;
    this.directory = directory;
  }

  static MonitorRun start(RunPlan plan, Path runs, MonitorState state) throws Exception {
    Path directory =
        Files.createDirectories(runs.resolve(plan.manifest().runId())).toAbsolutePath();
    Path manifest = directory.resolve("manifest.json");
    ContractJson.mapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(manifest.toFile(), plan.manifest());
    state.artifacts(directory.toString());
    AnalyzerProcess analyzer = AnalyzerProcess.start(manifest, state::report, state::observe);
    try {
      int port = analyzer.awaitReady(Duration.ofSeconds(30));
      Process application =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
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
          plan.scenario().name().equals("closed-ring")
              ? "Manual closed ring: no automatic stop and no RPC deadline. Kill the application PID to stop."
              : "Finite control workload with a 30-second RPC deadline.");
      return new MonitorRun(application, analyzer, state, directory);
    } catch (Exception failure) {
      analyzer.close();
      throw failure;
    }
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
          state.applicationCompleted(completion.outcome(), completion.hopsCompleted());
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
      state.applicationExited(exit);
      completed = state.snapshot();
    }
    archive(completed);
  }

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
