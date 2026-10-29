# Monitor hardening design

Date: 2026-10-29. This design implements the owner-supplied integrated-branch review.

## Intent

Make the local operator monitor reliable under reordered/retried display telemetry, early or
graceful Boutique shutdown, and failed fault injection, while restoring the information and visual
states removed by the flat-theme rewrite. This is display/testbed hardening only: the frozen v2
wire/report contracts and analyzer verdict rules do not change.

## Chosen approach

- Keep manifest-bounded display projections in `MonitorState`. Apply each slot/entity transition
  only when its instance-local sequence is newer, and expose latest instance sequence plus blocked
  and queued counts to the browser.
- Give the Boutique child a `STOP`/stdin-EOF path and a JVM shutdown hook. Normal monitor shutdown
  closes the deployment, emits final checkpoints, and flushes telemetry before the process exits;
  TERM remains a cleanup fallback.
- Report startup failure explicitly from the child instead of inferring it from the absence of a
  500 ms store sample. Convert fault-control write failures to HTTP 409.
- Restore CSS for every class emitted by the browser, remove the unreachable browser-side slot
  reducer, and use the backend projection for slots while retaining event-derived compatibility for
  service activity counts.
- Replace name-matching scenario policy with explicit monitor behavior metadata. Propagate one demo
  seed value to the child. Reuse small root-instrumentation and telemetry-pipeline lifecycle helpers
  from both Chain and Boutique deployments.

## Constraints

- Java 21, existing dependencies only, one top-level class per file, google-java-format.
- No changes to `common`, `instrumentation`, analyzer semantics, v2 DTOs, or detector inputs.
- Display projections remain non-authoritative operator output and never feed the analyzer.
- Cancellation, helper return, execution exit, slot release, final checkpoint, and telemetry flush
  remain distinct operations.

## Verification

Each behavior receives a failing regression before implementation. Run affected Java tests, Node
monitor tests, `./gradlew build integrationTest`, and `git diff --check` on Java 21.
