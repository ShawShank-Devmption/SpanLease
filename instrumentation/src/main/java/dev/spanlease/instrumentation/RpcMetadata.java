package dev.spanlease.instrumentation;

import dev.spanlease.common.EventPayload;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Optional;

/**
 * SpanLease gRPC metadata (design §4): request identity and send reference, and the terminal
 * response reference in trailers. Values are validated before use; an absent, duplicated or
 * malformed value is treated as absent rather than repaired.
 */
final class RpcMetadata {
  static final Metadata.Key<String> INVOCATION_ID = key("sl-invocation-id");
  static final Metadata.Key<String> CALLER_INSTANCE = key("sl-caller-instance");
  static final Metadata.Key<String> CALLER_SEQ = key("sl-caller-seq");
  static final Metadata.Key<String> RESPONSE_INSTANCE = key("sl-response-instance");
  static final Metadata.Key<String> RESPONSE_SEQ = key("sl-response-seq");

  private static final int MAX_ID_LENGTH = 256;
  private static final int MAX_SEQUENCE_DIGITS = 18;

  private RpcMetadata() {}

  static Metadata requestHeaders(String invocationId, String sentReference) {
    Metadata headers = new Metadata();
    headers.put(INVOCATION_ID, invocationId);
    int separator = sentReference.lastIndexOf('#');
    headers.put(CALLER_INSTANCE, sentReference.substring(0, separator));
    headers.put(CALLER_SEQ, sentReference.substring(separator + 1));
    return headers;
  }

  /** Replaces any forwarded response reference so trailers name exactly this server's record. */
  static void putResponse(Metadata trailers, String responseReference) {
    int separator = responseReference.lastIndexOf('#');
    trailers.removeAll(RESPONSE_INSTANCE);
    trailers.removeAll(RESPONSE_SEQ);
    trailers.put(RESPONSE_INSTANCE, responseReference.substring(0, separator));
    trailers.put(RESPONSE_SEQ, responseReference.substring(separator + 1));
  }

  static Optional<String> invocationId(Metadata headers) {
    return single(headers, INVOCATION_ID).filter(RpcMetadata::validId);
  }

  static Optional<String> requestReference(Metadata headers) {
    return reference(headers, CALLER_INSTANCE, CALLER_SEQ);
  }

  static Optional<String> responseReference(Metadata trailers) {
    return trailers == null
        ? Optional.empty()
        : reference(trailers, RESPONSE_INSTANCE, RESPONSE_SEQ);
  }

  static EventPayload.Outcome outcome(Status status) {
    return switch (status.getCode()) {
      case OK -> EventPayload.Outcome.OK;
      case CANCELLED -> EventPayload.Outcome.CANCELLED;
      case DEADLINE_EXCEEDED -> EventPayload.Outcome.DEADLINE;
      default -> EventPayload.Outcome.ERROR;
    };
  }

  private static Optional<String> reference(
      Metadata metadata, Metadata.Key<String> instanceKey, Metadata.Key<String> sequenceKey) {
    Optional<String> instance =
        single(metadata, instanceKey).filter(value -> validId(value) && !value.contains("#"));
    Optional<String> sequence = single(metadata, sequenceKey).filter(RpcMetadata::validSequence);
    if (instance.isEmpty() || sequence.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(instance.orElseThrow() + "#" + sequence.orElseThrow());
  }

  private static Metadata.Key<String> key(String name) {
    return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
  }

  private static Optional<String> single(Metadata metadata, Metadata.Key<String> key) {
    Iterable<String> values = metadata.getAll(key);
    if (values == null) {
      return Optional.empty();
    }
    var iterator = values.iterator();
    String first = iterator.next();
    return iterator.hasNext() ? Optional.empty() : Optional.of(first);
  }

  private static boolean validId(String value) {
    return !value.isBlank()
        && value.length() <= MAX_ID_LENGTH
        && value.chars().allMatch(character -> character > 0x20 && character < 0x7f);
  }

  private static boolean validSequence(String value) {
    if (value.isEmpty()
        || value.length() > MAX_SEQUENCE_DIGITS
        || !value.chars().allMatch(Character::isDigit)) {
      return false;
    }
    return Long.parseLong(value) > 0;
  }
}
