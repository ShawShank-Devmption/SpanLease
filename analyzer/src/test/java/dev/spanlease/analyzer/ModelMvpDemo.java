package dev.spanlease.analyzer;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/** Runs two deterministic model scenarios without defining a pending report wire format. */
final class ModelMvpDemo {
  private static final long TAU_MS = 2_000;

  private ModelMvpDemo() {}

  public static void main(String[] args) {
    ModelDetectorMvp.Report deadlock = evaluateClosedRing();
    ModelDetectorMvp.Report noDeadlock = evaluateSpareCapacity();

    System.out.println("SpanLease pre-G0 model MVP (Java " + Runtime.version().feature() + ")");
    print("closed-ring", deadlock);
    print("spare-capacity", noDeadlock);
  }

  private static ModelDetectorMvp.Report evaluateClosedRing() {
    TruthClosureOracle.Snapshot snapshot =
        new TruthClosureOracle.Snapshot(
            Set.of("a0", "b0", "c0"),
            Set.of("a1"),
            Map.of("a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0")),
            Map.of("A0", "a0", "B0", "b0", "C0", "c0"));
    Map<String, ModelDetectorMvp.Interval> persistence =
        ModelDetectorMvp.requiredAssertions(snapshot, Set.of("a0", "b0", "c0", "a1")).stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    UnaryOperator.identity(),
                    ignored -> new ModelDetectorMvp.Interval(100, 2_200)));
    return ModelDetectorMvp.evaluate(completeInput(snapshot, persistence));
  }

  private static ModelDetectorMvp.Report evaluateSpareCapacity() {
    TruthClosureOracle.Snapshot snapshot =
        new TruthClosureOracle.Snapshot(
            Set.of("a0", "b0", "c0"),
            Set.of("a1"),
            Map.of("a0", "b0", "b0", "c0", "c0", "a1"),
            Map.of("a1", Set.of("A0", "A1")),
            Map.of("A0", "a0", "B0", "b0", "C0", "c0"));
    return ModelDetectorMvp.evaluate(completeInput(snapshot, Map.of()));
  }

  private static ModelDetectorMvp.Input completeInput(
      TruthClosureOracle.Snapshot snapshot, Map<String, ModelDetectorMvp.Interval> persistence) {
    return new ModelDetectorMvp.Input(true, true, true, true, true, TAU_MS, snapshot, persistence);
  }

  private static void print(String scenario, ModelDetectorMvp.Report report) {
    System.out.printf(
        "%s verdict=%s evidence=%s interval=%s missing=%s%n",
        scenario,
        report.verdict(),
        new TreeSet<>(report.evidenceSet()),
        report.evidenceInterval().map(ModelMvpDemo::format).orElse("none"),
        report.missingEvidence());
  }

  private static String format(ModelDetectorMvp.Interval interval) {
    return "[" + interval.startInclusiveMs() + "," + interval.endExclusiveMs() + ")";
  }
}
