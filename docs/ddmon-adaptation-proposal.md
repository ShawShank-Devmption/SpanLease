# Active invocation baseline: unresolved implementation review

This is a concrete E-RPC design handoff, not an implementation or a claim that the
published DDMon algorithm has been reproduced. The current offline invocation-cycle
alarm in `baselines` is a weaker information projection. It must not be renamed DDMon.
The source comparison and its inspection scope are in `docs/literature-review.md`.

The proposed active baseline would place proxies at blocking-call send/return and
application-gate admission boundaries. Each proxy owns a process epoch and independent
call/probe identities; the baseline does not read SpanLease events, cuts, leases, truth
or expected verdicts. The testbed retains the same gate/routing and disables SpanLease
for its cost comparison. Probe traffic and local state transitions need their own
versioned protocol, distinct from the v2 observation contract.

The minimum model includes executing handlers, incoming queued requests, each eligible
worker and the coordinator selecting any one worker. A handler waits for its exact
call attempt. A queued request can proceed when **any** eligible worker progresses;
closure therefore needs evidence about **all** eligible owners. Root deadlines,
cancellation requests, response delivery, actual handler exit and release remain
separate. Cancellation alone cannot erase a held worker in the baseline either.

A naive cycle-return probe is wrong: at capacity two, handler x owns one slot and waits
for queued q at the same pool, while owner y is doing finite local work. The path
x→q→x returns a probe, but y can release the other slot and admit q. Any active adaptation
must include y and its progress state rather than equating a returned probe with closure.
Likewise a probe sent before a timeout must not treat response/cancel as actual worker
exit, or combine old ownership with a newer call identity.

Proposed review scope: first specify the failure-free, no-in-measurement-timeout case
against the paper's proxy/coordinator/worker semantics; disable transparent retry and
hedging. Define probe invalidation across every wait/ownership epoch and characterize
how monitor forwarding affects application scheduling. Then define finite-deadline and
cancellation extensions explicitly, with their own counterexamples and tests. Never
transfer a proof for synchronous proxy visibility to delayed passive notifications.
Only then implement the active baseline and measure message count, trigger-to-evidence
latency and overhead against the same truth and root deadline origin.

This choice is material because an active proxy can change visibility and scheduling;
it is not a library wrapper or an alias for the implemented graph reducer. Per
AGENTS.md, resolving it needs a concrete model/protocol review before dependent
implementation is claimed ready. The review must decide supported timeout semantics,
worker-coordinator encoding, probe consistency/invalidation and the exact evidence
reported. No protocol or correctness acceptance is fabricated here.
