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
    Application application,
    Map<String, Integer> capacities,
    List<Hop> rootHops,
    Duration rootDeadline,
    Duration observationWindow,
    MonitorBehavior monitorBehavior) {
  public static final Duration DEFAULT_ROOT_DEADLINE = Duration.ofSeconds(30);

  public Scenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(application, "application");
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
    Objects.requireNonNull(monitorBehavior, "monitorBehavior");
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

  public static Scenario boutiqueLive() {
    return boutique(
        "boutique-live",
        "live store traffic through SpanLease-instrumented Ad Service",
        false,
        false);
  }

  public static Scenario boutiqueDeadlock() {
    return boutique(
        "boutique-deadlock",
        "live store traffic; injected cycle ads-a→ads-b→ads-c→ads-a, one slot each",
        false,
        true);
  }

  public static Scenario boutiqueSpareCapacity() {
    return boutique(
        "boutique-spare-capacity",
        "live store traffic; injected cycle with a spare ads-a slot",
        true,
        true);
  }

  public static Scenario named(String name) {
    return switch (name) {
      case "closed-ring" -> closedRing();
      case "spare-capacity" -> spareCapacity();
      case "slow-progress" -> slowProgress(3);
      case "boutique-live" -> boutiqueLive();
      case "boutique-deadlock" -> boutiqueDeadlock();
      case "boutique-spare-capacity" -> boutiqueSpareCapacity();
      default -> throw new IllegalArgumentException("unknown scenario: " + name);
    };
  }

  public boolean manualStop() {
    return monitorBehavior.manualStop();
  }

  public boolean injectable() {
    return monitorBehavior.injectable();
  }

  public boolean rootWithoutDeadline() {
    return monitorBehavior.rootWithoutDeadline();
  }

  private static Scenario boutique(
      String name, String description, boolean spareCapacity, boolean cycle) {
    Map<String, Integer> capacities =
        Map.of("ads-a", spareCapacity ? 2 : 1, "ads-b", 1, "ads-c", 1);
    List<Hop> hops = new ArrayList<>();
    hops.add(hop("ads-a", 0));
    if (cycle) {
      hops.add(hop("ads-b", 0));
      hops.add(hop("ads-c", 0));
      hops.add(hop("ads-a", spareCapacity ? 4_000 : 0));
    }
    return new Scenario(
        name,
        description,
        Application.BOUTIQUE,
        capacities,
        hops,
        DEFAULT_ROOT_DEADLINE,
        Duration.ofSeconds(8),
        new MonitorBehavior(true, cycle, cycle && !spareCapacity));
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
        Application.CHAIN,
        capacities,
        hops,
        DEFAULT_ROOT_DEADLINE,
        Duration.ofSeconds(8),
        new MonitorBehavior(returnToFirst && !spare, false, returnToFirst && !spare));
  }

  private static Hop hop(String service, int workMs) {
    return Hop.newBuilder().setTargetService(service).setWorkMs(workMs).build();
  }

  public enum Application {
    CHAIN,
    BOUTIQUE
  }

  /** Explicit monitor-only lifecycle controls; finite CLI deadlines remain independent. */
  public record MonitorBehavior(
      boolean manualStop, boolean injectable, boolean rootWithoutDeadline) {}
}
