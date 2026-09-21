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
