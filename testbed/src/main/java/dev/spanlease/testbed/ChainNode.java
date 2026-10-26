package dev.spanlease.testbed;

import dev.spanlease.common.RunManifest;
import dev.spanlease.instrumentation.EventSink;
import dev.spanlease.instrumentation.InstanceRecorder;
import dev.spanlease.instrumentation.LeasePolicy;
import dev.spanlease.instrumentation.PeriodicScanner;
import dev.spanlease.instrumentation.SlotTrackingExecutor;
import dev.spanlease.instrumentation.SpanLeaseClient;
import dev.spanlease.instrumentation.SpanLeaseServer;
import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * One instrumented Chain service instance (A-TESTBED).
 *
 * <p>gRPC callbacks run on their own small executor; every decoded request is admitted to a bounded
 * {@link SlotTrackingExecutor} whose workers run the handler. The handler (A-SPEC §1) requires
 * {@code remaining[0]} to name this service, performs {@code work_ms}, calls {@code remaining[1]}
 * with the tail through the SpanLease blocking helper while keeping its slot, performs {@code
 * post_hold_ms}, then replies. The root deadline propagates through the gRPC Context as a remaining
 * budget; transparent retries are disabled on every channel.
 */
public final class ChainNode implements AutoCloseable {
  static final int WAITING_CAPACITY = 4_096;
  private static final long MAX_DURATION_MS = 600_000;
  private static final long WORK_SLICE_MS = 20;

  private final String serviceName;
  private final InstanceRecorder recorder;
  private final SlotTrackingExecutor gate;
  private final PeriodicScanner scanner;
  private final SpanLeaseServer spanLease;
  private final SpanLeaseClient client;
  private final HandlerJournal journal;
  private final ExecutorService callbacks;
  private final Server server;
  private final Map<String, ManagedChannel> peers = new ConcurrentHashMap<>();

  private ChainNode(
      String serviceName,
      String instanceId,
      int capacity,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> invocationIds,
      HandlerJournal journal)
      throws IOException {
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.recorder = new InstanceRecorder(instanceId, sink, Clock.systemUTC(), System::nanoTime);
    this.gate =
        new SlotTrackingExecutor(
            recorder, RunPlan.POOL, capacity, WAITING_CAPACITY, threads(serviceName + "-worker"));
    LeasePolicy leases =
        new LeasePolicy(
            parameters.leaseAgeThresholdMs(),
            LeasePolicy.DEFAULT.renewalMs(),
            LeasePolicy.DEFAULT.expiryFactor());
    this.scanner =
        new PeriodicScanner(
            recorder,
            List.of(gate),
            leases,
            Duration.ofMillis(Math.min(leases.ageThresholdMs(), leases.renewalMs()) / 2),
            Duration.ofMillis(parameters.checkpointIntervalMs()),
            serviceName + "-scanner");
    this.spanLease = new SpanLeaseServer(gate, invocationIds);
    this.client = new SpanLeaseClient(gate, invocationIds);
    this.callbacks = Executors.newFixedThreadPool(2, threads(serviceName + "-grpc-callback"));
    this.server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .executor(callbacks)
            .addStreamTracerFactory(spanLease.streamTracerFactory())
            .addService(spanLease.intercept(new ChainService()))
            .build()
            .start();
  }

  /** Starts the instance; the gate's {@code resource.init} precedes the server accepting calls. */
  public static ChainNode start(
      String serviceName,
      String instanceId,
      int capacity,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> invocationIds,
      HandlerJournal journal)
      throws IOException {
    return new ChainNode(
        serviceName, instanceId, capacity, parameters, sink, invocationIds, journal);
  }

  public String serviceName() {
    return serviceName;
  }

  public int port() {
    return server.getPort();
  }

  /** Resolves logical service names to loopback ports; one instance per service. */
  public void connect(Map<String, Integer> servicePorts) {
    servicePorts.forEach(
        (service, port) ->
            peers.computeIfAbsent(
                service,
                ignored ->
                    NettyChannelBuilder.forAddress(
                            InetAddress.getLoopbackAddress().getHostAddress(), port)
                        .usePlaintext()
                        .disableRetry()
                        .build()));
  }

  public SlotTrackingExecutor.Snapshot snapshot() {
    return gate.snapshot();
  }

  @Override
  public void close() throws InterruptedException {
    server.shutdownNow();
    peers.values().forEach(ManagedChannel::shutdownNow);
    gate.close();
    recorder.checkpoint();
    scanner.close();
    server.awaitTermination(5, TimeUnit.SECONDS);
    for (ManagedChannel channel : peers.values()) {
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    callbacks.shutdown();
    callbacks.awaitTermination(5, TimeUnit.SECONDS);
  }

  private CallReply handle(SlotTrackingExecutor.ExecutionContext execution, CallRequest request) {
    int remaining = request.getRemainingCount();
    journal.record(serviceName, request.getRequestId(), remaining, HandlerJournal.Boundary.RUN);
    try {
      Hop hop = request.getRemaining(0);
      work(Integer.toUnsignedLong(hop.getWorkMs()));
      int hopsCompleted = 1;
      if (remaining > 1) {
        String next = request.getRemaining(1).getTargetService();
        ManagedChannel channel = peers.get(next);
        if (channel == null) {
          throw Status.FAILED_PRECONDITION
              .withDescription("no binding for service " + next)
              .asRuntimeException();
        }
        CallRequest tail =
            request.toBuilder()
                .clearRemaining()
                .addAllRemaining(request.getRemainingList().subList(1, remaining))
                .build();
        journal.record(
            serviceName, request.getRequestId(), remaining, HandlerJournal.Boundary.CALL_ENTER);
        CallReply reply;
        try {
          reply =
              client.call(
                  execution, next, ChainGrpc.newBlockingStub(channel), stub -> stub.call(tail));
        } finally {
          journal.record(
              serviceName, request.getRequestId(), remaining, HandlerJournal.Boundary.CALL_RETURN);
        }
        hopsCompleted += reply.getHopsCompleted();
      }
      work(Integer.toUnsignedLong(hop.getPostHoldMs()));
      return CallReply.newBuilder()
          .setRequestId(request.getRequestId())
          .setHopsCompleted(hopsCompleted)
          .build();
    } finally {
      journal.record(
          serviceName, request.getRequestId(), remaining, HandlerJournal.Boundary.HANDLER_RETURN);
    }
  }

  /** Finite application work that stops cooperatively when the call is cancelled. */
  private static void work(long durationMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
    for (long left = durationMs; left > 0; ) {
      if (Context.current().isCancelled()) {
        throw Status.CANCELLED.withDescription("work cancelled").asRuntimeException();
      }
      try {
        Thread.sleep(Math.min(left, WORK_SLICE_MS));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw Status.CANCELLED.withDescription("work interrupted").asRuntimeException();
      }
      left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }
  }

  private static String invalid(CallRequest request, String serviceName) {
    if (request.getRemainingCount() == 0) {
      return "empty hop list";
    }
    if (!request.getRemaining(0).getTargetService().equals(serviceName)) {
      return "remaining[0] does not name " + serviceName;
    }
    for (Hop hop : request.getRemainingList()) {
      if (hop.getTargetService().isBlank()
          || Integer.toUnsignedLong(hop.getWorkMs()) > MAX_DURATION_MS
          || Integer.toUnsignedLong(hop.getPostHoldMs()) > MAX_DURATION_MS) {
        return "invalid hop " + hop.getTargetService();
      }
    }
    return null;
  }

  private static ThreadFactory threads(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private final class ChainService extends ChainGrpc.ChainImplBase {
    @Override
    public void call(CallRequest request, StreamObserver<CallReply> responseObserver) {
      String invalid = invalid(request, serviceName);
      if (invalid != null) {
        responseObserver.onError(
            Status.INVALID_ARGUMENT.withDescription(invalid).asRuntimeException());
        return;
      }
      spanLease.handleUnary(request, responseObserver, ChainNode.this::handle);
    }
  }
}
