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

**Phase**: **Phase 3 backend in flight (2026-04-21).** Preset child
collection + apply-path + toggle Layer-3 pseudo-cue landed on the backend.
`./gradlew test` green including new `BuildLayer3AssignmentsForPresetTest`.
Frontend (`PresetEditor`, draft context, hook routing, call-site swap) next
up — see the Phase 3 section and the 2026-04-21 planning notes captured in
Decisions.

Prior milestone — **Phase 2 complete (2026-04-21):** `CueEditor` ships on
all three call-sites (`Cues.tsx`, `ProgramPage.tsx`, `RunPage.tsx`) covering
edit + create-first. `CueForm`, `CueEffectFlow`, `CuePresetPicker` deleted
(`CueEffectFlow` / `CuePresetPicker` renamed + moved to
`src/components/cues/editor/` as `EffectFlow` / `PresetPicker`, only
consumed by `CueTargetDetail`). Stack-cue Live edit delegates to
`CueStackManager`; auto-advance pauses on `beginEdit`, resumes on `endEdit`
or disconnect. Remaining `cueEdit.*` handlers wired (`setPalette`,
`addPresetApplication`, `addAdHocEffect`). Phase 1 fixture-level colour
known issue closed.

See the Change log for durable invariants and engine surface.

## Known issues

~~**Colour picker in cue-edit mode (Phase 1 plumbing limitation)**~~ — **Addressed in Phase 2 (2026-04-21)**. `ColourSwatch` / `VirtualDimmerSlider` now accept a `fixtureKey` prop (threaded from `FixtureContent` / per-head `PropertiesList`) and a new `useUpdateFixtureColour` hook routes RGB writes via `cueEdit.setProperty { propertyName: 'rgbColour' }` in cue mode (W/A/UV still use `setChannel` — the backend accepts those). Mirrors the existing `useUpdateGroupColour` pattern; no backend changes.

**Next actions:**
1. **Phase 3 frontend** — `PresetEditor` rebuild on `FixtureContent` primitives, draft context, hook routing for `kind: 'preset'`, swap in `FxPresets.tsx`, delete `PresetForm`. Backend is done.
2. **Phase 3 follow-ups** (all flagged 2026-04-21; see Phase 3 section for detail):
   - Palette-resolution cascade for presets — preset palette → cue palette → global, resolved property-like. Backend now ships the data field (`fx_presets.palette`); cascade resolution still matches Phase 2's "global palette only" semantics.
   - Timed preset property assignments — `applyCue` immediate presets contribute Layer 3, delayed/recurring preset applications do NOT. `FxEngine.setCueAssignments` is a replace operation; a timed-fire append path needs new engine API. Document limitation.
   - Hard `NOT NULL` on `fx_presets.fixture_type` — backfill window first, then tighten the DB column. Runtime enforcement (input validation) already rejects nulls as of Phase 3 backend.
   - Preset per-head / per-element assignments — schema would need a nullable `target_key` column (not yet added). UI stays single-head in Phase 3.
   - "Preview on selection" live-apply affordance for the preset editor — deferred from Phase 3 scope.
   - ~~Preset-toggle Layer-4 true direct-write path~~ — **Promoted to its own [Phase 7](#phase-7--property-level-layer-4-writer)** (2026-04-21). Phase 3 backend implements preset-toggle as a **Layer 3 pseudo-cue** (negative `cueId = -presetId`, priority 0) for pragmatism; Phase 7 builds the reusable `PropertyValue → channel-writes` resolver and migrates toggle onto a real Layer-4 path. Observable behaviour of the Phase-3 workaround matches Layer-4 for the common case; divergence only shows when an `updateChannel` lands on a channel the preset toggle is asserting (Phase-3 pseudo-cue wins; Phase-7 Layer-4 channel-keyed last-write-wins puts the manual drag on top).
3. **Integration test**: PATCH + snapshot-from-live + cueEdit round-trip through an in-memory HTTP harness — blocked on the same DB test-harness gap that blocks Phase 5's pipeline test. Track under Phase 5.
4. **moveInDark during outgoing fade** (spec'd, not yet implemented). The current linear interp path handles basic position fades; the "pre-apply incoming position during outgoing fade when outgoing intensity is 0 at end" affordance is deferred. Scope small — the resolver already knows the moveInDark flag on each `Assignment`. Good candidate for a standalone follow-up session once a real moving-head fixture is on the test rig.
5. **Effect/Preset picker UX polish** (deferred from 2d). The Effects/Presets tabs inside `CueTargetDetail` still open `EffectFlow` / `PresetPicker` at their target-picker step even though a card selection is already active — redundant and confusing. Extract `EffectConfigureStep` / `PresetPickStep` / `TimingFields` into `editor/shared/` and drop the target-picker step when opened from a selected card.

**Per-phase tracker:**

| Phase | Summary | Status |
|-------|---------|--------|
| 0 | Layering foundation: make the composition model explicit in code (priority-ordered effects, reset-to-layer-below, `PropertyCategory` composition rules, stomp plumbing) | Done |
| 1 | `CuePropertyAssignment` model + migration; frontend `EditorContext` routing layer | Done |
| 2 | `CueEditor` replaces `CueForm` — fixture/group modal UX for cue authoring | Done (2026-04-21) |
| 3 | `PresetEditor` replaces `PresetForm` using the same primitives | Backend done (2026-04-21); frontend in progress |
| 4 | Program view inline editor + "Grab live state" snapshot action | Not started |
| 5 | **FX pipeline integration harness**: rig stub + end-to-end tests covering the Phase-0 layer cascade with real Layer-3 data, unlocking a reusable benchmark + integration test suite | Not started |
| 6 | **Persisted-reference validation for cue assignments**: dead-reference diagnostic for `CuePropertyAssignment` rows (fixture rename / removal), paralleling control-surface Phase 7 | Not started |
| 7 | **Property-level Layer 4 writer**: reusable `PropertyValue → channel-writes` resolver + migrate preset-toggle off the Phase-3 pseudo-cue onto a real Layer-4 path | Not started |

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

### Phase 3 pre-implementation decisions (2026-04-21)

Confirmed with the user before Phase 3 backend work started:

- **Standalone preset-toggle contributes property values**, not just effects. Operator expectation: toggling "red preset" makes the lights red. Phase 3 backend implements this as a **Layer 3 pseudo-cue** (negative `cueId = -presetId`, priority 0) rather than a true Layer-4 direct-write — same observable behaviour, reuses the full Layer 3 pipeline (multi-channel resolver, group expansion, specificity, DMX publish). A true Layer-4 property-level writer is deferred to follow-up.
- **`fixtureType` required on presets**. Enforced at input validation (POST/PUT reject blank). DB column stays nullable during a backfill window; hard `NOT NULL` lands in a follow-up.
- **No legacy preset static-effect migration** — user confirms no presets currently use `StaticValue` / `StaticSetting` effect entries in JSON. Skip the `MigrateLegacyPresetStaticEffects` mirror of the cue-side migration.
- **Presets own a palette** with the same shape as cues. Preset palette refs should resolve via the same composition-model cascade as property values — preset palette → cue palette → global. Phase 3 ships the `fx_presets.palette` data field; the cascade resolver change is scoped as a follow-up.
- **Timed preset property assignments don't contribute Layer 3**. `applyCue` immediate presets (no `delayMs` / `intervalMs`) fan their property assignments into Layer 3 alongside the cue's own assignments. Delayed / recurring preset applications stay effects-only — `FxEngine.setCueAssignments` is a replace operation, so a runtime "append Layer 3 at fire time" path would need new engine API. Flagged for follow-up.

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

### Entry criteria
- Phase 1 exit criteria met.

### Exit criteria
- `CueEditor` mounts on all three call-sites (`Cues.tsx`, `ProgramPage.tsx`, `RunPage.tsx`) for both edit and create, in sheet + inline modes.
- Per-target tabs (Properties / Effects / Presets) replace the nested-sheet `CueForm` surface.
- Stack cues editable in Live mode (delegated to `CueStackManager`); auto-advance pauses on `beginEdit`, resumes on `endEdit` / disconnect.
- `CueForm`, `CueEffectFlow`, `CuePresetPicker` deleted; `git grep` is clean.
- Phase 1 fixture-level colour known issue closed.

### Work summary

- **Frontend editor** under `src/components/cues/editor/`: `CueEditor` (orchestrator), `CueEditorHeader`, `CueTargetGrid`, `CueTargetDetail`, plus `EffectFlow` / `PresetPicker` (renamed from the `Cue`-prefixed originals, consumed only by the detail pane now).
- **Cue-mode colour routing**: `ColourSwatch` / `VirtualDimmerSlider` / `PropertyVisualizer` take `fixtureKey`; new `useUpdateFixtureColour` routes RGB via `cueEdit.setProperty { rgbColour }`, mirroring `useUpdateGroupColour`.
- **Remaining cueEdit handlers** in `CueEditSession.kt`: `setPalette`, `addPresetApplication`, `addAdHocEffect`. Immediate (no-timing) presets/effects spawn on stage in Live mode via the same `createInstanceFromPresetForCue` / effect-spawn paths as `applyCue`; timed entries persist only.
- **Live stack-cue edit**: `beginEdit` / `setMode` BLIND→LIVE delegate to `CueStackManager.activateCueInStack`; `endEdit` LIVE leaves the stack active and calls `resumeAutoAdvance`; `setMode` LIVE→BLIND calls `deactivateStack`. Session carries `cueStackId` to pick the right teardown branch. Auto-advance paused for the duration of any Live stack-cue edit.
- **Call-site migration**: `Cues.tsx` edit + create-first (`handleCreate` POSTs a blank/pre-populated cue before opening the editor, since `cueEdit.beginEdit` needs a backend cueId), `ProgramPage.tsx` wide-viewport inline + narrow-viewport sheet, `RunPage.tsx` mobile sheet. `cueFormSaving` state removed; `CueEditor` owns save progress.

### Phase 2 verification

- Open existing + new cues from `/cues`, drill into stacks in `/program`, edit cues from the `/run` mobile sheet. All three routes mount `CueEditor`.
- Live/Blind toggle flips cleanly; Live edits reflect on stage; re-triggering the cue reproduces the edited state.
- Stack cue in Live mode: parent stack's auto-advance pauses; closing the editor leaves the stack playing.
- `git grep 'CueForm\|CueEffectFlow\|CuePresetPicker'` → zero code hits.

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

**Phase 7 extension point**: when [Phase 7](#phase-7--property-level-layer-4-writer) lands,
this harness gains a Worked Example for Layer-4 property writes (preset-toggle resolves a
`PropertyValue` into multi-channel Layer 4 direct writes; `updateChannel` on a covered
channel overrides last-write-wins; toggle-off clears). Plan for the extension when
designing the harness scaffolding.

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

## Phase 7 — Property-level Layer 4 writer

**Goal**: add a reusable `PropertyValue → List<ChannelWrite>` resolver and migrate any
non-cue property-level stage contributions (today: preset-toggle) from the Layer-3
pseudo-cue workaround onto a true Layer-4 direct-write path. Closes Open Question #8.

**Motivation**: Phase 3 needed preset-toggle to contribute property values (operator
expectation: toggling "red preset" makes lights red). A true Layer-4 write would need a
`PropertyValue → channel-writes` resolver that doesn't exist yet — `DirectWriteStore.putProperty`
only handles scalar 7-bit MIDI sliders. Phase 3 shipped a pragmatic Layer-3 pseudo-cue
(negative `cueId = -presetId` at priority 0) which gives the right observable behaviour in
the common case, but leaves the layering model conceptually dishonest (Layer 3 = cues is no
longer strictly true) and creates a small priority-collision risk if the priority formula
ever shifts. See the Phase 3 backend change-log entry.

### Entry criteria
- Phase 3 complete (preset-toggle uses Layer-3 pseudo-cue today).
- Phase 5 pipeline-test harness in place, so the new Layer-4 path can be covered by the
  integration suite.

### Exit criteria
- New `PropertyChannelWriter` (or equivalent) in `fx/` that converts a `PropertyValue` plus a
  fixture (or group) into a flat list of `(Universe, Channel, UByte)` tuples using the
  fixture's DMX patch. Covers `Slider`, `Setting`, `Colour` (R/G/B + optional W/A/UV), and
  `Position` (pan + tilt).
- New `FxEngine.writeLayer4Property(target, propertyName, value)` /
  `clearLayer4Property(target, propertyName)` APIs that combine the resolver with
  `DirectWriteStore.put` / `clear` and push an immediate DMX publish for the affected
  channels.
- `togglePresetOnTargets` migrated off the pseudo-cue path onto `writeLayer4Property`.
  `presetTogglePseudoCueId` helper deleted; `FxEngine.hasCueAssignments` kept only if other
  callers land (otherwise dropped).
- Open Question #8 marked resolved.
- Phase 5 integration suite extended with a Worked Example covering a Layer-4 property
  write: preset-toggle shows on stage; `updateChannel` on a covered channel overrides
  (channel-keyed last-write-wins); toggle-off clears.

### Phase 7 work

- [ ] Design the resolver's input shape — `Fixture` + property name + `PropertyValue`, or a
  pre-resolved `FxTarget`? Favour `Fixture` + property name + `PropertyValue` to keep the
  helper standalone (not effect-engine coupled).
- [ ] Implement `PropertyChannelWriter` covering all four `PropertyValue` variants.
  Round-trip tests against the existing `parseExtendedColour` / `Layer3Resolver.parseAssignmentValue`
  value parsers — if you can parse a string into a `PropertyValue` and resolve it to
  channels, the channels should write the same DMX bytes the cue-apply pipeline would have
  produced for the same assignment.
- [ ] Add `FxEngine.writeLayer4Property` / `clearLayer4Property` and wire through to
  `DirectWriteStore` + the controller publish path used by `updateChannel`.
- [ ] Migrate `togglePresetOnTargets` — remove the pseudo-cue block, call
  `writeLayer4Property` per (target, preset assignment) on toggle-on, `clearLayer4Property`
  on toggle-off. Toggle-state check shifts from `hasCueAssignments(-presetId)` to a new
  per-preset lookup (e.g. a `ConcurrentHashMap<Int, Set<ChannelKey>>` tracking which
  channels each toggled preset is currently writing).
- [ ] Update Open Question #8 → "Resolved in Phase 7".
- [ ] Phase 5 test-harness extension for Layer-4 property writes.

### Phase 7 files

Backend:
- New: `src/main/kotlin/uk/me/cormack/lighting7/fx/PropertyChannelWriter.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt` — new Layer-4 write API
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/DirectWriteStore.kt` — possibly add
  a per-owner tracking map to support toggle clears without replaying all writes
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/routes/projectFxPresets.kt` — drop
  pseudo-cue path, call new API
- New tests: `src/test/kotlin/uk/me/cormack/lighting7/fx/PropertyChannelWriterTest.kt`
- Updated: `src/test/kotlin/uk/me/cormack/lighting7/fx/FxEnginePipelineTest.kt` (Phase 5
  harness) — add Layer-4 property-write Worked Example

Frontend: none directly, but Phase 3's deferred "Preview on selection" affordance is
unblocked once the resolver exists — layer that on as a small follow-up if desired.

### Phase 7 verification

- Toggle a preset with `rgbColour = #FF0000` + `dimmer = 128` on a group → rig shows red at
  50% intensity.
- With the preset toggled on, drag the red channel on the Fixtures page → manual channel
  drag wins (Layer 4 channel-keyed last-write-wins).
- Toggle the preset off → all four channels (R, G, B, dimmer) clear back to whatever Layer
  5 baseline was there.
- Apply a cue that asserts the same properties → cue wins (Layer 3 > Layer 4), matching
  Phase 3 behaviour. Deactivate the cue → preset's Layer 4 values re-emerge.
- Phase 5 harness asserts the above cascade in an integration test.

### Phase 7 open questions

- **Per-owner tracking in `DirectWriteStore`**: toggle-off needs to know which channels
  each preset toggled — either we track this in `togglePresetOnTargets` (ConcurrentHashMap
  keyed by `presetId`), or we give `DirectWriteStore` an "owner" concept. Cleaner to keep
  `DirectWriteStore` simple and track in the toggle caller; confirm before implementing.
- **Fold "Preview on selection" into Phase 7 or split?** Phase 3 deferred this affordance;
  it uses the same resolver. Probably leave as a Phase 7 stretch.

---

## Reuse inventory (don't rebuild)

Frontend:
- `FixtureContent`, `PropertyVisualizers`, `GroupPropertyVisualizers`, `CompactFixtureCard`, `GroupPropertiesSection`, `GroupMembersSection`, `MultiHeadIndicator`, `ParkedIndicator`, `GroupDimmerBar`.
- `ColourPickerPopover`.
- `EffectParameterForm`, `EffectTypePicker`, `EffectCategoryPicker`. Compatibility filtering from commit `1ebc633` kicks in automatically.
- `CuePaletteEditor` — already reused by `CueEditor`; share as-is with the preset editor.
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
8. ~~**Preset-toggle layer placement (Phase 3)**~~ — **Deferred to [Phase 7](#phase-7--property-level-layer-4-writer) (2026-04-21).** Toggle currently uses a Layer 3 pseudo-cue (negative cueId, priority 0). Phase 7 builds the `PropertyValue → channel-writes` resolver and migrates toggle onto a real Layer-4 direct-write path. Divergence between the two approaches only shows when `updateChannel` lands on a channel the toggle is asserting (pseudo-cue wins today; Layer-4 last-write-wins after Phase 7).

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

### Phase 3 backend — preset property assignments + toggle pseudo-cue (landed 2026-04-21)

Adds first-class `FxPresetPropertyAssignment` to presets and fans them into Layer 3 at cue
apply time. Frontend comes next.

**DB / DTO shape.** New table
`fx_preset_property_assignments(id, preset_id FK, property_name, value TEXT, fade_duration_ms NULLABLE, sort_order)`.
Value strings reuse the cue-side canonical form (`Layer3Resolver.parseAssignmentValue` is the
inverse). Unlike cue assignments, preset rows are **preset-local** — there's no `target_type`
or `target_key` column. Targets come from the cue's preset-application at apply time, and the
builder fans each (propertyName, value) across them. New `palette: JSON<List<String>>` column
on `fx_presets` mirrors `cues.palette`; default empty. `fixtureType` stays nullable at the
column level but is **runtime-required** — `NewFxPreset` POST/PUT rejects blank with 400. Hard
`NOT NULL` deferred to a backfill follow-up.

`NewFxPreset` and `FxPresetDetails` carry the new fields; the `copy` endpoint preserves them.
`createPresetChildren(preset, propertyAssignments)` / `deletePresetChildren(preset)` helpers
mirror the cue-side `createCueChildren` / `deleteCueChildren` pattern. DELETE route drops
children before `preset.delete()` to avoid FK violation. No DB migration file — the project
uses `SchemaUtils.createMissingTablesAndColumns`; registering `DaoFxPresetPropertyAssignments`
in `State.kt` is enough.

**Apply path.** `buildLayer3AssignmentsForPreset(fixtures, cueId, priority, presetId, presetAssignments, applyTargets)`
sits next to `buildLayer3AssignmentsForCue` in `routes/projectCues.kt`. Fan logic is a nested
loop: outer over targets (with group → member expansion), inner over preset assignments
(category lookup + value parsing + specificity tagging). Rows are tagged with the *cue's*
`cueId` and priority so `FxEngine.removeCueAssignments(cueId)` cleans them up alongside the
cue's own rows.

**`applyCue` integration.** Immediate preset applications (no `delayMs` / `intervalMs`) now
load preset effects + preset property assignments in one DB hit (`immediatePresets`
intermediate), concatenate their Layer 3 contribution with `buildLayer3AssignmentsForCue`'s
output, and hand the combined list to `engine.setCueAssignments` in a single call. Effect
spawn pass reuses the same `immediatePresets` data — no second DB round-trip. **Timed preset
applications don't contribute Layer 3** — `setCueAssignments` is a replace, so an at-fire
append path would need new engine API. Flagged as a Phase 3 follow-up.

**Cue-edit session.** `republishLayer3` in `CueEditSession.kt` extended: on each cueEdit
write it now includes preset property assignments for every immediate preset application
attached to the cue. `addPresetApplication` in LIVE mode invokes `republishLayer3` before
spawning preset effects, so the preset-contributed baseline lands before effects do — matches
`applyCue`'s ordering.

**Preset toggle Layer 3 pseudo-cue.** `togglePresetOnTargets` now also writes property
assignments to Layer 3 under a **negative synthetic `cueId = -presetId`** at priority 0.
Rationale: the user requested "Layer 4 direct-write", but a true property-level Layer-4
writer would need a `PropertyValue → channel-writes` resolver that doesn't exist (current
`DirectWriteStore.putProperty` is 7-bit MIDI scalar, colour/position not handled). The
pseudo-cue approach reuses the full Layer 3 pipeline; real cues (priority ≥ 1) still
override; untoggle calls `removeCueAssignments(-presetId)` to clear. New
`FxEngine.hasCueAssignments(cueId)` query helper checks whether the pseudo-cue is active,
so toggle state is derivable from engine state alone (mirrors the existing
`isPresetActiveOnTarget` effect-tag check). **Promoted to [Phase 7](#phase-7--property-level-layer-4-writer)**
(2026-04-21) to build the resolver and migrate toggle onto a real Layer-4 path — the
pseudo-cue shortcut stays until then.

**`AiTools.kt` signature change.** `togglePresetOnTargets` now takes a
`presetPropertyAssignments: List<FxPresetPropertyAssignmentDto>` parameter; callers pass
`emptyList()` when not applicable or the preset's rows when toggling by id.

**Tests.** `BuildLayer3AssignmentsForPresetTest` mirrors the cue-side test class: fixture
fan-out, group expansion with `targetIsGroup=true`, multi-assignment × multi-target product,
missing target / unknown property skip-and-continue, empty input. `./gradlew test` green.

### Phase 2 — CueEditor + stubs + stack-cue Live (landed 2026-04-21)

Replaces `CueForm` with `CueEditor` (fixture/group modal primitives). All
three call-sites — `Cues.tsx`, `ProgramPage.tsx`, `RunPage.tsx` — mount
`CueEditor`; `CueForm` / `CueEffectFlow` / `CuePresetPicker` deleted. The
latter two were renamed + moved under `src/components/cues/editor/` as
`EffectFlow` / `PresetPicker` since their only consumer is now
`CueTargetDetail`; the redundant target-picker step they still render when
opened from a selected card is acknowledged as a minor UX rough edge
(deferred to a follow-up that extracts their `configure` / `pick` / `timing`
steps into shared primitives).

**Session lifecycle.** `CueEditor` opens `cueEdit.beginEdit { cueId, mode }`
on mount (default `'live'`), emits `cueEdit.endEdit` on unmount, and
`cueEdit.setMode` on Live/Blind toggle. A `sessionIdRef` gate stops a
re-render during an in-flight save from reopening a session. An
`EditorContextProvider` with `{ kind: 'cue', id: cueId, mode }` wraps the
editor subtree so `useUpdateChannel` / `useUpdateFixtureColour` / group
update hooks dispatch `cueEdit.*` automatically.

**Fixture-level colour routing (closes Phase 1 Known Issue 1).** Fixed
without backend changes: `ColourSwatch` / `VirtualDimmerSlider` /
`PropertyVisualizer` gained an optional `fixtureKey` prop (threaded from
`FixtureContent` / per-head `PropertiesList` via `element.key`). New
`useUpdateFixtureColour` hook mirrors `useUpdateGroupColour`: in cue mode
RGB goes out as one `cueEdit.setProperty` with `propertyName: 'rgbColour'`
and `#RRGGBB` value; W/A/UV still go out as `cueEdit.setChannel`. Individual
R/G/B `ColourChannelSlider` onChange now go through the combined callback
(preserving the other two components), sending one `setProperty` instead of
three rejected `setChannel` calls. `useVirtualDimmer.setValue` routes the
same way.

**cueEdit handlers** (`CueEditSession.kt`):
- `setPalette(cueId, palette)` — replaces `DaoCue.palette`; in LIVE mode
  also calls `FxEngine.setCuePalette` so running effects pick up
  palette-ref changes.
- `addPresetApplication(cueId, presetId, targets, timing)` — inserts a
  `DaoCuePresetApplication`; in LIVE mode immediate presets (no `delayMs` /
  `intervalMs`) spawn per target via the same
  `createInstanceFromPresetForCue` path `applyCue` uses, with `cueId` +
  derived priority attached. Timed presets persist only —
  `CueTriggerManager` handles them at normal cue apply.
- `addAdHocEffect(cueId, effect)` — same immediate-vs-timed split. Sort
  order auto-computed if caller sends `0`.
All three ack via `cueEdit.paletteChanged` / `cueEdit.presetApplicationAdded` /
`cueEdit.adHocEffectAdded`; `cueListChanged()` broadcasts the REST-shaped
update so the client read model refreshes.

**Import hazard.** `FxPresetEffectDto` lives in
`uk.me.cormack.lighting7.models` (not `.fx`); `TogglePresetTarget` lives in
`uk.me.cormack.lighting7.routes` (not `.fx`). Future cueEdit handlers that
spawn effects need these exact package paths.

**Live stack-cue edit.** `CueEditSessionState` carries `cueStackId: Int?`,
snapshotted at `beginEdit` and kept in sync on `setMode`. Cleanup paths
branch on this. Private helper `applyCueForLiveEdit(state, applyData)`
centralises the branch: stack cues go through
`cueStackManager.activateCueInStack(..)` + `pauseAutoAdvance(stackId)` (the
pause immediately cancels the auto-advance timer step 8 of activation
schedules); standalone cues use `applyCue(.., replaceAll = false)`.
Called from both `beginEdit` LIVE and `setMode` BLIND→LIVE.

**Cleanup semantics.** Decision 2026-04-21 on session-end behaviour:
- **`endEdit` LIVE / disconnect** on stack cue → leave the stack active
  with the edited cue as its active cue; call
  `resumeAutoAdvance(state, stackId)` so the show keeps rolling.
  Rationale: a network blip or editor-close mid-show should not freeze the
  stack; this matches operator expectation from `CueStackPause`.
- **`setMode` LIVE→BLIND** on stack cue → `deactivateStack(stackId, state)`
  (drops effects + Layer 3 + triggers + cancels the paused auto-advance
  job). The operator explicitly asked for no stage impact.
- **Standalone cue** cleanup unchanged: `deactivateTriggersForCue` +
  `removeEffectsForCue`.

**Auto-advance helpers** on `CueStackManager`: new public
`resumeAutoAdvance(state, stackId, scope) → Boolean` (looks up the active
cue in `activeStacks[stackId]`, re-reads its `autoAdvance` /
`autoAdvanceDelayMs` from the DB, reschedules); no-ops if stack inactive,
no auto-advance, or a timer already running. Private
`scheduleAutoAdvance(state, stackId, delayMs, scope)` is the canonical
timer-launching shape used by step 8 of `activateCueInStack` and by
`resumeAutoAdvance`. The resumed timer starts its countdown from *now*,
not the original deadline — predictable after an N-second edit.

**Republish path untouched.** `setProperty` / `setChannel` /
`clearAssignment` during a stack-cue LIVE session continue to use
`republishLayer3` → `FxEngine.setCueAssignments(cueId, built)`. Crossfade
ownership is safe because a human-speed edit session doesn't overlap with
the 16 ms-tick crossfade window that only happens during cue transition.
Opening an edit on a mid-fade-in cue lets the crossfade complete naturally
before any edits arrive.

**Call-site migrations.**
- `Cues.tsx`: edit path mounts `CueEditor`. Create-new uses
  **create-first**: `handleCreate` POSTs a blank cue (or one pre-populated
  from `fetchCurrentState` for standalone) via `createCue`, then opens
  `CueEditor` on the returned id. Since `cueEdit.beginEdit` needs a
  backend cueId, creation cannot be deferred to Save. Trade-off: an
  abandoned creation leaves a blank "New Cue N" in the list — same as
  ProgramView's Add Cue flow. Deep-link params on the cue-row inline
  click/add handlers (`edit-preset` / `add-effect` / etc.) degraded to
  plain `onEdit()` — the new editor's per-target layout doesn't support a
  flat-list deep-link shape anyway.
- `ProgramPage.tsx`: wide-viewport inline panel + narrow-viewport sheet
  both render `CueEditor`. `cueFormProps` renamed to `cueEditorProps`;
  `isInStack: true as const` stays hardcoded (Program is always a stack
  context); `defaultEditMode: 'live' as const` added (OQ2 resolution).
- `RunPage.tsx`: mobile cue-list sheet renders `CueEditor mode="sheet"`
  with `defaultEditMode="live"`.
- `cueFormSaving` state removed from both routes. `CueEditor` owns its
  in-flight save state internally; operator-speed double-clicks are gated
  by that + the async mutation round-trip.

**Identifier rename.** Stale `CueForm`-flavored names on the remaining
routes renamed to match the new reality: `onOpenCueForm` → `onOpenCueEditor`
(ProgramView / StackDetail / ProgramCueRow), `cueFormOpen` /
`cueFormCueId` / `cueFormCue` / `cueFormStackId` → `cueEditorOpen` etc.,
`handleCueFormSave` / `handleCueFormClose` → `handleCueEditorSave` /
`handleCueEditorClose`, `openCueForm` → `openCueEditor`,
`showInlineCueForm` → `showInlineCueEditor`.


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
