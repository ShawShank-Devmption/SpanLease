package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Direct subset enumeration independently checks production over 64 complete three-slot histories.
 */
class ProductionReducerOracleTest {
  private static final List<String> HOSTS = List.of("A", "B", "C");

  @Test
  void everyThreeOwnerRoutingPatternMatchesExhaustiveClosureAndFixedContextReplay() {
    for (int pattern = 0; pattern < 64; pattern++) {
      Fixture fixture = fixture(pattern);
      Set<String> expected = TruthClosureOracle.maximalClosedResidual(fixture.truth());
      AnalyzerEngine forward = new AnalyzerEngine(fixture.manifest());
      fixture.events().forEach(forward::ingest);
      VerdictReport report = forward.evaluate(3_100);
      assertThat(report.coverage().missing()).as("pattern %s", pattern).isEmpty();
      assertThat(report.verdict())
          .as("pattern %s", pattern)
          .isEqualTo(
              expected.isEmpty() ? Verdict.CONFIRMED_NO_DEADLOCK : Verdict.CONFIRMED_DEADLOCK);
      Set<String> actual = new HashSet<>();
      report.core().executions().forEach(execution -> actual.add(execution.id()));
      report.core().queueEntries().forEach(queue -> actual.add(queue.invocationId()));
      assertThat(actual).as("pattern %s exact residual", pattern).isEqualTo(expected);

      List<ObservationEnvelope> permuted = new ArrayList<>(fixture.events());
      Collections.shuffle(permuted, new Random(42 + pattern));
      AnalyzerEngine reordered = new AnalyzerEngine(fixture.manifest());
      permuted.forEach(
          envelope -> {
            reordered.ingest(envelope);
            reordered.ingest(envelope);
          });
      assertThat(reordered.evaluate(3_100)).as("pattern %s replay", pattern).isEqualTo(report);
    }
  }

  private static Fixture fixture(int pattern) {
    Map<String, Stream> streams = new HashMap<>();
    Stream driver = new Stream("driver");
    List<RunManifest.Instance> instances = new ArrayList<>();
    instances.add(new RunManifest.Instance("driver", "driver", 0, 10_000, 10));
    List<RunManifest.Pool> pools = new ArrayList<>();
    Map<String, Integer> routes = new HashMap<>();
    Map<String, String> acquires = new HashMap<>();
    Map<String, String> blocks = new HashMap<>();
    Map<String, String> sends = new HashMap<>();
    Set<String> blocked = new HashSet<>();
    Set<String> queued = new HashSet<>();
    Map<String, String> counterparts = new HashMap<>();
    Map<String, Set<String>> eligible = new HashMap<>();
    Map<String, String> owners = new HashMap<>();
    int choices = pattern;
    for (String host : HOSTS) {
      Stream stream = new Stream(host);
      streams.put(host, stream);
      routes.put(host, choices % 4);
      choices /= 4;
      instances.add(new RunManifest.Instance(host, "svc" + host, 0, 10_000, 10));
      pools.add(new RunManifest.Pool(host, "app", List.of(host + "0"), true));
      stream.add(0, "", new EventPayload.ResourceInit("app", 1, List.of(host + "0")));
      String sentRoot =
          driver.add(10, "", new EventPayload.RpcInvocationSent("root-" + host, "", "svc" + host));
      stream.add(20, sentRoot, new EventPayload.RpcInvocationArrived("root-" + host));
      acquires.put(
          host,
          stream.add(
              30, "", new EventPayload.ResourceAcquire("e-" + host, host + "0", "root-" + host)));
      owners.put(host + "0", "e-" + host);
      int route = routes.get(host);
      if (route > 0) {
        String call = "call-" + host;
        blocks.put(host, stream.add(40, "", new EventPayload.RpcBlockBegin("e-" + host, call)));
        sends.put(
            host,
            stream.add(
                50,
                "",
                new EventPayload.RpcInvocationSent(
                    call, "e-" + host, "svc" + HOSTS.get(route - 1))));
        blocked.add("e-" + host);
        queued.add(call);
        counterparts.put("e-" + host, call);
        eligible.put(call, Set.of(HOSTS.get(route - 1) + "0"));
      }
    }
    for (int i = 0; i < HOSTS.size(); i++) {
      String host = HOSTS.get(i);
      int route = routes.get(host);
      if (route > 0) {
        Stream target = streams.get(HOSTS.get(route - 1));
        target.add(
            100 + 2 * i, sends.get(host), new EventPayload.RpcInvocationArrived("call-" + host));
        target.add(101 + 2 * i, "", new EventPayload.ResourceWaitBegin("call-" + host, "app"));
      }
    }
    for (String host : HOSTS) {
      if (routes.get(host) > 0) {
        streams
            .get(host)
            .add(
                2_800,
                "",
                new EventPayload.ExecutionLease(
                    "e-" + host,
                    "call-" + host,
                    acquires.get(host),
                    blocks.get(host),
                    2_760,
                    5_000));
      }
    }
    streams.put("driver", driver);
    List<ObservationEnvelope> events = new ArrayList<>();
    streams
        .values()
        .forEach(
            stream -> {
              stream.add(3_000, "", new EventPayload.InstanceCheckpoint(stream.events.size(), 0));
              events.addAll(stream.events);
            });
    return new Fixture(
        new RunManifest(
            2,
            "oracle-" + pattern,
            "sha256:oracle-" + pattern,
            instances,
            pools,
            new RunManifest.Parameters(2_000, 1_000, 10, 250, 500, 1_000, 500)),
        events,
        new TruthClosureOracle.Snapshot(blocked, queued, counterparts, eligible, owners));
  }

  private record Fixture(
      RunManifest manifest, List<ObservationEnvelope> events, TruthClosureOracle.Snapshot truth) {}

  private static final class Stream {
    private final String host;
    private final List<ObservationEnvelope> events = new ArrayList<>();

    private Stream(String host) {
      this.host = host;
    }

    private String add(long time, String parent, EventPayload payload) {
      ObservationEvent event =
          new ObservationEvent(
              2, host, events.size() + 1L, parent, time, time * 1_000_000, "", "", payload);
      events.add(new ObservationEnvelope(event, time, time * 1_000_000));
      return event.eventReference();
    }
  }
}
