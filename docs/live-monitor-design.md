# Live monitor and TeaStore integration

Requested 2026-10-27: a local monitor window continuously showing incoming gRPC
calls, observed worker/queue activity, deadlock analysis and the resulting evidence.
The owner permits research references from 2025 onward only for this new demo.

## Design

Use buildless HTML/CSS/JavaScript served by a loopback-only Java 21 HTTP server in
testbed. The existing analyzer remains a separate process. An optional stdout stream
for already-defined ObservationEnvelope records feeds the monitor after ingestion;
the existing report stream is authoritative for verdicts. Nothing in the browser
classifies deadlocks, fills missing evidence or supplies detector inputs.

The page shows real received event records and their identities, counts incoming
arrivals, identifies the selected scenario/deployment, and preserves confirmed
historical evidence even when later cancellation changes the latest verdict.
Displayed history is bounded and labelled when truncated; detector history is unaffected.
One workload may execute at a time. Browser requests poll snapshots and start only
whitelisted scenarios using a per-process control token and same-origin checks.

Start against the existing live Chain workload. Separately install pinned upstream
TeaStore in a dedicated Docker Compose project. Its stock REST traffic is not
represented as gRPC observations. A selected application path must be adapted and
verified before labelling its data as TeaStore integration. Installation, a monitor
window, and a diagnostic callback fault do not establish G1–G3 or E-REALISM acceptance.

The monitor is a user-requested operator/demo interface. It is not a new detector
contribution and does not expand the supported Java/blocking-unary/gate scope.

## Implementation and verification

1. Extend the analyzer's optional process output with received envelopes using the
   existing v2 DTO; retain the default CLI/report behavior. Test real OTLP delivery
   to the optional observation consumer and preserve all existing receiver checks.
2. Extend the process harness and LiveRun with an optional observation listener.
   Add bounded monitor state with tests for truncation, concurrent-run rejection,
   historical evidence retention and run reset.
3. Add loopback HTTP/page/control endpoints. Test methods, origin/token rejection,
   unknown routes/scenarios and valid read-only state without running a workload.
4. Implement the browser page, launch it locally, and verify all three real scenarios
   through the browser while observing the data source and evidence panels.
5. Pin/install upstream TeaStore and record actual architecture, readiness and source
   revision. Document the selected path and adaptation before changing application code.
6. Add a reference/data-provenance note using verified publications from 2025 onward.
   Published telemetry without custom worker/checkpoint/causal evidence remains
   unsuitable as a confirmation fixture; replay must be labelled separately.

No event payload/report field is added or repurposed. No application-path I/O or
telemetry queue-space wait is introduced. No external hosting/publication is required.

## Approved interactive diagnostic behavior — 2026-10-27

The owner requested an explicit deadlock verdict, exact workers and OS/JVM details,
then requested a workload PID they can kill manually. The monitor now owns two child
processes: the existing analyzer and a separate Chain workload JVM. The three Chain
services still share one application PID; this does not claim a three-host deployment.
Only that workload PID is shown in the stop command. No arbitrary-PID kill endpoint
is exposed by the browser.

The interactive closed ring uses `submitUntilStopped`: no RPC deadline and no
eight-second observation-window cancellation. This deliberately differs from the
finite CLI and is not evidence about pre-deadline detection latency. The finite
spare-capacity and slow-progress controls retain their deadlines. Stopping a process
ends the running application; it is not evidence of successful request completion
or a new `CONFIRMED_NO_DEADLOCK` report. The parent observes actual process exit,
shows STOPPED and retains the first positive report, exact identities and interval.
Latest detector reports remain separately inspectable; uncertainty is never hidden
by replacing the authoritative latest report with a fabricated positive or negative.

`RuntimeDiagnostics` binds a running handler's `ExecutionContext` to its actual Java
thread at entry and removes it at exit. Only the monitor deployment enables these
hooks; it performs no application-path disk/network output. A separate sampler uses
JDK management APIs to capture OS name/version/architecture, process PID, Java/VM
version, available processors, heap, actual worker names/Java IDs/states, cumulative
thread CPU time where available, monitor/wait counts, JVM lock object/owner and stack.
These facts are sent to the monitor only, not the analyzer. Native OS thread IDs and
kernel wait channels are explicitly unavailable. JVM WAITING and JVM monitor counts
are not the logical RPC/slot predicate. A diagnostic sample has its own timestamp;
it is not presented as a causally certified simultaneous snapshot.

After exit, `monitor-result.json`, `first-deadlock.json` when present, the manifest
and the retained report history are saved under `testbed/build/monitor-runs/<run>/`.
Display/archived history is bounded at 2,000 observations and 200 reports, with the
first positive separately retained; this is not the complete replay/research bundle.
The analyzer still retains its full finite-run history: stop interactive runs after
inspection rather than using this as an unattended monitoring service. Clock
qualification remains the manifest's recorded one-hour demo interval; later samples
cannot extend that qualification or create new confirmations.

Regression coverage exercises real gRPC → OTLP → analyzer with a separate workload
PID, persistence beyond the old observation window, operator process termination,
retained evidence, normal completion of both controls and exclusion of new runs
until the preceding workload has exited. This is demo regression evidence, not
G1/G2 acceptance, independent ground truth or a detector correctness proof.
