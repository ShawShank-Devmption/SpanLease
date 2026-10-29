package dev.spanlease.testbed.feasibility;

import io.grpc.Context;
import io.grpc.Metadata;
import java.util.Optional;
import java.util.UUID;

final class FeasibilityMetadata {
  static final Metadata.Key<String> INVOCATION_ID =
      Metadata.Key.of("sl-invocation-id", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> CALLER_INSTANCE =
      Metadata.Key.of("sl-caller-instance", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> CALLER_SEQ =
      Metadata.Key.of("sl-caller-seq", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> RESPONSE_INSTANCE =
      Metadata.Key.of("sl-response-instance", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> RESPONSE_SEQ =
      Metadata.Key.of("sl-response-seq", Metadata.ASCII_STRING_MARSHALLER);
  static final Context.Key<RequestIdentity> REQUEST_IDENTITY =
      Context.key("bfeas-request-identity");

  private static final int MAX_IDENTITY_LENGTH = 256;

  private FeasibilityMetadata() {}

  static Optional<RequestIdentity> requestIdentity(Metadata headers) {
    if (headers == null) {
      return Optional.empty();
    }
    String invocationId = headers.get(INVOCATION_ID);
    String callerInstance = headers.get(CALLER_INSTANCE);
    Optional<Long> callerSequence = positiveSequence(headers.get(CALLER_SEQ));
    if (!validInvocationId(invocationId)
        || !validInstance(callerInstance)
        || callerSequence.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new RequestIdentity(invocationId, callerInstance + "#" + callerSequence.orElseThrow()));
  }

  static Optional<String> responseReference(Metadata trailers) {
    if (trailers == null) {
      return Optional.empty();
    }
    String responseInstance = trailers.get(RESPONSE_INSTANCE);
    Optional<Long> responseSequence = positiveSequence(trailers.get(RESPONSE_SEQ));
    if (!validInstance(responseInstance) || responseSequence.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(responseInstance + "#" + responseSequence.orElseThrow());
  }

  static boolean validInstance(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_IDENTITY_LENGTH) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21 || character > 0x7e) {
        return false;
      }
    }
    return true;
  }

  private static boolean validInvocationId(String value) {
    if (value == null || value.length() > MAX_IDENTITY_LENGTH) {
      return false;
    }
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static Optional<Long> positiveSequence(String value) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  record RequestIdentity(String invocationId, String sentReference) {}
}
