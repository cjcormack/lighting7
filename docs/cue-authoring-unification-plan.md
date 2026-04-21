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

**Phase**: 2 — sub-phase **2c landed 2026-04-21** (frontend migration:
`ProgramPage.tsx` wide-viewport inline panel and narrow-viewport sheet both
mount `CueEditor`; `RunPage.tsx` mobile cue-list sheet mounts `CueEditor`).
All three call-sites — `Cues.tsx`, `ProgramPage.tsx`, `RunPage.tsx` — now use
`CueEditor`; `CueForm` has zero remaining JSX references. `cueFormSaving`
state removed from both routes (CueEditor manages its own in-flight save
state). `npm run type-check` and `npm run build` green. Sub-phase 2d (delete
`CueForm` / `CueEffectFlow` / `CuePresetPicker`, collapse this Phase 2 section
back into a single block, consolidate per-sub-phase change log entries) is
queued.

See the Change log for durable invariants and engine surface.

## Known issues

~~**Colour picker in cue-edit mode (Phase 1 plumbing limitation)**~~ — **Addressed in 2a (2026-04-21)**. `ColourSwatch` / `VirtualDimmerSlider` now accept a `fixtureKey` prop (threaded from `FixtureContent` / per-head `PropertiesList`) and a new `useUpdateFixtureColour` hook routes RGB writes via `cueEdit.setProperty { propertyName: 'rgbColour' }` in cue mode (W/A/UV still use `setChannel` — the backend accepts those). Mirrors the existing `useUpdateGroupColour` pattern; no backend changes.

**Next actions:**
1. ~~Remaining `cueEdit.*` follow-up messages~~ — **landed 2a**. `setPalette` / `addPresetApplication` / `addAdHocEffect` handlers implemented; immediate (no-timing) presets/effects spawn on stage in Live mode.
2. ~~Live stack-cue edit support~~ — **landed 2b**. `beginEdit` / `setMode` LIVE now delegate to `CueStackManager.activateCueInStack`; `endEdit` / disconnect leave the stack active and resume auto-advance; `setMode LIVE→BLIND` deactivates the stack. Auto-advance is paused on `beginEdit` for stack LIVE sessions (resolved OQ3).
3. ~~ProgramPage / RunPage migration to `CueEditor`~~ — **landed 2c**. Both routes' CueForm call-sites swapped for `CueEditor` (inline + sheet). `cueFormSaving` state removed; CueEditor manages save progress internally.
4. Delete `CueForm` / `CueEffectFlow` / `CuePresetPicker`; collapse 2a/2b/2c/2d sections into one Phase 2 block; consolidate change log entries — queued for 2d.
5. Integration test: PATCH + snapshot-from-live + cueEdit round-trip through an in-memory HTTP harness — blocked on the same DB test-harness gap that blocks Phase 5's pipeline test. Track under Phase 5.
6. ~~Frontend: thread `fixtureKey` through to fixture-level colour components~~ — **landed 2a**. See Change log entry and resolved Known Issue 1.
7. **moveInDark during outgoing fade** (spec'd, not yet implemented). The current linear interp path handles basic position fades; the "pre-apply incoming position during outgoing fade when outgoing intensity is 0 at end" affordance is deferred. Scope small — the resolver already knows the moveInDark flag on each `Assignment`. Good candidate for a standalone follow-up session once a real moving-head fixture is on the test rig.

**Per-phase tracker:**

| Phase | Summary | Status |
|-------|---------|--------|
| 0 | Layering foundation: make the composition model explicit in code (priority-ordered effects, reset-to-layer-below, `PropertyCategory` composition rules, stomp plumbing) | Done |
| 1 | `CuePropertyAssignment` model + migration; frontend `EditorContext` routing layer | Done (smoke-check passed 2026-04-21; stack-cue Live edit + remaining cueEdit stubs carried into Phase 2) |
| 2 | `CueEditor` replaces `CueForm` — fixture/group modal UX for cue authoring | In progress (2a + 2b + 2c landed 2026-04-21; 2d queued) |
| 3 | `PresetEditor` replaces `PresetForm` using the same primitives | Not started |
| 4 | Program view inline editor + "Grab live state" snapshot action | Not started |
| 5 | **FX pipeline integration harness**: rig stub + end-to-end tests covering the Phase-0 layer cascade with real Layer-3 data, unlocking a reusable benchmark + integration test suite | Not started |
| 6 | **Persisted-reference validation for cue assignments**: dead-reference diagnostic for `CuePropertyAssignment` rows (fixture rename / removal), paralleling control-surface Phase 7 | Not started |

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

### Phase 2 sub-phasing (2026-04-21)

Confirmed with the user 2026-04-21:

- **Phase 2 split into 2a / 2b / 2c / 2d**, one Claude Code session per sub-phase. Each sub-phase must be pickup-able cold from this doc alone. See the [Phase 2](#phase-2--fx-cue-editor-rebuild) section for the detailed breakdown.
- **Fixture-level colour routing fix**: thread `fixtureKey` through `ColourPropertyDescriptor` so `ColourSwatch` / `VirtualDimmerSlider` emit `cueEdit.setProperty { propertyName: 'rgbColour' }` in cue mode, matching group-level behaviour. Frontend-only; closes Phase 1 Known Issue 1. Lands in 2a.
- **Auto-advance during Live stack-cue edit** (resolves OQ3): pause on `beginEdit`, resume on `endEdit` / disconnect. Applies only when editing a stack cue in LIVE mode. Lands in 2b.
- **ProgramPage default mode** (resolves OQ2): Live, matching `Cues.tsx`. Revisit once "show is running" becomes a defined signal. Lands in 2c.

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

## Phase 0 — Layering Foundation (done)

Formalised the composition model documented in
[docs/lighting-composition-model.md](lighting-composition-model.md) — see the Change log
for invariants. Summary: named layer pipeline through `FxEngine` →
`ControllerTransaction` → `ArtNetController`; `CompositionRule` enum + per-category
defaults + `@FixtureProperty` override; `FxInstance.priority` with sorted-snapshot
iteration; reset-to-layer-below; parking consulted pre-composition; `stomp` on the cue
model; `Layer3Resolver` scaffolding ready for Phase 1. Direct writes now persist under
running effects (intended behavioural change).

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

Phase 2 is split into four sub-phases, each a separate Claude Code session:
**2a** (frontend `CueEditor` + core stubs + fixture-level colour fix), **2b**
(backend Live stack-cue edit + auto-advance pause), **2c** (ProgramPage inline
+ RunPage migration), **2d** (delete `CueForm` / `CueEffectFlow` /
`CuePresetPicker`, collapse this section back into one Phase-5-sized block,
consolidate change log entries). Each sub-phase must be pickup-able cold from
this doc alone. The collapse step at the end of 2d is mandatory — the long-
lived doc should not carry ~250 lines of sub-phase detail forever.

### Phase 2 exit criteria (applies across 2a–2d)
- Users can open a cue from `Cues.tsx`, `ProgramPage.tsx`, or `RunPage.tsx`,
  edit contents via the new surface, and the cue reproduces the stage shape
  when re-triggered.
- Ad-hoc effects and preset applications are authored via per-target tabs, not
  nested sheets.
- Stack cues editable in Live mode.
- `CueForm.tsx`, `CueEffectFlow.tsx`, `CuePresetPicker.tsx` removed.

---

### Sub-phase 2a — `CueEditor` component + core stubs

**Goal**: replace `CueForm` for non-stack cues in `Cues.tsx`. Implement the three
remaining `cueEdit.*` stubs so the editor's palette, preset-application, and
ad-hoc-effect tabs have working routed backends. Fix the fixture-level colour
picker known issue from Phase 1.

#### 2a entry criteria
- Phase 1 exit criteria met.

#### 2a exit criteria
- New `src/components/cues/editor/` folder holds the orchestrator
  (`CueEditor.tsx`) + header/grid/detail/tabs components.
- `Cues.tsx` sheet mounts `CueEditor mode="sheet"` in place of `CueForm`.
  `CueForm` remains in-tree for ProgramPage/RunPage until 2c/2d.
- Non-stack cues round-trip through the editor — Live mode reflects on stage,
  Blind persists silently.
- Backend `cueEdit.setPalette`, `cueEdit.addPresetApplication`,
  `cueEdit.addAdHocEffect` implemented (no more "not implemented yet"
  rejections).
- Fixture-level colour picker no longer errors in cue mode — RGB writes route
  via `cueEdit.setProperty { propertyName: 'rgbColour' }`.

#### 2a work

Frontend — `CueEditor` component tree (new `src/components/cues/editor/`):

- [x] `CueEditor.tsx` — orchestrator. Props: `cue`, `projectId`, `isInStack`,
  `inheritedPalette`, `mode: 'sheet' | 'inline'`, `defaultEditMode`. Wraps
  children in `<EditorContextProvider value={{ kind: 'cue', id, mode }}>`.
  Lifecycle: on mount → `beginCueEditSession(cueId, mode)`; on unmount →
  `endCueEditSession(cueId)`; on Live/Blind toggle → `setCueEditMode`.
- [x] `CueEditorHeader.tsx` — metadata (name, cue#, notes, fade, auto-advance)
  + palette bar (reuse `CuePaletteEditor`) + Live/Blind toggle button (amber
  accent when Blind).
- [x] `CueTargetGrid.tsx` — segmented `Groups | Fixtures` + card grid with
  "in cue" badges on cards that have existing assignments / presets / effects.
- [x] `CueTargetDetail.tsx` — selected-card detail pane. Wraps
  `FixtureContent` / `GroupPropertiesSection`. Per-target tabs:
  **Properties** / **Effects** / **Presets**.
- [ ] Extraction of `EffectConfigureStep` / `PresetPickStep` / `TimingFields`
  into `shared/` — **deferred to 2d**. 2a reuses `CueEffectFlow` and
  `CuePresetPicker` as-is (user still picks target in the flow even though the
  detail pane has one). Not ideal UX; split happens in cleanup.
- [x] Triggers panel — reuse `CueTriggerEditor` in a collapsible section of
  the editor body.

Frontend — fixture-level colour picker (closes Phase 1 Known Issue 1):

- [x] Add `fixtureKey?: string` prop to `ColourSwatch` / `VirtualDimmerSlider`
  / `PropertyVisualizer` (prop-threaded from `FixtureContent`; descriptor type
  unchanged — no backend work needed).
- [x] New `useUpdateFixtureColour` hook in `src/hooks/usePropertyValues.ts`
  mirrors `useUpdateGroupColour`: RGB → `cueEdit.setProperty` with
  `rgbColour`; W/A/UV → `cueEdit.setChannel`.
- [x] `FixtureContent` passes `fixture.key` to every property visualizer;
  per-head `PropertiesList` passes `element.key`.
- [x] `useVirtualDimmer` now accepts `fixtureKey`, routes via `setProperty` in
  cue mode, and the "Not routed for cue-edit" comment is gone.
- [x] Individual R/G/B `ColourChannelSlider` onChange now route via the
  combined `updateColour` callback, so they emit one `setProperty` rather
  than three rejected `setChannel` calls.

Frontend — `Cues.tsx` swap:

- [x] Edit-existing path mounts `CueEditor mode="sheet"`. Create-new still
  uses `CueForm` (no cueId → no session to open). Creation flow migration
  deferred — either 2c/2d or a separate cleanup once we decide on a "draft
  cue" shape.

Backend — remaining cueEdit stubs in
`src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSession.kt`:

- [x] `cueEdit.setPalette { cueId, palette }` — replaces cue palette in DB;
  in Live mode also calls `FxEngine.setCuePalette` so running effects pick up
  palette-ref changes on the next tick.
- [x] `cueEdit.addPresetApplication { cueId, presetId, targets, timing }` —
  inserts the DaoCuePresetApplication row; in Live mode, immediate presets
  (no timing) spawn via the same `createInstanceFromPresetForCue` path as
  `applyCue`. Timed presets are persisted only — `CueTriggerManager` handles
  them when the cue is applied normally.
- [x] `cueEdit.addAdHocEffect { cueId, effect }` — same shape. Immediate
  effects spawn live; timed ones persist only.
- [x] Dispatch wired in `Sockets.kt` — the "not implemented yet" rejection
  is gone.
- [x] Ack messages: `cueEdit.paletteChanged` /
  `cueEdit.presetApplicationAdded` / `cueEdit.adHocEffectAdded`. Frontend
  types in `src/api/cueEditWsApi.ts` updated.

#### 2a files

Frontend (new):
- `src/components/cues/editor/CueEditor.tsx`
- `src/components/cues/editor/CueEditorHeader.tsx`
- `src/components/cues/editor/CueTargetGrid.tsx`
- `src/components/cues/editor/CueTargetDetail.tsx`
- `src/components/cues/editor/CueTargetEffectsTab.tsx`
- `src/components/cues/editor/CueTargetPresetsTab.tsx`
- `src/components/cues/editor/shared/EffectConfigureStep.tsx`
- `src/components/cues/editor/shared/PresetPickStep.tsx`
- `src/components/cues/editor/shared/TimingFields.tsx`

Frontend (updated):
- `src/routes/Cues.tsx` — swap sheet contents for `CueEditor`.
- `src/api/fixturesApi.ts` — `fixtureKey` on `ColourPropertyDescriptor`.
- `src/components/fixtures/PropertyVisualizers.tsx` — RGB routing in cue mode.
- `src/hooks/useVirtualDimmer.ts` — drop stale "can't route" comment.

Backend (updated):
- `src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSession.kt` —
  `setPalette`, `addPresetApplication`, `addAdHocEffect` handlers.
- `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` — confirm
  dispatch wires the new handlers (already stubbed at ~1081–1093).

#### 2a verification

Use `preview_*` MCP tools against running frontend + backend.

- `npm run lint`, `npm run type-check`, `npm run build` pass.
- Open a non-stack cue from `/cues` → header Live/Blind toggle flips cleanly
  (amber in Blind).
- Groups tab: pick a group, edit colour → stage updates in Live / stays in
  Blind → re-open cue → colour assignment persists.
- Fixtures tab: pick a multi-head fixture, set a per-head override → persists.
- Effects tab: add a beat-synced ad-hoc effect → runs on stage in Live, shows
  in cue summary.
- Presets tab: quick-apply a preset → preset application saved.
- Triggers panel: add/edit/remove a trigger unchanged.
- Close → re-trigger cue normally → identical stage output.
- Fixture-level colour picker: open non-stack cue, open a fixture card, spin
  the colour wheel → no "use setProperty with rgbColour" errors; RGB persists
  into `propertyAssignments`.

---

### Sub-phase 2b — Live stack-cue edit

**Goal**: drop the "Live edit of stack cues not supported yet" rejection by
routing stack-cue LIVE edits through `CueStackManager`. Add auto-advance pause
for the duration of the edit session (resolved OQ3).

#### 2b entry criteria
- 2a landed and merged. Editor is exercised for non-stack cues.

#### 2b exit criteria
- Opening a stack cue from `Cues.tsx` in Live mode activates the cue on stage
  via the stack manager; `cueEdit.*` writes reflect on stage; `endEdit`
  returns the stack cleanly.
- Parent stack's auto-advance is paused for the duration of any Live stack-cue
  edit session and resumed on `endEdit` / disconnect.

#### 2b work

- [x] `beginEdit` LIVE path — replaced the rejection with a shared
  `applyCueForLiveEdit(state, applyData)` helper that dispatches to
  `CueStackManager.activateCueInStack(state, stackId, cueId)` for stack cues
  and `applyCue(..)` for standalone cues. Snapshot still captured before
  activation. `CueEditSessionState.cueStackId` remembers stack membership for
  cleanup paths.
- [x] `setMode` BLIND→LIVE path — same shared helper.
- [x] `setMode` LIVE→BLIND path — stack cues clean up through
  `CueStackManager.deactivateStack(stackId, state)` (drops effects + Layer 3
  + triggers + cancels the paused auto-advance job in one call); standalone
  cues retain the existing trigger-deactivate + effect-removal pair.
- [x] `endEdit` + `endSessionOnDisconnect` — on LIVE stack-cue sessions,
  leave the stack active with the edited cue as its active cue and call
  `CueStackManager.resumeAutoAdvance(state, stackId)` so the show keeps
  rolling. Standalone cues keep the stop-on-stage behaviour. Decision
  2026-04-21: prefer resume-playback over `deactivateStack` — matches
  operator expectation that closing the editor mid-show shouldn't freeze
  the stack.
- [x] Auto-advance pause on `beginEdit` LIVE for stack cues via the existing
  `CueStackManager.pauseAutoAdvance(stackId)`; resumption handled by the new
  `resumeAutoAdvance(state, stackId, scope)` which re-reads the active cue's
  config and reschedules via the extracted private `scheduleAutoAdvance`
  helper.
- [x] Republish path verified — `setProperty` / `setChannel` /
  `clearAssignment` continue to use `publishLayer3ToControllers` through
  `FxEngine.setCueAssignments(cueId, built)`; `CueStackManager`'s crossfade
  ownership is undisturbed because the stack isn't mid-crossfade during a
  human-speed edit session.
- [ ] Integration test once Phase 5 test harness lands; dev-rig smoke only
  for now.

#### 2b files

- Updated: `src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSession.kt`
- Updated (optional): `src/main/kotlin/uk/me/cormack/lighting7/fx/CueStackManager.kt`
  (auto-advance pause helper).
- Updated: `src/store/cues.ts` if any new error/success shape.

#### 2b verification

- Open a cue inside a stack from `/cues`: editor opens in Live mode, cue
  activates on stage (stack state = this cue active).
- Edit colour / position → stage reflects; re-open → persists.
- Close editor → stack returns to prior playback cleanly.
- Auto-advance timer paused during the edit; resumes on close.
- Toggle Blind ↔ Live across the stack boundary: stage follows.

---

### Sub-phase 2c — ProgramPage inline hosting

**Goal**: swap the existing `CueForm` usage in ProgramPage's wide-viewport
right panel and RunPage's mobile sheet for `CueEditor`. Keep Phase 4 polish
(inline Q/name/fade cells, "Grab live state" button) out of scope.

#### 2c entry criteria
- 2a + 2b landed. Non-stack and stack-cue edits both work in Live and Blind.

#### 2c exit criteria
- `ProgramPage.tsx` wide-viewport right panel mounts `CueEditor mode="inline"`.
- `RunPage.tsx` mobile sheet mounts `CueEditor mode="sheet"`.
- All three call-sites (Cues / Program / Run) use `CueEditor`.

#### 2c work

- [x] `src/routes/ProgramPage.tsx` — replaced both the inline-panel and
  narrow-viewport sheet `CueForm` renders with `CueEditor` (inline / sheet
  modes respectively). Reused `cueFormCueId` / `cueFormCue` / `cueFormStackId`
  / `showInlineCueForm` state; renamed the shared props object from
  `cueFormProps` to `cueEditorProps`; dropped `isSaving` from the props
  (CueEditor manages its own `isSaving`).
- [x] `src/routes/RunPage.tsx` — swapped the mobile cue-list sheet to
  `CueEditor mode="sheet"`; added explicit `defaultEditMode="live"`.
- [x] `cueFormSaving` state and its setter calls (in both `handleCueFormSave`
  and `handleDuplicate` on ProgramPage; in `handleCueFormSave` on RunPage)
  removed — the state was only wired to CueForm's `isSaving` prop, and
  CueEditor doesn't accept that prop.
- [x] Default mode stays `'live'` for Program/Run views (resolved OQ2);
  revisit in Phase 4 once "show is running" is defined.

#### 2c files

- Updated: `src/routes/ProgramPage.tsx`
- Updated: `src/routes/RunPage.tsx`

#### 2c verification

- Wide-viewport Program view: drill into a stack, row-click a cue → inline
  `CueEditor` mounts; edit Properties / Effects / Presets / Triggers → same
  round-trip as `Cues.tsx`.
- Mobile Run view: row-click → sheet opens with `CueEditor`.

---

### Sub-phase 2d — Clean-up + doc collapse

**Goal**: delete obsolete components once all three call-sites use
`CueEditor`. Collapse this Phase 2 section back into a single ~40-line block
and consolidate the per-sub-phase Change log entries into one durable Phase 2
entry.

#### 2d entry criteria
- 2a + 2b + 2c landed; all three call-sites migrated.

#### 2d exit criteria
- `CueForm.tsx`, `CueEffectFlow.tsx`, `CuePresetPicker.tsx` deleted.
- `git grep 'CueForm\|CueEffectFlow\|CuePresetPicker'` returns zero hits (other
  than commit messages / change-log history).
- Phase 2 doc section collapsed; per-sub-phase Change log entries consolidated
  into one.
- Status block says Phase 2 done, Phase 3 ready.

#### 2d work

- [ ] Delete `src/components/cues/CueForm.tsx`.
- [ ] Delete `src/components/cues/CueEffectFlow.tsx`.
- [ ] Delete `src/components/cues/CuePresetPicker.tsx`.
- [ ] Keep `CueFxTable.tsx` (read-only row previews), `CueTriggerEditor.tsx`
  (reused), `CuePaletteEditor.tsx` (reused).
- [ ] Remove unused exports from `src/components/cues/index.ts`.
- [ ] Collapse four sub-phase sections back into one ~40-line block matching
  Phase 3 / Phase 6 size (Entry / Exit + terse Work summary).
- [ ] Consolidate per-sub-phase Change log entries into a single "Phase 2 —
  CueEditor + stubs + stack-cue Live (landed YYYY-MM-DD)" entry. Durable
  invariants to cover: session lifecycle wiring, `fixtureKey` on
  `ColourPropertyDescriptor`, stack-cue Live delegate, auto-advance pause,
  new cueEdit handlers + their Live-mode side-effects.
- [ ] Tick Phase 1 "Next actions" carried into Phase 2 (remaining stubs,
  stack-cue Live, colour-picker threading).

#### 2d files

- Deleted: `src/components/cues/CueForm.tsx`, `CueEffectFlow.tsx`, `CuePresetPicker.tsx`.
- Updated: `src/components/cues/index.ts` (if it exists).
- Updated: `docs/cue-authoring-unification-plan.md` (collapse + consolidate).

#### 2d verification

- Full build + smoke-check of `Cues.tsx`, `ProgramPage.tsx`, `RunPage.tsx`.
- `git grep` for deleted components returns zero code hits.
- Handover doc re-reads cleanly as a single Phase 2 entry.

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

## Phase 5 — FX pipeline integration harness

**Goal**: pay off the two test-infrastructure deferrals from Phase 0 — a rig stub that lets
us drive real `FxEngine` ticks against a synthetic `DmxController` in test sources, and a
benchmark harness for the per-tick allocation shape. With Phase 1 in place, integration
tests can finally exercise the full Layer 2 → 3 → 4 → 5 cascade end-to-end.

**Motivating review finding** (2026-04-19): Phase 0's change log flagged two deferred items
(*`FxEnginePipelineTest`*, *benchmark gate*) both blocked on the same missing piece — a test-
sources-accessible `DmxController` stub. The sealed interface can't be extended from test
sources across the main/test module boundary. Phase 0 worked around this with component-
level tests (Layer3Resolver + DirectWriteStore tested in isolation, LayerResolver as thin
glue) plus manual smoke-check. That coverage is adequate but leaves us without automated
detection of any Phase-1 regression that only shows up when the full pipeline runs.

### Entry criteria
- Phase 1 exit criteria met. Real `CuePropertyAssignment` rows flow into Layer 3.

### Exit criteria
- A `DmxController` test-stub is available to test sources. Options:
  - (preferred) relax `sealed interface DmxController` to `interface DmxController` with a
    comment documenting that concrete implementations are kept to the `dmx/` package by
    convention — same move the MIDI layer made in Phase 4 for `MidiController`
  - or: add a production-side `TestableDmxController` subclass marked `@InternalForTests`
- `FxEnginePipelineTest` exists, driving real beat and wall-clock ticks against the stub,
  covering every Worked Example from [lighting-composition-model.md](lighting-composition-model.md#worked-examples).
- Benchmark harness in `src/test/kotlin/.../fx/FxEngineBenchmark.kt` measures per-tick
  allocation shape. Initial pass establishes a baseline; subsequent runs detect regressions.
- CI gates on the benchmark's baseline within a tolerance (e.g. ±20%). Failing baseline
  fails the build.
- All prior tests continue to pass.

### Phase 5 design

**Stub controller:** implements `DmxController`, records every `setValue(s)` call, exposes
helpers to assert "fixture F's dimmer channel had value V at tick T". No real network I/O;
`TickerState` fades work in-memory against the stub's `currentValues` map.

**Integration test shape** — one test per Worked Example in the composition-model doc:

- Example 1: parked + effect → output matches park
- Example 2: direct write + additive effect → effect wiggles over sticky value
- Example 3: two HTP dimmer cues → max with fade weights
- Example 4: two LTP colour cues with crossfade → RGB-linear interpolation
- Example 5: cue edit session with discard → snapshot restored

Plus a regression test for the deferred smoke-check steps in the Phase 0 change log
(SineWave + updateChannel=180, two effects on one property with priorities, park+effect).

**Benchmark harness:** exercises a synthetic rig (4 universes × 64 fixtures × 8 channels)
at 50 Hz wall-clock + 120 Hz beat for 60 s, reporting allocation bytes/tick and p50/p99
tick duration. Uses Kotlin's `measureTime` and the JVM's allocation counters via `java.lang.management`.

### Phase 5 work

- [ ] Decide stub approach (relax seal vs `@InternalForTests`) — confirm with the user
- [ ] Land the stub under `src/test/kotlin/.../dmx/TestDmxController.kt`
- [ ] `FxEnginePipelineTest` with one test per Worked Example
- [ ] `FxEngineBenchmark` with baseline capture and regression gate
- [ ] Update Phase 0 Change log's "deviations from plan" section: cross off the deferred
  items
- [ ] Wire the benchmark into CI (decide: pass/fail on absolute numbers, or track-only?)

### Phase 5 files

- New: `src/test/kotlin/uk/me/cormack/lighting7/dmx/TestDmxController.kt`
- New: `src/test/kotlin/uk/me/cormack/lighting7/fx/FxEnginePipelineTest.kt`
- New: `src/test/kotlin/uk/me/cormack/lighting7/fx/FxEngineBenchmark.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/DmxController.kt` (if we relax
  the seal)

### Phase 5 verification

- `./gradlew test` runs the pipeline test suite to green
- Benchmark run produces a baseline report; a deliberately-regressed commit (e.g. an
  extra per-tick `mutableListOf`) fails the regression gate
- No production behaviour change

### Phase 5 open questions

- **Relax the `DmxController` seal or add a test-only subclass?** Relaxing matches the MIDI
  precedent and is the simpler path, but it does weaken the production contract slightly.
  Recommend: relax with a comment, consistent with MIDI.
- **CI regression gate semantics?** Track-only (report, don't fail) vs fail-on-regression.
  Recommend: fail-on-regression with ±20% tolerance after one week of baseline collection.

---

## Phase 6 — Persisted-reference validation for cue assignments

**Goal**: apply the same dead-reference diagnostic to `CuePropertyAssignment` rows that
control-surface Phase 7 applies to surface bindings. When a fixture is renamed or a property
removed, cue assignments referencing the old shape are flagged in the UI rather than
silently failing at apply time.

**Motivating review finding** (2026-04-19): cue assignments store `(target_type, target_key,
property_name)` — the same persisted-fixture-reference shape that causes silent-failure
risk in MIDI bindings. Today cue apply doesn't fail catastrophically on a missing fixture
(Layer3Resolver emits nothing for the missing pair, and the stage just doesn't show that
property) but the cue editor gives no hint that an assignment is dead. This phase gives
operators visibility.

**Dependency:** factor out a shared `PersistedFixtureReferenceValidator` that both this
phase and control-surface Phase 7 use. Decision on whether to pre-abstract: see
control-surface Phase 7's open question. This plan assumes a common validator exists by
the time Phase 6 lands.

### Entry criteria
- Phase 1 exit criteria met — real cue assignment data exists.
- Control-surface Phase 7 has landed, OR we decide to ship the validator here first and
  have control-surface Phase 7 consume it.

### Exit criteria
- `AssignmentHealth` sealed class mirrors `BindingHealth` from control-surface Phase 7
  (MissingFixture, MissingGroup, MissingProperty).
- `GET /api/rest/projects/{projectId}/cues/{cueId}` includes `health` on each
  `propertyAssignments` row.
- `CueEditor` UI renders dead assignments with a visible marker + "Rebind" quick-action.
- Cue apply logs a rate-limited warn on dead assignments.
- Tests: rename a fixture → reopen cue → dead markers appear; rebind → markers clear.

### Phase 6 work

- [ ] Shared `PersistedFixtureReferenceValidator` lifted into a common package, consumed
  by both `ControlSurfaceBindingService` and cue assignment validation
- [ ] `AssignmentHealth` evaluation in `applyCue` and in the cue detail REST response
- [ ] `CueEditor` renders dead-assignment markers; quick-rebind action
- [ ] Rate-limited warn log at apply time
- [ ] Tests covering the health transitions

### Phase 6 files

Backend:
- New or Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/AssignmentHealth.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/Layer3Resolver.kt` (optional — log
  when resolution encounters a dead key)

Frontend:
- Updated: `src/api/cuesApi.ts` types
- Updated: `src/components/cues/CueEditor.tsx` rendering + quick-rebind

### Phase 6 verification

- Rename a fixture in a patch → open a cue that referenced the old key → dead markers
  visible within one WS round-trip
- Click "Rebind" on a dead assignment → bind sheet opens pre-populated; commit →
  marker clears
- Backend logs a rate-limited warning on `applyCue` when a dead assignment is encountered

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
2. ~~**Default mode per surface**~~ — **Resolved 2026-04-21**. Program view defaults to Live, matching `Cues.tsx`. Revisit once "show is running" is a defined signal; may then flip to Blind when a show is running. User-preference sticky default deferred.
3. ~~**Auto-advance pause (Live mode only)**~~ — **Resolved 2026-04-21**. Yes — pause on `beginEdit`, resume on `endEdit` / disconnect, for Live stack-cue edits only. Blind never touches auto-advance. Lands in sub-phase 2b.
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

## Change log

Detailed per-session narration lives in git. This section captures durable invariants and
gotchas that would cost time to rediscover.

### Phase 2c — ProgramPage + RunPage migrated to `CueEditor` (landed 2026-04-21)

Per-sub-phase entry — will be folded into a single "Phase 2" change log block
in 2d.

**All three call-sites use `CueEditor` now.** `Cues.tsx` (edit-existing path,
landed in 2a), `ProgramPage.tsx` (wide-viewport inline panel and
narrow-viewport sheet, this sub-phase), and `RunPage.tsx` (mobile cue-list
sheet, this sub-phase). The only remaining `CueForm` render is Cues.tsx's
create-new path — the cue-edit session is keyed on a backend cueId that
doesn't exist for unsaved drafts. Creation-flow migration is tied up in 2d's
decision on a "draft cue" shape.

**`cueFormSaving` state deleted from both routes.** `CueEditor` has no
`isSaving` prop — it manages save progress via its own internal `isSaving`
state set inside `handleSave`. The routes' `cueFormSaving` was only ever
wired to `CueForm`'s `isSaving` prop, so removing it is pure cleanup.
`handleDuplicate` on ProgramPage used to toggle `cueFormSaving` around the
create-cue + re-open flow; that's now just a plain try/catch. Operator-speed
double-clicks aren't a concern because the mutation awaits the HTTP response,
and the duplicate button inside CueEditor's footer is gated by CueEditor's
own `isSaving`.

**Prop-object rename.** `cueFormProps` → `cueEditorProps` on ProgramPage, to
stop the name implying it goes to `CueForm`. The object still carries `open`
/ `onOpenChange` / `cue` / `projectId` / `onSave` / `isInStack: true as const`
/ `onDuplicate` / `onRemoveFromStack` plus a new `defaultEditMode: 'live' as
const`. ProgramPage always operates in a stack context, so `isInStack` stays
hardcoded to `true`.

**Default `defaultEditMode` is `'live'`** for both Program and Run (matches
`Cues.tsx`). OQ2 resolution notes this may flip to `'blind'` in Phase 4 once
"show is running" is a defined signal, but today's mental model is "open for
edit = apply on stage" regardless of surface.

**Lint / type-check / build baseline.** `npm run type-check` passes clean.
`npm run build` green (1.94 MB bundle, same shape as before). `npm run lint`
reports 4284 errors — identical count to the pre-migration baseline (spot-
checked by `git stash` + re-lint), confirming no new lint debt.

**Deferred into 2d.** (i) Delete `CueForm.tsx`, `CueEffectFlow.tsx`,
`CuePresetPicker.tsx`. (ii) Migrate the create-new path in `Cues.tsx` (needs
a draft-cue shape decision). (iii) Extract `EffectConfigureStep` /
`PresetPickStep` / `TimingFields` into `shared/` — 2a's deferred cleanup.
(iv) Collapse Phase 2 sub-phase sections back into a single block.
(v) Consolidate 2a/2b/2c change log entries into one.

### Phase 2b — Live stack-cue edit + auto-advance pause (landed 2026-04-21)

Per-sub-phase entry — will be folded into a single "Phase 2" change log block in 2d.

**Session state carries stack membership.** `CueEditSessionState` gained
`cueStackId: Int?`, snapshotted from `applyData.cueStackId` at `beginEdit` and
kept in sync on `setMode` transitions. Cleanup paths (`endEdit`, `setMode`,
`endSessionOnDisconnect`) branch on this to pick between the standalone
cue-stop path and the stack-aware path. Storing the id on the session (rather
than re-reading the DB on close) keeps teardown correct even if the cue's
`cueStack` FK has been reassigned mid-session.

**Live apply routes through CueStackManager.** New private helper
`applyCueForLiveEdit(state, applyData)` centralises the branch:
- `applyData.cueStackId != null`:
  `cueStackManager.activateCueInStack(state, stackId, cueId)` +
  `cueStackManager.pauseAutoAdvance(stackId)`. The pause happens immediately
  after the activate call so the auto-advance timer scheduled by step 8 of
  `activateCueInStack` is cancelled before it can fire — microsecond race
  window, but the timer delay is always >0 so it wouldn't fire anyway.
- Otherwise: the existing `applyCue(state, applyData, replaceAll = false)`.

Called from both `beginEdit` LIVE and `setMode` BLIND→LIVE so the two paths
behave identically.

**Cleanup semantics on session end.** Decision 2026-04-21 on the plan's
"`deactivateStack` vs lighter return-to-stack-playback" open question:
- **`endEdit` LIVE / `endSessionOnDisconnect` LIVE stack cue** → leave the
  stack active with the edited cue as its active cue, call
  `resumeAutoAdvance(state, stackId)`. The show keeps rolling; the operator
  can GO / BACK from here. Rationale: symmetric to how pause/resume works
  for surface `CueStackPause` — explicit user action vs. implicit cleanup on
  a network blip should not freeze the show.
- **`setMode` LIVE→BLIND stack cue** → `deactivateStack(stackId, state)`.
  The operator explicitly asked for no stage impact, so tear the stack
  down completely (effects + Layer 3 + triggers + cancel the paused
  auto-advance job).
- **Standalone cue cleanup** unchanged: `deactivateTriggersForCue` +
  `removeEffectsForCue`.

**Auto-advance pause + resume.** `CueStackManager` gained:
- `resumeAutoAdvance(state, stackId, scope)` — looks up the active cue in
  `activeStacks[stackId]`, re-reads its `autoAdvance` + `autoAdvanceDelayMs`
  from the DB, and reschedules via the extracted private helper. No-ops if
  the stack isn't active, the active cue has no auto-advance, or a timer is
  already running. Returns `Boolean` for parity with `pauseAutoAdvance`.
- Private `scheduleAutoAdvance(state, stackId, delayMs, scope)` — single
  canonical timer-launching shape used by step 8 of `activateCueInStack`
  and by `resumeAutoAdvance`.

The resumed timer starts its countdown from *now*, not from the original
scheduled deadline — the operator just spent N seconds editing, so
re-starting the countdown is more predictable than racing a stale deadline.

**Republish path untouched.** `setProperty` / `setChannel` /
`clearAssignment` during a stack-cue LIVE session continue to use
`republishLayer3` → `FxEngine.setCueAssignments(cueId, built)`, same as
Phase 1. Crossfade ownership is undisturbed because a human-speed edit
session doesn't overlap with the 16ms-tick crossfade window that happens
only during a cue transition. If the operator opens edit on a cue that's
currently mid-fade-in, the crossfade will naturally complete (via the
existing `crossfadeOutgoingCueId` state) before any edits arrive.

**Test adjustment.** The 2a-era placeholder
`remaining stub messages still deserialise without error` in
`CueEditSessionTest` was testing that `addPresetApplication` accepted a
minimal `{cueId:1}` payload — true when the handler was a stub, false after
2a added `presetId` + `targets`. Renamed to
`setPalette addPresetApplication addAdHocEffect round-trip` and exercises
the full post-2a payload shape.

### Phase 2a — `CueEditor` component + core cueEdit stubs (landed 2026-04-21)

Per-sub-phase entry — will be folded into a single "Phase 2" change log block in 2d.

**Frontend editor.** New folder `src/components/cues/editor/` hosts the orchestrator
(`CueEditor.tsx`) + three child components (`CueEditorHeader`, `CueTargetGrid`,
`CueTargetDetail`). `Cues.tsx` mounts `CueEditor` for the edit-existing path; create-new
still uses `CueForm` because the cue-edit session is keyed on a backend cueId that
doesn't exist for unsaved drafts. `CueTargetDetail` reuses `CueEffectFlow` /
`CuePresetPicker` as-is inside the Effects / Presets tabs (step extraction into
`shared/` deferred to 2d cleanup — for 2a the target-picker step is redundant when
opened from a selected card, but not broken). Triggers stay in a collapsible section
inside the editor body, powered by the existing `CueTriggerEditor`. Metadata / palette
still round-trip through the existing REST `PUT /cues/{id}` on Save; the session
messages only drive Layer 3 / effect / preset spawns that need cue-edit semantics.

**Lifecycle wiring.** `CueEditor` opens `cueEdit.beginEdit { cueId, mode }` on mount
(default `'live'`), closes with `cueEdit.endEdit` on unmount, and emits
`cueEdit.setMode` on Live/Blind toggle. The `sessionIdRef` gate stops a re-render
during an in-flight save from reopening a session. `EditorContextProvider` wraps the
entire editor subtree with `{ kind: 'cue', id: cueId, mode }` so all routing-aware
hooks (`useUpdateChannel`, `useUpdateFixtureColour`, group update hooks, …) dispatch
`cueEdit.*` automatically.

**Fixture-level colour routing.** Fixed Phase 1 Known Issue 1 without touching the
backend or the descriptor schema: `ColourSwatch` / `VirtualDimmerSlider` /
`PropertyVisualizer` gained an optional `fixtureKey` prop (threaded from
`FixtureContent` and from per-head `PropertiesList` using `element.key`). A new
`useUpdateFixtureColour` hook in `src/hooks/usePropertyValues.ts` mirrors
`useUpdateGroupColour`: in cue mode RGB goes out as one `cueEdit.setProperty` with
`propertyName: 'rgbColour'` and a `#RRGGBB` value; W/A/UV still go out as
`cueEdit.setChannel` (the backend accepts those, only R/G/B sub-channels are
rejected). Individual R/G/B `ColourChannelSlider` onChange also go through the
combined callback — setting R while preserving G/B — so we send one `setProperty`
instead of three rejected `setChannel` calls. `useVirtualDimmer.setValue` routes the
same way.

**cueEdit handlers.** `CueEditSession.kt` gained three handlers:
- `setPalette(cueId, palette)` — replaces `DaoCue.palette`; in LIVE mode also calls
  `FxEngine.setCuePalette` so running effects pick up palette-ref changes. The cue-
  palette version bump already exists in the engine.
- `addPresetApplication(cueId, presetId, targets, timing)` — inserts a
  `DaoCuePresetApplication` row; in LIVE mode immediate presets (no `delayMs`,
  no `intervalMs`) spawn per target via the same `createInstanceFromPresetForCue`
  path `applyCue` uses, with `cueId` + derived priority attached. Timed presets
  persist only — `CueTriggerManager` handles them at normal cue apply.
- `addAdHocEffect(cueId, effect)` — same shape as `addPresetApplication` but for
  ad-hoc `CueAdHocEffectDto`. Sort order auto-computed if caller sends `0`.

All three return cue-change acks (`cueEdit.paletteChanged` /
`cueEdit.presetApplicationAdded` / `cueEdit.adHocEffectAdded`) that the client can
subscribe to. `cueListChanged()` broadcasts the REST-shaped update to all clients,
which is how the sheet's read model refreshes.

**Import hazard.** `FxPresetEffectDto` lives in `uk.me.cormack.lighting7.models`
(not `.fx`) and `TogglePresetTarget` lives in `uk.me.cormack.lighting7.routes` (not
`.fx`). Got burned on the first compile pass; resolved with the correct package
paths. Flagging here because future cueEdit handlers that spawn effects will need
the same imports.

**Deferred into later sub-phases.** (i) ~~Live stack-cue edit + auto-advance pause~~
**→ landed 2b**. (ii) ProgramPage inline + RunPage sheet migration → 2c. (iii) Delete
`CueForm` / `CueEffectFlow` / `CuePresetPicker`, extract shared effect/preset
steps, migrate the create-new path, collapse these sub-phase sections back into
one → 2d.

### Phase 1 — `CuePropertyAssignment` + cueEdit routing (landed 2026-04-19 → 2026-04-20b, smoke-check passed 2026-04-21)

**DB / DTO shape.** `cue_property_assignments(id, cue_id FK, target_type ∈ {fixture, group},
target_key, property_name, value TEXT, fade_duration_ms NULLABLE, sort_order)`. Value is
always a canonical property-level string — unsigned decimal `"0".."255"` for Slider /
Setting, `ExtendedColour.toSerializedString()` for Colour, `"pan,tilt"` for Position.
`Layer3Resolver.parseAssignmentValue` / `PropertyValue.serialize()` are inverses; there's a
round-trip test per shape in `Layer3ResolverTest`.

**Apply path.** `buildLayer3AssignmentsForCue(fixtures, cueData)` in `routes/projectCues.kt`
expands groups to per-member rows AND keeps a group-level row so `Layer3Resolver`'s
specificity rule fires when a fixture-level override exists in the same cue. Missing
fixture / group / property rows are logged at warn and skipped — cue apply never throws on
stale references. The builder always stamps `fadeWeight = 1.0`; per-cue fade progress is
the engine's concern.

**Engine surface.** `FxEngine.setCueAssignments(cueId, list)` / `removeCueAssignments(cueId)`
/ `clearAllCueAssignments()` are the authoritative writers; each publishes Layer 3 → Layer 4
→ Layer 5 to controllers via `publishLayer3ToControllers` (single `ControllerTransaction`,
skips effect-covered keys and fully-parked targets). `updateCueFadeWeights(Map<Int, Double>)`
scales each stored `Assignment.fadeWeight` by a per-cue weight at republish time — weight =
1.0 entries are dropped, so the map stays small across the lifetime of a show.
`publishLayer3ToControllers` short-circuits keys whose composed `PropertyValue` didn't
change, so 60 fps crossfade ticks don't thrash the controller.

**Crossfade ownership.** `CueStackManager.activateCueInStack` always removes outgoing
effects immediately via `removeEffectsForCueStackKeepPalette`, and incoming effects start
at full intensity. Only Layer 3 property assignments crossfade: outgoing Layer 3 is kept
live for the crossfade duration, `runCrossfade` ticks per-cue fade weights each 16 ms via
`updateCueFadeWeights`, and end-of-crossfade calls `removeCueAssignments(outgoingCueId)`.
`ActiveStackState.crossfadeOutgoingCueId` is the single source of truth for "who are we
currently fading out" — consulted on cancel, on normal completion, and on `deactivateStack`,
so a mid-flight cancel never leaks Layer 3. Rationale: scaling running effects via
`FxInstance.intensityMultiplier` during cue transitions produced a drop-to-0 bug when two
cues' OVERRIDE-blend effects targeted the same property (higher-priority effect
last-writes-wins, `scaled(0)` on a `StaticSetting` emitted a literal 0 at t=0). Industry
consoles (Eos, grandMA, Hog 4) snap effects on cue transition and fade values; we now
match. `FxInstance.intensityMultiplier` and `FxOutput.scaled()` are retained for
manual / scripted effect fades.

**Composer invariants.** `Layer3Resolver.composeLtp`'s outgoing-contributor filter is
`fadeWeight > 0.0`. The earlier `< 1.0` upper bound excluded steady-state outgoing at weight
1.0 (crossfade start) and would have snap-cut to incoming at t=0. Existing mid-fade tests
don't exercise the boundary — the three LTP-boundary tests in `Layer3ResolverTest` do.

**Legacy migration.** `LegacyStaticEffectMigration` in `models/MigrateLegacyStaticEffects.kt`
runs on every `State.configureDatabase()` boot. Reads `cue_ad_hoc_effects` rows where
`effect_type IN ('StaticValue', 'StaticSetting')`, converts to `cue_property_assignments`,
deletes originals. Idempotent (filter becomes empty once rows are gone). `StaticValue` /
`StaticSetting` effect classes are still registered because the busking view still spawns
them — cleanup blocks on busking migrating to Layer 4.

**cueEdit socket session.** Per-connection `AtomicReference<CueEditSessionState?>` held in
the `configureSockets` scope; `endSessionOnDisconnect` stops orphaned Live cues on close.
Implemented: `beginEdit` / `endEdit` / `setProperty` / `setChannel` / `setMode` /
`clearAssignment` / `discardChanges`. Live stack-cue edit still rejects — delegate to
`CueStackManager.activateCueInStack` in a Phase 2 pass. `setChannel` rejects colour sub-
channels (use `setProperty` with `rgbColour`); `setPalette` / `addPresetApplication` /
`addAdHocEffect` are still stubs (need UI).

**Snapshot-from-live.** `POST /cues/{id}/snapshot-from-live` walks
`FxEngine.layerResolver.currentLayer3State`, serialises via `PropertyValue.serialize()`, and
replaces the cue's palette + preset-applications + ad-hoc-effects + property-assignments via
the shared `createCueChildren` helper. `buildCueApplyData(DaoCue)` is the single source of
truth for cue-data assembly shared between `/apply`, `beginEdit`, and `republishLayer3`.

**Frontend routing.** `src/components/lighting-editor/EditorContext.tsx` carries
`{ kind: 'live' | 'cue' | 'preset', id?, mode? }`; default `kind: 'live'`. Low-level hooks
(`useUpdateChannel`, `useUpdateGroup{Slider,Colour,Position,Setting}`) are routing-aware —
`kind === 'live'` is byte-identical to pre-Phase-1; `kind === 'cue'` dispatches `cueEdit.*`.
Fixture-level colour writes still flow through `updateChannel` because
`ColourPropertyDescriptor` doesn't carry `fixtureKey` — see §"Known issues" item 1 for the
two-option fix, deferred to Phase 2.

### Phase 0 — layering foundation (landed 2026-04-18)

Formalised the composition model documented in
[docs/lighting-composition-model.md](lighting-composition-model.md): `CompositionRule`
enum + per-`PropertyCategory` defaults (`HTP` for DIMMER/UV/STROBE, `LTP` elsewhere),
`@FixtureProperty(composition = ...)` per-property override, `FxInstance.priority` with
sorted-snapshot iteration, reset-to-layer-below instead of reset-to-zero, parking consulted
pre-composition, `stomp: Boolean` on the cue model. Cue-derived priority formula:
`stackId * 1_000_000 + sortOrder * 1_000 + 1`; manual effects stay at 0. Direct writes now
persist under running effects (behavioural change).
