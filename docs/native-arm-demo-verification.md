# Native ARM build and live demo verification

Date: 2026-10-27. Scope: the project owner's requested Apple Silicon build and demo
readiness, ahead of the full research backlog. Single-implementer verification;
G1–G3 acceptance is not claimed.

## Toolchain change

D16 upgrades the shared grpc-java runtime/compiler pin from 1.68.1 to 1.76.0 in
`gradle/libs.versions.toml`. The new official `osx-aarch_64` generator is a universal
Mach-O executable with arm64 and x86_64 slices; the old artifact contained only x86_64.
Gradle still selects the host classifier, so the build does not hard-code macOS or a
local compiler path. No Rosetta/system runtime installation was needed.

The [upstream release](https://github.com/grpc/grpc-java/releases/tag/v1.76.0)
documents native macOS aarch64 support. The inspected generator's SHA-256 is
`8100c67d1ea547fc34dd638db20876921349df99c42608fdfdcf1cef15c1d6ff`.

Local execution used the native Homebrew Java 21.0.12.1 JDK and Gradle wrapper 8.12.1.
Dependency insight confirmed protobuf-java resolves to the existing 4.28.3 pin and
OpenTelemetry SDK/logs to 1.45.0. These versions were checked together through the
fresh compilation, compatibility/lifecycle tests and real OTLP demo.

## Commands and results

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon clean build :analyzer:test :testbed:test :testbed:liveDemo
```

Result: **BUILD SUCCESSFUL**, all 43 actionable tasks executed. Protobuf/gRPC
generation and compilation ran from clean outputs. Formatting and module-boundary
checks passed. JUnit XML records:

| Module | Tests | Failures / errors / skipped |
|---|---:|---:|
| analyzer | 41 | 0 / 0 / 0 |
| common | 5 | 0 / 0 / 0 |
| instrumentation | 9 | 0 / 0 / 0 |
| testbed | 4 | 0 / 0 / 0 |
| Total | 59 | 0 / 0 / 0 |

The testbed tests include real blocking-unary loopback calls and gate/RPC lifecycle
feasibility. `baselines` has no source/tests; it is not included in the test count.

Additional dependency checks succeeded:

```sh
./gradlew --no-daemon \
  :testbed:dependencyInsight --dependency protobuf-java --configuration runtimeClasspath \
  :instrumentation:dependencyInsight --dependency opentelemetry-sdk --configuration runtimeClasspath
```

## Observed demo outcomes

| Scenario / run ID | Observed result |
|---|---|
| `closed-ring-09b37734` | `CONFIRMED_DEADLOCK`; exactly the first handler execution on each of svcA/B/C, their three declared slot-0 units, and one queued svcA leaf. First report had 2.17 s of narrowed persistence with τ=2 s. Root was cancelled by the independent eight-second observation stop. |
| `spare-capacity-d50a9ef4` | Root completed successfully with four hops. Fourteen `CONFIRMED_NO_DEADLOCK` reports had certified horizons inside the final leaf's four-second journal interval. No positive deadlock report occurred. |

The run directories are under `testbed/build/live-runs/`. A post-run artifact check
compared every positive report's execution/unit sets with the known deployed scenario,
required one queued leaf, empty missing evidence and persistence ≥τ, and checked the
negative cuts against the spare leaf journal interval. Both checks passed. This is
demo smoke verification, not the independent gate-truth oracle required by A-TRUTH.

For the closed ring, the console observed first detection 2.55 s after root submission
and 27.45 s before its configured root deadline. Those are one-run demo observations,
not a performance estimate or promised detection bound. Early negative/inconclusive
reports concern earlier cuts and lease/checkpoint warmup.

## Limits and next step

The ARM generation blocker in `stage1-correctness-review.md` is resolved. Demo readiness
does not complete G1–G3, evaluation, native Linux/Windows validation, a vulnerability
audit or clean-environment reproduction. This was a clean-output build using the
existing Gradle dependency cache. CI/independent reproduction has not been run here.

The live demo is ready for the next optimization pass using:

```sh
./gradlew :testbed:liveDemo
```

Keep this build/test command and the two scenario outcomes as the regression baseline.
