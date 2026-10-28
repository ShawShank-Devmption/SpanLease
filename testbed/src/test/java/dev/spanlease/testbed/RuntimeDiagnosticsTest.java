package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.spanlease.instrumentation.SlotTrackingExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeDiagnosticsTest {
  @Test
  void samplesTheActualHandlerThreadAndItsAssignedSlotWithoutGuessingFromNames() throws Exception {
    RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              var execution =
                  new SlotTrackingExecutor.ExecutionContext(
                      "execution-7", "invocation-3", "A:app:slot-1");
              diagnostics.enter("svcA", execution);
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                diagnostics.exit(execution.executionId());
              }
            },
            "actual-worker");
    worker.start();
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var sample = diagnostics.sample();
      assertThat(sample.pid()).isEqualTo(ProcessHandle.current().pid());
      assertThat(sample.osName()).isNotBlank();
      assertThat(sample.workers()).hasSize(1);
      var observed = sample.workers().getFirst();
      assertThat(observed.javaThreadId()).isEqualTo(worker.threadId());
      assertThat(observed.threadName()).isEqualTo("actual-worker");
      assertThat(observed.executionId()).isEqualTo("execution-7");
      assertThat(observed.unitId()).isEqualTo("A:app:slot-1");
      assertThat(observed.stack()).isNotEmpty();
    } finally {
      release.countDown();
      worker.join(5_000);
    }
    assertThat(worker.isAlive()).isFalse();
    assertThat(diagnostics.sample().workers()).isEmpty();
  }
}
