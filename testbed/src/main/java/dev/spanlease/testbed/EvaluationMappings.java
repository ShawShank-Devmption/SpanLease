package dev.spanlease.testbed;

import dev.spanlease.instrumentation.SlotTrackingExecutor;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Evaluator-only associations. Never supplied to the truth recorder or analyzer. */
public final class EvaluationMappings {
  private final ConcurrentLinkedQueue<Binding> bindings = new ConcurrentLinkedQueue<>();

  public void inbound(Object job, String invocation) {
    bindings.add(new Binding((TruthRecorder.Job) job, invocation, "", ""));
  }

  public void execution(TruthRecorder.Job job, SlotTrackingExecutor.ExecutionContext context) {
    bindings.add(
        new Binding(job, context.inboundInvocationId(), context.executionId(), context.unitId()));
  }

  public List<Binding> entries() {
    return List.copyOf(bindings);
  }

  public record Binding(TruthRecorder.Job job, String invocation, String execution, String unit) {}
}
