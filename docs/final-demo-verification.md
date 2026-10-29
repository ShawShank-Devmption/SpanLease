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

Clean-clone verification: pending.

## Runtime and browser

Monitor uses loopback port 8098 for this rehearsal; the documented default is 8088.
Synthetic closed-ring run `closed-ring-34cba480` produced exactly three executions,
three units and one queue entry. Workload PID 54392 was terminated by the operator
script; archived reports retain the positive evidence. Its last 200 reports had
maximum evaluation gap 255 ms. This short run is not the 15-minute Boutique check.

Combined Docker and complete browser qualification: in progress.

## Review and acceptance

Author integration review checks the browser consumes analyzer verdicts, slot display
uses `unit_latest`, returned text uses `textContent`, store calls carry no diagnostic
header, injection has its own root Context, and no detector production/schema changes
were introduced. This is author review, not independent cross-lane approval.
Independent review, remote CI and G1–G3 acceptance are not asserted.
