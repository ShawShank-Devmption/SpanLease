# Final demo guide

This is the operator runbook for the [2026-10-29 final-demo design](superpowers/specs/2026-10-29-final-demo-design.md).
The synthetic Chain monitor already exists. This Dev 2 handoff adds the browser UI
and litmus regressions; the Boutique runtime, source adaptation and Compose deployment
(T1–T6) are pending on this branch. Boutique controls therefore require that runtime
handoff before they can operate. The script below is the acceptance procedure, not a
record of a completed rehearsal.

## Prerequisites and startup

Use Java 21, Docker Desktop with its engine running, and free ports 8080 (shop),
8088 (monitor) and 9555 (ad-service edge). Run commands from the repository root.
The Boutique steps require `deploy/online-boutique/compose.yaml`, pinned upstream
images and the Dev 1 T1–T6 implementation; these are not supplied by this Dev 2 change.

```sh
./gradlew :testbed:monitorDemo
```

Open <http://127.0.0.1:8088>. For Boutique, start the selected monitor run first,
then start the store in another terminal:

```sh
docker compose -f deploy/online-boutique/compose.yaml up -d
```

Open <http://localhost:8080/product/0PUK6V6EV0>. The intended edge default is
`127.0.0.1:9555`. Stop the previous run using its displayed `kill -TERM <pid>` command
before starting another. The PID belongs to the workload JVM; keep the monitor running
to inspect retained evidence. Frontend reconnection timing is pending T6/T11 measurement.
If ads do not return within 15 seconds of starting a new Boutique run, run:

```sh
docker compose -f deploy/online-boutique/compose.yaml restart frontend
```

## Five-minute walkthrough

1. Choose **Synthetic / Closed ring**, then Start. Wait for **DEADLOCK DETECTED**.
   Inspect the reported executions, three occupied workers and queued dependency.
   Open the OS/JVM diagnostics for timestamped thread details. Run the displayed kill
   command; the application stops and the confirmed historical evidence remains.
2. Choose the synthetic **Spare capacity** control and Start. It should complete four
   hops and show `CONFIRMED_NO_DEADLOCK` during leaf work. Startup inconclusive reports
   can occur. Also run **Slow progress** as the finite progress control.
3. Once the runtime handoff is available, choose **Online Boutique / Live store traffic
   (healthy)** and Start; bring up Compose. Browse the product page. Confirm an ad
   banner appears and forwarded/OK store-call counts rise in the monitor.
4. Stop that workload PID, choose **Injected deadlock · ads-a → ads-b → ads-c → ads-a**,
   then Start. Once healthy traffic is visible and Inject enables, click it once.
   Confirm ads disappear while product pages still render. Wait for **DEADLOCK
   DETECTED** and check exactly the three ads-a/b/c slot-0 workers and one queued
   request at ads-a. Kill the displayed PID; the monitor must retain the positive
   evidence and mark the process stopped, without turning process exit into a
   confirmed negative verdict.
5. Start **Spare capacity · ads-a has two workers** and inject once. The fault should
   complete with its ads and store calls should recover. A transient inconclusive
   report is acceptable in this manual walkthrough; a confirmed deadlock is a failure.
   The automated Boutique test additionally requires a certified negative cut strictly
   between injection and fault completion. Switch back to Synthetic and confirm the
   store panel and Inject control clear.

Stop the last workload PID and shut down the containers when finished:

```sh
docker compose -f deploy/online-boutique/compose.yaml down
```

## What is real and what is injected

The synthetic source runs real Java/gRPC Chain services with custom OTel LogRecords
exported over OTLP to the analyzer. The intended Boutique source runs the upstream
Online Boutique shop, with its ad service replaced by an instrumented adaptation of
the pinned upstream Java ad service. The monitor's method is `hipstershop.AdService/GetAds`.
Store calls are ordinary requests; only the separate Inject action creates the
controlled ads-a → ads-b → ads-c → ads-a route. This is an injected resource-wait
fault in the adaptation, not evidence of an upstream Online Boutique defect.

The browser displays analyzer verdicts; it does not calculate verdicts. Latest
received slot observations and timestamped JVM/thread samples are operator diagnostics,
not certified snapshots or detector evidence. Exact positive evidence comes from the
reported historical cut, rather than from the current display history.

## Troubleshooting

- **Port 9555 in use:** terminate the previous displayed workload PID and wait for exit.
  Check the owning process before stopping it; do not kill an unrelated service.
- **Docker cannot reach the edge:** the frontend targets `host.docker.internal:9555`.
  If Docker Desktop cannot reach the loopback bind, restart the monitor with
  `./gradlew :testbed:monitorDemo --args='--edge-bind 0.0.0.0:9555'` once the runtime handoff supports
  that argument, then start a new run. This exposes the ad edge to the LAN; the default
  remains loopback. Check Docker host-gateway support and the Compose `extra_hosts`.
- **Image platform errors or slow startup:** T6 must record platform checks and pinned
  digests in `third_party/online-boutique/SOURCE.md`. Only images lacking ARM64 should
  request `linux/amd64`; those need Docker Desktop emulation on Apple Silicon.
- **Loadgenerator overwhelms the monitor:** use the planned `USERS=3`, `RATE=1`.
  Stop the loadgenerator while diagnosing; do not raise load for this demo. The analyzer
  retains full history. Missing evidence calls for inspecting the report's coverage,
  not weakening verdict checks.
- **No Boutique calls or unknown scenario:** confirm the T1–T6 runtime is integrated.
  The Dev 2 browser assets alone do not implement `/api/inject` or an ad-service edge.

## Verification procedure and artifacts

On a clean clone with Java 21, run:

```sh
./gradlew clean build integrationTest
```

Record actual test totals, failures and skips, including the two real litmus cases
(rather than `NO-SOURCE`). Run the walkthrough with Docker and record every run ID
and artifact directory under `testbed/build/monitor-runs/<run>/`, including manifests,
`reports.jsonl`, configuration, diagnostics and `first-deadlock.json` where produced.
Then run one 15-minute `boutique-live` session. Check consecutive
`evaluated_at_wall_ms` values in `reports.jsonl` and record whether the report gaps
remain ≤ 1 second at the end. Measure frontend reconnection after killing a run,
waiting 30 seconds and starting another; record whether the restart workaround is
needed. Review the two implementation lanes for the design's anti-goals.

## Verified on

Local Dev 2 checks on 2026-10-29 ran on Darwin arm64,
`Ananyas-MacBook-Air.local`, using Temurin Java 21.0.13 from
`/Users/ananyaganesan/Projects/MiniLang/.minilang-tools/jdk-21.0.13+9/Contents/Home`.
The final `./gradlew --no-daemon clean build integrationTest` passed all 106 build
tests and both real litmus tests (108 Java tests total). `spotlessApply`,
`node --check testbed/src/main/resources/monitor/monitor.js`, and
`node --test testbed/src/test/js/monitor.test.cjs` also passed (nine DOM tests). These are local
implementation checks, not a clean-clone rehearsal. No browser was available, so
light/dark visual checks at desktop/mobile sizes were not performed. CI is configured
to run the nine DOM tests with Node 22 and upload test artifacts;
remote CI execution has not been verified. The full final-demo qualification record
remains pending T11.

**T11 pending.** No clean-clone final-demo verification, Docker walkthrough, Boutique
run IDs, reconnection measurement, 15-minute cadence result or machine qualification
is asserted by this document. Earlier Chain verification remains in
[native ARM demo verification](native-arm-demo-verification.md) and
[live application demo](live-application-demo.md); it does not establish Boutique
acceptance. Fill this section with the date, machine, exact commands/test counts,
run IDs/artifact paths, reconnect timing and cadence measurement after T11.

## Limits

G1, G2 and G3 remain pending. Litmus regressions are implemented pre-G1 and are not
formal I-LITMUS/I-CI acceptance or an independent A-TRUTH recorder. The manual closed
ring and Boutique deadlock injection have no RPC deadline.
Boutique sessions persist until the operator stops their workload PID; the spare-capacity
injection has a 30-second deadline and frontend store calls have 100-millisecond
deadlines. The manual deadlock demonstrations do not establish a pre-deadline timing
result. Stop Boutique runs within 30 minutes. No confirmation
is possible after the manifest's 60-minute clock qualification expires. Long sessions
grow analyzer history; history compaction and extended clock qualification are outside
this plan. No claim of novelty, safe remediation or production validation follows
from a successful demonstration.
