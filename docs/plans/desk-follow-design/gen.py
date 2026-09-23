"""Generate the desk-follow design canvas: python3 gen.py → *.dc.html + canvas.json beside it.

The boards are copy in boards.py and survey.py; this file is the shared CSS and the frame. It
rewrites canvas.json from scratch, so a canvas re-saved by the editor loses its extra keys.
"""
import json, os, datetime
from boards import BOARDS  # [(file, title, w, h, x, y, body_html)]

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = HERE  # the boards sit beside this script in the design record

CSS = """
body { margin: 0; }
.app { --fg:oklch(0.985 0 0); --card:oklch(0.21 0.006 285.885); --muted:oklch(0.274 0.006 286.033); --mfg:oklch(0.72 0.015 286.067); --bd:oklch(0.32 0.006 286.033); --pri:oklch(0.623 0.214 259.815); --bg:oklch(0.141 0.005 285.823); --amber:oklch(0.828 0.189 84.429); --green:oklch(0.79 0.17 152); --red:oklch(0.704 0.191 22.216); --violet:oklch(0.72 0.16 300);
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", "Helvetica Neue", sans-serif; -webkit-font-smoothing: antialiased; background:var(--bg); color:var(--fg); box-sizing:border-box; }
.app * { box-sizing:border-box; }
.mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size:0.92em; }
.lbl { font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); }
.h1 { font-size:26px; font-weight:700; letter-spacing:-0.015em; margin:0; }
.h2 { font-size:15px; font-weight:700; letter-spacing:-0.005em; margin:0; }
.h3 { font-size:13px; font-weight:700; margin:0; }
.lede { font-size:14px; line-height:1.55; color:var(--mfg); margin:0; max-width:980px; }
.lede b, .cap b, .note b, td b, li b { color:var(--fg); font-weight:600; }
.cap { font-size:12px; line-height:1.5; color:var(--mfg); margin:0; }
.note { border-radius:10px; border:1px solid var(--bd); background:var(--card); padding:12px 14px; display:flex; flex-direction:column; gap:6px; }
.note p { margin:0; font-size:12px; line-height:1.5; color:var(--mfg); }
.note.key { border-color:oklch(0.5 0.16 300); background:oklch(0.27 0.09 302 / 0.28); }
.note.warn { border-color:oklch(0.6 0.14 80); background:oklch(0.5 0.12 80 / 0.12); }
.note.bad { border-color:oklch(0.58 0.16 25); background:oklch(0.5 0.15 25 / 0.12); }
.note.good { border-color:oklch(0.55 0.13 152); background:oklch(0.5 0.12 152 / 0.12); }
.col { display:flex; flex-direction:column; gap:12px; min-width:0; }
.row { display:flex; gap:12px; min-width:0; }
.grid2 { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:16px; }
.grid3 { display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:16px; }
ol, ul { margin:0; padding-left:18px; }
li { font-size:12px; line-height:1.55; color:var(--mfg); }
li + li { margin-top:4px; }
table { border-collapse:collapse; width:100%; font-size:12px; }
th { text-align:left; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.07em; color:var(--mfg); padding:8px 10px; border-bottom:1px solid var(--bd); vertical-align:bottom; }
td { padding:9px 10px; border-bottom:1px solid var(--bd); vertical-align:top; line-height:1.45; color:var(--mfg); }
td.name { color:var(--fg); font-weight:600; white-space:nowrap; }
.yes { color:var(--green); font-weight:600; }
.no { color:var(--red); font-weight:600; }
.meh { color:var(--amber); font-weight:600; }
.tag { display:inline-flex; align-items:center; height:20px; padding:0 7px; border-radius:10px; font-size:10px; font-weight:700; letter-spacing:0.04em; white-space:nowrap; }
.tag.today { background:var(--muted); color:var(--mfg); }
.tag.prop { background:oklch(0.623 0.214 259.815 / 0.22); color:oklch(0.83 0.1 259); }
.tag.call { background:oklch(0.5 0.12 80 / 0.25); color:var(--amber); }
.tag.goes { background:oklch(0.5 0.15 25 / 0.22); color:oklch(0.8 0.12 25); }
.pill { display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 10px; border-radius:14px; font-size:12px; font-weight:500; white-space:nowrap; border:1px solid var(--bd); background:var(--card); color:var(--fg); }
.pill.local { border-style:dashed; border-color:var(--amber); color:var(--amber); background:transparent; }
.pill .sub { color:var(--mfg); }
.seg { display:inline-flex; align-items:center; gap:2px; border:1px solid var(--bd); background:var(--card); border-radius:8px; padding:2px; height:28px; }
.seg span { display:inline-flex; align-items:center; height:22px; padding:0 8px; border-radius:6px; font-size:11px; font-weight:600; color:var(--mfg); white-space:nowrap; }
.seg span.on { background:var(--muted); color:var(--fg); }
.seg.dis { opacity:0.45; }
.btn { display:inline-flex; align-items:center; justify-content:center; gap:6px; height:28px; padding:0 10px; border-radius:8px; font-size:12px; font-weight:500; white-space:nowrap; color:var(--fg); border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); font-family:inherit; }
.btn.pri { background:var(--pri); border-color:var(--pri); color:oklch(0.21 0.006 285.885); font-weight:600; }
.btn.ghost { border-color:transparent; background:transparent; color:var(--mfg); }
.field { height:32px; padding:0 10px; border-radius:8px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); font-size:13px; display:inline-flex; align-items:center; gap:6px; white-space:nowrap; color:var(--fg); }
.panel { border:1px solid var(--bd); border-radius:12px; background:var(--card); display:flex; flex-direction:column; overflow:hidden; }
.panel .hd { display:flex; align-items:center; gap:8px; height:44px; padding:0 14px; border-bottom:1px solid var(--bd); font-size:14px; font-weight:700; }
.panel .bd { display:flex; flex-direction:column; gap:12px; padding:14px; }
.wrow { border:1px solid var(--bd); border-radius:10px; padding:10px 12px; display:flex; flex-direction:column; gap:8px; background:var(--bg); }
.wrow .top { display:flex; align-items:center; gap:8px; }
.wrow .opts { display:flex; flex-wrap:wrap; align-items:center; gap:8px 14px; font-size:11px; color:var(--mfg); }
.wrow .opt { display:inline-flex; align-items:center; gap:6px; }
.badge { font-size:10px; font-weight:600; color:var(--mfg); border:1px solid var(--bd); border-radius:6px; padding:1px 6px; }
.flow { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.box { border:1px solid var(--bd); border-radius:10px; background:var(--card); padding:10px 12px; font-size:12px; line-height:1.45; color:var(--mfg); }
.box b { color:var(--fg); }
.box.desk { border-color:oklch(0.5 0.16 300); background:oklch(0.27 0.09 302 / 0.28); }
.box.win { border-color:oklch(0.5 0.14 259); background:oklch(0.3 0.08 259 / 0.25); }
.arrow { color:var(--mfg); font-size:18px; }
.kbd { font-family:inherit; font-size:10px; border:1px solid var(--bd); border-bottom-width:2px; border-radius:4px; padding:0 4px; color:var(--mfg); }
.toast { display:flex; align-items:flex-start; gap:10px; border:1px solid var(--bd); border-radius:10px; background:var(--card); padding:12px 14px; box-shadow:0 12px 40px rgba(0,0,0,0.5); font-size:13px; max-width:420px; }
.toast .d { font-size:12px; color:var(--mfg); margin-top:2px; }
.cmd { border:1px solid var(--bd); border-radius:12px; background:var(--card); overflow:hidden; box-shadow:0 12px 40px rgba(0,0,0,0.5); }
.cmd .q { height:44px; display:flex; align-items:center; padding:0 14px; border-bottom:1px solid var(--bd); font-size:14px; color:var(--fg); }
.cmd .g { padding:8px 14px 4px; }
.cmd .it { display:flex; align-items:center; gap:10px; height:36px; padding:0 14px; font-size:13px; color:var(--fg); }
.cmd .it.hi { background:var(--muted); }
.cmd .it .det { margin-left:auto; font-size:11px; color:var(--mfg); }
.band { display:flex; align-items:center; gap:8px; height:40px; padding:0 12px; border:1px solid var(--bd); border-radius:10px; background:var(--bg); font-size:12px; }
.band .gap { flex:1; }
.band .rl { font-size:9px; font-weight:700; letter-spacing:0.08em; color:var(--mfg); }
.tile { display:inline-flex; flex-direction:column; justify-content:space-between; width:88px; height:52px; padding:6px 8px; border-radius:8px; border:1px solid var(--bd); background:var(--card); font-size:11px; font-weight:600; }
.tile.on { border-color:var(--pri); box-shadow:0 0 0 1px var(--pri) inset; }
.tile i { display:block; height:3px; border-radius:2px; background:var(--amber); }
"""

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<style>{css}</style>
</helmet>
<div class="app" style="width: {w}px; height: {h}px; padding: 48px 56px; display: flex; flex-direction: column; gap: 24px; overflow: hidden;">
{body}
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":{w},"height":{h}}}}}'>
class Component extends DCLogic {{
  renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
"""

os.makedirs(OUT, exist_ok=True)
boards, order = {}, []
for f, title, w, h, x, y, body in BOARDS:
    with open(os.path.join(OUT, f), 'w') as fh:
        fh.write(TEMPLATE.format(title=title, css=CSS, w=w, h=h, body=body.strip()))
    boards[f] = {"x": x, "y": y, "w": w, "h": h, "title": title}
    order.append(f)

canvas_path = os.path.join(OUT, 'canvas.json')
created = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
if os.path.exists(canvas_path):
    created = json.load(open(canvas_path)).get('createdOnFiles', {}).get('at', created)
canvas = {
    "v": 3,
    "createdOnFiles": {"v": 1, "at": created},
    "title": "Desk follow redesign",
    "launch": {"view": "canvas"},
    "pages": [],
    "boards": boards,
    "order": order,
    "notes": {
        "t1": {"x": 0, "y": -260, "text": "Following the desk — D18 reviewed, and following itself", "kind": "title1", "maxW": 2960},
    },
    "designSystems": [],
}
json.dump(canvas, open(canvas_path, 'w'), indent=2, ensure_ascii=False)
print('wrote', len(order), 'boards')
