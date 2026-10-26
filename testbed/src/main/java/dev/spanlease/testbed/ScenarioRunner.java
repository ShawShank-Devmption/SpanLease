package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.VerdictReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finite bare/instrumented multi-root runner. Roots stop on the workload clock, never a verdict.
 */
public final class ScenarioRunner {
  private ScenarioRunner() {}

  public static Result run(Workload workload, boolean instrumented, Path runs, long seed)
      throws Exception {
    return run(workload, instrumented, runs, seed, List.of());
  }

  public static Result run(
      Workload workload,
      boolean instrumented,
      Path runs,
      long seed,
      List<ObservationFaults.Rule> faults)
      throws Exception {
    RunPlan plan = RunPlan.create(workload.scenario(), System.currentTimeMillis(), instrumented);
    Path directory = Files.createDirectories(runs.resolve(plan.manifest().runId()));
    var json = ContractJson.mapper();
    Path manifest = directory.resolve("manifest.json");
    json.writeValue(manifest.toFile(), plan.manifest());
    json.writeValue(
        directory.resolve("configuration.json").toFile(),
        Map.of(
            "workload",
            workload.name(),
            "mode",
            instrumented ? "instrumented" : "bare",
            "seed",
            seed,
            "queue_capacity",
            workload.queueCapacity(),
            "roots",
            workload.roots().stream()
                .map(
                    r ->
                        Map.of(
                            "id",
                            r.id(),
                            "at_ms",
                            r.atMs(),
                            "hops",
                            r.hops().stream()
                                .map(
                                    h ->
                                        Map.of(
                                            "target_service",
                                            h.getTargetService(),
                                            "work_ms",
                                            h.getWorkMs(),
                                            "post_hold_ms",
                                            h.getPostHoldMs()))
                                .toList()))
                .toList(),
            "window_ms",
            workload.window().toMillis(),
            "deadline_ms",
            workload.rootDeadline().toMillis()));
    json.writeValue(directory.resolve("faults.json").toFile(), faults);
    AnalyzerProcess analyzer = instrumented ? AnalyzerProcess.start(manifest, ignored -> {}) : null;
    ChainDeployment deployment = null;
    SetupBarrier setup = null;
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    List<ChainDriver.RootCall> calls = new CopyOnWriteArrayList<>();
    long endedAt = 0;
    long analyzerCpuStarted = 0;
    long analyzerCpuUsed = 0;
    long cpuStarted = 0;
    long wallStarted = 0;
    long cpuUsed = 0;
    long wallUsed = 0;
    try {
      int port = analyzer == null ? 0 : analyzer.awaitReady(Duration.ofSeconds(30));
      deployment =
          ChainDeployment.start(
              plan,
              "http://127.0.0.1:" + port,
              service -> {
                var next = new AtomicInteger();
                return () -> service + "-" + seed + "-call-" + next.incrementAndGet();
              },
              instrumented,
              workload.queueCapacity(),
              faults,
              seed);
      if (workload.coordinatedStart()) {
        setup = new SetupBarrier(workload, deployment.truth());
        SetupBarrier barrier = setup;
        deployment.nodes().values().forEach(node -> node.setup(barrier::enter));
      }
      final ChainDeployment running = deployment;
      analyzerCpuStarted = analyzer == null ? 0 : analyzer.cpuNanos();
      cpuStarted = cpuNanos();
      wallStarted = System.nanoTime();
      List<CompletableFuture<Void>> completions = new ArrayList<>();
      for (Workload.Root spec : workload.roots()) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        completions.add(completion);
        scheduler.schedule(
            () -> {
              try {
                var call = running.driver().submit(spec.id(), spec.hops(), workload.rootDeadline());
                calls.add(call);
                call.reply().whenComplete((reply, failure) -> completion.complete(null));
              } catch (Throwable failure) {
                completion.completeExceptionally(failure);
              }
            },
            spec.atMs(),
            TimeUnit.MILLISECONDS);
      }
      if (setup != null) setup.releaseAfterAllArrive();
      long measurementStartedMs = System.currentTimeMillis();
      json.writeValue(
          directory.resolve("measurement.json").toFile(),
          Map.of(
              "started_at_ms",
              measurementStartedMs,
              "setup_barriers_released",
              true,
              "coordinated_start",
              workload.coordinatedStart()));
      try {
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
            .get(workload.window().toMillis(), TimeUnit.MILLISECONDS);
        endedAt = System.currentTimeMillis();
        cpuUsed = cpuNanos() - cpuStarted;
        analyzerCpuUsed = analyzer == null ? 0 : analyzer.cpuNanos() - analyzerCpuStarted;
        wallUsed = System.nanoTime() - wallStarted;
        if (analyzer != null) {
          long settled = System.currentTimeMillis() + 1_000;
          if (!analyzer.awaitReport(
              r -> r.report().evaluatedAtWallMs() >= settled, Duration.ofSeconds(3)))
            throw new IllegalStateException("no final analyzer report");
        }
      } catch (java.util.concurrent.TimeoutException expectedRunningWait) {
        // The independent workload window has ended; cancellation is outside measurement.
      }
      if (endedAt == 0) {
        endedAt = System.currentTimeMillis();
        cpuUsed = cpuNanos() - cpuStarted;
        analyzerCpuUsed = analyzer == null ? 0 : analyzer.cpuNanos() - analyzerCpuStarted;
        wallUsed = System.nanoTime() - wallStarted;
      }
    } finally {
      if (setup != null) setup.close();
      scheduler.shutdownNow();
      try {
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("root scheduler did not stop");
        calls.forEach(ChainDriver.RootCall::cancel);
        for (var call : calls)
          call.reply().handle((reply, failure) -> null).get(10, TimeUnit.SECONDS);
      } finally {
        try {
          if (deployment != null) deployment.close();
        } finally {
          if (analyzer != null) analyzer.close();
        }
      }
    }
    var truth = deployment.truth();
    json.writeValue(directory.resolve("truth.json").toFile(), truth.entries());
    json.writeValue(directory.resolve("mappings.json").toFile(), deployment.mappings().entries());
    List<VerdictReport> reports =
        analyzer == null
            ? List.of()
            : analyzer.reports().stream().map(AnalyzerProcess.TimedReport::report).toList();
    List<String> lines = new ArrayList<>();
    for (var report : reports) lines.add(json.writeValueAsString(report));
    Files.write(directory.resolve("reports.jsonl"), lines);
    if (!truth.scorable()) throw new IllegalStateException("truth overflow: run is unscorable");
    Set<String> expected =
        TruthOracle.closedJobs(truth.entries(), workload.capacities(), endedAt - 1);
    var result =
        new Result(
            workload.name(),
            instrumented,
            seed,
            endedAt,
            calls.stream()
                .map(
                    c ->
                        new Outcome(
                            c.requestId(),
                            c.outcome().orElse(io.grpc.Status.Code.UNKNOWN).name(),
                            c.outcome().orElse(io.grpc.Status.Code.UNKNOWN)
                                    == io.grpc.Status.Code.OK
                                ? c.reply().join().getHopsCompleted()
                                : 0,
                            c.latencyNanos(),
                            c.sentAtMs(),
                            c.deadlineAtMs()))
                .toList(),
            expected,
            reports,
            truth.entries(),
            deployment.mappings().entries(),
            directory.toString(),
            metrics(deployment, cpuUsed, wallUsed, analyzerCpuUsed));
    json.writeValue(directory.resolve("result.json").toFile(), result);
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !(args[1].equals("bare") || args[1].equals("instrumented")))
      throw new IllegalArgumentException("usage: workload bare|instrumented output-directory seed");
    var result =
        run(
            Workload.named(args[0]),
            args[1].equals("instrumented"),
            Path.of(args[2]),
            Long.parseLong(args[3]));
    System.out.println(
        ContractJson.mapper()
            .writeValueAsString(
                Map.of(
                    "directory",
                    result.directory(),
                    "truth_closed_jobs",
                    result.closedJobs(),
                    "outcomes",
                    result.outcomes())));
  }

  private static long cpuNanos() {
    return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
  }

  private static Map<String, Long> metrics(
      ChainDeployment deployment, long cpu, long wall, long analyzerCpu) {
    Map<String, Long> metrics = new java.util.TreeMap<>(deployment.telemetryMetrics());
    metrics.put("application_process_cpu_ns", cpu);
    metrics.put("analyzer_process_cpu_ns", analyzerCpu);
    metrics.put("observation_wall_ns", wall);
    Map<String, java.util.Set<String>> queues = new java.util.HashMap<>();
    long maxQueue = 0;
    for (var entry : deployment.truth().entries()) {
      if (entry.job() == null) continue;
      var current = queues.computeIfAbsent(entry.service(), ignored -> new java.util.HashSet<>());
      if (entry.transition().equals("QUEUE")) current.add(entry.job().id());
      else if (List.of("ASSIGN", "CANCEL", "REJECT").contains(entry.transition()))
        current.remove(entry.job().id());
      maxQueue = Math.max(maxQueue, current.size());
    }
    metrics.put("max_physical_queue_depth", maxQueue);
    return Map.copyOf(metrics);
  }

  public record Outcome(
      String root,
      String status,
      int hopsCompleted,
      long latencyNs,
      long sentAtMs,
      long deadlineAtMs) {}

  public record Result(
      String workload,
      boolean instrumented,
      long seed,
      long measurementEndedMs,
      List<Outcome> outcomes,
      Set<String> closedJobs,
      List<VerdictReport> reports,
      List<TruthRecorder.Entry> truth,
      List<EvaluationMappings.Binding> mappings,
      String directory,
      Map<String, Long> metrics) {}
}
