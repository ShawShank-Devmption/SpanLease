# Final demo — Dev 1 handoff

Date: 2026-10-29. Baseline: `72a2051` on `origin/feat/stage1-mvp`.
Implementation branch: `feat/final-demo-dev1` in `.worktrees/final-demo-dev1`.

## Delivered

Dev 1 T1–T6 from `docs/superpowers/plans/2026-10-29-final-demo.md` are implemented.

- T1: explicit application kind; six named scenarios; Boutique store/injection fields
  and a manifest-bounded `unit_latest` display projection independent of display
  history truncation. Beginning a Chain run clears Boutique state.
- T2: absent diagnostic metadata is distinct from explicit hops=0. Store calls skip
  diagnostic work and journal entries. Explicit leaf work observes its complete
  monotonic duration, including the final fractional millisecond.
- T3: native GetAds edge, bounded recent-ad counters, three instrumented replicas,
  driver recorder/checkpoints and one isolated diagnostic root. Incoming store
  Context propagates; fault injection uses a separate root Context and one-shot
  admission. Port conflicts identify the bind and clean up the deployment.
- T4: child STORE/INJECTED/COMPLETE output; INJECT stdin; shared authenticated,
  same-origin POST control; explicit edge-bind override; separate Boutique fault
  completion and workload exit. Application configuration is archived before launch.
- T5: real process evidence for healthy traffic, exact closed wait, spare capacity,
  PID kill retention, startup port conflict and Boutique-to-Chain switching.
- T6: full upstream Docker store with immutable image digests and measured local
  behavior. Provenance, commands, platform inventory and run IDs are in
  `third_party/online-boutique/SOURCE.md`.

No production files in common, analyzer or instrumentation changed. No detector
wire contract or dependency changed. D21 records the application architecture.
R1/R2/R3/R14/R16 responsibilities continue to use the existing gate/RPC hooks;
the existing lifecycle regressions and new native-wire/process tests passed.

## Verification evidence

- Baseline `./gradlew build`: 106 tests passed.
- Task-specific RED/GREEN checks preceded new runtime/control behavior; the process
  evidence tests exercise existing runtime behavior through real local RPCs.
- `./gradlew spotlessApply`, affected testbed and instrumentation checks passed.
- Three consecutive `./gradlew build --rerun-tasks` runs passed, each executing all
  36 build tasks: 129 tests, zero failures/errors/skips. Module counts: common 5,
  instrumentation 9, analyzer 56, testbed 59. Run durations: 1m50s, 1m49s, 1m46s.
- Final `./gradlew build integrationTest` returned BUILD SUCCESSFUL using the
  verified build outputs, but `:testbed:integrationTest` explicitly reported
  **NO-SOURCE**. There are no automated integrationTest litmus cases yet; those
  remain Dev 2 T9. This is not an integration-suite pass.
- `docker compose -f deploy/online-boutique/compose.yaml config --quiet` passed;
  image pulls and `up -d` succeeded for all eleven services on Apple Silicon.
- Real-store runs: `boutique-live-b07e9ea3`, `boutique-live-3a525a02`,
  `boutique-deadlock-e33254ec`, `boutique-spare-capacity-f21a43e9`.
  Chrome showed the healthy ad and exact deadlock workers. During the fault the
  product endpoint returned 200 with an empty ad section; after spare completion
  it returned the ad again. Deadlock evidence remained archived after PID kill.
- The 30-second outage/restart measurement exceeded the 15-second threshold:
  approximately 17.1 s after the first new edge sample. The runbook must restart
  frontend after each new Boutique run.

The final review is an author self-review because AGENTS.md prohibits delegation
without the user's explicit request. It is not independent review by Dev 2.

## Dev 2 handoff and remaining work

Use Task 1's exact snake_case snapshot fields and Task 4's `/api/inject` control
contract. `unit_latest` must supply the slot panel after the 2000-observation
display deque truncates; it is labelled as latest received observations, never a
certified snapshot. Returned ad text remains display-only.

T7–T10 remain Dev 2 work: flat UI, source/scenario controls, store panel, corrected
manual-stop/service copy, synthetic litmus sources and CI, final guide and status.
The pre-existing UI still contains Chain-specific process text and reconstructs
display slots from its bounded history; T8 replaces those paths. Backend Boutique
runs currently start through authenticated `/api/run?scenario=...` and `/api/inject`.

The required frontend restart command for the final guide is:

```bash
docker compose -f deploy/online-boutique/compose.yaml restart frontend
```

Joint T11 (clean-clone rehearsal, final UI walkthrough, 15-minute cadence check and
cross-lane review) is pending. Dev 1 completion does not complete Dev 2 or accept
G1–G3, A-TRUTH, baselines, evaluation, proofs or publication. Keep Boutique demo
sessions within 30 minutes; manifest clock qualification expires at 60 minutes.

## Implementation rulings

1. The slot projection contains every manifest-declared unit, including unobserved
   units marked unknown. The truncation test therefore requires the held ads-a
   entry without demanding a singleton list. Omitting those units would hide scope.
2. Deadline counters update asynchronously after the client's timeout. The test
   waits up to five seconds for ten edge deadline outcomes and retains the original
   ≥10 assertion; a missing counter still fails, after that bounded wait.

No deferred code-review findings were identified in the author review. Demo run
artifacts and verification logs are local, gitignored build/scratch evidence; they
are not a durable reproduction archive or a C-REPLAY deliverable.

## Integration with Jobin history — 2026-10-29

At the owner's request to push to Jobin, merged the existing `origin/Jobin` history
at `695f560` into the final-demo branch without rewriting either history. Preserved
its 23 test-only feasibility cases and review records. Resolved documentation
conflicts by keeping the current D13/D14 and stage-1 baseline while explicitly
retaining the older consumer-review snapshot; combined the test-only OTel SDK
dependencies with the analyzer-process runtime configuration.

The combined `./gradlew build` passed in 1m50s: 152 tests, zero failures/errors/skips
(testbed 82; other module counts unchanged). The original uncommitted
`build_deck.py` edit is outside these commits.
