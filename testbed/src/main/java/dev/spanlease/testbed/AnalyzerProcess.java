package dev.spanlease.testbed;

import dev.spanlease.common.ContractJson;
import dev.spanlease.common.VerdictReport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Runs the analyzer as a separate process and consumes its report stream (A-HARNESS). The testbed
 * never links analyzer classes: it hands over the manifest file, reads {@code READY} and {@code
 * REPORT} lines from stdout and decodes reports with the shared {@code common} contract. The
 * analyzer's only inputs are that manifest and the OTLP observations it receives.
 */
public final class AnalyzerProcess implements AutoCloseable {
  /** Analyzer runtime classpath, supplied by the Gradle tasks that run the harness. */
  public static final String CLASSPATH_PROPERTY = "spanlease.analyzer.classpath";

  private static final String MAIN_CLASS = "dev.spanlease.analyzer.LiveAnalyzer";

  private final Process process;
  private final CompletableFuture<Integer> ready = new CompletableFuture<>();
  private final List<TimedReport> reports = new CopyOnWriteArrayList<>();
  private final Consumer<TimedReport> listener;
  private final Thread reader;

  private AnalyzerProcess(Process process, Consumer<TimedReport> listener) {
    this.process = process;
    this.listener = listener;
    this.reader = new Thread(this::readOutput, "analyzer-output");
    reader.setDaemon(true);
    reader.start();
  }

  /** Launches the analyzer on a free loopback port, emitting a report per evaluation. */
  public static AnalyzerProcess start(Path manifestFile, Consumer<TimedReport> listener)
      throws IOException {
    String classpath = System.getProperty(CLASSPATH_PROPERTY);
    if (classpath == null || classpath.isBlank()) {
      throw new IllegalStateException(
          CLASSPATH_PROPERTY + " is not set; run through ./gradlew :testbed:liveDemo");
    }
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                MAIN_CLASS,
                "--manifest",
                manifestFile.toString(),
                "--port",
                "0",
                "--emit",
                "all")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    return new AnalyzerProcess(process, Objects.requireNonNull(listener, "listener"));
  }

  /** Waits until the analyzer's OTLP receiver is listening and returns its port. */
  public int awaitReady(Duration timeout) throws InterruptedException, TimeoutException {
    try {
      return ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException failure) {
      throw new IllegalStateException("analyzer failed to start", failure.getCause());
    }
  }

  public List<TimedReport> reports() {
    return List.copyOf(reports);
  }

  /** Waits for a report matching the predicate; returns false if none arrives in time. */
  public synchronized boolean awaitReport(Predicate<TimedReport> predicate, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (reports.stream().noneMatch(predicate)) {
      long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remainingMs <= 0) {
        return false;
      }
      wait(remainingMs);
    }
    return true;
  }

  private void readOutput() {
    try (BufferedReader output =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line = output.readLine(); line != null; line = output.readLine()) {
        if (line.startsWith("READY port=")) {
          ready.complete(Integer.parseInt(line.substring("READY port=".length())));
        } else if (line.startsWith("REPORT ")) {
          TimedReport report =
              new TimedReport(
                  System.currentTimeMillis(),
                  ContractJson.mapper()
                      .readValue(line.substring("REPORT ".length()), VerdictReport.class));
          synchronized (this) {
            reports.add(report);
            notifyAll();
          }
          listener.accept(report);
        }
      }
    } catch (IOException | RuntimeException failure) {
      ready.completeExceptionally(failure);
      return;
    }
    ready.completeExceptionally(new IllegalStateException("analyzer exited before READY"));
  }

  @Override
  public void close() throws InterruptedException {
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    reader.join(TimeUnit.SECONDS.toMillis(5));
  }

  /** One decoded report and when the harness received it. */
  public record TimedReport(long receivedAtMs, VerdictReport report) {}
}
