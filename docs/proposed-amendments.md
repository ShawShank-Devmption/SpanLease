# Proposed semantic and contract amendments

> Historical amendment rationale. D8 adopts revised documentation addressing A-1–A-6;
> D9 specifies the resulting v2 contract candidate in design.md. G0 all-developer
> approval and fixture migration remain pending. Statements below about preserving
> v1 tables describe the earlier review step, not the current document baseline.

Status: **review proposals, not an adopted schema or replacement proposal**.
2026-09-19. Required by the findings in [technical-review.md](technical-review.md).
The user authorized document corrections; the repository additionally requires a
contract-tagged PR, all three developers' approval, and a schema-version bump for
wire-visible changes. No such approval or bump is claimed here. Existing event/verdict
tables, proto, and frozen replay schema remain unchanged.

## A-1 — Define the target around deadlines and resource binding

Proposed replacement for permanent-deadlock wording in requirements §2/proposal §3:

> A qualifying persistent circular wait is a closed set of observed RPC waits and
> executor-capacity dependencies with no internal progress transition during its
> certified interval. An external deadline or cancellation can break the condition.
> Confirmation describes that interval and does not assert permanent future blocking.

For `any_unit`, all units of every pool needed by a queued member must be held within
the closed set. Other pools can be partly occupied. Bound invocations wait on the
selected instance's pool, not on all replicas of the logical service. Preserve the
non-goal of remediation. Distinguish single-response waits from queue alternatives:
the RPC dependency is singleton AND, but queue admission is OR over eligible units.

## A-2 — Make completeness, initial state and causality representable

Before freezing an implementation, enumerate exactly what the analyzer observes:

- A declared monitored instance/resource universe and stable capacity epochs, separate
  from the ground-truth channel. Observed initial FREE/HELD/UNKNOWN state must be
  distinguishable from declared capacity. A trusted start-before-traffic handshake is
  one option; attaching mid-run requires an atomic state snapshot plus event boundary.
- Per-instance completeness certificates through a sequence boundary, emitted after
  all earlier transitions, including idle instances. A heartbeat carrying only a
  timestamp is insufficient. Drops must preserve detectable sequence holes, and a
  completeness boundary cannot certify a dropped prefix. Missing certificates prevent
  advancing the certified window. Specify retention, restart and recovery behavior.
- Exact request-send and response-send causal references. Decide how successful/error
  response receipt is distinguished from local deadline/cancel completion. Do not
  invent a server response parent for a local timeout or use execution.end as send
  unless the implementation actually enforces that ordering.
- Analyzer-visible coverage/loss information. Current I4's “drop marker” has no wire
  representation. A drop counter only visible to operators cannot certify coverage.

These may need new control records or an explicit auxiliary observation protocol and
versioned schema. Define the minimum design only after the gRPC spike; do not hide
these facts in `execution.lease` or add an eleventh event without contract review.
The extra observations are monitored-system evidence, never truth-recorder inputs.

## A-3 — Guard both confirmations

Proposed replacement for the control flow in design §5.4/proposal §8.3:

```text
evaluate(observations, declared_scope, fixed_query, evaluation_context):
    if required deployment instrumentation/identity contract is absent:
        return INSUFFICIENT_OBSERVABILITY, named defect
    construct causally closed, certified observation window
    if required completeness or initial-state evidence is missing:
        return CANDIDATE_INCONCLUSIVE, named missing evidence
    construct graph retaining UNKNOWN state explicitly
    reduce only with observed progress witnesses
    if whole queried scope is covered and residual is empty:
        return CONFIRMED_NO_DEADLOCK
    if a closed witness has complete coverage and certified persistence >= tau:
        return CONFIRMED_DEADLOCK, witness
    return CANDIDATE_INCONCLUSIVE, named missing evidence
```

This is intentionally conservative: a whole-scope guard can defer a locally provable
positive when unrelated coverage is missing. Any later local optimization needs a
proof of the smaller confirmation scope. A negative result is about the queried
scope/cut, not a claim about every possible cut in the whole run.

The four existing labels can remain, but requirements currently describes candidate
inconclusive only with a nonempty residual. Clarify how transient missing evidence with
no reconstructible candidate is reported; do not pretend that it establishes absence.
Unknown owners/counterparts and expired leases cannot serve as progress witnesses.

## A-4 — Time, evidence intervals and replay

Specify a query horizon, recorded analyzer receipt/evaluation times, monotonic clocks
for local durations, and the physical reference/skew assumptions. Do not change the
default parameters merely to make tests pass. With endpoint errors bounded by ε against
a reference, use `[max(a_i+ε), min(b_i-ε)]` as guaranteed overlap. A bound only on
inter-host offsets does not itself bound clock rate or establish that reference.

Compute persistence from uninterrupted transition histories for the same witness
(including invocation identities), over a certified window. A lease observed at t
reports evidence at t; expiry t+L does not assert that the execution cannot terminate
before t+L. A lease tied to acquire does not by itself identify the current block.

State determinism as a function of the complete recorded observation/query context;
compare event permutations at identical certified cut and evaluation horizon. Restrict
evidence-removal properties to that same query; dropping events may otherwise change
which historical cut is selected. The two confirmed labels are incomparable: absent
formal joins/meets, call the scheme an information order rather than a mathematical
four-element lattice.

Derive L1 separately from a practical detection-latency bound, accounting for lease
availability/renewal, scanner and evaluation scheduling, Δ and processing backlog.
The τ+Δ+2ε expression is currently an obligation, not a measured 3.1s guarantee. Either
prove it for the chosen protocol or revise the proposal explicitly. Unknown violations
of Δ/ε cannot always be detected; guarantees remain conditional. Fault experiments
outside assumptions measure behavior rather than prove universal safe degradation.

## A-5 — Residual versus irreducible witness

Simplest proposal: report the complete residual as an **evidence set**, explicitly
including downstream-blocked dependents and potentially multiple deadlocks, and remove
irreducibility claims. Preserve the JSON `core` key if its wire structure is unchanged,
but review its documented semantics and localization ground-truth target together.
If a small root witness is central to the paper, specify a separate deterministic
witness extraction algorithm, recheck closure and persistence, and prove the claimed
minimality notion. Ordinary SCC extraction alone is not enough for capacity closure.

## A-6 — Instrumentation lifetime and concurrency

Trace and specify stream admission, queue entry, logical handler execution, response
send, worker exit and capacity release. Use the early transport hook only for what it
actually observes. If an application-level executor gate is necessary, explicitly
amend the deployment model; do not describe it as transparent instrumentation of the
original gRPC executor.

Synchronize every instance-local transition, sequence reservation/publication and
lease snapshot coherently. Document bounded critical sections and lock ordering. I4
must distinguish no waiting for telemetry I/O/queue space from the short state-lock
contention already required by I1; “never blocks” cannot literally coexist with locks.

Cancellation requests stop eligibility for a closed wait witness but do not free a
worker. Define queue-cancel versus assigned-cancel identities, terminal idempotence,
legitimate cancel+block.end pairs, transport rejection before acquire, and release only
after actual task exit. A lease scan racing a close must not publish a renewed wait
after that close. No exception or callback may bypass release accounting.
