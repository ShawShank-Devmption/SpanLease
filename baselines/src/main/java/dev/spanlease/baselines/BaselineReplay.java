package dev.spanlease.baselines;

import dev.spanlease.analyzer.AnalyzerEngine;
import dev.spanlease.analyzer.ReplaySession;
import dev.spanlease.common.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Offline information/retention comparisons on identical delivered facts and evaluation times.
 * Native SDK spans are real; replay CPU is NOT a live instrumentation overhead experiment. Alarms
 * from weaker projections are deliberately not called confirmed detector verdicts.
 */
public final class BaselineReplay {
  private BaselineReplay() {}

  private static final AttributeKey<String> ENVELOPE = AttributeKey.stringKey("sl.envelope");

  public static List<Result> evaluate(ReplaySession session, int retention) throws Exception {
    if (retention <= 0) throw new IllegalArgumentException("positive retention required");
    var json = ContractJson.mapper();
    AnalyzerEngine enriched = new AnalyzerEngine(session.manifest());
    AnalyzerEngine buffered = new AnalyzerEngine(session.manifest());
    Map<String, Span> instances = new TreeMap<>();
    Map<String, Span> executions = new HashMap<>();
    Map<String, Integer> seen = new HashMap<>();
    Map<String, String> bindings = new HashMap<>();
    session.manifest().instances().forEach(i -> bindings.put(i.instanceId(), i.serviceBinding()));
    Map<String, String[]> pendingCalls = new TreeMap<>();
    Map<String, String> owners = new HashMap<>();
    Set<String> queues = new HashSet<>();
    List<SpanData> completed = new ArrayList<>();
    List<Result> results = new ArrayList<>();
    ArrayDeque<ObservationEnvelope> ring = new ArrayDeque<>();
    boolean triggered = false;
    boolean timeoutAlarm = false;
    try (var provider =
        SdkTracerProvider.builder()
            .setSpanLimits(SpanLimits.builder().setMaxNumberOfEvents(100_000).build())
            .addSpanProcessor(
                SimpleSpanProcessor.create(
                    new SpanExporter() {
                      @Override
                      public CompletableResultCode export(Collection<SpanData> spans) {
                        completed.addAll(spans);
                        return CompletableResultCode.ofSuccess();
                      }

                      @Override
                      public CompletableResultCode flush() {
                        return CompletableResultCode.ofSuccess();
                      }

                      @Override
                      public CompletableResultCode shutdown() {
                        return CompletableResultCode.ofSuccess();
                      }
                    }))
            .build()) {
      var tracer = provider.get("spanlease-baseline");
      for (var step : session.steps()) {
        for (var envelope : step.observations()) {
          var event = envelope.event();
          Span instance =
              instances.computeIfAbsent(
                  event.instanceId(),
                  id ->
                      tracer
                          .spanBuilder("instance-facts")
                          .setAttribute("instance", id)
                          .setNoParent()
                          .startSpan());
          instance.addEvent(
              event.eventType().wireName(),
              io.opentelemetry.api.common.Attributes.of(
                  ENVELOPE, json.writeValueAsString(envelope)));
          var payload = event.payload();
          if (payload instanceof EventPayload.ResourceAcquire acquire) {
            owners.put(acquire.resourceInstanceId(), acquire.executionId());
            queues.remove(acquire.invocationId());
            executions.computeIfAbsent(
                acquire.executionId(),
                id ->
                    tracer
                        .spanBuilder("handler")
                        .setNoParent()
                        .setAttribute("execution", id)
                        .setAttribute("invocation", acquire.invocationId())
                        .setStartTimestamp(event.wallTimeMs(), TimeUnit.MILLISECONDS)
                        .startSpan());
          } else if (payload instanceof EventPayload.ResourceWaitBegin wait) {
            queues.add(wait.invocationId());
          } else if (payload instanceof EventPayload.ResourceRelease release) {
            owners.remove(release.resourceInstanceId());
          } else if (payload instanceof EventPayload.ExecutionCancel cancel
              && cancel.executionId().isEmpty()) {
            queues.remove(cancel.invocationId());
          } else if (payload instanceof EventPayload.ExecutionEnd end) {
            Span span = executions.remove(end.executionId());
            if (span != null) span.end(event.wallTimeMs(), TimeUnit.MILLISECONDS);
          } else if (payload instanceof EventPayload.RpcInvocationSent sent
              && !sent.callerExecutionId().isEmpty()) {
            pendingCalls.put(
                sent.invocationId(),
                new String[] {bindings.get(event.instanceId()), sent.targetService()});
          } else if (payload instanceof EventPayload.RpcBlockEnd end) {
            pendingCalls.remove(end.invocationId());
            timeoutAlarm |= end.outcome() == EventPayload.Outcome.DEADLINE;
          }
          if (!triggered) {
            if (ring.size() == retention) ring.removeFirst();
            ring.addLast(envelope);
            if (payload instanceof EventPayload.ResourceWaitBegin) {
              triggered = true;
              for (var retained : ring)
                buffered.ingest(
                    new ObservationEnvelope(
                        retained.event(), envelope.receiptWallMs(), envelope.receiptMonotonicNs()));
              ring.clear();
            }
          } else buffered.ingest(envelope);
        }
        boolean overflow = false;
        for (var item : instances.entrySet()) {
          SpanData data = ((ReadableSpan) item.getValue()).toSpanData();
          overflow |= data.getTotalRecordedEvents() > data.getEvents().size();
          int previous = seen.getOrDefault(item.getKey(), 0);
          for (int i = previous; i < data.getEvents().size(); i++) {
            String body = data.getEvents().get(i).getAttributes().get(ENVELOPE);
            enriched.ingest(json.readValue(body, ObservationEnvelope.class));
          }
          seen.put(item.getKey(), data.getEvents().size());
        }
        if (step.evaluationWallMs() == null) continue;
        long at = step.evaluationWallMs();
        List<String> saturated =
            session.manifest().pools().stream()
                .filter(p -> p.unitIds().stream().allMatch(owners::containsKey))
                .map(RunManifest.Pool::qualifiedId)
                .sorted()
                .toList();
        results.add(
            new Result(
                at,
                completed.size(),
                executions.size(),
                timeoutAlarm,
                hasCycle(pendingCalls.values()),
                saturated,
                queues.size(),
                triggered,
                overflow ? null : enriched.evaluate(at),
                buffered.evaluate(at),
                overflow ? "NATIVE_SPAN_RETENTION_EXHAUSTED" : "OFFLINE_INFORMATION_COMPARISON"));
      }
      instances.values().forEach(Span::end);
      executions.values().forEach(Span::end);
    }
    return List.copyOf(results);
  }

  private static boolean hasCycle(Collection<String[]> edges) {
    Map<String, Set<String>> graph = new TreeMap<>();
    for (var edge : edges) graph.computeIfAbsent(edge[0], ignored -> new TreeSet<>()).add(edge[1]);
    for (String start : graph.keySet())
      if (visits(start, start, graph, new HashSet<>())) return true;
    return false;
  }

  private static boolean visits(
      String start, String current, Map<String, Set<String>> graph, Set<String> seen) {
    if (!seen.add(current)) return false;
    for (String next : graph.getOrDefault(current, Set.of()))
      if (next.equals(start) || visits(start, next, graph, seen)) return true;
    return false;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException(
          "usage: replay.json baselines.jsonl | --all runs-directory");
    if (args[0].equals("--all")) {
      try (var sessions = Files.walk(Path.of(args[1]), 2)) {
        for (Path session :
            sessions
                .filter(path -> path.getFileName().toString().equals("observations.replay.json"))
                .sorted()
                .toList()) {
          main(
              new String[] {
                session.toString(), session.resolveSibling("baselines.jsonl").toString()
              });
        }
      }
      return;
    }
    var json = ContractJson.mapper();
    var session = json.readValue(Path.of(args[0]).toFile(), ReplaySession.class);
    List<String> output = new ArrayList<>();
    for (var result : evaluate(session, 8192)) output.add(json.writeValueAsString(result));
    Files.write(Path.of(args[1]), output);
  }

  public record Result(
      long evaluatedAtMs,
      int completedHandlerSpans,
      int ordinaryLiveHandlerSpans,
      boolean completedTimeoutAlarm,
      boolean invocationCycleAlarm,
      List<String> saturatedPools,
      int queueDepth,
      boolean bufferTriggered,
      VerdictReport enrichedLiveFacts,
      VerdictReport triggeredBufferFacts,
      String scope) {}
}
