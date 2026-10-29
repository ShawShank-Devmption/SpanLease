package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** R6/R10/R14/R16: impossible histories and causal aliases cannot authorize a verdict. */
class AnalyzerHistoryValidationTest {
  @Test
  void requestParentMustBeASendOfTheSameInvocationToThisService() {
    invalid(replace("B", 2, event -> changed(event, "A#1", event.payload())));
    invalid(
        replace(
            "A",
            4,
            event ->
                changed(event, "", new EventPayload.RpcInvocationSent("other", "a0", "svcB"))));
    invalid(
        replace(
            "A",
            4,
            event ->
                changed(event, "", new EventPayload.RpcInvocationSent("inv-ab", "a0", "svcC"))));
  }

  @Test
  void responseParentMustBeAResponseForTheMatchingInvocation() {
    List<ObservationEnvelope> events = new ArrayList<>(ProductionDemo.observations(false));
    events.add(
        envelope(
            "A",
            9,
            "B#1",
            3_100,
            new EventPayload.RpcBlockEnd(
                "a0", "inv-ab", EventPayload.Outcome.OK, EventPayload.CompletionKind.RESPONSE)));
    events.add(envelope("A", 10, "", 3_200, new EventPayload.InstanceCheckpoint(9, 0)));
    invalid(events);
  }

  @Test
  void localForwardParentAndCrossHostCausalCycleAreInvalid() {
    invalid(replace("A", 3, event -> changed(event, "A#4", event.payload())));
    invalid(replace("A", 4, event -> changed(event, "B#2", event.payload())));
  }

  @Test
  void acquisitionCannotTakeAnotherInstancesUnitOrAliasAnExecution() {
    invalid(
        replace(
            "A",
            2,
            event -> changed(event, "", new EventPayload.ResourceAcquire("a0", "B0", "root-a"))));
    invalid(
        replace(
            "C",
            3,
            event -> changed(event, "", new EventPayload.ResourceAcquire("b0", "C0", "inv-bc"))));
  }

  @Test
  void poolInitializationIsNotRepeatable() {
    invalid(
        replace(
            "A",
            6,
            event -> changed(event, "", new EventPayload.ResourceInit("main", 1, List.of("A0")))));
  }

  @Test
  void releaseRequiresActualExitAndExitRequiresClosedHelper() {
    invalid(
        replace("A", 7, event -> changed(event, "", new EventPayload.ResourceRelease("a0", "A0"))));
    invalid(
        replace(
            "A",
            7,
            event ->
                changed(
                    event,
                    "",
                    new EventPayload.ExecutionEnd("a0", "root-a", EventPayload.Outcome.OK))));
  }

  @Test
  void leaseMustBindBothAcquireAndCurrentInvocation() {
    invalid(
        replace(
            "A",
            7,
            event ->
                changed(
                    event,
                    "",
                    new EventPayload.ExecutionLease("a0", "inv-ab", "A#1", "A#3", 2_600, 5_000))));
    invalid(
        replace(
            "A",
            7,
            event ->
                changed(
                    event,
                    "",
                    new EventPayload.ExecutionLease("a0", "other", "A#2", "A#3", 2_600, 5_000))));
  }

  private static void invalid(List<ObservationEnvelope> events) {
    AnalyzerEngine engine = new AnalyzerEngine(ProductionDemo.manifest(false));
    events.forEach(engine::ingest);
    VerdictReport report = engine.evaluate(3_300);
    assertThat(report.verdict()).isEqualTo(Verdict.INSUFFICIENT_OBSERVABILITY);
    assertThat(report.coverage().missing())
        .extracting(VerdictReport.MissingEvidence::code)
        .contains("INVALID_EVENT");
  }

  private static List<ObservationEnvelope> replace(
      String instance, long sequence, UnaryOperator<ObservationEvent> mutation) {
    return ProductionDemo.observations(false).stream()
        .map(
            envelope -> {
              ObservationEvent event = envelope.event();
              return event.instanceId().equals(instance) && event.localSeq() == sequence
                  ? new ObservationEnvelope(
                      mutation.apply(event),
                      envelope.receiptWallMs(),
                      envelope.receiptMonotonicNs())
                  : envelope;
            })
        .toList();
  }

  private static ObservationEvent changed(
      ObservationEvent event, String parent, EventPayload payload) {
    return new ObservationEvent(
        2,
        event.instanceId(),
        event.localSeq(),
        parent,
        event.wallTimeMs(),
        event.monotonicTimeNs(),
        "",
        "",
        payload);
  }

  private static ObservationEnvelope envelope(
      String instance, long sequence, String parent, long time, EventPayload payload) {
    return new ObservationEnvelope(
        new ObservationEvent(
            2, instance, sequence, parent, time, time * 1_000_000, "", "", payload),
        time,
        time * 1_000_000);
  }
}
