package dev.spanlease.analyzer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.spanlease.common.ContractJson;
import dev.spanlease.common.ObservationEnvelope;
import dev.spanlease.common.RunManifest;
import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Online analyzer: a loopback OTLP/gRPC receiver plus the single-writer analyzer core.
 *
 * <p>Receiver threads only validate and hand whole batches to {@link AnalyzerIngress}. One core
 * thread owns the {@link AnalyzerEngine}: it ingests admitted batches and evaluates the whole
 * manifest scope every evaluation interval, emitting a report whenever the verdict, evidence core
 * or missing-evidence set changes. The analyzer never receives ground truth; its only inputs are
 * the manifest and OTLP observations.
 *
 * <p>Command line: {@code --manifest <run-manifest.json> [--port <n>] [--emit changes|all]} (port
 * default 4317, 0 picks a free port). Stdout protocol: one {@code READY port=<n>} line once the
 * receiver listens, then one {@code REPORT <verdict-json>} line per change, or per evaluation with
 * {@code --emit all} (used by evaluation harnesses). The process runs until terminated.
 */
public final class LiveAnalyzer implements AutoCloseable {
  static final int INGRESS_CAPACITY_RECORDS = 65_536;
  private static final int MAX_EXPORT_BYTES = 4 * 1024 * 1024;

  private final AnalyzerEngine engine;
  private final AnalyzerIngress ingress = new AnalyzerIngress(INGRESS_CAPACITY_RECORDS);
  private final long evaluationIntervalMs;
  private final boolean emitEveryEvaluation;
  private final Consumer<VerdictReport> reports;
  private final Clock wallClock;
  private final Server server;
  private final Thread core;
  private volatile boolean running = true;
  private volatile RuntimeException failure;

  private LiveAnalyzer(
      RunManifest manifest,
      int port,
      boolean emitEveryEvaluation,
      Consumer<VerdictReport> reports,
      Clock wallClock,
      LongSupplier monotonicClock)
      throws IOException {
    this.engine = new AnalyzerEngine(manifest);
    this.evaluationIntervalMs = manifest.parameters().evaluationIntervalMs();
    this.emitEveryEvaluation = emitEveryEvaluation;
    this.reports = Objects.requireNonNull(reports, "reports");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    OtlpLogReceiver receiver =
        new OtlpLogReceiver(
            Set.copyOf(
                manifest.instances().stream().map(RunManifest.Instance::instanceId).toList()),
            ingress,
            wallClock,
            monotonicClock);
    this.server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            .maxInboundMessageSize(MAX_EXPORT_BYTES)
            .addService(receiver)
            .build()
            .start();
    this.core = new Thread(this::runCore, "spanlease-analyzer-core");
    core.start();
  }

  /**
   * Starts the receiver on loopback and the analyzer core thread.
   *
   * @param emitEveryEvaluation report every evaluation instead of only changes
   */
  public static LiveAnalyzer start(
      RunManifest manifest,
      int port,
      boolean emitEveryEvaluation,
      Consumer<VerdictReport> reports,
      Clock wallClock,
      LongSupplier monotonicClock)
      throws IOException {
    return new LiveAnalyzer(
        manifest, port, emitEveryEvaluation, reports, wallClock, monotonicClock);
  }

  public int port() {
    return server.getPort();
  }

  /** Waits for the core to stop; rethrows an unexpected core failure. */
  public void awaitTermination() throws InterruptedException {
    core.join();
    if (failure != null) {
      throw failure;
    }
  }

  private void runCore() {
    long nextEvaluation = wallClock.millis() + evaluationIntervalMs;
    ReportSignature last = null;
    try {
      while (running) {
        long waitMs = nextEvaluation - wallClock.millis();
        if (waitMs > 0) {
          List<ObservationEnvelope> batch = ingress.poll(waitMs);
          if (batch != null) {
            batch.forEach(engine::ingest);
          }
          continue;
        }
        ingress.drain().forEach(batch -> batch.forEach(engine::ingest));
        long evaluatedAt = wallClock.millis();
        VerdictReport report = engine.evaluate(evaluatedAt);
        ReportSignature signature = ReportSignature.of(report);
        if (emitEveryEvaluation || !signature.equals(last)) {
          last = signature;
          reports.accept(report);
        }
        nextEvaluation += evaluationIntervalMs;
        if (nextEvaluation <= evaluatedAt) {
          nextEvaluation = evaluatedAt + evaluationIntervalMs;
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException unexpected) {
      failure = unexpected;
    }
  }

  @Override
  public void close() throws InterruptedException {
    running = false;
    core.interrupt();
    server.shutdown();
    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
      server.shutdownNow();
    }
    core.join(TimeUnit.SECONDS.toMillis(5));
  }

  public static void main(String[] args) throws Exception {
    Path manifestPath = null;
    int port = 4317;
    boolean emitAll = false;
    for (int index = 0; index < args.length; index += 2) {
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("missing value for " + args[index]);
      }
      switch (args[index]) {
        case "--manifest" -> manifestPath = Path.of(args[index + 1]);
        case "--port" -> port = Integer.parseInt(args[index + 1]);
        case "--emit" -> emitAll = parseEmit(args[index + 1]);
        default -> throw new IllegalArgumentException("unknown option " + args[index]);
      }
    }
    if (manifestPath == null) {
      throw new IllegalArgumentException(
          "usage: --manifest <run-manifest.json> [--port <n>] [--emit changes|all]");
    }
    ObjectMapper json = ContractJson.mapper();
    RunManifest manifest = json.readValue(manifestPath.toFile(), RunManifest.class);
    LiveAnalyzer analyzer =
        start(
            manifest,
            port,
            emitAll,
            report -> emit("REPORT " + encode(json, report)),
            Clock.systemUTC(),
            System::nanoTime);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    analyzer.close();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                }));
    emit("READY port=" + analyzer.port());
    analyzer.awaitTermination();
  }

  private static boolean parseEmit(String value) {
    return switch (value) {
      case "all" -> true;
      case "changes" -> false;
      default -> throw new IllegalArgumentException("--emit must be changes or all");
    };
  }

  private static String encode(ObjectMapper json, VerdictReport report) {
    try {
      return json.writeValueAsString(report);
    } catch (JsonProcessingException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static synchronized void emit(String line) {
    System.out.println(line);
    System.out.flush();
  }

  /** Report identity for change detection; lease renewals alone do not produce a new report. */
  private record ReportSignature(
      Verdict verdict,
      List<String> executions,
      List<String> queueEntries,
      List<String> units,
      List<VerdictReport.MissingEvidence> missing) {
    static ReportSignature of(VerdictReport report) {
      return new ReportSignature(
          report.verdict(),
          report.core().executions().stream().map(VerdictReport.Execution::id).toList(),
          report.core().queueEntries().stream()
              .map(VerdictReport.QueueEntry::invocationId)
              .toList(),
          report.core().units().stream().map(VerdictReport.Unit::id).toList(),
          report.coverage().missing());
    }
  }
}
