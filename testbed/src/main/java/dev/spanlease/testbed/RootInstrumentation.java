package dev.spanlease.testbed;

import dev.spanlease.common.RunManifest;
import dev.spanlease.instrumentation.EventSink;
import dev.spanlease.instrumentation.InstanceRecorder;
import dev.spanlease.instrumentation.LeasePolicy;
import dev.spanlease.instrumentation.PeriodicScanner;
import dev.spanlease.instrumentation.RootCaller;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Shared recorder, scanner and caller lifecycle for a manifest-declared root instance. */
final class RootInstrumentation implements AutoCloseable {
  private final InstanceRecorder recorder;
  private final PeriodicScanner scanner;
  private final RootCaller caller;

  RootInstrumentation(
      String instanceId,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> invocationIds) {
    recorder = new InstanceRecorder(instanceId, sink, Clock.systemUTC(), System::nanoTime);
    scanner =
        new PeriodicScanner(
            recorder,
            List.of(),
            LeasePolicy.DEFAULT,
            Duration.ofMillis(LeasePolicy.DEFAULT.renewalMs() / 2),
            Duration.ofMillis(parameters.checkpointIntervalMs()),
            "root-scanner-" + instanceId);
    caller = new RootCaller(recorder, invocationIds);
  }

  RootCaller caller() {
    return caller;
  }

  /** Stops periodic emission, then publishes the final checkpoint before telemetry closes. */
  @Override
  public void close() throws InterruptedException {
    scanner.close();
    recorder.checkpoint();
  }
}
