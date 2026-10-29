package dev.spanlease.analyzer;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.List;

/** Runs deterministic schema-v2 production-analyzer scenarios for demonstrations. */
public final class ProductionDemo {
  private ProductionDemo() {}

  public static void main(String[] args) throws Exception {
    System.out.println("closed-ring");
    System.out.println(ContractJson.mapper().writeValueAsString(evaluate(false)));
    System.out.println("spare-capacity");
    System.out.println(ContractJson.mapper().writeValueAsString(evaluate(true)));
  }

  static VerdictReport evaluate(boolean spareCapacity) {
    RunManifest manifest = manifest(spareCapacity);
    AnalyzerEngine analyzer = new AnalyzerEngine(manifest);
    observations(spareCapacity).forEach(analyzer::ingest);
    return analyzer.evaluate(3_100);
  }

  static RunManifest manifest(boolean spareCapacity) {
    List<String> aUnits = spareCapacity ? List.of("A0", "A1") : List.of("A0");
    return new RunManifest(
        2,
        spareCapacity ? "demo-spare" : "demo-deadlock",
        spareCapacity ? "sha256:demo-spare" : "sha256:demo-deadlock",
        List.of(
            new RunManifest.Instance("A", "svcA", 0, 10_000, 10),
            new RunManifest.Instance("B", "svcB", 0, 10_000, 10),
            new RunManifest.Instance("C", "svcC", 0, 10_000, 10)),
        List.of(
            new RunManifest.Pool("A", "main", aUnits, true),
            new RunManifest.Pool("B", "main", List.of("B0"), true),
            new RunManifest.Pool("C", "main", List.of("C0"), true)),
        new RunManifest.Parameters(2_000, 1_000, 10, 250, 500, 1_000, 500));
  }

  static List<ObservationEnvelope> observations(boolean spareCapacity) {
    List<ObservationEnvelope> events = new ArrayList<>();
    List<String> aUnits = spareCapacity ? List.of("A0", "A1") : List.of("A0");

    events.add(event("A", 1, "", 0, new EventPayload.ResourceInit("main", aUnits.size(), aUnits)));
    events.add(event("A", 2, "", 100, new EventPayload.ResourceAcquire("a0", "A0", "root-a")));
    events.add(event("A", 3, "", 200, new EventPayload.RpcBlockBegin("a0", "inv-ab")));
    events.add(event("A", 4, "", 210, new EventPayload.RpcInvocationSent("inv-ab", "a0", "svcB")));
    events.add(event("A", 5, "C#5", 300, new EventPayload.RpcInvocationArrived("inv-ca")));
    events.add(event("A", 6, "", 310, new EventPayload.ResourceWaitBegin("inv-ca", "main")));
    events.add(
        event(
            "A",
            7,
            "",
            2_800,
            new EventPayload.ExecutionLease("a0", "inv-ab", "A#2", "A#3", 2_600, 5_000)));
    events.add(event("A", 8, "", 3_000, new EventPayload.InstanceCheckpoint(7, 0)));

    events.add(event("B", 1, "", 0, new EventPayload.ResourceInit("main", 1, List.of("B0"))));
    events.add(event("B", 2, "A#4", 220, new EventPayload.RpcInvocationArrived("inv-ab")));
    events.add(event("B", 3, "", 230, new EventPayload.ResourceAcquire("b0", "B0", "inv-ab")));
    events.add(event("B", 4, "", 240, new EventPayload.RpcBlockBegin("b0", "inv-bc")));
    events.add(event("B", 5, "", 250, new EventPayload.RpcInvocationSent("inv-bc", "b0", "svcC")));
    events.add(
        event(
            "B",
            6,
            "",
            2_800,
            new EventPayload.ExecutionLease("b0", "inv-bc", "B#3", "B#4", 2_560, 5_000)));
    events.add(event("B", 7, "", 3_000, new EventPayload.InstanceCheckpoint(6, 0)));

    events.add(event("C", 1, "", 0, new EventPayload.ResourceInit("main", 1, List.of("C0"))));
    events.add(event("C", 2, "B#5", 260, new EventPayload.RpcInvocationArrived("inv-bc")));
    events.add(event("C", 3, "", 270, new EventPayload.ResourceAcquire("c0", "C0", "inv-bc")));
    events.add(event("C", 4, "", 280, new EventPayload.RpcBlockBegin("c0", "inv-ca")));
    events.add(event("C", 5, "", 290, new EventPayload.RpcInvocationSent("inv-ca", "c0", "svcA")));
    events.add(
        event(
            "C",
            6,
            "",
            2_800,
            new EventPayload.ExecutionLease("c0", "inv-ca", "C#3", "C#4", 2_520, 5_000)));
    events.add(event("C", 7, "", 3_000, new EventPayload.InstanceCheckpoint(6, 0)));
    return List.copyOf(events);
  }

  private static ObservationEnvelope event(
      String instance, long sequence, String parent, long wallTimeMs, EventPayload payload) {
    ObservationEvent event =
        new ObservationEvent(
            2, instance, sequence, parent, wallTimeMs, wallTimeMs * 1_000_000, "", "", payload);
    return new ObservationEnvelope(event, wallTimeMs, wallTimeMs * 1_000_000);
  }
}
