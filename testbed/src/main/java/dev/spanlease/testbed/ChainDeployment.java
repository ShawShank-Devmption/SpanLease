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
  private final EvaluationMappings mappings = new EvaluationMappings();
  private final TruthRecorder truth = new TruthRecorder(Clock.systemUTC(), 100_000);

  private final ObservationFaults faults;

  private ChainDeployment(List<ObservationFaults.Rule> rules, long seed) {
    faults = new ObservationFaults(rules, seed);
  }

  /**
   * Starts every instance exporting to {@code otlpEndpoint}.
   *
   * @param invocationIds invocation identity source per logical service ("driver" for roots)
   */
  public static ChainDeployment start(
      RunPlan plan, String otlpEndpoint, Function<String, Supplier<String>> invocationIds)
      throws IOException, InterruptedException {
    return start(plan, otlpEndpoint, invocationIds, true, ChainNode.WAITING_CAPACITY);
  }

  public static ChainDeployment start(
      RunPlan plan,
      String otlpEndpoint,
      Function<String, Supplier<String>> invocationIds,
      boolean instrumented,
      int queueCapacity)
      throws IOException, InterruptedException {
    return start(plan, otlpEndpoint, invocationIds, instrumented, queueCapacity, List.of(), 0);
  }

  public static ChainDeployment start(
      RunPlan plan,
      String otlpEndpoint,
      Function<String, Supplier<String>> invocationIds,
      boolean instrumented,
      int queueCapacity,
      List<ObservationFaults.Rule> rules,
      long seed)
      throws IOException, InterruptedException {
    ChainDeployment deployment = new ChainDeployment(rules, seed);
    try {
      Map<String, Integer> ports = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : plan.instanceByService().entrySet()) {
        String service = entry.getKey();
        OtlpTelemetry pipeline =
            instrumented ? deployment.open(otlpEndpoint, entry.getValue()) : null;
        ChainNode node =
            ChainNode.start(
                service,
                entry.getValue(),
                plan.scenario().capacities().get(service),
                plan.manifest().parameters(),
                pipeline == null ? event -> true : deployment.faults.wrap(pipeline.sink()),
                invocationIds.apply(service),
                deployment.journal,
                deployment.truth,
                instrumented,
                queueCapacity,
                deployment.mappings);
        deployment.nodes.put(service, node);
        ports.put(service, node.port());
      }
      deployment.nodes.values().forEach(node -> node.connect(ports));
      OtlpTelemetry driverPipeline =
          instrumented ? deployment.open(otlpEndpoint, plan.driverInstanceId()) : null;
      deployment.driver =
          new ChainDriver(
              plan.driverInstanceId(),
              plan.manifest().parameters(),
              driverPipeline == null
                  ? event -> true
                  : deployment.faults.wrap(driverPipeline.sink()),
              invocationIds.apply("driver"),
              instrumented);
      deployment.driver.connect(ports);
      return deployment;
    } catch (IOException | RuntimeException failure) {
      deployment.close();
      throw failure;
    }
  }

  public EvaluationMappings mappings() {
    return mappings;
  }

  public TruthRecorder truth() {
    return truth;
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

  public Map<String, Long> telemetryMetrics() {
    return Map.of(
        "encoded_event_json_bytes",
        telemetry.stream().mapToLong(t -> t.sink().encodedBytes()).sum(),
        "sink_rejected_offers",
        telemetry.stream().mapToLong(t -> t.sink().rejectedOffers()).sum(),
        "encoding_failures",
        telemetry.stream().mapToLong(t -> t.sink().encodingFailures()).sum(),
        "fault_proxy_losses",
        faults.lost(),
        "sdk_flush_failures",
        telemetry.stream().mapToLong(OtlpTelemetry::flushFailures).sum());
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
    faults.close();
    for (OtlpTelemetry pipeline : telemetry) {
      pipeline.close();
    }
  }
}
