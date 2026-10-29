package dev.spanlease.common;

import java.util.Objects;

/** One immutable, transport-independent schema-v2 observation. */
public record ObservationEvent(
    int schemaVersion,
    String instanceId,
    long localSeq,
    String causalParent,
    long wallTimeMs,
    long monotonicTimeNs,
    String traceId,
    String spanId,
    EventPayload payload) {
  public static final int SCHEMA_VERSION = 2;

  public ObservationEvent {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported schema version: " + schemaVersion);
    }
    instanceId = ContractValidation.id(instanceId, "instanceId");
    ContractValidation.positive(localSeq, "localSeq");
    causalParent = ContractValidation.optionalId(causalParent, "causalParent");
    ContractValidation.nonnegative(wallTimeMs, "wallTimeMs");
    ContractValidation.nonnegative(monotonicTimeNs, "monotonicTimeNs");
    traceId = ContractValidation.optionalId(traceId, "traceId");
    spanId = ContractValidation.optionalId(spanId, "spanId");
    Objects.requireNonNull(payload, "payload");

    if (payload instanceof EventPayload.InstanceCheckpoint checkpoint
        && checkpoint.throughSeq() != localSeq - 1) {
      throw new IllegalArgumentException("checkpoint throughSeq must equal localSeq - 1");
    }
    if (payload instanceof EventPayload.RpcInvocationArrived && causalParent.isEmpty()) {
      throw new IllegalArgumentException("arrived event requires request causal parent");
    }
    if (payload instanceof EventPayload.RpcBlockEnd blockEnd) {
      boolean response = blockEnd.completionKind() == EventPayload.CompletionKind.RESPONSE;
      if (response == causalParent.isEmpty()) {
        throw new IllegalArgumentException(
            "response completion requires a parent and local completion forbids one");
      }
    }
  }

  public EventType eventType() {
    return payload.eventType();
  }

  public String eventReference() {
    return instanceId + "#" + localSeq;
  }
}
