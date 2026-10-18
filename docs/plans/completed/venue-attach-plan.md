# Attaching a show to a venue — Detach in reverse

> **Document status: DONE — sessions 1–3 shipped 2026-10-17, each session's heading citing its
> commit; closed with the venue-visit plan at its K6.** Agreed 2026-10-15. This is the re-attach
> half of design session R5 of [`venue-visit-plan.md`](venue-visit-plan.md). Its navigation half is
> [`navigation-plan.md`](navigation-plan.md).
>
> - **Questions.** The questions asked and their answers are in §10 and in the shared design record,
>   [`navigation-design/INDEX.md`](navigation-design/INDEX.md), with the attach board.
> - **The one-page copy.** A one-page copy of both plans for human readers is
>   [`navigation-design/navigation.html`](navigation-design/navigation.html), with a live copy at
>   <https://claude.ai/artifact/56QW1wW5M8HcB7yXCzqbhX>. The checked-in file is the authority.
>
> This plan builds on the venue plan
> ([`venue-plan.md`](venue-plan.md)) and changes one of its sessions: Detach
> (session 7) starts recording where a show came from. It does not reopen D9 ("no touring tool"),
> and moving a show between venues stays `FU-VENUE-TOURING`.

## 1. Context

- **The gap.** Venue-visit §1 notes that "A detached show can't be re-attached to a venue." Detach is
  the only way off a venue (venue plan D9), and it is one-way.
- **What this plan adds.** *Attach to venue…* puts a standalone show onto a venue version, whether the
  show was detached or never on a venue. The show's rows that are the venue's records go, its
  differences become what a show on a venue already has (facet overrides, hides and its own
  additions), and the pin comes back.

What the code does today, read on `main` at `d35463c`:

- **Detach writes the effective rig at the pin into the show's own rows**
  (`venue/VenueDetach.kt`).
  - **What it writes:**
    - universes, riggings, regions, VENUE-layer elements, Fixed seating, viewpoints, groups and their
      visible members;
    - patches and placements, with the overridden facet fields (`:201-247`).
  - **Uuids.** `copyRow` keeps every uuid, swapping only the project (`:355-367`).
  - **Links.** The twelve `VenueLinks` are re-pointed snapshot → show (`:253-259`).
  - **Machine overrides.** The venue-id rows are **copied** to the show's id (P4), and any the show
    already had are kept (`:262-271`).
  - **Parks.** Each snapshot park the show neither re-parked nor released becomes a show row with the
    venue park's uuid. Releases are deleted (`:296-309`).
  - **Clean-up.**
    - Hides and overrides are deleted.
    - The version's stage size is copied.
    - `venueUuid`, `venueVersion` and `venueRepo` are nulled (`:312-320`).
- **Nothing records the origin.** Once detached, a show cannot say which venue or version it left.
- **Uuids are stable across a venue's versions.**
  - `KeptUuidCopy` makes every version from the draft with uuids kept.
  - A draft row keeps its uuid from publish to publish.
  - So a detached show's rows still share uuids with the same records in **any** later version, not
    just the one it left.
- **Matching by uuid is safe within a scope only.** Record uuids aren't unique across projects
  (`docs/fixtures-engineering.md` §"Rig scope"). `VenueLinks.repoint` treats a uuid held twice in its
  target as matching nothing (`models/VenueLinks.kt:90-94`).
- **Update is the model for a review.**
  - `venue/VenueUpdate.kt` answers its review as the same run rolled back.
  - It carries `drift` (`KEEP_MINE | TAKE_VENUES`), P8's address-overlap `decisions`, `blockers`,
    `dropped` and `deadReferences`.
  - Re-attach reuses each of these shapes.
- **A show on a venue can differ from it in three ways.** It can add records of its own, it can hide a
  venue record (six kinds, `show_hides`), and it can override one of a venue patch's four facets
  (Address, Position, Focus, Media, `show_overrides`, each with a `base`).
  - Identity is the venue's: name, key, fixture type, head number, placements' count, order and
    labels (D4).
  - Riggings, regions, elements, groups and viewpoints can only be hidden
    (`FU-VENUE-ELEMENT-OVERRIDES`).
- **Keys are one namespace.** A show record may not take a visible venue record's key, head number or
  name (`KEY_TAKEN_BY_VENUE`, `venue/VenueRigGuard.kt`). Universes are matched by `subnet:universe` in
  Detach's clash check and not in Update's.
- **Show content names fixtures by key.** Cue rows and ad-hoc effects, Look rows and effects, palette
  rows and surface bindings carry `targetType` + `targetKey`. Group members, busk rig tiles, cue
  events and scenery use row ids, which `VenueLinks` re-points.
  - Since R4.3 (`3cefa3ed`) a cue's ad-hoc effect or a Look effect may target a `selection`, whose
    key is a JSON list of fixture keys and group names (`EffectTargetRef.Selection`,
    `models/TargetRef.kt`). A key match must look inside it.
  - `ai/RigKeyReferences.kt` already walks all of these for MCP's `rename_fixtures` and
    `unpatch_fixtures`: `rewrite` renames a key everywhere, a selection's items and busk rig cell
    tiles included, and `users` finds who names one, scripts included.

## 2. Decisions

Agreed with Chris, 2026-10-15 (§10):

| # | Decision |
|---|---|
| D1 | **Detach records where the show came from.** `detachedFrom` holds the venue uuid and version, and is portable. Re-attach matches against the version picked, by default the installed one. With an origin, a venue record that existed at the origin version but that the show lacks becomes a hide, and one added since comes in, as an Update would bring it. Without an origin (a standalone show, or one detached before this ships), every unmatched venue record comes in, and the review lets each be hidden. |
| D2 | **A matched patch whose identity differs keeps the show's by default.** Identity is key, fixture type, head number or placement count. The venue's patch is hidden, and the show's stays as its own addition under a fresh uuid, so every cue, Look and palette row still finds its key. Per row, *Take venue's* renames the show's references from its key to the venue's in the same transaction. |
| D3 | **Hall structure takes the venue's by default, and groups keep the show's.** A differing rigging, region, scene element or viewpoint becomes the venue's, listed as a change. A differing group keeps the show's: the venue group is hidden and the show's kept under a fresh uuid. Each row can be flipped in the review. |
| D4 | **Both roles may attach**, as with Detach and Update. *Attach to venue…* is in a standalone show's project actions (card, home and ⌘K) and is reviewed as Update is. A running show reloads. An MCP tool, `attach_to_venue`, takes a `dryRun`. |

The plan adds its own:

- **P1 — One PR per session**, in §8's order. Each session ends with `./gradlew test` and
  `npm run check` green, its engineering-doc paragraphs written, and a done-marker in its heading
  citing its change commit(s).
  The venue-visit plan's P1 checklist ([`venue-visit-plan.md`](venue-visit-plan.md)) applies
  before each PR.
- **P2 — Attaching what Detach just wrote changes nothing.** Detaching a show and attaching it back
  to the same version leaves:
  - the same effective rig, compared through `RigScope.effective`;
  - the same output;
  - the same parks;
  - the same hides and overrides, up to the uuids of the rows they name.

  This round trip is the plan's first test and its definition of correct.
- **P3 — Three-way where there's an origin.** With `detachedFrom`, each matched record is compared
  three ways:
  - the show's value (S);
  - the origin version's value (O);
  - the target version's value (T).

  This tells a change the show made from one the venue made, as Update's drift does. Without an
  origin, every difference counts as the show's.
- **P4 — Review is the run rolled back**, as Update's is (`VenueUpdate.run(…, dryRun = true)`): one
  code path, so the review cannot promise what the apply won't do.
- **P5 — A kept record never shares a uuid with a visible venue record.** Every show row that stays
  its own while a venue record matches it (D2, D3, a key-only match) takes a fresh uuid. The show's
  uuid-keyed references to it are rewritten to match: machine overrides, seat views'
  `seatElementUuid`, a platform's `regionUuid`, and its own facet values. So no uuid is ambiguous
  in the scope (`VenueLinks` §"ambiguous").
- **P6 — Universes are never hidden and never overridden.** A venue universe always comes in. A show
  universe matching one goes, and its machine-local address is the venue's (P4 of the venue plan). If
  that address differs here, it is listed as an output change.

## 3. Matching

### 3.1 Inputs

- **Show.** The show's own rows. It must be a standalone show: no pin and kind `SHOW`.
- **Target.** The target version's snapshot (`venue_versions`), resolved like a pin.
- **Origin.** Optionally, the origin snapshot, when `detachedFrom` names a version this desk holds.
  - If it names a version the desk lacks, attach goes on without an origin and says so.
  - If it names another venue, the origin is ignored. That case is touring, `FU-VENUE-TOURING`.

### 3.2 Pairs: uuid first, then key

Each of the show's records is paired with at most one target record of its kind:

| Kind | First by | Then by | Notes |
|---|---|---|---|
| Universe | uuid | `subnet:universe` | |
| Patch | uuid | `key` | Placements pair by placement uuid inside a paired patch, else by position. |
| Rigging, region, viewpoint | uuid | name | |
| Scene element | uuid | name | VENUE layer only. SET elements are always the show's own. |
| Seating | uuid | name | Fixed blocks only. A show's Preset copy keeps the preset's uuid but never pairs, since the scope never loads the venue's Preset blocks. A standalone show's seating has no `block` and is treated as Preset. |
| Group | uuid | name | Members are compared through the patch pairing. |
| Park | `universe:channel` | | |

- A uuid pair wins over a key pair.
- **A record on each side of two different pairs.** A show record may pair by uuid with one venue
  record while its key belongs to another venue record. The second is then a **key clash**: it is
  hidden if it can be (D2's default), and is a blocker if it can't (§3.6).

### 3.3 What a pair becomes

Each pair compares S with T, and with O where there is an origin (P3):

| S against O and T | Patch facets | Identity (D2) | Hall structure (D3) | Group (D3) |
|---|---|---|---|---|
| S = T | inherit (row goes) | inherit | inherit | inherit |
| S = O ≠ T: the venue changed it | inherit the venue's (no override) | the venue's (references renamed) | the venue's | the venue's |
| S ≠ O, O = T, or no origin: the show changed it | **override**, base = T | keep mine (default) / take venue's | take venue's (default) / keep mine | keep mine (default) / take venue's |
| S ≠ O ≠ T, S ≠ T: both changed it | **override**, base = O: drift, answered *Keep mine* (default) / *Take venue's* as in Update | as above, flagged *both changed* | as above, flagged | as above, flagged |

- **Inherit.** The show's row is deleted. Its row-id links are re-pointed onto the target record
  (§4.2), and its uuid-keyed references are rewritten to the target's uuid where the pair was by key.
- **Override.** The show's row is deleted, as for inherit, and a `show_overrides` row is written for
  each differing facet. Its value is the show's facet, with uuids mapped through the pairing, and its
  `base` is as in the table.
  - **A facet that names a show-only record.** A show patch on a universe or rigging that has no pair
    keeps it. An Address or Position override may name the show's own universe or rigging, which the
    effective rig holds (`docs/fixtures-engineering.md` §"Facet overrides"), so it is not dormant.
- **Keep mine.**
  - The target record is hidden (`show_hides`).
  - The show's row stays, with a fresh uuid (P5).
  - For a group, its members, which may include venue patches (D12), are kept.
- **Take venue's** for identity is inherit, plus a **rename**:
  - Every key-named row the show owns that names the show's key is rewritten to the target's key:
    cue property assignments, layer targets and ad-hoc effects, Look rows and effects, palette rows,
    surface bindings, busk rig cell tiles, and each item of a selection effect. That is
    `RigKeyReferences.rewrite` (§1), which the rename reuses rather than copies.
  - A changed fixture type keeps rows the new type can't take. They read as *Missing property*
    (`AssignmentHealth`).
  - Scripts aren't rewritten. They are listed as dead references.

### 3.4 What has no pair

- **A show record with no target pair is a show addition** and stays the show's own. A link from it to
  a paired record is re-pointed (§4.2).
- **A venue record with no show pair:**
  - **With an origin**, if the record's uuid is in the origin snapshot, the show removed it after
    detaching, so it is **hidden**. Otherwise the venue added it since, and it **comes in**.
  - **Without an origin**, it comes in, and the review offers *Hide in this show* per record.
  - **A refused hide.** A hide that `ShowHides.hideRefusal` refuses (`VENUE_HIDE_IN_USE`) comes in
    instead, with the reason shown.
  - **Universes.** A venue universe always comes in (P6).
- **Address overlaps** between a show addition (or an Address override) and an incoming venue patch
  are P8's decisions, as in Update:
  - `KEEP_BOTH`
  - `READDRESS_MINE`
  - `HIDE_VENUES`
  - `REMOVE_MINE`

### 3.5 Everything else the show holds

- **Parks.** These are per channel, resolved as `models/VenueParks.kt` resolves them:
  - **The show parks the channel.**
    - The same value as the venue's effective park: the show's row goes, so the channel is inherited.
    - With an origin, the origin's value, where the venue re-parked or unparked the channel since: the
      venue changed it (P3), so the show's row goes and the venue's comes in. That is an **output
      change** and is listed (decided with Chris, 2026-10-17, in Att.1's review).
    - Otherwise a different value: the show's row stays as a re-park.
    - Otherwise no venue park on the channel: the show's row stays as its own.
  - **The show doesn't park it, but the venue does.** This needs a judgement, so it uses the origin
    when there is one:
    - With an origin, if O parked the channel, the show unparked it after detaching, so a
      **release** is written.
    - Otherwise the venue parked it since, and it comes in. That is an **output change** and is
      listed.
    - Without an origin, a release is written, so the show's output stays as it is.
- **Machine overrides.** The show's own rows for records that pair go, because those records now
  resolve against the venue's id.
  - A universe address that differs from the venue's resolved one on this desk is listed as an
    output change (P6).
  - Overrides of records the show keeps are re-keyed to their fresh uuid (P5).
- **Stage size.** The target's is used. A difference is listed as a drawing change.
- **Scene images.** The show's store keeps its own images. A paired element reads the version's.
- **Prompt book.** It is untouched: its anchors name entries (since cue model session 1), not rig
  records.

### 3.6 Blockers

The review answers these instead of attaching (`ATTACH_BLOCKED`):

- **A key clash that can't be resolved.** The show keeps a record whose key or name a visible venue
  record holds, and the venue record can't be hidden (`VENUE_HIDE_IN_USE`). The fix is to rename the
  show's record.
- **A head-number clash** of the same kind. Head numbers are in the namespace. Detach's own check
  misses them, and this plan's doesn't.
- **A universe number clash.** A show universe left unpaired because a venue universe it does not
  pair with holds its `subnet:universe` (the venue renumbered one the show pairs by uuid). Universes are
  never hidden (P6), so it is never resolved by a hide.
- **A *Take venue's* rename onto a key the show already uses** for another kept record.

## 4. The attach

### 4.1 The wire

| Route | Notes |
|---|---|
| `GET /projects/{id}/venue/attach?venue={venueId}&to={n}` | The review: the attach rolled back (P4). `to` defaults to the venue's installed version. |
| `POST /projects/{id}/venue/attach` `{venueId, to?, decisions, dryRun?}` | Apply. `decisions` answers drift, identity, hall structure, group and overlap rows by id, as Update's do, and, without an origin, each incoming record's hide. |

- **Decisions as Att.1 built them** (`e5b60d72`). An answer is Update's `UpdateDecisionAnswer`
  `{id, answer, startChannel?}`. The pairing makes five kinds (`AttachDecisionDto.kind`): `IDENTITY`,
  `HALL`, `GROUP` and `DRIFT`, each `KEEP_MINE | TAKE_VENUES`, and `HIDE`, `TAKE_VENUES | HIDE_VENUES`.
  Each has a default the pairing applies when it is unanswered, and `decided` says only whether it was
  answered. So `ATTACH_UNDECIDED` counts P8's overlaps alone, not Update's `any { !it.decided }`. The
  pairing throws `ATTACH_INVALID` for an answer its decision does not take, and leaves an id it did not
  make to the caller: Att.2 refuses one that is not an overlap's either.
- **Errors.**
  - 409 `NOT_A_STANDALONE_SHOW` (a venue, or a show already on a venue). `projectWriteGuard` answers
    first for a version (`VENUE_VERSION_READ_ONLY`) and for an operator on a venue
    (`VENUE_ADMIN_ONLY`).
  - 404 `VENUE_VERSION_UNKNOWN` (a `to` the venue lacks) and 409 `NOT_A_VENUE` (a `venueId` that is
    not a venue), as `VenueAttachRows.inputs` already answers them. A version a desk lacks is never
    the target: the target is read from the venue's `venue_versions`.
  - 409 `VENUE_NOTHING_PUBLISHED`, also from `inputs`.
  - 409 `ATTACH_UNDECIDED` (an overlap left unanswered).
  - 409 `ATTACH_BLOCKED` (§3.6).
  - 400 `ATTACH_INVALID`.
- **Both roles** (D4), under `projectWriteGuard`, as `/detach` is.
- **A live show reloads** (`switchProject(id, reload = true)`, the carry), and fires the lists Detach
  fires.
- **MCP.** `attach_to_venue {projectId, venueId, version?, decisions?, dryRun}` answers the same DTO.
  There is no remote-access gate: it is a stored write, as Detach is. It sits beside `extract_venue`
  (`ai/VenueSetupTools.kt`, its schema in `ai/SetupToolSchemas.kt`) and takes that tool's
  `reloadLiveShow` rule: attaching the current project is refused without `reloadLiveShow: true`,
  and a dry run never needs it (`docs/mcp-engineering.md`'s `extract_venue` row).
- **Answer.** `AttachReviewDto`:
  - `{from: null, to, origin?, output, drawing, inherited, overrides, kept, hides, additions,
    incoming, renames, decisions, blockers, deadReferences}`.
  - Each item names its kind, the show's record and the venue's, and why.
  - `inherited` is a count per kind, with no rows.
  - Att.1's `AttachPlan` carries every list but two, as the DTOs Att.2 serialises (`AttachItemDto`,
    `AttachOverrideDto`, `AttachRenameDto`, `AttachDecisionDto`, `AttachOriginDto`), and its
    `writes` is not part of the answer. Att.2 adds `deadReferences` and P8's overlap decisions, both
    judged on the written rig (§4.2 steps 7 and 8). It also adds each rename's reach, which §5's
    decision rows show and `AttachRenameDto` lacks: `RigKeyReferences.users` over the show answers
    who names the key.

### 4.2 The transaction

In one transaction, in this order (the review is the same, rolled back):

1. Resolve the target, and the origin if there is one. Pair every kind (§3.2). Att.1 built this
   step: `VenueAttachRows.inputs(show, venueId, to)` reads the three row sets inside the caller's
   transaction, and `VenueAttachPairing.pair(show, origin, target, answers)` answers an `AttachPlan`
   whose `writes` (`AttachWrites`) lists every write below.
2. **Re-point links onto the target.** Re-point every `VenueLinks` link from each inherited or
   overridden show row to its target record, through the pairing map rather than by uuid equality,
   since a key pair's uuids differ. The map is `AttachWrites.replaced`, show row id → target row id
   per kind. `VenueLinks.Link.repoint` matches by uuid between two projects only, so Att.2 adds a
   form that takes the map. The last two links in `VenueLinks.ALL`, a placement's patch and a
   member's group, are ownership: a replaced patch's placements and a replaced group's members are
   deleted in step 5, never moved onto the venue's rows.
3. **Rewrite references.** Rewrite the show's uuid-keyed references to paired records: seat views,
   platform `regionUuid`s and facet values, through `AttachWrites.uuidRewrites`. Delete the machine
   overrides in `machineOverridesDropped` and re-key those in `machineOverridesRekeyed` (§3.5).
4. **Write the pin.** Set `venueUuid` and `venueVersion = to` (and `venueRepo` from the venue's
   sync config). Clear `detachedFrom`.
5. **Delete the show's paired rows**, children first: placements, members, then patches, groups,
   riggings, regions, elements, viewpoints, universes. Delete the show's parks in
   `AttachWrites.parksDropped` too.
6. **Write what the pairs became.** Write the overrides with their base (`overrides`), the hides
   (`hides`), the releases (`releases`), and the fresh uuids of kept rows (`freshUuids`, P5). Then
   apply the renames: `RigKeyReferences.rewrite(show, fixtureRenames, groupRenames, …)`. Its
   `renamedPatchIds` are the target's patches, since step 2 has already moved the show's busk rig
   tiles onto them.
7. **Check with the venue's guards.** Open a fresh `RigScope` and run Update's `clashesOf` (key,
   head number and name) and the overlap comparison, before against after. Any clash is a blocker. An
   introduced overlap is a decision. Update's helpers are private to `VenueUpdate` (`clashesOf`,
   `spansOf`, `overlapPairs`, `decisionOf`, `applyDecisions`, `deadReferences`), and `clashesOf` and
   `deadReferences` take its private `Pin`, so Att.2 lifts them where both can call them.
8. **Collect the leftovers.** Collect `deadReferences` (keys and group names leaving the rig that
   cue, Look and palette rows still name, a selection effect's items included as Update's own
   collection reads them since R4.3, and every script naming a renamed key) and the output and
   drawing lists.
9. **Commit or roll back.** Roll back on `dryRun`, on blockers, or on an undecided overlap. Otherwise
   commit.

### 4.3 Detach records its origin (D1)

- **The column.** `projects.detached_from_venue_uuid` + `.detached_from_version`. They are nullable,
  additive columns (`createMissingTablesAndColumns`).
- **Detach's writes.** Detach writes both in the transaction that clears the pin, and its answer
  gains `detachedFrom`.
- **Clone keeps them.**
- **Sync.** `project.json` gains `detachedFromVenueUuid` and `detachedFromVersion`, both optional,
  so they're omitted by canonical JSON when absent.
  - An older desk ignores them (`ignoreUnknownKeys`) and loses them on its next export, which costs
    only the origin.
  - Session 1 checks `docs/sync-engineering.md`'s rule for an additive optional field and bumps
    `formatVersion` only if it says to. `RichProjectFixture` sets both. It took no bump
    (`e5b60d72`): an older reader loses only the origin, which leaves a valid standalone show.
- **Machine-local or portable.** The fields are portable on purpose. A show detached on one desk and
  pulled on another can still attach with its origin.
- **Attach clears them**, and so does *Extract* (a show that extracts a venue is no longer detached
  from another).

### 4.4 Held, behind and live

- **At the installed version** a show is never behind, so it opens unheld.
- **At an older version** (`to` < installed) it is behind and judged as any pinned show is.
  - If it is behind on output and on the rig, the attach does for it what a publish does for a
    running show behind: it lifts the hold for that pin pair (*Run against*), rather than blacking
    it out mid-run.
  - The review says so.
  - **`VenueHoldService` has no door for this yet.** The reload's loader calls `judge` first, which
    holds a show behind on output unless `runAgainst` already names its pin pair. `setRunAgainst`
    refuses a show not behind (`VENUE_NOT_BEHIND`), as a standalone show is before the attach, and
    `refreshCurrent` sets `runAgainst` only for a show not held and runs after the load. So Att.2
    adds a call that records `(projectId, to, installed)` before `switchProject(id, reload = true)`.
- **On the rig,** the reload carries the running show (`Show.resume`) and crossfades what changed
  over `RESUME_FADE_MS`. By P2 that is nothing for a clean round trip.

## 5. UX — the attach sheet

`components/venue/AttachVenueSheet.tsx`, on the same shell and item rows as `VenueUpdateSheet`:

1. **Pick.**
   - **Venue.** The desk's venues, with the `detachedFrom` venue first and marked *Detached from
     here*.
   - **Version.** It defaults to the installed one. With an origin, the origin version is offered
     too, marked.
   - A line says whether matching is three-way (*Using v7, the version Sleeping Beauty left*) or not
     (*No record of where this show came from: every venue record the show lacks comes in*).
2. **Review**, grouped as Update's review is:
   - **Output.**
     - *Attaching changes no output.*
     - Otherwise each change, *changes output* first, then *drawing only*.
   - **From the venue.** The counts inherited per kind (*54 patches, 4 universes, 7 riggings…*).
   - **Overrides.** Each facet, show value against venue value. Drift rows carry *Keep mine* /
     *Take venue's*.
   - **Decisions.**
     - Identity, D2: *Keep mine* / *Take venue's*, with the rename's reach (*renames hex-7 → hex-07 in
       12 cue rows, 3 Looks, 1 palette, 1 binding*).
     - Hall structure and groups, D3.
     - Overlaps, P8.
   - **Hidden in this show.** Each record, with why (*removed since v7*, *hidden by Keep mine*).
     Without an origin, every incoming record has *Hide in this show*.
   - **Yours.** The show's additions.
   - **Dead references** and **blockers.** Attach is disabled while a blocker stands.
3. **Attach to TCH venue v8.** The review collapses to the attach's progress, then closes on the
   show's home.

- **Entry points:**
  - `projectActions`' *Attach to venue…* (navigation plan §6), on a standalone show's card, home and
    ⌘K;
  - the home's *No venue · Attach to venue…* status line.
- **The detach dialog** (`components/venue/DetachVenueDialog.tsx`) gains one line: *TCH venue v7
  will be remembered, so the show can be attached again.*

## 6. Migration

- **Shows detached before this ships** have no origin, so they attach under D1's no-origin rule.
- **Existing rows** are otherwise untouched.

## 7. Dependencies

- **Session 3 needs navigation plan session 4** (`projectActions`), which has landed: *Attach to
  venue…* is already an action there, offered on a standalone show and disabled with
  `ATTACH_NOT_BUILT` (`lib/projectActions.ts`). Session 3 gives it a dialog and a `run`. The
  Projects card, the home's ⋮ and ⌘K follow. The home's Venue line does not: `ProjectHome`'s
  `VenueRow` draws a disabled button from the action's label and reason alone.
- **No venue-visit S session is a prerequisite.**

## 8. Implementation — three sessions

Each session is one PR (P1).

### Session 1 — The origin, and the pairing — done, `e5b60d72`

- §4.3: the `detachedFrom` columns, Detach writing them, the DTO, Clone, Extract clearing them, and the
  sync field.
- **The pairing.** `venue/VenueAttachPairing.kt` is a pure function over three row sets (the show's
  own, the origin's and the target's): `pair(show, origin?, target) → AttachPlan`. It applies §3.2,
  §3.3 and §3.4, and §3.5's park and machine-override rules. It writes nothing.
- **Tests:**
  - Detach Sleeping Beauty's fixture, then pair at v7: every record inherits, with no overrides,
    hides or releases (P2's pairing half).
  - The same, after the show edits a patch's Focus: one override, base = v7.
  - After the venue publishes v8 re-addressing that patch, and attaching at v8: the
    venue's change inherited three-way, the show's drifted.
  - A key-only pair from a standalone show.
  - A key clash.
  - A changed fixture type: keep mine with a fresh uuid, and take venue's with renames.
  - A group that differs, kept.
  - A rigging that differs, venue's.
  - A show patch on a universe the venue lacks: an Address override naming the show's universe.
  - A park unparked after detach becomes a release, and one parked since comes in.
  - No origin: everything unmatched comes in.
- **Docs:** `docs/fixtures-engineering.md` §"Detach" (the origin); `docs/sync-engineering.md` (the
  field).

### Session 2 — The attach — done, `2fed1b9`

- **As built**, decided with Chris in review (2026-10-17): a show park on a universe paired with a venue
  universe of another number moves to the venue's number (`AttachWrites.parksMoved`); dead references,
  Update's too, read everything `RigKeyReferences` reads, bindings and scripts included
  (`FU-VENUE-UPDATE-BINDINGS` closed); *Remove mine* on a patch kept by *Keep mine* also lifts the hide
  of the venue's counterpart; a live show's renamed fixtures keep today's rename behaviour on the reload,
  which the review's `notes` say. The review carries P8's overlaps as `overlaps`, beside `decisions`.
- §4.1–4.2, 4.4.
  - `venue/VenueAttach.kt`, which applies the plan in one transaction, with the review as the run
    rolled back. It writes the `AttachPlan` Att.1's pairing answers (`e5b60d72`, §4.2 step 1), and
    adds what the pairing leaves to it: P8's overlaps, the dead references, each rename's reach, and
    the `NOT_A_STANDALONE_SHOW` check (`VenueAttachRows.inputs` does not make it).
  - The routes, beside `routes/venueDetach.kt`'s (its reload and list frames are the model), and
    `attach_to_venue`.
  - The reload, and *Run against* for a running show attached behind.
  - **No `formatVersion`.** The attach writes records sync already carries: hides and overrides (v24),
    releases (v27), the pin, and the origin it clears.
- **Tests:**
  - P2's round trip: detach, then attach at the same version. The effective rig and parks are equal
    field for field (through `RigScope.effective` and `VenueParks.effective`), and so is the output
    composed from a fixed programmer state.
  - The review equals the apply's answer.
  - Every blocker.
  - An undecided overlap is refused, and is applied when answered.
  - A rename rewrites cue, Look, palette and binding rows, and an item inside a selection effect.
  - No uuid is held by both a show row and a visible venue row afterwards (P5).
  - The dry run leaves the database unchanged.
  - A running show reloads, and one attached behind runs against.
- **Operator check.** This plan has no Verification section, so the session writes its own
  Outstanding row in `manual-validation.md`: on a trial venue, detach a running show and attach it
  back at the same version, and no channel moves.
- **Docs:**
  - `docs/fixtures-engineering.md`, a new §"Attach" beside §"Detach";
  - `docs/mcp-engineering.md`;
  - CLAUDE.md's Show-on-a-venue endpoints and its §Venues paragraph.

### Session 3 — The sheet — done, `b8736de`

- **As built** (2026-10-17): a rename's reach counts the records that name the key — *in 8 cues, 1
  Look* — since `usedBy` lists records, not rows, and scripts are said apart. The version offered is the
  installed one and, while the desk holds it, the one the show left. The review is read again 250 ms after
  the last answer, and an answer the desk refuses as one it no longer takes (`No decision '…'`, `'…' does
  not answer '…'`, both pinned by `VenueAttachTest`) is dropped and the review read again. A dry run sends
  *Re-address mine* without a start, so the desk picks the free one it suggests; the attach sends that
  suggestion. Update's rows moved into `VenueReviewParts.tsx`, which both sheets draw from.
- §5: `AttachVenueSheet`, the entry points, and the Detach dialog's line.
- **The entry points** (§7): an `attach` dialog in `ProjectDialog`, mounted by `ProjectActionDialogs`
  (`components/projectActions/ProjectActions.tsx`) beside Detach's; the action's `run` opens it, and
  `ATTACH_NOT_BUILT` goes. `VenueRow` takes the whole action, so its button runs it.
- **The origin is on the wire since Att.1**: `ProjectSummary.detachedFrom` and
  `ProjectDetail.detachedFrom` (`api/projectApi.ts`), typed `DetachedFrom` `{venueUuid, version,
  venueId?, venueName?}` in `api/venueApi.ts`, `venueId` and `venueName` absent for a venue this
  desk lacks. The sheet's default and its *Detached from here* mark read it.
- **Tests:**
  - The sheet defaults to the origin venue and the installed version.
  - It says three-way or not.
  - A decision row's answer reaches the POST.
  - Attach is disabled on a blocker.
  - *Attach to venue…* is enabled and opens the sheet from the card, the home's ⋮ and Venue line,
    and ⌘K. Who sees it is pinned since navigation session 4 (`projectActions.test.ts`), and that
    test and `ProjectHome.test.tsx` change from the disabled slot.
- **Docs:** the frontend CLAUDE.md's venue paragraphs, and its §"The app bar and the switcher" action
  table row and §"A project's home", which describe the disabled slot.

## 9. Out of scope

- **Moving a show from one venue to another** (`FU-VENUE-TOURING`). Attach takes a standalone show
  only. A key mapping across halls is that follow-up's.
- **Overrides for riggings, elements, regions, groups and viewpoints** (`FU-VENUE-ELEMENT-OVERRIDES`).
  D3 hides and keeps until they exist. When they land, D3's *keep mine* for hall structure can become
  an override.
- **Attaching a venue to a venue**, or a version to anything.
- **Rewriting scripts** for a rename. Scripts are listed as dead references and left alone.

## 10. Questions

Answered by Chris, 2026-10-15 (the second of R5's three rounds):

1. **How does re-attach know where a show came from?** Detach records it, and matching is three-way
   where it can be (D1). Pinning the origin version and then running Update, and asking about every
   unmatched record each time, were declined.
2. **A paired patch whose identity differs?** Keep mine by default, with *Take venue's* renaming
   references per row (D2). Always taking the venue's, and blocking until fixed by hand, were
   declined.
3. **Records with no facets that differ?** Hall structure takes the venue's, and groups keep the show's
   (D3). The venue's for everything, and the show's for everything, were declined.
4. **Who attaches, and from where?** Both roles, like Detach, from the project actions, with an MCP
   tool (D4). Admins only was declined.
