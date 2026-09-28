import { useState, useEffect, useCallback, useRef } from "react";

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
// Act 1: cue "5" at sortOrder 5, "4A" at sortOrder 6 → triggers OOO banner
const INIT = {
  1: {
    id: 1, name: "Act 1", context: "theatre", loop: false,
    cues: [
      { id:1,  sortOrder:1,  cueNumber:"1",  cueType:"STANDARD", name:"Pre-show preset",  fadeDurationMs:3000, fadeCurve:"EASE_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"House open look" },
      { id:2,  sortOrder:2,  cueNumber:"2",  cueType:"STANDARD", name:"House to half",    fadeDurationMs:5000, fadeCurve:"LINEAR",       autoAdvance:true,  autoAdvanceDelayMs:2000, notes:"SM: 'House to half'" },
      { id:3,  sortOrder:3,  cueNumber:null, cueType:"MARKER",   name:"Scene 1 – The Study", fadeDurationMs:null, fadeCurve:null, autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:4,  sortOrder:4,  cueNumber:"3",  cueType:"STANDARD", name:"Study pre-set",    fadeDurationMs:0,    fadeCurve:"LINEAR",       autoAdvance:false, autoAdvanceDelayMs:null, notes:"Snap — desk lamp only" },
      { id:5,  sortOrder:5,  cueNumber:"5",  cueType:"STANDARD", name:"Morning light",    fadeDurationMs:8000, fadeCurve:"EASE_OUT",     autoAdvance:false, autoAdvanceDelayMs:null, notes:"p12 — 'The sun rose slowly'" },
      { id:6,  sortOrder:6,  cueNumber:"4A", cueType:"STANDARD", name:"Phone flash",      fadeDurationMs:0,    fadeCurve:"LINEAR",       autoAdvance:true,  autoAdvanceDelayMs:1500, notes:"Snap + auto p14" },
      { id:7,  sortOrder:7,  cueNumber:null, cueType:"MARKER",   name:"Scene 2 – The Garden", fadeDurationMs:null, fadeCurve:null, autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:8,  sortOrder:8,  cueNumber:"6",  cueType:"STANDARD", name:"Garden afternoon", fadeDurationMs:4000, fadeCurve:"CUBIC_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:"Bright, open air" },
      { id:9,  sortOrder:9,  cueNumber:"7",  cueType:"STANDARD", name:"Sunset build",     fadeDurationMs:6000, fadeCurve:"EASE_IN_OUT",  autoAdvance:false, autoAdvanceDelayMs:null, notes:"p28 — long cross-fade" },
      { id:10, sortOrder:10, cueNumber:"8",  cueType:"STANDARD", name:"Night",            fadeDurationMs:3000, fadeCurve:"SINE_IN_OUT",  autoAdvance:false, autoAdvanceDelayMs:null, notes:"End of act 1" },
    ]
  },
  2: {
    id: 2, name: "Electric Feel", context: "band", loop: true,
    cues: [
      { id:101, sortOrder:1, cueNumber:null, cueType:"STANDARD", name:"Intro strobe burst", fadeDurationMs:0,    fadeCurve:"LINEAR",       autoAdvance:true,  autoAdvanceDelayMs:2000, notes:null },
      { id:102, sortOrder:2, cueNumber:null, cueType:"STANDARD", name:"Verse wash",         fadeDurationMs:1000, fadeCurve:"EASE_IN_OUT",  autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:103, sortOrder:3, cueNumber:null, cueType:"STANDARD", name:"Chorus chase",       fadeDurationMs:500,  fadeCurve:"LINEAR",       autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:104, sortOrder:4, cueNumber:null, cueType:"STANDARD", name:"Bridge build",       fadeDurationMs:2000, fadeCurve:"CUBIC_IN_OUT", autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
      { id:105, sortOrder:5, cueNumber:null, cueType:"STANDARD", name:"Outro full",         fadeDurationMs:3000, fadeCurve:"EASE_OUT",     autoAdvance:false, autoAdvanceDelayMs:null, notes:null },
    ]
  }
};

// ─── HELPERS ──────────────────────────────────────────────────────────────────
const firstStd = cues => cues.find(c => c.cueType === 'STANDARD')?.id ?? null;
function nextStd(cues, id, loop) {
  const i = cues.findIndex(c => c.id === id);
  for (let j = i + 1; j < cues.length; j++) if (cues[j].cueType === 'STANDARD') return cues[j].id;
  if (loop) for (let j = 0; j < i; j++) if (cues[j].cueType === 'STANDARD') return cues[j].id;
  return null;
}
function prevStd(cues, id) {
  const i = cues.findIndex(c => c.id === id);
  for (let j = i - 1; j >= 0; j--) if (cues[j].cueType === 'STANDARD') return cues[j].id;
  return null;
}
const fmtMs = ms => !ms ? "SNAP" : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
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
.btn-tap:active{transform:scale(.97)}
.btn-back{height:100%;padding:0 22px;font-size:13px;background:none;color:#324050;border:none;border-right:1px solid #1c2433;border-radius:0;transition:background .1s,color .1s;letter-spacing:.08em}
.btn-back:hover{background:#0f1820;color:#6090b8}
.btn-go{height:100%;padding:0 42px;font-size:24px;letter-spacing:.16em;background:#0a2e1a;color:#3aca70;border:none;border-radius:0;transition:background .1s,box-shadow .1s}
.btn-go:hover{background:#0f3d22;box-shadow:inset 0 0 24px rgba(74,222,128,.12)}
.btn-go:active{background:#145c30}

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

.cr-ooo{display:flex;align-items:center;gap:10px;padding:6px 16px;background:rgba(200,150,0,.07);border-bottom:1px solid rgba(200,150,0,.18);font-size:12px;color:#906a10;flex-shrink:0;letter-spacing:.02em}
.cr-ooo-btn{font-family:'Barlow Condensed',sans-serif;font-size:10px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;background:rgba(200,150,0,.1);border:1px solid rgba(200,150,0,.28);color:#b08020;padding:2px 10px;border-radius:2px;cursor:pointer;transition:background .1s}
.cr-ooo-btn:hover{background:rgba(200,150,0,.2)}

.cr-hdr{display:flex;align-items:center;height:24px;padding:0 0 0 19px;border-bottom:1px solid #111820;background:#0b0e13;flex-shrink:0}
.cr-hdr>div{font-size:9px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#1e2e3e}

.cr-list{flex:1;overflow-y:auto;padding:2px 0}
.cr-list::-webkit-scrollbar{width:4px}
.cr-list::-webkit-scrollbar-track{background:#090b0e}
.cr-list::-webkit-scrollbar-thumb{background:#1c2433;border-radius:2px}
.cr-list::-webkit-scrollbar-thumb:hover{background:#263650}

.cr-mkr{display:flex;align-items:center;gap:10px;padding:9px 14px}
.cr-mkr-line{flex:1;height:1px;background:#141c28}
.cr-mkr-label{font-size:10px;font-weight:700;letter-spacing:.13em;text-transform:uppercase;color:#254060;padding:2px 9px;white-space:nowrap;border:1px solid #192c40;border-radius:2px;background:#0c1520}

.cr-row{position:relative;display:flex;align-items:center;height:36px;padding:0 0 0 16px;border-left:3px solid transparent;cursor:pointer;overflow:hidden;transition:background .08s}
.cr-row:hover{background:#0e1218}
.cr-row.done{opacity:.36}
.cr-row.active{border-left-color:#d4901e;background:rgba(212,144,30,.055)}
.cr-row.standby{border-left-color:#3ab868;background:rgba(58,184,104,.04)}
.cr-row.editing{border-left-color:#4880c8;background:rgba(72,128,200,.05)}
.cr-row.active.editing{border-left-color:#d4901e}

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
.cc-edit{width:30px;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:13px;color:#141c28;transition:color .1s}
.cr-row:hover .cc-edit{color:#304050}
.cr-row.editing .cc-edit{color:#4880c8}

.st-done{color:#1a3828;font-size:12px}
.st-active{color:#d4901e;font-size:15px}
.st-standby{color:#3ab868;font-size:12px}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.3}}
.st-auto{color:#5890f0;font-size:10px;animation:blink 1s ease-in-out infinite}
.auto-pill{display:inline-flex;align-items:center;background:rgba(88,144,240,.1);color:#3a6ab0;font-size:9px;font-weight:700;letter-spacing:.05em;padding:1px 5px;border-radius:2px;border:1px solid rgba(88,144,240,.18)}

.cr-ed{width:50px;flex-shrink:0;background:#0b0e13;border-left:1px solid #1c2433;display:flex;overflow:hidden;transition:width .24s cubic-bezier(.16,1,.3,1)}
.cr-ed.open{width:300px}
.cr-ed-strip{width:50px;min-width:50px;flex-shrink:0;display:flex;flex-direction:column;align-items:center;padding:10px 0;gap:6px;border-right:1px solid #1c2433}
.cr-ed-sbtn{width:34px;height:34px;display:flex;align-items:center;justify-content:center;border:1px solid transparent;border-radius:4px;cursor:pointer;background:none;color:#253545;font-size:14px;transition:all .1s;font-family:'Barlow Condensed',sans-serif}
.cr-ed-sbtn:hover{color:#4a6070;background:#111820;border-color:#1c2433}
.cr-ed-sbtn.on{color:#4880c8;background:rgba(72,128,200,.1);border-color:rgba(72,128,200,.22)}
.cr-ed-content{flex:1;overflow-y:auto;padding:14px;display:flex;flex-direction:column;gap:13px;min-width:0}
.cr-ed-content::-webkit-scrollbar{width:3px}
.cr-ed-content::-webkit-scrollbar-thumb{background:#1c2433}
.cr-ed-empty{color:#1e2e3e;font-size:11px;margin-top:24px;text-align:center;letter-spacing:.07em;line-height:1.8;text-transform:uppercase;font-weight:600}
.cr-ed-sec-title{font-size:9px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:#253545}
.cr-ed-cname{font-size:15px;font-weight:600;color:#607090;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:3px}
.cr-ed-cqnum{font-family:'JetBrains Mono',monospace;font-size:11px;color:#304a60;margin-top:2px}
.cr-ed-div{height:1px;background:#131820}
.cr-ed-f{display:flex;flex-direction:column;gap:5px}
.cr-ed-l{font-size:9px;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:#253545}
.cr-ed-inp{background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:6px 9px;color:#7a9ab8;font-family:'JetBrains Mono',monospace;font-size:12px;width:100%;outline:none;transition:border-color .12s,color .12s}
.cr-ed-inp:focus{border-color:#284878;color:#a8c8e8}
.cr-ed-sel{background:#080a0d;border:1px solid #1a2433;border-radius:3px;padding:6px 9px;color:#7a9ab8;font-family:'Barlow Condensed',sans-serif;font-size:13px;width:100%;outline:none;cursor:pointer}
.cr-ed-sel:focus{border-color:#284878}
.cr-ed-chkrow{display:flex;align-items:center;gap:8px;cursor:pointer}
.cr-ed-chk{accent-color:#4a8eff;cursor:pointer;width:14px;height:14px}
.cr-ed-chklbl{font-size:13px;color:#4060a0;cursor:pointer}
.cr-active-badge{display:flex;align-items:center;gap:7px;padding:7px 10px;border-radius:3px;background:rgba(212,144,30,.08);border:1px solid rgba(212,144,30,.2);font-size:11px;color:#a07018;font-weight:600;letter-spacing:.05em}
.cr-ed-ta{resize:vertical;min-height:56px;font-family:'Barlow Condensed',sans-serif !important;font-size:13px !important;line-height:1.5 !important}
`;

// ─── ShowBar ──────────────────────────────────────────────────────────────────
function ShowBar({ dbo, onDbo, bpm, onTap, stackName, activeName, standbyName, onGo, onBack }) {
  return (
    <div className="cr-bar">
      <div className="cr-bar-sec">
        <div>
          <div className="cr-lbl" style={{ marginBottom: 4 }}>Blackout</div>
          <button className={`cr-btn btn-dbo${dbo ? ' on' : ''}`} onClick={onDbo}>DBO</button>
        </div>
      </div>
      <div className="cr-bar-sec">
        <div>
          <div className="cr-lbl">BPM</div>
          <div className="cr-bpm">{bpm ?? '—'}</div>
        </div>
        <button className="cr-btn btn-tap" onClick={onTap}>TAP</button>
      </div>
      <div className="cr-bar-sec grow">
        {activeName ? (
          <div className="cr-cuinfo">
            <div className="cr-cuinfo-active">▶ {activeName}</div>
            {standbyName && <div className="cr-cuinfo-standby">◉  next — {standbyName}</div>}
          </div>
        ) : (
          <div className="cr-cuinfo">
            <div className="cr-cuinfo-idle">
              {standbyName ? `${stackName}  ·  ◉ ${standbyName}` : `${stackName}  ·  end of stack`}
            </div>
          </div>
        )}
      </div>
      <div className="cr-kbhints">
        <span className="cr-kbhint">← back</span>
        <span className="cr-kbhint">space: go</span>
      </div>
      <button className="cr-btn btn-back" onClick={onBack} style={{ height: '100%', borderRadius: 0, borderRight: '1px solid #1c2433' }}>◀ BACK</button>
      <button className="cr-btn btn-go" onClick={onGo}>GO</button>
    </div>
  );
}

// ─── MarkerRow ────────────────────────────────────────────────────────────────
function MarkerRow({ name }) {
  return (
    <div className="cr-mkr">
      <div className="cr-mkr-line" />
      <span className="cr-mkr-label">{name}</span>
      <div className="cr-mkr-line" />
    </div>
  );
}

// ─── CueRow ───────────────────────────────────────────────────────────────────
function CueRow({ cue, isActive, isStandby, isDone, isEditing, isTheatre, fadeP, autoP, onClick }) {
  const cls = ['cr-row',
    isDone && 'done',
    isActive && 'active',
    isStandby && !isActive && 'standby',
    isEditing && 'editing',
  ].filter(Boolean).join(' ');

  let statusEl = null;
  if (isDone)                         statusEl = <span className="st-done">✓</span>;
  else if (isActive && autoP != null) statusEl = <span className="st-auto">●</span>;
  else if (isActive)                  statusEl = <span className="st-active">▶</span>;
  else if (isStandby)                 statusEl = <span className="st-standby">◉</span>;

  const showFadeBar = isActive && fadeP > 0 && autoP == null;
  const showAutoBar = isActive && autoP != null;

  return (
    <div className={cls} onClick={onClick}>
      {showFadeBar && <div className="cr-fadebar" style={{ width: `${(fadeP * 100).toFixed(2)}%` }} />}
      {showAutoBar && <div className="cr-autobar" style={{ width: `${(autoP * 100).toFixed(2)}%` }} />}
      <div className="cc-s">{statusEl}</div>
      {isTheatre && <div className="cc-q">{cue.cueNumber ? `Q${cue.cueNumber}` : ''}</div>}
      <div className="cc-n">{cue.name}</div>
      <div className="cc-f">
        {cue.fadeDurationMs > 0 ? `${fmtMs(cue.fadeDurationMs)} ${fmtCurve(cue.fadeCurve)}` : 'SNAP'}
      </div>
      <div className="cc-a">
        {cue.autoAdvance && <span className="auto-pill">AUTO</span>}
      </div>
      {isTheatre && <div className="cc-notes">{cue.notes || ''}</div>}
      <div className="cc-edit">✏</div>
    </div>
  );
}

// ─── EditorPanel ──────────────────────────────────────────────────────────────
function EditorPanel({ open, cue, onClose, onOpen, onChange, isActiveCue }) {
  return (
    <div className={`cr-ed${open ? ' open' : ''}`}>
      <div className="cr-ed-strip">
        <button
          className={`cr-ed-sbtn${open ? ' on' : ''}`}
          onClick={open ? onClose : onOpen}
          title={open ? "Close editor" : "Open editor"}
        >
          {open ? '✕' : '✏'}
        </button>
      </div>
      {open && (
        <div className="cr-ed-content">
          {!cue ? (
            <div className="cr-ed-empty">Click a cue<br />to edit</div>
          ) : (
            <>
              <div>
                <div className="cr-ed-sec-title">Editing Cue</div>
                <div className="cr-ed-cname">{cue.name}</div>
                {cue.cueNumber && <div className="cr-ed-cqnum">Q{cue.cueNumber}</div>}
              </div>
              {isActiveCue && (
                <div className="cr-active-badge">▶ Currently fading</div>
              )}
              <div className="cr-ed-div" />
              <div className="cr-ed-f">
                <label className="cr-ed-l">Cue Number</label>
                <input className="cr-ed-inp" value={cue.cueNumber ?? ''} placeholder="e.g. 14A"
                  onChange={e => onChange(cue.id, 'cueNumber', e.target.value || null)} />
              </div>
              <div className="cr-ed-f">
                <label className="cr-ed-l">Fade Duration (ms)</label>
                <input className="cr-ed-inp" type="number" min={0} step={100}
                  value={cue.fadeDurationMs ?? 0}
                  onChange={e => onChange(cue.id, 'fadeDurationMs', parseInt(e.target.value) || 0)} />
              </div>
              <div className="cr-ed-f">
                <label className="cr-ed-l">Fade Curve</label>
                <select className="cr-ed-sel" value={cue.fadeCurve ?? 'LINEAR'}
                  onChange={e => onChange(cue.id, 'fadeCurve', e.target.value)}>
                  <option value="LINEAR">Linear</option>
                  <option value="EASE_IN_OUT">Ease In/Out (Sine)</option>
                  <option value="SINE_IN_OUT">Sine In/Out</option>
                  <option value="CUBIC_IN_OUT">Cubic In/Out</option>
                  <option value="EASE_IN">Ease In</option>
                  <option value="EASE_OUT">Ease Out</option>
                </select>
              </div>
              <div className="cr-ed-f">
                <label className="cr-ed-chkrow">
                  <input type="checkbox" className="cr-ed-chk" checked={!!cue.autoAdvance}
                    onChange={e => onChange(cue.id, 'autoAdvance', e.target.checked)} />
                  <span className="cr-ed-chklbl">Auto-advance</span>
                </label>
                {cue.autoAdvance && (
                  <>
                    <label className="cr-ed-l" style={{ marginTop: 6 }}>Delay after fade (ms)</label>
                    <input className="cr-ed-inp" type="number" min={0} step={100}
                      value={cue.autoAdvanceDelayMs ?? 0}
                      onChange={e => onChange(cue.id, 'autoAdvanceDelayMs', parseInt(e.target.value) || 0)} />
                  </>
                )}
              </div>
              <div className="cr-ed-f">
                <label className="cr-ed-l">Notes</label>
                <textarea className="cr-ed-inp cr-ed-ta"
                  value={cue.notes ?? ''} placeholder="Script note or reference..."
                  onChange={e => onChange(cue.id, 'notes', e.target.value || null)} />
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

// ─── MAIN COMPONENT ───────────────────────────────────────────────────────────
export default function CueStackRunner() {
  const [stacks, setStacks]           = useState(INIT);
  const [stackId, setStackId]         = useState(1);
  const [completedIds, setCompleted]  = useState(new Set());
  const [activeCueId, setActive]      = useState(null);
  const [standbyCueId, setStandby]    = useState(() => firstStd(INIT[1].cues));
  const [fadeP, setFadeP]             = useState(0);
  const [autoP, setAutoP]             = useState(null);
  const [editingCueId, setEditing]    = useState(null);
  const [editorOpen, setEditorOpen]   = useState(false);
  const [bpm, setBpm]                 = useState(null);
  const [dbo, setDbo]                 = useState(false);
  const [tapTimes, setTapTimes]       = useState([]);
  const [oooDismissed, setOooDismiss] = useState(false);
  // Manual context override per stack
  const [ctxOverride, setCtxOverride] = useState({});

  // Animation refs
  const animR   = useRef(null);
  const autoR   = useRef(null);
  const goFn    = useRef(null);
  const activeR = useRef(null);
  const standbyR = useRef(null);
  const stackIdR = useRef(stackId);
  const stacksR  = useRef(stacks);

  useEffect(() => { activeR.current  = activeCueId; }, [activeCueId]);
  useEffect(() => { standbyR.current = standbyCueId; }, [standbyCueId]);
  useEffect(() => { stackIdR.current = stackId; }, [stackId]);
  useEffect(() => { stacksR.current  = stacks; }, [stacks]);

  const cancelAll = useCallback(() => {
    if (animR.current)  { cancelAnimationFrame(animR.current);  animR.current = null; }
    if (autoR.current)  { cancelAnimationFrame(autoR.current);  autoR.current = null; }
  }, []);

  const markDone = useCallback(id => setCompleted(p => new Set([...p, id])), []);

  const getCue = useCallback(id => {
    const s = stacksR.current[stackIdR.current];
    return s?.cues.find(c => c.id === id) ?? null;
  }, []);

  // startAuto: called after a fade completes when autoAdvance=true.
  // Keeps activeCueId live during countdown, marks done + fires go at end.
  const startAuto = useCallback((cue, cueId) => {
    const delay = cue.autoAdvanceDelayMs ?? 0;
    const finish = () => {
      setAutoP(null);
      markDone(cueId);
      setActive(null);
      setFadeP(0);
      goFn.current?.();
    };
    if (delay <= 0) { setTimeout(finish, 32); return; }
    const t0 = performance.now();
    const tick = t => {
      const p = Math.min((t - t0) / delay, 1);
      setAutoP(p);
      if (p < 1) autoR.current = requestAnimationFrame(tick);
      else { autoR.current = null; finish(); }
    };
    autoR.current = requestAnimationFrame(tick);
  }, [markDone]);

  const go = useCallback(() => {
    const sid = standbyR.current;
    if (!sid) return;
    const cue = getCue(sid);
    if (!cue) return;

    cancelAll();

    // Mark previous active as done (if mid-fade when user presses GO again)
    if (activeR.current) markDone(activeR.current);

    // New active, clear progress
    setActive(sid);
    setFadeP(0);
    setAutoP(null);

    // Advance standby cursor
    const s = stacksR.current[stackIdR.current];
    setStandby(nextStd(s.cues, sid, s.loop));

    const dur = cue.fadeDurationMs ?? 0;
    const nid = sid;

    if (dur > 0) {
      const t0 = performance.now();
      const tick = t => {
        const p = Math.min((t - t0) / dur, 1);
        setFadeP(p);
        if (p < 1) {
          animR.current = requestAnimationFrame(tick);
        } else {
          animR.current = null;
          setFadeP(0);
          if (cue.autoAdvance) startAuto(cue, nid);
          else { markDone(nid); setActive(null); }
        }
      };
      animR.current = requestAnimationFrame(tick);
    } else {
      // Snap cut
      if (cue.autoAdvance) startAuto(cue, nid);
      else { markDone(nid); setActive(null); }
    }
  }, [getCue, cancelAll, markDone, startAuto]);

  useEffect(() => { goFn.current = go; }, [go]);

  const handleBack = useCallback(() => {
    cancelAll();
    setFadeP(0);
    setAutoP(null);
    const prev = activeR.current;
    const s = stacksR.current[stackIdR.current];
    const curSB = standbyR.current;
    setActive(null);

    if (prev) {
      // Was mid-fade — return to pre-fired state
      setStandby(prev);
      setCompleted(p => { const n = new Set(p); n.delete(prev); return n; });
    } else {
      // No active — move standby cursor back
      const p = prevStd(s.cues, curSB);
      if (p) {
        setStandby(p);
        setCompleted(prev => { const n = new Set(prev); n.delete(p); return n; });
      }
    }
  }, [cancelAll]);

  const switchStack = useCallback(id => {
    cancelAll();
    setStackId(id);
    setCompleted(new Set());
    setActive(null);
    setFadeP(0);
    setAutoP(null);
    setStandby(firstStd(INIT[id].cues));
    setEditing(null);
    setEditorOpen(false);
    setOooDismiss(false);
  }, [cancelAll]);

  // Keyboard handler
  useEffect(() => {
    const onKey = e => {
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(e.target.tagName)) return;
      if (e.code === 'Space')     { e.preventDefault(); go(); }
      if (e.code === 'ArrowLeft') { e.preventDefault(); handleBack(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [go, handleBack]);

  // Tap tempo
  const handleTap = useCallback(() => {
    const now = Date.now();
    setTapTimes(prev => {
      const r = [...prev, now].filter(t => now - t < 5000).slice(-8);
      if (r.length >= 2) {
        const d = r.slice(1).map((t, i) => t - r[i]);
        setBpm(Math.round(60000 / (d.reduce((a, b) => a + b) / d.length)));
      }
      return r;
    });
  }, []);

  // Live cue editing (stored in stacks state)
  const updateCue = useCallback((cueId, field, value) => {
    setStacks(p => {
      const s = p[stackIdR.current];
      return { ...p, [stackIdR.current]: { ...s, cues: s.cues.map(c => c.id === cueId ? { ...c, [field]: value } : c) } };
    });
  }, []);

  // Fix cue order via natural sort
  const fixOrder = useCallback(() => {
    setStacks(p => {
      const s = p[stackIdR.current];
      return { ...p, [stackIdR.current]: { ...s, cues: sortByCueNum(s.cues) } };
    });
    setOooDismiss(false);
  }, []);

  useEffect(() => () => cancelAll(), [cancelAll]);

  // Derived
  const stack      = stacks[stackId];
  const baseCtx    = stack.context;
  const isTheatre  = (ctxOverride[stackId] ?? baseCtx) === 'theatre';
  const ooo        = isTheatre && !oooDismissed && detectOOO(stack.cues);
  const editCue    = editingCueId ? stack.cues.find(c => c.id === editingCueId) : null;
  const activeName = activeCueId  ? stack.cues.find(c => c.id === activeCueId)?.name  : null;
  const standbyName = standbyCueId ? stack.cues.find(c => c.id === standbyCueId)?.name : null;

  const handleRowClick = id => {
    if (editingCueId === id && editorOpen) setEditorOpen(false);
    else { setEditing(id); setEditorOpen(true); }
  };

  const toggleCtx = val => setCtxOverride(p => ({ ...p, [stackId]: val }));

  return (
    <div className="cr">
      <style>{CSS}</style>

      <ShowBar
        dbo={dbo}
        onDbo={() => { cancelAll(); setDbo(d => !d); setActive(null); setFadeP(0); setAutoP(null); }}
        bpm={bpm} onTap={handleTap}
        stackName={stack.name}
        activeName={activeName} standbyName={standbyName}
        onGo={go} onBack={handleBack}
      />

      <div className="cr-body">
        <div className="cr-runner">

          {/* Stack tabs + context toggle */}
          <div className="cr-tabs">
            {Object.values(stacks).map(s => (
              <button key={s.id} className={`cr-tab${s.id === stackId ? ' on' : ''}`}
                onClick={() => switchStack(s.id)}>
                {s.name}
                {s.loop && <span className="cr-loop">↻</span>}
              </button>
            ))}
            <div className="cr-tabs-spacer" />
            <div className="cr-ctx-wrap">
              <button className={`cr-ctx-btn${isTheatre ? ' on' : ''}`} onClick={() => toggleCtx('theatre')}>Theatre</button>
              <button className={`cr-ctx-btn${!isTheatre ? ' on' : ''}`} onClick={() => toggleCtx('band')}>Band</button>
            </div>
          </div>

          {/* Out-of-order banner */}
          {ooo && (
            <div className="cr-ooo">
              <span>⚠</span>
              <span>Cue numbers are out of order.</span>
              <button className="cr-ooo-btn" onClick={fixOrder}>Fix Order</button>
              <button className="cr-ooo-btn" onClick={() => setOooDismiss(true)}>Dismiss</button>
            </div>
          )}

          {/* Column headers */}
          <div className="cr-hdr">
            <div style={{ width: 20 }}></div>
            {isTheatre && <div style={{ width: 48 }}>Q</div>}
            <div style={{ flex: 1 }}>Name</div>
            <div style={{ width: 86, textAlign: 'right', paddingRight: 8 }}>Fade</div>
            <div style={{ width: 36 }}></div>
            {isTheatre && <div style={{ width: 200, paddingLeft: 12 }}>Note</div>}
            <div style={{ width: 30 }}></div>
          </div>

          {/* Cue list */}
          <div className="cr-list">
            {stack.cues.map(cue => {
              if (cue.cueType === 'MARKER') return <MarkerRow key={cue.id} name={cue.name} />;
              const isActive  = cue.id === activeCueId;
              const isStandby = cue.id === standbyCueId;
              const isDone    = completedIds.has(cue.id);
              const isEditing = cue.id === editingCueId && editorOpen;
              return (
                <CueRow key={cue.id} cue={cue}
                  isActive={isActive} isStandby={isStandby} isDone={isDone} isEditing={isEditing}
                  isTheatre={isTheatre}
                  fadeP={isActive ? fadeP : 0}
                  autoP={isActive ? autoP : null}
                  onClick={() => handleRowClick(cue.id)}
                />
              );
            })}
          </div>
        </div>

        {/* Inline editor panel */}
        <EditorPanel
          open={editorOpen} cue={editCue}
          onClose={() => setEditorOpen(false)}
          onOpen={() => setEditorOpen(true)}
          onChange={updateCue}
          isActiveCue={editingCueId === activeCueId}
        />
      </div>
    </div>
  );
}
