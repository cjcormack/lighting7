# Looks and Layers — replacing FX Presets and named Palettes

> **Document status: COMPLETE (2026-08-22).** All five sessions landed — 1 (backend), 2 (the Look
> library's frontend), 3a (the programmer as a layer stack), 3b (the programmer's frontend) and 4
> (the reference-era retirement, per-layer controls, the read surface and the docs). The narrative
> below is preserved as a session-by-session record; durable reference lives in
> [lighting-composition-model.md](../../lighting-composition-model.md),
> [fx-engineering.md](../../fx-engineering.md), [cues-engineering.md](../../cues-engineering.md) and
> `lighting-react`'s `CLAUDE.md` §"Looks and layers".
>
> **What session 4 did.** It began by exercising 3a and 3b against a desk for the first time — a
> second instance over a *copy* of the live database, because the real data dir is outside the
> agent sandbox's writable set. That found two bugs no test had:
> `computeProvenance`'s `PROGRAMMER` branch never named the winning layer (3a wired the `CUE` branch
> and left this one, so a cell lit by a busking pad answered *the programmer*), and
> `programmer.layerState` reached only the acting tab, because the assumption that "every layer
> mutation also emits `provenanceState`" is false for a mutation that moves no value. Both fixed
> and re-verified on the desk. Then: the `ref:{uuid}` value grammar retired across both repos in one
> change; the three Make Hard routes became one flatten-layer route with the first tests any of them
> ever had; per-layer blend and mask became editable; the four "independent" read renderers turned
> out to be one file and now share `LayerRow`; `models/palettes.kt`, `models/fxPresets.kt` and
> `DaoCuePresetApplications` are gone.
>
> **The rig checks are closed.** All three of §13's cases passed against the live test desk on
> 2026-08-22 once it was restarted onto this code — the touring edit, the within-cue intensity flip,
> and a layer drag not respawning the effects under it. Three extensions remain unseen and are
> listed there; the mid-crossfade Look edit is the one worth doing.
>
> **Two things deliberately not done**, each with a follow-up. Per-layer `stomp` shipped carried
> but unread (`FU-LOOK-STOMP-WITHIN-CUE` — it needed an `FxInstance` layer id and a suppression
> channel out of `cook`, which is engine work rather than a flag flip, so shipping the toggle alone
> would have been a control that does nothing); **that follow-up has since landed** — the engine
> reads it and `LookStack`'s badge is a toggle, see the Completed section of `followups.md`. A
> Look's element rows still compose nowhere (`FU-LOOK-ELEMENT-ROWS`, correction #10, pre-existing).
>
> **Session 4's own departures from this plan are recorded at the end of §5.**
>
> **Session 3 split into 3a (backend) and 3b (frontend) on 2026-08-22**, the same call D4 made for
> sessions 1 and 2, and for the same reason: the programmer route surface alone is ~3,000 lines
> across nine files, and the frontend slice is a `LookStack` extraction plus the Program/Programmer
> view merge plus the `ref:` read-path teardown. 3a is deliberately invisible to the desk —
> `/looks/{id}/toggle` and `/looks/preview` keep their exact request and response shapes, and every
> new wire field is additive with a default — so the two halves could land weeks apart without a
> broken intermediate state.
>
> **The `ref:` grammar retirement and the flatten-layer route moved out of session 3 into session
> 4's retirement pass**, joining the `models/palettes.kt` / `models/fxPresets.kt` /
> `DaoCuePresetApplications` deletion that already lived there. `ProgrammerValue.Ref` is load-bearing
> for `changedSinceInclude` — the mechanism that stops Mode A hardening every untouched `P1` row
> into a literal — and Include-a-cue deliberately writes ref slots, so untangling it in the same
> session that rewrote Update was the riskiest available combination. Grouping all the
> reference-era removal in one pass is also tidier than splitting it across two sessions.
> Supersedes the FX-preset and named-palette halves of
> [programmer-redesign-proposal.md](completed/programmer-redesign-proposal.md) §3.5.
> Not in production yet — migrate hard, no rollback shims.
>
> **Landed:** the four tables, `fx/CueComposer.kt` (cook + blending, 29 tests),
> `fx/LookRegistry.kt` replacing `PaletteRegistry`, `routes/projectLooks.kt`, the `lookListChanged`
> collapse, `applyCue` / `buildCombinedCueLayerRows` / `CueStackManager` / `CueTriggerManager`
> rewired onto cook, compatibility filtering, sync at `formatVersion` 5, the AI surface, and the
> startup migration. The FX-preset and named-palette HTTP routes are unmounted.
>
> **Outstanding from session 1:** the migration still has **no test** — see §9.6. It remains the
> largest residual risk, and the check that would flag the two intended behaviour changes cue by cue.
> A review pass caught two bugs in it that no test would have had to be clever to find, both now
> fixed:
>
> - Every uuid was read with `ResultSet.getString` on a column Exposed stores as a **16-byte blob**
>   (`UUIDColumnType` → `BINARY(16)`), which reinterprets the bytes as text and replaces everything
>   above 0x7F with U+FFFD. Verified on a real desk database: a preset uuid of
>   `7849763a-28bd-…` migrated to a 16-character mojibake string. That destroyed every guarantee
>   uuid preservation buys — idempotency, `ref:{uuid}` resolution, sync identity.
> - Uuids were written back as **text** literals. SQLite never compares a BLOB equal to a TEXT
>   value, so even a correct uuid written that way is invisible to `DaoLooks.uuid eq lookUuid` —
>   the lookup `loadLookSnapshot` performs — and every migrated Look would have failed to load with
>   "look could not be loaded — skipping layer".
>
> Both now go through `javaUuid()` / `sqlUuid()` helpers, and the migration deletes the unreachable
> rows its pre-fix version wrote (`typeof(uuid) <> 'blob'`) so the repaired pass stays idempotent
> instead of minting a duplicate "Warm (2)".
>
> The same pass found three more, also fixed: `CueStackManager.activateCueInStack` and the AI
> `apply_cue` tool both hand-roll their `CueApplyData` and neither set `layers`, so **no layer fired
> at all on a stack GO** — the primary firing path — nor through the AI; the fired-timed-layer set
> was keyed on the *look* rather than the layer, so one cue layering a Look twice at two delays fired
> both at the first delay; and look-spawned effects were stamped with `presetId = lookId`, which made
> `captureCurrentState` record a cue whose preset application named whatever `DaoFxPreset` shared
> that number (`FxInstance` now carries a separate `lookId`).
>
> **Session 2 landed** — the desk's UI is breathing again. `/projects/:id/looks` replaces both
> retired routes as one library banked by a sticky in-page family filter (not four sibling routes:
> a Look's families are derived, so one covering colour and position cannot own a path); the busking
> pads, the cue editor's layer list and the FX-panel picker all speak Looks; `buildCueInput` sends
> `layers`; and the ~39 importers of the four retired modules are gone. Four small backend routes
> came with it, each existing only to serve this UI: `POST /looks/{id}/toggle`,
> `POST`/`DELETE /looks/preview`, `lookId` on `POST /programmer/include`, and the
> `compatiblePresetIds` → `compatibleLookIds` rename (that field has held Look ids since session 1;
> only its name still said preset). `FU-LOOK-HEALTH-ARM-CLEANUP` is done in the same pass.
>
> **Still outstanding for session 3.** Include/Record/Update still call
> `buildCueAssignmentsForPreset` and therefore **ignore layers** — that was always session 3
> (§5.3), and it remains a live gap.
>
> The data-integrity half of that gap is now *unreachable from the UI*, which is the most session 2
> could honestly do about it: `POST /programmer/record-palette` is still mounted and still writes
> `DaoPalette` / `DaoPaletteEntry` rows that `LookRegistry` cannot see, but nothing calls it any
> more — `RecordPaletteSheet` is deleted rather than left reporting success over an invisible write.
> Include is retargeted at Looks and is deliberately **one-way**: it stages a Look's literals so
> they can be seen and busked from, and the programmer disables Update for a `LOOK` target rather
> than let the write-back path put rows into the retired tables. Making that round trip is the
> session-3 rewrite.
>
> Two smaller things session 3 inherits. There is **no way to create a bound Look** yet (the library
> says so in the Recorded section's empty state) — it needs the server-side record. And the toggle
> route stamps `FxInstance.presetId = lookId`, exactly as the AI's `apply_look` already did, so
> `captureCurrentState` would reconstruct a preset application naming whatever `DaoFxPreset` shares
> that number; harmless while nothing composes from preset applications, and it goes when the pads
> become programmer layers.
>
> **Five corrections to this plan, found in the code and applied:**
> 1. **`minReader` is written but never read.** The importer gates on `SUPPORTED_FORMAT_VERSION` /
>    `MIN_SUPPORTED_FORMAT_VERSION` in `ProjectImporter.kt`; both had to move to 5 as well, or the
>    bump is inert.
> 2. **Timed layers cannot append.** `CueTriggerManager` retracted a fired preset's rows by
>    *structural equality*; appending a fired layer beside cooked rows puts two contributors on one
>    key, with the tie decided by `HashMap` order. Firing now re-cooks the whole cue and publishes
>    through `replaceCueAssignments`, which preserves an in-flight crossfade weight.
> 3. **`CueStackManager.activateCueInStack` was already asymmetric** — it called
>    `buildCueAssignmentsForCue` alone, so an immediate preset's *property assignments* never reached
>    Layer 4 on a stack GO. Routing both paths through cook fixes that silently; it is a real
>    behaviour change beyond the intended LTP flip.
> 4. **Presets and palettes stop being portable at v5**, rather than being exported alongside looks.
>    Exporting both would put two representations of one entity in the repo and materialise both on
>    import. Their `SyncCoverageTest` dispositions are now `Excluded`.
> 5. **`AssignmentHealth.PaletteTypeMismatch` lost its only producer.** A Look has no declared
>    attribute type — families are derived — so "wrong-type reference" is no longer a coherent
>    diagnosis. The arm is unreachable and should be deleted with the frontend health descriptor.

> **Ten corrections to this plan, found while implementing 3a and applied:**
>
> 1. **§5's "an ordered layer list *above* the local slot stack" is backwards.** Layers materialise
>    *below* every local slot — tail of the key's stack, and a `seq` beneath every real write. §3.3's
>    "local always wins" and `CueComposer.cook`'s unconditional local overlay are the correct
>    statement; §5 inverts it in one word, and building to that wording gives a programmer where
>    adding a layer stomps the operator's busk.
> 2. **§5 and §3.6's "retire the `preset:{id}` *and `include`* owners" — `INCLUDE` must survive.**
>    It is the only thing preventing Mode A from hardening every untouched `P1`/`ref:` local row
>    (`changedSinceInclude`, pinned by `ProgrammerIncludeTargetTest`). The structural diff replaces
>    the *layer-list* half of Include's bookkeeping; the local-row half is still slot survival. Only
>    `preset:{id}` retired.
> 3. **§5's "`fxInstancesToCueChildren` collapses live instances per layer" was not possible as
>    written.** `FxInstance` had no layer dimension, and `lookId` cannot serve because one cue may
>    layer the same Look twice. A new `FxInstance.programmerLayerId` was required. In the end that
>    function does not collapse layers at all — it *skips* layer-owned effects, because the layer is
>    recorded and re-spawns its own.
> 4. **§5's `plugins/PaletteSocket.kt` carries no named-palette traffic at all.** It is 100% the
>    positional `P1`/`P2`/`P*` colour list, which §7 says survives. The traffic meant is in
>    `plugins/ProgrammerSocket.kt`. As written, §5 invites deleting the surviving colour list.
> 5. **§5's `routes/projectCuesMakeHard.kt` is mounted**, not unmounted (`routes/projectCues.kt`).
>    Only the preset twin is unmounted. The merge therefore retires a *live* route.
> 6. **§5 Session 1 records "Delete `buildCueAssignmentsForPreset`" as done. It was not** — it
>    survived with two live callers until 3a deleted it.
> 7. **`RecordSource.ALL` needed an explicit rule, and §5 had none.** `ALL` emits flattened rows and
>    **no** layers; `TOUCHED` emits the layer list plus only the operator's own rows. Emitting both
>    would put two representations of the same keys in one cue: the composed output would be
>    identical, but the cue would be permanently detached from the Look, so a later Look edit would
>    move the layer and be immediately overridden by the frozen row. Losing the touring behaviour
>    silently is the worst available outcome.
> 8. **A cue built entirely from layers writes no `INCLUDE` slots.** The gate that sets the include
>    target counted only `entriesWritten` and `fxSpawned`, so including such a cue left Update
>    falling through to the Mode B checklist, unable to write back the stack the operator had just
>    included. `IncludeOutcome.layersInstalled` exists for that gate. Found by a test.
> 9. **A latent migration bug.** `state/StateMigrations.kt` copies preset property assignments into
>    `look_rows` verbatim, *without* the `ref:` fold it applies to cue assignments. A pre-migration
>    preset row holding a `ref:` therefore lands as a **deferred look row holding a `ref:`** — which
>    `validateLookRows` forbids and which reads as white. It cannot be represented (a deferred row
>    has no target, and per-fixture palette resolution needs one), so the honest fix is to drop and
>    count it. No such row exists on the dev desk, and `LooksMigrationTest` does not cover it. Fix in
>    session 4 with the rest of the migration's reference handling.
> 10. **A pre-existing gap §3.1 implies works: a Look's element row composes nowhere.**
>     `DaoLookRows.elementKey` exists, §6 migrates element rows and §9.7 seeds one — but
>     `CueComposer.applyLayer` drops every element row, and `buildCueAssignmentsForCue` has no
>     element path either. Not a session-3 regression; it deserves an explicit follow-up rather than
>     an implied capability.

## 1. Context

Two operator use-cases drive this:

1. **A reusable look, editable in one place.** A director's note must not mean editing every
   cue that uses the look.
2. **Composition.** A show should have a library of looks that a cue is *built from*.

Today those halves are served by **different mechanisms on different entities**, and neither
entity delivers both:

- **Named palettes** (`models/palettes.kt`) give reference semantics at the level of a *single
  property value* — a row stores `ref:{uuid}`, `PaletteRegistry` resolves per fixture, and
  `routes/paletteRepublish.kt` re-resolves live consumers on edit. Editing propagates, but a
  palette carries no effects and cannot be composed as a bundle.
- **FX presets** (`models/fxPresets.kt`) give bundle composition — effects plus static rows —
  but are scoped to one `fixtureType`, are target-less, and are not ordered against the cue's
  own values.

So the defect is not that two entities exist. It is that **reference** and **composition** are
separate features, and composition *inside* a cue is not modelled at all.

### What the code actually does today (verified, not assumed)

`buildCombinedCueLayerRows` (`routes/projectCuesHelpers.kt:990`) concatenates
`cueOwn + presetRows`, and `cueDerivedPriority` gives **every** row of a cue — its own and all
its immediate presets' — the *identical* `priority` and `fadeWeight`. Consequences:

- **LTP (colour, position, settings): the first row in the list wins.**
  `composeLtp` uses `maxWithOrNull`, whose stdlib contract is "the first element having the
  largest value" — the incumbent is replaced only on a strict `>`. So cue-own beats preset,
  and among several presets the **earlier** `sortOrder` wins. That is the reverse of what
  `sortOrder` implies, and it is **untested**: every multi-contributor case in
  `CueAssignmentResolverTest.kt` uses distinct priorities, so the exact-tie path never runs.
- **HTP (dimmer): `max()`.** Weights sum to 2.0, missing the crossfade-blend branch. Cue
  asserts `dimmer=100`, its own preset asserts `180` → 180. Neither overrides.
- **The KDoc is wrong.** `buildCueAssignmentsForPreset:1057` claims "the sort order alone
  decides (last-write-wins for OVERRIDE blend)". That describes the *FX* layer, which is a
  genuine last-applied-wins sequential fold (`FxTarget.applyValue`, iterated in
  `compareBy(priority, id)` order). Layer 4 is first-encountered-wins. Two mechanisms in the
  same `applyCue`, doing opposite things.
- **No mid-fade cross-blending.** Both rows are scaled by the same per-cue weight in
  `FxEngine.republishCueAssignments`, so weights never diverge within a cue and the
  `winner.fadeWeight >= 1.0` early return fires. One row wins outright; the other is discarded.

Two further symptoms:

- **Ordering is a stub.** `sortOrder` exists on all three per-cue DTOs and tables, but the
  frontend never writes or reads it — adds append, removes filter. It reaches the resolver
  only as *list position*, and `Assignment` has no `sortOrder` field at all.
- **Surfaces have multiplied.** Program and Programmer are separate nav entries; three
  independent entry points apply a preset/palette to the stage (`AddPresetSheet`,
  `IncludeSheet`, the busking pads); and "palette" names two unrelated concepts.

**Intended outcome.** One library entity (**Look**), one reference mechanism (**Layer**), an
explicitly ordered composition inside both cues and the programmer, and one authoring surface
rendering that one structure.

Not yet in production (see the head of
`docs/plans/completed/programmer-redesign-proposal.md`), so: migrate hard, no rollback shims,
no dual-read period.

## 2. Decisions taken

| # | Decision | Consequence |
|---|---|---|
| D1 | **Full merge: layers only.** One `Look` entity; `ref:{uuid}` retired as a value grammar. | `PaletteRegistry`, `PaletteResolver`, `PaletteRef`, `paletteRepublish.kt` and both Make-Hard routes collapse into the cook step plus one "flatten layer" gesture. |
| D2 | **Both targeting modes, via deferred targets.** A Look row names a concrete target, or is deferred and takes targets from the layer line referencing it. | A bound Look behaves like today's palette; a fully-deferred one like today's preset. `fixtureType` survives only as an editor hint. |
| D3 | **Naming: Look + Layer.** | Avoids colliding with cue *children* (timed applications, `CueTriggerManager`) that "sub-cue" would have implied. Matches `LayersPane` and the composition-model doc's existing language. |
| D4 | **Four sessions; the Look library's frontend is its own.** *(Revised 2026-08-21 — originally "three sessions, each shipping its own frontend slice".)* | The backend slice alone filled a session, so pairing it with its UX meant shipping neither. The frontend is now session 2, immediately after the backend it consumes, rather than folded into it or deferred to the end. |

Routine calls made while planning, stated so they can be overridden: the Look keeps a
positional colour-list column so `PaletteCascade`'s most-specific scope survives (§7);
preset-compatibility filtering survives via `editorFixtureType` (§5.1); a `RecallLook` MIDI
binding target is a follow-up, not scope (§8).

## 3. The model

### 3.1 A Look

```
DaoLooks (looks)
  project, name, notes, sortOrder, uuid
  editorFixtureType varchar?   -- synthetic-fixture form-editor hint; deferred looks only
  palette json<List<String>>   -- positional colour list, inherited from DaoFxPresets.palette (§7)
```

**No stored attribute-family type.** Which families a Look touches is derived server-side from
its rows via the existing `maskGroupForProperty`, so the library banks by family the way
`/palettes/:type` does today and a Look can grow from one family to several with no migration.
`PaletteType` was already a `typealias` of `PropertyMaskGroup` to keep this cheap
(`models/palettes.kt:19`).

```
DaoLookRows (look_rows)
  look, targetType, targetKey,    -- targetType == 'deferred' → targets from the layer line
  propertyName, value, fadeDurationMs?, elementKey?, sortOrder, uuid

DaoLookEffects (look_effects)
  look, targetType, targetKey,    -- same deferred convention
  effectType, category, propertyName, beatDivision, blendMode, distribution,
  phaseOffset, elementMode, elementFilter, stepTiming, parameters json,
  speedMasterUuid?, rateSpeedMasterUuid?, sortOrder, uuid
```

`value` is always a **literal** in the existing `PropertyValue.serialize` grammar. Looks do not
nest (§7).

`DaoLookEffects` unifies two near-identical shapes: `FxPresetEffectDto` (target-less, a JSON
blob only to avoid DDL) and `CueAdHocEffectDto` (targeted, real columns). It becomes real
columns.

`moveInDark` deliberately does **not** appear on a Look row — a cue-crossfade concept, which
is also why `PaletteEntryDto` excluded it (`models/palettes.kt:30`).

### 3.2 A Layer

```
DaoCueLayers (cue_layers)
  cue, look, sortOrder, enabled,
  targets json<List<CueTargetDto>>,
  propertyMask varchar?,        -- comma-separated PropertyMaskGroup names; null = all
  blendMode varchar default 'OVERRIDE',
  amount double default 1.0,
  stomp bool default false,
  speedMasterUuid?, rateSpeedMasterUuid?,
  delayMs?, intervalMs?, randomWindowMs?,
  uuid
```

**`targets` has one meaning serving two jobs**: when non-empty it is the target set the layer
operates over — it *supplies* targets to deferred rows and *filters* bound rows. That single
rule is what lets the migration preserve coverage exactly (§6).

`propertyMask` is what subsumes value-level `ref:`. "This cue's colour comes from Warm,
everything else local" is one `COLOUR`-masked layer, not a separate feature.

`DaoCueLayers` directly supersedes `DaoCuePresetApplications`, which is already the ordered
per-cue application list (`sortOrder`, timing fields, speed-master overrides) — this is an
extension of that table, not a new concept beside it.

The cue's own `DaoCuePropertyAssignments` / `DaoCueAdHocEffects` stay as they are and become
**the local layer** — always exactly one, always last, so it needs no identity row. Keeping
them separate is deliberate on three counts: they carry `moveInDark`; `CueEditSession`'s
`upsertAssignment` matches on `(targetType, targetKey, propertyName)` with no layer dimension
and keeps working unchanged (§5.3); and it gives surface/cue-edit writes an unambiguous
destination.

The programmer holds the same structure in memory — an ordered layer list, no table, since the
programmer is transient runtime state.

### 3.3 The cook step

Flatten the stack to one row per (fixture, property) **before** the resolver sees it:

```
layers in sortOrder → local rows → cook → exactly ONE contributor
                                           per (fixture,property) → resolver
```

```kotlin
// new: fx/CueComposer.kt
fun cook(cue, looks, fixtures): List<CueAssignmentResolver.Assignment> {
    val acc = LinkedHashMap<Key, PropertyValue>()
    for (layer in cue.layers.filter { it.enabled }.sortedBy { it.sortOrder }) {
        for (row in look(layer).rows.filter { layer.mask covers it.propertyName }) {
            for (fixture in expand(row, layer.targets)) {   // group→member, specificity
                acc[key] = blend(acc[key], parse(row.value, fixture), layer.blendMode, layer.amount)
            }
        }
    }
    for (row in cue.localAssignments) acc[key] = row.value  // local always wins
    return acc.map { Assignment(cueId, cueDerivedPriority, fadeWeight = 1.0, ...) }
}
```

**Why cook rather than per-layer priorities.** The obvious alternative is to give each layer a
distinct `Assignment.priority` (there is even room: `cueDerivedPriority` leaves 999 slots
between cues). It does not work, and the reason is decisive: **`composeHtp` ignores `priority`
except on exact value ties.** Per-layer priority would give ordered override for colour and
position and leave dimmer on `max()` — the exact category-dependent split we are trying to
remove. Cooking is the only way to get one rule for all categories.

What this buys:

- **Within-cue** = strict ordered override (plus blend/amount), independent of
  `PropertyCategory`, explainable in one sentence.
- **Cross-cue** = untouched. All existing HTP/LTP, crossfade weighting and `moveInDark` logic
  keeps working, because the resolver still sees one contributor per cue per key — which is
  what it was written for.

**Invariant, worth its own test:** `cook` never emits two `Assignment`s with the same
`(targetKey, propertyName)` for one cue.

**Named behaviour change:** layered intensity becomes later-wins instead of HTP `max()`. That
is the requested semantics, but it *is* a change.

### 3.4 Blending

Per layer: `blendMode` ∈ {`OVERRIDE`, `MAX`, `MIN`, `MULTIPLY`} and `amount` ∈ [0,1] — a
linear mix of the layer over what has accumulated beneath (grandMA3 calls this Amount).
`OVERRIDE` at `1.0` is the default and is plain replacement.

`propertyMask` gives per-property *inclusion*. Per-property *blend overrides* are out of scope
— they multiply the UI for a rare case. Recorded as a follow-up (§8), not silently cut.

### 3.5 Effects, and the constraint that cannot be layered away

Effects do not cook — they spawn `FxInstance`s. **Spawning them in layer order is sufficient**:
`FxEngine.sortedEffectsComparator` is `compareBy(priority, id)`, `id` is a monotonic creation
counter, and per-tick composition is a genuine sequential fold through
`FxTarget.applyValue(..., blendMode)`. So same-priority effects already resolve last-created-wins,
and layer order becomes effect order for free. No priority arithmetic needed; keep the uniform
per-cue priority.

But one limit must be stated rather than designed around: **effects are Layer 3 and values are
Layer 4**, so an effect sits above a static value regardless of layer order. "Layer 2 sets
colour statically, Layer 1 runs a colour effect" resolves to the effect winning even though
Layer 2 is later. Layer order governs values-vs-values and effects-vs-effects, not the
value/effect boundary.

The escape hatch is **per-layer `stomp`**. Note today's `stomp` is *cross-cue*:
`stompForCue` removes ad-hoc effects owned by *other* cue ids and explicitly excludes the
stomping cue's own effects (`FxEngine.kt:1911`). Within-cue stomp is therefore genuinely new
behaviour built on existing scaffolding, not a flag flip.

### 3.6 Why this collapses Programmer vs Program

If a cue is *an ordered stack of Look layers plus a local layer*, so is the programmer:

- **Programmer** = the live, editable instance; **Cue** = a saved one
- **Record** = save the programmer's stack; **Include** = load a cue's stack into the
  programmer; **Update** = write the local layer back

So "each preset gets its own collapsible section alongside the cue's local settings/FXs" is
not a UI decision bolted on — it is the rendering of one data structure, and **the same
component serves the cue editor and the programmer**. That is what makes merging the views
cheap rather than a second project.

Two `ProgrammerStore` owners retire into this: `preset:{id}` (toggling a Look becomes adding a
programmer layer) and `include` ("changed since Include" becomes a structural diff of layer
list + local rows rather than a slot-survival trick).

**The owner-slot stack is *not* being repurposed as the layer mechanism**, and the reason is
worth recording because it looks tempting: `withSlot` promotes the writing owner to the front
on every `put`, so its order is *write-recency*, re-derived on each mutation. Layers need a
stable author-declared rank — the opposite invariant. Slot ownership is also per-*subsystem*
("who wrote this"), not per-layer, and its keys have no cue dimension at all. The remaining
owners — `web`, `surface`, `flash`, `locate`, `unpark` — stay exactly as they are, as
concurrent writers *on the local layer*.

### 3.7 Prior art

This is essentially **grandMA3 Recipes**: cue lines of Group × Preset × Phaser × Amount,
cooked into values at apply time and re-cooked when the referenced preset changes. Worth
consulting for edge cases. `docs/research/composition-model-prior-art.md` does not cover
recipes and should gain a short section.

## 4. UX

First-class, and never deferred to a trailing session — but not necessarily in the same session as
the backend it consumes (D4). The Look library's own UX is session 2.

### 4.1 The one component

`LookStack` renders an ordered, collapsible layer list plus a local section, used **unchanged**
for a cue and for the programmer:

```
┌ Cue 12 "Act 1 Warm" ──────────────────────────┐
│ ⋮⋮ ▸ 1  Warm Wash        [COLOUR]   ●100%  ⊘  │
│ ⋮⋮ ▾ 2  Slow Pulse    →front-bar     ● 80%    │
│         dimmer  SineWave  ½  M1               │
│    ▾ Local (this cue only)                    │
│         hex-3.dimmer  180                     │
│         hex-3.colour  ████                    │
│    + Add layer      + Add local value         │
└───────────────────────────────────────────────┘
```

Reorder with dnd-kit, already used by `StackDetail.tsx` for cue reordering. `⊘` is `enabled`,
the pill is `amount`, the bracket is `propertyMask`.

### 4.2 Authoring a Look — both models, chosen by targeting mode

The sharpest objection to a merge is that the two current editors are opposites: the preset
editor is a form built entirely around `fixtureType` via `buildSyntheticPresetFixture`, while
`PaletteDetailSheet` is deliberately read-only about values — its own doc comment argues that
"a grid of hex codes divorced from the heads they light is exactly the abstraction the
programmer redesign exists to remove". Both positions are right for their own targeting mode,
so keep both:

- **Bound Looks** → Record from the programmer, or Include → edit on stage → Update.
- **Deferred Looks** → the existing form editor (`PresetEditor`, `PresetDraftContext`,
  `buildSyntheticPresetFixture`) against `editorFixtureType`. A synthetic fixture is the only
  way to author values for targets that do not exist yet, so the hint earns its keep — as an
  editor affordance, not a data constraint.

### 4.3 Provenance becomes layer-aware

`useRowOwnership` already ranks `parked > programmer > effect > cue > baseline` per cell.
Extending `provenanceState` to name the **winning layer** turns existing colouring into an
answer for "why is this fixture this colour?" — pointing at *Warm Wash*, not at *a cue*. The
cook step knows the winner already, so this is a small change with high operator value.

### 4.4 Views retired or folded

| Today | After |
|---|---|
| `/presets` and `/palettes/:type` (×4) | one `/looks` library, banked by derived family |
| `/programmer` (`view="values"`) | redirects into Program — the pane there already renders the identical `ProgrammerSheet`, so this is nav and chrome only |
| `/programmer/fx` (`FxSheet` diagnostic) | a tab in the merged surface; today unreachable from Program at all |
| three "apply a preset/palette" entry points | one "add layer" gesture; busking pads add a programmer layer |

### 4.5 Read renderers — the hidden cost

Cue content is rendered by **four** component trees sharing nothing with `LayersPane`:
`RunCueCard`/`CueCardBody`, `RunMobileCueCard` (below the 600px runner threshold), and the
Prompt Book rail cards. Session 4 owns these; it is the likeliest thing to slip.

## 5. Implementation — four sessions

### Session 1 — Look entity, cook step, layer model (backend) — **done 2026-08-21**

- `models/looks.kt`: `DaoLooks`, `DaoLookRows`, `DaoLookEffects`. `DaoCueLayers` in
  `models/cues.kt`. Register all four in `models/Schema.kt` `ALL_TABLES`.
- `fx/CueComposer.kt`: `cook`, the blend functions, the one-row-per-key invariant.
- `fx/LookRegistry.kt` replacing `fx/PaletteRegistry.kt` — same version-counter cache and
  race-safe fill, now resolving whole layers. Keep the "load outside the `FxEngine` lock" rule.
- Rewire `applyCue`, `republishCueLayer`, `buildCombinedCueLayerRows`
  (`routes/projectCuesHelpers.kt`) and `fx/CueStackManager.kt` / `fx/CueTriggerManager.kt`
  onto `cook`. Delete `buildCueAssignmentsForPreset`.
- Effects: spawn in layer order (§3.5); `createInstanceFromPreset` /
  `createInstanceFromPresetForCue` / `CueStackManager.createInstanceForStackCue` become
  look-effect equivalents.
- `routes/projectLooks.kt`: CRUD, copy, preview, `LOOK_IN_USE` delete guard counting cue
  layers (mirroring the existing `SPEED_MASTER_IN_USE` / `paletteUsage` precedent).
- `show/Fixtures.kt`: `Listener.presetListChanged` + `paletteListChanged` → one
  `lookListChanged`; update the no-op implementors (`midi/GlobalScalerState.kt`,
  `midi/SurfaceFeedbackPublisher.kt`, `state/State.kt`) and the `show/Show.kt:75`
  invalidate-all wiring.
- **§5.1 Compatibility surface** — `routes/lightFixtures.kt` `inferPresetCapabilities` /
  `loadPresetCompatibilityInfos` / `compatibleIdsFor`, plus the `lightGroups.kt` twin, filter
  by `preset.fixtureType in allowedTypeKeys` and by inferred capabilities. Retarget at
  `editorFixtureType` for deferred looks; bound looks are excluded from compatibility
  filtering entirely (they name their own targets, so the question is moot).
- Sync: `sync/dto/SyncDtos.kt` (`LookJson` with rows/effects embedded, `CueLayerJson` as its
  own top-level folder the way `CuePresetApplicationJson` is), `ProjectExporter`,
  `ProjectImporter`, project-delete cascade in **both** `routes/projects.kt` and
  `ProjectImporter.replaceFromWorkingTree` (there is no DB-level `ON DELETE CASCADE`),
  `SyncCoverageTest.dispositions`, `testsupport/RichProjectFixture.kt`. Bump
  `formatVersion` 4 → 5 and `minReader` 1 → 5: `CuePresetApplicationJson.presetUuid` is a
  required field being removed. Clone needs no work — it is export→import
  (`sync/ProjectCloner.kt` has zero entity-specific code, by design).
- AI surface: `create_fx_preset` / `apply_preset` / `cuePresetApplicationSchema` in
  `ai/AiTools.kt` + `ai/AiToolSchemas.kt`, the `get_show_state` listing, and the
  `ai/AiService.kt` system prompt → look/layer equivalents.
- Migration (§6), one-shot at startup.

### Session 2 — The Look library's frontend — **done 2026-08-21**

**This session was not optional polish — the desk's UI was broken without it.** Session 1
unmounted the FX-preset and named-palette HTTP routes (their reference mechanism now resolves
through `LookRegistry`, so anything the old CRUD created would be invisible to every consumer), and
the frontend still called them: `/presets` and `/palettes/:type` 404'd, the busking pads 404'd
because they toggled a preset by id, and every existing `ref:` chip read "broken" because it
resolved its name from the palette list. The import list — not the three bullets this plan
originally carried — was the real scope: 79 files changed, +2560/−2235, with both the `presets/` and
`palettes/` component directories deleted.

**Four decisions taken while doing it, each a departure from what this section assumed:**

1. **One route with a sticky family filter, not four sibling routes.** The plan said "banked by
   derived family" and the palette banks were four routes with a sticky type. Those cannot be
   reproduced here, and the reason is the derivation itself: a Look covering colour and position
   belongs to two banks at once, so no family can own a path. `/looks` therefore takes an in-page
   `LookFamilyFilterBar` with `'ALL'` as a first-class default, and Cmd+K deep-links via
   `?family=`. This is a *documented exception* to the sibling-route rule in the frontend's
   `CLAUDE.md`, not an oversight of it.
2. **The value-level reference *authoring* surfaces are deleted, not retargeted.** `ref:` still
   resolves, so `LookRefBadge` still renders rows that hold one — name-only, since there is no
   declared type to colour a chip by. But nothing mints a new one: `ApplyPalettePopover`,
   `PalettePickerPopover` and the cue-assignment picker's reference button are gone, because a
   layer with a `propertyMask` is what replaces them.
3. **`RecordPaletteSheet` is deleted rather than left working.** Its route is still mounted and
   still writes rows `LookRegistry` cannot see; a surface that reports success over an invisible
   write is worse than a missing one. Creating a bound Look therefore waits for session 3, and the
   library's Recorded section says so in place of a button.
4. **Include-a-Look is one-way, and enforced at both ends.** The client disables Update for a
   `LOOK` target (`includedTargetIsReadOnly`), and `handleProgrammerUpdate` refuses one with a new
   `INCLUDE_TARGET_READ_ONLY`. That second half is not belt-and-braces: Mode A otherwise falls
   through to `includeTarget.cueId!!`, which is null for a Look, so the alternative to the guard is
   a 500 rather than a refusal.

Two smaller things found on the way, both of which would have shipped silently broken. The
`useUnsavedChanges` hook reports through the **Sheet's own context**, so calling it from the
component that renders the Sheet registers with nothing and the discard guard is dead — both new
sheets use `<Sheet unsavedChanges={…}>` with Cancel through `SheetClose` instead. And the library
needed its own `LOOK_IN_USE` delete guard: the guard UI existed only on the bound half, so a
template Look could not be deleted from anywhere.

One piece of **backend** work falls here rather than in session 1, because it exists only to serve
this UI: a `POST /project/{id}/looks/{lookId}/toggle` route. `loadLookToggleData`
(`routes/projectLooks.kt`) already adapts a Look into the shapes `togglePresetOnTargets` consumes —
deferred rows and effects only, since the toggle surface supplies the targets — and `apply_look` in
the AI surface already uses it. There is no HTTP endpoint yet, and the pads need one. (Session 3
then converts the pads from "toggle" to "add a programmer layer", which is the end state; a toggle
route is what keeps them working in between.)

Frontend:
- `api/looksApi.ts` + `store/looks.ts` replacing the four `fxPresets*` / `palettes*` modules.
- `/projects/:id/looks` library replacing the Presets and Palettes routes; banked by derived
  family (`LookDto.families`, server-derived); both authoring models (§4.2). The deferred half
  reuses `PresetEditor` / `PresetDraftContext` / `syntheticFixture.ts` against
  `editorFixtureType`; the bound half is Record-and-edit-on-stage, so it needs no value grid.
- `LayersPane.tsx` gains the real ordered layer section with dnd-kit reordering.
- `lib/cueUtils.ts` `buildCueInput`: replace `presetApplications` with `layers` **and extend its
  regression test** — that function is a deliberate field-by-field rebuild, so a missed field
  silently drops on every inline edit.
- Busking pads point at the Look toggle route.
- Every other importer of the retired modules: `PresetPicker` (both of them),
  `ApplyPalettePopover`, `PalettePickerPopover`, `PaletteRefBadge`, `MakeHardDialog`,
  `RecordPaletteSheet`, `FixturesTable`, `AddFixtureSheet`, `ViewSwitcher`, `IncludeSheet`,
  `CueDetailContent`, and the `presets/` and `palettes/` component directories.
- `AssignmentHealth.PaletteTypeMismatch` has no producer left (it named a Look's declared
  attribute type, which no longer exists). Delete the arm, its `describeAssignmentHealth` case and
  the frontend descriptor entry **in one change** — it is a serialized sealed arm, so removing it
  needs a forced recompile or every serialization test fails with `NoClassDefFoundError` while the
  build reports success. Tracked as `FU-LOOK-HEALTH-ARM-CLEANUP`.

### Session 3a — Programmer as a layer stack (backend) — **done**

Landed as five commits. What differs from the plan below is recorded in the ten corrections at the
head of this document; the design that replaced it is documented in the code, principally
`fx/ProgrammerLayerStack.kt`'s class doc and `ProgrammerStore.LAYER_SEQ_BASE`.

The shape of it: the programmer holds an ordered `List<ProgrammerLayer>` in `ProgrammerStore`, and
every mutation re-cooks the stack and **materialises** the result into slots under one new
`ProgrammerOwner.LAYERS`, rather than teaching the 50 Hz read path about layers. The deciding reason
is not the per-tick cost — `composeProgrammerOver` only runs for keys a running effect covers — but
that *everything else* about the programmer is answered by cold-path coverage oracles
(`coversFixture`, `activePropertiesByFixture`, `activeKeys`, `entries`), each of which would have had
to become layer-aware, and each of which needs the cooked key set anyway.

"A local write always wins, whichever happened first" needs **two** mechanisms, and neither implies
the other: tail insertion in `withSlot` (because `get` returns the stack top and ignores `seq`, and
`SliderTarget`/`SettingTarget` take the property entry unconditionally), and a reserved negative
`seq` band (because three of the four overrides also arbitrate across *granularities* by recency).
The band is `LAYER_SEQ_BASE + layerIndex`, not a flat sentinel: a flat value would produce the first
`seq` tie this store has ever held, and the two colour-bundling sites break a tie in **opposite**
directions — `SliderTarget` prefers an explicit `white` row, `ColourTarget` prefers the Colour
entry's bundled component — while both write the same DMX channel.
`ProgrammerLayerSlotCompositionTest` pins that the two paths agree; under a `seq = 0` mutant it
reports 10 and 250 for one channel.

Also here: effect priorities derived from layer index, so a **reorder re-ranks in place rather than
respawning** (a respawn restarts every effect's phase, mid-drag); Record saves the stack as layers
(§7 above); Include installs a cue's layers as programmer layers, closing the gap §10 recorded;
Update diffs the stack structurally against a baseline taken at Include; `CueEditSession` snapshots
the layer list so Discard reverts a reorder; and the entire preset-toggle apparatus is deleted —
`presetToggleStates`, `presetPreviewStates`, `swapPresetPreviewSlot`, `togglePresetOnTargets`,
`applyPresetProgrammerWrites`, `ProgrammerOwner.preset` and `buildCueAssignmentsForPreset`.

The Look editor's live preview is a layer holding an **inline** snapshot, because it previews an
*unsaved* draft that `LookRegistry` has never heard of; `CueComposer.cook` and `cookEffects` take an
optional `resolveLook` for exactly that one caller.

### Session 3b — the programmer's frontend — **done 2026-08-22**

Landed in `lighting-react` as one change: `LookStack` extracted and shared, the programmer's
layer ops on the wire, the Program/Programmer merge, `RecordLookSheet`, layer-aware provenance,
and the pads' ring. `npm run check` green at 1008 tests (from 951).

**Seven departures from the plan, each found in the code:**

1. **The pads stayed on `POST /looks/{id}/toggle`.** §5 said move them to explicit layer ops; that
   route *is* `ProgrammerLayerStack.toggle` now — it adds or removes a layer, matching on
   `lookId` + exact `targets`. Moving the pads would have duplicated that match rule client-side,
   because the ring has to compute the same thing. Only the ring changed, and it reads the **layer
   stack** rather than the effect list: `lookLayerPresence`. The plan's "match the new `lookId`
   field on the FX-list DTO" is the wrong fix — a Look made only of static rows spawns no effect,
   so no effect-list match can ever see it.
2. **The Programmer nav entry was deleted, not repointed.** §4.4 called the merge "nav and chrome
   only", but `pathMatch: "/program"` and `"/programmer"` cannot both exist for one destination —
   two sidebar rows leading to one page *is* the collision. `/programmer` and `/programmer/fx` are
   `ProgrammerLegacyRedirect`. Cost: Cmd+K no longer carries the word "Programmer".
3. **The Values tab must be `forceMount`ed.** `useListSelection` clears its scope on unmount, and
   its own comment records why that was safe: "only one list per scope is ever mounted at a time
   (the three scopes belong to mutually exclusive routes)". Tabs broke that premise, and without
   the force-mount, glancing at the layer stack silently discards the fixture selection Record and
   Record-look scope on. It costs nothing new — the pane rendered `ProgrammerSheet` unconditionally
   before — and Radix never hides a force-mounted panel, so the explicit `hidden` is load-bearing.
4. **`FAMILY_COLUMNS` was deleted rather than used.** The plan offered "use them or delete them"
   for both caller-less helpers. `familyForCategory` got a real caller — per-family counts in the
   record sheet's mask picker — and the family→columns direction still had none.
5. **Those counts cannot be scoped to the selection**, which matters because the selection defaults
   *on* in this sheet. A group-addressed entry's expansion into fixtures is server-side, so a
   client-side filter would drop those entries rather than narrow honestly. They count the whole
   programmer and the label says so; which *families* are in play survives the narrowing, the
   magnitude does not.
6. **The shared layer picker had to learn `allowTiming`.** Its confirm step renders delay /
   interval / random-window fields, and a programmer layer has no playback to delay against — so
   the fields were being offered and then dropped. Hidden for the programmer; the speed-master
   override on that step is still honoured.
7. **`ProvenanceEntry`'s new fields must go in the client's provenance signature.** A key can move
   from "the cue" to "the cue's Warm Wash layer" with `source` unchanged, so a cell that didn't
   wake would keep naming the old answer. Same class of bug as the `entrySignature` note in §9.9.

One frontend trap worth the same billing as session 2's `useUnsavedChanges` note: **RTK Query's
`data` falls back to the previous argument's result while a new one is in flight, and `isLoading`
is `false` whenever it does.** `FU-FE-LOOK-SAVE-GUARD-TEST`'s guard read `data`, so editing Look A,
closing, then opening Look B handed the editor A's rows under B's id — and Update would have written
A's rows into B, deleting B's. `currentData` is this argument's own data or nothing. Found by a
review pass, not by the tests that were written first.

### Session 3 — the original plan, for the record

Backend:
- `ProgrammerStore`: ordered layer list above the local slot stack; retire the `preset:{id}`
  and `include` owners and the `presetToggleStates` / `presetPreviewStates` bookkeeping
  (`swapPresetPreviewSlot`'s concurrency contract carries over to "preview a Look as a
  programmer layer" — see `PresetPreviewSlotTest`).
- Record / Include / Update (`routes/programmerRecord.kt`, `programmerInclude.kt`,
  `programmerCapture.kt`) rewritten against layers. `fxInstancesToCueChildren` collapses live
  instances per layer rather than per preset. Update's "changed since Include" becomes a
  structural diff.
- Remove the `ref:` grammar: `fx/PaletteRef.kt`, `fx/PaletteResolver.kt`,
  `routes/paletteRepublish.kt`, `routes/programmerPaletteRecord.kt`, `plugins/PaletteSocket.kt`'s
  named-palette traffic, the ref arms of `fx/AssignmentHealth.kt` and
  `fx/PersistedFixtureReferenceValidator.kt`. `projectCuesMakeHard.kt` and
  `projectFxPresetsMakeHard.kt` become one **"flatten layer into local rows"** route — which
  also disposes of the target-less hardening problem
  (`docs/lighting-composition-model.md:295`), since a layer always has a target set.
- `provenanceState` carries the winning layer (§4.3).
- **§5.3 `CueEditSession`** — `upsertAssignment` matches `(targetType, targetKey,
  propertyName)` with no layer dimension, and `setProperty`/`setChannel` callers (including
  `SurfaceInputRouter`) have no way to name a layer. Rule: **surface and cue-edit writes always
  land in the local layer.** Because local rows stay in `DaoCuePropertyAssignments`,
  `upsertAssignment` and `discardChanges`'s snapshot-and-restore need no change; only
  `beginEdit`'s snapshot gains the layer list.

Frontend:
- The `LookStack` component (§4.1), shared by the cue editor and the programmer.
- `/programmer` redirects into Program; `FxSheet` folded in as a tab.
- Busking pads apply a Look as a programmer layer.
- `lib/programmerValue.ts`: drop `parsePaletteRefUuid` / `serializePaletteRef` /
  `isPaletteRefValue`; `PaletteRefBadge` becomes a layer chip.

### Session 4 — Blend/amount UX, remaining renderers, retirement, docs

**Now also carries the reference-era retirement, moved here from session 3** (see the header):
the `ref:` value grammar (`fx/PaletteRef.kt`, `fx/PaletteResolver.kt`, `ProgrammerValue.Ref`,
`CueAssignmentResolver.Assignment.paletteUuid`, the two `AssignmentHealth` arms,
`PersistedFixtureReferenceValidator.validatePaletteReference`, the `ref:` half of
`activeCuesReferencingLook`, and the palette arm of Mode A Update), and the merge of
`projectCuesMakeHard.kt` + `projectFxPresetsMakeHard.kt` into one **flatten-layer** route.

Two notes for whoever picks that up. The **no-nesting guard must survive the grammar**:
`validateLookRows` rejects a `ref:` in a Look row, `LookRoutesTest` pins it, and that rejection *is*
the non-recursion guarantee `FU-LOOK-NESTED` depends on — keep it as an inlined shape check.
And the migration's raw `removePrefix("ref:")` is the **upgrade path**, not dead code.

The flatten-layer route lands on bare ground: there is no test anywhere for any make-hard route, and
the cue route's group-expansion rule (preserving `sortOrder` / `fadeDurationMs` / `moveInDark`) plus
its `fixtureCategoryFor`-not-catalogue handling of `position` are both undertested behaviours being
re-implemented. Also fix its hardcoded `withCurrentProject(state, "current", …)`, which ignores the
URL's `projectId`.



Frontend:
- Per-layer blend / amount / mask / stomp controls.
- `RunCueCard`/`CueCardBody`, `RunMobileCueCard` and the Prompt Book rail render layer stacks
  (§4.5). Mobile pass.

Backend:
- Per-layer `stomp` (§3.5 — new within-cue behaviour, not a flag flip).
- Delete `models/palettes.kt`, `models/fxPresets.kt`, `DaoCuePresetApplications` and their
  routes and tables.
- `PaletteCascade`'s `preset` scope becomes a `look` scope (§7).

Docs:
- `docs/lighting-composition-model.md`: §"Named palettes (references)" → §"Looks and layers";
  rewrite §"Hardening"; document the cook step and the Layer 3/4 constraint (§3.5); **fix the
  first-wins/last-wins record** rather than carrying the old KDoc's claim forward.
- `docs/fx-engineering.md`: preset sections → Look sections; layer-ordered effect spawning.
- `docs/cues-engineering.md`: Record/Include/Update against layers.
- `docs/research/composition-model-prior-art.md`: add grandMA3 Recipes.
- Move this plan to `docs/plans/completed/` with a session-by-session status header, the way
  `programmer-redesign-proposal.md` records its five sessions; retire superseded `FU-PAL-*`
  items and add the new ones (§8).

### Session 4 — the retirement pass — **done 2026-08-22**

Landed across both repos. `npm run check` green at **1003 tests**; backend green in three groups.

**Nine departures from the plan, each found in the code — the last one only by driving the route
against a desk:**

1. **§4.5's "four independent read renderers" is one file.** No renderer reads `presetApplications`
   at all — `Cue` has no such field. `RunCueCard` reaches `CueDetailContent` through
   `RunOutputPane`, and `RunMobileCueCard` and `PromptBookCueCard` through `CueCardBody`; none of
   the four render cue content themselves. `RunMobileCueCard` needed *zero* changes. And
   `LayerRow` was already exported with a `readOnly` prop and a test pinning it, so the pass was a
   ~55-line deletion plus making `handlers` optional. §10's warning that this would slip was the
   one prediction in the plan that was simply wrong.
2. **Flattening a *middle* layer cannot be output-preserving, and the plan didn't notice.** Local
   rows beat every layer unconditionally, so promoting a middle layer's values would make them win
   over the layers above — the cue would look different immediately after an operation whose whole
   promise is that nothing changes. The route refuses a single `layerId` with 409 unless it is the
   last enabled layer; whole-stack flatten is always safe.
3. **The flatten route emits fixture-targeted rows only.** The old cue route could keep a group row
   when every member resolved alike, because it rewrote a value in place without cooking. Cook's
   output is per fixture *by construction* and a cooked key carries no group name, so re-deriving
   one would mean guessing which of several overlapping groups to name. Pinned by a test rather
   than left to be discovered.
4. **There were *three* Make Hard routes, not two.** `routes/programmerMakeHard.kt` — mounted, no
   test, and its KDoc claimed a shared WS op that does not exist — dies with the grammar too, since
   `hardenProgrammerRefs` has nothing left to harden. Its frontend surface (`MakeHardDialog`, the
   toolbar action, `referenceCount`, the `makeProgrammerHard` endpoint) went with it.
5. **`FxPresetEffectDto` had to be relocated, not deleted** — the plan flagged this and it was
   worse than stated: sixteen files use it, because it had long since stopped being preset-shaped
   and become the shared effect wire shape. It is now `LookEffectSpec` in `models/looks.kt`.
   `TogglePresetTarget` went the other way: field-for-field identical to `CueTargetDto`, so it
   collapsed into it rather than being renamed.
6. **`POST /programmer/record-palette` does not exist**, and had not for a session — only stale
   prose in three files mentioned it. `AssignmentHealth.PaletteTypeMismatch` was likewise already
   gone, so §1 removed two health arms rather than three.
7. **The migration test needed the legacy schema kept alive.** `LooksMigrationTest` seeds *v4* rows,
   so deleting `models/palettes.kt` and `models/fxPresets.kt` took its ability to construct its own
   input. The tables now live in `src/test/.../testsupport/LegacySchema.kt`, copied verbatim
   (column types are the point — `uuid` is a `javaUUID` blob, and reading it as text is the bug that
   shipped), created per-test with `SchemaUtils.create`. The migration already guarded every read on
   `sqlite_master`, so it correctly no-ops on a database that has never had them.
8. **`cueEdit.addPresetApplication` was dead on both sides** and is deleted rather than ported to
   layers. The frontend declared the outgoing type and never constructed it; a cue-edit session adds
   a layer through the ordinary cue PATCH. There is deliberately no `cueEdit.addLayer` — nothing
   asked for one.

9. **A layer was unaddressable from a client, and only a desk found it.** The flatten route takes an
   optional `layerId`, and `CueLayerDto` carried none — `lookId` is not unique (a cue may layer the
   same Look twice) and array position is not identity when `sortOrder` is authoritative. So the
   route's single-layer mode had no way to be *called*, while all eleven of its unit tests passed,
   because they read the id straight from the database. `CueLayerDto` now carries a read-only `id`,
   on the same convention as `lookName`, with a test asserting the read hands out the id the route
   accepts. A good argument for driving a new route over the wire at least once, not only through
   its tests.

**Renames that crossed the wire**, all with their frontend halves in the same change:
`SpeedMasterUsage.presetEffects`/`cuePresetApplications` → `lookEffects`/`cueLayers` (and the
`SpeedMasterInUseResponse` fields with them, since the speed-master delete guard has to keep seeing
everything that references a master); `CueStackCueEntry.presetCount` → `layerCount`;
`ProjectSummary.fxPresetCount` → `lookCount`; `LookSummary.refRowCount` deleted;
`ProgrammerEntry`'s five `palette*` fields deleted; `LookRefBadge` → `LookNameBadge`.

**One UI inconsistency found by a test and fixed in the component rather than the test**: the
read-only mask badge rendered the raw wire string (`[COLOUR]`) while the new editable trigger
rendered labels (`[Colour]`). Wording one surface differently from the other would have undone the
reason `LayerRow` is shared between them.

## 6. Migration

One-shot at startup, no rollback shim.

| From | To |
|---|---|
| each `DaoPalette` | a `Look`; entries → `DaoLookRows`, concrete targets |
| each `DaoFxPreset` | a `Look`; `propertyAssignments` → rows with `targetType='deferred'`; the `effects` blob → `DaoLookEffects` rows, deferred; `fixtureType` → `editorFixtureType`; `palette` column carried over |
| each `DaoCuePresetApplication` | a `DaoCueLayer`, `targets` = its targets, carrying `sortOrder`, timing fields and speed-master overrides |
| each cue assignment whose value is `ref:{uuid}` | folded into **one** layer per (cue, Look), `targets` = exactly the referenced targets, `propertyMask` = exactly the referenced properties; then the row deleted |

That last row is why `targets` filters bound rows as well as supplying deferred ones. Without
the restriction, a cue that referenced a palette for two fixtures would silently start
asserting every fixture the palette covers.

**Coverage-preservation is the migration's correctness test:** for each cue, `cook` after
migration must produce the same `(fixture, property) → value` map as
`buildCombinedCueLayerRows` did before. Write it as a temporary golden test over
`RichProjectFixture`, whose seeding already exercises the hard cases — a preset row holding a
named-palette ref, a cue assignment holding one, an `elementKey` row, a timed application with
all three timing fields, and a palette with both fixture- and group-scoped entries.

Note the ordering flip this bakes in: migrated multi-preset cues currently resolve LTP ties
*earlier-first*; after cooking they resolve *later-wins*. For a cue where two presets assert
the same (fixture, property), the composed result changes. This is the intended fix, but the
golden test will flag those cues, and each should be eyeballed rather than blanket-accepted.

## 7. Explicitly out of scope

- **The positional colour list** (`P1`/`P2`/`P*`, `PaletteCascade`, `DaoCues.palette`,
  `fx/effects/PaletteColourEffects.kt`, `plugins/PaletteSocket.kt`) — a third unrelated thing
  called "palette", parameterising *effects*, not looks. It survives; `DaoLooks` keeps a
  `palette` column so the cascade's most-specific scope (`preset > cue > global`) becomes
  `look > cue > global` rather than being lost. Note a Look row's literal may itself contain
  `P1`, resolved against whatever cascade is active — so the cook step must thread the cascade
  through exactly as the current builders do. Happy side effect: once named palettes are gone
  the word is free, and the UI's "Colour List" relabelling can be dropped.
- **Nested Looks.** `FU-PAL-LINKED` remains the home for the idea; the cook step is where it
  would land.
- **Per-property blend overrides** (§3.4).
- **A `RecallLook` MIDI binding target.** No binding target names a preset or palette today
  (`midi/BindingTarget.kt`), so the surface needs no redesign — but a Look is an obvious thing
  to want on a button. Follow-up (§8).
- **Run and Prompt Book transport behaviour.** Only their *rendering* changes.

## 8. Follow-ups to record

- `FU-LOOK-PERPROP-BLEND` — per-property blend override within a layer. Trigger: an operator
  wants one property of a layer to mix while the rest override.
- `FU-LOOK-MIDI-RECALL` — a `RecallLook` binding target.
- `FU-LOOK-NESTED` — supersedes/absorbs `FU-PAL-LINKED`.
- `FU-LOOK-STOMP-GRANULAR` — if per-layer stomp proves too coarse.
- Retire `FU-PAL-APPLY-NEAREST-COVERAGE` and `FU-PAL-DELETE-HARDEN-LOOP` — both concern
  palette-ref mechanics that cease to exist.

## 9. Verification

Status as of session 1: ✅ done · ⬜ outstanding.

1. ✅ `./gradlew test` — the project's pre-commit check (no Makefile; the global
   `make commit-check` rule does not apply). 1651 tests at the end of session 1, all passing.

   Session 2 re-ran it **split by package**, because the whole suite exceeds a single foreground
   timeout: `routes.*` + `plugins.*` (517) and `fx.*` / `state.*` / `sync.*` / `models.*` /
   `midi.*` / `ai.*` (819), plus `LookRoutesTest` on its own. All green. Two traps worth writing
   down: the filter needs `:test`, not `test` — the bare form also hits the `launcher` subproject,
   which has no tests and fails the build with "No tests found for given includes"; and Gradle
   compiles test sources once at the *start* of a run, so a test file edited after a run began is
   silently absent from it (a stable test count is the tell).
2. ✅ **Cook invariant** — at most one `Assignment` per `(targetKey, propertyName)` per cue.
   `CueComposerTest`, including the timed-layer re-cook path.
3. ✅ **Precedence** — for both an LTP and an HTP category: layer 1 vs layer 2 vs local,
   asserting later-wins then local-wins. These are the tests whose absence let the accidental
   behaviour survive; `CueAssignmentResolverTest` had no exact-tie case at all.
4. ✅ **Blend/amount** — `OVERRIDE` at amount 0.5, `MAX`, `MIN`, `MULTIPLY`, plus the
   lone-layer identity cases (mixing up from zero, down from full).
5. ✅ **Mask and target restriction** — a masked layer asserts only in-mask properties; a layer
   with explicit `targets` asserts only those, even when the Look covers more.
6. ✅ **Migration golden test** — `state/LooksMigrationTest.kt`, 10 tests. Coverage preservation is
   asserted as "nothing lost, nothing altered" plus one named addition: a `ref:` naming a *palette*
   cannot resolve once the resolver reads Looks, so migration *recovers* that row. (A test artefact,
   not a production window — `runStateMigrations` runs before the show initialises, so a real desk
   never composes a cue in the pre-migration state.) The documented LTP flip is pinned separately,
   with its before *and* after values, so the change is a decision rather than a surprise.

   **Validated by mutation**, because a migration test that merely passes proves nothing. Restoring
   the text-uuid write fails 4 of the 10; restoring `getString` on a blob fails 8. Those are the two
   bugs that reached a real desk database, and this test would have stopped both.
7. ✅ **Sync round-trip** — `sync/ProjectRoundTripTest.kt` and `SyncCoverageTest` pass with
   dispositions recorded for all four new tables; `RichProjectFixture` seeds a bound Look, a
   deferred Look with an element row and an effect, and two layers between them covering every
   optional field non-default.
8. ✅ **Crossfade regression** — existing resolver tests pass unchanged; that is the evidence cook
   did not perturb Layer 4. `ProjectCloneTest` also confirms a `ref:` inside an opaque value string
   still remaps to the clone's own Look.
9. ✅ **Frontend** — `npm run check` (build + 950 tests + lint at zero warnings). New coverage:
   `cueUtils.test.ts` pins all thirteen layer fields through `buildCueInput` field by field, that
   `lookName` is stripped and that `presetApplications` is *not* sent, plus `reorderCueLayers`
   (moves the item, renumbers every `sortOrder`, densifies a gappy list, ignores an out-of-range
   index); `LayersPane.test.tsx` (14) covers enable/disable, amount commit-on-blur/Enter with
   clamping and Escape, remove-and-densify, the read-only mask badge and a reorder handle per
   layer; `store/looks.test.ts` (10) pins the URL/method contract and that `saveLook` sends **only
   the keys it was given** — absent `rows` is what stops a metadata edit clearing the contents.
   `LookRoutesTest` gained toggle, preview and include-by-`lookId`.

   Two notes on what is *not* tested. The dnd-kit drag itself is not drivable with `fireEvent` and
   nothing in this repo drives one, so the pointer sequence is covered by asserting a handle per
   layer plus `reorderCueLayers` directly, rather than by a fake drag that would prove only that
   the mock works. And the amount control is pinned at both ends — a retype to the current value
   sends nothing — because every commit PATCHes the whole cue.
10. ✅ **Session 3a** — `./gradlew :test`, split by package. **The documented two-group split is not
    the whole suite**: `routes.*`+`plugins.*` and `fx.*`/`state.*`/`sync.*`/`models.*`/`midi.*`/`ai.*`
    leave out `auth.*`, `dmx.*`, `fixture.*` (22 test files), `perf.*`, `scripts.*`, `show.*` and
    `update.*` — another ~334 tests. Use three groups, not two.

    New coverage: `ProgrammerLayerSlotCompositionTest` (15 — all four `composeProgrammerOver`
    overrides, both W/A/UV bundling directions, sideband-beats-layer, the `Long.MIN_VALUE` sentinel
    guard; **mutation-validated** against `seq = 0`), `ProgrammerLayerStackTest` (22),
    `ProgrammerLayerStackEffectsTest` (7 — including that a reorder changes no `FxInstance.id`),
    `LookRecordTest` (12), plus layer cases across the Include, Record and Update route tests and
    `CueComposerTest`'s winner tests.

    Tests **deleted** rather than ported, each because its subject is gone: `PresetPreviewSlotTest`
    (contract carried by the stack's preview tests, `assertSame` included),
    `BuildCueAssignmentsForPresetTest` (20 — `CueComposerTest` covers the same ground for layers),
    and three per-preset-owner cases in `ProgrammerOwnershipCollisionTest` — a layer stack cooks
    every layer into **one** slot per key, so "two presets sharing a property release
    independently" has no equivalent and no stale per-layer bookkeeping can strand an entry. The
    locate-vs-layer interaction *was* ported, because `LocateManager` does keep per-target records.

    `LooksMigrationTest`'s two "before the migration" computations are now **frozen goldens** rather
    than live calls to the deleted `buildCueAssignmentsForPreset`. That is the right shape for a
    golden test anyway: it states the old behaviour as a fact to be preserved rather than
    re-deriving it from code that can no longer be wrong.
11. ✅ **Session 3b** — `npm run check` in `lighting-react`: build + **1008 tests** + lint at zero
    warnings (951 at the start of the session).

    New coverage: `LookStack.test.tsx` (16 — the shared component's contract driven by spies, so a
    handler shape that changed breaks it and not the cue's own test), `ProgrammerLookStack.test.tsx`
    (6 — the index→`layerId` translation, including that a preview layer does not shift the ids the
    rows act on), `ProgrammerPane.test.tsx` (6 — **mutation-validated**: removing `forceMount` fails
    two), `lookPresence.test.ts` (9), `LookEditor.test.tsx` (5 — both halves of
    `FU-FE-LOOK-SAVE-GUARD-TEST`), `lookSaveGuard.test.ts` (4), plus layer cases in
    `programmerWsApi.test.ts` and `useRowOwnership.test.ts`. `LayersPane.test.tsx` stays as the
    cue-integration test — it asserts the PATCH payload, which the shared component's test cannot.

    `src/test/backendMock.ts` gained a **stateful** `programmerWs` holder, unlike every other bridge
    there: the programmer's consumers read `getState()` / `layers()` synchronously and then wait to
    be told it changed, so a bare callback holder would fire a notification carrying nothing.
12. ✅ **Against a running desk — done at the head of session 4, and it found two bugs.**

    Run on a **second desk over a copy of the live database** —
    `LIGHTING7_DATA_DIR=<copy> ./gradlew run --args="-port=8414"` — because the live desk's data dir
    is outside the agent sandbox's writable set. That is a better shape than it sounds: the real
    startup migration runs against real data, reversibly. `./gradlew run` builds `lighting-react`'s
    `dist/` into the JAR, so the second desk serves the UI on its own port with no Vite proxy.
    Drive the checklist over REST + WebSocket with Node 24's global `WebSocket`; two traps if you
    redo it, both of which cost time here. curl writes the session cookie with a **`#HttpOnly_`
    prefix**, so filtering all `#` lines drops the only cookie that matters and everything 401s.
    And a WS client must **send `programmer.state`** to prime itself and re-send it after each
    `provenanceState` push, because that is what the real client does — a probe that only listens
    sees no layers and invents three bugs that aren't there.

    **Confirmed working:** the migration on real data, uuids intact (`7849763a-28bd-…`, the exact
    row the pre-fix migration turned to mojibake); a rows-only bound Look, families derived across
    two of them; the pad's `POST /looks/{id}/toggle` applying it with `effectCount: 0` — the case
    the old `FxInstance.presetId` ring could never see; add / patch / move / remove with the server
    renumbering `sortOrder` in place; `blendMode` / `propertyMask` / `amount` / `stomp` all
    round-tripping (so session 4's per-layer UI needs **no** wire work — verified live, not merely
    from the DTOs); Include → edit on stage → Update writing back **only** the changed row
    (`rowsWritten: 1`), so `changedSinceInclude` is right; Record producing a bound Look in the
    library.

    **Two bugs, both fixed here.** (a) `computeProvenance`'s `PROGRAMMER` branch built a bare entry,
    so a layer-won cell answered *the programmer* and never named its Look — 3a wired the `CUE`
    branch and left this one. Fixed via `ProgrammerStore.layerWinnerRankByKey`, decoding the rank
    from the `LAYER_SEQ_BASE` band and reporting only keys the layer actually *won*.
    (b) `programmer.layerState` was a unicast reply only; the assumption that "every layer mutation
    also emits `provenanceState`" is false for a mutation that moves no value, so a layer whose
    `targets` don't match its bound Look's rows left every other tab on a stale list. Fixed with
    `ProgrammerStore.layersFlow`, emitted from `mutateLayers` (not `recook` — `reset()` bypasses
    that path). Both pinned: `ProgrammerLayerTest` +2, `ProgrammerStoreTest` +5.

    Not covered by this route: the live database, and DMX output — the sandbox blocks ArtNet
    broadcast, so a second desk computes values but lights nothing. §13 still needs a rig.
13. ✅ **On the rig — all three core cases closed 2026-08-22**, against the live test desk on 8413
    once it was restarted onto session-4 code. Verified from computed DMX output, which is what the
    composition claims are about; whether the physical heads track it is still a human-eyes
    question, and three extensions remain (see `manual-validation.md`).

    - **`FU-MANUAL-PALETTE-TOURING`** — a bound Look, a cue layering it, GO, then a `PUT` changing
      the Look's colour: output went `255,0,0` → `0,0,255` **with no re-fire**. Use-case 1's whole
      payoff, seen outside a unit test for the first time. Still unseen: two heads (per-fixture
      resolution), a position Look, and — the one worth doing — a mid-crossfade edit, which is the
      `cueFadeWeights` preservation `replaceCueAssignments` exists for.
    - **`FU-MANUAL-LAYER-PRECEDENCE`** — one cue, Bright (255) then Dim (60) on one fixture: the
      fixture sat at **60**, not 255. Ordered override for intensity, not HTP max. Flipping the two
      layers' `sortOrder` flipped the winner. Still unseen: the cross-cue half (both cues live → 255,
      because across cues HTP still governs), which is the *pairing* an operator trips on.
    - **The layer-drag phase check** — two layer-spawned effects running, `FxInstance` ids `[3,4]`;
      after `programmer.moveLayer` the stack order was `[4,3]` and the ids were still `[3,4]`. No
      respawn, so no phase restart. The companion "re-ranked in place" assertion could not be made
      over the wire: `priority` is not on the active-effect DTO (only a derived `isProgrammerBand`),
      so that half stays with `ProgrammerLayerStackEffectsTest`.

    The original entry follows.

    ⬜ **On the rig** (`docs/plans/manual-validation.md`) — edit a Look while a cue depending on it
    is live and confirm the change moves without re-firing the cue. That is use-case 1's payoff, and
    `FU-MANUAL-PALETTE-TOURING` records it as never yet seen on hardware. `FU-MANUAL-LAYER-PRECEDENCE`
    is the new companion check: layered intensity is later-wins, which is the change an operator is
    most likely to be surprised by. 3a adds a third: **dragging a layer must not restart the phase
    of the effects running under it.** That is what the layer-index priority scheme buys, it is
    unit-tested by asserting `FxInstance.id`s don't change, and it is exactly the kind of thing that
    looks fine in a test and wrong on stage.

Run the desk with `./gradlew run` (REST on :8413). Never stop or kill a Gradle daemon — the
live desk *is* one.

## 10. Scope honesty

**Session 1 is evidence, not an estimate any more.** The backend alone — 4 tables, the composer,
the registry, the route file, the rewire of five apply paths, sync at v5, the AI surface and the
migration — filled a whole session: ~1900 insertions and ~2300 deletions across 54 files, plus 6
new ones. It did not leave room for its own frontend, which is why D4 was revised and the library's
UX became session 2.

The test surface was the largest single cost, as predicted, but it landed differently than the
original estimate assumed. Three files were **deleted rather than rewritten**, because unmounting
the palette and preset HTTP routes left them without a subject: `PaletteRoutesTest` (778 lines),
`FxPresetMakeHardTest`, `FxPresetRoundTripTest`. Their coverage does not simply vanish — make-hard
becomes the flatten-layer route and record/include/update are rewritten against layers, both in
session 3, and that is where the replacement tests belong. `PaletteRegistryTest`,
`PaletteRepublishTest` and `PaletteResolverTest` **ported** with only their seeding changed, which is
the useful signal that the behaviour survived the merge intact.

Net after session 1: 1651 tests, all passing. New coverage is `CueComposerTest` (27 — the invariant,
precedence both ways, blend/amount, mask, target restriction, cascade, timed layers) and
`LookRoutesTest` (7, including the layer-FK delete guard end to end).

**The known gap.** Include, Record and Update still call `buildCueAssignmentsForPreset`, so **Include
currently ignores layers**. The plan always scheduled that work for the programmer session (now 3),
and §5.3 is where it lands — but it is a live functional gap in the meantime, not merely unfinished
work, and it is the first thing to fix once the frontend is breathing.

§4.5 (four independent read renderers) remains the most likely thing to slip, now out of session 4.
If it does, the honest move is a fifth session for the Run/Prompt-Book/mobile renderers rather than
shipping session 4 half-done.
