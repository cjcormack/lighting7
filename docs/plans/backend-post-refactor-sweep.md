# Backend post-refactor architectural sweep — findings and cleanup plan

> **Document status: BACKLOG, WAVES 0–5 COMPLETE.** This is the output of the
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

~~**B1. Template/Look-row `fadeDurationMs` ignored when a layer tracks it**~~ — done, `bd659fd`. medium / P1 / M / opus
Honoured on click-apply (`projectTemplates.kt:300`) but `CueComposer.LayerContent.OfTemplate`
(`CueComposer.kt:428-434`) and `SourceRow` (`:437-443`) drop it. **Fix:** carry the field through
the cook so tracked layers fade like applied ones.

Grew in the landing: three additions, all confirmed with the operator. The cue's own local rows had
the identical dead field (`buildCueAssignmentsForCue` dropped it), so they are included rather than
left as a follow-up. `CueTriggerManager`'s timed-layer fire counts as an arrival and gets the flag,
which meant `replaceCueAssignments` needed it too. And the review pass found the per-key fade map
could not come from the LTP-shaped winner map alone — `composeHtp` ignores priority, so a *blended*
HTP bucket has no single source row and snaps, while a single-contributor one (the common case, since
DIMMER is HTP) still fades. Which publishes may ramp at all is now an arrival-versus-edit decision at
the call sites; `docs/lighting-composition-model.md` §"Per-row fade time" has the table.

~~**B2. Scripts cannot address speed masters**~~ — done, `84885df`, pulled forward and landed with D7 (same 24 signatures). high / P1 / M / opus

~~**B3. `elementMode` settable on update but not add**~~ — done, `39f4d7c`. medium / P1 / S / sonnet
`AddEffectRequest` (`lightFx.kt:251-262`) lacks the field `UpdateEffectRequest` has; a FLAT group
effect needs add-then-PUT. **Fix:** add the field to add.

~~**B4. `FxInstanceState` reports both master fields regardless of `timingSource`**~~ — done,
`54fdc8a`. low / P2 / S / sonnet
`FxEngine.kt:1574-1581`: BEAT effects read only `speedMasterSlot`, WALL_CLOCK only
`rateMasterSlot`, but state reports both, so the FX sheet shows a live chip for a field the effect
cannot read. **Fix:** report only the consumed field.

Grew in the landing: the review pass found `FxSocket.kt`'s `buildFxStateMessage` (the WS
reconnect/request answer, sharing a documented "can't disagree" invariant with the push stream)
had the identical defect and was fixed alongside it. Independently, `lightFx.kt`'s REST-facing
`toDto()`/`toIndirectDto()` had the same defect too — not named by the item text or the review,
confirmed with the user before including it here rather than filing a follow-up.

~~**B5. Scripts and FX definitions mutate with no WS broadcast**~~ — done, `47dcb83`. medium / P1 / S / sonnet
`projectScripts.kt` and `fxDefinitions.kt` CRUD fire nothing while every sibling resource fires a
`*ListChanged`; a second client's script list and effect library go stale forever. **Fix:** add
`scriptListChanged` / `fxDefinitionListChanged` through `BroadcastSocket`.

Grew in the landing: the review pass flagged that `FixturesChangeListener` has no default no-op
bodies, so the two new methods forced empty override stubs at five other implementers. Confirmed
with the user, who took the refactor now — every member defaults to a no-op and each implementer
overrides only what it uses.

~~**B6. Deferred Look *rows* are half-retired**~~ — done, `0dadd42`. medium / P2 / M / opus
Write-boundary rejects them (`projectLooks.kt:719`) but `LookRegistry.loadLookSnapshot`,
`LookRowEntry.isDeferred`, `LookRowDto.isDeferred`, `DaoLookRow.isDeferred` and the
`rowTarget == null` arm of `CueComposer.applyLayer` (`CueComposer.kt:502-513`) still support them.
**Fix:** drop the row-side `isDeferred` plumbing; only Look *effects* and template rows defer.
(Related, already tracked: `FU-LOOK-ELEMENT-ROWS` for `elementKey` composing nowhere.)

Grew in the landing: a regression test pinning `validateLookRows`' rejection, and the split of the
zero-write include warning into its two real causes — the test that pinned it had been describing a
deferred row while actually exercising a bound one on an unpatched fixture. Nothing defensive was
added for a stored `deferred` row (no `toDetailsDto` filter, no validation on the sync-import or
copy paths): the operator's call, on the grounds that no database outside the dev desk has held one.

~~**B7. Layer source invariant enforced three ways with three behaviours**~~ — done, `fb50bed`. medium / P2 / M / opus
`DaoCueLayers.look`/`template` exactly-one invariant: read-time `check{}` throws
(`models/cues.kt:419`), `resolveCueLayerSource` silently drops (`projectCuesHelpers.kt:501`),
import raises `ImportError` (`ProjectImporter.kt:709`). **Fix:** a DB CHECK constraint (or
discriminator column) and one shared resolution behaviour with a warn.

Landed as `layerSourceShape` + a `cue_layer_exactly_one_source` CHECK, with **two** deliberate
departures. The importer keeps its `ImportError` — operator's call: it shares the verdict but not
the severity, because archive JSON is untrusted input arriving through a call that can report, and
silently dropping a layer on a sync pull is worse than failing the pull. And the CHECK only reaches
a database created after it (Exposed emits CHECKs in `CREATE TABLE` only; SQLite cannot
`ALTER TABLE ADD CONSTRAINT`), so the dev desk stays unconstrained and the warn-and-drop paths are
what hold the line there.

Grew in the landing: a **fourth** site, and the one that made the read-time throw reachable at all.
`POST /cues/{cueId}/copy` copied `look` alone and never `template`, so copying any cue with a
template layer wrote a row naming neither record — which is why the throw was only ever seen on a
GO, never on the write that caused it.

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

~~**C2. Reflective property access on the DMX write path**~~ — done, `503b50d`, high / P1 / L / fable
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

~~**C3. Crossfade republish re-runs the full resolver at ~62 fps under a lock**~~ — done, `d317d93`. high / P1 / M / fable
*(Premise partly spent: C1 cached the per-frame `resolveEffectFixtureKeys` walk this item cites.
Re-measure `[crossfade]` before implementing.)*
`CROSSFADE_TICK_MS = 16` → `updateCueFadeWeights` → `republishCueAssignments` (`FxEngine.kt:1405`):
per frame it copies every row, re-runs `CueAssignmentResolver.resolve` + winners + index, and walks
all active effects × `resolveEffectFixtureKeys` — inside `synchronized(cueAssignmentsLock)` that
programmer writes also contend for. The resolver's own doc still claims it runs "on cue apply
(rare), not per tick" (`CueAssignmentResolver.kt:169`). **Fix:** precompute effect coverage per
crossfade; hoist the winner-set resolve out of the per-frame path (only weights change); drop the
`cueLayerState` duplicate map built per publish (`LayerResolver.kt:39`).
Grew in the landing: the review moved the coverage-cache invalidation from `emitStateUpdate` to
the mutation seam (a stale skip between mutate and broadcast is a channel nothing repaints), and
made a weight reaching 1.0 force a full republish so winner attribution can't stay pinned past
end-of-fade when the outgoing cue had no Layer 4 rows. The per-frame row copies + regroup that
remain are filed under C6. Operator check: `FU-MANUAL-CROSSFADE-C3`.

~~**C4. Template edits can open a JDBC transaction on the 50 Hz tick loop**~~ — done, `638c0cb`. high / P1 / M / opus
`templateListChanged → registry.invalidateAll()` clears the cache and bumps the *global* version;
`TypedParams.invalidateColourCacheIfStale` (`TypedParams.kt:132-139`) then misses and
`loadTemplateSnapshot` opens a transaction from the tick — the exact thing
`TemplateColourSource.kt:33` forbids. The global version also invalidates *every* effect's colour
cache on any single-template edit. **Fix:** re-warm after `invalidateAll` (the single-template path
at `lookRepublish.kt:83` already does), and version per template uuid.
Grew in the landing: the review caught that re-warming *after* the version bump is the same bug in
miniature — the bump reaches the tick before the warm cache does — so both paths now read first and
publish the bump with the snapshot together, which turned the cited precedent (`invalidate` then a
caller-side `snapshot`) into one `refresh` call. The re-warm covers every uuid *asked* for rather
than every uuid cached, because a miss is not cached and the not-yet-created template is exactly the
case the un-scoped bump exists for. A re-warm read that fails is logged, not thrown, since
`invalidateAll` runs ahead of the WS broadcast in an unguarded listener chain. The re-warm's
unbounded synchronous cost against the size-1 pool is `FU-TMPL-REWARM-BOUND`. No benchmark
comparison — reasoned out in `docs/testing-engineering.md` §"Recorded baselines". Operator check:
`FU-MANUAL-FX-TEMPLATE-COLOUR` step 4.

~~**C5. Timed-layer fires re-cook the whole cue and hit the DB per fire**~~ — done, `ab8c791`. medium / P1 / M / opus
`CueTriggerManager.kt:129-141`: each fire = `buildCueAssignmentsForCue` + full `CueComposer.cook`
over all layers, at up to 10/s per timed layer. Plus N+1 FK dereferences building `CueApplyData`
(`projectCuesHelpers.kt:339`, `models/cues.kt:419-430`) and one-transaction-per-cue in
`republishForLookEdit` (`lookRepublish.kt:116-123`). **Fix:** cache the static half of the cook and
overlay fired layers; batch the DB reads. (The two-builder split feeding this is
`FU-CUE-APPLYDATA-ONE-BUILDER`, already Ready — do it as part of this item.)

Landed as a memo keyed on the fired-layer **set**, not as a cached static half with fired layers
overlaid: `cook` ranks all contributing layers by `sortOrder` together, so a timed layer below a
static one has to lose to it, and `CookWinner.index` and the stomp suppression are derived from
that same rank — overlaying inverts all three. Grew in the landing: the stamp needed
`LookRegistry.version` to bump on *both* sides of its eviction (it bumped only before, which
`expanded`'s in-flight guard requires) and to become an `AtomicLong`, or a stamp taken over the
still-visible pre-edit entry would have matched forever. No benchmark comparison — every cost is
off the tick; reasoned out in `docs/testing-engineering.md` §"Recorded baselines".

~~**C6. Per-tick allocation bundle**~~ — done, `91bb7ff`. medium / P1 / M / opus
`DistributionStrategy.RANDOM` allocates a `Random` + full Fisher-Yates per member per call
(`DistributionStrategy.kt:143-155`, O(N²)/tick); `calculateOffset` computed twice per member
(`FxInstance.kt:396` + call sites); anonymous `DistributionMemberInfo` + `EffectContext` per
element per tick (`FxEngine.kt:2635` et al.); `resetActiveProperties` scratch maps per tick
(`:2463`); `rateScales()` DoubleArray 50/s unconditionally (`SpeedMasterBank.kt:234`). **Fix:**
cache the permutation, return offset with phase, reuse scratch structures, skip rateScales when
unused.
Filed here from C3's review: each crossfade frame still copies every fading row
(`assignment.copy(fadeWeight = …)` in `republishCueAssignments`) and re-runs `resolveIndexed`'s
moveInDark pre-pass + two-level regroup, all pure functions of the unchanged row set — verified
safe to hoist by passing a cueId→weight lookup into the resolver and caching the buckets + armed
set across the fade.
Landed as four of the five, not five: `calculateOffset` was already computed once per member —
A6 made `calculatePhaseForMember` take the offset precomputed, and the KDoc there says so.
Grew in the landing: "skip `rateScales` when unused" turned out to be undefinable without
settling that `slotFor(null)` is slot 0, so a wall-clock effect with *no* rate master was being
scaled by master 1 — against both its own KDoc and CLAUDE.md. Taken as a fix (a `NO_RATE_MASTER`
sentinel) rather than preserved, on the operator's call, with `FU-MANUAL-WALLCLOCK-RATE` for the
rig check.

~~**C7. `emitStateUpdate` makes cue apply O(N²)**~~ — done, `034146b`. medium / P1 / M / opus
`FxEngine.kt:3009-3045` maps every active effect (with group lookups) once per `addEffect`;
`syncEffects` and `applyCue` call `addEffect` in loops. **Fix:** a batched `addEffects` emitting
once.

Grew in the landing: a fourth loop, `programmerInclude`'s local `spawn` over a cue's ad-hoc
children, was batched too — same shape, same family, taken on the operator's call. And measuring
it needed a sixth `FxEngineBenchmark` scenario, since the five tick/republish scenarios all spawn
their effects in rig setup, outside every measured window.

~~**C8. Cook-internal repeated work**~~ — done, `2381eae`. medium / P2 / M / opus
Programmer recook resolves the stack and builds cookLayers twice (`ProgrammerLayerStack.kt:445-450`
vs `:508`); group expansion + allowed-set allocation per row (`CueComposer.kt:515-516`); template
intent parsed per head not per row (`:536`); `TemplateResolver` walks the property catalogue twice
per head (`TemplateResolver.kt:137,147`); `coversTarget` re-expands per bound effect (`:660-678`);
`EffectKey` hashes whole parameter maps and forces respawns on unrelated param edits
(`ProgrammerLayerStack.kt:152`); `TypedParams` blank-case non-local return defeats the cache
(`TypedParams.kt:93,110`). **Fix:** one cook producing rows+effects; hoist/memoise per cook.
Grew in the landing: the split-pair snapshot hazard was on the cue GO path too, so
`CueStackManager.activateCueInStack` and `applyCueToEngine` take `cookAll` as well — nothing calls
`cook` + `cookEffects` as a pair now. The `EffectKey` respawn half was left alone: the type's KDoc
documents respawn-on-edit as the reason it keys on the whole `LookEffectEntry`, and changing it
means updating a live instance's parameters in place, which is a feature rather than a hoist. Only
its hashing was memoised. Measured on a new sixth `FxEngineBenchmark` scenario — none of the five
reaches `CueComposer`, so there was no before/after without it.

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

~~**E1. Split `FxEngine` (3,046 lines, ~7 responsibilities)**~~ — done, `3041ea2`. high / P2 / L / fable
Tick loops, Layer-4 cue-assignment bookkeeping, ~300 lines of programmer write delegation,
provenance computation, cascade publishing, effect registry/CRUD, DTO emission. **Fix:** extract at
the seams — `CueAssignmentLayer`, `ProgrammerWriter`, `ProvenanceService`, `CascadePublisher` —
leaving the engine as tick loops + effect set. Do **after** the D-wave deletions and C-wave fixes
so the split moves less code.
Grew in the landing: the review's three code nits were taken (one `allChannelsParked`, an inline
publish-lock wrapper, and a pre-existing `cueStackIds` leak in the new `CueAssignmentLayer.clearAll`
— see the commit).

~~**E2. Layering inversions**~~ — done, `4fafce2`. medium / P2 / M / opus
fx→routes: `ProgrammerLayerStack`, `CueTriggerManager`, `CueStackManager` import
`createInstanceFromPreset`/`resolveTargetForCue` from `routes/projectCuesHelpers.kt` — move them
into `fx/` as an `EffectSpawner`. models→fx: `models/{cues,looks,templates}.kt` import
`LayerSource`/`LayerSourceDto`/`AssignmentHealth` from `fx/` — move those types into `models/`.

Grew in the landing: `EffectSpawner` alone left `fx/` importing `routes/` for the rest of the
cue-apply domain, so that moved too — `fx/CueApply.kt` now holds `CueApplyData` and its builders,
`cueDerivedPriority`, `buildStompOverlap*`, `fixtureCategoryFor`, `buildCueAssignmentsForCue` and
`buildCombinedCueLayerRows`, leaving `projectCuesHelpers.kt` (1350 → 770 lines) the part that really
is transport. `FixtureGroup<*>.detectCapabilities()` and the two `Dao…CueChild.toDto()` converters
moved with it, to `fixture/group/` and `models/cues.kt`. Still open: `fx/` no longer *imports*
`routes/`, but `GroupSliderPropertyDescriptor` is declared in terms of `routes.ChannelRef`, so the
edge survives transitively through `fixture/group/GroupProperties.kt` — relocating
`PropertyDescriptor`/`ChannelRef` is its own item.

~~**E3. Split `CueStackManager` (827 lines, four jobs)**~~ — done, `617f35c`. medium / P2 / M / opus
DB reads, effect spawning, Layer-4 publishing, crossfade animation, auto-advance timers, standby
arming, run-state broadcast; `activateCueInStack` alone is 267 lines. **Fix:** extract the
crossfade driver and the standby/run-state half.

Grew in the landing: the review found two latent races in the moved code, both pre-existing and
neither a regression, and both were fixed here rather than filed — they sit inside the new classes
and one contradicted the KDoc the extraction had just added. `runStateFor` read the live stack
twice, so an activation landing between the two reads produced a frame whose live cue was the
outgoing one and whose next cue came from the incoming one; and `CueCrossfadeDriver.start`
published its job after `scope.launch` returned, so a concurrent cancel cancelled nothing and left
an orphaned fade to pin a superseded cue back to weight 1.0.

~~**E4. One param-coercion layer**~~ — done, `0ce10d9`. medium / P2 / M / opus
`BlendMode`/`DistributionStrategy`/`ElementMode`/`ElementFilter` parsing appears in
`projectCuesHelpers.kt` (silent default), `lightFx.kt` (400), `lightGroups.kt` (500) — same bad
string, three outcomes. `TypedParams.ubyte` also reimplements `EffectParamUtils.toUByteParam`.
**Fix:** one `EffectSpecCoercion` object used by all four call sites; collapse the two util layers.

Grew in the landing: the coercion object carries **two named policies** rather than one, because
the three outcomes were not all wrong. `Strict` rejects (request bodies); `Lenient` warns and
defaults (stored rows, where the cue still has to fire). Both sit on one nullable lookup, so the
vocabulary is single even where the policy is not. The review then found that the *authoring*
endpoints write these fields to the DB with no validation at all, which is where the bad string
actually enters — filed as E10 rather than folded in here.

~~**E5. Single source for layer ranking and cook priorities**~~ — done, `7fd70e9`. medium / P2 / S / opus
The contributing-layer predicate is implemented three times (`CueComposer.cook:242`,
`cookEffects:374`, `ProgrammerLayerStack.syncEffects:514` — the last missing the timed clause) and
`priorityFor(rank)` re-derives what `CookWinner.index` carries, with agreement documented but
unenforced. Also `CueComposer.cook`'s four-parameters-for-two-dependencies resolver signature
(`CueComposer.kt:212-234`). **Fix:** one `contributingLayers()` helper; pass resolvers only.

Grew in the landing: both of `cook`'s resolvers are *required* rather than defaulting to
`{ null }`. Failing to resolve a layer is not an error in `cook` — the layer is dropped with a
warning — so an omitted resolver would cook a cue to darkness and report only that each of its
layers could not load.

~~**E6. Programmer-band effect lifecycle has two owners**~~ — done, `e7a4666`. medium / P2 / M / fable
`ProgrammerLayerStack` keeps its own `effectInstances` map + lock and reconciles against
`engine.getActiveEffects()` per recook, while `FxEngine.removeProgrammerBandEffects` can delete
behind its back. **Fix:** put the (layerId, effectEntry, targetKey) identity on `FxInstance`, let
the engine own the band. Grew in the landing: `reset()` no longer clears effect bookkeeping, so a
band instance orphaned across it is retracted by the next recook instead of leaking until a sweep
— unobservable today, since `reset()`'s only caller sweeps the band on the next line.

~~**E7. Beat/wall-clock processing twins**~~ — done, `692d334`. medium / P2 / M / opus
`processFixture/MultiElement/Group/GroupFlatElement` effects exist twice (~190 duplicated lines,
`FxEngine.kt:2571-2798` vs `:2212-2398`) differing only in phase function — every C-fix lands
twice until then. **Fix:** parameterise on a phase supplier. (Distinct from
`FU-PERF-FRAME-TXN-UNIFY`, which is about unifying the *loops* — leave that trigger-gated.)
Grew in the landing: the review found that the wall-clock pass's synthetic `ClockTick` pinned
`tickNumber` at 0, which is not inert — it reaches every `StatefulEffect`, and `CandleFlicker`
(the only STATEFUL + WALL_CLOCK built-in) therefore held dead-steady at `baseLevel` on the rig.
Fixed in its own commit, `8b8ffa5`, with the contract pinned at the engine end in
`FxEnginePipelineTest` rather than against `calculateStateful` directly.

~~**E8. Tick-path error handling**~~ — done, `c606cfc`. medium / P1 / S / opus
`System.err.println` with no stack trace at 120/s for a misbehaving script effect
(`FxEngine.kt:2101,2202` + 6 more), one fully-empty catch (`resetOne`, `:2498`). **Fix:** SLF4J,
rate-limited, auto-pause an effect that throws N consecutive ticks. Also: move the `runBlocking`
test shims (`processBeatTick`/`processWallClockTick`) to test code; note `fxEngine.start(GlobalScope)`
(`Show.kt:238`) as accepted-for-now with a comment. Grew in the landing: the pass loops caught
nothing at all, so one throw outside the per-effect try killed every effect on the desk for the
life of the process; both levels now catch `Throwable` (a compiled script effect can raise an
`Error`), and an auto-pause resets the effect's properties so a half-applied group frame isn't
left frozen.

**E9. Small structural items** — low / P3 / S / sonnet
`FixturesChangeListener` 15-method interface → default methods (six implementers stub ~12 no-ops
each); extract `TapTempo` from `MasterClock`; name the magic-number relationships
(`PROGRAMMER_FX_PRIORITY_BASE` band vs the 100k rank clamp; `MAX_CATCHUP_MS` vs 300 BPM;
`DEFAULT_BPM` doubling as the rate-scale reference).

~~**E10. Authoring endpoints store effect enums unvalidated**~~ — done, `dc1e7ea`. medium / P2 / M / opus
E4 gave the four effect enum fields one coercion layer with two policies, and wired `Strict` into
the three REST *apply* endpoints. The endpoints that **write the stored rows** were not in its
scope and still pass `blendMode` / `distribution` / `elementMode` / `elementFilter` from the
request body straight into `varchar(50)`: `projectLooks.kt:671`, `projectCuesHelpers.kt:458,477`,
`plugins/ProgrammerSocket.kt:676`, `ai/AiTools.kt:440,682,700`. So `POST …/looks/{id}/effects`
with `blendMode: "ADD"` succeeds, the row reads back `"ADD"`, `LookStack.tsx` renders "ADD" as the
layer's blend — and every spawn warns and silently plays `OVERRIDE`. The value the operator sees
and the value the desk plays disagree permanently, which is the failure `Lenient` is designed to
survive rather than one it should have to. **Fix:** `EffectSpecCoercion.Strict` at every write
site, so a bad value is rejected where it enters and `Lenient` is left covering only rows written
by an older build. The WS and AI surfaces have no 400 to return, so each needs its own error path
— that, and the seven call sites, is why this is its own item and not part of E4.

Grew in the landing: an eighth site, `programmer.patchLayer`, stores the same column through the
same route into Record and was taken in with the seven. Making the cue write path strict then
turned a *stored* bad blend into a 500 on Record CREATE, because Include carried a stored layer's
`blendMode` into the programmer as a raw string and Record handed it straight back — the same
rawness that left Record's MERGE / UPDATE_EXISTING paths, which write `DaoCueLayer` directly,
storing unvalidated blends. One root cause, fixed once: `installFromCue` canonicalises through
`Lenient`. `ProjectImporter` stays lenient on purpose and now says why.

## F — API consistency *(decision: normalize hard, no aliases)*

~~**F1. Write the conventions down, then apply them**~~ — done, `1af6ef8` (+ lighting-react `85229a7`). medium / P2 / M / opus
One short `docs/api-conventions.md`: kebab-case paths; plural collections with GET-on-collection;
one spelling for vocabulary enumerations; `?force=true` as the guard-override convention;
"unbounded lists are fine at desk scale" stated explicitly. Then fix the deviants:
`/controlSurfaceTypes`, `/stageRegions`, `/surfaceBindings`,
`GET /project/list|current` and `GET /fixture/list|types` → plural resources.
(`/cueedit-histogram` was on this list; D1 deleted it. Note `?force=true` lost two of its three
users with D1 as well — the surviving guard-override convention is worth restating on whatever is
left rather than on what this item was written against.)

Landed as the full subtree rename, not just the named list endpoints: `/project/**` →
`/projects/**` and `/fixture/**` → `/fixtures/**`, confirmed with the desk owner before starting.
Introducing `GET /projects` while items stayed on `/project/{id}` would have left two spellings
for one resource, which is what this section's "no aliases" decision rules out. Both trees mount
from a single `route()` call, so the backend cost was one line each. `/fixture/types` became a
*top-level* `/fixture-types` rather than `/fixtures/types`: it enumerates what the build can
drive, most of which is not patched, so it belongs beside `/control-surface-types`.

~~**F2. One scoping rule for `{projectId}`**~~ — done, `05fb674` (+ lighting-react `481e453`). medium / P2 / M / opus
102 handlers 409 unless the id is current, while equally project-dependent surfaces (`/groups`,
`/fx`, `/programmer`, `/locate`, `/ai/conversations`) are global. **Recommended rule:** persisted
project *data* is project-scoped and 409-guarded; live-runtime surfaces are global by design.
Write it in the conventions doc; move the misfits (`/ai/conversations` is project-dependent data —
scope it).

Landed as a three-tier rule, not two: project-scoped mutations split again on whether the write
*is* a live-show mutation. 27 handlers (patches, riggings, stage regions, universe configs,
surface bindings, speed masters, sync) deliberately take `withProject` and then re-sync the show
only `if (isCurrentProject)`, because patching a rig you are not currently running is a real
workflow. Documenting the flat rule would have meant converting all of them.

Grew in the landing: `/ai/chat` now resolves the conversation within the current project and
re-checks between tool rounds (409 + partial transcript persisted if `set-current` lands
mid-loop), without which the scoping was decorative on the write path; and
`/fx/definitions/{definitionId}` had the identical defect — GET/PUT/DELETE/test resolved by bare
`findById` while list/create filtered by project — fixed in the same commit.

~~**F3. Normalize mutation responses**~~ — done, `5c59124`. low / P2 / S / sonnet
Deletes: 204 everywhere (currently split 204 vs 200-empty by resource); updates: return the DTO
everywhere (universe-configs returns 204); `show/advance` vs `show/deactivate` reply asymmetry.

Shrunk in the landing: universe-configs' PUT already returned its DTO (fixed incidentally by
`eb190ef`, an unrelated Art-Net streaming change) — verified rather than re-touched.

~~**F4. POST-for-read**~~ — done, `411363b` (+ lighting-react `a5d1853`). low / P3 / S / sonnet
`cue-stacks/{id}/preview` and `templates/resolve` → GET with query params. Compile-checks stay
POST (they carry source bodies) — note that in the conventions doc.

Landed as `cue-stacks/{id}/preview` only, not both: `templates/resolve`'s body is an unsaved draft
(a list of rows), the same "too shaped for a query string, no cache key worth it" reasoning the
plan already gives for compile-checks — confirmed against lighting-react's own `resolveTemplate`
comment, which keeps it a mutation for exactly that reason. It stays POST, documented as a second
named exception alongside compile-checks in api-conventions.md.

~~**F5. WS naming + snapshot rules**~~ — done, `27323a3` (+ lighting-react `c19aa12`). medium / P2 / M / opus
Dotted namespaces are the modern scheme; fix the two mixed-scheme stragglers
(`speedMasterListChanged` → `speedMasters.listChanged`, `surfaceBindingsChanged` →
`surfaceBank.bindingsChanged`). One snapshot rule: every stateful family pushes on connect,
request-messages remain as explicit resync — and fix the double `speedMasters.state` frame (server
pushes on connect *and* client requests on open). Document the three reply conventions and pick
one for future ops.

Grew in the landing: eleven families needed a connect snapshot, not one, and two of them were
being served by mechanisms that only *looked* like snapshots — `ParkManager.parkStateFlow` and
`FxEngine.fxStateFlow` were replay-1 `MutableSharedFlow`s, which replay nothing on a desk where
nothing has ever been parked and no effect has ever been added. Both are `StateFlow` now.
`ProjectManager.projectChangedFlow` went the other way, to replay-0: it was doubling as
`projectState`'s snapshot, and the `.drop(1)` two subscribers used to suppress its stale replay
swallowed the *first real* project switch instead whenever the replay cache happened to be empty —
which left a WebSocket's fixtures listener bound to the outgoing project for the rest of its life.
The conventions went into `docs/websocket-engineering.md` §"Conventions" (naming, snapshot rule,
the three reply conventions and the pick), leaving H1 to rewrite the reference tables below it.

~~**F6. Auth gating: route-tree composition** *(decision taken)*~~ — done, `60cc3b3` (+ lighting-react `0d2081d`). high / P1 / M / opus
Replace `ADMIN_ONLY_PREFIXES` string matching (`auth/AuthGate.kt:134`) with an `adminOnly {}`
route-tree wrapper; while there, admin-gate the code-execution/filesystem endpoints
(`scripts/run`, `fx/definitions/{id}/test`, `/script-editor` compile, `project/import|export`
client-supplied paths, AI `run_lighting_script`). The frontend mirrors the prefix list by hand
(`restApi.ts:15`) — flag for the frontend sweep. Cross-ref `FU-AUTH-WS-PER-MESSAGE`,
`FU-AUTH-OPERATOR-LOCKDOWN` (this narrows both). Also `/script-editor` mounts outside the
warm-up gate and the `/api` namespace (`router.kt:66`, `scriptEditor.kt:50`) — bring it inside.

Landed as operator-open scripts, not admin-gated: the desk owner's call, on the grounds that an
operator is trusted local crew who can already do anything the desk process can. `scripts/run`,
the FX-definition test, the editor compile and the AI `run_lighting_script` are unchanged, and
`AuthGateTest` now pins that. Only `project/{id}/export` and `project/import` were gated, on the
caller-supplied-filesystem-path argument. `/script-editor` moved to `/api/script-editor` inside
the auth gate but is **exempt from the readiness gate** — nothing under it touches the show, and
one 503 silently drops every editor on the page to read-only. Grew in the landing: the `/api`
node is shared with the WebSocket (Ktor reuses an equal selector), so the gates hang off a
transparent child of it; `transparentChild` in `routes/RouteComposition.kt` is the primitive both
that and `adminOnly {}` are built on.

~~**F7. Error envelope hygiene**~~ — done, `ba40af4`. low / P2 / S / sonnet
Move `ErrorResponse` out of `lightFx.kt:287` into a neutral `routes/ApiTypes.kt`; make `code`
present wherever the UI branches (today: 5 codes, ~278 prose-only sites — add codes opportunistically,
not exhaustively); drop the retired `fx_presets` arm in `ErrorHandling.kt:145`.
Audited all five live UI code-branch points (`lighting-react`'s `CODE_SPEED_MASTER_*`,
`LOOK_IN_USE`, `TEMPLATE_IN_USE`, `INCLUDE_TARGET_GONE`, `LAST_ADMIN`, `SELF_TARGET`) — every one
already carries a machine code, so no prose-only site needed one added opportunistically.

~~**F8. DTO unification across transports**~~ — done, `974e6c0` (+ lighting-react `d6c26c4`). medium / P2 / M / opus
`GroupSummary` (WS) vs `GroupSummaryDto` (REST), each with its own capability detection;
`FxEffectState` (WS, 12 fields) vs `EffectDto` (REST, 20 fields); `FxEffectState` built twice
differently in one file (`FxSocket.kt:163` vs `:228`, already diverged once). **Fix:** one DTO per
concept, one builder; client's missing `rateSpeedMasterIndex` goes to the frontend sweep.
The `GroupSummary` half needed nothing — D3 (`de2e1d5`) had already deleted `GroupSocket.kt` and
with it the WS type. The third representation the item doesn't name, `FxEngine.FxInstanceState`,
went too: the surviving `EffectDto` lives in `fx/EffectDto.kt` and the engine's state flow carries
it directly.
Grew in the landing: the five call sites were hand-assembling the `masterStates()` snapshot and
the `isMultiElementExpanded()` answer that the one builder needs, so `FxEngine.effectDtos` /
`effectDto` now own that pairing. B4's speed-master gating became one pair of accessors instead of
the same conditional written out per builder — which surfaced the one effect report B4 had missed,
`GroupEffectDto` (`GET /groups/{name}/fx`), fixed here with the operator's agreement.

## G — AI surface refresh

~~**G1. Speed-master awareness**~~ — done, `996a94e`. high / P1 / M / opus
`set_bpm` is master-1-only; no list/create/retune tools; the system prompt never mentions masters.
And the schemas can't express fields the handlers already read: `AiTools.kt:113-115` reads
`speedMasterUuid`/`rateSpeedMasterUuid` off effects but `lookEffectSchema`, `adHocEffectSchema`,
and `cueLayerSchema` (`AiToolSchemas.kt`) declare neither. **Fix:** add the schema fields; add a
speed-masters tool (or extend `set_bpm` with a master ref).
Landed as both halves of the parenthetical: `set_bpm` took the optional master ref and
`create_speed_master` is the new tool, sharing `createSpeedMaster` lifted out of the REST POST
handler. Listing masters stayed out — the system prompt now carries them with their uuids, and
G2 owns the `get_current_state` section. References are validated against the live bank at parse
time rather than written through, since `slotFor` would resolve a typo to master 1 silently.
Grew in the landing: `SpeedMasterBank.setBpm` returns whether it applied (a master deleted
between resolve and write was being reported as success), and `AiService`'s fixture line had a
pre-existing precedence bug dropping its closing paren for grouped fixtures, fixed here.

~~**G2. `get_current_state` misses the new subsystems**~~ — done, `3e61ebc`. medium / P1 / M / opus
No `speed_masters`, no programmer (which the AI itself mutates via `apply_look`), no cue run state
(active/armed-next/fade — the point of server-owned Next). **Fix:** add the three sections.

~~**G3. Missing tools for the new authoring loop**~~ — done, `1a5bf3a`. medium / P2 / M / opus
No template create (successor to the removed `set_palette`), no Record/Include/Update, no
standby/GO. **Fix:** add per product judgement — standby/GO first (smallest, highest show value).
Landed as all three, not standby/GO alone: the operator took the whole loop in one pass.
Grew in the landing: rather than a second implementation of the gestures that decide which rows
get overwritten, Record/Include/Update came out of the Ktor handlers into
`routes/programmerSurface.kt`, which both surfaces now render. `CueStackManager.go` is shared with
`SurfaceActions.cueStackGo` for the same reason, and two pre-existing bugs went with it —
`activate_cue_stack` hand-querying the first row instead of `activateAtFirstCue`, and
`advance_cue_stack` claiming a stack had been deactivated when `advanceStack` returned null.

**G4. Retired vocabulary** — low / P3 / S / sonnet
`parsePresetEffect` name, prompt text "the preset", ~~the always-absent `presetId` key~~ (gone with
A2), tombstone naming `get_show_state` for `get_current_state`.

## H — Docs and naming (one mechanical pass at the end)

~~**H1. Rewrite `docs/websocket-engineering.md`**~~ — done, `ca2cc8d`. medium / P2 / M / sonnet
It documents `fxPresetListChanged` (gone), inventories ~12 of ~110 actual message types, and lists
2 of 16 plugin files. Regenerate the inventory from the `@SerialName` declarations.

Grew in the landing: the real count is 95 (37 in, 58 out), not ~110. The doc also documented an
application-level `ping` message that has never existed in the codebase — that went too, along
with a note that keep-alive is Ktor's protocol ping.

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
| 1 | ~~C1–C2~~ **done** | The two big hot-path wins, taken against the fresh wave-0 baseline. fable. See the re-sequencing note below. |
| 2 | ~~D1–D6, D8, D9, A5–A10, E8, B3–B5~~ **done** | Retirements — everything after moves less code. D1 and D2 are done, so cueEdit-adjacent and tempo-surface work is unblocked. **A5/A6 land in the tick path: re-capture the benchmark baseline when this wave completes.** |
| 3 | ~~C3–C7, B1–B2~~ **done** | Remaining hot-path fixes, measured against the *re-captured* baseline, not the wave-0 one. fable for C3. B2 was pulled forward — see below. |
| 4 | ~~E1–E7, E10, C8, B6–B7, F6~~ **done** | Structure. E1 (FxEngine split) last in the wave, after everything shrank it. |
| 5 | ~~F1–F3, F5, F7–F8, G1–G3~~ **done** | API normalization — coordinate breaking changes with the frontend sweep (one list of frontend-visible changes maintained as these land). |
| 6 | ~~H1~~, H2–H3, G4, ~~D7, F4~~, E9 | Mechanical passes. D7 was pulled forward — see below. |

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
~~F1~~ (landed: the whole REST path rename went in with `lighting-react` `85229a7`, so
nothing is outstanding — see below), ~~F2~~ (landed: `/ai/conversations` moved to
`/projects/{projectId}/ai/conversations` and the four callers were repointed to
`projects/current/...` in `481e453`, so nothing is outstanding), ~~F3~~ (landed: status-code-only
changes — RTK Query's `fetchBaseQuery` already treats a `204`/an unread body as a no-op, so nothing
was outstanding; `deactivateProgram` gained a JSON body it doesn't read, which is harmless), ~~F4~~
(landed: `previewCueLook` moved to `GET` + `?cueId=` in `lighting-react` `a5d1853`, so nothing is
outstanding), ~~F5~~ (landed: two WS messages renamed —
`speedMasterListChanged` → `speedMasters.listChanged`, `surfaceBindingsChanged` →
`surfaceBank.bindingsChanged` — and every stateful family now pushes its snapshot on connect, so
the client's ten request-on-open sends went with it, in `lighting-react` `c19aa12`. Nothing
is outstanding — see below), ~~F6~~ (landed: the widget's base URL moved to
`/api/script-editor` in `0d2081d`, and `Projects.tsx` must gate Export/Import on `isAdmin` — see
below), E10 (the authoring
routes and the two `programmer.*Layer` frames now reject an unrecognised effect enum — verified
that no `lighting-react` sender can produce one, so this is a confirm-only entry), B3
(`elementMode` now accepted on `POST /fx/add`, matching `PUT`), B4 (`speedMasterUuid` /
`rateSpeedMasterUuid` — on the WS `fxState` push and reconnect answer, and on the REST
`EffectDto`/`IndirectEffectDto` — now null out whichever field the effect's `timingSource` doesn't
consume, instead of echoing both), B5 (`scriptListChanged` / `fxDefinitionListChanged`: two new WS
broadcast messages, script and FX-definition CRUD now invalidate a second client's caches instead
of leaving them stale forever), E4 (`POST /fx/add`, `PUT /fx/{id}` and `POST /groups/{name}/fx`
now **400** an unrecognised `distributionStrategy` or `elementFilter` instead of quietly using
LINEAR / ALL — the shipped UI only sends canonical names so nothing needs changing, but any
hand-built or replayed request with a legacy value now loses the whole effect), ~~F8~~ (landed: the
`fxState` frame is now the same `EffectDto` REST returns, so `phase` became `currentPhase`,
`targetKey` lost its `.property` suffix in favour of a sibling `propertyName`, and `effectType`
became the registry id rather than the display name — `lighting-react` `d6c26c4` follows, and
nothing is outstanding. See below).

**F5.** Two WS messages are renamed on the wire: `speedMasterListChanged` →
`speedMasters.listChanged` and `surfaceBindingsChanged` → `surfaceBank.bindingsChanged`. No
aliases, so a client that still listens for the old spelling silently stops invalidating.

The bigger change is that **every stateful family now pushes its snapshot on connect** —
`channelState`, `universesState`, `channelMappingState`, `parkState`, `fxState`, `projectState`,
`programmer.state`, `speedMasters.state`, `surfaceBank.state`, `surfaceScaler.state`,
`surfaceDevices.state`. A client can render the desk from the connect burst alone; the request
messages all still exist, but only as explicit resync. `lighting-react` dropped its ten
request-on-open sends accordingly, and `projectState` in particular is a new connect frame where
the client previously had to ask (the replay-1 `projectChanged` it used to lean on only arrived on
a desk that had already switched project once).

**F8.** The `fxState` WebSocket frame and `GET /api/rest/fx/active` now return the *same* object.
Three fields changed spelling or meaning on the WS side: `phase` → `currentPhase`; `targetKey` is
the bare fixture/group key with a new sibling `propertyName`, not the composite `"key.property"`;
and `effectType` is the registry id rather than the effect's display name. Nothing was removed —
the frame gained the whole REST field set, and REST gained `cueStackId`, `speedMasterIndex` and
`rateSpeedMasterIndex`. `lighting-react` `d6c26c4` updated the type declaration; the only
behavioural consequence there was a fix, since Kill All's `removeFx({ fixtureKey })` cache tag was
built from the composite key and so could never match the bare key REST tags with.

Separately, `GET /groups/{name}/fx` now nulls whichever of `speedMasterUuid` /
`rateSpeedMasterUuid` the effect's `timingSource` doesn't read, matching what B4 already did for
every other effect report. The group FX sheet should stop showing a rate-master chip on BEAT-timed
effects that carry a stale stored rate master.

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

B3 (`39f4d7c`) — `AddEffectRequest` gained the `elementMode` field `UpdateEffectRequest` already
had; `POST /fx/add` (and the group add route, which shares the DTO) can now set FLAT mode without
a follow-up `PUT`.

B4 (`54fdc8a`) — `speedMasterUuid`/`rateSpeedMasterUuid` are now gated on `timingSource` in all
three producers of this shape: `FxEngine.emitStateUpdate` (the WS `fxState` push), `FxSocket`'s
`buildFxStateMessage` (the WS reconnect/request answer — not named by the item text, but shares a
documented "the two `FxEffectState` producers can't disagree" invariant with the push, so it had
to move too), and `lightFx.kt`'s `toDto()`/`toIndirectDto()` (REST `GET /fx/active` and the
add/update response body — same defect, found independently while landing the item, confirmed
with the user before folding it in). The paired `*Index` display fields were deliberately left
unconditional — see the item body above.

B5 (`47dcb83`) — `scriptListChanged`/`fxDefinitionListChanged` added to `FixturesChangeListener`
and `BroadcastSocket`; script CRUD (`projectScripts.kt`, including the cross-project copy
endpoint, gated on the target project being current) and FX-definition CRUD (`fxDefinitions.kt`)
now fire them. Also, per the review pass and confirmed with the user:
`FixturesChangeListener`'s 16 members now all default to a no-op, and the five other implementers
(`GlobalScalerState`, `SurfaceFeedbackPublisher`, `Show.kt`'s two registry listeners, `State`'s
binding-health listener) were trimmed to only the overrides they actually use.

E4 (`0ce10d9`) — the four effect enum fields now have one parser, `fx/EffectSpecCoercion.kt`, with
two named policies: `Strict` (request bodies, rejects) and `Lenient` (stored rows, warns and
defaults). Frontend-visible only in that the three REST apply endpoints got *stricter*:
`distributionStrategy` and `elementFilter` used to degrade an unrecognised value to LINEAR / ALL
and add the effect, and now 400 the request, matching what `blendMode` always did. Casing is now
tolerated everywhere, which it was not on the two throwing fields. The authoring endpoints that
write these values to the DB were the other half, landed as E10.

E10 (`dc1e7ea`) — the authoring half of E4, and frontend-visible in the same narrow way: a
`blendMode` / `distribution` / `elementMode` / `elementFilter` value the desk does not recognise is
now refused where it enters rather than stored. The look and cue write routes answer 400,
`programmer.addLayer` / `programmer.patchLayer` answer a `programmer.error` frame, and the AI tool
call comes back failed. `lighting-react` sends only canonical names (`BlendMode` is a string-union
type, `distribution`/`elementFilter` are literals), so nothing there needs changing — the entry is
here so the frontend sweep can confirm rather than discover it. Casing and surrounding whitespace
are still tolerated, so this cannot break a client that was already sending a real value.

F6 (`60cc3b3`, + lighting-react `0d2081d`) — two frontend-visible changes. The script editor's
language services moved from `/script-editor/*` to `/api/script-editor/*`; the widget's base URL
is a module-level global with one assignment, so the client half is one line, and it landed with
the backend because a mismatch is silent (a failed `/versions` probe drops every editor on the
page to read-only). And `POST /project/{id}/export` + `POST /project/import` are now admin-only,
which **`Projects.tsx` does not yet know**: it offers both controls to any signed-in user, so an
operator gets a bare 403 from something that used to work. Gating them is left to the frontend
sweep (the desk owner's call), and `FS-COORD-ADMIN-GATE` names the file and the controls. Nothing
else changed status: the code-execution endpoints stayed operator-reachable, so no existing
surface becomes a 403 generator and the script editor needs no role handling.

F1 (`1af6ef8`, + lighting-react `85229a7`) — the conventions are written down in
`docs/api-conventions.md` (kebab-case segments, plural collections with the list GET on the
collection, one spelling for vocabulary enumerations, `?force=true` as the guard-override,
unbounded lists deliberate at desk scale), and every deviant was renamed. Frontend-visible in
full: `/controlSurfaceTypes` → `/control-surface-types`, `/project/{id}/stageRegions` →
`/projects/{id}/stage-regions`, `/project/{id}/surfaceBindings` →
`/projects/{id}/surface-bindings`, `GET /project/list` → `GET /projects`, `GET /project/current`
→ `GET /projects/current`, `GET /fixture/list` → `GET /fixtures`, `GET /fixture/{key}` →
`GET /fixtures/{key}`, `GET /fixture/types` → `GET /fixture-types`, and every other path under
the two renamed subtrees. The client half landed in the same run rather than being deferred:
there are no aliases, so a split would have left the desk's UI 404ing on nearly every call.
The sync export directory `stageRegions` and the WS message `surfaceBindingsChanged` were left
alone on purpose — the first is canonical-JSON layout (renaming it is a `formatVersion` break,
not a path rename), the second was F5's, and is now `surfaceBank.bindingsChanged`.

F2 (`05fb674`, + lighting-react `481e453`) — `docs/api-conventions.md` gains a "Project scoping"
section, and the one misfit moved. Frontend-visible in full: `GET /ai/conversations`,
`GET /ai/conversations/{id}` and `DELETE /ai/conversations/{id}` are now
`/projects/{projectId}/ai/conversations[/{conversationId}]`; the four callers went to
`projects/current/…` in the same run. The delete answers `204` rather than `200`-empty (F3's
target anyway, taken here because the caller was being repointed regardless). `POST /ai/chat`
keeps its path but gains a `409`: it refuses when the current project changes mid-loop, and a
`conversationId` belonging to another project is now a `404` rather than being silently continued.
`/fx/definitions/{definitionId}` is unchanged in shape but now `404`s a definition belonging to a
different project on GET/PUT/DELETE/test.

F3 (`5c59124`) — `docs/api-conventions.md` gains a "Mutation responses" section. Frontend-visible
but nothing to change: fx effect delete, cue delete, cue-slot delete, cue-stack delete and script
delete now answer `204` instead of `200`-empty, and `POST /{id}/show/deactivate` now answers the
same `ShowActivateResponse` shape as `/activate`/`/advance`/`/go-to` (both fields `null`) instead
of a bare `200`. Confirmed harmless rather than deferred: RTK Query's `fetchBaseQuery` already
treats an empty/`204` response as a no-op, and `deactivateProgram` is typed `void` and never reads
its body, so the new field is inert until something chooses to read it.

F4 (`411363b`, + lighting-react `a5d1853`) — `docs/api-conventions.md` gains a "POST-for-read"
section. Frontend-visible in full: `GET /{projectId}/cue-stacks/{stackId}/preview` replaces the
`POST` of the same path; `cueId` moves from the JSON body to a `?cueId=` query parameter (absent
still means "the stack's effective next"). The client half landed in the same run —
`previewCueLook`'s RTK Query definition sends `params: { cueId }` instead of a `POST` body.
`templates/resolve` stays `POST`, and is now a second named exception alongside compile-checks in
the conventions doc — its body is an unsaved draft (a list of rows), not a scalar, so there is no
clean query-string spelling and no cache key worth building around it.

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
