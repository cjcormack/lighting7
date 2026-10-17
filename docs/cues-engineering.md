# Cues Engineering Documentation

This document describes the Cue system — named states built as an ordered stack of **Look layers** plus the cue's own local values and ad-hoc effects. See `lighting-composition-model.md` §"Looks and layers" for how the stack is composed.

> **See also**: [lighting-composition-model.md](lighting-composition-model.md). Cues contribute
> at **Layer 4** (property assignments) and own the ad-hoc effects they
> spawn at **Layer 3**. Cue-owned effects receive a derived priority so composition across
> restarts and reapplies is deterministic. The `stomp` flag (see below) removes other cues'
> ad-hoc effects when a stomping cue applies and overlaps their targets.

## Overview

A **Cue** is a named, project-scoped entity that captures a complete lighting look:
- **Layers** — an ordered list of Looks to apply, each with its own targets, property mask, blend
  mode and amount. Looks are read fresh at apply time, so editing a Look moves every cue layering
  it. Later layers override earlier ones for the same fixture and property, whatever the attribute;
  the cue's own local values win over every layer.
- **Ad-hoc effects** — manually applied effects belonging to no Look, stored as full inline effect definitions.
- **Scenery** — scene elements the cue moves on GO, each on its own clock (stage-view plan session 8;
  `cue-stacks-engineering.md` §"Scenery").
- **Events** — one-shot triggers (a confetti cannon's tubes) the cue fires a set time after GO into it,
  while the desk is armed (stage-view plan session 9; §"Cue events" below).

A cue used to carry a **positional colour list** too (`palette` + `updateGlobalPalette`), which its
effects indexed as `P1` / `P2` / `P*` and which cascaded global → stack → cue. That whole grammar is
gone: an effect parameter names a **colour palette** instead (`tmpl:{uuid}`, see
`fx/TemplateColourSource.kt`), which has one meaning wherever it is read and needs no per-cue scope.

Key behaviours:
- **Multiple concurrent cues**: Applying a cue adds it alongside other running cues (not replace).
- **Re-apply**: Applying a cue that is already running refreshes it (stops and re-starts its effects only).
- **Replace all**: Apply with `?replaceAll=true` to stop all other running cues first.
- **Stop**: Use the stop endpoint to remove a specific cue's effects without affecting others.
- Create from the current live FX state
- Duplicate within active project, copy to another project
- Deleting a Look is blocked if any cue layer references it (`LOOK_IN_USE`), unless forced

## Data Model

### Database Tables

```
cues — what a cue *is*; where it sits in a stack is its entry's (cue model plan, session 1)
├── id (auto-increment PK)
├── name (varchar 255)
├── project_id (FK → projects)
├── fade_duration, fade_curve (Duration; nanoseconds on disk)
├── notes, stomp
└── uuid

cue_stack_entries — one row of a stack's list (cue-stacks-engineering.md §"Database Tables")
├── id (auto-increment PK)
├── stack_id (FK → cue_stacks)
├── cue_id (FK → cues, nullable — null exactly when kind is MARKER)
├── kind (CUE | MARKER), label (a marker's text)
├── ordinal (double — the list's order, ties by uuid: `ENTRY_ORDER`)
├── cue_number, cue_number_auto
├── auto_advance, auto_advance_delay (the follow; Duration, nanoseconds on disk)
└── uuid

cue_layers
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── look_id (FK → looks, nullable)        ┐ exactly one of the two: a layer applies a Look
├── template_id (FK → templates, nullable) ┘ or a template
├── sort_order (int, default 0)
├── enabled (bool, default true)
├── targets (JSON: List<CueTargetDto>)
├── property_mask (varchar, nullable — I/P/C/B mask, e.g. "INTENSITY,COLOUR")
├── blend_mode (varchar, default OVERRIDE)
├── amount (double, default 1.0)
├── stomp (bool, default false — per-layer stomp, see lighting-composition-model.md §Stomp)
├── speed_master_uuid, rate_speed_master_uuid (nullable — override the layer's effects' masters)
├── delay (Duration, nullable — delayed application; nanoseconds on disk)
├── interval (Duration, nullable — recurring application)
├── random_window (Duration, nullable — randomisation for recurring)
└── uuid

cue_ad_hoc_effects
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── target_type (varchar 50)
├── target_key (varchar 255)
├── effect_type (varchar 255)
├── category (varchar 50)
├── property_name (varchar 255, nullable)
├── beat_division (double)
├── blend_mode (varchar 50)
├── distribution (varchar 50)
├── phase_offset (double, default 0.0)
├── element_mode (varchar 50, nullable)
├── element_filter (varchar 50, nullable)
├── step_timing (boolean, nullable)
├── parameters (JSON: Map<String, String>)
├── delay (Duration, nullable — delayed application; nanoseconds on disk)
├── interval (Duration, nullable — recurring application)
├── random_window (Duration, nullable — randomisation for recurring)
├── sort_order (int, default 0)
├── speed_master_uuid, rate_speed_master_uuid (nullable)
└── uuid

cue_triggers (script hooks only)
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── trigger_type (enum: ACTIVATION, DEACTIVATION — a delayed or recurring hook is an
│                 ACTIVATION with timing; a write naming DELAYED or RECURRING is normalised)
├── delay (Duration, nullable — fire this long after activation; nanoseconds on disk)
├── interval (Duration, nullable — re-fire at this interval)
├── random_window (Duration, nullable — randomisation window)
├── script_id (FK → scripts — required)
├── sort_order (int, default 0)
└── uuid

cue_events (stage-view plan session 9 — see §"Cue events")
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── patch_id (FK → fixture_patches)
├── trigger (varchar 64 — the trigger's name on its fixture type, e.g. output1)
├── offset (Duration — how long after GO it fires; nanoseconds on disk)
├── sort_order (int, default 0)
├── uuid
└── unique(cue_id, patch_id, trigger) — a tube fires once per cue
```

Cue names are free-form and **not** unique. The old `unique(project_id, name)` index predated
cue stacks; with a project owning many stacks, two stacks may legitimately both hold a
"Blackout". Cues are identified by `id` locally and by `uuid` across cloud sync. Cue *numbers*
are an entry's and remain unique per stack via the partial index `uq_entry_number_per_stack` (a
marker entry takes none); it replaced `uq_cue_number_per_stack` when the startup migration moved
the list onto entries (`state/CueModelMigration.kt`).

### Key Design Decisions

- **Separate child tables with FKs** rather than JSON columns for layers and ad-hoc effects. This provides referential integrity (FK from a layer to its Look), simpler queries for usage counts — the delete guard is a plain indexed FK query where the named-palette era could only scan opaque value text — and proper normalization.
- **Targets stored as JSON** within `cue_layers` because each application has a small, variable-length list of targets that doesn't benefit from its own table.
- **Parameters stored as JSON**, matching `cue_ad_hoc_effects` and `look_effects`.
- **A write keeps its children's uuids** (show-cue-editing plan P3, §3.4). Every cue write — the
  REST `POST`, `PUT` and `PATCH`, Record's `CREATE` and `UPDATE_EXISTING`, and the AI tools'
  `create_cue` and `build_cue_stack` — goes through `writeCueChildren` (`routes/projectCuesHelpers.kt`),
  which reconciles each child list against what is stored rather than deleting it and creating it
  again: a row is matched by `(targetType, targetKey, canonical property)`, a layer by the record it
  applies (`lookId | templateId`), an effect by `(targetType, targetKey, canonical property,
  effectType)` and a hook by `(triggerType, scriptId)`. `pairByKey` pairs the n-th incoming item of
  a key with the n-th stored one in `sortOrder` order, so a key held twice — a Look layered twice at
  two delays — pairs deterministically. A survivor is updated in place and keeps its `uuid`;
  everything else about it is the incoming item's, and the rest are inserted or deleted. So a
  one-value edit is one changed record in the sync diff, and `CueAdHocEffectDto.uuid` (read only,
  ignored on write) names one effect across writes. An effect swapped for another type on the same
  property is a removal and an addition, as on a Look (`writeLookChildren`, its twin). A `PATCH`
  replaces only the lists it carries. Record's `UPDATE_EXISTING` pairs the recording against the
  rows, effects and layers it replaces (in scope and mask), so a re-recorded value keeps its row;
  `MERGE`, and with it Update, already upserted in place, and Update's layer diff keeps a layer by
  its id. `deleteCueChildren` is only for a cue being deleted. A copy into another project, a clone
  and the importer still mint or carry uuids as they always did.

## REST API

All endpoints are scoped under `/api/rest/projects/{projectId}/cues`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/{projectId}/cues` | List all cues for a project, by name, each with its `entries` and `usedBy` — `{pads, slots, bindings}`, what else names it, the Cues table's *Used by* (cue model plan session 5). One cue's read leaves `usedBy` null |
| POST | `/{projectId}/cues` | Create a new cue (current project only); without `cueStackId` it is loose |
| GET | `/{projectId}/cues/{cueId}` | Get cue details; `?entryId=` picks the place the place fields describe |
| PUT | `/{projectId}/cues/{cueId}` | Update a cue's content (current project only); its number and follow are the entry's |
| DELETE | `/{projectId}/cues/{cueId}` | Delete a cue (current project only): 409 `CUE_IN_USE` while anything names it, `?force=true` to take those with it (§"Delete") |
| POST | `/{projectId}/cues/delete` | `{cueIds, force?, dryRun?}` — several at once, all or none (§"Delete") |
| POST | `/{projectId}/cues/{cueId}/duplicate` | `{stackId?}` — a copy of the cue in this project (current project only): its rows, layers, effects, hooks, notes, scenery and events, named `<name> (copy)`, appended unnumbered to the **end** of `stackId`'s list, or loose without one (show-cue-editing plan D12). Answers 201 `{cueId, name, stackId?, entryId?}`; 400 for a stack of another project or a separator |
| POST | `/{projectId}/cues/copy-from` | `{fromProjectId, cueIds, stackId?, after?, dryRun?}` — cues of any other project copied into this one (current project only), loose or each a new unnumbered entry in `stackId` (after `after`, else at the end). Answers `{copied: [{fromCueId, cueId, entryId?}], unresolved: [{problem, in}], stackId?, looksCopied, looksLinked, palettesCopied, palettesLinked}`, 201, or 200 for a `dryRun`, the same run rolled back. 400 for no cues, this project as the source, a separator, or an `after` not in the stack; 404 for an unknown project, cue or stack (§"Copying") |
| POST | `/{projectId}/cues/{cueId}/apply` | Take a **loose hold** on the cue (current project only) — live above every stack, newest press on top. Query param: `replaceAll=true` lets every other loose hold go first. |
| POST | `/{projectId}/cues/{cueId}/stop` | Let the cue's loose hold go; it comes off stage unless a stack still holds it |
| POST | `/{projectId}/cues/{cueId}/update-stage` | `{dryRun?}` — *Update stage*: a live cue's stored rows and untimed effects to the stage over the cue's own fade (current project only, both roles). Answers `{rows, effects: {edited, stopped, started}, fadeMs, curve}`; 409 `CUE_NOT_LIVE` for a cue nothing holds. See [cue stacks](cue-stacks-engineering.md#edited-since-go-and-update-stage) |
| POST | `/{projectId}/cues/from-state` | Create cue from the current FX state |

Writing the *programmer* into a cue lives outside this namespace, under
`/api/rest/programmer` — see [the composition model](lighting-composition-model.md#record--include--update):

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/programmer/record` | Write the programmer into a cue (`CREATE`/`MERGE`/`REMOVE`/`UPDATE_EXISTING`, optional I/P/C/B mask) |
| POST | `/programmer/include` | Load a cue's layers + rows + FX, or a Look's rows and effects (a deferred effect on `targets`, else the desk selection), into the programmer for editing |
| POST | `/programmer/record-look` | Record the programmer into a Look — `CREATE`/`MERGE`/`REMOVE`/`UPDATE_EXISTING`, with an explicit I/P/C/B mask |
| POST | `/project/{id}/looks` … `/looks/{lookId}` | Look CRUD, banked by derived attribute family; delete guarded by `LOOK_IN_USE` |
| POST | `/programmer/update` | Write programmer edits back — to the include target, or to cues named from the Mode B checklist |

`POST /{projectId}/cues/{cueId}/snapshot-from-live` was removed in the programmer redesign's
Session 3. Capturing the stage is now `POST /programmer/record { source: "STAGE_SNAPSHOT",
mode: "UPDATE_EXISTING", cueId }` — the same capture, plus the programmer overlay the old route
could not see (it read Layer 4 only, so anything busked was silently dropped), and preserving
triggers and timed effects the old route left to chance.

### Apply Semantics

An apply is a **loose hold** (cue model plan D6, D10; `cue-stacks-engineering.md` §"Holds"), the
same as a busk cue pad, a cue slot, MIDI `FireCue`, the hand and the AI's `apply_cue`. Through the
hold registry:

1. If the cue is not live, its **first hold brings it on**, exactly as a GO would: the layer stack
   is cooked to one contributor per (fixture, property) and published as Layer 4 rows, each layer's
   effects are spawned **in layer order**, each ad-hoc effect from its stored definition, its stomp
   applied, its timed effects and triggers started, and its events fired (D15). Every new
   `FxInstance` is tagged with the cue's ID.
2. If something already holds it — a stack, or an earlier press — nothing on stage moves: the press
   only restamps it to the top of the loose band.
3. `replaceAll=true` first lets every other loose hold go; stacks are untouched.

### Stop Semantics

When a cue is stopped via `POST /{cueId}/stop` (or `stop_cue`, or a second pad or slot press):
1. Its **loose hold** is let go. If that was its last hold, the cue comes off — triggers stopped,
   effects removed, its rows dropped (a snap).
2. If a stack still holds it, it stays on stage at that stack's priority. A stop never moves a
   stack's playhead; stopping the stack is the stack's own gesture.
3. Other running cues are unaffected.

Deleting a cue drops **every** hold it has (`holds.dropAll`).

### Delete

Deleting a cue is its own operation (cue model plan D1, D8; `routes/cueDelete.kt`), never a side
effect of removing an entry or deleting a stack.

- **Refused while anything names it.** `DELETE /cues/{id}` on a cue with an entry, a busk pad, a
  cue slot, a prompt-book anchor or a `FireCue` binding is 409 `CUE_IN_USE`, `{error, code, uses}`,
  each use listed (`CueUsesDto`: `entries` `{stackId, stackName, entryId, cueNumber}`, `pads`,
  `slots`, `anchors`, `bindings`). A loose, unused cue deletes straight away.
- **`?force=true`** takes the cue with everything that names it: every entry (each stack renumbered,
  a live one detached as Remove from stack detaches it), the anchors on them, its pads and slots,
  its children, scenery and events, and every hold. A `FireCue` binding is left to report the cue
  missing, as a binding to a deleted cue always has (`FU-CUE-BINDING-SWEEP`).
- **Several at once:** `POST /cues/delete {cueIds, force?, dryRun?}` answers `{deleted, uses}`; any
  cue in use without `force` is 409 `CUE_IN_USE` and nothing is deleted. `dryRun` deletes nothing and
  lists the uses — what a confirm shows.
- **The confirm** is `CueDeleteDialog` (frontend `components/cues/CueDeleteDialog.tsx`,
  show-cue-editing plan session 3), from the cue action set on the Show view and the Cues table: a
  dry run lists each cue's uses, one confirm for several, then `POST /cues/delete` with `force`; it
  says that a binding stays and reports its cue missing.
- **A stack delete leaves its cues** (`DELETE /cue-stacks/{id}`): its entries, anchors and set go,
  and it answers `{looseCueIds, deletedCueIds}`. `?deleteLooseCues=true` (D17) deletes, as a forced
  delete would, the cues it left in no stack — and only those; a cue another stack still holds stays.
  The Show view's confirm offers it unticked, listing those cues' pads and slots from a dry run.

### Colour resolution

Every effect, wherever it is spawned from, gets the **same** colour source:

```kotlin
resolveColourSource = templateColourSource(state.show.templateRegistry)
colourSourceVersion = { state.show.templateRegistry.versionFor(refs) }
```

`refs` is the set of palette uuids *this* effect's parameters name (including its registration's
declared defaults) — `createEffectWithTemplates` computes it once and uses it for both the pre-warm
and the version. Scoping it is what keeps an edit to one palette from re-resolving every colour
parameter on the desk, and an effect naming no palette from re-resolving at all. A palette *list*
change (create / rename / delete) still moves every scoped version by one, because an effect is
allowed to name a uuid that has no palette yet.

There were two `createEffectInstance` variants until the positional list went — a cue-scoped one
resolving `P1` against `getCuePalette(cueId) ?: getPalette()`, and a second one Include needed
because that fallback silently reached the *global* list when the cue was not live. A `tmpl:`
reference has one answer wherever it is read, so the fork had nothing left to be about and the two
collapsed into one.

**One invariant when adding a spawn site**: call `prewarmTemplateColours` on the request thread
first. `TemplateRegistry.snapshot` falls back to a DB read, and the tick loop must never take one.

### From-State Capture

The `from-state` endpoint and `programmer/record { source: "STAGE_SNAPSHOT" }` share
`captureCurrentState`, which captures the current FxEngine state:
- Effects with a non-null `lookId` are grouped by Look into layer entries, deduplicating targets
- Effects belonging to no Look (ad-hoc) are captured as full `CueAdHocEffectDto` entries with all fields

### Look Delete Blocking

When a Look is referenced by any cue layer (via FK), it cannot be deleted. The delete endpoint returns 409 Conflict with code `LOOK_IN_USE` and the names of referencing cues. `?force=true` deletes the layers with it.

## Cue Stack Membership

**A stack's list is its entries** (`cue_stack_entries`, `models/cueStackEntries.kt`, cue model plan
session 1). An entry is either a **cue entry**, which places one cue at one point in one stack and
carries everything about it that is a matter of the list — its order, its number and auto flag, its
follow — or a **marker entry**, a labelled divider with no cue behind it. The cue keeps only what it
*is*: its name, fade, notes, stomp and children. See `cue-stacks-engineering.md` for the stack itself.

- **A cue may have any number of entries** (plan D1, D7): one in several stacks (**shared**), twice
  in one, or none (**loose**). Editing a cue changes it everywhere it plays; its number and follow
  are each entry's own (D2), so a shared cue can be Q14 in one stack and Q3 in another.
  `POST /cue-stacks/{id}/add-cue` `{cueId, sortOrder?, insertByNumber?, cueNumber?}` **adds** an entry,
  sharing the cue rather than moving it.
- ***Move to stack…*** is `POST /cue-stacks/{id}/entries/{entryId}/move` `{toStackId}`
  (show-cue-editing plan session 3): the **same entry row** re-pointed to the end of the other
  stack, so its uuid, explicit number, follow and prompt-book anchor go with it; an auto number is
  labelled again where it lands, and both stacks renumber. Answers `{stackId, entryId}`; 409 when
  the target already holds the entry's explicit number, 400 for its own stack, a separator or
  another project's, 404 for a marker. Moving the **live** entry leaves the stage alone as a removal
  does (below): its old stack keeps the hold, at its old priority, until its next GO
  (`moveEntry` in `routes/projectCueStacks.kt`).
- **The caller names the entry** (P7). Every route that runs or edits a cue's place in a list takes
  the stack and the **entry** id, and nothing picks a "home" stack for a cue. A press that holds a
  cue loose — a busk pad, a cue slot, MIDI `FireCue`, the hand, `POST /cues/{id}/apply`, the AI's
  `apply_cue` — needs no entry at all. The include target carries the entry it was included from
  (`IncludedTarget.entryId`, `POST /programmer/include {cueId, entryId?}`), for the indicator's
  number. Record and Update write the cue, whichever entry: a held scenery state the cue already
  tracks is left out only when it tracks it at **every** one of its entries
  (`writeHeldSceneryIntoCue`), and a loose cue compares against the bases. `soleEntryOf` — a cue's
  entry only while it has exactly one — is left only for the MCP briefing and `mark_up_prompt_book`,
  until the plan's session 7 moves them onto entries.
- **An entry's number and follow** are written on the entry: `PATCH /cue-stacks/{id}/entries/{entryId}`
  `{cueNumber?, autoAdvance?, autoAdvanceDelayMs?}`, the fields present only, answering the entry's
  row. A typed number is explicit (409 if another cue entry of the stack has it explicitly, as on `add-cue` and `POST /cues`), a blank
  one hands the entry back to auto-numbering, and an unchanged one leaves the auto flag alone. The
  cue's `PUT` / `PATCH` no longer write these.
- **Remove from stack** is `DELETE /cue-stacks/{id}/entries/{entryId}`: the entry and its prompt-book
  anchor go, the stack renumbers, and the cue stays — loose if that was its last entry. Removing
  the **live** entry leaves the stage alone (D16): the stack keeps holding its cue, *detached* at the
  place it stood (`CueStackManager.detachEntry`), and its next GO fires the entry that followed it
  (GO BACK the one before), letting the detached hold go. Its scenery still tracks the entries
  before that place. A carried reload does not bring a detached entry back.
- **A marker is an entry, never a cue.** `POST /cues` refuses `cueType: "MARKER"` (400, naming the
  markers route); markers are made, renamed and removed through
  `POST/PUT/DELETE /cue-stacks/{id}/markers[/{entryId}]`, and can be neither armed nor fired.
  `CueType` survives only as the wire's word for a row's kind: `CueStackCueEntry.cueType`, and a
  pre-v32 archive's cue record, which the importer still reads.

`CueDetails.entries` lists every entry naming the cue in show order (stacks in show order, then
position), each with its stack, position, number and follow. Its place fields — `cueStackId`,
`cueStackName`, `entryId`, `sortOrder` (the entry's **position**, markers counted), the number,
follow and `trackedScenery` — describe the entry `?entryId=` names, else the cue's only entry, and
are null for a shared cue read without one, or a loose cue. `NewCue` takes an optional
`cueStackId` (absent makes a loose cue) and `sortOrder`, a position (appended if omitted).

**In the sync archive** (`formatVersion` 32, cue model plan session 3; `docs/sync-engineering.md`
§"Version 32"): `cues/{uuid}.json` is what the cue is — name, fade, notes, stomp, scenery, events —
and nothing about where it sits. Each entry is its own record, `cueStackEntries/{uuid}.json`, written
from the entry rows directly (never through `soleEntryOf`), so a cue several entries name, or none,
travels as it is. An older archive's cue file still carried its one place, which the importer turns
into an entry named with the cue's uuid; a MARKER cue becomes the marker entry under its own uuid
(which is why the session-1 migration gave a marker entry its marker cue's uuid), and a cue with no
stack imports **loose** — the "Unsorted" fallback is gone from the importer.

Two kinds of **hold** put a cue on stage, and both go through the hold registry (`fx/CueHolds.kt`;
`cue-stacks-engineering.md` §"Holds"):

| Path | Hold |
|------|------|
| A stack GO, GO TO, BACK, activate, auto-advance | `Stack(stackId, entryId)` through `CueStackManager.activateEntry` — the entry becomes its stack's live one, with the crossfade and auto-advance it carries |
| `POST /cues/{id}/apply`, the AI's `apply_cue`, a busk cue pad, a cue slot, MIDI `FireCue`, the hand | `Loose(seq)` — the cue live above every stack, the newest press on top, with no stack moved; its effects, rows, timed effects, triggers, scenery and events exactly as a GO's |

## FxInstance Integration

The `FxInstance` class has a `cueId: Int?` field (alongside `lookId`, `cueLayerId` and
`programmerLayerId`, which name what spawned it). When a cue is applied, all created FxInstances are tagged with the cue's ID. This allows:
- The hold registry to remove a cue's own effects when its last hold goes (not all cues)
- The effect list (`EffectDto`, `get_current_state`) to name the cue an effect belongs to

### Priority

A live cue composes at its **highest hold's** priority (cue model plan P3, D10; `fx/CueHolds.kt`):

- A **stack hold** — `cueDerivedPriority` in
  [`fx/CueApply.kt`](../src/main/kotlin/uk/me/cormack/lighting7/fx/CueApply.kt):
  `stackId * 1_000_000 + position * 1_000 + 1`, where `position` is the entry's place in the stack's
  list, markers counted.
- A **loose hold** — `LOOSE_BAND + seq`, `LOOSE_BAND = 2_000_000_000` and `seq` the registry's
  press counter: above every stack, and the newest press on top. A press over a cue it already holds
  re-stamps it as the newest.

A cue held twice — by two stacks, by a stack and a press — is one contribution at the higher of the
two (D3). Manual (uncued) effects keep priority `0`; the programmer band sits above all of it.

The engine iterates effects in priority-ascending order, then by compose order (creation order,
which a split copy takes from its parent), then id. Higher priority composes later, so under non-OVERRIDE blend modes a later-positioned cue dominates
earlier cues on the same property, and on Layer 4 the higher priority wins every LTP property (HTP
dimmers still take the highest level). See
[lighting-composition-model.md](lighting-composition-model.md) §"Layer 3 — Effects" and §"Holds and
the loose band". Note that a property held by the programmer (Layer 2) suppresses cue-owned effects on it entirely.

Because a stack hold's priority is derived from the entry's position, **reordering a stack changes
precedence**. Every write that moves an entry's position — reorder, `add-cue` (and the AI's
`add_cue_to_stack`), `sort-by-cue-number`, a cue created or recorded mid-list, a cue deleted, a
marker added or removed (a marker counts in the positions) — calls
`holds.restampEntries(stackEntryPriorities(stack))` after writing the entries' order. That re-ranks
the stack holds whose entries it names and restamps each affected cue to its new highest hold
through `FxEngine.repriorityCues`, which moves both layers a cue owns — `FxInstance.priority` and
the Layer 4 `Assignment.priority` rows — so cues already on stage compose in the new order without
being re-applied. A cue a press holds keeps its loose priority. Crossfade weights are left
untouched, and cues whose priority didn't change are skipped, which makes the ordinary
single-live-cue reorder a no-op.

Timed effects and timed layers stamp the cue's priority **when they fire**, from the registry, so
a timer that fires after a press has raised the cue composes at the raised priority.

### Stomp

`DaoCues.stomp: Boolean` (default false) — when a stomping cue applies, the engine calls
`FxEngine.stompForCue(stompingCueId, overlap)` to remove ad-hoc effects owned by *other*
cues that target the overlap set. Phase 0 derives the overlap from the stomping cue's own
ad-hoc effect targets; Phase 1 will switch to Layer 4 property assignments once they land.
Manual effects and effects owned by the stomping cue itself are never stomped. See
[lighting-composition-model.md](lighting-composition-model.md) §"Stomp".

## Active Cue Tracking

A cue is live while anything **holds** it (`cue-stacks-engineering.md` §"Holds"): a stack whose live
entry names it, or a loose press. The registry publishes every live cue with its holds as
`cueHolds.state` (`State.cueHoldsFrame`, a `StateFlow`), and that frame is the frontend's one
answer:

- `useCueRings()` (`store/cueHolds.ts`) — its keys every held cue, and how a busk cue pad and a cue
  slot draw it (D11): **solid**
  while a `LOOSE` hold is among its holds (the next press lets it go), an **outline** while only
  stacks hold it (the next press takes it loose).

It used to be read from each stack's `activeCueId`, which could never see a cue held loose, and
before that derived from `FxInstance.cueId` over the `fxState` stream, which could never see a cue
made only of values (no effect carries its id). A stack's own run state — `cueRunStateChanged`,
keyed by entry — still says which **entry** each stack is on; holds say which **cues** are on stage.

## Cue events

**A cue event fires one one-shot trigger — a confetti cannon's tube — a set time after GO into its
cue** (stage-view plan session 9, D15, D16). Events are the cannons' door into a show, and they are
deliberately unlike everything else a cue carries:

- **They fire when the cue comes on, once** (cue model plan D15). The hold registry fires them on
  the cue's **first** hold (`EffectsService.onCueGo`, from `CueHolds.acquire`): a GO, GO TO the cue
  itself or the auto-advance timer making it a stack's live cue, or a loose press — a busk pad, a
  cue slot, `POST /cues/{id}/apply`, the AI's `apply_cue`. A hold on a cue something already holds
  fires none: a second stack going onto it, a press over a stack that has it, a GO between two
  entries of it. **They never track**: GO TO a *later* cue fires nothing of
  an earlier one's, and a **GO BACK** into the cue fires nothing either (`fireEvents = false` on the
  BACKWARD arm), since stepping back is a correction, not a go. **They are never previewed**: only
  the GO builder (`buildCueGoData`) carries them, so a Next GO preview, a republish or a cook cannot.
- **They need the arm.** Judged at GO: unarmed, the GO goes and every event of the cue is skipped
  — logged, and announced on every window as one `effects.skipped` frame — and **never queued**, so
  arming a moment later does not bring them back. An event scheduled under an arm that has dropped
  by its offset — a disarm, a lapse, a stack stop, a project switch, even if armed again since — is
  skipped too; extending a held arm (arming again while armed) is not a drop. In Blind every event is **rehearsed** instead: the
  burst is drawn, nothing is sent, no arm is needed and no tube is spent.
- **They are not channel values.** Nothing composes, crossfades, records or stomps them. A fire is a
  backend-timed pulse, ~300 ms high then idle, written above composition through the show's
  `TriggerOutput` (`docs/fixtures-engineering.md` §"One-shot triggers").
- **A spent tube sends nothing.** An event reaching one is skipped and announced (`SPENT`).

The write is whole-list: `PUT /api/rest/projects/{id}/cues/{cueId}/events`
`{events: [{patchId, trigger, offsetMs}]}`, answered with the list as stored (`CueEventDto`:
`uuid, patchId, patchUuid, fixtureKey, fixtureName, trigger, triggerLabel, offsetMs, sortOrder`).
Every problem comes back at once: a patch with no trigger, a trigger its type does not have (by name,
`output1`, or label, `A`), a tube named twice, an offset outside 0–600 000 ms; a MARKER has none. The
cue's own DTO carries them as `CueDetails.events`. A re-save keeps each tube's row and uuid, so an
unchanged list exports byte-for-byte as before. Deleting a cue or a patch sweeps its events; a cue
copy carries them, one into another project those that resolve there (§"Copying"). They travel in sync embedded in `CueJson.events` on
`formatVersion` 21 (`docs/sync-engineering.md` §"Version 21 — cue events").

The MCP tools author them too — `create_cue` and `build_cue_stack` take `events`
(`[{fixture, trigger, offsetSeconds?}]`, the fixture by key or name) and `set_cue_events` replaces
one cue's list. No tool arms or fires (`docs/mcp-engineering.md`).

## Speed masters on a cue

**A cue may set speed masters' tempo as it comes on** (venue-visit plan S16, D15, D20): a list of
`{speedMasterUuid, bpm}` pairs, `CueDetails.speedMasters`, stored as a JSON column on the cue
(`cues.speed_masters`, `models/cueSpeedMasters.kt`). It is the cue's, not its entries', so a shared
cue sets the same tempo from every stack, and it never tracks: a later cue sets nothing an earlier
one set. Unlike events it is state the cue **re-asserts**:

- **Every GO into one of its entries sets it** — GO, GO TO, GO BACK, auto-advance and a re-fire, and
  a GO into a cue another stack already holds live. The hook is `CueStackManager.activateEntry`,
  which every one of those passes through, never the first-hold path where events fire
  (`CueHolds.acquire`). `buildCueGoData` alone carries the list (`CueApplyData.goSpeedMasters`), as it
  alone carries events, so a preview, a republish or a cook sets nothing.
- **Every loose press sets it too** — a busk pad, a cue slot, `POST /cues/{id}/apply`, the AI's
  `apply_cue`, MIDI `FireCue` and the hand — through `takeLooseCue`, since a press brings the cue on
  as a GO does.
- **A reload sets none.** `Show.resume` re-GOes each stack's live entry and re-presses each loose
  hold with `setTempo = false` beside `fireEvents = false`: the flag is its own, because a GO BACK
  fires no events and still sets tempo. So a tap since the cue went (D15: a tap wins until the next
  cue that sets that master) survives a sync pull or a venue Update.
- **A follower or a missing master is skipped, never fatal.** `fx/CueTempo.kt` writes each pair
  through `SpeedMasterBank.setBpm` as a `MANUAL` write; one the bank refuses as a follower
  (`SPEED_MASTER_FOLLOWER`) or does not know (deleted under the cue with `?force=true`, or an import
  naming a master the archive lacked) is named in the log and passed over, and the GO goes on. A
  master already at the pair's tempo is left alone, so a GO that changes nothing sends no
  `speedMasters.changed` frame and leaves a tap's source as it was.

The write is the cue's own: `POST /cues`, `PUT` and `PATCH /cues/{id}` take `speedMasters` as the
whole list (a PATCH without it leaves it alone, a PUT without it clears it), every problem at once —
a uuid naming no master of the project, a BPM outside 20–300, a master named twice. A uuid the cue
already names is accepted even when its master has gone, so a list can be re-saved with the dead row
still in it, or with it removed. A master a cue names counts as a use for the speed master's delete
guard (`SpeedMasterUsage.cueTempos`, `cueTempoCount` on the 409), so deleting it is refused until
`?force=true`. *Duplicate*, `POST /cues/copy-from` and `copy_to_project` carry the list through the
one cue copier (§"Copying"), and across projects each master is mapped by its index — a pair whose
index the target lacks is left out and reported under `unresolved`. The MCP
tools `create_cue` and `build_cue_stack` take `speedMasters` in the same shape, or with a pair's
master named by `masterIndex` (1 for master 1) instead of its uuid, which is how `build_cue_stack`
names one in a project that is not current; `get_current_state` lists only the current project's
uuids. It syncs embedded in
`CueJson.speedMasters` on `formatVersion` 35 (`docs/sync-engineering.md` §"Version 35 — speed masters
on a cue"). The cue editor's **Cue** tab edits it (`frontend/CLAUDE.md` §"The cue editor").

## Copying

**One cue copier** (`routes/cueCopier.kt`, cue model plan §3.8, D9, D18) makes every copy of a cue:
*Duplicate* within a project, `POST /cues/copy-from` from another project, and MCP's
`copy_to_project`, which copies whole stacks through it. A copy is a new cue with fresh uuids and the
source's name, holding everything the source holds — fade, curve, notes, stomp, its speed masters,
layers, own rows, effects, script hooks, scenery and events. Within one project every reference is
kept as it is. Across projects each is resolved **in the target**:

- **A Look or palette** a layer applies is the target's of that name, else copied with the cue, and
  so is one a `tmpl:{uuid}` effect parameter names — a colour reference, or an item of a list
  (a colour list, Palette Steps' `palettes`). A copied Look or palette has its own references
  mapped the same way. The answer names each under `looksCopied` / `looksLinked` /
  `palettesCopied` / `palettesLinked`.
- **A speed master** maps by its index — a layer's and an effect's (one the target lacks drives off
  master 1) and the cue's own tempo list (one the target lacks is left out). **A script** maps by
  name; a hook whose script the target lacks is left out.
- **A cue event's patch and a scenery row's element** match by **uuid** first when the two projects
  are on one venue — a show pins it, a venue is it — whatever version each pins, since a published
  version keeps every record's uuid; otherwise, and failing that, a patch by key and fixture type and
  an element by name. One that does not resolve is left out.
- **A fixture or group target** — a layer's, a row's, an effect's, each item of a selection — is kept
  whatever the target's effective rig (`RigScope.of(target)`) holds; one the rig lacks reads as
  missing through `AssignmentHealth`, as a venue Update's dead references do.

Everything that did not come across is in `unresolved`, each problem with the records that hold it
(`cue 'Storm'`, `Look 'Night'`). **Prompt-book anchors are never copied**: an anchor is an entry's,
and a copy is placed afresh. `POST /cues/copy-from` places each copy in the order of `cueIds`,
unnumbered, so it takes the next auto number — after the `after` entry, else at the end of the
stack — or leaves it loose, and restamps the stack's holds and announces its pads as Duplicate does.
A `dryRun` is the same run rolled back. Any project can be the source (D18); the target is the
current project, under the show-editing lock in the Cues table (frontend `CLAUDE.md` §"The Cues
table"). The REST `POST /cues/{id}/copy`, which copied as-is into a stack it called "Unsorted", is
gone. When a portable column is added to a cue, Look, palette or their children, add it to the
copier too.

## WebSocket Notifications

When cues are created, updated, or deleted, a `cueListChanged` message is broadcast to all connected WebSocket clients:

```json
{"type": "cueListChanged"}
```

The frontend subscribes to this message and invalidates the `CueList` RTK Query tag, triggering a re-fetch of the cue list.

The `fxState` WebSocket message now includes `cueId` on each effect in `activeEffects`, enabling the frontend to track which cues are running.

## Frontend Integration

### API Layer
- `src/api/cuesApi.ts` — Type definitions for Cue, CueInput, StopCueResponse, etc.
- `src/api/cuesWsApi.ts` — WebSocket subscription for `cueListChanged`
- `src/api/lightingApi.ts` — Registers `cues` WS API
- `src/api/fxApi.ts` — `FxEffectState` includes `cueId`

### State Management
- `src/store/cues.ts` — RTK Query endpoints with `CueList` tag, `stopCue` mutation; liveness is `store/cueHolds.ts`'s `useCueRings()`, which replaced `useActiveCueIds()`
- `src/store/restApi.ts` — `CueList` added to tag types

### UI Components

Cues are authored and run in the **Show** view (`/projects/:projectId/show`); every cue is listed
once, loose ones included, in the **Cues table** (`/projects/:projectId/cues`, `routes/Cues.tsx`,
cue model plan session 5). `components/cues/CueForm.tsx` is gone.

- `src/routes/Cues.tsx` + `src/components/cues/CueLibrarySheet.tsx` — the Cues table, read off
  `GET /cues` (each cue's entries and `usedBy`); its bar is the cue action set
  (`components/runner/CueActions.tsx`), and a loose cue opens `CueEditor` in a right-hand sheet

- `src/routes/ShowPage.tsx` — the show: an ordered list of stacks, drilling into
  `/show/stacks/:stackId?cue=:cueId`
- `src/components/cues/editor/CueEditor.tsx` — the cue editor: the header (the entry's number
  and follow, the cue's name, fade and curve) and its tabs, mounted by the card
  (`components/runner/CueCardEditor.tsx`), the cue sheet's bottom pane and `stage/OwnerEditor`
- `src/components/cues/CueDetailContent.tsx` — the read-only body the mobile runner and the Prompt
  Book rail draw through `CueCardBody`
- `src/components/cues/CopyFromShowSheet.tsx` — *Copy from another show…*, the Cues table's
  library verb: a project, its cues, a stack or none, then the dry run's review before the copy

### Navigation
- The Cues row in `navigation.ts`, a Library row after Looks (hidden in a venue)
- On a show's home (`routes/ProjectHome.tsx`) the Show card counts its stacks and the Cues card the
  project's cues once each (`NavItem.homeCount`)
- Routes in `App.tsx`: `/projects/:projectId/cues` is the Cues table and bare `/cues` resolves the
  current project's (`CuesRedirect`)

## MCP tools

Three tools (`ai/AiTools.kt`, offered over MCP — `docs/mcp-engineering.md`):
- `create_cue` — Create a named cue with layers (a Look, or a palette by `paletteId`), its own
  `values` (resolved per head in `create_look`'s grammar; they beat every layer) and ad-hoc effects
- `apply_cue` — Take a **loose hold** on a saved cue by ID (§"Apply Semantics"): live above every
  stack, newest on top, no stack moved. Optional `replaceAll` lets every other loose hold go first;
  stacks are never touched.
- `stop_cue` — Let the cue's loose hold go. It comes off unless a running stack also holds it; other
  running cues are unaffected.

Plus the three programmer gestures, which is how a cue gets *authored* rather than described:
`record_cue` (the programmer into a cue, in any `RecordMode` — CREATE / MERGE / UPDATE_EXISTING / REMOVE), `include_into_programmer`
(a cue or Look back out as an edit buffer) and `update_from_programmer` (write back only what
changed since the Include; `preview=true` returns the checklist without writing). They call the
same `performProgrammerRecord` / `performProgrammerInclude` / `performProgrammerUpdate` cores in
`routes/programmerSurface.kt` that the REST routes render — the mode/mask/scope rules and the
include-target bookkeeping decide which rows get overwritten, so there is exactly one copy of them.
`create_palette` completes the set: the named, referenceable value an effect parameter points at
with `tmpl:{uuid}`.

The `get_current_state` tool includes `cueId` on active effects and `cues` in its default include set, returning cue names and counts for the current project.

The MCP `instructions` and `describe_rig` (`ai/RigBriefing.kt`) describe:
- Multiple concurrent cues, applied alongside one another
- Colour palettes, listed with their uuids under `palettes`, and the `tmpl:{uuid}` grammar an
  effect parameter uses to name one
- Active effects display includes `cueId` for each effect

## Assignment values: one form

A `cue_property_assignments.value` (and its `look_rows` twin) holds a **literal**, in the canonical
`CueAssignmentResolver.PropertyValue.serialize()` grammar — `"200"`, `"#rrggbb[;wN;aN;uvN]"`,
`"pan,tilt"`. Nothing else.

Two other forms have been through here, and both retired for the same reason. `"ref:{paletteUuid}"`
resolved per fixture against the `Palette` entity and **retired in session 4** of the
looks-and-layers plan. The **positional ref** — `"P1"`, `"P2"`, `"P*"`, indexing an ordered colour
list scoped global → stack → cue — went with the whole positional palette.

Neither capability was lost; each moved up a level. A dependency on a *named look* is a
**`DaoCueLayer`**, which names its Look through a real FK — an FK cannot be half-rewritten by an
import, the delete guard is an indexed query rather than a scan over opaque text, and a
`propertyMask` expresses "only this cue's colour comes from Warm" without a reference per row. A
dependency on a *named colour* inside an **effect parameter** is `tmpl:{uuid}`, which is the one
consumer with no layer to hang off.

Three things outlived those grammars, all deliberately. `validateLookRows` rejects both a `ref:`- and
a `tmpl:`-shaped value at the Look write boundary — that rejection *is* the no-nesting guarantee, so
it survives as an inlined shape check with its own local constant.
`CueAssignmentResolver.parseAssignmentValue` returns **null** for a `tmpl:`-shaped value rather than
letting `parseExtendedColour` answer white. The third was the migration that folded `ref:` rows from
a v4 database into layers; it was removed on 2026-08-24 with the rest of them (see
[InstallBootstrap.kt](../src/main/kotlin/uk/me/cormack/lighting7/state/InstallBootstrap.kt)), so a
v4 database no longer has an upgrade path in the code — recover it from git history if one turns up.

Note what the reference got right, because a layer inherits it: it stored the Look's **uuid** rather
than its int id, since int primary keys never appear in the sync export and are re-minted on import.
A layer's `lookId` is an int, which is safe only because it is a real foreign key the importer
rewrites — `uuid` is still the only thing that may appear *inside* a value.
