package dev.spanlease.analyzer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exhaustive truth-side oracle for small model cases.
 *
 * <p>This is intentionally test-only and independent of the future production reducer. It checks
 * the closure predicate directly for every nonempty subset of blocked executions and queued
 * requests. A missing owner represents a physically free eligible unit in these complete truth
 * snapshots; analyzer UNKNOWN state is not modeled here.
 */
final class TruthClosureOracle {
  private TruthClosureOracle() {}

  static Set<String> maximalClosedResidual(Snapshot snapshot) {
    Set<String> residual = new TreeSet<>();
    closedSubsets(snapshot).forEach(residual::addAll);
    if (!residual.isEmpty() && !isClosed(snapshot, residual)) {
      throw new IllegalStateException("union of qualifying truth subsets must remain closed");
    }
    return Set.copyOf(residual);
  }

  static Set<Set<String>> closedSubsets(Snapshot snapshot) {
    List<String> candidates = new ArrayList<>();
    candidates.addAll(snapshot.blockedExecutions());
    candidates.addAll(snapshot.waitingRequests());
    candidates.sort(String::compareTo);

    Set<Set<String>> subsets = new HashSet<>();
    enumerate(snapshot, candidates, 0, new HashSet<>(), subsets);
    return Set.copyOf(subsets);
  }

  private static void enumerate(
      Snapshot snapshot,
      List<String> candidates,
      int index,
      Set<String> selected,
      Set<Set<String>> subsets) {
    if (index == candidates.size()) {
      if (!selected.isEmpty() && isClosed(snapshot, selected)) {
        subsets.add(Set.copyOf(selected));
      }
      return;
    }

    enumerate(snapshot, candidates, index + 1, selected, subsets);
    String candidate = candidates.get(index);
    selected.add(candidate);
    enumerate(snapshot, candidates, index + 1, selected, subsets);
    selected.remove(candidate);
  }

  private static boolean isClosed(Snapshot snapshot, Set<String> selected) {
    for (String member : selected) {
      if (snapshot.blockedExecutions().contains(member)) {
        String counterpart = snapshot.blockedCounterparts().get(member);
        if (counterpart == null || !selected.contains(counterpart)) {
          return false;
        }
        continue;
      }

      if (!snapshot.waitingRequests().contains(member)) {
        return false;
      }
      Set<String> eligibleUnits = snapshot.eligibleUnitsByRequest().get(member);
      if (eligibleUnits == null || eligibleUnits.isEmpty()) {
        return false;
      }
      for (String unit : eligibleUnits) {
        String owner = snapshot.ownerByUnit().get(unit);
        if (owner == null || !selected.contains(owner)) {
          return false;
        }
      }
    }
    return true;
  }

  record Snapshot(
      Set<String> blockedExecutions,
      Set<String> waitingRequests,
      Map<String, String> blockedCounterparts,
      Map<String, Set<String>> eligibleUnitsByRequest,
      Map<String, String> ownerByUnit) {
    Snapshot {
      blockedExecutions = Set.copyOf(blockedExecutions);
      waitingRequests = Set.copyOf(waitingRequests);
      blockedCounterparts = Map.copyOf(blockedCounterparts);
      eligibleUnitsByRequest = immutableSetValues(eligibleUnitsByRequest);
      ownerByUnit = Map.copyOf(ownerByUnit);
    }

    private static Map<String, Set<String>> immutableSetValues(Map<String, Set<String>> source) {
      return source.entrySet().stream()
          .collect(
              java.util.stream.Collectors.toUnmodifiableMap(
                  Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }
  }
}
