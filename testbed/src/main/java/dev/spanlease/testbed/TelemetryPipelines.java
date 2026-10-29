package dev.spanlease.testbed;

import dev.spanlease.instrumentation.OtlpTelemetry;
import java.util.ArrayList;
import java.util.List;

/** Owns the per-instance OTLP pipelines for one testbed deployment. */
final class TelemetryPipelines implements AutoCloseable {
  private static final int SINK_CAPACITY = 8_192;

  private final List<OtlpTelemetry> pipelines = new ArrayList<>();

  OtlpTelemetry open(String endpoint, String instanceId) {
    OtlpTelemetry pipeline = OtlpTelemetry.start(endpoint, instanceId, SINK_CAPACITY);
    pipelines.add(pipeline);
    return pipeline;
  }

  @Override
  public void close() throws InterruptedException {
    for (OtlpTelemetry pipeline : pipelines) pipeline.close();
  }
}
