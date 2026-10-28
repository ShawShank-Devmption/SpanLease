package dev.spanlease.analyzer;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.VerdictReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates message identities and acyclicity of local-order plus exact-parent edges, never time.
 */
final class CausalHistoryValidator {
  private CausalHistoryValidator() {}

  static List<VerdictReport.MissingEvidence> validate(
      RunManifest manifest, List<ObservationEvent> events) {
    Map<String, ObservationEvent> byReference = new TreeMap<>();
    Map<String, ObservationEvent> sends = new HashMap<>();
    Map<String, String> arrivals = new HashMap<>();
    Map<String, String> services = new HashMap<>();
    manifest
        .instances()
        .forEach(instance -> services.put(instance.instanceId(), instance.serviceBinding()));
    List<VerdictReport.MissingEvidence> invalid = new ArrayList<>();
    for (ObservationEvent event : events) {
      byReference.put(event.eventReference(), event);
      if (event.payload() instanceof EventPayload.RpcInvocationSent sent) {
        if (sends.putIfAbsent(sent.invocationId(), event) != null) {
          invalid.add(defect(event));
        }
      } else if (event.payload() instanceof EventPayload.RpcInvocationArrived arrived) {
        if (arrivals.putIfAbsent(arrived.invocationId(), event.eventReference()) != null) {
          invalid.add(defect(event));
        }
      }
    }

    Map<String, Set<String>> children = new HashMap<>();
    Map<String, Integer> incoming = new TreeMap<>();
    Map<String, String> previous = new HashMap<>();
    for (ObservationEvent event : events) {
      String reference = event.eventReference();
      incoming.putIfAbsent(reference, 0);
      String prior = previous.put(event.instanceId(), reference);
      if (prior != null) {
        edge(prior, reference, children, incoming);
      }
      ObservationEvent parent = byReference.get(event.causalParent());
      if (parent == null) {
        continue; // An absent parent is handled by cut retraction, not invented or repaired here.
      }
      edge(parent.eventReference(), reference, children, incoming);
      boolean valid = true;
      if (event.payload() instanceof EventPayload.RpcInvocationArrived arrived) {
        valid =
            parent.payload() instanceof EventPayload.RpcInvocationSent sent
                && sent.invocationId().equals(arrived.invocationId())
                && sent.targetService().equals(services.get(event.instanceId()));
      } else if (event.payload() instanceof EventPayload.RpcBlockEnd end
          && end.completionKind() == EventPayload.CompletionKind.RESPONSE) {
        valid =
            parent.payload() instanceof EventPayload.RpcResponseSent response
                && response.invocationId().equals(end.invocationId());
        ObservationEvent sentEvent = sends.get(end.invocationId());
        if (sentEvent != null) {
          EventPayload.RpcInvocationSent sent =
              (EventPayload.RpcInvocationSent) sentEvent.payload();
          valid &= sent.targetService().equals(services.get(parent.instanceId()));
        }
      }
      if (!valid) {
        invalid.add(defect(event));
      }
    }

    ArrayDeque<String> ready = new ArrayDeque<>();
    incoming.forEach(
        (reference, count) -> {
          if (count == 0) ready.add(reference);
        });
    int visited = 0;
    while (!ready.isEmpty()) {
      String reference = ready.remove();
      visited++;
      for (String child : children.getOrDefault(reference, Set.of())) {
        int remaining = incoming.compute(child, (ignored, count) -> count - 1);
        if (remaining == 0) ready.add(child);
      }
    }
    if (visited != incoming.size()) {
      incoming.entrySet().stream()
          .filter(entry -> entry.getValue() > 0)
          .findFirst()
          .ifPresent(
              entry ->
                  invalid.add(new VerdictReport.MissingEvidence("INVALID_EVENT", entry.getKey())));
    }
    return invalid.stream().distinct().toList();
  }

  private static void edge(
      String parent,
      String child,
      Map<String, Set<String>> children,
      Map<String, Integer> incoming) {
    if (children.computeIfAbsent(parent, ignored -> new HashSet<>()).add(child)) {
      incoming.merge(child, 1, Integer::sum);
    }
  }

  private static VerdictReport.MissingEvidence defect(ObservationEvent event) {
    return new VerdictReport.MissingEvidence("INVALID_EVENT", event.eventReference());
  }
}
