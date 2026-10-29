package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtlpEventDecoderTest {
  @Test
  void decodesValidatedOtlpEnvelope() throws Exception {
    ObservationEvent event = event();
    ExportLogsServiceRequest request = request(event, event.instanceId());

    List<ObservationEnvelope> decoded = new OtlpEventDecoder().decode(request, 2_000, 20);

    assertThat(decoded).containsExactly(new ObservationEnvelope(event, 2_000, 20));
  }

  @Test
  void rejectsConflictingNativeAttributeAndJsonBody() throws Exception {
    ObservationEvent event = event();
    ExportLogsServiceRequest request = request(event, "different-instance");

    assertThatThrownBy(() -> new OtlpEventDecoder().decode(request, 2_000, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sl.instance_id");
  }

  /** The OTel SDK marshals an empty string attribute as an AnyValue with no value set. */
  @Test
  void acceptsSdkEncodingOfEmptyCausalParentOnlyWhenBodyParentIsEmpty() throws Exception {
    ObservationEvent event = event();
    ExportLogsServiceRequest request = withUnsetCausalParent(request(event, event.instanceId()));

    assertThat(new OtlpEventDecoder().decode(request, 2_000, 20))
        .containsExactly(new ObservationEnvelope(event, 2_000, 20));

    ObservationEvent child =
        new ObservationEvent(
            2, "A", 1, "B#4", 1_000, 5, "", "", new EventPayload.RpcInvocationArrived("inv-1"));
    ExportLogsServiceRequest unsetParent = withUnsetCausalParent(request(child, "A"));
    assertThatThrownBy(() -> new OtlpEventDecoder().decode(unsetParent, 2_000, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sl.causal_parent");
  }

  private static ExportLogsServiceRequest withUnsetCausalParent(ExportLogsServiceRequest request) {
    ExportLogsServiceRequest.Builder builder = request.toBuilder();
    LogRecord.Builder record =
        builder.getResourceLogsBuilder(0).getScopeLogsBuilder(0).getLogRecordsBuilder(0);
    for (int index = 0; index < record.getAttributesCount(); index++) {
      if (record.getAttributes(index).getKey().equals("sl.causal_parent")) {
        record.setAttributes(
            index,
            KeyValue.newBuilder()
                .setKey("sl.causal_parent")
                .setValue(AnyValue.getDefaultInstance()));
      }
    }
    return builder.build();
  }

  private static ObservationEvent event() {
    return new ObservationEvent(
        2, "A", 1, "", 1_000, 5, "", "", new EventPayload.ResourceInit("main", 1, List.of("A0")));
  }

  private static ExportLogsServiceRequest request(ObservationEvent event, String instance)
      throws Exception {
    LogRecord.Builder record =
        LogRecord.newBuilder()
            .setBody(
                AnyValue.newBuilder()
                    .setStringValue(ContractJson.mapper().writeValueAsString(event)))
            .addAttributes(longAttribute("sl.schema_version", 2))
            .addAttributes(stringAttribute("sl.event_type", event.eventType().wireName()))
            .addAttributes(stringAttribute("sl.instance_id", instance))
            .addAttributes(longAttribute("sl.local_seq", 1))
            .addAttributes(stringAttribute("sl.causal_parent", event.causalParent()))
            .addAttributes(longAttribute("sl.wall_time_ms", 1_000))
            .addAttributes(longAttribute("sl.monotonic_time_ns", 5));
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(
            ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)))
        .build();
  }

  private static KeyValue longAttribute(String key, long value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setIntValue(value))
        .build();
  }

  private static KeyValue stringAttribute(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
        .build();
  }
}
