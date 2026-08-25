# Backend post-refactor architectural sweep — findings and cleanup plan

> **Document status: BACKLOG, WAVE 0 COMPLETE (A1–A4, A11, C0 all done); WAVE 2 IN PROGRESS
> (D1–D6, D8, D9 done).** This is the output of the
> post-refactor architectural sweep: a categorized backlog for later fix agents, organised into
> execution waves. Items cite file:line as of `b5067e5`; expect drift as waves land. A matching
> frontend sweep happens separately — the "Frontend-coordination register" at the bottom is its
> input. When an item lands, strike it through here with the commit SHA; when a wave completes,
> note it in this header.

## Context

The desk went through four major refactor waves (cue compositing → cook+resolver, the shared
Programmer, palettes/presets → Looks and Templates, speed masters), all landed by 2026-08-23 with
no desk pass yet. This sweep audited the backend for refactor debris, performance regressions,
dead code, unnecessary architecture, and API design consistency — three thorough parallel audits
(composition domain, FX runtime, API surface incl. frontend consumption), findings verified by
spot-checks.

**Decisions already taken by Chris (2026-08-24):**
1. **Retire the `cueEdit.*` family** rather than keep-and-fix.
2. **Retire the legacy tempo surface on both sides** (backend + the two frontend components).
3. **Auth: structural fix only** — route-tree admin gating, and gate the code-execution/filesystem
   endpoints while doing so.
4. **Normalize the API hard** — one convention per axis, no aliases or deprecation windows.

## How to read

Each item: `Severity / Priority / Complexity / Suggested model`.
- **Severity**: critical (data loss / hard failure), high (wrong behaviour or real perf cost),
  medium (latent or quality), low (cosmetic).
- **Priority**: P0 fix first · P1 next · P2 structural · P3 whenever.
- **Complexity**: S (< half a session, mechanical), M (a session, some judgement), L (multi-session
  or design-sensitive).
- **Model**: sonnet = mechanical/well-specified; opus = default; fable = concurrency-sensitive,
  hot-path, or large-scale restructuring.

Findings that duplicate an existing `followups.md` item cite its slug instead of restating it.

---

## A — Correctness bugs

~~**A1. Project delete / replace-import never deletes `templates`**~~ — done, `2fecc13`. critical / P0 / S / sonnet
`routes/projects.kt:215-271` tears down looks, cues, groups, … but has no template loop; the
`templates.project_id` FK (no ON DELETE) blocks `project.delete()`. `ProjectImporter.replaceProject`
(`sync/ProjectImporter.kt:225-249`) has the same omission and re-imports templates onto survivors,
tripping `uniqueIndex(project, name)`. **Fix:** template+template_rows teardown beside the look loop
in both files; test seeded from `RichProjectFixture` (which already seeds two templates).

~~**A2. `presetId` is always null and degrades the Include duplicate-guard**~~ — done, `ab0ff8b`. high / P0 / M / opus
Every one of the 8 callers of `createInstanceFromPreset` passes `presetId = null`, so
`programmerInclude.kt:276-279` dedups on `null == null` — two *different* effects on one
(target, property) are treated as one and the second is never spawned. **Fix:** delete the
parameter, `FxInstance.presetId`, `ProgrammerFxOrigin.presetId` + `Kind.PRESET_APPLICATION`,
`EffectDto.presetId` (wire), and the AI `presetId` key; replace the guard with an
effect-registration-id (+ lookId/layerId) comparison.

~~**A3. `PUT /fx/effect/{id}` reconstructs the type from the display name**~~ — done, `ab0ff8b`. high / P0 / S / opus
`lightFx.kt:132` does `existing.effect.name.replace(" ", "")` — an idiom `programmerInclude.kt:266`
documents as broken for user-defined FX definitions, so a parameter-only update of a user-defined
effect 400s. **Fix:** store the registration id on `FxInstance` at creation; use it here.

~~**A4. Composite effects are single-output; the docs say otherwise**~~ — done, `d3024f6`. high / P0 / M / opus
`FxInstance.compositeTargets` (`FxInstance.kt:292`) is never assigned anywhere, making the whole
secondary-output branch (`FxEngine.kt:2418-2434`) unreachable; `CompositeScriptEffect.outputTypes`
also declares only the primary type (`ScriptEffectAdapter.kt:116`). `LightningStrike`'s COLOUR
output is silently discarded while `docs/fx-engineering.md:199` documents it working. **Fix
(recommended):** delete the dead branch + interface claims and document composite as
primary-output-only; wire it for real only if a need appears.

~~**A5. `suppressionCache`/`suppressionCacheEpoch` two-volatile race**~~ — done, `1239117`. medium / P1 / S / fable
Grew in the landing: the review of the pair-object fix found the writer side racy too
(`putValue` bumps epoch before installing the slot), so suppression now caches on a new
`ProgrammerStore.coverageEpoch` bumped after mutations — which also stops busk-time fader
writes rebuilding the map per tick. The same commit swept the sibling two-volatile tears the
review surfaced (`LayerResolver`, `TypedParams`+`TemplateRegistry`), the lookRepublish
scan-TOCTOU/silent-skip/attempted-vs-replaced reporting, and the cue DELETE route's missing
live-output teardown.

~~**A6. `FxInstance` mutable fields shared across threads without happens-before**~~ — done, `4cee250`. medium / P1 / S / fable
Landed as the second offered fix (immutable snapshots), not the first: a per-field `@Volatile`
draft was reviewed and found to still tear across fields mid-pass and mid-update. The fields —
plus `elementMode`/`elementFilter`, folded back in from C1 — now live in one `FxDynamics` value
behind an `AtomicReference` that `updateEffect`'s swap shares with the replacement instance,
which also closed a pre-existing race the review surfaced: a pause/resume landing mid-swap was
silently undone by the swap's read-copy-publish. `timingSource` proved to be pre-publication-only
and stays a plain `var` with that constraint documented; `lastPhase` stores once per pass. See
`docs/fx-engineering.md` §"Instance dynamics".

~~**A7. `ProgrammerLayerStack.toggle` compares target lists by order**~~ — done, `2169335`. medium / P1 / S / sonnet
Review of the fix found the identical order-sensitive comparison in two sibling sites in
`programmerRecord.kt` (REMOVE's layer match, `appendFxChildren`'s upsert match); both now share
the same `sameTargets` helper.

~~**A8. Silent catches in the cue-apply spawn paths**~~ — done, `1414911`. medium / P1 / S / sonnet
Six `catch (_: Exception) { null }` sites (`CueStackManager.kt:226,265`,
`CueTriggerManager.kt:347,395`, `ProgrammerLayerStack.kt:574`, `programmerInclude.kt:290`) made a
fixture-resolution failure silently drop an effect; `CueStackManager.kt:779` empty-caught
`advanceStack`. All now log at warn with cue/layer/target context, matching `CueComposer`'s
existing pattern for its equivalent skips. `CueStackManager.kt` had no logger — added one.

~~**A9. Timed-trigger edge conditions**~~ — done, `951fda1`. low / P2 / S / sonnet
`CueTriggerManager.kt:95` schedules amount-0 timed layers (cook filters them; the trigger doesn't);
`deactivateTriggersForCue` (`:232-252`) doesn't clear the stomp suppression a timed fire wrote —
an invariant currently held by luck. **Fix:** add the `amount > 0` filter; clear suppression on
deactivate.

~~**A10. `TemplateRegistry.version++` is not atomic**~~ — done, `1239117` (landed inside A5's
sweep, not its own commit). low / P2 / S / sonnet

~~**A11. Every position effect silently produces nothing when applied to `pan`/`tilt`**~~ — done, `d0d0cc5`. high / P0 / S / opus
All seven `src/main/resources/fx/position/*.fx.kts` declare `outputType: POSITION` with
`compatibleProperties: [pan, tilt]`, but `PositionTarget`'s property name is the synthetic
`"position"` (`FxTarget.kt:573`), which is also the only name the two target resolvers map to it
(`lightFx.kt:463`, `projectCuesHelpers.kt:1210`). `pan` and `tilt` are `@FixtureProperty`
`DmxSlider`s, so they're real descriptors alongside `"position"` (`DmxFixture.kt:150,308`) and both
frontend surfaces pick the *first* compatible property present — `AddEditFxSheet.tsx:223`,
`useBuskingState.ts:351` — so they post `propertyName: "pan"`. That resolves to a `SliderTarget`,
and `SliderTarget.applyValueToFixture` drops a `FxOutput.Position` (`FxTarget.kt:213`): no light,
no error, no log. Same failure mode as the A4 tail (a `compatibleProperties` entry whose type the
effect can't produce), but on the main FX-add path and for every position effect. Nothing in the
suite applies a position effect through either resolver. **Fix (pick one):** narrow the seven
resources to `compatibleProperties: [position]`, or have the resolvers map `pan`/`tilt` to
`PositionTarget` when the effect outputs POSITION. Neither needs a frontend change. **Guard:**
one test asserting every built-in registration's `compatibleProperties` resolve to a target
accepting its `outputType`, under the same mapping the add path uses — it goes red today, which
is the point. Found while landing A4; not desk-verified (static read of both repos).

*(The `CueEditSession` bugs — moveInDark lost on discard and upsert, fresh layer uuids causing sync
tombstone churn, ad-hoc effects missing `cueStackId`, silent layer drops — intentionally had no
items here, because D1 was going to delete the file. It has.)*

## B — Functional gaps

**B1. Template/Look-row `fadeDurationMs` ignored when a layer tracks it** — medium / P1 / M / opus
Honoured on click-apply (`projectTemplates.kt:300`) but `CueComposer.LayerContent.OfTemplate`
(`CueComposer.kt:428-434`) and `SourceRow` (`:437-443`) drop it. **Fix:** carry the field through
the cook so tracked layers fade like applied ones.

~~**B2. Scripts cannot address speed masters**~~ — done, `84885df`, pulled forward and landed with D7 (same 24 signatures). high / P1 / M / opus

**B3. `elementMode` settable on update but not add** — medium / P1 / S / sonnet
`AddEffectRequest` (`lightFx.kt:251-262`) lacks the field `UpdateEffectRequest` has; a FLAT group
effect needs add-then-PUT. **Fix:** add the field to add.

**B4. `FxInstanceState` reports both master fields regardless of `timingSource`** — low / P2 / S / sonnet
`FxEngine.kt:1574-1581`: BEAT effects read only `speedMasterSlot`, WALL_CLOCK only
`rateMasterSlot`, but state reports both, so the FX sheet shows a live chip for a field the effect
cannot read. **Fix:** report only the consumed field.

**B5. Scripts and FX definitions mutate with no WS broadcast** — medium / P1 / S / sonnet
`projectScripts.kt` and `fxDefinitions.kt` CRUD fire nothing while every sibling resource fires a
`*ListChanged`; a second client's script list and effect library go stale forever. **Fix:** add
`scriptListChanged` / `fxDefinitionListChanged` through `BroadcastSocket`.

**B6. Deferred Look *rows* are half-retired** — medium / P2 / M / opus
Write-boundary rejects them (`projectLooks.kt:719`) but `LookRegistry.loadLookSnapshot`,
`LookRowEntry.isDeferred`, `LookRowDto.isDeferred`, `DaoLookRow.isDeferred` and the
`rowTarget == null` arm of `CueComposer.applyLayer` (`CueComposer.kt:502-513`) still support them.
**Fix:** drop the row-side `isDeferred` plumbing; only Look *effects* and template rows defer.
(Related, already tracked: `FU-LOOK-ELEMENT-ROWS` for `elementKey` composing nowhere.)

**B7. Layer source invariant enforced three ways with three behaviours** — medium / P2 / M / opus
`DaoCueLayers.look`/`template` exactly-one invariant: read-time `check{}` throws
(`models/cues.kt:419`), `resolveCueLayerSource` silently drops (`projectCuesHelpers.kt:501`),
import raises `ImportError` (`ProjectImporter.kt:709`). **Fix:** a DB CHECK constraint (or
discriminator column) and one shared resolution behaviour with a warn.

## C — Performance (hot paths)

~~**C0. Extend the FX benchmark before touching the tick path**~~ — done, `b7939e5`. n/a / P0 / M / opus
`FxEngineBenchmark.kt` covers only single-fixture `SliderTarget`s — none of the paths C1–C6 live
on. **Fix:** add a group colour chase across multi-element fixtures on two masters, and a crossfade
scenario. Prerequisite for every C item; cross-ref `FU-TEST-FX-BENCH-CI-GATE`.

~~**C1. Per-tick target re-expansion and register locking**~~ — done, `49f3b09`. high / P1 / L / fable
`resolveEffectFixtureKeys` (`FxEngine.kt:2907-2928`) re-expands every group/multi-element fixture
per effect per pass, taking `registerLock.read` per member; `processGroupFlatElementEffect`
rebuilds the whole flat element list per tick (`:2755-2769` + wall-clock twin). **Fix:** resolve
key lists and flat-element lists once at `addEffect`, invalidate on
`fixturesChanged`/`patchListChanged`.

Landed as a pull-based cache rather than the listener the item assumed: `Fixtures.structureVersion`
bumped inside the register write lock, stamped into an immutable `FxTargetExpansion` on the
`FxInstance` and re-checked per read. Resolved once per *register generation*, not at `addEffect`
— `updateEffect` and a repatch both had to move it, and a version re-check covers both without a
mutation site to forget. Six derivation sites collapsed onto it (the item named two);
`processGroupFlatElementEffect` and its wall twin turned out to be byte-identical to the
multi-element walkers once the lists arrived pre-resolved, so both were deleted in favour of
`processElementKeys` / `processWallClockElementKeys`. Numbers, and why `[chase-beat]` moved only
~6 %, in `docs/testing-engineering.md` §"Recorded baselines".

**C2. Reflective property access on the DMX write path** — high / P1 / L / fable
`FxTarget.kt:465-521`: `fixtureProperties.find{}` linear scan + `KProperty1.call` per fixture per
tick (a colour effect pays ~6/fixture/tick); `getSlider`/`getSetting` re-resolve per call. **Fix:**
memoise per-(fixture-class, property) accessors the way `elementCatalogues` already does.

Landed as `503b50d`, but **almost none of the win came from what this entry describes.** The cost was one layer
down: `Fixture` ran `this::class.memberProperties` and `this::class.annotations` in *instance*
initializers, and `FixturesWithTransaction` binds a fixture to a tick by constructing a new
instance — so every touched fixture re-ran a full reflection scan 50×/s. `wrappedFixtureCache`
deduped within a tick, which is why this never looked like a per-tick cost when reading the code.
Hoisting it to a per-class `fixture/FixturePropertyCatalogue.kt` cut `[beat]`'s per-tick allocation
by 68 % and its p50 by 69 %. Six copies of the same `memberProperties` + `@FixtureProperty` scan
collapsed onto it (`Fixture`, both `DmxFixture` duplicates, `PropertyChannelWriter`'s
`elementCatalogues`, `projectCuesHelpers`, `GlobalScalerState`).

Three corrections this item's premises needed:
- The `find{}` scans **were** replaced (five sites, now O(1)), and doing so **changed nothing
  measurable** — a linear scan over nine properties was never the cost. Kept for the lookup
  shape, not for a number. `KProperty1.call` → `.get` was consequently not attempted.
- `getSlider` does **no** reflection for `dimmer`/`uv`/`white`/`amber` — those are `as?` interface
  checks (`FxTarget.kt:320-331`). Only its `else` branch resolves, and no benchmark reaches it.
- `[chase-beat]` could never have measured this: `RgbwPixel` is a `FixtureElement`, which does not
  extend `Fixture`, so `ColourTarget`'s `if (fixture is Fixture)` gate excludes it. The benchmark
  KDoc asserting otherwise was wrong and is fixed. Scenario 4 (`[colour-beat]`) was added to cover
  the bundled-W/A/UV path; `bundledChannelParked` remains unmeasured (it needs a `ParkManager`,
  which needs a `Database`).

Numbers and the null result in `docs/testing-engineering.md` §"Recorded baselines". One behaviour
gap found and *not* fixed here — elements declaring `bundleWithColour` never receive the component
— is filed in `docs/plans/followups.md`.

**C3. Crossfade republish re-runs the full resolver at ~62 fps under a lock** — high / P1 / M / fable
*(Premise partly spent: C1 cached the per-frame `resolveEffectFixtureKeys` walk this item cites.
Re-measure `[crossfade]` before implementing.)*
`CROSSFADE_TICK_MS = 16` → `updateCueFadeWeights` → `republishCueAssignments` (`FxEngine.kt:1405`):
per frame it copies every row, re-runs `CueAssignmentResolver.resolve` + winners + index, and walks
all active effects × `resolveEffectFixtureKeys` — inside `synchronized(cueAssignmentsLock)` that
programmer writes also contend for. The resolver's own doc still claims it runs "on cue apply
(rare), not per tick" (`CueAssignmentResolver.kt:169`). **Fix:** precompute effect coverage per
crossfade; hoist the winner-set resolve out of the per-frame path (only weights change); drop the
`cueLayerState` duplicate map built per publish (`LayerResolver.kt:39`).

**C4. Template edits can open a JDBC transaction on the 50 Hz tick loop** — high / P1 / M / opus
`templateListChanged → registry.invalidateAll()` clears the cache and bumps the *global* version;
`TypedParams.invalidateColourCacheIfStale` (`TypedParams.kt:132-139`) then misses and
`loadTemplateSnapshot` opens a transaction from the tick — the exact thing
`TemplateColourSource.kt:33` forbids. The global version also invalidates *every* effect's colour
cache on any single-template edit. **Fix:** re-warm after `invalidateAll` (the single-template path
at `lookRepublish.kt:83` already does), and version per template uuid.

**C5. Timed-layer fires re-cook the whole cue and hit the DB per fire** — medium / P1 / M / opus
`CueTriggerManager.kt:129-141`: each fire = `buildCueAssignmentsForCue` + full `CueComposer.cook`
over all layers, at up to 10/s per timed layer. Plus N+1 FK dereferences building `CueApplyData`
(`projectCuesHelpers.kt:339`, `models/cues.kt:419-430`) and one-transaction-per-cue in
`republishForLookEdit` (`lookRepublish.kt:116-123`). **Fix:** cache the static half of the cook and
overlay fired layers; batch the DB reads. (The two-builder split feeding this is
`FU-CUE-APPLYDATA-ONE-BUILDER`, already Ready — do it as part of this item.)

**C6. Per-tick allocation bundle** — medium / P1 / M / opus
`DistributionStrategy.RANDOM` allocates a `Random` + full Fisher-Yates per member per call
(`DistributionStrategy.kt:143-155`, O(N²)/tick); `calculateOffset` computed twice per member
(`FxInstance.kt:396` + call sites); anonymous `DistributionMemberInfo` + `EffectContext` per
element per tick (`FxEngine.kt:2635` et al.); `resetActiveProperties` scratch maps per tick
(`:2463`); `rateScales()` DoubleArray 50/s unconditionally (`SpeedMasterBank.kt:234`). **Fix:**
cache the permutation, return offset with phase, reuse scratch structures, skip rateScales when
unused.

**C7. `emitStateUpdate` makes cue apply O(N²)** — medium / P1 / M / opus
`FxEngine.kt:3009-3045` maps every active effect (with group lookups) once per `addEffect`;
`syncEffects` and `applyCue` call `addEffect` in loops. **Fix:** a batched `addEffects` emitting
once.

**C8. Cook-internal repeated work** — medium / P2 / M / opus
Programmer recook resolves the stack and builds cookLayers twice (`ProgrammerLayerStack.kt:445-450`
vs `:508`); group expansion + allowed-set allocation per row (`CueComposer.kt:515-516`); template
intent parsed per head not per row (`:536`); `TemplateResolver` walks the property catalogue twice
per head (`TemplateResolver.kt:137,147`); `coversTarget` re-expands per bound effect (`:660-678`);
`EffectKey` hashes whole parameter maps and forces respawns on unrelated param edits
(`ProgrammerLayerStack.kt:152`); `TypedParams` blank-case non-local return defeats the cache
(`TypedParams.kt:93,110`). **Fix:** one cook producing rows+effects; hoist/memoise per cook.

**C9. `MasterClock.tickFlow` emits to nobody** — low / P2 / S / sonnet
`MasterClock.kt:202`: production is driven by `onTick`/wake channel; only tests collect the flow.
**Fix:** delete or gate on subscriber count; fix the `docs/fx-engineering.md:31` diagram.

**C10. `FxScriptCompiler` cache grows unboundedly** — medium / P2 / S / sonnet
`FxScriptCompiler.kt:30,51`: one entry (with classloader) per compiled content hash, never evicted.
**Fix:** LRU bound.

## D — Dead code and retirements

~~**D1. Retire the `cueEdit.*` family**~~ — done, `26cc782`. high / P1 / L / opus
No client can start a session (frontend removed its arm in 2b; `beginEdit` over WS is the only
entry), so the MIDI cue-edit fader branch (`midi/SurfaceActions.kt:148-167`) is transitively dead
too. Remove: `plugins/CueEditSession.kt` (827 lines), `CueEditSessionRegistry.kt`,
`CueEditLatencyTracker`, `GET /perf/cueedit-histogram`, the three `409 CUE_EDIT_SESSION_OPEN`
guards, the `Sockets.kt` teardown hook, and the SurfaceActions branch. Update `followups.md`:
`FU-TEST-MULTI-CONN-CUEEDIT` becomes moot (one-line Completed/retired row). Frontend keeps a 409
handler — flag for the frontend sweep. All A-series cueEdit bugs die here.

~~**D2. Retire the legacy tempo surface, both sides**~~ — done, `db937f6` (+ lighting-react `179280e`). high / P1 / M / opus
Backend: `setFxBpm`/`tapTempo`/`beatSync`/`requestBeatSync` (`FxSocket.kt`), REST
`GET/POST /fx/clock/status|bpm|tap` (`lightFx.kt:27-46`), and `fxState.bpm` if nothing else reads
it. Frontend (coordinate or do inline — two components): `BeatIndicator.tsx`,
`EffectsOverviewPanel.tsx`, `store/fx.ts` → `speedMasters.*` (master 1 = null uuid already works).
Update CLAUDE.md's endpoint list and `docs/fx-engineering.md`.

~~**D3. `GroupSocket` is dead in both directions**~~ — done, `de2e1d5`. medium / P1 / S / sonnet
No `setupGroupSubscriptions` exists, so `groupsState` is never pushed nor requested; `addGroupFx`
is a deliberate no-op; frontend `groupsApi.addFx/clearFx` have zero call sites and it branches on a
`groupFxAdded` message the backend never emits. **Fix:** delete `plugins/GroupSocket.kt` and flag
the client stubs for the frontend sweep.

~~**D4. Look preview routes: dead surface that writes live programmer state**~~ — done, `17c5dac`. medium / P1 / S / sonnet
`POST/DELETE /project/{id}/looks/preview` (`projectLooks.kt:399,415`) drove the deleted
`LookLivePreview`; frontend confirms no caller. It still mutates the shared
`ProgrammerLayerStack.installPreview`. **Fix:** delete routes + `installPreview` (and check
`FU-PROG-FOCUS-PREVIEW-LAYER` — Ready — doesn't want this hook before deleting).

~~**D5. Dead endpoint/message singles**~~ — done, `98ec1a2` (+ lighting-react `c6ee984`). medium / P1 / M / sonnet
Delete (or consciously keep, one line each in the commit): `POST /fx/clear`,
`DELETE /fx/fixture/{key}`, `POST /cues/{cueId}/flatten` (+ the frontend field kept for it),
`GET /project/{id}/machine-overrides`, WS `unparkAll`, `ping` (Ktor pingPeriod already keeps
alive), the two no-op `addFx`/`addGroupFx` messages, and the REST/WS twin dedup:
`POST /programmer/clear-all` vs `programmer.clearAll` (keep the WS one the client uses; align the
reply field names), `programmer.addLayer` vs `POST /looks/{id}/toggle` (keep one).

`programmer.addLayer` vs `POST /looks/{id}/toggle` turned out not to be a twin — false positive,
no action taken; both are live and serve distinct frontend features.

~~**D6. fx-internal dead code sweep**~~ — done, `8bf4b0b`. medium / P1 / M / sonnet
`FxEngine.appendCueAssignments`/`removeCueAssignmentSubset`/`replaceCueAssignmentSubset`/
`mutateCueAssignments` + their tests (retired append model);
`CueTriggerManager.launchTimedActionWithState` (folded into `launchTimedAction`);
`CueStackManager.StackData` (constructed, never read); `FxTarget.getCurrentValueFromFixture` (4
overrides, no callers); `FxEngine.getEffectsForTarget`; `SpeedMasterBank.Frame.rateScale`;
`MasterClock.beatDurationMs`/`divisionDurationMs`; `DistributionStrategy.CUSTOM` (unreachable from
any authoring surface); `plugins/Routing.kt` (empty stub); unused imports
(`CueAssignmentResolver.kt:7`, `models/cueStacks.kt`). **Not removed** — re-verified as still
live, contra the original item: `LookRegistry`'s flattening layer
(`ExpandedLook.byFixture`/`expand`/`literalFor`) and `EffectContext.basePhase`. **Note:** deleting
sealed subclasses needs `--rerun-tasks` on the next build (a green build can still fail every
serialization test otherwise).

~~**D7. Kotlin effect classes in `fx/effects/`: delete and replace the vocabulary**~~ — done, pulled forward and landed with B2, `84885df`. The DB audit the original item was gated on came back clean. low / P3 / S / opus

~~**D8. Palette-era vestiges in `ProgrammerStore`**~~ — done, `8aa5507`. low / P2 / S / sonnet
`IncludedTarget.Kind.PALETTE` (never constructed), `paletteId`, `clearIncludeTargetForPalette`, and
`paletteUuid` holding a Look uuid. **Fix:** delete the arm, rename `paletteUuid` → `sourceUuid`.

~~**D9. Permanently-empty wire fields**~~ — done, `10c51ae`. low / P2 / S / sonnet
`LookRepublishOutcome.programmerKeysUncovered` (always 0, self-documented) removed — turned out to
be dead in every sense: never actually on the wire despite its own doc comment's claim, and no test
asserted on it either. ~~`EffectDto.presetId`~~ (gone with A2, along with `GroupEffectDto.presetId`).

## E — Architecture

**E1. Split `FxEngine` (3,046 lines, ~7 responsibilities)** — high / P2 / L / fable
Tick loops, Layer-4 cue-assignment bookkeeping, ~300 lines of programmer write delegation,
provenance computation, cascade publishing, effect registry/CRUD, DTO emission. **Fix:** extract at
the seams — `CueAssignmentLayer`, `ProgrammerWriter`, `ProvenanceService`, `CascadePublisher` —
leaving the engine as tick loops + effect set. Do **after** the D-wave deletions and C-wave fixes
so the split moves less code.

**E2. Layering inversions** — medium / P2 / M / opus
fx→routes: `ProgrammerLayerStack`, `CueTriggerManager`, `CueStackManager` import
`createInstanceFromPreset`/`resolveTargetForCue` from `routes/projectCuesHelpers.kt` — move them
into `fx/` as an `EffectSpawner`. models→fx: `models/{cues,looks,templates}.kt` import
`LayerSource`/`LayerSourceDto`/`AssignmentHealth` from `fx/` — move those types into `models/`.

**E3. Split `CueStackManager` (827 lines, four jobs)** — medium / P2 / M / opus
DB reads, effect spawning, Layer-4 publishing, crossfade animation, auto-advance timers, standby
arming, run-state broadcast; `activateCueInStack` alone is 267 lines. **Fix:** extract the
crossfade driver and the standby/run-state half.

**E4. One param-coercion layer** — medium / P2 / M / opus
`BlendMode`/`DistributionStrategy`/`ElementMode`/`ElementFilter` parsing appears in
`projectCuesHelpers.kt` (silent default), `lightFx.kt` (400), `lightGroups.kt` (500) — same bad
string, three outcomes. `TypedParams.ubyte` also reimplements `EffectParamUtils.toUByteParam`.
**Fix:** one `EffectSpecCoercion` object used by all four call sites; collapse the two util layers.

**E5. Single source for layer ranking and cook priorities** — medium / P2 / S / opus
The contributing-layer predicate is implemented three times (`CueComposer.cook:242`,
`cookEffects:374`, `ProgrammerLayerStack.syncEffects:514` — the last missing the timed clause) and
`priorityFor(rank)` re-derives what `CookWinner.index` carries, with agreement documented but
unenforced. Also `CueComposer.cook`'s four-parameters-for-two-dependencies resolver signature
(`CueComposer.kt:212-234`). **Fix:** one `contributingLayers()` helper; pass resolvers only.

**E6. Programmer-band effect lifecycle has two owners** — medium / P2 / M / fable
`ProgrammerLayerStack` keeps its own `effectInstances` map + lock and reconciles against
`engine.getActiveEffects()` per recook, while `FxEngine.removeProgrammerBandEffects` can delete
behind its back. **Fix:** put the (layerId, effectEntry, targetKey) identity on `FxInstance`, let
the engine own the band.

**E7. Beat/wall-clock processing twins** — medium / P2 / M / opus
`processFixture/MultiElement/Group/GroupFlatElement` effects exist twice (~190 duplicated lines,
`FxEngine.kt:2571-2798` vs `:2212-2398`) differing only in phase function — every C-fix lands
twice until then. **Fix:** parameterise on a phase supplier. (Distinct from
`FU-PERF-FRAME-TXN-UNIFY`, which is about unifying the *loops* — leave that trigger-gated.)

**E8. Tick-path error handling** — medium / P1 / S / opus
`System.err.println` with no stack trace at 120/s for a misbehaving script effect
(`FxEngine.kt:2101,2202` + 6 more), one fully-empty catch (`resetOne`, `:2498`). **Fix:** SLF4J,
rate-limited, auto-pause an effect that throws N consecutive ticks. Also: move the `runBlocking`
test shims (`processBeatTick`/`processWallClockTick`) to test code; note `fxEngine.start(GlobalScope)`
(`Show.kt:238`) as accepted-for-now with a comment.

**E9. Small structural items** — low / P3 / S / sonnet
`FixturesChangeListener` 15-method interface → default methods (six implementers stub ~12 no-ops
each); extract `TapTempo` from `MasterClock`; name the magic-number relationships
(`PROGRAMMER_FX_PRIORITY_BASE` band vs the 100k rank clamp; `MAX_CATCHUP_MS` vs 300 BPM;
`DEFAULT_BPM` doubling as the rate-scale reference).

## F — API consistency *(decision: normalize hard, no aliases)*

**F1. Write the conventions down, then apply them** — medium / P2 / M / opus
One short `docs/api-conventions.md`: kebab-case paths; plural collections with GET-on-collection;
one spelling for vocabulary enumerations; `?force=true` as the guard-override convention;
"unbounded lists are fine at desk scale" stated explicitly. Then fix the deviants:
`/controlSurfaceTypes`, `/stageRegions`, `/surfaceBindings`,
`GET /project/list|current` and `GET /fixture/list|types` → plural resources.
(`/cueedit-histogram` was on this list; D1 deleted it. Note `?force=true` lost two of its three
users with D1 as well — the surviving guard-override convention is worth restating on whatever is
left rather than on what this item was written against.)

**F2. One scoping rule for `{projectId}`** — medium / P2 / M / opus
102 handlers 409 unless the id is current, while equally project-dependent surfaces (`/groups`,
`/fx`, `/programmer`, `/locate`, `/ai/conversations`) are global. **Recommended rule:** persisted
project *data* is project-scoped and 409-guarded; live-runtime surfaces are global by design.
Write it in the conventions doc; move the misfits (`/ai/conversations` is project-dependent data —
scope it).

**F3. Normalize mutation responses** — low / P2 / S / sonnet
Deletes: 204 everywhere (currently split 204 vs 200-empty by resource); updates: return the DTO
everywhere (universe-configs returns 204); `show/advance` vs `show/deactivate` reply asymmetry.

**F4. POST-for-read** — low / P3 / S / sonnet
`cue-stacks/{id}/preview` and `templates/resolve` → GET with query params. Compile-checks stay
POST (they carry source bodies) — note that in the conventions doc.

**F5. WS naming + snapshot rules** — medium / P2 / M / opus
Dotted namespaces are the modern scheme; fix the two mixed-scheme stragglers
(`speedMasterListChanged` → `speedMasters.listChanged`, `surfaceBindingsChanged` →
`surfaceBank.bindingsChanged`). One snapshot rule: every stateful family pushes on connect,
request-messages remain as explicit resync — and fix the double `speedMasters.state` frame (server
pushes on connect *and* client requests on open). Document the three reply conventions and pick
one for future ops.

**F6. Auth gating: route-tree composition** *(decision taken)* — high / P1 / M / opus
Replace `ADMIN_ONLY_PREFIXES` string matching (`auth/AuthGate.kt:134`) with an `adminOnly {}`
route-tree wrapper; while there, admin-gate the code-execution/filesystem endpoints
(`scripts/run`, `fx/definitions/{id}/test`, `/script-editor` compile, `project/import|export`
client-supplied paths, AI `run_lighting_script`). The frontend mirrors the prefix list by hand
(`restApi.ts:15`) — flag for the frontend sweep. Cross-ref `FU-AUTH-WS-PER-MESSAGE`,
`FU-AUTH-OPERATOR-LOCKDOWN` (this narrows both). Also `/script-editor` mounts outside the
warm-up gate and the `/api` namespace (`router.kt:66`, `scriptEditor.kt:50`) — bring it inside.

**F7. Error envelope hygiene** — low / P2 / S / sonnet
Move `ErrorResponse` out of `lightFx.kt:287` into a neutral `routes/ApiTypes.kt`; make `code`
present wherever the UI branches (today: 5 codes, ~278 prose-only sites — add codes opportunistically,
not exhaustively); drop the retired `fx_presets` arm in `ErrorHandling.kt:145`.

**F8. DTO unification across transports** — medium / P2 / M / opus
`GroupSummary` (WS) vs `GroupSummaryDto` (REST), each with its own capability detection;
`FxEffectState` (WS, 12 fields) vs `EffectDto` (REST, 20 fields); `FxEffectState` built twice
differently in one file (`FxSocket.kt:163` vs `:228`, already diverged once). **Fix:** one DTO per
concept, one builder; client's missing `rateSpeedMasterIndex` goes to the frontend sweep.

## G — AI surface refresh

**G1. Speed-master awareness** — high / P1 / M / opus
`set_bpm` is master-1-only; no list/create/retune tools; the system prompt never mentions masters.
And the schemas can't express fields the handlers already read: `AiTools.kt:113-115` reads
`speedMasterUuid`/`rateSpeedMasterUuid` off effects but `lookEffectSchema`, `adHocEffectSchema`,
and `cueLayerSchema` (`AiToolSchemas.kt`) declare neither. **Fix:** add the schema fields; add a
speed-masters tool (or extend `set_bpm` with a master ref).

**G2. `get_current_state` misses the new subsystems** — medium / P1 / M / opus
No `speed_masters`, no programmer (which the AI itself mutates via `apply_look`), no cue run state
(active/armed-next/fade — the point of server-owned Next). **Fix:** add the three sections.

**G3. Missing tools for the new authoring loop** — medium / P2 / M / opus
No template create (successor to the removed `set_palette`), no Record/Include/Update, no
standby/GO. **Fix:** add per product judgement — standby/GO first (smallest, highest show value).

**G4. Retired vocabulary** — low / P3 / S / sonnet
`parsePresetEffect` name, prompt text "the preset", ~~the always-absent `presetId` key~~ (gone with
A2), tombstone naming `get_show_state` for `get_current_state`.

## H — Docs and naming (one mechanical pass at the end)

**H1. Rewrite `docs/websocket-engineering.md`** — medium / P2 / M / sonnet
It documents `fxPresetListChanged` (gone), inventories ~12 of ~110 actual message types, and lists
2 of 16 plugin files. Regenerate the inventory from the `@SerialName` declarations.

**H2. KDoc/dangling-reference sweep** — low / P3 / S / sonnet
The dangling refs to deleted types (`MissingPaletteEntry`, `PaletteEntryDto`, `lookName`,
`CookLayer.lookId`, `fallbackFromProgrammer`, `loadPaletteSnapshot`, `ref:{uuid}` mentions); the
two orphaned double-KDoc blocks (`CueComposer.kt:54-92`, `FxEngine.kt:528`); stale "Phase 0/1"
staging language in `models/cues.kt` and `LayerResolver.kt`; refactor tombstone comments in hot
files; `SyncDtos.kt`'s misplaced rows comment (`:164` — actively wrong about the wire format) and
blank-gap/stale prose; `firedTimedLooks` naming; `CueTriggerManager` class docs still describing
presets; `docs/fx-engineering.md` tickFlow diagram and composite claim (per A4/C9).

**H3. preset→look rename pass** — low / P3 / S / sonnet
`createInstanceFromPreset` → `createEffectInstance`, `presetEffectDto`/`toPresetEffectDto`,
`parsePresetEffect`, spawn parameter names. Pure rename, after A2/D-wave so it renames less.

---

## Execution waves

| Wave | Items | Note |
|---|---|---|
| 0 | ~~A1–A4, A11, C0~~ **done** | Data-loss + behavioural bugs, benchmark baseline. Independent, parallelizable. |
| 1 | ~~C1~~ (`49f3b09`), ~~C2~~ (`503b50d`) **done** | The two big hot-path wins, taken against the fresh wave-0 baseline. fable. See the re-sequencing note below. |
| 2 | ~~D1–D6, D8, D9, A5–A10~~ **done**, E8, B3–B5 | Retirements — everything after moves less code. D1 and D2 are done, so cueEdit-adjacent and tempo-surface work is unblocked. **A5/A6 land in the tick path: re-capture the benchmark baseline when this wave completes.** |
| 3 | C3–C7, B1, ~~B2~~ | Remaining hot-path fixes, measured against the *re-captured* baseline, not the wave-0 one. fable for C3. B2 was pulled forward — see below. |
| 4 | E1–E7, C8, B6, B7, F6 | Structure. E1 (FxEngine split) last in the wave, after everything shrank it. |
| 5 | F1–F5, F7, F8, G1–G3 | API normalization — coordinate breaking changes with the frontend sweep (one list of frontend-visible changes maintained as these land). |
| 6 | H1–H3, G4, ~~D7~~, E9, F4 | Mechanical passes. D7 was pulled forward — see below. |

**Re-sequencing note (2026-08-25): B2 + D7 taken together.** They land on the same 24
`effect: Effect` signatures, and neither touches the tick path, so pulling them out of waves 3
and 6 spends neither the wave-0 baseline nor the re-capture wave 3 is waiting on. One thing to
carry forward: that re-capture will be taken with the benchmark's effects living in
`testsupport/TestEffects.kt` — byte-equivalent copies of what the 2026-04-22 baseline used, so
still comparable, but the freeze is now a convention rather than a fact.

**Re-sequencing note (2026-08-24).** C1+C2 were originally behind the retirements, on the
"everything after moves less code" principle. They were pulled forward when C0 landed, for two
reasons:

1. **A5 and A6 sit inside the code the benchmark measures.** A6 puts `isRunning`, `phaseOffset`,
   `distributionStrategy` and friends behind an atomic snapshot read once per pass — `isRunning`
   gates the whole per-effect pass and the rest were read per member per tick; A5 changes how
   `suppressionCache` is published on the same path. Landing them first invalidates the wave-0
   baseline before the item it exists for gets to use it. Running C1+C2 first spends the
   baseline while it is exactly matched to the code.
2. **Nothing C1 or C2 touches is D-wave code.** D1 (cueEdit) and D2 (legacy tempo) don't reach
   group expansion or the colour write path, so the "moves less code" argument doesn't apply to
   these two specifically — it still does for C3–C7, which is why they stay behind the
   retirements.

The cost of the swap is that C1+C2 rewrite code the retirements would not have deleted anyway,
so it is close to free; the benefit is one clean before/after on the two items with the largest
expected win.

Frontend-coordination register (hand to the frontend sweep): D1 (409 handler), ~~D2~~ (done in the
same change — ten files, not two components), D3/D9 (dead stubs, groupFxAdded, presetId types,
rateSpeedMasterIndex), D4 (`POST`/`DELETE /project/{id}/looks/preview` are gone and
`programmer.layerState`'s `isPreview` field no longer exists — the Look editor's live-preview
caller, `ProgrammerLookStack`'s local `isPreview` filter, and the unfiltered-stack special-casing
in `ProgrammerScopeBand`/`LookRowStoreProvider` that `FU-PROG-FOCUS-PREVIEW-LAYER` flagged are all
dead code now, not just unreachable),
F1/F2/F3/F5 (renamed paths/messages/status codes), F6 (hand-copied admin prefix list).

**D7.** `GET /fx/library` now returns each parameter's real `type`, `defaultValue` and
`description` instead of `"string"`, `""`, `""`. Same payload shape, so nothing breaks and
nothing needs changing — but the FX sheet consumes all three, so typed controls and prefilled
defaults should start working on their own. Worth confirming the double-slider range at
`EffectParameterForm.tsx:478` for parameters whose real defaults exceed 1.0.

D1's frontend half, now that the backend is done (nothing here is urgent — the backend simply
never answers 409 `CUE_EDIT_SESSION_OPEN` again, so the handlers are unreachable rather than
broken):

- `CUE_EDIT_SESSION_OPEN` handling in `RecordSheet.tsx`, `UpdateDialog.tsx`,
  `store/programmerOps.ts` (the union type) and `lib/programmerSource.ts` (the comment).
- **The `force` senders — remove these before the backend fields.** `RecordSheet.submit(force)`
  and `UpdateDialog.commit(force)` put `force` in every request body, and the backend's `Json` is
  strict on unknown keys, so the two inert `force` fields have to outlive their senders. Deleting
  them backend-first 400s every Record and Update.
- `routes/Diagnostics.tsx`'s cueEdit latency panel: `GET /perf/cueedit-histogram` is gone, so the
  panel now 404s rather than showing its empty state.
- Stale doc comments naming the family in `EditorContext.tsx`, `CueCardEditor.tsx` and
  `useCellWriters.ts`.

**Landed so far:** A2/A3 (`ab0ff8b`) — `presetId` gone from `EffectDto` and `GroupEffectDto`;
`effectType` on both now reports the registration id. A4 (`d3024f6`) — no DTO shape change, but
`LightningStrike`'s `compatibleProperties` is now `[dimmer]` only, so the library stops offering
it on colour-only fixtures (it produced no light there anyway). C0 (`b7939e5`) — `FxEngineBenchmark`
grew `[chase-beat]`/`[chase-wall]` (group colour chase over multi-element fixtures on two masters)
and `[crossfade]`; the wave-0 baseline every C item measures against is recorded in
`docs/testing-engineering.md` §"Recorded baselines". Test-only, no production change.

C1 (`49f3b09`) — target expansion now resolved once per fixture-register generation into an
`FxTargetExpansion` cached on the `FxInstance`, invalidated by a new `Fixtures.structureVersion`.
No API or DTO change; two behaviour changes worth knowing: an effect naming a deleted
group/fixture logs "not found" once per register generation instead of once per tick, and
`processGroupFlatElementEffect` / `processWallClockGroupFlatElementEffect` are gone (folded into
`processElementKeys` / `processWallClockElementKeys`). Numbers in
`docs/testing-engineering.md` §"Recorded baselines"; they also revise what C2 and C3 have left
to win.

C2 (`503b50d`) — `Fixture`'s per-instance reflection hoisted to a per-class `FixturePropertyCatalogue`, which
six sites now share; the five `fixtureProperties.find{}` scans on the tick indexed. No API or DTO
change. `[beat]` −68 % allocation, `[crossfade]` −22 %, `[chase-*]` unchanged by design. Two
things to carry forward: the benchmark grew a `[colour-beat]` scenario (C0's harness, extended)
and scenario 2's docs were corrected — it never measured C2. C3's re-measurement baseline is now
the C2 block, not the C1 one.

D1 (`26cc782`) — the `cueEdit.*` family is gone: three files deleted
(`CueEditSession.kt`, `CueEditSessionRegistry.kt`, `CueEditLatencyTracker.kt`), fifteen
`@SerialName("cueEdit.*")` socket messages with them, plus `GET /perf/cueedit-histogram`, all four
409 guards and the Include warning, the `Sockets.kt` dispatch arm and teardown hook,
`SocketScope.cueEditSessionRef`, and the two `SurfaceActions` routing branches. Suite 1804 → 1767,
0 failures.

Five things worth carrying forward:

1. **`force` survives on the Record and Update request bodies, inert.** It existed only to bypass
   the 409, but `RecordSheet` and `UpdateDialog` send it on *every* submit, not just the conflict
   retry — and the route's `Json` is Ktor's default, which is strict on unknown keys. Removing the
   field would 400 every Record and Update until the frontend sweep lands. Flatten's `force` was a
   query parameter with no sender, so that one went.
2. **Two things beyond `cueEdit` were left provably dead and handled differently.**
   `PropertyChannelResolver.serializeToAssignmentValue` had no other caller and was deleted (a
   one-line wrapper over `toPropertyValue`). `CueStackManager.resumeAutoAdvance` also lost its only
   caller but was **kept**: `pauseAutoAdvance` is still live via the surface PAUSE binding, and half
   a pause/resume pair is worse than an unused half. Its doc comment now says so. If D6 disagrees,
   that is the place to take it.
3. **`SurfaceFeedbackPublisher` lost a whole index, not just a branch.**
   `Index.continuousByAssignmentKey` existed solely to let `resyncEntriesMatching` skip a per-entry
   walk on the cue-edit hot path — it and `resyncAllDevices`, `value7BitFromAssignment`,
   `assignmentKeyFor`, `buildAssignmentMap` and the `sessionAssignments` cache all died with the
   event handler. `computeValue7Bit` now has one source: the live composed DMX value.
   `FU-PERF-REGISTRY-INDICES` (Completed, `672c139`) built that index; it is now half-retired.
4. **`MidiFloodHarness` was kept and reframed**, against the plan's instinct to delete it. It has no
   compile-time dependency on the family (only doc framing), it is gated by `-Dmidi.flood=true`
   which `build.gradle.kts` does not even forward, and it is still the only load generator for the
   surface write path — which is still per-event work, just landing in
   `FxEngine.writeProgrammerProperty` now. `FU-MANUAL-SUSPEND-PATH` still wants it.
   `FU-PERF-COALESCE-WRITES`, its other consumer, was already cancelled.
5. **`HttpRoundTripTest` was rewritten, not deleted.** Its whole round-trip ran through a cueEdit
   session, but the test exists to close `FU-TEST-HTTP-ROUNDTRIP` — HTTP → WS → HTTP — so the WS leg
   moved to `programmer.set`, which is what the frontend has done since 2b anyway. Its
   source-cue assertions went (nothing writes a cue over the socket any more).

Docs revised: `lighting-composition-model.md` (the cueEdit guard, the Layer-4 fader-routing note,
the whole "Cue edit sessions" section, and the "two cue-authoring paths" divergence — now one),
`midi-control-surface-engineering.md` §Phase 6, `testing-engineering.md` (the third opt-in harness,
with a warning about the shared `if` block), and `CLAUDE.md`'s follow-up gate list.
`FU-MANUAL-CUEEDIT-HARDWARE` in `manual-validation.md` was retired unrun — the plan named only the
`followups.md` item.

**Not done:** the plan's D-wave verification asks for a boot-and-connect-the-real-frontend smoke
pass (`./gradlew run` + the frontend dev server). That needs the operator; `./gradlew test`
(`--rerun-tasks`, for the deleted sealed subclasses) is green. The frontend still has its 409
handler and `force` senders — frontend sweep, register below.

D3 (`de2e1d5`) — `plugins/GroupSocket.kt` deleted outright: no `setupGroupSubscriptions` existed
anywhere in `Sockets.kt`'s per-connection setup list, so `groupsState` was never pushed on connect
like every other domain, and `AddGroupFxInMessage` was a literal no-op. Confirmed frontend-side
before deleting: `groupsApi.addFx`/`clearFx` (the WS senders) have zero call sites — `store/groups.ts`
uses the REST `groupsApi` for everything, including its own `clearGroupFx` mutation
(`DELETE /groups/{name}/fx`) — and `groupFxAdded` is branched on in `handleOnMessage` but the
backend never emitted it. Also removed: the `GroupInMessage` dispatch arm in `Sockets.kt` and the
two now-dead wire-format tests. No sealed-subclass rerun needed — `InMessage`/`OutMessage` are plain
open sealed classes with no separate registration list. Suite 1764, 0 failures. Frontend stubs
(`groupsApi.addFx`/`clearFx`, the `groupFxAdded` branch, `GroupsInMessage`) left for the frontend
sweep, per the register below.

## Verification

- `./gradlew test` per wave (the suite pins `lighting7.dataDir` — if `FileSystemException` or a
  route-DTO `MissingFieldException` appears, that pin is lost, not a real failure). After any
  sealed-subclass deletion, build once with `--rerun-tasks`.
- A1: new test — delete + replace-import a `RichProjectFixture` project holding templates; extend
  `ProjectRoundTripTest` where DTO fields change (canonical JSON omits defaults — set non-default
  fixture values).
- C-wave: `FxEngineBenchmark` before/after per item, on the C0-extended scenarios. The "before"
  for C1/C2 is the wave-0 block in `docs/testing-engineering.md` §"Recorded baselines"; C3–C7
  measure against the baseline re-captured after wave 2 (A5/A6 move the tick path). Run each
  comparison on one machine in one sitting — `[beat]` p99 varied 714–1354 µs across two runs of
  identical code, so cross-session comparisons at that resolution mean nothing.
- D-wave: grep for each retired `@SerialName`/route string in both repos afterwards; boot the app
  and connect the real frontend (`./gradlew run`, frontend dev server) for a smoke pass.
- API waves: the OpenAPI/Swagger surface at `/openapi` is the quick diff of what changed.
- Anything operator-on-the-rig (crossfade smoothness after C3, template edit under running effects
  after C4) goes to `docs/plans/manual-validation.md` rows, not this plan.
