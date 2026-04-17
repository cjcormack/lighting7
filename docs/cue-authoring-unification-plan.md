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

**Phase**: 0 — not started.

**Most recent session**: Planning v2 (2026-04-17). Added Phase 0 Layering Foundation after exploring current composition pipeline; resolved data-model open questions via layer model. No code changes yet.

**Next actions** (for the session that picks this up):
1. Read [Phase 0 — Layering Foundation](#phase-0--layering-foundation).
2. Draft `docs/lighting-composition-model.md` — the canonical spec the refactor targets. Discuss with user before coding.
3. Update this document's Status block at end of session.

**Per-phase tracker:**

| Phase | Summary | Status |
|-------|---------|--------|
| 0 | Layering foundation: formalise channel composition (parking, effects, property assignments, direct writes, fades) into a documented, explicit pipeline | Not started |
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

- **Live edit**: opening a cue for edit activates it on stage; direct-manipulation changes apply live AND persist into the cue.
- **First-class property assignments**: new backend child collection `CuePropertyAssignment`. Migrate existing `StaticValue` / `StaticSetting` ad-hoc effects into it.
- **Scope**: all views (Cues, Presets, Program) in phases; shared primitive first.
- **Presets stay** as reusable effect/state bundles. Preset editor rebuilt on the same surface.
- **Inspiration for the editor UI is the Fixture/Group detail modals**, not the Busking view.
- **Busking view is out of scope** — not folded into the cue editor.
- **Not running in production yet.** No rollback shims, no read-compat wrappers, no feature flags required. Migrations can be lossy / iterative.
- **Layering foundation is a prerequisite.** Phase 0 formalises channel composition before we add property assignments as a new layer.

### Data-model decisions (resolved via the layering model)

- **`value` is stored property-level, not channel-level.** Colour as hex, dimmer as 0–255 or 0–1 depending on the property's canonical form, settings as enum string, pan/tilt as their native unit. The composition resolver expands property → channels at apply time. Reason: the cue stores the operator's intent (a colour, a setting) not a pre-resolved channel mapping; fixture patch changes don't invalidate the cue.
- **Property assignments live in Layer 3** (see composition model below). Effects blend over them per each effect's own blend mode. OVERRIDE replaces the assignment; ADDITIVE adds to it; MAX / MIN bound it. This is the answer to "base vs effects": the existing blend-mode machinery is the answer, applied between layers rather than only within layer 2.
- **Group vs member specificity**: we record the operator's choice (group row or fixture row) as-is. At resolve time the fixture/member-level assignment wins over a group-level assignment for the same property. Reason: operators set group-level "all these lights are red", then override individual fixtures; the specificity rule matches that mental model.
- **Direct writes while a cue is open for edit** are **routed into the cue's property assignments** via `cueEdit.setChannel`, which upserts a Layer 3 row for the target. Outside of edit mode, direct writes remain as today (Layer 4, ephemeral under effects).
- **Dirty state / discard**: no soft-edit buffer. Edits auto-persist. User can trigger the previous saved state via cue re-trigger or via explicit `cueEdit.clearAssignment`. Undo is out of scope.

## Target experience

Opening a cue enters **Cue Edit mode**:

- Cue activates on stage.
- Main surface = a fixture/group list styled like today's `Fixtures` and `Groups` pages. Each compact card shows the cue's contribution (colour swatch, dimmer range bar, mixed-state indicators) derived from the cue's `propertyAssignments`.
- Selecting a card opens the existing `FixtureContent` / `GroupPropertiesSection`, bound to an `EditorContext` so its writes go into the cue. All existing property primitives (colour picker, sliders, pan/tilt, setting dropdowns, per-head overrides) work unchanged.
- Per-target tabs: **Properties** (default) | **Effects** | **Presets**.
- Palette bar at the top.
- Metadata (name, number, fade, notes, auto-advance, stack) in a collapsible header.
- Triggers (script hooks) in a separate side panel.

Leaving edit mode deactivates the cue or hands back to stack playback. Reopening reproduces the exact stage shape because fixture state is stored directly.

---

## Phase 0 — Layering Foundation

**Goal**: define a formal, documented channel-composition model and refactor the backend pipeline to match it. No user-visible feature changes; existing UIs keep working. This unblocks every later phase.

### Entry criteria
- None.

### Exit criteria
- `docs/lighting-composition-model.md` exists and is reviewed — the canonical spec for channel output composition.
- `FxEngine` / `ControllerTransaction` / `ArtNetController` refactored so the layer pipeline is explicit in code (named layers, ordered composition, single documented output site).
- Parking is reframed as "Layer 1" in code and docs (the implementation may still short-circuit at transmit time as an optimisation, but it's documented as a layer).
- Multi-effect-per-property has a defined rule (see below).
- Direct writes have a defined interaction with effects and property assignments.
- `dmx-engineering.md`, `fx-engineering.md`, `cues-engineering.md` updated to reference the composition model instead of describing ad-hoc rules.

### Proposed composition model (for the doc)

Channel output is computed per frame as the top-most non-empty contribution from an ordered stack of layers. A per-channel output is a single byte; a per-property "intent" is the richer input that resolves to channels.

**Layers, top to bottom:**

1. **Parking** — absolute override. If a channel is parked, transmit the parked value; no other layer contributes. Unchanged from today (`ParkManager`); reframed in docs as Layer 1.
2. **Effects** — FxEngine's composed output of all active `FxInstance`s targeting this channel, each scaled by its cue/stack fade envelope (`intensityMultiplier`). Effects are ordered by priority (see multi-effect rule below); within each effect, `blendMode` describes how the effect's output combines with **the accumulated output of lower layers + prior effects**.
3. **Property Assignments** — deterministic property-level state contributed by active cues. Resolved per-channel by (a) expanding target → fixtures (group → members, with member rows overriding group rows), (b) expanding property → channels using the fixture's patch, (c) resolving per-cue precedence by cue stack ordering + standalone cues (last-applied-wins within the layer, matching today's cue model). Static property assignments always use `OVERRIDE` within this layer.
4. **Direct Live Writes** — transient writes from `updateChannel` that aren't currently scoped to a cue-edit session. Visible when no higher layer is writing to the channel. Today's behaviour preserved: they're overwritten by effects on the next tick.
5. **Default / baseline** — 0 (blackout) unless fixture profile specifies otherwise.

**Multi-effect-per-property rule** (to replace today's undefined last-wins): effects targeting the same channel compose in priority order, with each effect's `blendMode` applied against the accumulated value. Priority = (a) stack position for cue-stack cues, (b) creation order for standalone / manual effects. Equal priorities fall back to creation order. Document this explicitly.

**Direct write + cue edit interaction**: when `EditorContext` on the client is `kind: 'cue'`, the client sends `cueEdit.setChannel` instead of `updateChannel`. The server upserts a Layer 3 property assignment (resolved from channel → property) AND performs the same transient channel write for instant feedback. On the next frame, Layer 3 naturally wins over transient Layer 4 writes, so the value sticks.

**Crossfade envelopes**: continue to implement as `intensityMultiplier` on `FxInstance`, applied inside Layer 2 composition. Property assignments also need fade behaviour — suggestion: track per-cue fade weight and apply as an output multiplier on Layer 3 contributions during transitions. Decide concretely in Phase 0 design.

**Palette**: not a layer. It's a lookup table referenced by effect scripts (and, once added, by property assignments for "use palette colour P2"). Unchanged.

### Phase 0 work

Repo: `/Users/chris/Development/Personal/lighting7`.

- **Write `docs/lighting-composition-model.md`** first. Include the layer stack, precedence rules, direct-write semantics, fade behaviour, palette role, parked-channel semantics, and how groups / element modes expand at resolve time. Review with user before touching code.
- **Refactor output pipeline** so layers are explicit:
  - Introduce a `Layer` abstraction (or at minimum named composition steps) in `FxEngine.processBeatTick()` / `ControllerTransaction.apply()`. Today the reset-to-neutral pass + per-effect apply is the implicit Layer 2; make it explicit so Layer 3 (empty for now) slots in cleanly.
  - Define priority ordering for `FxInstance`s (add a field or derive from existing tags). Replace today's `ConcurrentHashMap` iteration with a sorted pass.
  - Keep `ParkManager`'s transmit-time override as-is but document it as Layer 1; add a matching pre-composition skip so parked channels don't waste work in Layer 2.
- **Don't add property assignments in this phase.** Layer 3 is reserved and empty. Phase 1 fills it.
- **Tests**: unit tests for the composition pipeline covering every layer interaction (parked + effect, effect + effect at same priority, effect + effect at different priorities, direct write below effect, direct write above baseline). Existing effect/parking tests should continue to pass.

No frontend changes in Phase 0.

### Files touched in Phase 0

- `src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt` (composition pipeline; lines around 545–1200).
- `src/main/kotlin/uk/me/cormack/lighting7/fx/FxTarget.kt` (blend mode application; lines 196–317).
- `src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt` (parking override at line 257; reframe, don't necessarily move).
- `src/main/kotlin/uk/me/cormack/lighting7/dmx/ParkManager.kt`.
- **New**: `docs/lighting-composition-model.md`.
- Updates: `docs/dmx-engineering.md`, `docs/fx-engineering.md`, `docs/cues-engineering.md` — cross-reference the new model.

### Phase 0 verification

- New unit tests for each layer interaction pass.
- Existing tests pass unchanged.
- Run the backend + frontend manually. Confirm no user-visible behaviour change in fixture/group modals, cue apply, busking, and program playback.
- Verify parked channels still win over effects. Verify two effects on the same property now compose deterministically by priority (previously last-wins).

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
  - `cueEdit.beginEdit { cueId }` / `cueEdit.endEdit { cueId }` — session tracks active cue-edit.
  - `cueEdit.setChannel { cueId, universe, id, level }` — resolves channel → fixture/property via patch, upserts a property assignment in that property's canonical form, AND performs the transient channel write for instant feedback.
  - `cueEdit.setProperty { cueId, targetType, targetKey, propertyName, value }` — explicit property-level form for colour, settings, etc. where channel-resolution would lose fidelity.
  - `cueEdit.setPalette { cueId, palette }`.
  - `cueEdit.addPresetApplication { cueId, presetId, targets, timing? }`.
  - `cueEdit.addAdHocEffect { cueId, ... }`.
  - `cueEdit.clearAssignment { cueId, targetKey, propertyName }`.
  - Explicit messages per write (no implicit capture) — less magic, easier to debug.
- **Migration**: convert `StaticValue` / `StaticSetting` rows in `cue_ad_hoc_effects` into `cue_property_assignments`; delete originals. Lossy / inexact conversions are OK (not in production).
- **Tests**: migration correctness, PATCH round-trip, `applyCue()` layer-3 integration (uses Phase 0 pipeline), `cueEdit.setChannel` upserts + live-write, group-vs-member specificity resolution.

### Phase 1 frontend work

Repo: `/Users/chris/Development/Personal/lighting-react`.

- **New folder** `src/components/lighting-editor/`:
  - `EditorContext.tsx` — React context `{ kind: 'live' | 'cue' | 'preset', id?: number }`. Default `kind: 'live'`.
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
  - Header: metadata + palette bar (lift `CuePaletteEditor` from `CueForm.tsx:397-430` into shared).
  - Main area: segmented control (`Groups` / `Fixtures`), grid of `CompactFixtureCard`-style cards. Each card driven by the cue's `propertyAssignments` via `GroupPropertyVisualizers`.
  - Detail pane: selected card opens `FixtureContent` / `GroupPropertiesSection`, wrapped in `<EditorContext.Provider value={{ kind: 'cue', id }}>`.
  - Per-target tabs: **Properties** / **Effects** / **Presets**.
  - Triggers panel as collapsible aside, reuses `CueTriggerEditor`.
  - Lifecycle: on mount → `POST /cues/{id}/apply` + `cueEdit.beginEdit`; on unmount → `cueEdit.endEdit` + `/stop` (or hand-back to stack playback).
- **Effects-overlay preview**: when effects are running on the cue being edited and obscuring a property the user is trying to see, show a subtle indicator on the property pad (e.g. "Effect active — showing base value"). Full solo-layer preview is a stretch goal. Add this affordance in Phase 2 design; revisit if users struggle.
- `src/routes/Cues.tsx` — swap sheet contents for `CueEditor`.
- `src/routes/ProgramPage.tsx` — wide-viewport inline panel mounts `CueEditor` (rough; Phase 4 polishes).
- Obsolete: `src/components/cues/CueEffectFlow.tsx`, `CuePresetPicker.tsx`. Keep `CueFxTable.tsx` for read-only previews in list rows.

### Phase 2 verification

Use `preview_*` MCP tools against running frontend + backend.

- Open a cue from `Cues.tsx` → cue activates on stage.
- Groups mode: pick a group, change colour → stage updates; close editor; reopen → cue persists change.
- Fixtures mode: open a multi-head fixture, set per-head overrides → overrides persist.
- Effects tab: add a beat-synced ad-hoc effect → runs on stage, saved.
- Presets tab: quick-apply → preset application saved.
- Close editor; trigger cue normally → identical stage output.
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

1. **Stack mid-fade**: if a cue is opened for edit while another cue in the same stack is mid-fade, what's the correct behaviour? Snap-to-target then enter edit is proposed but not confirmed.
2. **Mid-show safety**: live edit mode is the default, but should there be a stack-state guard that warns when editing a cue currently running in a live-show context?
3. **Auto-advance pause**: should opening a cue for edit pause the parent stack's auto-advance? Strongly suggested yes but not confirmed.
4. **Session ownership of `cueEdit.*`**: with multi-client connections, should the server reject `cueEdit.setChannel` from clients that didn't send `beginEdit`? Default to yes (session-scoped) unless looser semantics preferred.
5. **Layer 3 fade behaviour during cue crossfades**: property assignments need a crossfade path matching effects' `intensityMultiplier`. Is weighted blend between outgoing/incoming cue's property values acceptable, or do we want value-level interpolation (e.g. colour-space-aware) for specific property types? Phase 0 design question.
6. **Phase 2 effects-overlay preview affordance** — how prominent? Just a badge, or a "show base only" toggle?

## Handover checklist (end of every session)

- [ ] Update the [Status](#status) block — phase, most-recent-session date, next actions.
- [ ] Tick off completed bullets in the current phase's Work section.
- [ ] Append new decisions to [Decisions](#decisions) with the date.
- [ ] Append new questions raised to [Open Questions](#open-questions).
- [ ] Note deviations from plan in a **Change log** section at the bottom (create on first use).
- [ ] Commit this file alongside the code changes.
