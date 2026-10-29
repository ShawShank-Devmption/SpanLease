package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Identity and metadata instrumentation for root (driver) calls. A root has no execution: it
 * records only {@code rpc.invocation.sent} with an empty caller execution, before dispatch, and
 * propagates that reference so the receiver's arrival has an exact causal parent.
 */
public final class RootCaller {
  private final InstanceRecorder recorder;
  private final Supplier<String> invocationIds;

  public RootCaller(InstanceRecorder recorder, Supplier<String> invocationIds) {
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.invocationIds = Objects.requireNonNull(invocationIds, "invocationIds");
  }

  /** Performs exactly one blocking unary call through {@code rpc} on the instrumented stub. */
  public <S extends AbstractStub<S>, R> R call(String targetService, S stub, Function<S, R> rpc) {
    Objects.requireNonNull(stub, "stub");
    Objects.requireNonNull(rpc, "rpc");
    String invocationId = invocationIds.get();
    ObservationEvent sent =
        recorder.publish(new EventPayload.RpcInvocationSent(invocationId, "", targetService), "");
    return rpc.apply(
        stub.withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(
                RpcMetadata.requestHeaders(invocationId, sent.eventReference()))));
  }
}
