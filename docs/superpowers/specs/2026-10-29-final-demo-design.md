# Final demo: synthetic scenarios + live Online Boutique store

Date: 2026-10-29. Status: owner decisions recorded below, plan in
`docs/superpowers/plans/2026-10-29-final-demo.md`. Implementers: two developers
(Dev 1 = Boutique runtime, Dev 2 = website, synthetic litmus CI and docs).
This supersedes the scope section of
`2026-10-27-online-boutique-adservice-design.md` (Ad-Service-only, no frontend). All
of its other rules still apply: native wire contract, attribution, fault-header rules,
I1–I5 and the no-contract-change boundary.

## Intent

The owner wants one final demo that shows SpanLease working end to end on
(1) **synthetic data**: the existing controlled Chain scenarios, and (2) **live gRPC
calls from Google's Online Boutique running locally**, with the browser monitor made
simpler and visually flat. The demo must work for real, with no mocked traffic and
no scripted verdicts, so it can be presented as a fully implemented system.

## Owner decisions (2026-10-29)

| Question | Decision |
|---|---|
| Boutique scope | **Full store in Docker.** Google's real frontend, catalog, cart, checkout, etc., plus the loadgenerator, run locally via Docker Compose. Their `GetAds` calls reach SpanLease-instrumented Java Ad Service replicas. |
| Boutique deadlock | **Injected, store degrades.** The operator clicks *Inject fault*. A driver root call with no deadline forms `ads-a → ads-b → ads-c → ads-a`. Real store `GetAds` calls then queue behind it, ads disappear from the shop page, and SpanLease names the exact workers. |
| "Synthetic data" | **Chain scenarios**: closed ring, spare capacity, slow progress. These are real gRPC calls on a synthetic topology and already work. No new replay mode. |
| Plan scope | **Demo + litmus CI.** All demo work, plus the two mandatory real litmus tests running in `integrationTest` and CI. G1–G3, baselines, evaluation and the paper stay in the backlog. |
| Website | Simpler, with one flat monotone theme (no gradients). |

## Goals

1. `./gradlew :testbed:monitorDemo` plus one Docker Compose command gives a single
   page with two sources: **Synthetic (Chain)** and **Online Boutique (live store)**.
2. Boutique live mode: real shop traffic (loadgenerator plus a human browser at
   `http://localhost:8080`) produces `GetAds` calls that appear in the monitor in real
   time and get detector verdicts. Healthy traffic never produces `CONFIRMED_DEADLOCK`.
3. Boutique deadlock: after *Inject fault* the monitor shows **DEADLOCK DETECTED**
   with exactly three executions/units (ads-a, ads-b, ads-c) and the queued ads-a
   callback. The shop page stops showing ads. Killing the workload PID ends it and
   keeps the evidence.
4. Boutique spare capacity: the same injection with ads-a at two slots completes,
   gives no confirmed deadlock, and the store's ads come back.
5. The synthetic Chain scenarios behave exactly as today.
6. `./gradlew integrationTest` runs the two litmus tests (closed ring →
   `CONFIRMED_DEADLOCK` with exact core; spare capacity → `CONFIRMED_NO_DEADLOCK`)
   on every push to main and on every PR.

## Non-goals

- Instrumenting the Go/C#/Node/Python Boutique services. Only the Java Ad Service
  path is monitored. The rest of the store is unmodified upstream.
- Claiming that stock Boutique deadlocks. The cycle is an injected, labelled fault.
- A synthetic telemetry replay mode, ML/RCA, remediation, or an automatic stop button.
- G1–G3 acceptance, A-TRUTH, baselines (E-*), the evaluation matrix or the manuscript.
- Any change to the event/manifest/report/replay schema, analyzer semantics or `common`.

## Anti-goals (failure even if it "works")

- Any verdict computed or edited in the browser or harness, or any canned/mock data
  presented as live.
- Monitored arrivals without SpanLease request metadata. That gives permanently
  inconclusive verdicts, and "fixing" it by relaxing the analyzer would violate the
  contract.
- A deadline or cancellation that silently breaks the injected cycle. The frontend's
  100 ms ad deadline must never reach the fault route.
- Wording that claims G1–G3 acceptance, soundness proofs, timing guarantees or "first"
  novelty. "Implemented and demonstrated end to end" is accurate; "validated" or
  "proven" is not.

## Architecture

```
 Docker Compose (upstream images, pinned v0.10.7)           host JVMs (this repo)
 ┌──────────────────────────────────────────┐   ┌───────────────────────────────────────┐
 │ loadgenerator → frontend :8080 ─────────┐│   │ workload JVM (killable PID)           │
 │ cart+redis, catalog, currency, checkout, ││   │  BoutiqueEdge :9555 (driver role)     │
 │ payment, email, shipping, recommendation ││   │   └─RootCaller→ ads-a ⇄ ads-b ⇄ ads-c │
 └──────────────────────────────────────────┘│   │  fault driver (Inject → hops=3)       │
        AD_SERVICE_ADDR=host.docker.internal:9555 │      │ custom OTel LogRecords / OTLP  │
                                                 └──►│      ▼                                │
                                                     │ analyzer JVM ── reports ──► monitor   │
                                                     │ monitor :8088 (browser UI)            │
                                                     └───────────────────────────────────────┘
```

### Why an edge in the driver role (decided by the design, not the owner)

Design §4 requires every monitored arrival to carry `sl-invocation-id`,
`sl-caller-instance` and `sl-caller-seq`. The Go frontend sends none of them, so direct
calls would be invalid evidence. `BoutiqueEdge` is a plain gRPC `AdService` server on a
fixed port, 9555. It is not gated and is not a monitored resource. For each frontend
`GetAds` it forwards the request unchanged to `ads-a` through the run's existing driver
`RootCaller`, inside the incoming call's gRPC `Context`, so the frontend's 100 ms
deadline and cancellation propagate to `ads-a`. This is exactly the design's driver: it
emits root sends and checkpoints, never execution-scoped events. Independent page views
have no causal edge between root responses and later root sends (design §4,
"unrepresented causal edge" rule). No contract change.

### Fault route stays separate from store traffic

- The edge never adds the diagnostic header. Store calls are always local ad handling.
- *Inject fault* sends one driver root `GetAds(kitchen)` with `x-spanlease-boutique-hops: 3`
  and **no deadline** (deadlock mode) or the existing 30 s deadline (spare mode). It
  goes straight to `ads-a`, not through the edge, so the frontend deadline can't cancel it.
- Fault forwarding is enabled at deployment start (before admission opens) for the two
  fault modes. The header rules of the 2026-10-27 spec are unchanged.
- **Required fix:** today an absent header and `hops=0` look the same, so in spare mode
  every store call would do the 4 s diagnostic leaf work. Only a *present* header value
  `0` may run leaf work. Absent always means immediate local handling.
- `HandlerJournal` must not grow without bound under continuous store traffic. Record
  only fault-route calls (header present).

### Boutique modes (all run until the operator kills the PID)

| Scenario id | Capacities a/b/c | Fault forwarding | Inject | Expected |
|---|---|---|---|---|
| `boutique-live` | 1/1/1 | disabled (header rejected) | not offered | Live store traffic, ads shown, no `CONFIRMED_DEADLOCK` ever |
| `boutique-deadlock` | 1/1/1 | enabled | no-deadline root, hops 3 | `CONFIRMED_DEADLOCK`: 3 executions/units + queued ads-a callback; store ads vanish |
| `boutique-spare-capacity` | 2/1/1 | enabled | 30 s root, hops 3, 4 s leaf | Completes; no confirmed deadlock; ads vanish ≤ ~4 s then return |

Store calls that queue at `ads-a` during a fault are cancelled by their 100 ms deadline.
The gate already removes queued cancelled jobs (`SlotTrackingExecutor.requestCancellation`),
so they show up as upstream dependents or cancellations, never as part of the core.

### Monitor ↔ workload control

The workload JVM already reports to the monitor through stdout lines (`DIAGNOSTICS`,
`COMPLETE`). Add:
- `STORE {json}` every 500 ms: forwarded/ok/deadline/error counts, the edge port, and
  the last ≤ 5 ads served (operator display only, never analyzer input).
- `INJECTED {json}` once the fault root is sent; `COMPLETE` when it finishes.
- monitor → workload: one `INJECT` line on the child's stdin. This is the only accepted
  command, honoured once per run and only in the fault modes.
- HTTP: `POST /api/inject`, with the same control-token and same-origin checks as
  `/api/run`, accepted only while a Boutique fault-mode run is alive and not yet injected.

## Website

- One flat monotone theme from CSS tokens: neutral grays, one text colour and one
  border colour. No gradients, glows or decorative hero. Colour is reserved for verdict
  status: deadlock red, no-deadlock green, inconclusive amber, used on the verdict
  banner and badges only. Light and dark via `prefers-color-scheme`.
- Layout, top to bottom:
  1. Header (name + connection).
  2. One control bar: source toggle **Synthetic · Online Boutique**, scenario select
     filtered by source, *Start*, *Inject fault* (Boutique fault modes only), and *Open
     store ↗* (Boutique only).
  3. Source line (application, native method, run id).
  4. Verdict banner.
  5. Four metrics.
  6. Two columns. Left: services/slots, incoming calls, and a *Store traffic* panel
     (Boutique only). Right: evidence, process/PID/kill command, run activity.
  7. Collapsed `<details>`: event stream, OS/JVM/thread diagnostics, raw report JSON.
- Remove the hero copy and the in-page references panel (references move to the README).
- No Chain-only assumptions: the service list comes from the manifest, manual-stop text
  comes from explicit state, and hop counts show only for Chain.
- Response data is rendered with `textContent`. Only validated local `/product/...`
  paths become links (to `http://localhost:8080`).

## Constraints

- Java 21, existing catalog pins, no new Gradle dependency. Docker Desktop (with
  `host.docker.internal`) is a documented **demo prerequisite only**. Unit tests and CI
  never need Docker.
- Upstream images pinned to tag `v0.10.7` from `release/kubernetes-manifests.yaml` at
  commit `38e7348e…`. Record digests in `third_party/online-boutique/SOURCE.md`. The
  upstream `adservice` and `shoppingassistantservice` are not started.
- The edge binds 127.0.0.1:9555 by default, with a documented override if Docker
  Desktop can't reach loopback. The monitor stays loopback-only.
- No sleeps-for-sync in tests. Seeded RNGs. `analyzer` gets no new dependencies.

## Builder's calls (settled during review; owner may override)

1. **Run length and load.** A Boutique run is a demo session, with ≤ 30 minutes as
   guidance. Two hard limits apply:
   - The manifest's clock qualification is one hour, so no confirmation is possible
     after 60 minutes.
   - The analyzer keeps full history (design §5.4), so the loadgenerator runs at
     `USERS=3`, `RATE=1`.

   The rehearsal measures that report cadence stays ≤ 1 s over a 15-minute live run.
   History compaction and longer qualification are out of scope (analyzer change).
2. **Litmus "exact core".** Expected values come from the scenario topology and the
   manifest, never from analyzer output:
   - exactly three executions;
   - units equal to the three `…:app:slot-0` IDs;
   - every unit owned by a core execution;
   - exactly one queue entry, at svcA;
   - persistence ≥ τ.

   No partial independent recorder is built (that is A-TRUTH). The tests are recorded
   as **implemented, pre-G1, not accepted**. They do not mark I-LITMUS/I-CI done.
3. **Spare capacity needs a certified negative.** The Boutique spare-capacity process
   test requires a `CONFIRMED_NO_DEADLOCK` whose certified horizon lies between
   injection and fault completion, plus no confirmed deadlock. In the manual runbook a
   transient inconclusive is acceptable; a confirmed deadlock is a failure.
4. **Slots panel under live traffic.** Display history (2,000 observations) truncates
   within minutes of store traffic. The monitor keeps a separate display-only map of
   each declared unit's latest received acquire/release, bounded by the unit count. It
   is labelled "latest received observation per slot, not a certified snapshot". It
   never feeds the analyzer and never replaces report evidence.
5. **Edge restarts between runs.** The edge lives and dies with each run's workload
   PID. The rehearsal measures how long the Go frontend takes to reconnect after a new
   run starts. If ads don't return within 15 s, the runbook adds
   `docker compose restart frontend` after starting a run.

## Remaining backlog after this plan (reported, not planned)

A-TRUTH independent gate truth, A-FAULT/I-FAULT fault runs, bare/instrumented
equivalence, B-INTEROP SDK retry/partial-success, C-REPLAY archive, C-TIME full
endpoint search, C-PROOF, the G1/G2/G3 reviews, baselines module (E-*), eval workspace,
statistics, reproduction package and the paper.

## Verification (what "done" means)

1. `./gradlew build` and `./gradlew integrationTest` are green, and CI runs both.
2. Process tests (no Docker) cover the in-JVM edge calls, deadlock injection,
   spare-capacity completion, PID kill evidence retention, and a Boutique → Chain
   switch clearing Boutique state.
3. Manual runbook, recorded in `docs/final-demo-guide.md` with actual run IDs: compose
   up → live mode with ads on the shop page and calls in the monitor → deadlock inject →
   ads gone, DEADLOCK DETECTED, exact workers → kill PID → evidence retained → spare
   mode recovers → each synthetic scenario.
