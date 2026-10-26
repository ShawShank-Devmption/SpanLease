# SpanLease

Research prototype for persistent circular waits across blocking unary RPCs and
bounded Java application worker pools. Read requirements.md, design.md, tasks.md and
AGENTS.md before contributing. The finite-run detector, bare/instrumented testbed,
independent gate truth, replay and automated regressions are implemented. Contract v2
was frozen by solo role-based G0 review (D13/D14); research acceptance G1–G3 remains
pending.

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
The MVP includes schema-v2 DTOs, the application gate and gRPC adapter, custom OTel
export/OTLP ingestion, the analyzer, and a separate-process live harness. See
[`docs/mvp-demo-guide.md`](docs/mvp-demo-guide.md) for commands and limitations.

All dependency, Java, formatter and Gradle pins are in `gradle/libs.versions.toml`.
Wrapper properties necessarily repeat the distribution URL/checksum as generated
bootstrap metadata. After an approved Gradle pin change, regenerate with
`./gradlew wrapper` and verify the wrapper again. Generated Java lives under build
directories and is excluded from handwritten-source formatting. grpc-java's
`@generated=omit` option avoids adding a legacy annotation dependency.

`./gradlew :testbed:integrationTest` runs the real gRPC/OTLP closed-ring and
spare-capacity litmus assertions with a separate analyzer JVM. CI runs both `build`
and these integration tests and uploads their reports and live-run evidence.
G0's solo review is recorded in `docs/stage0-g0-review.md`; these MVP regressions
do not establish G1 or G2 acceptance.

## Historical pre-G0 model MVP

The earlier executable Java 21 test-source model demonstrates conservative verdict
selection. It predates the schema-v2 production implementation:

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
B-FEAS and C-MODEL. B/C acceptance is pending; creating it does not fabricate review.
See [verification evidence](docs/bootstrap-verification.md) and tasks.md for status.

Review these artifacts, then commit/push on the team's agreed branch. Local creation
alone does not update the remote or unblock a developer who lacks the artifacts.

## Extended finite-run tooling

The bare/instrumented workload runner, independent gate truth, fault/crash regressions,
exact replay, native-span baseline information comparisons and run-level pilot statistics
are documented in [`eval/README.md`](eval/README.md). The correctness argument and its
review limits are in [`docs/correctness-argument.md`](docs/correctness-argument.md).
`tasks.md` distinguishes implemented engineering evidence from pending research gates,
live baseline/ablation work, publication-scale experiments and manuscript acceptance.
