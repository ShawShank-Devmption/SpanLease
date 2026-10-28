package dev.spanlease.testbed;

import dev.spanlease.testbed.proto.Hop;
import java.time.Duration;
import java.util.ArrayList;
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
    return closedRing(3);
  }

  public static Scenario closedRing(int services) {
    return chain("closed-ring", services, true, false);
  }

  /**
   * Same cyclic route, but svcA has a spare slot: the final A hop acquires it and performs 4 s of
   * finite work while its three ancestors are blocked, then the whole chain completes.
   */
  public static Scenario spareCapacity() {
    return spareCapacity(3);
  }

  public static Scenario spareCapacity(int services) {
    return chain("spare-capacity", services, true, true);
  }

  /** Finite work at a nonblocked leaf provides observed progress while ancestors retain slots. */
  public static Scenario slowProgress(int services) {
    return chain("slow-progress", services, false, false);
  }

  private static Scenario chain(String name, int services, boolean returnToFirst, boolean spare) {
    if (services != 2 && services != 3 && services != 5) {
      throw new IllegalArgumentException("demo service count must be 2, 3 or 5");
    }
    Map<String, Integer> capacities = new TreeMap<>();
    List<Hop> hops = new ArrayList<>();
    for (int index = 0; index < services; index++) {
      String service = "svc" + (char) ('A' + index);
      capacities.put(service, index == 0 && spare ? 2 : 1);
      hops.add(hop(service, !returnToFirst && index == services - 1 ? 4_000 : 0));
    }
    if (returnToFirst) hops.add(hop("svcA", spare ? 4_000 : 0));
    String description =
        returnToFirst
            ? spare
                ? "cyclic route with a spare svcA slot: the final leaf can run"
                : "closed ring with one slot per service: the final leaf queues at svcA"
            : "finite slow leaf with no return call: blocked ancestors have a progressing counterpart";
    return new Scenario(
        services == 3 ? name : name + "-" + services,
        description,
        capacities,
        hops,
        DEFAULT_ROOT_DEADLINE,
        Duration.ofSeconds(8));
  }

  private static Hop hop(String service, int workMs) {
    return Hop.newBuilder().setTargetService(service).setWorkMs(workMs).build();
  }
}
