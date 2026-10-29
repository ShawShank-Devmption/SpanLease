# SpanLease

Research prototype for persistent circular waits across blocking unary RPCs and
bounded Java application worker pools. Read requirements.md, design.md, tasks.md and
AGENTS.md before contributing. The runtime and live demo are implemented; full detector
validation and G1–G3 acceptance remain pending. D14 records G0's solo role review.

## Build (A-BOOT)

Install a Java 21 JDK and select it for the terminal running Gradle. The wrapper pins
Gradle and verifies its distribution checksum; no global Gradle install is needed.
First build needs Gradle distributions, Maven Central and the Gradle Plugin Portal,
including native protoc/grpc compiler artifacts.

```sh
./gradlew build
./gradlew :analyzer:test
./gradlew :testbed:test
./gradlew spotlessApply
```

`build` compiles generated protobuf/gRPC sources, checks handwritten Java formatting,
runs JUnit and seeded jqwik tests, and resolves/checks production module boundaries.
The network compatibility test binds only to an ephemeral loopback port. SDK/log
and OTLP serialization tests check library compatibility, not detector event fidelity.
HTML results are under each module's `build/reports/tests/test/`.

Java modules: `common`, `instrumentation`, `testbed`, `analyzer`, `baselines`.
`eval` is reserved for later evaluation scripts, not a placeholder Java service.
The modules contain schema-v2 DTOs, instrumentation, a live analyzer and the Chain
testbed. Baseline experiments and the complete evaluation remain future work.

All dependency, Java, formatter and Gradle pins are in `gradle/libs.versions.toml`.
Wrapper properties necessarily repeat the distribution URL/checksum as generated
bootstrap metadata. After an approved Gradle pin change, regenerate with
`./gradlew wrapper` and verify the wrapper again. Generated Java lives under build
directories and is excluded from handwritten-source formatting. grpc-java's
`@generated=omit` option avoids adding a legacy annotation dependency.
The shared grpc-java 1.76.0 pin includes native Apple Silicon generation; Rosetta is
not required. See [ARM verification](docs/native-arm-demo-verification.md).

`:testbed:integrationTest` contains the closed-ring and spare-capacity litmus
regressions, and CI invokes `integrationTest`. These are implemented, pre-G1, not
accepted as formal I-LITMUS/I-CI evidence. A successful build or live demo is not G1 or G2 acceptance.
CI runs the real build unconditionally; it no longer treats a skipped build as success.

## Live demo

For the interactive browser monitor, select Java 21 and run:

```sh
./gradlew :testbed:monitorDemo
```

Open `http://127.0.0.1:8088`. Select **Closed ring** and start a run. The page shows
**DEADLOCK DETECTED**, the affected slots/executions, wait-for dependencies and actual
Java worker thread names/IDs, states and stacks. Its separate workload JVM has a
displayed PID and `kill -TERM <pid>` command. Killing that PID stops all three demo
services; the monitor observes exit and retains the confirmed historical evidence.
Process exit is not relabelled as a confirmed negative verdict.

The interactive closed ring has **no RPC deadline or automatic observation stop**;
it is a manual diagnostic demonstration, not a pre-deadline evaluation run. Stop it
after inspection. Diagnostic snapshots have their own timestamps and never supply
analyzer evidence. Native OS thread IDs/kernel wait channels are not collected.
The finite controls still use a 30-second deadline and complete normally. The CLI
demo below retains its finite eight-second observation window and deadlines.

See [monitor behavior and limits](docs/live-monitor-design.md).

Select Java 21, then run:

```sh
./gradlew :testbed:liveDemo
```

This starts three real gRPC services and a separate analyzer process, exporting custom
OTel LogRecords over OTLP. The closed ring should produce `CONFIRMED_DEADLOCK`; the
spare-capacity chain should complete four hops with `CONFIRMED_NO_DEADLOCK` reports
during its leaf work. Startup/lease warmup can produce inconclusive reports; each
verdict applies to its reported historical cut.

Manifests, reports and handler journals are under `testbed/build/live-runs/`.
[Demo instructions](docs/mvp-demo-guide.md) include the current verification and limits.
For scenario commands and connecting your own services, see the
[live application integration guide](docs/live-application-demo.md).

## Pre-G0 model MVP

An executable Java 21 model demonstrates conservative verdict selection without
freezing the pending v2 wire/report contracts:

```sh
./gradlew :analyzer:modelMvp
```

It evaluates a persistent closed ring and a fully observed spare-capacity case. The
implementation is deliberately confined to analyzer test sources; it is not the real
gRPC/OTLP detector or integration evidence. See
[`docs/model-mvp.md`](docs/model-mvp.md) for scope and limitations.

## Production-layer MVP demo

After selecting Java 21, run the schema-v2 analyzer demonstration:

```sh
./gradlew :analyzer:productionDemo
```

It prints deterministic JSON for a certified closed ring and a fully observed
spare-capacity negative. See [`docs/mvp-demo-guide.md`](docs/mvp-demo-guide.md) for the
five-minute demo, test commands, architecture explanation and integration sequence.

## Dev A handoff

[A-SPEC](docs/scenario-truth-spec.md) defines the behavioral prerequisites for
B-FEAS and C-MODEL; D13/D14 record solo role acceptance, not independent review.
See [verification evidence](docs/bootstrap-verification.md) and tasks.md for status.

Review these artifacts, then commit/push on the team's agreed branch. Local creation
alone does not update the remote or unblock a developer who lacks the artifacts.

## Final demo

See the [final demo runbook](docs/final-demo-guide.md) for startup, the five-minute
walkthrough, troubleshooting and the pending verification record. The monitor offers
**Synthetic** Chain scenarios and an **Online Boutique** UI. On this Dev 2 branch,
Boutique runtime/edge/Compose support (T1–T6) remains a separate pending handoff;
selecting the UI does not provide those services. The two litmus regressions run with:

```sh
./gradlew integrationTest
```

Final Docker rehearsal and G1–G3 acceptance remain pending.

## Research references

- [DDMon · OOPSLA 2025](https://doi.org/10.1145/3763069): deadlock-monitor comparison.
- [RCAEval · WWW 2025](https://doi.org/10.1145/3701716.3715290): telemetry dataset reference.
- [Microservice Benchmark Applications · 2025](https://doi.org/10.1007/978-3-031-85634-1_15): TeaStore workload reference.

These references guide workloads and comparison. The live feed comes from the named
application, not these papers. Historical datasets alone do not provide SpanLease's
custom evidence. See the [literature review](docs/literature-review.md) for claim limits.
