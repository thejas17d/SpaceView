"""Builds SpaceView.html (project root): every Markdown doc in one page, section by section.

Run from the project root after any doc changes:   python docs/build_html.py
Needs markdown-it-py (pip install markdown-it-py). Output is a single self-contained file (no network needed).
"""
import datetime
import html
import re
import subprocess
from pathlib import Path

from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "SpaceView.html"

# (group, file, short title). Order = reading order.
DOCS = [
    ("Status", "HANDOFF.md", "Handoff & current status"),
    ("Status", "docs/PLAN.md", "Plan"),
    ("Status", "docs/TESTING.md", "Device testing checklist"),
    ("Research", "docs/research/measurement-engine.md", "Measurement engine"),
    ("Research", "docs/research/ui-ux.md", "UI / UX"),
    ("Research", "docs/research/business.md", "Business"),
    ("Research", "docs/research/android-performance.md", "Android performance & Play"),
    ("Reviews", "docs/reviews/M0-M1-review.md", "M0–M1 review"),
    ("Reviews", "docs/reviews/M2-review.md", "M2 review"),
    ("Reviews", "docs/reviews/M2-gate.md", "M2 gate"),
    ("Reviews", "docs/reviews/M3-gallery-review.md", "M3 gallery review"),
    ("Reviews", "docs/reviews/M3-scan-review.md", "M3 scan review"),
    ("Reviews", "docs/reviews/M3-gate.md", "M3 gate"),
]

# Milestone strip at the top. UPDATE when a milestone changes state: done | active | next.
MILESTONES = [
    ("M0", "Scaffold + CI", "done"),
    ("M1", "Measurement engine", "done"),
    ("M2", "Measure mode", "done"),
    ("M3", "Scan → Gallery", "done"),
    ("M4", "Place in room", "next"),
    ("M5", "Ads, Hindi, onboarding", "active"),
    ("M6", "Perf, size, release", "next"),
]


def slug(text: str) -> str:
    s = re.sub(r"<[^>]+>", "", text).lower()
    s = re.sub(r"[^\w\s-]", "", s, flags=re.UNICODE)
    return re.sub(r"[\s_]+", "-", s).strip("-")[:60] or "s"


def git(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()
    except Exception:
        return ""


md = MarkdownIt("commonmark", {"html": False}).enable(["table", "strikethrough"])
doc_ids = {Path(f).name: "doc-" + slug(Path(f).stem) for _, f, _ in DOCS}


def render(path: str, doc_id: str):
    text = (ROOT / path).read_text(encoding="utf-8")
    tokens = md.parse(text)
    toc, used = [], set()
    for i, t in enumerate(tokens):
        if t.type == "heading_open":
            title = tokens[i + 1].content
            hid = f"{doc_id}--{slug(title)}"
            n = 2
            while hid in used:
                hid = f"{doc_id}--{slug(title)}-{n}"
                n += 1
            used.add(hid)
            t.attrSet("id", hid)
            if t.tag == "h2":
                toc.append((hid, re.sub(r"[`*_]", "", title)))
            # Each doc's own H1 becomes the section heading; demote it so there is one H1 per page.
            if t.tag == "h1":
                t.tag = "h2"
                t.attrSet("class", "doc-h1")
                tokens[i + 2].tag = "h2"
        if t.type == "inline" and t.children:
            for c in t.children:
                if c.type == "link_open":
                    href = c.attrGet("href") or ""
                    name = href.split("#")[0].split("/")[-1]
                    if name in doc_ids:
                        c.attrSet("href", "#" + doc_ids[name])
                    elif href.startswith("http"):
                        c.attrSet("target", "_blank")
                        c.attrSet("rel", "noopener")
    body = md.renderer.render(tokens, md.options, {})
    body = body.replace("<table>", '<div class="table-wrap"><table>').replace("</table>", "</table></div>")
    return body, toc


def main():
    sections, nav = [], []
    current_group = None
    for group, path, title in DOCS:
        if not (ROOT / path).exists():
            continue
        doc_id = doc_ids[Path(path).name]
        body, toc = render(path, doc_id)
        if group != current_group:
            if current_group is not None:
                nav.append("</ol>")
            nav.append(f'<p class="nav-group">{html.escape(group)}</p><ol class="nav-docs">')
            current_group = group
        sub = "".join(f'<li><a href="#{h}">{html.escape(t)}</a></li>' for h, t in toc)
        nav.append(
            f'<li><details><summary><a href="#{doc_id}">{html.escape(title)}</a></summary>'
            f'<ol class="nav-sub">{sub}</ol></details></li>'
        )
        mtime = datetime.datetime.fromtimestamp((ROOT / path).stat().st_mtime).strftime("%d %b %Y, %H:%M")
        sections.append(
            f'<section class="doc" id="{doc_id}">'
            f'<header class="doc-head"><span class="doc-group">{html.escape(group)}</span>'
            f'<span class="doc-path">{html.escape(path)} · updated {mtime}</span></header>'
            f"{body}</section>"
        )
    nav.append("</ol>")

    commit = git("log", "-1", "--format=%h · %s · %cd", "--date=format:%d %b %Y")
    strip = "".join(
        f'<li class="ms ms-{state}"><span class="ms-id">{m}</span><span class="ms-name">{html.escape(n)}</span>'
        f'<span class="ms-state">{ {"done": "Done", "active": "Partly done", "next": "Next"}[state] }</span></li>'
        for m, n, state in MILESTONES
    )
    generated = datetime.datetime.now().strftime("%d %b %Y, %H:%M")
    page = TEMPLATE.format(
        nav="".join(nav), sections="".join(sections), strip=strip,
        commit=html.escape(commit), generated=generated, count=len(sections),
    )
    OUT.write_text(page, encoding="utf-8")
    print(f"Wrote {OUT} ({OUT.stat().st_size // 1024} KB, {len(sections)} docs)")


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SpaceView Build Book</title>
<meta name="description" content="Every SpaceView document in one page: status, plan, testing, research and milestone reviews.">
<style>
/* Tokens from app/src/main/kotlin/com/spaceview/app/ui/theme/Color.kt: Paper / Graphite, Signal yellow. */
:root {{
  --bg: #F6F5F1; --panel: #FFFFFF; --sunk: #F0EEE9; --line: #E3E1DA; --line-strong: #D8D5CC;
  --ink: #16181B; --ink-2: #4A4F55; --ink-3: #6B7178;
  --signal: #FFD400; --signal-ink: #7A6000; --link: #2F5D8A;
  --good: #3F7A57; --fair: #8A6A1F; --rough: #A3483D;
  --mono: ui-monospace, "Cascadia Mono", Consolas, "SF Mono", Menlo, monospace;
  --sans: system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", sans-serif;
  color-scheme: light;
}}
@media (prefers-color-scheme: dark) {{
  :root:not([data-theme="light"]) {{
    --bg: #16181B; --panel: #1E2124; --sunk: #23262A; --line: #30343A; --line-strong: #3A3F45;
    --ink: #F0EEE9; --ink-2: #A9AEB4; --ink-3: #8A8F95;
    --signal: #FFD400; --signal-ink: #FFD400; --link: #8DB7E0;
    --good: #8CC3A0; --fair: #E0C070; --rough: #E59A8F; color-scheme: dark;
  }}
}}
:root[data-theme="dark"] {{
  --bg: #16181B; --panel: #1E2124; --sunk: #23262A; --line: #30343A; --line-strong: #3A3F45;
  --ink: #F0EEE9; --ink-2: #A9AEB4; --ink-3: #8A8F95;
  --signal: #FFD400; --signal-ink: #FFD400; --link: #8DB7E0;
  --good: #8CC3A0; --fair: #E0C070; --rough: #E59A8F; color-scheme: dark;
}}
* {{ box-sizing: border-box; }}
html {{ scroll-behavior: smooth; scroll-padding-top: 16px; }}
body {{ margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.6 var(--sans); }}
a {{ color: var(--link); text-underline-offset: 2px; }}
.layout {{ display: grid; grid-template-columns: 300px minmax(0, 1fr); min-height: 100vh; }}
nav.index {{ position: sticky; top: 0; height: 100vh; overflow-y: auto; padding: 24px 18px 40px;
  border-right: 1px solid var(--line); background: var(--panel); font-size: 14px; }}
.brand {{ display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }}
.brand-mark {{ width: 26px; height: 26px; border: 2px solid var(--ink); position: relative; }}
.brand-mark::before, .brand-mark::after {{ content: ""; position: absolute; background: var(--signal); }}
.brand-mark::before {{ left: 50%; top: -6px; bottom: -6px; width: 2px; transform: translateX(-50%); }}
.brand-mark::after {{ top: 50%; left: -6px; right: -6px; height: 2px; transform: translateY(-50%); }}
.brand b {{ font-size: 17px; letter-spacing: .02em; }}
.nav-group {{ margin: 18px 0 6px; font: 600 11px/1 var(--mono); letter-spacing: .12em; text-transform: uppercase; color: var(--ink-3); }}
nav ol {{ list-style: none; margin: 0; padding: 0; }}
nav summary {{ cursor: pointer; padding: 4px 0; list-style: none; }}
nav summary::-webkit-details-marker {{ display: none; }}
nav summary::before {{ content: "▸"; display: inline-block; width: 14px; color: var(--ink-3); transition: transform .15s; }}
nav details[open] > summary::before {{ transform: rotate(90deg); }}
nav summary a {{ color: var(--ink); text-decoration: none; font-weight: 600; }}
nav a.active {{ color: var(--signal-ink); }}
.nav-sub {{ margin: 2px 0 8px 14px !important; border-left: 1px solid var(--line); }}
.nav-sub a {{ display: block; padding: 3px 0 3px 10px; color: var(--ink-2); text-decoration: none; font-size: 13px; line-height: 1.35; }}
.nav-toggle {{ display: none; }}
.nav-sub a:hover, nav summary a:hover {{ color: var(--link); }}
.theme-btn {{ margin-top: 22px; width: 100%; padding: 8px; border: 1px solid var(--line-strong); background: var(--sunk);
  color: var(--ink); font: 13px var(--sans); border-radius: 6px; cursor: pointer; }}
main {{ min-width: 0; overflow-wrap: break-word; padding: 32px clamp(16px, 4vw, 56px) 80px; max-width: 1040px; }}
.hero h1 {{ margin: 0 0 6px; font-size: clamp(28px, 4vw, 40px); line-height: 1.15; letter-spacing: -.01em; }}
.hero p {{ margin: 0; color: var(--ink-2); }}
.meta {{ margin-top: 10px; font: 12px/1.5 var(--mono); color: var(--ink-3); }}
.milestones {{ display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 6px; list-style: none; padding: 0; margin: 24px 0 8px; }}
.ms {{ background: var(--panel); border: 1px solid var(--line); border-top: 3px solid var(--line-strong);
  border-radius: 6px; padding: 8px 10px; display: flex; flex-direction: column; gap: 2px; min-width: 0; }}
.ms-id {{ font: 700 13px var(--mono); }}
.ms-name {{ font-size: 12.5px; line-height: 1.3; color: var(--ink-2); }}
.ms-state {{ font: 11px var(--mono); color: var(--ink-3); margin-top: auto; padding-top: 4px; }}
.ms-done {{ border-top-color: var(--good); }} .ms-done .ms-state {{ color: var(--good); }}
.ms-active {{ border-top-color: var(--signal); }} .ms-active .ms-state {{ color: var(--signal-ink); }}
section.doc {{ background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
  padding: 8px clamp(16px, 3vw, 36px) 28px; margin-top: 28px; }}
.doc-head {{ display: flex; flex-wrap: wrap; gap: 4px 14px; justify-content: space-between; align-items: baseline;
  padding: 10px 0; border-bottom: 1px dashed var(--line-strong); font: 12px var(--mono); color: var(--ink-3); }}
.doc-group {{ text-transform: uppercase; letter-spacing: .1em; color: var(--signal-ink); font-weight: 700; }}
h2.doc-h1 {{ font-size: 28px; line-height: 1.2; margin: 20px 0 8px; }}
.doc h2:not(.doc-h1) {{ font-size: 21px; margin: 34px 0 8px; padding-top: 14px; border-top: 1px solid var(--line); }}
.doc h3 {{ font-size: 17px; margin: 24px 0 6px; }}
.doc h4 {{ font-size: 15px; margin: 18px 0 4px; color: var(--ink-2); }}
.doc p, .doc li {{ max-width: 78ch; }}
.doc li + li {{ margin-top: 3px; }}
code {{ font: .88em var(--mono); background: var(--sunk); border: 1px solid var(--line); padding: 1px 5px; border-radius: 4px; overflow-wrap: anywhere; }}
pre {{ background: var(--sunk); border: 1px solid var(--line); border-radius: 6px; padding: 12px 14px; overflow-x: auto; }}
pre code {{ background: none; border: 0; padding: 0; overflow-wrap: normal; }}
blockquote {{ margin: 12px 0; padding: 4px 16px; border-left: 3px solid var(--signal); color: var(--ink-2); background: var(--sunk); }}
.table-wrap {{ overflow-x: auto; margin: 14px 0; border: 1px solid var(--line); border-radius: 6px; }}
table {{ border-collapse: collapse; width: 100%; font-size: 14px; }}
th, td {{ text-align: left; vertical-align: top; padding: 7px 10px; border-bottom: 1px solid var(--line); }}
th {{ background: var(--sunk); font-weight: 600; white-space: nowrap; }}
tr:last-child td {{ border-bottom: 0; }}
hr {{ border: 0; border-top: 1px solid var(--line); margin: 24px 0; }}
.to-top {{ position: fixed; right: 16px; bottom: 16px; width: 40px; height: 40px; border-radius: 50%;
  background: var(--ink); color: var(--bg); display: grid; place-items: center; text-decoration: none; font-size: 18px; opacity: .85; }}
@media (max-width: 900px) {{
  .layout {{ display: block; }}
  nav.index {{ position: static; height: auto; max-height: none; border-right: 0; border-bottom: 1px solid var(--line); padding: 16px; }}
  .milestones {{ grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); }}
  .nav-toggle {{ display: block; font-weight: 600; padding: 8px 0; }}
  nav.index {{ padding-bottom: 8px; }}
  main {{ padding: 20px 16px 72px; }}
}}
@media print {{ nav.index, .to-top {{ display: none; }} .layout {{ display: block; }} section.doc {{ break-before: page; border: 0; }} }}
</style>
</head>
<body>
<div class="layout">
<nav class="index" aria-label="Contents">
  <div class="brand"><span class="brand-mark" aria-hidden="true"></span><b>SpaceView</b></div>
  <details class="nav-wrap" id="navWrap" open><summary class="nav-toggle">Contents</summary>
  {nav}
  </details>
  <button class="theme-btn" type="button" id="themeBtn">Toggle light / dark</button>
</nav>
<main>
  <header class="hero" id="top">
    <h1>SpaceView Build Book</h1>
    <p>Every SpaceView document in one place: status, plan, testing checklist, the four research reports and every milestone review. {count} documents, in reading order.</p>
    <div class="meta">Last commit: {commit}<br>Generated {generated} from the Markdown sources by <code>python docs/build_html.py</code>. Edit the .md files, not this page.</div>
    <ol class="milestones" aria-label="Milestones">{strip}</ol>
  </header>
  {sections}
</main>
</div>
<a class="to-top" href="#top" aria-label="Back to top">↑</a>
<script>
(function () {{
  var root = document.documentElement, btn = document.getElementById('themeBtn');
  try {{ var t = localStorage.getItem('sv-theme'); if (t) root.setAttribute('data-theme', t); }} catch (e) {{}}
  btn.addEventListener('click', function () {{
    var dark = root.getAttribute('data-theme') ? root.getAttribute('data-theme') === 'dark'
      : matchMedia('(prefers-color-scheme: dark)').matches;
    var next = dark ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try {{ localStorage.setItem('sv-theme', next); }} catch (e) {{}}
  }});
  if (matchMedia('(max-width: 900px)').matches) document.getElementById('navWrap').removeAttribute('open');
  // Highlight the document currently on screen in the index.
  var links = {{}};
  document.querySelectorAll('nav summary a').forEach(function (a) {{ links[a.getAttribute('href').slice(1)] = a; }});
  var io = new IntersectionObserver(function (entries) {{
    entries.forEach(function (e) {{
      if (e.isIntersecting && links[e.target.id]) {{
        Object.values(links).forEach(function (a) {{ a.classList.remove('active'); }});
        links[e.target.id].classList.add('active');
      }}
    }});
  }}, {{ rootMargin: '-10% 0px -80% 0px' }});
  document.querySelectorAll('section.doc').forEach(function (s) {{ io.observe(s); }});
}})();
</script>
</body>
</html>
"""

if __name__ == "__main__":
    main()
