package dev.spanlease.analyzer;

import dev.spanlease.common.ObservationEnvelope;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * OTLP/gRPC logs receiver. Each export request is decoded and validated as a whole, stamped with
 * receipt time, and admitted to the bounded ingress atomically or not at all.
 *
 * <p>A malformed record, or a record from an instance outside the manifest, rejects the request
 * with INVALID_ARGUMENT: OTLP does not retry it, and the producer's sequence gap exposes the loss.
 * When the ingress cannot hold the whole batch the request fails with UNAVAILABLE so the exporter
 * retries; partial success is never reported for a batch that was not admitted.
 */
final class OtlpLogReceiver extends LogsServiceGrpc.LogsServiceImplBase {
  private final Set<String> scopeInstances;
  private final AnalyzerIngress ingress;
  private final OtlpEventDecoder decoder = new OtlpEventDecoder();
  private final Clock wallClock;
  private final LongSupplier monotonicClock;
  private final AtomicLong rejectedRequests = new AtomicLong();
  private final AtomicLong overloadedRequests = new AtomicLong();

  OtlpLogReceiver(
      Set<String> scopeInstances,
      AnalyzerIngress ingress,
      Clock wallClock,
      LongSupplier monotonicClock) {
    this.scopeInstances = Set.copyOf(scopeInstances);
    this.ingress = Objects.requireNonNull(ingress, "ingress");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
  }

  @Override
  public void export(
      ExportLogsServiceRequest request, StreamObserver<ExportLogsServiceResponse> response) {
    List<ObservationEnvelope> batch;
    try {
      batch = decoder.decode(request, wallClock.millis(), monotonicClock.getAsLong());
    } catch (RuntimeException malformed) {
      reject(response, "malformed SpanLease record: " + malformed.getMessage());
      return;
    }
    Optional<String> outside =
        batch.stream()
            .map(envelope -> envelope.event().instanceId())
            .filter(instance -> !scopeInstances.contains(instance))
            .findFirst();
    if (outside.isPresent()) {
      reject(response, "instance outside the run manifest: " + outside.orElseThrow());
      return;
    }
    if (!ingress.tryAdmit(batch)) {
      overloadedRequests.incrementAndGet();
      response.onError(
          Status.UNAVAILABLE.withDescription("analyzer ingress is full").asRuntimeException());
      return;
    }
    response.onNext(ExportLogsServiceResponse.getDefaultInstance());
    response.onCompleted();
  }

  long rejectedRequests() {
    return rejectedRequests.get();
  }

  long overloadedRequests() {
    return overloadedRequests.get();
  }

  private void reject(StreamObserver<ExportLogsServiceResponse> response, String description) {
    rejectedRequests.incrementAndGet();
    response.onError(Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException());
  }
}
