# Final Demo (Synthetic + Live Online Boutique) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One monitor page that shows SpanLease on synthetic Chain scenarios and on live
`GetAds` traffic from Google's Online Boutique running locally, with a flat monotone UI,
plus the two litmus tests running in CI.

**Architecture:** The upstream store runs unmodified in Docker Compose. The frontend's
`AD_SERVICE_ADDR` points at `BoutiqueEdge` (127.0.0.1:9555), a driver-role gRPC server in
the killable workload JVM. The edge forwards each store call through the run's
`RootCaller` to the instrumented `ads-a`, so arrivals carry SpanLease metadata. A
one-shot *Inject fault* sends a driver root call with `x-spanlease-boutique-hops: 3`
straight to `ads-a`, forming `ads-a → ads-b → ads-c → ads-a`. The existing analyzer,
OTLP pipeline and monitor process model are reused unchanged.

**Tech Stack:** Java 21, Gradle Kotlin DSL (existing catalog pins only), grpc-java
blocking stubs, custom OTel LogRecords/OTLP, JUnit 5 + AssertJ, buildless HTML/CSS/JS,
Docker Compose (demo prerequisite only).

**Spec:** `docs/superpowers/specs/2026-10-29-final-demo-design.md`. Also read the rules it
keeps from `docs/superpowers/specs/2026-10-27-online-boutique-adservice-design.md`.

**Baseline:** branch `feat/stage1-mvp` at the commit that adds this plan. `./gradlew build`
passes there: 106 tests (common 5, instrumentation 9, analyzer 56, testbed 36), with
Java 21 from `/opt/homebrew/opt/openjdk@21`.

## Global Constraints

- Read `AGENTS.md` → `requirements.md` → `design.md` → `tasks.md` → `docs/decisions.md` before coding.
- No change to `common`, `analyzer`, `instrumentation`, the event/manifest/report/replay schema or `schema_version`.
- No new Gradle dependency. Versions come only from `gradle/libs.versions.toml`. Docker is a demo prerequisite, never a test or CI requirement.
- Upstream images: tag `v0.10.7` under `us-central1-docker.pkg.dev/online-boutique-ci/microservices-demo/`, from commit `38e7348eb289eb5b87c0c6e8cb19ced0449dc389`. Do not start the upstream `adservice` or `shoppingassistantservice`.
- Edge default bind: `127.0.0.1:9555`. Monitor: loopback `:8088` only. The store frontend is published on `127.0.0.1:8080`.
- Store calls never carry the diagnostic header. Only *Inject* sends `hops=3`. Absent header = immediate local ad handling, with no leaf work and no journal entry.
- Deadlock-mode injection has **no deadline**. Spare-mode injection uses `Scenario.DEFAULT_ROOT_DEADLINE` (30 s). Diagnostic leaf work is 4,000 ms at `ads-a`, in spare mode only.
- Preserve I1–I5. Cancellation never releases a running worker. Diagnostics, store stats and ads are operator-only and never reach the analyzer.
- Monitor JSON is serialized by `ContractJson` (snake_case). The browser renders response data with `textContent` only.
- Tests: no sleeps-for-sync (latches, `timedWait` on `MonitorState`, bounded futures), seeded RNGs, `@Timeout` on every process test.
- Never claim G1–G3 acceptance, proofs, timing guarantees or novelty in UI/docs. "Implemented and demonstrated end to end" is the permitted wording.
- Format with `./gradlew spotlessApply`. Commit messages are one line. Append decisions, never rewrite them.

## Review Focus

1. **Store traffic in spare mode.** An ordinary `GetAds` (no header) at a node configured with 4,000 ms leaf work must return immediately. Pinned by Task 2 `storeCallsSkipDiagnosticLeafWork`.
2. **Frontend deadline vs. fault route.** The frontend's 100 ms deadline must not reach the injected chain. The deadlock must persist ≥ 10 s while store calls keep timing out. Pinned by Task 5 `injectedDeadlockConfirmsExactCoreWhileStoreTrafficQueues`.
3. **Unbounded growth under continuous traffic.** `HandlerJournal` must record only fault-route calls, and store stats keep ≤ 5 recent ads. Pinned by Task 2 (`journal` empty after store calls) and Task 3 `StoreTrafficTest`.
4. **Inject misuse.** A second inject, inject in `boutique-live`, inject after exit, missing token, wrong method and foreign origin must each be refused without touching the workload. Pinned by Task 4 `LiveMonitorTest` and `MonitorStateTest` cases.
5. **Edge port already taken** (stale run or upstream adservice). Startup must fail fast with a message naming the port, and the monitor shows FAILED instead of hanging. Pinned by Task 3 `edgePortConflictFailsFast`.

---

## Ownership, order and interfaces between the two developers

| Dev 1 — Boutique runtime | Dev 2 — website, synthetic CI, docs |
|---|---|
| T1 scenarios + monitor snapshot contract | T7 monotone theme + simplified layout (start immediately) |
| T2 adapter fixes | T9 litmus integration tests + CI (start immediately) |
| T3 edge + deployment | T8 source toggle + Boutique UI (needs T1 merged; T4 for inject) |
| T4 monitor ↔ workload control | T10 README, runbook, status docs |
| T5 real process evidence | T11 joint rehearsal with Dev 1 |
| T6 Docker Compose store | |

Each task is a short branch off `feat/stage1-mvp` and a PR reviewed by the other dev.
**T1 is the hand-off contract.** Dev 2 builds T8 against the snapshot fields it defines
and does not invent other fields. If T8 needs more data, Dev 1 adds it in T1/T4.

---

### Task 1 (Dev 1): Scenario application kind, Boutique scenarios, snapshot contract

**Files:**
- Modify: `testbed/src/main/java/dev/spanlease/testbed/Scenario.java`
- Create: `testbed/src/main/java/dev/spanlease/testbed/StoreStats.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorState.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/LiveMonitor.java` (scenario switch → `Scenario.named`)
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorApplication.java` (scenario switch → `Scenario.named`)
- Test: `testbed/src/test/java/dev/spanlease/testbed/BoutiqueScenarioTest.java` (create), `MonitorStateTest.java`

**Interfaces:**
- Produces:
  - `enum Scenario.Application { CHAIN, BOUTIQUE }`, as a new record component `application`, placed after `description`. Chain factories pass `CHAIN`.
  - `static Scenario boutiqueLive()`, `boutiqueDeadlock()`, `boutiqueSpareCapacity()`.
  - `static Scenario named(String id)` for exactly `closed-ring`, `spare-capacity`, `slow-progress`, `boutique-live`, `boutique-deadlock`, `boutique-spare-capacity`. Anything else throws `IllegalArgumentException`.
  - `boolean manualStop()`: true for `closed-ring` and every Boutique scenario.
  - `boolean injectable()`: true for `boutique-deadlock` and `boutique-spare-capacity`.
  - `boolean rootWithoutDeadline()`: true for `closed-ring` and `boutique-deadlock`.
  - `record StoreStats(String storeUrl, int edgePort, long forwarded, long ok, long deadlineExceeded, long failed, List<AdView> recentAds, long sampledAtMs)` with nested `record AdView(String redirectUrl, String text)`.
  - `MonitorState` methods: `store(StoreStats)`, `injected(long atMs)`, `faultCompleted(String outcome, List<StoreStats.AdView> ads)`.
  - `MonitorState.Snapshot` gains these components (JSON name in brackets), appended at the end:
    - `application` [`application`]: `"CHAIN"` or `"BOUTIQUE"`
    - `nativeMethod` [`native_method`]: `"spanlease.testbed.Chain/Call"` or `"hipstershop.AdService/GetAds"`
    - `manualStop` [`manual_stop`]
    - `injectable` [`injectable`]: scenario injectable ∧ alive ∧ not injected
    - `injectedAtMs` [`injected_at_ms`], nullable
    - `faultOutcome` [`fault_outcome`]: `""` until completion
    - `faultAds` [`fault_ads`]
    - `faultCompletedAtMs` [`fault_completed_at_ms`], nullable (monitor wall time when `COMPLETE` arrived)
    - `store` [`store`]: `null` for Chain
    - `unitLatest` [`unit_latest`]: `List<UnitView>` for every manifest-declared unit, where `record UnitView(String unitId, String instanceId, String state, String executionId, long localSeq)` and `state` is `"unknown"` (before init), `"free"` or `"held"`. It is updated in `observe(...)` from `resource.init` / `resource.acquire` / `resource.release` payloads, independent of the bounded observation deque, and display-only.
- Consumes: nothing new.

- [ ] **Step 1: Write `BoutiqueScenarioTest`**

```java
@Test void boutiqueScenariosHaveExactTopology() {
  var spare = Scenario.named("boutique-spare-capacity");
  assertThat(spare.application()).isEqualTo(Scenario.Application.BOUTIQUE);
  assertThat(spare.capacities()).containsExactlyInAnyOrderEntriesOf(Map.of("ads-a", 2, "ads-b", 1, "ads-c", 1));
  assertThat(spare.rootHops()).extracting(Hop::getTargetService).containsExactly("ads-a", "ads-b", "ads-c", "ads-a");
  assertThat(spare.rootHops().getLast().getWorkMs()).isEqualTo(4_000);
  assertThat(spare.injectable()).isTrue();
  assertThat(spare.rootWithoutDeadline()).isFalse();
  var deadlock = Scenario.named("boutique-deadlock");
  assertThat(deadlock.capacities().values()).containsOnly(1);
  assertThat(deadlock.rootWithoutDeadline()).isTrue();
  assertThat(deadlock.rootHops().getLast().getWorkMs()).isZero();
  var live = Scenario.named("boutique-live");
  assertThat(live.injectable()).isFalse();
  assertThat(live.manualStop()).isTrue();
  assertThat(live.rootHops()).extracting(Hop::getTargetService).containsExactly("ads-a");
}
@Test void chainScenariosKeepTheirBehavior() {
  assertThat(Scenario.named("closed-ring").application()).isEqualTo(Scenario.Application.CHAIN);
  assertThat(Scenario.named("closed-ring").rootWithoutDeadline()).isTrue();
  assertThat(Scenario.named("spare-capacity").manualStop()).isFalse();
}
@Test void unknownScenarioIsRejected() {
  assertThatThrownBy(() -> Scenario.named("boutique")).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Add `MonitorStateTest` cases**
  - `boutiqueSnapshotExposesStoreAndInjectState`: `begin(boutique-deadlock plan)` → `application` = `BOUTIQUE`, `injectable` false. After `applicationStarted(1)` it is true. After `injected(5)` it is false and `injectedAtMs` = 5. After `store(stats)` the snapshot `store` equals `stats`.
  - `chainRunAfterBoutiqueClearsBoutiqueState`: after a Boutique `begin`/`store`/`injected`/`faultCompleted`, `applicationExited(0)`, then `begin(closed-ring plan)` gives `store` null, `injectedAtMs` null, `faultAds` empty, `application` `CHAIN`.
  - `injectableIsFalseForLiveAndAfterExit`: a `boutique-live` plan is never injectable. A `boutique-deadlock` plan is not injectable after `applicationExited`.
  - `unitLatestSurvivesDisplayTruncation`: use `new MonitorState(2)`. Observe `resource.init` for ads-a, then `resource.acquire` of `slot-0` by `exec-1`, then 10 checkpoints. `unit_latest` has ads-a `slot-0` `held` by `exec-1`, while `observations` has size 2. After `begin(chain plan)`, `unit_latest` lists only the Chain units, all `unknown`.
- [ ] **Step 3:** Run `./gradlew :testbed:test --tests '*BoutiqueScenarioTest' --tests '*MonitorStateTest'`. Expected: compilation failure (missing methods).
- [ ] **Step 4:** Implement. Boutique scenarios use `DEFAULT_ROOT_DEADLINE` and observation window `Duration.ofSeconds(8)`; the window isn't used for manual-stop runs. Make the descriptions plain, e.g. `"live store traffic; injected cycle ads-a→ads-b→ads-c→ads-a, one slot each"`. `dataSource` text: Chain is unchanged. Boutique is `"Online Boutique · live store traffic → SpanLease-instrumented Ad Service"`. Replace the two duplicated `switch` blocks in `LiveMonitor.run` and `MonitorApplication.main` with `Scenario.named`. `LiveMonitor` maps `IllegalArgumentException` → 400 `unknown scenario`.
- [ ] **Step 5:** Rerun the Step 3 command plus `./gradlew :testbed:test`. Expected: all pass, including the existing `MonitorRunTest`/`DemoOptionsTest`.
- [ ] **Step 6:** `./gradlew spotlessApply`, then commit: `feat(testbed): add Boutique scenarios and monitor snapshot contract`.

### Task 2 (Dev 1): Separate store calls from the diagnostic route in the adapter

**Files:**
- Modify: `testbed/src/main/java/dev/spanlease/testbed/BoutiqueFaults.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/BoutiqueAdNode.java`
- Test: `BoutiqueFaultsTest.java`, `BoutiqueAdNodeTest.java`

**Interfaces:**
- Produces: `BoutiqueFaults.ABSENT = -1`. `parse(Metadata, boolean)` returns `ABSENT` when there is no header, and `REMAINING` defaults to `ABSENT`. `BoutiqueAdNode.handle` behaves as: `budget > 0` → forward; `budget == 0` → leaf work, then ads; `ABSENT` → ads immediately. Journal and diagnostics-journal entries are written only when `budget != ABSENT`. `BoutiqueAdNode.start(...)` keeps its signature.

- [ ] **Step 1:** In `BoutiqueFaultsTest`, change both absent-header assertions to `isEqualTo(BoutiqueFaults.ABSENT)`. Keep the 0..3, duplicate, malformed and normal-mode-header cases.
- [ ] **Step 2:** Add `BoutiqueAdNodeTest.storeCallsSkipDiagnosticLeafWork`. Start the node with fault enabled, `nextService` `"ads-a"` and `leafWorkMs` `4_000`. Send a plain `GetAds(kitchen)` through `RootCaller` with `withDeadlineAfter(1, SECONDS)`. Assert the two kitchen ad texts and `journal.entries()` is empty. Then a call with `BoutiqueFaults.headers(0)` and a 10 s deadline also returns kitchen ads, and the journal has exactly one `RUN` and one `HANDLER_RETURN` for `ads-a`, ≥ 4,000 ms apart.
- [ ] **Step 3:** Run `./gradlew :testbed:test --tests '*BoutiqueFaultsTest' --tests '*BoutiqueAdNodeTest'`. Expected: FAIL (absent is 0, and the store call times out).
- [ ] **Step 4:** Implement the three-way branch. The `journal.record` calls and the leaf loop move inside `budget != ABSENT` / `budget == 0`. `diagnostics.enter/exit` still wrap every admitted job, because the monitor's thread view needs them.
- [ ] **Step 5:** Rerun Step 3. Expected: PASS. Run `./gradlew :testbed:test :instrumentation:test`. Expected: PASS.
- [ ] **Step 6:** Commit: `fix(testbed): keep store GetAds calls off the diagnostic leaf and journal path`.

### Task 3 (Dev 1): `BoutiqueEdge`, `StoreTraffic` and `BoutiqueDeployment`

**Files:**
- Create: `testbed/src/main/java/dev/spanlease/testbed/StoreTraffic.java`
- Create: `testbed/src/main/java/dev/spanlease/testbed/BoutiqueEdge.java`
- Create: `testbed/src/main/java/dev/spanlease/testbed/BoutiqueDeployment.java`
- Modify: `docs/decisions.md` (append **D21**)
- Test: `StoreTrafficTest.java`, `BoutiqueDeploymentTest.java` (create)

**Interfaces:**
- Consumes: `Scenario` (T1), `BoutiqueAdNode` (T2), `RunPlan`, `OtlpTelemetry.start(endpoint, instanceId, capacity)`, `InstanceRecorder`, `PeriodicScanner`, `RootCaller` (instrumentation, unchanged).
- Produces:
  - `final class StoreTraffic`: `void forwarded()`, `void ok(Demo.AdResponse)`, `void deadline()`, `void failed()`, `StoreStats sample(String storeUrl, int edgePort, long nowMs)`. Thread-safe, keeps the last ≤ 5 ads.
  - `final class BoutiqueEdge implements AutoCloseable`: `static BoutiqueEdge start(InetSocketAddress bind, RootCaller roots, ManagedChannel adsA, StoreTraffic traffic) throws IOException`, `int port()`.
  - `final class BoutiqueDeployment implements AutoCloseable`: `static BoutiqueDeployment start(RunPlan plan, String otlpEndpoint, InetSocketAddress edgeBind, long seed, RuntimeDiagnostics diagnostics) throws IOException, InterruptedException`; `StoreStats store(long nowMs)`; `CompletableFuture<Demo.AdResponse> inject()`; `int edgePort()`; `close()`.

- [ ] **Step 1: Write `StoreTrafficTest.keepsCountsAndOnlyFiveRecentAds`.** Seven `ok(response with one distinct ad each)` plus one `deadline()` and one `failed()`, then `sample("http://localhost:8080", 9555, 1)`. Assert `forwarded` counts only `forwarded()` calls, `ok` = 7, `deadlineExceeded` = 1, `failed` = 1, and `recentAds` holds exactly the last five ads in arrival order.
- [ ] **Step 2: Write `BoutiqueDeploymentTest`.** Use OTLP endpoint `http://127.0.0.1:9`. Export failures stay off application paths (I4), so behavior is unaffected. Use edge bind `127.0.0.1:0`. Store traffic comes from a plain `AdServiceGrpc` blocking stub with no SpanLease metadata and a 100 ms deadline, like the Go frontend.
  - `edgeForwardsStoreCallsToAdsA` (`boutique-live`): 20 calls all return kitchen ads. `store(now).ok()` = 20.
  - `injectDeadlocksAdsAAndStoreCallsTimeOut` (`boutique-deadlock`): `inject()` is not done after 3 s (`get(3, SECONDS)` throws `TimeoutException`). Then 10 store calls all fail with `DEADLINE_EXCEEDED` and `store(now).deadlineExceeded()` ≥ 10. A second `inject()` throws `IllegalStateException`.
  - `spareInjectionCompletesAndStoreRecovers` (`boutique-spare-capacity`): `inject().get(20, SECONDS)` returns 2 kitchen ads. A store call after that returns kitchen ads.
  - `liveModeRejectsInject`: `inject()` on `boutique-live` throws `IllegalStateException`.
  - `edgePortConflictFailsFast`: hold a `ServerSocket` on a loopback port, then `start` with that port throws `IOException` whose message contains the port number. No threads leak: `close` runs on the failure path.
- [ ] **Step 3:** Run `./gradlew :testbed:test --tests '*StoreTrafficTest' --tests '*BoutiqueDeploymentTest'`. Expected: compilation failure.
- [ ] **Step 4: Implement.**
  - **Driver instance:** `BoutiqueDeployment` owns `InstanceRecorder(plan.driverInstanceId(), sink, Clock.systemUTC(), System::nanoTime)`, a `PeriodicScanner` with no pools (same arguments as `ChainDriver`'s constructor) and one `RootCaller`. Close checkpoints the driver after the nodes stop.
  - **Nodes:** `ads-a/b/c` start with `plan.instanceByService()`, capacities from the scenario, `faultEnabled = scenario.injectable()` and peers a→b, b→c, c→a. `ads-a` gets `leafWorkMs = rootHops.getLast().getWorkMs()`; others get 0. Replica RNG is `new Random(seed + index)`.
  - **Edge:** a dedicated cached thread pool named `boutique-edge-N` as the server executor. The handler calls `roots.call("ads-a", AdServiceGrpc.newBlockingStub(adsA), s -> s.getAds(request))` on the handler thread, so the incoming gRPC `Context` (frontend deadline and cancellation) propagates. Errors return their `Status` to the caller. A bind failure is rethrown as `IOException("Boutique edge cannot bind <host>:<port> …")`.
  - **`inject()`:** throws `IllegalStateException` unless `scenario.injectable()` and not already injected. It runs on a fresh `Context.ROOT.withCancellation()` (as `ChainDriver.submit` does) on its own thread, using a stub to the `ads-a` channel with `BoutiqueFaults.headers(3)`. The stub has no deadline when `rootWithoutDeadline()`, else `DEFAULT_ROOT_DEADLINE`. It never goes through the edge. `close()` cancels that context.
- [ ] **Step 5:** Rerun Step 3. Expected: PASS. Then run `./gradlew :testbed:test`. Expected: PASS.
- [ ] **Step 6:** Append **D21** to `docs/decisions.md`: full upstream store in Docker, Java ad path only, and the driver-role edge because the Go frontend sends no `sl-*` metadata (design §4 driver semantics, no schema change). Store calls never carry the fault header. This supersedes the scope of the 2026-10-27 Boutique spec. Not G1–G3/E-REALISM evidence.
- [ ] **Step 7:** Commit: `feat(testbed): add Boutique edge, store traffic stats and deployment`.

### Task 4 (Dev 1): Monitor ↔ workload control (`STORE`, `INJECT`, `/api/inject`)

**Files:**
- Modify: `MonitorApplication.java`, `MonitorRun.java`, `MonitorState.java`, `LiveMonitor.java`
- Test: `LiveMonitorTest.java`, `MonitorStateTest.java`

**Interfaces:**
- Consumes: `BoutiqueDeployment` (T3), `StoreStats` / `MonitorState` methods (T1).
- Produces:
  - Workload stdout lines: `STORE <StoreStats json>` every 500 ms (Boutique only), `INJECTED {"at_ms":…}` once, and `COMPLETE <Completion json>`. Boutique's `COMPLETE` is the fault-root outcome; the store keeps running.
  - Workload stdin: line `INJECT`.
  - `MonitorApplication.Completion(String outcome, int hopsCompleted, List<StoreStats.AdView> ads)`.
  - `MonitorRun.start(RunPlan, Path, MonitorState, InetSocketAddress edgeBind)` passes `-Dspanlease.boutique.edge=host:port` to the child. The existing 3-arg overload keeps `127.0.0.1:9555`.
  - `boolean MonitorRun.inject()` writes `INJECT\n` and flushes. It returns false if the state is not injectable.
  - `LiveMonitor`: `POST /api/inject` returns 200 / 403 / 405 / 409. The `--edge-bind host:port` CLI option defaults to `127.0.0.1:9555`.

- [ ] **Step 1:** Add `LiveMonitorTest` cases. No workload is started; reuse the existing test's server setup.
  - `injectRequiresPostTokenAndOrigin`: `GET /api/inject` → 405, POST without token → 403, POST with token and `Origin: http://evil.test` → 403.
  - `injectWithoutRunningInjectableRunIs409`: POST with valid token while IDLE → 409.
  - `unknownScenarioIs400`: `POST /api/run?scenario=boutique` → 400.
- [ ] **Step 2:** Add `MonitorStateTest.applicationExitDuringBoutiqueRunIsStoppedNotComplete`: a Boutique run with `faultCompleted("OK", ads)` then `applicationExited(143)` gives phase `STOPPED`, `fault_outcome` `"OK"` and `injectable` false. Store keeps running after completion, so only exit ends the run.
- [ ] **Step 3:** Run `./gradlew :testbed:test --tests '*LiveMonitorTest' --tests '*MonitorStateTest'`. Expected: FAIL.
- [ ] **Step 4: Implement.**
  - `MonitorApplication` branches on `scenario.application()`. Chain is unchanged except that the `manual` flag becomes `scenario.rootWithoutDeadline()`. Boutique:
    - start `BoutiqueDeployment` with the edge bind from the system property and seed `42`;
    - print `STORE` lines from the existing sampler;
    - read stdin on a daemon thread, accepting only the exact line `INJECT` and ignoring anything else;
    - after `inject()`, print `INJECTED`, and print `COMPLETE` when its future finishes;
    - block the main thread until the process is killed.
  - `MonitorRun.awaitExit` parses the new lines into `state.store` / `state.injected`. For Boutique runs, `COMPLETE` maps to `state.faultCompleted`, never `applicationCompleted`. Progress text uses `scenario.manualStop()`, not name checks.
  - `MonitorState.applicationExited` sets `COMPLETE` only for Chain runs that completed. Boutique exit is always `STOPPED`.
  - `LiveMonitor`: extract the token/origin check from `run` into `authorized(HttpExchange)` and use it for both endpoints. Track the current `MonitorRun`. Write `boutique-config.json` (scenario, seed, edge bind, image tag, upstream commit) into the run directory before launch.
- [ ] **Step 5:** Rerun Step 3, then `./gradlew :testbed:test`. Expected: PASS.
- [ ] **Step 6:** Commit: `feat(testbed): wire Boutique store stats and fault injection through the monitor`.

### Task 5 (Dev 1): Real process evidence for the three Boutique modes

**Files:**
- Test: `testbed/src/test/java/dev/spanlease/testbed/BoutiqueMonitorRunTest.java` (create)
- Modify only if a test exposes a defect: the responsible testbed class. Never the analyzer or the expected values.

**Interfaces:**
- Consumes: `MonitorRun.start(plan, runs, state, new InetSocketAddress("127.0.0.1", 0))` and `inject()` (T4). The edge port comes from `state.snapshot().store().edgePort()`.

Store traffic in every test is a seeded `ScheduledExecutorService`. It sends one plain-stub `GetAds(kitchen)` with a 100 ms deadline every 100 ms and counts outcomes in `AtomicInteger`s. Waits use `timedWait` on `state` with a bounded deadline, as in `MonitorRunTest.awaitDeadlock`. Every test ends by destroying `snapshot.applicationPid()` and awaiting exit.

- [ ] **Step 1:** `@Timeout(60) liveStoreTrafficNeverConfirmsADeadlock` (`boutique-live`):
  - within 20 s at least one report is `CONFIRMED_NO_DEADLOCK`;
  - ≥ 90 % of sent store calls succeed with kitchen ads;
  - no report is `CONFIRMED_DEADLOCK`;
  - no report's `coverage` names `INVALID_IDENTITY`.

  The `CONFIRMED_NO_DEADLOCK` report proves the edge supplies valid request parents. A transient `MISSING_CAUSAL_PARENT` is legitimate, because the driver and `ads-a` export through separate OTLP pipelines, so it is not asserted.
- [ ] **Step 2:** `@Timeout(90) injectedDeadlockConfirmsExactCoreWhileStoreTrafficQueues` (`boutique-deadlock`): start traffic, wait for ≥ 5 successful store calls, then `run.inject()`. Await `firstDeadlock`, then assert:
  - `core().executions()` has size 3;
  - `core().units()` ids equal exactly `{plan.instanceByService().get(s) + ":app:slot-0" | s ∈ ads-a, ads-b, ads-c}`;
  - every unit's `ownerExecutionId` is a core execution id;
  - `core().queueEntries()` has size 1 with `instanceId` = the ads-a instance;
  - after the first confirmation, `awaitExit` does not finish within 10 s and ≥ 90 % of store calls in that window are `DEADLINE_EXCEEDED`;
  - after destroying the PID: phase `STOPPED`, `firstDeadlock` retained, latest verdict ≠ `CONFIRMED_NO_DEADLOCK`, and `first-deadlock.json` exists.
- [ ] **Step 3:** `@Timeout(60) spareCapacityInjectionCompletesAndStoreRecovers` (`boutique-spare-capacity`):
  - after inject, `fault_outcome` becomes `"OK"` within 40 s and `fault_ads` holds the 2 kitchen ads;
  - `firstDeadlock` is null;
  - at least one `CONFIRMED_NO_DEADLOCK` report has `query().certifiedThroughWallMs()` strictly between `injected_at_ms` and `fault_completed_at_ms`;
  - store calls succeed again after the completion.
- [ ] **Step 4:** Run `./gradlew :testbed:test --tests '*BoutiqueMonitorRunTest'`. Expected: PASS. If a verdict is `INCONCLUSIVE`, read its named missing evidence and fix the responsible testbed layer. Do not weaken assertions or touch the analyzer. Report any analyzer-side cause to the owner as a G1 finding.
- [ ] **Step 5:** Run `./gradlew build` three times in a row. Expected: green each time, with no flaky process test.
- [ ] **Step 6:** Commit: `test(testbed): prove Boutique live, deadlock and spare-capacity evidence end to end`.

### Task 6 (Dev 1): Run the real store locally with Docker Compose

**Files:**
- Create: `deploy/online-boutique/compose.yaml`
- Modify: `third_party/online-boutique/SOURCE.md` (images, digests, compose transformations)

**Interfaces:**
- Produces: `docker compose -f deploy/online-boutique/compose.yaml up -d` serves the shop at `http://localhost:8080`, with ad calls going to `host.docker.internal:9555`.

- [ ] **Step 1: Check image platforms.** Run `docker manifest inspect us-central1-docker.pkg.dev/online-boutique-ci/microservices-demo/frontend:v0.10.7` (and the same for each image). Record which images ship `linux/arm64`. Add `platform: linux/amd64` only to images that lack it.
- [ ] **Step 2: Write `compose.yaml`.**
  - **Services:** `frontend`, `productcatalogservice`, `currencyservice`, `cartservice`, `redis-cart` (`redis:alpine`, pinned by digest), `recommendationservice`, `shippingservice`, `checkoutservice`, `paymentservice`, `emailservice`, `loadgenerator`.
  - **Ports:** use container ports, not the k8s Service ports:
    - catalog 3550, currency 7000, cart 7070 (`REDIS_ADDR=redis-cart:6379`)
    - recommendation 8080, shipping 50051, checkout 5050, payment 50051
    - email 8080, so checkout's `EMAIL_SERVICE_ADDR=emailservice:8080`
    - frontend 8080, published as `127.0.0.1:8080:8080`
  - **Frontend env:** as in `release/kubernetes-manifests.yaml`, except `AD_SERVICE_ADDR=host.docker.internal:9555`. `SHOPPING_ASSISTANT_SERVICE_ADDR=shoppingassistantservice:80` must still be set (the frontend requires it) but that service is not started. Set `ENABLE_PROFILER=0` and `extra_hosts: ["host.docker.internal:host-gateway"]`.
  - **Load generator:** `FRONTEND_ADDR=frontend:8080`, `USERS=3`, `RATE=1`. Keep this low, because the analyzer retains full run history.
  - Set `DISABLE_PROFILER=1` where the manifest sets it. Pin every image by `tag@digest`.
- [ ] **Step 3: Verify reachability.** With `./gradlew :testbed:monitorDemo` running and `boutique-live` started, run `compose up -d`. Open `http://localhost:8080/product/0PUK6V6EV0` and check an ad banner shows. Check the monitor's `store.forwarded` rises with the loadgenerator. If Docker Desktop can't reach `127.0.0.1:9555`, use `--edge-bind 0.0.0.0:9555` and document that this exposes the port to the LAN. Do not change the default.
- [ ] **Step 3b: Measure frontend reconnect.** Kill the run's PID, wait 30 s, then start a new `boutique-live` run. Time how long until `store.forwarded` rises again. If it takes > 15 s, the runbook (T10) says to run `docker compose -f deploy/online-boutique/compose.yaml restart frontend` after starting each run. Record the measurement in `SOURCE.md`.
- [ ] **Step 4: Verify the deadlock path.** Inject in `boutique-deadlock`. Product pages render with no ad banner, the monitor shows DEADLOCK DETECTED with ads-a/b/c, and the `kill -TERM <pid>` shown on the page stops it. Record run IDs for T11.
- [ ] **Step 5:** Record digests, platform choices and the env/port differences from the k8s manifest in `SOURCE.md`. Commit: `feat(deploy): run upstream Online Boutique locally against the instrumented ad service`.

### Task 7 (Dev 2): Monotone theme and simplified layout

**Files:**
- Modify: `testbed/src/main/resources/monitor/monitor.css` (rewrite), `index.html`, `monitor.js` (selectors only)
- Modify: `README.md` (receives the references list removed from the page), `docs/decisions.md` (append **D22**)
- Test: `LiveMonitorTest.java` (existing static-resource assertions keep passing)

**Interfaces:**
- Produces element ids that T8 fills: `#source-toggle` (two radio inputs `synthetic` / `boutique`), `#scenario`, `#start`, `#inject` (hidden by default), `#store-link` (hidden), `#store-panel` (hidden).
- Keeps every existing id that `monitor.js` writes (`verdict-*`, `records`, `arrivals`, `confirmed`, `phase`, `process`, `services`, `calls`, `events`, `runtime`, `result`, `raw`, `activity`).

- [ ] **Step 1: Rewrite `monitor.css` around tokens on `:root`.**

| Token | Light | Dark (under `@media (prefers-color-scheme: dark)`) |
|---|---|---|
| `--bg` | `#fafafa` | `#111111` |
| `--surface` | `#ffffff` | `#181818` |
| `--border` | `#e2e2e2` | `#2a2a2a` |
| `--text` | `#1a1a1a` | `#ededed` |
| `--muted` | `#6b6b6b` | `#9a9a9a` |
| `--bad` | `#b42318` | `#f97066` |
| `--good` | `#1f7a3d` | `#6fcf97` |
| `--warn` | `#9a6700` | `#e3b341` |

  Style rules:
  - Fonts: `system-ui` for text, `ui-monospace` for code. Radius 6px.
  - No `gradient`, `box-shadow` or glow.
  - Primary button: `--text` background with `--bg` text.
  - Status colours appear only on `.verdict.*` and `.badge.*`.
  - Body has an explicit background.
  - Single column under 900px; no horizontal scroll at 375px.
- [ ] **Step 2: Restructure `index.html` per the spec's layout.**
  - Header: name + connection.
  - Control bar: source toggle, scenario select, Start, Inject, Open store.
  - Source line, verdict banner, four metrics.
  - Left column: services, calls, store panel. Right column: evidence, process/PID, activity.
  - One collapsed `<details>` each for the event stream, OS/JVM diagnostics and raw JSON.
  - Delete the hero/intro copy and the references panel.
  - Footer: `Research prototype · verdicts describe reported historical cuts`.
- [ ] **Step 3:** Update `monitor.js` selectors only. Chain behavior is unchanged in this task.
- [ ] **Step 4: Verify.**
  - `node --check testbed/src/main/resources/monitor/monitor.js` passes.
  - `grep -c gradient testbed/src/main/resources/monitor/monitor.css` prints `0`.
  - `./gradlew :testbed:test --tests '*LiveMonitorTest'` passes.
  - `./gradlew :testbed:monitorDemo`: run closed ring, spare capacity and slow progress in a browser in light and dark mode, at 1440px and 375px. DEADLOCK DETECTED, evidence, PID/kill command and the diagnostics `<details>` all still work.
- [ ] **Step 5:** Append **D22**: owner-requested flat monotone UI, references moved to the README, no change to the data shown or its semantics. Commit: `feat(monitor): simplify layout and switch to a flat monotone theme`.

### Task 8 (Dev 2): Source toggle and Boutique UI

**Files:**
- Modify: `testbed/src/main/resources/monitor/monitor.js`, `index.html`, `monitor.css`

**Interfaces:**
- Consumes: the snapshot fields from T1 (`application`, `native_method`, `manual_stop`, `injectable`, `injected_at_ms`, `fault_outcome`, `fault_ads`, `store.{store_url, edge_port, forwarded, ok, deadline_exceeded, failed, recent_ads[].{redirect_url,text}, sampled_at_ms}`) and `POST /api/inject` from T4. Same token header as `/api/run`.

- [ ] **Step 1: Source toggle.** It filters `#scenario` options: Synthetic shows the three Chain ids, Online Boutique shows `boutique-live`, `boutique-deadlock` and `boutique-spare-capacity`. Option labels:
  - "Live store traffic (healthy)"
  - "Injected deadlock · ads-a → ads-b → ads-c → ads-a"
  - "Spare capacity · ads-a has two workers"

  While a run is active, the toggle follows `state.application`.
- [ ] **Step 2: Inject button.** Visible when `application === 'BOUTIQUE'` and the selected scenario isn't `boutique-live`. Enabled only when `state.injectable`. It POSTs `/api/inject`. A non-200 response's text goes to `#control-note`.
- [ ] **Step 3: Store panel and link.**
  - Counters, plus recent ads as `text`, built with `textContent`.
  - An ad's link is `http://localhost:8080` + `redirect_url` only when `redirect_url` matches `^/product/[A-Z0-9]{10}$`; otherwise the ad shows as plain text.
  - `#store-link` uses `state.store.store_url` only when it matches `^http://(localhost|127\.0\.0\.1):\d{1,5}$`.
  - Show "Fault injected at …", then the fault outcome and its ads.
- [ ] **Step 3b: Slots from `unit_latest`.** Render each service's slots from `state.unit_latest`, not from the bounded observation history. Caption: "Latest received observation per slot · not a certified snapshot".
- [ ] **Step 4: Remove Chain-only assumptions.**
  - The process text lists services from `manifest.instances` where `service_binding !== 'driver'`.
  - The manual-stop sentence uses `state.manual_stop`.
  - Hop counts render only when `application === 'CHAIN'`.
  - The source line shows `state.data_source` and `state.native_method`.
- [ ] **Step 5: Verify.**
  - `node --check` passes.
  - Browser walkthrough (after T6 is available, or with the T5 in-JVM traffic): live counters rise; in deadlock mode Inject enables once, disables after the click and DEADLOCK DETECTED names ads-a/b/c; spare mode shows the outcome and its ads.
  - Switching to Chain clears the store panel and Inject.
  - Set a breakpoint to replace one `recent_ads[].text` with `<img src=x onerror=alert(1)>` and confirm it renders as text.
- [ ] **Step 6:** Commit: `feat(monitor): add synthetic/Boutique source toggle, store panel and fault injection control`.

### Task 9 (Dev 2): Litmus integration tests in `integrationTest` and CI

**Files:**
- Create: `testbed/src/integrationTest/java/dev/spanlease/testbed/LitmusTest.java`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/decisions.md` (append **D23**), `README.md` (remove the "integrationTest has no sources" text)

**Interfaces:**
- Consumes: `LiveRun.execute(plan, dir, ids, progress, reports)`, `LiveRun.Result.first/leafRun/certifiedWithin`, `RunPlan.create`, `Scenario.closedRing()/spareCapacity()`. All existing.

- [ ] **Step 1:** `@Timeout(90) deterministicClosedWaitConfirmsTheExactCore`. Use `Scenario.closedRing()` and `UUID` invocation ids. Expected values come only from the scenario topology and `plan.instanceByService()`, never from analyzer output.
  - `result.first(CONFIRMED_DEADLOCK)` is present;
  - its `core().executions()` has size 3;
  - `core().units()` ids equal `{svcA, svcB, svcC instance + ":app:slot-0"}`;
  - every unit owner is a core execution;
  - `core().queueEntries()` has exactly one entry, at the svcA instance;
  - `persistence().narrowedSpanMs()` ≥ `plan.manifest().parameters().tauMs()`.
- [ ] **Step 2:** `@Timeout(90) spareCapacityCyclicPatternConfirmsNoDeadlock`. Use `Scenario.spareCapacity()`:
  - `rootOutcome()` is `OK` and `rootHopsCompleted()` is 4;
  - no `CONFIRMED_DEADLOCK` report;
  - `certifiedWithin(CONFIRMED_NO_DEADLOCK, result.leafRun().orElseThrow())` is non-empty.
- [ ] **Step 3:** Run `./gradlew :testbed:integrationTest`. Expected: 2 tests, PASS. Not `NO-SOURCE`.
- [ ] **Step 4:** In CI, after the build step, add `- name: Litmus integration tests` / `run: ./gradlew --no-daemon integrationTest`. Remove the stale I-CI comment.
- [ ] **Step 5:** Append **D23**: the two litmus tests are implemented and run in CI as automated end-to-end regressions. Their expected core comes from scenario topology and manifest unit IDs; there is no independent truth recorder (A-TRUTH). They are **implemented, pre-G1, not accepted**. Formal I-LITMUS/I-CI/G2 acceptance still requires the G1 review in `tasks.md`. Commit: `test(testbed): add closed-ring and spare-capacity litmus integration tests to CI`.

### Task 10 (Dev 2): README, runbook and status

**Files:**
- Create: `docs/final-demo-guide.md`
- Modify: `README.md`, `tasks.md`, `docs/superpowers/specs/2026-10-27-online-boutique-adservice-design.md` (status line only: "scope superseded by 2026-10-29 final-demo design")

- [ ] **Step 1: Write `docs/final-demo-guide.md`.**
  - **Prerequisites:** Java 21, Docker Desktop, ports 8080/8088/9555 free.
  - **Commands:** `./gradlew :testbed:monitorDemo` and `docker compose -f deploy/online-boutique/compose.yaml up -d`.
  - **5-minute script:**
    1. Synthetic closed ring → DEADLOCK DETECTED → kill PID.
    2. Spare capacity → no deadlock.
    3. Boutique live → shop ads + calls in the monitor.
    4. Boutique deadlock → Inject → ads vanish, exact workers → kill PID → evidence retained.
    5. Boutique spare → recovers.
  - **What is real vs. injected:** the store is upstream; the ad replicas are an instrumented adaptation; the cycle is an injected fault.
  - **Troubleshooting:** port 9555 in use, Docker loopback reachability, amd64 emulation, loadgenerator volume.
  - **Limits:** G1–G3 pending; long runs grow analyzer history.
- [ ] **Step 2: Update `README.md`.** Add a "Final demo" section linking the guide and listing both data sources. Add a "Research references" list (the three links removed from the page). Keep the existing honest-status wording.
- [ ] **Step 3:** In `tasks.md`, add a dated "Final demo — 2026-10-29" status block. List T1–T11, mark I-LITMUS/I-CI as "implemented, pre-G1, not accepted", and list what stays pending (the spec's backlog list). In the guide's Limits section, state the run-length rule: Boutique runs ≤ 30 min, and no confirmation after the manifest's 60-minute clock qualification.
- [ ] **Step 4:** Commit: `docs: add final demo runbook and update README and task status`.

### Task 11 (Dev 1 + Dev 2): Rehearsal and sign-off

- [ ] **Step 1:** On a clean clone: `./gradlew clean build integrationTest`. Expected: green, with test counts recorded.
- [ ] **Step 2:** Run the full 5-minute script from `docs/final-demo-guide.md` with the real Docker store. Then run one 15-minute `boutique-live` session and confirm consecutive report `evaluated_at_wall_ms` gaps stay ≤ 1 s at the end of the run (check in `reports.jsonl`). Record the run IDs and `testbed/build/monitor-runs/<run>/` artifacts in the guide's "Verified on" section, along with the date and machine.
- [ ] **Step 3:** Each dev reviews the other's lane against the spec's anti-goals: no browser-side verdicts, no relaxed analyzer, no fault header on store calls, no overclaiming.
- [ ] **Step 4:** Commit `docs: record final demo verification`, push, and open a PR from `feat/stage1-mvp` to `main`.

## Self-review notes

- **Spec coverage:**
  - Goals 1–6 → T1, T3–T8, T6, T9.
  - Edge driver → T3.
  - Absent vs. 0 header and the journal bound → T2.
  - Control channel → T4.
  - Website → T7/T8.
  - Constraints → Global Constraints.
  - Verification 1–3 → T9/T5/T11.
  - Backlog → T10 Step 3.
- **Type consistency:** `StoreStats`/`AdView` are defined in T1 and used in T3, T4 and T8 (snake_case JSON). `inject()` returns `CompletableFuture<Demo.AdResponse>` in T3 and `MonitorRun.inject()` returns `boolean` in T4.
- **Builder-check calls** (spec "Builder's calls" 1–5) → T6 Step 3b, T1 `unit_latest`, T5 Step 3, T9 Step 5, T10 Step 3, T11 Step 2.
- **Risk to watch:** whether live traffic with frequent 100 ms cancellations still certifies cuts (T5 Steps 1–3). If it doesn't, the named missing evidence decides the fix. Do not change the assertion.
