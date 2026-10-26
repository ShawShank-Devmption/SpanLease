# SpanLease MVP demo and integration guide

Status: the two real multi-service litmus scenarios have executable assertions and run
in CI. G1–G3 remain pending: independent gate truth, the full scenario/race/fault matrix,
interop and proof acceptance are separate obligations.

## Prepare Java 21

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

The version must begin with 21.

## Five-minute demo

### 1. Establish the build

```sh
./gradlew --no-daemon clean build :testbed:integrationTest
```

Explain that this compiles the Java 21 modules, generates the frozen gRPC contract,
checks formatting and module boundaries, and runs unit/property/lifecycle tests.

### 2. Show real gRPC lifecycle feasibility

```sh
./gradlew :testbed:test --tests dev.spanlease.testbed.GrpcGateFeasibilityTest
```

Explain that this is a real loopback grpc-java call, not a mocked call graph. The test
proves that stream arrival occurs before the generated unary callback, the callback
submits work to a separate bounded application gate, a second request becomes visibly
queued, a blocking downstream stub runs on an application worker, response close can
precede job exit, and cancellation does not free the worker.

### 3. Show runtime invariants and non-blocking telemetry

```sh
./gradlew :instrumentation:test
```

Point out the tests for acquire-before-run, end-before-release, exception cleanup,
bounded-queue rejection, visible sequence gaps, single outstanding block, exact response
parent and asynchronous OTel LogRecord encoding.

### 4. Run the production analyzer

```sh
./gradlew :analyzer:productionDemo
```

The first JSON report is a certified single-slot A/B/C ring. Its core names executions
`a0`, `b0`, `c0`, queued invocation `inv-ca`, units `A0`, `B0`, `C0`, and explicit wait/
ownership edges. The guaranteed persistence interval accounts for epsilon and spans tau, so the
verdict is `CONFIRMED_DEADLOCK`.

The second report changes only A's capacity by adding `A1`. Progress reduction removes
the queued A request, then removes its caller and propagates backward until the graph is
empty, producing `CONFIRMED_NO_DEADLOCK` for that certified cut.

### 5. Demonstrate conservative failure

```sh
./gradlew :analyzer:test --tests \
  dev.spanlease.analyzer.AnalyzerEngineTest.missingInteriorSequenceCannotBeRepairedByCheckpoint
```

Explain that deleting one interior sequence prevents checkpoint certification. Time
passing does not repair the gap and the analyzer returns an inconclusive verdict rather
than inventing progress.

## Run the real end-to-end litmus tests

```sh
./gradlew :testbed:integrationTest
./gradlew :testbed:test --tests dev.spanlease.testbed.AdapterGrpcTest
./gradlew :analyzer:test --tests dev.spanlease.analyzer.AnalyzerPersistenceTest
./gradlew :testbed:liveDemo
```

`LiveLitmusTest` launches real loopback Chain services and a driver, exports custom
LogRecords through the real OTel SDK over OTLP/gRPC, and starts `LiveAnalyzer` in a
separate JVM. The testbed resolves its classpath for that launch; analyzer classes do
not enter the testbed's compile/runtime classpaths. No truth enters the analyzer.

The closed ring must report exactly the three first executions and occupied slots,
plus the final queued A invocation, for at least τ before the root deadline. The spare
capacity run must complete all four hops, contain a certified negative cut during the
leaf's finite work, and never report a positive. These are regression tests with
workload-supplied expected identities, not completion of the independent A-TRUTH oracle.

`AdapterGrpcTest` forces response-wins and deadline-wins transport orderings (R2),
missing/duplicate metadata and overlapping reused IDs (R14), and response/helper
return/cancellation versus actual exit/release (R16), using latches and bounded awaits.
The response-before-exit hold is a diagnostic hook, outside positive litmus evidence.

`AnalyzerPersistenceTest` checks newer upstream waiters and disjoint rings, cascading
closure pruning, incompatible lease intervals, expiry boundaries, stale evaluation ticks
and changed wait identities (C-TIME/R9). Candidate endpoints come from guaranteed
assertion and lease boundaries; the latest nonempty temporally closed subset wins.
Temporal pruning to empty remains inconclusive. A positive report names the lease that
actually covers its selected endpoint, and reports an interval of exactly τ. Leases
must match the acquisition, invocation and block references; mismatches are invalid
evidence. Module-boundary checks cover integration classpaths as well as unit tests.

Live demo artifacts are under `testbed/build/live-runs/<run-id>/`: manifest JSON,
report JSONL, and the partial handler journal. Integration run evidence is retained under
`testbed/build/integration-runs/`. Gradle test reports are under `testbed/build/reports/tests/`; CI uploads
verification reports even after failures.

## How to explain the production design

Describe it as an evidence pipeline with four trust boundaries:

```text
grpc stream arrival / generated adapter
        -> bounded application gate + blocking-RPC lifecycle
        -> bounded custom OTel LogRecord pipeline over OTLP
        -> validated receiver, certified causal cut, graph reduction and v2 report
```

1. **Runtime ownership.** `SlotTrackingExecutor` is the application worker pool, separate
   from gRPC callback execution. One lock serializes admission, ownership, sequence
   allocation and publication attempts. No application path waits for telemetry I/O.
2. **RPC causality.** An execution opens at most one downstream block. `block.begin` and
   `invocation.sent` are committed before transport dispatch. The sent reference travels
   in request metadata. The server's response reference returns in trailers and becomes
   the exact parent of `block.end`.
3. **Loss-aware transport.** `OtelEventSink` performs an immediate bounded offer and
   asynchronously emits unsampled custom LogRecords. A failed offer retains its sequence
   hole. `OtlpEventDecoder` requires native attributes and JSON payload to agree.
4. **Conservative analysis.** `AnalyzerEngine` accepts only contiguous checkpointed
   prefixes, retracts causally unsupported children, reconstructs slots and waits, removes
   everything with an observed progress path, and confirms a remaining residual only
   when a closed subset has uninterrupted coverage for tau and matching eligible leases.
   Newer dependents are pruned from the proposed witness without inventing real progress.

Cancellation, response, helper return, task exit and slot release are separate events.
The detector reports evidence; it does not automatically kill or restart work.

## How it integrates into an application

The implemented adapter performs these steps:

1. Build an immutable `RunManifest` and create one `SlotTrackingExecutor` per declared
   application pool before opening admission.
2. Share one `InstanceRecorder` across all hooks and gates in a process epoch, then
   connect its `EventSink` to `OtelEventSink`, using a dedicated unsampled OTel
   logger provider and the configured OTLP exporter.
3. Capture `sl-invocation-id` and request send reference in a `ServerStreamTracer`, then
   pass them through gRPC `Context` to the generated unary service adapter.
4. Submit one decoded handler job to the gate. Application spans begin only after the
   supplied `ExecutionContext` proves acquisition.
5. Wrap every supported generated blocking-stub call with `SpanLeaseClient.call`; it
   opens a logical block, propagates the send reference, and closes the block with the
   response trailer reference or the client-observed local completion outcome.
6. Record response reference before server close, keep cancellation cooperative, and let
   the gate's actual task-finally path emit end and release.
7. Send OTLP batches to a bounded receiver that calls `OtlpEventDecoder`, admits complete
   batches to one analyzer-core thread, and periodically calls `AnalyzerEngine.evaluate`.

Requests without a usable unique SpanLease ID and caller reference receive a fresh
local external identity and no joined arrival record. They cannot satisfy a monitored
caller's dependency. Concurrent reuse of an otherwise valid invocation ID is rejected
by the gate. The decoder accepts a present OTLP `AnyValue` with no value set only when
the corresponding JSON string is empty; absent attributes or nonempty mismatches fail.

Remaining work includes complete independent gate truth and the bare/instrumented
switch, additional workload scenarios, loss/skew/crash injection, deterministic replay,
proof review, SDK retry/overload tests and the remaining analyzer races. CI execution
of these MVP regressions does not establish G1/G2 acceptance or detector soundness.
