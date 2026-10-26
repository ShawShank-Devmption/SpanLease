package dev.spanlease.common;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable declaration of one supported run and its fixed instance/pool scope. */
public record RunManifest(
    int schemaVersion,
    String runId,
    String manifestHash,
    List<Instance> instances,
    List<Pool> pools,
    Parameters parameters) {
  public RunManifest {
    if (schemaVersion != ObservationEvent.SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported manifest schema version");
    }
    runId = ContractValidation.id(runId, "runId");
    manifestHash = ContractValidation.id(manifestHash, "manifestHash");
    instances = List.copyOf(instances);
    pools = List.copyOf(pools);
    Objects.requireNonNull(parameters, "parameters");
    requireUnique(instances.stream().map(Instance::instanceId).toList(), "instance IDs");
    requireUnique(pools.stream().map(Pool::qualifiedId).toList(), "pool IDs");
    requireUnique(pools.stream().flatMap(pool -> pool.unitIds().stream()).toList(), "unit IDs");
    Set<String> instanceIds = new HashSet<>(instances.stream().map(Instance::instanceId).toList());
    if (pools.stream().anyMatch(pool -> !instanceIds.contains(pool.instanceId()))) {
      throw new IllegalArgumentException("every pool must reference a declared instance");
    }
  }

  private static void requireUnique(List<String> values, String field) {
    if (new HashSet<>(values).size() != values.size()) {
      throw new IllegalArgumentException(field + " must be unique");
    }
  }

  public record Instance(
      String instanceId,
      String serviceBinding,
      long clockQualifiedFromMs,
      long clockQualifiedThroughMs,
      long epsilonMs) {
    public Instance {
      instanceId = ContractValidation.id(instanceId, "instanceId");
      serviceBinding = ContractValidation.id(serviceBinding, "serviceBinding");
      ContractValidation.nonnegative(clockQualifiedFromMs, "clockQualifiedFromMs");
      if (clockQualifiedThroughMs < clockQualifiedFromMs) {
        throw new IllegalArgumentException("clock qualification end precedes start");
      }
      ContractValidation.positive(epsilonMs, "epsilonMs");
    }
  }

  public record Pool(
      String instanceId, String resourceId, List<String> unitIds, boolean instrumented) {
    public Pool {
      instanceId = ContractValidation.id(instanceId, "instanceId");
      resourceId = ContractValidation.id(resourceId, "resourceId");
      unitIds = ContractValidation.ids(unitIds, "unitIds");
      if (unitIds.isEmpty()) {
        throw new IllegalArgumentException("pool must contain at least one unit");
      }
    }

    public String qualifiedId() {
      return instanceId + ":" + resourceId;
    }
  }

  public record Parameters(
      long persistenceMs,
      long deliveryBoundMs,
      long epsilonMs,
      long evaluationIntervalMs,
      long checkpointIntervalMs,
      long checkpointFreshnessMs,
      long leaseAgeThresholdMs) {
    public Parameters {
      ContractValidation.positive(persistenceMs, "persistenceMs");
      ContractValidation.positive(deliveryBoundMs, "deliveryBoundMs");
      ContractValidation.positive(epsilonMs, "epsilonMs");
      ContractValidation.positive(evaluationIntervalMs, "evaluationIntervalMs");
      ContractValidation.positive(checkpointIntervalMs, "checkpointIntervalMs");
      ContractValidation.positive(checkpointFreshnessMs, "checkpointFreshnessMs");
      ContractValidation.positive(leaseAgeThresholdMs, "leaseAgeThresholdMs");
    }
  }
}
