# Integrated final-demo verification

Date: 2026-10-29. Branch: `feat/final-demo-integrated`.
Runtime baseline: `origin/Jobin` at `d798fcb`; UI/litmus/docs baseline:
`origin/Ananya` at `1a9ea65`; merge: `bca6b1c`.

## Build

Darwin arm64, Ananyas-MacBook-Air, Temurin Java 21.0.13.
`./gradlew --no-daemon clean build integrationTest` passed in 2m09s:
common 5, instrumentation 9, analyzer 56, testbed 82, integrationTest 2.
Total: 154 Java tests, zero failures/errors/skips. JavaScript syntax check
and all nine DOM regressions passed. The process port-conflict test deliberately
prints a bind exception and passes by checking FAILED plus the port.

A separate local clone at `ec8c357` also passed the same command in 2m11s:
154 Java tests, zero failures/errors/skips. It shares the host JDK and Gradle
dependency cache; this is a clean source checkout, not a fresh machine image.
After the transport-setup test fix at `6a2f6d8`, the clean clone reran every task
and again passed all 154 tests in 2m11s. Its JavaScript DOM checks also passed.
Cadence-tool checks passed for the 1000 ms boundary, a 1001 ms gap, reversed
time and insufficient reports.

## Runtime and browser

Monitor uses loopback port 8098 for this rehearsal; the documented default is 8088.
Synthetic closed-ring run `closed-ring-34cba480` produced exactly three executions,
three units and one queue entry. Workload PID 54392 was terminated by the operator
script; archived reports retain the positive evidence. Its last 200 reports had
maximum evaluation gap 255 ms. This short run is not the 15-minute Boutique check.

Docker Engine 29.6.2 / Compose 5.3.1 started all eleven pinned services.
Combined Docker API rehearsal passed:

| Scenario | Run | Evidence |
|---|---|---|
| Synthetic spare | `spare-capacity-25e0a71a` | Normal exit; certified negative; no positive |
| Synthetic slow progress | `slow-progress-4a753472` | Normal exit; certified negative; no positive |
| Boutique deadlock | `boutique-deadlock-24cf8a3b` | Exact 3/3/1 core; persists 10 seconds after confirmation; store deadlines; product HTTP 200 with no ad banner; positive retained after PID kill |
| Boutique spare | `boutique-spare-capacity-67fc2a1a` | Injection OK with kitchen ads; negative certified strictly between injection/completion; product HTTP 200 with its ad banner restored; no positive |

Artifacts: `testbed/build/monitor-runs/<run>/` and temporary local snapshots in
`testbed/build/integrated-rehearsal/`. The first operator script assumed the
product's store banner would use the injection's kitchen ad text; inspection showed
its product-specific decor ad. The rerun checks actual ad-banner presence/absence,
while retaining the certified-cut check for injection. The automated process tests
separately check the injected kitchen ads.

Chrome loaded the integrated monitor and started the synthetic closed ring. Full
visual checks are blocked: the computer-use service first failed screen capture,
then returned `Sky Computer Use native pipe startup failed`, including after reset.
Desktop/mobile light/dark checks, UI injection clicks, expanded diagnostics and
browser XSS visual inspection are not claimed. Nine DOM regressions cover control,
text rendering and slot-state behavior but do not establish visual qualification.

The live-store run `boutique-live-e51a8f44` completed **901.25 seconds** measured
with the operator's monotonic timer: 401 forwarded calls, 401 OK, zero deadlines,
zero other failures, no positive report, and a latest `CONFIRMED_NO_DEADLOCK`.
Start/end wall timestamps and monotonic duration are retained in
`testbed/build/integrated-rehearsal/soak-duration.json`.

After workload exit, `tools/check-demo-cadence.py` checked its archived last 200
reports: retained span 49,754 ms, minimum gap 246 ms, maximum gap **254 ms**, limit
1,000 ms, PASS. This establishes the requested end-of-run cadence check, not a
full-history archive or a general timing guarantee. The operator then stopped the
demo containers and monitor. Local build artifacts remain available until cleaned.

The full visual matrix and independent cross-lane sign-off remain pending.
[Draft PR #1](https://github.com/ShawShank-Devmption/SpanLease/pull/1) contains the
integrated implementation and review record.

## Review and acceptance

Author integration review checks the browser consumes analyzer verdicts, slot display
uses `unit_latest`, returned text uses `textContent`, store calls carry no diagnostic
header, injection has its own root Context, and no detector production/schema changes
were introduced. This is author review, not independent cross-lane approval.
CI exposed a mismatch between client and edge observations in the deployment test:
a failing run recorded 11 forwarded calls (one healthy warmup), one OK, nine edge
deadlines and one other failure, while all ten fault calls timed out at the client.
A propagated cancellation can reach the edge before its local deadline expires;
[gRPC's server cancellation path](https://github.com/grpc/grpc-java/blob/v1.76.0/core/src/main/java/io/grpc/internal/ServerImpl.java#L821-L835)
can therefore cancel its Context independently of the local deadline timer.

The deployment test establishes a healthy transport, then drives bounded additional
100 ms calls until it observes ten edge deadlines. Every attempted fault call must
still return `DEADLINE_EXCEEDED` to the client; at least ten attempts and at least ten
edge deadline outcomes remain mandatory. The test retains the five-second final
observation bound and now includes actual counters on timeout. It does not relabel
cancellations, relax the threshold, extend frontend deadlines, or alter production
code. This resolves a test workload assumption, not an analyzer defect.

After this correction, the isolated clean source clone passed all 154 Java tests
in 2m11s. Remote CI at `84a85c1` passed in 3m46s, including build, DOM regressions
and both litmus integrations: [run 40799267291](https://github.com/ShawShank-Devmption/SpanLease/actions/runs/40799267291).
The concurrent working-tree slot-display review changes were excluded from this
isolated build; their separate verification is recorded below.

Independent review and G1–G3 acceptance are not asserted.
