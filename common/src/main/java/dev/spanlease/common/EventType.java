package dev.spanlease.common;

import com.fasterxml.jackson.annotation.JsonValue;

/** Stable schema-v2 event type names. */
public enum EventType {
  RESOURCE_INIT("resource.init"),
  INSTANCE_CHECKPOINT("instance.checkpoint"),
  RPC_INVOCATION_SENT("rpc.invocation.sent"),
  RPC_INVOCATION_ARRIVED("rpc.invocation.arrived"),
  RESOURCE_WAIT_BEGIN("resource.wait.begin"),
  RESOURCE_ACQUIRE("resource.acquire"),
  RPC_BLOCK_BEGIN("rpc.block.begin"),
  EXECUTION_LEASE("execution.lease"),
  RPC_RESPONSE_SENT("rpc.response.sent"),
  RPC_BLOCK_END("rpc.block.end"),
  EXECUTION_CANCEL("execution.cancel"),
  EXECUTION_END("execution.end"),
  RESOURCE_RELEASE("resource.release");

  private final String wireName;

  EventType(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }
}
