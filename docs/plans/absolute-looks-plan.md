# Looks as absolute states

> **Document status: agreed 2026-10-21; session 1 of 3 done.** D1–D3 are Chris's calls, made after the
> Timeline drew one scenery-only Look as three columns. The P rows are the plan's own.

## 1. Context

Sleeping Beauty's Pre-show holds one layer of *Act 1, Scene 1 — Set*, a Look holding only scenery,
and the Timeline draws it as three columns: House 1, House 2 and House 3. The layer names those
fixtures because they were selected when the Look was pressed. They change nothing on stage, since the
Look has no rows and no effects, but everything that reads a layer's targets reads them.

What the code says, read on 2026-10-21 at `b776bad6`. `K/` is `src/main/kotlin/uk/me/cormack/lighting7`,
`F/` is `frontend/src`.

- **A Look press takes the selection as given.** `resolveLookToggleTargets`
  (`K/routes/projectLooks.kt:620`) uses the request's targets when there are any, else the Look's own
  fixtures; a scenery-only Look pressed with none gets a layer naming none (`:627`). The apply picker
  sends the selection unless nothing is selected (`F/components/press/ApplyPicker.tsx:146`), and the
  busk pad, the cue editor and `apply_look` send it too.
- **A Look layer's targets do three jobs in the cook.** They fan out a deferred effect, one instance
  per target (`K/fx/CueComposer.kt:529`); they are the allowed set for the Look's bound rows
  (`:715-760`, `LayerTargets.covers`); and they drop a bound effect they do not cover (`:539-544`).
- **And more outside it.** The press works per head: an off press narrows a Look layer to the heads
  not pressed, and an on press widens the top one (`K/fx/LayerPress.kt`). In a cue an untargeted Look
  layer is read as its own fixtures so that a press can subtract from it (`K/routes/cueLayerPress.kt:235`,
  `CueLayerOps` at `:291`). A pad's ring is the Look's coverage of the selection
  (`ProgrammerLayerStack.appliedState`, `K/fx/ProgrammerLayerStack.kt:448`;
  `F/components/busking/lookPresence.ts`), so an untargeted Look layer lights nothing. Record keys a
  layer on `(source, targets)` (`K/routes/programmerRecord.kt:320`) and a scoped Record narrows layers
  by target (`:486`). A focused Look layer refuses writes outside its targets
  (`F/components/programmer/LookRowStore.tsx:180`, `AddToTargetsButton`). Detach and Apply as values
  take group hints from the targets. The Timeline makes a column per Look and target
  (`F/lib/stackTimeline/columns.ts:96`).
- **A deferred Look effect** is a `look_effects` row with `target_type = 'deferred'`. MCP's
  `create_look` and `update_look` write every effect that way (`parseLookEffects`,
  `K/ai/AiTools.kt:2444`). A Look holding one needs a selection wherever it is pressed: cue slots, the
  hand, surface buttons and the busk auto-layout all branch on `hasDeferredEffects`, and Include runs
  it on the request's targets or the desk selection (`K/routes/programmerLookInclude.kt`).
- **A press also carries the selection's mask.** A Look lands masked to `mask ∩ its families`
  (`resolveLookMask`, `K/routes/projectLooks.kt:660`), or is refused when nothing is inside
  (`LOOK_OUTSIDE_MASK`). A layer's mask is also an operator setting after the press: the programmer's
  layer popover (`F/components/looks/LookStack.tsx:920`), the cue editor's Looks tab, the Timeline's
  `layerSettings`, the promotion mask `MakeLayerSheet` gives a new layer, and MCP's cue layers.
- **On this desk**, one Look has a deferred effect: a ColourCycle on Experiment's *S5 Deferred FX*, a
  test. Two cue layers press a Look, Pre-show's and 43!'s *Haze*. Both have targets, but both Looks
  have no rows, so neither target list changes what lands. Programmer layers are runtime only
  (`K/fx/ProgrammerStore.kt:324`).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **A Look is an absolute state**, a sub-cue: pressed, it lands its own bound rows, bound effects and scenery, never narrower and never wider. A Look layer names no targets. To apply something to the selection, use a palette. |
| D2 | **Deferred Look effects go.** Every Look effect names its target: a fixture, a group or a selection. The ones stored are dropped, with a warning, once from this database and on every import. |
| D3 | **A Look layer's mask is a layer setting, not the press's.** A Look press ignores the selection's targets and mask alike. The layer popover, the cue editor, the Timeline's *Settings…*, the promotion mask and MCP still set one, as they set amount or blend. |

The plan adds its own:

| # | Decision |
|---|---|
| P1 | **A Look press is whole.** On puts one layer of the Look on top; off takes every layer of it off, whatever is selected. A press never adds a second layer of a Look. |
| P2 | **The doors keep their fields.** A press route serves palettes too, so its `targets` and `families` stay, and a Look press ignores them. A stored write that would give a Look layer targets (a cue's layers, `programmer.addLayer` and `patchLayer`, the Timeline's ops, MCP's cue layers) strips them in session 2, and from session 3, once no client sends them, refuses them by name (`LOOK_LAYER_TARGETED`). |
| P3 | **In a solo bank a Look comes and goes whole, as a cue does.** A Look press takes every layer sibling off whole and lets go of cue siblings. A palette press takes a Look sibling off whole, since it cannot be narrowed, and narrows palette siblings on its heads as today. |
| P4 | **A Look's ring is on or off**: lit while a layer of it is on the programmer's stack, whatever is selected. `appliedState` keeps coverage for palettes only. A surface's `LOOK_APPLIED` and a Look pad's `COVERS_SELECTION` both mean "a layer of it is on". |
| P5 | **Record keys a Look layer on its source.** A scoped Record (*Selection only*) replaces a cue's Look layer only when every fixture the Look lands on is in scope, and otherwise keeps it. Today it replaces an untargeted layer under any scope (`K/routes/programmerRecord.kt:486`). |
| P6 | **Include and Apply as values take no targets for a Look**, and Detach takes its group hints from the rows' own targets. |
| P7 | **The fixture sheet's Look picker goes** (`F/components/fx/LookTogglePicker.tsx` and the routes' `compatibleLookIds`): a Look cannot be put on one fixture. An effect palette does that job. |
| P8 | **No sync bump.** Nothing new is written, and a v37 reader imports what this desk writes correctly: an untargeted Look layer and a bound Look effect are both v37 states. The importer drops deferred Look effects and Look-layer targets from any archive and logs each, as `loadLookSnapshot` already drops a deferred Look row. |
| P9 | **Two one-shot steps**, `PRAGMA user_version` 2 and 3 after `CueEntryUuidMigration`'s 1, one per backend session: step 2 deletes deferred `look_effects` rows, step 3 clears `cue_layers.targets` where `look_id` is set. Each logs what it changed. |

## 3. What changes on the desk

### 3.1 Deferred effects (D2)

- `validateLookEffects` (`K/routes/projectLooks.kt:1033`) refuses `deferred`. `LookEffectDto.isDeferred`
  goes and `EffectEntry.target` becomes non-null (`K/fx/LookRegistry.kt:51, 323`). `loadLookSnapshot`
  drops a deferred effect an older database wrote, as it already drops a deferred row.
- The cook's deferred arm goes (`K/fx/CueComposer.kt:529-536`).
- Include loses `deferredTargets` and its desk-selection fallback (`K/routes/programmerLookInclude.kt`,
  `K/routes/programmerSurface.kt:253-272`), and `IncludedLookEffect.deferred` goes
  (`K/fx/FxInstance.kt:196`). So do Update stage's head-by-head pairing of a deferred effect
  (`K/routes/cueUpdateStage.kt:169`) and Record's Update keeping one deferred
  (`K/routes/lookRecord.kt:665-690`).
- MCP: `create_look` and `update_look` effects require a `target` or `targets`, as an ad-hoc effect
  does (`K/ai/AiToolSchemas.kt:216-290`). `include_into_programmer`'s targets are for palettes only.
  `RigBriefing` and `SetupTools` lose their deferred prose.
- `LookSummary.hasDeferredEffects` stays on the wire, always false, until session 3 removes it along
  with its readers.

### 3.2 The press (D1, D3, P1–P4)

- `resolveLookPress` (`K/routes/pressArm.kt:48`) answers no targets and no mask. `resolveLookToggleTargets`,
  `resolveLookMask`, `LOOK_NEEDS_SELECTION`, `LOOK_NO_TARGETS`, `LOOK_OUTSIDE_MASK` and `skippedFamilies`
  go (`K/routes/projectLooks.kt:483-674`). A Look none of whose fixtures is patched lands nothing,
  as a cue does, and its ring still lights (P4).
- `LayerPress`: a Look layer names nothing, so its arm is always the twin. P3 needs a whole-sibling
  rule: today a palette press keeps an untargeted Look sibling, and a Look press naming no targets
  keeps a targeted palette sibling (`press`, `without`). `decideCueLayerPress`'s own-fixtures arm and `CueLayerOps`'
  `pressedLook` go (`K/routes/cueLayerPress.kt:235-262, 291`).
- `ProgrammerLayerStack`: `toggle`, `wouldRelease`, `installFromCue` and `patch` (`K/fx/ProgrammerLayerStack.kt:337-764`),
  and `appliedState` per P4.
- The doors: the busk press (`K/routes/BuskPressService.kt:183`), cue slots
  (`K/routes/projectCueSlots.kt`, where `CUE_SLOT_LOOK_NEEDS_SELECTION` goes), the hand's
  eligibility, `apply_look`'s `pressLook` (`K/ai/AiTools.kt:404`), and MIDI: `applyLook` and
  `pressPad` (`K/midi/SurfaceActions.kt:469-573`), the bind-time refusal
  (`K/midi/ControlSurfaceBindingService.kt:672-700`), `BindingHealthEvaluator`,
  `AssignmentHealth.LookNeedsSelection`, `SurfaceFeedbackPublisher` per P4, and `State.kt:625-656`'s
  "needs a selection" query.
- `/looks/{id}/apply` and `apply_look`'s `asValues` take no targets (P6).

### 3.3 The cook (D1)

A Look layer's rows and bound effects land whole. `LayerTargets` keeps filtering palette layers only
(`K/fx/CueComposer.kt:539-544, 715-760, 940-985`). The mask still filters rows (`:679-684`).

### 3.4 Record, Detach and Update (P5, P6)

- `K/routes/programmerRecord.kt`: REMOVE and the upsert match a Look layer on its source (`:206`,
  `:320`), and the scoped replace follows P5 (`:486`). `K/routes/programmerUpdate.kt:570-592` writes no
  targets for a Look layer.
- Detach and Apply as values take group hints from the rows (`K/fx/ProgrammerLayerStack.kt:564, 630-673`).
- `RigKeyReferences` (`K/ai/RigKeyReferences.kt:194`) no longer finds Look-layer targets to rewrite.

### 3.5 The reads

- The Timeline route's `lookHeads` (`K/routes/cueStackTimeline.kt:120-148`) is the Look's own
  fixtures, and its edit ops take no target for a Look (`K/routes/cueStackTimelineEdit.kt:191-206`).
- `compatibleIdsFor` (`K/routes/lightFixtures.kt:145-162`) loses its Look half (P7).

### 3.6 Sync and the database (P8, P9)

- The importer drops a deferred Look effect and a Look layer's targets, logging each, beside the
  cue-layer import (`K/sync/ProjectImporter.kt:1691`). The exporter is unchanged.
- `RichProjectFixture` keeps a Look layer and a bound Look effect, its Look layers naming no targets.
- `docs/sync-engineering.md` gets a paragraph beside "A detached show's origin … took no bump", for
  the same reason.
- Steps 2 and 3 sit in `K/state/` beside `CueEntryUuidMigration.kt`, each tested over a database that
  holds one row it changes and one it must not.

## 4. The client (session 3)

- Press senders send no targets for a Look: `usePress` (`F/hooks/usePress.ts:289-293`); `ApplyPicker`,
  which loses *Own fixtures* and its Looks' "outside the mask" section (`:131-156, 218, 324-333`);
  `CueRowStore.pressLayer`; `MakeLayerSheet`, which keeps its promotion mask (`:106-114`, D3); and the
  hand (`F/lib/handTargets.ts`, `HandLayerTargets.tsx`, `slotDrop.ts`).
- Rings follow P4: `lookPresence.ts`, `BuskingView.tsx:383-417`, `CueSlotOverviewPanel.tsx`.
- `hasDeferredEffects` goes, with its readers: `LibraryPalette.tsx:268-275`, `buskLayout.ts:642`'s pool,
  `SurfaceLibrary.tsx:576-583`, `recordOptions.ts:76`, `LookSheet.tsx:398`, `LookDetailSheet.tsx:288`
  and `CueEffectsTab.tsx:466`.
- A Look layer shows no target chips: `LookStack.tsx:478-510`, `AddToTargetsButton` for a Look,
  `StackTable.tsx:330`'s `targetsLabelOf`, and `CueLooksTab.tsx:119`'s `onSetTargets`. A focused Look
  layer loses its write guard (`LookRowStore.tsx:180`): every write lands in the Look, as it does
  today on a layer naming none. The mask pickers stay.
- `LookTogglePicker` and `FxTray`'s use of it go (P7).
- The Timeline draws one column per Look, sub *Look*. *+ Column* offers no target for a Look, and
  `edits.ts` sends none (`F/lib/stackTimeline/columns.ts:22-29, 96-106`).
- The programmer socket's layer messages and `programmerWsApi.ts` keep `targets` for palettes.

## 5. What is not changing

- Palettes, generic and per-fixture: their targets, family replacement, per-head press and coverage
  rings.
- A Look's content (bound rows, bound effects, scenery), and Record and Update into a Look.
- A layer's other settings (amount, blend, stomp, timing, masters) and its mask (D3).
- A cue's own rows and ad-hoc effects.
- The stack-timeline plan's session 5, which edits effect columns only.

## 6. Implementation: three sessions

Session 1 is backend only and lands alone. Session 2 is backend only and leaves the current client
working, because it strips targets where session 3 refuses them (P2). Session 3 needs session 2.

### Session 1: deferred effects go (backend) — done

- §3.1, step 2, the importer's effect half.
- **Tests:** `validateLookEffects` refuses `deferred`; `create_look` and `update_look` refuse an
  effect with no target and write one with a target; step 2 drops the row and changes nothing when
  run again; a v37 archive with a deferred effect imports without it and logs it; a deferred row
  written straight to the database is dropped at load; Include of a Look runs its bound effects only.
  The tests pinning the deferred arm (`CueComposerTest`, `ProgrammerLayerStackEffectsTest`,
  `McpLookEffectIncludeTest`, `ProgrammerIncludeRouteTest`) move to bound effects.
- **Docs:** the composition model's §"Record / Include / Update" and §"Looks, palettes and layers";
  `mcp-engineering.md` §"Tools"; CLAUDE.md where it names deferred effects.

### Session 2: a Look is whole on the desk (backend)

- §3.2–3.5, step 3, the importer's layer half, P2's strip.
- **Tests:**
  - A press through the programmer, a cue and a busk pad: on puts one untargeted layer on whatever is
    selected, and off takes it off whatever is selected.
  - The selection's mask is ignored, and a mask set afterwards is kept.
  - A solo bank follows P3, in both directions.
  - Rings and surface feedback follow P4, with and without a selection.
  - Record upserts on the source, and a scoped Record follows P5.
  - Step 3 clears a targeted Look layer and changes nothing when run again.
  - A v37 archive's targeted Look layer imports untargeted.
  - A cue PUT with a targeted Look layer is stored untargeted.
  - The cook lands a Look's rows whole.

  The per-target Look cases in `ProgrammerLayerStackTest`, `ProgrammerLayerPressTest`,
  `BuskPressRouteTest`, `CueLayerPressRouteTest`, `CueStackTimelineEditRouteTest`,
  `SurfaceFeedbackPublisherTest` and `HandStateTest` move to palettes or go.
- **Docs:** the composition model's §"A press is per target" (Looks leave it), §"The busk layout"
  and §"Applied state is resolved by the desk"; `midi-control-surface-engineering.md`;
  `cue-stacks-engineering.md` §"`timeline`"; CLAUDE.md's Palette, Busk and Cue Stack endpoint lines.

### Session 3: the client follows (frontend + backend)

- §4, P2's refusal, and `hasDeferredEffects` off the wire.
- **Tests:** every sender sends no targets for a Look; the ring reads a layer, not coverage; the
  Timeline gives one column per Look; each stored boundary refuses `LOOK_LAYER_TARGETED`.
- **Docs:** `frontend/CLAUDE.md`'s sections on Looks, palettes and layers, the busk layout, the hand,
  the MIDI surface view, applying to a selection, the selection's mask and the cue editor; the
  stack-timeline plan's D3 row.

## 7. Verification

At the desk:

- **Session 1.** Experiment's *S5 Deferred FX* loads with no effect, and the log names it.
- **Session 2.** Sleeping Beauty's Pre-show layer names no targets. With House 2 selected, a busk
  pad's press of the Set Look lands it whole and lights the ring; a second press with nothing
  selected takes it off.
- **Session 3.** Pre-show's Timeline shows one Look column, and the apply picker has no *Own
  fixtures* switch.
