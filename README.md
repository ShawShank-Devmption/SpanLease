# SpanLease

Research prototype for persistent circular waits across blocking unary RPCs and
bounded Java application worker pools. Read requirements.md, design.md, tasks.md and
AGENTS.md before contributing. The detector is not implemented or validated yet;
contract v2 awaits G0 review.

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
Production implementations remain empty except generated frozen messages/stubs.
Tests do not define public gate/event/report APIs for other developers.

All dependency, Java, formatter and Gradle pins are in `gradle/libs.versions.toml`.
Wrapper properties necessarily repeat the distribution URL/checksum as generated
bootstrap metadata. After an approved Gradle pin change, regenerate with
`./gradlew wrapper` and verify the wrapper again. Generated Java lives under build
directories and is excluded from handwritten-source formatting. grpc-java's
`@generated=omit` option avoids adding a legacy annotation dependency.

`integrationTest` is intentionally absent until I-CI supplies the real end-to-end
detector litmus tests. A successful bootstrap build is not G0, G1 or G2 acceptance.
CI runs the real build unconditionally; it no longer treats a skipped build as success.

## Dev A handoff

[A-SPEC](docs/scenario-truth-spec.md) defines the behavioral prerequisites for
B-FEAS and C-MODEL. B/C acceptance is pending; creating it does not fabricate review.
See [verification evidence](docs/bootstrap-verification.md) and tasks.md for status.

Review these artifacts, then commit/push on the team's agreed branch. Local creation
alone does not update the remote or unblock a developer who lacks the artifacts.
