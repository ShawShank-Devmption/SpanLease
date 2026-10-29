package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RESPONSE_OBSERVED;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.SENT;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.CALLER_INSTANCE;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.CALLER_SEQ;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.INVOCATION_ID;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class FeasibilityClientHooks {
  static final class CallCapture {
    final String invocationId;
    final AtomicReference<String> sentReference = new AtomicReference<>("");
    final AtomicReference<String> responseReference = new AtomicReference<>("");
    final AtomicReference<Status> terminalStatus = new AtomicReference<>();
    final AtomicBoolean localCancellationRequested = new AtomicBoolean();

    CallCapture(String invocationId) {
      this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    }
  }

  private FeasibilityClientHooks() {}

  static ClientInterceptor interceptor(
      FeasibilityJournal journal, String clientInstance, CallCapture capture) {
    Objects.requireNonNull(journal, "journal");
    Objects.requireNonNull(capture, "capture");
    if (!FeasibilityMetadata.validInstance(clientInstance)) {
      throw new IllegalArgumentException("clientInstance must be bounded visible ASCII");
    }
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(
            next.newCall(method, options)) {
          @Override
          public void start(Listener<RespT> listener, Metadata headers) {
            FeasibilityJournal.Entry sent =
                journal.append(SENT, capture.invocationId, "", "", "", "");
            String reference = clientInstance + "#" + sent.ordinal();
            capture.sentReference.set(reference);
            headers.put(INVOCATION_ID, capture.invocationId);
            headers.put(CALLER_INSTANCE, clientInstance);
            headers.put(CALLER_SEQ, Long.toString(sent.ordinal()));
            Listener<RespT> wrapped =
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    FeasibilityMetadata.responseReference(trailers)
                        .ifPresent(
                            response -> {
                              capture.responseReference.set(response);
                              journal.append(
                                  RESPONSE_OBSERVED,
                                  capture.invocationId,
                                  "",
                                  "",
                                  response,
                                  status.getCode().name());
                            });
                    capture.terminalStatus.set(status);
                    super.onClose(status, trailers);
                  }
                };
            super.start(wrapped, headers);
          }
        };
      }
    };
  }
}
