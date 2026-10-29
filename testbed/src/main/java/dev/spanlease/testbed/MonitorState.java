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
  private final Map<String, Long> instanceLatestSeq = new TreeMap<>();
  private final Map<String, Map<String, SequencedPresence>> blockedByInstance = new HashMap<>();
  private final Map<String, Map<String, SequencedPresence>> queuedByInstance = new HashMap<>();

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
    instanceLatestSeq.clear();
    blockedByInstance.clear();
    queuedByInstance.clear();
    manifest
        .pools()
        .forEach(
            pool -> {
              instanceLatestSeq.put(pool.instanceId(), 0L);
              blockedByInstance.put(pool.instanceId(), new HashMap<>());
              queuedByInstance.put(pool.instanceId(), new HashMap<>());
              pool.unitIds()
                  .forEach(
                      unit -> {
                        unitInstances.put(unit, pool.instanceId());
                        unitLatest.put(
                            unit, new UnitView(unit, pool.instanceId(), "unknown", "", 0));
                      });
            });
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
    String instanceId = observation.event().instanceId();
    long localSeq = observation.event().localSeq();
    instanceLatestSeq.computeIfPresent(
        instanceId, (ignored, previous) -> Math.max(previous, localSeq));
    EventPayload payload = observation.event().payload();
    if (payload instanceof EventPayload.ResourceInit init) {
      init.unitIds().forEach(unit -> updateUnit(unit, "free", "", localSeq));
    } else if (payload instanceof EventPayload.ResourceWaitBegin wait) {
      updatePresence(queuedByInstance, instanceId, wait.invocationId(), localSeq, true);
    } else if (payload instanceof EventPayload.ResourceAcquire acquire) {
      updatePresence(queuedByInstance, instanceId, acquire.invocationId(), localSeq, false);
      updateUnit(acquire.resourceInstanceId(), "held", acquire.executionId(), localSeq);
    } else if (payload instanceof EventPayload.RpcBlockBegin block) {
      updatePresence(blockedByInstance, instanceId, block.executionId(), localSeq, true);
    } else if (payload instanceof EventPayload.RpcBlockEnd block) {
      updatePresence(blockedByInstance, instanceId, block.executionId(), localSeq, false);
    } else if (payload instanceof EventPayload.ExecutionCancel cancel
        && cancel.executionId().isEmpty()) {
      updatePresence(queuedByInstance, instanceId, cancel.invocationId(), localSeq, false);
    } else if (payload instanceof EventPayload.ExecutionEnd end) {
      updatePresence(blockedByInstance, instanceId, end.executionId(), localSeq, false);
    } else if (payload instanceof EventPayload.ResourceRelease release) {
      updatePresence(blockedByInstance, instanceId, release.executionId(), localSeq, false);
      updateUnit(release.resourceInstanceId(), "free", "", localSeq);
    }
  }

  private void updateUnit(String unitId, String state, String executionId, long localSeq) {
    String instanceId = unitInstances.get(unitId);
    UnitView previous = unitLatest.get(unitId);
    if (instanceId != null && (previous == null || localSeq > previous.localSeq())) {
      unitLatest.put(unitId, new UnitView(unitId, instanceId, state, executionId, localSeq));
    }
  }

  private static void updatePresence(
      Map<String, Map<String, SequencedPresence>> states,
      String instanceId,
      String entityId,
      long localSeq,
      boolean present) {
    Map<String, SequencedPresence> byEntity = states.get(instanceId);
    if (byEntity == null) return;
    SequencedPresence previous = byEntity.get(entityId);
    if (previous == null || localSeq > previous.localSeq()) {
      byEntity.put(entityId, new SequencedPresence(localSeq, present));
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
    if (!phase.equals("FAILED")) {
      phase =
          scenario.application() == Scenario.Application.CHAIN && applicationCompleted
              ? "COMPLETE"
              : "STOPPED";
    }
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

  synchronized boolean canInject() {
    return scenario != null && scenario.injectable() && applicationAlive && injectedAtMs == null;
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
        canInject(),
        injectedAtMs,
        faultOutcome,
        faultAds,
        faultCompletedAtMs,
        store,
        instanceLatestSeq.entrySet().stream()
            .map(
                entry ->
                    new ServiceView(
                        entry.getKey(),
                        entry.getValue(),
                        activeCount(blockedByInstance.get(entry.getKey())),
                        activeCount(queuedByInstance.get(entry.getKey()))))
            .toList(),
        new ArrayList<>(unitLatest.values()));
  }

  private static int activeCount(Map<String, SequencedPresence> states) {
    if (states == null) return 0;
    return (int) states.values().stream().filter(SequencedPresence::present).count();
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
      List<ServiceView> serviceLatest,
      List<UnitView> unitLatest) {}

  record ServiceView(String instanceId, long localSeq, int blocked, int queued) {}

  record UnitView(
      String unitId, String instanceId, String state, String executionId, long localSeq) {}

  private record SequencedPresence(long localSeq, boolean present) {}
}
