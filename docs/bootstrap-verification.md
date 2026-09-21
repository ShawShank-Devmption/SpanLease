# A-BOOT verification and handoff

Owner: Dev A. Artifact date: 2026-09-21. Verified: 2026-09-22 (Asia/Kolkata).
Status: author and independent Dev B bootstrap verification passed; remote CI status
has not been independently observed.

## Scope

Five Gradle Java modules, Java 21 toolchain, frozen Chain proto generation,
google-java-format, JUnit/AssertJ/jqwik, explicit JUnit Platform launcher and CI.
The root build resolves classpaths and verifies allowed project dependencies in
production and tests; common production libraries are restricted to Jackson.
These checks are build-graph checks, not proof against reflection or dynamically
loaded code. Analyzer truth independence still needs implementation review.

Compatibility tests exercise Jackson long values, SDK LogRecord attributes,
OTLP protobuf serialization, frozen Chain field numbers/unary method identity,
seeded Chain serialization properties and a generated blocking stub over a real
ephemeral loopback gRPC connection. The echo fixture is not an application gate.
It implements no event/report schema, race protocol or Dev B public interface.

## Verification record

The authoring machine initially had Java 26, not Java 21. A temporary Azul Java 21
JDK was downloaded without changing the system Java installation; its SHA-256 was
checked against vendor metadata. Gradle's distribution checksum is catalog-pinned
and repeated in the generated wrapper properties.

Environment: macOS 26.5.2 arm64, Azul Java 21.0.12.1, Gradle 8.12.1. Commands ran
with `JAVA_HOME` selecting that JDK and `--gradle-user-home` pointing to an isolated
temporary cache. The system-default Java remains unchanged; select Java 21 before
building. This is not a compatibility claim for running Gradle under Java 26.

| Command/check | Result |
|---|---|
| Official Gradle distribution: `wrapper spotlessApply build` | PASS; wrapper generated, formatting applied, dependencies resolved, all tests executed |
| `./gradlew --version` | PASS; generated wrapper launches Gradle 8.12.1 on Java 21 |
| `./gradlew --no-daemon clean build` | PASS; 39 tasks executed, six test cases, zero failures/errors/skips |
| jqwik property in ChainContractTest | PASS; 100 accepted checks, seed 42 |
| Wrapper JAR SHA-256 against Gradle's published checksum | MATCH: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |
| Temporary init script adds analyzer test dependency on testbed; run verifyModuleBoundaries | Expected failure: `:analyzer must not depend on :testbed (testCompileClasspath)` |
| `git diff --check` | PASS |

The already verified official distribution ZIP was preseeded into the isolated
wrapper cache to avoid repeating a slow download. The wrapper installed it and
validated its catalog-pinned checksum. A fresh network download through the wrapper
and the Linux GitHub Actions job have not been executed here. Maven/plugin and native
generator downloads did execute during the initial build. Windows wrapper script is
generated but not tested on Windows. Pins are compatibility-tested, not a latest-version
or vulnerability audit.

The forbidden dependency was injected only for that Gradle invocation; no repository
dependency was changed. The check covers test classpaths as well as production, so
analyzer tests cannot quietly import the testbed either. The baseline module has no
implementation/tests yet and correctly reports NO-SOURCE; six actual tests execute
across common, instrumentation, analyzer and testbed.

## Dev B consumer review

Reviewer: Dev B (Jobin). Review date: 2026-09-22. Reviewed source commit: `21c8fb4`.

Dev B independently downloaded the official Azul Zulu Java 21.0.12.1 macOS ARM64
archive into a task-specific temporary directory and verified SHA-256
`042093e0895c940a02d68e727bc37b59f3958e58aa1463ec9080845d77af0a45` against Azul's
package metadata. Gradle used a separate temporary user home.

| Dev B check | Result |
|---|---|
| `./gradlew --version` under Java 21 | PASS; Gradle 8.12.1 and Java 21.0.12.1 |
| `./gradlew --no-daemon clean build` | PASS; 39 tasks, six tests, zero failures/errors/skips |
| `./gradlew --no-daemon :analyzer:test :testbed:test` | PASS; requested tasks successful/up-to-date after clean build |
| Wrapper JAR SHA-256 | MATCH: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |
| Temporary analyzer test dependency on testbed | Expected failure at `testCompileClasspath` boundary |
| Repository status after verification | No tracked changes |

Dev B accepts A-BOOT at source commit `21c8fb4` as the build prerequisite for B-FEAS.
This acceptance is limited to bootstrap/build compatibility and module isolation. It
does not validate detector behavior, observe the GitHub Actions result, approve the v2
contract, or satisfy any later gate.

JUnit XML/HTML evidence is generated under each tested module's
`build/test-results/test/` and `build/reports/tests/test/`. Build outputs are ignored,
not committed as research results. GitHub Actions execution is a separate check after
publication of the change.

## Acceptance boundaries

- A-SPEC is authored in `scenario-truth-spec.md`; Dev B lifecycle acceptance is
  recorded there and Dev C truth/clock/oracle review remains pending.
- A-BOOT does not satisfy B-FEAS: no gate, stream-tracer, trailers or cancellation
  feasibility result exists yet. No R1–R16 implementation is claimed by these tests.
- No G0 approval, v2 types or historical v1 replay migration is included.
- `integrationTest` and the detector litmus suite belong to I-CI; there is no no-op
  task or bootstrap CI guard pretending they have executed.
- These local files are not a remote push, PR approval or team acceptance record.
- CLAUDE.md, requirements.md, frozen proto and v1 replay fixtures are unchanged.
