package dev.spanlease.testbed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluation-only physical oracle. Enumerates every subset rather than reusing production
 * reduction; concrete witness checks also verify that the reported subset is itself closed.
 */
public final class TruthOracle {
  private TruthOracle() {}

  private static Snapshot snapshot(List<TruthRecorder.Entry> journal, long atMs) {
    Map<String, String> owners = new HashMap<>();
    Set<String> queued = new HashSet<>();
    Map<String, String> blocked = new HashMap<>();
    Map<String, TruthRecorder.Job> jobs = new HashMap<>();
    for (TruthRecorder.Entry entry : journal) {
      if (entry.wallMs() > atMs || entry.job() == null) continue;
      var job = entry.job();
      String key = attempt(job);
      jobs.put(key, job);
      switch (entry.transition()) {
        case "QUEUE" -> queued.add(key);
        case "ASSIGN" -> {
          queued.remove(key);
          owners.put(entry.service() + ":" + entry.slot(), key);
        }
        case "CALL_ENTER" -> blocked.put(key, job.requestId() + "/" + (job.remaining() - 1));
        case "CALL_RETURN", "EXIT" -> blocked.remove(key);
        case "RELEASE" -> owners.remove(entry.service() + ":" + entry.slot());
        case "CANCEL", "REJECT" -> queued.remove(key);
        default -> {}
      }
    }
    return new Snapshot(owners, queued, blocked, jobs);
  }

  public static Set<String> closedJobs(
      List<TruthRecorder.Entry> journal, Map<String, Integer> capacities, long atMs) {
    var state = snapshot(journal, atMs);
    var candidates = new ArrayList<>(state.blocked().keySet());
    candidates.addAll(state.queued());
    candidates.sort(String::compareTo);
    if (candidates.size() > 20)
      throw new IllegalArgumentException("truth oracle limit: 20 candidates");
    Set<String> union = new TreeSet<>();
    for (long mask = 1; mask < (1L << candidates.size()); mask++) {
      Set<String> subset = new HashSet<>();
      for (int i = 0; i < candidates.size(); i++)
        if ((mask & (1L << i)) != 0) subset.add(candidates.get(i));
      if (closed(state, capacities, subset)) union.addAll(subset);
    }
    return Set.copyOf(union);
  }

  public static boolean witnesses(
      List<TruthRecorder.Entry> journal,
      Map<String, Integer> capacities,
      long atMs,
      Set<String> selected) {
    return !selected.isEmpty() && closed(snapshot(journal, atMs), capacities, selected);
  }

  private static boolean closed(
      Snapshot state, Map<String, Integer> capacities, Set<String> selected) {
    for (String member : selected) {
      if (state.blocked().containsKey(member)) {
        if (!selected.contains(state.blocked().get(member))) return false;
      } else {
        if (!state.queued().contains(member)) return false;
        String service = state.jobs().get(member).service();
        for (int slot = 0; slot < capacities.get(service); slot++) {
          if (!selected.contains(state.owners().get(service + ":" + slot))) return false;
        }
      }
    }
    return true;
  }

  public static String attempt(TruthRecorder.Job job) {
    return job.requestId() + "/" + job.remaining();
  }

  private record Snapshot(
      Map<String, String> owners,
      Set<String> queued,
      Map<String, String> blocked,
      Map<String, TruthRecorder.Job> jobs) {}
}
