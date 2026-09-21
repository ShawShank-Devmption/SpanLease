package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.ARRIVAL;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.INVALID_ARRIVAL;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.RESPONSE_RESERVED;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.REQUEST_IDENTITY;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.RESPONSE_INSTANCE;
import static dev.spanlease.testbed.feasibility.FeasibilityMetadata.RESPONSE_SEQ;

import io.grpc.Context;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class FeasibilityServerHooks {
  private FeasibilityServerHooks() {}

  static ServerStreamTracer.Factory arrivalTracerFactory(FeasibilityJournal journal) {
    Objects.requireNonNull(journal, "journal");
    return new ServerStreamTracer.Factory() {
      @Override
      public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
        FeasibilityMetadata.RequestIdentity identity =
            FeasibilityMetadata.requestIdentity(headers).orElse(null);
        if (identity == null) {
          journal.append(INVALID_ARRIVAL, "", "", "", "", fullMethodName);
        } else {
          journal.append(
              ARRIVAL, identity.invocationId(), "", "", identity.sentReference(), fullMethodName);
        }
        return new ServerStreamTracer() {
          @Override
          public Context filterContext(Context context) {
            return identity == null ? context : context.withValue(REQUEST_IDENTITY, identity);
          }
        };
      }
    };
  }

  static ServerInterceptor responseInterceptor(FeasibilityJournal journal, String serverInstance) {
    Objects.requireNonNull(journal, "journal");
    if (!FeasibilityMetadata.validInstance(serverInstance)) {
      throw new IllegalArgumentException("serverInstance must be bounded visible ASCII");
    }
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        FeasibilityMetadata.RequestIdentity identity = REQUEST_IDENTITY.get();
        AtomicBoolean reserved = new AtomicBoolean();
        ServerCall<ReqT, RespT> wrapped =
            new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
              @Override
              public void close(Status status, Metadata trailers) {
                if (identity != null && reserved.compareAndSet(false, true)) {
                  FeasibilityJournal.Entry entry =
                      journal.append(
                          RESPONSE_RESERVED,
                          identity.invocationId(),
                          "",
                          "",
                          "",
                          status.getCode().name());
                  trailers.discardAll(RESPONSE_INSTANCE);
                  trailers.discardAll(RESPONSE_SEQ);
                  trailers.put(RESPONSE_INSTANCE, serverInstance);
                  trailers.put(RESPONSE_SEQ, Long.toString(entry.ordinal()));
                }
                super.close(status, trailers);
              }
            };
        return next.startCall(wrapped, headers);
      }
    };
  }
}
