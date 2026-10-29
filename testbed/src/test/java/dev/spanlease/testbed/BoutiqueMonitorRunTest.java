package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.Verdict;
import dev.spanlease.common.VerdictReport;
import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class BoutiqueMonitorRunTest {
  @TempDir Path runs;

  @Test
  @Timeout(60)
  void liveStoreTrafficNeverConfirmsADeadlock() throws Exception {
    try (Session session = start(Scenario.boutiqueLive());
        Traffic traffic = new Traffic(session.state)) {
      assertThat(session.run.inject()).isFalse();
      await(
          session.state,
          Duration.ofSeconds(20),
          snapshot ->
              traffic.sent.get() >= 30
                  && snapshot.reports().stream()
                      .anyMatch(report -> report.verdict() == Verdict.CONFIRMED_NO_DEADLOCK));
      traffic.close();
      assertThat(traffic.ok.get())
          .isGreaterThanOrEqualTo((int) Math.ceil(traffic.sent.get() * 0.9));
      assertThat(session.state.snapshot().reports())
          .noneMatch(report -> report.verdict() == Verdict.CONFIRMED_DEADLOCK);
      assertThat(session.state.snapshot().reports())
          .allSatisfy(
              report ->
                  assertThat(report.coverage().missing())
                      .noneMatch(missing -> missing.code().equals("INVALID_IDENTITY")));
    }
  }

  @Test
  @Timeout(90)
  void injectedDeadlockConfirmsExactCoreWhileStoreTrafficQueues() throws Exception {
    try (Session session = start(Scenario.boutiqueDeadlock());
        Traffic traffic = new Traffic(session.state)) {
      await(session.state, Duration.ofSeconds(10), snapshot -> traffic.ok.get() >= 5);
      assertThat(session.run.inject()).isTrue();
      assertThat(session.run.inject()).isFalse();
      await(session.state, Duration.ofSeconds(30), snapshot -> snapshot.firstDeadlock() != null);
      VerdictReport first = session.state.snapshot().firstDeadlock();
      assertThat(first.core().executions()).hasSize(3);
      assertThat(first.core().units())
          .extracting(VerdictReport.Unit::id)
          .containsExactlyInAnyOrderElementsOf(
              List.of("ads-a", "ads-b", "ads-c").stream()
                  .map(service -> session.plan.instanceByService().get(service) + ":app:slot-0")
                  .toList());
      List<String> executions =
          first.core().executions().stream().map(VerdictReport.Execution::id).toList();
      assertThat(first.core().units())
          .allSatisfy(unit -> assertThat(executions).contains(unit.ownerExecutionId()));
      assertThat(first.core().queueEntries())
          .singleElement()
          .satisfies(
              queue ->
                  assertThat(queue.instanceId())
                      .isEqualTo(session.plan.instanceByService().get("ads-a")));
      int beforeSent = traffic.sent.get();
      int beforeDeadline = traffic.deadline.get();
      assertThatThrownBy(() -> session.finished.get(10, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);
      traffic.close();
      assertThat(traffic.deadline.get() - beforeDeadline)
          .isGreaterThanOrEqualTo((int) Math.ceil((traffic.sent.get() - beforeSent) * 0.9));
      session.stop();
      assertThat(session.state.snapshot().phase()).isEqualTo("STOPPED");
      assertThat(session.state.snapshot().firstDeadlock()).isEqualTo(first);
      assertThat(session.state.snapshot().latestReport()).isNotNull();
      assertThat(session.run.inject()).isFalse();
      assertThat(runs.resolve(session.plan.manifest().runId()).resolve("first-deadlock.json"))
          .exists();
    }
  }

  @Test
  @Timeout(60)
  void spareCapacityInjectionCompletesAndStoreRecovers() throws Exception {
    try (Session session = start(Scenario.boutiqueSpareCapacity());
        Traffic traffic = new Traffic(session.state)) {
      await(session.state, Duration.ofSeconds(10), snapshot -> traffic.ok.get() >= 5);
      assertThat(session.run.inject()).isTrue();
      await(
          session.state, Duration.ofSeconds(40), snapshot -> snapshot.faultOutcome().equals("OK"));
      var snapshot = session.state.snapshot();
      assertThat(snapshot.faultAds())
          .extracting(StoreStats.AdView::text)
          .containsExactly(
              "Bamboo glass jar for sale. 10% off.",
              "Mug for sale. Buy two, get third one for free");
      assertThat(snapshot.firstDeadlock()).isNull();
      assertThat(snapshot.reports())
          .anySatisfy(
              report -> {
                assertThat(report.verdict()).isEqualTo(Verdict.CONFIRMED_NO_DEADLOCK);
                assertThat(report.query().certifiedThroughWallMs())
                    .isNotNull()
                    .isGreaterThan(snapshot.injectedAtMs())
                    .isLessThan(snapshot.faultCompletedAtMs());
              });
      int completed = traffic.ok.get();
      await(session.state, Duration.ofSeconds(10), next -> traffic.ok.get() >= completed + 5);
    }
  }

  @Test
  @Timeout(60)
  void startupPortConflictIsFailedAndNamesThePort() throws Exception {
    try (ServerSocket occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress("127.0.0.1", 0));
      MonitorState state = new MonitorState(2_000);
      RunPlan plan = RunPlan.create(Scenario.boutiqueLive(), System.currentTimeMillis());
      state.begin(plan);
      try (MonitorRun run =
          MonitorRun.start(
              plan, runs, state, new InetSocketAddress("127.0.0.1", occupied.getLocalPort()))) {
        run.awaitExit();
        assertThat(state.snapshot().phase()).isEqualTo("FAILED");
        assertThat(state.snapshot().error()).contains("" + occupied.getLocalPort());
        assertThat(run.inject()).isFalse();
      }
    }
  }

  @Test
  @Timeout(30)
  void userStopBeforeTheFirstStoreSampleIsStoppedNotStartupFailed() throws Exception {
    MonitorState state = new MonitorState(2_000);
    RunPlan plan = RunPlan.create(Scenario.boutiqueLive(), System.currentTimeMillis());
    state.begin(plan);
    MonitorRun run = MonitorRun.start(plan, runs, state, new InetSocketAddress("127.0.0.1", 0));
    CompletableFuture<Void> finished = awaitExit(run);
    try {
      assertThat(ProcessHandle.of(state.snapshot().applicationPid()).orElseThrow().destroy())
          .isTrue();
      finished.get(10, TimeUnit.SECONDS);
      assertThat(state.snapshot().phase()).isEqualTo("STOPPED");
      assertThat(state.snapshot().error()).isEmpty();
      assertThat(state.snapshot().exitCode()).isNotZero();
    } finally {
      run.close();
    }
  }

  @Test
  @Timeout(60)
  void monitorCloseGracefullyCleansBoutiqueAndReleasesTheEdgePort() throws Exception {
    int edgePort;
    try (ServerSocket reservation = new ServerSocket()) {
      reservation.bind(new InetSocketAddress("127.0.0.1", 0));
      edgePort = reservation.getLocalPort();
    }
    InetSocketAddress edgeBind = new InetSocketAddress("127.0.0.1", edgePort);

    MonitorState firstState = new MonitorState(2_000);
    RunPlan firstPlan = RunPlan.create(Scenario.boutiqueLive(), System.currentTimeMillis());
    firstState.begin(firstPlan);
    MonitorRun first = MonitorRun.start(firstPlan, runs, firstState, edgeBind);
    CompletableFuture<Void> firstFinished = awaitExit(first);
    first.close();
    firstFinished.get(10, TimeUnit.SECONDS);
    assertThat(firstState.snapshot().exitCode()).isZero();
    assertThat(firstState.snapshot().phase()).isEqualTo("STOPPED");

    MonitorState secondState = new MonitorState(2_000);
    RunPlan secondPlan = RunPlan.create(Scenario.boutiqueLive(), System.currentTimeMillis());
    secondState.begin(secondPlan);
    MonitorRun second = MonitorRun.start(secondPlan, runs, secondState, edgeBind);
    CompletableFuture<Void> secondFinished = awaitExit(second);
    second.close();
    secondFinished.get(10, TimeUnit.SECONDS);
    assertThat(secondState.snapshot().exitCode()).isZero();
  }

  @Test
  @Timeout(60)
  void closedControlPipeRejectsInjectionInsteadOfDroppingTheRequest() throws Exception {
    try (Session session = start(Scenario.boutiqueDeadlock())) {
      application(session.run).getOutputStream().close();
      assertThat(session.run.inject()).isFalse();
    }
  }

  @Test
  @Timeout(60)
  void boutiqueThenChainClearsProcessState() throws Exception {
    MonitorState state;
    try (Session session = start(Scenario.boutiqueLive());
        Traffic traffic = new Traffic(session.state)) {
      state = session.state;
      await(state, Duration.ofSeconds(10), snapshot -> traffic.ok.get() >= 5);
    }
    RunPlan chain = RunPlan.create(Scenario.spareCapacity(), System.currentTimeMillis());
    assertThat(state.begin(chain)).isTrue();
    try (MonitorRun run = MonitorRun.start(chain, runs, state)) {
      run.awaitExit();
      assertThat(state.snapshot().application()).isEqualTo("CHAIN");
      assertThat(state.snapshot().store()).isNull();
      assertThat(state.snapshot().faultAds()).isEmpty();
      assertThat(state.snapshot().injectedAtMs()).isNull();
      assertThat(state.snapshot().rootOutcome()).isEqualTo("OK");
    }
  }

  private Session start(Scenario scenario) throws Exception {
    RunPlan plan = RunPlan.create(scenario, System.currentTimeMillis());
    MonitorState state = new MonitorState(2_000);
    state.begin(plan);
    MonitorRun run = MonitorRun.start(plan, runs, state, new InetSocketAddress("127.0.0.1", 0));
    CompletableFuture<Void> finished = awaitExit(run);
    Session session = new Session(plan, state, run, finished);
    try {
      await(state, Duration.ofSeconds(20), snapshot -> snapshot.store() != null);
      return session;
    } catch (Throwable failure) {
      session.close();
      throw failure;
    }
  }

  private static CompletableFuture<Void> awaitExit(MonitorRun run) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            run.awaitExit();
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          }
        });
  }

  private static Process application(MonitorRun run) throws Exception {
    Field field = MonitorRun.class.getDeclaredField("application");
    field.setAccessible(true);
    return (Process) field.get(run);
  }

  private static void await(
      MonitorState state, Duration timeout, Predicate<MonitorState.Snapshot> condition)
      throws InterruptedException {
    long until = System.nanoTime() + timeout.toNanos();
    synchronized (state) {
      while (!condition.test(state.snapshot())) {
        long remaining = until - System.nanoTime();
        assertThat(remaining)
            .as("condition before timeout; latest report: %s", state.snapshot().latestReport())
            .isPositive();
        TimeUnit.NANOSECONDS.timedWait(state, remaining);
      }
    }
  }

  private record Session(
      RunPlan plan, MonitorState state, MonitorRun run, CompletableFuture<Void> finished)
      implements AutoCloseable {
    void stop() throws Exception {
      ProcessHandle.of(state.snapshot().applicationPid())
          .filter(ProcessHandle::isAlive)
          .ifPresent(ProcessHandle::destroy);
      finished.get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
      try {
        stop();
      } finally {
        run.close();
      }
    }
  }

  private static final class Traffic implements AutoCloseable {
    final AtomicInteger sent = new AtomicInteger();
    final AtomicInteger ok = new AtomicInteger();
    final AtomicInteger deadline = new AtomicInteger();
    private final ManagedChannel channel;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean closed;

    Traffic(MonitorState state) {
      channel =
          NettyChannelBuilder.forAddress("127.0.0.1", state.snapshot().store().edgePort())
              .usePlaintext()
              .disableRetry()
              .build();
      scheduler.scheduleAtFixedRate(
          () -> {
            sent.incrementAndGet();
            try {
              Demo.AdResponse response =
                  AdServiceGrpc.newBlockingStub(channel)
                      .withDeadlineAfter(100, TimeUnit.MILLISECONDS)
                      .getAds(Demo.AdRequest.newBuilder().addContextKeys("kitchen").build());
              if (response.getAdsList().stream()
                  .map(Demo.Ad::getText)
                  .toList()
                  .equals(
                      List.of(
                          "Bamboo glass jar for sale. 10% off.",
                          "Mug for sale. Buy two, get third one for free"))) ok.incrementAndGet();
            } catch (StatusRuntimeException failure) {
              if (failure.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED)
                deadline.incrementAndGet();
            } finally {
              synchronized (state) {
                state.notifyAll();
              }
            }
          },
          new Random(42).nextInt(20),
          100,
          TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() throws InterruptedException {
      if (closed) return;
      closed = true;
      scheduler.shutdown();
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
