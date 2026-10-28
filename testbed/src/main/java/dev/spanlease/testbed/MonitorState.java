package dev.spanlease.testbed;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
  private Scenario scenario;
  private StoreStats store;
  private Long injectedAtMs;
  private String faultOutcome = "";
  private List<StoreStats.AdView> faultAds = List.of();
  private Long faultCompletedAtMs;
  private final Map<String, UnitView> unitLatest = new TreeMap<>();
  private final Map<String, String> unitInstances = new HashMap<>();

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
    scenario = plan.scenario();
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
    store = null;
    injectedAtMs = null;
    faultOutcome = "";
    faultAds = List.of();
    faultCompletedAtMs = null;
    unitLatest.clear();
    unitInstances.clear();
    manifest
        .pools()
        .forEach(
            pool ->
                pool.unitIds()
                    .forEach(
                        unit -> {
                          unitInstances.put(unit, pool.instanceId());
                          unitLatest.put(
                              unit, new UnitView(unit, pool.instanceId(), "unknown", "", 0));
                        }));
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
    EventPayload payload = observation.event().payload();
    if (payload instanceof EventPayload.ResourceInit init) {
      init.unitIds().forEach(unit -> updateUnit(unit, "free", "", observation.event().localSeq()));
    } else if (payload instanceof EventPayload.ResourceAcquire acquire) {
      updateUnit(
          acquire.resourceInstanceId(),
          "held",
          acquire.executionId(),
          observation.event().localSeq());
    } else if (payload instanceof EventPayload.ResourceRelease release) {
      updateUnit(release.resourceInstanceId(), "free", "", observation.event().localSeq());
    }
  }

  private void updateUnit(String unitId, String state, String executionId, long localSeq) {
    String instanceId = unitInstances.get(unitId);
    if (instanceId != null) {
      unitLatest.put(unitId, new UnitView(unitId, instanceId, state, executionId, localSeq));
    }
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

  synchronized void store(StoreStats sample) {
    store = sample;
    notifyAll();
  }

  synchronized void injected(long atMs) {
    injectedAtMs = atMs;
    notifyAll();
  }

  synchronized void faultCompleted(String rootOutcome, List<StoreStats.AdView> ads) {
    faultOutcome = rootOutcome;
    faultAds = List.copyOf(ads);
    faultCompletedAtMs = System.currentTimeMillis();
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
    Scenario.Application application =
        scenario == null ? Scenario.Application.CHAIN : scenario.application();
    return new Snapshot(
        phase,
        error,
        application == Scenario.Application.CHAIN
            ? "Live Chain · real local gRPC / OTLP · controlled workload"
            : "Online Boutique · live store traffic → SpanLease-instrumented Ad Service",
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
        deadlockDiagnostics,
        application.name(),
        application == Scenario.Application.CHAIN
            ? "spanlease.testbed.Chain/Call"
            : "hipstershop.AdService/GetAds",
        scenario != null && scenario.manualStop(),
        scenario != null && scenario.injectable() && applicationAlive && injectedAtMs == null,
        injectedAtMs,
        faultOutcome,
        faultAds,
        faultCompletedAtMs,
        store,
        new ArrayList<>(unitLatest.values()));
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
      RuntimeDiagnostics.Snapshot deadlockDiagnostics,
      String application,
      String nativeMethod,
      boolean manualStop,
      boolean injectable,
      Long injectedAtMs,
      String faultOutcome,
      List<StoreStats.AdView> faultAds,
      Long faultCompletedAtMs,
      StoreStats store,
      List<UnitView> unitLatest) {}

  record UnitView(
      String unitId, String instanceId, String state, String executionId, long localSeq) {}
}
