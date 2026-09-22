package dev.spanlease.testbed.feasibility;

import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.BLOCK_BEGIN;
import static dev.spanlease.testbed.feasibility.FeasibilityJournal.Hook.BLOCK_END;

import dev.spanlease.testbed.proto.CallReply;
import dev.spanlease.testbed.proto.CallRequest;
import dev.spanlease.testbed.proto.ChainGrpc;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class FeasibilityBlockingHelper implements AutoCloseable {
  enum CompletionKind {
    RESPONSE,
    LOCAL,
    UNKNOWN
  }

  record CallResult(
      CallReply reply,
      Status status,
      CompletionKind completionKind,
      String responseReference,
      Throwable failure) {}

  static final class CallHandle {
    private final Future<CallResult> future;
    private final CountDownLatch contextReady;
    private final AtomicReference<Context.CancellableContext> context;
    private final FeasibilityClientHooks.CallCapture capture;

    private CallHandle(
        Future<CallResult> future,
        CountDownLatch contextReady,
        AtomicReference<Context.CancellableContext> context,
        FeasibilityClientHooks.CallCapture capture) {
      this.future = future;
      this.contextReady = contextReady;
      this.context = context;
      this.capture = capture;
    }

    CallResult await(Duration timeout)
        throws InterruptedException, ExecutionException, TimeoutException {
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    void cancelLocally(Throwable cause) throws InterruptedException {
      if (!contextReady.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("caller did not create cancellable context");
      }
      capture.localCancellationRequested.set(true);
      context.get().cancel(cause);
    }
  }

  private final FeasibilityJournal journal;
  private final String clientInstance;
  private final ExecutorService callers;

  FeasibilityBlockingHelper(FeasibilityJournal journal, String clientInstance) {
    this.journal = Objects.requireNonNull(journal, "journal");
    if (!FeasibilityMetadata.validInstance(clientInstance)) {
      throw new IllegalArgumentException("clientInstance must be bounded visible ASCII");
    }
    this.clientInstance = clientInstance;
    AtomicInteger threadSequence = new AtomicInteger();
    this.callers =
        Executors.newCachedThreadPool(
            runnable -> new Thread(runnable, "bfeas-caller-" + threadSequence.incrementAndGet()));
  }

  CallHandle start(ChainGrpc.ChainBlockingStub stub, CallRequest request, String invocationId) {
    Objects.requireNonNull(stub, "stub");
    Objects.requireNonNull(request, "request");
    FeasibilityClientHooks.CallCapture capture =
        new FeasibilityClientHooks.CallCapture(invocationId);
    CountDownLatch contextReady = new CountDownLatch(1);
    AtomicReference<Context.CancellableContext> context = new AtomicReference<>();
    Future<CallResult> future =
        callers.submit(
            () -> {
              journal.append(BLOCK_BEGIN, invocationId, "", "", "", "");
              Context.CancellableContext cancellable = Context.current().withCancellation();
              context.set(cancellable);
              contextReady.countDown();
              CallReply reply = null;
              Throwable failure = null;
              Status status = Status.OK;
              try {
                reply =
                    cancellable.call(
                        () ->
                            stub.withInterceptors(
                                    FeasibilityClientHooks.interceptor(
                                        journal, clientInstance, capture))
                                .call(request));
              } catch (StatusRuntimeException rpcFailure) {
                failure = rpcFailure;
                status = rpcFailure.getStatus();
              } finally {
                cancellable.cancel(null);
                journal.append(
                    BLOCK_END,
                    invocationId,
                    "",
                    "",
                    capture.responseReference.get(),
                    status.getCode().name());
              }
              CompletionKind completion =
                  !capture.responseReference.get().isEmpty()
                      ? CompletionKind.RESPONSE
                      : capture.localCancellationRequested.get()
                          ? CompletionKind.LOCAL
                          : CompletionKind.UNKNOWN;
              return new CallResult(
                  reply, status, completion, capture.responseReference.get(), failure);
            });
    return new CallHandle(future, contextReady, context, capture);
  }

  @Override
  public void close() throws InterruptedException {
    callers.shutdownNow();
    if (!callers.awaitTermination(2, TimeUnit.SECONDS)) {
      throw new IllegalStateException("blocking helper callers did not terminate");
    }
  }
}
