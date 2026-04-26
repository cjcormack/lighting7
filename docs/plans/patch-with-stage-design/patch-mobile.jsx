// Mobile (iOS) versions of the three variations.
// Renders inside an iOS frame; the patch panel is presented as a full-screen sheet.

const { useState: umState, useRef: umRef } = React;

// ─── Mobile chrome (Patch Fixture sheet header used in screenshots) ────
function PvMobileSheet({ title, subtitle, onBack, onClose, children, footer }) {
  return (
    <div className="pvm-sheet">
      <div className="pvm-sheet-hd">
        <button className="pvm-back" onClick={onBack}>{Pi.back}</button>
        <div className="pvm-titles">
          <div className="ttl">{title}</div>
          {subtitle && <div className="sub">{subtitle}</div>}
        </div>
        <button className="pvm-close" onClick={onClose}>{Pi.close}</button>
      </div>
      <div className="pvm-sheet-bd">{children}</div>
      {footer && <div className="pvm-sheet-ft">{footer}</div>}
    </div>
  );
}

// Mobile-tuned fields
function PvmField({ label, children, hint }) {
  return (
    <div className="pvm-field">
      <div className="pvm-flbl">{label}</div>
      {children}
      {hint && <div className="pvm-fhint">{hint}</div>}
    </div>
  );
}

function PvmAddressRow({ f }) {
  return (
    <div className="pvm-addr">
      <PvmField label="Universe"><input className="pvm-input mono" defaultValue={f.uni ?? 0} /></PvmField>
      <PvmField label="Start Channel" hint={`${f.chCount} ch · ${f.ch}–${f.ch + f.chCount - 1}`}><input className="pvm-input mono" defaultValue={f.ch} /></PvmField>
    </div>
  );
}

function PvmIdentity({ f }) {
  return (
    <>
      <PvmAddressRow f={f} />
      <PvmField label="Name"><input className="pvm-input" defaultValue={f.name} /></PvmField>
      <PvmField label="Key"><input className="pvm-input mono" defaultValue={f.key} /></PvmField>
      <PvmField label="Group"><input className="pvm-input" placeholder="Type to assign or create…" /></PvmField>
    </>
  );
}

// Mobile position chips (recent + active free input)
function PvmPositionField({ value, onChange }) {
  const presets = window.PATCH_DATA.positionPresets;
  return (
    <PvmField label="Position">
      <input className="pvm-input mono" placeholder="e.g. LX1, ADV 2" value={value || ""} onChange={(e) => onChange(e.target.value.toUpperCase())} />
      <div className="pvm-chips">
        {presets.slice(0, 7).map(p => (
          <button key={p} className={"pvm-chip" + (value === p ? " on" : "")} onClick={() => onChange(p)}>{p}</button>
        ))}
      </div>
    </PvmField>
  );
}

// Mobile beam picker
function PvmBeamField({ value, onChange }) {
  const presets = window.PATCH_DATA.beamPresets;
  return (
    <PvmField label="Beam Angle">
      <div className="pvm-beam">
        <input className="pvm-input mono" type="number" min="2" max="120" value={value ?? ""} onChange={(e) => onChange(parseInt(e.target.value, 10) || 0)} />
        <span className="deg">°</span>
      </div>
      <div className="pvm-chips">
        {presets.map(p => (
          <button key={p.name} className={"pvm-chip" + (value === p.deg ? " on" : "")} onClick={() => onChange(p.deg)}>
            {p.name}<span className="num">{p.deg}°</span>
          </button>
        ))}
      </div>
    </PvmField>
  );
}

// Mobile gel picker (inline, no popover — opens a full-height list below)
function PvmGelField({ value, onChange }) {
  const [open, setOpen] = umState(false);
  const [q, setQ] = umState("");
  const [brand, setBrand] = umState("All");
  const current = window.PATCH_HELP.findGel(value);
  const results = window.PATCH_HELP.searchGels(q, brand);

  return (
    <PvmField label="Gel">
      <div className="pvm-gel" onClick={() => setOpen(o => !o)}>
        <div className="sw" style={{ background: current?.color || "transparent", borderStyle: current ? "solid" : "dashed" }} />
        {current ? (
          <div className="info">
            <div className="code">{current.code} <span className="brand">{current.brand}</span></div>
            <div className="name">{current.name}</div>
          </div>
        ) : (
          <div className="info"><div className="none">Open white — no gel</div></div>
        )}
        {value && <button className="x" onClick={(e) => { e.stopPropagation(); onChange(null); }}>{Pi.close}</button>}
        <span className="chev">{Pi.chev}</span>
      </div>
      {open && (
        <div className="pvm-gel-list-wrap">
          <div className="pvm-gel-search-row">
            <span className="ic">{Pi.search}</span>
            <input placeholder="Search gels (L201, blue, amber)…" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="pvm-gel-tabs">
            {["All", "Lee", "Rosco"].map(b => (
              <button key={b} className={brand === b ? "on" : ""} onClick={() => setBrand(b)}>{b}</button>
            ))}
          </div>
          <div className="pvm-gel-list">
            <div className="pvm-gel-row" onClick={() => { onChange(null); setOpen(false); }}>
              <div className="sw" style={{ background: "transparent", borderStyle: "dashed" }} />
              <div className="code">—</div><div className="name" style={{fontStyle:"italic", color:"var(--text-faint)"}}>Open white</div>
            </div>
            {results.slice(0, 30).map(g => (
              <div key={g.brand + g.code} className="pvm-gel-row" onClick={() => { onChange(g.code); setOpen(false); }}>
                <div className="sw" style={{ background: g.color }} />
                <div className="code">{g.code}</div>
                <div className="name">{g.name}<span className="brand">{g.brand}</span></div>
              </div>
            ))}
          </div>
        </div>
      )}
    </PvmField>
  );
}

// ─── Mobile Patch List screen (chrome-only — for Stage Toggle artboards) ──
function PvmPatchList() {
  const fixtures = window.PATCH_DATA.fixtures;
  return (
    <div className="pvm-list">
      <div className="pvm-list-hd">
        <div className="pvm-list-ttl">
          <h1>Patch List</h1>
          <div className="sub">{fixtures.length} fixtures · 7 groups</div>
        </div>
        <button className="pvm-list-add">{Pi.plus}</button>
      </div>
      <div className="pvm-list-search">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="7" cy="7" r="5"/><path d="m11 11 3 3"/></svg>
        <input placeholder="Search fixtures…" />
      </div>
      <div className="pvm-list-rows">
        {fixtures.map(f => (
          <div key={f.id} className="pvm-list-row">
            <div className="addr">U{f.uni ?? 0}.{String(f.ch).padStart(3, "0")}</div>
            <div className="meta">
              <div className="n">{f.name}</div>
              <div className="t">{f.type}{f.pos ? ` · ${f.pos}` : ""}</div>
            </div>
            <div className="ch">{f.chCount}ch</div>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, { PvmPatchList });

// ─── Variation A (mobile) — drag-on-stage map ────────────────────
function PvmVariationA() {
  const fx = window.PATCH_DATA.fixtures.find(f => f.id === "spot-l");
  const [pos, setPos] = umState({ x: fx.x ?? 50, y: fx.y ?? 50 });
  const [position, setPosition] = umState(fx.pos || "");
  const [dragging, setDragging] = umState(false);
  const stageRef = umRef(null);

  const place = (clientX, clientY) => {
    const r = stageRef.current.getBoundingClientRect();
    setPos({
      x: Math.max(2, Math.min(98, ((clientX - r.left) / r.width) * 100)),
      y: Math.max(4, Math.min(94, ((clientY - r.top) / r.height) * 100)),
    });
  };

  const ctxFixtures = window.PATCH_HELP.contextFixtures();

  return (
    <PvMobileSheet
      title="Patch Fixture"
      subtitle={`${fx.type} · ${fx.chCount}ch`}
      footer={<><button className="pvm-secondary">Close</button><button className="pvm-primary">Patch</button></>}
    >
      <PvmIdentity f={fx} />
      <PvmField label="Stage Position" hint="Drag the dot to place the fixture">
        <div
          ref={stageRef}
          className="pvm-stage"
          onTouchStart={(e) => { const t = e.touches[0]; place(t.clientX, t.clientY); setDragging(true); }}
          onTouchMove={(e) => { if (!dragging) return; const t = e.touches[0]; place(t.clientX, t.clientY); }}
          onTouchEnd={() => setDragging(false)}
          onMouseDown={(e) => { place(e.clientX, e.clientY); setDragging(true); }}
          onMouseMove={(e) => { if (dragging) place(e.clientX, e.clientY); }}
          onMouseUp={() => setDragging(false)}
          onMouseLeave={() => setDragging(false)}
        >
          <div className="pv-stage-back">UPSTAGE</div>
          <div className="pv-stage-axis" />
          <div className="pv-stage-front">DOWNSTAGE</div>
          {ctxFixtures.filter(o => o.id !== fx.id).map(o => (
            <div key={o.id} className="pv-stage-fixture ctx" style={{ left: o.x + "%", top: o.y + "%" }} />
          ))}
          <div className="pv-stage-fixture sel" style={{ left: pos.x + "%", top: pos.y + "%" }}>
            <span className="lbl">{fx.name}</span>
          </div>
          <div className="pv-stage-readout">x {pos.x.toFixed(0)} · y {pos.y.toFixed(0)}</div>
        </div>
      </PvmField>
      <PvmPositionField value={position} onChange={setPosition} />
    </PvMobileSheet>
  );
}

// ─── Variation B (mobile) — XY pad + numeric ─────────────────────
function PvmVariationB() {
  const fx = window.PATCH_DATA.fixtures.find(f => f.id === "spot-r");
  const [pos, setPos] = umState({ x: fx.x ?? 50, y: fx.y ?? 50, z: 4.0 });
  const [position, setPosition] = umState(fx.pos || "");
  const padRef = umRef(null);

  const place = (clientX, clientY) => {
    const r = padRef.current.getBoundingClientRect();
    setPos(p => ({
      ...p,
      x: Math.max(0, Math.min(100, ((clientX - r.left) / r.width) * 100)),
      y: Math.max(0, Math.min(100, ((clientY - r.top) / r.height) * 100)),
    }));
  };

  return (
    <PvMobileSheet
      title="Patch Fixture"
      subtitle={`${fx.type} · ${fx.chCount}ch`}
      footer={<><button className="pvm-secondary">Close</button><button className="pvm-primary">Patch</button></>}
    >
      <PvmIdentity f={fx} />
      <PvmPositionField value={position} onChange={setPosition} />
      <PvmField label="Stage Coordinates" hint="X · Y in metres from stage centre">
        <div
          ref={padRef}
          className="pv-xy-pad"
          style={{ aspectRatio: "1.4", marginTop: 0 }}
          onTouchStart={(e) => { const t = e.touches[0]; place(t.clientX, t.clientY); }}
          onTouchMove={(e) => { const t = e.touches[0]; place(t.clientX, t.clientY); }}
          onMouseDown={(e) => place(e.clientX, e.clientY)}
        >
          <div className="axis-x" /><div className="axis-y" />
          <span className="lbl-corner" style={{top:4, left:6}}>up</span>
          <span className="lbl-corner" style={{bottom:4, left:6}}>down</span>
          <div className="dot" style={{ left: pos.x + "%", top: pos.y + "%" }} />
        </div>
        <div className="pvm-xyz">
          <div className="cell"><span className="lbl-sm">X</span><input className="pvm-input mono" type="number" value={pos.x.toFixed(1)} onChange={(e) => setPos(p => ({ ...p, x: parseFloat(e.target.value) || 0 }))} /></div>
          <div className="cell"><span className="lbl-sm">Y</span><input className="pvm-input mono" type="number" value={pos.y.toFixed(1)} onChange={(e) => setPos(p => ({ ...p, y: parseFloat(e.target.value) || 0 }))} /></div>
          <div className="cell"><span className="lbl-sm">Z</span><input className="pvm-input mono" type="number" step="0.1" value={pos.z.toFixed(1)} onChange={(e) => setPos(p => ({ ...p, z: parseFloat(e.target.value) || 0 }))} /></div>
        </div>
      </PvmField>
    </PvMobileSheet>
  );
}

// ─── Variation C (mobile) — Position-driven (generic dimmer) ─────
function PvmVariationC() {
  const fx = window.PATCH_DATA.fixtures.find(f => f.id === "sdff");
  const [position, setPosition] = umState(fx.pos || "");
  const [offset, setOffset] = umState(50);
  const [beam, setBeam] = umState(fx.beam ?? 26);
  const [gel, setGel] = umState(fx.gel || null);

  const positionMeta = (p) => {
    const count = window.PATCH_DATA.fixtures.filter(o => o.pos === p).length;
    return count === 0 ? "empty" : count === 1 ? "1 fixture" : count + " fixtures";
  };

  return (
    <PvMobileSheet
      title="Patch Fixture"
      subtitle="Generic Dimmer · 1ch"
      footer={<><button className="pvm-secondary">Close</button><button className="pvm-primary">Patch</button></>}
    >
      <PvmIdentity f={fx} />

      <div className="pvm-section-h">RIGGING POSITION</div>
      <div className="pvm-positions">
        {window.PATCH_DATA.positionPresets.slice(0, 6).map(p => (
          <button key={p} className={"pos-card" + (position === p ? " on" : "")} onClick={() => setPosition(p)}>
            <span className="name">{p}</span>
            <span className="meta">{positionMeta(p)}</span>
          </button>
        ))}
        <button className="pos-card pos-add" onClick={() => { const v = prompt("New position", ""); if (v) setPosition(v.toUpperCase()); }}>
          {Pi.plus}<span style={{marginTop: 2, fontSize: 11}}>New</span>
        </button>
      </div>
      <input className="pvm-input mono" style={{marginTop: 8}} placeholder="Or custom…" value={position} onChange={(e) => setPosition(e.target.value.toUpperCase())} />

      {position && (
        <PvmField label="Bar Offset">
          <div className="pv-bar-vis">
            <div className="truss" />
            <div className="clamp-self" style={{ left: offset + "%" }} />
            <div className="ruler" />
          </div>
          <input type="range" min="0" max="100" value={offset} onChange={(e) => setOffset(parseInt(e.target.value, 10))} style={{ width: "100%", accentColor: "var(--accent)" }} />
        </PvmField>
      )}

      <div className="pvm-section-h">GENERIC DIMMER</div>
      <PvmBeamField value={beam} onChange={setBeam} />
      <PvmGelField value={gel} onChange={setGel} />
    </PvMobileSheet>
  );
}

Object.assign(window, { PvmVariationA, PvmVariationB, PvmVariationC });
