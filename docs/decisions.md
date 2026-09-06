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
