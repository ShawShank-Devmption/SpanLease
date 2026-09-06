#!/usr/bin/env python3
"""Generate SpanLease Project Review 1 presentation from the research proposal."""

from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

# ---- palette (matches the proposal's terracotta / cream identity) ----
TERRA      = RGBColor(0xC0, 0x5A, 0x3C)   # accent
TERRA_DK   = RGBColor(0x9A, 0x43, 0x2B)
INK        = RGBColor(0x22, 0x22, 0x22)   # body text
INK_SOFT   = RGBColor(0x55, 0x55, 0x55)
CREAM      = RGBColor(0xF7, 0xF3, 0xEE)   # panel fill
CREAM_DK   = RGBColor(0xEC, 0xE4, 0xDA)
WHITE      = RGBColor(0xFF, 0xFF, 0xFF)
NAVY       = RGBColor(0x2C, 0x36, 0x50)   # secondary accent
GREEN      = RGBColor(0x3C, 0x6E, 0x47)
GREY_LINE  = RGBColor(0xD8, 0xD0, 0xC6)

HEAD_FONT  = "Georgia"
BODY_FONT  = "Calibri"
MONO_FONT  = "Consolas"

EMU = 914400
SW, SH = Inches(13.333), Inches(7.5)

prs = Presentation()
prs.slide_width  = SW
prs.slide_height = SH
BLANK = prs.slide_layouts[6]


def slide():
    return prs.slides.add_slide(BLANK)


def box(s, l, t, w, h):
    tb = s.shapes.add_textbox(l, t, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    return tb, tf


def rect(s, l, t, w, h, fill=None, line=None, line_w=None):
    sp = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, l, t, w, h)
    sp.shadow.inherit = False
    if fill is None:
        sp.fill.background()
    else:
        sp.fill.solid()
        sp.fill.fore_color.rgb = fill
    if line is None:
        sp.line.fill.background()
    else:
        sp.line.color.rgb = line
        sp.line.width = line_w or Pt(1)
    return sp


def set_run(r, text, size, color, bold=False, italic=False, font=BODY_FONT):
    r.text = text
    r.font.size = Pt(size)
    r.font.color.rgb = color
    r.font.bold = bold
    r.font.italic = italic
    r.font.name = font


def para(tf, text, size, color, bold=False, italic=False, font=BODY_FONT,
         space_after=6, space_before=0, align=PP_ALIGN.LEFT, level=0, first=False,
         line_spacing=1.06):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.level = level
    p.space_after = Pt(space_after)
    p.space_before = Pt(space_before)
    p.line_spacing = line_spacing
    r = p.add_run()
    set_run(r, text, size, color, bold, italic, font)
    return p


def bullet(tf, text, size=15, color=INK, bold=False, first=False, space_after=7,
           lead=None, lead_color=TERRA, indent=False):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.space_after = Pt(space_after)
    p.space_before = Pt(0)
    p.line_spacing = 1.05
    p.alignment = PP_ALIGN.LEFT
    if indent:
        p.level = 1
    r0 = p.add_run()
    set_run(r0, ("– " if indent else "▪  "), size, lead_color, bold=True)
    if lead:
        rl = p.add_run()
        set_run(rl, lead, size, color, bold=True)
        rs = p.add_run()
        set_run(rs, text, size, color, bold=False)
    else:
        r = p.add_run()
        set_run(r, text, size, color, bold)
    return p


def kicker(s, text, l=Inches(0.9), t=Inches(0.55), color=TERRA):
    _, tf = box(s, l, t, Inches(10), Inches(0.4))
    p = para(tf, text.upper(), 12.5, color, bold=True, first=True)
    # letter spacing
    rPr = p.runs[0]._r.get_or_add_rPr()
    rPr.set('spc', '180')
    return p


def title(s, text, t=Inches(0.92), size=32, color=INK, l=Inches(0.9), w=Inches(11.5)):
    _, tf = box(s, l, t, w, Inches(1.0))
    para(tf, text, size, color, bold=True, first=True, font=HEAD_FONT,
         line_spacing=1.0)


def accent_bar(s, t=Inches(1.72), l=Inches(0.92), w=Inches(0.9)):
    rect(s, l, t, w, Pt(3.2), fill=TERRA)


def footer(s, page):
    _, tf = box(s, Inches(0.9), Inches(7.02), Inches(9), Inches(0.35))
    para(tf, "SpanLease · Trace-based distributed deadlock detection", 9,
         INK_SOFT, first=True)
    _, tf2 = box(s, Inches(11.6), Inches(7.02), Inches(1.5), Inches(0.35))
    para(tf2, f"{page:02d}", 9, INK_SOFT, first=True, align=PP_ALIGN.RIGHT)


def content_slide(kick, ttl):
    s = slide()
    rect(s, 0, 0, SW, SH, fill=WHITE)
    kicker(s, kick)
    title(s, ttl)
    accent_bar(s)
    return s


def panel(s, l, t, w, h, fill=CREAM, bar=TERRA, bar_w=Pt(3.2)):
    rect(s, l, t, w, h, fill=fill)
    if bar:
        rect(s, l, t, bar_w, h, fill=bar)
    return


# =====================================================================
# SLIDE 1 — TITLE
# =====================================================================
s = slide()
rect(s, 0, 0, SW, SH, fill=WHITE)
rect(s, 0, 0, Inches(0.32), SH, fill=TERRA)
# top meta
_, tf = box(s, Inches(0.95), Inches(0.75), Inches(11), Inches(0.4))
p = para(tf, "RESEARCH PROPOSAL  ·  REVISION 2  ·  PROJECT REVIEW 1", 13,
         TERRA, bold=True, first=True)
p.runs[0]._r.get_or_add_rPr().set('spc', '220')

_, tf = box(s, Inches(0.92), Inches(1.55), Inches(11.5), Inches(1.6))
para(tf, "SpanLease", 60, INK, bold=True, first=True, font=HEAD_FONT)

_, tf = box(s, Inches(0.95), Inches(2.95), Inches(11.2), Inches(1.2))
para(tf, "Pre-timeout localization of persistent RPC / executor circular waits "
         "from OpenTelemetry-compatible runtime events.", 21, INK_SOFT,
     first=True, font=HEAD_FONT, italic=True, line_spacing=1.12)

# central claim panel
pt = Inches(4.35)
panel(s, Inches(0.92), pt, Inches(11.5), Inches(1.72), fill=CREAM)
_, tf = box(s, Inches(1.25), pt + Inches(0.16), Inches(11.0), Inches(1.5))
para(tf, "CENTRAL CLAIM", 11, TERRA, bold=True, first=True)
para(tf, "An OpenTelemetry-compatible runtime monitor for synchronous Java/gRPC "
         "services on bounded executors. It reconstructs a causally consistent, "
         "capacity-aware wait-for graph and localizes persistent circular waits "
         "before RPC deadlines — returning an inconclusive result when the "
         "evidence does not justify confirmation.", 15, INK, space_before=3)

# footer meta row
_, tf = box(s, Inches(0.95), Inches(6.5), Inches(12), Inches(0.8))
p = para(tf, "Prepared by  Shashank", 13, INK, bold=True, first=True)
para(tf, "B.Tech CSE (Data Science), VIT — SCOPE      ·      Target venues: "
         "ACM Middleware · ICPE · IEEE IC2E      ·      August 2026", 12,
     INK_SOFT, space_before=2)

# =====================================================================
# SLIDE 2 — AGENDA
# =====================================================================
s = content_slide("Project Review 1", "What this review covers")
items = [
    ("Problem statement", "Persistent circular waits that current tooling sees only at the deadline"),
    ("Research gap", "Why the two available classes of evidence are each insufficient"),
    ("Literature review", "Six families of prior work and the constraints they impose"),
    ("Proposed solution", "Event contract, capacity-aware reconstruction, verdict semantics"),
    ("Novelty & innovation", "The bounded, defensible combination that is new"),
    ("Objectives", "Implementation phases, contributions and the evaluation plan"),
]
top = Inches(2.15)
colw = Inches(5.75)
for i, (h, d) in enumerate(items):
    col = i % 2
    row = i // 2
    l = Inches(0.95) + col * Inches(5.95)
    t = top + row * Inches(1.35)
    panel(s, l, t, colw, Inches(1.12), fill=CREAM, bar=TERRA)
    _, tf = box(s, l + Inches(0.35), t + Inches(0.13), colw - Inches(0.55), Inches(0.95))
    para(tf, f"{i+1:02d}   {h}", 16.5, INK, bold=True, first=True, font=HEAD_FONT)
    para(tf, d, 12.5, INK_SOFT, space_before=3)
footer(s, 2)

# =====================================================================
# SLIDE 3 — PROBLEM STATEMENT
# =====================================================================
s = content_slide("Problem statement", "Detecting the deadlock only at the deadline is too late")
_, tf = box(s, Inches(0.95), Inches(2.05), Inches(11.4), Inches(1.2))
para(tf, "Microservice systems built on synchronous RPC can enter persistent "
         "circular waits that involve both in-flight RPC executions and bounded "
         "executor capacity. While the condition persists, capacity inside the "
         "cycle is unavailable, queueing spreads outward, and timed-out clients "
         "retry — so the system can stay degraded after the trigger is gone.",
     15, INK, first=True, line_spacing=1.12)

# three consequence cards
cards = [
    ("Blocked capacity", "Every capacity unit inside the cycle is unusable to unrelated traffic."),
    ("Outward spread", "Queueing and client-side retries propagate the stall across services."),
    ("Persistent damage", "The regime survives the original trigger; external intervention is required."),
]
top = Inches(3.55)
for i, (h, d) in enumerate(cards):
    l = Inches(0.95) + i * Inches(3.95)
    panel(s, l, top, Inches(3.7), Inches(1.7), fill=CREAM, bar=TERRA)
    _, tf = box(s, l + Inches(0.32), top + Inches(0.22), Inches(3.2), Inches(1.4))
    para(tf, h, 16, TERRA_DK, bold=True, first=True, font=HEAD_FONT)
    para(tf, d, 13, INK, space_before=5, line_spacing=1.08)

panel(s, Inches(0.95), Inches(5.55), Inches(11.45), Inches(1.15), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(5.7), Inches(10.9), Inches(0.9))
para(tf, "The operational value of a verdict is not that it arrives sooner, but "
         "that it identifies WHICH executions and WHICH capacity units to act on "
         "— something neither a shortened deadline nor a service restart can express.",
     14.5, INK, bold=True, first=True, line_spacing=1.1)
footer(s, 3)

# =====================================================================
# SLIDE 4 — THREE CONDITIONS (key distinction)
# =====================================================================
s = content_slide("Problem statement · framing", "Three conditions that must not be conflated")
_, tf = box(s, Inches(0.95), Inches(2.0), Inches(11.4), Inches(0.6))
para(tf, "Operational discussion routinely merges three distinct conditions. "
         "SpanLease targets only the third — and the distinction governs every "
         "design decision that follows.", 14, INK_SOFT, first=True, italic=True,
     line_spacing=1.1)

rows = [
    ("Slow request", "An execution making progress, or waiting on a dependency that will respond.",
     "Resolves without intervention. Cancelling it destroys useful work.", CREAM),
    ("Executor saturation", "All capacity units of a bounded executor are occupied; arrivals queue.",
     "Evidence of pressure, not proof of deadlock. May clear when any owner finishes.", CREAM),
    ("Persistent circular wait", "A closed set where every member is blocked on capacity held inside the set.",
     "Does NOT resolve without intervention.  ← the target condition", CREAM_DK),
]
top = Inches(2.85)
rh = Inches(1.15)
# header
hcols = [(Inches(0.95), Inches(2.9), "CONDITION"),
         (Inches(3.9),  Inches(4.35), "DEFINITION"),
         (Inches(8.3),  Inches(4.1), "RESOLUTION BEHAVIOUR")]
for l, w, htxt in hcols:
    _, tf = box(s, l, top - Inches(0.42), w, Inches(0.35))
    p = para(tf, htxt, 11, TERRA, bold=True, first=True)
    p.runs[0]._r.get_or_add_rPr().set('spc', '120')

for i, (c, d, r, fill) in enumerate(rows):
    t = top + i * (rh + Inches(0.12))
    is_target = (i == 2)
    panel(s, Inches(0.95), t, Inches(11.45), rh, fill=fill,
          bar=(TERRA if is_target else GREY_LINE), bar_w=Pt(4 if is_target else 2))
    _, tf = box(s, Inches(1.25), t + Inches(0.14), Inches(2.6), rh - Inches(0.2))
    para(tf, c, 14.5, (TERRA_DK if is_target else INK), bold=True, first=True,
         font=HEAD_FONT, line_spacing=1.0)
    _, tf = box(s, Inches(3.9), t + Inches(0.14), Inches(4.3), rh - Inches(0.2))
    para(tf, d, 12.5, INK, first=True, line_spacing=1.05)
    _, tf = box(s, Inches(8.3), t + Inches(0.14), Inches(4.0), rh - Inches(0.2))
    para(tf, r, 12.5, (INK if is_target else INK_SOFT),
         bold=is_target, first=True, line_spacing=1.05)
footer(s, 4)

# =====================================================================
# SLIDE 5 — RESEARCH GAP
# =====================================================================
s = content_slide("Research gap", "Two classes of evidence exist — neither is sufficient")
_, tf = box(s, Inches(0.95), Inches(2.0), Inches(11.4), Inches(0.55))
para(tf, "While the condition persists, only two kinds of evidence are available, "
         "and each fails the predicate for a different reason.", 14, INK_SOFT,
     first=True, italic=True)

two = [
    ("Completed span traces", "TIMING",
     "Arrive on span end — at the deadline, outside the intervention window in a default deployment.",
     "GAP", "Carry full request identity, but too late to act on."),
    ("Aggregate saturation metrics", "TIMING",
     "Continuous, exported on a periodic interval.",
     "GAP", "Report counts and occupancy only. Cannot name which execution holds which unit, or which invocation waits on which execution."),
]
top = Inches(2.75)
for i, (h, k1, v1, k2, v2) in enumerate(two):
    l = Inches(0.95) + i * Inches(5.95)
    panel(s, l, top, Inches(5.75), Inches(2.75), fill=CREAM, bar=TERRA)
    _, tf = box(s, l + Inches(0.35), top + Inches(0.22), Inches(5.1), Inches(2.4))
    para(tf, h, 17, INK, bold=True, first=True, font=HEAD_FONT)
    para(tf, k1, 10.5, TERRA, bold=True, space_before=9)
    para(tf, v1, 13, INK, space_before=1, line_spacing=1.08)
    para(tf, "WHY IT FAILS THE PREDICATE", 10.5, TERRA, bold=True, space_before=9)
    para(tf, v2, 13, INK, space_before=1, line_spacing=1.08)

panel(s, Inches(0.95), Inches(5.75), Inches(11.45), Inches(1.0), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(5.88), Inches(10.9), Inches(0.8))
para(tf, "The gap:  no existing signal names the specific executions and capacity "
         "units of a persistent cycle, before the deadline, over bounded "
         "(multi-instance) executors.", 14.5, INK, bold=True, first=True,
     line_spacing=1.1)
footer(s, 5)

# =====================================================================
# SLIDE 6 — LITERATURE REVIEW
# =====================================================================
s = content_slide("Literature review", "Six families of prior work — and what each constrains")
fams = [
    ("Classical & consistent-predicate detection",
     "Chandy–Misra–Haas edge-chasing; Knapp's survey; Cooper–Marzullo & Garg–Waldecker modal predicates over consistent cuts.",
     "Cycle/knot detection, multi-valued verdicts and consistent cuts cannot be claimed as novel."),
    ("RPC & distributed-object monitors",
     "Cheriton–Skeen (SOSP '93); Kaveh & Emmerich (2001); DDMon (OOPSLA '25) — incl. passive observing monitors.",
     "Request-level RPC wait-for monitoring — even passive — is not new."),
    ("Middleware thread-pool modelling",
     "Sethi & Anand (Middleware '08); IBM patent on bounded web-service thread pools.",
     "Distributed thread-pool deadlock is a recognised phenomenon with prior art."),
    ("Live / in-progress observability",
     "AWS X-Ray in-progress segments; Pydantic Logfire pending spans; OTel zpages; TraceWeaver (SIGCOMM '24) — trace reconstruction without app changes.",
     "Exposing in-progress spans is not novel — it is the strongest baseline."),
    ("Triggered & retrospective tracing",
     "Hindsight (NSDI '23): cheap always-on capture + selective materialisation on trigger.",
     "The two-channel capture/materialise design is prior art."),
    ("Local / infrastructure detectors",
     "JVM thread introspection; DB wait-for-graph detection; eBPF mutex detectors (live WFG via uprobes).",
     "Live wait-for-graph construction is not novel; these are per-host / per-engine."),
]
top = Inches(1.98)
colw = Inches(5.75)
for i, (h, d, c) in enumerate(fams):
    col = i % 2
    row = i // 2
    l = Inches(0.95) + col * Inches(5.95)
    t = top + row * Inches(1.5)
    panel(s, l, t, colw, Inches(1.36), fill=CREAM, bar=NAVY)
    _, tf = box(s, l + Inches(0.3), t + Inches(0.08), colw - Inches(0.5), Inches(1.28))
    para(tf, h, 13, INK, bold=True, first=True, font=HEAD_FONT, line_spacing=1.0)
    para(tf, d, 10.5, INK_SOFT, space_before=2, line_spacing=1.0)
    p = para(tf, "Constraint:  " + c, 10.5, TERRA_DK, space_before=2, line_spacing=1.0)
    p.runs[0].font.bold = True
# recent-work highlight strip
rt = top + 3 * Inches(1.5) + Inches(0.02)
panel(s, Inches(0.95), rt, Inches(11.45), Inches(0.6), fill=CREAM_DK, bar=GREEN, bar_w=Pt(4))
_, tf = box(s, Inches(1.3), rt + Inches(0.08), Inches(11.0), Inches(0.5))
tf.vertical_anchor = MSO_ANCHOR.MIDDLE
p = para(tf, "PUBLISHED IN THE LAST 3 YEARS:   ", 11.5, GREEN, bold=True, first=True)
r = p.add_run()
set_run(r, "Hindsight — NSDI 2023   ·   TraceWeaver — SIGCOMM 2024   ·   "
           "DDMon — OOPSLA 2025", 11.5, INK, bold=True)
footer(s, 6)

# =====================================================================
# SLIDE 7 — COMPARISON TABLE
# =====================================================================
s = content_slide("Literature review · positioning", "Where SpanLease sits against prior approaches")
cols = ["Approach", "Multi-inst.\ncapacity", "Causal\nconsist.", "Loss\nhandled", "Pre-timeout\nverdict"]
data = [
    ("Classical WFG algorithms", "in theory", "yes", "no", "yes"),
    ("Consistent predicate detection", "varies", "yes", "no", "yes"),
    ("Cheriton–Skeen RPC monitor", "no", "partly", "no", "yes"),
    ("DDMon (passive observer)", "no", "weaker", "no", "yes"),
    ("In-progress spans (X-Ray, Logfire)", "no", "no", "no", "partial"),
    ("Triggered tracing (Hindsight)", "no", "no", "partly", "yes"),
    ("Executor / in-flight metrics", "counts", "no", "n/a", "satur. only"),
    ("Completed traces + timeout", "no", "post hoc", "sampl.", "no"),
    ("SpanLease (proposed)", "slot level", "explicit", "explicit", "yes"),
]
top = Inches(2.05)
xs = [Inches(0.95), Inches(5.35), Inches(7.35), Inches(9.15), Inches(10.75)]
ws = [Inches(4.4), Inches(2.0), Inches(1.8), Inches(1.6), Inches(1.6)]
# header row
rect(s, Inches(0.95), top, Inches(11.45), Inches(0.62), fill=NAVY)
for x, w, c in zip(xs, ws, cols):
    _, tf = box(s, x + Inches(0.08), top + Inches(0.03), w - Inches(0.1), Inches(0.58))
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, c, 10.5, WHITE, bold=True, first=True, line_spacing=0.95)
rh = Inches(0.48)
for ri, row in enumerate(data):
    t = top + Inches(0.62) + ri * rh
    is_last = (ri == len(data) - 1)
    fill = CREAM_DK if is_last else (CREAM if ri % 2 else WHITE)
    rect(s, Inches(0.95), t, Inches(11.45), rh, fill=fill)
    for j, (x, w, val) in enumerate(zip(xs, ws, row)):
        _, tf = box(s, x + Inches(0.08), t + Inches(0.02), w - Inches(0.1), rh - Inches(0.02))
        tf.vertical_anchor = MSO_ANCHOR.MIDDLE
        col = TERRA_DK if is_last else INK
        para(tf, val, 11 if j == 0 else 10.5, col,
             bold=(j == 0 or is_last), first=True, line_spacing=0.95,
             font=HEAD_FONT if j == 0 else BODY_FONT)
rect(s, Inches(0.95), top + Inches(0.62) + len(data)*rh, Inches(11.45), Pt(2.5), fill=TERRA)
footer(s, 7)

# =====================================================================
# SLIDE 8 — PROPOSED SOLUTION (pipeline)
# =====================================================================
s = content_slide("Proposed solution", "A four-stage OpenTelemetry-compatible pipeline")
stages = [
    ("1 · Event contract", "Ten custom event types — invocation, queue-arrival, slot acquire/release, block begin/end, renewable leases, cancellation, termination.", TERRA),
    ("2 · Causal reconstruction", "Consistent-cut construction from RPC send/arrival relations & local order. Physical time used only for persistence thresholds.", NAVY),
    ("3 · Capacity-aware predicate", "Directed graph over executions, queue entries and individual capacity units. Closure test replaces cycle detection (correct for k>1).", GREEN),
    ("4 · Four-valued verdict", "Confirmed / refuted / inconclusive / unobservable — each verdict shipped with a coverage report.", TERRA_DK),
]
top = Inches(2.15)
bw = Inches(2.72)
gap = Inches(0.18)
for i, (h, d, col) in enumerate(stages):
    l = Inches(0.95) + i * (bw + gap)
    rect(s, l, top, bw, Inches(2.5), fill=CREAM)
    rect(s, l, top, bw, Inches(0.62), fill=col)
    _, tf = box(s, l + Inches(0.18), top + Inches(0.09), bw - Inches(0.3), Inches(0.5))
    para(tf, h, 13.5, WHITE, bold=True, first=True, font=HEAD_FONT, line_spacing=0.95)
    _, tf = box(s, l + Inches(0.22), top + Inches(0.78), bw - Inches(0.4), Inches(1.7))
    para(tf, d, 11.5, INK, first=True, line_spacing=1.08)
    if i < 3:
        _, tf = box(s, l + bw - Inches(0.02), top + Inches(0.95), gap + Inches(0.15), Inches(0.5))
        para(tf, "→", 20, INK_SOFT, first=True, bold=True, align=PP_ALIGN.CENTER)

panel(s, Inches(0.95), Inches(5.1), Inches(11.45), Inches(1.55), fill=CREAM_DK, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(5.25), Inches(10.9), Inches(1.35))
para(tf, "Built on OpenTelemetry-compatible CUSTOM telemetry", 13, TERRA_DK,
     bold=True, first=True)
para(tf, "Reuses W3C context propagation, the logs data model, OTLP transport and "
         "the SpanProcessor extension point — but defines its own event vocabulary. "
         "Crucially, arrival is intercepted at the transport layer, so a queued "
         "invocation is observable before it has an execution or a span.",
     13, INK, space_before=4, line_spacing=1.1)
footer(s, 8)

# =====================================================================
# SLIDE 9 — THE TECHNICAL HEART (capacity-aware predicate + verdicts)
# =====================================================================
s = content_slide("Proposed solution · core", "Capacity-aware closure & conservative verdicts")
# left: predicate
panel(s, Inches(0.95), Inches(2.05), Inches(5.7), Inches(4.55), fill=CREAM, bar=GREEN)
_, tf = box(s, Inches(1.28), Inches(2.22), Inches(5.15), Inches(4.3))
para(tf, "Why cycle detection alone is wrong", 16, GREEN, bold=True, first=True,
     font=HEAD_FONT)
para(tf, "For a resource with capacity k > 1, a directed cycle is necessary but "
         "NOT sufficient — a holder outside the cycle may be about to release a "
         "satisfying unit.", 13, INK, space_before=6, line_spacing=1.1)
para(tf, "SpanLease models each executor slot as a distinct resource instance and "
         "applies a capacity-closure test (C3):", 13, INK, space_before=8,
     line_spacing=1.1)
bullet(tf, "every unit that could satisfy a blocked member is owned inside the set,", 12.5, space_after=4)
bullet(tf, "no satisfying unit is free or held by an execution outside the set.", 12.5, space_after=8)
para(tf, "An iterative reduction discharges any member that could still progress; "
         "what remains is an irreducible evidence core.", 13, INK, space_before=2,
     line_spacing=1.1)

# right: verdict lattice
panel(s, Inches(6.85), Inches(2.05), Inches(5.55), Inches(4.55), fill=CREAM, bar=TERRA)
_, tf = box(s, Inches(7.18), Inches(2.22), Inches(5.0), Inches(0.5))
para(tf, "Four-valued verdict lattice", 16, TERRA_DK, bold=True, first=True,
     font=HEAD_FONT)
verds = [
    ("CONFIRMED_DEADLOCK", "Terminal set exists; all conditions hold; coverage complete.", GREEN),
    ("CONFIRMED_NO_DEADLOCK", "Reduction empties the set — the predicate is refuted.", NAVY),
    ("CANDIDATE_INCONCLUSIVE", "A set survives, but a required observation is missing/delayed.", TERRA),
    ("INSUFFICIENT_OBSERVABILITY", "Coverage too incomplete to evaluate the predicate at all.", INK_SOFT),
]
vt = Inches(2.78)
for i, (name, desc, col) in enumerate(verds):
    t = vt + i * Inches(0.92)
    rect(s, Inches(7.18), t, Inches(0.12), Inches(0.78), fill=col)
    _, tf = box(s, Inches(7.42), t, Inches(4.85), Inches(0.85))
    para(tf, name, 12.5, col, bold=True, first=True, font=MONO_FONT)
    para(tf, desc, 11.5, INK, space_before=1, line_spacing=1.0)
footer(s, 9)

# =====================================================================
# SLIDE 10 — NOVELTY & INNOVATION
# =====================================================================
s = content_slide("Novelty & innovation", "A bounded, defensible novelty claim")
_, tf = box(s, Inches(0.95), Inches(2.0), Inches(11.4), Inches(0.85))
para(tf, "Individually, none of these are claimed as novel: wait-for graphs, "
         "resource-instance modelling, in-progress span exposure, leases, "
         "multi-valued verdicts, passive RPC observation, or distributed "
         "thread-pool deadlock. The contribution is the COMBINATION.", 14, INK,
     first=True, italic=True, line_spacing=1.12)

nov = [
    ("Slot-level capacity closure", "A capacity-closure predicate over individual executor slots — correct for k > 1, where cycle detection produces false positives."),
    ("Causally consistent reconstruction", "Rebuilt from OTel-compatible custom events, including pre-span queue observation of invocations that have no execution yet."),
    ("Coverage-conscious verdicts", "Semantics that separate missing evidence from missing instrumentation, and detection confidence from remediation safety."),
    ("Evaluated on identical ground truth", "Compared against in-progress-span and RPC-monitor baselines feeding the same analyzer, on a shared testbed."),
]
top = Inches(3.05)
for i, (h, d) in enumerate(nov):
    col = i % 2
    row = i // 2
    l = Inches(0.95) + col * Inches(5.95)
    t = top + row * Inches(1.5)
    panel(s, l, t, Inches(5.75), Inches(1.35), fill=CREAM, bar=TERRA)
    _, tf = box(s, l + Inches(0.35), t + Inches(0.16), Inches(5.1), Inches(1.15))
    para(tf, f"◆  {h}", 15, TERRA_DK, bold=True, first=True, font=HEAD_FONT)
    para(tf, d, 12.5, INK, space_before=4, line_spacing=1.08)

panel(s, Inches(0.95), Inches(6.15), Inches(11.45), Inches(0.72), fill=NAVY, bar=None)
_, tf = box(s, Inches(1.3), Inches(6.24), Inches(10.9), Inches(0.55))
para(tf, "The defensible contribution is an integration and formalisation — not a "
         "priority claim. Every component has ancestry; the composition is new.",
     13.5, WHITE, bold=True, first=True, align=PP_ALIGN.CENTER)
footer(s, 10)

# =====================================================================
# SLIDE 11 — OBJECTIVES / IMPLEMENTATION PLAN
# =====================================================================
s = content_slide("Objectives", "Implementation plan — risk-ordered phases")
phases = [
    ("P0", "Bounded-executor gRPC testbed + independent ground-truth recorder", "Deadlocks on demand; truth at request & slot level"),
    ("P1", "Transport-layer arrival interception (before executor dispatch)", "Primary integration risk — queued invocation observable pre-span"),
    ("P2", "Slot-level acquire/release with stable resource_instance_id", "Ownership reconstructable for the whole run"),
    ("P3", "Invocation-identity propagation & client block boundaries", "Client wait edges join server counterparts, no unmatched edges"),
    ("P4", "Lease scanner (age threshold + renewal) on priority channel", "Event volume bounded & characterised"),
    ("P5", "Analyzer: cut construction, reduction, coverage, verdicts", "Reproduces ground truth; NO_DEADLOCK on spare-capacity cycles"),
    ("P6 · P7", "Baselines, ablations & reproducibility package", "Identical workload & ground truth; every figure regenerable"),
]
top = Inches(2.05)
rh = Inches(0.63)
for i, (p, d, exit_) in enumerate(phases):
    t = top + i * (rh + Inches(0.045))
    fill = CREAM if i % 2 == 0 else WHITE
    rect(s, Inches(0.95), t, Inches(11.45), rh, fill=fill)
    rect(s, Inches(0.95), t, Inches(1.15), rh, fill=(TERRA if i < 2 else NAVY))
    _, tf = box(s, Inches(0.95), t, Inches(1.15), rh)
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, p, 13, WHITE, bold=True, first=True, align=PP_ALIGN.CENTER, font=HEAD_FONT)
    _, tf = box(s, Inches(2.28), t + Inches(0.04), Inches(5.7), rh - Inches(0.06))
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, d, 12.5, INK, bold=True, first=True, line_spacing=0.98)
    _, tf = box(s, Inches(8.15), t + Inches(0.04), Inches(4.15), rh - Inches(0.06))
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    para(tf, exit_, 11, INK_SOFT, first=True, line_spacing=0.98)
# header labels
_, tf = box(s, Inches(2.28), top - Inches(0.34), Inches(5), Inches(0.3))
para(tf, "DELIVERABLE", 10, TERRA, bold=True, first=True)
_, tf = box(s, Inches(8.15), top - Inches(0.34), Inches(4), Inches(0.3))
para(tf, "EXIT CRITERION", 10, TERRA, bold=True, first=True)
footer(s, 11)

# =====================================================================
# SLIDE 12 — EVALUATION PLAN
# =====================================================================
s = content_slide("Objectives · validation", "How the claims will be tested")
# research questions
panel(s, Inches(0.95), Inches(2.05), Inches(5.7), Inches(4.55), fill=CREAM, bar=NAVY)
_, tf = box(s, Inches(1.28), Inches(2.22), Inches(5.15), Inches(4.3))
para(tf, "Research questions", 16, NAVY, bold=True, first=True, font=HEAD_FONT)
rqs = [
    ("RQ1", "Localize cycles before the deadline — how much budget is recovered?"),
    ("RQ2", "Separate slow / saturated / circular-wait conditions?"),
    ("RQ3", "How accurately is the evidence core identified at slot level?"),
    ("RQ4", "How do verdicts behave under loss, delay, skew, partitions?"),
    ("RQ5", "Runtime, telemetry and analyzer cost — and scaling?"),
    ("RQ6", "What does each mechanism contribute, by ablation?"),
]
for i, (q, d) in enumerate(rqs):
    p = para(tf, q + "  ", 12.5, TERRA_DK, bold=True, space_before=(7 if i else 8))
    r = p.add_run()
    set_run(r, d, 12.5, INK)

# baselines + metrics
panel(s, Inches(6.85), Inches(2.05), Inches(5.55), Inches(4.55), fill=CREAM, bar=TERRA)
_, tf = box(s, Inches(7.18), Inches(2.22), Inches(5.0), Inches(4.3))
para(tf, "Baselines & metrics", 16, TERRA_DK, bold=True, first=True, font=HEAD_FONT)
para(tf, "Compared against, on identical ground truth:", 12.5, INK_SOFT,
     space_before=5, italic=True)
for b in ["Metrics-only saturation detection",
          "Completed-trace + timeout diagnosis (status quo)",
          "In-progress spans + same analyzer (strongest baseline)",
          "Cheriton–Skeen RPC wait-for monitor · DDMon observer"]:
    bullet(tf, b, 12, space_after=4)
para(tf, "Metrics", 12.5, TERRA_DK, bold=True, space_before=9)
for m in ["Timeliness — deadline budget saved",
          "Localisation — node/edge precision, recall, Jaccard",
          "Correctness — false-confirmation rate with one-sided upper bound",
          "Cost — CPU, memory, telemetry bytes, analyzer throughput"]:
    bullet(tf, m, 12, space_after=4)
footer(s, 12)

# =====================================================================
# SLIDE 13 — SUMMARY / CLOSING
# =====================================================================
s = slide()
rect(s, 0, 0, SW, SH, fill=NAVY)
rect(s, 0, 0, Inches(0.32), SH, fill=TERRA)
kicker(s, "Summary", t=Inches(0.7), color=TERRA)
_, tf = box(s, Inches(0.9), Inches(1.1), Inches(11.6), Inches(1.4))
para(tf, "SpanLease in one statement", 34, WHITE, bold=True, first=True,
     font=HEAD_FONT)
_, tf = box(s, Inches(0.92), Inches(2.15), Inches(11.5), Inches(1.6))
para(tf, "A slot-level capacity-closure predicate over a causally consistent "
         "reconstruction — built from an OpenTelemetry-compatible event contract "
         "that observes an invocation before it has an execution or a span — with "
         "verdict semantics that distinguish missing evidence from missing "
         "instrumentation.", 18, RGBColor(0xEB, 0xE6, 0xDF), first=True,
     line_spacing=1.18, italic=True)

takeaways = [
    ("Problem", "Persistent circular waits over bounded executors are seen only at the deadline."),
    ("Gap", "Neither completed traces nor saturation metrics can name the responsible set in time."),
    ("Solution", "Capacity-aware, causally consistent, pre-timeout localization with conservative verdicts."),
    ("Novelty", "A new, bounded combination — validated against the closest baselines on shared ground truth."),
]
top = Inches(4.0)
for i, (h, d) in enumerate(takeaways):
    col = i % 2
    row = i // 2
    l = Inches(0.92) + col * Inches(5.95)
    t = top + row * Inches(1.3)
    rect(s, l, t, Inches(5.7), Inches(1.12), fill=RGBColor(0x37, 0x42, 0x5E))
    rect(s, l, t, Pt(3.5), Inches(1.12), fill=TERRA)
    _, tf = box(s, l + Inches(0.32), t + Inches(0.14), Inches(5.2), Inches(0.9))
    para(tf, h, 15, TERRA, bold=True, first=True, font=HEAD_FONT)
    para(tf, d, 12.5, RGBColor(0xE6, 0xE1, 0xDA), space_before=3, line_spacing=1.05)

_, tf = box(s, Inches(0.92), Inches(6.85), Inches(11.5), Inches(0.4))
para(tf, "Status: design proposal — no implementation or measurement yet. "
         "Correctness stated as proof obligations (S1 safety, L1 liveness).",
     11.5, RGBColor(0xB9, 0xC0, 0xCE), first=True, italic=True)

out = "/Users/shashankbhat/Projects/os_project/output/SpanLease_Review1.pptx"
import os
os.makedirs(os.path.dirname(out), exist_ok=True)
prs.save(out)
print("Saved:", out)
print("Slides:", len(prs.slides._sldIdLst))
