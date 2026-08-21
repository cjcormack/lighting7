# Looks and Layers — replacing FX Presets and named Palettes

> **Status: session 1 (backend) landed 2026-08-21. Sessions 2–4 remain — see §5.**
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
> **Next, and urgent:** session 2, the Look library's frontend. Session 1 unmounted the FX-preset
> and named-palette HTTP routes, so `/presets`, `/palettes/:type` and the busking pads currently
> 404. Include/Record/Update still call `buildCueAssignmentsForPreset` and therefore **ignore
> layers** — that is session 3 (§5.3), as always planned, but it is a live gap until then.
>
> Worse than "ignores layers", and the reason session 3 should not slip: `POST /programmer/include`
> and `POST /programmer/record-palette` are **still mounted** and still read and write
> `DaoPalette` / `DaoPaletteEntry`, while `republishForLookEdit` and `includePaletteIntoProgrammer`
> now resolve through `LookRegistry`, which reads only `looks` / `look_rows`. So recording a *new*
> palette produces a row no consumer can see, and re-recording a migrated one reports "N entries
> written, N cues republished" while the rig does not move — the two tables diverge from that point
> on. Left as-is deliberately (the fix is the session-3 rewrite onto layers, not a patch), but it is
> a data-integrity gap, not just a missing feature.
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

### Session 2 — The Look library's frontend

**This session is not optional polish — the desk's UI is currently broken without it.** Session 1
unmounted the FX-preset and named-palette HTTP routes (their reference mechanism now resolves
through `LookRegistry`, so anything the old CRUD created would be invisible to every consumer). The
frontend still calls them, so today: `/presets` and `/palettes/:type` 404, and **the busking pads
404** because they toggle a preset by id. Around 35 files import
`api/fxPresetsApi` / `api/palettesApi` / `store/fxPresets` / `store/palettes`; that import list is
the real scope of this session, not the three bullets the plan originally carried.

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

### Session 3 — Programmer as a layer stack; the view merge

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
   `make commit-check` rule does not apply). 1651 tests, all passing.
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
9. ⬜ **Frontend** — `buildCueInput` regression test for the layers field; component tests for
   reorder, enable/disable, amount. Session 2.
10. ⬜ **On the rig** (`docs/plans/manual-validation.md`) — edit a Look while a cue depending on it
    is live and confirm the change moves without re-firing the cue. That is use-case 1's payoff, and
    `FU-MANUAL-PALETTE-TOURING` records it as never yet seen on hardware. `FU-MANUAL-LAYER-PRECEDENCE`
    is the new companion check: layered intensity is later-wins, which is the change an operator is
    most likely to be surprised by.

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
