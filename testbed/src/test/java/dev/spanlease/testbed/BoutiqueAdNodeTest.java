package dev.spanlease.testbed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.spanlease.common.EventPayload;
import dev.spanlease.common.ObservationEvent;
import dev.spanlease.instrumentation.EventSink;
import dev.spanlease.instrumentation.InstanceRecorder;
import dev.spanlease.instrumentation.RootCaller;
import hipstershop.AdServiceGrpc;
import hipstershop.Demo;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoutiqueAdNodeTest {
  @Test
  void storeCallsSkipDiagnosticLeafWork() throws Exception {
    EventSink sink = ignored -> true;
    HandlerJournal journal = new HandlerJournal(Clock.systemUTC());
    var plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    try (var node =
        BoutiqueAdNode.start(
            "ads-a",
            "ads-a@test",
            1,
            plan.manifest().parameters(),
            sink,
            () -> UUID.randomUUID().toString(),
            new RuntimeDiagnostics(),
            journal,
            true,
            "ads-a",
            4_000,
            new Random(42))) {
      ManagedChannel channel =
          NettyChannelBuilder.forAddress("127.0.0.1", node.port())
              .usePlaintext()
              .disableRetry()
              .build();
      try {
        RootCaller caller =
            new RootCaller(
                new InstanceRecorder("driver@test", sink, Clock.systemUTC(), System::nanoTime),
                () -> UUID.randomUUID().toString());
        var plain = AdServiceGrpc.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS);
        assertThat(caller.call("ads-a", plain, client -> client.getAds(kitchen())).getAdsList())
            .extracting(Demo.Ad::getText)
            .containsExactly(
                "Bamboo glass jar for sale. 10% off.",
                "Mug for sale. Buy two, get third one for free");
        assertThat(journal.entries()).isEmpty();

        var diagnostic =
            AdServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(BoutiqueFaults.headers(0)));
        assertThat(
                caller.call("ads-a", diagnostic, client -> client.getAds(kitchen())).getAdsList())
            .extracting(Demo.Ad::getText)
            .containsExactly(
                "Bamboo glass jar for sale. 10% off.",
                "Mug for sale. Buy two, get third one for free");

        assertThat(journal.entries())
            .extracting(HandlerJournal.Entry::service, HandlerJournal.Entry::boundary)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("ads-a", HandlerJournal.Boundary.RUN),
                org.assertj.core.groups.Tuple.tuple(
                    "ads-a", HandlerJournal.Boundary.HANDLER_RETURN));
        assertThat(journal.entries().getLast().wallMs() - journal.entries().getFirst().wallMs())
            .isGreaterThanOrEqualTo(4_000);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void nativeGetAdsReturnsRealAdsAndRejectsDiagnosticHeadersBeforeAdmission() throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    EventSink sink =
        event -> {
          events.add(event);
          return true;
        };
    var plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    try (var node =
        BoutiqueAdNode.start(
            "ads-a",
            "ads-a@test",
            1,
            plan.manifest().parameters(),
            sink,
            () -> UUID.randomUUID().toString(),
            new RuntimeDiagnostics(),
            new HandlerJournal(Clock.systemUTC()),
            false,
            "",
            0,
            new Random(42))) {
      ManagedChannel channel =
          NettyChannelBuilder.forAddress("127.0.0.1", node.port())
              .usePlaintext()
              .disableRetry()
              .build();
      try {
        var caller =
            new RootCaller(
                new InstanceRecorder("driver@test", sink, Clock.systemUTC(), System::nanoTime),
                () -> UUID.randomUUID().toString());
        var stub = AdServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
        var response = caller.call("ads-a", stub, client -> client.getAds(kitchen()));
        assertThat(response.getAdsList())
            .extracting(Demo.Ad::getText)
            .containsExactly(
                "Bamboo glass jar for sale. 10% off.",
                "Mug for sale. Buy two, get third one for free");
        long acquisitions =
            events.stream()
                .filter(e -> e.payload() instanceof EventPayload.ResourceAcquire)
                .count();
        var invalid =
            stub.withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(BoutiqueFaults.headers(1)));
        assertThatThrownBy(() -> caller.call("ads-a", invalid, client -> client.getAds(kitchen())))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                error ->
                    assertThat(error.getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThat(
                events.stream()
                    .filter(e -> e.payload() instanceof EventPayload.ResourceAcquire)
                    .count())
            .isEqualTo(acquisitions);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void selfCallbackQueuesBeforeAdmissionAndCancellationClosesHelperBeforeRelease()
      throws Exception {
    List<ObservationEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch queued = new CountDownLatch(1), released = new CountDownLatch(1);
    EventSink sink =
        event -> {
          events.add(event);
          if (event.payload() instanceof EventPayload.ResourceWaitBegin) queued.countDown();
          if (event.payload() instanceof EventPayload.ResourceRelease) released.countDown();
          return true;
        };
    var plan = RunPlan.create(Scenario.closedRing(), System.currentTimeMillis());
    try (var node =
        BoutiqueAdNode.start(
            "ads-a",
            "ads-a@test",
            1,
            plan.manifest().parameters(),
            sink,
            () -> UUID.randomUUID().toString(),
            new RuntimeDiagnostics(),
            new HandlerJournal(Clock.systemUTC()),
            true,
            "ads-a",
            0,
            new Random(42))) {
      node.connect(Map.of("ads-a", node.port()));
      var channel =
          NettyChannelBuilder.forAddress("127.0.0.1", node.port())
              .usePlaintext()
              .disableRetry()
              .build();
      Context.CancellableContext context = Context.current().withCancellation();
      try {
        var caller =
            new RootCaller(
                new InstanceRecorder("driver@test", sink, Clock.systemUTC(), System::nanoTime),
                () -> UUID.randomUUID().toString());
        var stub =
            AdServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(BoutiqueFaults.headers(1)));
        var call =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return context.call(
                        () -> caller.call("ads-a", stub, client -> client.getAds(kitchen())));
                  } catch (Exception failure) {
                    throw new RuntimeException(failure);
                  }
                });
        assertThat(queued.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(node.snapshot().waitingCount()).isEqualTo(1);
        assertThat(node.snapshot().ownerByUnit().values()).allMatch(owner -> owner != null);
        assertThat(
                events.stream()
                    .filter(e -> e.payload() instanceof EventPayload.ResourceAcquire)
                    .count())
            .isEqualTo(1);
        context.cancel(new RuntimeException("test operator stop"));
        assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class);
        long blockEnd =
            events.stream()
                .filter(
                    e ->
                        e.instanceId().equals("ads-a@test")
                            && e.payload() instanceof EventPayload.RpcBlockEnd)
                .mapToLong(ObservationEvent::localSeq)
                .min()
                .orElseThrow();
        long end =
            events.stream()
                .filter(
                    e ->
                        e.instanceId().equals("ads-a@test")
                            && e.payload() instanceof EventPayload.ExecutionEnd)
                .mapToLong(ObservationEvent::localSeq)
                .min()
                .orElseThrow();
        long release =
            events.stream()
                .filter(
                    e ->
                        e.instanceId().equals("ads-a@test")
                            && e.payload() instanceof EventPayload.ResourceRelease)
                .mapToLong(ObservationEvent::localSeq)
                .min()
                .orElseThrow();
        assertThat(blockEnd).isLessThan(end);
        assertThat(end).isLessThan(release);
      } finally {
        context.cancel(null);
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private static Demo.AdRequest kitchen() {
    return Demo.AdRequest.newBuilder().addContextKeys("kitchen").build();
  }
}
