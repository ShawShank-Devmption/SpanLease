# B-FEAS Real gRPC Feasibility Design

Date: 2026-09-22

Owner: Dev B (Jobin)

Task: B-FEAS

Status: design for review; no implementation or feasibility result yet

## 1. Objective

B-FEAS must determine, against the pinned grpc-java version, whether the supported
blocking-unary deployment can:

- capture invocation identity at transport arrival before application admission;
- map one admitted handler job to one named application worker slot;
- expose a decoded ready request as queued before an application span or execution;
- surround a generated blocking-stub call with one logical block interval;
- propagate exact request and response causal references;
- keep cancellation, response, helper return, actual task exit, and slot release
  distinct; and
- keep blocking application work off gRPC callback threads.

The output is executable feasibility evidence and a hook-lifetime handoff to Dev A and
Dev C. It is not a production event contract, gate, exporter, analyzer, or G0 approval.

## 2. Constraints

- Use Java 21, the existing Gradle build, grpc-java 1.68.1, and the frozen generated
  `ChainGrpc` blocking unary stub.
- Keep the prototype under test sources. Do not create production v2 DTOs or public
  instrumentation APIs before G0.
- Use a real ephemeral loopback Netty gRPC server and channel.
- Separate the gRPC callback executor from the bounded application workers.
- Use latches, barriers, or bounded futures for synchronization; never use sleeps as
  correctness coordination.
- Do not add dependencies unless the existing pinned stack cannot perform the probe.
- Record observed behavior and limitations. A negative feasibility finding is a valid
  result and must not be hidden behind a guessed abstraction.

## 3. Considered structures

### 3.1 Testbed test-only fixture — selected

Place the prototype in `testbed/src/test/java/dev/spanlease/testbed/feasibility`.
This location can use the frozen generated stub and real test server without reversing
module dependencies or exposing spike types as production APIs.

### 3.2 Custom feasibility source set — rejected

A separate source set would isolate the prototype but add Gradle configuration solely
for one Stage 0 task. It offers no evidence advantage over a test-only package.

### 3.3 Instrumentation production package — rejected

Putting the prototype in `instrumentation/src/main` would make unapproved interfaces
look reusable before B-CONTRACT and G0. That conflicts with the task boundary even if
the package name contains `feasibility`.

## 4. Architecture

The fixture has six test-only components with narrow responsibilities.

### 4.1 Feasibility journal

The journal records immutable entries containing:

- a test-local ordinal;
- hook/transition name;
- invocation, execution, and slot labels when available;
- current thread name and declared thread role; and
- optional causal reference or outcome.

It is not a v2 event stream. Names may resemble lifecycle concepts for readability,
but it does not allocate `sl.local_seq`, encode OTel records, or act as analyzer input.
The journal exists only to assert hook ordering and produce the handoff timeline.

### 4.2 Bounded application gate

The test gate owns:

- a short state lock;
- a fixed list of named slots;
- one single-thread application executor per slot;
- a bounded FIFO of decoded ready jobs; and
- per-job state for queued, assigned, running, cancellation requested, ended, and
  released.

Submission atomically chooses a free slot or enqueues the job. Queue overflow rejects
without creating an execution or owning a slot. Assignment reserves the slot and
records acquire before scheduling the job. The job's `finally` path records actual end,
then releases the slot and atomically assigns the next queued job.

Queued cancellation removes an unassigned job. Running cancellation records the
request and signals a cooperative cancellation handle but cannot release the slot.
The prototype need not implement production sequence allocation, checkpoints, leases,
or telemetry publication.

### 4.3 Early server arrival hook

A `ServerStreamTracer.Factory` receives the full method name and request headers. It:

1. copies the bounded invocation and sent-reference metadata values;
2. records transport arrival on the callback side; and
3. returns a tracer whose filtered gRPC `Context` carries only the copied immutable
   values to the unary adapter.

An ordinary `ServerInterceptor` is not used to claim pre-dispatch arrival. The probe
must record the actual callback/thread behavior of the stream tracer and context
propagation on grpc-java 1.68.1.

### 4.4 Unary adapter and cancellation bridge

The generated unary service method reads the copied context, validates/decodes the
request, records READY, installs a cancellation callback on the server call observer,
submits one application job to the gate, and returns promptly.

Application work executes only on a named gate-slot thread. As the first application
action after acquire, the job starts a test-only OTel SDK application span; a minimal
test span processor records start/end in the feasibility journal. The response observer
may be completed from that worker. The cancellation callback calls the gate's
cancellation handle; it does not execute application cleanup or release a slot.

### 4.5 Client blocking helper and request metadata

The test helper enforces one outstanding call for its test execution and performs:

1. open logical block state;
2. allocate a deterministic test invocation identity;
3. invoke the generated blocking stub through a per-call client interceptor; and
4. close the block exactly once in `finally` with the observed completion evidence.

The client interceptor records the sent marker and writes invocation/sent-reference
metadata before delegating `ClientCall.start`. It wraps the response listener and copies
terminal trailers before delegating `onClose`, so the blocking helper can test whether
the response reference is available before it returns or throws.

### 4.6 Server response-close wrapper

A server interceptor wraps `ServerCall.close`. On the first terminal close it:

1. reserves a test response reference;
2. records the response-close hook;
3. inserts the reference into the terminal trailers; and
4. delegates close.

Later close attempts do not reserve another reference. A close failure after reservation
does not prove client receipt; only the client listener's observed trailers establish
that relation.

## 5. End-to-end data flow

```text
blocking helper opens logical block
  -> client interceptor records sent and attaches request metadata
  -> ServerStreamTracer.Factory copies metadata and records arrival
  -> generated unary adapter records decoded READY
  -> gate records QUEUED or ACQUIRE
  -> acquired application job starts on named slot thread
  -> handler performs work or generated blocking downstream call
  -> response wrapper reserves response reference and delegates close
  -> client listener copies terminal trailers
  -> blocking helper returns/throws and records one block end
  -> server application cleanup completes
  -> gate records actual end, then release
```

Per-instance ordering in this probe comes from the journal's test-local ordinal. The
probe does not claim that local ordinals order separate hosts or implement the proposed
v2 sequence contract.

## 6. Completion classification

The probe distinguishes three evidence cases:

1. **Observed response:** terminal trailers contain a valid response reference for the
   same invocation. The helper classifies the completion as response-linked.
2. **Positively identified local completion:** a hook/API result proves the failure was
   local without fabricating a remote response. B-FEAS records only classifications
   actually demonstrated by grpc-java behavior.
3. **Unknown origin:** terminal metadata is absent or invalid and no positive local
   origin is available. The helper records unknown/invalid evidence. Missing trailers
   alone never imply local completion.

If grpc-java cannot support a proposed positive local classification, the handoff must
say so and B-CONTRACT must remain blocked until the contract/model is revised or the
supported scope is narrowed.

## 7. Deterministic feasibility tests

### 7.1 Early arrival, readiness, and saturated queue

With one slot, hold the first admitted job on an application latch. Send a second valid
request. Assert:

- transport arrival precedes READY for the second invocation;
- READY precedes QUEUED;
- the second invocation has no execution/slot and no application-start entry while
  queued; and
- releasing the first job causes acquire before the second application start.

### 7.2 One job per slot and queue overflow

With one slot and one waiting position, hold the running job, queue a second request,
and send a third. Assert:

- at most one job is running on the named slot;
- the second request is queued without owning capacity;
- the third request is rejected promptly without execution or slot ownership; and
- exceptional/rejected paths do not leak the slot.

### 7.3 Response trailers before helper return and response before exit

The handler sends its terminal response, then waits on a cleanup latch before returning
from the application job. Assert:

- the response-close wrapper reserves the response reference before delegate close;
- the client captures that reference before the blocking helper returns;
- the caller can return while the server execution still owns its slot; and
- releasing cleanup records actual end before release.

The cleanup latch is a diagnostic hidden dependency and this test does not claim a
closed RPC/slot-only deadlock.

### 7.4 Running cancellation and callback independence

Block the application worker in a cancellable handler, cancel the client call, and keep
application cleanup gated. Assert:

- the server cancellation callback runs while the application slot is occupied;
- cancellation records a request but not end/release;
- the callback thread differs from the named application worker; and
- actual exit followed by release occurs only after cooperative cleanup completes.

### 7.5 Completion-origin conservatism

Exercise an instrumented successful response and a deliberately uninstrumented or
metadata-missing remote error. Assert:

- the successful response is linked to the exact response reference; and
- missing response metadata remains unknown unless a separate hook positively proves
  local completion.

Any deadline/cancellation classification claimed by the handoff requires an executable
case that distinguishes locally generated completion from a remote terminal status.

## 8. Concurrency and cleanup rules

- All waits in tests have bounded timeouts for failure reporting, but state coordination
  is latch/barrier based.
- Gate state transitions occur under its lock; user code and gRPC callbacks do not.
- Test executors, servers, and channels close in `finally`/`AutoCloseable` paths.
- A failed assertion still releases latches or cancels outstanding calls during cleanup.
- Thread names identify callback and application roles; names support evidence but do
  not replace ordering assertions.
- Tests use deterministic IDs; no claim about production UUID uniqueness is made.

## 9. Files and dependency impact

Expected implementation scope:

- new test-only Java files under
  `testbed/src/test/java/dev/spanlease/testbed/feasibility/`;
- focused additions to `testbed` test dependencies for the already pinned OTel SDK span
  probe, or for an existing pinned grpc artifact only if it is not already available
  transitively; and
- `docs/b-feas-handoff.md` containing results, exact hook lifetimes, test commands,
  limitations, and consumer questions.

No changes are expected in `common` production types, `instrumentation/src/main`, the
frozen proto, analyzer code, v1 replay fixtures, or the v2 contract tables.

## 10. Acceptance boundary

B-FEAS is ready for A/C review when executable evidence demonstrates:

- saturated decoded work is visible before application execution/span creation;
- one admitted job owns one named slot;
- response close/receipt can precede actual handler exit and release;
- cancellation alone does not free a running slot;
- blocking application work is isolated from callback execution;
- request metadata is committed before dispatch;
- successful response metadata is observed before helper return;
- one terminal block result is produced per helper call; and
- unsupported/unknown completion origins remain explicit.

The handoff reports observed facts and counterexamples. It does not complete
B-CONTRACT, implement R1-R16 production behavior, approve schema v2, or permit B-TYPES
before G0.
