package dev.spanlease.analyzer;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Validates and decodes SpanLease custom LogRecords from an OTLP export request. */
public final class OtlpEventDecoder {
  public List<ObservationEnvelope> decode(
      ExportLogsServiceRequest request, long receiptWallMs, long receiptMonotonicNs) {
    List<ObservationEnvelope> decoded = new ArrayList<>();
    request
        .getResourceLogsList()
        .forEach(
            resource ->
                resource
                    .getScopeLogsList()
                    .forEach(
                        scope ->
                            scope
                                .getLogRecordsList()
                                .forEach(
                                    record ->
                                        decoded.add(
                                            decodeRecord(
                                                record, receiptWallMs, receiptMonotonicNs)))));
    return List.copyOf(decoded);
  }

  private ObservationEnvelope decodeRecord(
      LogRecord record, long receiptWallMs, long receiptMonotonicNs) {
    if (!record.hasBody() || !record.getBody().hasStringValue()) {
      throw new IllegalArgumentException("SpanLease LogRecord body must contain event JSON");
    }
    ObservationEvent event;
    try {
      event =
          ContractJson.mapper()
              .readValue(record.getBody().getStringValue(), ObservationEvent.class);
    } catch (IOException failure) {
      throw new IllegalArgumentException("invalid SpanLease event JSON", failure);
    }

    Map<String, AnyValue> attributes = new HashMap<>();
    for (KeyValue attribute : record.getAttributesList()) {
      AnyValue previous = attributes.putIfAbsent(attribute.getKey(), attribute.getValue());
      if (previous != null) {
        throw new IllegalArgumentException("duplicate LogRecord attribute: " + attribute.getKey());
      }
    }
    requireLong(attributes, "sl.schema_version", event.schemaVersion());
    requireString(attributes, "sl.event_type", event.eventType().wireName());
    requireString(attributes, "sl.instance_id", event.instanceId());
    requireLong(attributes, "sl.local_seq", event.localSeq());
    requireString(attributes, "sl.causal_parent", event.causalParent());
    requireLong(attributes, "sl.wall_time_ms", event.wallTimeMs());
    requireLong(attributes, "sl.monotonic_time_ns", event.monotonicTimeNs());
    return new ObservationEnvelope(event, receiptWallMs, receiptMonotonicNs);
  }

  private static void requireLong(Map<String, AnyValue> attributes, String key, long expected) {
    AnyValue value = attributes.get(key);
    if (value == null || !value.hasIntValue() || value.getIntValue() != expected) {
      throw new IllegalArgumentException("missing or conflicting attribute: " + key);
    }
  }

  /**
   * The OTel SDK marshals an empty string attribute as an AnyValue with no value set, so that form
   * matches only an empty expected value; any other representation must be the exact string.
   */
  private static void requireString(Map<String, AnyValue> attributes, String key, String expected) {
    AnyValue value = attributes.get(key);
    boolean matches =
        value != null
            && (value.hasStringValue()
                ? value.getStringValue().equals(expected)
                : expected.isEmpty() && value.getValueCase() == AnyValue.ValueCase.VALUE_NOT_SET);
    if (!matches) {
      throw new IllegalArgumentException("missing or conflicting attribute: " + key);
    }
  }
}
