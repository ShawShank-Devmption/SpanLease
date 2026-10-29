package dev.spanlease.testbed;

import dev.spanlease.instrumentation.OtlpTelemetry;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The instrumented deployment for one {@link RunPlan}: one {@link ChainNode} per service and the
 * root driver, each with its own process epoch and OTLP pipeline to the analyzer endpoint.
 */
public final class ChainDeployment implements AutoCloseable {
  private static final int SINK_CAPACITY = 8_192;

  private final List<OtlpTelemetry> telemetry = new ArrayList<>();
  private final Map<String, ChainNode> nodes = new LinkedHashMap<>();
  private final HandlerJournal journal = new HandlerJournal(Clock.systemUTC());
  private ChainDriver driver;

  private ChainDeployment() {}

  /**
   * Starts every instance exporting to {@code otlpEndpoint}.
   *
   * @param invocationIds invocation identity source per logical service ("driver" for roots)
   */
  public static ChainDeployment start(
      RunPlan plan, String otlpEndpoint, Function<String, Supplier<String>> invocationIds)
      throws IOException, InterruptedException {
    return start(plan, otlpEndpoint, invocationIds, null);
  }

  static ChainDeployment start(
      RunPlan plan,
      String otlpEndpoint,
      Function<String, Supplier<String>> invocationIds,
      RuntimeDiagnostics diagnostics)
      throws IOException, InterruptedException {
    ChainDeployment deployment = new ChainDeployment();
    try {
      Map<String, Integer> ports = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : plan.instanceByService().entrySet()) {
        String service = entry.getKey();
        OtlpTelemetry pipeline = deployment.open(otlpEndpoint, entry.getValue());
        ChainNode node =
            ChainNode.start(
                service,
                entry.getValue(),
                plan.scenario().capacities().get(service),
                plan.manifest().parameters(),
                pipeline.sink(),
                invocationIds.apply(service),
                deployment.journal,
                diagnostics);
        deployment.nodes.put(service, node);
        ports.put(service, node.port());
      }
      deployment.nodes.values().forEach(node -> node.connect(ports));
      OtlpTelemetry driverPipeline = deployment.open(otlpEndpoint, plan.driverInstanceId());
      deployment.driver =
          new ChainDriver(
              plan.driverInstanceId(),
              plan.manifest().parameters(),
              driverPipeline.sink(),
              invocationIds.apply("driver"));
      deployment.driver.connect(ports);
      return deployment;
    } catch (IOException | RuntimeException failure) {
      deployment.close();
      throw failure;
    }
  }

  public ChainDriver driver() {
    return driver;
  }

  public Map<String, ChainNode> nodes() {
    return Map.copyOf(nodes);
  }

  public HandlerJournal journal() {
    return journal;
  }

  private OtlpTelemetry open(String endpoint, String instanceId) {
    OtlpTelemetry pipeline = OtlpTelemetry.start(endpoint, instanceId, SINK_CAPACITY);
    telemetry.add(pipeline);
    return pipeline;
  }

  /** Stops instances, emitting a final checkpoint each, then flushes every OTLP pipeline. */
  @Override
  public void close() throws InterruptedException {
    if (driver != null) {
      driver.close();
    }
    for (ChainNode node : nodes.values()) {
      node.close();
    }
    for (OtlpTelemetry pipeline : telemetry) {
      pipeline.close();
    }
  }
}
