package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.Verdict;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Live multi-root scenarios scored with a bounded subset-enumeration oracle from physical truth.
 */
@Timeout(180)
class WorkloadMatrixTest {
  @Test
  void fullScenarioMatrixMatchesIndependentTruth() throws Exception {
    Path runs = Path.of(System.getProperty("spanlease.integration.runs"));
    for (Workload workload : Workload.standard()) {
      var result = ScenarioRunner.run(workload, true, runs, 42);
      boolean positive =
          List.of(
                  "closed-ring",
                  "saturated-ring-k2",
                  "upstream-dependent",
                  "disjoint-cycles",
                  "coordinated-ring-n3-k2")
              .contains(workload.name());
      assertThat(result.closedJobs().isEmpty())
          .as(workload.name() + " physical closure")
          .isEqualTo(!positive);
      var positives =
          result.reports().stream().filter(r -> r.verdict() == Verdict.CONFIRMED_DEADLOCK).toList();
      if (positive) {
        assertThat(positives).as(workload.name()).isNotEmpty();
        for (var report : positives) {
          Set<String> expected = new HashSet<>();
          for (var execution : report.core().executions()) {
            var binding =
                result.mappings().stream()
                    .filter(b -> b.execution().equals(execution.id()))
                    .findFirst()
                    .orElseThrow();
            expected.add(TruthOracle.attempt(binding.job()));
          }
          for (var queue : report.core().queueEntries()) {
            var binding =
                result.mappings().stream()
                    .filter(b -> b.invocation().equals(queue.invocationId()))
                    .findFirst()
                    .orElseThrow();
            expected.add(TruthOracle.attempt(binding.job()));
          }
          var boundaries = new java.util.TreeSet<Long>();
          boundaries.add(report.persistence().firstMs());
          boundaries.add(report.persistence().lastMs());
          result.truth().stream()
              .map(TruthRecorder.Entry::wallMs)
              .filter(
                  t -> t >= report.persistence().firstMs() && t <= report.persistence().lastMs())
              .forEach(boundaries::add);
          for (long at : boundaries) {
            assertThat(TruthOracle.witnesses(result.truth(), workload.capacities(), at, expected))
                .as(workload.name() + " closed witness at " + at)
                .isTrue();
          }
        }
      } else {
        assertThat(positives).as(workload.name()).isEmpty();
        assertThat(result.reports())
            .extracting(r -> r.verdict())
            .contains(Verdict.CONFIRMED_NO_DEADLOCK);
      }
      if (workload.name().equals("queue-rejection"))
        assertThat(result.outcomes())
            .extracting(ScenarioRunner.Outcome::status)
            .contains("RESOURCE_EXHAUSTED");
    }
  }
}
