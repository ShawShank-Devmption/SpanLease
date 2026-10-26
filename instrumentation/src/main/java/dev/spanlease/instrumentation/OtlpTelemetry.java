package dev.spanlease.instrumentation;

import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated, unsampled OTLP/gRPC LogRecord pipeline for one instance's SpanLease events.
 *
 * <p>Application paths reach only the bounded {@link OtelEventSink} offer. Batching (100 records,
 * 50 ms flush; design §4.1), export and OTLP retry run on SDK threads. Delivery is not assumed:
 * losses in the sink queue, SDK queue or transport surface to the analyzer as sequence gaps.
 */
public final class OtlpTelemetry implements AutoCloseable {
  private static final int SDK_QUEUE_RECORDS = 8192;
  private static final int EXPORT_BATCH_RECORDS = 100;
  private static final Duration EXPORT_FLUSH = Duration.ofMillis(50);

  private final SdkLoggerProvider provider;
  private final OtelEventSink sink;

  private OtlpTelemetry(SdkLoggerProvider provider, OtelEventSink sink) {
    this.provider = provider;
    this.sink = sink;
  }

  /**
   * Starts a pipeline exporting to an OTLP/gRPC endpoint such as {@code http://127.0.0.1:4317}.
   * Loopback plaintext is the experiment default; remote endpoints require authenticated TLS.
   */
  public static OtlpTelemetry start(String endpoint, String instanceId, int sinkCapacity) {
    Objects.requireNonNull(endpoint, "endpoint");
    OtlpGrpcLogRecordExporter exporter =
        OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(endpoint)
            .setRetryPolicy(RetryPolicy.getDefault())
            .build();
    SdkLoggerProvider provider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(
                BatchLogRecordProcessor.builder(exporter)
                    .setMaxQueueSize(SDK_QUEUE_RECORDS)
                    .setMaxExportBatchSize(EXPORT_BATCH_RECORDS)
                    .setScheduleDelay(EXPORT_FLUSH)
                    .build())
            .build();
    OtelEventSink sink =
        new OtelEventSink(
            sinkCapacity, provider.get("spanlease"), "spanlease-export-" + instanceId);
    return new OtlpTelemetry(provider, sink);
  }

  public OtelEventSink sink() {
    return sink;
  }

  /** Drains the sink queue into the SDK, then flushes and shuts down the exporter. */
  @Override
  public void close() throws InterruptedException {
    try {
      sink.close();
    } finally {
      provider.forceFlush().join(5, TimeUnit.SECONDS);
      provider.shutdown().join(5, TimeUnit.SECONDS);
    }
  }
}
