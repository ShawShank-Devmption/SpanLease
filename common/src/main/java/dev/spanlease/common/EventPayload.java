package dev.spanlease.common;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;

/** Immutable schema-v2 event payloads, independent of OTLP encoding. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "event_type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = EventPayload.ResourceInit.class, name = "resource.init"),
  @JsonSubTypes.Type(value = EventPayload.InstanceCheckpoint.class, name = "instance.checkpoint"),
  @JsonSubTypes.Type(value = EventPayload.RpcInvocationSent.class, name = "rpc.invocation.sent"),
  @JsonSubTypes.Type(
      value = EventPayload.RpcInvocationArrived.class,
      name = "rpc.invocation.arrived"),
  @JsonSubTypes.Type(value = EventPayload.ResourceWaitBegin.class, name = "resource.wait.begin"),
  @JsonSubTypes.Type(value = EventPayload.ResourceAcquire.class, name = "resource.acquire"),
  @JsonSubTypes.Type(value = EventPayload.RpcBlockBegin.class, name = "rpc.block.begin"),
  @JsonSubTypes.Type(value = EventPayload.ExecutionLease.class, name = "execution.lease"),
  @JsonSubTypes.Type(value = EventPayload.RpcResponseSent.class, name = "rpc.response.sent"),
  @JsonSubTypes.Type(value = EventPayload.RpcBlockEnd.class, name = "rpc.block.end"),
  @JsonSubTypes.Type(value = EventPayload.ExecutionCancel.class, name = "execution.cancel"),
  @JsonSubTypes.Type(value = EventPayload.ExecutionEnd.class, name = "execution.end"),
  @JsonSubTypes.Type(value = EventPayload.ResourceRelease.class, name = "resource.release")
})
public sealed interface EventPayload {
  EventType eventType();

  record ResourceInit(String resourceId, int capacity, List<String> unitIds)
      implements EventPayload {
    public ResourceInit {
      resourceId = ContractValidation.id(resourceId, "resourceId");
      ContractValidation.positive(capacity, "capacity");
      unitIds = ContractValidation.ids(unitIds, "unitIds");
      if (unitIds.size() != capacity) {
        throw new IllegalArgumentException("capacity must equal unitIds size");
      }
    }

    @Override
    public EventType eventType() {
      return EventType.RESOURCE_INIT;
    }
  }

  record InstanceCheckpoint(long throughSeq, long droppedTotal) implements EventPayload {
    public InstanceCheckpoint {
      ContractValidation.nonnegative(throughSeq, "throughSeq");
      ContractValidation.nonnegative(droppedTotal, "droppedTotal");
    }

    @Override
    public EventType eventType() {
      return EventType.INSTANCE_CHECKPOINT;
    }
  }

  record RpcInvocationSent(String invocationId, String callerExecutionId, String targetService)
      implements EventPayload {
    public RpcInvocationSent {
      invocationId = ContractValidation.id(invocationId, "invocationId");
      callerExecutionId = ContractValidation.optionalId(callerExecutionId, "callerExecutionId");
      targetService = ContractValidation.id(targetService, "targetService");
    }

    @Override
    public EventType eventType() {
      return EventType.RPC_INVOCATION_SENT;
    }
  }

  record RpcInvocationArrived(String invocationId) implements EventPayload {
    public RpcInvocationArrived {
      invocationId = ContractValidation.id(invocationId, "invocationId");
    }

    @Override
    public EventType eventType() {
      return EventType.RPC_INVOCATION_ARRIVED;
    }
  }

  record ResourceWaitBegin(String invocationId, String resourceId) implements EventPayload {
    public ResourceWaitBegin {
      invocationId = ContractValidation.id(invocationId, "invocationId");
      resourceId = ContractValidation.id(resourceId, "resourceId");
    }

    @Override
    public EventType eventType() {
      return EventType.RESOURCE_WAIT_BEGIN;
    }
  }

  record ResourceAcquire(String executionId, String resourceInstanceId, String invocationId)
      implements EventPayload {
    public ResourceAcquire {
      executionId = ContractValidation.id(executionId, "executionId");
      resourceInstanceId = ContractValidation.id(resourceInstanceId, "resourceInstanceId");
      invocationId = ContractValidation.id(invocationId, "invocationId");
    }

    @Override
    public EventType eventType() {
      return EventType.RESOURCE_ACQUIRE;
    }
  }

  record RpcBlockBegin(String executionId, String invocationId) implements EventPayload {
    public RpcBlockBegin {
      executionId = ContractValidation.id(executionId, "executionId");
      invocationId = ContractValidation.id(invocationId, "invocationId");
    }

    @Override
    public EventType eventType() {
      return EventType.RPC_BLOCK_BEGIN;
    }
  }

  record ExecutionLease(
      String executionId,
      String invocationId,
      String startRef,
      String blockRef,
      long observedAgeMs,
      long leaseExpiryMs)
      implements EventPayload {
    public ExecutionLease {
      executionId = ContractValidation.id(executionId, "executionId");
      invocationId = ContractValidation.id(invocationId, "invocationId");
      startRef = ContractValidation.id(startRef, "startRef");
      blockRef = ContractValidation.id(blockRef, "blockRef");
      ContractValidation.nonnegative(observedAgeMs, "observedAgeMs");
      ContractValidation.positive(leaseExpiryMs, "leaseExpiryMs");
    }

    @Override
    public EventType eventType() {
      return EventType.EXECUTION_LEASE;
    }
  }

  record RpcResponseSent(String invocationId, String executionId, Outcome outcome)
      implements EventPayload {
    public RpcResponseSent {
      invocationId = ContractValidation.id(invocationId, "invocationId");
      executionId = ContractValidation.optionalId(executionId, "executionId");
      Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public EventType eventType() {
      return EventType.RPC_RESPONSE_SENT;
    }
  }

  record RpcBlockEnd(
      String executionId, String invocationId, Outcome outcome, CompletionKind completionKind)
      implements EventPayload {
    public RpcBlockEnd {
      executionId = ContractValidation.id(executionId, "executionId");
      invocationId = ContractValidation.id(invocationId, "invocationId");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(completionKind, "completionKind");
    }

    @Override
    public EventType eventType() {
      return EventType.RPC_BLOCK_END;
    }
  }

  record ExecutionCancel(
      String executionId, String invocationId, CancelSource cancelSource, CancelReason cancelReason)
      implements EventPayload {
    public ExecutionCancel {
      executionId = ContractValidation.optionalId(executionId, "executionId");
      invocationId = ContractValidation.id(invocationId, "invocationId");
      Objects.requireNonNull(cancelSource, "cancelSource");
      Objects.requireNonNull(cancelReason, "cancelReason");
    }

    @Override
    public EventType eventType() {
      return EventType.EXECUTION_CANCEL;
    }
  }

  record ExecutionEnd(String executionId, String invocationId, Outcome outcome)
      implements EventPayload {
    public ExecutionEnd {
      executionId = ContractValidation.id(executionId, "executionId");
      invocationId = ContractValidation.id(invocationId, "invocationId");
      Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public EventType eventType() {
      return EventType.EXECUTION_END;
    }
  }

  record ResourceRelease(String executionId, String resourceInstanceId) implements EventPayload {
    public ResourceRelease {
      executionId = ContractValidation.id(executionId, "executionId");
      resourceInstanceId = ContractValidation.id(resourceInstanceId, "resourceInstanceId");
    }

    @Override
    public EventType eventType() {
      return EventType.RESOURCE_RELEASE;
    }
  }

  enum Outcome {
    OK,
    ERROR,
    CANCELLED,
    DEADLINE
  }

  enum CompletionKind {
    RESPONSE,
    LOCAL
  }

  enum CancelSource {
    CLIENT,
    SERVER,
    DEADLINE
  }

  enum CancelReason {
    REQUESTED,
    DEADLINE,
    REJECTED
  }
}
