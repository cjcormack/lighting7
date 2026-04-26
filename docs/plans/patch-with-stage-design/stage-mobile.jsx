// Mobile stage view + integration into the main HTML

const { useState: smState } = React;

function StageMobile({ markerStyle = "live", bgKind = "schematic", showBeams = true }) {
  const [groupFilter, setGroupFilter] = smState(null);
  const [selectedId, setSelectedId] = smState(null);
  const Marker = MARKER_BY[markerStyle];
  const fixtures = window.PATCH_DATA.fixtures
    .filter(f => typeof f.x === "number")
    .map(f => ({ ...f, dim: groupFilter && !(f.groups || []).includes(groupFilter) }));
  const selected = window.PATCH_DATA.fixtures.find(f => f.id === selectedId);

  return (
    <div className="pvm-mount" style={{ position: "relative" }}>
      <div className="svm-shell">
        <div className="svm-hd">
          <div className="ttl">Stage</div>
          <button title="Reset"><svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M14 4v4h-4M2 12V8h4"/><path d="M14 8a6 6 0 0 0-10-3M2 8a6 6 0 0 0 10 3"/></svg></button>
        </div>
        <div className="svm-chips">
          <button className={"sv-chip" + (!groupFilter ? " on" : "")} onClick={() => setGroupFilter(null)}>All<span className="n">{window.PATCH_DATA.fixtures.length}</span></button>
          {window.PATCH_DATA.groups.filter(g => g.count > 0).map(g => (
            <button key={g.id} className={"sv-chip" + (groupFilter === g.id ? " on" : "")} onClick={() => setGroupFilter(g.id === groupFilter ? null : g.id)}>{g.name}<span className="n">{g.count}</span></button>
          ))}
        </div>
        <div className="svm-stage-wrap">
          <StageBackdrop kind={bgKind} onBgClick={() => setSelectedId(null)}>
            {fixtures.map(f => {
              const live = deriveLive(f);
              return (
                <div key={f.id}
                     className={"sv-pos" + (f.dim ? " dim" : "") + (selectedId === f.id ? " sel-pos" : "")}
                     style={{ left: f.x + "%", top: f.y + "%" }}
                     onClick={(e) => { e.stopPropagation(); setSelectedId(f.id); }}>
                  <Marker f={f} live={live} selected={selectedId === f.id} showBeams={showBeams} />
                </div>
              );
            })}
          </StageBackdrop>
        </div>
      </div>
      {selected && (
        <div className="svm-bottomsheet">
          <div className="svm-bs-grab" />
          <div className="svm-bs-hd">
            <div className="ttl">
              <h3>{selected.name}</h3>
              <div className="sub">{selected.type}{selected.pos ? ` · ${selected.pos}` : ""}</div>
            </div>
            <button className="sv-park"><svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8"><rect x="3" y="7" width="10" height="7" rx="1"/><path d="M5 7V4a3 3 0 0 1 6 0v3"/></svg> Unpark <span className="ch">{selected.chCount}</span></button>
            <button className="x" onClick={() => setSelectedId(null)}>{Pi.close}</button>
          </div>
          <div className="svm-bs-bd">
            <div className="sv-tabs"><button className="on">Dimmer</button><button>Colour</button><button>Strobe</button></div>
            {(() => { const live = deriveLive(selected); return (
              <>
                {selected.fxKind !== "haze" && (
                  <div className="sv-row">
                    <div className="lbl-block">Colour</div>
                    <div className="sv-color"><div className="sw" style={{ background: live.color }} /><div className="vals">live</div></div>
                  </div>
                )}
                <div className="sv-row">
                  <div className="lbl-block">Dimmer</div>
                  <div className="sv-slider"><div className="track"><div className="fill" style={{ width: live.i + "%" }} /></div><span className="num">{live.i}%</span></div>
                </div>
              </>
            ); })()}
          </div>
        </div>
      )}
    </div>
  );
}

Object.assign(window, { StageMobile });
