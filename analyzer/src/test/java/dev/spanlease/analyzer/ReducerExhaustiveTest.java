package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Bounded exhaustive predicate checks, independent of production reduction; not a proof. */
class ReducerExhaustiveTest {
  @Test
  void allSmallCompleteCapacitySnapshotsMatchSubsetEnumeration() {
    for (int pools = 1; pools <= 3; pools++) {
      int maxCapacity = pools <= 2 ? 2 : 1;
      for (int capacity = 1; capacity <= maxCapacity; capacity++) {
        int radix = pools + 2; // free, running, or blocked on a queue at one of the pools
        int states = (int) Math.pow(radix, pools * capacity);
        for (int code = 0; code < states; code++) check(pools, capacity, radix, code);
      }
    }
  }

  private static void check(int count, int capacity, int radix, int code) {
    Fixture f = new Fixture(count, capacity);
    Map<String, String> owners = new HashMap<>();
    Set<String> blocked = new HashSet<>();
    Set<String> queues = new HashSet<>();
    Map<String, String> counterpart = new HashMap<>();
    Map<String, Set<String>> eligible = new HashMap<>();
    Map<String, String> acquire = new HashMap<>();
    Map<String, String> blocks = new HashMap<>();
    List<Runnable> arrivals = new ArrayList<>();
    int rest = code;
    for (int p = 0; p < count; p++) {
      for (int slot = 0; slot < capacity; slot++) {
        int kind = rest % radix;
        rest /= radix;
        if (kind == 0) continue;
        String instance = "S" + p;
        String execution = instance + "e" + slot;
        String unit = instance + "u" + slot;
        owners.put(unit, execution);
        acquire.put(
            execution,
            f.add(
                instance,
                100,
                "",
                new EventPayload.ResourceAcquire(execution, unit, execution + "root")));
        if (kind == 1) continue;
        String invocation = execution + "call";
        String target = "S" + (kind - 2);
        blocked.add(execution);
        queues.add(invocation);
        counterpart.put(execution, invocation);
        eligible.put(invocation, f.units.get(target));
        blocks.put(
            execution,
            f.add(instance, 200, "", new EventPayload.RpcBlockBegin(execution, invocation)));
        String sent =
            f.add(
                instance,
                210,
                "",
                new EventPayload.RpcInvocationSent(invocation, execution, target));
        arrivals.add(
            () -> {
              f.add(target, 250, sent, new EventPayload.RpcInvocationArrived(invocation));
              f.add(target, 260, "", new EventPayload.ResourceWaitBegin(invocation, "app"));
            });
      }
    }
    arrivals.forEach(Runnable::run);
    for (String execution : blocked) {
      String source = execution.substring(0, 2);
      f.add(
          source,
          2_800,
          "",
          new EventPayload.ExecutionLease(
              execution,
              counterpart.get(execution),
              acquire.get(execution),
              blocks.get(execution),
              2_600,
              5_000));
    }
    for (String instance : f.units.keySet())
      f.add(instance, 3_000, "", new EventPayload.InstanceCheckpoint(f.sequences.get(instance), 0));
    var expected =
        TruthClosureOracle.maximalClosedResidual(
            new TruthClosureOracle.Snapshot(blocked, queues, counterpart, eligible, owners));
    var engine = new AnalyzerEngine(f.manifest);
    f.events.forEach(engine::ingest);
    var report = engine.evaluate(3_100);
    assertThat(report.verdict())
        .as("pools=%s capacity=%s code=%s", count, capacity, code)
        .isEqualTo(expected.isEmpty() ? Verdict.CONFIRMED_NO_DEADLOCK : Verdict.CONFIRMED_DEADLOCK);
    Set<String> actual = new HashSet<>();
    report.core().executions().forEach(e -> actual.add(e.id()));
    report.core().queueEntries().forEach(q -> actual.add(q.invocationId()));
    assertThat(actual).isEqualTo(expected);
  }

  private static final class Fixture {
    final Map<String, Set<String>> units = new TreeMap<>();
    final Map<String, Long> sequences = new TreeMap<>();
    final List<ObservationEnvelope> events = new ArrayList<>();
    final RunManifest manifest;

    Fixture(int count, int capacity) {
      List<RunManifest.Instance> instances = new ArrayList<>();
      List<RunManifest.Pool> pools = new ArrayList<>();
      for (int p = 0; p < count; p++) {
        String id = "S" + p;
        Set<String> slots = new TreeSet<>();
        for (int slot = 0; slot < capacity; slot++) slots.add(id + "u" + slot);
        units.put(id, slots);
        instances.add(new RunManifest.Instance(id, id, 0, 10_000, 10));
        pools.add(new RunManifest.Pool(id, "app", List.copyOf(slots), true));
        add(id, 0, "", new EventPayload.ResourceInit("app", capacity, List.copyOf(slots)));
      }
      manifest =
          new RunManifest(
              2,
              "exhaustive",
              "sha256:exhaustive",
              instances,
              pools,
              new RunManifest.Parameters(2_000, 1_000, 10, 250, 500, 1_000, 500));
    }

    String add(String id, long time, String parent, EventPayload payload) {
      long sequence = sequences.merge(id, 1L, Long::sum);
      var event =
          new ObservationEvent(
              2, id, sequence, parent, time, sequence * 1_000_000, "", "", payload);
      events.add(new ObservationEnvelope(event, 3_000, 3_000_000_000L));
      return event.eventReference();
    }
  }
}
