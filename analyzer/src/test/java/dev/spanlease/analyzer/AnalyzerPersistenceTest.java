package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** C-TIME/R9: exact uninterrupted assertions, endpoint search and temporal closure pruning. */
class AnalyzerPersistenceTest {
  @Test
  void newUpstreamWaiterDoesNotResetOlderRing() {
    Fixture f = new Fixture();
    f.ring("A", 100);
    f.upstream("U", "A", 2_500);
    VerdictReport report = f.evaluate(3_000);
    assertWitness(report, "A-exec");
    assertThat(report.core().queueEntries())
        .extracting(VerdictReport.QueueEntry::invocationId)
        .containsExactly("A-call");
  }

  @Test
  void newDisjointRingDoesNotHideOlderRing() {
    Fixture f = new Fixture();
    f.ring("A", 100);
    f.ring("B", 2_500);
    assertWitness(f.evaluate(3_000), "A-exec");
  }

  @Test
  void allQualifiedDisjointRingsAndDependentsRemainInWitness() {
    Fixture f = new Fixture();
    f.ring("A", 100);
    f.ring("B", 200);
    f.upstream("U", "A", 300);
    assertWitness(f.evaluate(3_000), "A-exec", "B-exec", "U-exec");
  }

  @Test
  void oldDependentIsPrunedWhenItsCounterpartIsTooNew() {
    Fixture f = new Fixture();
    f.upstream("U", "A", 100);
    f.ring("A", 2_500);
    VerdictReport report = f.evaluate(3_000);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.persistence()).isNull();
  }

  @Test
  void latestEligibleEndpointCanPrecedeCheckpointWhenLeasesExpire() {
    Fixture f = new Fixture();
    f.ring("A", 100);
    f.lease("A", 2_200, 2_600);
    VerdictReport report = f.evaluateWithoutRenewal(4_000);
    assertWitness(report, "A-exec");
    assertThat(report.persistence().lastMs()).isEqualTo(2_590);
    assertThat(report.persistence().firstMs()).isEqualTo(590);
    assertThat(report.core().units().getFirst().leaseRef()).isEqualTo("A#7");
  }

  @Test
  void mismatchedLeaseIntervalsCannotProduceAClosedWitness() {
    Fixture f = new Fixture();
    // A queues at B and B queues at A; their owners have disjoint lease eligibility windows.
    f.upstream("A", "B", 100);
    f.upstream("B", "A", 100);
    f.lease("A", 2_200, 2_500);
    f.lease("B", 2_600, 3_500);
    assertThat(f.evaluateWithoutRenewal(4_000).verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  @Test
  void sameExecutionWithNewWaitDoesNotReuseOldPersistenceOrLease() {
    Fixture f = new Fixture();
    f.ring("A", 100);
    f.lease("A", 2_200, 5_000);
    f.add(
        "A",
        2_400,
        "",
        new EventPayload.RpcBlockEnd(
            "A-exec", "A-call", EventPayload.Outcome.DEADLINE, EventPayload.CompletionKind.LOCAL));
    f.add(
        "A",
        2_410,
        "",
        new EventPayload.ExecutionCancel(
            "", "A-call", EventPayload.CancelSource.DEADLINE, EventPayload.CancelReason.DEADLINE));
    f.blocks.put("A", f.add("A", 2_500, "", new EventPayload.RpcBlockBegin("A-exec", "A-new")));
    String sent = f.add("A", 2_510, "", new EventPayload.RpcInvocationSent("A-new", "A-exec", "A"));
    f.add("A", 2_520, sent, new EventPayload.RpcInvocationArrived("A-new"));
    f.add("A", 2_530, "", new EventPayload.ResourceWaitBegin("A-new", "main"));
    VerdictReport report = f.evaluateWithoutRenewal(4_000);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .contains("PERSISTENCE_TOO_SHORT", "EXPIRED_LEASE");
  }

  @Test
  void leaseForAnotherAcquisitionCannotQualifyCurrentWait() {
    assertMismatchedLease("A-call", "A#1");
  }

  @Test
  void leaseForAnotherInvocationCannotQualifyCurrentWait() {
    assertMismatchedLease("another-call", "A#2");
  }

  private static void assertMismatchedLease(String invocation, String acquireRef) {
    Fixture f = new Fixture();
    f.ring("A", 100);
    f.add(
        "A",
        2_200,
        "",
        new EventPayload.ExecutionLease(
            "A-exec", invocation, acquireRef, f.blocks.get("A"), 2_000, 5_000));
    VerdictReport report = f.evaluateWithoutRenewal(3_000);
    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("INVALID_EVENT", "A#7"));
  }

  @Test
  void evaluationTicksCannotExtendAnUnchangedCertifiedHistory() {
    Fixture f = new Fixture();
    f.ring("A", 1_000);
    VerdictReport first = f.evaluate(2_000);
    assertThat(first.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(f.engine.evaluate(2_900).verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  @Test
  void epsilonNarrowedLeaseExpiryIsInclusiveButOneMillisecondEarlierFails() {
    Fixture eligible = new Fixture();
    eligible.ring("A", 100);
    eligible.lease("A", 2_000, 2_150);
    assertWitness(eligible.evaluateWithoutRenewal(3_000), "A-exec");
    Fixture shortLease = new Fixture();
    shortLease.ring("A", 100);
    shortLease.lease("A", 2_000, 2_149);
    assertThat(shortLease.evaluateWithoutRenewal(3_000).verdict())
        .isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
  }

  @Test
  void certifiedSuffixLeaseAndClosingEventBoundARetractedCutsCurrentAssertion() {
    Fixture f = new Fixture();
    f.ring("A", 100);
    // U's send is received but is outside U's latest checkpoint. Its arrival retracts A's cut,
    // leaving the old ring at the cut, while A's later complete prefix certifies its closure.
    f.add("U", 2_100, "", new EventPayload.InstanceCheckpoint(f.sequences.get("U"), 0));
    String sent = f.add("U", 2_150, "", new EventPayload.RpcInvocationSent("external", "", "A"));
    f.add("A", 2_200, sent, new EventPayload.RpcInvocationArrived("external"));
    f.lease("A", 2_250, 5_000);
    f.add(
        "A",
        2_300,
        "",
        new EventPayload.RpcBlockEnd(
            "A-exec", "A-call", EventPayload.Outcome.DEADLINE, EventPayload.CompletionKind.LOCAL));
    f.add(
        "A",
        2_310,
        "",
        new EventPayload.ExecutionCancel(
            "", "A-call", EventPayload.CancelSource.DEADLINE, EventPayload.CancelReason.DEADLINE));
    f.add("A", 3_000, "", new EventPayload.InstanceCheckpoint(f.sequences.get("A"), 0));
    f.add("B", 3_000, "", new EventPayload.InstanceCheckpoint(f.sequences.get("B"), 0));
    f.events.forEach(f.engine::ingest);

    VerdictReport report = f.engine.evaluate(3_100);
    assertWitness(report, "A-exec");
    assertThat(report.query().cut().get("A")).isEqualTo(6L);
    assertThat(report.persistence().lastMs()).isEqualTo(2_289);
    assertThat(report.core().units().getFirst().leaseRef()).isEqualTo("A#8");
  }

  private static void assertWitness(VerdictReport report, String... executions) {
    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactlyInAnyOrder(executions);
    assertThat(report.persistence().narrowedSpanMs()).isEqualTo(2_000);
    assertThat(report.coverage().missing()).isEmpty();
  }

  private static final class Fixture {
    final List<ObservationEnvelope> events = new ArrayList<>();
    final TreeMap<String, Long> sequences = new TreeMap<>();
    final TreeMap<String, String> acquires = new TreeMap<>();
    final TreeMap<String, String> blocks = new TreeMap<>();
    final TreeMap<String, String> calls = new TreeMap<>();
    final AnalyzerEngine engine;

    Fixture() {
      List<String> ids = List.of("A", "B", "U");
      engine =
          new AnalyzerEngine(
              new RunManifest(
                  2,
                  "time",
                  "sha256:time",
                  ids.stream().map(id -> new RunManifest.Instance(id, id, 0, 10_000, 10)).toList(),
                  ids.stream()
                      .map(id -> new RunManifest.Pool(id, "main", List.of(id + "0"), true))
                      .toList(),
                  new RunManifest.Parameters(2_000, 1_000, 10, 250, 500, 1_000, 500)));
      ids.forEach(
          id -> add(id, 0, "", new EventPayload.ResourceInit("main", 1, List.of(id + "0"))));
    }

    void ring(String id, long start) {
      upstream(id, id, start);
    }

    void upstream(String id, String target, long start) {
      acquires.put(
          id,
          add(
              id,
              start,
              "",
              new EventPayload.ResourceAcquire(id + "-exec", id + "0", id + "-root")));
      calls.put(id, id + "-call");
      blocks.put(
          id, add(id, start + 10, "", new EventPayload.RpcBlockBegin(id + "-exec", id + "-call")));
      String sent =
          add(
              id,
              start + 15,
              "",
              new EventPayload.RpcInvocationSent(id + "-call", id + "-exec", target));
      add(target, start + 20, sent, new EventPayload.RpcInvocationArrived(id + "-call"));
      add(target, start + 30, "", new EventPayload.ResourceWaitBegin(id + "-call", "main"));
    }

    void lease(String id, long observed, long expiry) {
      add(
          id,
          observed,
          "",
          new EventPayload.ExecutionLease(
              id + "-exec",
              calls.get(id),
              acquires.get(id),
              blocks.get(id),
              observed - 100,
              expiry));
    }

    VerdictReport evaluate(long checkpoint) {
      blocks.keySet().forEach(id -> lease(id, checkpoint - 200, checkpoint + 1_000));
      return evaluateWithoutRenewal(checkpoint);
    }

    VerdictReport evaluateWithoutRenewal(long checkpoint) {
      for (String id : List.of("A", "B", "U")) {
        add(id, checkpoint, "", new EventPayload.InstanceCheckpoint(sequences.get(id), 0));
      }
      events.forEach(engine::ingest);
      return engine.evaluate(checkpoint + 100);
    }

    String add(String id, long wall, String parent, EventPayload payload) {
      long seq = sequences.merge(id, 1L, Long::sum);
      ObservationEvent event =
          new ObservationEvent(2, id, seq, parent, wall, wall * 1_000_000, "", "", payload);
      events.add(new ObservationEnvelope(event, wall, wall * 1_000_000));
      return event.eventReference();
    }
  }
}
