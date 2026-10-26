package dev.spanlease.testbed;

import dev.spanlease.common.RunManifest;
import dev.spanlease.instrumentation.EventSink;
import dev.spanlease.instrumentation.InstanceRecorder;
import dev.spanlease.instrumentation.LeasePolicy;
import dev.spanlease.instrumentation.PeriodicScanner;
import dev.spanlease.instrumentation.RootCaller;
import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import dev.spanlease.testbed.proto.Hop;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Root workload driver: an observed instance with no pools. It records only root sends and
 * checkpoints (design §4); each root call is cancellable so measurement can end independently of
 * any detector verdict.
 */
public final class ChainDriver implements AutoCloseable {
  private final InstanceRecorder recorder;
  private final PeriodicScanner scanner;
  private final RootCaller rootCaller;
  private final Clock clock = Clock.systemUTC();
  private final ExecutorService rootThreads =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "driver-root");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

  public ChainDriver(
      String instanceId,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> invocationIds) {
    this(instanceId, parameters, sink, invocationIds, true);
  }

  public ChainDriver(
      String instanceId,
      RunManifest.Parameters parameters,
      EventSink sink,
      Supplier<String> invocationIds,
      boolean instrumented) {
    this.recorder =
        instrumented
            ? new InstanceRecorder(instanceId, sink, clock, System::nanoTime)
            : InstanceRecorder.disabled(instanceId, clock, System::nanoTime);
    this.scanner =
        !instrumented
            ? null
            : new PeriodicScanner(
                recorder,
                List.of(),
                LeasePolicy.DEFAULT,
                Duration.ofMillis(LeasePolicy.DEFAULT.renewalMs() / 2),
                Duration.ofMillis(parameters.checkpointIntervalMs()),
                "driver-scanner");
    this.rootCaller = new RootCaller(recorder, invocationIds);
  }

  public void connect(Map<String, Integer> servicePorts) {
    servicePorts.forEach(
        (service, port) ->
            channels.computeIfAbsent(
                service,
                ignored ->
                    NettyChannelBuilder.forAddress(
                            InetAddress.getLoopbackAddress().getHostAddress(), port)
                        .usePlaintext()
                        .disableRetry()
                        .build()));
  }

  /** Sends one root request asynchronously with an absolute deadline. */
  public RootCall submit(String requestId, List<Hop> hops, Duration deadline) {
    String target = hops.get(0).getTargetService();
    ManagedChannel channel = Objects.requireNonNull(channels.get(target), "unbound " + target);
    CallRequest request =
        CallRequest.newBuilder().setRequestId(requestId).addAllRemaining(hops).build();
    long sentAtMs = clock.millis();
    long sentNanos = System.nanoTime();
    var completedNanos = new java.util.concurrent.atomic.AtomicLong();
    Deadline rootDeadline = Deadline.after(deadline.toMillis(), TimeUnit.MILLISECONDS);
    Context.CancellableContext context = Context.current().withCancellation();
    CompletableFuture<CallReply> reply =
        CompletableFuture.supplyAsync(
                () -> {
                  Context previous = context.attach();
                  try {
                    return rootCaller.call(
                        target,
                        ChainGrpc.newBlockingStub(channel).withDeadline(rootDeadline),
                        stub -> stub.call(request));
                  } finally {
                    context.detach(previous);
                  }
                },
                rootThreads)
            .whenComplete((value, failure) -> completedNanos.set(System.nanoTime()));
    return new RootCall(
        requestId,
        sentAtMs,
        sentAtMs + deadline.toMillis(),
        reply,
        context,
        sentNanos,
        completedNanos);
  }

  @Override
  public void close() throws InterruptedException {
    channels.values().forEach(ManagedChannel::shutdownNow);
    for (ManagedChannel channel : channels.values()) {
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    rootThreads.shutdownNow();
    recorder.checkpoint();
    if (scanner != null) scanner.close();
  }

  /** One in-flight root request. */
  public record RootCall(
      String requestId,
      long sentAtMs,
      long deadlineAtMs,
      CompletableFuture<CallReply> reply,
      Context.CancellableContext context,
      long sentNanos,
      java.util.concurrent.atomic.AtomicLong completedNanos) {
    public long latencyNanos() {
      long finished = completedNanos.get();
      return finished == 0 ? -1 : finished - sentNanos;
    }

    /** Requests cancellation; the cascade reaches every descendant through gRPC Context. */
    public void cancel() {
      context.cancel(Status.CANCELLED.withDescription("measurement ended").asException());
    }

    /** Final status code once the root call has finished, or empty while it is running. */
    public java.util.Optional<Status.Code> outcome() {
      if (!reply.isDone()) {
        return java.util.Optional.empty();
      }
      try {
        reply.join();
        return java.util.Optional.of(Status.Code.OK);
      } catch (java.util.concurrent.CompletionException failure) {
        return java.util.Optional.of(
            failure.getCause() instanceof StatusRuntimeException status
                ? status.getStatus().getCode()
                : Status.Code.UNKNOWN);
      } catch (java.util.concurrent.CancellationException cancelled) {
        return java.util.Optional.of(Status.Code.CANCELLED);
      }
    }
  }
}
