package dev.spanlease.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Library compatibility only; no detector event schema or production exporter. */
class LogSdkCompatibilityTest {
  @Test
  void sdkExportsCustomLogAttributes() {
    List<LogRecordData> captured = new ArrayList<>();
    LogRecordExporter exporter =
        new LogRecordExporter() {
          @Override
          public CompletableResultCode export(Collection<LogRecordData> records) {
            captured.addAll(records);
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
    try (SdkLoggerProvider provider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build()) {
      provider
          .get("bootstrap")
          .logRecordBuilder()
          .setBody("compatibility")
          .setAttribute(AttributeKey.stringKey("bootstrap.mode"), "test")
          .emit();
      assertThat(captured).hasSize(1);
      assertThat(captured.getFirst().getAttributes().get(AttributeKey.stringKey("bootstrap.mode")))
          .isEqualTo("test");
    }
  }
}
