# Prototypes

Clickable UI prototypes built with Claude for design exploration. These are **reference implementations only** — not production code. They use inline styles, mock data, and no TypeScript.

When a prototype is ready to build for real, the Confluence doc ([lighting7 UI/UX Research](https://photoshelter.atlassian.net/wiki/x/AYDXdw)) is the source of truth for decisions and the next session brief.

---

## CueStackRunner.prototype.jsx

**Status:** Complete  
**Confluence section:** Concept C — Hybrid Split Runner + Inline Editor

Interactive prototype of the Cue Stack Runner. Demonstrates all row states, real-time progress bars, keyboard controls, and the collapsible inline editor.

### What it covers

- **GO / BACK** — Space bar and buttons. GO fires the standby cue and advances the cursor immediately (optimistic). BACK mid-fade returns the cursor to the cue that was fading; BACK after completion steps back and un-completes.
- **Active + standby simultaneously** — Active cue shows amber `▶` with an animating fade progress bar. Standby cue shows green `◉`. Both visible at the same time.
- **Auto-advance** — After fade completes, a blue countdown bar shows `autoAdvanceDelayMs` ticking down. The cue stays in "active" state (not done) during the delay. Fires GO automatically at the end.
- **MARKER rows** — Rendered as inert section dividers. GO/BACK skip them.
- **Out-of-order banner** — Detects when `cueNumber` values are out of natural sort order relative to `sortOrder`. "Fix Order" runs the three-group partition algorithm (participating / pinned / unnumbered) in-place. Reappears immediately if a cue number edit creates a new violation.
- **Inline editor** — Collapses to a 50px icon strip. Click any cue row to expand (≈300px). Edits `fadeDurationMs`, `fadeCurve`, `autoAdvance`, `autoAdvanceDelayMs`, `notes`, `cueNumber`. Click the same row again to collapse. Shows "Currently fading" badge if the selected cue is the active one.
- **Theatre vs Band context** — Toggle in the tab bar overrides stack default. Theatre shows Q-number and notes columns. Band hides both.
- **Loop indicator** — `↻` badge on the stack tab when `loop: true`.
- **Persistent show bar** — DBO toggle, BPM + tap tempo, active/standby cue names, BACK + GO always visible.

### Decisions settled by this prototype

| Question | Resolution |
|---|---|
| Optimistic or confirmed standby cursor? | Optimistic — jump immediately on GO |
| Auto-advance UX during delay | Blue bar in active row; cue stays active, not done, until GO fires |
| BACK mid-fade | Returns standby to the mid-fade cue without marking it done |
| Editor collapse | 50px icon strip → ~300px panel; click same row to toggle |
| OOO banner trigger | Reactive to any cue number edit, not just on load |

### Production porting notes

When building `src/routes/CueStackRunner.tsx`:

- Replace mock data with RTK Query hooks from `src/store/cueStacks.ts`
- Replace `useState` runner state with Redux slice (active/standby cue IDs, completed set)
- Fade progress and auto-advance countdown remain client-side (`requestAnimationFrame`) — backend does not emit progress events
- `advance FORWARD/BACKWARD` → `POST /{stackId}/advance`; fire optimistically, reconcile on WebSocket `cueStackListChanged`
- Column visibility (theatre/band) should derive from `session_type` on the parent show session once that's available; use the manual toggle in the tab bar as a local override in the interim
- Editor field changes hit `PUT /{stackId}` (stack-level) or the relevant cue endpoint — no "save" button, debounce on change
- `fixOrder` → `POST /{stackId}/sort-by-cue-number`
