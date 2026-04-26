// Stage view — three marker styles, side-panel fixture sheet, group filter chips.
// Reuses PATCH_DATA fixtures + their x/y positions.

const { useState: usSt, useMemo: usMemo } = React;

// ─── Live-ish state per fixture (intensity 0-100 + colour) ──────────
function deriveLive(f) {
  // deterministic-ish "live" snapshot for the design
  const seed = (f.id || "").split("").reduce((a, c) => a + c.charCodeAt(0), 0);
  const rng = (n) => ((Math.sin(seed * (n + 1)) + 1) / 2);
  const map = {
    "beam-bar-1": { i: 0, color: "#fff" },
    "hex2":  { i: 78, color: "#ff5b6e" },
    "hex3":  { i: 78, color: "#ff7a40" },
    "hex4":  { i: 78, color: "#ffce4a" },
    "scan":  { i: 0, color: "#fff" },
    "laser1":{ i: 92, color: "#3ed6a5" },
    "spot-r":{ i: 64, color: "#7ad1ff" },
    "spot-l":{ i: 64, color: "#7ad1ff" },
    "haze":  { i: 30, color: "#aaa" },
    "laser2":{ i: 92, color: "#a36bff" },
    "bar1":  { i: 50, color: "#ff7ed8" },
    "bar2":  { i: 50, color: "#7ad1ff" },
    "sdff":  { i: 70, color: "#fbcfa0" }, // gel L162-ish
    "fph1":  { i: 0, color: "#fff" },
  };
  const def = map[f.id];
  if (def) return def;
  return { i: Math.round(rng(1) * 100), color: "#7ad1ff" };
}

// ─── Topbar with Stage tab highlighted ─────────────────────────────
function StageTopbar() {
  return (
    <div className="pv-topbar">
      <div className="title">Chris' DMX Controller v7</div>
      <div className="sp" />
      <div className="conn"><span className="dot" />Connected</div>
      <button className="iconbtn" title="Fixtures"><svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor"><rect x="2" y="2" width="5" height="5" rx="1"/><rect x="9" y="2" width="5" height="5" rx="1"/><rect x="2" y="9" width="5" height="5" rx="1"/><rect x="9" y="9" width="5" height="5" rx="1"/></svg></button>
      <button className="iconbtn" title="Patch List"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="2" y="2" width="12" height="12"/><path d="M2 6h12M2 10h12M6 2v12M10 2v12"/></svg></button>
      <button className="iconbtn active" title="Stage"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="1.5" y="3" width="13" height="9" rx="1"/><circle cx="8" cy="7.5" r="1.4" fill="currentColor" stroke="none"/><path d="M5 12v1.5M11 12v1.5"/></svg></button>
      <button className="iconbtn" title="FX"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 12c2-6 4 6 6 0s4 6 6 0"/></svg></button>
      <button className="iconbtn" title="Light"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="8" cy="8" r="3"/><path d="M8 1v2M8 13v2M1 8h2M13 8h2"/></svg></button>
    </div>
  );
}

// ─── Sidebar with Stage active ─────────────────────────────────────
function StageSidebar() {
  const item = (icon, label, active) => (
    <div className={"pv-sb-item" + (active ? " active" : "")}>{icon}<span>{label}</span></div>
  );
  return (
    <aside className="pv-sidebar">
      <div className="pv-sb-project">{Pi.folder}<span>43!</span></div>
      <nav className="pv-sb-nav">
        {item(Pi.grid, "Fixtures")}
        {item(Pi.list, "Patch List")}
        {item(<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="1.5" y="3" width="13" height="9" rx="1"/><circle cx="8" cy="7.5" r="1.4" fill="currentColor" stroke="none"/></svg>, "Stage", true)}
        {item(Pi.groups, "Groups")}
        {item(Pi.surfaces, "Surfaces")}
        <div className="pv-sb-section">PROGRAMMING</div>
        {item(Pi.scripts, "Scripts")}
        {item(Pi.fx, "FX Library")}
        {item(Pi.preset, "FX Presets")}
        <div className="pv-sb-section">LIVE</div>
        {item(Pi.fx, "FX")}
        {item(Pi.program, "Program")}
        {item(Pi.run, "Run")}
        {item(Pi.channels, "Channels")}
      </nav>
      <div className="pv-sb-configure">{Pi.gear}<span>Configure Project</span></div>
    </aside>
  );
}

// ─── Group filter chips ────────────────────────────────────────────
function StageGroupChips({ active, onChange }) {
  const groups = window.PATCH_DATA.groups.filter(g => g.count > 0);
  return (
    <div className="sv-chips">
      <button className={"sv-chip" + (!active ? " on" : "")} onClick={() => onChange(null)}>All<span className="n">{window.PATCH_DATA.fixtures.length}</span></button>
      {groups.map(g => (
        <button key={g.id} className={"sv-chip" + (active === g.id ? " on" : "")} onClick={() => onChange(g.id === active ? null : g.id)}>
          {g.name}<span className="n">{g.count}</span>
        </button>
      ))}
    </div>
  );
}

// ─── Marker A — Realistic fixture icons ────────────────────────────
function MarkerRealistic({ f, live, selected }) {
  const k = f.fxKind || "par";
  const cls = "sv-mk sv-mk-real" + (selected ? " sel" : "");
  const tint = live.color;
  return (
    <div className={cls} title={f.name}>
      {k === "par" && (
        <svg viewBox="0 0 32 32" width="32" height="32">
          <rect x="6" y="3" width="20" height="6" rx="1.5" fill="#444" />
          <circle cx="16" cy="20" r="9" fill={tint} opacity={Math.max(0.18, live.i / 100)} stroke="rgba(255,255,255,.25)" strokeWidth="1.2"/>
          <circle cx="16" cy="20" r="3" fill="#222" />
        </svg>
      )}
      {k === "moving" && (
        <svg viewBox="0 0 32 32" width="32" height="32">
          <rect x="9" y="2" width="14" height="4" rx="1" fill="#3a3a40" />
          <rect x="11" y="6" width="10" height="6" fill="#4d4d54" />
          <ellipse cx="16" cy="18" rx="9" ry="10" fill={tint} opacity={Math.max(0.2, live.i / 100)} stroke="rgba(255,255,255,.3)" strokeWidth="1.2"/>
          <circle cx="16" cy="18" r="3" fill="#1a1a1c" />
        </svg>
      )}
      {k === "bar" && (
        <svg viewBox="0 0 60 16" width="60" height="16">
          <rect x="0" y="3" width="60" height="10" rx="2" fill="#2a2a2e" stroke="rgba(255,255,255,.2)"/>
          {Array.from({ length: 12 }).map((_, i) => (
            <circle key={i} cx={4 + i * 4.7} cy="8" r="1.6" fill={tint} opacity={Math.max(0.2, live.i / 100)} />
          ))}
        </svg>
      )}
      {k === "laser" && (
        <svg viewBox="0 0 28 28" width="28" height="28">
          <rect x="4" y="6" width="20" height="14" rx="2" fill="#3a3a40" />
          <circle cx="14" cy="13" r="4" fill="#0a0a0a" stroke={tint} strokeWidth="1.5"/>
          <circle cx="14" cy="13" r="1.5" fill={tint}/>
        </svg>
      )}
      {k === "haze" && (
        <svg viewBox="0 0 28 28" width="28" height="28">
          <rect x="4" y="9" width="20" height="10" rx="2" fill="#3a3a40" />
          <path d="M3 5 Q8 3 14 5 T25 5" stroke="rgba(255,255,255,.4)" strokeWidth="1.5" fill="none"/>
          <path d="M3 22 Q8 24 14 22 T25 22" stroke="rgba(255,255,255,.25)" strokeWidth="1.5" fill="none"/>
        </svg>
      )}
      {k === "dimmer" && (
        <svg viewBox="0 0 28 28" width="28" height="28">
          <rect x="6" y="3" width="16" height="5" rx="1" fill="#444" />
          <path d="M6 8 L4 22 L24 22 L22 8 Z" fill={tint} opacity={Math.max(0.2, live.i / 100)} stroke="rgba(255,255,255,.25)"/>
        </svg>
      )}
      <div className="sv-mk-label">{f.name}{f.pos ? <span className="sv-pos">{f.pos}</span> : null}</div>
    </div>
  );
}

// ─── Marker B — Abstract colour-coded dots ─────────────────────────
function MarkerAbstract({ f, live, selected }) {
  const colors = {
    par: "#ff8a4a", moving: "#7ad1ff", bar: "#ff7ed8", laser: "#a36bff", haze: "#9ca3af", dimmer: "#fbcfa0"
  };
  const c = colors[f.fxKind] || "#7ad1ff";
  const cls = "sv-mk sv-mk-abs" + (selected ? " sel" : "");
  return (
    <div className={cls} title={f.name}>
      <div className="sv-dot" style={{ background: c, boxShadow: `0 0 12px ${c}55` }} />
      <div className="sv-mk-label">{f.name}{f.pos ? <span className="sv-pos">{f.pos}</span> : null}</div>
    </div>
  );
}

// ─── Marker C — Live state with beam cone ──────────────────────────
function MarkerLive({ f, live, selected, showBeams }) {
  const cls = "sv-mk sv-mk-live" + (selected ? " sel" : "");
  const intensity = Math.max(0.05, live.i / 100);
  const hasBeam = (f.fxKind === "moving" || f.fxKind === "par" || f.fxKind === "dimmer");
  const beamW = (f.beam || 30) * 1.6; // px; tuned for visual
  return (
    <div className={cls} title={f.name}>
      {showBeams && hasBeam && live.i > 5 && (
        <div className="sv-cone" style={{
          background: `radial-gradient(ellipse at 50% 0%, ${live.color}cc 0%, ${live.color}55 25%, transparent 65%)`,
          width: beamW + "px",
          opacity: intensity,
        }} />
      )}
      <div className="sv-glow" style={{
        background: live.color,
        boxShadow: `0 0 ${4 + intensity * 18}px ${live.color}, 0 0 ${8 + intensity * 30}px ${live.color}aa`,
        opacity: 0.3 + intensity * 0.7,
      }} />
      <div className="sv-mk-label">
        {f.name}
        {f.pos && <span className="sv-pos">{f.pos}</span>}
        <span className="sv-pct">{live.i}%</span>
      </div>
    </div>
  );
}

const MARKER_BY = { realistic: MarkerRealistic, abstract: MarkerAbstract, live: MarkerLive };

// ─── Stage backdrop ────────────────────────────────────────────────
function StageBackdrop({ kind = "grid", children, onBgClick }) {
  return (
    <div className={"sv-stage sv-stage-" + kind} onClick={onBgClick}>
      {kind === "schematic" && (
        <>
          <div className="sv-house" />
          <div className="sv-apron" />
          <div className="sv-wing sv-wing-l" />
          <div className="sv-wing sv-wing-r" />
          <div className="sv-backdrop" />
          <div className="sv-truss sv-truss-1"><span>LX1</span></div>
          <div className="sv-truss sv-truss-2"><span>LX2</span></div>
          <div className="sv-truss sv-truss-3"><span>ADV 1</span></div>
          <div className="sv-cline" />
          <div className="sv-foh">FOH</div>
        </>
      )}
      {kind === "grid" && (
        <>
          <div className="sv-grid-back">UPSTAGE</div>
          <div className="sv-grid-axis-x" />
          <div className="sv-grid-axis-y" />
          <div className="sv-grid-front">DOWNSTAGE</div>
        </>
      )}
      {kind === "void" && (
        <>
          <div className="sv-void-floor" />
          <div className="sv-void-front">DOWNSTAGE</div>
        </>
      )}
      {children}
    </div>
  );
}

// ─── Right-side fixture sheet ──────────────────────────────────────
function FixtureSheet({ fixture, onClose }) {
  if (!fixture) return null;
  const live = deriveLive(fixture);
  const tabs = ["Dimmer", "Colour", "Uv", "Strobe"];
  return (
    <div className="pv-panel sv-fix-panel">
      <div className="pv-panel-hd" style={{ alignItems: "flex-start" }}>
        <div className="ttl" style={{ flex: 1 }}>
          <h2>{fixture.name}</h2>
          <div className="sub">{fixture.type}</div>
        </div>
        <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
          <button className="sv-btn-ghost" title="Edit positions"><svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 14l3-1 8-8-2-2-8 8-1 3z"/></svg></button>
          <button className="sv-park"><svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8"><rect x="3" y="7" width="10" height="7" rx="1"/><path d="M5 7V4a3 3 0 0 1 6 0v3"/></svg> Unpark <span className="ch">{fixture.chCount}</span></button>
        </div>
        <button className="x" onClick={onClose}>{Pi.close}</button>
      </div>
      <div className="pv-panel-bd">
        <div className="sv-tabs">
          {tabs.map((t, i) => <button key={t} className={i === 0 ? "on" : ""}>{t}</button>)}
        </div>
        <div className="sv-encs">
          <span className="lbl">dimmer:</span>
          <span className="enc">{Pi.channels} XT Encoder 1</span>
          <span className="enc">{Pi.channels} XT Encoder 16</span>
          <span className="enc">{Pi.channels} XT Fader 1</span>
        </div>

        {fixture.fxKind !== "haze" && (
          <div className="sv-row">
            <div className="lbl-block">Rgb<br/>Colour</div>
            <div className="sv-color">
              <span className="lock">🔒</span>
              <div className="sw" style={{ background: live.color }} />
              <div className="vals">R:0 G:0 B:0<br/>W:0 A:0 UV:0</div>
            </div>
          </div>
        )}
        {[["Dimmer", live.i], ["Program Speed", 0], ["Strobe", 0]].map(([label, val]) => (
          <div key={label} className="sv-row">
            <div className="lbl-block">{label}</div>
            <div className="sv-slider">
              <div className="track"><div className="fill" style={{ width: val + "%" }} /></div>
              <span className="num">{val} ({val}%)</span>
            </div>
          </div>
        ))}
        <div className="sv-row">
          <div className="lbl-block">Dimmer<br/>Mode</div>
          <select className="sv-select"><option>Manual</option></select>
        </div>
        <div className="sv-row">
          <div className="lbl-block">Mode</div>
          <select className="sv-select"><option>None</option></select>
        </div>

        <div className="sv-fx">
          <span>{Pi.fx} Effects {Pi.chev}</span>
          <button className="add">+</button>
        </div>
      </div>
    </div>
  );
}

// ─── The whole Stage View page ─────────────────────────────────────
function StageView({ markerStyle = "realistic", bgKind = "grid", showBeams = true, defaultSelectedId = null }) {
  const [groupFilter, setGroupFilter] = usSt(null);
  const [selectedId, setSelectedId] = usSt(defaultSelectedId);

  const fixtures = usMemo(() => {
    const all = window.PATCH_DATA.fixtures.filter(f => typeof f.x === "number");
    if (!groupFilter) return all;
    return all.map(f => ({ ...f, dim: !(f.groups || []).includes(groupFilter) }));
  }, [groupFilter]);

  const Marker = MARKER_BY[markerStyle];
  const selected = window.PATCH_DATA.fixtures.find(f => f.id === selectedId);

  return (
    <div className="pv-app">
      <StageSidebar />
      <div className="pv-main">
        <StageTopbar />
        <div className="pv-crumbs">
          <span>Projects</span><span className="sep">›</span>
          <span>43!</span><span className="badge">active</span><span className="sep">›</span>
          <span className="cur">Stage</span>
        </div>
        <div className="pv-content sv-content">
          <div className="pv-page-h">
            <div>
              <h1>Stage</h1>
              <div className="pv-page-sub">{fixtures.length} fixtures · click a fixture to control it</div>
            </div>
            <div style={{display:"flex", gap: 6}}>
              <button className="sv-tool-btn" title="Reset view"><svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M14 4v4h-4M2 12V8h4"/><path d="M14 8a6 6 0 0 0-10-3M2 8a6 6 0 0 0 10 3"/></svg></button>
              <button className="sv-tool-btn" title="Add position"><svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M8 3v10M3 8h10"/></svg></button>
            </div>
          </div>

          <StageGroupChips active={groupFilter} onChange={setGroupFilter} />

          <StageBackdrop kind={bgKind} onBgClick={() => setSelectedId(null)}>
            {fixtures.map(f => {
              const live = deriveLive(f);
              const selected = f.id === selectedId;
              const dim = f.dim;
              return (
                <div
                  key={f.id}
                  className={"sv-pos" + (dim ? " dim" : "") + (selected ? " sel-pos" : "")}
                  style={{ left: f.x + "%", top: f.y + "%" }}
                  onClick={(e) => { e.stopPropagation(); setSelectedId(f.id); }}
                >
                  <Marker f={f} live={live} selected={selected} showBeams={showBeams} />
                </div>
              );
            })}
          </StageBackdrop>
        </div>
      </div>
      {selected && <FixtureSheet fixture={selected} onClose={() => setSelectedId(null)} />}
    </div>
  );
}

Object.assign(window, { StageView, StageTopbar, StageSidebar, FixtureSheet, deriveLive, MARKER_BY, StageBackdrop, StageGroupChips });
