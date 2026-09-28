import { useState, useEffect, useCallback, useRef, useMemo } from "react";

// ─── NATURAL SORT ─────────────────────────────────────────────────────────────
function natKey(s) {
  return s.split(/(\d+)/).filter(Boolean).map(p =>
    /^\d+$/.test(p) ? p.padStart(20, '0') : p.toLowerCase()
  );
}
function natCmp(a, b) {
  const ka = natKey(a), kb = natKey(b);
  for (let i = 0; i < Math.max(ka.length, kb.length); i++) {
    if ((ka[i] || '') < (kb[i] || '')) return -1;
    if ((ka[i] || '') > (kb[i] || '')) return 1;
  }
  return 0;
}
const isPartic = cn => cn && /^\d/.test(cn);
function detectOOO(cues) {
  const p = cues.filter(c => c.cueType === 'STANDARD' && isPartic(c.cueNumber));
  for (let i = 1; i < p.length; i++)
    if (natCmp(p[i].cueNumber, p[i - 1].cueNumber) < 0) return true;
  return false;
}
function sortByCueNum(cues) {
  const out = [...cues];
  const partIdxs = cues.reduce((acc, c, i) =>
    c.cueType === 'STANDARD' && isPartic(c.cueNumber) ? [...acc, i] : acc, []);
  const sorted = [...partIdxs].sort((a, b) => natCmp(cues[a].cueNumber, cues[b].cueNumber));
  partIdxs.forEach((origI, n) => {
    out[origI] = { ...cues[sorted[n]], sortOrder: cues[origI].sortOrder };
  });
  return out;
}

// ─── MOCK DATA ────────────────────────────────────────────────────────────────

const PRESETS = [
  { id: 'p1', name: 'Warm Wash', description: 'Amber wash across full stage', channels: 12 },
  { id: 'p2', name: 'Cool State', description: 'Steel blue state wash', channels: 8 },
  { id: 'p3', name: 'Desk Lamp Spot', description: 'Tight spot DSR on desk', channels: 2 },
  { id: 'p4', name: 'Garden Gobo', description: 'Leaf gobo breakup floor', channels: 6 },
  { id: 'p5', name: 'Sunset Build', description: 'Amber-to-magenta colour wash', channels: 14 },
  { id: 'p6', name: 'Night Sky', description: 'Deep blue with star gobo', channels: 10 },
  { id: 'p7', name: 'Strobe Burst', description: 'Full stage strobe chase', channels: 16 },
  { id: 'p8', name: 'Chase Rainbow', description: 'RGB chase across strip', channels: 8 },
];

const SCRIPTS = [
  { id: 'scr1', name: 'Flash Sequence', description: 'Rapid 3-flash strobe burst' },
  { id: 'scr2', name: 'Colour Cycle', description: 'Slow RGB cycle on movers' },
  { id: 'scr3', name: 'Crowd Scan', description: 'Pan/tilt sweep across audience' },
  { id: 'scr4', name: 'Fog Burst', description: 'Fog machine relay for 3s' },
];

const FX_CUES = [
  { id: 'fx1', name: 'Pre-show Preset', presets: ['p1', 'p3'], fadeDurationMs: 3000, fadeCurve: 'EASE_IN_OUT', notes: 'House open look' },
  { id: 'fx2', name: 'House to Half', presets: ['p1'], fadeDurationMs: 5000, fadeCurve: 'LINEAR', notes: 'SM call' },
  { id: 'fx3', name: 'Study Pre-set', presets: ['p3'], fadeDurationMs: 0, fadeCurve: 'LINEAR', notes: 'Snap — desk lamp only' },
  { id: 'fx4', name: 'Morning Light', presets: ['p1', 'p5'], fadeDurationMs: 8000, fadeCurve: 'EASE_OUT', notes: 'p12 — The sun rose' },
  { id: 'fx5', name: 'Phone Flash', presets: ['p7'], fadeDurationMs: 0, fadeCurve: 'LINEAR', notes: 'Snap + auto' },
  { id: 'fx6', name: 'Garden Afternoon', presets: ['p4', 'p1'], fadeDurationMs: 4000, fadeCurve: 'CUBIC_IN_OUT', notes: 'Bright, open air' },
  { id: 'fx7', name: 'Sunset Build', presets: ['p5'], fadeDurationMs: 6000, fadeCurve: 'EASE_IN_OUT', notes: 'Long cross-fade' },
  { id: 'fx8', name: 'Night', presets: ['p6'], fadeDurationMs: 3000, fadeCurve: 'SINE_IN_OUT', notes: 'End of act' },
  { id: 'fx9', name: 'Strobe Burst', presets: ['p7'], fadeDurationMs: 0, fadeCurve: 'LINEAR', notes: 'Intro hit' },
  { id: 'fx10', name: 'Verse Wash', presets: ['p2', 'p8'], fadeDurationMs: 1000, fadeCurve: 'EASE_IN_OUT', notes: null },
  { id: 'fx11', name: 'Chorus Chase', presets: ['p8'], fadeDurationMs: 500, fadeCurve: 'LINEAR', notes: null },
  { id: 'fx12', name: 'Bridge Build', presets: ['p5', 'p1'], fadeDurationMs: 2000, fadeCurve: 'CUBIC_IN_OUT', notes: null },
  { id: 'fx13', name: 'Outro Full', presets: ['p1', 'p5', 'p6'], fadeDurationMs: 3000, fadeCurve: 'EASE_OUT', notes: null },
];

let nextCueId = 200;
const INIT_STACKS = {
  1: {
    id: 1, name: "Act 1", context: "theatre", loop: false,
    cues: [
      { id:1, sortOrder:1, cueNumber:"1", cueType:"STANDARD", name:"Pre-show preset", fadeDurationMs:3000, fadeCurve:"EASE_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"House open look", fxCueId:'fx1', triggerActivate:null, triggerDeactivate:null },
      { id:2, sortOrder:2, cueNumber:"2", cueType:"STANDARD", name:"House to half", fadeDurationMs:5000, fadeCurve:"LINEAR", autoAdvance:true, autoAdvanceDelayMs:2000, notes:"SM: House to half", fxCueId:'fx2', triggerActivate:null, triggerDeactivate:null },
      { id:3, sortOrder:3, cueNumber:null, cueType:"MARKER", name:"Scene 1 – The Study", fadeDurationMs:null, fadeCurve:null, autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:4, sortOrder:4, cueNumber:"3", cueType:"STANDARD", name:"Study pre-set", fadeDurationMs:0, fadeCurve:"LINEAR", autoAdvance:false, autoAdvanceDelayMs:null, notes:"Snap — desk lamp only", fxCueId:'fx3', triggerActivate:null, triggerDeactivate:null },
      { id:5, sortOrder:5, cueNumber:"5", cueType:"STANDARD", name:"Morning light", fadeDurationMs:8000, fadeCurve:"EASE_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"p12 — The sun rose slowly", fxCueId:'fx4', triggerActivate:'scr2', triggerDeactivate:null },
      { id:6, sortOrder:6, cueNumber:"4A", cueType:"STANDARD", name:"Phone flash", fadeDurationMs:0, fadeCurve:"LINEAR", autoAdvance:true, autoAdvanceDelayMs:1500, notes:"Snap + auto p14", fxCueId:'fx5', triggerActivate:'scr1', triggerDeactivate:null },
      { id:7, sortOrder:7, cueNumber:null, cueType:"MARKER", name:"Scene 2 – The Garden", fadeDurationMs:null, fadeCurve:null, autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:8, sortOrder:8, cueNumber:"6", cueType:"STANDARD", name:"Garden afternoon", fadeDurationMs:4000, fadeCurve:"CUBIC_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"Bright, open air", fxCueId:'fx6', triggerActivate:null, triggerDeactivate:null },
      { id:9, sortOrder:9, cueNumber:"7", cueType:"STANDARD", name:"Sunset build", fadeDurationMs:6000, fadeCurve:"EASE_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"p28 — long cross-fade", fxCueId:'fx7', triggerActivate:null, triggerDeactivate:null },
      { id:10, sortOrder:10, cueNumber:"8", cueType:"STANDARD", name:"Night", fadeDurationMs:3000, fadeCurve:"SINE_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"End of act 1", fxCueId:'fx8', triggerActivate:null, triggerDeactivate:'scr4' },
    ]
  },
  2: {
    id: 2, name: "Electric Feel", context: "band", loop: true,
    cues: [
      { id:101, sortOrder:1, cueNumber:null, cueType:"STANDARD", name:"Intro strobe burst", fadeDurationMs:0, fadeCurve:"LINEAR", autoAdvance:true, autoAdvanceDelayMs:2000, notes:null, fxCueId:'fx9', triggerActivate:'scr1', triggerDeactivate:null },
      { id:102, sortOrder:2, cueNumber:null, cueType:"STANDARD", name:"Verse wash", fadeDurationMs:1000, fadeCurve:"EASE_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:null, fxCueId:'fx10', triggerActivate:null, triggerDeactivate:null },
      { id:103, sortOrder:3, cueNumber:null, cueType:"STANDARD", name:"Chorus chase", fadeDurationMs:500, fadeCurve:"LINEAR", autoAdvance:false, autoAdvanceDelayMs:null, notes:null, fxCueId:'fx11', triggerActivate:'scr3', triggerDeactivate:null },
      { id:104, sortOrder:4, cueNumber:null, cueType:"STANDARD", name:"Bridge build", fadeDurationMs:2000, fadeCurve:"CUBIC_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:null, fxCueId:'fx12', triggerActivate:null, triggerDeactivate:null },
      { id:105, sortOrder:5, cueNumber:null, cueType:"STANDARD", name:"Outro full", fadeDurationMs:3000, fadeCurve:"EASE_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:null, fxCueId:'fx13', triggerActivate:null, triggerDeactivate:null },
    ]
  }
};

const SESSIONS = [
  {
    id: 'sess1', name: 'Spring Production 2026',
    entries: [
      { id: 'e1', type: 'STACK', stackId: 1, stackName: 'Act 1' },
      { id: 'e2', type: 'MARKER', label: '— Interval —' },
      { id: 'e3', type: 'STACK', stackId: 2, stackName: 'Electric Feel' },
    ],
  },
  {
    id: 'sess2', name: 'Dress Rehearsal',
    entries: [
      { id: 'e4', type: 'STACK', stackId: 1, stackName: 'Act 1' },
      { id: 'e5', type: 'STACK', stackId: 2, stackName: 'Electric Feel' },
    ],
  },
];
const INIT_SESSION = SESSIONS[0];
let nextEntryId = 10;

// ─── HELPERS ──────────────────────────────────────────────────────────────────
const firstStd = cues => cues.find(c => c.cueType === 'STANDARD')?.id ?? null;
function nextStd(cues, id, loop) { const i = cues.findIndex(c => c.id === id); for (let j = i+1; j < cues.length; j++) if (cues[j].cueType === 'STANDARD') return cues[j].id; if (loop) for (let j = 0; j < i; j++) if (cues[j].cueType === 'STANDARD') return cues[j].id; return null; }
function prevStd(cues, id) { const i = cues.findIndex(c => c.id === id); for (let j = i-1; j >= 0; j--) if (cues[j].cueType === 'STANDARD') return cues[j].id; return null; }
const fmtMs = ms => !ms ? "SNAP" : ms < 1000 ? ms+"ms" : (ms/1000).toFixed(1)+"s";
const fmtCurve = c => ({ LINEAR:"LIN", EASE_IN_OUT:"SINE", SINE_IN_OUT:"SINE", CUBIC_IN_OUT:"CUB", EASE_IN:"↑", EASE_OUT:"↓" }[c] || "");

// ─── CSS ──────────────────────────────────────────────────────────────────────
const CSS = `
@import url('https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap');
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
.cr{font-family:'Barlow Condensed',sans-serif;background:#090b0e;color:#bcc8d8;height:100vh;display:flex;flex-direction:column;overflow:hidden;user-select:none;font-size:14px}
.cr-bar{display:flex;align-items:stretch;height:56px;background:#0d1118;border-bottom:1px solid #1c2433;flex-shrink:0;box-shadow:0 2px 20px rgba(0,0,0,.7)}
.cr-bar-sec{display:flex;align-items:center;padding:0 16px;border-right:1px solid #1c2433;gap:10px;flex-shrink:0}
.cr-bar-sec.grow{flex:1;border-right:none;overflow:hidden;min-width:0}
.cr-lbl{font-size:9px;font-weight:700;letter-spacing:.15em;text-transform:uppercase;color:#253545}
.cr-bpm{font-family:'JetBrains Mono',monospace;font-size:22px;font-weight:600;color:#48657a;min-width:56px;text-align:center}
.cr-cuinfo{display:flex;flex-direction:column;justify-content:center;gap:3px;flex:1;overflow:hidden;min-width:0}
.cr-cuinfo-active{font-size:15px;font-weight:600;color:#f5a234;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cr-cuinfo-standby{font-size:11px;color:#3a9060;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cr-cuinfo-idle{font-size:13px;color:#324050;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cr-kbhints{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:0 14px;border-right:1px solid #1c2433;gap:3px}
.cr-kbhint{font-size:9px;color:#1e2e3e;letter-spacing:.07em;white-space:nowrap;font-weight:600;text-transform:uppercase}
.cr-btn{display:inline-flex;align-items:center;justify-content:center;gap:5px;border:none;border-radius:3px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;font-weight:700;letter-spacing:.09em;text-transform:uppercase;transition:background .1s,box-shadow .12s}
.btn-dbo{height:38px;padding:0 16px;font-size:13px;background:#4a1010;color:#e08080;border:1px solid #6a1818}
.btn-dbo:hover{background:#6a1818}
.btn-dbo.on{background:#c82020;color:#fff;box-shadow:0 0 20px rgba(200,32,32,.55)}
.btn-tap{height:30px;padding:0 14px;font-size:11px;background:#111820;color:#324050;border:1px solid #1a2a3a}
.btn-tap:hover{background:#192535;color:#5580a0}
.btn-back{height:100%;padding:0 22px;font-size:13px;background:none;color:#324050;border:none;border-right:1px solid #1c2433;border-radius:0;transition:background .1s,color .1s;letter-spacing:.08em}
.btn-back:hover{background:#0f1820;color:#6090b8}
.btn-go{height:100%;padding:0 42px;font-size:24px;letter-spacing:.16em;background:#0a2e1a;color:#3aca70;border:none;border-radius:0;transition:background .1s,box-shadow .1s}
.btn-go:hover{background:#0f3d22;box-shadow:inset 0 0 24px rgba(74,222,128,.12)}
.btn-edit-mode{height:38px;padding:0 16px;font-size:12px;background:#111820;color:#324050;border:1px solid #1a2a3a;letter-spacing:.08em}
.btn-edit-mode:hover{background:#192535;color:#5580a0}
.btn-edit-mode.on{background:#1a2040;color:#7a8aff;border-color:#3a4090;box-shadow:0 0 16px rgba(100,120,255,.2)}
.cr-body{display:flex;flex:1;overflow:hidden}
.cr-runner{flex:1;display:flex;flex-direction:column;overflow:hidden;min-width:0}
.cr-tabs{display:flex;height:38px;flex-shrink:0;background:#0b0e13;border-bottom:1px solid #1c2433;align-items:center}
.cr-tab{display:flex;align-items:center;gap:8px;padding:0 22px;background:none;border:none;border-right:1px solid #1c2433;cursor:pointer;font-family:'Barlow Condensed',sans-serif;font-size:12px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:#253545;transition:all .12s;position:relative;height:100%;flex-shrink:0}
.cr-tab:hover{color:#485870;background:#0e1318}
.cr-tab.on{color:#7a9ab8;background:#0f1820}
.cr-tab.on::after{content:'';position:absolute;bottom:0;left:0;right:0;height:2px;background:#3a7ecc}
.cr-loop{font-size:16px;color:#253545}
.cr-tab.on .cr-loop{color:#4060a0}
.cr-tabs-spacer{flex:1}
.cr-ctx-wrap{display:flex;align-items:center;gap:0;margin:0 14px;flex-shrink:0}
.cr-ctx-btn{height:26px;padding:0 12px;font-family:'Barlow Condensed',sans-serif;font-size:10px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;background:#0e1318;color:#253545;border:1px solid #1c2433;cursor:pointer;transition:all .1s}
.cr-ctx-btn:first-child{border-radius:3px 0 0 3px}
.cr-ctx-btn:last-child{border-radius:0 3px 3px 0;border-left:none}
.cr-ctx-btn.on{background:#1a2535;color:#6090b8;border-color:#2a4060}
.cr-ooo{display:flex;align-items:center;gap:10px;padding:6px 16px;background:rgba(200,150,0,.07);border-bottom:1px solid rgba(200,150,0,.18);font-size:12px;color:#906a10;flex-shrink:0}
.cr-ooo-btn{font-family:'Barlow Condensed',sans-serif;font-size:10px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;background:rgba(200,150,0,.1);border:1px solid rgba(200,150,0,.28);color:#b08020;padding:2px 10px;border-radius:2px;cursor:pointer}
.cr-hdr{display:flex;align-items:center;height:24px;padding:0 0 0 19px;border-bottom:1px solid #111820;background:#0b0e13;flex-shrink:0}
.cr-hdr>div{font-size:9px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#1e2e3e}
.cr-list{flex:1;overflow-y:auto;padding:2px 0}
.cr-list::-webkit-scrollbar{width:4px}
.cr-list::-webkit-scrollbar-track{background:#090b0e}
.cr-list::-webkit-scrollbar-thumb{background:#1c2433;border-radius:2px}
.cr-mkr{display:flex;align-items:center;gap:10px;padding:9px 14px}
.cr-mkr-line{flex:1;height:1px;background:#141c28}
.cr-mkr-label{font-size:10px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#254060;padding:2px 9px;white-space:nowrap;border:1px solid #192c40;border-radius:2px;background:#0c1520}
.prog-mkr{display:flex;align-items:center;gap:10px;padding:9px 14px;cursor:pointer;transition:background .08s}
.prog-mkr:hover{background:#0e1318}
.prog-mkr-inp{background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:4px 9px;color:#5080a0;font-family:'Barlow Condensed',sans-serif;font-size:11px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;text-align:center;outline:none;flex:0 0 auto;min-width:120px;transition:border-color .12s}
.prog-mkr-inp:focus{border-color:#284878;color:#7ab0d0}
.prog-mkr-del{background:none;border:none;color:#253545;font-size:13px;cursor:pointer;padding:2px 6px;transition:color .1s}
.prog-mkr-del:hover{color:#904040}
.cr-row{position:relative;display:flex;align-items:center;height:36px;padding:0 0 0 16px;border-left:3px solid transparent;cursor:pointer;overflow:hidden;transition:background .08s}
.cr-row:hover{background:#0e1218}
.cr-row.done{opacity:.36}
.cr-row.active{border-left-color:#d4901e;background:rgba(212,144,30,.055)}
.cr-row.standby{border-left-color:#3ab868;background:rgba(58,184,104,.04)}
.cr-fadebar{position:absolute;bottom:0;left:0;height:2px;background:linear-gradient(90deg,#9a6010,#f0a030);box-shadow:0 0 8px rgba(240,160,48,.55);pointer-events:none}
.cr-autobar{position:absolute;bottom:0;left:0;height:2px;background:linear-gradient(90deg,#2050b0,#5890f0);box-shadow:0 0 8px rgba(88,144,240,.55);pointer-events:none}
.cc-s{width:20px;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:11px}
.cc-q{width:48px;flex-shrink:0;font-family:'JetBrains Mono',monospace;font-size:11px;font-weight:600;color:#25405a;padding-right:4px}
.cc-n{flex:1;font-size:14px;font-weight:500;color:#5a7080;letter-spacing:.01em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
.cr-row.active .cc-n{color:#e8c07a;font-weight:600}
.cr-row.standby .cc-n{color:#7adca0;font-weight:600}
.cc-f{width:86px;flex-shrink:0;text-align:right;font-family:'JetBrains Mono',monospace;font-size:11px;color:#253a4a;padding-right:8px}
.cc-a{width:36px;flex-shrink:0;text-align:center;font-size:10px;padding-right:4px}
.cc-notes{width:200px;flex-shrink:0;font-size:11px;color:#253a4a;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;border-left:1px solid #141c28;padding:0 12px;font-style:italic}
.st-done{color:#1a3828;font-size:12px}
.st-active{color:#d4901e;font-size:15px}
.st-standby{color:#3ab868;font-size:12px}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.3}}
.st-auto{color:#5890f0;font-size:10px;animation:blink 1s ease-in-out infinite}
.auto-pill{display:inline-flex;align-items:center;background:rgba(88,144,240,.1);color:#3a6ab0;font-size:9px;font-weight:700;letter-spacing:.05em;padding:1px 5px;border-radius:2px;border:1px solid rgba(88,144,240,.18)}
.cc-prog-btn{flex-shrink:0;background:rgba(100,120,255,.08);border:1px solid rgba(100,120,255,.2);border-radius:3px;padding:2px 8px;font-size:9px;font-weight:700;letter-spacing:.06em;color:#6a7aff;cursor:pointer;font-family:'Barlow Condensed',sans-serif;text-transform:uppercase;transition:all .1s;margin-right:4px}
.cc-prog-btn:hover{background:rgba(100,120,255,.15);border-color:rgba(100,120,255,.35)}
.prog{flex:1;display:flex;flex-direction:column;overflow:hidden}
.prog-topbar{display:flex;align-items:center;height:48px;padding:0 20px;background:#0d1118;border-bottom:1px solid #1c2433;gap:16px;flex-shrink:0}
.prog-title{font-size:18px;font-weight:600;color:#7a9ab8;letter-spacing:.04em}
.prog-subtitle{font-size:12px;color:#3a5060;letter-spacing:.06em}
.prog-ready{margin-left:auto;display:flex;align-items:center;gap:8px;padding:6px 16px;background:#0a2e1a;border:1px solid #1a4a30;border-radius:4px;color:#3aca70;font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;cursor:pointer;transition:all .12s}
.prog-ready:hover{background:#0f3d22;box-shadow:0 0 12px rgba(74,222,128,.15)}
.sess-list{flex:1;overflow-y:auto;padding:12px 16px;display:flex;flex-direction:column;gap:6px}
.sess-entry{display:flex;align-items:center;padding:10px 16px;background:#0c1018;border:1px solid #151c28;border-radius:4px;cursor:pointer;transition:all .12s;gap:12px}
.sess-entry:hover{background:#10161f;border-color:#1c2a3a}
.sess-entry-num{font-family:'JetBrains Mono',monospace;font-size:11px;color:#253a50;width:24px;text-align:center;flex-shrink:0}
.sess-entry-name{font-size:14px;font-weight:600;color:#5a7a90;flex:1}
.sess-entry-meta{font-size:11px;color:#253a4a;flex-shrink:0}
.sess-entry-mkr{display:flex;align-items:center;gap:10px;padding:6px 16px}
.sess-entry-mkr-line{flex:1;height:1px;background:#141c28}
.sess-entry-mkr-label{font-size:10px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#254060;padding:2px 9px}
.prog-stack{flex:1;display:flex;flex-direction:column;overflow:hidden}
.prog-stack-hdr{display:flex;align-items:center;height:42px;padding:0 16px;background:#0b0e13;border-bottom:1px solid #1c2433;gap:12px;flex-shrink:0}
.prog-back{background:none;border:1px solid #1c2433;border-radius:3px;color:#3a5060;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:4px 12px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.prog-back:hover{color:#6090b8;border-color:#2a4060}
.prog-stack-name{font-size:15px;font-weight:600;color:#6a8aa0;letter-spacing:.03em}
.prog-stack-cues{font-size:11px;color:#304050}
.prog-add-btn{margin-left:auto;background:#111828;border:1px solid #1c2a3a;border-radius:3px;color:#4a6a80;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:5px 14px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.prog-add-btn:hover{color:#6aa0c0;border-color:#2a4a60;background:#152030}
.prog-add-sep{background:#0c1520;border:1px solid #192c40;border-radius:3px;color:#254060;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:5px 14px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.prog-add-sep:hover{color:#4080b0;border-color:#2a4a60;background:#101828}
.prog-cue-row{display:flex;align-items:center;height:40px;padding:0 16px;border-bottom:1px solid #111820;cursor:pointer;transition:background .08s;gap:8px}
.prog-cue-row:hover{background:#0e1218}
.prog-cue-drag{width:16px;color:#1c2433;font-size:12px;cursor:grab;flex-shrink:0}
.prog-cue-drag:hover{color:#3a5060}
.prog-cue-num{width:44px;font-family:'JetBrains Mono',monospace;font-size:11px;font-weight:600;color:#25405a;flex-shrink:0}
.prog-cue-name{flex:1;font-size:14px;font-weight:500;color:#5a7080;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.prog-cue-fade{width:80px;font-family:'JetBrains Mono',monospace;font-size:11px;color:#253a4a;text-align:right;flex-shrink:0}
.prog-cue-edit-icon{width:24px;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:13px;color:#1c2a3a;transition:color .1s}
.prog-cue-row:hover .prog-cue-edit-icon{color:#3a5060}
.lib-overlay{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:100;display:flex;justify-content:flex-end}
.lib-drawer{width:380px;background:#0b0e13;border-left:1px solid #1c2433;display:flex;flex-direction:column;animation:slideIn .2s ease-out}
@keyframes slideIn{from{transform:translateX(100%)}to{transform:translateX(0)}}
.lib-hdr{display:flex;align-items:center;height:48px;padding:0 16px;border-bottom:1px solid #1c2433;gap:12px;flex-shrink:0}
.lib-title{font-size:14px;font-weight:700;color:#6a8aa0;letter-spacing:.06em;text-transform:uppercase}
.lib-close{background:none;border:none;color:#3a5060;font-size:18px;cursor:pointer;margin-left:auto;padding:4px}
.lib-search{padding:8px 16px;flex-shrink:0}
.lib-search input{width:100%;background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:8px 12px;color:#7a9ab8;font-size:13px;font-family:'Barlow Condensed',sans-serif;outline:none}
.lib-search input:focus{border-color:#284878}
.lib-search input::placeholder{color:#253545}
.lib-list{flex:1;overflow-y:auto;padding:4px 0}
.lib-item{display:flex;align-items:center;padding:10px 16px;cursor:pointer;transition:background .08s;gap:12px;border-bottom:1px solid #0e1318}
.lib-item:hover{background:#10161f}
.lib-item-name{font-size:14px;font-weight:500;color:#5a7a90;flex:1}
.lib-item-presets{font-size:10px;color:#253a4a}
.lib-item-add{background:#0a2e1a;border:1px solid #1a4a30;border-radius:3px;color:#3aca70;font-size:10px;font-weight:700;padding:3px 10px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;letter-spacing:.06em;text-transform:uppercase;flex-shrink:0}
.lib-item-add:hover{background:#0f3d22}
.sheet-overlay{position:fixed;inset:0;z-index:200;display:flex;flex-direction:column;justify-content:flex-end;background:rgba(0,0,0,.65)}
.sheet-panel{background:#0b0e13;border-top:1px solid #1c2433;border-radius:10px 10px 0 0;max-height:80vh;display:flex;flex-direction:column;animation:sheetUp .22s ease-out}
@keyframes sheetUp{from{transform:translateY(100%)}to{transform:translateY(0)}}
.sheet-handle{display:flex;justify-content:center;padding:8px 0 4px}
.sheet-handle-bar{width:36px;height:4px;background:#1c2433;border-radius:2px}
.sheet-hdr{display:flex;align-items:center;padding:0 20px 12px;gap:12px}
.sheet-title{font-size:16px;font-weight:600;color:#7a9ab8;letter-spacing:.03em;flex:1}
.sheet-close{background:none;border:1px solid #1c2433;border-radius:3px;color:#3a5060;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:4px 12px;cursor:pointer;font-family:'Barlow Condensed',sans-serif}
.sheet-close:hover{color:#6090b8;border-color:#2a4060}
.sheet-body{flex:1;overflow-y:auto;padding:0 20px 20px}
.sheet-section{margin-bottom:16px}
.sheet-section-title{font-size:9px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:#253545;margin-bottom:8px}
.sheet-field{display:flex;flex-direction:column;gap:5px;margin-bottom:10px}
.sheet-field-label{font-size:9px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:#253545}
.sheet-row{display:flex;align-items:center;padding:8px 12px;background:#0c1018;border:1px solid #151c28;border-radius:3px;margin-bottom:4px;gap:12px;transition:background .08s}
.sheet-row:hover{background:#10161f;border-color:#1c2a3a}
.sheet-row-name{font-size:13px;font-weight:500;color:#5a7a90;flex:1}
.sheet-row-meta{font-size:11px;color:#253a4a}
.sheet-row-remove{background:none;border:none;color:#3a2020;font-size:11px;cursor:pointer;padding:2px 6px;transition:color .1s}
.sheet-row-remove:hover{color:#904040}
.sheet-add-pill{display:inline-flex;align-items:center;gap:4px;padding:5px 12px;background:#0c1018;border:1px dashed #1c2a3a;border-radius:3px;font-size:11px;font-weight:600;color:#3a5060;cursor:pointer;transition:all .1s;letter-spacing:.04em}
.sheet-add-pill:hover{color:#5a8aaa;border-color:#2a4a6a;background:#0e1420}
.sheet-trigger-slot{display:flex;align-items:center;padding:8px 12px;background:#0c1018;border:1px solid #151c28;border-radius:3px;margin-bottom:4px;gap:8px}
.sheet-trigger-label{font-size:10px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:#253545;width:80px;flex-shrink:0}
.sheet-trigger-name{font-size:13px;color:#5a7a90;flex:1}
.sheet-trigger-empty{font-size:12px;color:#253545;font-style:italic;flex:1}
.sheet-trigger-action{background:none;border:none;color:#3a5060;font-size:10px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;cursor:pointer;padding:2px 8px;transition:color .1s;font-family:'Barlow Condensed',sans-serif}
.sheet-trigger-action:hover{color:#6090b8}
.sheet-remove-zone{margin-top:20px;padding-top:16px;border-top:1px solid #151c28}
.sheet-remove-btn{background:none;border:1px solid rgba(200,40,40,.15);border-radius:3px;color:#503030;font-size:11px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;padding:6px 14px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.sheet-remove-btn:hover{background:rgba(200,40,40,.08);color:#904040;border-color:rgba(200,40,40,.3)}
.sheet-inp{background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:6px 9px;color:#7a9ab8;font-family:'JetBrains Mono',monospace;font-size:12px;width:100%;outline:none;transition:border-color .12s}
.sheet-inp:focus{border-color:#284878;color:#a8c8e8}
.sheet-sel{background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:6px 9px;color:#7a9ab8;font-family:'Barlow Condensed',sans-serif;font-size:13px;width:100%;outline:none;cursor:pointer}
.sheet-chkrow{display:flex;align-items:center;gap:8px;cursor:pointer}
.sheet-chk{accent-color:#4a8eff;cursor:pointer;width:14px;height:14px}
.sheet-chklbl{font-size:13px;color:#4060a0;cursor:pointer}
.sheet-ta{resize:vertical;min-height:56px;font-family:'Barlow Condensed',sans-serif !important;font-size:13px !important;line-height:1.5 !important}
.top-nav{display:flex;align-items:stretch;height:40px;background:#080a0d;border-bottom:1px solid #1c2433;flex-shrink:0}
.top-nav-item{display:flex;align-items:center;padding:0 24px;background:none;border:none;cursor:pointer;font-family:'Barlow Condensed',sans-serif;font-size:12px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:#253545;transition:all .12s;position:relative;border-right:1px solid #111820}
.top-nav-item:hover{color:#4a6070;background:#0c0e14}
.top-nav-item.on{color:#7a9ab8}
.top-nav-item.on::after{content:'';position:absolute;bottom:0;left:0;right:0;height:2px;background:#3a7ecc}
.top-nav-spacer{flex:1}
.top-nav-session{display:flex;align-items:center;padding:0 18px;font-size:11px;color:#253a4a;gap:6px}
.top-nav-session-dot{width:6px;height:6px;border-radius:50%;background:#3aca70}
.top-nav-deactivate{display:flex;align-items:center;padding:0 14px;background:none;border:none;border-left:1px solid #111820;cursor:pointer;font-family:'Barlow Condensed',sans-serif;font-size:10px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:#3a4050;transition:color .1s;flex-shrink:0}
.top-nav-deactivate:hover{color:#905050}
.cr-tab-mkr{display:flex;align-items:center;height:100%;padding:0 8px;gap:5px;flex-shrink:0;pointer-events:none}
.cr-tab-mkr-line{width:1px;height:16px;background:#141c28;flex-shrink:0}
.cr-tab-mkr-label{font-size:9px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#1c2a38;white-space:nowrap}
.sess-picker{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px 24px;gap:16px}
.sess-picker-hdr{width:100%;max-width:480px;display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}
.sess-picker-title{font-size:18px;font-weight:700;color:#4a6a80;letter-spacing:.06em;text-transform:uppercase}
.sess-picker-new{background:#0c1520;border:1px dashed #1c2a3a;border-radius:3px;color:#3a5060;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:6px 16px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.sess-picker-new:hover{color:#5a8090;border-color:#2a4060}
.sess-picker-list{width:100%;max-width:480px;display:flex;flex-direction:column;gap:8px}
.sess-picker-item{display:flex;align-items:center;padding:14px 18px;background:#0c1018;border:1px solid #151c28;border-radius:6px;gap:14px;transition:all .12s}
.sess-picker-item:hover{background:#0f1520;border-color:#1c2a3a}
.sess-picker-item-name{font-size:15px;font-weight:600;color:#5a7a90;flex:1}
.sess-picker-item-meta{font-size:11px;color:#253a4a;flex-shrink:0}
.sess-picker-activate{background:#0a2e1a;border:1px solid #1a4a30;border-radius:3px;color:#3aca70;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:6px 16px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s;flex-shrink:0}
.sess-picker-new-row{display:flex;gap:8px;width:100%;max-width:480px}
.sess-picker-inp{flex:1;background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:8px 12px;color:#7a9ab8;font-size:14px;font-family:'Barlow Condensed',sans-serif;outline:none;transition:border-color .12s}
.sess-picker-inp:focus{border-color:#284878}
.sess-picker-inp::placeholder{color:#253545;font-style:italic}
.sess-picker-create{background:#0a2e1a;border:1px solid #1a4a30;border-radius:3px;color:#3aca70;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:0 18px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s;flex-shrink:0}
.sess-picker-create:hover{background:#0f3d22}
.sess-picker-create:disabled{opacity:.35;cursor:default}
.sess-picker-divider{width:100%;max-width:480px;display:flex;align-items:center;gap:10px;margin:4px 0}
.sess-picker-divider-line{flex:1;height:1px;background:#141c28}
.sess-picker-divider-label{font-size:9px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#1e2e3e}
.sess-entry-drag{width:16px;color:#1c2433;font-size:12px;cursor:grab;flex-shrink:0;display:flex;align-items:center;justify-content:center;user-select:none}
.sess-entry-drag:hover{color:#3a5060}
.sess-entry-remove{background:none;border:none;color:#253545;font-size:11px;cursor:pointer;padding:2px 6px;flex-shrink:0;transition:color .1s}
.sess-entry-remove:hover{color:#904040}
.sess-entry-mkr-drag{color:#1c2433;font-size:12px;cursor:grab;flex-shrink:0;user-select:none}
.sess-entry-mkr-drag:hover{color:#3a5060}
.prog-title-inp{background:none;border:none;border-bottom:1px solid transparent;color:#7a9ab8;font-family:'Barlow Condensed',sans-serif;font-size:18px;font-weight:600;letter-spacing:.04em;outline:none;min-width:100px;max-width:260px;transition:border-color .15s;padding:0}
.prog-title-inp:focus{border-bottom-color:#284878}
.prog-add-marker{background:#0c1520;border:1px solid #192c40;border-radius:3px;color:#254060;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:5px 14px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.prog-add-marker:hover{color:#4080b0;border-color:#2a4a60;background:#101828}
.prog-add-stack{background:#111828;border:1px solid #1c2a3a;border-radius:3px;color:#3a4a60;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:5px 14px;cursor:pointer;font-family:'Barlow Condensed',sans-serif;transition:all .1s}
.prog-add-stack:hover{color:#5a7090;border-color:#2a4060;background:#151e2c}
`;


// ═══════════════════════════════════════════════════════════════════════════════
// SUB-COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

function MarkerRow({ name }) {
  return (<div className="cr-mkr"><div className="cr-mkr-line" /><span className="cr-mkr-label">{name}</span><div className="cr-mkr-line" /></div>);
}

function CueRow({ cue, isActive, isStandby, isDone, isTheatre, fadeP, autoP, onClick, editMode, onProgram }) {
  const cls = ['cr-row', isDone && 'done', isActive && 'active', isStandby && !isActive && 'standby'].filter(Boolean).join(' ');
  let statusEl = null;
  if (isDone) statusEl = <span className="st-done">✓</span>;
  else if (isActive && autoP != null) statusEl = <span className="st-auto">●</span>;
  else if (isActive) statusEl = <span className="st-active">▶</span>;
  else if (isStandby) statusEl = <span className="st-standby">◉</span>;
  return (
    <div className={cls} onClick={onClick}>
      {isActive && fadeP > 0 && autoP == null && <div className="cr-fadebar" style={{ width: `${(fadeP*100).toFixed(2)}%` }} />}
      {isActive && autoP != null && <div className="cr-autobar" style={{ width: `${(autoP*100).toFixed(2)}%` }} />}
      <div className="cc-s">{statusEl}</div>
      {isTheatre && <div className="cc-q">{cue.cueNumber ? `Q${cue.cueNumber}` : ''}</div>}
      <div className="cc-n">{cue.name}</div>
      <div className="cc-f">{cue.fadeDurationMs > 0 ? `${fmtMs(cue.fadeDurationMs)} ${fmtCurve(cue.fadeCurve)}` : 'SNAP'}</div>
      <div className="cc-a">{cue.autoAdvance && <span className="auto-pill">AUTO</span>}</div>
      {isTheatre && <div className="cc-notes">{cue.notes || ''}</div>}
      {editMode && <button className="cc-prog-btn" onClick={e => { e.stopPropagation(); onProgram?.(cue); }}>Program</button>}
    </div>
  );
}

// ─── CUE EDIT SHEET (unified: properties + presets + FX + triggers + remove) ──
function CueEditSheet({ cue, onClose, onChange, onDelete, onDuplicate, isActiveCue }) {
  if (!cue) return null;
  const fxCue = cue.fxCueId ? FX_CUES.find(fx => fx.id === cue.fxCueId) : null;
  const cuePresets = fxCue ? fxCue.presets.map(pid => PRESETS.find(p => p.id === pid)).filter(Boolean) : [];
  const activateScript = cue.triggerActivate ? SCRIPTS.find(s => s.id === cue.triggerActivate) : null;
  const deactivateScript = cue.triggerDeactivate ? SCRIPTS.find(s => s.id === cue.triggerDeactivate) : null;
  return (
    <div className="sheet-overlay" onClick={onClose}>
      <div className="sheet-panel" onClick={e => e.stopPropagation()} style={{ height: '75vh' }}>
        <div className="sheet-handle"><div className="sheet-handle-bar" /></div>
        <div className="sheet-hdr">
          <div className="sheet-title">{cue.name}</div>
          {isActiveCue && <span style={{ fontSize: 11, color: '#a07018', fontWeight: 600 }}>▶ Active</span>}
          <button className="sheet-close" onClick={onClose}>Done</button>
        </div>
        <div className="sheet-body">
          <div className="sheet-section">
            <div className="sheet-section-title">Cue Properties</div>
            <div className="sheet-field"><span className="sheet-field-label">Cue Number</span><input className="sheet-inp" value={cue.cueNumber ?? ''} placeholder="e.g. 14A" onChange={e => onChange(cue.id, 'cueNumber', e.target.value || null)} /></div>
            <div className="sheet-field"><span className="sheet-field-label">Fade Duration (ms)</span><input className="sheet-inp" type="number" min={0} step={100} value={cue.fadeDurationMs ?? 0} onChange={e => onChange(cue.id, 'fadeDurationMs', parseInt(e.target.value) || 0)} /></div>
            <div className="sheet-field"><span className="sheet-field-label">Fade Curve</span>
              <select className="sheet-sel" value={cue.fadeCurve ?? 'LINEAR'} onChange={e => onChange(cue.id, 'fadeCurve', e.target.value)}>
                <option value="LINEAR">Linear</option><option value="EASE_IN_OUT">Ease In/Out</option><option value="SINE_IN_OUT">Sine In/Out</option>
                <option value="CUBIC_IN_OUT">Cubic In/Out</option><option value="EASE_IN">Ease In</option><option value="EASE_OUT">Ease Out</option>
              </select>
            </div>
            <div className="sheet-field">
              <label className="sheet-chkrow"><input type="checkbox" className="sheet-chk" checked={!!cue.autoAdvance} onChange={e => onChange(cue.id, 'autoAdvance', e.target.checked)} /><span className="sheet-chklbl">Auto-advance</span></label>
              {cue.autoAdvance && <div style={{marginTop:6}}><span className="sheet-field-label">Delay after fade (ms)</span><input className="sheet-inp" type="number" min={0} step={100} value={cue.autoAdvanceDelayMs ?? 0} style={{marginTop:4}} onChange={e => onChange(cue.id, 'autoAdvanceDelayMs', parseInt(e.target.value) || 0)} /></div>}
            </div>
            <div className="sheet-field"><span className="sheet-field-label">Notes</span><textarea className="sheet-inp sheet-ta" value={cue.notes ?? ''} placeholder="Script note or reference..." onChange={e => onChange(cue.id, 'notes', e.target.value || null)} /></div>
          </div>
          <div className="sheet-section">
            <div className="sheet-section-title">FX Presets</div>
            {cuePresets.length > 0 ? cuePresets.map(p => (
              <div key={p.id} className="sheet-row"><span className="sheet-row-name">{p.name}</span><span className="sheet-row-meta">{p.channels} ch</span><button className="sheet-row-remove" title="Remove preset">✕</button></div>
            )) : <div style={{fontSize:12,color:'#253545',fontStyle:'italic',marginBottom:8}}>No presets assigned</div>}
            <div className="sheet-add-pill">+ Add Preset</div>
          </div>
          <div className="sheet-section">
            <div className="sheet-section-title">FX Effects</div>
            {fxCue ? <div className="sheet-row"><span className="sheet-row-name">{fxCue.name}</span><span className="sheet-row-meta">FX Cue</span><button className="sheet-row-remove" title="Remove effect">✕</button></div>
              : <div style={{fontSize:12,color:'#253545',fontStyle:'italic',marginBottom:8}}>No effects</div>}
            <div className="sheet-add-pill">+ Add Effect</div>
          </div>
          <div className="sheet-section">
            <div className="sheet-section-title">Triggers</div>
            <div style={{fontSize:11,color:'#304050',marginBottom:8}}>Scripts that run when this cue is activated or deactivated.</div>
            <div className="sheet-trigger-slot">
              <span className="sheet-trigger-label">On Activate</span>
              {activateScript ? <><span className="sheet-trigger-name">{activateScript.name}</span><button className="sheet-trigger-action" onClick={() => onChange(cue.id, 'triggerActivate', null)}>Remove</button></> : <><span className="sheet-trigger-empty">None</span><button className="sheet-trigger-action">+ Add</button></>}
            </div>
            <div className="sheet-trigger-slot">
              <span className="sheet-trigger-label">On Deactivate</span>
              {deactivateScript ? <><span className="sheet-trigger-name">{deactivateScript.name}</span><button className="sheet-trigger-action" onClick={() => onChange(cue.id, 'triggerDeactivate', null)}>Remove</button></> : <><span className="sheet-trigger-empty">None</span><button className="sheet-trigger-action">+ Add</button></>}
            </div>
          </div>
          {onDuplicate && <div style={{marginTop:16}}><button className="sheet-add-pill" style={{width:'100%',justifyContent:'center',borderStyle:'solid'}} onClick={() => { onDuplicate(cue); }}>Duplicate Cue</button></div>}
          {onDelete && <div className="sheet-remove-zone"><button className="sheet-remove-btn" onClick={() => { onDelete(cue.id); onClose(); }}>Remove Cue from Stack</button></div>}
        </div>
      </div>
    </div>
  );
}

// (Library Drawer removed — Add Cue creates a blank cue directly)

// ─── SORTABLE SESSION ENTRY ROW (native HTML5 drag-and-drop) ─────────────────
function SessEntryRow({ entry, idx, stacks, onDrill, onRemove, onDragStart, onDragOver, onDrop, dragging }) {
  const isDragging = dragging === entry.id;

  if (entry.type === 'MARKER') {
    return (
      <div
        className="sess-entry-mkr"
        style={{ cursor: 'default', opacity: isDragging ? 0.4 : 1 }}
        draggable
        onDragStart={() => onDragStart(entry.id)}
        onDragOver={e => { e.preventDefault(); onDragOver(entry.id); }}
        onDrop={e => { e.preventDefault(); onDrop(entry.id); }}
      >
        <div className="sess-entry-mkr-drag">⠿</div>
        <div className="sess-entry-mkr-line" />
        <span className="sess-entry-mkr-label">{entry.label}</span>
        <div className="sess-entry-mkr-line" />
        <button className="sess-entry-remove" onClick={() => onRemove(entry.id)}>✕</button>
      </div>
    );
  }

  const s = stacks[entry.stackId];
  const cc = s ? s.cues.filter(c => c.cueType === 'STANDARD').length : 0;
  return (
    <div
      className="sess-entry"
      style={{ opacity: isDragging ? 0.4 : 1 }}
      draggable
      onDragStart={() => onDragStart(entry.id)}
      onDragOver={e => { e.preventDefault(); onDragOver(entry.id); }}
      onDrop={e => { e.preventDefault(); onDrop(entry.id); }}
      onClick={() => onDrill(entry.stackId)}
    >
      <div className="sess-entry-drag" onClick={e => e.stopPropagation()}>⠿</div>
      <span className="sess-entry-num">{idx + 1}</span>
      <span className="sess-entry-name">{entry.stackName}</span>
      <span className="sess-entry-meta">{cc} cues · {s?.loop ? 'Loop' : 'Sequential'}</span>
      <button className="sess-entry-remove" onClick={e => { e.stopPropagation(); onRemove(entry.id); }}>✕</button>
      <span style={{ color: '#253a50', fontSize: 16 }}>→</span>
    </div>
  );
}

// ─── STACK PICKER OVERLAY ─────────────────────────────────────────────────────
function StackPickerOverlay({ stacks, session, onAdd, onClose }) {
  const addedIds = new Set(session.entries.filter(e => e.type === 'STACK').map(e => e.stackId));
  return (
    <div className="lib-overlay" onClick={onClose}>
      <div className="lib-drawer" onClick={e => e.stopPropagation()}>
        <div className="lib-hdr">
          <span className="lib-title">Add Stack to Session</span>
          <button className="lib-close" onClick={onClose}>✕</button>
        </div>
        <div className="lib-list">
          {Object.values(stacks).map(s => {
            const cc = s.cues.filter(c => c.cueType === 'STANDARD').length;
            const added = addedIds.has(s.id);
            return (
              <div key={s.id} className="lib-item">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="lib-item-name">{s.name}</div>
                  <div className="lib-item-presets">{cc} cues · {s.loop ? 'Loop' : 'Sequential'} · {s.context}</div>
                </div>
                <button
                  className="lib-item-add"
                  style={added ? { opacity: 0.4, cursor: 'default' } : {}}
                  onClick={() => { if (!added) onAdd(s); }}
                >{added ? 'Added' : '+ Add'}</button>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════════════════════
export default function ShowModeV2() {
  const [view, setView] = useState('program');
  const [stacks, setStacks] = useState(INIT_STACKS);
  const [sessions, setSessions] = useState(SESSIONS);
  const [activeSessionId, setActiveSessionId] = useState(INIT_SESSION.id);
  const [session, setSession] = useState(INIT_SESSION);
  const [activeEntryId, setActiveEntryId] = useState(() => INIT_SESSION.entries.find(e => e.type === 'STACK')?.id ?? null);
  // stackId derived — null when session has no active entry (blank session)
  const activeEntry = useMemo(() => session.entries.find(e => e.id === activeEntryId), [session, activeEntryId]);
  const stackId = activeEntry?.stackId ?? null;
  const [showStackPicker, setShowStackPicker] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [editMode, setEditMode] = useState(false);
  const [progDrillStackId, setProgDrillStackId] = useState(null);
  const [progEditSheetCueId, setProgEditSheetCueId] = useState(null);
  const [completedIds, setCompleted] = useState(new Set());
  const [activeCueId, setActive] = useState(null);
  const [standbyCueId, setStandby] = useState(() => firstStd(INIT_STACKS[1].cues));
  const [fadeP, setFadeP] = useState(0);
  const [autoP, setAutoP] = useState(null);
  const [bpm, setBpm] = useState(null);
  const [dbo, setDbo] = useState(false);
  const [tapTimes, setTapTimes] = useState([]);
  const [oooDismissed, setOooDismiss] = useState(false);
  const [ctxOverride, setCtxOverride] = useState({});
  const [runEditSheetCueId, setRunEditSheetCueId] = useState(null);
  const listScrollRef = useRef(null);
  const savedScrollPos = useRef(0);
  const animR = useRef(null); const autoR = useRef(null); const goFn = useRef(null);
  const activeR = useRef(null); const standbyR = useRef(null);
  const stackIdR = useRef(stackId); const stacksR = useRef(stacks);
  const activeEntryIdR = useRef(activeEntryId); const sessionR = useRef(session);
  useEffect(() => { activeR.current = activeCueId; }, [activeCueId]);
  useEffect(() => { standbyR.current = standbyCueId; }, [standbyCueId]);
  useEffect(() => { stackIdR.current = stackId; }, [stackId]);
  useEffect(() => { stacksR.current = stacks; }, [stacks]);
  useEffect(() => { activeEntryIdR.current = activeEntryId; }, [activeEntryId]);
  useEffect(() => { sessionR.current = session; }, [session]);
  const cancelAll = useCallback(() => { if (animR.current) { cancelAnimationFrame(animR.current); animR.current = null; } if (autoR.current) { cancelAnimationFrame(autoR.current); autoR.current = null; } }, []);
  const markDone = useCallback(id => setCompleted(p => new Set([...p, id])), []);
  const getCue = useCallback(id => stacksR.current[stackIdR.current]?.cues.find(c => c.id === id) ?? null, []);
  const startAuto = useCallback((cue, cueId) => {
    const delay = cue.autoAdvanceDelayMs ?? 0;
    const finish = () => { setAutoP(null); markDone(cueId); setActive(null); setFadeP(0); goFn.current?.(); };
    if (delay <= 0) { setTimeout(finish, 32); return; }
    const t0 = performance.now();
    const tick = t => { const p = Math.min((t - t0) / delay, 1); setAutoP(p); if (p < 1) autoR.current = requestAnimationFrame(tick); else { autoR.current = null; finish(); } };
    autoR.current = requestAnimationFrame(tick);
  }, [markDone]);
  const go = useCallback(() => {
    const sid = standbyR.current;
    if (!sid) {
      // Boundary: find the next STACK entry after the current one and advance
      const curIdx = sessionR.current.entries.findIndex(e => e.id === activeEntryIdR.current);
      const nextEntry = sessionR.current.entries.slice(curIdx + 1).find(e => e.type === 'STACK');
      if (nextEntry) {
        cancelAll();
        setActiveEntryId(nextEntry.id);
        setCompleted(new Set());
        setActive(null); setFadeP(0); setAutoP(null);
        setStandby(firstStd(stacksR.current[nextEntry.stackId]?.cues || []));
        setOooDismiss(false);
      }
      return;
    }
    const cue = getCue(sid); if (!cue) return;
    cancelAll(); if (activeR.current) markDone(activeR.current);
    setActive(sid); setFadeP(0); setAutoP(null);
    const s = stacksR.current[stackIdR.current]; setStandby(nextStd(s.cues, sid, s.loop));
    const dur = cue.fadeDurationMs ?? 0;
    if (dur > 0) {
      const t0 = performance.now();
      const tick = t => { const p = Math.min((t - t0) / dur, 1); setFadeP(p); if (p < 1) animR.current = requestAnimationFrame(tick); else { animR.current = null; setFadeP(0); if (cue.autoAdvance) startAuto(cue, sid); else { markDone(sid); setActive(null); } } };
      animR.current = requestAnimationFrame(tick);
    } else { if (cue.autoAdvance) startAuto(cue, sid); else { markDone(sid); setActive(null); } }
  }, [getCue, cancelAll, markDone, startAuto]);
  useEffect(() => { goFn.current = go; }, [go]);
  const handleBack = useCallback(() => {
    cancelAll(); setFadeP(0); setAutoP(null);
    const prev = activeR.current; const s = stacksR.current[stackIdR.current]; const curSB = standbyR.current;
    setActive(null);
    if (prev) { setStandby(prev); setCompleted(p => { const n = new Set(p); n.delete(prev); return n; }); }
    else { const p = prevStd(s.cues, curSB); if (p) { setStandby(p); setCompleted(prev => { const n = new Set(prev); n.delete(p); return n; }); } }
  }, [cancelAll]);
  const switchToEntry = useCallback((entry) => {
    cancelAll(); setActiveEntryId(entry.id); setCompleted(new Set()); setActive(null); setFadeP(0); setAutoP(null);
    setStandby(firstStd(stacks[entry.stackId]?.cues || [])); setOooDismiss(false);
  }, [cancelAll, stacks]);
  const switchStack = useCallback(id => {
    const entry = sessionR.current.entries.find(e => e.type === 'STACK' && e.stackId === id);
    if (entry) { cancelAll(); setActiveEntryId(entry.id); setCompleted(new Set()); setActive(null); setFadeP(0); setAutoP(null); setStandby(firstStd(stacks[id]?.cues || [])); setOooDismiss(false); }
  }, [cancelAll, stacks]);
  useEffect(() => {
    const onKey = e => { if (['INPUT','TEXTAREA','SELECT'].includes(e.target.tagName)) return; if (view !== 'run') return; if (e.code === 'Space') { e.preventDefault(); go(); } if (e.code === 'ArrowLeft') { e.preventDefault(); handleBack(); } };
    window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey);
  }, [go, handleBack, view]);
  const handleTap = useCallback(() => { const now = Date.now(); setTapTimes(prev => { const r = [...prev, now].filter(t => now - t < 5000).slice(-8); if (r.length >= 2) { const d = r.slice(1).map((t, i) => t - r[i]); setBpm(Math.round(60000 / (d.reduce((a, b) => a + b) / d.length))); } return r; }); }, []);
  const updateCueIn = useCallback((cueId, field, value, sid) => { const t = sid || stackIdR.current; setStacks(p => { const s = p[t]; return { ...p, [t]: { ...s, cues: s.cues.map(c => c.id === cueId ? { ...c, [field]: value } : c) } }; }); }, []);
  const removeCue = useCallback((cueId, sid) => { const t = sid || stackIdR.current; setStacks(p => { const s = p[t]; return { ...p, [t]: { ...s, cues: s.cues.filter(c => c.id !== cueId) } }; }); if (cueId === progEditSheetCueId) setProgEditSheetCueId(null); if (cueId === runEditSheetCueId) setRunEditSheetCueId(null); }, [progEditSheetCueId, runEditSheetCueId]);
  const fixOrder = useCallback(() => { setStacks(p => { const s = p[stackIdR.current]; return { ...p, [stackIdR.current]: { ...s, cues: sortByCueNum(s.cues) } }; }); setOooDismiss(false); }, []);
  const addMarker = useCallback((tid) => { const sid = tid || progDrillStackId; if (!sid) return; setStacks(p => { const s = p[sid]; const mx = Math.max(0, ...s.cues.map(c => c.sortOrder)); return { ...p, [sid]: { ...s, cues: [...s.cues, { id: ++nextCueId, sortOrder: mx+1, cueNumber: null, cueType: 'MARKER', name: 'New Section', fadeDurationMs: null, fadeCurve: null, autoAdvance: false, autoAdvanceDelayMs: null, notes: null }] } }; }); }, [progDrillStackId]);
  const createBlankCue = useCallback((targetStackId) => { const sid = targetStackId || progDrillStackId; if (!sid) return; const newId = ++nextCueId; setStacks(p => { const s = p[sid]; const mx = Math.max(0, ...s.cues.map(c => c.sortOrder)); return { ...p, [sid]: { ...s, cues: [...s.cues, { id: newId, sortOrder: mx+1, cueNumber: null, cueType: 'STANDARD', name: 'New Cue', fadeDurationMs: 3000, fadeCurve: 'LINEAR', autoAdvance: false, autoAdvanceDelayMs: null, notes: null, fxCueId: null, triggerActivate: null, triggerDeactivate: null }] } }; }); setProgEditSheetCueId(newId); }, [progDrillStackId]);
  const duplicateCue = useCallback((sourceCue, targetStackId) => { const sid = targetStackId || progDrillStackId || stackIdR.current; if (!sid) return; const newId = ++nextCueId; setStacks(p => { const s = p[sid]; const mx = Math.max(0, ...s.cues.map(c => c.sortOrder)); return { ...p, [sid]: { ...s, cues: [...s.cues, { ...sourceCue, id: newId, sortOrder: mx+1, cueNumber: null, name: sourceCue.name + ' (copy)' }] } }; }); setProgEditSheetCueId(newId); setRunEditSheetCueId(null); }, [progDrillStackId]);
  const openRunEditSheet = useCallback(cue => { if (listScrollRef.current) savedScrollPos.current = listScrollRef.current.scrollTop; setRunEditSheetCueId(cue.id); }, []);
  const closeRunEditSheet = useCallback(() => { setRunEditSheetCueId(null); requestAnimationFrame(() => { if (listScrollRef.current) listScrollRef.current.scrollTop = savedScrollPos.current; }); }, []);
  // Session management (Program tab)
  const removeEntry = useCallback(id => setSession(s => ({ ...s, entries: s.entries.filter(e => e.id !== id) })), []);
  const addSessionMarker = useCallback(() => setSession(s => ({ ...s, entries: [...s.entries, { id: `e${++nextEntryId}`, type: 'MARKER', label: 'New Marker' }] })), []);
  // Native HTML5 drag-and-drop for session entry reorder
  const [draggingEntryId, setDraggingEntryId] = useState(null);
  const dragOverId = useRef(null);
  const handleEntryDragStart = useCallback(id => { setDraggingEntryId(id); dragOverId.current = id; }, []);
  const handleEntryDragOver = useCallback(id => { dragOverId.current = id; }, []);
  const handleEntryDrop = useCallback(targetId => {
    setDraggingEntryId(null);
    const sourceId = draggingEntryId;
    if (!sourceId || sourceId === targetId) return;
    setSession(s => {
      const entries = [...s.entries];
      const fi = entries.findIndex(e => e.id === sourceId);
      const ti = entries.findIndex(e => e.id === targetId);
      const [moved] = entries.splice(fi, 1);
      entries.splice(ti, 0, moved);
      return { ...s, entries };
    });
  }, [draggingEntryId]);
  const activateSession = useCallback(id => {
    const sess = SESSIONS.find(s => s.id === id); if (!sess) return;
    const firstEntry = sess.entries.find(e => e.type === 'STACK');
    setActiveSessionId(id); setSession(sess);
    if (firstEntry) {
      cancelAll(); setActiveEntryId(firstEntry.id); setCompleted(new Set()); setActive(null); setFadeP(0); setAutoP(null);
      setStandby(firstStd(stacks[firstEntry.stackId]?.cues || [])); setOooDismiss(false);
    }
  }, [cancelAll, stacks]);
  // New session: prompt for name, create blank, activate
  const handleNewSession = useCallback(() => {
    const name = newSessionName.trim();
    if (!name) return;
    const newSess = { id: `sess_${Date.now()}`, name, entries: [] };
    setSessions(prev => [...prev, newSess]);
    setActiveSessionId(newSess.id);
    setSession(newSess);
    cancelAll();
    setActiveEntryId(null);
    setCompleted(new Set());
    setActive(null); setFadeP(0); setAutoP(null); setStandby(null);
    setOooDismiss(false);
    setNewSessionName('');
    setView('program');
  }, [cancelAll, newSessionName]);
  // Add a stack to the current session; auto-activates if it's the first
  const addStackToSession = useCallback(s => {
    const entry = { id: `e${++nextEntryId}`, type: 'STACK', stackId: s.id, stackName: s.name };
    setSession(prev => ({ ...prev, entries: [...prev.entries, entry] }));
    // If no active entry yet, make this the active one and seed standby
    setActiveEntryId(prev => prev !== null ? prev : entry.id);
    setStandby(prev => prev !== null ? prev : (firstStd(stacks[s.id]?.cues || [])));
  }, [stacks]);
  // Safety: if the active entry was removed from the session, reset to the next available stack
  useEffect(() => {
    if (activeEntryId && !session.entries.find(e => e.id === activeEntryId)) {
      const fallback = session.entries.find(e => e.type === 'STACK');
      setActiveEntryId(fallback?.id ?? null);
      setStandby(fallback ? firstStd(stacks[fallback.stackId]?.cues || []) : null);
    }
  }, [session.entries, activeEntryId, stacks]);
  useEffect(() => () => cancelAll(), [cancelAll]);

  const stack = stacks[stackId]; const baseCtx = stack?.context || 'theatre';
  const isTheatre = (ctxOverride[stackId] ?? baseCtx) === 'theatre';
  const ooo = isTheatre && !oooDismissed && stack && detectOOO(stack.cues);
  const activeName = activeCueId ? stack?.cues.find(c => c.id === activeCueId)?.name : null;
  const standbyName = standbyCueId ? stack?.cues.find(c => c.id === standbyCueId)?.name : null;
  // Next STACK entry after current (for boundary hint and GO advance)
  const nextEntry = useMemo(() => {
    if (standbyCueId) return null;
    const curIdx = session.entries.findIndex(e => e.id === activeEntryId);
    return session.entries.slice(curIdx + 1).find(e => e.type === 'STACK') ?? null;
  }, [standbyCueId, session, activeEntryId]);
  const progStack = progDrillStackId ? stacks[progDrillStackId] : null;
  const progEditSheetCue = progEditSheetCueId && progStack ? progStack.cues.find(c => c.id === progEditSheetCueId) : null;
  const runEditSheetCue = runEditSheetCueId && stack ? stack.cues.find(c => c.id === runEditSheetCueId) : null;
  const toggleCtx = val => setCtxOverride(p => ({ ...p, [stackId]: val }));
  const totalCues = useMemo(() => { let n = 0; session.entries.forEach(e => { if (e.type === 'STACK' && stacks[e.stackId]) n += stacks[e.stackId].cues.filter(c => c.cueType === 'STANDARD').length; }); return n; }, [session, stacks]);

  return (
    <div className="cr">
      <style>{CSS}</style>
      <div className="top-nav">
        <button className={`top-nav-item${view === 'program' ? ' on' : ''}`} onClick={() => setView('program')}>Program</button>
        <button className={`top-nav-item${view === 'run' ? ' on' : ''}`} onClick={() => setView('run')}>Show</button>
        <div className="top-nav-spacer" />
        {activeSessionId && <button className="top-nav-deactivate" onClick={() => setActiveSessionId(null)}>Deactivate</button>}
        <div className="top-nav-session"><div className="top-nav-session-dot" style={{ background: activeSessionId ? '#3aca70' : '#905050' }} />{session.name}</div>
      </div>

      {!activeSessionId ? (
        <div className="sess-picker">
          <div className="sess-picker-hdr">
            <span className="sess-picker-title">Choose Session</span>
          </div>
          <div className="sess-picker-new-row">
            <input
              className="sess-picker-inp"
              placeholder="New session name…"
              value={newSessionName}
              onChange={e => setNewSessionName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleNewSession()}
              autoFocus
            />
            <button
              className="sess-picker-create"
              onClick={handleNewSession}
              disabled={!newSessionName.trim()}
            >Create</button>
          </div>
          {sessions.length > 0 && <>
            <div className="sess-picker-divider">
              <div className="sess-picker-divider-line" />
              <span className="sess-picker-divider-label">or resume existing</span>
              <div className="sess-picker-divider-line" />
            </div>
            <div className="sess-picker-list">
              {sessions.map(s => {
                const stackCount = s.entries.filter(e => e.type === 'STACK').length;
                return (
                  <div key={s.id} className="sess-picker-item">
                    <span className="sess-picker-item-name">{s.name}</span>
                    <span className="sess-picker-item-meta">{stackCount} stack{stackCount !== 1 ? 's' : ''}</span>
                    <button className="sess-picker-activate" onClick={() => activateSession(s.id)}>Activate</button>
                  </div>
                );
              })}
            </div>
          </>}
        </div>
      ) : (<>

        {view === 'program' && (
          <div className="prog">
            <div className="prog-topbar">
              <input
                className="prog-title-inp"
                value={session.name}
                onChange={e => setSession(s => ({ ...s, name: e.target.value }))}
                onClick={e => e.stopPropagation()}
              />
              <span className="prog-subtitle">{session.entries.filter(e => e.type === 'STACK').length} stacks · {totalCues} cues</span>
              <button className="prog-add-marker" onClick={addSessionMarker}>+ Add Marker</button>
              <button className="prog-add-stack" onClick={() => setShowStackPicker(true)}>+ Add Stack</button>
              <button className="prog-ready" onClick={() => setView('run')}>Ready to run →</button>
            </div>
            {!progDrillStackId ? (
              <div className="sess-list" onDragEnd={() => setDraggingEntryId(null)}>
                {session.entries.map((entry, idx) => (
                  <SessEntryRow
                    key={entry.id}
                    entry={entry}
                    idx={idx}
                    stacks={stacks}
                    onDrill={id => setProgDrillStackId(id)}
                    onRemove={removeEntry}
                    onDragStart={handleEntryDragStart}
                    onDragOver={handleEntryDragOver}
                    onDrop={handleEntryDrop}
                    dragging={draggingEntryId}
                  />
                ))}
              </div>
            ) : (
              <div className="prog-stack">
                <div className="prog-stack-hdr">
                  <button className="prog-back" onClick={() => { setProgDrillStackId(null); setProgEditSheetCueId(null); }}>← Session</button>
                  <span className="prog-stack-name">{progStack?.name}</span>
                  <span className="prog-stack-cues">{progStack?.cues.filter(c => c.cueType === 'STANDARD').length} cues</span>
                  <button className="prog-add-btn" onClick={() => createBlankCue(progDrillStackId)}>+ Add Cue</button>
                  <button className="prog-add-sep" onClick={() => addMarker(progDrillStackId)}>+ Separator</button>
                </div>
                <div className="cr-hdr"><div style={{width:16}}></div><div style={{width:44,paddingLeft:8}}>Q</div><div style={{flex:1}}>Name</div><div style={{width:80,textAlign:'right',paddingRight:8}}>Fade</div><div style={{width:24}}></div></div>
                <div className="cr-list">
                  {progStack?.cues.map(cue => {
                    if (cue.cueType === 'MARKER') return (<div key={cue.id} className="prog-mkr"><div className="cr-mkr-line" /><input className="prog-mkr-inp" value={cue.name} onChange={e => updateCueIn(cue.id, 'name', e.target.value, progDrillStackId)} onClick={e => e.stopPropagation()} /><div className="cr-mkr-line" /><button className="prog-mkr-del" onClick={() => removeCue(cue.id, progDrillStackId)}>✕</button></div>);
                    return (<div key={cue.id} className="prog-cue-row" onClick={() => setProgEditSheetCueId(cue.id)}><div className="prog-cue-drag" title="Drag to reorder (not wired in prototype)">⠿</div><div className="prog-cue-num">{cue.cueNumber ? `Q${cue.cueNumber}` : '—'}</div><div className="prog-cue-name">{cue.name}</div><div className="prog-cue-fade">{cue.fadeDurationMs > 0 ? `${fmtMs(cue.fadeDurationMs)} ${fmtCurve(cue.fadeCurve)}` : 'SNAP'}</div><div className="prog-cue-edit-icon">✏</div></div>);
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {view === 'run' && stack && (<>
          <div className="cr-bar">
            <div className="cr-bar-sec"><div><div className="cr-lbl" style={{marginBottom:4}}>Blackout</div><button className={`cr-btn btn-dbo${dbo?' on':''}`} onClick={() => { cancelAll(); setDbo(d => !d); setActive(null); setFadeP(0); setAutoP(null); }}>DBO</button></div></div>
            <div className="cr-bar-sec"><div><div className="cr-lbl">BPM</div><div className="cr-bpm">{bpm ?? '—'}</div></div><button className="cr-btn btn-tap" onClick={handleTap}>TAP</button></div>
            <div className="cr-bar-sec"><div><div className="cr-lbl" style={{marginBottom:4}}>Edit</div><button className={`cr-btn btn-edit-mode${editMode?' on':''}`} onClick={() => setEditMode(e => !e)}>{editMode ? 'EDIT ON' : 'EDIT'}</button></div></div>
            <div className="cr-bar-sec grow">
              {activeName ? (
                <div className="cr-cuinfo">
                  <div className="cr-cuinfo-active">▶ {activeName}</div>
                  {standbyName
                    ? <div className="cr-cuinfo-standby">◉ next — {standbyName}</div>
                    : nextEntry && <div className="cr-cuinfo-standby">→ {nextEntry.stackName}</div>
                  }
                </div>
              ) : (
                <div className="cr-cuinfo">
                  <div className="cr-cuinfo-idle">
                    {standbyName
                      ? `${stack.name} · ◉ ${standbyName}`
                      : nextEntry
                        ? `${stack.name} · → ${nextEntry.stackName}`
                        : `${stack.name} · end of stack`
                    }
                  </div>
                </div>
              )}
            </div>
            <div className="cr-kbhints"><span className="cr-kbhint">← back</span><span className="cr-kbhint">space: go</span></div>
            <button className="cr-btn btn-back" onClick={handleBack} style={{height:'100%',borderRadius:0,borderRight:'1px solid #1c2433'}}>◀ BACK</button>
            <button className="cr-btn btn-go" onClick={go}>GO</button>
          </div>
          <div className="cr-body"><div className="cr-runner">
            <div className="cr-tabs">
              {session.entries.map(entry => {
                if (entry.type === 'MARKER') {
                  return (
                    <div key={entry.id} className="cr-tab-mkr">
                      <div className="cr-tab-mkr-line" />
                      <span className="cr-tab-mkr-label">{entry.label}</span>
                      <div className="cr-tab-mkr-line" />
                    </div>
                  );
                }
                const s = stacks[entry.stackId];
                return (
                  <button key={entry.id} className={`cr-tab${entry.id === activeEntryId ? ' on' : ''}`} onClick={() => switchToEntry(entry)}>
                    {entry.stackName}{s?.loop && <span className="cr-loop">↻</span>}
                  </button>
                );
              })}
              <div className="cr-tabs-spacer" />
              <div className="cr-ctx-wrap"><button className={`cr-ctx-btn${isTheatre?' on':''}`} onClick={() => toggleCtx('theatre')}>Theatre</button><button className={`cr-ctx-btn${!isTheatre?' on':''}`} onClick={() => toggleCtx('band')}>Band</button></div>
            </div>
            {editMode && ooo && (<div className="cr-ooo"><span>⚠</span><span>Cue numbers are out of order.</span><button className="cr-ooo-btn" onClick={fixOrder}>Fix Order</button><button className="cr-ooo-btn" onClick={() => setOooDismiss(true)}>Dismiss</button></div>)}
            <div className="cr-hdr"><div style={{width:20}}></div>{isTheatre && <div style={{width:48}}>Q</div>}<div style={{flex:1}}>Name</div><div style={{width:86,textAlign:'right',paddingRight:8}}>Fade</div><div style={{width:36}}></div>{isTheatre && <div style={{width:200,paddingLeft:12}}>Note</div>}{editMode && <div style={{width:60}}></div>}</div>
            <div className="cr-list" ref={listScrollRef}>
              {stack.cues.map(cue => {
                if (cue.cueType === 'MARKER') return <MarkerRow key={cue.id} name={cue.name} />;
                return (<CueRow key={cue.id} cue={cue} isActive={cue.id===activeCueId} isStandby={cue.id===standbyCueId} isDone={completedIds.has(cue.id)} isTheatre={isTheatre} fadeP={cue.id===activeCueId?fadeP:0} autoP={cue.id===activeCueId?autoP:null} onClick={() => {}} editMode={editMode} onProgram={openRunEditSheet} />);
              })}
            </div>
          </div></div>
        </>)}

        {progEditSheetCue && <CueEditSheet cue={progEditSheetCue} onClose={() => setProgEditSheetCueId(null)} onChange={(id,f,v) => updateCueIn(id,f,v,progDrillStackId)} onDelete={id => removeCue(id, progDrillStackId)} onDuplicate={cue => duplicateCue(cue, progDrillStackId)} isActiveCue={false} />}
        {runEditSheetCue && <CueEditSheet cue={runEditSheetCue} onClose={closeRunEditSheet} onChange={(id,f,v) => updateCueIn(id,f,v)} onDelete={id => removeCue(id)} onDuplicate={cue => duplicateCue(cue)} isActiveCue={runEditSheetCueId === activeCueId} />}
        {showStackPicker && <StackPickerOverlay stacks={stacks} session={session} onAdd={s => addStackToSession(s)} onClose={() => setShowStackPicker(false)} />}
      </>)}
    </div>
  );
}
