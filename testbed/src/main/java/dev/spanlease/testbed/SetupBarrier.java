package dev.spanlease.testbed;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Setup-only gate: all initial worker jobs must arrive, then every waiter leaves before
 * measurement.
 */
public final class SetupBarrier implements AutoCloseable {
  private final Map<String, Integer> initialRemaining;
  private final CountDownLatch arrived;
  private final CountDownLatch departed;
  private final CountDownLatch release = new CountDownLatch(1);
  private final TruthRecorder truth;

  public SetupBarrier(Workload workload, TruthRecorder truth) {
    initialRemaining =
        workload.roots().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Workload.Root::id, r -> r.hops().size()));
    arrived = new CountDownLatch(initialRemaining.size());
    departed = new CountDownLatch(initialRemaining.size());
    this.truth = truth;
  }

  public void enter(TruthRecorder.Job job) {
    if (!java.util.Objects.equals(initialRemaining.get(job.requestId()), job.remaining())) return;
    truth.record(job, "SETUP_ENTER");
    arrived.countDown();
    try {
      if (!release.await(15, TimeUnit.SECONDS))
        throw new IllegalStateException("setup release timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } finally {
      truth.record(job, "SETUP_LEAVE");
      departed.countDown();
    }
  }

  public void releaseAfterAllArrive() throws InterruptedException {
    if (!arrived.await(10, TimeUnit.SECONDS))
      throw new IllegalStateException("not every initial worker reached setup");
    release.countDown();
    if (!departed.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("setup waiters did not leave");
  }

  @Override
  public void close() {
    release.countDown();
  }
}
