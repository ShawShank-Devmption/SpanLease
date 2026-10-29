# SpanLease MVP demo and integration guide

For selectable live scenarios and an application wiring walkthrough, see
[`live-application-demo.md`](live-application-demo.md).

Status: production-layer MVP components with deterministic demonstrations. This is not
G1/G2 acceptance: the real multi-service litmus path and complete race/fault matrix are
still pending.

## Run the live demo first

The ARM build and real demo were verified on 2026-10-27; see
[`native-arm-demo-verification.md`](native-arm-demo-verification.md) for evidence.
The shared grpc-java 1.76.0 compiler/runtime pin supports Apple Silicon directly.

After selecting Java 21 as below:

```sh
./gradlew :testbed:liveDemo
```

This runs two finite scenarios over real gRPC and OTLP, with the analyzer in its own
process. The first closes A→B→C→A with one slot each and should report three blocked
executions, one queued leaf and three held units. The observation window ends after
eight seconds and cancels the root independently of the verdict. The second gives A a
spare slot; its final leaf performs four seconds of finite work, and the root should
finish successfully with four completed hops. Check negative reports inside that leaf
interval, not only after everything has finished.

Early negative cuts before the ring closes, and inconclusive cuts while initial
checkpoints/leases arrive, are expected. Each report concerns its own historical cut.
The console prints the first detection's observed timing; it is not a latency guarantee.

Artifacts are written to `testbed/build/live-runs/<run-id>/`: `manifest.json`,
`reports.jsonl` and `handler-journal.json`. They are build outputs and are deleted by
`clean`. The handler journal is partial truth, and received envelopes are not yet
archived; these files are not the complete reproduction package.

For demo optimization, the entry point/output is `LiveDemo`, scenario work/windows
are in `Scenario`, and deployment/analyzer lifecycle is in `LiveRun`. Keep the
checkpoint, lease, cancellation and persistence guards intact when comparing changes.
The research task backlog and G1–G3 are separate from having a runnable demo.

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
./gradlew --no-daemon clean build
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
ownership edges. The persistence interval is narrowed by epsilon and exceeds tau, so the
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
   when leases and uninterrupted time overlap for tau.

Cancellation, response, helper return, task exit and slot release are separate events.
The detector reports evidence; it does not automatically kill or restart work.

## How it integrates into an application

The intended adapter performs these steps:

1. Build an immutable `RunManifest` and create one `SlotTrackingExecutor` per declared
   application pool before opening admission.
2. Connect the gate's `EventSink` to `OtelEventSink`, using a dedicated unsampled OTel
   logger provider and the configured OTLP exporter.
3. Capture `sl-invocation-id` and request send reference in a `ServerStreamTracer`, then
   pass them through gRPC `Context` to the generated unary service adapter.
4. Submit one decoded handler job to the gate. Application spans begin only after the
   supplied `ExecutionContext` proves acquisition.
5. Wrap every supported generated blocking-stub call with `openBlock`; propagate its
   `sentReference` in request metadata and complete it with the response trailer reference
   or a positively classified local outcome.
6. Record response reference before server close, keep cancellation cooperative, and let
   the gate's actual task-finally path emit end and release.
7. Send OTLP batches to a bounded receiver that calls `OtlpEventDecoder`, admits complete
   batches to one analyzer-core thread, and periodically calls `AnalyzerEngine.evaluate`.

The repository implements the DTOs, gate, reusable gRPC adapter, scheduled scanner,
bounded LogRecord sink, OTLP receiver and live analyzer. Remaining acceptance work
includes independent gate truth/mapping, bare-mode equivalence, production history/
causal/temporal validation, proof review and the G2 litmus/fault suite. See
[`stage1-correctness-review.md`](stage1-correctness-review.md) for concrete blockers.
