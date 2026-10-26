package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual SDK retry through actual receiver admission. The overload callback releases capacity. */
@Timeout(30)
class ReceiverSdkInteropTest {
  @Test
  void sdkRetriesUnavailableAfterAtomicReceiverCapacityBecomesAvailable() throws Exception {
    AnalyzerIngress ingress = new AnalyzerIngress(1);
    var receiver =
        new OtlpLogReceiver(
            Set.of("A"),
            ingress,
            Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC),
            () -> 7);
    AtomicReference<List<ObservationEnvelope>> first = new AtomicReference<>();
    ServerInterceptor drainOnOverload =
        new ServerInterceptor() {
          @Override
          public <Q, R> ServerCall.Listener<Q> interceptCall(
              ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                  @Override
                  public void close(Status status, Metadata trailers) {
                    if (status.getCode() == Status.Code.UNAVAILABLE) {
                      try {
                        first.set(ingress.poll(0));
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                      }
                    }
                    super.close(status, trailers);
                  }
                },
                headers);
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(ServerInterceptors.intercept(receiver, drainOnOverload))
            .build()
            .start();
    var exporter =
        OtlpGrpcLogRecordExporter.builder()
            .setEndpoint("http://127.0.0.1:" + server.getPort())
            .setTimeout(Duration.ofSeconds(5))
            .setRetryPolicy(
                RetryPolicy.builder()
                    .setMaxAttempts(2)
                    .setInitialBackoff(Duration.ofMillis(10))
                    .setMaxBackoff(Duration.ofMillis(20))
                    .build())
            .build();
    try {
      assertThat(exporter.export(record(1)).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(exporter.export(record(2)).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(receiver.overloadedRequests()).isEqualTo(1);
      assertThat(first.get()).extracting(e -> e.event().localSeq()).containsExactly(1L);
      assertThat(ingress.poll(0)).extracting(e -> e.event().localSeq()).containsExactly(2L);
      assertThat(receiver.rejectedRequests()).isZero();
    } finally {
      exporter.shutdown().join(5, TimeUnit.SECONDS);
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static List<LogRecordData> record(long sequence) throws Exception {
    List<LogRecordData> records = new ArrayList<>();
    LogRecordExporter capture =
        new LogRecordExporter() {
          @Override
          public CompletableResultCode export(Collection<LogRecordData> batch) {
            records.addAll(batch);
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };
    var event =
        new ObservationEvent(
            2,
            "A",
            sequence,
            "",
            1_000,
            sequence,
            "",
            "",
            new EventPayload.InstanceCheckpoint(sequence - 1, 0));
    try (var provider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(capture))
            .build()) {
      provider
          .get("interop")
          .logRecordBuilder()
          .setBody(ContractJson.mapper().writeValueAsString(event))
          .setAttribute(AttributeKey.longKey("sl.schema_version"), 2L)
          .setAttribute(AttributeKey.stringKey("sl.instance_id"), "A")
          .setAttribute(AttributeKey.stringKey("sl.event_type"), "instance.checkpoint")
          .setAttribute(AttributeKey.longKey("sl.local_seq"), sequence)
          .setAttribute(AttributeKey.stringKey("sl.causal_parent"), "")
          .setAttribute(AttributeKey.longKey("sl.wall_time_ms"), 1_000L)
          .setAttribute(AttributeKey.longKey("sl.monotonic_time_ns"), sequence)
          .emit();
    }
    return records;
  }
}
