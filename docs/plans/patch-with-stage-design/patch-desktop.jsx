// Three desktop variations of the patch panel.
// Each renders inside the patch-list backdrop with the side panel docked right.

const { useState: uvState, useRef: uvRef } = React;

// ─── Backdrop (the page behind the panel) ────────────────────
function PvBackdrop({ selectedId, onSelect, showPosition }) {
  return (
    <>
      <PvSidebar />
      <div className="pv-main">
        <PvTopbar />
        <PvCrumbs />
        <div className="pv-content">
          <div className="pv-page-h">
            <div>
              <h1>Patch List</h1>
              <div className="pv-page-sub">20 fixtures patched, 7 groups.</div>
            </div>
            <button className="pv-btn-primary">{Pi.plus} Patch</button>
          </div>
          <div className="pv-univ-row">
            <span className="lbl">Universes</span>
            <span className="pill">U0 <em>no address</em></span>
            <span className="pill">U1 <em>no address</em></span>
          </div>
          <div className="pv-grp-row">
            <span className="lbl">Groups</span>
            {window.PATCH_DATA.groups.map(g => (
              <span key={g.id} className="gp">{g.name}<span className="n">{g.count}</span></span>
            ))}
          </div>
          <PvPatchList selectedId={selectedId} onSelect={onSelect} showPosition={showPosition} />
        </div>
      </div>
    </>
  );
}

// ─── Common panel header + footer ────────────────────────────
function PvPanelShell({ fixture, children, footerExtras }) {
  return (
    <div className="pv-panel">
      <div className="pv-panel-hd">
        <button className="back">{Pi.back}</button>
        <div className="ttl">
          <h2>{fixture.type}</h2>
          <div className="sub">{fixture.chCount} channels · {fixture.mode || `${fixture.chCount}-Channel`}</div>
        </div>
        <button className="x">{Pi.close}</button>
      </div>
      <div className="pv-panel-bd">{children}</div>
      <div className="pv-panel-ft">
        {footerExtras}
        <button className="secondary">Close</button>
        <button className="primary">Save</button>
      </div>
    </div>
  );
}

// ─── Common address + identity rows ──────────────────────────
function PvIdentityRows({ f }) {
  return (
    <>
      <div className="pv-addr">
        <div className="field">
          <div className="pv-addr-label">UNIVERSE</div>
          <input className="pv-addr-input" defaultValue={f.uni ?? 0} style={{ borderColor: "var(--accent)", boxShadow: "0 0 0 2px var(--accent-soft)" }} />
        </div>
        <div className="field">
          <div className="pv-addr-label">START CHANNEL</div>
          <input className="pv-addr-input" defaultValue={f.ch} />
          <div className="pv-addr-helper">◇ <span>{f.chCount} channel · Channels {f.ch}–{f.ch + f.chCount - 1}</span></div>
        </div>
      </div>
      <div className="pv-row">
        <label className="pv-lbl">Name</label>
        <input className="pv-input" defaultValue={f.name} />
      </div>
      <div className="pv-row">
        <label className="pv-lbl">Key</label>
        <input className="pv-input mono" defaultValue={f.key} />
      </div>
      <div className="pv-row">
        <label className="pv-lbl">Group</label>
        <input className="pv-input" placeholder="Type to assign or create…" />
      </div>
    </>
  );
}

// ═══════════════════════════════════════════════════════════════
// VARIATION A — Inline mini-stage map (drag to place)
// ═══════════════════════════════════════════════════════════════
function PvVariationA({ fixtureId = "spot-l" }) {
  const [selId, setSelId] = uvState(fixtureId);
  const fx = window.PATCH_DATA.fixtures.find(f => f.id === selId) || window.PATCH_DATA.fixtures[0];
  const [pos, setPos] = uvState({ x: fx.x ?? 50, y: fx.y ?? 50 });
  const [position, setPosition] = uvState(fx.pos || "");
  const [beam, setBeam] = uvState(fx.beam ?? 26);
  const [gel, setGel] = uvState(fx.gel || null);
  const [dragging, setDragging] = uvState(false);
  const stageRef = uvRef(null);
  const isGeneric = fx.isGeneric || fx.type === "Generic Dimmer";

  React.useEffect(() => {
    setPos({ x: fx.x ?? 50, y: fx.y ?? 50 });
    setPosition(fx.pos || "");
    setBeam(fx.beam ?? 26);
    setGel(fx.gel || null);
  }, [selId]);

  const handleStageMove = (e) => {
    if (!dragging || !stageRef.current) return;
    const r = stageRef.current.getBoundingClientRect();
    const x = Math.max(2, Math.min(98, ((e.clientX - r.left) / r.width) * 100));
    const y = Math.max(4, Math.min(94, ((e.clientY - r.top) / r.height) * 100));
    setPos({ x, y });
  };

  const ctxFixtures = window.PATCH_HELP.contextFixtures();

  return (
    <div className="pv-app">
      <PvBackdrop selectedId={selId} onSelect={setSelId} showPosition={true} />
      <PvPanelShell fixture={fx}>
        <PvIdentityRows f={fx} />

        <PvSection title="Stage" meta="Position & rigging" defaultOpen={true} icon={<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="8" cy="8" r="2"/><path d="M8 1v2M8 13v2M1 8h2M13 8h2"/></svg>}>
          <div className="pv-stage-toolbar">
            <span style={{fontSize: 11, color: "var(--text-faint)"}}>Click or drag to place</span>
            <div style={{flex: 1}} />
            <button onClick={() => setPos({ x: 50, y: 50 })}>Center</button>
            <button className="clear" onClick={() => setPos({ x: -100, y: -100 })}>Unplace</button>
          </div>
          <div
            ref={stageRef}
            className="pv-stage"
            onMouseDown={(e) => {
              const r = stageRef.current.getBoundingClientRect();
              const x = ((e.clientX - r.left) / r.width) * 100;
              const y = ((e.clientY - r.top) / r.height) * 100;
              setPos({ x: Math.max(2, Math.min(98, x)), y: Math.max(4, Math.min(94, y)) });
              setDragging(true);
            }}
            onMouseMove={handleStageMove}
            onMouseUp={() => setDragging(false)}
            onMouseLeave={() => setDragging(false)}
          >
            <div className="pv-stage-back">UPSTAGE</div>
            <div className="pv-stage-axis" />
            <div className="pv-stage-front">DOWNSTAGE</div>
            <div className="pv-stage-hint">Stage map · drag dot</div>

            {ctxFixtures.filter(o => o.id !== fx.id).map(o => (
              <div key={o.id} className="pv-stage-fixture ctx" style={{ left: o.x + "%", top: o.y + "%" }} title={o.name}>
                <span className="lbl" style={{display: "none"}}>{o.name}</span>
              </div>
            ))}
            {pos.x >= 0 && pos.y >= 0 && (
              <div className="pv-stage-fixture sel" style={{ left: pos.x + "%", top: pos.y + "%" }}>
                <span className="lbl">{fx.name}{position ? ` · ${position}` : ""}</span>
              </div>
            )}
            <div className="pv-stage-readout">x {pos.x.toFixed(0)} · y {pos.y.toFixed(0)}</div>
          </div>
        </PvSection>

        <PvPositionInput value={position} onChange={setPosition} />

        {isGeneric && (
          <PvSection title="Generic Dimmer" meta="Beam & Gel" defaultOpen={true}>
            <PvBeamAngle value={beam} onChange={setBeam} />
            <PvGelPicker value={gel} onChange={setGel} />
          </PvSection>
        )}
      </PvPanelShell>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════
// VARIATION B — Numeric X/Y + small XY pad
// ═══════════════════════════════════════════════════════════════
function PvVariationB({ fixtureId = "spot-r" }) {
  const [selId, setSelId] = uvState(fixtureId);
  const fx = window.PATCH_DATA.fixtures.find(f => f.id === selId) || window.PATCH_DATA.fixtures[0];
  const [position, setPosition] = uvState(fx.pos || "");
  const [pos, setPos] = uvState({ x: fx.x ?? 50, y: fx.y ?? 50, z: 4.0 });
  const [beam, setBeam] = uvState(fx.beam ?? 26);
  const [gel, setGel] = uvState(fx.gel || null);
  const padRef = uvRef(null);
  const isGeneric = fx.isGeneric || fx.type === "Generic Dimmer";

  React.useEffect(() => {
    setPos({ x: fx.x ?? 50, y: fx.y ?? 50, z: 4.0 });
    setPosition(fx.pos || "");
    setBeam(fx.beam ?? 26);
    setGel(fx.gel || null);
  }, [selId]);

  const onPadDrag = (e) => {
    if (e.buttons !== 1) return;
    const r = padRef.current.getBoundingClientRect();
    setPos(p => ({ ...p, x: Math.max(0, Math.min(100, ((e.clientX - r.left) / r.width) * 100)), y: Math.max(0, Math.min(100, ((e.clientY - r.top) / r.height) * 100)) }));
  };

  return (
    <div className="pv-app">
      <PvBackdrop selectedId={selId} onSelect={setSelId} showPosition={true} />
      <PvPanelShell fixture={fx}>
        <PvIdentityRows f={fx} />
        <PvPositionInput value={position} onChange={setPosition} />

        <PvSection title="Stage Position" meta="X · Y · Z" defaultOpen={true}>
          <div className="pv-xy">
            <div className="field">
              <span className="lbl-sm">X (stage L–R)</span>
              <input className="pv-input mono" type="number" value={pos.x.toFixed(1)} onChange={(e) => setPos(p => ({ ...p, x: parseFloat(e.target.value) || 0 }))} />
            </div>
            <div className="field">
              <span className="lbl-sm">Y (depth)</span>
              <input className="pv-input mono" type="number" value={pos.y.toFixed(1)} onChange={(e) => setPos(p => ({ ...p, y: parseFloat(e.target.value) || 0 }))} />
            </div>
            <div className="field">
              <span className="lbl-sm">Z (height m)</span>
              <input className="pv-input mono" type="number" step="0.1" value={pos.z.toFixed(1)} onChange={(e) => setPos(p => ({ ...p, z: parseFloat(e.target.value) || 0 }))} />
            </div>
          </div>
          <div
            ref={padRef}
            className="pv-xy-pad"
            onMouseDown={onPadDrag}
            onMouseMove={onPadDrag}
          >
            <div className="axis-x" />
            <div className="axis-y" />
            <span className="lbl-corner" style={{top: 4, left: 6}}>upstage</span>
            <span className="lbl-corner" style={{bottom: 4, left: 6}}>downstage</span>
            <span className="lbl-corner" style={{bottom: 4, right: 6}}>SR</span>
            <span className="lbl-corner" style={{top: 4, right: 6}}>SR</span>
            <div className="dot" style={{ left: pos.x + "%", top: pos.y + "%" }} />
          </div>
        </PvSection>

        {isGeneric && (
          <>
            <PvBeamAngle value={beam} onChange={setBeam} />
            <PvGelPicker value={gel} onChange={setGel} />
          </>
        )}
      </PvPanelShell>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════
// VARIATION C — Position-driven (rigging bar pickers, no XY)
// ═══════════════════════════════════════════════════════════════
function PvVariationC({ fixtureId = "sdff" }) {
  const [selId, setSelId] = uvState(fixtureId);
  const fx = window.PATCH_DATA.fixtures.find(f => f.id === selId) || window.PATCH_DATA.fixtures[0];
  const [position, setPosition] = uvState(fx.pos || "");
  const [offset, setOffset] = uvState(50); // % along the bar
  const [beam, setBeam] = uvState(fx.beam ?? 26);
  const [gel, setGel] = uvState(fx.gel || null);
  const isGeneric = fx.isGeneric || fx.type === "Generic Dimmer";

  React.useEffect(() => {
    setPosition(fx.pos || "");
    setBeam(fx.beam ?? 26);
    setGel(fx.gel || null);
  }, [selId]);

  // Other fixtures sharing this rigging position
  const sameBar = window.PATCH_DATA.fixtures.filter(o => o.pos === position && o.id !== fx.id);

  const positionMeta = (p) => {
    const count = window.PATCH_DATA.fixtures.filter(o => o.pos === p).length;
    return count === 0 ? "empty" : count === 1 ? "1 fixture" : count + " fixtures";
  };

  return (
    <div className="pv-app">
      <PvBackdrop selectedId={selId} onSelect={setSelId} showPosition={true} />
      <PvPanelShell fixture={fx}>
        <PvIdentityRows f={fx} />

        <PvSection title="Rigging Position" meta={position || "unassigned"} defaultOpen={true} icon={<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 5h12M4 5v8M12 5v8"/><circle cx="8" cy="9" r="1.5"/></svg>}>
          <div className="pv-positions">
            {window.PATCH_DATA.positionPresets.slice(0, 8).map(p => (
              <button key={p} className={"pos-card" + (position === p ? " on" : "")} onClick={() => setPosition(p)}>
                <span className="name">{p}</span>
                <span className="meta">{positionMeta(p)}</span>
              </button>
            ))}
            <button className="pos-card pos-add" onClick={() => {
              const v = prompt("New rigging position name", "");
              if (v) setPosition(v.toUpperCase());
            }}>{Pi.plus} New</button>
          </div>

          <input className="pv-input mono" placeholder="Or type a custom position…"
                 style={{ marginTop: 10 }}
                 value={position} onChange={(e) => setPosition(e.target.value.toUpperCase())} />

          {position && (
            <>
              <div className="pv-bar-vis" title={`${position} — ${sameBar.length + 1} fixtures`}>
                <div className="truss" />
                {sameBar.map((o, i) => {
                  const left = ((i + 1) / (sameBar.length + 2)) * 100;
                  return <div key={o.id} className="clamp-other" style={{ left: left + "%" }} title={o.name} />;
                })}
                <div className="clamp-self" style={{ left: offset + "%" }} title={fx.name} />
                <div className="ruler" />
              </div>
              <div className="pv-position-slider">
                <span style={{fontSize: 11, color: "var(--text-faint)"}}>SL</span>
                <input type="range" min="0" max="100" value={offset} onChange={(e) => setOffset(parseInt(e.target.value, 10))} />
                <span style={{fontSize: 11, color: "var(--text-faint)"}}>SR</span>
                <span className="num">{offset}%</span>
              </div>
            </>
          )}
        </PvSection>

        {isGeneric && (
          <PvSection title="Generic Dimmer" meta="Beam & Gel" defaultOpen={true}>
            <PvBeamAngle value={beam} onChange={setBeam} />
            <PvGelPicker value={gel} onChange={setGel} />
          </PvSection>
        )}
      </PvPanelShell>
    </div>
  );
}

Object.assign(window, { PvVariationA, PvVariationB, PvVariationC, PvBackdrop, PvPanelShell, PvIdentityRows });

// ─── Chrome-only screen for the Stage Toggle artboards ──────────────
// Renders the full app shell (sidebar + topbar + crumbs + Patch List) with
// no fixture sheet open on the right — like the user's screenshot reference.
function PvChrome() {
  return (
    <div className="pv-app pv-app-chrome">
      <PvBackdrop selectedId={null} onSelect={() => {}} showPosition={true} />
    </div>
  );
}

Object.assign(window, { PvChrome });
