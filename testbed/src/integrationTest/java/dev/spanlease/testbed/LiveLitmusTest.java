package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import io.grpc.Status;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real gRPC → SDK/OTLP → separate analyzer process; expected identities are workload supplied. */
@Timeout(60)
class LiveLitmusTest {
  private final Path runs = Path.of(System.getProperty("spanlease.integration.runs"));

  @Test
  void closedRingConfirmsExactExecutionsQueueAndSlotsBeforeDeadline() throws Exception {
    LiveRun.Result result = run(Scenario.closedRing());
    VerdictReport report = result.first(Verdict.CONFIRMED_DEADLOCK).orElseThrow().report();
    List<String> instances =
        List.of("svcA", "svcB", "svcC").stream()
            .map(result.plan().instanceByService()::get)
            .toList();

    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactlyInAnyOrderElementsOf(instances.stream().map(id -> id + ":exec-1").toList());
    assertThat(report.core().queueEntries())
        .extracting(VerdictReport.QueueEntry::invocationId)
        .containsExactly("svcC-call-1");
    assertThat(report.core().queueEntries().getFirst().instanceId())
        .isEqualTo(result.plan().instanceByService().get("svcA"));
    assertThat(report.core().units())
        .extracting(VerdictReport.Unit::id)
        .containsExactlyInAnyOrderElementsOf(
            instances.stream().map(id -> id + ":app:slot-0").toList());
    assertThat(report.core().executions())
        .extracting(VerdictReport.Execution::blockedOnInvocation)
        .containsExactlyInAnyOrder("svcA-call-1", "svcB-call-1", "svcC-call-1");
    assertThat(report.persistence().narrowedSpanMs()).isGreaterThanOrEqualTo(2_000);
    assertThat(report.coverage().missing()).isEmpty();
    assertThat(report.evaluatedAtWallMs()).isLessThan(result.rootDeadlineAtMs());
    assertThat(result.rootOutcome()).isEqualTo(Status.Code.CANCELLED);
  }

  @Test
  void spareCapacityHasCertifiedProgressWhileAncestorsAreBlocked() throws Exception {
    LiveRun.Result result = run(Scenario.spareCapacity());
    LiveRun.Interval leaf = result.leafRun().orElseThrow();
    long epsilon = result.plan().manifest().parameters().epsilonMs();
    assertThat(result.certifiedWithin(Verdict.CONFIRMED_NO_DEADLOCK, leaf))
        .anySatisfy(
            timed -> {
              // Exclude a final-state negative with one older checkpoint: the evaluation itself
              // must precede leaf return, while every service's certified horizon follows leaf
              // entry.
              assertThat(timed.report().query().certifiedThroughWallMs())
                  .isGreaterThan(leaf.fromMs() + epsilon);
              assertThat(timed.report().evaluatedAtWallMs()).isLessThan(leaf.toMs() - epsilon);
              for (String service : List.of("svcA", "svcB", "svcC")) {
                assertThat(result.journal())
                    .anySatisfy(
                        entry -> {
                          assertThat(entry.service()).isEqualTo(service);
                          assertThat(entry.boundary())
                              .isEqualTo(HandlerJournal.Boundary.CALL_ENTER);
                          assertThat(entry.wallMs())
                              .isLessThan(timed.report().query().certifiedThroughWallMs());
                        });
                assertThat(result.journal())
                    .anySatisfy(
                        entry -> {
                          assertThat(entry.service()).isEqualTo(service);
                          assertThat(entry.boundary())
                              .isEqualTo(HandlerJournal.Boundary.CALL_RETURN);
                          assertThat(entry.wallMs())
                              .isGreaterThan(timed.report().evaluatedAtWallMs());
                        });
              }
            });
    assertThat(result.first(Verdict.CONFIRMED_DEADLOCK)).isEmpty();
    assertThat(result.rootOutcome()).isEqualTo(Status.Code.OK);
    assertThat(result.rootHopsCompleted()).isEqualTo(4);
  }

  private LiveRun.Result run(Scenario scenario) throws Exception {
    return LiveRun.execute(
        RunPlan.create(scenario, System.currentTimeMillis()),
        runs,
        service -> {
          AtomicInteger next = new AtomicInteger();
          return () -> service + "-call-" + next.incrementAndGet();
        },
        ignored -> {},
        ignored -> {});
  }
}
