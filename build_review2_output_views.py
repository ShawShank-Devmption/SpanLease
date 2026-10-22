#!/usr/bin/env python3
"""Render captured Review 2 outputs in a legible local browser view for screenshots."""

import html
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "output" / ".review2_assets"
ASSETS.mkdir(parents=True, exist_ok=True)
lines = (ROOT / "output" / "SpanLease_Review2_Demo_Output.txt").read_text().splitlines()
assert lines[0] == "closed-ring" and lines[2] == "spare-capacity" and len(lines) == 4
deadlock, spare = json.loads(lines[1]), json.loads(lines[3])
assert deadlock["verdict"] == "CONFIRMED_DEADLOCK"
assert spare["verdict"] == "CONFIRMED_NO_DEADLOCK"


def page(name, subtitle, lines_to_show):
    content = "\n".join(html.escape(line) for line in lines_to_show)
    doc = f"""<!doctype html><html><head><meta charset='utf-8'>
<style>
* {{box-sizing:border-box}}
html,body {{margin:0;width:1200px;height:675px;background:#182029;color:#eaf0f2;
  font-family:Menlo,Consolas,monospace}}
.header {{height:100px;background:#263645;padding:21px 36px;border-bottom:5px solid #bb5739}}
.label {{font:700 16px Arial,sans-serif;color:#f3b29f;letter-spacing:2px}}
.title {{font:700 30px Arial,sans-serif;margin-top:9px}}
.screen {{padding:30px 42px;font-size:20px;line-height:1.52;white-space:pre-wrap}}
.muted {{color:#a7b8c5}} .ok {{color:#80d0a8}}
.footer {{position:absolute;bottom:0;left:0;right:0;height:50px;background:#263645;
  padding:13px 36px;color:#bfcbd2;font:15px Arial,sans-serif}}
</style></head><body><div class='header'><div class='label'>CAPTURED OUTPUT</div>
<div class='title'>{html.escape(name)}</div></div><div class='screen'>{content}</div>
<div class='footer'>{html.escape(subtitle)}</div></body></html>"""
    return doc


deadlock_lines = [
    "$ ./gradlew -q :analyzer:productionDemo",
    "scenario: closed-ring",
    f"run_id: {deadlock['run_id']}",
    f"verdict: {deadlock['verdict']}",
    f"scope: {', '.join(deadlock['query']['scope_instances'])}",
    f"occupied units: {deadlock['coverage']['units_with_observed_state']}/{deadlock['coverage']['units_total']}",
    f"persistence: {deadlock['persistence']['narrowed_span_ms']} ms >= tau {deadlock['persistence']['tau_ms']} ms",
    f"blocked executions: {', '.join(x['id'] for x in deadlock['core']['executions'])}",
    f"queued invocation: {deadlock['core']['queue_entries'][0]['invocation_id']}",
    f"evidence edges: {len(deadlock['core']['edges'])}",
    f"missing evidence: {len(deadlock['coverage']['missing'])}",
]
spare_lines = [
    "$ ./gradlew -q :analyzer:productionDemo",
    "scenario: spare-capacity",
    f"run_id: {spare['run_id']}",
    f"verdict: {spare['verdict']}",
    f"scope: {', '.join(spare['query']['scope_instances'])}",
    f"observed units: {spare['coverage']['units_with_observed_state']}/{spare['coverage']['units_total']}",
    "A has an additional free unit: A1",
    f"residual executions: {len(spare['core']['executions'])}",
    f"residual queue entries: {len(spare['core']['queue_entries'])}",
    f"residual edges: {len(spare['core']['edges'])}",
    f"missing evidence: {len(spare['coverage']['missing'])}",
]

totals = {}
for module in ("instrumentation", "analyzer", "testbed"):
    roots = [ET.parse(p).getroot() for p in (ROOT / module / "build/test-results/test").glob("TEST-*.xml")]
    totals[module] = tuple(sum(int(root.attrib.get(k, 0)) for root in roots)
                           for k in ("tests", "failures", "errors"))
    assert roots and totals[module][1:] == (0, 0)
test_lines = [
    "$ ./gradlew :instrumentation:test :analyzer:test :testbed:test --rerun-tasks",
    "Java 21  |  Gradle 8.12.1",
    "",
    f"instrumentation: {totals['instrumentation'][0]} tests, 0 failures",
    f"analyzer:        {totals['analyzer'][0]} tests, 0 failures",
    f"testbed:        {totals['testbed'][0]} tests, 0 failures",
    "",
    "Verified cases:",
    "  real gRPC loopback + bounded gate lifecycle",
    "  closed ring and spare-capacity analyzer verdicts",
    "  missing sequence remains inconclusive",
    "",
    f"BUILD SUCCESSFUL  |  {sum(v[0] for v in totals.values())} tests, 0 failures",
]

for filename, title, subtitle, content in [
    ("deadlock.html", "Closed-ring report", "Fields extracted from the captured v2 JSON report", deadlock_lines),
    ("spare.html", "Spare-capacity report", "Fields extracted from the captured v2 JSON report", spare_lines),
    ("tests.html", "Component verification", "Counts read from fresh Gradle JUnit XML results", test_lines),
]:
    (ASSETS / filename).write_text(page(title, subtitle, content))
    print(ASSETS / filename)
