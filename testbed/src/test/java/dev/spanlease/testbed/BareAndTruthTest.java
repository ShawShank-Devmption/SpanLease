package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ContractJson;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BareAndTruthTest {
  @TempDir Path directory;

  @Test
  void bareModeUsesSameGateAndRecordsPhysicalLifecycleWithoutTelemetry() throws Exception {
    var workload =
        new Workload(
            "bare-chain",
            Map.of("A", 1, "B", 1),
            List.of(Workload.root("root", 0, "A", "B")),
            Duration.ofSeconds(5),
            4);
    var plan = RunPlan.create(workload.scenario(), System.currentTimeMillis());
    ChainDeployment deployment =
        ChainDeployment.start(
            plan,
            "unused",
            service -> {
              AtomicInteger counter = new AtomicInteger();
              return () -> service + counter.incrementAndGet();
            },
            false,
            4);
    try (deployment) {
      var root =
          deployment
              .driver()
              .submit("root", workload.roots().getFirst().hops(), Duration.ofSeconds(5));
      assertThat(root.reply().get(5, java.util.concurrent.TimeUnit.SECONDS).getHopsCompleted())
          .isEqualTo(2);
      for (var node : deployment.nodes().values())
        assertThat(node.snapshot().lastSequence()).isZero();
    }
    assertThat(deployment.truth().scorable()).isTrue();
    for (String service : List.of("A", "B")) {
      var entries =
          deployment.truth().entries().stream().filter(e -> e.service().equals(service)).toList();
      assertThat(entries)
          .extracting(TruthRecorder.Entry::transition)
          .containsSubsequence("INIT", "READY", "ASSIGN", "RUN", "RESPONSE", "EXIT", "RELEASE");
      assertThat(entries)
          .extracting(TruthRecorder.Entry::ordinal)
          .containsExactlyElementsOf(
              java.util.stream.LongStream.rangeClosed(1, entries.size()).boxed().toList());
    }
    assertThat(
            TruthOracle.closedJobs(
                deployment.truth().entries(), workload.capacities(), Long.MAX_VALUE))
        .isEmpty();
  }

  @Test
  void truthOverflowIsExplicitAndRoundTripsWithoutDetectorRecords() throws Exception {
    var recorder = new TruthRecorder(Clock.systemUTC(), 1);
    var job = recorder.job("A", "root", 1);
    recorder.record(job, "RUN");
    recorder.record(job, "EXIT");
    assertThat(recorder.scorable()).isFalse();
    assertThat(recorder.dropped()).isEqualTo(1);
    assertThat(ContractJson.mapper().writeValueAsString(recorder.entries()))
        .doesNotContain("local_seq", "execution_id", "block_ref");
  }
}
