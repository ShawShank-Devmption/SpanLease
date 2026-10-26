# Decision log (append-only)

One entry per resolved ambiguity or deviation. Never edit or delete past entries —
supersede with a new one. Format: `D<n> | date | decision | why | affects`.

An LLM or dev who finds code deviating from `design.md` checks here before "fixing" it.

---

**D1 | 2026-09-06 | Repo root = Gradle root.** No `spanlease/` wrapper directory. The
proposal PDFs and `build_deck.py` stay tracked at root; deck outputs (`output/`) are
gitignored. *Why:* one less path level in every config/CI reference; the repo is
single-purpose. *Affects:* design.md §2 layout.

**D2 | 2026-09-06 | Dependency versions pinned in `gradle/libs.versions.toml`.** No
version literals in build files; grpc/protobuf/OTel bumped only together. Pins are
known-good at bootstrap; T0.1 verifies latest compatible patches once. *Why:* three
parallel LLM streams must not each pick their own grpc/protobuf combo. *Affects:*
design.md §2.1, LLM system prompt rule 1.

**D3 | 2026-09-06 | Parameter defaults fixed in design.md §4.1** (τ=2000ms, Δ=1000ms,
ε=50ms, lease age/renewal 500ms, expiry_factor 3, eval interval 250ms, k=2, deadline 30s)
with startup-checked constraints. *Why:* the litmus tests are sensitive to the
relationships between these; unpinned they'd be guessed per-module. *Affects:* every
module's config class; eval sweeps override them explicitly.

**D4 | 2026-09-06 | Testbed proto frozen** at
`testbed/src/main/proto/spanlease/testbed/chain.proto`: one generic blocking-unary
`Chain.Call` service deployed three times, chains expressed as data (`remaining` hops).
`request_id` in the body is workload identity; SpanLease `invocation_id` travels only in
gRPC metadata. *Why:* cross-lane artifact; per-scenario server code would multiply
integration surface. *Affects:* T0.4 (now: wire into build, don't design).

**D5 | 2026-09-06 | Synthetic scenario schema frozen** at
`analyzer/src/test/resources/replays/SCHEMA.md` (+ worked example
`three_cycle_deadlock.yaml`): true history and delivery faults declared separately;
`local_seq` implicit from listing order; every scenario states its expected verdict, and
inconclusive expectations name the gap. *Why:* this is the interface between Dev A's
fault scenarios and Dev C's tests. *Affects:* C1.1, golden replay tests, A2.2.

**D6 | 2026-09-06 | Hosting: remote is a GitHub repo (private), CI = GitHub Actions**
(`.github/workflows/ci.yml`, with bootstrap guards so it passes before the Gradle wrapper
lands). Repo initialized locally; the team creates the remote and pushes at kickoff —
remote URL to be appended here as D7 when created. *Affects:* tasks.md working
agreements.

**D7 | 2026-09-19 | Specification and publication review recorded; frozen contracts
unchanged.** Added literature review, requirements traceability/counterexamples and
proposed semantic amendments in `docs/`. Corrected gRPC interception and OTLP overload
guidance; added feasibility, completeness, proof, baseline and publication gates to
design/tasks. Proposal conflicts are marked unresolved, not silently adopted. Event
and verdict tables, proto and replay schema remain unchanged; no new dependencies or
schema-version bump. *Why:* current design can mistake absent evidence for progress,
does not establish completeness/persistence, and overclaims core irreducibility.
*Affects:* design §§5–6,9–10; tasks phase-0 gates and acceptance criteria. This entry
supersedes D6's reservation of identifier D7 only; the remote URL is still unrecorded.

**D8 | 2026-09-19 | User-requested final documentation baseline supersedes proposal-r2
defects explicitly.** Rewrote requirements/design/tasks and AGENTS, leaving CLAUDE.md
unchanged as requested. Adopted an explicit application worker gate separate from gRPC
callbacks; logical blocking-helper boundaries; qualified reference-clock intervals;
complete-prefix startup/checkpoint protocol; response causality; cancellation distinct
from actual exit; residual evidence-set semantics; S2 and conditional L1. The proposal
PDF and earlier reviews are historical motivation/rationale, not overrides of the new
baseline. *Why:* the user requested consistent final implementation documents after
the review rather than contradictory historical algorithms with warning notes.
*Affects:* requirements.md, design.md, tasks.md, AGENTS.md.

**D9 | 2026-09-19 | Contract v2 documented as review candidate, not silently frozen.**
Design specifies thirteen record types (ten revised lifecycle records plus resource.init,
instance.checkpoint and rpc.response.sent), reference/monotonic timestamps, manifest/
envelopes and revised verdict JSON. Added explicit approval gate G0 and v1 replay
migration task; all-developer sign-off is not yet recorded. Existing proto, catalog,
replay schema/example and runtime files are unchanged. *Why:* finalizing the proposed
documents does not fabricate the three developers' required contract approval.
*Affects:* design §§4/8; B-CONTRACT, C-CONTRACT and G0 in tasks.md.

**D10 | 2026-09-19 | Three accountable lanes with explicit dependency graph.** A owns
system/truth/runs, B instrumentation/telemetry/packaging, C analyzer/proof/statistics.
Replaced provisional week estimates with prerequisites, accepted handoff artifacts and
G0–G3 gates. *Why:* reduce blocked work and prevent consumers implementing guessed
interfaces. *Affects:* tasks.md and AGENTS.md; former task IDs are historical.

**D11 | 2026-09-21 | A-BOOT is build compatibility, not runtime feasibility.** Add five
Java subprojects, catalog-pinned Gradle wrapper/checksum, Java 21 toolchain,
protobuf/grpc generation, formatting, real JUnit/jqwik tests and classpath boundary
checks. `eval` remains a script workspace. Use grpc generator `@generated=omit` to
avoid a legacy annotation dependency; explicitly pin the test-only JUnit Platform
launcher. CI runs build unconditionally; I-CI will add real integrationTest rather
than a no-op task. *Why:* provide executable producer prerequisites without inventing
B's APIs or implying G0/G2 acceptance. *Affects:* build/catalog/CI, design §2, README.

**D12 | 2026-09-21 | A-SPEC delivered as reviewable behavioral handoff.** Added
scenario/truth lifecycle, independent identities, onset/latency bounds, exact example
residuals and bare/instrumented equivalence. Proto post_hold remains before reply;
response-before-exit uses a separate diagnostic lifecycle hook. B/C review is pending,
not fabricated. No event/report/proto/replay wire change. *Why:* consumers need explicit
physical semantics and truth independence before proposing production interfaces.
*Affects:* docs/scenario-truth-spec.md, design §6.4, tasks.md, AGENTS.md.

**D13 | 2026-09-22 | One implementer may execute all three role lanes while ownership
and gates remain distinct.** The project owner directed solo implementation for the
current MVP. Dev A/B/C labels continue to identify responsibility, handoff direction and
the review perspective that must be exercised; they do not imply three different people
during this solo phase. A role review by the sole implementer is recorded as such and is
never described as independent review. Contract G0 still requires completion and review
of every listed prerequisite before v2 is frozen; later G1–G3 evidence gates are not
waived. *Why:* permit sequential implementation without fabricating additional
contributors or collapsing module/truth boundaries. *Affects:* interpretation of role
assignments and approvals in tasks.md; no wire, predicate or architecture change.

**D14 | 2026-09-22 | G0 schema v2 frozen by solo role-based review.** After the D13
review model, the sole implementer accepted A-BOOT, A-SPEC, C-MODEL, R-LIT and executable
B-FEAS evidence, then reviewed the B/C contract decisions recorded in
`docs/stage0-g0-review.md`. Version 2's thirteen event payloads, JSON replay/report
direction and explicit v1 incompatibility are now the implementation baseline. This is
not independent three-person review and does not satisfy G1–G3. *Why:* the project owner
authorized one implementer to proceed across all three preserved responsibility lanes.
*Affects:* B-TYPES/C-TYPES and subsequent v2 implementation; wire-visible changes still
require a later schema bump and contract review.


**D15 | 2026-10-26 | The live analyzer runs in a separate JVM.** The testbed resolves
analyzer runtime artifacts only to launch `LiveAnalyzer`; those classes never join the
testbed compile/runtime classpaths. The analyzer receives the manifest and custom OTLP
observations, never handler truth or expected verdicts. *Why:* preserve the module and
truth boundary while exercising the receiver and report protocol. The current services
and driver share one testbed JVM but represent distinct configured instance epochs;
this is not evidence of a multi-host deployment. *Affects:* A-HARNESS, testbed launch
configuration, live demo and integration litmus tests.

**D16 | 2026-10-26 | One recorder orders each instance epoch's entire event stream.**
Share one `InstanceRecorder` across its gates, RPC hooks and scanner. Its lock serializes
state transitions, sequence allocation and immediate publication attempts. Failed offers
retain their sequence positions. The demo's logical instances have separate recorders;
do not create multiple allocators for the same instance ID. *Why:* independently
numbered hook streams cannot certify a contiguous process prefix. *Affects:* I1/I4,
B-GATE/B-RPC/B-SCAN and prefix certification; no schema change.

**D17 | 2026-10-26 | Decode present unset OTLP values only for expected empty strings.**
The pinned SDK's empty-string attribute can arrive as `AnyValue.VALUE_NOT_SET`. Accept
that representation only when the validated JSON field is empty. Continue rejecting
absent attributes, duplicates, wrong types and nonempty disagreements. *Why:* support
the actual SDK serialization without manufacturing missing identity or causal evidence.
*Affects:* C-INGEST and empty-attribute decoder tests; no wire field or version change.

**D18 | 2026-10-26 | Unjoinable inbound calls use local external identities.** Missing,
malformed or multiply supplied invocation metadata or caller references cannot establish
a monitored causal join. The adapter assigns a fresh local identity and emits no joined
arrival; admission/ownership still remain observed. A concurrently reused valid ID is
rejected by the gate rather than admitted twice. *Why:* support entry calls from outside
the monitored deployment without guessing a caller dependency. This is not evidence
that a missing counterpart for a monitored caller can be discharged as progress.
*Affects:* B-RPC/R14, application integration; no schema change.

**D19 | 2026-10-26 | Complete the design's temporal witness search in the MVP.** Evaluate
finite endpoints at assertion-duration and epsilon-narrowed lease boundaries in latest
first order. For each endpoint, keep members with uninterrupted tau coverage and an
eligible matching lease, then reapply closure pruning. Keep every survivor at the latest
qualifying endpoint and report exactly `[b−tau,b]`; use the lease actually covering b in
unit evidence. Certified observations after a retracted cut may bound its assertions;
terminal endpoints remain half open on the millisecond timestamp grid. Temporal pruning
to empty is inconclusive, never negative. *Why:* a newer dependent or disjoint ring must
not reset an older closed witness, and a lease outside the selected endpoint cannot
justify it. *Affects:* C-TIME/R9, analyzer evidence/report selection; v2 report shape and
predicate unchanged. C-PROOF and general historical-cut search remain separate work.


**D20 | 2026-10-26 | Share the physical gate between bare and instrumented runs.**
An optional recorder disables sequence allocation, DTO construction, RPC metadata and
scanners while retaining identical admission, cancellation, worker and cleanup paths.
A separate `GateObserver` receives application-owned job tokens and physical slot
indices. The bounded truth recorder allocates its own epoch/job/ordinal identities;
evaluator mappings are stored separately and never enter truth or analyzer input.
Truth overflow is unscorable. *Why:* compare one execution architecture and observe
actual gate decisions even when telemetry is off. *Affects:* A-TRUTH/B-INTEGRATE; no v2
wire change. Local truth clock bounds are declared relative to the shared host clock,
not an assertion of measured UTC or multi-host synchronization.

**D21 | 2026-10-26 | Fail closed on known suffix gaps and invalid causal identities.**
A known gap beyond an older checkpoint blocks either confirmation. Delayed receipt
cannot refresh source-age freshness. Validate request/response parent types and IDs,
source execution epochs, declared unit uniqueness, empty scope and clock-error bounds.
Expired leases cannot justify a progress-based negative with blocked owners. Report
actual initialization coverage, frontier gap ranges and unmatched counterparts. A
finite history budget permanently makes the run inconclusive when exceeded. *Why:*
old cuts, aliases and discarded facts must not manufacture complete observations.
*Affects:* C-CUT/C-REPORT/R5–R7/R9/R10/R12/R13/R15; no field/schema change.

**D22 | 2026-10-26 | Record replay context separately from the observation schema.**
The finite replay container's format version is 1; enclosed manifests/events/reports
remain v2. Ordered steps preserve batches, first receipt clocks and evaluation times.
The live analyzer atomically writes this journal before emitting each recorded report.
Historical v1 YAML is retained explicitly; new captured v2 JSON fixtures are separate.
*Why:* event payloads and source timestamps cannot reproduce receipt freshness or
online cut selection. *Affects:* C-REPLAY, live harness and golden fixtures.

**D23 | 2026-10-26 | Distinguish delivery faults from a physical crash.**
Seeded bounded fault rules alter copies of observations, never independent truth.
The crash regression launches the same ChainNode in separate service JVMs and kills
one abruptly; its last atomically exported truth snapshot is marked truncated. No
terminal checkpoint/end/release is fabricated. *Why:* dropping telemetry is not a
physical process crash. *Affects:* A-FAULT/I-FAULT/R13 and retained run artifacts.

**D24 | 2026-10-26 | Baseline replay and local pilots have limited evidence scope.**
Native SDK live/enriched span snapshots, completed spans, invocation-cycle/saturation
projections and triggered buffer retention are evaluated on identical delivered facts.
Their offline CPU is not independent live instrumentation overhead. Cycle/saturation
alarms are not confirmed deadlocks or an active DDMon reproduction. Pilot statistics
use independent runs, separate confirmation directions and explicitly unscorable
vector-cut comparisons. The hotel-search workload is a sourced path projection, not
DeathStarBench performance. *Why:* provide reproducible tools without upgrading
information comparisons, a small local sample or an argument into research acceptance.
*Affects:* baselines/eval, E-REALISM/E-PROTOCOL and pending G1–G3.

**D25 | 2026-10-27 | Recheck cancellation after unary admission.** A server context
can expire before the adapter installs its listener and submits the job. The earlier
callback has no queued job to remove. Recheck cancellation immediately after the
serialized admission decision; a queued job is removed and an assigned job keeps its
slot until actual exit. A real deadline-before-admission regression holds a separate
occupier and verifies no orphan queue entry. *Why:* listener registration alone does
not cover cancellation preceding readiness. *Affects:* B-RPC/B-INTEGRATE/R3; no wire
or cancellation/release semantic change.
