package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.RunManifest;
import dev.spanlease.instrumentation.OtlpTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Isolated service process used for real abrupt-crash experiments. No shutdown hook invents exit.
 */
public final class NodeProcess {
  private NodeProcess() {}

  public record Configuration(
      String service,
      String instance,
      int capacity,
      RunManifest.Parameters parameters,
      String endpoint,
      String truthPath) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("usage: node configuration.json");
    var json = ContractJson.mapper();
    var config = json.readValue(Path.of(args[0]).toFile(), Configuration.class);
    var truth = new TruthRecorder(Clock.systemUTC(), 100_000);
    var mappings = new EvaluationMappings();
    var ids = new java.util.concurrent.atomic.AtomicInteger();
    var writer = Executors.newSingleThreadScheduledExecutor();
    try (var telemetry = OtlpTelemetry.start(config.endpoint(), config.instance(), 8192);
        var node =
            ChainNode.start(
                config.service(),
                config.instance(),
                config.capacity(),
                config.parameters(),
                telemetry.sink(),
                () -> config.service() + "-call-" + ids.incrementAndGet(),
                new HandlerJournal(Clock.systemUTC()),
                truth,
                true,
                32,
                mappings)) {
      writer.scheduleWithFixedDelay(
          () -> {
            try {
              Path target = Path.of(config.truthPath());
              Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
              json.writeValue(
                  temporary.toFile(),
                  Map.of(
                      "entries",
                      truth.entries(),
                      "scorable",
                      truth.scorable(),
                      "mappings",
                      mappings.entries(),
                      "captured_at_ms",
                      System.currentTimeMillis()));
              Files.move(
                  temporary,
                  target,
                  StandardCopyOption.REPLACE_EXISTING,
                  StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception failure) {
              failure.printStackTrace(System.err);
            }
          },
          0,
          100,
          TimeUnit.MILLISECONDS);
      System.out.println("READY " + node.port());
      System.out.flush();
      try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
        for (String line = input.readLine(); line != null; line = input.readLine()) {
          if (line.equals("STOP")) break;
          if (!line.startsWith("CONNECT ")) throw new IllegalArgumentException("unknown command");
          Map<String, Integer> ports =
              json.readValue(
                  line.substring(8),
                  new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
          node.connect(ports);
          System.out.println("CONNECTED");
          System.out.flush();
        }
      }
    } finally {
      writer.shutdown();
      writer.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
