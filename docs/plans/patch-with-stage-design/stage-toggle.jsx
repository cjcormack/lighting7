// Stage Toggle — global overlay system, available from topbar icon on any page.
// Three variations: 'strip' (thin drop-down), 'panel' (half-height slide-down),
// 'overlay' (full takeover of content area).

const { useState: stUseState, useEffect: stUseEffect, useRef: stUseRef } = React;

// ─── Stage icon (the topbar trigger) ────────────────────────────────
const StageIcon = (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
    <rect x="1.5" y="3" width="13" height="9" rx="1"/>
    <circle cx="8" cy="7.5" r="1.4" fill="currentColor" stroke="none"/>
    <path d="M5 12v1.5M11 12v1.5"/>
  </svg>
);

// ─── A topbar that knows about a Stage button (replaces PvTopbar) ───
function StageAwareTopbar({ stageOpen, onToggleStage }) {
  return (
    <div className="pv-topbar">
      <div className="title">Chris' DMX Controller v7</div>
      <div className="sp" />
      <div className="conn"><span className="dot" />Connected</div>
      <button className="iconbtn" title="Grid">{Pi.grid}</button>
      <button className="iconbtn" title="Table">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
          <rect x="2" y="2" width="12" height="12"/>
          <path d="M2 6h12M2 10h12M6 2v12M10 2v12"/>
        </svg>
      </button>
      <button
        className={"iconbtn stage-trigger" + (stageOpen ? " on" : "")}
        title={stageOpen ? "Close Stage" : "Open Stage"}
        onClick={onToggleStage}
      >
        {StageIcon}
      </button>
      <button className="iconbtn" title="FX">{Pi.fx}</button>
      <button className="iconbtn" title="Light">{Pi.gear}</button>
    </div>
  );
}

// ─── Compact fixture marker for the toggle overlay ──────────────────
function ToggleMarker({ f, live, selected, showBeams, scale = 1 }) {
  const intensity = Math.max(0.05, live.i / 100);
  const hasBeam = (f.fxKind === "moving" || f.fxKind === "par" || f.fxKind === "dimmer");
  return (
    <div className={"sv-mk sv-mk-live" + (selected ? " sel" : "")} title={f.name}>
      {showBeams && hasBeam && live.i > 5 && (
        <div className="sv-cone" style={{
          background: `radial-gradient(ellipse at 50% 0%, ${live.color}cc 0%, ${live.color}55 25%, transparent 65%)`,
          width: ((f.beam || 30) * 1.6 * scale) + "px",
          opacity: intensity,
        }} />
      )}
      <div className="sv-glow" style={{
        background: live.color,
        boxShadow: `0 0 ${4 + intensity * 18}px ${live.color}, 0 0 ${8 + intensity * 30}px ${live.color}aa`,
        opacity: 0.3 + intensity * 0.7,
        width: 14 * scale + "px",
        height: 14 * scale + "px",
      }} />
      <div className="sv-mk-label">
        {f.name}
        {f.pos && <span className="sv-pos">{f.pos}</span>}
      </div>
    </div>
  );
}

// ─── Variation A: STRIP — thin drop-down below topbar ──────────────
// Shows fixtures as small cards (echoing the user's screenshot pattern)
function StageStrip({ selectedId, onSelect, onClose }) {
  const fixtures = window.PATCH_DATA.fixtures.filter(f => typeof f.x === "number");
  const cardsPerPage = 7;
  const [page, setPage] = stUseState(0);
  const pages = Math.ceil(fixtures.length / cardsPerPage);
  const visible = fixtures.slice(page * cardsPerPage, page * cardsPerPage + cardsPerPage);

  return (
    <div className="stage-strip">
      <div className="stage-strip-row">
        {visible.map(f => {
          const live = deriveLive(f);
          const sel = f.id === selectedId;
          return (
            <button key={f.id}
              className={"stage-strip-card" + (sel ? " on" : "")}
              onClick={() => onSelect(sel ? null : f.id)}>
              <div className="ssc-head">
                <span className="ssc-name">{f.name}</span>
                <div className="ssc-sw" style={{ background: live.color, opacity: 0.3 + live.i / 200 }} />
              </div>
              <div className="ssc-meta">
                {f.pos && <span className="ssc-pos">{f.pos}</span>}
                <span className="ssc-pct">{live.i}%</span>
              </div>
              <div className="ssc-bar">
                <div className="ssc-fill" style={{ width: live.i + "%", background: live.color }} />
              </div>
            </button>
          );
        })}
        <button className="stage-strip-card add" title="Add">
          <span className="ssc-plus">+</span>
        </button>
      </div>
      {pages > 1 && (
        <div className="stage-strip-dots">
          {Array.from({ length: pages }).map((_, i) => (
            <button key={i}
              className={"ssd" + (i === page ? " on" : "")}
              onClick={() => setPage(i)} />
          ))}
        </div>
      )}
    </div>
  );
}

// ─── Variation B: PANEL — half-height slide-down stage map ─────────
function StagePanel({ selectedId, onSelect, onClose }) {
  const fixtures = window.PATCH_DATA.fixtures.filter(f => typeof f.x === "number");
  return (
    <div className="stage-panel">
      <div className="stage-panel-hd">
        <div className="title">
          <span className="dot" />
          <span className="t">Stage</span>
          <span className="cnt">{fixtures.length} fixtures</span>
        </div>
        <div className="sp" />
        <button className="ghost" title="Reset">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M14 4v4h-4M2 12V8h4"/><path d="M14 8a6 6 0 0 0-10-3M2 8a6 6 0 0 0 10 3"/>
          </svg>
        </button>
        <button className="ghost" title="Pop out">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M9 2h5v5M14 2L8 8M7 4H3v9h9V9"/>
          </svg>
        </button>
        <button className="ghost x" onClick={onClose} title="Close">{Pi.close}</button>
      </div>
      <div className="stage-panel-bd">
        <div className="sv-stage sv-stage-grid">
          <div className="sv-grid-back">UPSTAGE</div>
          <div className="sv-grid-axis-x" />
          <div className="sv-grid-axis-y" />
          <div className="sv-grid-front">DOWNSTAGE</div>
          {fixtures.map(f => {
            const live = deriveLive(f);
            const sel = f.id === selectedId;
            return (
              <div key={f.id}
                   className={"sv-pos" + (sel ? " sel-pos" : "")}
                   style={{ left: f.x + "%", top: f.y + "%" }}
                   onClick={(e) => { e.stopPropagation(); onSelect(sel ? null : f.id); }}>
                <ToggleMarker f={f} live={live} selected={sel} showBeams={true} scale={0.85} />
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── Variation C: OVERLAY — full content area takeover ─────────────
function StageOverlay({ selectedId, onSelect, onClose }) {
  const [groupFilter, setGroupFilter] = stUseState(null);
  const allFixtures = window.PATCH_DATA.fixtures.filter(f => typeof f.x === "number");
  const fixtures = allFixtures.map(f => ({
    ...f,
    dim: groupFilter && !(f.groups || []).includes(groupFilter)
  }));

  return (
    <div className="stage-overlay">
      <div className="stage-overlay-hd">
        <div className="t">Stage</div>
        <div className="sub">{allFixtures.length} fixtures · click to control</div>
        <div className="sp" />
        <button className="ghost" title="Reset">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M14 4v4h-4M2 12V8h4"/><path d="M14 8a6 6 0 0 0-10-3M2 8a6 6 0 0 0 10 3"/>
          </svg>
        </button>
        <button className="ghost x" onClick={onClose} title="Close (Esc)">{Pi.close}</button>
      </div>
      <div className="stage-overlay-chips">
        <button className={"sv-chip" + (!groupFilter ? " on" : "")} onClick={() => setGroupFilter(null)}>
          All<span className="n">{allFixtures.length}</span>
        </button>
        {window.PATCH_DATA.groups.filter(g => g.count > 0).map(g => (
          <button key={g.id}
            className={"sv-chip" + (groupFilter === g.id ? " on" : "")}
            onClick={() => setGroupFilter(g.id === groupFilter ? null : g.id)}>
            {g.name}<span className="n">{g.count}</span>
          </button>
        ))}
      </div>
      <div className="stage-overlay-bd">
        <div className="sv-stage sv-stage-void"
             onClick={() => onSelect(null)}>
          <div className="sv-void-floor" />
          <div className="sv-void-front">DOWNSTAGE</div>
          {fixtures.map(f => {
            const live = deriveLive(f);
            const sel = f.id === selectedId;
            return (
              <div key={f.id}
                   className={"sv-pos" + (f.dim ? " dim" : "") + (sel ? " sel-pos" : "")}
                   style={{ left: f.x + "%", top: f.y + "%" }}
                   onClick={(e) => { e.stopPropagation(); onSelect(sel ? null : f.id); }}>
                <ToggleMarker f={f} live={live} selected={sel} showBeams={true} scale={1.1} />
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── Inline fixture inspector strip (for selected) ─────────────────
function StageFixtureBar({ fixture, onClose, position = "bottom" }) {
  if (!fixture) return null;
  const live = deriveLive(fixture);
  return (
    <div className={"stage-fixbar stage-fixbar-" + position}>
      <div className="fb-id">
        <div className="sw" style={{ background: live.color }} />
        <div className="meta">
          <div className="n">{fixture.name}</div>
          <div className="t">{fixture.type}{fixture.pos ? ` · ${fixture.pos}` : ""}</div>
        </div>
      </div>
      <div className="fb-row">
        <span className="lbl">Dimmer</span>
        <div className="track"><div className="fill" style={{ width: live.i + "%" }} /></div>
        <span className="num">{live.i}%</span>
      </div>
      {fixture.fxKind !== "haze" && (
        <div className="fb-row">
          <span className="lbl">Colour</span>
          <div className="fb-color" style={{ background: live.color }} />
          <span className="num mono">{live.color}</span>
        </div>
      )}
      <div className="sp" />
      <button className="sv-park">
        <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8">
          <rect x="3" y="7" width="10" height="7" rx="1"/><path d="M5 7V4a3 3 0 0 1 6 0v3"/>
        </svg> Unpark <span className="ch">{fixture.chCount}</span>
      </button>
      <button className="fb-x" onClick={onClose}>{Pi.close}</button>
    </div>
  );
}

// ─── Trigger portal: replaces topbar buttons with a stage-aware set ──
// Listens for the .pv-topbar inside our subtree and injects a Stage button.
function StageTriggerPortal({ open, onToggle, hostRef }) {
  const [target, setTarget] = stUseState(null);
  stUseEffect(() => {
    if (!hostRef.current) return;
    // Find the FIRST .pv-topbar in this host (each variation renders one)
    const tb = hostRef.current.querySelector(".pv-topbar");
    if (!tb) return;
    // Find the existing FX button to insert before it (so Stage sits between Table and FX)
    const buttons = tb.querySelectorAll(".iconbtn");
    if (buttons.length < 2) return;
    // Create a slot div if not already present
    let slot = tb.querySelector(".stage-trigger-slot");
    if (!slot) {
      slot = document.createElement("span");
      slot.className = "stage-trigger-slot";
      slot.style.display = "contents";
      // Insert before the 3rd icon button (FX) — i.e. after Grid + Table
      buttons[2].before(slot);
    }
    setTarget(slot);
    return () => { slot && slot.remove(); };
  }, [hostRef]);

  if (!target) return null;
  return ReactDOM.createPortal(
    <button
      className={"iconbtn stage-trigger" + (open ? " on" : "")}
      title={open ? "Close Stage" : "Open Stage"}
      onClick={onToggle}
    >
      {StageIcon}
    </button>,
    target
  );
}

// ─── Host wrapper: wraps a Patch variation, adds the Stage toggle ───
function StageToggleHost({
  mode = "strip", // 'strip' | 'panel' | 'overlay'
  defaultOpen = false,
  defaultSelectedId = null,
  children,
}) {
  const [open, setOpen] = stUseState(defaultOpen);
  const [selectedId, setSelectedId] = stUseState(defaultSelectedId);
  const hostRef = stUseRef(null);

  const fixture = selectedId
    ? window.PATCH_DATA.fixtures.find(f => f.id === selectedId)
    : null;

  return (
    <div ref={hostRef} className={"stage-host stage-host-" + mode + (open ? " open" : "")}>
      {children}
      <StageTriggerPortal open={open} onToggle={() => setOpen(o => !o)} hostRef={hostRef} />
      {/* Strip: drops in directly below topbar */}
      {open && mode === "strip" && (
        <div className="stage-strip-mount">
          <StageStrip
            selectedId={selectedId}
            onSelect={setSelectedId}
            onClose={() => setOpen(false)}
          />
          {fixture && (
            <StageFixtureBar
              fixture={fixture}
              onClose={() => setSelectedId(null)}
              position="strip"
            />
          )}
        </div>
      )}
      {/* Panel: half-height slide-down, page squished below */}
      {open && mode === "panel" && (
        <div className="stage-panel-mount">
          <StagePanel
            selectedId={selectedId}
            onSelect={setSelectedId}
            onClose={() => setOpen(false)}
          />
          {fixture && (
            <StageFixtureBar
              fixture={fixture}
              onClose={() => setSelectedId(null)}
              position="bottom"
            />
          )}
        </div>
      )}
      {/* Overlay: covers the entire content area */}
      {open && mode === "overlay" && (
        <div className="stage-overlay-mount">
          <StageOverlay
            selectedId={selectedId}
            onSelect={setSelectedId}
            onClose={() => setOpen(false)}
          />
          {fixture && (
            <StageFixtureBar
              fixture={fixture}
              onClose={() => setSelectedId(null)}
              position="overlay"
            />
          )}
        </div>
      )}
    </div>
  );
}

Object.assign(window, {
  StageToggleHost, StageAwareTopbar, StageIcon, StageTriggerPortal,
  StageStrip, StagePanel, StageOverlay, StageFixtureBar,
});
