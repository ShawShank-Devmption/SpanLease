# Reproduction

Use Java 21 and the checked-in Gradle wrapper. Statistics use Python's standard library
(tested locally with Python 3 and in CI with Python 3.11); no unpinned Python packages.

```sh
./gradlew --no-daemon build :testbed:integrationTest
python3 -m unittest discover -s eval/tests -v
./gradlew :testbed:experiments --args='pilot 3 eval/results/pilot-3'
python3 eval/summarize.py eval/results/pilot-3 eval/results/pilot-3/summary.json
```

Use a new output directory for every plan. The slower `matrix` plan adds ring
length/capacity/deadline sweeps. Review `protocol.md` before treating its output as
research evidence. A run seed fixes configuration/fault selection, not OS scheduling.

For a single bare/instrumented run:

```sh
./gradlew :testbed:scenario --args='slow-chain bare eval/results/manual 42'
./gradlew :testbed:scenario --args='slow-chain instrumented eval/results/manual 42'
```

A completed instrumented directory contains `observations.replay.json`. Reproduce
its reports without a network or truth file, and compare native-span information
and triggered-buffer retention:

```sh
./gradlew :analyzer:replay --args='RUN/observations.replay.json RUN/replayed.jsonl'
./gradlew :baselines:replay --args='RUN/observations.replay.json RUN/baselines.jsonl'
```

Substitute the actual run directory for RUN. `reports.jsonl` and `replayed.jsonl`
must match for a recorded all-evaluation session. The SDK baseline's instance-fact
span is intentionally enriched with the entire detector observation contract; its
handler spans alone have no pre-admission queue/slot facts. Buffer eviction can lose
startup evidence and makes analysis inconclusive. Cycle/saturation alarms are not
confirmed deadlock verdicts.

Fault and real service-JVM crash reproduction:

```sh
./gradlew :testbed:integrationTest --tests dev.spanlease.testbed.FaultMatrixTest
./gradlew :testbed:integrationTest --tests dev.spanlease.testbed.CrashIntegrationTest
```

Crash artifacts include the last atomically written truth snapshots and an explicit
truncation record. Do not infer post-crash truth, an execution end or a slot release.
CI retains integration run artifacts even on failure. Local experimental results are
ignored by Git; archive the full run directory along with the source revision and
summary before making results claims.
