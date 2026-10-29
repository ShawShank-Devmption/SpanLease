package dev.spanlease.testbed;

import java.nio.file.Path;
import java.util.List;

/** Finite demo selection; validation completes before opening a server or starting a workload. */
record DemoOptions(Path runsDirectory, List<Scenario> scenarios) {
  static DemoOptions parse(String[] args) {
    if (args.length == 1 && !args[0].startsWith("--")) {
      return new DemoOptions(
          Path.of(args[0]), List.of(Scenario.closedRing(), Scenario.spareCapacity()));
    }
    Path directory = Path.of("live-runs");
    String scenario = "default";
    int services = 3;
    for (int index = 0; index < args.length; index += 2) {
      if (index + 1 >= args.length)
        throw new IllegalArgumentException("missing value for " + args[index]);
      switch (args[index]) {
        case "--runs-directory" -> directory = Path.of(args[index + 1]);
        case "--scenario" -> scenario = args[index + 1];
        case "--services" -> services = Integer.parseInt(args[index + 1]);
        default -> throw new IllegalArgumentException("unknown demo option: " + args[index]);
      }
    }
    List<Scenario> selected =
        switch (scenario) {
          case "default" ->
              List.of(Scenario.closedRing(services), Scenario.spareCapacity(services));
          case "all" ->
              List.of(
                  Scenario.closedRing(services),
                  Scenario.spareCapacity(services),
                  Scenario.slowProgress(services));
          case "closed-ring" -> List.of(Scenario.closedRing(services));
          case "spare-capacity" -> List.of(Scenario.spareCapacity(services));
          case "slow-progress" -> List.of(Scenario.slowProgress(services));
          default ->
              throw new IllegalArgumentException(
                  "scenario must be closed-ring, spare-capacity, slow-progress, or all");
        };
    return new DemoOptions(directory, selected);
  }
}
