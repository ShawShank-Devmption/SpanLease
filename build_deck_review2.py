#!/usr/bin/env python3
"""Generate the SpanLease Project Review 2 deck.

Visual identity, helper vocabulary and page furniture follow build_deck.py
(Review 1) so the two decks read as one series. Every status claim here is
sourced from the tracked documents and from a local verified build; see
REVIEW2_SOURCES at the bottom of this file.
"""

from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.dml import MSO_LINE_DASH_STYLE

# ---- palette (identical to Review 1) ----
TERRA     = RGBColor(0xC0, 0x5A, 0x3C)
TERRA_DK  = RGBColor(0x9A, 0x43, 0x2B)
INK       = RGBColor(0x22, 0x22, 0x22)
INK_SOFT  = RGBColor(0x55, 0x55, 0x55)
CREAM     = RGBColor(0xF7, 0xF3, 0xEE)
CREAM_DK  = RGBColor(0xEC, 0xE4, 0xDA)
WHITE     = RGBColor(0xFF, 0xFF, 0xFF)
NAVY      = RGBColor(0x2C, 0x36, 0x50)
GREEN     = RGBColor(0x3C, 0x6E, 0x47)
GOLD      = RGBColor(0xD4, 0x96, 0x22)
GOLD_BG   = RGBColor(0xFF, 0xF4, 0xDD)
GREY_LINE = RGBColor(0xD8, 0xD0, 0xC6)
GREY_MID  = RGBColor(0x8A, 0x96, 0xA3)
GREY_BG   = RGBColor(0xF6, 0xF6, 0xF7)

HEAD_FONT = "Georgia"
BODY_FONT = "Calibri"
MONO_FONT = "Consolas"

SW, SH = Inches(13.333), Inches(7.5)

prs = Presentation()
prs.slide_width = SW
prs.slide_height = SH
BLANK = prs.slide_layouts[6]


# =====================================================================
# helpers (same vocabulary as build_deck.py)
# =====================================================================
def slide():
    return prs.slides.add_slide(BLANK)


def notes(s, text):
    s.notes_slide.notes_text_frame.text = text


def box(s, l, t, w, h):
    tb = s.shapes.add_textbox(l, t, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    return tb, tf


def rect(s, l, t, w, h, fill=None, line=None, line_w=None, dashed=False,
         shape=MSO_SHAPE.RECTANGLE):
    sp = s.shapes.add_shape(shape, l, t, w, h)
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
        if dashed:
            sp.line.dash_style = MSO_LINE_DASH_STYLE.DASH
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


def runs(tf, parts, size, space_after=6, space_before=0, first=False,
         line_spacing=1.06, align=PP_ALIGN.LEFT):
    """parts = [(text, color, bold, italic, font_or_None), ...]"""
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.alignment = align
    p.space_after = Pt(space_after)
    p.space_before = Pt(space_before)
    p.line_spacing = line_spacing
    for part in parts:
        text, color, bold = part[0], part[1], part[2]
        italic = part[3] if len(part) > 3 else False
        font = part[4] if len(part) > 4 else BODY_FONT
        sz = part[5] if len(part) > 5 else size
        set_run(p.add_run(), text, sz, color, bold, italic, font)
    return p


def bullet(tf, text, size=14, color=INK, bold=False, first=False, space_after=6,
           lead=None, lead_color=TERRA, indent=False, line_spacing=1.05):
    p = tf.paragraphs[0] if first else tf.add_paragraph()
    p.space_after = Pt(space_after)
    p.space_before = Pt(0)
    p.line_spacing = line_spacing
    p.alignment = PP_ALIGN.LEFT
    if indent:
        p.level = 1
    set_run(p.add_run(), ("– " if indent else "▪  "), size, lead_color, bold=True)
    if lead:
        set_run(p.add_run(), lead, size, color, bold=True)
        set_run(p.add_run(), text, size, color, bold=False)
    else:
        set_run(p.add_run(), text, size, color, bold)
    return p


def kicker(s, text, l=Inches(0.9), t=Inches(0.55), color=TERRA):
    _, tf = box(s, l, t, Inches(11.5), Inches(0.4))
    p = para(tf, text.upper(), 12.5, color, bold=True, first=True)
    p.runs[0]._r.get_or_add_rPr().set('spc', '180')
    return p


def title(s, text, t=Inches(0.92), size=31, color=INK, l=Inches(0.9), w=Inches(11.6)):
    _, tf = box(s, l, t, w, Inches(1.0))
    para(tf, text, size, color, bold=True, first=True, font=HEAD_FONT, line_spacing=1.0)


def accent_bar(s, t=Inches(1.72), l=Inches(0.92), w=Inches(0.9)):
    rect(s, l, t, w, Pt(3.2), fill=TERRA)


def footer(s, page, tag="SpanLease · Project Review 2"):
    _, tf = box(s, Inches(0.9), Inches(7.02), Inches(9), Inches(0.35))
    para(tf, tag, 9, INK_SOFT, first=True)
    _, tf2 = box(s, Inches(11.6), Inches(7.02), Inches(1.5), Inches(0.35))
    para(tf2, f"{page:02d}", 9, INK_SOFT, first=True, align=PP_ALIGN.RIGHT)


def content_slide(kick, ttl, page):
    s = slide()
    rect(s, 0, 0, SW, SH, fill=WHITE)
    kicker(s, kick)
    title(s, ttl)
    accent_bar(s)
    footer(s, page)
    return s


def panel(s, l, t, w, h, fill=CREAM, bar=TERRA, bar_w=Pt(3.2)):
    rect(s, l, t, w, h, fill=fill)
    if bar:
        rect(s, l, t, bar_w, h, fill=bar)


def card(s, l, t, w, h, head, body, head_color=TERRA_DK, fill=CREAM, bar=TERRA,
         head_size=15, body_size=12, pad=0.3, head_font=HEAD_FONT):
    panel(s, l, t, w, h, fill=fill, bar=bar)
    _, tf = box(s, l + Inches(pad), t + Inches(0.14), w - Inches(pad + 0.18), h - Inches(0.22))
    para(tf, head, head_size, head_color, bold=True, first=True, font=head_font)
    if body:
        para(tf, body, body_size, INK, space_before=4, line_spacing=1.08)


def statchip(s, l, t, w, h, value, label, fill=CREAM, value_color=TERRA_DK):
    rect(s, l, t, w, h, fill=fill)
    _, tf = box(s, l, t + Inches(0.1), w, h - Inches(0.16))
    para(tf, value, 26, value_color, bold=True, first=True, font=BODY_FONT,
         align=PP_ALIGN.CENTER, line_spacing=1.0)
    para(tf, label, 9.5, INK_SOFT, align=PP_ALIGN.CENTER, space_before=2, line_spacing=1.02)


def table_header(s, l, t, cols, widths, size=10.5, color=TERRA_DK):
    x = l
    for c, w in zip(cols, widths):
        _, tf = box(s, x, t, Inches(w), Inches(0.3))
        p = para(tf, c.upper(), size, color, bold=True, first=True)
        p.runs[0]._r.get_or_add_rPr().set('spc', '80')
        x += Inches(w)
    rect(s, l, t + Inches(0.32), Inches(sum(widths)), Pt(1.1), fill=GREY_LINE)


def table_row(s, l, t, cells, widths, h=0.42, size=11, bolds=None, colors=None,
              rule=True, fonts=None):
    x = l
    for i, (c, w) in enumerate(zip(cells, widths)):
        _, tf = box(s, x, t, Inches(w), Inches(h))
        b = bolds[i] if bolds else False
        col = colors[i] if colors else INK
        fnt = fonts[i] if fonts else BODY_FONT
        para(tf, c, size, col, bold=b, first=True, line_spacing=1.04, font=fnt)
        x += Inches(w)
    if rule:
        rect(s, l, t + Inches(h), Inches(sum(widths)), Pt(0.6), fill=GREY_LINE)


def progress(s, l, t, w, frac, h=0.13, done_color=TERRA, rest=CREAM_DK):
    rect(s, l, t, w, Inches(h), fill=rest)
    if frac > 0:
        rect(s, l, t, Inches(w.inches * frac), Inches(h), fill=done_color)


PAGE = 0


def nextpage():
    global PAGE
    PAGE += 1
    return PAGE


# =====================================================================
# 01 — TITLE
# =====================================================================
nextpage()
s = slide()
rect(s, 0, 0, SW, SH, fill=WHITE)
rect(s, 0, 0, Inches(0.32), SH, fill=TERRA)

_, tf = box(s, Inches(0.95), Inches(0.72), Inches(11.6), Inches(0.4))
p = para(tf, "PROJECT REVIEW 2  ·  IMPLEMENTATION STATUS, ARCHITECTURE AND DETECTION ALGORITHM",
         12.5, TERRA, bold=True, first=True)
p.runs[0]._r.get_or_add_rPr().set('spc', '160')

_, tf = box(s, Inches(0.92), Inches(1.45), Inches(11.6), Inches(1.5))
para(tf, "SpanLease", 58, INK, bold=True, first=True, font=HEAD_FONT)

_, tf = box(s, Inches(0.95), Inches(2.78), Inches(11.3), Inches(1.2))
para(tf, "Pre-deadline localization of persistent RPC / executor circular waits "
         "from OpenTelemetry-compatible runtime events.", 20, INK_SOFT,
     first=True, font=HEAD_FONT, italic=True, line_spacing=1.12)

panel(s, Inches(0.92), Inches(3.72), Inches(11.5), Inches(1.62), fill=CREAM)
_, tf = box(s, Inches(1.25), Inches(3.86), Inches(11.0), Inches(1.42))
para(tf, "WHERE THE PROJECT STANDS", 11, TERRA, bold=True, first=True)
para(tf, "Stage 0 is closed and the v2 evidence contract is frozen for implementation. "
         "The gate, blocking-RPC lifecycle, telemetry sink, OTLP decoder and the "
         "conservative analyzer core are implemented and passing on Java 21. The real "
         "multi-service integration path, the independent truth harness and the proof "
         "obligations remain open and are not claimed.", 14.5, INK, space_before=3,
     line_spacing=1.1)

for i, (v, lab) in enumerate([("33 / 0", "tests passing / failing"),
                              ("2,066", "production Java LOC"),
                              ("G0", "gate cleared"),
                              ("13", "frozen event types")]):
    statchip(s, Inches(0.92) + i * Inches(2.92), Inches(5.55), Inches(2.72), Inches(0.92),
             v, lab)

_, tf = box(s, Inches(0.95), Inches(6.72), Inches(12), Inches(0.6))
p = para(tf, "Prepared by  Shashank", 12.5, INK, bold=True, first=True)
para(tf, "B.Tech CSE (Data Science), VIT — SCOPE      ·      Build verified on Java 21, "
         "2026-09-24      ·      Target venues: ACM Middleware · ICPE · IEEE IC2E",
     11, INK_SOFT, space_before=2)
notes(s, "Review 2 is a status-and-evidence review, not a results review. Two sentences of "
         "framing: Review 1 proposed the idea; since then we froze the observation contract "
         "at gate G0 and built the layers that the contract makes buildable. Everything on "
         "these slides is either running code with a passing test, or explicitly labelled as "
         "pending. I will not claim detection results we have not measured.")

# =====================================================================
# 02 — HOW THIS DECK MAPS TO THE REVIEW CRITERIA
# =====================================================================
s = content_slide("Review 2 · agenda", "How this review maps to the assessment criteria", nextpage())

rows = [
    ("System design and architecture", "1", "Figure 1 architecture, the four trust boundaries, and why the analyzer is isolated from ground truth", "04 – 05"),
    ("Module identification", "1", "Six modules with one accountable owner each, their allowed dependencies and the enforced boundary rules", "06 – 07"),
    ("Module completion", "4", "Per-module delivered vs pending work units, the verified build, the named tests and the two litmus outputs", "08 – 11"),
    ("Presentation and discussion", "4", "Detection algorithm, dataset and sources checked, honest gaps, and the roadmap to G1–G3", "12 – 20"),
]
t0 = Inches(2.12)
for i, (crit, mark, what, where) in enumerate(rows):
    t = t0 + i * Inches(1.12)
    panel(s, Inches(0.95), t, Inches(11.45), Inches(0.98), fill=CREAM if i % 2 == 0 else CREAM_DK)
    _, tf = box(s, Inches(1.3), t + Inches(0.1), Inches(4.3), Inches(0.8))
    para(tf, crit, 16, INK, bold=True, first=True, font=HEAD_FONT)
    para(tf, f"slides {where}", 10.5, TERRA_DK, space_before=2)
    _, tf = box(s, Inches(5.65), t + Inches(0.14), Inches(5.15), Inches(0.75))
    para(tf, what, 12.5, INK, first=True, line_spacing=1.08)
    rect(s, Inches(10.95), t + Inches(0.19), Inches(1.15), Inches(0.6), fill=WHITE)
    _, tf = box(s, Inches(10.95), t + Inches(0.25), Inches(1.15), Inches(0.5))
    para(tf, f"{mark} mark" + ("s" if mark != "1" else ""), 12.5, TERRA_DK, bold=True,
         first=True, align=PP_ALIGN.CENTER)

_, tf = box(s, Inches(0.95), Inches(6.72), Inches(11.5), Inches(0.4))
para(tf, "Ground rule for this deck: implemented means a class in a production source set with a "
         "passing targeted test. Everything else is labelled pending.", 11.5, INK_SOFT,
     first=True, italic=True)
notes(s, "Say the ground rule out loud. It is the single most important sentence for the "
         "discussion marks: the examiner should know that nothing on the status slides is "
         "aspirational. If they push on any box, I can open the test that proves it.")

# =====================================================================
# 03 — PROBLEM AND PREDICATE RECAP
# =====================================================================
s = content_slide("Recap from Review 1", "What we detect, and what counts as proof of it", nextpage())

_, tf = box(s, Inches(0.95), Inches(2.02), Inches(11.4), Inches(0.9))
para(tf, "A synchronous Java/gRPC service holds a bounded pool of application worker slots. "
         "A handler keeps its slot for the whole nested downstream call. If the call graph "
         "closes into a ring while every eligible slot in the ring is held, no participant can "
         "progress — and today that becomes visible only when the deadline fires.",
     14.5, INK, first=True, line_spacing=1.12)

panel(s, Inches(0.95), Inches(3.08), Inches(6.55), Inches(2.1), fill=CREAM, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(3.2), Inches(6.0), Inches(1.9))
para(tf, "THE CLOSED-WAIT PREDICATE", 11, TERRA, bold=True, first=True)
para(tf, "A nonempty set S qualifies only if every execution in S is observed blocked, every "
         "queue entry is observed waiting, each blocked execution's counterpart is inside S, "
         "and every eligible slot of each queued member's pool is held by an execution inside S "
         "— all of it holding uninterrupted for a guaranteed overlap of at least τ, at a "
         "consistent cut, under qualified clocks.", 12.5, INK, space_before=4, line_spacing=1.1)

vs = [("CONFIRMED_DEADLOCK", "Covered closed set satisfies the predicate throughout the reported interval", TERRA_DK),
      ("CONFIRMED_NO_DEADLOCK", "Complete evidence for the declared scope reduces the candidate set to empty at that cut", GREEN),
      ("CANDIDATE_INCONCLUSIVE", "Missing, late or expired evidence, or too-short persistence, blocks confirmation", NAVY),
      ("INSUFFICIENT_OBSERVABILITY", "Unsupported configuration or invalid identity contract makes the predicate unevaluable", GREY_MID)]
for i, (name, desc, col) in enumerate(vs):
    t = Inches(3.08) + i * Inches(0.54)
    rect(s, Inches(7.75), t, Inches(4.65), Inches(0.46), fill=CREAM if i % 2 == 0 else WHITE)
    rect(s, Inches(7.75), t, Pt(3.0), Inches(0.46), fill=col)
    _, tf = box(s, Inches(7.98), t + Inches(0.02), Inches(4.35), Inches(0.44))
    runs(tf, [(name + "   ", col, True, False, MONO_FONT, 9.5),
              (desc, INK_SOFT, False, False, BODY_FONT, 9.5)], 9.5, first=True, line_spacing=1.02)

_, tf = box(s, Inches(7.75), Inches(5.26), Inches(4.65), Inches(0.34))
para(tf, "The two confirmations are incomparable, not a ranking.", 10, INK_SOFT, first=True, italic=True)

panel(s, Inches(0.95), Inches(5.68), Inches(11.45), Inches(1.02), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(5.80), Inches(10.9), Inches(0.86))
runs(tf, [("The conservative direction is the design. ", INK, True),
          ("An unobserved slot is neither free nor held; an expired lease is not progress; a "
           "gap in the sequence is never healed by time passing. Any ambiguity produces an "
           "inconclusive verdict that names the exact missing evidence.", INK, False)],
     13, first=True, line_spacing=1.1)
notes(s, "Thirty seconds on this slide. The examiner needs the predicate in mind before the "
         "architecture figure, because every box in that figure exists to supply one clause of "
         "this predicate: observed blocked, observed waiting, counterpart inside S, every "
         "eligible slot held, and the tau-long uninterrupted overlap.")

# =====================================================================
# 04 — FIGURE 1: SYSTEM ARCHITECTURE
# =====================================================================
s = content_slide("System design and architecture", "Figure 1 — the SpanLease evidence pipeline", nextpage())

IMPL = dict(fill=CREAM, line=TERRA, line_w=Pt(1.25))
FEAS = dict(fill=WHITE, line=NAVY, line_w=Pt(1.1))
PEND = dict(fill=GREY_BG, line=GREY_MID, line_w=Pt(1.0), dashed=True)
CTR = dict(fill=GOLD_BG, line=GOLD, line_w=Pt(1.1))


def fbox(l, t, w, h, num, text, style, size=9.5, num_color=TERRA):
    rect(s, Inches(l), Inches(t), Inches(w), Inches(h), **style)
    _, tf = box(s, Inches(l + 0.08), Inches(t + 0.03), Inches(w - 0.14), Inches(h - 0.06))
    parts = []
    if num:
        parts.append((f"{num}   ", num_color, True, False, BODY_FONT, size + 1))
    parts.append((text, INK, False, False, BODY_FONT, size))
    runs(tf, parts, size, first=True, line_spacing=1.02, space_after=0)


def down_arrow(cx, t, h=0.14, w=0.17):
    rect(s, Inches(cx - w / 2), Inches(t), Inches(w), Inches(h), fill=TERRA,
         shape=MSO_SHAPE.DOWN_ARROW)


def right_arrow(l, t, w=0.24, h=0.17):
    rect(s, Inches(l), Inches(t), Inches(w), Inches(h), fill=TERRA,
         shape=MSO_SHAPE.RIGHT_ARROW)


def group_label(l, t, text):
    _, tf = box(s, Inches(l), Inches(t), Inches(5.4), Inches(0.26))
    p = para(tf, text.upper(), 9, TERRA_DK, bold=True, first=True, space_after=0)
    p.runs[0]._r.get_or_add_rPr().set('spc', '110')


# group frames
rect(s, Inches(0.62), Inches(1.98), Inches(4.55), Inches(3.32), fill=None, line=GREY_LINE, line_w=Pt(1.0))
rect(s, Inches(5.42), Inches(1.98), Inches(1.75), Inches(3.32), fill=None, line=GREY_LINE, line_w=Pt(1.0))
rect(s, Inches(7.42), Inches(1.98), Inches(5.30), Inches(3.32), fill=None, line=GREY_LINE, line_w=Pt(1.0))

group_label(0.74, 2.03, "Monitored service instance  ·  deployed as A, B, C")
group_label(5.54, 2.03, "Telemetry")
group_label(7.54, 2.03, "Analyzer  ·  observations only")

# column 1 — five boxes of 0.42 with 0.12 arrows, ending clear of the panel at 5.30
C1L, C1W, C1C = 0.80, 4.19, 0.80 + 4.19 / 2
c1 = [("", "gRPC server · transport and callback executor", FEAS),
      ("1", "ServerStreamTracer — arrival identity before dispatch", FEAS),
      ("", "Generated unary adapter — one job per request  (pending)", PEND),
      ("2", "SlotTrackingExecutor — bounded queue · k named slots", IMPL),
      ("3", "Blocking-RPC lifecycle — one open downstream block", IMPL)]
y = 2.38
for i, (num, txt, st) in enumerate(c1):
    fbox(C1L, y, C1W, 0.42, num, txt, st)
    if i < len(c1) - 1:
        down_arrow(C1C, y + 0.44, h=0.12)
    y += 0.58

# column 2
C2L, C2W, C2C = 5.56, 1.47, 5.56 + 1.47 / 2
fbox(C2L, 2.38, C2W, 0.78, "4", "OtelEventSink — bounded offer, never blocks the handler", IMPL, size=8.5)
down_arrow(C2C, 3.18, h=0.12)
fbox(C2L, 3.32, C2W, 0.66, "", "OTel SDK LogRecords · unsampled · own thread", IMPL, size=8.5)
down_arrow(C2C, 4.00, h=0.12)
fbox(C2L, 4.14, C2W, 0.72, "", "OTLP/gRPC export → bounded receiver  (pending)", PEND, size=8.5)

# column 3
C3L, C3W, C3C = 7.60, 4.94, 7.60 + 4.94 / 2
fbox(C3L, 2.38, C3W, 0.44, "5", "OtlpEventDecoder — native attributes must match the JSON body", IMPL)
down_arrow(C3C, 2.86, h=0.12)
rect(s, Inches(C3L), Inches(3.02), Inches(C3W), Inches(1.46), fill=CREAM, line=TERRA, line_w=Pt(1.6))
_, tf = box(s, Inches(C3L + 0.1), Inches(3.05), Inches(C3W - 0.2), Inches(0.3))
runs(tf, [("6   ", TERRA, True, False, BODY_FONT, 10.5),
          ("AnalyzerEngine — single-writer core", INK, True, False, BODY_FONT, 9.5)],
     9.5, first=True, space_after=0)
chips = [("Certify", "prefix"), ("Consistent", "cut"), ("Graph +", "unknowns"),
         ("Progress", "reduction"), ("τ / lease", "overlap")]
cw, gap = 0.868, 0.08
for i, (a, b) in enumerate(chips):
    cl = C3L + 0.14 + i * (cw + gap)
    rect(s, Inches(cl), Inches(3.42), Inches(cw), Inches(0.86), fill=WHITE, line=TERRA, line_w=Pt(0.9))
    _, tf = box(s, Inches(cl), Inches(3.50), Inches(cw), Inches(0.74))
    para(tf, f"{i + 1}", 8, TERRA, bold=True, first=True, align=PP_ALIGN.CENTER,
         space_after=1, line_spacing=1.0)
    para(tf, a, 8.5, INK, bold=True, align=PP_ALIGN.CENTER, space_after=0, line_spacing=1.0)
    para(tf, b, 8.5, INK, bold=True, align=PP_ALIGN.CENTER, space_after=0, line_spacing=1.0)
    if i < 4:
        right_arrow(cl + cw + 0.005, 3.78, w=0.068, h=0.11)
down_arrow(C3C, 4.52, h=0.12)
fbox(C3L, 4.68, C3W, 0.52, "7", "VerdictReport v2 — verdict · evidence set · coverage · missing codes", IMPL)

# inter-column arrows
right_arrow(5.18, 3.54)
right_arrow(7.18, 3.54)

# bottom row
fbox(0.62, 5.50, 4.55, 0.66, "",
     "The ring closes across instances: box 3 calls the next service, so A → B → C → A "
     "with k slots each.", PEND, size=9)
fbox(5.42, 5.50, 1.75, 0.66, "", "RunManifest — declared pools and units; configuration only",
     CTR, size=8, num_color=GOLD)
rect(s, Inches(7.42), Inches(5.40), Inches(5.30), Pt(1.1), fill=None, line=NAVY, line_w=Pt(1.1), dashed=True)
fbox(7.42, 5.50, 5.30, 0.66, "8",
     "Evaluation harness — joins verdicts with independent ground truth  (pending).  "
     "Trust boundary: truth never crosses into the analyzer.", PEND, size=9, num_color=NAVY)

# legend — fixed columns, no collisions
for lx, lab, f, ln, dsh in [(0.62, "Implemented and tested", CREAM, TERRA, False),
                            (2.55, "Proven feasible on real gRPC", WHITE, NAVY, False),
                            (4.95, "Pending", GREY_BG, GREY_MID, True),
                            (6.15, "Frozen contract", GOLD_BG, GOLD, False)]:
    rect(s, Inches(lx), Inches(6.27), Inches(0.20), Inches(0.14), fill=f, line=ln,
         line_w=Pt(1.0), dashed=dsh)
    _, tf = box(s, Inches(lx + 0.24), Inches(6.19), Inches(2.3), Inches(0.28))
    para(tf, lab, 8.5, INK_SOFT, first=True, space_after=0)
_, tf = box(s, Inches(9.9), Inches(6.19), Inches(2.85), Inches(0.28))
para(tf, "1–8 = order of evidence flow", 8.5, TERRA_DK, first=True, space_after=0, bold=True)

_, tf = box(s, Inches(0.62), Inches(6.52), Inches(12.1), Inches(0.44))
para(tf, "Figure 1 · SpanLease evidence pipeline. Drawn in the component-and-dataflow "
         "convention used by the systems papers in our own review — DDMon (OOPSLA 2025), "
         "Hindsight (NSDI 2023) and CRISP (ATC 2022): one accent colour, numbered edges read "
         "left to right, sub-systems boxed by owner, and an explicit trust boundary drawn as a "
         "dashed line rather than described in prose.", 10, INK_SOFT, first=True,
     italic=True, line_spacing=1.06)
notes(s, "Walk the numbers, do not read the boxes. 1: we capture arrival before gRPC dispatches "
         "the handler, so a queued request is visible before any application span exists. 2: the "
         "gate is the monitored resource, and it is separate from gRPC's callback executor. 3: "
         "one open downstream block per execution, and this is the edge that closes the ring. "
         "4: telemetry never blocks the handler, a dropped record leaves a visible sequence hole. "
         "5-6-7: the analyzer certifies, cuts, reduces, time-qualifies, then reports. 8 is behind "
         "the dashed line on purpose: ground truth is only ever used by the evaluator, never by "
         "the detector. If asked why the figure looks like this: it follows the same convention "
         "as the three closest systems papers we reviewed.")

# =====================================================================
# 05 — ARCHITECTURE RATIONALE
# =====================================================================
s = content_slide("System design and architecture", "Four trust boundaries, and one enforced rule", nextpage())

items = [
    ("1", "Runtime ownership", "SlotTrackingExecutor is the monitored resource — not gRPC's "
     "callback executor. One lock serializes the state transition, the sequence allocation and "
     "the publication attempt. A job holds its slot through nested calls and cleanup until it "
     "actually exits; cancellation alone never frees a worker."),
    ("2", "RPC causality", "An execution opens at most one downstream block. block.begin and "
     "invocation.sent are committed before transport dispatch; the sent reference travels in "
     "request metadata, and the server's response reference returns in trailers and becomes the "
     "exact parent of block.end. Cross-host order comes only from these references."),
    ("3", "Loss-aware transport", "The sink performs an immediate bounded offer and never waits "
     "for queue space or network. A failed offer keeps its sequence position, so the loss shows "
     "up later as a hole that a checkpoint cannot certify past."),
    ("4", "Conservative analysis", "The analyzer accepts only contiguous checkpointed prefixes, "
     "retracts causally unsupported children, removes everything with an observed path to "
     "progress, and confirms only when leases and uninterrupted time overlap for τ."),
]
t = Inches(2.08)
for num, head, body in items:
    rect(s, Inches(0.95), t, Inches(0.52), Inches(0.96), fill=TERRA)
    _, tf = box(s, Inches(0.95), t + Inches(0.22), Inches(0.52), Inches(0.5))
    para(tf, num, 20, WHITE, bold=True, first=True, align=PP_ALIGN.CENTER,
         font=HEAD_FONT, line_spacing=1.0)
    _, tf = box(s, Inches(1.62), t + Inches(0.02), Inches(10.75), Inches(0.94))
    para(tf, head, 15, INK, bold=True, first=True, font=HEAD_FONT, space_after=2)
    para(tf, body, 12, INK_SOFT, line_spacing=1.08)
    t += Inches(1.06)

panel(s, Inches(0.95), Inches(6.35), Inches(11.45), Inches(0.72), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(6.44), Inches(10.9), Inches(0.6))
runs(tf, [("Enforced boundary: ", NAVY, True),
          ("analyzer must never depend on instrumentation or testbed, and never reads ground "
           "truth. The Gradle build fails on a production dependency that crosses that line — "
           "the rule is checked, not just documented.", INK, False)], 12.5, first=True,
     line_spacing=1.08)
notes(s, "This is the slide that earns the design mark. The point is that each boundary exists "
         "because a specific way of being wrong was identified first. If cancellation released a "
         "slot, we would report a false negative. If we let physical timestamps order events "
         "across hosts, we would invent causal edges. If telemetry could block the handler, the "
         "monitor would cause the very stall it detects.")

# =====================================================================
# 06 — MODULE IDENTIFICATION
# =====================================================================
s = content_slide("Module identification", "Six modules, one accountable owner each", nextpage())

W = [1.55, 1.05, 5.15, 3.7]
L = Inches(0.95)
table_header(s, L, Inches(2.06), ["Module", "Owner", "Responsibility", "Allowed dependencies"], W)
mods = [
    ("common", "B + C", "Immutable event, manifest, verdict and coverage DTOs; identity rules; validation; deterministic JSON", "JDK and Jackson only — no gRPC, no OTel SDK"),
    ("instrumentation", "B", "Application gate, blocking-call lifecycle, stream and metadata hooks, LogRecord encoding and export", "common"),
    ("testbed", "A", "Generic Chain service, driver, deterministic scenarios, independent truth recorder", "common, instrumentation"),
    ("analyzer", "C", "OTLP decode, certification, consistent cut, reduction, temporal qualification, verdict report", "common only — never instrumentation or testbed"),
    ("baselines", "C", "Evaluation-only comparison monitors: metrics, completed traces, live spans, invocation monitor", "never imported by analyzer production code"),
    ("eval", "A / B / C", "Fault schedules, manifest and truth joining, statistics, figures, reproduction package", "scripts workspace, not a Java service"),
]
t = Inches(2.46)
for i, (m, o, r, d) in enumerate(mods):
    if i % 2 == 0:
        rect(s, L, t - Inches(0.04), Inches(sum(W)), Inches(0.78), fill=CREAM)
    table_row(s, L, t, [m, o, r, d], W, h=0.7, size=11,
              bolds=[True, False, False, False],
              colors=[TERRA_DK, INK_SOFT, INK, INK_SOFT],
              fonts=[MONO_FONT, BODY_FONT, BODY_FONT, BODY_FONT], rule=False)
    t += Inches(0.74)

panel(s, Inches(0.95), Inches(6.28), Inches(11.45), Inches(0.78), fill=CREAM_DK, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(6.37), Inches(10.9), Inches(0.66))
runs(tf, [("Why owners and not just packages: ", TERRA_DK, True),
          ("B owns the event half of common and C owns the report half, so the wire contract has "
           "a producer and a consumer that must both sign a change. A contract-tagged pull "
           "request plus a schema_version bump is the only way to alter it.", INK, False)],
     12, first=True, line_spacing=1.08)
notes(s, "Module identification mark. Three things to say: every module has exactly one "
         "accountable owner; the dependency column is enforced by the build, not by convention; "
         "and the analyzer's dependency line is deliberately the narrowest in the project because "
         "that is what makes its verdicts independent of the thing being measured.")

# =====================================================================
# 07 — WORK-UNIT DECOMPOSITION
# =====================================================================
s = content_slide("Module identification", "Each module decomposes into named work units", nextpage())

lanes = [
    ("Dev A — system and truth", TERRA, [
        ("A-BOOT", "done"), ("A-SPEC", "done"), ("A-TESTBED", "todo"), ("A-TRUTH", "todo"),
        ("A-TRIGGER", "todo"), ("A-FAULT", "todo"), ("A-HARNESS", "todo")]),
    ("Dev B — instrumentation", NAVY, [
        ("B-FEAS", "done"), ("B-CONTRACT", "done"), ("B-TYPES", "done"), ("B-GATE", "done"),
        ("B-RPC", "part"), ("B-EXPORT", "part"), ("B-SCAN", "todo"), ("B-INTEGRATE", "todo"),
        ("B-INTEROP", "todo")]),
    ("Dev C — analyzer and evidence", GREEN, [
        ("C-MODEL", "done"), ("R-LIT", "done"), ("C-CONTRACT", "done"), ("C-TYPES", "done"),
        ("C-INGEST", "part"), ("C-CUT", "done"), ("C-REDUCE", "done"), ("C-TIME", "done"),
        ("C-REPORT", "done"), ("C-REPLAY", "todo"), ("C-PROOF", "todo")]),
]
FILLS = {"done": (CREAM, TERRA, INK), "part": (GOLD_BG, GOLD, INK), "todo": (GREY_BG, GREY_MID, GREY_MID)}
t = Inches(2.08)
for lane, col, units in lanes:
    _, tf = box(s, Inches(0.95), t, Inches(4.6), Inches(0.3))
    para(tf, lane, 13, col, bold=True, first=True, font=HEAD_FONT, space_after=0)
    x = Inches(0.95)
    y = t + Inches(0.36)
    for name, st in units:
        f, ln, tc = FILLS[st]
        w = Inches(0.94)
        rect(s, x, y, w, Inches(0.42), fill=f, line=ln, line_w=Pt(1.0),
             dashed=(st == "todo"))
        _, tf = box(s, x - Inches(0.05), y + Inches(0.08), w + Inches(0.1), Inches(0.32))
        para(tf, name, 8, tc, bold=True, first=True, align=PP_ALIGN.CENTER,
             font=MONO_FONT, space_after=0, line_spacing=1.0)
        x += Inches(1.0)
    t += Inches(1.16)

leg2 = [("delivered", CREAM, TERRA, False), ("partly delivered", GOLD_BG, GOLD, False),
        ("not started", GREY_BG, GREY_MID, True)]
lx = 0.95
for lab, f, ln, dsh in leg2:
    rect(s, Inches(lx), Inches(5.62), Inches(0.22), Inches(0.16), fill=f, line=ln, line_w=Pt(1.0), dashed=dsh)
    _, tf = box(s, Inches(lx + 0.27), Inches(5.55), Inches(2.2), Inches(0.3))
    para(tf, lab, 10, INK_SOFT, first=True, space_after=0)
    lx += 2.0

panel(s, Inches(0.95), Inches(5.90), Inches(11.45), Inches(1.08), fill=CREAM, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(5.98), Inches(10.9), Inches(0.98))
para(tf, "CRITICAL PATH", 10.5, TERRA, bold=True, first=True, space_after=3)
runs(tf, [("A-BOOT → B-FEAS → B-CONTRACT → C-CONTRACT → G0 → B-TYPES → B-GATE → "
           "B-RPC / B-SCAN / B-EXPORT → B-INTEGRATE → ", INK, False, False, MONO_FONT, 9.5),
          ("G1", TERRA_DK, True, False, MONO_FONT, 9.5),
          (" → I-LITMUS → I-FAULT → G2 → E-RUN → E-STATS → G3", INK, False, False, MONO_FONT, 9.5)],
     9.5, line_spacing=1.06, space_after=3)
para(tf, "We are on the segment between G0 and G1. The analyzer lane ran ahead by design: it is "
         "built against synthetic evidence, not against B's exporter.", 11, INK_SOFT,
     space_before=2, line_spacing=1.05)
notes(s, "This slide answers 'how do you know what is left'. The work units are not invented for "
         "the review; they are the same IDs used in tasks.md with finish-to-start prerequisites. "
         "Point out that C's lane is furthest along by design, because the analyzer is specified "
         "against the contract rather than against B's running code.")

# =====================================================================
# 08 — MODULE COMPLETION: MATRIX
# =====================================================================
s = content_slide("Module completion", "What is built, module by module", nextpage())

W2 = [1.5, 0.95, 4.35, 2.3, 2.35]
table_header(s, L, Inches(2.06), ["Module", "Classes", "Delivered", "Stage-1 units", "Still open"], W2)
comp = [
    ("common", "9", "Event and payload DTOs for all 13 types, manifest, verdict report, identity "
     "validation, deterministic JSON", 1.0, "2 / 2", "—"),
    ("instrumentation", "3", "SlotTrackingExecutor gate, blocking-block handle with response and "
     "local completion, bounded OTel sink", 0.45, "2.5 / 6", "scanner, checkpoints, reusable "
     "gRPC adapter, interop"),
    ("analyzer", "3", "OTLP decoder, single-writer engine: certify → cut → graph → reduce "
     "→ τ/lease → report, production demo", 0.6, "5 / 8", "receiver service, replay "
     "validator, written proof"),
    ("testbed", "0", "Real loopback gRPC feasibility gate proving the required hook lifetimes "
     "(test source only)", 0.1, "0 / 5", "chain deployment, driver, independent truth, faults, "
     "harness"),
    ("baselines", "0", "Not started — Stage 3 work, gated on G2", 0.0, "0 / 7", "all comparison monitors"),
    ("eval", "0", "Not started — Stage 4 work, gated on G2", 0.0, "0 / 3", "runs, statistics, reproduction"),
]
t = Inches(2.48)
for i, (m, cls, delivered, frac, units, open_) in enumerate(comp):
    if i % 2 == 0:
        rect(s, L, t - Inches(0.05), Inches(sum(W2)), Inches(0.72), fill=CREAM)
    table_row(s, L, t, [m, cls, delivered], W2[:3], h=0.62, size=10.5,
              bolds=[True, False, False], colors=[TERRA_DK, INK_SOFT, INK],
              fonts=[MONO_FONT, BODY_FONT, BODY_FONT], rule=False)
    bx = L + Inches(sum(W2[:3]))
    _, tf = box(s, bx, t - Inches(0.02), Inches(W2[3]), Inches(0.3))
    para(tf, units + "  units", 10.5, INK, bold=True, first=True, space_after=0)
    progress(s, bx, t + Inches(0.26), Inches(W2[3] - 0.25), frac)
    table_row(s, bx + Inches(W2[3]), t, [open_], [W2[4]], h=0.62, size=10,
              colors=[GREY_MID], rule=False)
    t += Inches(0.68)

panel(s, Inches(0.95), Inches(6.38), Inches(11.45), Inches(0.68), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(6.46), Inches(10.9), Inches(0.56))
runs(tf, [("Read the bars as task units, not effort. ", NAVY, True),
          ("A unit is counted delivered only when its production class exists and its targeted "
           "test passes. The detector is not end-to-end yet: nothing here claims G1.", INK, False)],
     12, first=True, line_spacing=1.06)
notes(s, "Heaviest-weighted criterion, so be precise and be honest. The three implemented modules "
         "are real: 2,066 lines of production Java, not stubs. The two empty modules are empty on "
         "purpose — they are gated behind G2 in the plan, and starting them early would mean "
         "building baselines against an unproven detector.")

# =====================================================================
# 09 — MODULE COMPLETION: VERIFIED BUILD
# =====================================================================
s = content_slide("Module completion", "Verification evidence, not assertion", nextpage())

for i, (v, lab, col) in enumerate([("BUILD\nSUCCESSFUL", "./gradlew build on Java 21", GREEN),
                                   ("33", "tests executed", TERRA_DK),
                                   ("0", "failures", GREEN),
                                   ("100", "seeded property checks", NAVY)]):
    l = Inches(0.95) + i * Inches(2.9)
    rect(s, l, Inches(2.06), Inches(2.7), Inches(1.05), fill=CREAM)
    _, tf = box(s, l, Inches(2.16), Inches(2.7), Inches(0.9))
    para(tf, v, 18 if i == 0 else 29, col, bold=True, first=True, font=BODY_FONT,
         align=PP_ALIGN.CENTER, line_spacing=0.95, space_after=1)
    para(tf, lab, 10, INK_SOFT, align=PP_ALIGN.CENTER, line_spacing=1.04)

W3 = [2.5, 0.95, 8.0]
table_header(s, L, Inches(3.36), ["Module", "Tests", "What the targeted tests actually prove"], W3)
ev = [
    ("instrumentation", "6", "acquire precedes run and exit precedes release, including queued and "
     "exceptional jobs · overflow rejects without acquiring and a failed offer leaves a "
     "checkpoint gap · cancellation does not release before actual task exit · block and "
     "send precede transport, response parent precedes a single block.end"),
    ("analyzer", "19", "certified closed ring yields the exact evidence set · certified spare "
     "capacity reduces to no-deadlock · a missing interior sequence cannot be repaired by a "
     "checkpoint · expired lease and incomplete prefix each prevent both confirmations · a "
     "newer upstream dependent does not reset a persistent cycle"),
    ("testbed", "4", "real loopback grpc-java call establishes the required gate and RPC lifetimes "
     "· frozen unary contract keeps its field numbers · 100 seeded property checks on the "
     "finite chain"),
    ("common", "4", "v2 event round-trips with stable type and long sequence · report sorts its "
     "evidence and round-trips · Jackson preserves long identifiers with no transport dependency"),
]
t = Inches(3.76)
for i, (m, n, what) in enumerate(ev):
    if i % 2 == 0:
        rect(s, L, t - Inches(0.05), Inches(sum(W3)), Inches(0.76), fill=CREAM)
    table_row(s, L, t, [m, n, what], W3, h=0.68, size=10.5,
              bolds=[True, True, False], colors=[TERRA_DK, INK, INK_SOFT],
              fonts=[MONO_FONT, BODY_FONT, BODY_FONT], rule=False)
    t += Inches(0.72)

_, tf = box(s, Inches(0.95), Inches(6.62), Inches(11.5), Inches(0.4))
runs(tf, [("Race matrix R1–R16: ", TERRA_DK, True, False, BODY_FONT, 11.5),
          ("6 rows have targeted evidence today (R1, R2, R3, R4, R12, R16 in part); 10 rows remain "
           "and are a G1 obligation. Tests use latches and bounded awaits, never sleeps.",
           INK_SOFT, False, False, BODY_FONT, 11.5)], 11.5, first=True, line_spacing=1.05)
notes(s, "Do not let this become a wall of test names. Pick two and explain why they matter: "
         "'cancellation does not release before actual task exit' is the one that stops a false "
         "negative, and 'a missing interior sequence cannot be repaired by a checkpoint' is the "
         "one that stops a false positive. Then be first to say that 10 of the 16 race rows are "
         "still uncovered — saying it before being asked is worth more than the coverage itself.")

# =====================================================================
# 10 — MODULE COMPLETION: LITMUS OUTPUT
# =====================================================================
s = content_slide("Module completion", "The two litmus outputs produced today", nextpage())

_, tf = box(s, Inches(0.95), Inches(2.02), Inches(11.4), Inches(0.5))
para(tf, "Run with ./gradlew :analyzer:productionDemo. Both reports are real output from the "
         "production analyzer against schema-v2 observations — same engine, same code path, "
         "only the observed capacity differs.", 13, INK, first=True, line_spacing=1.08)

panel(s, Inches(0.95), Inches(2.62), Inches(5.6), Inches(3.0), fill=CREAM, bar=TERRA)
_, tf = box(s, Inches(1.28), Inches(2.72), Inches(5.05), Inches(3.3))
para(tf, "CASE 1 · CERTIFIED CLOSED RING, k = 1", 10.5, TERRA, bold=True, first=True)
para(tf, "CONFIRMED_DEADLOCK", 19, TERRA_DK, bold=True, font=HEAD_FONT, space_before=4, space_after=5)
for txt in ['executions  a0 → b0 → c0',
            'queue entry  inv-ca waiting on A:main',
            'units  A0, B0, C0 all owned, all leased',
            'edges  3 wait · 1 queue-wait · 3 own',
            'persistence  first 320 · last 2990 ms',
            'narrowed span  2670 ms  ≥  τ = 2000 ms',
            'coverage  3 / 3 units observed, no gaps']:
    lead, rest = txt.split("  ", 1)
    runs(tf, [(lead.ljust(15), TERRA_DK, True, False, MONO_FONT, 10),
              (rest, INK, False, False, MONO_FONT, 10)], 10, space_after=4, line_spacing=1.05)

panel(s, Inches(6.8), Inches(2.62), Inches(5.6), Inches(3.0), fill=CREAM, bar=GREEN)
_, tf = box(s, Inches(7.13), Inches(2.72), Inches(5.05), Inches(3.3))
para(tf, "CASE 2 · SAME CYCLIC PATTERN, ONE SPARE SLOT", 10.5, GREEN, bold=True, first=True)
para(tf, "CONFIRMED_NO_DEADLOCK", 19, GREEN, bold=True, font=HEAD_FONT, space_before=4, space_after=5)
for txt in ['change  A gains unit A1 — nothing else',
            'reduction  queued inv-ca removed first',
            '…then  its caller, then backward to empty',
            'core  empty: no executions, no edges',
            'persistence  null',
            'coverage  4 / 4 units observed, no gaps',
            'scope  this cut only, not the whole run']:
    lead, rest = txt.split("  ", 1)
    runs(tf, [(lead.ljust(15), GREEN, True, False, MONO_FONT, 10),
              (rest, INK, False, False, MONO_FONT, 10)], 10, space_after=4, line_spacing=1.05)

panel(s, Inches(0.95), Inches(5.82), Inches(11.45), Inches(0.72), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(5.91), Inches(10.9), Inches(0.6))
runs(tf, [("These are the two litmus tests the project must always pass. ", NAVY, True),
          ("A cyclic call pattern is not a deadlock: case 2 has the same ring and still confirms "
           "safety, because a spare slot is an observed path to progress.", INK, False)],
     12, first=True, line_spacing=1.08)
notes(s, "This is the demo slide. If there is a laptop, run the command live — it takes seconds "
         "and prints both JSON reports. The insight to land: the difference between the two runs is "
         "one capacity unit, and the analyzer reaches opposite verdicts from the same code path. "
         "That is what capacity-aware means, and it is why a pure RPC wait-for graph would call "
         "both of these a cycle.")

# =====================================================================
# 11 — THE EVIDENCE CONTRACT
# =====================================================================
s = content_slide("Module completion · common", "The frozen v2 evidence contract", nextpage())

_, tf = box(s, Inches(0.95), Inches(2.02), Inches(11.4), Inches(0.44))
para(tf, "Every record carries schema_version, instance epoch, a positive local sequence, an exact "
         "causal parent or nothing, a reference-qualified wall time and a process-local monotonic "
         "time. Frozen at G0 on 2026-09-22; a wire-visible change needs a contract pull request and "
         "a version bump.", 12.5, INK, first=True, line_spacing=1.08)

groups = [
    ("Capacity and completeness", TERRA, [
        ("resource.init", "capacity and unit IDs before any admission — stops unseen capacity being read as absence"),
        ("instance.checkpoint", "certifies a contiguous received prefix, emitted even when idle — makes a lost suffix visible"),
        ("resource.wait.begin", "ready job queued, atomically with the event"),
        ("resource.acquire", "named slot assigned before the job can run"),
        ("resource.release", "slot actually returned, strictly after execution.end")]),
    ("Invocation and blocking", NAVY, [
        ("rpc.invocation.sent", "committed before the real transport send"),
        ("rpc.invocation.arrived", "receipt before admission; parent is the exact sent reference"),
        ("rpc.block.begin", "logical entry to the blocking helper"),
        ("rpc.response.sent", "committed at response close — supplies cross-host order"),
        ("rpc.block.end", "outcome plus completion kind: response or local, never guessed")]),
    ("Liveness and termination", GREEN, [
        ("execution.lease", "exact current block reference from an atomic snapshot, with expiry"),
        ("execution.cancel", "cancellation observed — explicitly not a release"),
        ("execution.end", "actual job exit, including exceptional paths")]),
]
x = Inches(0.95)
for gname, col, items in groups:
    rect(s, x, Inches(2.62), Inches(3.72), Inches(3.12), fill=CREAM)
    rect(s, x, Inches(2.62), Inches(3.72), Pt(3.0), fill=col)
    _, tf = box(s, x + Inches(0.22), Inches(2.72), Inches(3.35), Inches(3.45))
    para(tf, gname.upper(), 10, col, bold=True, first=True, space_after=5)
    for n, d in items:
        runs(tf, [(n, INK, True, False, MONO_FONT, 9.5)], 9.5, space_after=1, line_spacing=1.0)
        runs(tf, [(d, INK_SOFT, False, False, BODY_FONT, 9)], 9, space_after=6, line_spacing=1.03)
    x += Inches(3.87)

panel(s, Inches(0.95), Inches(5.94), Inches(11.45), Inches(0.72), fill=CREAM_DK, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(6.02), Inches(10.9), Inches(0.62))
runs(tf, [("Three of the thirteen are the ones Review 1 did not have: ", TERRA_DK, True),
          ("resource.init, instance.checkpoint and rpc.response.sent. Each one closes a "
           "counterexample where the old contract could mistake absent evidence for progress.",
           INK, False)], 12, first=True, line_spacing=1.06)
notes(s, "Do not read thirteen names. Say: the contract is grouped into capacity, invocation and "
         "liveness, and the three records added since Review 1 each kill a specific "
         "counterexample — unseen capacity read as absence, a silently lost suffix, and "
         "invented cross-host response order. That is the concrete progress between the reviews.")

# =====================================================================
# 12 — ALGORITHM: PIPELINE
# =====================================================================
s = content_slide("Detection algorithm", "Seven stages from record to verdict", nextpage())

stages = [
    ("1", "Ingest and validate", "Decode OTLP, check schema v2, reject unsupported versions, "
     "de-duplicate by instance and sequence. Identical duplicates are idempotent; a conflicting "
     "duplicate quarantines and invalidates the affected coverage."),
    ("2", "Certify the prefix", "A checkpoint certifies only a contiguous received prefix "
     "starting at 1, including every init record and itself. A checkpoint beyond a missing "
     "sequence does not skip or repair it, and elapsed time never fills a gap."),
    ("3", "Build the consistent cut", "Start from each instance's latest certified prefix; "
     "retract any prefix holding a causal child without its parent and iterate to a fixed point. "
     "A send without its receive is legal; a receive without its parent is not."),
    ("4", "Reconstruct the graph", "Replay local transitions through the cut. Wait edges join "
     "invocations to the actual counterpart, queue edges name the eligible pool, ownership edges "
     "name each unit and owner. Unresolved arrivals and unseen slots stay UNKNOWN."),
    ("5", "Reduce by observed progress", "Worklist removal: drop a queued entry if an eligible "
     "unit is observed free or its owner is known non-blocked; drop a blocked execution if its "
     "counterpart is known non-blocked or already removed. Repeat to a fixed point."),
    ("6", "Qualify in time", "For each surviving assertion take the guaranteed interval "
     "[a + ε, b − ε], intersect them, and require the overlap to be at least τ with a "
     "matching unexpired lease at the chosen endpoint."),
    ("7", "Emit the verdict", "One of four verdicts with the exact evidence set, the query scope "
     "and cut, the certified horizon, the coverage counts and every missing-evidence code. "
     "Deterministic and replayable for identical inputs."),
]
t = Inches(2.02)
for i, (num, head, body) in enumerate(stages):
    h = Inches(0.66)
    if i % 2 == 0:
        rect(s, Inches(0.95), t, Inches(11.45), h, fill=CREAM)
    rect(s, Inches(0.95), t, Inches(0.46), h, fill=TERRA if i % 2 == 0 else CREAM_DK)
    _, tf = box(s, Inches(0.95), t + Inches(0.17), Inches(0.46), Inches(0.42))
    para(tf, num, 15, WHITE if i % 2 == 0 else TERRA_DK, bold=True, first=True,
         align=PP_ALIGN.CENTER, font=HEAD_FONT, line_spacing=1.0)
    _, tf = box(s, Inches(1.55), t + Inches(0.05), Inches(2.5), Inches(0.6))
    para(tf, head, 12.5, INK, bold=True, first=True, font=HEAD_FONT, line_spacing=1.04)
    _, tf = box(s, Inches(4.1), t + Inches(0.03), Inches(8.15), Inches(0.64))
    para(tf, body, 10.5, INK_SOFT, first=True, line_spacing=1.06)
    t += Inches(0.68)
notes(s, "Stages 2 and 3 are the ones that distinguish this from a naive wait-for graph, and "
         "stage 6 is the one that distinguishes a deadlock from a slow call. If the examiner only "
         "remembers one thing: we never evaluate a snapshot, we evaluate a certified prefix at a "
         "causally consistent cut, and then we require the same dependencies to hold "
         "uninterrupted for tau.")

# =====================================================================
# 13 — ALGORITHM: REDUCTION
# =====================================================================
s = content_slide("Detection algorithm", "Reduction and verdict selection", nextpage())

rect(s, Inches(0.95), Inches(2.06), Inches(6.85), Inches(4.25), fill=CREAM)
_, tf = box(s, Inches(1.2), Inches(2.16), Inches(6.4), Inches(4.1))
para(tf, "CORE LOOP  ·  design §5.4", 10, TERRA, bold=True, first=True, space_after=6)
code = [
    ("if deployment / identity contract invalid:", INK_SOFT),
    ("    return INSUFFICIENT_OBSERVABILITY", TERRA_DK),
    ("if scope, prefix, clock or freshness incomplete:", INK_SOFT),
    ("    return CANDIDATE_INCONCLUSIVE", NAVY),
    ("if any candidate dependency is UNKNOWN:", INK_SOFT),
    ("    return CANDIDATE_INCONCLUSIVE", NAVY),
    ("S = all observed blocked executions", INK),
    ("      + all waiting queue entries at the cut", INK),
    ("repeat:", INK),
    ("    drop queued q  if an eligible unit is FREE", INK),
    ("                   or its owner is non-blocked", INK),
    ("                   or its owner was dropped", INK),
    ("    drop blocked e if its counterpart completed", INK),
    ("                   or was already dropped", INK),
    ("until fixed point", INK),
    ("", INK),
    ("if S is empty:  return CONFIRMED_NO_DEADLOCK", GREEN),
    ("P = members of S with certified uninterrupted", INK),
    ("      coverage of [b - tau, b] at endpoint b", INK),
    ("prune P by the same closure rules", INK),
    ("if P nonempty and all leases eligible at b:", INK),
    ("    return CONFIRMED_DEADLOCK with evidence P", TERRA_DK),
    ("else: return CANDIDATE_INCONCLUSIVE", NAVY),
]
for line, col in code:
    runs(tf, [(line if line else " ", col, col in (TERRA_DK, GREEN, NAVY), False, MONO_FONT, 9)],
         9, space_after=0, line_spacing=1.08)

facts = [
    ("The second pass can never say safe", "Temporal pruning searches for a qualified closed "
     "subset. It is forbidden from returning CONFIRMED_NO_DEADLOCK — otherwise a newly "
     "arrived upstream waiter would reset an old persistent cycle's interval."),
    ("Termination and order independence", "Removal is monotone over a finite candidate set, so "
     "the worklist reaches a fixed point independent of processing order. Proving this formally "
     "is task C-PROOF and is still open."),
    ("Evidence set, not root cause", "We report every qualifying closed member at the chosen "
     "endpoint. We do not claim a minimum or irreducible core, and a positive verdict is "
     "historical evidence — not authorization to cancel anything."),
]
t = Inches(2.06)
for head, body in facts:
    rect(s, Inches(8.1), t, Inches(4.3), Inches(1.36), fill=WHITE, line=GREY_LINE, line_w=Pt(1.0))
    _, tf = box(s, Inches(8.32), t + Inches(0.12), Inches(3.9), Inches(1.2))
    para(tf, head, 12.5, TERRA_DK, bold=True, first=True, font=HEAD_FONT, space_after=3)
    para(tf, body, 10.5, INK_SOFT, line_spacing=1.07)
    t += Inches(1.46)
notes(s, "Two sentences carry this slide. First: reduction removes anything with an observed path "
         "to progress, so what survives has no such path — and if nothing survives we can "
         "confirm safety for that cut. Second: the temporal pass can only ever narrow towards a "
         "positive, never flip to a negative, which is the guard against a fresh waiter resetting "
         "an old cycle. We have a test named exactly that.")

# =====================================================================
# 14 — ALGORITHM: TIME
# =====================================================================
s = content_slide("Detection algorithm", "Persistence needs intervals, not sampling", nextpage())

_, tf = box(s, Inches(0.95), Inches(2.02), Inches(11.4), Inches(0.46))
para(tf, "Seeing the same cycle in two consecutive evaluations proves nothing: the membership can "
         "be identical while the underlying invocation, owner or eligibility changed in between. "
         "Persistence must come from uninterrupted history, not repeated ticks.", 13, INK,
     first=True, line_spacing=1.08)

cards2 = [
    ("Guaranteed intervals", "For a transition observed at wall time a and a later certified "
     "endpoint b, the guaranteed valid interval is [a + ε, b − ε]. Every intervening "
     "sequence must be present. Intervals shorter than zero are empty."),
    ("Overlap, then τ", "Intersect by max of the lower bounds and min of the upper bounds. The "
     "result must be at least τ = 2000 ms by default, and τ must itself be at least four "
     "evaluation intervals."),
    ("What closes an interval", "A change of invocation, owner, eligibility or a cancellation "
     "closes the corresponding interval — even when node membership is unchanged. Terminal "
     "events use half-open boundaries."),
    ("Leases prove now, not later", "A lease carries the exact current block reference from an "
     "atomic snapshot. It proves blocked state at emission only; an expired lease keeps "
     "ownership as known-but-ineligible and never frees a slot."),
]
for i, (h, b) in enumerate(cards2):
    l = Inches(0.95) + (i % 2) * Inches(5.82)
    t = Inches(2.62) + (i // 2) * Inches(1.5)
    card(s, l, t, Inches(5.6), Inches(1.34), h, b, head_size=14, body_size=11, pad=0.32)

panel(s, Inches(0.95), Inches(5.62), Inches(11.45), Inches(1.4), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(5.70), Inches(10.9), Inches(1.3))
para(tf, "DETECTION LATENCY IS A BUDGET, NOT A GUARANTEE", 10.5, NAVY, bold=True, first=True, space_after=4)
runs(tf, [("max(tau + 2*eps, age_threshold + scanner_tick + J_s) + renewal + scanner_tick "
           "+ J_s + checkpoint_interval + J_c + delta + eval_interval + J_e + P",
           INK, True, False, MONO_FONT, 9.5)], 9.5, space_after=4, line_spacing=1.08)
para(tf, "The J terms are measured scheduling delays and P is analyzer processing lag — experiment "
         "assumptions, not defaults we are entitled to. The derivation is reviewed at G1; until "
         "then it is a hypothesis. Under overload there is no finite bound, and the proposal's old "
         "3.1 s / 27 s figures are withdrawn.", 10.5, INK_SOFT, line_spacing=1.06)
notes(s, "The honest answer to 'how fast is it' is on this slide, and giving it honestly is worth "
         "more than a number. We withdrew the proposal's 3.1-second claim because it assumed "
         "scheduling delays we never measured. What we have is a budget expression whose terms are "
         "all things we can measure at G2.")

# =====================================================================
# 15 — CONSERVATISM
# =====================================================================
s = content_slide("Detection algorithm", "When the analyzer refuses to answer", nextpage())

_, tf = box(s, Inches(0.95), Inches(2.02), Inches(11.4), Inches(0.42))
para(tf, "Every inconclusive report names its exact cause from a fixed vocabulary, so an operator "
         "can tell a monitoring defect from a healthy system. These sixteen codes are part of the "
         "frozen report schema.", 13, INK, first=True, line_spacing=1.08)

codegroups = [
    ("Deployment is unsupported", TERRA, ["UNSUPPORTED_SCOPE", "UNINSTRUMENTED_RESOURCE",
                                          "INVALID_IDENTITY", "INVALID_EVENT"]),
    ("Evidence is incomplete", NAVY, ["MISSING_INIT", "SEQUENCE_GAP", "MISSING_CHECKPOINT",
                                      "STALE_CHECKPOINT", "MISSING_CAUSAL_PARENT"]),
    ("A dependency is unknown", GREEN, ["UNKNOWN_UNIT", "UNKNOWN_COUNTERPART", "CANCEL_PENDING",
                                        "EXPIRED_LEASE"]),
    ("Time or resources fall short", GOLD, ["UNQUALIFIED_CLOCK", "PERSISTENCE_TOO_SHORT",
                                            "RESOURCE_LIMIT"]),
]
x = Inches(0.95)
for name, col, codes in codegroups:
    rect(s, x, Inches(2.56), Inches(2.76), Inches(2.5), fill=CREAM)
    rect(s, x, Inches(2.56), Inches(2.76), Pt(3.0), fill=col)
    _, tf = box(s, x + Inches(0.2), Inches(2.66), Inches(2.4), Inches(2.34))
    para(tf, name.upper(), 9.5, col, bold=True, first=True, space_after=7, line_spacing=1.04)
    for c in codes:
        runs(tf, [(c, INK, False, False, MONO_FONT, 9.5)], 9.5, space_after=5, line_spacing=1.02)
    x += Inches(2.9)

examples = [
    ("A cancelled but still-blocked helper", "CANCEL_PENDING until block.end or job exit resolves "
     "it — cancellation is not observed progress"),
    ("One interior record lost forever", "SEQUENCE_GAP blocks certification for that instance for "
     "the rest of the run; only a clean run recovers"),
    ("Checkpoint older than the horizon", "STALE_CHECKPOINT makes the current query inconclusive "
     "without invalidating an older justified report"),
]
t = Inches(5.26)
for h, b in examples:
    rect(s, Inches(0.95), t, Inches(11.45), Inches(0.52), fill=WHITE, line=GREY_LINE, line_w=Pt(0.9))
    _, tf = box(s, Inches(1.2), t + Inches(0.06), Inches(11.0), Inches(0.44))
    runs(tf, [(h + "  →  ", TERRA_DK, True, False, BODY_FONT, 11),
              (b, INK_SOFT, False, False, BODY_FONT, 11)], 11, first=True, line_spacing=1.04)
    t += Inches(0.58)
notes(s, "The likely challenge here is 'isn't inconclusive just a cop-out'. The answer: an "
         "inconclusive verdict that names SEQUENCE_GAP on instance B is an actionable monitoring "
         "bug report, while a confident answer derived from missing data is a silent false result. "
         "We would rather ship the first.")

# =====================================================================
# 16 — DATASET
# =====================================================================
s = content_slide("Dataset", "What the detector actually runs on today", nextpage())

_, tf = box(s, Inches(0.95), Inches(2.02), Inches(11.4), Inches(0.42))
para(tf, "Stating this plainly: no third-party or production trace dataset has been ingested. "
         "Everything below tier 1 is either generated by us or captured from a real gRPC call in "
         "our own test process.", 13, INK, first=True, line_spacing=1.08)

tiers = [
    ("TIER 1", "In use today", TERRA, CREAM, [
        ("Deterministic scenario fixtures",
         "Closed A/B/C ring at k = 1 · spare-capacity negative · interior sequence gap · expired "
         "lease · short persistence · unsupported deployment · disjoint cycles · upstream dependent"),
        ("Real gRPC lifecycle capture",
         "A loopback grpc-java v1.68.1 call in GrpcGateFeasibilityTest: real stream tracer, real "
         "callback executor, real blocking stub, real trailers. Not a mock."),
        ("Golden fixtures and property checks",
         "Round-trip JSON for every event payload and for the verdict report, with sorted arrays; "
         "plus 100 seeded property tries over the finite chain contract"),
        ("Frozen v1 YAML replay",
         "three_cycle_deadlock.yaml is kept deliberately as an incompatible fixture: production "
         "replay must reject it, not reinterpret it"),
    ]),
    ("TIER 2", "Specified, not yet collected", NAVY, WHITE, [
        ("Scenario and fault sweeps",
         "k = 1 / 2 / 4, cycle length 2 / 3 / 5, replicas, unrelated load, deadlines, and seeded "
         "delivery faults: interior and tail loss, duplication, reordering, skew, crash, partition"),
        ("Independent ground truth",
         "Own identities and counters recorded by the testbed, never read by the analyzer, joined "
         "only inside the evaluation harness"),
    ]),
    ("TIER 3", "External validity — nothing ingested", GREY_MID, GREY_BG, [
        ("Trace-derived topology",
         "Palette (arXiv:2506.06448) and the production-characteristic graph generator "
         "(arXiv:2412.19083) are the two candidate routes; neither supplies slot-level truth"),
    ]),
]
t = Inches(2.50)
for tag, sub, col, fill, rows2 in tiers:
    h = Inches(0.18 + 0.44 * len(rows2))
    rect(s, Inches(0.95), t, Inches(11.45), h, fill=fill)
    rect(s, Inches(0.95), t, Pt(3.2), h, fill=col)
    _, tf = box(s, Inches(1.25), t + Inches(0.08), Inches(1.95), Inches(0.62))
    para(tf, tag, 12, col, bold=True, first=True, space_after=1)
    para(tf, sub, 8.5, INK_SOFT, line_spacing=1.02)
    yy = t + Inches(0.1)
    for h2, b2 in rows2:
        _, tf = box(s, Inches(3.2), yy, Inches(2.75), Inches(0.44))
        para(tf, h2, 10.5, INK, bold=True, first=True, line_spacing=1.03)
        _, tf = box(s, Inches(6.0), yy, Inches(6.28), Inches(0.44))
        para(tf, b2, 9.5, INK_SOFT, first=True, line_spacing=1.03)
        yy += Inches(0.44)
    t += h + Inches(0.13)
notes(s, "Expect the question 'where is your dataset'. Answer in one breath: the detector's input "
         "is a telemetry stream, not a corpus, so tier 1 is a generated event stream plus one real "
         "gRPC capture; tier 2 is the sweep we will produce ourselves because only we can record "
         "the slot-level truth; tier 3 is external validity, and we have identified two published "
         "routes but ingested neither. Volunteering that last sentence is the point.")

# =====================================================================
# 17 — SOURCES: CLOSEST WORK
# =====================================================================
s = content_slide("Sources checked", "Closest work, and what it forces us to defend", nextpage())

W4 = [3.35, 3.5, 4.6]
table_header(s, L, Inches(2.06), ["Work · evidence inspected", "Relevant result", "Implication for SpanLease"], W4)
lit = [
    ("DDMon — Rowicki, Francalanza, Scalas\nOOPSLA 2025, PACMPL 9(OOPSLA2) 291\nreport §9 + appendix B read", 
     "Distributed proxy monitors observe RPC messages and exchange probes; Coq-mechanized transparency results, Erlang/OTP implementation",
     "Closest competitor. RPC-level detection is established. We must defend executor admission, real worker ownership and delayed one-way telemetry. A port cannot inherit their proof."),
    ("Hindsight — Zhang et al.\nNSDI 2023, pp. 321–339\nabstract and record inspected",
     "Always-on local capture with retroactive collection when a symptom triggers",
     "Cheap capture plus triggered retrieval is prior art. Compare trigger-to-evidence delay, bytes and eviction. It is a collection system, not a capacity-closure detector."),
    ("CRISP — Zhang et al.\nUSENIX ATC 2022, pp. 655–672\nabstract and bibliography inspected",
     "Critical-path analysis and online anomaly detection at Uber scale",
     "Request-dependency localization is established. We must explain why a latency critical path does not certify a closed capacity wait, and not conflate ranking with predicate verification."),
    ("CrossTrace — Phan et al. · arXiv:2508.11342\nPalette — Anand et al. · arXiv:2506.06448\nMS graph generator · arXiv:2412.19083\nabstracts only, venues unverified",
     "Span correlation from delay patterns and eBPF; trace-derived representative system generation; production-characteristic call graphs",
     "Inferred correlation cannot silently satisfy our observed-identity assumption. The two generators are our route to a workload beyond a constructed cycle, but neither supplies slot-level truth."),
]
t = Inches(2.48)
for i, (a, b, c) in enumerate(lit):
    if i % 2 == 0:
        rect(s, L, t - Inches(0.05), Inches(sum(W4)), Inches(1.04), fill=CREAM)
    x = L
    for j, (txt, w) in enumerate(zip([a, b, c], W4)):
        _, tf = box(s, x, t, Inches(w - 0.18), Inches(1.0))
        para(tf, txt, 9.5 if j == 0 else 10, INK if j != 1 else INK_SOFT,
             bold=(j == 0), first=True, line_spacing=1.05)
        x += Inches(w)
    t += Inches(1.0)

panel(s, Inches(0.95), Inches(6.5), Inches(11.45), Inches(0.56), fill=CREAM_DK, bar=NAVY)
_, tf = box(s, Inches(1.3), Inches(6.56), Inches(10.9), Inches(0.46))
runs(tf, [("DDMon changed our positioning the most: ", NAVY, True),
          ("its §9 lists observing monitors with delayed notifications and RPC timeouts as open "
           "problems, and its appendix B already models replication — so our old “no "
           "multi-instance capacity” table entry was too categorical and was removed.",
           INK, False)], 11, first=True, line_spacing=1.05)
notes(s, "The strongest thing to say here is what we took away from ourselves. Reading DDMon's "
         "appendix B showed that our comparison table overstated the gap, so we deleted that claim. "
         "A reviewer who sees us weaken our own novelty claim will trust the ones we keep.")

# =====================================================================
# 18 — SOURCES: FOUNDATIONS, IMPLEMENTATION, UNVERIFIED
# =====================================================================
s = content_slide("Sources checked", "Foundations, sources read, and open citations", nextpage())

card(s, Inches(0.95), Inches(2.02), Inches(3.72), Inches(2.62),
     "Foundations — ancestry only",
     "Coffman, Elphick, Shoshani 1971 · Chandy, Misra, Haas 1983 · Knapp 1987 · "
     "Cooper, Marzullo 1991 · Garg, Waldecker 1994.\n"
     "They establish deadlock conditions, distributed detection, AND/OR models, consistent cuts "
     "and global-predicate detection. Cited for those foundations, never as SpanLease novelty. "
     "Editions and pages still to be checked against primary texts.",
     head_size=13, body_size=10.5)

card(s, Inches(4.82), Inches(2.02), Inches(3.72), Inches(2.62),
     "Implementation sources — read",
     "grpc-java v1.68.1 ServerImpl — method lookup and call handling are scheduled on the "
     "executor before ordinary interception.\n"
     "grpc-java v1.68.1 ServerStreamTracer — the factory receives headers before stream "
     "creation; a feasibility candidate, not a slot adapter.\n"
     "OTLP specification — a populated partial-success response must not be retried.",
     head_size=13, body_size=10.5, fill=CREAM, bar=NAVY, head_color=NAVY)

card(s, Inches(8.69), Inches(2.02), Inches(3.71), Inches(2.62),
     "Unverified — labelled as such",
     "Cheriton–Skeen SOSP 1993: the RPC monitoring attribution is unconfirmed, so our baseline "
     "is described only as a generic invocation wait-for monitor.\n"
     "Kaveh–Emmerich ESEC/FSE 2001 · Sethi–Anand 2008 and patent US8060878B2 · X-Ray "
     "in-progress segments, Logfire pending spans, OTel zpages: full texts and exact product "
     "behaviour not yet retrieved.",
     head_size=13, body_size=10.5, fill=GREY_BG, bar=GREY_MID, head_color=GREY_MID)

panel(s, Inches(0.95), Inches(4.74), Inches(11.45), Inches(2.14), fill=CREAM, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(4.82), Inches(10.9), Inches(2.0))
para(tf, "REVIEW METHOD AND ITS LIMITS  ·  review date 2026-09-19", 10.5, TERRA, bold=True,
     first=True, space_after=5)
bullet(tf, "Read the proposal's related work, then retrieved public primary sources: DDMon's arXiv "
           "technical report including §9 and appendix B, the USENIX pages for Hindsight and CRISP, "
           "and arXiv metadata for the adjacent 2024–2026 entries.", size=10.5, space_after=5)
bullet(tf, "arXiv queries: ti:deadlock AND (all:distributed OR all:microservice OR all:RPC), and "
           "all:microservices AND all:tracing. Routing, robotics and ML “deadlock” hits excluded. "
           "Publication dates checked per record, never from API filters.", size=10.5, space_after=5)
bullet(tf, "A focused review, not an exhaustive systematic one and not proof of absence of prior "
           "art. ACM DL, IEEE Xplore, DBLP and citation chasing around DDMon remain to be done. No "
           "“first system” claim is justified yet.", size=10.5, space_after=5)
bullet(tf, "Venue fit, not a prediction: ACM Middleware for a validated mechanism, ICPE for the "
           "accuracy / latency / overhead tradeoff, IC2E for deployment evidence — with a workshop "
           "or short paper the realistic target for preliminary results.", size=10.5, space_after=0)
notes(s, "Two points. One: we separate sources we read from sources we only have metadata for, and "
         "the grey card is the list we have not verified — those claims stay out of the "
         "manuscript until we retrieve the full texts. Two: the search is deliberately recorded "
         "with its exact queries and its limits, because a literature review without its method is "
         "not reproducible.")

# =====================================================================
# 19 — GAPS AND RISKS
# =====================================================================
s = content_slide("Honest status", "What is not done, and what could still go wrong", nextpage())

gaps = [
    ("Not end-to-end yet", "There is no running multi-service deployment. The gate, lifecycle, sink, "
     "decoder and engine all work, but they are connected by tests rather than by a live OTLP path. "
     "The reusable gRPC adapter, the scheduled scanner and the receiver service are the missing links.", TERRA),
    ("No independent ground truth yet", "A-TRUTH and A-HARNESS are not started, so we cannot yet "
     "score a verdict against reality. Until then ‘correct’ means ‘agrees with our own "
     "model oracle’, which is weaker.", TERRA),
    ("Proof obligations open", "S1, S2, reduction termination and order independence, and the "
     "conditional L1 bound are written as obligations, not results. C-PROOF is a G1 prerequisite. "
     "Bounded model checking is not a proof and we do not present it as one.", NAVY),
    ("G0 was a solo role review", "One implementer exercised the A, B and C review perspectives "
     "under decision D13. It is recorded as role-based self-review and is never described as "
     "independent three-person review.", NAVY),
    ("10 of 16 race rows uncovered", "Reorder and duplicate replay, lost acquire, scanner races, "
     "clock skew, phantom cycles, concurrent ingestion, crash horizons, identity aliasing and epoch "
     "reuse all still need their targeted tests.", GREY_MID),
    ("Availability cost unmeasured", "A permanent gap blocks certification for that instance for the "
     "rest of the run. That is deliberately conservative, and we have not yet measured how often it "
     "would cost a real deployment an answer.", GREY_MID),
]
for i, (h, b, col) in enumerate(gaps):
    l = Inches(0.95) + (i % 2) * Inches(5.82)
    t = Inches(2.08) + (i // 2) * Inches(1.5)
    rect(s, l, t, Inches(5.6), Inches(1.34), fill=CREAM if col is TERRA else WHITE,
         line=GREY_LINE if col is not TERRA else None, line_w=Pt(0.9))
    rect(s, l, t, Pt(3.0), Inches(1.34), fill=col)
    _, tf = box(s, l + Inches(0.3), t + Inches(0.1), Inches(5.12), Inches(1.2))
    para(tf, h, 13.5, col if col is not GREY_MID else INK, bold=True, first=True,
         font=BODY_FONT if any(c.isdigit() for c in h) else HEAD_FONT, space_after=3)
    para(tf, b, 10.5, INK_SOFT, line_spacing=1.07)

panel(s, Inches(0.95), Inches(6.62), Inches(11.45), Inches(0.44), fill=WHITE, bar=None)
_, tf = box(s, Inches(0.95), Inches(6.6), Inches(11.5), Inches(0.4))
para(tf, "Every one of these is tracked as a named task with a prerequisite, not as a known unknown.",
     11.5, INK_SOFT, first=True, italic=True)
notes(s, "Put this slide before the roadmap on purpose. Reviewers find the gaps anyway; finding "
         "them first is the difference between a status review and a sales pitch. If asked which "
         "one worries me most: the independent truth harness, because without it every correctness "
         "claim is circular.")

# =====================================================================
# 20 — ROADMAP
# =====================================================================
s = content_slide("Next", "The path from here to G1, G2 and G3", nextpage())

miles = [
    ("G1", "Integration readiness", TERRA, [
        "Reusable stream-tracer and unary service adapter (B-RPC)",
        "Scheduled scanner, leases and checkpoints (B-SCAN)",
        "Bounded OTLP receiver with atomic batch admission (C-INGEST)",
        "Chain deployment, driver and independent truth (A-TESTBED, A-TRUTH)",
        "Replay validator and the written proof (C-REPLAY, C-PROOF)",
        "Remaining 10 race rows with targeted tests",
    ]),
    ("G2", "Real end-to-end correctness", NAVY, [
        "Both litmus tests over real gRPC → OTLP → analyzer (I-LITMUS)",
        "Fault runs: cancellation, lost final record, stale checkpoint, crash, skew (I-FAULT)",
        "Both litmus tests wired into Gradle integrationTest and CI (I-CI)",
        "Baseline and parameter freeze recorded with its commit",
    ]),
    ("G3", "Evaluation and claim audit", GREEN, [
        "Baselines: metrics, completed traces, ordinary and equally enriched live spans, "
        "invocation monitor, documented DDMon adaptation",
        "Sweeps over k, cycle length, replicas, load, deadlines and faults",
        "Statistics with independent-run confidence intervals, both false-confirmation directions",
        "Pinned reproduction package and a claim-to-evidence audit",
    ]),
]
x = Inches(0.95)
for tag, name, col, items in miles:
    rect(s, x, Inches(2.06), Inches(3.72), Inches(3.92), fill=CREAM)
    rect(s, x, Inches(2.06), Inches(3.72), Pt(3.2), fill=col)
    _, tf = box(s, x + Inches(0.26), Inches(2.2), Inches(3.3), Inches(3.7))
    para(tf, tag, 26, col, bold=True, first=True, space_after=0, line_spacing=1.0)
    para(tf, name, 12.5, INK, bold=True, space_after=8, line_spacing=1.04)
    for it in items:
        bullet(tf, it, size=10.5, space_after=6, lead_color=col, line_spacing=1.06)
    x += Inches(3.87)

panel(s, Inches(0.95), Inches(6.20), Inches(11.45), Inches(0.62), fill=CREAM_DK, bar=TERRA)
_, tf = box(s, Inches(1.3), Inches(6.27), Inches(10.9), Inches(0.52))
runs(tf, [("Immediate next step: ", TERRA_DK, True),
          ("close B-RPC and B-SCAN so a real three-instance chain can export to a real receiver. "
           "That single step turns every test-level claim on slide 09 into an end-to-end one.",
           INK, False)], 12, first=True, line_spacing=1.05)
notes(s, "End on the single next step rather than the whole roadmap. If the examiner asks for a "
         "date, give the dependency instead: G1 needs the adapter, the scanner and the receiver, "
         "and the truth recorder in parallel — we deliberately do not promise a week count, "
         "because the plan says to estimate only after the feasibility gate, which we just cleared.")

# =====================================================================
# 21 — CLOSING
# =====================================================================
nextpage()
s = slide()
rect(s, 0, 0, SW, SH, fill=WHITE)
rect(s, 0, 0, Inches(0.32), SH, fill=TERRA)
kicker(s, "Project Review 2 · summary", t=Inches(0.7))
_, tf = box(s, Inches(0.92), Inches(1.15), Inches(11.5), Inches(1.0))
para(tf, "Where SpanLease stands", 34, INK, bold=True, first=True, font=HEAD_FONT)

pts = [
    ("The contract is frozen", "Thirteen v2 record types, a consistent-cut protocol and a four-verdict "
     "report schema were reviewed and frozen at G0 on 2026-09-22. Three of the record types exist "
     "specifically to close counterexamples found after Review 1."),
    ("Three modules are real", "common, instrumentation and analyzer hold 2,066 lines of production "
     "Java behind 33 passing tests, including a real loopback gRPC lifecycle capture and both litmus "
     "verdicts from the production engine."),
    ("The algorithm is conservative by construction", "Certify, cut, reduce, then qualify in time. "
     "Unknown is never free, an expired lease is never progress, and a gap is never healed by elapsed "
     "time — ambiguity produces a named inconclusive result."),
    ("What remains is integration and proof", "The live OTLP path, the independent truth harness and "
     "the written proofs are the G1 gate. No detection latency, accuracy or novelty claim is made "
     "until they exist."),
]
t = Inches(2.3)
for i, (h, b) in enumerate(pts):
    rect(s, Inches(0.92), t, Inches(11.5), Inches(0.96), fill=CREAM if i % 2 == 0 else CREAM_DK)
    _, tf = box(s, Inches(1.3), t + Inches(0.1), Inches(3.5), Inches(0.8))
    para(tf, h, 14.5, TERRA_DK, bold=True, first=True, font=HEAD_FONT, line_spacing=1.04)
    _, tf = box(s, Inches(5.0), t + Inches(0.1), Inches(7.2), Inches(0.8))
    para(tf, b, 11.5, INK, first=True, line_spacing=1.08)
    t += Inches(1.04)

_, tf = box(s, Inches(0.92), Inches(6.46), Inches(11.5), Inches(0.66))
para(tf, "Questions", 20, INK, bold=True, first=True, font=HEAD_FONT, space_after=3)
para(tf, "./gradlew build   ·   ./gradlew :analyzer:productionDemo   ·   "
         "./gradlew :testbed:test --tests GrpcGateFeasibilityTest", 10, INK_SOFT,
     font=MONO_FONT)
notes(s, "Close on the fourth point, not the third: the honest boundary of what is claimed is the "
         "strongest note to end on. Keep the three commands on screen during questions — if "
         "anything on slides 08 to 10 is challenged, run it rather than argue it.")


OUT = "output/SpanLease_Review2.pptx"
prs.save(OUT)
print(f"wrote {OUT} with {len(prs.slides.__iter__.__self__._sldIdLst)} slides")

# ---------------------------------------------------------------------
# REVIEW2_SOURCES — every status claim in this deck and where it comes from
# ---------------------------------------------------------------------
REVIEW2_SOURCES = """
build success, 33 tests, 0 failures   local `./gradlew build` 2026-09-24 + build/test-results
2,066 production LOC / 15 classes     wc -l over */src/main/java
G0 approved 2026-09-22 (solo, D13/14) docs/decisions.md D13, D14; docs/stage0-g0-review.md
13 event types, defaults, invariants  design.md sections 4, 4.1, 7
algorithm stages and pseudocode       design.md sections 5.1-5.7
verdict JSON and missing codes        design.md section 8
predicate and four verdicts           requirements.md section 7
module table and boundary rules       design.md section 2; AGENTS.md
task IDs, lanes, gates, critical path tasks.md sections 2-7
litmus JSON values                    `./gradlew :analyzer:productionDemo` output
test names                            */src/test/**/*.java
literature, queries, unverified list  docs/literature-review.md
dataset tiers                         analyzer/src/test/**, docs/scenario-truth-spec.md,
                                      docs/model-mvp.md, docs/mvp-demo-guide.md
architecture figure convention        DDMon OOPSLA'25, Hindsight NSDI'23, CRISP ATC'22
                                      component-and-dataflow figures (docs/literature-review.md)
"""
