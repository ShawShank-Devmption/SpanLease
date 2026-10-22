package dev.spanlease.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V2ContractTest {
  @Test
  void eventRoundTripsWithStableTypeAndLongSequence() throws Exception {
    ObservationEvent event =
        new ObservationEvent(
            2,
            "svcA@epoch1",
            Long.MAX_VALUE,
            "svcB@epoch1#7",
            10_000,
            42,
            "",
            "",
            new EventPayload.RpcInvocationArrived("inv-1"));

    String encoded = ContractJson.mapper().writeValueAsString(event);
    ObservationEvent decoded = ContractJson.mapper().readValue(encoded, ObservationEvent.class);

    assertThat(decoded).isEqualTo(event);
    assertThat(encoded).contains("\"event_type\":\"rpc.invocation.arrived\"");
  }

  @Test
  void checkpointCannotCertifyPastItsOwnPrefix() {
    assertThatThrownBy(
            () ->
                new ObservationEvent(
                    2,
                    "svcA@epoch1",
                    8,
                    "",
                    100,
                    100,
                    "",
                    "",
                    new EventPayload.InstanceCheckpoint(5, 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("throughSeq");
  }

  @Test
  void reportSortsEvidenceAndRoundTrips() throws Exception {
    VerdictReport report =
        new VerdictReport(
            2,
            "run-1",
            "sha256:test",
            Verdict.CONFIRMED_DEADLOCK,
            3_000,
            new VerdictReport.Query(
                List.of("svcB@e1", "svcA@e1"), Map.of("svcA@e1", 8L, "svcB@e1", 9L), 2_900L),
            new VerdictReport.Persistence(1_000, 3_000, 2_000, 2_000),
            new VerdictReport.Core(
                List.of(
                    new VerdictReport.Execution("b0", "ib", "ia", "svcB@e1#4"),
                    new VerdictReport.Execution("a0", "ia", "ib", "svcA@e1#4")),
                List.of(),
                List.of(),
                List.of(new VerdictReport.Edge("b0", "a0", "wait"))),
            new VerdictReport.Coverage(2, 2, List.of(), List.of(), List.of(), List.of()));

    String encoded = ContractJson.mapper().writeValueAsString(report);
    VerdictReport decoded = ContractJson.mapper().readValue(encoded, VerdictReport.class);

    assertThat(decoded).isEqualTo(report);
    assertThat(decoded.core().executions())
        .extracting(VerdictReport.Execution::id)
        .containsExactly("a0", "b0");
  }
}
