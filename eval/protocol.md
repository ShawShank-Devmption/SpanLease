# Finite local evaluation protocol

This is an executable pilot protocol, recorded before the new pilot runs. It is not
an externally preregistered or independently reviewed publication experiment.

`ExperimentMain pilot R output` fixes the 10 workload cases in `Workload.standard`,
plus two loads (20 roots/queue 256 and 100 roots/queue 4). Each workload is run in bare
and instrumented modes for R repetitions, seeds 42 through 41+R. Mode ordering within
a pair is deterministically shuffled. `matrix` adds ring lengths 2/3/5, capacities 1/2,
and root deadlines 8/30 seconds. Every run uses a new manifest and process epoch.
Each trial uses a fresh application JVM, with a separate analyzer JVM when instrumented,
to avoid sharing JIT or application state between replications. There is no mid-run
schema or capacity change. Replica routing stays statically bound;
a multi-replica sweep is not yet included in this protocol.

The full plan is written before dispatch; an append-and-flush attempt ledger records
both completed and failed trials. No automatic retry discards failed trials. A failed
trial makes the command fail after retaining the other results. Source commit and
working-tree changes, Java/OS/architecture, seeds, manifests, schedules, truth,
evaluator-only mappings, received replay sessions and reports are retained. A dirty
source tree must be archived separately or rerun from a clean commit for reproduction.

Roots stop on workload completion or the independent observation window. Deadlock
verdicts never trigger cancellation. The coordinated saturated ring waits for all 3k
initial owners, releases all setup waiters, then begins measurement; all leaves queue
at the next service. Other rings fill capacity by a finite serial route with no setup
barrier. Cancellation/drain follows measurement and is outside scored persistence.

Application process CPU and analyzer process CPU are measured separately during the
root/observation phase, excluding process deployment and the final report-settlement
wait. Root latency uses monotonic submit-to-future-completion time. Report all-root and
successful-root p99 separately so rejections cannot masquerade as faster success.
Maximum physical queue depth is derived from truth gate decisions. Encoded event JSON
bytes are measured at the sink, **not network wire bytes**. Byte/drop/flush counters
cover startup through teardown; CPU and latency use the measurement phase above. SDK queue losses are not
assumed zero: sequence gaps remain the completeness evidence. Whole-run cold-start,
JIT/cache, shared-host interference and truth recorder costs are validity limitations;
this pilot does not isolate steady-state instrumentation cost or all OS resource costs.

The unit of statistical replication is a run. `summarize.py` computes per-run p99,
then seeded percentile bootstrap intervals (5,000 resamples) for mean run-level metrics
within each workload/mode. One-run groups have no bootstrap CI. It never treats the
analyzer's repeated ticks or individual roots within a run as independent trials.
False-positive and false-negative-confirmation counts are run indicators. Positive
witnesses are joined through explicit evaluator mappings and compared with bounded
physical truth subset enumeration across every transition in the claimed interval.
Negative comparisons are scored only when physical state is stable across the cut
horizon-to-evaluation uncertainty window; ambiguous vector-cut mappings are reported
unscorable, not silently treated as successes. The oracle refuses more than 20 current
candidates. These are local measured-clock checks, not a general multi-host truth proof.

For zero observed failures among N independently repeated scorable runs, report the
one-sided 95% bound `1 - 0.05**(1/N)` separately for each confirmation direction. Missing
or unscorable trials do not inflate N. A small pilot cannot support a low failure-rate
claim. Approximately 2,995 zero-failure trials are needed for an upper bound below 0.1%
under the sampled distribution. Detection misses remain separate from false confirmed
negative reports. Report all failed attempts and inconclusive/unscorable counts.

The externally sourced hotel-search paths and their transformations are documented in
`docs/external-workload.md`; these are not original DeathStarBench performance results.
The native-span baseline replay is an information/retention comparison with the same
received facts. It is not a live baseline instrumentation-cost comparison, an active
DDMon implementation, or a reproduction of Hindsight. Those scope distinctions must
survive any manuscript or figures. Protocol review, warmup/steady-state cost studies,
mechanism ablations, replica/degraded-load sweeps, broader external validity and
multi-host clock/scheduler bounds remain prerequisites for the full research evaluation.
