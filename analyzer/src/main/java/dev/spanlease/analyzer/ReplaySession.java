package dev.spanlease.analyzer;

import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Finite replay container. Step order, first receipt clocks and evaluation times are authoritative.
 */
public record ReplaySession(int formatVersion, RunManifest manifest, List<Step> steps) {
  public ReplaySession {
    if (formatVersion != 1)
      throw new IllegalArgumentException("unsupported replay container version");
    Objects.requireNonNull(manifest, "manifest");
    steps = List.copyOf(steps);
  }

  /**
   * Validates through the same ingress/state checks as online analysis, preserving delivery faults.
   */
  public List<VerdictReport> evaluate() {
    AnalyzerEngine engine = new AnalyzerEngine(manifest);
    List<VerdictReport> reports = new ArrayList<>();
    long previous = -1;
    long received = -1;
    for (Step step : steps) {
      for (ObservationEnvelope observation : step.observations()) {
        received = Math.max(received, observation.receiptWallMs());
        engine.ingest(observation);
      }
      if (step.evaluationWallMs() != null) {
        long time = step.evaluationWallMs();
        if (time < previous || time < received) {
          throw new IllegalArgumentException("evaluation precedes a prior evaluation or receipt");
        }
        reports.add(engine.evaluate(time));
        previous = time;
      }
    }
    return List.copyOf(reports);
  }

  public record Step(List<ObservationEnvelope> observations, Long evaluationWallMs) {
    public Step {
      observations = List.copyOf(observations);
      if (evaluationWallMs != null && evaluationWallMs < 0) {
        throw new IllegalArgumentException("negative evaluation time");
      }
    }
  }
}
