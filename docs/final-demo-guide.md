# Final demo guide

This is the operator runbook for the [2026-10-29 final-demo design](superpowers/specs/2026-10-29-final-demo-design.md).
The integrated demo includes the Boutique runtime and pinned Compose store (T1–T6),
flat monitor UI (T7–T8), and automated litmus regressions (T9). This guide combines
the two implementation lanes. Historical lane checks are listed below; the combined
Docker rehearsal and 15-minute qualification must be recorded separately.

## Prerequisites and startup

Use Java 21, Docker Desktop with its engine running, and free ports 8080 (shop),
8088 (monitor) and 9555 (ad-service edge). Run commands from the repository root.
The Compose deployment pins upstream image digests; see
[provenance and platform inventory](../third_party/online-boutique/SOURCE.md).

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
to inspect retained evidence. Dev 1 measured frontend reconnection at about 17.1 seconds after the first new
edge sample, exceeding the 15-second threshold. **After starting each new Boutique
run, restart frontend:**

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
3. Choose **Online Boutique / Live store traffic
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
exported over OTLP to the analyzer. The Boutique source runs the upstream
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
  `./gradlew :testbed:monitorDemo --args='--edge-bind 0.0.0.0:9555'` then start a new run. This exposes the ad edge to the LAN; the default
  remains loopback. Check Docker host-gateway support and the Compose `extra_hosts`.
- **Image platform errors or slow startup:** T6 records platform checks and pinned
  digests in `third_party/online-boutique/SOURCE.md`. Only images lacking ARM64 should
  request `linux/amd64`; those need Docker Desktop emulation on Apple Silicon.
- **Loadgenerator overwhelms the monitor:** use the configured `USERS=3`, `RATE=1`.
  Stop the loadgenerator while diagnosing; do not raise load for this demo. The analyzer
  retains full history. Missing evidence calls for inspecting the report's coverage,
  not weakening verdict checks.
- **No Boutique calls or unknown scenario:** confirm you are running the integrated branch and have started a Boutique run.
  Inspect startup errors and verify the edge port is reachable.

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
remain ≤ 1 second at the end. After stopping the run, check its retained tail with
`python3 tools/check-demo-cadence.py testbed/build/monitor-runs/<run>/reports.jsonl`.
The monitor archives only its last 200 reports; this checks end-of-run cadence,
not the full session duration. Record the session start/stop times separately.
Measure frontend reconnection after killing a run,
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

Dev 1 separately recorded real Docker-store runs on 2026-10-29:
`boutique-live-b07e9ea3`, `boutique-live-3a525a02`,
`boutique-deadlock-e33254ec`, and `boutique-spare-capacity-f21a43e9`.
See [source verification](../third_party/online-boutique/SOURCE.md#local-verification--2026-10-29)
and [runtime handoff](dev1-final-demo-handoff.md) for observations, machine details
and the reconnect measurement. These precede integration with the final UI.

**Combined T11 qualification is pending.** Record the integrated clean-clone build,
browser walkthrough, real Docker run IDs, 15-minute cadence and cross-lane review in
[the integrated verification record](final-demo-verification.md). Earlier lane checks
do not establish combined acceptance or independent review.

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
