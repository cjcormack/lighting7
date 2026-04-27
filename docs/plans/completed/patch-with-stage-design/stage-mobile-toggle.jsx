// Mobile Stage toggle — full-screen overlay invoked from a mobile topbar icon.

const { useState: smtState } = React;

// A mobile patch shell with a topbar that includes the Stage toggle button.
// `inner` is the Patch screen (PvmVariationA / B / C) — it renders inside the shell.
function MobileStageToggleHost({ defaultOpen = false, defaultSelectedId = null, children }) {
  const [open, setOpen] = smtState(defaultOpen);
  const [selectedId, setSelectedId] = smtState(defaultSelectedId);
  const fixture = selectedId
    ? window.PATCH_DATA.fixtures.find(f => f.id === selectedId)
    : null;

  return (
    <div className="mob-stage-host" style={{ position: "relative", width: "100%", height: "100%" }}>
      {/* Render the underlying patch screen */}
      {children}
      {/* Floating topbar Stage trigger pinned over the page header */}
      <button
        className={"mob-stage-trigger" + (open ? " on" : "")}
        onClick={() => setOpen(o => !o)}
        title="Stage"
      >
        {StageIcon}
      </button>
      {/* Full-screen overlay */}
      {open && (
        <div className="stage-mob-mount">
          <div className="stage-mob-hd">
            <div className="t">Stage</div>
            <button className="ghost" title="Reset">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M14 4v4h-4M2 12V8h4"/>
                <path d="M14 8a6 6 0 0 0-10-3M2 8a6 6 0 0 0 10 3"/>
              </svg>
            </button>
            <button className="ghost" onClick={() => setOpen(false)} title="Close">
              {Pi.close}
            </button>
          </div>
          <div className="svm-chips">
            <button className="sv-chip on">All<span className="n">{window.PATCH_DATA.fixtures.length}</span></button>
            {window.PATCH_DATA.groups.filter(g => g.count > 0).slice(0, 4).map(g => (
              <button key={g.id} className="sv-chip">{g.name}<span className="n">{g.count}</span></button>
            ))}
          </div>
          <div className="svm-stage-wrap">
            <div className="sv-stage sv-stage-void"
                 onClick={() => setSelectedId(null)}>
              <div className="sv-void-floor" />
              <div className="sv-void-front">DOWNSTAGE</div>
              {window.PATCH_DATA.fixtures.filter(f => typeof f.x === "number").map(f => {
                const live = deriveLive(f);
                const sel = f.id === selectedId;
                return (
                  <div key={f.id}
                       className={"sv-pos" + (sel ? " sel-pos" : "")}
                       style={{ left: f.x + "%", top: f.y + "%" }}
                       onClick={(e) => { e.stopPropagation(); setSelectedId(sel ? null : f.id); }}>
                    <ToggleMarker f={f} live={live} selected={sel} showBeams={true} scale={0.9} />
                  </div>
                );
              })}
            </div>
          </div>
          {fixture && (
            <div className="svm-bottomsheet">
              <div className="svm-bs-grab" />
              <div className="svm-bs-hd">
                <div className="ttl">
                  <h3>{fixture.name}</h3>
                  <div className="sub">{fixture.type}{fixture.pos ? ` · ${fixture.pos}` : ""}</div>
                </div>
                <button className="sv-park">
                  <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8">
                    <rect x="3" y="7" width="10" height="7" rx="1"/>
                    <path d="M5 7V4a3 3 0 0 1 6 0v3"/>
                  </svg> Unpark <span className="ch">{fixture.chCount}</span>
                </button>
                <button className="x" onClick={() => setSelectedId(null)}>{Pi.close}</button>
              </div>
              <div className="svm-bs-bd">
                {(() => { const live = deriveLive(fixture); return (
                  <>
                    {fixture.fxKind !== "haze" && (
                      <div className="sv-row">
                        <div className="lbl-block">Colour</div>
                        <div className="sv-color">
                          <div className="sw" style={{ background: live.color }} />
                          <div className="vals">live</div>
                        </div>
                      </div>
                    )}
                    <div className="sv-row">
                      <div className="lbl-block">Dimmer</div>
                      <div className="sv-slider">
                        <div className="track"><div className="fill" style={{ width: live.i + "%" }} /></div>
                        <span className="num">{live.i}%</span>
                      </div>
                    </div>
                  </>
                ); })()}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

Object.assign(window, { MobileStageToggleHost });
