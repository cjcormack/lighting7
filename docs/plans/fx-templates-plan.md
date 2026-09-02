# Effects on templates — a busking pad for a named effect

> **Document status: IN PROGRESS — sessions 1–2 done (`bba3efc`, `a35250e`).** The backend model,
> write boundary, cook, sync wiring and both apply gestures have landed; the two frontend sessions
> (3–4) have not. §11 lists where the landed backend differs from §2–§5, for the sessions that
> read it. The visual design is
> settled and checked in beside this plan at [`fx-templates-design/`](fx-templates-design/INDEX.md)
> — six static artboards: the model, the New template sheet, the Templates view, the busk view,
> the programmer, and everywhere else a template appears. The live canvas at
> <https://claude.ai/code/artifact/5f04a67c-3c6d-4d74-9e4b-f4bd09f4111a> is a convenience copy,
> private to Chris; the checked-in files are the authority. This document is the engineering half:
> the model change, the decisions and their reasons, and the session split.

## 1. Context

The busk view presses **named things from the library** onto a selection. Since the effect pads
were removed (busking-view plan §4a, 2026-09-01) an ad-hoc effect reaches the stage only through
a Look with deferred effects, a cue, or the programmer's `+ Effect`. That leaves a gap an operator
feels immediately: "a slow amber breathe on whatever I have selected" is one named thing, of one
family, with no targets of its own — which is the definition of a template — but a template
cannot hold it. `models/templates.kt` says so by design ("No effects. D7: effects live in a Look or
on a cue, never on a layer"), and six places in the backend assume it by name.

Today the workaround is a Look with zero rows and one deferred effect. It works — the composer
fans a deferred Look effect over the layer's targets, the busk view gives it a pad, the toggle
route puts it on and takes it off — but it lands in the Looks pool rather than the family column,
its authoring is the Look editor rather than a family-native sheet, and the library's two-thing
taxonomy ("a Look composes cues, a template composes values") has no honest place for it.

**The ask** is to let a template hold an effect, so an effect template is authored, listed,
busked and tracked exactly as a value template is. The mechanics mostly exist: deferred fan-out in
`CueComposer.effectsForLayer`, spawning in `EffectSpawner`, the layer stack's toggle, the
per-layer tempo/amount/mask overrides. What is missing is the storage, the decision reversal, and
the surfaces.

## 2. Decisions taken

- **D1 — a template holds a value *or* an effect, never both.** A `Holds` choice sits under the
  family in the sheet and, like the family, is the template's identity — fixed after creation. A
  colour *and* a chase is a Look, which already holds rows plus deferred effects and has its own
  busk pool. This keeps "a template is one named thing" true, and keeps the cook's template arm
  from needing both halves.
- **D2 — one effect per template.** "Amber Breathe" is one thing; two effects together is what a
  Look with deferred effects already is. Enforced by a unique index on `template_effects.template_id`
  and at the write boundary. Recorded as `FU-TMPL-MULTI-EFFECT` if it ever pinches.
- **D3 — an effect template is always generic.** There is no per-fixture effect: an effect fans
  over whatever the layer names, so `template_effects` carries **no** `target_type` / `target_key`
  (unlike `look_effects`, whose deferred/bound split survives for a reason that does not apply
  here). The composer treats it as a deferred effect over the layer's targets, full stop.
- **D4 — the family is the effect's library `category`**, through the same map the speed-master
  usage vocabulary already uses (`dimmer` → Intensity, `colour` → Colour, `position` → Position).
  Beam has no category, so a Beam template cannot hold an effect: the sheet disables the Effect
  choice under Beam and says why. `TemplateProperty`'s closed vocabulary is untouched.
- **D5 — the composer's template arm gains the effect half; nothing else in the cook moves.** A
  template layer already takes a rank (it contributes values); it now also contributes its one
  effect through the existing `effectsForLayer` fan-out, which stays the *single* implementation
  for both cooks (sweep item C8's reason). The `isTemplate` skip in `cookEffects` and the
  `OfLook`-only guard in `cookAll` go. Layer 3/4 ordering, stomp, amount and the effect-priority
  arithmetic are unchanged because a template effect is just one more `LookEffectEntry` in the
  triples.
- **D6 — one entry type.** `TemplateSnapshot` gains `effect: LookEffectEntry?` (target always
  null) rather than a parallel `TemplateEffectEntry`. The name is now slightly wrong and the plan
  renames it to `EffectEntry` in session 1 — a mechanical rename, not a semantic one.
- **D7 — provenance generalises, the invariant does not.** `FxInstance.lookId` becomes a
  `sourceRef` (Look id or template id, with the name carried in the DTO) so `FX running` can say
  *in Amber Breathe* the way it says *in Storm Wash*. `programmerLayerEffectKey` is already keyed
  by layer id and needs nothing.
- **D8 — the speed master is stamped at authoring time; null keeps meaning master 1.** The sheet
  defaults its master select to the project master whose `usage` matches the family
  (`resolveSpeedMasterForCategory` in `lib/speedMasterModel.ts`, client-side with a test, kept
  uncalled since the effect pads went) and **stores that uuid**. The AI's `create_template` needs
  the same lookup server-side — a small function against the bank, with the vocabulary test
  `SpeedMasterUsageVocabularyTest` already pins.
  "By usage" is how the default is *labelled*, not a stored mode: the `null → slot 0` invariant
  the bank and the wire protocol are built on does not move, and this gives the busk plan's D1
  routing its first real caller. Cost, accepted: retagging a master's usage later does not move
  templates already stamped. Recorded as `FU-TMPL-USAGE-RETAG`.
- **D9 — two gestures, the value gestures.** On the programmer strip, **click** mints a
  programmer-band copy of the effect on the selected heads (`ProgrammerOwner.WEB`, the `+ Effect`
  path — yours until recorded, Record writes it onto the cue as an ad-hoc effect, retuning the
  template does not move it); **⌥click** adds a template layer masked to the family (the busk
  pad's press, unchanged). Literals-versus-dependency is exactly the value template's split, so an
  operator learns nothing new.
- **D10 — the busk pad is a toggle, as now.** Press adds the layer, press again removes it;
  presence reads from the layer stack. Effect pads sit in their family column beneath the values,
  under an *Effects* hairline: the column is the family, the hairline is the kind. Within each
  half the library's sort order holds.
- **D11 — *Save as template…* is the effect twin of *New from selection*.** Offered on a
  programmer-owned running effect (not on one a Look or template already owns); it opens a name
  sheet and posts to the **existing** create route with an `effect` body — the client already
  holds the spec. No new backend route.
- **D12 — an effect template is never a colour.** The `tmpl:` colour-reference rule already
  requires *generic, one row, Colour*; an effect template has no rows and stays excluded on both
  sides (`isOfferable` client-side, `resolveColourGeneric` server-side). The other direction is
  allowed and useful — an effect template's colour parameter may name a value colour template —
  and a template naming its own uuid is refused at the write boundary.
- **D13 — sync: additive, no format bump.** `template_effects` is portable show content embedded
  inline in `TemplateJson` as `effect: TemplateEffectJson? = null`. A new optional field with a
  null default needs no `formatVersion` bump per the rule in `SyncDtos.kt`. `ExportUuidRemapper`
  rewrites every uuid string in the export textually, so `speedMasterUuid`, `rateSpeedMasterUuid`
  and a `tmpl:` reference inside `parameters` are remapped with no special handling — the same
  reason `LookEffectJson` needs none.

## 3. The model

### 3.1 Schema

One new table. Nothing on `templates` or `template_rows` changes.

`template_effects` — one row per effect template, zero for a value template:

| column | type | meaning |
| --- | --- | --- |
| `template_id` | FK → `templates`, **unique** | D2: at most one effect |
| `effect_type` | varchar | `FxRegistry` id |
| `category` | varchar | library category; D4 derives the family from it |
| `property_name` | varchar, null | as `look_effects` |
| `beat_division` | double | |
| `blend_mode` | varchar | |
| `distribution` | varchar | |
| `phase_offset` | double | |
| `element_mode` / `element_filter` / `step_timing` | as `look_effects` | |
| `parameters` | text (JSON map) | may contain `tmpl:` refs (D12) |
| `speed_master_uuid` | uuid, null | D8: stamped at authoring; null = master 1 |
| `rate_speed_master_uuid` | uuid, null | |
| `uuid` | uuid | sync identity |

No `target_type` / `target_key` (D3), no `sort_order` (D2).

### 3.2 The write boundary

`validateTemplateRows` becomes `validateTemplateContents(rows, effect)`:

1. **Exactly one of** rows-non-empty / effect-non-null (D1). Both empty stays invalid, as now.
2. **Exactly one family**, derived from rows *or* from `effect.category` via the D4 map. A
   category with no family (`controls`, or anything unmapped) is refused by name — this is where
   "Beam cannot hold an effect" lives server-side.
3. `effect_type` must resolve in `FxRegistry`, and its registration's `category` must equal the
   stored `category` (the library is the authority; the column is a denormalisation for the
   family derivation and the list DTO).
4. No `tmpl:` parameter may name the template's own uuid (D12). Other refs are validated as
   `look_effects` parameters are today.
5. The four existing rules for rows are unchanged.

`performTemplateCreate` and the update route share it, so the AI's `create_template` cannot write
a template no consumer can resolve.

### 3.3 The read side

- `TemplateSnapshot.effect: EffectEntry?` (D6). `TemplateRegistry.snapshot` loads it beside the
  rows; `versionFor` is unchanged because the template's generation already bumps on any write.
- `TemplateSummary` / `TemplateDto` gain `effect: TemplateEffectDto?` and a derived
  `kind: "value" | "effect"`. `family` is derived as now, from rows or effect. `isGeneric` is true
  for an effect template.
- `CueComposer`: `LayerContent.OfTemplate` exposes `effects` (zero or one entry); `cookAll` and
  `cookEffects` call `effectsForLayer` for both arms (D5). The "layer names no targets" warning
  applies to an effect template exactly as to a deferred Look effect.
- `ProgrammerLayerStack.build` and `projectCuesHelpers` stop assuming a template never reaches
  them; the `lookId` stamp becomes the D7 `sourceRef`.
- `POST /templates/{id}/apply` grows an arm: for an effect template it spawns one programmer-band
  instance per selected head through `EffectSpawner` (D9) and returns the instance ids alongside
  the existing `applied` / `skips` shape; the value arm is untouched.
- `toggle` already spawns effects for any layer whose content has them; its `effectCount` reply
  simply stops being always-zero for templates. `TemplateRoutesTest` line 325 pins the old value
  and is rewritten, not deleted.
- The delete guard's response gains `runningCount`: programmer layers tracking the template
  right now. `layerCount` keeps meaning cue layers.

## 4. UX

Grep-able summary of what `fx-templates-design/` draws; the artboards win on layout and copy.

- **New template** (`NewTemplate.dc.html`): family buttons as today; then a bordered **Holds —
  one only** segment, *Value* | *Effect*, with the caption "A value is resolved per head; an
  effect runs on every head the layer names … For a value and an effect together, record a Look";
  then Name beside **Speed master** (Fade is hidden for an effect — it has no arrival); then the
  effect editor: an *Effect* select filtered to the family ("7 colour effects — the family is the
  filter"), *Distribution*, the *Speed* beat-division toggles, *Parameters* (colour parameters
  offer the value colour templates), a collapsed *Advanced* (blend, phase offset); then **Runs
  on** in place of *Resolves to*: a head count, one sentence, and a one-bar preview strip.
  Footer unchanged: Cancel / Create template. Edit mode locks Holds as it locks the family.
- **Templates view** (`Library.dc.html`): one list, one family bar. An effect row shows a
  family-tinted **wave tile** in the swatch slot and the subtitle *Effect · Colour Pulse · ½ beat ·
  M2 Chases*. Subtitle copy for the page gains two words: "Named values and effects you build
  looks and cues out of." Row menu unchanged. The faint row wash on the artboard is optional.
- **Busk view** (`Busk.dc.html`): per family column, values, then an *Effects* hairline, then
  effect pads (wave glyph before the name; detail *Colour Pulse · ½ · M2*). Same pad component,
  same presence ladder, same long-press-to-edit. Beam has no hairline. Looks pool unchanged. Rail
  caption reworded to what a usage badge now does: "names the family whose effect templates
  default to this master."
- **Programmer** (`Programmer.dc.html`): the strip shows effect chips after a hairline when the
  cell selection is in their family; tooltip *Click to run a copy of "Amber Breathe" on the
  selection · ⌥click to add a layer that tracks it*. `LookNameBadge` gains the wave beside the
  palette glyph for an effect template, everywhere it is drawn (layer row, scope band). In *One
  layer* scope the grid is a read: live values ringed in the layer colour with the wave and the
  division in the cell; the notice reads "runs one effect for the whole family — the grid shows
  the live value under it" with the *Edit template* link. `FX running` home badge says *in Amber
  Breathe · layer 3*; a programmer-owned row's menu offers *Save as template…*. `+ Effect` stays
  disabled on a focused effect-template layer with the reworded reason.
- **Elsewhere** (`Elsewhere.dc.html`): cue editor layer row and its overrides (tempo, amount,
  asserts) unchanged in shape; add-layer picker hint "One value or effect each. These take their
  fixtures from the layer."; the effect colour picker's Templates row offers value colour
  templates only (D12); the delete guard gains "and is running on the programmer now" when
  `runningCount > 0`; the command palette keeps one *New Template* action; the Looks cross-link
  gains "or effect".

## 5. Implementation — four sessions

Backend first, because every frontend surface reads the new DTO fields, and the composer change is
the part that can break a show.

### Session 1 — the model and the cook (lighting7) — done, `bba3efc`

1. `models/templates.kt`: `DaoTemplateEffects` + DAO; rewrite the "No effects" KDoc paragraph
   into the D1–D3 rule. `Schema.kt` `ALL_TABLES`.
2. `SyncCoverageTest.dispositions`: `Portable("templates", "effect")`. `SyncDtos.kt`:
   `TemplateEffectJson`, `TemplateJson.effect`. `ProjectExporter` / `ProjectImporter` wiring,
   `ExportUuidRemapper` for the three uuid carriers. `RichProjectFixture` gains an effect template
   with a **non-default** value on every field, including a `tmpl:` parameter and a stamped master.
   Extend `ProjectRoundTripTest`; the clone path follows for free.
3. `routes/projectTemplates.kt`: `validateTemplateContents` (§3.2), DTO fields (§3.3), the family
   derivation from `category`, the delete guard's `runningCount`. `ai/AiToolSchemas.kt`
   `create_template` gains an optional `effect` object; `AiService` prompt text says a template
   may be an effect.
4. `fx/TemplateRegistry.kt`: snapshot loads the effect; `LookEffectEntry` → `EffectEntry` (D6).
5. `fx/CueComposer.kt`: D5. Delete the two "templates hold no effects" comments and the skip.
   `fx/ProgrammerLayerStack.kt`, `routes/projectCuesHelpers.kt`, `fx/FxInstance.kt`,
   `fx/EffectDto.kt`: D7 `sourceRef`.
6. Docs: `lighting-composition-model.md` §"Looks, templates and layers" (the D7 sentence and the
   `tmpl:` rules), `fx-engineering.md`, `sync-engineering.md` §"How to add a new table", and the
   two `CLAUDE.md` sentences that say a template holds no effects.
7. Tests: composer — a template layer spawns its effect over the layer's targets, ranks unchanged
   for the layers above it, stomp from a higher layer suppresses it, a template with no targets
   warns and spawns nothing; write boundary — every §3.2 rule, including self-reference and Beam;
   registry — snapshot carries the effect and the version bumps on an effect edit; sync — the
   round trip and the remap; `TemplateRoutesTest` — toggle reports a non-zero effect count for an
   effect template, zero for a value.

Gate: `./gradlew test` green; `SyncCoverageTest` is what proves step 2 was not skipped.

### Session 2 — the gestures (lighting7) — done, `a35250e`

1. `routes/templateApply.kt`: the effect arm (D9). One instance per selected head, `WEB`-owned,
   stamped from the template's spec with no override; response carries the instance ids.
2. `fxState` / `fxChanged` DTOs carry the D7 source name so the home badge can be drawn.
3. Tests: apply on an effect template mints programmer-band instances the FX list attributes to
   the programmer, Record writes them as ad-hoc effects, and a later template edit does not move
   them; apply on a value template is byte-for-byte what it was.

### Session 3 — library and editor (lighting-react)

1. `templatesApi.ts` types: `effect`, `kind`, `runningCount`.
2. `TemplateEditor.tsx`: the Holds segment; the effect branch reuses `EffectParameterForm`'s
   pieces with no target (beat division / cycle length, speed master defaulting per D8,
   distribution, parameters, Advanced) and drops the target-bound rows (element mode, target
   setting/property/option, start-on-beat). The effect select is `FxRegistry` filtered by the
   family's category. Fade hidden for an effect. `TemplateRunsOn` replaces `TemplateResolvesTo`
   for the effect branch: head count from the existing capability filter, one bar previewed
   client-side from the spec (a preview, not a promise — the artboard's caption says so).
3. `TemplateListRow.tsx`: wave tile via a `TemplateValuePreview` branch; `describeShape` gains
   the effect grammar. Page subtitle copy.
4. `NewTemplateFromEffectSheet.tsx` (D11), opened from `ProgrammerFxList`'s row menu on a
   programmer-owned effect. Posts to the existing create route.
5. Tests: `describeShape` for an effect; the editor's validity rules (name + exactly one of rows /
   effect; Effect disabled under Beam); the runs-on head count against a fixture list.

### Session 4 — busk, programmer, and everywhere else (lighting-react)

1. `BuskPools.tsx`: `TemplateColumns` splits each family's pads at a hairline (D10); pad face gets
   the wave glyph and the effect detail line; `templateSwatch` unchanged (an effect has none).
   Rail caption reworded.
2. `LookNameBadge.tsx`: wave for an effect template (needs `kind` on the badge's input, threaded
   through `describeStackSource`). `ProgrammerScopeBand`, `LayerRow` and `CueDetailContent` pick
   it up for free.
3. `TemplateStrip.tsx`: effect chips after a hairline; click calls the apply route's effect arm,
   ⌥click toggles as now; tooltip copy.
4. `LayerRowNotices.tsx` copy for an effect template; `ProgrammerAddEffect.tsx` tooltip reworded;
   `ProgrammerFxList.tsx` home badge from the D7 source name; `LayerPicker.tsx` hint; the delete
   dialog's `runningCount` sentence; `Looks.tsx` cross-link; `CommandPalette` keywords gain
   `effect`, `chase`.
5. Grid read in *One layer* scope on an effect-template layer: live value ringed, wave and
   division in the cell. This is the one piece of new grid rendering and the most likely to slip
   to a follow-up if it fights `ProgrammerGrid`'s cell model — if it does, ship the notice and the
   ring without the in-cell glyph and record it.
6. Tests: `lookPresence` for a template layer whose content is an effect; the strip's
   click / ⌥click dispatch; the column split keeps library order within each half.

## 6. Migration

Nothing to migrate — one additive table, created by the startup schema pass. Existing templates
are value templates with no `template_effects` row, which is exactly today's behaviour. A desk
restart is required for the table to appear. Sync needs no `formatVersion` bump (D13); an older
reader ignores the optional field and imports the template as a value template with no rows,
which the write boundary would refuse — so the importer must skip, with a logged warning, an
effect template arriving from a newer writer rather than write an empty one. That is the one
cross-version case worth a test.

## 7. Explicitly out of scope

- **More than one effect per template** (D2). A Look is the container for several.
- **Per-fixture effect templates** (D3). An effect that differs per head is a Look with bound
  effects.
- **Value and effect on one template** (D1).
- **Beam effects.** The library has no beam category; adding one is an FX-library change, not a
  template change.
- **A stored "by usage" mode on the speed master** (D8). Stamping keeps the null invariant.
- **Momentary / flash pads** — still `FU-BUSK-MOMENTARY`.
- **MIDI surface bindings for template pads** — the binding vocabulary is its own piece of work.
- **The Looks pool changing.** A Look with deferred effects stays where and what it is.

## 8. Follow-ups to record

Recorded in `followups.md` on landing (`FU-TMPL-FX-EDIT-NO-RETIME` and
`FU-PROG-STALE-SOURCE-NAME` were added too, both found in the doing):

- `FU-TMPL-MULTI-EFFECT` — Trigger: an operator keeps making Looks that are exactly two deferred
  effects of one family with no rows.
- `FU-TMPL-USAGE-RETAG` — Trigger: retagging a master's usage and expecting existing effect
  templates to follow it.
- `FU-TMPL-GRID-FX-CELL` — only if session 4 step 5 ships without the in-cell glyph.
- `FU-TMPL-CLICK-GROUP-PARTIAL` — recorded in session 2. Trigger: a click on a mixed group spawns
  nothing where ⌥click lights the capable heads. Absorbs the group-key skip label, the overlapping-
  target duplication, and the two capability models the click arm now chains.

Gates in the existing index worth reading before starting: `FU-FE-SHARED-LOOK-EDIT-GUARD`
(Ready) is the shared-edit guard an effect-template edit wants when the template is tracked by
several cues — this plan assumes the same guard, so landing that item first would be cheaper than
special-casing it here; `FU-FE-USE-TARGET-PROPERTIES` fires on a 6th consumer of fixture/group
property lookup — the *Runs on* head count should reuse `BuskingView`'s capability filter rather
than add one.

## 9. Verification

Unit: the per-session lists above. Desk checks, none run:

1. Create *Amber Breathe* (Colour, Effect, master by usage) → it appears in the Colour column of
   the busk view under the hairline, and in the Colour tab of the Templates view with the wave
   tile.
2. Select Front Wash on the busk view, press the pad → the effect runs on the four heads on M2's
   tempo; press again → it stops; the presence dot follows the layer stack, not the effect list.
3. Retune M2 → the running effect retimes. Edit the template's beat division → re-press the pad
   and it runs at the new division; the *live* instance keeps its old one until then
   (`FU-TMPL-FX-EDIT-NO-RETIME`, inherited from deferred Look effects). A programmer-band copy
   minted by a strip click never follows the template.
4. In the programmer, ⌥click the chip with two heads selected, then Record → the cue holds a
   template layer; GO on that cue runs the effect; the cue editor's layer panel shows tempo and
   amount overrides that work.
5. Click the chip → *FX running* shows a `programmer band` row; *Save as template…* on it creates
   a second template with the same settings; Clear releases the copy.
6. Delete a template that is tracked by a cue and running on the programmer → the guard names
   both; *Delete anyway* stops it everywhere.
7. Export → import a project holding an effect template with a `tmpl:` colour parameter and a
   stamped master → the effect, the reference and the master survive the uuid remap; clone the
   project → same.
8. Under Beam in the sheet, the Effect choice is disabled and the reason is visible.

## 10. Scope honesty

Guessed, to be verified in-session: whether `EffectParameterForm` can be hoisted to render with
no target without a refactor of its gating props (session 3 step 2 — if not, a sibling
`TemplateEffectForm` sharing its pieces is acceptable and the artboard does not care); whether the
in-cell wave and division survive `ProgrammerGrid`'s cell model (session 4 step 5, with its exit
recorded); whether the server-side usage lookup for `create_template` is worth its own function
or the AI tool simply takes an explicit master and leaves the default to the sheet (session 1
step 3 — the client stamps either way, which is how the busk plan's D1 already works). The
decision most likely to be revisited is D9's plain
click: if a copy-on-click proves surprising beside a pad that toggles, the fallback is "both
gestures add a layer" and the copy path is *Save as template…* alone — the sticky note on the
canvas says the same.

## 11. Where the landed backend differs from §2–§5

Session 1 only. Kept because sessions 2–4 read this document as their brief; the reasoning is in
`bba3efc` and the durable half is in `docs/lighting-composition-model.md`
§"A template holds a value *or* an effect" and `docs/sync-engineering.md` §"Version 8".

| § | Says | Actually |
| --- | --- | --- |
| D7 | `FxInstance.lookId` becomes a polymorphic `sourceRef` | `FxInstance.source: LayerSource?`. **`EffectDto` keeps `lookId` meaning a Look** and gains `templateId` + `sourceName` — read those |
| D13 | No `formatVersion` bump | v8, `minReader` 5, in `ProjectImporter`'s gate constants too. No FE action |
| D4 | Beam has no category, so the sheet disables Effect under Beam | `composite` and `controls` are refused as well; the sheet must disable on all three |
| §3.2 | Validate the resulting (post-write) contents | Only what the request sends, plus a separate Holds check — `TemplateInput.effect` needs **no** `effectPresent` flag |
| §3.3 | `TemplateSummary` / `TemplateDto` gain `effect` + `kind` | Server-side there is only `TemplateDto`; it gains `effect`, `kind` and no `runningCount` — that is on the delete guard's 409 body |
| §6 | The importer must skip an effect template from a newer writer | Dropped; the v8 gate makes it unreachable |

Session 2 rows:

| § | Says | Actually |
| --- | --- | --- |
| §5 s2.2 | `fxState` / `fxChanged` carry the D7 source name | Already landed in session 1 — `FxStateOutMessage` carries `EffectDto` directly, and `toEffectDto` is its one builder. No change |
| D9, §5 s2.1 | The clicked copy is provenanced to the template | **No `source` at all.** Stamping it would make `captureCurrentState` (which forks on `source`) rebuild a detached copy as a *tracking* layer, and would take it out of D11's *Save as template…* |
| §5 s2.1 | One instance per selected **head** | One per **target ref**, groups preserved — `effectsForLayer`'s fan-out, so click and ⌥click agree on a group selection |
| §3.3 | Returns the ids "alongside the existing `applied` / `skips` shape" | The fields are `written` / `skipped`; the new one is `effectIds`. `written` stays 0 for an effect template |
| — | (unstated) | The arm needs its own capability check — `FxTargetFactory` never fails, so `"rgbColour"` resolves to a `ColourTarget` on a hazer. `groupSupportsProperty`'s dispatch moved to `fixturesSupportProperty` in `fixture/group/GroupCapabilities.kt` and is shared rather than copied |
