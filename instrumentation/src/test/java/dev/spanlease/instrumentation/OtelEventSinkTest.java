package dev.spanlease.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OtelEventSinkTest {
  @Test
  @Timeout(10)
  void asynchronouslyEncodesV2EventAsCustomLogRecord() throws Exception {
    BlockingQueue<LogRecordData> exported = new LinkedBlockingQueue<>();
    LogRecordExporter exporter = exporter(exported);
    try (SdkLoggerProvider provider =
            SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        OtelEventSink sink = new OtelEventSink(8, provider.get("spanlease"), "otel-event-test")) {
      ObservationEvent event =
          new ObservationEvent(
              2,
              "A",
              1,
              "",
              1_000,
              5,
              "",
              "",
              new EventPayload.ResourceInit("main", 1, java.util.List.of("A0")));

      assertThat(sink.tryPublish(event)).isTrue();
      LogRecordData record = exported.poll(5, TimeUnit.SECONDS);

      assertThat(record).isNotNull();
      assertThat(record.getAttributes().get(AttributeKey.longKey("sl.schema_version")))
          .isEqualTo(2L);
      assertThat(record.getAttributes().get(AttributeKey.stringKey("sl.event_type")))
          .isEqualTo("resource.init");
      assertThat(record.getBodyValue().asString()).contains("\"instance_id\":\"A\"");
      assertThat(sink.encodingFailures()).isZero();
    }
  }

  private static LogRecordExporter exporter(BlockingQueue<LogRecordData> exported) {
    return new LogRecordExporter() {
      @Override
      public CompletableResultCode export(Collection<LogRecordData> records) {
        exported.addAll(records);
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
  }
}
