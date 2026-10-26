package dev.spanlease.testbed;

import dev.spanlease.testbed.proto.Hop;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One finite Chain workload (A-SPEC §6): per-service slot capacities, one root hop list, the root
 * deadline and an independent observation window. The window, not any detector verdict, decides
 * when measurement ends and roots are cancelled.
 */
public record Scenario(
    String name,
    String description,
    Map<String, Integer> capacities,
    List<Hop> rootHops,
    Duration rootDeadline,
    Duration observationWindow) {
  public static final Duration DEFAULT_ROOT_DEADLINE = Duration.ofSeconds(30);

  public Scenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Map<String, Integer> services = Collections.unmodifiableMap(new TreeMap<>(capacities));
    if (services.values().stream().anyMatch(capacity -> capacity == null || capacity <= 0)) {
      throw new IllegalArgumentException("every service needs a positive slot capacity");
    }
    rootHops = List.copyOf(rootHops);
    if (rootHops.isEmpty()) {
      throw new IllegalArgumentException("a scenario needs at least one hop");
    }
    if (rootHops.stream().anyMatch(hop -> !services.containsKey(hop.getTargetService()))) {
      throw new IllegalArgumentException("every hop must target a deployed service");
    }
    capacities = services;
    Objects.requireNonNull(rootDeadline, "rootDeadline");
    Objects.requireNonNull(observationWindow, "observationWindow");
  }

  /**
   * Serial k=1 closed wait: one root A→B→C→A with one slot per service. Jobs at A, B and C own
   * their slots and block on the next hop; the final A attempt queues behind A's own caller.
   */
  public static Scenario closedRing() {
    return new Scenario(
        "closed-ring",
        "svcA→svcB→svcC→svcA with one slot each: the last hop queues behind its own ancestor",
        Map.of("svcA", 1, "svcB", 1, "svcC", 1),
        List.of(hop("svcA", 0), hop("svcB", 0), hop("svcC", 0), hop("svcA", 0)),
        DEFAULT_ROOT_DEADLINE,
        Duration.ofSeconds(8));
  }

  /**
   * Same cyclic route, but svcA has a spare slot: the final A hop acquires it and performs 4 s of
   * finite work while its three ancestors are blocked, then the whole chain completes.
   */
  public static Scenario spareCapacity() {
    return new Scenario(
        "spare-capacity",
        "svcA→svcB→svcC→svcA where svcA has 2 slots: the last hop runs on the spare slot",
        Map.of("svcA", 2, "svcB", 1, "svcC", 1),
        List.of(hop("svcA", 0), hop("svcB", 0), hop("svcC", 0), hop("svcA", 4_000)),
        DEFAULT_ROOT_DEADLINE,
        Duration.ofSeconds(8));
  }

  private static Hop hop(String service, int workMs) {
    return Hop.newBuilder().setTargetService(service).setWorkMs(workMs).build();
  }
}
