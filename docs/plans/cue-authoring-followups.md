# Cue Authoring Unification — Follow-ups & Deferred Improvements

Items surfaced while running [cue-authoring-unification-plan.md](completed/cue-authoring-unification-plan.md) to completion (Phases 0–8 landed through 2026-04-23). None are blocking; each is captured here so the plan file itself can be closed out.

Companion: [control-surface-followups.md](control-surface-followups.md) — some deferred items span both plans and are cross-referenced rather than duplicated.

## Frontend polish

### 1. `ColourPickerPopover` W/A/UV sliders

**Origin**: Phase 8, deferred 2026-04-23.

Backend now emits white / amber / UV channels symmetrically for `Colour` property values via `PropertyChannelWriter` (`fx/PropertyChannelWriter.kt`). `FxColourPicker` and `PropertyVisualizers` already expose per-channel sliders through `ExtendedChannelSlider`, so direct manipulation already covers the extended channels. The one remaining surface is `lighting-react`'s `ColourPickerPopover`, which still renders RGB-only.

**Scope**: add W/A/UV sliders to the popover, gated on `ColourPropertyDescriptor.whiteChannel` / `amberChannel` / `uvChannel` presence. Pure UI polish; no backend change required. Palette serialisation already round-trips the extended suffix (`#rrggbb;w128;a64;uv32`), so the popover can write them through the existing `useUpdateFixtureColour` / `useUpdateGroupColour` hooks unchanged.

### 2. Effect / Preset picker UX polish

**Origin**: Phase 2d, deferred 2026-04-21.

Inside `CueTargetDetail`, the Effects and Presets tabs still open `EffectFlow` / `PresetPicker` at their target-picker step even though a card selection is already active. Redundant — the selection is known, the picker step shouldn't re-ask.

**Fix shape**: extract `EffectConfigureStep` / `PresetPickStep` / `TimingFields` into `src/components/cues/editor/shared/` and drop the target-picker step when opened from a selected card. Keep the full flow available for the non-selected-card entry path.

### 3. True in-place "Rebind" UX for dead assignments

**Origin**: Phase 6, deferred 2026-04-22.

`DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` ship a single Remove button per dead row. The plan's original framing called for a "Rebind" quick-action that opens a picker pre-populated with the dead assignment's property + value and creates a new row on commit. We shipped the simpler Remove-and-re-author flow — which covers the fixture-rename workflow discoverably — and deferred the in-place rebind.

Revisit if operator feedback asks for it. `PropertyChannelWriter` (landed Phase 7) is the right resolver to drive a live-preview of the proposed rebind, so the implementation is now unblocked.

### 4. "Preview on selection" live-apply for `PresetEditor`

**Origin**: Phase 3, deferred 2026-04-21 — unblocked by Phase 7 (2026-04-23).

Apply the preset-in-progress to a scratch fixture selection inside the preset editor to test live. Blocked on a property-level resolver at the time; `PropertyChannelWriter` now provides exactly that. Implementation is a Layer-4 write fan-out across the scratch selection when the editor has a live preview active, with a toggle-off clear on editor close.

## Backend / composition model

### 5. Palette-resolution cascade for presets

**Origin**: Phase 3, flagged 2026-04-21.

Backend ships the `fx_presets.palette` data field, but the resolver still treats palette refs as "global only" — preset palette → cue palette → global cascade isn't wired. A colour swatch with a palette ref inside a preset is resolved against the global palette, not the preset's own.

**Fix shape**: teach `Layer3Resolver` to take a per-contribution palette context (cue palette + preset palette when the row came from a preset application), resolving property-like. Cue palette already flows through via `FxEngine.setCuePalette`; extend to carry the originating preset's palette through the `buildLayer3AssignmentsForPreset` path.

### 6. Timed preset property assignments contribute Layer 3

**Origin**: Phase 3, flagged 2026-04-21.

`applyCue` immediate presets (no `delayMs` / `intervalMs`) fan their property assignments into Layer 3 alongside the cue's own assignments. Delayed / recurring preset applications stay effects-only — `FxEngine.setCueAssignments` is a **replace** operation, so a runtime "append Layer 3 at fire time" path needs new engine API.

**Fix shape**: add `FxEngine.appendCueAssignments(cueId, additions)` that composes with the existing stored list instead of replacing it. Wire `CueTriggerManager`'s timed preset fire path to call `appendCueAssignments` at fire time and `removeCueAssignmentSubset(cueId, keys)` on stop. Document the semantics — operators should expect timed presets to contribute layer state from fire-time, not from cue-apply time.

### 7. Preset per-head / per-element assignments

**Origin**: Phase 3, flagged 2026-04-21.

Preset property assignments are preset-local (`propertyName` only, no `target_type` / `target_key`). This works for single-head fixtures and fixtures without addressable elements, but a 4-head LED bar preset has to pick one head's values for all heads.

**Fix shape**: add a nullable `element_key` column to `fx_preset_property_assignments`; when null, assignment applies to all heads (current behaviour); when non-null, applies only to the named element. `PresetEditor` UI grows an element switcher for multi-element fixture types. Audit required for which fixture types need this — if the set is small (SlenderBeam, LedLightbar12Pixel), a simple "heads tab" alongside Properties/Effects covers it.

### 8. `moveInDark` during outgoing fade

**Origin**: Spec'd in Phase 1; not yet implemented.

The current linear-interp crossfade path handles basic position fades. The "pre-apply incoming position during outgoing fade when outgoing intensity is 0 at end" affordance (spec'd in [lighting-composition-model.md](../lighting-composition-model.md)) is still deferred.

Scope small — `Layer3Resolver` already knows the `moveInDark` flag on each `Assignment`. Good candidate for a standalone session once a real moving-head fixture is on the test rig and can validate the behaviour visually. Without hardware, it's hard to judge the "pre-apply window" threshold empirically.

### 9. Hard `NOT NULL` on `fx_presets.fixture_type`

**Origin**: Phase 3, flagged 2026-04-21.

Runtime enforcement (input validation) already rejects nulls as of Phase 3 backend. DB column stays nullable during a backfill window so pre-existing rows don't break boot.

**Fix shape**: after a backfill pass (or on next migration sweep), tighten the column to `NOT NULL`. No meaningful operator impact — this is a DB-hygiene tidy.

## Test infrastructure

### 10. `FxEngineBenchmark` CI regression gate

**Origin**: Phase 5, deferred 2026-04-22.

Benchmark ships track-only. The plan called for a fail-on-regression gate at ±20% tolerance against a committed baseline.

**Trigger to revisit**: collect a week of baseline numbers across the dev / CI hardware first to judge variance — a 20% threshold is a guess, and the real tolerance depends on how jittery the allocation counter + `measureNanoTime` numbers are on the actual CI runner. Without that variance study, a fixed threshold will either flake constantly or fail to catch real regressions.

### 11. Reduce FxEngine per-tick allocation

**Origin**: Phase 5 baseline capture, 2026-04-22.

Benchmark baseline: ~600 µs p50 / ~4 ms p99 per beat tick, **~1.9 MB/tick allocation** on a 4×168 HexFixture rig with 336 effects. Allocation is the most surprising number; not an immediate correctness concern, but a real future optimisation target.

Hot spots to profile before refactoring: `Layer3Resolver.compose*` list-building, `PropertyValue` boxing through the sealed hierarchy, `ChannelWrite` record allocation per channel per tick. `JFR` flight recording over a benchmark run should surface which of the three dominates.

### 12. End-to-end HTTP round-trip test

**Origin**: Phase 1, flagged 2026-04-21.

`PATCH` + `snapshot-from-live` + `cueEdit` round-trip through an in-memory HTTP harness is still missing. Phase 5's FX-engine-level pipeline test covers composition semantics in isolation, but the HTTP-path end-to-end is a separate gap.

**Blocker**: no DB test harness currently (tests run against a fresh Postgres schema or not at all). A proper fix sets up an ephemeral Postgres via Testcontainers (or a transaction-rollback harness against a shared dev DB), wires Ktor's `testApplication` DSL around it, and drives the full cue-edit → snapshot → apply flow. Cross-cutting — would unblock a bunch of other route-level tests too.

### 13. Live-rig validation for Phase 6 dead-assignment banner

**Origin**: Phase 6, flagged 2026-04-22.

Backend logic for `DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` is stateless and covered by unit tests. The WS fan-out + React rendering of dead markers after a fixture rename wasn't validated end-to-end on real hardware during the landing session.

Manual test: rename a fixture in a patch, reload the cue editor, confirm dead markers appear on affected rows, confirm Remove clears them. 10 minutes; worth doing before declaring Phase 6 fully validated in the field.

### 14. Frontend `vite build` validation

**Origin**: Phase 6, flagged 2026-04-22.

Node 16.x on the development machine at landing time blocked `vite build`; `tsc --noEmit` passed. Subsequent landings have worked around this. Confirm a clean prod build once Node is upgraded — nothing structural is known to fail, but the check hasn't run cleanly in a while.

## Cross-plan cross-references

These items surfaced during cue-authoring but live in [control-surface-followups.md](control-surface-followups.md) because the implementation is surface-coupled:

- [§4 Stringly-typed `"fixture"` / `"group"` target types](control-surface-followups.md) — `CuePropertyAssignmentDto.targetType`, `AssignmentKey`, `resolveTargetForCue`. Cross-cuts cue + surface code. A sealed `TargetRef` type would touch both.
- [§6 `AssignmentKey` vs `Layer3Resolver.Key` convergence](control-surface-followups.md) — the resolver key is targetType-agnostic because group expansion happens upstream; surfaces + cues need the discriminator. A future refactor that adds targetType to `Layer3Resolver.Key` is a bigger change than either plan's scope.
- [§7 Group-level Layer 3 assignments](control-surface-followups.md) — `routes/projectCues.kt::captureCurrentState` emits `targetType="fixture"` rows (post-expansion), but `cueEdit.setProperty` accepts `targetType="group"` rows. Round-tripping a group edit through `captureCurrentState` loses the group shape. Decision needed on whether group-scoped Layer 3 rows are first-class.
