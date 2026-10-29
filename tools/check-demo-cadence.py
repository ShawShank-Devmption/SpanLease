#!/usr/bin/env python3
"""Check final-demo report cadence from the retained end-of-run reports."""

import argparse
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path, help="run directory's reports.jsonl")
    parser.add_argument("--max-gap-ms", type=int, default=1000)
    args = parser.parse_args()
    try:
        times = [json.loads(line)["evaluated_at_wall_ms"]
                 for line in args.reports.read_text().splitlines() if line.strip()]
        if len(times) < 2:
            raise ValueError("at least two reports are required")
        if any(type(time) is not int for time in times):
            raise ValueError("evaluation times must be integer milliseconds")
        gaps = [after - before for before, after in zip(times, times[1:])]
        passed = min(gaps) >= 0 and max(gaps) <= args.max_gap_ms
        print(json.dumps({"reports": len(times), "first_evaluated_at_wall_ms": times[0],
                          "last_evaluated_at_wall_ms": times[-1],
                          "retained_span_ms": times[-1] - times[0],
                          "min_gap_ms": min(gaps), "max_gap_ms": max(gaps),
                          "limit_ms": args.max_gap_ms, "passed": passed}, indent=2))
        return 0 if passed else 1
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Invalid report archive: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
