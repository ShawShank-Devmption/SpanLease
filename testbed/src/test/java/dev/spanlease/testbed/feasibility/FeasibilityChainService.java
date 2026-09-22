package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.READY;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.REQUEST_IDENTITY;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Objects;

final class FeasibilityChainService extends ChainGrpc.ChainImplBase {
  @FunctionalInterface
  interface HandlerBehavior {
    void handle(
        CallRequest request,
        StreamObserver<CallReply> observer,
        FeasibilityApplicationGate.Execution execution)
        throws Exception;
  }

  private final FeasibilityJournal journal;
  private final FeasibilityApplicationGate gate;
  private final SdkTracerProvider provider;
  private final HandlerBehavior behavior;

  FeasibilityChainService(
      FeasibilityJournal journal,
      FeasibilityApplicationGate gate,
      SdkTracerProvider provider,
      HandlerBehavior behavior) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.behavior = Objects.requireNonNull(behavior, "behavior");
  }

  @Override
  public void call(CallRequest request, StreamObserver<CallReply> observer) {
    FeasibilityMetadata.RequestIdentity identity = REQUEST_IDENTITY.get();
    if (identity == null) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("missing invocation metadata")
              .asRuntimeException());
      return;
    }
    if (request.getRemainingCount() == 0
        || !request.getRemaining(0).getTargetService().equals("svcA")) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("expected svcA head hop").asRuntimeException());
      return;
    }
    String invocationId = identity.invocationId();
    journal.append(READY, invocationId, "", "", identity.sentReference(), "");
    ServerCallStreamObserver<CallReply> serverObserver =
        (ServerCallStreamObserver<CallReply>) observer;
    serverObserver.setOnCancelHandler(() -> gate.cancel(invocationId));
    FeasibilityApplicationGate.Admission admission =
        gate.submit(
            invocationId,
            execution -> {
              Span span =
                  provider
                      .get("b-feas")
                      .spanBuilder("application")
                      .setAttribute(FeasibilitySpanProcessor.INVOCATION_ID_ATTRIBUTE, invocationId)
                      .startSpan();
              try (Scope scope = span.makeCurrent()) {
                try {
                  behavior.handle(request, observer, execution);
                } catch (Exception failure) {
                  observer.onError(Status.INTERNAL.withCause(failure).asRuntimeException());
                  throw failure;
                }
              } finally {
                span.end();
              }
            });
    if (admission == FeasibilityApplicationGate.Admission.REJECTED) {
      observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());
    } else if (serverObserver.isCancelled()) {
      // A cancellation can win after callback installation but before gate registration.
      gate.cancel(invocationId);
    }
  }
}
