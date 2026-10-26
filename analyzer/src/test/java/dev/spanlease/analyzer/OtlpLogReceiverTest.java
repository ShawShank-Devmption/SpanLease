package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OtlpLogReceiverTest {
  private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);

  @Test
  void admitsValidBatchWholeWithReceiptTime() throws Exception {
    AnalyzerIngress ingress = new AnalyzerIngress(16);
    Result result = export(receiver(ingress), request("A", 1, 3));

    assertThat(result.status()).isNull();
    List<ObservationEnvelope> batch = ingress.poll(0);
    assertThat(batch)
        .extracting(envelope -> envelope.event().localSeq())
        .containsExactly(1L, 2L, 3L);
    assertThat(batch).allMatch(envelope -> envelope.receiptWallMs() == 5_000);
  }

  @Test
  void malformedRecordOrForeignInstanceRejectsWholeBatchWithoutRetry() throws Exception {
    AnalyzerIngress ingress = new AnalyzerIngress(16);
    OtlpLogReceiver receiver = receiver(ingress);
    ExportLogsServiceRequest malformed =
        request("A", 1, 2).toBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(
                                LogRecord.newBuilder()
                                    .setBody(AnyValue.newBuilder().setStringValue("{not json")))))
            .build();

    assertThat(export(receiver, malformed).status().getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(export(receiver, request("Z", 1, 1)).status().getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(ingress.poll(0)).isNull();
    assertThat(receiver.rejectedRequests()).isEqualTo(2);
  }

  @Test
  void overloadIsRetryableAndNeverPartiallyAdmitted() throws Exception {
    AnalyzerIngress ingress = new AnalyzerIngress(4);
    OtlpLogReceiver receiver = receiver(ingress);
    assertThat(export(receiver, request("A", 1, 3)).status()).isNull();

    assertThat(export(receiver, request("A", 4, 5)).status().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(ingress.drain()).hasSize(1);
    assertThat(export(receiver, request("A", 4, 5)).status()).isNull();
    assertThat(ingress.poll(0)).hasSize(2);
  }

  /** R11: concurrent receivers hand whole batches to a single consumer without loss or mixing. */
  @Test
  @Timeout(30)
  void concurrentExportsAreAdmittedAsWholeBatches() throws Exception {
    AnalyzerIngress ingress = new AnalyzerIngress(LiveAnalyzer.INGRESS_CAPACITY_RECORDS);
    OtlpLogReceiver receiver = receiver(ingress);
    int producers = 4;
    int batchesPerProducer = 50;
    int recordsPerBatch = 5;
    ExecutorService pool = Executors.newFixedThreadPool(producers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int producer = 0; producer < producers; producer++) {
      String instance = List.of("A", "B", "C", "D").get(producer);
      futures.add(
          pool.submit(
              () -> {
                start.await();
                for (int batch = 0; batch < batchesPerProducer; batch++) {
                  long first = (long) batch * recordsPerBatch + 1;
                  assertThat(
                          export(receiver, request(instance, first, first + recordsPerBatch - 1))
                              .status())
                      .isNull();
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(20, TimeUnit.SECONDS);
    }
    pool.shutdown();

    List<List<ObservationEnvelope>> admitted = ingress.drain();
    assertThat(admitted).hasSize(producers * batchesPerProducer);
    for (List<ObservationEnvelope> batch : admitted) {
      assertThat(batch).hasSize(recordsPerBatch);
      assertThat(batch)
          .extracting(envelope -> envelope.event().instanceId())
          .containsOnly(batch.get(0).event().instanceId());
      long first = batch.get(0).event().localSeq();
      assertThat(batch)
          .extracting(envelope -> envelope.event().localSeq())
          .containsExactly(first, first + 1, first + 2, first + 3, first + 4);
    }
    assertThat(ingress.tryAdmit(List.of())).isTrue();
  }

  private static OtlpLogReceiver receiver(AnalyzerIngress ingress) {
    return new OtlpLogReceiver(Set.of("A", "B", "C", "D"), ingress, CLOCK, () -> 7);
  }

  private static Result export(OtlpLogReceiver receiver, ExportLogsServiceRequest request) {
    List<Status> errors = new ArrayList<>();
    List<ExportLogsServiceResponse> responses = new ArrayList<>();
    receiver.export(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(ExportLogsServiceResponse value) {
            responses.add(value);
          }

          @Override
          public void onError(Throwable failure) {
            errors.add(Status.fromThrowable(failure));
          }

          @Override
          public void onCompleted() {}
        });
    return new Result(errors.isEmpty() ? null : errors.get(0));
  }

  private static ExportLogsServiceRequest request(String instance, long first, long last)
      throws Exception {
    ScopeLogs.Builder scope = ScopeLogs.newBuilder();
    for (long sequence = first; sequence <= last; sequence++) {
      ObservationEvent event =
          new ObservationEvent(
              2,
              instance,
              sequence,
              "",
              1_000 + sequence,
              sequence,
              "",
              "",
              new EventPayload.InstanceCheckpoint(sequence - 1, 0));
      scope.addLogRecords(
          LogRecord.newBuilder()
              .setBody(
                  AnyValue.newBuilder()
                      .setStringValue(ContractJson.mapper().writeValueAsString(event)))
              .addAttributes(longAttribute("sl.schema_version", 2))
              .addAttributes(stringAttribute("sl.event_type", "instance.checkpoint"))
              .addAttributes(stringAttribute("sl.instance_id", instance))
              .addAttributes(longAttribute("sl.local_seq", sequence))
              .addAttributes(stringAttribute("sl.causal_parent", ""))
              .addAttributes(longAttribute("sl.wall_time_ms", event.wallTimeMs()))
              .addAttributes(longAttribute("sl.monotonic_time_ns", event.monotonicTimeNs())));
    }
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(scope))
        .build();
  }

  private static KeyValue longAttribute(String key, long value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setIntValue(value))
        .build();
  }

  private static KeyValue stringAttribute(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
        .build();
  }

  private record Result(Status status) {}
}
