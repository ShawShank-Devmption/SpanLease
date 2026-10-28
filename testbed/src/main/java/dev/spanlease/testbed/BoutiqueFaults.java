package dev.spanlease.testbed;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Application-only fault metadata; absent metadata always means ordinary local GetAds handling. */
final class BoutiqueFaults {
  static final Metadata.Key<String> HOPS =
      Metadata.Key.of("x-spanlease-boutique-hops", Metadata.ASCII_STRING_MARSHALLER);
  static final Context.Key<Integer> REMAINING =
      Context.keyWithDefault("boutique-diagnostic-hops", 0);

  private BoutiqueFaults() {}

  static int parse(Metadata headers, boolean enabled) {
    Iterable<String> values = headers.getAll(HOPS);
    if (values == null) return 0;
    var iterator = values.iterator();
    String value = iterator.next();
    if (!enabled
        || iterator.hasNext()
        || value.length() != 1
        || value.charAt(0) < '0'
        || value.charAt(0) > '3')
      throw Status.INVALID_ARGUMENT
          .withDescription("diagnostic hops require enabled fault mode and one integer 0..3")
          .asRuntimeException();
    return value.charAt(0) - '0';
  }

  static Metadata headers(int hops) {
    if (hops < 0 || hops > 3) throw new IllegalArgumentException("diagnostic budget must be 0..3");
    Metadata headers = new Metadata();
    headers.put(HOPS, Integer.toString(hops));
    return headers;
  }

  static ServerInterceptor interceptor(boolean enabled) {
    return new ServerInterceptor() {
      @Override
      public <Q, R> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
        final int hops;
        try {
          hops = parse(headers, enabled);
        } catch (StatusRuntimeException invalid) {
          call.close(invalid.getStatus(), new Metadata());
          return new ServerCall.Listener<>() {};
        }
        return Contexts.interceptCall(
            Context.current().withValue(REMAINING, hops), call, headers, next);
      }
    };
  }
}
