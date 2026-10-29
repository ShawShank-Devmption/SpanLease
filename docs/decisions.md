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

**D15 | 2026-10-27 | Apply existing v2 whole-scope evidence guards before reduction.**
A fresh older checkpoint cannot hide a received sequence gap beyond its prefix;
report the known inclusive ranges and prevent either confirmation until the missing
records arrive. Likewise, check current-block lease eligibility for every blocked
execution in scope before progress reduction, including executions outside a surviving
positive residual. *Why:* review of `8430515` reproduced both confirmed-verdict paths
despite known gaps, negative confirmation despite missing/expired leases, and positive
confirmation hiding an unleased progressing branch. These are corrections to existing
design §§5.2–5.5, not a new predicate or wire contract. *Affects:* AnalyzerEngine,
R4/R5/R7/R12/R13 regression evidence and stage-1 status; no schema/dependency change or
G1–G3 acceptance. See `docs/stage1-correctness-review.md` for remaining blockers.

**D16 | 2026-10-27 | Use native Apple Silicon gRPC generation via the shared 1.76.0 pin.**
The project owner requested ARM-native generation and demo readiness. Upgrade all
catalog-managed grpc-java runtime/compiler artifacts together from 1.68.1 to 1.76.0,
whose official macOS generator includes both arm64 and x86_64 slices. Keep Gradle's
platform classifier selection for other hosts. Review protobuf 4.28.3 and OTel 1.45.0
compatibility with the clean build, SDK/OTLP tests and live demo rather than changing
unrelated pins without evidence. *Why:* the old osx-aarch_64 artifact was x86_64-only
and failed without Rosetta; a native upstream artifact avoids a system installation
or a machine-specific compiler override. *Affects:* dependency catalog and native-build
verification; no schema or detector-semantics change. Release evidence:
https://github.com/grpc/grpc-java/releases/tag/v1.76.0.

**D17 | 2026-10-27 | Validate causal bindings and local lifecycle before confirmation.**
The analyzer now rejects received wrong-type/wrong-invocation request or response
parents, wrong service bindings, send/arrival aliases and cycles in the union of
local order and exact causal edges. Reconstruction checks instance-local slot
ownership, repeated init/acquisition, current acquire/block/invocation lease binding,
and actual end before release. Invalid histories return insufficient observability;
missing parents remain a cut/completeness issue. *Why:* the G1 review reproduced
unsafe confirmations from structurally parseable but impossible histories. *Affects:*
C-INGEST/C-CUT and R6/R10/R14/R16 tests; this enforces existing v2 semantics and adds
no wire fields or schema version. G1 is not accepted by these tests alone.

**D18 | 2026-10-27 | Prune temporal witnesses and use explicit monotonic receipt context.**
At the currently selected certified endpoint, remove members whose uninterrupted
assertions are too young and reapply closure; an empty temporal witness is inconclusive,
never a negative verdict. Positive cores name the surviving old component rather than
young upstream/disjoint members. Online evaluation and checkpoint freshness now use
analyzer-local monotonic time; wall time remains report/qualified-interval context.
The one-argument evaluation helper is only for synthetic receipts sharing its origin.
*Why:* production had a whole-residual persistence timer and wall-based receipt ages.
*Affects:* C-TIME, fixed-context tests and a 64-history independent production-oracle
comparison. Full historical endpoint search and the production proof remain pending;
no claim of complete C-PROOF or G1 acceptance.

**D19 | 2026-10-27 | Expose the existing live demo and analyzer entry points.**
Add scenario selection and 2/3/5-service Chain routes, including a finite slow-progress
control, and a Gradle liveAnalyzer launcher for the existing CLI. Preserve the original
default demo pair and positional output directory. *Why:* the owner requested showing
live scenarios and how to integrate their application. *Affects:* A-TESTBED/demo
usability and application documentation; controlled Chain traffic is not an independent
realistic workload or G1–G3 evidence, and no new wire/control observation is introduced.

**D20 | 2026-10-27 | Separate manually stopped monitor workloads from finite experiments.**
The owner requested an explicit retained deadlock label, exact workers, OS/JVM
diagnostics and a PID they can terminate. The monitor owns a separate workload JVM
and analyzer JVM. The interactive closed ring has neither an RPC deadline nor an
automatic observation stop; the finite CLI and control scenarios retain deadlines.
Actual workload exit changes the application lifecycle to stopped, never invents a
negative detector verdict, and never erases historical evidence. JVM/thread samples
are timestamped operator diagnostics, collected outside worker I/O paths and never
used as detector inputs. *Why:* cancelling the old eight-second run legitimately
broke the cycle but made its latest negative cut appear to contradict the earlier
confirmation; sharing the monitor PID would also make manual termination kill the
page. *Affects:* Dev A monitor/testbed usability and diagnostic regression tests;
no event/manifest/report/replay schema change, no pre-deadline timing claim for the
manual mode, no native-thread/OS-lock inference and no G1–G3 acceptance. See
`docs/live-monitor-design.md` for lifecycle, retention and qualification limits.

**D21 | 2026-10-29 | Run the upstream store against a driver-role Boutique edge.**
The owner selected the full upstream Online Boutique store in Docker, with only its
Java Ad Service path instrumented by SpanLease. A plain native `GetAds` edge supplies
driver root metadata because the Go frontend sends no `sl-*` request references;
incoming Context deadlines/cancellation propagate through the existing RootCaller.
Store calls never carry the diagnostic header. A separate one-shot diagnostic root
creates the configured callback cycle, with no deadline in deadlock mode. Absent
headers skip diagnostic leaf work and journal entries. This supersedes only the
Ad-Service-only scope of the 2026-10-27 Boutique specification. *Why:* demonstrate
live upstream traffic while preserving design §4 driver semantics and causal joins.
*Affects:* testbed Boutique deployment and demo configuration; no schema change,
G1–G3 acceptance or E-REALISM evidence is implied.

**D22 | 2026-10-29 | Flat monotone operator UI and references in README.**
The owner requested a simpler monitor layout with explicit light/dark flat tokens,
collapsed event/diagnostic/raw details and source-specific controls. Move the three
research links from the page to README without dropping their provenance or the
warning that publications are not the live feed. Preserve report-driven verdicts,
process controls and timestamped diagnostic distinctions; this presentation change
does not alter detector semantics or establish G1–G3 acceptance. The Boutique UI
requires the separately pending T1–T6 runtime handoff. *Affects:* final-demo T7/T8,
static monitor assets and README; no detector wire contract change.

**D23 | 2026-10-29 | Automated litmus regressions precede formal acceptance.**
Add the two real gRPC → OTLP → analyzer litmus tests to Gradle `integrationTest` and
invoke that suite in CI. Expected closed-ring units and queue location come from the
scenario topology and manifest instance IDs, never analyzer-produced expected values;
the spare-capacity test requires completion and a certified negative during leaf work.
These are **implemented, pre-G1, not accepted** end-to-end regressions, with no
independent A-TRUTH recorder. CI configuration is not evidence that a remote CI run
passed. Actual command results belong in verification records. Formal I-LITMUS/I-CI
and G2 acceptance still require the G1 review and existing task prerequisites; G1–G3
remain pending. *Affects:* final-demo T9 and CI, not the analyzer's confirmation rules.
