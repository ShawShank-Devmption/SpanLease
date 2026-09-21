# B-FEAS Real gRPC Feasibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce test-only, executable evidence that grpc-java 1.68.1 supports SpanLease's proposed early-arrival, bounded-gate, blocking-helper, response-causality, cancellation, and slot-lifetime hooks.

**Architecture:** Add a feasibility-only package under the testbed test source set. A real ephemeral Netty server uses a stream tracer, response interceptor, separate callback executor, bounded named-slot gate, generated blocking stub, and test-only OTel span processor; deterministic tests assert hook order without defining production v2 APIs.

**Tech Stack:** Java 21, Gradle Kotlin DSL, grpc-java 1.68.1, generated `ChainGrpc` blocking unary stub, OpenTelemetry SDK 1.45.0, JUnit 5, AssertJ, google-java-format.

**Spec:** `docs/superpowers/specs/2026-09-22-b-feas-design.md`

## Global Constraints

- Keep all B-FEAS Java under `testbed/src/test/java/dev/spanlease/testbed/feasibility`.
- Do not modify `common` production types, `instrumentation/src/main`, analyzer code, the frozen proto, v1 replay fixtures, or the proposed v2 contract tables.
- Use the existing dependency catalog; add no version literal and no unreviewed dependency.
- Use a real ephemeral loopback Netty server and the generated blocking unary stub.
- Keep the gRPC callback executor separate from named application-slot executors.
- Use latches, conditions, and bounded futures; never use sleeps as correctness synchronization.
- Treat the journal as test evidence, not OTel detector events or analyzer input.
- Keep request/response metadata creation before transport dispatch/close.
- Cancellation never releases a running slot; actual job end precedes release.
- Unknown completion origin remains explicit when response metadata is absent.
- Format Java with google-java-format through `spotlessApply` and run the affected tests after every task.

## Review Focus

- Malformed or missing request identity must reach neither READY nor gate admission; Task 3 adds a real-RPC rejection test.
- A queued cancellation must remove the job without creating acquire/span/start entries; Task 2 adds the race-independent queued case.
- A handler exception must still record end then release and make the slot reusable; Task 2 adds an exception-path test.
- Duplicate server close attempts must reserve only one response reference; Task 3 adds an interceptor unit/integration assertion.
- Missing response trailers on a remote error must remain UNKNOWN unless the helper itself initiated cancellation; Task 6 tests both origins.

---

## File Structure

### Build file

- Modify `testbed/build.gradle.kts` to add test-only access to the already pinned OTel SDK.

### Test-only support

- Create `FeasibilityJournal.java`: immutable ordered hook evidence and condition-based awaiting.
- Create `FeasibilitySpanProcessor.java`: synchronous OTel application-span start/end evidence.
- Create `FeasibilityApplicationGate.java`: bounded FIFO admission, named slots, cancellation, end/release ordering.
- Create `FeasibilityMetadata.java`: bounded metadata keys, copied request identity, and response-reference validation.
- Create `FeasibilityServerHooks.java`: early stream tracer and once-only response-close wrapper.
- Create `FeasibilityClientHooks.java`: request metadata before dispatch and terminal trailer capture before listener delegation.
- Create `FeasibilityBlockingHelper.java`: one logical blocking interval, generated-stub call, local-cancel handle, conservative completion result.
- Create `FeasibilityChainService.java`: decoded READY boundary, cancellation bridge, gate submission, test application span.
- Create `FeasibilityHarness.java`: real Netty server/channel, separate executors, fixture ownership and cleanup.

### Tests

- Create `FeasibilityJournalTest.java`: ordering, bounded await, and span callback evidence.
- Create `FeasibilityApplicationGateTest.java`: assign/queue/overflow, queued cancel, running cancel, exception cleanup.
- Create `FeasibilityServerHooksTest.java`: request validation, copied context, and once-only response reservation.
- Create `FeasibilityClientHooksTest.java`: send-before-dispatch, trailer-before-delegate, and completion classification.
- Create `FeasibilityGrpcIntegrationTest.java`: early arrival, span boundary, response-before-exit, cancellation, thread separation, completion origin.

### Handoff

- Create `docs/b-feas-handoff.md`: commands, observed hook timeline, supported classifications, limitations, and A/C review request.
- Modify `tasks.md`: record B-FEAS implementation as ready for A/C review, not accepted or complete.
- Modify `docs/dev-b-stage0-readiness.md`: update the B-FEAS checklist only for demonstrated evidence.

---

### Task 1: Ordered Feasibility Journal and Application Span Probe

**Files:**
- Modify: `testbed/build.gradle.kts`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityJournal.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilitySpanProcessor.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityJournalTest.java`

**Interfaces:**
- Produces: `FeasibilityJournal.append(Hook, String, String, String, String, String)`.
- Produces: `FeasibilityJournal.await(Hook, String, Duration)` and `entriesFor(String)`.
- Produces: `FeasibilitySpanProcessor(FeasibilityJournal)` and `INVOCATION_ID_ATTRIBUTE`.
- Consumes: OTel `SpanProcessor`, `ReadWriteSpan`, and `ReadableSpan` from the pinned SDK.

- [ ] **Step 1: Add the pinned test-only OTel SDK dependency**

Add to `testbed/build.gradle.kts` inside `dependencies`:

```kotlin
testImplementation(platform(libs.otel.bom))
testImplementation(libs.otel.sdk)
```

- [ ] **Step 2: Write the failing journal/span tests**

Create `FeasibilityJournalTest.java` with these tests:

```java
@Test
void assignsStrictlyIncreasingOrdinalsAndFiltersByInvocation() {
  FeasibilityJournal journal = new FeasibilityJournal();
  FeasibilityJournal.Entry sent =
      journal.append(Hook.SENT, "inv-a", "", "", "client@probe#1", "");
  FeasibilityJournal.Entry arrival =
      journal.append(Hook.ARRIVAL, "inv-a", "", "", "client@probe#1", "");

  assertThat(arrival.ordinal()).isGreaterThan(sent.ordinal());
  assertThat(journal.entriesFor("inv-a")).containsExactly(sent, arrival);
}

@Test
void awaitReturnsOnlyAfterMatchingEntryIsAppended() throws Exception {
  FeasibilityJournal journal = new FeasibilityJournal();
  ExecutorService executor = Executors.newSingleThreadExecutor();
  try {
    Future<FeasibilityJournal.Entry> awaited =
        executor.submit(() -> journal.await(Hook.READY, "inv-a", Duration.ofSeconds(2)));
    journal.append(Hook.READY, "inv-a", "", "", "", "");
    assertThat(awaited.get(2, TimeUnit.SECONDS).hook()).isEqualTo(Hook.READY);
  } finally {
    executor.shutdownNow();
  }
}

@Test
void spanProcessorRecordsSynchronousStartAndEnd() {
  FeasibilityJournal journal = new FeasibilityJournal();
  FeasibilitySpanProcessor processor = new FeasibilitySpanProcessor(journal);
  try (SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
    Span span =
        provider
            .get("b-feas")
            .spanBuilder("application")
            .setAttribute(FeasibilitySpanProcessor.INVOCATION_ID_ATTRIBUTE, "inv-a")
            .startSpan();
    span.end();
  }
  assertThat(journal.entriesFor("inv-a"))
      .extracting(FeasibilityJournal.Entry::hook)
      .containsExactly(Hook.APPLICATION_SPAN_START, Hook.APPLICATION_SPAN_END);
}
```

- [ ] **Step 3: Run the tests to verify the missing types fail compilation**

Run:

```bash
./gradlew :testbed:test --tests '*FeasibilityJournalTest'
```

Expected: FAIL because `FeasibilityJournal` and `FeasibilitySpanProcessor` do not exist.

- [ ] **Step 4: Implement the journal API**

Create a package-private final `FeasibilityJournal` with:

```java
enum Hook {
  BLOCK_BEGIN,
  SENT,
  ARRIVAL,
  INVALID_ARRIVAL,
  READY,
  QUEUED,
  ACQUIRE,
  APPLICATION_SPAN_START,
  APPLICATION_SPAN_END,
  RESPONSE_RESERVED,
  RESPONSE_OBSERVED,
  BLOCK_END,
  CANCEL_REQUEST,
  REJECTED,
  EXECUTION_END,
  RELEASE
}

record Entry(
    long ordinal,
    Hook hook,
    String invocationId,
    String executionId,
    String slotId,
    String reference,
    String outcome,
    String threadName) {}

Entry append(
    Hook hook,
    String invocationId,
    String executionId,
    String slotId,
    String reference,
    String outcome)

Entry await(Hook hook, String invocationId, Duration timeout)
    throws InterruptedException, TimeoutException

List<Entry> entriesFor(String invocationId)
List<Entry> snapshot()
```

Use one `ReentrantLock`, one `Condition`, a `long nextOrdinal`, and an `ArrayList<Entry>`.
Allocate, append, and signal under the lock. Return immutable `List.copyOf` snapshots.
`await` repeatedly checks the predicate and uses `awaitNanos`; it throws
`TimeoutException` when the remaining duration reaches zero.

- [ ] **Step 5: Implement the synchronous span processor**

Create `FeasibilitySpanProcessor` implementing `SpanProcessor`:

```java
static final AttributeKey<String> INVOCATION_ID_ATTRIBUTE =
    AttributeKey.stringKey("bfeas.invocation_id");

@Override
public void onStart(Context parentContext, ReadWriteSpan span) {
  String invocationId = span.getAttribute(INVOCATION_ID_ATTRIBUTE);
  journal.append(Hook.APPLICATION_SPAN_START, invocationId, "", "", "", "");
}

@Override
public void onEnd(ReadableSpan span) {
  String invocationId = span.getAttribute(INVOCATION_ID_ATTRIBUTE);
  journal.append(Hook.APPLICATION_SPAN_END, invocationId, "", "", "", "");
}

@Override
public boolean isStartRequired() {
  return true;
}

@Override
public boolean isEndRequired() {
  return true;
}
```

Reject a null/blank span invocation ID with `IllegalStateException`; the probe must not
create unattributed application-span evidence.

- [ ] **Step 6: Format and run the focused tests**

Run:

```bash
./gradlew spotlessApply :testbed:test --tests '*FeasibilityJournalTest'
```

Expected: PASS, three tests.

- [ ] **Step 7: Commit Task 1**

```bash
git add testbed/build.gradle.kts testbed/src/test/java/dev/spanlease/testbed/feasibility
git commit -m "test: add B-FEAS hook journal and span probe"
```

---

### Task 2: Bounded Named-Slot Application Gate

**Files:**
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityApplicationGate.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityApplicationGateTest.java`

**Interfaces:**
- Consumes: `FeasibilityJournal` from Task 1.
- Produces: `Admission submit(String, Job)`.
- Produces: `CancelResult cancel(String)`.
- Produces: `JobState state(String)` and `boolean ownsSlot(String)` for assertions.
- Produces: `Execution(executionId, slotId, CancellationToken)` passed to application jobs.

- [ ] **Step 1: Write failing gate tests for assign, queue, and overflow**

Use `CountDownLatch running`, `CountDownLatch finish`, and a gate with one slot and one
waiting position:

```java
assertThat(gate.submit("inv-1", execution -> {
  running.countDown();
  assertThat(finish.await(2, TimeUnit.SECONDS)).isTrue();
})).isEqualTo(Admission.ASSIGNED);
assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
assertThat(gate.submit("inv-2", execution -> {})).isEqualTo(Admission.QUEUED);
assertThat(gate.submit("inv-3", execution -> {})).isEqualTo(Admission.REJECTED);
assertThat(gate.state("inv-2")).isEqualTo(JobState.QUEUED);
assertThat(gate.state("inv-3")).isEqualTo(JobState.REJECTED);
```

Assert the journal has ACQUIRE for `inv-1`, QUEUED for `inv-2`, REJECTED for `inv-3`,
and no ACQUIRE for `inv-2` until `finish.countDown()`.

- [ ] **Step 2: Write failing queued/running cancellation tests**

Add two tests:

```java
assertThat(gate.cancel("inv-2")).isEqualTo(CancelResult.QUEUED_REMOVED);
assertThat(gate.state("inv-2")).isEqualTo(JobState.CANCELLED);
assertThat(journal.entriesFor("inv-2"))
    .extracting(FeasibilityJournal.Entry::hook)
    .doesNotContain(Hook.ACQUIRE, Hook.APPLICATION_SPAN_START);
```

and, for the running job:

```java
assertThat(gate.cancel("inv-1")).isEqualTo(CancelResult.RUNNING_SIGNALLED);
assertThat(gate.ownsSlot("inv-1")).isTrue();
assertThat(tokenSeenByJob.requested()).isTrue();
```

Release the job, await RELEASE, and then assert ownership is false.

- [ ] **Step 3: Write a failing exception cleanup test**

Submit a job that throws `new IllegalStateException("probe")`. Await RELEASE and assert:

```java
assertThat(hooksFor(journal, "inv-1"))
    .containsSubsequence(Hook.ACQUIRE, Hook.EXECUTION_END, Hook.RELEASE);
assertThat(gate.submit("inv-2", execution -> secondRan.countDown()))
    .isEqualTo(Admission.ASSIGNED);
assertThat(secondRan.await(2, TimeUnit.SECONDS)).isTrue();
```

- [ ] **Step 4: Run the gate tests to verify failure**

Run:

```bash
./gradlew :testbed:test --tests '*FeasibilityApplicationGateTest'
```

Expected: FAIL because `FeasibilityApplicationGate` does not exist.

- [ ] **Step 5: Implement the gate types and constructor**

Create these nested types in `FeasibilityApplicationGate`:

```java
enum Admission { ASSIGNED, QUEUED, REJECTED }
enum CancelResult { QUEUED_REMOVED, RUNNING_SIGNALLED, NOT_FOUND }
enum JobState { QUEUED, ASSIGNED, RUNNING, CANCEL_REQUESTED, CANCELLED, ENDED, RELEASED, REJECTED }

@FunctionalInterface
interface Job {
  void run(Execution execution) throws Exception;
}

record Execution(String executionId, String slotId, CancellationToken cancellation) {}

static final class CancellationToken {
  private final AtomicBoolean requested = new AtomicBoolean();
  boolean requested() { return requested.get(); }
}
```

Constructor:

```java
FeasibilityApplicationGate(
    String gateName, int slotCount, int waitingCapacity, FeasibilityJournal journal)
```

Validate nonblank name, positive slots, and positive waiting capacity. Create one
single-thread executor per slot with thread names `bfeas-app-<gate>-slot-<index>`.

- [ ] **Step 6: Implement serialized admission, cancellation, and release**

Use one `ReentrantLock`, an `ArrayDeque<Pending>`, an ordered free-slot collection, and
a map from invocation ID to job state. Under the lock:

- reject duplicate invocation IDs;
- choose a free slot or enqueue;
- reject overflow without execution identity;
- remove queued cancellation without acquire;
- set a running token on cancellation without freeing the slot; and
- on actual job exit, record EXECUTION_END, record RELEASE, free the slot, and assign
  the next queued job.

Never run the job or an executor submission while holding the state lock. Prepare an
`Assignment` under the lock, unlock, and then submit it to the named slot executor.
Use deterministic test execution IDs `bfeas-execution-<counter>`.

- [ ] **Step 7: Run gate tests and the whole testbed suite**

Run:

```bash
./gradlew spotlessApply :testbed:test --tests '*FeasibilityApplicationGateTest'
./gradlew :testbed:test
```

Expected: all gate tests and existing testbed tests PASS.

- [ ] **Step 8: Commit Task 2**

```bash
git add testbed/src/test/java/dev/spanlease/testbed/feasibility
git commit -m "test: add B-FEAS bounded application gate"
```

---

### Task 3: Request Metadata, Early Arrival, and Once-Only Response Hooks

**Files:**
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityMetadata.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityServerHooks.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityServerHooksTest.java`

**Interfaces:**
- Consumes: Task 1 journal.
- Produces: exact request keys `sl-invocation-id`, `sl-caller-instance`, `sl-caller-seq`.
- Produces: exact response keys `sl-response-instance`, `sl-response-seq`.
- Produces: `Context.Key<RequestIdentity> REQUEST_IDENTITY`.
- Produces: `ServerStreamTracer.Factory arrivalTracerFactory(...)`.
- Produces: `ServerInterceptor responseInterceptor(...)`.

- [ ] **Step 1: Write failing metadata validation tests**

Test UUID-shaped invocation IDs, nonblank bounded instance IDs, and positive sequence
numbers:

```java
Metadata headers = new Metadata();
headers.put(INVOCATION_ID, "11111111-1111-4111-8111-111111111111");
headers.put(CALLER_INSTANCE, "client@probe");
headers.put(CALLER_SEQ, "7");
assertThat(FeasibilityMetadata.requestIdentity(headers).orElseThrow().sentReference())
    .isEqualTo("client@probe#7");
```

Assert blank invocation, overlong instance, nonnumeric sequence, zero sequence, or one
missing field returns `Optional.empty()`.

- [ ] **Step 2: Write failing stream-tracer context tests**

Create a factory, call `newServerStreamTracer`, then `filterContext(Context.ROOT)`.
Assert valid copied values appear through `REQUEST_IDENTITY.get(filteredContext)` and
that later mutation of the original `Metadata` does not change them. Assert malformed
headers record INVALID_ARRIVAL and provide no request identity.

- [ ] **Step 3: Write a failing once-only response-close test**

Use a minimal fake `ServerCall` whose `close` captures trailers. Invoke the wrapped
call's `close` twice and assert:

```java
assertThat(journal.entriesFor(INVOCATION))
    .extracting(FeasibilityJournal.Entry::hook)
    .containsOnlyOnce(Hook.RESPONSE_RESERVED);
assertThat(firstTrailers.get(RESPONSE_INSTANCE)).isEqualTo("server@probe");
assertThat(Long.parseLong(firstTrailers.get(RESPONSE_SEQ))).isPositive();
```

The second delegate close may be recorded by the fake call, but it must not allocate a
second response reference.

- [ ] **Step 4: Run the tests to verify failure**

Run:

```bash
./gradlew :testbed:test --tests '*FeasibilityServerHooksTest'
```

Expected: FAIL because the metadata and server hook types do not exist.

- [ ] **Step 5: Implement bounded copied metadata**

Create `FeasibilityMetadata` with:

```java
static final Metadata.Key<String> INVOCATION_ID =
    Metadata.Key.of("sl-invocation-id", Metadata.ASCII_STRING_MARSHALLER);
static final Metadata.Key<String> CALLER_INSTANCE =
    Metadata.Key.of("sl-caller-instance", Metadata.ASCII_STRING_MARSHALLER);
static final Metadata.Key<String> CALLER_SEQ =
    Metadata.Key.of("sl-caller-seq", Metadata.ASCII_STRING_MARSHALLER);
static final Metadata.Key<String> RESPONSE_INSTANCE =
    Metadata.Key.of("sl-response-instance", Metadata.ASCII_STRING_MARSHALLER);
static final Metadata.Key<String> RESPONSE_SEQ =
    Metadata.Key.of("sl-response-seq", Metadata.ASCII_STRING_MARSHALLER);
static final Context.Key<RequestIdentity> REQUEST_IDENTITY =
    Context.key("bfeas-request-identity");

record RequestIdentity(String invocationId, String sentReference) {}
```

Limit identity strings to 256 ASCII characters, parse invocation IDs with
`UUID.fromString`, and require positive decimal sequence values.

- [ ] **Step 6: Implement the early arrival factory**

`newServerStreamTracer` copies and validates the three request header strings inside
the factory method. It appends ARRIVAL before returning a tracer. The tracer overrides:

```java
@Override
public Context filterContext(Context context) {
  return identity == null
      ? context
      : context.withValue(FeasibilityMetadata.REQUEST_IDENTITY, identity);
}
```

Do not retain the `Metadata` object. Malformed identity appends INVALID_ARRIVAL with an
empty invocation ID and returns a tracer that leaves the context unchanged.

- [ ] **Step 7: Implement the response-close interceptor**

Wrap `ServerCall.close(Status, Metadata)` using
`ForwardingServerCall.SimpleForwardingServerCall`. Read the request identity from the
filtered current context when `interceptCall` begins. Use `AtomicBoolean` to reserve
once; append RESPONSE_RESERVED before mutating trailers and delegating close. Put
`serverInstance` and the positive journal ordinal in the two response trailer fields.

- [ ] **Step 8: Format and run focused/all testbed tests**

Run:

```bash
./gradlew spotlessApply :testbed:test --tests '*FeasibilityServerHooksTest'
./gradlew :testbed:test
```

Expected: all tests PASS.

- [ ] **Step 9: Commit Task 3**

```bash
git add testbed/src/test/java/dev/spanlease/testbed/feasibility
git commit -m "test: prove B-FEAS server hook ordering"
```

---

### Task 4: Client Send/Trailer Hooks and Blocking Helper

**Files:**
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityClientHooks.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityBlockingHelper.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityClientHooksTest.java`

**Interfaces:**
- Consumes: journal and metadata from Tasks 1 and 3.
- Produces: `CallCapture` holding request identity, response reference, status, and local-cancel flag.
- Produces: `ClientInterceptor interceptor(CallCapture)`.
- Produces: `CallHandle start(ChainBlockingStub, CallRequest, String)`.
- Produces: `CallResult await(Duration)` and `void cancelLocally(Throwable)`.

- [ ] **Step 1: Write a failing client interceptor ordering test**

Use a fake `Channel`/`ClientCall` that records headers passed to `start`. Assert the
interceptor appends SENT and populates request metadata before delegate `start`:

```java
assertThat(delegateSawHeaders.get(INVOCATION_ID)).isEqualTo(INVOCATION);
assertThat(delegateSawHeaders.get(CALLER_INSTANCE)).isEqualTo("client@probe");
assertThat(Long.parseLong(delegateSawHeaders.get(CALLER_SEQ))).isPositive();
assertThat(journal.entriesFor(INVOCATION).getFirst().hook()).isEqualTo(Hook.SENT);
```

- [ ] **Step 2: Write a failing trailer-before-delegate test**

Have the fake client call invoke the wrapped listener's `onClose` with valid response
trailers. The delegate listener inspects the capture during its own `onClose`; assert
the response reference and status are already stored and RESPONSE_OBSERVED is already
in the journal.

- [ ] **Step 3: Write failing blocking-helper completion tests**

Cover these pure/helper-controlled outcomes:

```java
assertThat(responseResult.completionKind()).isEqualTo(CompletionKind.RESPONSE);
assertThat(remoteErrorWithoutTrailers.completionKind()).isEqualTo(CompletionKind.UNKNOWN);
assertThat(explicitlyCancelledHandle.await(Duration.ofSeconds(2)).completionKind())
    .isEqualTo(CompletionKind.LOCAL);
```

For every result, assert exactly one BLOCK_BEGIN and one BLOCK_END for the invocation.

- [ ] **Step 4: Run focused tests to verify failure**

Run:

```bash
./gradlew :testbed:test --tests '*FeasibilityClientHooksTest'
```

Expected: FAIL because client hooks/helper do not exist.

- [ ] **Step 5: Implement `CallCapture` and client interceptor**

`CallCapture` stores:

```java
final String invocationId;
final AtomicReference<String> sentReference;
final AtomicReference<String> responseReference;
final AtomicReference<Status> terminalStatus;
final AtomicBoolean localCancellationRequested;
```

In `ClientCall.start`, append SENT, derive `clientInstance#ordinal`, populate the three
request headers, and then delegate. Wrap the listener with
`ForwardingClientCallListener.SimpleForwardingClientCallListener`; in `onClose`, copy
and validate response trailers, append RESPONSE_OBSERVED when valid, store status, and
only then delegate.

- [ ] **Step 6: Implement the generated-stub blocking helper**

Create:

```java
enum CompletionKind { RESPONSE, LOCAL, UNKNOWN }

record CallResult(
    CallReply reply,
    Status status,
    CompletionKind completionKind,
    String responseReference,
    Throwable failure) {}

final class CallHandle {
  CallResult await(Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException;
  void cancelLocally(Throwable cause);
}

CallHandle start(
    ChainGrpc.ChainBlockingStub stub, CallRequest request, String invocationId)
```

Use a dedicated test caller executor. The caller task appends BLOCK_BEGIN, creates a
`Context.CancellableContext`, invokes the generated blocking stub with the per-call
interceptor, catches `StatusRuntimeException`, classifies completion, and appends exactly
one BLOCK_END in `finally`. `cancelLocally` sets the local-cancel flag before calling
`CancellableContext.cancel(cause)`. Classification precedence is RESPONSE when valid
trailers were observed, otherwise LOCAL when this helper initiated cancellation,
otherwise UNKNOWN.

- [ ] **Step 7: Run client tests and the testbed suite**

Run:

```bash
./gradlew spotlessApply :testbed:test --tests '*FeasibilityClientHooksTest'
./gradlew :testbed:test
```

Expected: all tests PASS.

- [ ] **Step 8: Commit Task 4**

```bash
git add testbed/src/test/java/dev/spanlease/testbed/feasibility
git commit -m "test: add B-FEAS blocking client hooks"
```

---

### Task 5: Real Server Harness and Admission Integration

**Files:**
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityChainService.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityHarness.java`
- Create: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityGrpcIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1-4 support types.
- Produces: `HandlerBehavior.handle(CallRequest, StreamObserver<CallReply>, Execution)`.
- Produces: `FeasibilityHarness.start(int slots, int queueCapacity, HandlerBehavior behavior, boolean responseMetadata)`.
- Produces: accessors for journal, blocking helper, stub, gate, callback thread names, and tracer provider.

- [ ] **Step 1: Write the failing early-arrival/saturated-queue integration test**

Start a one-slot harness. The first behavior invocation counts down `firstRunning` and
awaits `releaseFirst`. Start a second client call while the first owns the slot. Assert:

```java
Entry arrival = journal.await(Hook.ARRIVAL, secondId, Duration.ofSeconds(2));
Entry ready = journal.await(Hook.READY, secondId, Duration.ofSeconds(2));
Entry queued = journal.await(Hook.QUEUED, secondId, Duration.ofSeconds(2));
assertThat(arrival.ordinal()).isLessThan(ready.ordinal());
assertThat(ready.ordinal()).isLessThan(queued.ordinal());
assertThat(journal.entriesFor(secondId))
    .extracting(Entry::hook)
    .doesNotContain(Hook.ACQUIRE, Hook.APPLICATION_SPAN_START);
```

Release the first job, then assert ACQUIRE precedes APPLICATION_SPAN_START for the
second invocation.

- [ ] **Step 2: Write the failing real-RPC overflow and malformed-identity tests**

With one slot/one waiting position, hold the first, queue the second, and start the
third. Assert the third returns `RESOURCE_EXHAUSTED`, has REJECTED, and has no ACQUIRE.

Create a raw stub/channel interceptor omitting one required request metadata field.
Assert the RPC returns `INVALID_ARGUMENT`, journal contains INVALID_ARRIVAL, and the
invocation never reaches READY or the gate.

- [ ] **Step 3: Run integration tests to verify missing harness failure**

Run:

```bash
./gradlew :testbed:test --tests '*FeasibilityGrpcIntegrationTest'
```

Expected: FAIL because the service and harness do not exist.

- [ ] **Step 4: Implement the service adapter**

`FeasibilityChainService` extends `ChainGrpc.ChainImplBase`. In `call`:

1. read `REQUEST_IDENTITY` from the current context;
2. reject missing identity with `Status.INVALID_ARGUMENT` before READY;
3. validate the request has a nonempty matching head hop;
4. append READY;
5. cast the observer to `ServerCallStreamObserver<CallReply>`;
6. install `setOnCancelHandler(() -> gate.cancel(invocationId))` before returning;
7. submit one job to the gate; and
8. on REJECTED call `observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException())`.

Inside the admitted job, start an SDK span with `INVOCATION_ID_ATTRIBUTE`, make it
current, invoke `HandlerBehavior`, and end it in `finally`. Do not block the callback
thread while the job runs.

- [ ] **Step 5: Implement the real fixture harness**

The harness owns and closes:

- `FeasibilityJournal`;
- `FeasibilityApplicationGate`;
- fixed callback executor named `bfeas-grpc-callback-<index>`;
- `SdkTracerProvider` with `FeasibilitySpanProcessor`;
- Netty server bound to `127.0.0.1:0`, with `addStreamTracerFactory`, optional response
  interceptor, and the generated service;
- Netty channel with plaintext and `disableRetry()`; and
- `FeasibilityBlockingHelper` plus its caller executor.

Expose `close()` that cancels calls, shuts down channel/server/gate/executors, and uses
bounded `awaitTermination`. Cleanup failure throws an assertion-visible exception.

- [ ] **Step 6: Run and format integration tests**

Run:

```bash
./gradlew spotlessApply :testbed:test --tests '*FeasibilityGrpcIntegrationTest'
./gradlew :testbed:test
```

Expected: arrival/queue/overflow/malformed identity tests PASS with existing bootstrap tests.

- [ ] **Step 7: Commit Task 5**

```bash
git add testbed/src/test/java/dev/spanlease/testbed/feasibility
git commit -m "test: integrate B-FEAS gate with real grpc"
```

---

### Task 6: Response, Exit, Cancellation, and Completion-Origin Evidence

**Files:**
- Modify: `testbed/src/test/java/dev/spanlease/testbed/feasibility/FeasibilityGrpcIntegrationTest.java`
- Modify only if evidence requires a correction: test-only support files from Tasks 1-5.

**Interfaces:**
- Consumes: complete harness and hook APIs.
- Produces: executable B-FEAS acceptance scenarios.

- [ ] **Step 1: Add the failing response-before-exit test**

Use a behavior that sends `onNext`/`onCompleted`, counts down `responseClosed`, and then
waits on `allowExit`. Assert the client handle completes with RESPONSE and a nonblank
response reference while:

```java
assertThat(gate.ownsSlot(invocationId)).isTrue();
assertThat(hooksFor(journal, invocationId)).doesNotContain(Hook.EXECUTION_END, Hook.RELEASE);
```

After `allowExit.countDown()`, await RELEASE and assert the order:

```text
RESPONSE_RESERVED < RESPONSE_OBSERVED < BLOCK_END < EXECUTION_END < RELEASE
```

Do not require client/server journal ordinals as a distributed clock; these entries are
within one loopback-process test journal and represent explicit hook calls.

- [ ] **Step 2: Add the failing running-cancellation/thread-separation test**

The behavior records its application thread, counts down `applicationRunning`, and
waits until its cancellation token is requested and `allowExit` is released. Invoke
`handle.cancelLocally(new CancellationException("probe"))`. Assert:

- CANCEL_REQUEST appears while the gate still owns the slot;
- the server cancellation callback thread starts with `bfeas-grpc-callback-`;
- the application thread starts with `bfeas-app-` and differs from the callback thread;
- the call result is LOCAL only because the helper initiated cancellation; and
- EXECUTION_END then RELEASE occur only after `allowExit`.

- [ ] **Step 3: Add the failing unknown-remote-origin test**

Start a harness with response metadata disabled and a behavior that terminates with
`Status.INTERNAL`. Assert:

```java
assertThat(result.status().getCode()).isEqualTo(Status.Code.INTERNAL);
assertThat(result.responseReference()).isEmpty();
assertThat(result.completionKind()).isEqualTo(CompletionKind.UNKNOWN);
```

This is a diagnostic unsupported endpoint used only to prove the helper does not
fabricate local completion from absent trailers.

- [ ] **Step 4: Run new tests to expose incorrect ordering/classification**

Run each new method by its exact JUnit name with:

```bash
./gradlew :testbed:test --tests '*FeasibilityGrpcIntegrationTest.responseCanPrecedeExitAndRelease'
./gradlew :testbed:test --tests '*FeasibilityGrpcIntegrationTest.cancellationDoesNotReleaseAndCallbacksRemainIndependent'
./gradlew :testbed:test --tests '*FeasibilityGrpcIntegrationTest.missingRemoteTrailersRemainUnknown'
```

Expected: each new test either FAILS against an uncovered behavior or PASSES because the
earlier minimal implementation already supplies that behavior. Record the first-run
result for each method; a first-run PASS is acceptable only after checking that the
assertion exercises the intended real callback boundary rather than a test double.

- [ ] **Step 5: Make the smallest test-only corrections required by observed behavior**

Permitted corrections are limited to:

- moving journal append points to the actual grpc-java callback boundary;
- copying metadata before listener/call delegation;
- making response reservation once-only;
- keeping cancellation as a token/state transition until application return; and
- changing an asserted positive classification to UNKNOWN when the API cannot prove origin.

If a required hook cannot be demonstrated, do not add a fake signal or disable the
counterexample. Keep the focused test failure and command output as evidence, document
the infeasibility in the handoff, and stop before B-CONTRACT without claiming Task 6
or B-FEAS complete.

- [ ] **Step 6: Run all feasibility and testbed tests repeatedly without sleeps**

Run:

```bash
./gradlew spotlessApply :testbed:test --tests '*feasibility*'
./gradlew :testbed:test --tests '*feasibility*'
./gradlew :testbed:test --tests '*feasibility*'
./gradlew :testbed:test
```

Expected: all supported feasibility cases PASS on three consecutive executions; no test
contains `Thread.sleep`.

- [ ] **Step 7: Commit Task 6**

```bash
git add testbed/src/test/java/dev/spanlease/testbed/feasibility
git commit -m "test: demonstrate B-FEAS lifecycle races"
```

---

### Task 7: B-FEAS Handoff and Full Verification

**Files:**
- Create: `docs/b-feas-handoff.md`
- Modify: `tasks.md`
- Modify: `docs/dev-b-stage0-readiness.md`

**Interfaces:**
- Consumes: actual passing tests and observed journal timelines from Tasks 1-6.
- Produces: reviewable runtime-semantics handoff for Dev A, Dev C, B-CONTRACT, and G0.

- [ ] **Step 1: Run the complete verification suite under Java 21**

Run:

```bash
./gradlew --no-daemon clean build
./gradlew --no-daemon :analyzer:test
./gradlew --no-daemon :testbed:test
git diff --check
```

Expected: all commands PASS. Record the exact task/test counts from this run; do not copy
counts from A-BOOT or an earlier run.

- [ ] **Step 2: Extract the observed hook timelines from test evidence**

For each integration scenario, record the asserted partial order, thread roles, and
completion classification. The handoff must include at least:

```text
successful response:
BLOCK_BEGIN -> SENT -> ARRIVAL -> READY -> ACQUIRE/QUEUED -> SPAN_START
-> RESPONSE_RESERVED -> RESPONSE_OBSERVED -> BLOCK_END
-> SPAN_END -> EXECUTION_END -> RELEASE

running cancellation:
ACQUIRE -> SPAN_START -> CANCEL_REQUEST
-> BLOCK_END(local only when helper initiated) -> SPAN_END
-> EXECUTION_END -> RELEASE
```

Adjust the written order only to match the passing assertions and explain any events
that are intentionally unordered.

- [ ] **Step 3: Write `docs/b-feas-handoff.md`**

Use these sections with concrete results:

```markdown
# B-FEAS Runtime Semantics Handoff

Status: implementation evidence ready for A/C review; not yet accepted.

## Environment and commands
## Test-only architecture
## Observed hook lifetimes
## Queue/admission evidence
## Response-before-exit evidence
## Cancellation and thread-separation evidence
## Completion-origin classification
## Verification results
## Limitations and unresolved questions
## A/C acceptance checklist
```

Name the tested commit, Java/Gradle/grpc-java versions, exact commands, pass/fail counts,
and all unsupported cases. State explicitly that the fixture is not production v2
instrumentation and no correctness/timing guarantee follows.

- [ ] **Step 4: Update task/readiness status conservatively**

In `tasks.md`, add a B-FEAS artifact-status bullet saying implementation evidence is
ready for A/C review, with the handoff path and the exact Task 6 commit returned by
`git rev-parse --short HEAD`. Do not say B-FEAS is accepted until A/C review records it.

In `docs/dev-b-stage0-readiness.md`, check only acceptance items directly demonstrated
by passing tests and link to `docs/b-feas-handoff.md`. Leave unsupported or unreviewed
items unchecked with a written reason.

- [ ] **Step 5: Verify documentation and scope**

Run:

```bash
git diff --check
rg -n 'TODO|TBD|FIXME' docs/b-feas-handoff.md tasks.md docs/dev-b-stage0-readiness.md
git diff --name-status 5e9cbbf..HEAD
git status --short
```

Expected: the marker scan has no matches; the implementation contains only the planned
testbed test files, test dependency change, handoff, and status documentation.

- [ ] **Step 6: Commit the handoff**

```bash
git add docs/b-feas-handoff.md docs/dev-b-stage0-readiness.md tasks.md
git commit -m "docs: hand off B-FEAS runtime evidence"
```

- [ ] **Step 7: Push and request A/C review**

```bash
git push origin Jobin
```

Report the commit range, verification commands/results, contract impact (`none`),
invariants demonstrated (I2, I3, I5 boundaries only, not production proofs), and the
remaining dependencies B-CONTRACT→C-MODEL/G0.
