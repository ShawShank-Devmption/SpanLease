package dev.spanlease.testbed;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.spanlease.common.ContractJson;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Process epochs and the immutable run manifest for one scenario, fixed before any instance admits
 * work. The manifest holds configuration only — instances, pools and parameters — never ownership
 * or expected outcomes. Every instance here shares this host's clock, so clock qualification covers
 * the run window with the configured ε.
 */
public record RunPlan(
    Scenario scenario,
    String driverInstanceId,
    Map<String, String> instanceByService,
    RunManifest manifest) {
  public static final String POOL = "app";

  /** Design §4.1 defaults. */
  static final long TAU_MS = 2_000;

  static final long DELTA_MS = 1_000;
  static final long EPSILON_MS = 50;
  static final long EVALUATION_INTERVAL_MS = 250;
  static final long CHECKPOINT_INTERVAL_MS = 500;
  static final long LEASE_AGE_THRESHOLD_MS = 500;
  static final long CHECKPOINT_FRESHNESS_MS =
      CHECKPOINT_INTERVAL_MS + DELTA_MS + EVALUATION_INTERVAL_MS + 2 * EPSILON_MS;

  private static final long CLOCK_QUALIFICATION_MS = 3_600_000;

  public RunPlan {
    instanceByService = Map.copyOf(instanceByService);
  }

  public static RunPlan create(Scenario scenario, long nowMs) {
    String epoch = UUID.randomUUID().toString();
    String runId = scenario.name() + "-" + epoch.substring(0, 8);
    String driver = "driver-0@" + epoch;
    Map<String, String> instances = new TreeMap<>();
    scenario
        .capacities()
        .keySet()
        .forEach(service -> instances.put(service, service + "-0@" + epoch));

    List<RunManifest.Instance> declared = new ArrayList<>();
    long from = Math.max(0, nowMs - EPSILON_MS);
    declared.add(
        new RunManifest.Instance(
            driver, "driver", from, from + CLOCK_QUALIFICATION_MS, EPSILON_MS));
    instances.forEach(
        (service, instance) ->
            declared.add(
                new RunManifest.Instance(
                    instance, service, from, from + CLOCK_QUALIFICATION_MS, EPSILON_MS)));
    List<RunManifest.Pool> pools = new ArrayList<>();
    instances.forEach(
        (service, instance) -> {
          List<String> units = new ArrayList<>();
          for (int slot = 0; slot < scenario.capacities().get(service); slot++) {
            units.add(instance + ":" + POOL + ":slot-" + slot);
          }
          pools.add(new RunManifest.Pool(instance, POOL, units, true));
        });
    RunManifest.Parameters parameters =
        new RunManifest.Parameters(
            TAU_MS,
            DELTA_MS,
            EPSILON_MS,
            EVALUATION_INTERVAL_MS,
            CHECKPOINT_INTERVAL_MS,
            CHECKPOINT_FRESHNESS_MS,
            LEASE_AGE_THRESHOLD_MS);
    RunManifest manifest =
        new RunManifest(
            ObservationEvent.SCHEMA_VERSION,
            runId,
            hash(runId, declared, pools, parameters),
            declared,
            pools,
            parameters);
    return new RunPlan(scenario, driver, new LinkedHashMap<>(instances), manifest);
  }

  private static String hash(
      String runId,
      List<RunManifest.Instance> instances,
      List<RunManifest.Pool> pools,
      RunManifest.Parameters parameters) {
    try {
      byte[] canonical =
          ContractJson.mapper()
              .writeValueAsString(List.of(runId, instances, pools, parameters))
              .getBytes(StandardCharsets.UTF_8);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
      throw new IllegalStateException("cannot hash run manifest", failure);
    }
  }
}
