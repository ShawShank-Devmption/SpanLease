package dev.spanlease.testbed;

import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded operator view of received facts; never supplies input to the detector or classifies it.
 */
final class MonitorState {
  private final int observationLimit;
  private final ArrayDeque<ObservationEnvelope> observations = new ArrayDeque<>();
  private final ArrayDeque<VerdictReport> reports = new ArrayDeque<>();
  private final ArrayDeque<String> progress = new ArrayDeque<>();
  private String phase = "IDLE";
  private String error = "";
  private String outcome = "";
  private String artifacts = "";
  private RunManifest manifest;
  private VerdictReport latest;
  private VerdictReport firstDeadlock;
  private long total;
  private long discarded;
  private long startedAtMs;
  private int hopsCompleted;
  private Long applicationPid;
  private boolean applicationAlive;
  private boolean applicationCompleted;
  private Integer exitCode;
  private RuntimeDiagnostics.Snapshot diagnostics;
  private RuntimeDiagnostics.Snapshot deadlockDiagnostics;

  MonitorState(int observationLimit) {
    if (observationLimit <= 0)
      throw new IllegalArgumentException("observationLimit must be positive");
    this.observationLimit = observationLimit;
  }

  synchronized boolean begin(RunPlan plan) {
    if (phase.equals("RUNNING")) return false;
    observations.clear();
    reports.clear();
    progress.clear();
    manifest = plan.manifest();
    latest = null;
    firstDeadlock = null;
    error = "";
    outcome = "";
    artifacts = "";
    total = 0;
    discarded = 0;
    hopsCompleted = 0;
    applicationPid = null;
    applicationAlive = false;
    applicationCompleted = false;
    exitCode = null;
    diagnostics = null;
    deadlockDiagnostics = null;
    startedAtMs = System.currentTimeMillis();
    phase = "RUNNING";
    return true;
  }

  synchronized void observe(ObservationEnvelope observation) {
    total++;
    if (observations.size() == observationLimit) {
      observations.removeFirst();
      discarded++;
    }
    observations.addLast(observation);
  }

  synchronized void report(AnalyzerProcess.TimedReport timed) {
    latest = timed.report();
    if (latest.verdict() == Verdict.CONFIRMED_DEADLOCK && firstDeadlock == null) {
      firstDeadlock = latest;
      deadlockDiagnostics = diagnostics;
    }
    if (reports.size() == 200) reports.removeFirst();
    reports.addLast(latest);
    notifyAll();
  }

  synchronized void applicationStarted(long pid) {
    applicationPid = pid;
    applicationAlive = true;
    notifyAll();
  }

  synchronized void diagnostics(RuntimeDiagnostics.Snapshot sample) {
    if (applicationPid != null && sample.pid() == applicationPid) diagnostics = sample;
    notifyAll();
  }

  synchronized void applicationCompleted(String rootOutcome, int hops) {
    outcome = rootOutcome;
    hopsCompleted = hops;
    applicationCompleted = true;
    notifyAll();
  }

  synchronized void applicationExited(int code) {
    applicationAlive = false;
    exitCode = code;
    if (!phase.equals("FAILED")) phase = applicationCompleted ? "COMPLETE" : "STOPPED";
    notifyAll();
  }

  synchronized void artifacts(String directory) {
    artifacts = directory;
  }

  synchronized void progress(String message) {
    if (progress.size() == 16) progress.removeFirst();
    progress.addLast(message);
  }

  synchronized void finish(LiveRun.Result result) {
    outcome = result.rootOutcome().name();
    hopsCompleted = result.rootHopsCompleted();
    artifacts = result.directory().toAbsolutePath().toString();
    phase = "COMPLETE";
  }

  synchronized void fail(String reason) {
    error = reason;
    applicationAlive = false;
    phase = "FAILED";
    notifyAll();
  }

  synchronized Snapshot snapshot() {
    return new Snapshot(
        phase,
        error,
        "Live Chain · real local gRPC / OTLP · controlled workload",
        manifest,
        List.copyOf(observations),
        List.copyOf(reports),
        List.copyOf(progress),
        latest,
        firstDeadlock,
        total,
        discarded,
        startedAtMs,
        outcome,
        hopsCompleted,
        artifacts,
        applicationPid,
        applicationAlive,
        exitCode,
        diagnostics,
        deadlockDiagnostics);
  }

  record Snapshot(
      String phase,
      String error,
      String dataSource,
      RunManifest manifest,
      List<ObservationEnvelope> observations,
      List<VerdictReport> reports,
      List<String> progress,
      VerdictReport latestReport,
      VerdictReport firstDeadlock,
      long observedTotal,
      long discardedObservations,
      long startedAtMs,
      String rootOutcome,
      int rootHopsCompleted,
      String artifacts,
      Long applicationPid,
      boolean applicationAlive,
      Integer exitCode,
      RuntimeDiagnostics.Snapshot diagnostics,
      RuntimeDiagnostics.Snapshot deadlockDiagnostics) {}
}
