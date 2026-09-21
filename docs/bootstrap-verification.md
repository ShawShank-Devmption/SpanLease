# A-BOOT verification and handoff

Owner: Dev A. Artifact date: 2026-09-21. Verified: 2026-09-22 (Asia/Kolkata).
Status: local bootstrap verification passed; team review and remote CI pending.

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

JUnit XML/HTML evidence is generated under each tested module's
`build/test-results/test/` and `build/reports/tests/test/`. Build outputs are ignored,
not committed as research results. GitHub Actions execution is a separate check after
publication of the change.

## Acceptance boundaries

- A-SPEC is authored in `scenario-truth-spec.md`; B/C review is still pending.
- A-BOOT does not satisfy B-FEAS: no gate, stream-tracer, trailers or cancellation
  feasibility result exists yet. No R1–R16 implementation is claimed by these tests.
- No G0 approval, v2 types or historical v1 replay migration is included.
- `integrationTest` and the detector litmus suite belong to I-CI; there is no no-op
  task or bootstrap CI guard pretending they have executed.
- These local files are not a remote push, PR approval or team acceptance record.
- CLAUDE.md, requirements.md, frozen proto and v1 replay fixtures are unchanged.
