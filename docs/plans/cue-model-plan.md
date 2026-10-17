# The cue model — one shared cue, stacks as ordered entries

> **Document status: AGREED, 2026-10-14.** This is design session R1 of
> [`venue-visit-plan.md`](venue-visit-plan.md), whose D1 it implements: one shared cue, stacks as
> ordered membership, cues outside any stack, and delete as its own explicit operation. The
> questions asked and their answers are in §10 and in the design record,
> [`cue-model-design/INDEX.md`](cue-model-design/INDEX.md). A one-page copy for human readers is
> [`cue-model-design/cue-model.html`](cue-model-design/cue-model.html), with a live copy at
> <https://claude.ai/artifact/396mjPfVFE8D6oi2Xh6EHH> (the checked-in file is the authority).
>
> Where this plan and the venue-visit plan disagree, this plan wins inside its scope (the
> venue-visit plan's preamble).

## 1. Context

The venue-visit evening wanted one cue in several stacks: the same blackout at the end of each act,
a pre-show state the house stack and the show stack both use. Today that is a duplicate, and an edit
to one is not an edit to the other. D1 of the venue-visit plan decided the model; this plan works
out what it takes.

The findings that shape the plan, from three surveys of the code on `main` at `258aa34`:

- **A cue's place in a stack is stored on the cue.** `cues.cue_stack_id` is non-null
  (`models/cues.kt:175`), and `sort_order` (`:176`), `cue_number` (`:181`), `cue_number_auto`
  (`:188`), `auto_advance` and `auto_advance_delay` sit beside it. Numbers are unique per stack
  through a raw-SQL partial index, `uq_cue_number_per_stack ON cues (cue_stack_id, cue_number)`
  (`state/State.kt:1314`), and `renumberAutoCues` / `releaseAutoNumber` query
  `DaoCues.cueStack eq stack.id` (`routes/cueNumbering.kt:293-338`).
- **SQLite can't relax that column without rebuilding the table**, and there are no migrations
  (CLAUDE.md §Database). `PRAGMA foreign_keys` is off on this desk
  (`models/fixturePatchPlacements.kt:47`), so a rebuild needs no FK juggling, but `NOT NULL` is
  enforced. `state/InstallBootstrap.kt` says where a recovered migration plugs in. This checkout is
  shallow, so the old `StateMigrations.kt` is recovered from the full history on the desk.
- **Deleting a stack deletes its cues** (`deleteCueStackRows`, `routes/projectCueStacks.kt:699-726`),
  and the card's Remove deletes a cue outright with no confirmation. Both sweep the cue's children,
  scenery, events, prompt-book anchors, busk pads and cue slots by hand
  (`routes/projectCuesHelpers.kt:630-660`), because nothing cascades.
- **`add-cue` moves a cue** (`cue.cueStack = stack`, `routes/projectCueStacks.kt:234`), and the MCP
  `add_cue_to_stack` says so in its description. Copies land in an "Unsorted" stack
  (`getOrCreateUnsortedStack`, `routes/projectCues.kt:693`), as `create_cue` does, and the
  importer puts an old archive's stack-less cues there too (`sync/ProjectImporter.kt:1181-1196`).
- **The stack is derived from the cue in at least twenty places**, with no stack supplied by the
  caller:
  - `CueStackManager.fireCue` (`fx/CueStackManager.kt:618`), `/cues/{id}/apply` and `/stop`
    (`routes/projectCues.kt:494-551`)
  - the busk press (`routes/BuskPressService.kt:91`) and pad LEDs (`state/State.kt:950`)
  - `BuskCueDto` (`routes/projectBusk.kt:706`), Record and Update (`routes/programmerSurface.kt:123,
    364`)
  - `trackedSceneryAt`, `sceneryBeneathCue`, `elementSceneryOf` (`routes/projectScenery.kt:170-250`,
    `routes/programmerSceneryRecord.kt:191`)
  - `toCueDetails`, `RigKeyReferences`, `RigBriefing`, `get_prompt_book`
- **The engine below the stack manager is keyed by cue id alone.** `CueAssignmentLayer` holds
  `cueAssignments`, `cueFadeWeights` and `cueStackIds` by cue (`fx/CueAssignmentLayer.kt:31-58`).
  So do `CueTriggerManager`'s maps, `removeEffectsForCue`, `repriorityCues` and
  `EffectsService.onCueGo` (`state/EffectsService.kt:357`). The same cue live in two stacks would
  overwrite one stack's rows with the other's, share one fade weight, and be torn down by either
  stack moving on. Only `removeEffectsForCueStack` and the manager's `activeStacks` are safe.
- **Priority comes from the cue's home stack**, `stackId * 1_000_000 + sortOrder * 1_000 + 1`
  (`fx/CueApply.kt:184-192`). A cue fired in another stack would stamp the wrong band.
- **Tracking walks the stack's cue list.** Scenery tracks from the top of the stack down to the
  live cue (`SceneryService.liveStack`, `state/SceneryService.kt:510`). Standby and "next" walk
  `orderedStandardCueIds` by `indexOf(cueId)` (`fx/CueRunStateTracker.kt:97-131`), which is ambiguous
  once a cue can appear twice.
- **Nothing persisted names a stack with a cue.** Busk pads, cue slots, prompt-book anchors, cue
  events, cue scenery and `BindingTarget.FireCue` (`midi/BindingTarget.kt:103`) all name the cue
  alone. Only the include target carries a stack (`IncludedTarget.cue(cueId, cueStackId)`,
  `fx/ProgrammerStore.kt:117`), for the indicator.
- **A prompt-book anchor is unique per cue per book** (`models/promptBooks.kt:113, 124`), labelled
  with the cue's number.
- **Sync stores membership inside the cue.** `CueJson` carries `cueStackUuid`, `sortOrder`,
  `cueNumber`, `cueNumberAuto` and `autoAdvance` (`sync/dto/SyncDtos.kt:835`). The writer is at
  `formatVersion` 29 and the importer reads 5–29 (`sync/ProjectImporter.kt:234-235`; 31 and 5–31 at
  `:265-266` by venue-visit course check K1). Merging is per
  record (`sync/ThreeWayDiff.kt`), so a renumber touches every cue file in the stack and conflicts
  with any concurrent edit to those cues. `FU-SYNC-ORDINAL-DOUBLE` asks for its fix "in the same
  bump" as the next one.
- **There is no all-cues view.** The old `/cues` routes redirect to the Show view
  (`frontend/src/App.tsx:222-242`), and `useProjectCueListQuery` has one consumer, the new-cue name
  picker (`components/runner/ShowView.tsx:100`).
- **Cross-project copy exists twice.** `copy_to_project` copies whole stacks between projects by
  name and key (`ai/VenueSetupTools.kt:332-700`). The REST `POST /cues/{id}/copy` copies one cue
  into "Unsorted" and keeps the source project's Look and template FKs on its layers
  (`routes/projectCues.kt:385-390`), which is a cross-project leak.
- **Cue-edit live apply is retired** (sweep D1). Its comments survived at
  `fx/CueAssignmentLayer.kt:362`, `fx/CueStackManager.kt:373` and `fx/CueApply.kt:439` until
  session 2, which took them out.

## 2. Decisions

From the venue-visit plan, and agreed with Chris on 2026-10-14 (§10):

| # | Decision |
|---|---|
| D1 | **One shared cue** (venue-visit D1). A cue is a project record; a stack is an ordered list of **entries**, each naming a cue. Editing a cue changes it in every stack. A cue may be in no stack (**loose**). Removing an entry or deleting a stack never deletes a cue. |
| D2 | **An entry holds the cue's place in that stack**: its position, its number (and whether that number is auto), and its follow (auto-advance and its delay). **The cue holds what it is**: name, layers, values, effects, fade time and curve, notes, stomp, scenery and events (and S16's speed masters). One fade per cue, the same in every stack. |
| D3 | **A cue live in two places is one contribution, held until the last.** Every stack entry or loose press that makes it live is a *hold*. It fades in on the first hold and out on the last release; a stack moving on from a cue another holder still holds leaves it on stage. |
| D4 | **Prompt-book anchors are per entry.** A cue used in Act 1's stack and Act 2's gets an anchor at each place, labelled with that stack's number. |
| D5 | **Existing data arrives by a recovered migration.** One startup migration, where `InstallBootstrap.kt` says it plugs in: a backup of the database file, an entry built from each cue's stack, order, number and follow, then `cues` and `prompt_book_anchors` rebuilt without the moved columns. |
| D6 | **Busk pads, cue slots, MIDI `FireCue` and the hand name the cue, and run it loose.** A press holds the cue outside any stack, with no follow and no tracking. A loose cue can be placed. |
| D7 | **A cue may appear twice in one stack.** Everything at runtime, standby, anchors and the MCP tools keys on the entry, never on (stack, cue). |
| D8 | **Deleting a cue in use is refused** with 409 `CUE_IN_USE`, naming every entry, pad, slot, anchor and binding; `?force=true` removes them with it. The UI always shows the list in its confirm and sends force. A loose, unused cue deletes straight away. |
| D9 | **A cue copied from another show is mapped by key**, across venue versions. Values, layers and effects already name fixtures by key; cue events map a patch by uuid, else by key; scenery maps an element by uuid, else by name. What doesn't resolve is reported and dropped, with a dry run first. The copy is a new cue, loose or into a chosen stack. |
| D10 | **Loose holds compose above every stack**, newest press on top. Stacks keep their order among themselves. |
| D11 | **A cue pad has two lit looks.** Solid: held loose. Outline: live only through a stack. A press always toggles the loose hold. |
| D12 | **A marker is a labelled entry, not a cue.** It is never shared, pressed, listed or deleted as a cue. |
| D13 | **The all-cues table is its own nav item**, a library view beside Looks and Palettes. |
| D14 | **Numbering is per entry, and venue-visit D11 still holds.** An explicit renumber never relabels its auto neighbours, and *Fix Order* moves a cue that reads out of order. |
| D15 | **A cue's events fire when it comes on**: on its first hold. A GO into a cue another hold already keeps live fires nothing, as GO BACK fires nothing today. |
| D16 | **Removing the live entry leaves the stage alone.** The stack keeps the cue on stage until its next GO, which fires the entry that followed the removed one. |
| D17 | **A stack delete offers to delete the cues it would leave loose**, unticked by default. Ticking it runs D8's delete on them, so their pads and slots are named first. |
| D18 | **Any project can be a copy's source.** A show on the same venue also matches venue records by uuid; from anywhere else they fall back to key and name. |

The plan adds its own:

- **P1 — One PR per session**, in §5's order, per CLAUDE.md §"Git workflow". Each session ends
  with `./gradlew test` and `npm run check` green, its engineering-doc paragraphs written, and a
  done-marker in its heading citing its change commit(s), never the merge commit.
  The venue-visit plan's P1 checklist ([`venue-visit-plan.md`](venue-visit-plan.md)) applies
  before each PR.
- **P2 — The entry is the playback key.** A stack's live cue, its standby, a GO TO, a preview, an
  anchor and an include's context name an entry id. A cue id alone means the cue's content, or a
  loose hold.
- **P3 — Holds are one registry.** `fx/CueHolds.kt` is the only place that says what holds a cue
  live. The engine layers are keyed by cue as they are today (D3 makes that correct); acquiring
  and releasing go through the registry, which decides when a cue fades in, fades out, and at what
  priority it composes.
- **P4 — One entry per cue until sync can say more.** Sessions 1 and 2 keep every cue in exactly
  one stack, so today's archive format can still describe the desk. Session 3 bumps `formatVersion`
  before session 4 lets a cue be shared or loose (the venue plan's P6, applied here).
- **P5 — Auto numbers are derived, never synced.** The archive carries an entry's number only when
  it is explicit; every reader recomputes the auto ones from position. A drag then changes one
  file, not every auto-numbered entry after it. *Amended 2026-10-16 (§10, 17):* an auto number a
  reader could not derive — one venue-visit D11 holds beside an explicit renumber waiting for *Fix
  Order* — is written too, and read back as explicit, so every desk shows the same labels.
- **P6 — Ordinals are doubles on entries.** A new entry takes the midpoint of its neighbours, and
  two desks inserting into the same gap both keep their entries, ordered by `(ordinal, uuid)`.
  This is `FU-SYNC-ORDINAL-DOUBLE` for entries. The other ordered tables keep their integers, and
  the follow-up stays open for them.
- **P7 — The caller names the stack.** Every site in §1 that derives a stack from a cue takes it
  from its caller instead: an entry id, or nothing for a loose hold. No code path picks a "home"
  stack.

## 3. The model

### 3.1 Vocabulary

- **Cue**: a project record of what a state is (D2). It is **loose** while no entry names it.
- **Entry**: one row of a stack's list. A **cue entry** names a cue; a **marker entry** carries a
  label and no cue (D12).
- **Hold**: what keeps a cue live. A **stack hold** is a running stack whose live entry names the
  cue; a **loose hold** is a press from a pad, a slot, a binding, the hand's place-and-press, REST
  `/apply` or MCP `apply_cue`.
- **Shared**: a cue with two or more entries, in one stack or several.

### 3.2 Stored

| Where | What |
|---|---|
| `cues` | `id`, `uuid`, `project_id`, `name`, `fade_duration`, `fade_curve`, `notes`, `stomp`. Loses `cue_stack_id`, `sort_order`, `cue_number`, `cue_number_auto`, `auto_advance`, `auto_advance_delay` and `cue_type`. |
| `cue_stack_entries` (new) | `id`, `uuid`, `stack_id` (→ `cue_stacks`), `cue_id` (→ `cues`, null for a marker), `kind` (`CUE \| MARKER`), `label` (a marker's text), `ordinal` (double), `cue_number` (varchar 20), `cue_number_auto`, `auto_advance`, `auto_advance_delay` (`duration`). |
| `uq_entry_number_per_stack` | Raw-SQL partial unique index: `(stack_id, cue_number) WHERE cue_number IS NOT NULL AND kind = 'CUE'`. Replaces `uq_cue_number_per_stack`, which the migration drops. |
| `prompt_book_anchors` | `cue_id` becomes `entry_id` (→ `cue_stack_entries`), unique per `(prompt_book_id, entry_id)`. |
| `busk_pads.cue_id`, `cue_slots.cue_id`, `cue_scenery`, `cue_events`, `cue_layers` and the other cue children | Unchanged: they name the cue. |
| `control_surface_bindings` `FireCue` | Unchanged (a cue id and uuid); it now presses loose. |

`SyncCoverageTest` gains `cue_stack_entries` as Portable (`cueStackEntries`).
`RichProjectFixture` gains a shared cue (two entries in two stacks), a cue twice in one stack, a
loose cue on a busk pad, a marker entry, an explicit number beside auto ones, and an anchor on the
second entry of the shared cue: every one a non-default value (CLAUDE.md §"Database changes").

### 3.3 Numbering (D14)

`cueNumbering.kt`'s parse, group and order rules are unchanged, and so is its auto-numbering
algorithm. The functions take a stack's entries instead of its cues:

- `renumberAutoEntries(stack)` replaces `renumberAutoCues(stack)`. It runs from every handler that
  changes a stack's list: add, remove, move, reorder, sort-by-number, a number edit, and an
  import. **Removing an entry, or moving one out, renumbers the stack it left as well as the one it
  joined** (venue-visit R1).
- `releaseAutoNumber` frees a number from an auto entry in the same stack.
- **Venue-visit S3 lands D11 first**, on `renumberAutoCues`. Session 1 carries its rule and its
  three pinned cases across onto entries unchanged. If S3 has not landed when session 1 starts,
  session 1 waits for it rather than re-implementing it. (Landed: the rule is an auto number kept while its
  entry keeps its index, through `renumberAutoCues(stack, before)` and a `standardCueIds` snapshot
  every handler takes before it changes the list — so `renumberAutoEntries` takes the same
  `before`, of entry ids — plus an out-of-order explicit number neither anchoring nor bounding a
  run; `docs/cue-stacks-engineering.md` §"Auto Numbering".)
- A number is a stack's label, so a shared cue can be Q14 in one stack and Q3 in another. A loose
  cue has no number; it is shown by name.

### 3.4 Runtime (D3, D6, D10, P2, P3)

**The hold registry**, `fx/CueHolds.kt`, maps cue id → its holds: `Stack(stackId, entryId)` or
`Loose(seq)`. Every path that makes a cue live or lets it go goes through it:

```
acquire(cue, hold, fade):
  first hold of this cue → spawn its effects and Layer 4 rows, fade in on `fade`, start its
                           timed effects and triggers
  otherwise              → restamp its priority; nothing fades
release(cue, hold, fade):
  last hold of this cue  → fade it out on `fade`, then remove its effects and rows
  otherwise              → restamp its priority; nothing fades
priority(cue) = the highest of its holds:
  Stack(s, e)  → stackBand(s) + position of e in s   (today's formula, by entry position)
  Loose(seq)   → LOOSE_BAND + seq                      (D10: above every stack, newest on top)
```

- **`CueStackManager`** keeps `activeStacks` by stack, with `activeEntryId` (and the cue it names).
  A GO from entry *a* to entry *b* in stack *s* is `acquire(cue(b), Stack(s, b))` then
  `release(cue(a), Stack(s, a))`, sharing one crossfade. When *a* and *b* name the same cue (D7),
  nothing on stage moves, and the follow timer is *b*'s. A GO onto the entry the stack is already on
  re-fires it: its hold is let go and taken again, snapping, so it re-fires unless another hold keeps
  it (as session 2 shipped).
- **`CueCrossfadeDriver`** is unchanged in shape: one fade per stack, and weights by cue. The
  registry tells it which side of the fade is real. An incoming cue already held elsewhere starts at
  weight 1, and an outgoing cue still held elsewhere stays at 1.
- **`CueAssignmentLayer` and `FxInstance.cueStackId`.** `cueStackIds: cueId → stackId` becomes
  the registry's question. Preview's `assignmentsExcludingCues(holds.cuesHeldOnlyBy(s) + cueId)`
  keeps a cue that another hold still holds. Effects are removed by cue when the last hold goes, never by stack.
  `removeEffectsForCueStack` then serves only a stack's own non-cue effects, if any remain.
- **Standby and next** (`CueRunStateTracker`) key on entries: `standbyEntryIds: stackId → entryId`,
  and `orderedCueEntries(stack)` replaces `orderedStandardCueIds`. Positions are entry positions, so
  a cue twice in one stack is never ambiguous.
- **Loose holds.** `/cues/{id}/apply`, MCP `apply_cue`, a busk cue pad, a cue slot, `FireCue` and
  the hand take a loose hold, snapping in and out (`Arrival.SNAP`, `Departure.SNAP`); `/stop`,
  `stop_cue`, a second press and a solo sibling release it. `replaceAll` lets go of every other loose
  hold first (`dropAllLoose`); it never touches a stack's. `applyCue`'s separate path is gone (session
  2): a loose cue starts its timed effects, triggers and events as a GO does.
- **Scenery** tracks per stack, down the entry list to the live entry. A loose cue folds in as a
  one-cue stack in GO order: its own rows, no set, no tracking. `SceneryService`'s `goes` key on
  entries, and `onLooseHeld` / `onLooseReleased` are the loose hold's hooks.
- **Events** fire when the cue comes on (D15): `acquire`'s first-hold arm calls
  `EffectsService.onCueGo`, whatever path took the hold. A GO into a cue another hold keeps live
  fires nothing. GO BACK still fires nothing, because the registry is told the GO's direction. The
  label is the entry's number (`Q14`), or the cue's name for a loose press.
- **Stomp, stop and delete** are by cue, through the registry: `stompForCue` exempts the cue's own
  effects whichever hold spawned them, and a forced delete releases every hold first.
- **`ShowCarry`** carries `(stackId, entryId)` for each live stack, plus the loose holds in press
  order, and `Show.resume` re-acquires both.
- **The wire.** `cueRunStateChanged` gains `activeEntryId`, `nextEntryId` and `standbyEntryId`
  beside today's cue ids. A new outbound frame, `cueHolds.state`, is `StateFlow`-backed: per live
  cue, its holds. Pads, slots and the cues table draw D11's two looks from it. It is outbound only,
  so `FU-AUTH-WS-PER-MESSAGE` does not fire and `TraceSampling.WS_MESSAGES` needs no row.

### 3.5 Everything that names a cue

| Reference | Today | After |
|---|---|---|
| Stack list | `cues.cue_stack_id` + `sort_order` | `cue_stack_entries` (D1). Done, session 1. |
| Busk pad, cue slot, `FireCue`, the hand | the cue; press GOes in its stack | the cue; press holds it loose (D6, D11). Done, session 2. |
| Prompt-book anchor | the cue (unique per book) | the entry (D4). Stored by entry since session 1, the routes and the book since session 4; the MCP tools name the cue until session 7. |
| Cue events, cue scenery, layers, values, effects, triggers | the cue | the cue (content) |
| Stack scenery (the set) | the stack | the stack |
| Include target | `(cueId, cueStackId)` | `(cueId, entryId?)`: the entry it was included from, for the indicator; Update writes the cue. Done on REST and in the Show view, session 4; MCP `include_into_programmer` in session 7. |
| Record CREATE | needs a stack | a stack is optional; without one the new cue is loose. Done on REST and `record_cue`, session 4; the Record sheet's *No stack* in session 5. |
| Standby, GO TO, preview, `activate` at a cue | a cue id in the stack | an entry id (P2). REST since sessions 1–2; the MCP tools in session 7. |
| `projects.active_stack_id` | the playhead stack | unchanged |
| MIDI `CueStackGo/Back/Pause` | the stack | unchanged |

### 3.6 Delete (D1, D8)

- **Remove from stack** deletes an entry. The cue stays, loose if that was its last entry. The
  stack's numbers are renumbered, and the entry's anchor goes with it. **Removing the live entry
  leaves the stage alone** (D16). The stack's hold moves to a *detached* state that still holds the
  cue, standby and "next" are read from the removed entry's old position, and the next GO fires the
  entry that followed it and releases the detached hold.
- **Delete a stack** deletes its entries and its set. Its cues stay; the answer counts how many were
  left loose. The confirm offers *Also delete the N cues no other stack uses*, unticked (D17); the
  route takes `deleteLooseCues: true`, which runs D8's delete with force on them, after the confirm
  has listed their pads and slots from a dry run.
- **Delete a cue**: `DELETE /cues/{id}`, and `POST /cues/delete {cueIds, force?, dryRun?}` for
  several. Without force it is 409 `CUE_IN_USE` with `{entries: [{stackId, stackName, entryId,
  number}], pads, slots, anchors, bindings}`. With force it releases every hold, removes every entry
  (renumbering their stacks), anchor, pad and slot, and the cue with its children, scenery and
  events. Bindings are left to report `MissingCue`, as today. The card's **Remove** becomes
  *Remove from stack*; deleting a cue is the cues table's action, with the confirm D8 describes.

### 3.7 The all-cues table (D13)

A library view, `/projects/:id/cues`, registered in `navigation.ts` beside Looks and Palettes
(the navigation plan's Library section), and in `lib/windowViews.ts` so the Screens sheet can open it. It reclaims the
path the `/cues` redirects hold today.

Since Nav.1 the row is `section: "library"` after Looks, with `hiddenInVenue: true` as the rest of
Library, and `navigation.test.ts` pins Library's ids and the venue-hidden ids, so both lists gain
`cues`. `lib/windowViews.ts` is seven views today (`windowViews.test.ts` pins them), so Cues makes
eight and the Screens sheet's list does change. The `/cues` redirects are now `App.tsx:234-253`:
`/projects/:id/cues…` go to `CuesLegacyRedirect`, bare `/cues` to `ShowRedirect`. By the frontend's
routes rules, bare `/cues` becomes the table's "which project?" redirect, and `legacyRedirects.tsx`
and the rule-3 sentence naming `/cues` change with it.

- **Columns:** name; *In*, each entry as `Stack · Q14` (a chip per entry, *Loose* when none); fade;
  layers; FX; scenery; events; *Used by*, pads, slots and bindings; notes. The live state comes from
  `cueHolds.state` (D11's two looks).
- **Filters:** a stack, *Loose only*, *Shared only*, *On a pad*. Sort and filter are a query
  string, as the venue-visit plan's S14 tables are.
- **Multi-select:** *Add to stack…* (shares, appended or by number), *Duplicate*, *Delete…* (the
  D8 confirm). *Copy from another show…* (§3.8) is the library row's verb instead (§10, 22).
- **A row opens** the cue in its first entry's stack. A loose cue opens `CueEditor` in a
  right-hand sheet, as `stage/OwnerEditor` opens a cue (R2.4, `a4ab679`); with no entry it says
  the cue has no number or follow.

Built by session 5 (`a410c6c5`): the bar is `CueActions` as its fourth host, so a multi-select
carries the set's *Duplicate*, *Delete…*, *Add to stack…*, *Move to stack…* and *Remove from stack*,
and no verb of the table's own. The library row draws no create verb. Another project's table reads
only and draws no action set. *Copy from another show…* is session 6's, in the library row.

### 3.8 Copy from another show (D9)

`POST /api/rest/projects/{id}/cues/copy-from {fromProjectId, cueIds, stackId?, after?, dryRun?}`,
into the project in the path. It reuses `copy_to_project`'s copier, cut down to cues. That copier's
cue half is inline in the tool's private `copy` (`ai/VenueSetupTools.kt`), beside a second
field-by-field cue copy in `copyCueRecord`, so the session lifts one cross-project cue copier that
`copy_to_project` calls too:

- Looks and templates link by name, else are copied. Speed masters map by index, scripts by name.
  Since S16 (`8236eb9`) that includes the cue's own `speedMasters` list. `copy_to_project` maps the
  list, the layers' and the effects' masters against one read of the target's bank and reports each
  one the target lacks. `mapCueSpeedMasters`, which `copyCueRecord` calls, seeds the target's default
  bank first and only logs, which is why `a3949db3` moved `copy_to_project` off it. One slip to leave
  behind: `copy_to_project`'s layer and effect message names a master as its index plus one, but
  `masterIndex` is 1-based, as its cue-tempo message has it.
- **Venue records match by uuid first.** Two shows on one venue share its records' uuids across
  versions, so a cue event's patch or a scenery element resolves even when the shows pin different
  versions. A show's own patch maps by key and fixture type, and its own element by name.
  `copy_to_project` matches neither by uuid today: a cue event's patch goes by key and fixture type,
  a scenery element by name.
- **Fixture and group targets** are checked against the target's effective rig
  (`RigScope.of(target)`) and listed as unresolved. The rows are kept, and `AssignmentHealth` marks
  them, as an Update's dead references are.
- A cue event or a scenery row that doesn't resolve is **dropped and reported**. Anchors are never
  copied.
- **An effect parameter naming a palette** (`tmpl:{uuid}`: a colour reference, and since R4.7
  Palette Steps' `palettes` list, `b56faf0`) maps as a layer's palette does, by name, else copied.
  `copy_to_project` copies parameters as they are today, so they name the source's palettes.
- The answer is `{copied: [{fromCueId, cueId, entryId?}], unresolved: [...]}`; `dryRun` is the same
  run rolled back.

It replaces REST `POST /cues/{id}/copy` and its layer FK leak. A same-project **Duplicate** is
`POST /cues/{id}/duplicate {stackId?, after?}`: a new cue with the scenery and events, appended
after the source's entry in that stack, or loose.

> **Revised by R2** ([`show-cue-editing-plan.md`](show-cue-editing-plan.md) D12): Duplicate appends the
> copy to the **end** of the stack, `POST /cues/{id}/duplicate {stackId?}` with no `after`, and R2's
> session 3 builds the route, so session 6 here builds only the cross-project copy.
>
> **Landed with R2 session 3.** `POST /cues/{id}/duplicate {stackId?}` copies through
> `copyCueRecord` (`routes/projectCuesHelpers.kt`), which the REST copy now shares, so session 6's
> cross-project copy starts from it.

**Any project can be the source** (D18). The venue's uuid matching applies only when both projects
are on the same venue; otherwise every record falls back to key and name.

### 3.9 Sync (P4, P5, P6)

**A `formatVersion` bump**, from session 3. It takes its number by venue-visit P5 (28 when this plan
was written). Below, *vN* is that version:

- `cues/{uuid}.json` drops `cueStackUuid`, `sortOrder`, `cueNumber`, `cueNumberAuto`,
  `autoAdvance`, `autoAdvanceDelayMs` and `cueType`.
- **`cueStackEntries/{uuid}.json`**, one file per entry: `{uuid, cueStackUuid, cueUuid?, kind?,
  label?, ordinal, cueNumber?, autoAdvance?, autoAdvanceDelayMs?}`. `cueNumber` is written only when
  explicit, or when a reader could not derive it (P5 as amended); `kind` is omitted for a cue entry. One file per entry is the merge story: two desks
  adding to one stack add two records, a drag between two entries rewrites one file, and a
  renumber rewrites only the entries whose explicit number changed.
- `promptBookAnchors/` carries `entryUuid` instead of `cueUuid`.
- **A v5 to vN−1 archive still imports.** Each cue becomes one entry in its `cueStackUuid` stack, with
  `ordinal = sortOrder`, its number (explicit only when `cueNumberAuto` is false) and its follow. A
  MARKER cue becomes a marker entry labelled with its name. A cue with no stack is loose, so the
  "Unsorted" fallback goes. An anchor moves to its cue's one entry.
- **After a merge, two explicit numbers can clash** in one stack (two desks each typed 14A). The
  importer keeps the number on the entry with the lower uuid and makes the other auto, and the sync
  result names it. A unique-index failure must never abort a pull.
- **An older desk refuses vN**, through `SUPPORTED_FORMAT_VERSION`, as every bump since v6 has.
  `minReader` stays 5.
- `ExportUuidRemapper`, `ProjectCloner` and `KeptUuidCopy` need no cue code: the entries' links
  are `…Uuid` fields.
- `copy_to_project` copies whole stacks, so a cue two copied stacks share is copied once and
  shared in the target.

### 3.10 MCP tools

Every tool names a cue by id, as today, and **an entry by `entryId` or by the stack's `number`**.
That is the number an operator reads off the Show view, and it is unique in its stack.

| Tool | Change |
|---|---|
| `create_cue` | Loose by default; `stackId` with an optional `number` and `after` also adds an entry. No more "Unsorted". |
| `add_cue_to_stack` | **Shares**: `{stackId, cueId, number?, after?}`, answers the entry. The description loses "moved". |
| `remove_cue_from_stack` (new) | `{stackId, entryId \| number}`. Never deletes the cue. |
| `delete_cues` (new) | `{cueIds, force?, dryRun?}`, the D8 guard. |
| `copy_cues` (new) | `{fromProjectId, cueIds, stackId?, dryRun?}`, the §3.8 copy. |
| `build_cue_stack` | A row may name an existing `cueId` to share it instead of defining a cue. |
| `activate_cue_stack`, `set_standby` | Take `entryId` or `number` in place of `cueId`. |
| `apply_cue`, `stop_cue` | Done in session 2: a loose hold and its release (D6), current project only. |
| `record_cue` | CREATE takes an optional `stackId`; without one the cue is loose. |
| `get_current_state` | `cues` lists each cue's entries; `cue_run` gains entry ids; a new `cue_holds`. |
| `describe_rig` | Each cue lists `in: Act 1 Q14, Act 2 Q3`, or `loose`. The briefing says a cue may be shared, and that delete is explicit. |
| `get_prompt_book`, `mark_up_prompt_book` | Anchors name an entry: `{stackId, number}` or `entryId`. |
| `delete_cue_stacks` | The stack's cues stay; the answer counts those left loose. |
| `include_into_programmer` | Names the cue, and optionally the entry it is included from (`entryId` or `{stackId, number}`), so the include target carries it as the Show view's does (session 4). |
| `update_from_programmer`, `set_cue_events`, `set_scenery` | Unchanged: they name the cue. Update's preview lists each cue once, with its entries. |

Session 4 (`2d82cea0`) already made `create_cue` loose, `add_cue_to_stack` share (its description
too) and `delete_cue_stacks` leave its cues and count them (`cuesLeftLoose`, its description too).
What those rows still need is `create_cue`'s `stackId`, and `add_cue_to_stack`'s `number` and
`after` (it takes `sortOrder`) and an answer naming the entry.

S16 (`8236eb9`, `a3949db3`) gave `create_cue` and `build_cue_stack` a `speedMasters` list, each
master by `masterIndex` or uuid (`cueSpeedMasterItemSchema`); no row above changes for it. R4.5
(`98a226a6`) added no tool, so a stack's effect size has none and this table adds none.

## 4. UX

R2 designs the Show view's cue editing and its standard action set. This plan settles only what
the model forces:

- **A shared cue is marked** wherever an entry is drawn: a chip `in 3 stacks` on the card and the
  table row, listing the others on hover or tap.
- **The card's Remove is *Remove from stack*.** It needs no confirmation, since nothing is lost: the
  cue is still in the Cues table.
- ***Add to stack…*** on a card or a table row shares the cue. ***Move to stack…*** is add, then
  remove. A drag between stacks in the stack list moves.

  > **Revised by R2 session 3** (Chris, 2026-10-16): *Move to stack…* moves the entry whole, through
  > `POST /cue-stacks/{id}/entries/{entryId}/move {toStackId}`, so its uuid, explicit number, follow
  > and prompt-book anchor go with it. Add then remove dropped all four.
- **A cue pad's ring** follows D11: solid, outline, or off. A pad's face shows the cue's name, and
  its numbers when it has any.
- **The busk view's "open cue"** (`BuskingView.tsx`'s `onInspect`) opens the cue's first entry, or
  the Cues table filtered to it when it is loose.

## 5. Implementation — seven sessions

Each session is one PR (P1). Sessions 1–3 change no behaviour an operator can see, except that
pads, slots and `FireCue` run loose from session 2 (D6).

### Session 1 — entries and the migration (backend + frontend) — done, `baf05837`

- **The migration** (D5), `state/CueModelMigration.kt`, called from `State.initDatabase`:
  - It runs when `cues` still has `cue_stack_id` (`PRAGMA table_info`). It runs **before**
    `createMissingTablesAndColumns`, because the reconciliation cannot add the NOT NULL `entry_id`
    to a non-empty anchors table. `InstallBootstrap.kt`'s note says "after the schema is created"
    for the old ones; this one creates its own table, and its KDoc says why it is the exception.
  - **Backup first:** `VACUUM INTO '<db>.pre-cue-model-<utc>.db'` on a raw connection, outside
    Exposed's transaction.
  - One transaction: create `cue_stack_entries`; one entry per cue, from its stack, sort order (as
    a double, renormalised per stack to `1.0, 2.0, …` by `(sort_order, id)`), number, auto flag and
    follow; a MARKER cue becomes a marker entry labelled with its name, and the cue and its children
    go. Anchors are re-pointed to the entry. `cues` and `prompt_book_anchors` are rebuilt from
    `SchemaUtils.createStatements` (so their shape is exactly the model's), the rows are copied by
    id, and `uq_cue_number_per_stack` is dropped.
  - Idempotent: a second start finds no `cue_stack_id` and does nothing.
  - Recover `StateMigrations.kt`'s seam from the full history first, for its ordering lesson.
- **The model:** `DaoCueStackEntries`, entry DTOs, `renumberAutoEntries` (§3.3), and every
  read and write of order, number, follow and type moved onto entries. The create, add, reorder,
  sort and delete handlers keep their behaviour, with **one entry per cue** (P4): `add-cue` still
  moves a cue.
- **The exporter still writes today's version** from the one entry each cue has; a marker entry is written as a
  MARKER cue whose uuid is the entry's. There is no sync change.
- **Frontend:** `CueStackCueEntry` gains `entryId`, and reorder, standby and go-to send entry ids.
  Markers render from entries.
- **Tests:**
  - The migration runs over a database built from the pre-change DDL, checked in as
    `src/test/resources/db/pre-cue-model.sql`, with `RichProjectFixture`'s stacks, a marker and an
    anchor. Every cue keeps its id, uuid and children; every entry matches its old row; the
    anchors re-point; a second run is a no-op.
  - Numbering: the venue-visit S3 cases, on entries.
- **Docs:** `cue-stacks-engineering.md` and `cues-engineering.md` §"Data Model" and §"Cue Stack
  Membership"; CLAUDE.md §Database (a migration exists again, and where).
- **Then:** Chris runs the migration on a copy of the desk's database before the desk itself
  (§9).

### Session 2 — holds (backend + frontend) — done, `595aced3`

- **`fx/CueHolds.kt`** (P3) and every path in §3.4 through it: the manager, the crossfade, the
  assignment layer, the trigger manager, timed effects, stomp, stop, delete, events, scenery and the
  show carry.
- **Loose holds** (D6, D10): `/apply`, `/stop`, `apply_cue`, `stop_cue`, busk press, cue slots,
  `FireCue`, the hand. `BuskPressService`'s `PressRecord.Cue` loses its stack.
- **Entries at runtime** (P2): standby, next, GO TO, preview and the run state by entry id.
- **Wire:** `cueRunStateChanged`'s entry ids, `cueHolds.state`. **Frontend:** the pad ring (D11),
  `useCueRings` from `cueHolds.state`, replacing `useActiveCueIds`.
- **Tests:** two entries naming one cue, inserted directly, in two stacks and twice in one stack.
  Stack A moving on leaves the cue on stage while B holds it; it fades out only on the last
  release; priority is the highest hold's; a loose press beats both stacks; a crossfade between two
  entries of the same cue moves nothing; Preview keeps a cue another stack holds; events fire once,
  on the first hold (D15). The stale
  live-apply comments in §1 go.
- **Docs:** `cue-stacks-engineering.md` §"Runtime", `cues-engineering.md` §"Priority" and
  §"Active Cue Tracking", `lighting-composition-model.md` (holds and the loose band),
  `websocket-engineering.md` (`cueHolds.state`).

### Session 3 — sync vN (backend) — done, `4a0d116f`, `3ea86265`, `1be6a69c`, `d8aba579`

- §3.9: `cueStackEntries/`, ordinals (P6), auto numbers not exported (P5), anchors by entry, the
  pre-vN reader, the duplicate-number rule on import.
- **Tests:** `ProjectRoundTripTest` over the extended fixture; a pre-vN archive (with a marker, an
  anchor, a stack-less cue) imports to the right entries; two desks inserting into one gap both
  keep their entries in a stable order; a number clash after a merge resolves and is reported; a
  drag changes one file.
- **Docs:** `sync-engineering.md` §"Version N" and §"Ordinal contract"; `followups.md` narrows
  `FU-SYNC-ORDINAL-DOUBLE` to the other tables and corrects `FU-SYNC-FORMAT-MIGRATIONS`' stale
  version numbers.

### Session 4 — sharing and explicit delete (backend + Show view) — done, `2d82cea0`

- **Add shares** (D1): `add-cue` and `add_cue_to_stack` add an entry. *Move to stack* is add then
  remove. A cue may be in no stack, or twice in one (D7).
- **Remove from stack** (live entries included, D16), **stack delete leaves cues** with its
  `deleteLooseCues` offer (§3.6, D17), **explicit delete** with its guard (D8), and bulk delete.
- **Record without a stack** makes a loose cue, and `create_cue` stops using "Unsorted".
  `getOrCreateUnsortedStack` goes once session 6 has removed the REST `/cues/{id}/copy` that still
  calls it; until then that copy keeps its stack. `POST /cues` stops requiring `cueStackId`.
- **`soleEntryOf`'s callers move off it** (session 1's P4 bridge, which answers null for a cue in
  several stacks). Each takes an entry from its caller, or lists every entry: the cue routes' number
  and follow, `add-cue` and `add_cue_to_stack` (the "Add shares" bullet above), Record and Update's
  scenery, Update's preview, `BuskCueDto`, the venue Update's label and `CueDetails`. The include
  target gains R1 §3.5's `entryId?`, and its indicator reads it. `GET /cues` keeps `cueNumber` and
  `cueStackName`, null for a shared or loose cue, until session 5 moves the list and `RecordCuePicker`
  to entries together. The briefing and `mark_up_prompt_book` move in session 7 and the REST copy goes
  in session 6, so the function itself goes in session 7; the importer's call is session 3's.
- **An entry's number and follow are written on the entry**, not through the cue's `PUT` / `PATCH`
  (today they go through `soleEntryOf`, so on a shared cue the card's number edit would be dropped).
  The session names the route.
- **Anchors by entry on REST and in the book** (D4): the anchor routes
  (`/prompt-book/anchors/{cueId}` today) and `useBookAnchors` name an entry.
- **Frontend:** the `in N stacks` chip, *Remove from stack*, *Add to stack…*, *Move to stack…*
  (§4), and the stack delete's confirm with its unticked *Also delete* (D17). The Record sheet's
  stack select (venue-visit S10) gains *No stack* in session 5, with the Cues table (§10, 19).
- **Tests:** a cue in two stacks edited once reads the same in both; removing an entry renumbers
  the stack it left; removing the live entry moves nothing until the next GO, which fires its
  follower (D16); `deleteLooseCues` deletes only the cues no other stack holds (D17); stack delete leaves its cues loose and their other entries untouched; delete
  without force names every reference, and with force leaves nothing pointing at the cue.
- **Docs:** `cues-engineering.md` §"Delete" and §"Cue Stack Membership" (the P4 paragraphs go);
  `cue-stacks-engineering.md`'s overview and its stack-delete line; the `soleEntryOf` KDoc (the callers
  left); CLAUDE.md's
  cue endpoints. MCP `delete_cue_stacks` ("with all their cues") and `add_cue_to_stack` ("moved")
  describe the old behaviour until session 7.

### Session 5 — the Cues table (frontend + backend) — done, `a410c6c5`

- §3.7, with the list endpoint gaining *Used by*; each cue's entries it has carried since session 4
  (`CueDetails.entries`). `RecordCuePicker`
  (venue-visit S10) reads the same list and labels rows from `cueNumber` and `cueStackName`, so it moves
  to the entries too.
- **The Record sheet's stack select gains *No stack*** (§10, 19): Record CREATE without a stack has
  made a loose cue since session 4, and this session's table is where one is found.
- **A loose cue's pad opens the table.** The busk view's "open cue" opens a cue's first entry, and
  does nothing for a loose cue since session 4 (§4); it opens the Cues table filtered to that cue.
- **The loose cue's sheet mounts `CueEditor`** (show-cue-editing plan §4.7): R2 session 4 landed
  first, so the sheet is the editor from the start. Mounted with no entry it edits the cue and says
  it has no number or follow (`frontend/src/components/cues/editor/CueEditor.tsx`; `stage/OwnerEditor`'s cue
  sheet is the precedent).
- **D8's cue confirm** exists: R2 session 3 built `CueDeleteDialog`
  (`frontend/src/components/cues/CueDeleteDialog.tsx`) on `cueDeletePreview`, one confirm for one cue
  or several that lists every entry, pad, slot, anchor and binding, then deletes with `force`. The
  table's multi-select *Delete…* mounts it.
- **So does the action set.** R2 session 3's `CueActions` (`frontend/src/components/runner/`) does
  *Duplicate*, *Delete…*, *Add to stack…* and *Move to stack…* over several rows, and takes a loose
  row (`entryId` and `stackId` null). The table's bar mounts it as a fourth host, with rungs of its
  own in `FOLDS`, rather than drawing its own buttons. It takes the show-editing lock (`locked`).
  It **honours that lock while the show runs**, as the cue sheet does (§10, 20).
- **The home's cue count moves to this row** (Nav.4, `8ac4817`; frontend `CLAUDE.md` §"A project's
  home"). Show's card counts its stacks and the project's cues (`stacksAndCues` in
  `navigation.ts`) until the Cues row lands; then the row takes `homeCount: (c) => c.cues` and
  Show's card its stacks. The row is Library, so it has no `needsRig` (Nav.2) and is drawn off the
  rig. `useHomeCounts` reads the cue list only on the rig, so it reads it everywhere for this row,
  as it does the Looks (`GET /cues` is `withProject`). `ProjectHome.test.tsx` pins *2 stacks · 35
  cues*.
- **Tests:** frontend tests for the filters and the multi-select actions; a route test for the
  list's shape.
- **Docs:** `frontend/CLAUDE.md`'s views (§Navigation Registry, its ⌘K count of the seven views, the
  routes rules, and §Windows' `WINDOW_VIEWS` entries).

### Session 6 — copy from another show (backend + frontend) — done, `6ffcb054`

- §3.8: the route and *Copy from another show…* in the Cues table. The REST `/cues/{id}/copy` goes,
  and with it `getOrCreateUnsortedStack`, which only that copy still calls (session 4). No client
  calls the copy.
  *Duplicate* and its route landed with R2 session 3, so this session builds only the cross-project
  copy, from the `copyCueRecord` helper the duplicate and the old copy share. That helper copies
  scenery and events only within one project, and a layer's Look and palette, an effect's
  parameters and every layer's and effect's speed-master uuids as they are, so the copy maps all
  five (§3.8; the masters by index). Since S16 it also maps the cue's own speed masters by index,
  through `mapCueSpeedMasters`, which logs a dropped one and reports nothing; the copy reports it.
- **`FU-CUE-COPY-ROUTE` closes here.** S16's review (`a3949db3`) filed it against the REST copy:
  delete the route, `CopyCueRequest` / `CopyCueResponse` and their tests (`CueRoutesTest`,
  `CueLayerSourceInvariantTest`, the copy step in `CueSpeedMastersTest`), and move the item to
  Completed. `unresolved` names every speed master the target lacks, as `copy_to_project`'s does.
- **Placing a copy in a stack** does what Duplicate's handler does: `placeCueInStack`, the holds
  restamped from `stackEntryPriorities`, `cueListChanged` and `cueStackListChanged`, and
  `announceCuePadsChanged` over the stack's cues, so a neighbour's relabelled auto number reaches its
  pad (`FU-BUSK-PAD-STALE-NUMBER` lists the handlers that miss it).
- **Where the dialog opens** (§10, 22). *Copy from another show…* is a verb in the library row of the
  current project's Cues table, not a multi-select action: session 5's bar is `CueActions` over the
  shown project's cues, and the source cues are another project's. It opens a dialog that picks the
  source project, then its cues, a stack and where, with the dry run's answer before the copy.
  Another project's table keeps no action set. It honours the show-editing lock while the show
  runs, as the table's bar does (§10, 20).
- **Tests:** two shows on one venue at different versions: a cue event on a venue patch and a
  scenery row on a venue element both resolve by uuid; a show's own patch resolves by key; a
  standalone source resolves by key and name alone (D18); an unresolved fixture is kept and marked;
  a speed master the target lacks is reported; a dry run writes nothing.
- **Docs:** `cues-engineering.md` gains §"Copying"; its REST table's `/cues/{cueId}/copy` row, the
  `CopyCueDialog` line under UI Components (that dialog is already gone) and §"Speed masters on a
  cue"'s sentence on the REST copy go. `fixtures-engineering.md` §"Shows on a venue".

### Session 7 — the MCP tools (backend)

- §3.10, with the briefing (`RigBriefing.keyConcepts`) rewritten for shared cues and explicit delete
  (session 2 wrote the loose hold's part).
- **`soleEntryOf`'s last callers**, which name a cue's number only while it has one entry: the
  briefing's cue list (`RigBriefing.kt`), `mark_up_prompt_book` (`soleEntryOfCueId`, so it refuses a
  shared or loose cue) and `RigKeyReferences`' cue label (its own `singleOrNull`). Each lists every
  entry or takes one from its caller, and the function goes. Two tests call it too
  (`testsupport/CueEntries.kt`, `CueApplyDataBuilderTest`), and the exporter's v32 comment names it.
  The MCP stack tools' `entryInStack` bridge (the first entry of the cue in that stack, in
  `activate_cue_stack` and `set_standby`) gives way to `entryId` or `number` (§3.10). The function
  stays: Record CREATE's answer (`programmerSurface.kt`) and Update's preview labels
  (`programmerUpdate.kt`) take their stack from the caller and call it.
- **Stale words.** `record_cue`'s description still says CREATE makes a cue "in a stack", though
  `cueStackId` has been optional since session 4. `add_cue_to_stack` and `build_cue_stack` are two of
  the handlers `FU-BUSK-PAD-STALE-NUMBER` lists as not announcing a relabelled pad.
- **`include_into_programmer` names an entry**, so an AI include sets the target's `entryId` as the
  Show view's does.
- **No MCP tool moves an entry.** Since R2 session 3 the UI's *Move to stack…* moves the entry
  whole (`POST /cue-stacks/{id}/entries/{entryId}/move {toStackId}`), but a model can only add then
  remove, which drops the explicit number, the follow and the anchor. §3.10 **gains a move tool**
  on that route (§10, 21), answering the moved entry.
- **Tests:** each new tool, and each changed one naming an entry by number.
- **Docs:** `mcp-engineering.md`, `cue-stacks-engineering.md` §"MCP tools".

## 6. Migration

- **One startup migration** in session 1 (D5): additive for `cue_stack_entries`, a rebuild for
  `cues` and `prompt_book_anchors`, with a backup copy first. On a fresh database it does nothing.
- **A `formatVersion` bump** in session 3 (§3.9). Older archives import into the new model.
- **The Windows MSI** runs the same migration on upgrade. It is idempotent and needs no network.

## 7. Explicitly out of scope

- **Editing a cue in the Show view**, and the standard action set for cards and tables. Those are
  R2's, which follows this plan.
- **Per-stack fade overrides** (D2). If one is ever needed, it is a nullable column on the entry.
- **Double ordinals on the other nine ordered tables** (P6; `FU-SYNC-ORDINAL-DOUBLE` stays open).
- **A merge that understands numbers**, beyond the clash rule in §3.9.

## 8. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-SYNC-ORDINAL-DOUBLE` narrowed to the other tables (session 3).
- `FU-CUE-BINDING-SWEEP`: whether a forced cue delete should remove `FireCue` bindings rather than
  leave them reporting `MissingCue`, gated on an operator finding a dead binding confusing
  (session 4).

## 9. Verification

Beyond the unit suites, at the desk:

- **Session 1.** On a copy of the desk's database: the migration runs, every stack lists the same
  cues with the same numbers, the prompt book's anchors sit where they did, and a second start does
  nothing. Then on the desk itself, with the backup file kept.
- **Session 2.** Put one cue in two stacks (through the API), GO both, step one on: the state
  stays. A busk cue pad over a running stack wins and shows solid; the same cue live only in the
  stack shows outline.
- **Session 3.** Sync a show across session 3's bump on one desk and pull it on a second; insert a cue into
  one gap on both, sync both, and check both keep it in the same order.
- **Session 4.** Share a cue between two stacks, edit it once, check both. Delete a stack and find
  its cues loose. Delete a cue in use and read the list.
- **Session 5.** Find a loose cue in the Cues table, add it to a stack, delete two cues at once.
- **Session 6.** Copy a cue with a cannon event from Panto 2027 into Christmas Concert 2026 on the
  Commemoration Hall, one version apart.
- **Session 7.** Ask Claude to build a stack that shares a cue with another, and to delete a cue.

## 10. Questions

Answered by Chris, 2026-10-14, in four rounds:

1. **What belongs to an entry?** Position, number and follow; fade and everything else stays on the
   cue (D2).
2. **A cue live in two stacks, one moves on?** Held until the last hold goes (D3).
3. **Prompt-book anchors?** Per entry (D4).
4. **Existing data?** Recover the migration seam (D5).
5. **Pads, slots, `FireCue`, the hand?** They name the cue and run it loose (D6).
6. **The same cue twice in one stack?** Yes (D7).
7. **Deleting a cue in use?** 409 and force (D8).
8. **Copy scope?** Mapped by key (D9).
9. **Where does a loose cue compose?** Above the stacks (D10).
10. **A pad whose cue is live only in a stack?** Outline; a press toggles the loose hold (D11).
11. **Markers?** Labelled entries (D12).
12. **Where is the all-cues table?** Its own nav item (D13).

13. **Events on a GO into a cue already live?** Only when it comes on (D15).
14. **Removing the live entry?** Allowed; the stage is unchanged until the next GO (D16).
15. **A stack delete and its loose cues?** Offered, unticked (D17).
16. **Copy's source?** Any project (D18).

Answered by Chris, 2026-10-16, after session 3's review:

17. **Auto numbers across a sync?** A number D11 holds that position alone would not give is written
    and read as explicit, so every desk shows the same labels and a pull never relabels one (P5, as
    amended; `heldAutoNumbers`). Such a number then stops renumbering by itself.
18. **Ties at the v32 startup step?** Kept in the order the desk played them, by id, so nothing on
    stage moves; desks that played a tie differently settle it on their first sync.

Answered by Chris, 2026-10-16, during session 4:

19. **Does the Record sheet's stack select gain *No stack*?** Not yet: session 5 adds it with the
    Cues table, where a loose cue recorded that way can be found. The backend takes a stackless
    CREATE from session 4.

Answered by Chris, 2026-10-17, at venue-visit course check K3:

20. **Does the Cues table honour the show-editing lock while the show runs?** Yes, as the cue sheet
    does. Read-only only off the rig, as the Looks sheet is, was declined.
21. **Does an MCP tool move an entry to another stack?** Yes, on R2 session 3's move route, so the
    number, follow and anchor go with it. Leaving models to add then remove was declined.

Answered by Chris, 2026-10-17, at venue-visit course check K4:

22. **Where does *Copy from another show…* open?** A verb on the current project's Cues table,
    opening a dialog that picks the source project and its cues. A *Copy here…* action over a
    selection on another project's table, and both, were declined.
