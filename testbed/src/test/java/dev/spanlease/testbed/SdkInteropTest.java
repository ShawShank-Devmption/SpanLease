package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.proto.collector.logs.v1.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual pinned SDK exporter and loopback OTLP server, without analyzer dependencies. */
@Timeout(30)
class SdkInteropTest {
  @Test
  void unavailableRetriesTheIdenticalBatchAndThenSucceeds() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<ExportLogsServiceRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (Fixture f =
        new Fixture(
            (request, response) -> {
              requests.add(request);
              if (calls.incrementAndGet() == 1)
                response.onError(Status.UNAVAILABLE.asRuntimeException());
              else {
                response.onNext(ExportLogsServiceResponse.getDefaultInstance());
                response.onCompleted();
              }
            },
            1_000_000)) {
      var result = f.export("record");
      assertThat(result.isSuccess()).isTrue();
      assertThat(calls.get()).isEqualTo(2);
      assertThat(requests.getFirst()).isEqualTo(requests.getLast());
    }
  }

  @Test
  void partialSuccessIsNotRetried() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fixture f =
        new Fixture(
            (request, response) -> {
              calls.incrementAndGet();
              response.onNext(
                  ExportLogsServiceResponse.newBuilder()
                      .setPartialSuccess(
                          ExportLogsPartialSuccess.newBuilder()
                              .setRejectedLogRecords(1)
                              .setErrorMessage("test rejection"))
                      .build());
              response.onCompleted();
            },
            1_000_000)) {
      assertThat(f.export("record").isSuccess()).isTrue();
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @Test
  void invalidArgumentIsNotRetried() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fixture f =
        new Fixture(
            (request, response) -> {
              calls.incrementAndGet();
              response.onError(Status.INVALID_ARGUMENT.asRuntimeException());
            },
            1_000_000)) {
      assertThat(f.export("record").isSuccess()).isFalse();
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @Test
  void messageSizeLimitRejectsBeforeAdmission() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fixture f =
        new Fixture(
            (request, response) -> {
              calls.incrementAndGet();
              response.onNext(ExportLogsServiceResponse.getDefaultInstance());
              response.onCompleted();
            },
            128)) {
      assertThat(f.export("x".repeat(4_096)).isSuccess()).isFalse();
      assertThat(calls.get()).isZero();
    }
  }

  private interface Handler {
    void export(
        ExportLogsServiceRequest request, StreamObserver<ExportLogsServiceResponse> response);
  }

  private static final class Fixture implements AutoCloseable {
    final Server server;
    final OtlpGrpcLogRecordExporter exporter;

    Fixture(Handler handler, int maxBytes) throws Exception {
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .maxInboundMessageSize(maxBytes)
              .addService(
                  new LogsServiceGrpc.LogsServiceImplBase() {
                    @Override
                    public void export(
                        ExportLogsServiceRequest request,
                        StreamObserver<ExportLogsServiceResponse> response) {
                      handler.export(request, response);
                    }
                  })
              .build()
              .start();
      exporter =
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
    }

    CompletableResultCode export(String body) {
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
      try (var provider =
          SdkLoggerProvider.builder()
              .addLogRecordProcessor(SimpleLogRecordProcessor.create(capture))
              .build()) {
        provider.get("interop").logRecordBuilder().setBody(body).emit();
      }
      var result = exporter.export(records);
      result.join(10, TimeUnit.SECONDS);
      assertThat(result.isDone()).isTrue();
      return result;
    }

    @Override
    public void close() throws Exception {
      exporter.shutdown().join(5, TimeUnit.SECONDS);
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
