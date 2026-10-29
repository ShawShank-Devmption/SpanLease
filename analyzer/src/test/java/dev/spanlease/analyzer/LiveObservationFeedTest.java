package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.ObservationEvent;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LiveObservationFeedTest {
  @Test
  void monitorReceivesOnlyActualOtlpEnvelopesAfterCoreIngestion() throws Exception {
    ArrayBlockingQueue<ObservationEnvelope> feed = new ArrayBlockingQueue<>(4);
    try (LiveAnalyzer analyzer =
        LiveAnalyzer.start(
            ProductionDemo.manifest(false),
            0,
            false,
            ignored -> {},
            feed::add,
            Clock.systemUTC(),
            System::nanoTime)) {
      ManagedChannel channel =
          NettyChannelBuilder.forAddress("127.0.0.1", analyzer.port()).usePlaintext().build();
      try {
        ObservationEvent event =
            new ObservationEvent(
                2,
                "A",
                1,
                "",
                1_000,
                1_000_000_000,
                "",
                "",
                new EventPayload.InstanceCheckpoint(0, 0));
        LogRecord.Builder log =
            LogRecord.newBuilder()
                .setBody(
                    AnyValue.newBuilder()
                        .setStringValue(ContractJson.mapper().writeValueAsString(event)));
        Map<String, Object> attributes =
            Map.of(
                "sl.schema_version",
                2L,
                "sl.event_type",
                "instance.checkpoint",
                "sl.instance_id",
                "A",
                "sl.local_seq",
                1L,
                "sl.causal_parent",
                "",
                "sl.wall_time_ms",
                1_000L,
                "sl.monotonic_time_ns",
                1_000_000_000L);
        attributes.forEach(
            (key, value) ->
                log.addAttributes(
                    KeyValue.newBuilder()
                        .setKey(key)
                        .setValue(
                            value instanceof Long number
                                ? AnyValue.newBuilder().setIntValue(number).build()
                                : AnyValue.newBuilder().setStringValue((String) value).build())));
        LogsServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .export(
                ExportLogsServiceRequest.newBuilder()
                    .addResourceLogs(
                        ResourceLogs.newBuilder()
                            .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(log)))
                    .build());
        ObservationEnvelope received = feed.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.event()).isEqualTo(event);
        assertThat(received.receiptWallMs()).isGreaterThan(1_000);
        assertThat(feed).isEmpty();
      } finally {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }
}
