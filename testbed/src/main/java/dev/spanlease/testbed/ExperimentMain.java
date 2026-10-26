package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reproducible finite execution plan. Every attempt, including failure, is retained in a ledger.
 */
public final class ExperimentMain {
  private ExperimentMain() {}

  public record Trial(int index, long seed, String workload, boolean instrumented) {}

  private static Map<String, String> source() throws Exception {
    Process revision = new ProcessBuilder("git", "rev-parse", "HEAD").start();
    String commit =
        new String(
                revision.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .trim();
    if (revision.waitFor() != 0)
      throw new IllegalStateException("experiment must run from a Git checkout");
    Process status = new ProcessBuilder("git", "status", "--porcelain").start();
    String dirty =
        new String(status.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .trim();
    if (status.waitFor() != 0) throw new IllegalStateException("could not record source state");
    return Map.of("commit", commit, "working_tree_changes", dirty);
  }

  private static String executeTrial(Trial trial, Path output) throws Exception {
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D"
                    + AnalyzerProcess.CLASSPATH_PROPERTY
                    + "="
                    + System.getProperty(AnalyzerProcess.CLASSPATH_PROPERTY),
                "-cp",
                System.getProperty("java.class.path"),
                ScenarioRunner.class.getName(),
                trial.workload(),
                trial.instrumented() ? "instrumented" : "bare",
                output.toString(),
                Long.toString(trial.seed()))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    var text =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(
                    child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
              } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            });
    if (!child.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
      child.destroyForcibly();
      child.waitFor();
      throw new IllegalStateException("trial process exceeded 120 seconds");
    }
    String response = text.get(5, java.util.concurrent.TimeUnit.SECONDS).trim();
    if (child.exitValue() != 0)
      throw new IllegalStateException("trial process failed: " + child.exitValue());
    return ContractJson.mapper().readTree(response).get("directory").asText();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("usage: pilot|matrix repetitions output-directory");
    int repetitions = Integer.parseInt(args[1]);
    if (repetitions < 1) throw new IllegalArgumentException("positive repetitions required");
    Path output = Files.createDirectories(Path.of(args[2]));
    var json = ContractJson.mapper();
    List<Workload> workloads = new ArrayList<>(Workload.standard());
    workloads.add(Workload.load(20, 256));
    workloads.add(Workload.load(100, 4));
    if (args[0].equals("matrix")) {
      for (int length : List.of(2, 3, 5))
        for (int capacity : List.of(1, 2))
          for (int seconds : List.of(8, 30))
            workloads.add(Workload.ring(length, capacity, Duration.ofSeconds(seconds)));
    } else if (!args[0].equals("pilot"))
      throw new IllegalArgumentException("unknown experiment plan");
    List<Trial> trials = new ArrayList<>();
    int index = 0;
    for (int repeat = 0; repeat < repetitions; repeat++) {
      List<Boolean> modes = new ArrayList<>(List.of(false, true));
      for (Workload workload : workloads) {
        Collections.shuffle(modes, new Random(42L + repeat * 1_000L + index));
        for (boolean mode : modes)
          trials.add(new Trial(index++, 42L + repeat, workload.name(), mode));
      }
    }
    Path planFile = output.resolve("plan.json");
    if (Files.exists(planFile))
      throw new IllegalArgumentException("output already has a plan; use a new directory");
    json.writeValue(output.resolve("source.json").toFile(), source());
    json.writeValue(
        planFile.toFile(),
        Map.of(
            "kind",
            args[0],
            "repetitions",
            repetitions,
            "java_version",
            System.getProperty("java.version"),
            "os",
            System.getProperty("os.name"),
            "arch",
            System.getProperty("os.arch"),
            "trials",
            trials));
    int failures = 0;
    try (var ledger = Files.newBufferedWriter(output.resolve("attempts.jsonl"))) {
      for (Trial trial : trials) {
        try {
          String directory = executeTrial(trial, output);
          ledger.write(
              json.writeValueAsString(
                  Map.of("trial", trial, "status", "completed", "directory", directory)));
          System.out.println(
              "completed " + (trial.index() + 1) + "/" + trials.size() + " " + directory);
        } catch (Exception failure) {
          failures++;
          ledger.write(
              json.writeValueAsString(
                  Map.of("trial", trial, "status", "failed", "error", failure.toString())));
          failure.printStackTrace(System.err);
        }
        ledger.newLine();
        ledger.flush();
      }
    }
    if (failures != 0)
      throw new IllegalStateException(failures + " failed trials; preserved in attempts.jsonl");
  }
}
