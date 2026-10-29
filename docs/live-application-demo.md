# Live application integration and deadlock demonstration

## Where the current data comes from

`LiveDemo` starts real grpc-java servers and a root driver on this Mac. The services
share one JVM but have separate bounded application worker pools and gRPC callback
executors. The analyzer runs in another JVM. Requests travel over loopback TCP;
custom OTel SDK LogRecords travel over OTLP/gRPC to the analyzer.

The configured route, capacity, work durations and observation window create the
scenario. The detector receives runtime observations and computes reports online;
the expected scenario verdict is not an analyzer input. `HandlerJournal` is a
separate, partial record of application handler boundaries and is not sent to the
analyzer. This is a controlled live workload, not automatically collected production
traffic or a hard real-time timing guarantee.

## Run the demonstration

Select Java 21 first. On the tested Homebrew Apple Silicon setup:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --console=plain :testbed:liveDemo --args='--scenario all'
```

Individual scenarios and service counts:

```sh
./gradlew :testbed:liveDemo --args='--scenario closed-ring --services 3'
./gradlew :testbed:liveDemo --args='--scenario spare-capacity --services 3'
./gradlew :testbed:liveDemo --args='--scenario slow-progress --services 3'
./gradlew :testbed:liveDemo --args='--scenario closed-ring --services 5'
```

Supported counts are 2, 3 and 5. Without arguments, the original closed-ring and
spare-capacity pair runs. `--runs-directory` selects an artifact directory. Defaults
are under `testbed/build/live-runs/`; `clean` deletes those build outputs.

| Scenario | Application behavior | What to show |
|---|---|---|
| Closed ring | A holds its only worker while waiting for B; B waits for C; C calls back into A, where the decoded leaf queues. | `CONFIRMED_DEADLOCK`, three blocked executions, all three occupied slot-0 units, and the queued A leaf. For five services the same pattern has five owners. |
| Spare capacity | Same cyclic route, but A has two workers. The callback leaf runs on A's second worker and finishes finite work. | Negative reports during the leaf interval; the root completes four hops for the three-service route. Cyclic service names alone do not imply closure. |
| Slow progress | A→B→C with no callback; C performs finite work while A and B retain their workers. | Negative reports while the nonblocked leaf is making progress; the root completes three hops. |

For the closed ring, allow checkpoint/lease warmup and at least τ=2 seconds of
certified persistence. Initial inconclusive reports are expected. An early negative
report can describe a cut before the ring closes. Read the reported cut and interval,
not just the latest line's label.

The root deadline is 30 seconds. The demo's independent eight-second observation stop
cancels a still-running root; the detector does not trigger this stop. Cancellation
requests, blocking helper returns, actual job exits and slot release are separate.
This demonstrates a persistent pre-deadline circular wait with finite cancellation,
not a claim that finite-deadline calls are permanently deadlocked.

The console prints reports as they arrive. `manifest.json` records deployment/config,
`reports.jsonl` contains detector reports, and `handler-journal.json` records partial
application boundaries. Received observation envelopes and complete independent gate
truth are still missing from the artifact package; this is not G1/G2 acceptance or
the final replay/reproduction bundle.

## Connect your own running application

This prototype requires code integration before admission opens. It cannot attach to
an arbitrary process, infer worker capacity from ordinary spans, or diagnose JVM locks,
database transactions or arbitrary HTTP/async calls using these hooks.

The supported application uses Java 21, generated grpc-java **blocking unary** stubs,
one outstanding downstream invocation per admitted handler, stable instance-bound
routing, and explicit fixed-capacity worker pools. Choose a local development or
staging deployment in which you control the callback route and deadlines.

Use the already compiled Chain implementation as the working reference:

| Application integration point | Repository implementation |
|---|---|
| Immutable deployment manifest, including driver, pools, unit IDs and clock context | `testbed/.../RunPlan.java`; shared `common/.../RunManifest.java` |
| Dedicated SDK/OTLP pipeline per instance | `instrumentation/.../OtlpTelemetry.java`; `testbed/.../ChainDeployment.java` |
| Local serialized recorder and bounded application workers, initialized before serving | `instrumentation/.../InstanceRecorder.java`, `SlotTrackingExecutor.java`; `ChainNode` constructor |
| Early incoming request metadata and terminal response trailers | `SpanLeaseServer.streamTracerFactory()` and `SpanLeaseServer.intercept(service)` |
| Decoded unary method submission to one handler job | `SpanLeaseServer.handleUnary(...)`; `ChainNode.ChainService.call(...)` |
| Blocking downstream call while retaining its worker | `SpanLeaseClient.call(...)`; `ChainNode.handle(...)` |
| Incoming root request instrumentation and deadline | `RootCaller.call(...)`; `ChainDriver.submit(...)` |
| Idle checkpoints and atomic current-block leases | `PeriodicScanner`; constructors in `ChainNode` and `ChainDriver` |

All source paths above are under `src/main/java/dev/spanlease/<module>/`.

The server builder must install both hooks, using a callback executor separate from
the application gate:

```java
NettyServerBuilder.forPort(port)
    .executor(callbackExecutor)
    .addStreamTracerFactory(spanLeaseServer.streamTracerFactory())
    .addService(spanLeaseServer.intercept(service))
    .build()
    .start();
```

Inside the generated unary service method, call `handleUnary(request, observer,
handler)`. The supplied handler receives an `ExecutionContext` only after assignment;
perform application work there. Wrap each downstream generated blocking-stub call
with `SpanLeaseClient.call(execution, targetService, stub, invocation)`. Copy the
concrete, tested patterns from `ChainNode`; do not execute blocking work directly in
the gRPC callback or use an ordinary interceptor as an early-arrival replacement.

Every channel must disable transparent retries/hedging. Propagate the root deadline
as a remaining budget; do not reset it at every hop. Use fresh invocation IDs for
each supported sequential attempt. A root sent with plain `grpcurl` or an ordinary
uninstrumented stub does not provide the required send identity: use `RootCaller`
and include that driver instance in the manifest.

Create one immutable manifest for the run **before** starting traffic, with actual
fresh process-epoch IDs, exact pool/unit membership and service bindings. It contains
configuration, never current ownership or an expected verdict. Match those IDs in
the recorders. Restart/membership/capacity changes need a new run/epoch; do not reuse
a saved demo manifest for unrelated application processes.

Record the actual clock qualification covering the run. The demo uses a shared host
reference clock. Multi-host use needs qualified error bounds and authenticated TLS;
the supplied analyzer CLI is loopback-only and does not supply a secure remote
collector. Setting ε in JSON is not evidence that hosts satisfy that bound.

Start the analyzer with your application's current manifest:

```sh
./gradlew :analyzer:liveAnalyzer \
  --args='--manifest /absolute/path/to/current-run/manifest.json --port 4317 --emit all'
```

Wait for `READY port=4317`, then point each application's dedicated `OtlpTelemetry`
pipeline at `http://127.0.0.1:4317`. The CLI reports to stdout. `ChainDeployment` shows
the exporter setup and shutdown/flush lifecycle; it is not an automatic launcher for
your own application.

## An application-shaped example to explain on stage

Map A/B/C to Orders/Inventory/Payments. An Orders handler synchronously reserves
inventory, Inventory synchronously requests payment, and Payments synchronously calls
Orders to finish the operation. With one occupied worker per instance, the callback
cannot enter Orders because its only worker is waiting for the descendants. Show the
worker ownership and queued callback in the report. Then restart with two Orders
workers and repeat: the callback can run and the chain can finish.

This is an explanation of the Chain topology, not evidence that an independently
sourced production workload has been evaluated. Merely renaming services does not
complete E-REALISM. Positive reports are historical evidence, not authorization for
automatic cancellation or remediation.
