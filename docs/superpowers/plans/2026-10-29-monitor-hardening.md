# Monitor Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the integrated monitor's display ordering, Boutique shutdown/error handling, lost UI states, and listed maintenance issues without changing detector contracts or semantics.

**Architecture:** `MonitorState` owns bounded, sequence-aware display projections; the browser only renders those projections and recent event activity. The workload process exposes explicit startup and stop lifecycle signals, while shared testbed helpers own root instrumentation and telemetry cleanup for both deployments.

**Tech Stack:** Java 21, JUnit 5, AssertJ, grpc-java, plain HTML/CSS/JavaScript, Node test runner, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-10-29-monitor-hardening-design.md`

## Global Constraints

- Preserve schema version 2 and do not modify `common`, `instrumentation`, or analyzer behavior.
- Monitor projections are display-only and never detector evidence.
- Use latches/process protocols rather than sleeps for synchronization.
- Add no dependencies and format Java with google-java-format.
- Do not commit changes unless the owner separately requests a commit.

## Review Focus

- Reordered init/acquire/release and block/wait terminal events must not roll display state backward.
- Parent EOF, graceful STOP, and TERM must all release the Boutique edge port and flush cleanup.
- An early user stop must remain STOPPED; only an explicit child startup failure becomes FAILED.
- A workload dying during inject must produce HTTP 409 rather than a dropped connection.
- Every class emitted by monitor JavaScript must have an intentional flat-theme style.

---

### Task 1: Sequence-aware monitor projections

**Files:**
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorState.java`
- Test: `testbed/src/test/java/dev/spanlease/testbed/MonitorStateTest.java`

**Interfaces:**
- Produces: snapshot `serviceLatest` views containing instance ID, latest local sequence, blocked count, and queued count; package-private `canInject()`.

- [ ] Add tests proving older slot events, late init, old block begins, and old wait begins cannot overwrite newer display state.
- [ ] Run the focused test and confirm the new assertions fail for the expected stale-update reason.
- [ ] Add per-entity sequence guards, per-instance latest sequence/count projections, and the direct injection eligibility accessor.
- [ ] Run `:testbed:test --tests dev.spanlease.testbed.MonitorStateTest` and confirm it passes.

### Task 2: Graceful Boutique lifecycle and explicit errors

**Files:**
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorApplication.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorRun.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/LiveMonitor.java`
- Test: `testbed/src/test/java/dev/spanlease/testbed/BoutiqueMonitorRunTest.java`
- Test: `testbed/src/test/java/dev/spanlease/testbed/LiveMonitorTest.java`

**Interfaces:**
- Consumes: `MonitorState.canInject()`.
- Produces: child `STARTUP_FAILED <json>` and `STOP` stdin protocol; injection I/O maps to conflict.

- [ ] Add regressions for immediate Boutique stop/port reuse, explicit startup failure, and injection write failure classification.
- [ ] Run the focused tests and confirm the new cases fail under the current process protocol.
- [ ] Implement STOP and stdin-EOF cleanup, a TERM shutdown hook, explicit startup failure parsing, graceful close-before-kill fallback, and HTTP 409 handling.
- [ ] Run the focused lifecycle and HTTP tests and confirm they pass.

### Task 3: Restore service information and styles

**Files:**
- Modify: `testbed/src/main/resources/monitor/monitor.js`
- Modify: `testbed/src/main/resources/monitor/monitor.css`
- Test: `testbed/src/test/js/monitor.test.cjs`

**Interfaces:**
- Consumes: snapshot `unit_latest` and `service_latest`.

- [ ] Add Node tests for non-slot sequence movement, blocked/queued counts, absence of browser slot reconstruction, and all emitted CSS selectors.
- [ ] Run Node tests and confirm they fail for the missing information/styles.
- [ ] Render backend slot projections, service activity projections, and restore flat stopped/resolved/result/queued styling; delete the unreachable slot fallback.
- [ ] Run the Node suite and confirm it passes.

### Task 4: Remove scenario/seed/state duplication

**Files:**
- Modify: `testbed/src/main/java/dev/spanlease/testbed/Scenario.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorRun.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/MonitorApplication.java`
- Test: `testbed/src/test/java/dev/spanlease/testbed/BoutiqueScenarioTest.java`
- Test: `testbed/src/test/java/dev/spanlease/testbed/BoutiqueMonitorRunTest.java`

**Interfaces:**
- Produces: explicit `Scenario.MonitorBehavior`; one monitor seed passed in the child command line.

- [ ] Add tests that policy follows scenario metadata rather than its display name and that the archived seed equals the child seed.
- [ ] Run focused tests and confirm the metadata/seed assertions fail first.
- [ ] Replace name matching with explicit behavior metadata, centralize the seed, pass it to the child, and use direct state access instead of copying snapshots for injection checks.
- [ ] Run the focused tests and confirm they pass.

### Task 5: Share testbed runtime lifecycle setup

**Files:**
- Create: `testbed/src/main/java/dev/spanlease/testbed/RootInstrumentation.java`
- Create: `testbed/src/main/java/dev/spanlease/testbed/TelemetryPipelines.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/ChainDriver.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/ChainDeployment.java`
- Modify: `testbed/src/main/java/dev/spanlease/testbed/BoutiqueDeployment.java`
- Test: existing Chain/Boutique deployment and monitor integration tests.

**Interfaces:**
- Produces: package-private root caller lifecycle and telemetry collection lifecycle shared by both deployments.

- [ ] Run focused deployment tests to preserve a green behavioral characterization before refactoring.
- [ ] Extract only duplicated recorder/scanner/root-caller and telemetry open/close ownership; preserve shutdown ordering.
- [ ] Run deployment, monitor, and litmus tests and confirm behavior remains green.

### Task 6: Document and verify the integrated fix

**Files:**
- Modify: `docs/decisions.md` (append-only)
- Modify: `docs/live-monitor-design.md`

- [ ] Append the explicit display-projection and graceful-child-lifecycle decision and update the operator shutdown description.
- [ ] Run `spotlessApply`, Node tests, `./gradlew build integrationTest`, and `git diff --check` using Java 21.
- [ ] Inspect the final diff to confirm no common/instrumentation/analyzer files or frozen schemas changed.
