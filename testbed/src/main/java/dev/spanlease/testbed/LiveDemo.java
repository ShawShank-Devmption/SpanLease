package dev.spanlease.testbed;

import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * End-to-end SpanLease demonstration over real gRPC.
 *
 * <p>For each scenario: three instrumented Chain services and a root driver run in this JVM, each
 * with its own process epoch and OTLP/gRPC LogRecord pipeline; the analyzer runs as a separate
 * process, receives only the manifest and those observations, and evaluates the whole deployment
 * every 250 ms. Verdict changes are printed as they arrive. Run with {@code ./gradlew
 * :testbed:liveDemo}; artifacts are written under {@code testbed/build/live-runs}.
 */
public final class LiveDemo {
  private LiveDemo() {}

  public static void main(String[] args) throws Exception {
    Path runs = Path.of(args.length > 0 ? args[0] : "live-runs");
    System.out.println("SpanLease live demo — gRPC services → OTLP → analyzer process");
    for (Scenario scenario : List.of(Scenario.closedRing(), Scenario.spareCapacity())) {
      RunPlan plan = RunPlan.create(scenario, System.currentTimeMillis());
      Timeline timeline = new Timeline(plan);
      System.out.println();
      System.out.println("━━ " + scenario.name() + ": " + scenario.description());
      LiveRun.Result result =
          LiveRun.execute(
              plan,
              runs,
              service -> () -> UUID.randomUUID().toString(),
              timeline::progress,
              timeline::report);
      timeline.summarize(result);
    }
  }

  /** Prints progress and verdict changes relative to the start of each run. */
  private static final class Timeline {
    private final long startedAtMs = System.currentTimeMillis();
    private final Map<String, String> serviceByInstance;
    private String lastSignature;

    private Timeline(RunPlan plan) {
      this.serviceByInstance =
          plan.instanceByService().entrySet().stream()
              .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    private synchronized void progress(String message) {
      System.out.printf("[%+7.2fs] %s%n", seconds(System.currentTimeMillis()), message);
    }

    private synchronized void report(AnalyzerProcess.TimedReport timed) {
      VerdictReport report = timed.report();
      String signature =
          report.verdict()
              + report.coverage().missing().toString()
              + report.core().executions().stream().map(VerdictReport.Execution::id).toList();
      if (signature.equals(lastSignature)) {
        return;
      }
      lastSignature = signature;
      String missing =
          report.coverage().missing().stream()
              .map(gap -> gap.code() + "(" + readable(gap.subject()) + ")")
              .distinct()
              .collect(Collectors.joining(", "));
      Long horizon = report.query().certifiedThroughWallMs();
      System.out.printf(
          "[%+7.2fs] analyzer: %s%s%s%n",
          seconds(report.evaluatedAtWallMs()),
          report.verdict(),
          horizon == null ? "" : String.format("  (cut certified to %+.2fs)", seconds(horizon)),
          missing.isEmpty() ? "" : "  missing: " + missing);
      if (report.verdict() == Verdict.CONFIRMED_DEADLOCK) {
        printCore(report);
      }
    }

    private void printCore(VerdictReport report) {
      VerdictReport.Persistence persistence = Objects.requireNonNull(report.persistence());
      System.out.printf(
          "            persisted %.2fs ≥ τ %.2fs over the certified cut%n",
          persistence.narrowedSpanMs() / 1000.0, persistence.tauMs() / 1000.0);
      for (VerdictReport.Execution execution : report.core().executions()) {
        String held =
            report.core().units().stream()
                .filter(unit -> unit.ownerExecutionId().equals(execution.id()))
                .map(unit -> readable(unit.id()))
                .collect(Collectors.joining(", "));
        String waitsOn =
            report.core().edges().stream()
                .filter(edge -> edge.kind().equals("wait") && edge.from().equals(execution.id()))
                .map(edge -> readable(edge.to()))
                .findFirst()
                .orElse("?");
        System.out.printf(
            "            %-14s holds %-16s and is blocked on %s%n",
            readable(execution.id()), held, waitsOn);
      }
      for (VerdictReport.QueueEntry queued : report.core().queueEntries()) {
        System.out.printf(
            "            call %s is queued for a %s slot that only the blocked executions can free%n",
            readable(queued.invocationId()),
            serviceByInstance.getOrDefault(queued.instanceId(), "?"));
      }
    }

    private void summarize(LiveRun.Result result) {
      System.out.println("  ── result");
      result
          .first(Verdict.CONFIRMED_DEADLOCK)
          .ifPresentOrElse(
              deadlock -> {
                long detectedAt = deadlock.report().evaluatedAtWallMs();
                System.out.printf(
                    "     deadlock localized %.2fs after the root request, %.2fs before its RPC deadline%n",
                    (detectedAt - result.rootSentAtMs()) / 1000.0,
                    (result.rootDeadlineAtMs() - detectedAt) / 1000.0);
              },
              () -> {
                System.out.println("     no deadlock confirmed at any evaluation");
                result
                    .leafRun()
                    .ifPresent(
                        leaf ->
                            System.out.printf(
                                "     %d CONFIRMED_NO_DEADLOCK verdicts certified cuts inside the"
                                    + " %.2fs the final hop ran on the spare slot while its three"
                                    + " ancestors were blocked%n",
                                result.certifiedWithin(Verdict.CONFIRMED_NO_DEADLOCK, leaf).size(),
                                (leaf.toMs() - leaf.fromMs()) / 1000.0));
              });
      System.out.printf(
          "     root request outcome: %s (%d hops completed)%n",
          result.rootOutcome(), result.rootHopsCompleted());
      System.out.println("     artifacts: " + result.directory().toAbsolutePath());
    }

    /** Replaces process-epoch instance IDs with service names and shortens UUIDs. */
    private String readable(String identifier) {
      String result = identifier;
      for (Map.Entry<String, String> entry : serviceByInstance.entrySet()) {
        result = result.replace(entry.getKey(), entry.getValue());
      }
      result = result.replace(":app:", ":");
      return result.matches("[0-9a-f]{8}-[0-9a-f-]{27}") ? "inv-" + result.substring(0, 8) : result;
    }

    private double seconds(long wallMs) {
      return (wallMs - startedAtMs) / 1000.0;
    }
  }
}
