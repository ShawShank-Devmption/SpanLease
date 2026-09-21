package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.INVALID_ARRIVAL;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RESPONSE_RESERVED;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.CALLER_INSTANCE;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.CALLER_SEQ;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.INVOCATION_ID;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.REQUEST_IDENTITY;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.RESPONSE_INSTANCE;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.RESPONSE_SEQ;
import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class FeasibilityServerHooksTest {
  private static final String INVOCATION = "11111111-1111-4111-8111-111111111111";

  @Test
  void acceptsOnlyCompleteBoundedRequestIdentity() {
    Metadata valid = validHeaders();
    assertThat(FeasibilityMetadata.requestIdentity(valid).orElseThrow().sentReference())
        .isEqualTo("client@probe#7");

    assertThat(FeasibilityMetadata.requestIdentity(headers("", "client@probe", "7"))).isEmpty();
    assertThat(FeasibilityMetadata.requestIdentity(headers(INVOCATION, "a".repeat(257), "7")))
        .isEmpty();
    assertThat(FeasibilityMetadata.requestIdentity(headers(INVOCATION, "client@probe", "NaN")))
        .isEmpty();
    assertThat(FeasibilityMetadata.requestIdentity(headers(INVOCATION, "client@probe", "0")))
        .isEmpty();

    Metadata missingSequence = new Metadata();
    missingSequence.put(INVOCATION_ID, INVOCATION);
    missingSequence.put(CALLER_INSTANCE, "client@probe");
    assertThat(FeasibilityMetadata.requestIdentity(missingSequence)).isEmpty();
  }

  @Test
  void arrivalTracerCopiesHeadersBeforeContextPropagation() {
    FeasibilityJournal journal = new FeasibilityJournal();
    Metadata headers = validHeaders();
    ServerStreamTracer tracer =
        FeasibilityServerHooks.arrivalTracerFactory(journal)
            .newServerStreamTracer(ChainGrpc.getCallMethod().getFullMethodName(), headers);

    headers.discardAll(CALLER_INSTANCE);
    headers.put(CALLER_INSTANCE, "mutated@caller");
    Context filtered = tracer.filterContext(Context.ROOT);

    assertThat(REQUEST_IDENTITY.get(filtered)).isNotNull();
    assertThat(REQUEST_IDENTITY.get(filtered).invocationId()).isEqualTo(INVOCATION);
    assertThat(REQUEST_IDENTITY.get(filtered).sentReference()).isEqualTo("client@probe#7");
    assertThat(journal.entriesFor(INVOCATION))
        .extracting(FeasibilityJournal.Entry::hook)
        .containsExactly(FeasibilityJournal.Hook.ARRIVAL);
  }

  @Test
  void malformedArrivalDoesNotPopulateContext() {
    FeasibilityJournal journal = new FeasibilityJournal();
    Metadata headers = headers(INVOCATION, "client@probe", "0");
    ServerStreamTracer tracer =
        FeasibilityServerHooks.arrivalTracerFactory(journal)
            .newServerStreamTracer(ChainGrpc.getCallMethod().getFullMethodName(), headers);

    assertThat(REQUEST_IDENTITY.get(tracer.filterContext(Context.ROOT))).isNull();
    assertThat(journal.snapshot())
        .extracting(FeasibilityJournal.Entry::hook)
        .containsExactly(INVALID_ARRIVAL);
  }

  @Test
  void responseInterceptorReservesExactlyOneReferenceBeforeClose() throws Exception {
    FeasibilityJournal journal = new FeasibilityJournal();
    ServerInterceptor interceptor =
        FeasibilityServerHooks.responseInterceptor(journal, "server@probe");
    CapturingServerCall delegate = new CapturingServerCall();
    AtomicReference<ServerCall<CallRequest, CallReply>> wrapped = new AtomicReference<>();
    ServerCallHandler<CallRequest, CallReply> handler =
        (call, headers) -> {
          wrapped.set(call);
          return new ServerCall.Listener<>() {};
        };
    FeasibilityMetadata.RequestIdentity identity =
        new FeasibilityMetadata.RequestIdentity(INVOCATION, "client@probe#7");

    Context.current()
        .withValue(REQUEST_IDENTITY, identity)
        .call(() -> interceptor.interceptCall(delegate, new Metadata(), handler));
    wrapped.get().close(Status.OK, new Metadata());
    wrapped.get().close(Status.CANCELLED, new Metadata());

    assertThat(journal.entriesFor(INVOCATION))
        .extracting(FeasibilityJournal.Entry::hook)
        .containsOnlyOnce(RESPONSE_RESERVED);
    Metadata firstTrailers = delegate.closedTrailers.getFirst();
    assertThat(firstTrailers.get(RESPONSE_INSTANCE)).isEqualTo("server@probe");
    assertThat(Long.parseLong(firstTrailers.get(RESPONSE_SEQ))).isPositive();
    assertThat(delegate.closedTrailers).hasSize(2);
  }

  private static Metadata validHeaders() {
    return headers(INVOCATION, "client@probe", "7");
  }

  private static Metadata headers(String invocationId, String callerInstance, String callerSeq) {
    Metadata headers = new Metadata();
    headers.put(INVOCATION_ID, invocationId);
    headers.put(CALLER_INSTANCE, callerInstance);
    headers.put(CALLER_SEQ, callerSeq);
    return headers;
  }

  private static final class CapturingServerCall extends ServerCall<CallRequest, CallReply> {
    private final List<Metadata> closedTrailers = new ArrayList<>();

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(CallReply message) {}

    @Override
    public void close(Status status, Metadata trailers) {
      Metadata copy = new Metadata();
      copy.merge(trailers);
      closedTrailers.add(copy);
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public io.grpc.MethodDescriptor<CallRequest, CallReply> getMethodDescriptor() {
      return ChainGrpc.getCallMethod();
    }

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }
  }
}
