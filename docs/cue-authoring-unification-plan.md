# Cue Authoring Unification — Plan & Handover

> **Document status:** Living plan. Will be converted into permanent engineering docs (`lighting-composition-model.md`, `cues-engineering.md`, frontend docs) once implementation lands. Update this file at the end of every session.
>
> **Not in production yet.** We're free to break the DB, skip rollback shims, and iterate on migrations loosely.

## How to use this document

This plan spans multiple sessions across two repos (Kotlin backend `lighting7` + React frontend `lighting-react`). **Read this section first** if you're picking up the work cold.

- **Current status**: see [Status](#status) — what's done, what's in flight, what's next.
- **Locked decisions**: see [Decisions](#decisions) — directions already confirmed. Don't re-open without asking.
- **Open questions**: see [Open Questions](#open-questions) — items deferred for user input. Ask before implementing.
- **Resuming work**: identify the current phase from [Status](#status), read that phase's section for entry criteria, files and verification, and do the next unchecked item. Update Status before ending the session.

---

## Status

**Phase**: 0 — spec drafted; code work not started.

**Most recent session**: Planning v3 (2026-04-17). Prior-art survey across ETC EOS, grandMA2/3, Hog 4, MagicQ, Avolites; resolved five remaining design questions; drafted [docs/lighting-composition-model.md](lighting-composition-model.md) as the canonical spec; trimmed Phase 0 in this doc to point at it. No code changes yet.

**Next actions** (for the session that picks this up):
1. Read [docs/lighting-composition-model.md](lighting-composition-model.md) in full.
2. Read [Phase 0 — Layering Foundation](#phase-0--layering-foundation) for scope and file list.
3. Begin Phase 0 code work — start with `PropertyCategory` composition defaults and `FxInstance` priority ordering (small, independent, well-scoped).
4. Update this document's Status block at end of session.

**Per-phase tracker:**

| Phase | Summary | Status |
|-------|---------|--------|
| 0 | Layering foundation: make the composition model explicit in code (priority-ordered effects, reset-to-layer-below, `PropertyCategory` composition rules, stomp plumbing) | Spec drafted; code not started |
| 1 | `CuePropertyAssignment` model + migration; frontend `EditorContext` routing layer | Not started |
| 2 | `CueEditor` replaces `CueForm` — fixture/group modal UX for cue authoring | Not started |
| 3 | `PresetEditor` replaces `PresetForm` using the same primitives | Not started |
| 4 | Program view inline editor + "Grab live state" snapshot action | Not started |

---

## Context

The app has two distinct interaction models that don't share UI vocabulary:

1. **Fixture / Fixture Group detail modals** — state-oriented direct manipulation (`FixtureDetailModal`, `GroupDetailModal`, `FixtureContent`, `PropertyVisualizers`, `GroupPropertyVisualizers`, `CompactFixtureCard`). Colour pickers, dimmer sliders, pan/tilt, settings dropdowns, per-head overrides, parked indicators. Group-level multi-state is visualised cleanly (averaged swatch + yellow ring when mixed, min–max dimmer range bars, per-head squares). Edits are direct channel writes.

2. **Cue / Preset authoring** — form-based (`CueForm`, `PresetForm`). Nested sheets, timing as text inputs, targets picked abstractly, no fixture-state preview while authoring. "Static FXs" were bolted onto the effect machinery (commit `1ebc633` in lighting-react) to handle "set intensity to 50%", but the `StaticValue` effect indirection is clunky.

Underneath both: an **implicit and inconsistent composition model**. Parking is a post-transmission override in `ArtNetController`. Effects reset properties to neutral each tick, so direct writes don't stick while effects run. Multi-effect-per-property is undefined. Cues are just tags on `FxInstance`s rather than a real composition layer. The "base vs effects" question for property assignments has no principled answer because the existing layers aren't principled either.

**Goal**: formalise the composition model first, then build cue (and preset) authoring on top of the fixture/group modal UX with fixture property values as a first-class layer.

## Decisions

Confirmed with the user 2026-04-17:

- **Edit mode is live by default, with a blind option**: opening a cue for edit activates it on stage and applies direct-manipulation changes live AND into the cue. The editor exposes a toggle (**Live** / **Blind**) — in Blind mode the stage is untouched and changes persist to the cue only. Toggling mid-session transitions gracefully: Live→Blind stops the cue on stage but keeps editing; Blind→Live applies the cue's current contents to the stage. Default mode may vary by surface (e.g. Program view during a live show may default to Blind — see Open Questions).
- **First-class property assignments**: new backend child collection `CuePropertyAssignment`. Migrate existing `StaticValue` / `StaticSetting` ad-hoc effects into it.
- **Scope**: all views (Cues, Presets, Program) in phases; shared primitive first.
- **Presets stay** as reusable effect/state bundles. Preset editor rebuilt on the same surface.
- **Inspiration for the editor UI is the Fixture/Group detail modals**, not the Busking view.
- **Busking view is out of scope** — not folded into the cue editor.
- **Not running in production yet.** No rollback shims, no read-compat wrappers, no feature flags required. Migrations can be lossy / iterative.
- **Layering foundation is a prerequisite.** Phase 0 formalises channel composition before we add property assignments as a new layer.

### Composition model — canonical spec

The layer stack, per-category composition rules, crossfade behaviour, direct-write semantics, stomp flag, and cue-edit session semantics are specified in **[docs/lighting-composition-model.md](lighting-composition-model.md)**. That document is the source of truth; the plan references it rather than restating the rules.

Locked design decisions from the 2026-04-17 prior-art survey (detailed in the spec):

- **`value` is stored property-level**, not channel-level (hex colours, 0–255 sliders, enum strings, native pan/tilt units). The composition resolver expands property → channels at apply time using the fixture's patch.
- **Per-category composition rules** (HTP for `DIMMER` / `UV` / `STROBE`, LTP for everything else) are declared on `PropertyCategory`, with per-property fixture override via `@FixtureProperty(composition = ...)`. Cues don't pick their own blend mode; it's a property intrinsic.
- **Group vs member specificity**: fixture-level assignments win over group-level for the same property. Operators set "all these lights are red" at the group, then override individual fixtures.
- **Direct writes during cue edit** route into the cue's Layer 3 assignments via `cueEdit.setChannel`. In Live mode the server also performs the transient stage write for instant feedback; Blind persists only.
- **Snapshot-on-beginEdit + `cueEdit.discardChanges`** replaces the earlier "no soft-edit buffer" decision. Edits still auto-persist; the snapshot is a session-lifetime undo to the pre-edit baseline. This is our adaptation of the universal programmer / Update / Release pattern across pro consoles.
- **Effect reset-to-neutral fix lands in Phase 0**: effects reset to the layer below (Layer 3 value, else Layer 4 direct write, else Layer 5 baseline), not to hardcoded zero. Fixes the "direct writes clobbered under running effects" bug.
- **Layer 4 direct-write stickiness**: direct writes persist until a new cue covers the channel, `clearAssignment` is called, or a fresh `updateChannel` lands.
- **Stomp flag** on cues (default `false`): when a stomping cue applies, the FX engine removes ad-hoc effects owned by *other* cue IDs that target properties covered by this cue's Layer 3. Data-model support lands in Phase 0; authoring UX deferred.

## Target experience

Opening a cue enters **Cue Edit mode**:

- A **Live / Blind** toggle sits in the editor header. Live (default in most surfaces) activates the cue on stage and reflects edits there in real time; Blind leaves the stage untouched and persists edits to the cue silently. Toggling mid-session transitions cleanly (see Decisions).
- Main surface = a fixture/group list styled like today's `Fixtures` and `Groups` pages. Each compact card shows the cue's contribution (colour swatch, dimmer range bar, mixed-state indicators) derived from the cue's `propertyAssignments`.
- Selecting a card opens the existing `FixtureContent` / `GroupPropertiesSection`, bound to an `EditorContext` so its writes go into the cue. All existing property primitives (colour picker, sliders, pan/tilt, setting dropdowns, per-head overrides) work unchanged.
- Per-target tabs: **Properties** (default) | **Effects** | **Presets**.
- Palette bar at the top.
- Metadata (name, number, fade, notes, auto-advance, stack) in a collapsible header.
- Triggers (script hooks) in a separate side panel.

Leaving edit mode deactivates the cue or hands back to stack playback (in Live mode); in Blind mode, close is a no-op on stage. Reopening reproduces the exact stage shape because fixture state is stored directly.

---

## Phase 0 — Layering Foundation

**Goal**: make the channel-composition model documented in [docs/lighting-composition-model.md](lighting-composition-model.md) explicit in code. No user-visible feature changes; existing UIs keep working. This unblocks every later phase.

### Entry criteria
- [docs/lighting-composition-model.md](lighting-composition-model.md) exists and is reviewed (the spec is the precondition; it's already drafted alongside this plan).

### Exit criteria
- `FxEngine` / `ControllerTransaction` / `ArtNetController` refactored so the layer pipeline is explicit in code — named layers, ordered composition, single documented output site. Matches the model in the spec doc.
- `PropertyCategory` carries a `defaultComposition: CompositionRule` per enum value, per the spec's HTP/LTP table.
- `@FixtureProperty(composition = CompositionRule)` annotation parameter exists and overrides the category default when set (unused by any current fixture; Phase 1+ uses it if needed).
- `FxInstance` has a `priority: Int` field; effect iteration is a sorted pass (priority → cue-stack position → creation time).
- The reset-to-neutral pass resets to the layer below (Layer 3 → Layer 4 → Layer 5), not hardcoded zero. Direct writes are sticky under running effects.
- Parking is pre-composition-aware: a parked channel skips Layer 2 work rather than being overwritten at transmit time (still overridden at transmit time as a defence-in-depth — both paths agree).
- Cue model carries `stomp: Boolean` (default `false`); the engine honours it when a stomping cue applies.
- `dmx-engineering.md`, `fx-engineering.md`, `cues-engineering.md` each link to [docs/lighting-composition-model.md](lighting-composition-model.md) where they currently describe ad-hoc rules.

### Phase 0 work

Repo: `/Users/chris/Development/Personal/lighting7`. No frontend changes in Phase 0.

- **Declare composition rules on `PropertyCategory`** (per the spec's table): `DIMMER`, `UV`, `STROBE` default `HTP`; all others default `LTP`. Add a `CompositionRule` enum (`HTP`, `LTP`) alongside `PropertyCategory`.
- **Extend `@FixtureProperty` annotation** with `composition: CompositionRule = CompositionRule.UNSET` (or nullable). The resolver uses the annotation value when set, else falls back to the category default.
- **Refactor output pipeline so layers are explicit** in `FxEngine.processBeatTick()` and `ControllerTransaction.apply()`. Named composition steps; Layer 3 resolution hook present but returns empty (Phase 1 fills it). Parking consulted pre-composition to skip Layer 2 work for parked channels.
- **Add `priority: Int` field to `FxInstance`** and replace the `ConcurrentHashMap` iteration with a sorted pass (priority ascending → stable tie-break). Default priority = 0 for manual effects; cue-stack cues get derived priorities from their stack position.
- **Fix reset-to-neutral** in `FxEngine`: target = Layer 3 composed value if any, else Layer 4 sticky direct write if any, else Layer 5 baseline. Remove the hardcoded-zero path.
- **Add `stomp: Boolean` to the cue model** (DB column + DTO) with a Phase 0 resolver that, on cue apply, removes ad-hoc effects owned by other cue IDs whose targets overlap this cue's property assignments. Layer 3 assignments don't exist until Phase 1, so in Phase 0 this resolver sees an empty set and is a no-op — but the plumbing lands now so Phase 1 doesn't have to re-touch the cue model.
- **Layer 3 resolver scaffolding**: empty input, real output. Accepts an (empty) list of property assignments and emits per-channel values via the category's composition rule, applying fade weights. Unit-tested with synthetic inputs; Phase 1 wires it to real data.
- **Tests**: unit tests covering every layer interaction from the spec's Worked Examples — parked + effect, direct write below effect, HTP across two contributors, LTP with fade, cue-edit session discard (with Phase 1's data stubbed). Layer 3 resolver tested with synthetic inputs end-to-end including the HTP / LTP / `moveInDark` rules. Existing effect and parking tests must continue to pass unchanged.

### Files touched in Phase 0

- `src/main/kotlin/uk/me/cormack/lighting7/fixture/FixtureProperty.kt` (add `CompositionRule` enum; `defaultComposition` on each `PropertyCategory` value; `composition` param on `@FixtureProperty`).
- `src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt` (explicit layer pipeline, sorted effect iteration, reset-to-layer-below, stomp handling).
- `src/main/kotlin/uk/me/cormack/lighting7/fx/FxInstance.kt` — or wherever `FxInstance` lives (add `priority` field).
- `src/main/kotlin/uk/me/cormack/lighting7/fx/FxTarget.kt` (blend-mode application; confirm it composes over the new reset target).
- `src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt` (keep transmit-time parking override; confirm no change needed).
- `src/main/kotlin/uk/me/cormack/lighting7/dmx/ParkManager.kt` (expose a pre-composition query so FxEngine can skip parked channels).
- `src/main/kotlin/uk/me/cormack/lighting7/models/cues.kt` (add `stomp` column / field on the cue table).
- New: `src/main/kotlin/uk/me/cormack/lighting7/fx/Layer3Resolver.kt` (scaffolding; empty assignments in Phase 0, real data in Phase 1).
- Updates: `docs/dmx-engineering.md`, `docs/fx-engineering.md`, `docs/cues-engineering.md` — cross-reference [docs/lighting-composition-model.md](lighting-composition-model.md).

### Phase 0 verification

- New unit tests for every layer interaction from the spec's Worked Examples pass.
- Existing tests pass unchanged.
- Run the backend + frontend manually. Confirm no user-visible behaviour change in fixture/group modals, cue apply, busking, and program playback. One *intended* behaviour change: direct channel writes now persist visibly under running effects instead of being reset to zero on the next tick — verify this improvement and note it in the phase's changelog.
- Verify parked channels still win over effects.
- Verify two effects on the same property compose deterministically by priority (previously last-wins).
- Verify a cue with `stomp: true` applied via API removes another cue's ad-hoc effects targeting the same fixtures (even though the stomping cue has no Layer 3 assignments yet — use its effect targets as the overlap set for Phase 0 test scaffolding).

---

## Phase 1 — Property Assignments & Routing

**Goal**: fill Layer 3 with `CuePropertyAssignment`, migrate legacy static effects, and add the frontend `EditorContext` routing layer + `cueEdit.*` socket messages. No new UI.

### Entry criteria
- Phase 0 exit criteria met.

### Exit criteria
- `CuePropertyAssignment` table exists; migration has moved static effects into it; cues round-trip the new collection through `PATCH` / `PUT` / `GET`.
- `applyCue()` contributes property assignments as Layer 3 intent (resolved to channels by the Phase 0 pipeline).
- `EditorContext` exists on the frontend; all fixture/group property writes consult it. With `kind: 'live'` behaviour is byte-identical to today.
- `cueEdit.*` socket messages implemented on both sides, usable from the frontend (no UI consumer yet).

### Phase 1 backend work

- **New table `cue_property_assignments`** in `src/main/kotlin/uk/me/cormack/lighting7/models/cues.kt`:
  - `id`, `cue_id` (FK), `target_type` (`fixture` | `group`), `target_key`, `property_name`, `value` (string, property-level form), `sort_order`.
  - Mirror style of `cue_ad_hoc_effects` (see `docs/cues-engineering.md`).
- **Relation**: `propertyAssignments` on `Cue` alongside `presetApplications`, `adHocEffects`, `triggers`.
- **Routes** in `src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt`:
  - `GET` / `PUT` / `PATCH` round-trip `propertyAssignments`. PATCH: presence-of-key = replace collection.
  - `applyCue()` emits property assignments into Layer 3 state. Group targets expand to members with specificity rule (member-row wins). Property expands to channels using fixture patch.
  - `captureCurrentState()` returns property assignments by walking active Layer 3 contributions. Effects remain as effect records — no heuristic classification needed now that the layers are explicit.
  - **New endpoint** `POST /project/{projectId}/cues/{cueId}/snapshot-from-live` — wraps `captureCurrentState` + internal PATCH.
- **Cue Edit socket messages** (see `docs/websocket-engineering.md` pattern):
  - `cueEdit.beginEdit { cueId, mode: 'live' | 'blind' }` — session tracks the active cue-edit and its mode. In `live` the server applies the cue on stage on begin; in `blind` it doesn't.
  - `cueEdit.endEdit { cueId }` — in `live` mode stops the cue (or hands back to the stack); in `blind` mode a no-op on stage.
  - `cueEdit.setMode { cueId, mode }` — mid-session transition. `live → blind` stops the cue on stage, keeps the session open. `blind → live` applies the cue's current persisted state to the stage.
  - `cueEdit.setChannel { cueId, universe, id, level }` — resolves channel → fixture/property via patch, upserts a property assignment in that property's canonical form. In `live` mode also performs the transient channel write for instant feedback; in `blind` mode persists only.
  - `cueEdit.setProperty { cueId, targetType, targetKey, propertyName, value }` — explicit property-level form for colour, settings, etc. Same live/blind split as `setChannel`.
  - `cueEdit.setPalette { cueId, palette }`.
  - `cueEdit.addPresetApplication { cueId, presetId, targets, timing? }`.
  - `cueEdit.addAdHocEffect { cueId, ... }` — in `live` mode also spawns the effect immediately; in `blind` mode persists only.
  - `cueEdit.clearAssignment { cueId, targetKey, propertyName }` — in `live` mode also clears the live contribution for that channel/property.
  - Explicit messages per write (no implicit capture) — less magic, easier to debug.
- **Migration**: convert `StaticValue` / `StaticSetting` rows in `cue_ad_hoc_effects` into `cue_property_assignments`; delete originals. Lossy / inexact conversions are OK (not in production).
- **Tests**: migration correctness, PATCH round-trip, `applyCue()` layer-3 integration (uses Phase 0 pipeline), `cueEdit.setChannel` upserts + live-write, group-vs-member specificity resolution.

### Phase 1 frontend work

Repo: `/Users/chris/Development/Personal/lighting-react`.

- **New folder** `src/components/lighting-editor/`:
  - `EditorContext.tsx` — React context `{ kind: 'live' | 'cue' | 'preset', id?: number, mode?: 'live' | 'blind' }`. Default `kind: 'live'`; `mode` only applies when `kind === 'cue'` (presets always persist without stage-writes). The Phase 2 `CueEditor` sets `mode` on the context based on its toggle.
  - `routing.ts` — hook wrappers: `useRoutedUpdateChannel()`, `useRoutedSetProperty()`, `useRoutedApplyPreset()`, `useRoutedAddAdHocEffect()`, `useRoutedUpdatePalette()`. Each dispatches based on context.
- **Wire callers**: swap direct uses of `useUpdateChannelMutation`, palette mutations, etc. inside `FixtureContent`, `PropertyVisualizers`, `GroupPropertyVisualizers`, `ColourPickerPopover`, `CompactFixtureCard` to the routed hooks. Byte-for-byte parity when `kind: 'live'`.
- **Types**: extend `CueInput` / `Cue` in `src/api/cuesApi.ts` with `propertyAssignments: CuePropertyAssignment[]`; mirror in `src/store/cues.ts`. Add snapshot endpoint. Add `cueEdit.*` message types.

### Files touched in Phase 1

Backend:
- `src/main/kotlin/uk/me/cormack/lighting7/models/cues.kt`
- `src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt`
- `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` (cueEdit handlers — confirm filename)
- `src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt` (Layer 3 integration — hooks from Phase 0)
- New migration file (whatever tool the project uses — check migrations folder in Phase 0 exploration).

Frontend:
- **New**: `src/components/lighting-editor/EditorContext.tsx`, `src/components/lighting-editor/routing.ts`.
- `src/components/fixtures/FixtureContent.tsx`, `PropertyVisualizers.tsx`, `GroupPropertyVisualizers.tsx`, `ColourPickerPopover.tsx`.
- `src/components/groups/FixtureDetailModal.tsx`, `CompactFixtureCard.tsx`.
- `src/api/cuesApi.ts`, `src/store/cues.ts`, `src/api/channelsApi.ts`.

### Phase 1 verification

- Backend tests pass.
- Open a pre-migration cue that used `StaticValue` effects → stage output is identical post-migration.
- PATCH a cue with `propertyAssignments` via REST client → re-fetching returns the same payload.
- From browser console, dispatch `cueEdit.setChannel` during an edit session → assignment row exists AND channel updates live AND remains on the next frame (Layer 3 > Layer 4).
- `Fixtures` and `Groups` routes work identically to today when no cue is open for edit.
- Verify the specificity rule: set a group colour, override one fixture → apply the cue → only the overridden fixture differs.

---

## Phase 2 — FX Cue editor rebuild

**Goal**: replace `CueForm` with `CueEditor` built on fixture/group modal primitives.

### Entry criteria
- Phase 1 exit criteria met.

### Exit criteria
- Users can open a cue from `Cues.tsx`, edit contents via the new surface, and the cue reproduces the stage shape when re-triggered.
- Ad-hoc effects and preset applications are authored via per-target tabs, not nested sheets.
- `CueForm.tsx` removed.

### Work

- **New** `src/components/cues/CueEditor.tsx`:
  - Header: metadata + palette bar (lift `CuePaletteEditor` from `CueForm.tsx:397-430` into shared) + **Live / Blind** toggle with clear visual state (e.g. a pill / segmented control; red or amber accent when Blind so the operator never forgets which mode they're in).
  - Main area: segmented control (`Groups` / `Fixtures`), grid of `CompactFixtureCard`-style cards. Each card driven by the cue's `propertyAssignments` via `GroupPropertyVisualizers`.
  - Detail pane: selected card opens `FixtureContent` / `GroupPropertiesSection`, wrapped in `<EditorContext.Provider value={{ kind: 'cue', id, mode }}>` where `mode` reflects the header toggle.
  - Per-target tabs: **Properties** / **Effects** / **Presets**.
  - Triggers panel as collapsible aside, reuses `CueTriggerEditor`.
  - Lifecycle: on mount → `cueEdit.beginEdit { cueId, mode }` (Live also fires `POST /cues/{id}/apply`); on unmount → `cueEdit.endEdit`; on toggle → `cueEdit.setMode { mode }`.
  - Default mode = `live` for `Cues.tsx`; Program view may default differently — see Open Questions.
- **Effects-overlay preview**: when effects are running on the cue being edited and obscuring a property the user is trying to see, show a subtle indicator on the property pad (e.g. "Effect active — showing base value"). Full solo-layer preview is a stretch goal. Add this affordance in Phase 2 design; revisit if users struggle.
- `src/routes/Cues.tsx` — swap sheet contents for `CueEditor`.
- `src/routes/ProgramPage.tsx` — wide-viewport inline panel mounts `CueEditor` (rough; Phase 4 polishes).
- Obsolete: `src/components/cues/CueEffectFlow.tsx`, `CuePresetPicker.tsx`. Keep `CueFxTable.tsx` for read-only previews in list rows.

### Phase 2 verification

Use `preview_*` MCP tools against running frontend + backend.

- Open a cue from `Cues.tsx` in **Live** mode → cue activates on stage.
- Groups mode: pick a group, change colour → stage updates; close editor; reopen → cue persists change.
- Fixtures mode: open a multi-head fixture, set per-head overrides → overrides persist.
- Effects tab: add a beat-synced ad-hoc effect → runs on stage, saved.
- Presets tab: quick-apply → preset application saved.
- Close editor; trigger cue normally → identical stage output.
- Open a *different* cue in **Blind** mode (from the same session, stage still showing the previous live cue): change its colour and intensity → stage is unchanged; close; re-trigger that cue normally → reproduces the blind-edited state.
- Toggle Live → Blind mid-session: stage stops showing the cue; further edits don't affect stage.
- Toggle Blind → Live mid-session: stage shows the cue's current persisted state; subsequent edits appear live.
- Lint, typecheck, build pass.

---

## Phase 3 — FX Preset editor rebuild

**Goal**: rebuild `PresetForm` on the same primitives, scoped to a fixture type.

### Entry criteria
- Phase 2 complete.

### Exit criteria
- `PresetEditor` ships; `PresetForm` removed; preset `propertyAssignments` supported on backend.

### Work

- Backend: add `FxPresetPropertyAssignment` mirroring cue-side structure in `models/fxPresets.kt`. Extend routes. Preset-application resolution (used by cues) expands preset property assignments into Layer 3 at apply time, same pipeline as direct cue assignments.
- Frontend: new `src/components/presets/PresetEditor.tsx`:
  - No target grid. Synthetic fixture of the preset's target type drives `FixtureContent`, wrapped in `<EditorContext.Provider value={{ kind: 'preset', id }}>`. (Adapting `FixtureContent` to accept a synthetic/abstract fixture input is part of Phase 3 — it currently expects a patched fixture ID.)
  - Effects tab unchanged.
  - Optional "Preview on selection" — apply the preset-in-progress to a scratch fixture selection to test live.
- `src/routes/FxPresets.tsx` — swap in `PresetEditor`.

### Phase 3 verification

- Author a new preset via editor (colour + intensity property assignments + one effect).
- Use in a cue → identical behaviour to a pre-change preset.

---

## Phase 4 — Program view inline editor

**Goal**: bring the new editor into Program view; add live-snapshot workflow.

### Entry criteria
- Phase 2 complete (Phase 3 not required).

### Exit criteria
- Program view hosts `CueEditor` inline on wide viewports.
- "Grab live state" works from the editor header in Program context.
- Minimal metadata (Q number, name, fade) editable inline in Program cue rows.

### Work

- `src/components/runner/program/StackDetail.tsx` — click a cue row → inline `CueEditor` on wide viewports.
- Header action **"Grab live state"** → `POST /cues/{cueId}/snapshot-from-live`, refresh.
- Inline metadata cells in `ProgramCueRow` — mirror Run view pattern (commit `78380cb` in lighting-react).

### Phase 4 verification

- Busk a look in `FxBusking.tsx`.
- From Program view, select a cue, "Grab live state" → cue contents replaced.
- Re-trigger cue → reproduces the look.
- Inline-edit Q / name / fade from row → saves.

---

## Reuse inventory (don't rebuild)

Frontend:
- `FixtureContent`, `PropertyVisualizers`, `GroupPropertyVisualizers`, `CompactFixtureCard`, `GroupPropertiesSection`, `GroupMembersSection`, `MultiHeadIndicator`, `ParkedIndicator`, `GroupDimmerBar`.
- `ColourPickerPopover`.
- `EffectParameterForm`, `EffectTypePicker`, `EffectCategoryPicker`. Compatibility filtering from commit `1ebc633` kicks in automatically.
- `CuePaletteEditor` (inside `CueForm.tsx` today) — lift to shared.
- `CueTriggerEditor`.
- `useLazyCurrentCueStateQuery` in `src/store/cues.ts` — basis for "Grab live state".

Backend:
- `ParkManager`, `ArtNetController` parking override — reframe as Layer 1, don't rewrite.
- `FxEngine`'s effect composition — refactor in Phase 0 for clarity, but the per-tick property-reset + blend-mode machinery stays intact.
- `FxInstance` blend modes — reused between layers in Phase 0's multi-layer model (OVERRIDE / ADDITIVE / MAX / MIN / MULTIPLY).
- `captureCurrentState` in `projectCues.kt` — extend for snapshotting.
- Existing `updateChannel` / palette / preset-application handlers — mirror patterns for `cueEdit.*`.

## Out of scope

- **Busking view** — untouched.
- **Rollback / feature-flagging / read-compat shims** — not running in production.
- Undo/redo inside an edit session.
- Multi-operator concurrent editing.
- Changes to triggers / script hooks model.
- Relative edits (e.g. "intensity +10%"). Absolute only.
- Full solo-layer preview (show base values while effects run). Considered for Phase 2 as a stretch affordance.

## Open Questions

Flag these to the user before implementing.

1. **Stack mid-fade (Live mode only)**: if a cue is opened for edit in Live mode while another cue in the same stack is mid-fade, what's the correct behaviour? Snap-to-target then enter edit is proposed but not confirmed. (Blind mode is safe — no stage interaction.)
2. **Default mode per surface**: Cues page defaults to Live (rehearsal context). Should Program view default to Blind when the parent stack is active in a running show — i.e. protect live performance by default? Suggested yes, but depends on how "show is running" is signalled. Related: should the default be a user preference sticky across sessions?
3. **Auto-advance pause (Live mode only)**: should opening a cue for edit in Live mode pause the parent stack's auto-advance? Strongly suggested yes. Blind mode should never touch auto-advance.
4. **Session ownership of `cueEdit.*`**: with multi-client connections, should the server reject `cueEdit.setChannel` from clients that didn't send `beginEdit`? Default to yes (session-scoped) unless looser semantics preferred. Also: can two clients edit the same cue simultaneously (one Live, one Blind)? Probably reject the second `beginEdit` for the same `cueId`.
5. ~~**Layer 3 fade behaviour during cue crossfades**~~ — **Resolved 2026-04-17**. Per-category rules: sliders linear, colour RGB-linear, settings snap at 50% fade progress, position with `moveInDark` pre-applies during outgoing fade-out when outgoing intensity is 0 at end. Specified in [docs/lighting-composition-model.md](lighting-composition-model.md).
6. **Phase 2 effects-overlay preview affordance** — how prominent? Just a badge, or a "show base only" toggle? Most relevant in Live mode; less important in Blind.
7. **Blind→Live "apply to stage" safety**: toggling Blind→Live mid-session pushes the cue's current state to the stage. Is that safe to do unconditionally, or should it require confirmation ("this will override the live output")?

## Handover checklist (end of every session)

- [ ] Update the [Status](#status) block — phase, most-recent-session date, next actions.
- [ ] Tick off completed bullets in the current phase's Work section.
- [ ] Append new decisions to [Decisions](#decisions) with the date.
- [ ] Append new questions raised to [Open Questions](#open-questions).
- [ ] Note deviations from plan in a **Change log** section at the bottom (create on first use).
- [ ] Commit this file alongside the code changes.
