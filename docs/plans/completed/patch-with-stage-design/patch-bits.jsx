// Patch view shared chrome + form bits

const { useState: usePvState, useEffect: usePvEffect, useRef: usePvRef, useMemo: usePvMemo } = React;

// ─── Icons ──────────────────────────────────────────────────────────
const Pi = {
  folder:   <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 4.5A1.5 1.5 0 0 1 3.5 3h3L8 4.5h4.5A1.5 1.5 0 0 1 14 6v6a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 12V4.5Z"/></svg>,
  grid:     <svg viewBox="0 0 16 16" fill="currentColor"><rect x="2" y="2" width="5" height="5" rx="1"/><rect x="9" y="2" width="5" height="5" rx="1"/><rect x="2" y="9" width="5" height="5" rx="1"/><rect x="9" y="9" width="5" height="5" rx="1"/></svg>,
  list:     <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M4 4h9M4 8h9M4 12h9"/><circle cx="2" cy="4" r=".5" fill="currentColor"/><circle cx="2" cy="8" r=".5" fill="currentColor"/><circle cx="2" cy="12" r=".5" fill="currentColor"/></svg>,
  groups:   <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 6h10M3 10h10M3 3h10M3 13h10"/></svg>,
  surfaces: <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 5h12M2 8h12M2 11h12"/></svg>,
  scripts:  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M5 3L2 8l3 5M11 3l3 5-3 5"/></svg>,
  fx:       <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 12c2-6 4 6 6 0s4 6 6 0"/></svg>,
  preset:   <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="3" y="2" width="10" height="12" rx="1"/><path d="M6 6h4M6 9h4"/></svg>,
  cue:      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="2" y="3" width="12" height="10" rx="1"/><path d="M2 6h12"/></svg>,
  program:  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="8" cy="8" r="6"/><path d="M6 6v4l3-2z" fill="currentColor"/></svg>,
  run:      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M4 3l9 5-9 5z" fill="currentColor"/></svg>,
  channels: <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3v10M7 3v10M11 3v10"/></svg>,
  diag:     <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 8h2l2-4 4 8 2-4h2"/></svg>,
  gear:     <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="8" cy="8" r="2"/><path d="M8 1v2M8 13v2M1 8h2M13 8h2M3 3l1.5 1.5M11.5 11.5L13 13M3 13l1.5-1.5M11.5 4.5L13 3"/></svg>,
  back:     <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M10 4l-4 4 4 4"/></svg>,
  close:    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M4 4l8 8M12 4l-8 8"/></svg>,
  chev:     <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M6 4l4 4-4 4"/></svg>,
  warn:     <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M8 2l7 12H1L8 2z"/><path d="M8 7v3M8 12v.5"/></svg>,
  plus:     <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M8 3v10M3 8h10"/></svg>,
  search:   <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="7" cy="7" r="4.5"/><path d="M11 11l3 3"/></svg>,
  trash:    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 5h10M5 5V3h6v2M6 8v4M10 8v4M5 5l1 9h4l1-9"/></svg>,
};

// ─── Sidebar / topbar / breadcrumbs ────────────────────────────────
function PvSidebar() {
  const item = (icon, label, active) => (
    <div className={"pv-sb-item" + (active ? " active" : "")}>{icon}<span>{label}</span></div>
  );
  return (
    <aside className="pv-sidebar">
      <div className="pv-sb-project">{Pi.folder}<span>43!</span></div>
      <nav className="pv-sb-nav">
        {item(Pi.grid, "Fixtures")}
        {item(Pi.list, "Patch List", true)}
        {item(Pi.groups, "Groups")}
        {item(Pi.surfaces, "Surfaces")}
        <div className="pv-sb-section">PROGRAMMING</div>
        {item(Pi.scripts, "Scripts")}
        {item(Pi.fx, "FX Library")}
        {item(Pi.preset, "FX Presets")}
        {item(Pi.cue, "FX Cues")}
        <div className="pv-sb-section">LIVE</div>
        {item(Pi.fx, "FX")}
        {item(Pi.program, "Program")}
        {item(Pi.run, "Run")}
        {item(Pi.channels, "Channels")}
        {item(Pi.diag, "Diagnostics")}
      </nav>
      <div className="pv-sb-configure">{Pi.gear}<span>Configure Project</span></div>
    </aside>
  );
}

function PvTopbar() {
  return (
    <div className="pv-topbar">
      <div className="title">Chris' DMX Controller v7</div>
      <div className="sp" />
      <div className="conn"><span className="dot" />Connected</div>
      <button className="iconbtn" title="Grid">{Pi.grid}</button>
      <button className="iconbtn" title="Table"><svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="2" y="2" width="12" height="12"/><path d="M2 6h12M2 10h12M6 2v12M10 2v12"/></svg></button>
      <button className="iconbtn" title="FX">{Pi.fx}</button>
      <button className="iconbtn" title="Light">{Pi.gear}</button>
    </div>
  );
}

function PvCrumbs() {
  return (
    <div className="pv-crumbs">
      <span>Projects</span><span className="sep">›</span>
      <span>43!</span><span className="badge">active</span><span className="sep">›</span>
      <span className="cur">Patch List</span>
    </div>
  );
}

// ─── Patch list (left side) ─────────────────────────────────────────
function PvPatchList({ selectedId, onSelect, showPosition = true }) {
  const D = window.PATCH_DATA;
  const fxs = D.fixtures;
  return (
    <table className="pv-list">
      <thead>
        <tr>
          <th style={{ width: 64 }}>Address</th>
          <th>Type</th>
          <th style={{ width: 130 }}>Key</th>
          {showPosition && <th style={{ width: 66 }}>Position</th>}
          <th style={{ width: 130 }}>Name</th>
          <th style={{ width: 100 }}>Groups</th>
        </tr>
      </thead>
      <tbody>
        {fxs.map(f => (
          <tr key={f.id} className={selectedId === f.id ? "sel" : ""} onClick={() => onSelect && onSelect(f.id)}>
            <td className="addr">{f.addr}</td>
            <td className="ty">
              {f.type}
              <span className="ch">{f.chCount}ch</span>
              {f.gel && (() => { const g = window.PATCH_HELP.findGel(f.gel); return g ? <span className="gel-sw" style={{background: g.color}} /> : null; })()}
            </td>
            <td className="key">{f.key}</td>
            {showPosition && <td>{f.pos ? <span className="pos">{f.pos}</span> : null}</td>}
            <td className="name">{f.name}</td>
            <td>{(f.groups || []).slice(0, 2).map(g => <span key={g} className="grpchip">{g}</span>)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ─── Position autocomplete + chip ──────────────────────────────────
function PvPositionInput({ value, onChange }) {
  const [open, setOpen] = usePvState(false);
  const presets = window.PATCH_DATA.positionPresets;
  const matches = presets.filter(p => !value || p.toLowerCase().includes(value.toLowerCase()));
  return (
    <div className="pv-row" style={{ position: "relative" }}>
      <label className="pv-lbl">Position</label>
      <input
        className="pv-input mono"
        placeholder="e.g. LX1, ADV 2, FOH"
        value={value || ""}
        onChange={(e) => onChange(e.target.value.toUpperCase())}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
      />
      <div className="pv-chips" style={{ marginTop: 4 }}>
        {presets.slice(0, 6).map(p => (
          <button key={p} type="button" className={"pv-chip" + (value === p ? " on" : "")}
                  onMouseDown={(e) => { e.preventDefault(); onChange(p); }}>
            {p}
          </button>
        ))}
      </div>
    </div>
  );
}

// ─── Beam angle input (numeric + presets + visualizer) ────────────
function PvBeamAngle({ value, onChange }) {
  const presets = window.PATCH_DATA.beamPresets;
  const matchedPreset = presets.find(p => p.deg === value);
  const half = Math.max(2, (value || 0) / 2);
  return (
    <div className="pv-row">
      <label className="pv-lbl">Beam Angle</label>
      <div className="pv-beam">
        <div className="pv-beam-vis">
          <div className="pv-beam-cone" style={{ "--half": half + "deg" }} />
        </div>
        <div className="pv-beam-controls">
          <div className="pv-beam-num">
            <input
              className="pv-input mono"
              type="number" min="2" max="120"
              value={value ?? ""}
              onChange={(e) => onChange(parseInt(e.target.value, 10) || 0)}
              style={{ width: 64 }}
            />
            <span className="deg">°</span>
          </div>
          <div className="pv-beam-presets">
            {presets.map(p => (
              <button key={p.name} type="button"
                      className={"pp" + (matchedPreset?.name === p.name ? " on" : "")}
                      onClick={() => onChange(p.deg)}>
                {p.name}
                <span style={{ color: "rgba(255,255,255,.45)", marginLeft: 4, fontFamily: "var(--mono)", fontSize: 10 }}>{p.deg}°</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Gel picker (searchable Lee/Rosco) ─────────────────────────────
function PvGelPicker({ value, onChange }) {
  const [open, setOpen] = usePvState(false);
  const [q, setQ] = usePvState("");
  const [brand, setBrand] = usePvState("All");
  const ref = usePvRef(null);
  const current = window.PATCH_HELP.findGel(value);
  const results = window.PATCH_HELP.searchGels(q, brand);

  usePvEffect(() => {
    if (!open) return;
    const close = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  return (
    <div className="pv-row" ref={ref} style={{ position: "relative" }}>
      <label className="pv-lbl">Gel</label>
      <div className="pv-gel" onClick={() => setOpen(o => !o)}>
        <div className="sw" style={{ background: current?.color || "transparent", borderStyle: current ? "solid" : "dashed", borderColor: current ? "rgba(255,255,255,.1)" : "var(--border-strong)" }} />
        {current ? (
          <div className="info">
            <div className="code">{current.code} <span style={{color:"var(--text-faint)", fontSize: 10, marginLeft: 4}}>{current.brand}</span></div>
            <div className="name">{current.name}</div>
          </div>
        ) : (
          <div className="info"><div className="none">Open white — no gel</div></div>
        )}
        {value && (
          <button type="button" className="iconbtn" style={{background:"transparent",border:0,color:"var(--text-faint)",padding:"4px",cursor:"pointer"}}
                  onClick={(e) => { e.stopPropagation(); onChange(null); }}
                  title="Clear gel">
            {Pi.close}
          </button>
        )}
        <span className="chev">{Pi.chev}</span>
      </div>
      {open && (
        <div className="pv-gel-pop" onClick={(e) => e.stopPropagation()}>
          <input className="pv-gel-search" placeholder="Search gels (e.g. L201, blue, amber)…"
                 value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
          <div className="pv-gel-tabs">
            {["All", "Lee", "Rosco"].map(b => (
              <button key={b} className={brand === b ? "on" : ""} onClick={() => setBrand(b)}>{b}</button>
            ))}
          </div>
          <div className="pv-gel-list">
            <div className="pv-gel-row" onClick={() => { onChange(null); setOpen(false); }}>
              <div className="sw" style={{ background: "transparent", borderStyle: "dashed" }} />
              <div className="code">—</div>
              <div className="name" style={{ color: "var(--text-mute)", fontStyle: "italic" }}>Open white</div>
            </div>
            {results.map(g => (
              <div key={g.brand + g.code} className="pv-gel-row" onClick={() => { onChange(g.code); setOpen(false); }}>
                <div className="sw" style={{ background: g.color }} />
                <div className="code">{g.code}</div>
                <div className="name">{g.name} <span style={{color:"var(--text-faint)", fontSize:10, marginLeft:4}}>{g.brand}</span></div>
              </div>
            ))}
            {results.length === 0 && <div style={{padding: 10, color: "var(--text-faint)", fontSize: 11.5}}>No gels match.</div>}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Section (collapsible) ─────────────────────────────────────────
function PvSection({ title, meta, children, defaultOpen = true, icon }) {
  const [open, setOpen] = usePvState(defaultOpen);
  return (
    <div className={"pv-section" + (open ? " open" : " collapsed")}>
      <div className="pv-section-hd" onClick={() => setOpen(o => !o)}>
        {icon}
        <span className="ttl">{title}</span>
        {meta && <span className="meta">{meta}</span>}
        <span className="chev">{Pi.chev}</span>
      </div>
      <div className="pv-section-bd">{children}</div>
    </div>
  );
}

Object.assign(window, {
  Pi,
  PvSidebar, PvTopbar, PvCrumbs,
  PvPatchList,
  PvPositionInput, PvBeamAngle, PvGelPicker,
  PvSection,
});
