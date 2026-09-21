package dev.spanlease.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.junit.jupiter.api.Test;

/** Validates pinned OTLP/protobuf libraries, not the pending detector decoder. */
class OtlpCompatibilityTest {
  @Test
  void otlpLogRequestRoundTrips() throws Exception {
    ExportLogsServiceRequest request =
        ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(
                                LogRecord.newBuilder()
                                    .setBody(AnyValue.newBuilder().setStringValue("bootstrap")))))
            .build();
    assertThat(ExportLogsServiceRequest.parseFrom(request.toByteArray())).isEqualTo(request);
  }
}
