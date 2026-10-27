# Online Boutique Ad Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execute inline: AGENTS.md prohibits delegation unless the owner explicitly requests it.

**Goal:** Run native Online Boutique GetAds calls, a labelled injected deadlock and a spare-capacity control in the existing PID-aware local monitor.

**Architecture:** Adapt the pinned Google ad catalog/selection logic and use its unchanged application protobuf. Three Java replicas reuse the existing admission gate, RPC instrumentation, OTel pipeline and separate analyzer; the existing monitor owns their killable workload JVM. Explicit scenario metadata distinguishes Boutique from Chain and carries application results without changing detector contracts.

**Tech Stack:** Java 21, existing Gradle Kotlin DSL/catalog, grpc-java blocking unary stubs, protobuf, existing custom OTel LogRecords/OTLP, JDK collections/RNG, Jackson, JUnit 5/AssertJ, existing buildless monitor.

**Spec:** `docs/superpowers/specs/2026-10-27-online-boutique-adservice-design.md` (owner approved).

## Global Constraints

- Pin upstream commit `38e7348eb289eb5b87c0c6e8cb19ced0449dc389`; verify both source hashes in the spec and preserve Apache-2.0 notices.
- No new dependency; use existing Java/grpc/protobuf catalog pins, JDK collections and constructor-injected RNG.
- No detector event/manifest/report/replay schema or analyzer semantics change; no Chain.Call traffic on the Boutique route.
- Preserve I1–I5 and the cancellation/actual-exit/release distinction. Separate gRPC callbacks from admitted application workers.
- Java Ad Service only: no full shopping website, mixed-language monitoring, stock-Boutique-deadlock claim or E-REALISM/G1–G3 acceptance.
- Manual injected deadlock has no automatic stop/deadline; normal/spare controls have finite deadlines. All tests have bounded cleanup and no sleeps-for-sync.
- One accountable owner: Dev A for these demo/testbed changes; consume existing B/C interfaces without introducing guessed cross-lane APIs.
- Work sequentially in the existing checkout, preserving unrelated dirty changes. Commit only task-owned paths; never stage the whole working tree.

## Review Focus

1. Duplicate diagnostic metadata, noncanonical integers and normal-mode headers must not admit a job; native transport tests own this in Task 2.
2. Repeated/unknown/empty context keys must retain upstream selection behavior; literal catalog assertions own this in Task 1.
3. An immediate successful GetAds reply must not close the analyzer before final evidence is evaluated; process tests own this in Task 4.
4. Killing the workload while a callback is queued must preserve evidence without fabricating release or a negative verdict; process tests own this in Task 5.
5. A completed Boutique run followed by a Chain run must clear ads/provenance/diagnostics and retain correct service names; monitor-state/browser checks own this in Tasks 4–5.

## File responsibilities

- `testbed/src/main/proto/onlineboutique/demo.proto`: exact pinned upstream application protocol, generated into its original `hipstershop` package.
- `testbed/src/main/java/dev/spanlease/testbed/BoutiqueAds.java`: attributed catalog and request-to-ad selection, with injected `java.util.random.RandomGenerator`.
- `testbed/src/main/java/dev/spanlease/testbed/BoutiqueFaults.java`: documented diagnostic metadata key, validation and per-call Context; no detector observations.
- `testbed/src/main/java/dev/spanlease/testbed/BoutiqueAdNode.java`: one native unary adapter, named gate, callback executor, fixed peer, scanner and actual-thread diagnostics.
- `testbed/src/main/java/dev/spanlease/testbed/BoutiqueDeployment.java`: local replicas, pipelines, instrumented root caller and native GetAds invocation.
- Existing `Scenario`, `MonitorApplication`, `MonitorRun`, `MonitorState`, `LiveMonitor`: explicit application selection, response transfer, lifecycle and settled-report handling.
- Existing monitor resources: Boutique controls, provenance, native method and returned ads; retain PID/worker/history behavior.
- `docs/online-boutique-integration.md` and `third_party/online-boutique/`: source provenance/license, transformations, modes, verification and limitations.

### Task 1: Native application protocol and attributed ad selection

**Files:** Create the pinned proto, `BoutiqueAds.java`, `third_party/online-boutique/LICENSE`, `third_party/online-boutique/SOURCE.md`; create `BoutiqueAdsTest.java` and `BoutiqueContractTest.java` under the existing testbed test package. No dependency/build change is needed for the existing proto source root.

**Interfaces:** Consume generated `hipstershop.Demo.AdRequest`, `AdResponse`, `Ad` and `hipstershop.AdServiceGrpc`. Produce package-local `BoutiqueAds(RandomGenerator random)` and `Demo.AdResponse getAds(Demo.AdRequest request)`; expose an immutable source revision constant for provenance.

- [ ] Verify the source commit and hashes before copying. Use the existing reference checkout, or fetch that exact commit into a temporary directory if it is absent. Copy `protos/demo.proto` byte-for-byte to the destination and preserve the full upstream license. Record both inspected source paths/hashes and the JDK/RNG adaptation in SOURCE.md.

```sh
git -C /private/tmp/spanlease-online-boutique-reference rev-parse HEAD
shasum -a 256 /private/tmp/spanlease-online-boutique-reference/protos/demo.proto /private/tmp/spanlease-online-boutique-reference/src/adservice/src/main/java/hipstershop/AdService.java
```

- [ ] Write the catalog behavior test first, using literal upstream expected values rather than deriving expectations from the implementation:

```java
var ads = new BoutiqueAds(new java.util.Random(42));
var response = ads.getAds(Demo.AdRequest.newBuilder().addContextKeys("kitchen").build());
assertThat(response.getAdsList()).extracting(Demo.Ad::getRedirectUrl)
    .containsExactly("/product/0PUK6V6EV0", "/product/6E92ZMYYFZ");
assertThat(response.getAdsList()).extracting(Demo.Ad::getText)
    .containsExactly("Bamboo glass jar for sale. 10% off.",
        "Mug for sale. Buy two, get third one for free");
```

- [ ] Add tests that repeated `kitchen` keys return four matching ads, a known key plus an unknown key keeps the known results, and empty/unknown-only keys return two catalog ads with replacement. Inspect the generated descriptor and assert canonical method name `hipstershop.AdService/GetAds`, UNARY type and original field numbers.
- [ ] Run `./gradlew :testbed:test --tests '*BoutiqueAdsTest' --tests '*BoutiqueContractTest'`; observe the missing-feature failure.
- [ ] Implement the seven upstream ads and category ordering with immutable JDK lists/maps. The fallback list order is clothing, accessories, footwear, hair, decor, then the two kitchen ads. For each requested context key append all its matches; only if the result is empty draw two independent `random.nextInt(allAds.size())` selections.

```java
List<Demo.Ad> selected = new ArrayList<>();
for (String key : request.getContextKeysList()) selected.addAll(byCategory.getOrDefault(key, List.of()));
if (selected.isEmpty()) {
  selected.add(allAds.get(random.nextInt(allAds.size())));
  selected.add(allAds.get(random.nextInt(allAds.size())));
}
return Demo.AdResponse.newBuilder().addAllAds(selected).build();
```

- [ ] Rerun the focused tests, then format and commit only this task's files. Do not claim upstream binary equivalence: only the documented wire/catalog/selection behavior is retained.

### Task 2: Native unary adapter and bounded diagnostic callback

**Files:** Create `BoutiqueFaults.java`, `BoutiqueAdNode.java`, `BoutiqueFaultsTest.java`, `BoutiqueAdNodeTest.java`.

**Interfaces:** `BoutiqueFaults` owns `Metadata.Key<String> HOPS`, `Context.Key<Integer> REMAINING`, `int parse(Metadata headers, boolean enabled)`, `ServerInterceptor interceptor(boolean enabled)` and `Metadata headers(int hops)`. `BoutiqueAdNode.start(String service, String instance, int capacity, RunManifest.Parameters parameters, EventSink sink, Supplier<String> ids, RuntimeDiagnostics diagnostics, HandlerJournal journal, boolean faultEnabled, String nextService, long leafWorkMs, RandomGenerator random)` returns an AutoCloseable node with `int port()`, `void connect(Map<String,Integer> ports)` and `SlotTrackingExecutor.Snapshot snapshot()`.

- [ ] Write a parameterized metadata regression for absent, `0`, `3`, `-1`, `4`, empty, noninteger, whitespace, leading-plus, and duplicate values. Absence returns zero. Any supplied header in normal mode is INVALID_ARGUMENT; fault mode permits only canonical strings `0` through `3`. Verify malformed requests over real transport have no acquire event for that invocation.
- [ ] Write a real native GetAds test with an instrumented root and literal kitchen expectations. Root setup uses the existing APIs:

```java
List<ObservationEvent> records = new CopyOnWriteArrayList<>();
EventSink sink = event -> { records.add(event); return true; };
var recorder = new InstanceRecorder("driver@test", sink, Clock.systemUTC(), System::nanoTime);
var caller = new RootCaller(recorder, () -> UUID.randomUUID().toString());
var response = caller.call("ads-a", AdServiceGrpc.newBlockingStub(channel)
    .withDeadlineAfter(5, TimeUnit.SECONDS),
    stub -> stub.getAds(Demo.AdRequest.newBuilder().addContextKeys("kitchen").build()));
```

- [ ] Run `./gradlew :testbed:test --tests '*BoutiqueFaultsTest' --tests '*BoutiqueAdNodeTest'`; observe failure before implementing the adapter.
- [ ] Implement metadata parsing via `headers.getAll(HOPS)` and reject more than one value; do not silently take the first/last duplicate. The application interceptor captures only this diagnostic Context; retain `SpanLeaseServer.streamTracerFactory()` as the early identity hook.
- [ ] Build the node following the existing ChainNode ownership pattern: separate two-thread callback executor, existing SlotTrackingExecutor and scanner, server stream tracer/interceptor, and `SpanLeaseServer.handleUnary` for one admitted job. Capture the validated hop budget in the unary callback and pass it into the admitted handler.
- [ ] For a budget above zero, call the fixed peer through `SpanLeaseClient.call`, using the native generated blocking stub with metadata budget minus one. At zero, run optional finite diagnostic leaf work and call `BoutiqueAds.getAds`. Never encode routing in `context_keys` or use a Chain message on the wire.

```java
var stub = AdServiceGrpc.newBlockingStub(peer).withInterceptors(
    MetadataUtils.newAttachHeadersInterceptor(BoutiqueFaults.headers(remaining - 1)));
return client.call(execution, nextService, stub, downstream -> downstream.getAds(request));
```

- [ ] Add latch-based queue/cancellation assertions for the native adapter: queued callback has no execution until admitted; cancellation does not release a still-running handler. Check acquire-before-handler, actual return-before-release and matching response metadata. Diagnostics enter/exit use the supplied actual ExecutionContext in try/finally. Reuse the existing callback-independent lifecycle tests for R1/R2/R3/R14/R16.
- [ ] Record actual handler entry/return in the existing HandlerJournal, independently of analyzer events. This demo has exactly one root per run and no retries: use workload ID `boutique-root-1`, service name and `remaining = diagnosticBudget + 1`; the final callback leaf has remaining 1. No extra application metadata is introduced. This remains a partial application journal, not independent gate-truth acceptance.
- [ ] Rerun focused tests plus `:instrumentation:test`; format and commit task-owned files.

### Task 3: Boutique deployment and explicit scenario selection

**Files:** Create `BoutiqueDeployment.java` and `BoutiqueScenarioTest.java`; modify `Scenario.java` and its existing tests where needed. Keep CLI defaults unchanged.

**Interfaces:** Add `Scenario.Application { CHAIN, BOUTIQUE_ADS }` as an explicit record component. Produce `Scenario.boutiqueNormal()`, `boutiqueDeadlock()`, `boutiqueSpareCapacity()`, `Scenario.named(String name)` for the six whitelisted monitor choices, and `boolean manualStop()` for Chain closed-ring/Boutique deadlock only. Existing Chain factories set CHAIN. `BoutiqueDeployment.start(RunPlan plan, String endpoint, RuntimeDiagnostics diagnostics)` returns an AutoCloseable deployment; `Demo.AdResponse getAds()` makes the observed root call synchronously, choosing deadline policy from the scenario, and `HandlerJournal journal()` exposes the application-only boundary journal to the harness.

- [ ] Write scenario tests for exact names `boutique-normal`, `boutique-deadlock`, `boutique-spare-capacity`, native application kind, replica keys `ads-a/ads-b/ads-c`, capacity A=2 only in the spare mode, and the diagnostic route A/B/C/A. Verify existing Chain default/2/3/5-service tests still express their original behavior.

```java
var scenario = Scenario.boutiqueSpareCapacity();
assertThat(scenario.application()).isEqualTo(Scenario.Application.BOUTIQUE_ADS);
assertThat(scenario.capacities()).containsEntry("ads-a", 2)
    .containsEntry("ads-b", 1).containsEntry("ads-c", 1);
assertThat(scenario.manualStop()).isFalse();
```

- [ ] Run `./gradlew :testbed:test --tests '*BoutiqueScenarioTest'`; observe missing-feature failure.
- [ ] Keep rootHops only as existing testbed route configuration; document that Boutique serializes AdRequest on native gRPC, never that Chain proto. Populate the normal root route with ads-a; fault routes with ads-a/ads-b/ads-c/ads-a. Use existing 30-second deadline and 4,000ms diagnostic leaf work for the spare mode; no configured work in normal mode.
- [ ] Start each native node with the run's declared epoch/capacity and its existing OtlpTelemetry pipeline. Create the driver InstanceRecorder/RootCaller and idle scanner with no worker pools. Resolve all channels to the deployment's loopback ports and disable retries. Use per-replica constructor-injected RNG seeds and UUID-based production invocation IDs.
- [ ] Make the root `GetAds(kitchen)` call with header 3 only in fault modes; normal has no diagnostic header. Use no deadline only for the interactive deadlock. Close channels/nodes after actual exit, checkpoint the driver/instances, then flush pipelines. Record configuration/provenance/seed to `boutique-config.json` from the parent before workload launch.
- [ ] Rerun scenario and adapter tests; format and commit task-owned paths.

### Task 4: Process harness and honest monitor data

**Files:** Modify `MonitorApplication.java`, `MonitorRun.java`, `MonitorState.java`, `LiveMonitor.java`; extend `MonitorStateTest.java`, `LiveMonitorTest.java`; add `BoutiqueMonitorRunTest.java`.

**Interfaces:** Extend `MonitorApplication.Completion` with `List<AdView> ads` and `List<HandlerJournal.Entry> applicationJournal`, where nested immutable `AdView(String redirectUrl, String text)` is an operator-only DTO. Chain completions supply empty lists; Boutique completions carry the actual native response, application journal and zero Chain hop count. MonitorState stores/clears ads, the application journal and application name/native method/manual-stop metadata from its RunPlan; none of these enter common or analyzer input.

- [ ] Write process/state regressions first: a normal Boutique run returns exactly two kitchen ads and a real negative settled cut; starting Chain afterward clears ads and Boutique labels. Verify unsafe/unknown HTTP control requests still cannot start a workload. Run the new tests to observe failure before editing these paths.
- [ ] Select application explicitly in MonitorApplication; retain the existing sampler and separate JVM. Map real response ads to AdView without deriving any verdict from successful completion:

```java
List<MonitorApplication.AdView> shown = response.getAdsList().stream()
    .map(ad -> new MonitorApplication.AdView(ad.getRedirectUrl(), ad.getText())).toList();
System.out.println("COMPLETE " + json.writeValueAsString(
    new MonitorApplication.Completion("OK", 0, shown, deployment.journal().entries())));
```

- [ ] For natural completion only, after workload exit let the parent await a report evaluated after the completion message, with complete coverage and a certified cut advanced beyond the pre-completion cut, for at most the existing final-report grace. If unavailable, retain inconclusive state; never manufacture a negative or wait for a chosen verdict after a crash. Keep the native normal-mode test requiring an actual qualified negative to expose premature analyzer shutdown.
- [ ] Archive Boutique configuration plus actual reply data alongside existing result/evidence artifacts. Keep one-run exclusion until actual process exit; capture the old immutable snapshot before accepting a new run. Source labels must explicitly say `Online Boutique Ad Service · instrumented adaptation`.
- [ ] Rerun monitor state/control/process tests and all existing Chain process tests; format and commit task-owned paths.

### Task 5: Real deadlock/control proof, browser and handoff

**Files:** Extend `BoutiqueMonitorRunTest.java`; modify `testbed/src/main/resources/monitor/index.html`, `monitor.js`, `monitor.css`, README.md, tasks.md, docs/decisions.md; create `docs/online-boutique-integration.md`.

- [ ] Write the real process regressions first. Start the Boutique deadlock through MonitorRun, wait on report notifications with a bounded condition wait, and assert three executions/three units/one queued member at ads-a, exact owner↔slot bindings and required persistence. Leave it running beyond the previous eight-second stop using a bounded process-exit future timeout, terminate only its workload PID, and assert STOPPED plus preserved first confirmation and no invented terminal negative.
- [ ] Add the spare-capacity process case with real kitchen reply data, no positive confirmation and a complete negative whose certified horizon lies in the finite callback leaf interval. Independently record that leaf's application entry/return times in the testbed; do not read analyzer truth or reuse reducer logic to construct expectations.
- [ ] Run `./gradlew :testbed:test --tests '*BoutiqueMonitorRunTest'`; observe the behavior failure before completing the missing harness path. Fix implementation at the responsible layer until both cases pass; keep tests' literal topology/ownership expectations independent.
- [ ] Add three Boutique choices to the existing scenario control. Render actual ads with textContent and show only validated local `/product/` paths as links (or plain text); never inject response HTML. Display native RPC/provenance, derive the process's service list from the manifest, show Chain hop counts only for Chain, and choose manual-stop text from explicit state rather than run-ID prefixes.

```javascript
const services = state.manifest.instances
  .filter(instance => instance.service_binding !== 'driver')
  .map(instance => instance.service_binding);
process.append(text('p', `${services.join(', ')} share workload PID ${state.application_pid}.`));
```

- [ ] Keep the explicit DEADLOCK DETECTED and post-exit DEADLOCK ENDED banners, stable expanded diagnostic details, bounded-history UNKNOWN labels, and exact identity/stack panels. Verify a real normal reply, injected deadlock and spare control through the browser; then leave the requested manually stopped Boutique deadlock running with its actual PID for the owner.
- [ ] Document source changes, native RPC, mode/metadata contract, provenance/license, seed, exact verification commands/results and known limits. Append the next decision entry for this reviewed application adaptation; do not alter earlier decisions or claim formal gate acceptance. No automatic cancellation/remediation is introduced.
- [ ] Run final checks with Java 21:

```sh
./gradlew spotlessApply build
./gradlew :analyzer:test :testbed:test
node --check testbed/src/main/resources/monitor/monitor.js
git diff --check
```

- [ ] Review only task-owned diffs against the approved spec and invariants; record self-review as such, not independent review. Commit task-owned paths only after checks pass. Report the actual running PID and retained artifact directory, native RPC result, test counts, and material remaining limits.

## Plan self-review

All spec sections map to Tasks 1–5: source/license and selection (1), native RPC and validated fault metadata (1–2), deployment/modes/invariants (2–3), process/data provenance/fast completion (4), real evidence/controls/browser/handoff (5). Each Review Focus item has a named owning regression. All custom API names above are introduced by their producer task. The baseline detector protocol and existing CLI semantics remain unchanged.

## Execution handoff

The written design is approved. This plan awaits owner review before implementation.
Execute inline using executing-plans, without subagents, consistent with AGENTS.md.
