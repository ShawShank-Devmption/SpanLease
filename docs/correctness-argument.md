# Correctness argument and implementation obligations

2026-10-26. This is a reviewable argument for the finite-run supported model, not
independent proof acceptance, a machine-checked proof, or a claim about hidden dependencies.
It applies under requirements A1–A6. The cited functions are in `AnalyzerEngine`.

## State and evidence

Each instance has a sequence-indexed immutable history. Identical duplicates do not
change first receipt times. Conflicting duplicates invalidate the instance. `certify`
chooses a contiguous checkpoint prefix; any known later gap blocks confirmation as
well. Missing or stale idle checkpoints, insufficient clock qualification and future
checkpoint timestamps are missing evidence. Waiting does not fill a sequence hole.

`causalCut` monotonically decreases prefix positions until each included parent is
included. Arrival parents must be sends for the same invocation and selected binding;
response-completion parents must be responses for the same invocation. A retraction
removes the child and all its later local observations, preserving local prefixes.
The finite sum of prefix lengths decreases whenever another iteration is necessary,
so this procedure terminates. It computes a downward-closed cut under the two message
relations plus local order, provided A2 faithfully records those relations.

`reconstruct` replays each local history. Local slot transitions commute with other
instances' transitions except identity joins; A2 uniqueness makes those joins order
independent. Init supplies every declared unit's initial free state. Acquire installs
ownership; only release after actual end removes it. Cancellation and lease expiry
never remove ownership. An unmatched counterpart is unknown, not progressing. A
cancel-pending blocked owner makes the query inconclusive. Lease references must
match the current acquisition, block and invocation. Cross-instance execution records
and reused invocation IDs are invalid evidence.

These statements rely on instrumentation correctness and complete declared scope,
not on the analyzer detecting all possible corrupt or unsupported inputs. The
analyzer is not a Byzantine history verifier. Runtime hooks and real SDK regression
tests are evidence about implementation fidelity, separate from this model argument.

## Reduction lemma

Let B contain all observed blocked executions and queued requests. Define F(X) as the
members of X whose required dependencies are all in X: a blocked execution's one
counterpart; or every eligible unit owner of a queued request. A free unit makes that
queue request fail F. Unknown dependencies never reach this step.

F is monotone in X and F(X) is a subset of X. Repeated deletion in `pruneClosed` reaches
a fixed point in at most |B| successful deletion steps (each scan may remove several).
For any nonempty closed set W contained in B, induction shows W remains contained in
every intermediate X: deleting a member of W would require a missing dependency in W,
contradicting closure. Thus the terminal residual contains every possible W and is
itself closed when nonempty. This establishes greatest-set selection and independence
from deletion order. It does not establish minimum or irreducible root cause.

S2 follows for a complete supported cut: an empty residual cannot contain a nonempty
closed set. The negative verdict is restricted to that cut. Current matching lease
eligibility is checked before emitting a negative with blocked owners, per the
conservative whole-scope policy; no unknown or expired evidence is used as progress.

## Temporal selection and S1

For each current assertion m, history supplies its opening a and first later closing
c, or a certified checkpoint endpoint if it remains open. Qualified error at most ε
gives a guaranteed interval starting at a+ε and ending at c−ε, excluding the terminal
boundary. The implementation uses c−ε−1 on its integer millisecond grid. For an
execution, both ownership and its exact open block must hold; its lower bound is the
maximum of their starts. A new block clears old leases and duration state.

At endpoint b a member is eligible only when its guaranteed interval contains
[b−τ,b], and every execution has a matching lease with observed+ε ≤ b ≤ expiry−ε.
A lease is an endpoint eligibility observation, not evidence of future physical
blocking. Continuous histories establish duration. A retracted cut may use the later
certified suffix to find a closing event or matching lease; it never crosses a closing
event, even when the execution subsequently blocks again.

Applying the reduction lemma to the eligible subset yields a closed W all of whose
same assertions hold throughout [b−τ,b]. Every dependency required by W is also in W
for that interval. Therefore W witnesses the requirements predicate, giving S1
conditional on the state/evidence lemmas and A1–A6. If temporal pruning empties W,
only inconclusive is justified; the ordinary cut residual has not become progress.

Eligibility changes only at assertion start+τ/end or narrowed lease start/end. On the
integer grid, the right endpoint of any nonempty jointly feasible region is one of
those finitely many endpoints. Testing them latest-first therefore finds the latest
qualifying endpoint for the cut's current assertions. It is not a search over every
possible historical cut or every dependency that has already disappeared at this cut.
All surviving closed members at that endpoint are retained.

## Liveness and limits

Suppose a qualifying closed set remains present, with duration at least τ+2ε,
complete deliveries, recurring checkpoints and matching leases, qualified clocks,
and fair scanner/export/analyzer scheduling. Eventually a certified cut includes all
openings and an endpoint lies in the guaranteed history and narrowed lease ranges.
Monotone pruning cannot remove that set. Fair evaluation therefore reports it.
A finite numeric delay additionally requires measured scheduling/processing bounds;
none is inferred from default periods. The design §5.7 expression remains a budget
hypothesis until those bounds and lease/checkpoint feasibility are measured.

The implementation retains complete finite histories up to a configurable engine budget
(default one million unique events). Exhaustion permanently returns HISTORY_LIMIT
inconclusive for that run; retained state is never used to confirm after discarding new
facts. It has no streaming compaction proof or indefinite-run liveness guarantee. Physical timestamps at millisecond precision,
application instrumentation perturbation, supported unary semantics, and qualified
clock evidence remain scope restrictions. Loss or a crash may prevent any new
confirmation indefinitely. A historical positive does not authorize remediation.

## Executable evidence

`ReducerExhaustiveTest` compares production reports with independent subset enumeration
for every state in bounded one/two-slot, one/two-pool configurations, and three one-slot
pools (409 complete capacity snapshots). Choices per slot are free, running, or blocked
on a distinct queued call at any pool. These are reducer model snapshots; not every
snapshot must be physically reachable under immediate gate assignment. The suite is
separate from a proof and does not exhaust histories or time.

`AnalyzerRaceTest`, `AnalyzerPersistenceTest`, cancellation/lease tests and live
truth-oracle matrix cover gaps, fixed-context permutations, epoch reuse, causal joins,
duration changes, skew/lease boundaries, multiple rings and newer dependents. Real
gRPC, faulted SDK/OTLP and replay tests exercise the system boundary. Remaining formal
review, multi-host qualification and broader experimental generalization are tracked
in tasks.md; this document does not certify those tasks by itself.
