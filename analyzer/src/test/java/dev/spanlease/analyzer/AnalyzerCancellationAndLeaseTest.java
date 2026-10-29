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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Certified k=1 ring A→B→C→A (ε=10, τ=2000, checkpoints at 3000) with one varied fact. */
class AnalyzerCancellationAndLeaseTest {
  @Test
  void olderLeaseCoversEndpointWhenLatestRenewalIsTooFresh() {
    VerdictReport report =
        evaluate(
            ring -> {
              ring.leases(2_400, 3_900);
              ring.leases(2_995, 4_495);
            });

    assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_DEADLOCK);
    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactly("a0", "b0", "c0");
  }

  @Test
  void leaseEmittedWithinEpsilonOfEndpointDoesNotCoverIt() {
    VerdictReport report = evaluate(ring -> ring.leases(2_995, 4_495));

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .containsOnly("EXPIRED_LEASE");
  }

  @Test
  void cancelledQueueEntryIsNotAWaiterAndLeavesItsCallerUnresolved() {
    VerdictReport report =
        evaluate(
            ring -> {
              ring.a.add(
                  400,
                  "",
                  new EventPayload.ExecutionCancel(
                      "",
                      "inv-ca",
                      EventPayload.CancelSource.DEADLINE,
                      EventPayload.CancelReason.DEADLINE));
              ring.leases(2_400, 3_900);
            });

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("UNKNOWN_COUNTERPART", "inv-ca"));
    assertThat(report.core().queueEntries()).isEmpty();
  }

  /** A cancel-pending blocked owner is unresolved, never a progress witness (design §5.3). */
  @Test
  void cancelPendingBlockedOwnerIsInconclusiveRatherThanProgress() {
    VerdictReport report =
        evaluate(
            ring -> {
              ring.a.add(
                  400,
                  "",
                  new EventPayload.ExecutionCancel(
                      "a0",
                      "root-a",
                      EventPayload.CancelSource.CLIENT,
                      EventPayload.CancelReason.REQUESTED));
              ring.leases(2_400, 3_900);
            });

    assertThat(report.verdict()).isEqualTo(Verdict.CANDIDATE_INCONCLUSIVE);
    assertThat(report.coverage().missing())
        .containsExactly(new VerdictReport.MissingEvidence("CANCEL_PENDING", "a0"));
  }

  private static VerdictReport evaluate(Consumer<Ring> variation) {
    Ring ring = new Ring();
    variation.accept(ring);
    AnalyzerEngine engine = new AnalyzerEngine(manifest());
    for (Stream stream : List.of(ring.a, ring.b, ring.c)) {
      stream.add(3_000, "", new EventPayload.InstanceCheckpoint(stream.sequence, 0));
      stream.events.forEach(engine::ingest);
    }
    return engine.evaluate(3_100);
  }

  private static RunManifest manifest() {
    return new RunManifest(
        2,
        "ring",
        "sha256:ring",
        List.of(
            new RunManifest.Instance("A", "svcA", 0, 10_000, 10),
            new RunManifest.Instance("B", "svcB", 0, 10_000, 10),
            new RunManifest.Instance("C", "svcC", 0, 10_000, 10)),
        List.of(
            new RunManifest.Pool("A", "main", List.of("A0"), true),
            new RunManifest.Pool("B", "main", List.of("B0"), true),
            new RunManifest.Pool("C", "main", List.of("C0"), true)),
        new RunManifest.Parameters(2_000, 1_000, 10, 250, 500, 1_000, 500));
  }

  private static final class Ring {
    private final Stream a = new Stream("A");
    private final Stream b = new Stream("B");
    private final Stream c = new Stream("C");
    private final String acquireA;
    private final String blockA;
    private final String acquireB;
    private final String blockB;
    private final String acquireC;
    private final String blockC;

    private Ring() {
      a.add(0, "", new EventPayload.ResourceInit("main", 1, List.of("A0")));
      b.add(0, "", new EventPayload.ResourceInit("main", 1, List.of("B0")));
      c.add(0, "", new EventPayload.ResourceInit("main", 1, List.of("C0")));
      acquireA = a.add(100, "", new EventPayload.ResourceAcquire("a0", "A0", "root-a"));
      blockA = a.add(200, "", new EventPayload.RpcBlockBegin("a0", "inv-ab"));
      String sentAb = a.add(210, "", new EventPayload.RpcInvocationSent("inv-ab", "a0", "svcB"));
      b.add(220, sentAb, new EventPayload.RpcInvocationArrived("inv-ab"));
      acquireB = b.add(230, "", new EventPayload.ResourceAcquire("b0", "B0", "inv-ab"));
      blockB = b.add(240, "", new EventPayload.RpcBlockBegin("b0", "inv-bc"));
      String sentBc = b.add(250, "", new EventPayload.RpcInvocationSent("inv-bc", "b0", "svcC"));
      c.add(260, sentBc, new EventPayload.RpcInvocationArrived("inv-bc"));
      acquireC = c.add(270, "", new EventPayload.ResourceAcquire("c0", "C0", "inv-bc"));
      blockC = c.add(280, "", new EventPayload.RpcBlockBegin("c0", "inv-ca"));
      String sentCa = c.add(290, "", new EventPayload.RpcInvocationSent("inv-ca", "c0", "svcA"));
      a.add(300, sentCa, new EventPayload.RpcInvocationArrived("inv-ca"));
      a.add(310, "", new EventPayload.ResourceWaitBegin("inv-ca", "main"));
    }

    private void leases(long observedMs, long expiryMs) {
      a.add(
          observedMs,
          "",
          new EventPayload.ExecutionLease("a0", "inv-ab", acquireA, blockA, 1_000, expiryMs));
      b.add(
          observedMs,
          "",
          new EventPayload.ExecutionLease("b0", "inv-bc", acquireB, blockB, 1_000, expiryMs));
      c.add(
          observedMs,
          "",
          new EventPayload.ExecutionLease("c0", "inv-ca", acquireC, blockC, 1_000, expiryMs));
    }
  }

  private static final class Stream {
    private final String instance;
    private final List<ObservationEnvelope> events = new ArrayList<>();
    private long sequence;

    private Stream(String instance) {
      this.instance = instance;
    }

    private String add(long wallMs, String parent, EventPayload payload) {
      ObservationEvent event =
          new ObservationEvent(
              2, instance, ++sequence, parent, wallMs, wallMs * 1_000_000, "", "", payload);
      events.add(new ObservationEnvelope(event, wallMs, wallMs * 1_000_000));
      return event.eventReference();
    }
  }
}
