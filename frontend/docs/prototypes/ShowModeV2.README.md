# Show Mode Iteration 2 — Prototype README

**File:** `src/prototypes/ShowModeV2.prototype.jsx`  
**Session:** 7 — Show Mode Iteration 2: Prototype & Codebase Impact  
**Date:** 2026-04-12

## Overview

This prototype demonstrates the evolved Show Mode design based on a four-phase workflow model (Initial Programming → Show Assembly → Tech Runs → Production Run). It introduces:

- A **Program view** for session-centric show assembly
- An **evolved Run view** with a global Edit mode toggle
- A **unified Cue Edit Sheet** for all cue configuration — properties, presets, effects, triggers, duplicate, and remove

## Interactions Demonstrated

### 1. Program View — Session-Centric Show Assembly

**Session Overview** (default landing)
- Lists all stacks in show order within the active session ("Spring Production 2026")
- MARKER entries render as inert dividers between stacks
- Context banner shows stack/cue counts; "Ready to run →" links directly to Run view
- Click a stack entry to drill into its cue list

**Stack Detail** (drill-down from session overview)
- Full cue list with drag handles (⠿), cue number, name, fade info, and edit icon
- "← Session" back button returns to session overview
- Click any cue row to open its **Cue Edit Sheet**
- "+ Add Cue" creates a blank cue with defaults and opens its Edit Sheet
- "+ Separator" adds a new MARKER row at the bottom of the stack
- MARKER rows are editable inline — click the name to rename; ✕ to delete
- Drag handles are visual-only in the prototype (reorder deliberately not wired — see Omissions below)

### 2. Cue Edit Sheet

The unified surface for all cue editing. Opens as a bottom sheet overlay from both Program and Run views.

**Sections:**
- **Cue Properties** — cue number, fade duration, fade curve, auto-advance (with delay), notes
- **FX Presets** — lists presets assigned to this cue, with ✕ remove and "+ Add Preset" (placeholder)
- **FX Effects** — shows the source FX Cue if any, with ✕ remove and "+ Add Effect" (placeholder)
- **Triggers** — on-activate and on-deactivate script slots. Scripts are user-authored and run as part of the cue lifecycle (not external triggers like MIDI). Shows assigned script name with Remove, or "None" with "+ Add" (placeholder)
- **Duplicate Cue** — creates a copy pre-populated with all properties (name gets "(copy)" suffix, cue number cleared), opens the copy's Edit Sheet
- **Remove Cue from Stack** — low-prominence button at the very bottom

**Opening the sheet:**
- **Program view:** click any cue row, or "+ Add Cue" (creates blank and opens)
- **Run view (Edit mode on):** click the "Program" button on any cue row

### 3. Run View — Production Runner with Edit Mode

**Edit Mode Off (Production Run)**
- Clean GO/BACK interface — no authoring affordances visible
- Cue rows show status, name, fade, auto-advance, and notes (theatre) or just name and fade (band)
- ShowBar: DBO, BPM/TAP, Edit toggle, cue info, keyboard hints, BACK/GO
- Keyboard: Space = GO, ← = BACK

**Edit Mode On (Tech Run)**
- Toggle via "EDIT" button in ShowBar (alongside DBO and BPM)
- Reveals per-row "Program" button opening the Cue Edit Sheet
- Reveals OutOfOrderBanner when cue numbers are misordered
- Edit mode is global to the session — not per-stack

**All existing runner functionality preserved:**
- GO/BACK with optimistic standby cursor advance
- Fade progress bar (amber) via requestAnimationFrame
- Auto-advance countdown (blue bar) with automatic GO
- BACK mid-fade returns cursor
- Theatre/Band context toggle
- Stack tab switching with state reset
- BPM display + TAP tempo
- DBO toggle

### 4. Run → Edit Sheet Flow

- In Edit mode, clicking "Program" on any cue row saves the scroll position and opens the Cue Edit Sheet
- Edits are live (changes apply immediately to the cue in the stack)
- On close, scroll position is restored — the operator returns to their exact position in the cue list

## Design Decisions Made During Session

### Q1: Session-centric vs Stack-centric Program view?

**Decision: Session-centric.** The Program view lands on a session overview showing all stacks in show order, with drill-down into individual stacks. This closes UX gap #6 (Show Sessions UI) using the backend and store layer already shipped.

### Q2: Where does trigger authoring live?

**Decision: Within the Cue Edit Sheet.** Triggers are scripts configured to run on cue activate/deactivate. They belong alongside fade time, presets, effects, and notes — keeping the full composition of a cue visible in one place.

### Q3: Should FX Cues be demoted from primary nav?

**Decision: Keep in primary nav, reposition after Program and Show.** Nav order: Program → Show → FX Cues → Channels → Fixtures.

### Additional decisions

| Decision | Rationale |
|---|---|
| Unified Cue Edit Sheet (not separate Sheet + EditorPanel) | One surface for all cue editing; no "edit or inspect?" decision |
| Row click opens Edit Sheet directly | Simpler than a separate "Sheet" button |
| Add creates blank cue, opens Edit Sheet | Composition happens within the sheet — no library picker step |
| Duplicate in Edit Sheet, not a library "add from existing" | Pre-populates a copy for variations; more intuitive than browsing a library |
| Remove only in Edit Sheet (low-prominence) | Reduces visual noise in the cue list |
| Edit mode in ShowBar, not a floating toggle | Consistent with Channels view pattern; ShowBar is the session-level control surface |
| OOO banner hidden when Edit mode off | Production run should be distraction-free |
| Triggers = activate/deactivate scripts | User-authored scripts that run as part of the cue lifecycle, not external triggers |

## Deliberately Omitted from Prototype

| Feature | Why omitted | Production path |
|---|---|---|
| Drag-to-reorder cues | Requires a drag-and-drop library (e.g. dnd-kit) | Backend: `POST /{stackId}/reorder` (new). Frontend: dnd-kit wrapping cue rows |
| Session entry reorder | Same drag library dependency | Already supported by backend `reorder` endpoint |
| "+ Add Preset" / "+ Add Effect" in Edit Sheet | Placeholder — needs preset/effect browser UI | Will open a picker within the sheet when implemented |
| "+ Add" trigger scripts | Placeholder — needs script browser UI | Will open a script picker within the trigger slot |
| Cue name editing | Name comes from the FX Cue or is set at creation | Could add a name field to the Edit Sheet properties section |

## Mock Data

- **1 Show Session** with 3 entries (2 stacks + 1 interval marker)
- **2 Stacks**: Act 1 (theatre, 10 cues with markers) and Electric Feel (band, 5 cues, looping)
- **13 FX Cues** referenced by cues (for the FX Effects section in the Edit Sheet)
- **8 Presets** with mock channel counts
- **4 Scripts** for trigger slots (Flash Sequence, Colour Cycle, Crowd Scan, Fog Burst)
- Act 1 has intentional out-of-order cues (Q5 before Q4A) to demo OOO banner
- Some cues have pre-assigned triggers (e.g. "Morning light" → Colour Cycle on activate, "Night" → Fog Burst on deactivate)

## File Structure (for production implementation)

The prototype is a single self-contained file. In production, it would decompose into:

```
src/routes/Program.tsx          — /program route
src/routes/CueStackRunner.tsx   — /cue-stacks route (existing, extended)
src/components/program/
  SessionOverview.tsx
  StackDetail.tsx
src/components/sheets/
  CueEditSheet.tsx              — unified cue editing surface
src/components/runner/
  ShowBar.tsx                   — extended with Edit toggle
  CueRow.tsx                    — extended with Program button
```
