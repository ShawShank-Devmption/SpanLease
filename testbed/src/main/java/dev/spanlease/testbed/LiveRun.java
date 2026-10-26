package dev.spanlease.testbed;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.spanlease.common.ContractJson;
import dev.spanlease.common.Verdict;
import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One finite instrumented run, end to end (A-HARNESS).
 *
 * <p>Writes the manifest, starts the analyzer process, deploys the services exporting OTLP to it,
 * sends the scenario's root request and observes until the scenario window ends or the root
 * completes. Only then does it cancel and drain — detector verdicts never steer the run. The
 * handler journal stays on this side for the evaluator; manifest, reports and journal are written
 * to the run directory.
 */
public final class LiveRun {
  private static final Duration ANALYZER_START = Duration.ofSeconds(30);
  private static final Duration COMPLETION_GRACE = Duration.ofSeconds(1);
  private static final Duration DRAIN = Duration.ofSeconds(10);
  private static final Duration FINAL_REPORT = Duration.ofSeconds(3);

  private LiveRun() {}

  public static Result execute(
      RunPlan plan,
      Path runsDirectory,
      Function<String, Supplier<String>> invocationIds,
      Consumer<String> progress,
      Consumer<AnalyzerProcess.TimedReport> reports)
      throws Exception {
    Scenario scenario = plan.scenario();
    ObjectMapper json = ContractJson.mapper();
    Path directory = Files.createDirectories(runsDirectory.resolve(plan.manifest().runId()));
    Path manifestFile = directory.resolve("manifest.json");
    json.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), plan.manifest());

    try (AnalyzerProcess analyzer = AnalyzerProcess.start(manifestFile, reports)) {
      int port = analyzer.awaitReady(ANALYZER_START);
      progress.accept("analyzer process listening for OTLP on 127.0.0.1:" + port);
      ChainDriver.RootCall root;
      List<HandlerJournal.Entry> journal;
      ChainDeployment deployment =
          ChainDeployment.start(plan, "http://127.0.0.1:" + port, invocationIds);
      try (deployment) {
        progress.accept(
            "deployed "
                + scenario.capacities().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .map(
                        entry ->
                            entry.getKey()
                                + "("
                                + entry.getValue()
                                + " slot"
                                + (entry.getValue() == 1 ? "" : "s")
                                + ")")
                    .collect(Collectors.joining(", ")));
        root = deployment.driver().submit("root-1", scenario.rootHops(), scenario.rootDeadline());
        progress.accept(
            "root gRPC request sent: "
                + scenario.rootHops().stream()
                    .map(Hop::getTargetService)
                    .collect(Collectors.joining(" → "))
                + " (deadline "
                + scenario.rootDeadline().toSeconds()
                + "s)");

        Optional<Long> completedAt = awaitCompletion(root, scenario.observationWindow());
        if (completedAt.isPresent()) {
          progress.accept("root request completed: " + root.outcome().orElseThrow());
          long settled = completedAt.orElseThrow() + COMPLETION_GRACE.toMillis();
          analyzer.awaitReport(
              report -> report.report().evaluatedAtWallMs() >= settled,
              COMPLETION_GRACE.multipliedBy(3));
        } else {
          progress.accept("observation window ended; cancelling the root request");
        }
        root.cancel();
        awaitCompletion(root, DRAIN);
      }
      journal = deployment.journal().entries();
      json.writeValue(directory.resolve("truth.json").toFile(), deployment.truth().entries());
      json.writeValue(directory.resolve("mappings.json").toFile(), deployment.mappings().entries());
      if (!deployment.truth().scorable())
        throw new IllegalStateException("truth overflow; run is unscorable");
      long closedAt = System.currentTimeMillis();
      analyzer.awaitReport(report -> report.report().evaluatedAtWallMs() > closedAt, FINAL_REPORT);

      Result result =
          new Result(
              plan,
              root.sentAtMs(),
              root.deadlineAtMs(),
              root.outcome().orElse(Status.Code.UNKNOWN),
              root.outcome().orElse(Status.Code.UNKNOWN) == Status.Code.OK
                  ? root.reply().join().getHopsCompleted()
                  : 0,
              analyzer.reports(),
              journal,
              directory);
      write(directory, result, json);
      return result;
    }
  }

  /** Returns the completion time if the root finished within the timeout. */
  private static Optional<Long> awaitCompletion(ChainDriver.RootCall root, Duration timeout)
      throws InterruptedException {
    try {
      CallReply ignored = root.reply().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException running) {
      return Optional.empty();
    } catch (ExecutionException | java.util.concurrent.CancellationException finished) {
      // Completed with a failure status; the outcome is read from the RootCall.
    }
    return Optional.of(System.currentTimeMillis());
  }

  private static void write(Path directory, Result result, ObjectMapper json) throws IOException {
    List<String> lines = new ArrayList<>();
    for (AnalyzerProcess.TimedReport report : result.reports()) {
      lines.add(json.writeValueAsString(report.report()));
    }
    Files.write(directory.resolve("reports.jsonl"), lines, StandardCharsets.UTF_8);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("handler-journal.json").toFile(), result.journal());
  }

  /** Everything the evaluator needs; nothing here was given to the analyzer except the manifest. */
  public record Result(
      RunPlan plan,
      long rootSentAtMs,
      long rootDeadlineAtMs,
      Status.Code rootOutcome,
      int rootHopsCompleted,
      List<AnalyzerProcess.TimedReport> reports,
      List<HandlerJournal.Entry> journal,
      Path directory) {
    public Optional<AnalyzerProcess.TimedReport> first(Verdict verdict) {
      return reports.stream().filter(report -> report.report().verdict() == verdict).findFirst();
    }

    /** The final hop's handler interval at its own service, from the handler journal. */
    public Optional<Interval> leafRun() {
      String leaf = plan.scenario().rootHops().getLast().getTargetService();
      Optional<Long> ran = boundary(leaf, HandlerJournal.Boundary.RUN);
      Optional<Long> returned = boundary(leaf, HandlerJournal.Boundary.HANDLER_RETURN);
      return ran.isPresent() && returned.isPresent()
          ? Optional.of(new Interval(ran.orElseThrow(), returned.orElseThrow()))
          : Optional.empty();
    }

    /** Reports with the given verdict whose certified cut lies strictly inside the interval. */
    public List<AnalyzerProcess.TimedReport> certifiedWithin(Verdict verdict, Interval interval) {
      return reports.stream()
          .filter(timed -> timed.report().verdict() == verdict)
          .filter(
              timed -> {
                Long horizon = timed.report().query().certifiedThroughWallMs();
                return horizon != null && horizon > interval.fromMs() && horizon < interval.toMs();
              })
          .toList();
    }

    private Optional<Long> boundary(String service, HandlerJournal.Boundary boundary) {
      return journal.stream()
          .filter(
              entry ->
                  entry.service().equals(service)
                      && entry.remainingHops() == 1
                      && entry.boundary() == boundary)
          .map(HandlerJournal.Entry::wallMs)
          .findFirst();
    }
  }

  public record Interval(long fromMs, long toMs) {}
}
