#!/usr/bin/env python3
"""Descriptive pilot cost figure; optional plotting dependencies are pinned separately."""
import argparse
import hashlib
import json
import pathlib
import platform
import statistics

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('summary', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    summary = json.loads(args.summary.read_text())
    cases = ['load-20-q256', 'load-100-q4']
    labels = ['20 roots\nqueue 256', '100 roots\nqueue 4']
    colors = {'bare': '#2878A4', 'instrumented': '#D87825'}
    metrics = [
        ('Successful-root p99', 'Milliseconds', lambda r: r['p99_successful_roots_ms']),
        ('Application + analyzer CPU', 'Milliseconds', lambda r: (r['application_process_cpu_ns'] + r['analyzer_process_cpu_ns']) / 1e6),
        ('Successful roots', 'Percent of submitted roots', lambda r: 100 * r['successful_roots'] / r['root_count']),
    ]
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'svg.hashsalt': 'spanlease-local-pilot'})
    fig, axes = plt.subplots(1, 3, figsize=(12.5, 4.4))
    for axis, (title, ylabel, value) in zip(axes, metrics):
        for index, case in enumerate(cases):
            for mode, offset in [('bare', -.13), ('instrumented', .13)]:
                rows = [r for r in summary['runs'] if r['workload'] == case and r['mode'] == mode]
                values = [value(r) for r in rows if value(r) is not None]
                xs = [index + offset + (i - (len(values) - 1) / 2) * .045 for i in range(len(values))]
                axis.scatter(xs, values, color=colors[mode], s=45, zorder=3,
                             label=mode.capitalize() if index == 0 else None)
                if values:
                    mean = statistics.mean(values)
                    axis.plot([index + offset - .07, index + offset + .07], [mean, mean],
                              color=colors[mode], linewidth=2.5)
        axis.set_title(title, fontsize=11)
        axis.set_ylabel(ylabel)
        axis.set_xticks(range(len(cases)), labels)
        axis.set_ylim(bottom=0)
        axis.set_xlim(-.45, 1.45)
        axis.grid(axis='y', alpha=.2)
        axis.spines[['top', 'right']].set_visible(False)
    axes[0].legend(frameon=False, fontsize=9)
    axes[2].set_ylim(0, 105)
    fig.suptitle('SpanLease local pilot — two fresh JVM runs per mode and workload', fontsize=13)
    fig.text(.5, .02, 'Dots: individual runs; bars: means. Cold-start, shared-host pilot; no steady-state overhead claim.',
             ha='center', fontsize=9, color='#444444')
    fig.tight_layout(rect=(0, .07, 1, .93))
    fig.savefig(args.output / 'pilot-costs.png', dpi=220, metadata={'Software': 'SpanLease pilot plot'})
    fig.savefig(args.output / 'pilot-costs.svg', metadata={'Date': None})
    plt.close(fig)
    (args.output / 'plot-provenance.json').write_text(json.dumps(dict(
        scope='descriptive local pilot; two run-level points per group',
        python_version=platform.python_version(), matplotlib_version=matplotlib.__version__,
        summary_sha256=hashlib.sha256(args.summary.read_bytes()).hexdigest()), indent=2) + '\n')


if __name__ == '__main__':
    main()
