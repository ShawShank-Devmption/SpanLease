package dev.spanlease.testbed;

import dev.spanlease.testbed.proto.Hop;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multi-root retry-free workloads; delayed arrivals are a recorded schedule, never worker barriers.
 */
public record Workload(
    String name,
    Map<String, Integer> capacities,
    List<Root> roots,
    Duration window,
    int queueCapacity,
    Duration rootDeadline,
    boolean coordinatedStart) {
  public Workload(
      String name,
      Map<String, Integer> capacities,
      List<Root> roots,
      Duration window,
      int queueCapacity) {
    this(name, capacities, roots, window, queueCapacity, Duration.ofSeconds(30), false);
  }

  public Workload(
      String name,
      Map<String, Integer> capacities,
      List<Root> roots,
      Duration window,
      int queueCapacity,
      Duration rootDeadline) {
    this(name, capacities, roots, window, queueCapacity, rootDeadline, false);
  }

  public Workload {
    capacities = Map.copyOf(capacities);
    roots = List.copyOf(roots);
    if (roots.isEmpty() || window.isNegative() || window.isZero() || queueCapacity <= 0)
      throw new IllegalArgumentException("nonempty workload with positive bounds required");
    if (roots.stream().map(Root::id).distinct().count() != roots.size())
      throw new IllegalArgumentException("root IDs must be unique for truth attempt identities");
  }

  public Scenario scenario() {
    return new Scenario(name, name, capacities, roots.getFirst().hops(), rootDeadline, window);
  }

  public static List<Workload> standard() {
    return List.of(
        coordinatedRing(3, 2),
        new Workload(
            "closed-ring",
            Map.of("A", 1, "B", 1),
            List.of(root("r1", 0, "A", "B", "A")),
            Duration.ofSeconds(6),
            32),
        new Workload(
            "saturated-ring-k2",
            Map.of("A", 2, "B", 2),
            List.of(root("r1", 0, "A", "B", "A", "B", "A")),
            Duration.ofSeconds(6),
            32),
        new Workload(
            "upstream-dependent",
            Map.of("A", 1, "B", 1, "U", 1),
            List.of(root("r1", 0, "A", "B", "A"), root("r2", 3_000, "U", "A")),
            Duration.ofSeconds(7),
            32),
        new Workload(
            "disjoint-cycles",
            Map.of("A", 1, "B", 1, "C", 1, "D", 1),
            List.of(root("r1", 0, "A", "B", "A"), root("r2", 3_000, "C", "D", "C")),
            Duration.ofSeconds(7),
            32),
        new Workload(
            "slow-chain",
            Map.of("A", 1, "B", 1, "C", 1),
            List.of(new Root("r1", 0, List.of(hop("A", 0), hop("B", 0), hop("C", 3_000)))),
            Duration.ofSeconds(6),
            32),
        new Workload(
            "saturation-without-cycle",
            Map.of("A", 1),
            List.of(new Root("r1", 0, List.of(hop("A", 3_000))), root("r2", 0, "A")),
            Duration.ofSeconds(6),
            32),
        new Workload(
            "queue-rejection",
            Map.of("A", 1),
            List.of(
                new Root("r1", 0, List.of(hop("A", 1_500))),
                new Root("r2", 0, List.of(hop("A", 1_500))),
                new Root("r3", 0, List.of(hop("A", 1_500)))),
            Duration.ofSeconds(6),
            1),
        new Workload(
            "spare-capacity",
            Map.of("A", 2, "B", 1),
            List.of(new Root("r1", 0, List.of(hop("A", 0), hop("B", 0), hop("A", 3_000)))),
            Duration.ofSeconds(6),
            32),
        new Workload(
            "hotel-search-paths",
            Map.of("frontend", 2, "search", 2, "geo", 1, "rate", 1),
            List.of(
                new Root(
                    "geo-path",
                    0,
                    List.of(hop("frontend", 0), hop("search", 0), hop("geo", 1_000))),
                new Root(
                    "rate-path",
                    0,
                    List.of(hop("frontend", 0), hop("search", 0), hop("rate", 1_000)))),
            Duration.ofSeconds(5),
            32));
  }

  public static Workload coordinatedRing(int services, int capacity) {
    Map<String, Integer> pools = new java.util.TreeMap<>();
    List<Root> roots = new ArrayList<>();
    for (int service = 0; service < services; service++) {
      String current = "S" + service;
      String next = "S" + ((service + 1) % services);
      pools.put(current, capacity);
      for (int slot = 0; slot < capacity; slot++)
        roots.add(root(current + "-root-" + slot, 0, current, next));
    }
    return new Workload(
        "coordinated-ring-n" + services + "-k" + capacity,
        pools,
        roots,
        Duration.ofSeconds(6),
        64,
        Duration.ofSeconds(30),
        true);
  }

  public static Workload ring(int services, int capacity, Duration deadline) {
    if (services < 2 || capacity < 1) throw new IllegalArgumentException("ring bounds");
    Map<String, Integer> pools = new java.util.TreeMap<>();
    List<Hop> hops = new ArrayList<>();
    for (int p = 0; p < services; p++) pools.put("S" + p, capacity);
    for (int repeat = 0; repeat < capacity; repeat++)
      for (int p = 0; p < services; p++) hops.add(hop("S" + p, 0));
    hops.add(hop("S0", 0));
    return new Workload(
        "ring-n" + services + "-k" + capacity + "-d" + deadline.toSeconds(),
        pools,
        List.of(new Root("root", 0, hops)),
        Duration.ofSeconds(6),
        64,
        deadline);
  }

  public static Workload load(int roots, int queueCapacity) {
    List<Root> requests = new ArrayList<>();
    for (int i = 0; i < roots; i++)
      requests.add(new Root("load-" + i, i * 2L, List.of(hop("A", 5), hop("B", 5))));
    return new Workload(
        "load-" + roots + "-q" + queueCapacity,
        Map.of("A", 4, "B", 4),
        requests,
        Duration.ofSeconds(10),
        queueCapacity);
  }

  public static Workload named(String name) {
    var ring = java.util.regex.Pattern.compile("ring-n(\\d+)-k(\\d+)-d(\\d+)").matcher(name);
    if (ring.matches())
      return ring(
          Integer.parseInt(ring.group(1)),
          Integer.parseInt(ring.group(2)),
          Duration.ofSeconds(Long.parseLong(ring.group(3))));
    var load = java.util.regex.Pattern.compile("load-(\\d+)-q(\\d+)").matcher(name);
    if (load.matches())
      return load(Integer.parseInt(load.group(1)), Integer.parseInt(load.group(2)));
    return standard().stream()
        .filter(w -> w.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown workload " + name));
  }

  public static Root root(String id, long atMs, String... services) {
    List<Hop> hops = new ArrayList<>();
    for (String service : services) hops.add(hop(service, 0));
    return new Root(id, atMs, hops);
  }

  public static Hop hop(String service, int workMs) {
    return Hop.newBuilder().setTargetService(service).setWorkMs(workMs).build();
  }

  public record Root(String id, long atMs, List<Hop> hops) {
    public Root {
      hops = List.copyOf(hops);
      if (id.isBlank() || atMs < 0 || hops.isEmpty())
        throw new IllegalArgumentException("invalid root");
    }
  }
}
