# Cue Stacks Engineering Documentation

This document describes the Cue Stack system — ordered containers for sequential cue playback with looping and per-cue auto-advance and crossfades of values and effects.

## Overview

A **Cue Stack** is a named, project-scoped entity that groups cues into an ordered sequence for theatre-style playback:
- **An ordered list of entries** (`cue_stack_entries`) — each a cue's place in the stack, or a marker
- **Looping** — wraps from last cue back to first (and vice versa)
- **Per-cue auto-advance** — each cue can individually enable timed progression to the next cue
- **Per-cue crossfade** — each cue can configure its own fade-in duration and easing curve
- **Multiple active stacks** — several stacks can be active simultaneously

Key behaviours:
- A cue may be in any number of stacks, twice in one, or in none — a **loose** cue (cue model plan
  D1, D7). A stack's list names cues; it never owns them: removing an entry or deleting the stack
  leaves the cue (`cues-engineering.md` §"Delete"). The Cues table (`/projects/:id/cues`, cue model
  plan §3.7) lists every cue once from `GET /cues`, each with its entries and its `usedBy` — the busk
  pads, cue slots and `FireCue` bindings naming it — which is where a loose cue is found
- Activating a stack applies the first (or specified) cue's effects
- Advancing steps through the list's cue entries in order, markers skipped
- Deactivating removes all effects tagged with the stack's ID

## Data Model

### Database Tables

```
cue_stacks
├── id (auto-increment PK)
├── name (varchar 255)
├── project_id (FK → projects)
├── loop (boolean, default false)
└── unique(project_id, name)

cue_stack_entries (cue model plan session 1 — `models/cueStackEntries.kt`)
├── id (auto-increment PK)
├── stack_id (FK → cue_stacks)
├── cue_id (FK → cues, nullable — null exactly when kind is MARKER)
├── kind (varchar 20 — CUE or MARKER)
├── label (varchar 255, nullable — a marker's text)
├── ordinal (double, default 0.0 — the list's order; ties by uuid, `ENTRY_ORDER`)
├── cue_number (varchar 20, nullable — free-form display label)
├── cue_number_auto (boolean, default false — cue_number was derived from position)
├── auto_advance (boolean, default false — the follow)
├── auto_advance_delay (Duration, nullable — nanoseconds on disk)
├── uuid
└── index(stack_id, ordinal), index(cue_id)

Partial unique index: uq_entry_number_per_stack (stack_id, cue_number)
                      WHERE cue_number IS NOT NULL AND kind = 'CUE'
```

The cue itself (`cues`, `cues-engineering.md` §"Data Model") keeps only what it is — name, fade,
notes, stomp, children; everything that is a matter of the list is its entry's. A list plays in
`(ordinal, uuid)` order (`ENTRY_ORDER`, cue model plan P6) — never by id, which each desk mints for
itself. Every handler writes the order through `writeEntryOrder`, which changes as few entries as it
can (`planEntryOrdinals`): the entries already in order keep their ordinals, and an insert or a drag
takes the midpoint of its neighbours, an append the next whole number. A handler names the entry it
just made or moved in from another stack as **fresh**, so that entry's placeholder ordinal is never
taken for one already in place. It never writes a tie, and
renormalises the stack to `1.0, 2.0, …` only when doubles leave no room. The wire still speaks of a
**position** — `sortOrder` on `NewCue`, `add-cue` and the DTOs is the entry's index in the list,
markers counted.

#### The startup migration

The entries arrived by a one-off migration, `state/CueModelMigration.kt` (cue model plan D5), which
`State.initDatabase` runs **before** the schema is reconciled, while `cues` still has
`cue_stack_id`. It backs the database up with `VACUUM INTO '<db>.pre-cue-model-<utc>.db'`, then in
one transaction makes each cue an entry (its stack, its sort order renormalised per stack by
`(sort_order, id)`, its number, auto flag and follow), each MARKER cue a marker entry labelled with
its name and keeping its uuid (the cue and its children, pads and slots deleted), re-points every
prompt-book anchor to its cue's entry (dropping one whose cue is gone), rebuilds `cues` and
`prompt_book_anchors` from the model and drops `uq_cue_number_per_stack`. A converted database has
no `cue_stack_id`, so a second start does nothing. Its KDoc explains why it is the exception to
`InstallBootstrap.kt`'s "after the schema is created"; `CueModelMigrationTest` runs it over
`src/test/resources/db/pre-cue-model.sql`, the old shape frozen.

#### In the sync archive

From `formatVersion` 32 (cue model plan session 3, `docs/sync-engineering.md` §"Version 32") each
entry is its own record, `cueStackEntries/{uuid}.json`: its stack, its cue (or `kind: MARKER` and a
label), its ordinal, its follow, and its number when explicit — every reader derives the auto
numbers from position, so a drag is one changed file and a renumber touches only the entries whose
written number moved. An auto number a reader could not derive, one D11 holds beside an explicit
renumber waiting for *Fix Order*, is written too and read back as explicit (`heldAutoNumbers`), so a
pull or a clone shows the labels this desk does. An anchor names its entry. A cue file says nothing about where the cue sits.

A v5–v31 archive still imports: each cue becomes one entry in its stack, named with **its cue's
uuid** at its 0-based place, and a MARKER cue a marker entry; a cue with no stack is loose. The
entries a desk held before the bump were renamed the same way once, at startup
(`state/CueEntryUuidMigration.kt`), and each given its 0-based position as its ordinal, so two desks
write the same file for the same place. Two explicit numbers alike in one stack after a merge keep
the number on the lower uuid and make the other auto, and the sync result names it.

### Entry kinds

- **CUE** — places one cue. Participates in advance, go-to and standby.
- **MARKER** — an inert, labelled section divider with no cue behind it (cue model plan D12): made,
  renamed and removed through `/{stackId}/markers`, never through `/cues`. Invisible to `advance`;
  `go-to` and `standby` naming one are HTTP 400. Not moved by `sort-by-cue-number`. A row's kind
  reaches the wire as `cueType` (`STANDARD` | `MARKER`), the REST vocabulary — the v32 archive says
  `kind` on the entry record, and only a pre-v32 archive's cue file says `cueType`.

### Cue Number Model

See [`cueNumbering.kt`](../src/main/kotlin/uk/me/cormack/lighting7/routes/cueNumbering.kt); the
frontend mirrors it in `src/lib/cueNumber.ts` (lighting-react).

A number is parsed as **prefix + decimal run + letter suffix** — `S1-3.1` → `("S1-", [3,1], "")`,
`Pre-show 2` → `("Pre-show ", [2], "")`, `14A` → `("", [14], "A")`. The prefix is the **group key**,
and numbers are only ever compared against others in the same group. So
`["Pre-show 1", "Pre-show 2", "T2-1", "S-1", "S-2"]` is in order despite the groups not being
alphabetical, while `[…, "S-2", "S-1"]` is not.

| Class | Rule | Examples | Behaviour |
|-------|------|----------|-----------|
| **Numbered** | parses to a decimal run | "1", "1.5", "14A", "S1-3.1" | Sorted within its prefix group |
| **Unparseable** | no decimal run at all | "intro", "A" | Singleton group — never moved |
| **Unnumbered** | `cue_number` is null | — | Given an auto number (below) |

This replaced an earlier "first character must be a digit" participation rule, which pinned every
prefixed number — a stack numbered `S1-1`, `S1-2`, … had *nothing* eligible to sort.

### Auto Numbering

A cue entry with no explicit number is given one derived from its position, flagged with
`cue_number_auto`. `renumberAutoEntries(stack, before)` recomputes them and is called from every handler
that changes a stack's membership, order or numbers: cue create / delete / copy, `reorder`,
`add-cue`, Remove from stack (each stack an entry left), `sort-by-cue-number`, the marker routes,
Record's new cue, the AI's `build_cue_stack` (once, after it has placed every cue), and the entry
`PATCH` when it changes a number. Marker entries take no number and are not an auto
cue's neighbours.

**An auto number stays put while its cue does** (venue-visit plan D11). Each handler takes
`cueEntryIds(stack)` *before* it changes the list and hands it to `renumberAutoEntries`; an auto cue
still at the same index keeps the number it holds (unless an explicit number has claimed it), and
only the cues that are new, moved, or shifted by an insert or a delete are labelled again. A number
edit moves nothing, so it passes the order as it stands and no other auto cue changes: in five auto
cues, cue 2 set to `4.5` reads 1, 4.5, 3, 4, 5, the frontend's out-of-order check sees the descent
and offers *Fix Order*, and *Fix Order* moves that cue rather than renumbering anything — the cues it
passed take numbers from where they land (1, 2, 3, 4.5, 5). Cue 3 set to `10` reads 1, 2, 10, 4, 5
and sorts to 1, 2, 3, 4, 10; cue 3 set to `1` takes cue 1's number, so cue 1 — released by
`releaseAutoNumber` — takes the highest free integer below its neighbour and the stack reads
0, 2, 1, 4, 5, sorting to 0, 1, 2, 4, 5. `CueNumberingTest` pins the three on the rule and
`CueNumberingRouteTest` through the routes; `EntryNumberingTest` runs them again with a marker in
the list (cue-model plan D14).

The cues that do need a label are labelled in maximal runs, each from the nearest preceding *fixed*
number — an explicit one, or an auto one being kept:

- **Increment** its trailing decimal — `S1-3` → `S1-4`, `S1-5`.
- **Decimal-insert** beneath it instead — `S1-3.1`, `S1-3.2` — when incrementing would land on a
  number used elsewhere in the stack or run past the next fixed cue in the same group.

**An explicit number that reads out of order against the auto numbers beside it in its group** —
the nearest one before it is not below it, or the nearest one after it is not above it — **neither
anchors nor bounds a run**: the run passes through it, counting its place. It is a renumber waiting
for *Fix Order*, and anchoring on it would relabel the cues around it the next time anything in the
stack changed, which is D11's relabelling one gesture later. Two explicit numbers out of order
against each other still anchor, as they always did.

A run with nothing fixed before it borrows the following cue's prefix and counts *up to* it: two
cues before `S1-3` are `S1-1` and `S1-2`, and a lone cue before `S1-1` is `S1-0` — zero itself is
free, so there is no need to decimal-insert. A block a number in it is taken from is tried lower
down before giving up (a lone cue before `2` whose `1` is taken is `0`). Only when there isn't room
for the whole run below the boundary (two cues before `S1-1`, say) does it fall back to `S1-0.1`,
`S1-0.2`. With nothing either side the run is starting the series, so a fresh stack numbers `1`,
`2`, `3`.

**Nothing renumbers a stored stack on upgrade.** Every number the derive-from-scratch rule wrote is
one the keep rule leaves alone — a stack's kept labels are its own — and `copy_to_project` stores
`cue_number` and `cue_number_auto` as they are. **An import derives them from scratch**: the archive
carries only explicit numbers (sync v32, P5), so the importer — a pull, a clone, an import — numbers
every stack's auto entries from position alone once its entries are in, and every desk reading one
archive numbers it alike. That can relabel an auto number the desk had kept by the keep rule (an
explicit renumber's neighbours), which is the price of a drag being one changed file. On the desk
itself, a stack's auto numbers move only when an operator next changes that stack's list, and then
only for the cues that change moves.

Typing an explicit number releases that value from any auto sibling holding it (`releaseAutoNumber`)
so the edit can't be rejected by `uq_entry_number_per_stack`; clearing the field hands the cue back to
the auto scheme. `renumberAutoEntries` writes in two passes with a flush between, since two auto
numbers swapping places would otherwise be a transient duplicate.

Labels that won't fit `varchar(20)` are skipped — the cue stays blank rather than carrying a
truncated number.

**Backfill (removed).** The renumber only runs off a mutation, so cues that predated the
feature would have stayed blank until something touched their stack. A `backfillAutoCueNumbers()`
walked every stack once at startup to fix that. It went with the rest of the migrations on
2026-08-24, once the only database in existence had no blank auto numbers left — see
[InstallBootstrap.kt](../src/main/kotlin/uk/me/cormack/lighting7/state/InstallBootstrap.kt).
Nothing backfills now, so a cue that somehow acquires a blank auto number keeps it until its stack
is next mutated.

### Key Design Decisions

- **A stack lists cues; it does not own them** (D1) — `add-cue` adds an entry, sharing a cue another
  stack already holds; deleting a stack deletes its entries, their anchors and its set, and leaves
  its cues, loose where it was their last stack (`?deleteLooseCues=true` deletes those, D17). The
  caller always names the stack and the entry (P7) — nothing picks a cue's "home" stack
- **Ordinal** as a double, ties broken by uuid, so an insert or a drag lands between two entries
  without rewriting the list (`writeEntryOrder`), and two desks inserting into one gap play their
  entries in the same order (sync v32, one archive file per entry)
- **Per-cue fade settings** allow different transition timing for each cue in a stack; `fade_duration = null` means snap-cut
- **`cue_number` is a display label only** — the entries' order remains the authoritative playback order
- **Markers are entries, not cues** — `advance`, `go-to` and standby only target cue entries
- **Per-cue auto-advance** allows some cues to auto-advance while others wait for manual progression
- **EasingCurve enum** (LINEAR, SINE_IN_OUT, CUBIC_IN_OUT, etc.) stored as string for extensibility

## Runtime: CueStackManager and the hold registry

`CueStackManager` (`fx/CueStackManager.kt`) runs stacks: which **entry** each live stack is on, its
auto-advance timer, and GO / GO TO / BACK. It does not make a cue live itself — every path that puts
a cue on stage or takes it off goes through the **hold registry**, `fx/CueHolds.kt`, reached as
`cueStackManager.holds` (cue model plan P3, §3.4).

Three halves of running a stack live beside it rather than in it:

| Class | Owns |
|-------|------|
| `fx/CueHolds.kt` | What holds each live cue, its priority, and bringing a cue on (first hold) and taking it off (last release) — effects, Layer 4 rows, stomp, timed effects, triggers and events |
| `fx/CueCrossfadeDriver.kt` | The Layer 4 fade weights: one in-flight fade per stack, its tick loop, and the outgoing cue it must drop (rows and leaving effects) if cancelled — unless a hold has that cue again. The effects' envelopes ramp on their own (`fx/EffectEnvelope.kt`) |
| `fx/CueRunStateTracker.kt` | Standby arming, the definition of "next", `CueRunState` and its broadcast. Reached as `cueStackManager.runState` |

The tracker reads the manager's bookkeeping through a `LiveStacks` snapshot rather than the mutable
entry, so one broadcast frame can't describe two moments.

### Holds

A cue is live while anything **holds** it, and comes off with its last hold (D3: a cue live in two
places is one contribution, never two). A hold is one of:

- **`Stack(stackId, entryId)`** — a running stack whose live entry names the cue. A GO, GO TO, BACK,
  activate or auto-advance moves it from entry to entry.
- **`Loose(seq)`** — a press with no stack behind it (D6): `POST /cues/{id}/apply`, the MCP
  `apply_cue`, a busk cue pad, a cue slot, MIDI `FireCue` and a hand place. One loose hold per cue;
  pressing it again re-stamps it as the newest. `/cues/{id}/stop`, `stop_cue`, a second pad or slot
  press and a solo bank sibling let it go.

The registry decides three things:

- **`acquire` on a cue's first hold brings it on** — its ad-hoc and Look effects (growing from
  nothing over the GO's fade, effects plan D1), its Layer 4 rows (at weight 0 when a crossfade will
  bring them in), its stomp, its timed effects and triggers, and its **events** (D15). Any other
  acquire only restamps its priority: nothing on stage moves.
- **`release` of its last hold takes it off** — triggers and timed effects stopped, effects removed
  or shrinking to nothing over the GO's fade, its rows dropped or left at full weight for the
  caller's crossfade to take down. Any other release only restamps.
- **A cue's priority is its highest hold's.** A stack hold composes at the entry's
  `cueDerivedPriority` (the stack band plus the entry's position, markers counted); a loose hold at
  `LOOSE_BAND + seq` (`2_000_000_000 +`), above every stack, the newest press on top (D10). So a pad
  pressed over a running stack wins on every LTP property until it is let go, and the stack takes
  over again on release with nothing re-fired. A write that shifts entry positions (reorder, add-cue,
  sort, a cue created, recorded or deleted mid-list, an entry or a marker added or removed) calls
  `holds.restampEntries(stackEntryPriorities(stack))`, which re-ranks only the stack holds it names.

So the effects' envelopes follow the holds, as the rows' weights do (effects plan §3.5): a GO between
two entries of a cue that stays held moves no envelope, a GO onto a cue another stack or a press
already holds moves none, and a stack moving off a cue a press still holds leaves its effects at
full. A loose hold has no fade: its effects snap on and off with its rows. Each effect a cue coming
on cooks to is matched by one rule (`continues`: the same stored effect row —
`FxInstance.layerEffectUuid`, a Look's, a palette's or the cue's own ad-hoc row's uuid — on the same
target and property, with the same `lookEffectSettings()`), an ad-hoc one across a GO by its run key
(`handsRunTo`, below), and arrives one of four ways:

- **Taken back.** A cue brought on again while its fade-out is still running (Chris's answer,
  session 4) keeps its own instances: they grow from where they stand over the new hold's fade, or
  return to full at once on a snap — no respawn, no phase jump. That covers a press, another
  stack's GO and a GO BACK or GO TO on the same stack, whose cancel leaves the returning cue's
  effects for its acquire. One the cue no longer cooks to (it was edited meanwhile) finishes
  going, and one the engine retired in that instant is spawned afresh.
- **Adopted** (P4). A GO that takes one cue off and brings another on hands the incoming acquire
  the outgoing cue (`adoptFrom`: the outgoing cue's only hold is this stack's live entry). An
  incoming effect matching one of its live instances takes that instance over, id and phase kept,
  and is not faded — unless it is paused or an N-shot, which the incoming cue starts afresh. A
  Look layered at two speed masters is two effects, so one leaves and one arrives. Should a press
  take the outgoing cue between the two holds, or the acquire fail, the adoptions go back
  (`giveBack`) and the incoming cue runs its own.
- **Handed on** (stack-timeline plan D7, D8). An ad-hoc effect's row is its cue's alone, so it is
  never adopted by `continues`; its **run key** (`cue_ad_hoc_effects.run_uuid`) is what carries it
  across a GO. Under the same `adoptFrom` and the same paused and N-shot rule — an incoming N-shot
  row starts afresh too, an accent its GO fires — an incoming ad-hoc effect with a key takes over
  the outgoing cue's instance with that key, target and property,
  restamped with the incoming row's uuid. Same settings, it runs on untouched; different ones, it is
  edited in place at the GO, not over the fade (`EffectAligner`, `routes.alignEffect`: phase kept, but
  a move between Seconds and a master restarts it), and an edit refused leaves the instance to its
  cue, which fades it out while the incoming row spawns. `giveBack` restores a handed-on instance's
  cue, row uuid and settings; should the edit back be refused, the cue it returns to is marked edited
  since GO, so *Update stage* puts it right. The candidates are tried in compose order and each is taken once, so a
  key two rows of one cue share (an import, which keeps keys as they arrive) hands one instance to at
  most one row, the same one every time; a loose press hands on nothing.
- **Spawned**, growing from nothing over the fade.

Instances carried over get fresh compose orders in the cue's layer order (`restampComposeOrder`),
so a cue composes the same however it was reached.

The engine layers below stay keyed by cue — `CueAssignmentLayer`'s rows and fade weights,
`CueTriggerManager`'s jobs, `FxInstance.cueId` — which is what D3 makes correct. An effect a cue
brings on is tagged with the stack that brought it (`cueStackId`), for the effect list's readout;
nothing removes effects by that tag any more (`removeEffectsForCueStack` removes only
stack-scoped effects with no cue).

The registry's lock covers the bookkeeping only; the engine work runs outside it, and reads
(`isHeld`, `priorityOf`, `stackOf`) take a volatile view. A stack delete stops its stack inside its
own transaction, and the pool is one SQLite connection, so a lock held across a cook (which can read
the Look cache) would deadlock the two until Hikari timed out.

What holds every live cue is published as **`cueHolds.state`** — `State.cueHoldsFrame`, a
`StateFlow` that outlives a show so a socket survives a project switch (§"WebSocket"). Runtime only:
never persisted, never synced. A same-project reload carries the stacks' entries, their standbys and
the loose cues in press order (`ShowCarry`), re-taking each loose hold without firing its events; a
project switch starts empty.

### Edited since GO and Update stage

A stored cue edit sends nothing to the stage (show-cue-editing plan P2), so a held cue whose rows,
layers or effects are written is **edited since GO**: the stage still shows it as it came on. The
registry keeps the mark (`CueHolds.markEdited`, a set beside the holds) and `cueHolds.state` carries
it as `edited: true`.

- **What sets it**: the cue's `PUT` and `PATCH` when the write changes what *Update stage* would
  send — `DaoCue.stageContent()`, its own rows' values and its untimed layers and ad-hoc effects,
  compared across the write — and only while something holds the cue. A name, fade, notes, scenery
  or events write does not, nor a row's own fade or move-in-dark, nor a timed layer or effect: Update
  stage sends none of them (Chris's call, 2026-10-17, narrowing plan §3.5). **A Look or palette edit
  that moves its effects** marks every live cue layering it (Chris's call, 2026-10-17, amending §3.5):
  `republishForSourceEdit` republishes those cues' rows, never their effects, and
  `CueHolds.markIfLayerEffectsMoved` compares what the cue's untimed layer effects cook to now with
  what they cooked to as it came on or was last brought in line — a held cue with no rows of its
  own included, which the row republish never visits. A values-only edit republishes and marks
  nothing.
- **What clears it**: the cue's first hold (it comes on as stored), its last release and *Update
  stage*. A Record, an Update or the patch-edit tool's republish (`republishCueIfLive`) leaves it:
  it moves the rows and not the effects (Chris's call, 2026-10-17, amending §3.5). Nor does a
  Record or Update into a held cue set it: the effects it recorded are the programmer's instances,
  still running, which an *Update stage* would start a second copy of.
- **`POST /cues/{id}/update-stage {dryRun?}`** (`routes/cueUpdateStage.kt`) sends a live cue's stored
  rows and effects to the stage and answers `{rows, effects: {edited, stopped, started}, fadeMs,
  curve}`; `dryRun` answers the same and sends nothing, which is what the confirm words its fade
  from. 409 `CUE_NOT_LIVE` when nothing holds the cue. One runs at a time.
  - **Rows and effects come from one cook** (`buildCombinedCueLayerCook`, fired timed layers
    included), so both read one version of every Look. The rows go through `CueHolds.restage` →
    `CueAssignmentLayer.restageAssignments`, which asks under the publish lock whether a hold still
    has the cue — a release's row drop takes that lock too — so no rows are left behind a cue a GO
    took off meanwhile. Every key that moves ramps over the cue's own fade and curve, released keys
    included, and a snap cue snaps. The ramp is a transmit-time fade (`TransmitFade`, the curve
    threaded through `resetToFallback` to the controller's `ChannelChange`), so a cue with rows keeps
    its crossfade weight, and one with none that a GO is still bringing in takes the weight that
    crossfade has reached (`CueCrossfadeDriver.incomingWeight`). A GO's fade is not borrowed: a GO
    between two entries of one cue moves nothing, the driver holds one fade per stack, and a loose cue
    has no stack. `rows` counts the (head, property) keys whose value changes.
  - **A key a running effect covers does not ramp**: the publish leaves it to the effect, whose tick
    re-reads the base with no fade, so the base under an effect moves at once, and under an effect
    the update starts the ramp is overwritten on the effect's first tick. A row's own fade has the
    same limit; a ramp there needs a per-cue fade in the resolver rather than at transmit
    (`FU-UPDATE-STAGE-EFFECT-RAMP`).
  - **Effects** are reconciled against the running instances the cue's holds spawned, by stored
    uuid, cue layer, target and property (`CueEffectKey`; `cueEffectInstances` builds what a first
    hold would spawn, as `bringOn` does). A matched instance whose settings differ is edited in place
    through `applyEffectUpdate`, no `targets`: id and phase kept, but a move between Seconds and a
    master restarts its loop. One the in-place edit refuses is started afresh. An unmatched running
    instance shrinks to nothing over the cue's fade. An unmatched stored effect grows from nothing
    over it, at the cue's priority — **but only one the cue newly gained**: the registry keeps what
    the cue's first hold spawned (`spawnedEffects`, updated by each Update stage), and an effect in it
    with no running instance — an N-shot run out, one another cue's stomp removed — stays off. A
    changed layer brings its effects in line the same way, a palette layer's head by head.
  - **Settings compare with master 1 spelled once** (`comparableEffectSettings`): a stored null and
    master 1's uuid are one master, and only the master an effect's timing reads is compared — so an
    effect moved onto master 1 by uuid still matches its stored effect, here and in a later GO's P4
    adoption (`CueHolds.continues`).
  - **Compose order** is restamped only when the cue's untimed instances are out of its layer order,
    and its timed and script-spawned instances then keep their order after them.
  - **Not re-run**: events, scenery, stomp and the cue's speed-master tempos (a GO's, as events
    are), and the cue's **timed** effects and layers — fired
    things, whose timers keep what the last first hold armed; their next GO lands them (Chris's call,
    2026-10-17). Their instances carry no stored uuid, which is how the reconcile leaves them. A
    recurring timed layer's next fire re-cooks the rows as they were armed, as it did after a Record;
    and an effect moved from timed to untimed starts beside the copy its timer already fired, which
    runs on until the next GO (`FU-UPDATE-STAGE-TIMED`).
  - Current project only and both roles, as a GO is: it stores nothing.

### Per-Stack State

```kotlin
private data class ActiveStackState(
    val stackId: Int,
    val activeEntryId: Int,
    val activeCueId: Int,
    var autoAdvanceJob: Job?,
    // Copied from the live cue at activation, so the run-state broadcast can describe
    // the stack without going back to the DB.
    var fadeDurationMs: Long?,
    // The cue's own flag. What goes on the wire is derived from `autoAdvanceJob` instead —
    // "will it advance", not "is it configured to".
    var autoAdvance: Boolean,
    var autoAdvanceDelayMs: Long?,
    // Null unless a real crossfade is under way — a cue that snapped, or a GO between two
    // holds of cues that were already on and stay on, has no elapsed time to report.
    var fadeStartedAtMs: Long?,
)
```

Stored in a `ConcurrentHashMap<Int, ActiveStackState>`, keyed by stack. The live **entry** is the
stack's position (P2); the cue is what that entry names.

### Standby — the armed "next"

Lives in `CueRunStateTracker`, reached as `cueStackManager.runState`.

`standbys: ConcurrentHashMap<Int, CueEntryRef>` (`stackId → entry`) holds the entry an operator has
armed as the next GO. It sits **beside** `activeStacks`, not inside `ActiveStackState`, for two
reasons: an entry can be armed before the stack is running (pre-show), and activation replaces the
whole `ActiveStackState` entry. A marker is never armed.

Transient runtime state — never persisted, never synced (see `docs/sync-engineering.md`'s
decision tree).

It used to live in the browser. Each session computed "next" for itself, so a cue armed on a
tablet was invisible to the desk and the two disagreed about what GO would fire. There is now
one definition, `effectiveNext`:

> the armed standby, when one is set and isn't already live — else the positional next
> cue entry, honouring `loop`. Null at the end of a non-looping stack.

Everything reads it: `advanceStack` FORWARD **fires** it (so a client calls `advance`
unconditionally instead of choosing between `advance` and `go-to`), `activateAtFirstCue` starts a
stopped stack on it, `CueStackDetails.nextEntryId` / `nextCueId` report it, and the run-state
broadcast carries it. A GO consumes the standby; deactivating a stack clears it.

`orderedCueEntries` (the stack's cue entries, markers left out, as `CueEntryRef(entryId, cueId)`) +
`positional` are the shared primitives — `advanceStack` and `effectiveNext` cannot drift because
they walk the same list by the same rules. Both walk **entries**, so a stack that names one cue
twice steps through both. The one difference is at a non-looping boundary: `positional` returns null
there, which GO reads as "stay on the current entry" and "next" reads as "nothing on deck".

### Key Methods

| Method | Description |
|--------|-------------|
| `activateEntry(state, stackId, entryId, scope, fireEvents)` | Make an entry the stack's live one (handles the crossfade and auto-advance) |
| `advanceStack(state, stackId, direction, scope)` | Advance forward/backward respecting loop; BACKWARD fires no events |
| `goToEntry(state, stackId, entryId, scope)` | Jump to a specific entry |
| `go(state, stackId, scope)` / `activateAtFirstCue(...)` | GO a live stack, or start a stopped one on its effective next |
| `deactivateStack(stackId, state)` | Release the stack's hold, cancel its fade and timers |
| `getActiveEntryId(stackId)` / `getActiveCueId(stackId)` | The live entry, and the cue it names (or null) |
| `getActiveStackIds()` / `isStackActive(stackId)` | Live stacks |

On `cueStackManager.holds` (`CueHolds`):

| Method | Description |
|--------|-------------|
| `isHeld(cueId)` / `looseHeld(cueId)` / `holdsOf(cueId)` / `priorityOf(cueId)` | What holds a cue and at what priority |
| `snapshot()` | Every live cue with its holds, as `cueHolds.state` carries it |
| `cuesHeldOnlyBy(stackId)` | The cues a stack alone holds — what preview takes out of the base |
| `takeLooseCue(state, cueId)` / `toggleLooseCue(state, cueId)` | A press: take a loose hold (or re-stamp it as the newest), or take/let go by its current state |
| `dropLoose(state, cueId)` / `dropAllLoose(state, exceptCueId)` / `dropAll(state, cueId)` | Let a press go; every press but one (`/apply?replaceAll`, `apply_cue`'s `replaceAll`); every hold of a deleted cue |
| `restampEntries(entryPriorities)` | Re-rank the stack holds after a write that moved entry positions |

On `cueStackManager.runState` (`CueRunStateTracker`):

| Method | Description |
|--------|-------------|
| `setStandby(state, stackId, entryId)` / `clearStandby(state, stackId)` | Arm / disarm the next GO. Refuses an entry of another stack and a marker — arming is a deferred GO |
| `getStandbyEntryId(stackId)` / `getStandbyCueId(stackId)` | The explicitly armed entry, and the cue it names |
| `effectiveNext(...)` | What the next GO fires, as a `CueEntryRef`. Two overloads: one taking the stack's already-loaded entries (for the details DTO), one that queries |
| `runStateFor(state, stackId)` | The stack's run state, for the broadcast and the connect-time snapshot |
| `stacksWithRunState()` | Stacks that are live or hold an armed entry — what the connect snapshot walks |

### Activate Flow

1. Cancel any in-progress crossfade and auto-advance for this stack. The driver drops the abandoned
   fade's outgoing cue's rows unless a hold has that cue again.
2. **Acquire the incoming entry's hold, then release the outgoing's** — in that order, so a GO
   between two entries naming one cue (D7) never lets it go. With a fade and an outgoing stack, the
   incoming cue arrives at weight 0 if this is its first hold, and the outgoing cue's rows are left
   at full weight if this was its last.
3. Fade only what moved: the incoming cue if its hold was the first, the outgoing cue if its release
   was the last. A cue already held elsewhere stays at full on both sides, so a GO from A1 to A2 of
   the same cue — or onto a cue another stack already holds — moves nothing, and the run state
   reports no fade (`fadeStartedAtMs` null).
4. Effects fade by size with the values (effects plan D1): on a crossfading GO the incoming cue's
   new effects grow from nothing and the outgoing cue's shrink to nothing, each instance's envelope
   ramping on the clock over the cue's fade and curve; the effects the two cues share run on
   (§"Holds", P4). A side with effects and no rows fades all the same — the gate is rows *or*
   effects, so an effect-only cue fades. A 0 s fade, a first GO from a stopped stack and a re-fire
   snap, values and effects alike.
5. The **incoming** entry's follow schedules the auto-advance, even when it names the cue that was
   already live (D7).
6. Publish the run state, then the scenery (`SceneryService.onEntryLive`) — §"Scenery" below.
   `deactivateStack` likewise ends with `onStackStopped`
7. The cue's **events** fire inside step 2's acquire — on the cue's **first** hold only (D15), and
   never on the BACKWARD arm of `advanceStack`. A GO onto a cue another stack or a press already
   holds fires none. `deactivateStack` drops the desk's arm (`onStackStopped`)

A loose press runs the same acquire with a `Loose` hold and no crossfade: the cue's effects, rows,
stomp, timed effects, triggers and events come on exactly as a GO's would (the old separate apply
path is gone), and its scenery is tracked as a loose track (`SceneryService.onLooseHeld`).

A step "merge cue palette into stack palette" used to sit in this flow, and the stack carried a
positional colour list its cues inherited. That whole grammar is gone — an effect parameter names a
colour palette (`tmpl:{uuid}`) whose answer does not depend on which stack is running — so a stack
carries no colour state at all.

## Crossfade (Option B — Intensity Envelope)

A GO's fade moves two things over the same time and curve: each live cue's Layer 4 fade weight,
which `CueCrossfadeDriver` ticks, and each of its effects' **envelope**, which ticks itself.

### The effect envelope

`FxInstance.envelope` is an immutable `EffectEnvelope` (`fx/EffectEnvelope.kt`): a level, or a ramp
from one level to another over a duration on an `EasingCurve`, read on the clock wherever the weight
is read (`intensityMultiplier` is its level now). So the effects need no loop of their own: `CueHolds`
sets the ramps as the holds move — `bringOn` spawns an arriving cue's effects on `0 → 1` before they
are added, `takeOff` sets a leaving cue's on `now → 0` — and every change of fade is one
compare-and-set on the instance. An instance on its way out is **leaving**: it belongs to no cue,
layer or Release any more, and the engine's 50 Hz wall-clock pass removes it once it reaches 0
(`FxEngine.retireFinishedLeaving`, which runs whatever is running, so a paused or beat-loop effect
ends on time too).

### The envelope is a weight, not a scale

`FxOutput.scaled()` is gone (effects plan session 1). `intensityMultiplier` is the **envelope**, one
factor of the weight applied after the blend — `out = before + (blended − before)
× size × envelope × stackSize`, the last the cue stack's runtime effect size (effects plan D10) — so an envelope going to 0 hands the property back to what is underneath rather
than driving an `OVERRIDE` to 0. See `docs/fx-engineering.md` §"Size, direction and N-shot".

Applied in the target, after the blend: `calculateEffectOutput` returns the raw output, and each of
the three effect output sites hands `FxTarget.applyValue` the pass's weight (`FxEngine.weightOf`, which is `FxInstance.weight` with the stack's size). Both the beat and wall-clock passes share those three, differing only in
the `PhaseSource` they hand them:
1. `processFixtureEffect` — direct fixture
2. `processGroupEffect` — group member direct
3. `processElementKeys` — per-element distribution, serving both the multi-element fixture
   expansion and a group's `FLAT`/`PER_FIXTURE` element lists (they became the same walk once
   the expansion started arriving pre-resolved — see `FxTargetExpansion`)

### Crossfade Coroutine

`CueCrossfadeDriver.runFade`, launched by `CueCrossfadeDriver.start`.

```kotlin
private suspend fun runFade(outgoingCueId, incomingCueId, durationMs, easingCurve) {
    val startTime = System.currentTimeMillis()
    while (true) {
        val progress = (elapsed / durationMs).coerceIn(0.0, 1.0)
        val eased = easingCurve.apply(progress)
        // Outgoing: 1→0, Incoming: 0→1 — one map, so the engine republishes once per tick
        fxEngine.cueLayer.updateFadeWeights(buildMap {
            outgoingCueId?.let { put(it, 1.0 - eased) }
            incomingCueId?.let { put(it, eased) }
        })
        if (progress >= 1.0) break
        delay(CROSSFADE_TICK_MS) // 16 ms, ~60fps
    }
    // Remove outgoing's Layer 4, pin incoming at 1.0
}
```

Cancelling mid-flight (a new entry activating, or the stack stopping) drops the outgoing cue's
assignments too — end-of-fade would have removed them, so without that they'd linger frozen at
whatever weight the fade had reached. **Unless a hold has that cue again** (`cancel(stackId,
isHeld)`): then its rows are pinned at full instead, since another stack or a press is holding it
on stage. The incoming cue is pinned at full. A fade's two sides are read every tick, so a cue
that gains a hold mid-fade is taken out of it (`forgetOutgoing`) rather than faded off under it.

`cancel` treats the effects as it treats the rows: the abandoned outgoing cue's leaving effects are
removed at once (`FxEngine.retireLeaving`) unless a hold has it again — that hold took them back — or
the cancelling GO is bringing it back (`returning`), whose acquire takes them back; the incoming
cue's effects are pinned at full (`settleEffects`). The driver is started for a
side with effects and no rows too, so that a cancel can find them; its tick moves only rows.

### Easing Curves

`EasingCurve` enum: LINEAR, SINE_IN_OUT, CUBIC_IN_OUT, EASE_IN, EASE_OUT, EASE_IN_OUT. Stored as string on each cue, parsed at runtime.

## REST API

All endpoints under `/api/rest/projects/{projectId}/cue-stacks`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all stacks with ordered cues + active cue info |
| POST | `/` | Create stack |
| GET | `/{stackId}` | Get stack details |
| PUT | `/{stackId}` | Update stack settings |
| DELETE | `/{stackId}` | Delete stack, its entries and its set; its cues stay. `?deleteLooseCues=true` deletes those it left in no stack. Answers `{ looseCueIds, deletedCueIds }` |
| POST | `/{stackId}/reorder` | Reorder the list: body `{ entryIds: [3, 1, 5] }`, markers included; an entry left out keeps its relative order after the named ones |
| POST | `/{stackId}/add-cue` | Add a cue to this stack as a new entry — shared, never moved: body `{ cueId, sortOrder?, insertByNumber?, cueNumber? }` |
| PATCH | `/{stackId}/entries/{entryId}` | An entry's number and follow: `{ cueNumber?, autoAdvance?, autoAdvanceDelayMs? }`, the fields present only. Answers the row |
| DELETE | `/{stackId}/entries/{entryId}` | Remove from stack: the entry and its anchor go, the cue stays. The live entry's removal moves nothing until the next GO (`cues-engineering.md` §"Cue Stack Membership") |
| POST | `/{stackId}/activate` | Activate stack: optional `{ entryId }`; absent starts at the armed standby, else the first cue entry. HTTP 400 for a marker or another stack's entry |
| POST | `/{stackId}/deactivate` | Deactivate stack |
| POST | `/{stackId}/advance` | Advance through cue entries only (markers skipped): body `{ direction: "FORWARD"\|"BACKWARD" }` |
| POST | `/{stackId}/go-to` | Go to a specific entry: body `{ entryId }` — HTTP 400 for a marker or another stack's entry |
| POST | `/{stackId}/standby` | Arm the next GO: body `{ entryId }`; `{ entryId: null }` disarms. HTTP 400 for a marker or another stack's entry |
| POST | `/{stackId}/markers` | Add a marker entry: body `{ label, sortOrder? }` (a position; appended if omitted). 201 with its row |
| PUT / DELETE | `/{stackId}/markers/{entryId}` | Rename (`{ label }`) or remove a marker; 404 for a cue's entry |
| GET | `/{stackId}/preview?entryId=` | Compose an entry's cue without firing it (absent → the effective next). See below |
| GET | `/{stackId}/timeline` | The stack's Timeline read: every entry with its cue's stored layers, ad-hoc effects, scenery and tracked scenery. See below |
| POST | `/{stackId}/timeline/edit` | One Timeline gesture: `{ ops, dryRun? }`, a batch over the stack's stored cues in one transaction, nothing sent to the stage. See §"Timeline" |
| POST | `/{stackId}/sort-by-cue-number` | Group-aware sort of cue_number |

### `timeline`

`GET /{stackId}/timeline` (stack-timeline plan §3.5, `routes/cueStackTimeline.kt`) is what the
Show view's Timeline derives its grid from (P6): `{stackId, entries: [{entryId, kind: CUE | MARKER,
label?, cueId?, layers, effects, scenery, trackedScenery}]}`, one entry per row in `ENTRY_ORDER`. Under
`withProject` like the stack GET, so it reads any project's stack. Names, fade, follow, numbers and
live state are not repeated: the client has them from the stack list, `cueRunStateChanged` and
`cueHolds.state`, and the read rides those lists' invalidations rather than a frame of its own.

- **`layers`**, in layer order (`sortOrder`, then id): `{id, source: {kind, id, uuid, name,
  families}, targets, heads, sortOrder, rank, enabled, amount, blendMode, propertyMask, stomp, timed,
  delayMs, intervalMs, randomWindowMs, speedMasterUuid, rateSpeedMasterUuid}`. `rank` is the layer's
  place among **every** stored layer of the cue, from 1 at the bottom, so a layer whose source is gone
  — left out of the list — still holds its place and the ranks above it do not shift. `families` is a
  Look's `derivedFamilyGroups` (its rows' properties and its effects' categories) or a palette's
  `familyOf`. `heads` are the fixture keys the layer lands on, groups expanded
  (`TargetCoverage.expand`): a Look's own heads (`ownTargets`) narrowed to the targets it names as
  the cook narrows its rows (`covers`), and the targets themselves where it has a deferred effect; a
  per-fixture palette's row heads, narrowed as `LayerPressRules.paletteHeads` narrows a press; a
  generic palette's targets. `timed` is a delay, interval or random window.
- **`effects`**: `{id, uuid, runUuid, effectType, propertyName, targetType, targetKey, family, heads,
  settings}`, the target canonical (`canonicalTarget`). `family` is the group of the property it
  drives as Record reads it — the first head's property, else the library category's. `settings` is
  what `comparableEffectSettings` compares, built from the **row** rather than a live instance: the
  canonical type, the parameters over the library's defaults, an element mode only over members, a
  filter or step timing only where not the default, and only the master the timing reads, master 1
  by its uuid. Two rows of one run with equal settings are one effect to a GO; unequal, an edit in
  place (◆, D8) — unless either is an N-shot, which a GO never hands on.
- **`scenery`** `{elementUuid, state, transitionMs}` and **`trackedScenery`** `{elementUuid, state,
  fromCueId}` (null from the stack's set) — the latter from `trackedSceneryOfStack`, one read of the
  stack's scenery for every entry, at each **place** of a cue placed twice (`trackedSceneryAt` reads
  the same function for its one entry, resolving that place alone).

A cue placed twice carries the same children at both entries; each child is read once per cue. The
rig-derived fields — `heads`, a Look's row families, an effect's family by head — read the live
patch, so for a project that is not current the heads are the targets unexpanded and a Look's
families its effects' alone.

### `add-cue` with `insertByNumber`

When `insertByNumber: true`, the new entry is inserted at its `cueNumber`'s sorted position
**within its own prefix group** — other groups say nothing about where it belongs. Returns 400
without a `cueNumber`, or one with no decimal run to position it by. Entries at or after the
insertion point shift down, markers included. An **explicit** `cueNumber` another cue entry of the
stack already holds explicitly is refused (409, as on the entry `PATCH` and `POST /cues`) rather than tripping the unique index; an auto one
is released and derived again. Without `cueNumber` the entry is auto-numbered where it lands.

### `sort-by-cue-number`

Sorts each prefix group's cue entries among themselves and writes them back into the slots of the
list that group already occupied, so groups keep their relative placement. Markers and unparseable
numbers never move. Afterwards the cues the sort moved take auto numbers from where they landed and
the rest keep theirs (§"Auto Numbering", D11), so *Fix Order* moves a renumbered cue rather than
relabelling its neighbours.

Response: `{ updatedCues: [...], pinnedCount, nullNumberCount }` — `pinnedCount` is the number of
unparseable numbers left in place. No longer returns 400 when nothing was eligible; a stack that
needs no sorting simply comes back unchanged.

Both this and `reorder` also call `holds.restampEntries`, since a stack hold's priority is derived
from the entry's position — see [cues-engineering.md](cues-engineering.md) §Priority.

### DTOs

- `NewCueStack` — name, loop
- `CueStackDetails` — full stack with ordered cues, `activeEntryId` / `activeCueId`, `standbyEntryId` / `standbyCueId`, `nextEntryId` / `nextCueId` (what GO fires), canEdit, canDelete
- `CueStackCueEntry` — one **row** of the list: `entryId` (what reorder, standby and go-to name), `id` (the cue's; null on a marker), name (a marker's label), sortOrder (its position), layerCount, adHocEffectCount, autoAdvance, autoAdvanceDelayMs, fadeDurationMs, fadeCurve, cueNumber, cueNumberAuto, notes, cueType, scenery (the cue's own scenery changes, always encoded — §"Scenery")
- `CueStackActivateResponse` — stackId, entryId, cueId, cueName, effectCount
- `CueStackDeactivateResponse` — stackId, removedCount
- `SortByNumberResponse` — updatedCues, pinnedCount, nullNumberCount

## Timeline

`POST /{stackId}/timeline/edit` `{ops, dryRun?}` (stack-timeline plan §3.6, C3, P5;
`routes/cueStackTimelineEdit.kt`) is every write the Show view's Timeline makes: one gesture, one
request. Under `withCurrentProject` (409 off it), and 404 for another project's stack. The batch is
**one transaction, all or nothing**: its ops run in order, each over the state the one before it left,
and every cue an op names must have an entry in this stack. **Nothing is sent to the stage.**

| Op | Does |
|---|---|
| `layerOn {cueId, source: {kind, id}, target?, settingsFrom?}` | The cue editor's press (`decideCueLayerPress`, shared with `POST /cues/{id}/layers/press`, not copied) of a Look or palette on one target — or none, its own heads — taking `settingsFrom`'s settings (D6): a layer of the same record in this stack as the batch found it, so one an earlier op took off still lends them. Else a fresh press's. A new layer goes on top; the top layer of the record is widened where it carries the same settings, keeping its own identity. A cell already on is left alone |
| `layerOff {cueId, source, target?}` | The off press. A cell already off is left alone |
| `layerSettings {cueId, layerId, fields}` | The Looks tab's fields: `enabled`, `amount` (0–1), `blendMode`, `propertyMask` (a comma list, or null), `stomp`, `delayMs`, `intervalMs`, `randomWindowMs`, `speedMasterUuid`, `rateSpeedMasterUuid` (a project's master, or null). An unknown field is a problem |
| `replace {cueIds, from, to, keepSettings}` | In each cue holding `from` (a layer of its record naming its target, or none for its own heads): where the cue lacks `to`, `from` comes off and `to` goes on with `from`'s settings **where `from` was** (`keepSettings`; a palette's mask is its family), else on top with a press's defaults; where the cue already has `to`, `from` comes off. Still the press, so a palette arriving takes its family's others off its heads |
| `scenerySet {cueId, elementUuid, state, transitionMs?}` / `sceneryClear {cueId, elementUuid}` | One element's change, checked by `parseSceneryList` with the rest of the cue's list and written by `replaceCueScenery`; a clear hands the cue back to tracking |
| `restore {cueId, layers, adHocEffects, scenery}` | Undo (P5): the cue put back exactly as an answer's `before` holds it — `writeCueChildren(restore = true)` pairs layers and effects by uuid rather than key, so a row an edit deleted comes back with its uuid, and an effect with its run key; scenery by `replaceCueScenery`'s uuids. Stored orders are kept where they order the rows on their own, else renumbered by place |

The answer is `{changed, released: [{cueId, source, targets}], before: [{cueId, layers, adHocEffects,
scenery}], edited}`: the cues whose layers, effects or scenery changed (compared whole, uuids
included), each press's knock-on — another record's layers it narrowed or dropped, named on the
pressed targets — each changed cue as the batch found it (what Undo sends back as `restore` ops), and
the held cues whose `stageContent` changed, which are marked edited since GO (`CueHolds.markEdited`).
`cueListChanged` and `cueStackListChanged` fire once per batch. `dryRun` runs the batch and rolls it
back, marking and announcing nothing. Every problem is named at once: 400 `TIMELINE_EDIT_INVALID`
`{error, code, problems: [{op, message}]}`, `op` the op's index — an unknown record or op, a cue
with no entry in the stack, a refused press (a generic palette with no target), a bad field, a
scenery state its element refuses — and the batch rolls back.

The press's palette snapshots are read inside the batch's transaction, so `loadTemplateSnapshot`
queries a palette's rows rather than ordering `template.rows`, which throws on a referrer collection
the transaction has already loaded. Effect ops (`effectOn`, `effectOff`, `breakRun`, `joinRun`) are
the plan's session 5.

## Preview compose

`GET /{stackId}/preview?entryId=` answers "what would this cue look like?" — the channel values a cue
*would* produce, with nothing published. It backs the Next GO stage view (lighting-react
`docs/stage-vis-engineering.md` §"The fourth source: Next GO"): composing a cue in the browser
would otherwise mean reimplementing specificity, HTP/LTP, palette resolution and move-in-dark
arming client-side.

`routes/cuePreview.kt`, and it is reuse end to end:

1. **Retained rows** — `CueAssignmentLayer.assignmentsExcludingCues(holds.cuesHeldOnlyBy(stackId)
   + cueId)`: every published cue but the ones only this stack holds, because a GO releases this
   stack's hold and nothing else. A cue another stack or a loose press also holds stays on stage
   through the GO (D3), so it is retained — and so is a cue held loose with no stack at all. The
   previewed cue's own published rows go too: its incoming rows stand in for them. The rows carry
   their *stored* weight (always 1.0 — live crossfade progress lives in `cueFadeWeights`), so a cue
   caught mid-crossfade is previewed settled, which is what a preview wants. The incoming cue
   composes at the priority the GO would give it — this entry's, or the cue's live priority when
   another hold already ranks it higher.
2. **Incoming rows** — `buildCombinedCueLayerRows`, the same builder `republishCueLayer` uses,
   which is what makes the preview and the GO agree by construction rather than by inspection.
3. **Compose** — a *fresh* `CueAssignmentResolver`. `resolve` is a pure function of its rows, so
   this cannot disturb `layerResolver`'s live state (`CuePreviewRouteTest` asserts that).
4. **To channels** — `PropertyChannelWriter.resolve` per composed property, subnet 0 only,
   keyed so two properties backing one channel resolve last-write-wins as a transaction would.

Three deliberate limits, all worth knowing before building UI on it:

- **Layer 4 only.** Effects in the cue band have no static value to report, and timed preset
  applications don't contribute (matching a GO's first publish). A cue whose look is carried by an effect
  previews as whatever its assignments say — possibly nothing.
- **Assertions only.** Channels no cue asserts are *absent* rather than reported as 0; the caller
  falls back to the live output the way the "Output + Programmer" vis source already overlays.
  Reporting 0 would black out every unaddressed fixture in the preview.
- **No programmer, no park.** A preview of playback, not of the stage.

**Scenery is the exception to all three** (stage-view plan session 8): the response's `scenery` is
the whole stage's scenery as it would resolve with this cue live — its stack's set and its cues
tracked down to it, beside every other live stack and every live Look, programmer included (its
Looks and its own scenery, unless it is blind) — each
element with `from` (where it is drawn now) and the `durationMs` the GO would move it over. Unlike
`channels` it is whole: an element absent shows its base. It comes from the same resolver the GO
runs (`SceneryService.preview`), so a preview and the GO that follows cannot disagree.

**A cue with no entry has its own preview**, `GET /cues/{id}/preview` (`previewLooseCue`, beside
`previewCueLook` in `routes/cuePreview.kt`, show-cue-editing plan §3.7). The Stage view's *Cue in
edit* draws a loose cue, or a cue opened from none of its places, through it. It composes the cue as
stored, as a loose press would land it on its first hold: at `CueHolds.nextLoosePriority()`, above
every stack and every earlier press, and over every published cue but its own rows, since a press
releases nothing. `SceneryService.previewLoose` adds the cue as its own one-cue track. Both routes
share one composition (`composePreview`), so neither can drift from the other or from the GO or first
press that brings the cue on (`CuePreviewRouteTest`). A press of a cue already held only restamps it
and keeps its published rows (§"Edited since GO and Update stage"), while the preview draws the stored
edit, which is what *Cue in edit* wants. 404 for a cue the project lacks.

## Scenery

A cue, a stack and a Look can each carry **scenery changes** — an element of the scene document and
the states it takes (`visible`, a drawn drape's `open`, a flown piece's `trimM`) — in
`cue_scenery`, `cue_stack_scenery` and `look_scenery` (`models/scenery.kt`; stage-view plan session 8,
D11–D13). Scenery sits **beside** the composition model, not in it: nothing here is a DMX channel,
Record captures only what the programmer **holds** (the scenery-programmer plan's D7, its session 3;
below), and a palette never carries it. Above the three owners sits the **programmer's own scenery** — what the operator's
hands hold now (scenery-programmer plan D1). See `docs/lighting-composition-model.md`
§"Scenery — beside the layers".

**Scenery tracks; lighting does not.** A cue is a complete lighting state, but a tab closed at Q14
stays closed at Q15. `show/SceneryResolver.kt` resolves each element, per state, highest last:

1. the element's base (its own `params.states`);
2. each live stack's **set**, then its **cues from the top of the list down to the live cue**
   (cue entries only), the last change winning — computed from the list, not from history, so
   GO TO Q20 lands the set as if the list had been run. With two stacks live they fold in GO order,
   so the most recently GO'd wins — not the lighting resolver's database-id order;
3. the Looks the live cues **layer** (enabled, `amount > 0`, not timed), in layer order;
4. the Looks **live in the programmer** — pressed, or on a busk pad — in layer order;
5. the **programmer's own scenery** (`state/ProgrammerScenery.kt`, scenery-programmer plan D1, D2):
   a sparse overlay, element → the states held and the fade they were moved on, one per desk,
   runtime only. Written by `programmer.setScenery` / `programmer.clearScenery` on the programmer's
   socket and by the AI's `move_scenery`; each write is checked against the element's kind with
   `parseSceneryState` (`open` only on a DRAW drape, `trimM` only on a flown piece) and merged over
   what the element already holds. Clear (`clearProgrammerCompletely`) lets it all go on the Clear's
   fade, and State's project collector drops it on a switch.

A state the element's kind no longer takes (a drape switched from DRAW) is ignored.

**Blind** takes tiers 4 and 5 off stage: the live resolve leaves them out, and a second resolve that
includes them is published as `staged` — only the elements whose state differs from live, and only
while there is one (D12). Each staged entry moves from where live (or an earlier staged move) has the
piece, on the clock leaving Blind will give it, so the Stage view's Output + Programmer and
Programmer sources can draw the move the operator is rehearsing. Leaving Blind lands it.

**Each entry names what holds it** (`source`, D4): the source of its highest-tier state —
`base`, `set` (with the stack and its name), `cue` (stack, cue, label), `cueLook` (stack, Look and its
name), `programmerLook` (Look and name) or `programmer` (`SceneryResolver.holderOf`).

**The hook.** `state/SceneryService.kt` holds the result as a `StateFlow`, `scenery.state` on the
socket. It recomputes on every activation (`onEntryLive(stackId, entryId, cueId)`) and
`deactivateStack` (`onStackStopped`), so every GO path reports — REST, MIDI, auto-advance — and on
every loose hold (`onLooseHeld` / `onLooseReleased`): a busk pad, a cue slot, `/cues/{id}/apply`
and the AI's `apply_cue`. A loose cue is a **track of its own** beside the stacks' (keyed `-cueId`
so it never meets a stack id), its scenery the cue's own changes over its tracked state, ranked by
press order like any other track; letting it go lets its pieces go. It also follows the
programmer's layer stack (`layersFlow`), the programmer's scenery (`ProgrammerScenery.flow`), blind,
and every cue, stack, Look and element list change, so an edit anywhere moves the stage. The elements
resolved are those a stored change names, those the programmer holds, and those the last frame
carried — so a piece only the programmer held flies home before it leaves the frame, which it does
once landed on its base.

A hook only queues: the recompute runs on the service's one worker thread, never inside the
caller's transaction (a stack delete calls `deactivateStack` inside its own, and the pool is one
SQLite connection), and reads holding no lock. Two rules ride on that queue. **A recompute not yet
started answers every later one**, so a fader riding a layer's amount queues one read, not one per
move. And **a GO's moves start on the first recompute that reads it**, whatever queued that run: the
hook records the GO beside the live table, and the run takes both at once. So an edit's recompute
still queued when a GO lands moves the GO's changes on their clocks, two stacks going in one burst
each move on their own cue's, and a second GO on a stack before the first's was read leaves the
first cue's changes to land. The live table is the service's own, written by the hooks — not the
manager's, which changes a stack's entry before activation reaches `onEntryLive`. A stack's track
is tracked down to its live **entry**, so a stack naming one cue twice resolves the state at the
entry it is on.

**Clocks** (`SceneryService.durationFor`, scenery-programmer plan D6). Per state that moved, the
longest wins, each on the first clock that applies:

1. a change of **the cue just GO'd** keeps its own `transition` (a cue's only; null follows the cue's
   fade);
2. a **programmer move** takes the operator's fade when it is above 0;
3. a piece the programmer **let go on a fade** (Clear's, or a release that carried one) flies home on
   it;
4. **everything else** — a programmer move with no fade, a Look pressed, a set edit, a stack stopping,
   GO TO landing a change an earlier cue made — runs at the element's own `travelS`, the seconds for
   its whole travel (`docs/fixtures-engineering.md` §"The scene document"), scaled by the share of
   it moved: `|Δopen|` for a drawn drape, `|ΔtrimM| / |out − in|` for a flown piece (in its Z, out
   its base trim; a full travel when they are equal). `visible` never travels, and a piece with no
   `travelS` snaps, as every such move did before.

A move's `from` is where the piece is drawn at that moment, so a retarget mid-move starts there.

**REST.** Whole-list `PUT` on `cues/{id}/scenery`, `cue-stacks/{id}/scenery` and
`looks/{id}/scenery` (`routes/projectScenery.kt`), body `{scenery: [{elementUuid, state,
transitionMs?}]}`, every problem at once (a state its element's kind cannot take, an element named
twice, a `transitionMs` off a cue, or a separator). The owners' read DTOs carry the lists:
`CueDetails.scenery` (plus `trackedScenery`, what the cue shows without moving it and where from),
`CueStackDetails.scenery`, `LookDetails.scenery` — the stack list carries every cue's **own**
changes too, `CueStackDetails.cues[].scenery` (scenery-programmer plan D13, D14: the cue table's
Scenery column and the Prompt Book read every cue at once; `@EncodeDefault(ALWAYS)`, one batched query
per stack, `cueSceneryByCue`), so every cue scenery write — the `PUT`, the AI's `set_scenery` on a
cue, a Record or an Update that wrote scenery — fires `cueStackListChanged` beside `cueListChanged`, and an
element delete that swept scenery (REST or `set_scene`'s `removeElements`) announces the cue, stack
and Look lists (`sceneryOwnersChanged`) —
and the Look list's and busk pads' `LookDto`
carries a summary, `scenery: [{elementUuid, elementName, state}]` (`@EncodeDefault(ALWAYS)`, one
batched query per list, `lookScenerySummariesFor`), for the Looks sheet's Scenery column. Deleting an element sweeps its changes; deleting a
cue, stack or Look sweeps its own; a cue or Look copy carries them — one into another project those whose element
resolves there (`docs/cues-engineering.md` §"Copying").

**Record, Include and Update** (scenery-programmer plan session 3, D7, D8;
`routes/programmerSceneryRecord.kt`). Record (`POST /programmer/record`, `record-look`, the AI's
`record_cue` and `record_look`) writes the programmer's held scenery into the owner's rows unless its request says
`scenery: false`; no mask, source or fixture scope narrows it.

- **Into a cue**, a held piece the cue has no row for is written only where it differs from what the
  cue shows without it — `sceneryBeneathCue`: the base, the stack's set, then every earlier cue entry
  of the list, the resolver's own fold with the cue's own rows left out. Equal, it is counted as
  `sceneryAlreadyTracked` and not written. A row the cue already has is replaced, whatever it says —
  its clock kept unless the held piece carries one from Include — and a row already saying exactly
  that is left alone and not counted. MERGE and UPDATE_EXISTING are that; REMOVE deletes the cue's
  rows for the held pieces. A marker is no cue and is refused by Record outright. A new cue is read at its own place in
  the list, after it is created.
- **Into a Look**, every held state is written, merged per element over the Look's rows; REMOVE
  deletes them. A Look recorded with only scenery held is a scenery Look.
- **Include** (`programmer/include`, `include_into_programmer`) holds the source's own rows in the
  overlay (`ProgrammerScenery.include`), each replacing what it held on that element — never merged,
  never the tracked state, never masked — on the Include's fade, a cue row's `transitionMs` kept on
  the held entry (a later move keeps it). A cue with only scenery sets the include target.
- **Update** writes the held scenery back in Mode A only — into the cue by the MERGE rule above, into
  a Look as its scenery. Mode B writes none.
- **The baseline.** Include sets one (the source's rows) when it moves the include target — an Include that stages nothing leaves both where they were — a Record that retargets the include target
  (into a cue or a Look) resets it to what the source now says (all held, or none when the Record left scenery out or removed
  it), and a Mode A Update to everything held. `programmer.sceneryState` carries
  `changedSinceInclude` — the held pieces that differ from it — which the source strip adds to its
  value dirty count, because the include target and the overlay are two flows and a client cannot
  order them. A project switch drops it.

**Read by element, the other way round** (scenery-programmer plan D11, session 4): `GET
…/stage-elements/{id}/scenery` answers every owner that moves one element — its cues in show order
with their clocks, the stacks whose set holds it, the Looks that show it — each owner's own row and
never what a cue only tracks (`elementSceneryOf`, `routes/projectScenery.kt`). It is the Stage
view's *Moves with*: the popover a click on a piece opens, and the element form, whose entries open
each owner's editor. The popover itself writes the programmer's overlay (tier 5), as the programmer's
Scenery table does; in Blind the Stage view's programmer sources draw `staged` (D12).

## Events

A cue's **events** fire one-shot triggers — a confetti cannon's tubes — at their offsets after GO
into the cue, only while the desk is armed (stage-view plan session 9; `cues-engineering.md`
§"Cue events" is the contract). Two facts are the stack manager's:

- **The hook is the hold registry's.** Activation builds the cue with `buildCueGoData` (the one
  builder that carries events, so no republish or preview can fire one) inside its own read, and
  `CueHolds.acquire` calls `state.effectsService.onCueGo(cueId, label, events)` on the cue's
  **first** hold only (D15) — so every path that brings a cue on fires them once: a GO from REST,
  MIDI or auto-advance, and a loose press (a busk pad, a cue slot, `/cues/{id}/apply`, the AI's
  `apply_cue`). A GO onto a cue another stack or a press already holds fires none, and neither does
  a reload re-taking its holds. `advanceStack` passes `fireEvents = false` for BACKWARD: stepping
  back into a cue fires nothing. **The hook only schedules** — it judges the arm and blind
  at that instant and launches one coroutine per event on the effects service's scope; nothing
  blocks the GO and nothing runs inside a caller's transaction (the pool is one SQLite connection).
  A fire touches memory and the trigger output, and the spent tube is written by the service's own
  worker thread, scenery's arrangement.
- **A stack stop drops the arm.** `deactivateStack` calls `effectsService.onStackStopped`, which
  disarms the desk — any stack stopping is a stop, and the pending events of an armed GO find the
  arm gone when their offset comes and are announced as skipped.

## WebSocket

### Messages

```json
{"type": "cueStackListChanged"}
```

Broadcast on stack CRUD operations. Frontend subscribes and invalidates `CueStackList` RTK Query tag.

```json
{"type": "cueRunStateChanged", "projectId": 1, "stackId": 4, "activeCueId": 12,
 "nextCueId": 13, "activeEntryId": 31, "nextEntryId": 32, "standbyEntryId": null,
 "nextIsArmed": false, "transition": true, "fadeDurationMs": 2000,
 "fadeElapsedMs": 0, "autoAdvance": false, "autoAdvanceDelayMs": null}
```

A stack's run state: what is live, what the next GO fires, and how far through a fade the desk
is. **One frame per change, not a per-tick stream** — the client animates the fade locally
from `fadeElapsedMs` + `fadeDurationMs`, the way the session that pressed GO always did. The
crossfade itself still ticks at 60 fps inside `FxEngine`; that never goes on the wire.

The entry ids are the run state's identity (cue model plan P2): a stack that names one cue twice
is on one of its two entries, and only `activeEntryId` says which. The cue ids beside them are what
the entry names, kept for every reader that only asks which cue.

Fired from `CueStackManager`, not from the routes, so *every* path reports: REST, the MIDI
surface's GO binding, a cue-edit live apply, and the auto-advance timer (which previously moved
the rig with no client ever being told). The stack-list writes are the exception, because they can
change an active stack's next entry without a transition: every handler that places, reorders or
sorts a cue entry ends in `announceEntriesPlaced` (`routes/projectCueStacks.kt`), a move in
`announceEntryMoved` and a removal in `afterEntriesRemoved`, and each publishes the stack's run
state when it is active, as the stack's PUT does, since its loop decides what follows the last
entry. A marker is never a stack's next, so adding or removing one publishes nothing. `setupBroadcastSubscriptions` also sends one frame per
stack in `stacksWithRunState()` on connect, so a session that opens mid-fade animates the
remainder instead of nothing. The snapshot is read *after* the listener is registered, so a GO
landing in between is never missed — but that GO can then reach the socket twice, and every frame
is its own launch, so the two can arrive in either order. Each frame therefore carries a
server-side `CueRunState.seq` (one process-wide counter, taken *before* the state is read, so a
frame stamped `n` is no older than any publish stamped `n` or below), and a per-socket
`CueRunStateGate` sends only frames newer than the last it sent for that stack. A snapshot that
ties a change already sent is dropped; a change that ties a snapshot still goes, because only it
carries `transition`. `seq` is not on the wire.

`fadeElapsedMs` is an elapsed duration, deliberately not a start timestamp: a tablet with a
skewed clock would otherwise animate a fade that is already over. Null means no fade is running
— which is also how a client tells a standby-only change (leave the animation alone) from a cue
transition.

`autoAdvance` reports whether the stack **will** roll forward — a live `autoAdvanceJob`, not
merely a cue configured for one. `pauseAutoAdvance` / `resumeAutoAdvance` publish a frame for
exactly this reason: a cue-edit Live session cancels the timer on a cue still flagged
`autoAdvance`, and since the client draws the countdown but no longer *drives* it (see below), a
config-shaped answer leaves every session with a bar completing into nothing.

```json
{"type": "cueHolds.state", "projectId": 1, "cues": [
  {"cueId": 12, "priority": 1003, "holds": [{"kind": "STACK", "stackId": 4, "entryId": 31}]},
  {"cueId": 20, "priority": 2000000007, "holds": [
    {"kind": "STACK", "stackId": 5, "entryId": 40}, {"kind": "LOOSE", "seq": 7}]}]}
```

What holds every live cue (§"Holds"): a `StateFlow` (`State.cueHoldsFrame`), so the subscription is
the connect snapshot and every change is the whole frame. Outbound only. It is what the busk pad's
and the cue slot's ring read (D11: solid while a `LOOSE` hold is among them, outline while only
stacks hold it) and what "is this cue on stage" means everywhere (`useCueRings`, which replaced `useActiveCueIds`).

### The client no longer drives auto-advance

lighting-react used to run its own auto-advance timer and call the server when it finished. With
the transition broadcast in place that would step the stack once per open session, so
`scheduleAutoAdvance` here is the only timer and the client's countdown is a display of it. Any
change to who owns that timer has to move both halves together.

### FxState Integration

The `fxState` WebSocket message includes `cueStackId` on each effect in `activeEffects`:

```json
{
  "type": "fxState",
  "activeEffects": [
    { "id": 1, "effectType": "SineWave", "targetKey": "front-wash", "cueId": 10, "cueStackId": 1, ... }
  ]
}
```

## Frontend Integration

### API Layer
- `src/api/cueStacksApi.ts` — Type definitions for CueStack, CueStackInput, CueStackCueEntry, etc. A `CueStackCueEntry` is a union keyed by `cueType`: a `CueStackCueRow` (`id` the cue's) or a `CueStackMarkerRow` (`id: null`), both carrying `entryId`; rows are keyed, sorted, reordered, armed and gone to by `entryId`, and `isCueRow` / `cueRowById` narrow to cue rows
- `src/api/cueStacksWsApi.ts` — WebSocket subscription for `cueStackListChanged`
- `src/api/lightingApi.ts` — Registers `cueStacks` WS API
- `src/api/fxApi.ts` — `FxEffectState` includes `cueStackId`
- `src/api/cuesApi.ts` — `Cue` interface includes `cueStackId`, `cueStackName`, `entryId`, `sortOrder` (the context entry's), `entries` (every place) and, on the project list only, `usedBy`

### State Management
- `src/store/cueStacks.ts` — RTK Query endpoints with `CueStackList` tag, all CRUD + control mutations, and the marker mutations (`createMarkerEntry`, `renameMarkerEntry`, `deleteMarkerEntry`)
- `src/store/cues.ts` — `useActiveCueStackIds()` hook derives active stack IDs from FxState
- `src/store/restApi.ts` — `CueStackList` added to tag types

## MCP tools

Nine tools (`ai/AiTools.kt`, offered over MCP — `docs/mcp-engineering.md`). Each names a cue by id
and a **place** by entry (cue model plan §3.10): `entryId`, or the stack's `number` — the one the
Show view shows, unique in its stack — resolved by `ai/CueEntryRefs.kt`. A cue id alone never names
a place, since a cue may be in a stack twice (D7); `activate_cue_stack` and `set_standby` refuse one
by name.
- `create_cue_stack` — Create with name, loop
- `activate_cue_stack` — Activate, or GO to one entry (`entryId` or `number`). With no entry named
  it routes to `activateAtFirstCue`, so it honours an armed standby exactly as the REST route and
  the surface do
- `deactivate_cue_stack` — Deactivate
- `go_cue_stack` — GO: fire whatever is on deck, starting the stack if it is stopped. It calls
  `CueStackManager.go`, which is also what `SurfaceActions.cueStackGo` calls — the active-or-not
  branch is GO's whole meaning, so the model, the tablet and the physical GO button press one
  button rather than three implementations of it
- `advance_cue_stack` — Advance forward/backward; the model is pointed at `go_cue_stack` for FORWARD
  and this for BACKWARD
- `set_standby` — Arm an entry (or, naming none, disarm) as what the next GO fires, without moving a
  light; answers `standbyEntryId`, the effective `nextEntryId` and the live `activeEntryId`
- `add_cue_to_stack` — `{stackId, cueId, number?, after?}`: a new entry, sharing the cue with any
  stack it is already in, after the entry `after` names (`{entryId}` — a marker's too — or
  `{number}`), else at the end; answers the entry. `sortOrder` is refused by name
- `remove_cue_from_stack` — `{stackId, entryId | number}`: the REST entry delete (`removeEntries`,
  `announceEntriesRemoved`), never the cue, which is loose once its last entry goes
  (`cueLeftLoose`); a live entry leaves the stage alone until the next GO (D16)
- `move_cue_to_stack` — `{stackId, entryId | number, toStackId}`: *Move to stack…*
  (`moveEntryChecked`, `announceEntryMoved`, shared with the REST move), the entry whole — explicit
  number, follow and prompt-book anchor — to the end of the other stack; answers the moved entry

`create_cue` takes `stackId` with `number` and `after` to place the new cue as `add_cue_to_stack`
does, and the setup tool `build_cue_stack` (`ai/SetupTools.kt`) a row naming `cueId` to share one;
a row that does may carry only the entry's own `number`, `followSeconds` and `at` (an empty list, blank
text or `false` beside them is ignored). `create_cue`, `add_cue_to_stack`, `remove_cue_from_stack` and
`move_cue_to_stack` end in the REST route's announcement (`announceEntriesPlaced`,
`announceEntriesRemoved`, `announceEntryMoved`), and `build_cue_stack` and `copy_cues` into a stack
end in `announceEntriesPlaced` when they write the current project, so a busk pad's numbers and a
live stack's next follow.

The `get_current_state` tool includes `cue_stacks` in its default include set, returning stack names, cue counts, and the live cue and entry, `cue_run` alongside it for the run state of every stack that has one — `activeEntryId`, the armed-or-positional `nextEntryId` / `nextCueId`, `standbyEntryId`, `nextIsArmed`, and the live fade — and `cue_holds`, what holds each live cue (a stack's entry or a loose hold), from `cueHolds.state`'s frame. `cue_stacks` cannot answer what the next GO fires: "next" is server-owned (see §"Standby"), so a model that armed a standby has no other way to read its own arming back. Its `cues` list each cue's `entries`. Active effects include `cueStackId`. Auto-advance is an entry's and the crossfade a cue's, set on the entry and the cue editor's Cue tab or by `build_cue_stack`.

The MCP `instructions` (`RigBriefing.keyConcepts`) describe cue stack concepts and workflow.
