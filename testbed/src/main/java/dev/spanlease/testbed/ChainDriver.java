package dev.spanlease.testbed;

import dev.spanlease.common.RunManifest;
import dev.spanlease.instrumentation.EventSink;
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
  private final RootInstrumentation root;
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
    this.root = new RootInstrumentation(instanceId, parameters, sink, invocationIds);
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
    return submit(requestId, hops, java.util.Optional.of(deadline));
  }

  /** Interactive diagnostic run only: no RPC deadline; the operator stops the workload JVM. */
  RootCall submitUntilStopped(String requestId, List<Hop> hops) {
    return submit(requestId, hops, java.util.Optional.empty());
  }

  private RootCall submit(String requestId, List<Hop> hops, java.util.Optional<Duration> deadline) {
    String target = hops.get(0).getTargetService();
    ManagedChannel channel = Objects.requireNonNull(channels.get(target), "unbound " + target);
    CallRequest request =
        CallRequest.newBuilder().setRequestId(requestId).addAllRemaining(hops).build();
    long sentAtMs = clock.millis();
    Deadline rootDeadline =
        deadline.map(value -> Deadline.after(value.toMillis(), TimeUnit.MILLISECONDS)).orElse(null);
    Context.CancellableContext context = Context.current().withCancellation();
    CompletableFuture<CallReply> reply =
        CompletableFuture.supplyAsync(
            () -> {
              Context previous = context.attach();
              try {
                var stub = ChainGrpc.newBlockingStub(channel);
                return root.caller()
                    .call(
                        target,
                        rootDeadline == null ? stub : stub.withDeadline(rootDeadline),
                        client -> client.call(request));
              } finally {
                context.detach(previous);
              }
            },
            rootThreads);
    return new RootCall(
        requestId,
        sentAtMs,
        deadline.map(value -> sentAtMs + value.toMillis()).orElse(null),
        reply,
        context);
  }

  @Override
  public void close() throws InterruptedException {
    channels.values().forEach(ManagedChannel::shutdownNow);
    for (ManagedChannel channel : channels.values()) {
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    rootThreads.shutdownNow();
    root.close();
  }

  /** One in-flight root request. A null deadline is exclusive to the interactive monitor run. */
  public record RootCall(
      String requestId,
      long sentAtMs,
      Long deadlineAtMs,
      CompletableFuture<CallReply> reply,
      Context.CancellableContext context) {
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
