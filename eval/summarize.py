#!/usr/bin/env python3
"""Run-level pilot statistics using only Python's standard library; ticks are not trials."""
from __future__ import annotations
import argparse
import collections
import json
import math
import pathlib
import platform
import random
import statistics


def percentile(values, probability):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def bootstrap_mean(values, seed=42, repetitions=5000):
    if len(values) < 2:
        return None
    rng = random.Random(seed)
    samples = [statistics.mean(rng.choices(values, k=len(values))) for _ in range(repetitions)]
    return [percentile(samples, .025), percentile(samples, .975)]


def zero_failure_upper_bound(trials, confidence=.95):
    if trials <= 0:
        return None
    return -math.expm1(math.log1p(-confidence) / trials)


def closed_at(entries, capacities, at, selected=None):
    """Independent physical-state subset oracle, deliberately bounded to small scored runs."""
    owners, waiting, blocked, jobs = {}, set(), {}, {}
    for entry in entries:
        job = entry['job']
        if entry['wall_ms'] > at or not job:
            continue
        key = f"{job['request_id']}/{job['remaining']}"
        jobs[key] = job
        transition = entry['transition']
        if transition == 'QUEUE':
            waiting.add(key)
        elif transition == 'ASSIGN':
            waiting.discard(key)
            owners[(entry['service'], entry['slot'])] = key
        elif transition == 'CALL_ENTER':
            blocked[key] = f"{job['request_id']}/{job['remaining'] - 1}"
        elif transition in ('CALL_RETURN', 'EXIT'):
            blocked.pop(key, None)
        elif transition == 'RELEASE':
            owners.pop((entry['service'], entry['slot']), None)
        elif transition in ('CANCEL', 'REJECT'):
            waiting.discard(key)
    if selected is not None:
        if not selected:
            return False
        return all((blocked[member] in selected) if member in blocked else
                   (member in waiting and all(owners.get((jobs[member]['service'], slot)) in selected
                                              for slot in range(capacities[jobs[member]['service']])))
                   for member in selected)
    candidates = sorted(waiting | blocked.keys())
    if len(candidates) > 20:
        return None
    union = set()
    for mask in range(1, 1 << len(candidates)):
        subset = {member for i, member in enumerate(candidates) if mask & (1 << i)}
        closed = True
        for member in subset:
            if member in blocked:
                if blocked[member] not in subset:
                    closed = False
                    break
            elif any(owners.get((jobs[member]['service'], slot)) not in subset
                     for slot in range(capacities[jobs[member]['service']])):
                closed = False
                break
        if closed:
            union.update(subset)
    return union


def score(result, manifest):
    capacities = {next(i['service_binding'] for i in manifest['instances']
                       if i['instance_id'] == p['instance_id']): len(p['unit_ids'])
                  for p in manifest['pools']}
    truth = result['truth']
    mappings = result['mappings']
    by_execution = {m['execution']: m['job'] for m in mappings if m['execution']}
    by_invocation = {m['invocation']: m['job'] for m in mappings}
    bad_positive, bad_negative, uncertain = 0, 0, 0
    first_positive = None
    scored_positive = scored_negative = 0
    for report in result['reports']:
        verdict = report['verdict']
        if verdict == 'CONFIRMED_DEADLOCK':
            interval = report['persistence']
            first_positive = first_positive or report['evaluated_at_wall_ms']
            jobs = [by_execution.get(e['id']) for e in report['core']['executions']]
            jobs += [by_invocation.get(q['invocation_id']) for q in report['core']['queue_entries']]
            if not jobs or any(job is None for job in jobs):
                uncertain += 1
                continue
            expected = {f"{j['request_id']}/{j['remaining']}" for j in jobs}
            boundaries = {interval['first_ms'], interval['last_ms']}
            boundaries.update(e['wall_ms'] for e in truth
                              if interval['first_ms'] <= e['wall_ms'] <= interval['last_ms'])
            values = [closed_at(truth, capacities, at, expected) for at in boundaries]
            if any(value is None for value in values):
                uncertain += 1
            else:
                scored_positive += 1
                if not all(values):
                    bad_positive += 1
        elif verdict == 'CONFIRMED_NO_DEADLOCK':
            horizon = report['query']['certified_through_wall_ms']
            if horizon is None:
                uncertain += 1
                continue
            # A vector cut cannot in general be joined to truth by one timestamp. Only score a
            # negative when the entire physical state was stable across its time uncertainty.
            epsilon = manifest['parameters']['epsilon_ms']
            changes = {'READY', 'QUEUE', 'ASSIGN', 'CALL_ENTER', 'CALL_RETURN', 'CANCEL', 'EXIT', 'RELEASE'}
            if any(e['transition'] in changes and horizon - epsilon <= e['wall_ms'] <= report['evaluated_at_wall_ms'] + epsilon
                   for e in truth):
                uncertain += 1
                continue
            value = closed_at(truth, capacities, horizon)
            if value is None:
                uncertain += 1
            else:
                scored_negative += 1
                if value:
                    bad_negative += 1
    expected = result['closed_jobs']
    latencies = [o['latency_ns'] / 1e6 for o in result['outcomes'] if o.get('latency_ns', -1) >= 0]
    success_latencies = [o['latency_ns'] / 1e6 for o in result['outcomes']
                         if o.get('latency_ns', -1) >= 0 and o['status'] == 'OK']
    deadlines = [o['deadline_at_ms'] for o in result['outcomes'] if 'deadline_at_ms' in o]
    time_saved = min(deadlines) - first_positive if deadlines and first_positive is not None else None
    return dict(time_saved_before_earliest_deadline_ms=time_saved,
                scorable_positive_reports=scored_positive, scorable_negative_reports=scored_negative,
                false_positive_run=int(bad_positive > 0), false_negative_run=int(bad_negative > 0),
                positive_reports_mismatching_physical_oracle=bad_positive,
                negative_reports_mismatching_stable_physical_oracle=bad_negative,
                unscorable_report_count=uncertain,
                missed_persistent_scenario_run=int(bool(expected) and first_positive is None) if result["instrumented"] else None,
                first_positive_ms=first_positive, root_count=len(result['outcomes']),
                successful_roots=sum(o['status'] == 'OK' for o in result['outcomes']),
                p99_all_roots_ms=percentile(latencies, .99),
                p99_successful_roots_ms=percentile(success_latencies, .99), **result.get('metrics', {}))


def summarize(root):
    groups = collections.defaultdict(list)
    rows = []
    for path in sorted(root.glob('*/result.json')):
        result = json.loads(path.read_text())
        manifest = json.loads(path.with_name('manifest.json').read_text())
        row = dict(directory=str(path.parent), workload=result['workload'], mode='instrumented' if result['instrumented'] else 'bare',
                   seed=result['seed'], **score(result, manifest))
        rows.append(row)
        groups[(row['workload'], row['mode'])].append(row)
    summaries = []
    for (workload, mode), values in sorted(groups.items()):
        metrics = {}
        for metric in ('p99_all_roots_ms', 'p99_successful_roots_ms', 'application_process_cpu_ns',
                       'analyzer_process_cpu_ns', 'encoded_event_json_bytes', 'max_physical_queue_depth'):
            measurements = [v[metric] for v in values if v.get(metric) is not None]
            metrics[metric] = dict(n=len(measurements), mean=statistics.mean(measurements) if measurements else None,
                                   bootstrap_95_ci=bootstrap_mean(measurements))
        fp = sum(v['false_positive_run'] for v in values)
        fn = sum(v['false_negative_run'] for v in values)
        summaries.append(dict(workload=workload, mode=mode, independent_runs=len(values), metrics=metrics,
                              false_positive_runs=fp, false_negative_runs=fn,
                              missed_persistent_scenario_runs=sum(v['missed_persistent_scenario_run'] for v in values) if mode == 'instrumented' else None,
                              scorable_positive_runs=sum(v['scorable_positive_reports'] > 0 for v in values),
                              scorable_negative_runs=sum(v['scorable_negative_reports'] > 0 for v in values),
                              zero_false_positive_95_upper_bound=zero_failure_upper_bound(sum(v['scorable_positive_reports'] > 0 for v in values)) if fp == 0 else None,
                              zero_false_negative_confirmation_95_upper_bound=zero_failure_upper_bound(sum(v['scorable_negative_reports'] > 0 for v in values)) if fn == 0 else None))
    failures = []
    ledger = root / 'attempts.jsonl'
    if ledger.exists():
        failures = [json.loads(line) for line in ledger.read_text().splitlines() if json.loads(line)['status'] != 'completed']
    return dict(scope='local pilot; physical oracle checks are bounded; ambiguous vector-cut comparisons are unscorable',
                python_version=platform.python_version(), bootstrap_seed=42, bootstrap_repetitions=5000, failed_attempts=failures, runs=rows, groups=summaries)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('runs', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    args = parser.parse_args()
    result = summarize(args.runs)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(f"wrote {len(result['runs'])} runs, {len(result['groups'])} groups; {len(result['failed_attempts'])} failed attempts")


if __name__ == '__main__':
    main()
