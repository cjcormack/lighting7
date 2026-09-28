import React, { useState, useMemo, useRef, useEffect, useCallback } from "react";

// =============================================================================
// lighting7 — Show Mode Prompt-Book PROTOTYPE
// =============================================================================
// This fakes everything that would be real in lighting7:
//   • the PDF      → a rendered mock script page (so the prototype is self-contained)
//   • the cue stack & activeCueId → a local stepper standing in for the upstream runner
//   • persistence  → in-memory only
//
// What's REAL here is the interaction model we're pressure-testing:
//   active-cue emphasis, scroll-to, the lock toggle + affordances, drag-to-move
//   when unlocked, single-step undo on misdrag, and live advisory desync warnings.
// All geometry is normalized [0..1], matching model.ts, so nothing depends on
// pixel sizes — resize the window and overlays track.
// =============================================================================

// ---- types (mirror of model.ts, inlined for the prototype) ----
type Rect = { page: number; x: number; y: number; w: number; h: number };
type Region = Rect[];
type CueAnchor = { id: string; cueId: string; region: Region; label: string };
type AnnKind = "note" | "strikethrough" | "freetext";
type Annotation = { id: string; kind: AnnKind; region: Region; text?: string };
type LockState = "locked" | "unlocked";

// ---- design tokens ----
// Direction: a lighting console at half-light. Near-black desk surface, paper
// that reads as a gelled wash rather than office white, and a single saturated
// "live" amber — the colour of a cue light — reserved ONLY for the active cue.
// Everything else stays desaturated so the live cue is unmistakable in a dark
// booth. Warnings borrow a gel-red; cuts go cold grey-blue (struck, dead).
const C = {
  desk: "#16181d",
  deskRaised: "#1e2128",
  paper: "#e8e3d6",
  paperEdge: "#cdc7b6",
  ink: "#23211c",
  inkSoft: "#6c6657",
  live: "#ffb000",      // cue light amber — the one bold colour
  liveSoft: "#ffb00022",
  pending: "#8a8470",   // future cue anchors, quiet
  done: "#5c6b5c",      // passed cues, cooled green-grey
  warn: "#e2483d",      // gel red
  cut: "#7d8794",       // struck / dead
  rule: "#2c2f37",
  textLo: "#9a978d",
};

// ---- a mock "script page" rendered as lines, so we have something to anchor to.
// In lighting7 this whole left-of-overlay layer is the PDF canvas + text layer.
const SCRIPT_LINES: { t: string; dir?: boolean }[] = [
  { t: "ACT ONE — The Enchanted Wood", dir: false },
  { t: "" },
  { t: "FAIRY:  Welcome, one and all, to our tale tonight!", },
  { t: "        (she gestures; the wood should bloom)", dir: true },
  { t: "" },
  { t: "KING:   A christening! Summon every soul in the land—", },
  { t: "        save one. Let HER name go unspoken.", },
  { t: "        (thunder. lights snap cold)", dir: true },
  { t: "" },
  { t: "CARABOSSE:  Unspoken? I think NOT. (cackles)", },
  { t: "        You'll rue this slight, your Majesty.", },
  { t: "" },
  { t: "        [SCENE CHANGE — the years pass]", dir: true },
  { t: "" },
  { t: "AURORA: Sixteen today! And not a care to find.", },
  { t: "        (she dances toward the tower stair)", dir: true },
  { t: "" },
  { t: "NURSE:  Mind the spindle, child — oh! Too late...", },
  { t: "        (AURORA pricks her finger. blackout.)", dir: true },
  { t: "" },
  { t: "        — INTERVAL —", dir: true },
];

// Map a line index to a normalized vertical band on the page.
const LINE_H = 1 / (SCRIPT_LINES.length + 2);
const lineRegion = (i: number, lines = 1): Region => [
  { page: 0, x: 0.06, y: (i + 1) * LINE_H, w: 0.88, h: LINE_H * lines },
];

// ---- faked upstream cue stack. In lighting7 this is the Show Mode runner. ----
const CUE_STACK: { cueId: string; label: string }[] = [
  { cueId: "q1", label: "LX 1" },
  { cueId: "q2", label: "LX 2" },
  { cueId: "q3", label: "LX 3" },
  { cueId: "q4", label: "LX 4" },
  { cueId: "q5", label: "LX 5" },
  { cueId: "q6", label: "LX 6" },
];
const CUE_ORDER = CUE_STACK.map((c) => c.cueId);

// Initial anchors. NOTE q5 is deliberately placed ABOVE q4 in the script to
// demonstrate live out-of-order detection, and q6 is left UNANCHORED.
const INITIAL_ANCHORS: CueAnchor[] = [
  { id: "a1", cueId: "q1", label: "LX 1", region: lineRegion(2) },
  { id: "a2", cueId: "q2", label: "LX 2", region: lineRegion(5, 2) },
  { id: "a3", cueId: "q3", label: "LX 3", region: lineRegion(9) },
  { id: "a5", cueId: "q5", label: "LX 5", region: lineRegion(12) }, // out of order
  { id: "a4", cueId: "q4", label: "LX 4", region: lineRegion(14) },
  // q6 intentionally has no anchor
];

const INITIAL_ANNS: Annotation[] = [
  { id: "n1", kind: "strikethrough", region: lineRegion(10) },
  { id: "n2", kind: "note", region: lineRegion(17), text: "slow build, 5s — watch conductor" },
];

// ---- desync logic (mirror of model.ts computeWarnings) ----
type Warning = { kind: string; cueId: string; anchorId?: string; message: string };
const pos = (r: Region) =>
  r.reduce((b, x) => (x.page < b.page || (x.page === b.page && x.y < b.y) ? { page: x.page, y: x.y } : b),
    { page: Infinity, y: Infinity });
const overlap = (a: Region, b: Region) =>
  a.some((ra) => b.some((rb) => ra.page === rb.page &&
    ra.x < rb.x + rb.w && ra.x + ra.w > rb.x && ra.y < rb.y + rb.h && ra.y + ra.h > rb.y));

function computeWarnings(anchors: CueAnchor[], anns: Annotation[]): Warning[] {
  const out: Warning[] = [];
  const byCue = new Map(anchors.map((a) => [a.cueId, a]));
  const cuts = anns.filter((n) => n.kind === "strikethrough");
  let prev: { page: number; y: number } | null = null;
  let prevLabel = "";
  for (const cueId of CUE_ORDER) {
    const a = byCue.get(cueId);
    const label = CUE_STACK.find((c) => c.cueId === cueId)?.label ?? cueId;
    if (!a) { out.push({ kind: "unanchored-cue", cueId, message: `${label} has no anchor on the script.` }); continue; }
    const p = pos(a.region);
    if (prev && (p.page < prev.page || (p.page === prev.page && p.y < prev.y)))
      out.push({ kind: "out-of-order", cueId, anchorId: a.id, message: `${label} sits earlier in the script than ${prevLabel}.` });
    if (cuts.some((c) => overlap(a.region, c.region)))
      out.push({ kind: "anchor-in-cut", cueId, anchorId: a.id, message: `${label} is anchored inside a cut section.` });
    prev = p; prevLabel = label;
  }
  return out;
}

// =============================================================================
export default function PromptBookPrototype() {
  const [anchors, setAnchors] = useState<CueAnchor[]>(INITIAL_ANCHORS);
  const [anns] = useState<Annotation[]>(INITIAL_ANNS);
  const [activeIdx, setActiveIdx] = useState(0);
  const [lock, setLock] = useState<LockState>("locked");
  const [undo, setUndo] = useState<CueAnchor | null>(null);
  const pageRef = useRef<HTMLDivElement>(null);

  const activeCueId = CUE_STACK[activeIdx]?.cueId ?? null;
  const warnings = useMemo(() => computeWarnings(anchors, anns), [anchors, anns]);
  const warnByCue = useMemo(() => {
    const m = new Map<string, Warning[]>();
    for (const w of warnings) m.set(w.cueId, [...(m.get(w.cueId) ?? []), w]);
    return m;
  }, [warnings]);

  const anchorForCue = (cueId: string) => anchors.find((a) => a.cueId === cueId);

  // status of a cue relative to the playhead
  const statusOf = (cueId: string): "done" | "live" | "pending" => {
    const i = CUE_ORDER.indexOf(cueId);
    if (cueId === activeCueId) return "live";
    return i < activeIdx ? "done" : "pending";
  };

  // ---- scroll active anchor into view on advance (the runtime-driven motion) ----
  useEffect(() => {
    const a = activeCueId ? anchorForCue(activeCueId) : null;
    if (!a || !pageRef.current) return;
    const y = pos(a.region).y;
    const el = pageRef.current;
    const target = y * el.scrollHeight - el.clientHeight * 0.4;
    el.scrollTo({ top: Math.max(0, target), behavior: "smooth" });
  }, [activeIdx]); // eslint-disable-line

  const advance = useCallback((d: number) =>
    setActiveIdx((i) => Math.max(0, Math.min(CUE_STACK.length - 1, i + d))), []);

  // keyboard: space / arrows advance, like a real GO surface
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (e.key === " " || e.key === "ArrowDown" || e.key === "ArrowRight") { e.preventDefault(); advance(1); }
      if (e.key === "ArrowUp" || e.key === "ArrowLeft") { e.preventDefault(); advance(-1); }
      if (e.key.toLowerCase() === "l") setLock((l) => (l === "locked" ? "unlocked" : "locked"));
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [advance]);

  // ---- drag to move an anchor (only when unlocked) ----
  const dragState = useRef<{ id: string; startY: number; origY: number } | null>(null);
  const onAnchorPointerDown = (e: React.PointerEvent, a: CueAnchor) => {
    if (lock === "locked") return;
    e.stopPropagation();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    setUndo(structuredClone(a));
    dragState.current = { id: a.id, startY: e.clientY, origY: a.region[0].y };
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const ds = dragState.current;
    if (!ds || !pageRef.current) return;
    const dy = (e.clientY - ds.startY) / pageRef.current.scrollHeight;
    const ny = Math.max(0, Math.min(1 - LINE_H, ds.origY + dy));
    setAnchors((prev) => prev.map((a) =>
      a.id === ds.id ? { ...a, region: [{ ...a.region[0], y: ny }] } : a));
  };
  const onPointerUp = () => { dragState.current = null; };

  return (
    <div style={{
      display: "grid", gridTemplateColumns: "minmax(280px, 1fr) minmax(320px, 380px)",
      height: "100vh", fontFamily: "'Inter', system-ui, sans-serif",
      background: C.desk, color: C.paper, overflow: "hidden",
    }}>
      {/* ============================== LEFT: SCRIPT ============================== */}
      <div style={{ display: "flex", flexDirection: "column", minWidth: 0, borderRight: `1px solid ${C.rule}` }}>
        <Bar lock={lock} setLock={setLock} active={CUE_STACK[activeIdx]} undo={undo}
          onUndo={() => { if (undo) { setAnchors((p) => p.map((a) => a.id === undo.id ? undo : a)); setUndo(null); } }} />
        <div ref={pageRef} onPointerMove={onPointerMove} onPointerUp={onPointerUp}
          style={{ flex: 1, overflowY: "auto", padding: "32px 28px", background: C.desk }}>
          {/* the "page" — stand-in for the PDF canvas + text layer */}
          <div style={{
            position: "relative", margin: "0 auto", maxWidth: 720, aspectRatio: "1 / 1.35",
            background: C.paper, color: C.ink, borderRadius: 2,
            boxShadow: `0 1px 0 ${C.paperEdge}, 0 20px 60px #0008`,
            padding: "5% 6%", boxSizing: "border-box",
          }}>
            {/* script text */}
            <div style={{ fontFamily: "'Courier New', monospace", fontSize: 13, lineHeight: `${LINE_H * 100}%`, height: "100%" }}>
              {SCRIPT_LINES.map((l, i) => (
                <div key={i} style={{
                  whiteSpace: "pre", color: l.dir ? C.inkSoft : C.ink,
                  fontStyle: l.dir ? "italic" : "normal", height: `${LINE_H * 100}%`,
                }}>{l.t}</div>
              ))}
            </div>

            {/* overlay layer — absolute, normalized coords → percentages */}
            <div style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
              {/* annotations under anchors */}
              {anns.map((n) => n.region.map((r, k) => (
                <div key={n.id + k} style={annStyle(n, r)}>
                  {n.kind === "note" && (
                    <div style={{
                      position: "absolute", right: -8, top: "50%", transform: "translate(100%,-50%)",
                      background: C.deskRaised, color: C.textLo, fontSize: 10, padding: "3px 7px",
                      borderRadius: 4, whiteSpace: "nowrap", border: `1px solid ${C.rule}`,
                    }}>✎ {n.text}</div>
                  )}
                </div>
              )))}
              {/* cue anchors */}
              {anchors.map((a) => {
                const st = statusOf(a.cueId);
                const w = warnByCue.get(a.cueId);
                return a.region.map((r, k) => (
                  <div key={a.id + k}
                    onPointerDown={(e) => onAnchorPointerDown(e, a)}
                    style={anchorStyle(r, st, !!w, lock)}>
                    <span style={{
                      position: "absolute", left: -10, top: "50%", transform: "translate(-100%,-50%)",
                      fontSize: 10, fontWeight: 700, letterSpacing: 0.3,
                      color: st === "live" ? C.live : st === "done" ? C.done : C.pending,
                      whiteSpace: "nowrap",
                    }}>{a.label}{w && <span style={{ color: C.warn }}> ▲</span>}</span>
                  </div>
                ));
              })}
            </div>
          </div>
          <p style={{ textAlign: "center", color: C.textLo, fontSize: 11, marginTop: 16 }}>
            Space / ↓ advances · ↑ steps back · L toggles lock
          </p>
        </div>
      </div>

      {/* ============================== RIGHT: CUE STACK ============================== */}
      <div style={{ display: "flex", flexDirection: "column", background: C.deskRaised, minWidth: 0 }}>
        <div style={{ padding: "16px 18px 12px", borderBottom: `1px solid ${C.rule}` }}>
          <div style={{ fontSize: 11, letterSpacing: 1.5, textTransform: "uppercase", color: C.textLo }}>Cue Stack</div>
          <div style={{ fontSize: 13, color: C.paper, marginTop: 2 }}>Sleeping Beauty — Act One</div>
        </div>

        {/* pre-flight desync summary */}
        {warnings.length > 0 && (
          <div style={{ margin: "12px 14px 4px", padding: "10px 12px", background: "#2a1c1b",
            border: `1px solid ${C.warn}55`, borderRadius: 6 }}>
            <div style={{ fontSize: 11, fontWeight: 700, color: C.warn, marginBottom: 6 }}>
              ▲ {warnings.length} issue{warnings.length > 1 ? "s" : ""} before you run
            </div>
            {warnings.map((w, i) => (
              <div key={i} style={{ fontSize: 11.5, color: "#e8c9c4", lineHeight: 1.4, marginTop: 3 }}>{w.message}</div>
            ))}
          </div>
        )}

        <div style={{ flex: 1, overflowY: "auto", padding: "8px 10px" }}>
          {CUE_STACK.map((c, i) => {
            const st = statusOf(c.cueId);
            const w = warnByCue.get(c.cueId);
            const anchored = !!anchorForCue(c.cueId);
            return (
              <button key={c.cueId} onClick={() => setActiveIdx(i)}
                style={{
                  display: "flex", alignItems: "center", gap: 10, width: "100%", textAlign: "left",
                  padding: "11px 12px", marginBottom: 4, borderRadius: 7, cursor: "pointer",
                  background: st === "live" ? C.liveSoft : "transparent",
                  border: `1px solid ${st === "live" ? C.live : "transparent"}`,
                  color: C.paper, font: "inherit",
                }}>
                <span style={{
                  width: 8, height: 8, borderRadius: 8, flexShrink: 0,
                  background: st === "live" ? C.live : st === "done" ? C.done : C.pending,
                  boxShadow: st === "live" ? `0 0 8px ${C.live}` : "none",
                }} />
                <span style={{ fontWeight: 700, fontSize: 13, width: 42, color: st === "live" ? C.live : C.paper }}>{c.label}</span>
                <span style={{ flex: 1, fontSize: 12, color: st === "done" ? C.textLo : C.paper }}>
                  {st === "live" ? "▶ live" : st === "done" ? "done" : "standby"}
                </span>
                {!anchored && <Tag color={C.warn}>no anchor</Tag>}
                {w && anchored && <Tag color={C.warn}>▲</Tag>}
              </button>
            );
          })}
        </div>

        <div style={{ padding: 12, borderTop: `1px solid ${C.rule}`, display: "flex", gap: 8 }}>
          <BigBtn onClick={() => advance(-1)} ghost>Back</BigBtn>
          <BigBtn onClick={() => advance(1)}>GO ▶</BigBtn>
        </div>
      </div>
    </div>
  );
}

// ---- presentational helpers ----
function Bar({ lock, setLock, active, undo, onUndo }: {
  lock: LockState; setLock: (l: LockState) => void;
  active?: { label: string }; undo: CueAnchor | null; onUndo: () => void;
}) {
  const unlocked = lock === "unlocked";
  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 12, padding: "10px 16px",
      borderBottom: `1px solid ${C.rule}`,
      // unmistakable global state shift when editing is live
      background: unlocked ? "#2a2410" : C.deskRaised,
      boxShadow: unlocked ? `inset 0 0 0 1px ${C.live}55` : "none",
    }}>
      <span style={{ fontSize: 11, letterSpacing: 1.5, textTransform: "uppercase", color: C.textLo }}>Prompt-book</span>
      <span style={{ flex: 1 }} />
      {undo && (
        <button onClick={onUndo} style={ghostBtn}>↶ Undo move</button>
      )}
      <button onClick={() => setLock(unlocked ? "locked" : "unlocked")}
        style={{
          ...ghostBtn,
          color: unlocked ? "#16181d" : C.textLo,
          background: unlocked ? C.live : "transparent",
          borderColor: unlocked ? C.live : C.rule, fontWeight: 700,
        }}>
        {unlocked ? "● EDITING — click to lock" : "🔒 Locked"}
      </button>
    </div>
  );
}

const ghostBtn: React.CSSProperties = {
  font: "inherit", fontSize: 11.5, padding: "6px 11px", borderRadius: 6,
  border: `1px solid ${C.rule}`, background: "transparent", color: C.textLo, cursor: "pointer",
};

function Tag({ children, color }: { children: React.ReactNode; color: string }) {
  return <span style={{ fontSize: 10, fontWeight: 700, color, border: `1px solid ${color}55`, borderRadius: 4, padding: "2px 6px" }}>{children}</span>;
}
function BigBtn({ children, onClick, ghost }: { children: React.ReactNode; onClick: () => void; ghost?: boolean }) {
  return <button onClick={onClick} style={{
    flex: ghost ? "0 0 90px" : 1, padding: "14px 0", borderRadius: 8, font: "inherit",
    fontSize: 15, fontWeight: 800, letterSpacing: 0.5, cursor: "pointer",
    background: ghost ? "transparent" : C.live, color: ghost ? C.textLo : "#16181d",
    border: `1px solid ${ghost ? C.rule : C.live}`,
  }}>{children}</button>;
}

function anchorStyle(r: Rect, st: string, warn: boolean, lock: LockState): React.CSSProperties {
  const color = st === "live" ? C.live : st === "done" ? C.done : C.pending;
  return {
    position: "absolute", left: `${r.x * 100}%`, top: `${r.y * 100}%`,
    width: `${r.w * 100}%`, height: `${r.h * 100}%`,
    background: st === "live" ? C.liveSoft : "transparent",
    borderLeft: `3px solid ${warn ? C.warn : color}`,
    boxShadow: st === "live" ? `0 0 0 1px ${C.live}66, 0 0 14px ${C.live}33` : "none",
    pointerEvents: lock === "locked" ? "none" : "auto",
    cursor: lock === "locked" ? "default" : "grab",
    borderRadius: 2, transition: "background .2s, box-shadow .2s",
  };
}
function annStyle(n: Annotation, r: Rect): React.CSSProperties {
  if (n.kind === "strikethrough") return {
    position: "absolute", left: `${r.x * 100}%`, top: `${(r.y + r.h / 2) * 100}%`,
    width: `${r.w * 100}%`, height: 0, borderTop: `2px solid ${C.cut}`, opacity: 0.85,
  };
  return { position: "absolute", left: `${r.x * 100}%`, top: `${r.y * 100}%`, width: `${r.w * 100}%`, height: `${r.h * 100}%` };
}
