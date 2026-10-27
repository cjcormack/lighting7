# A show's order for a venue group

> **Document status: READY — calls decided 2026-10-27 (§3).** No session has started.

## 1. Context

A group's member order is the order its FX run in under every Order, and the order a fan runs in
when the group is its one target (`docs/groups-engineering.md` §"Member order"). On a show pinned to
a venue, a venue group is read-only: the group sheet says *The venue's group: its order is set in the
venue*, and the patch-group PUT answers 409 `VENUE_OWNED`. A show that wants the house wash to chase
the other way has two ways round today, and both are worse. It can edit the venue, which changes the
order for every show. Or it can hide the venue group and add its own of the same name, which keeps its
cues working (targets name a group by name) but stops it following the venue's membership changes.

Show recolours (tables-colours plan D10, `models/showColours.kt`) already let a show change one
thing about a venue group: a row keyed by the group's uuid, laid over the pinned version by
`RigScope`. This plan gives member order the same shape.

What this plan is *not*: a show changing a venue group's membership, per-member pan/tilt offsets or
name. Those stay the venue's (§7).

## 2. Decisions

- **D1 — Order only.** A show may reorder a venue group's members. It cannot add, remove or rename
  them, and the offsets stay the venue's. A show's own groups are unchanged.
- **D2 — `show_group_orders (project, group_uuid, members, uuid)`**, unique on `(project,
  group_uuid)`. `members` is a JSON list of the venue patch uuids, in the show's order. Uuids, not
  ids, because a uuid is a record's identity across the venue's versions, as a hide's and a
  recolour's are. A venue group never holds a show patch (venue plan D12), so every uuid in the list
  is a venue patch.
- **D3 — The effective order is a merge, not a replacement.** `GroupOrder.merge(venueOrder,
  wanted)` is a pure function: the members `wanted` names that the venue group still holds fill, in
  `wanted`'s order, the places those members hold in the venue's order. A member the venue added
  since keeps its venue place, and one the venue removed is skipped. That is exactly
  `reorderGroupMembers`' rule, which moves onto `GroupOrder` and is shared, so a stored reorder and
  an overlay can never disagree. Hidden patches are filtered first, as today.
- **D4 — One read.** `RigScope.members(group)` answers a `List` in effective order, numbered
  `0…n−1`, and every reader stops sorting by `sortOrder` itself: `DbFixtureLoader` (line 121), the
  group detail DTO (`routes/projectPatchGroups.kt` line 242, whose `sortOrder` becomes the effective
  index), `describe_rig` (`ai/SetupTools.kt` line 440), `edit_groups` (`ai/PatchEditTools.kt` lines
  316 and 357), Detach (`venue/VenueDetach.kt` line 173) and `commandRows.kt`. A standalone show and
  a venue have no overlay, so their order is unchanged.
- **D5 — The write is the group PUT's.** Through a show, a body naming only `memberOrder` (with or
  without `colour`) on a venue group writes the overlay rather than 409 `VENUE_OWNED`, as a body
  naming only `colour` writes a recolour. `memberOrder` stays patch ids and keeps its partial
  semantics: it is merged onto the current effective order, then stored whole as uuids. An explicit
  `"memberOrder": null` is *Take venue's* and deletes the row. So does a write whose result equals
  the venue's order, so no row ever restates the venue. It is a stored show write either role may
  make, like a recolour (call C). A hidden group refuses it, answering as a recolour of a hidden group does.
- **D6 — Hiding leaves it dormant.** A hidden group's overlay stays and comes back with the group,
  as a recolour does.
- **D7 — Drawing only for the hold.** An overlay never puts a show behind on output, and a venue
  reorder stays drawing only in `VenueDiff`, which already counts every group change as drawing. The
  hold is about channels and addresses, and order changes neither.
- **D8 — DTOs.** `PatchGroupDto` and the detail DTO carry `orderOwner: SHOW | VENUE` on a venue
  group, beside `colourOwner`. `GET /groups` needs nothing new: it reads the loader's order.
- **D9 — Sync, `formatVersion` 44.** `showGroupOrders/{uuid}.json`: `{uuid, groupUuid, members}`.
  A clone remaps only `uuid`. The importer keeps `members` as written: they name the pinned
  version's patches, which the importer already seeds. A v43 desk would import the show without the
  folder and push it back without it, which is the case the bump rule names, so the bump is needed.
  `minReader` stays 5. `RichProjectFixture` gains a non-default overlay, and `SyncCoverageTest` gains
  the table as portable.
- **D10 — Detach writes the effective order into the copies.** It already copies each venue group's
  members as `rig.members` answers them, but `copyRow` keeps the stored `sortOrder`. Each copied
  member's `sortOrder` becomes its effective index, and the show's overlay rows are deleted with its
  recolours. The answer counts the orders applied, as it counts recolours.
- **D11 — Attach turns an order-only difference into an overlay.** `VenueAttachPairing.groups()`
  judges ordered member lists, so today a show group with the venue's members in another order is a
  `group:` decision, and *Keep mine* forks it (the show keeps its own, the venue's is hidden). When
  the two sides hold the same members (after the attach's hides) and differ only in order, the pair
  goes the *Take venue's* way, and the show's order is planned as an overlay beside
  `plannedColours`. With an origin, it is three-way like colour: if only the venue reordered, the
  show takes the venue's order and no overlay is written. No question is asked (call B). A
  membership difference keeps today's decision unchanged.
- **D12 — Update drops an overlay whose group the target lacks**, as a `dropped` item `ORDER`, like
  a recolour (`venue/VenueUpdate.kt` line 230). A member the target removes needs no item: the merge
  skips it. When the venue *reordered* a group the show orders, Update asks (call A): an
  `order:<groupUuid>` decision, `KEEP_MINE` by default, `TAKE_VENUES` deleting the overlay, listed
  beside facet drift and, like drift, kept unless answered rather than blocking.
- **D13 — MCP.** `edit_groups` on a venue group in a show takes `members` when they are the same
  set as the group's, and writes the overlay. A different set stays `VENUE_OWNED`, naming *Make it
  the show's own* once it exists. `describe_rig` lists the effective order and marks a show-ordered
  group.
- **D14 — The group sheet and the Edit Group sheet** become reorderable on a venue group in a show,
  with only the order editable (name, members and colour as today). A *Your order* mark and *Take
  venue's* sit where the recolour's *yours* and *Take venue's* do. `useGroupMemberReorder`'s
  `VENUE_GROUP_ORDER_REASON` goes, and `VENUE_ADMIN_ORDER_REASON` stays for an operator in a venue.

## 3. Calls

Decided 2026-10-27.

- **A — Update and a venue reorder: a decision, kept unless answered** (D12). It needs no stored
  base: Update compares the from-snapshot's order with the target's, and the pin only moves at
  Update. Not *no drift, like colour*, because order changes what the FX look like and colour never
  does. Not *a stored base, like facets*, which would add a column, *Keep mine* and a drifted mark
  for no extra signal.
- **B — Attach's order-only pairs: an overlay, no question** (D11). An order-only difference has one
  sensible answer, and the review lists it as a drawing change.
- **C — Who may reorder: both roles** (D5), as with a recolour. Admin only would make it the first
  show-side override an operator could not make.

## 4. Risks

- **A reader that sorts by `sortOrder` itself** would read the venue's order and disagree with the
  loader, and nothing would fail. D4 lists every reader today. Session 1 adds a test that loads a
  show with an overlay and compares `GET /groups`, the group detail, `describe_rig` and `edit_groups`'
  answers.
- **Attach's judge** compares ordered lists in more than one place (`venueSide`, `showSide`,
  `originSide`). An order-only check has to compare the members as sets on all three sides, or a
  three-way case will misjudge who changed what.

## 5. Implementation — two sessions

Each session is one PR, run by `/next-session`. Session 1 is backend only: the frontend reads the
order through the same DTOs, so it shows a show's order, read-only, until session 2. Session 1 runs
the full `./gradlew test` and `npm run check` locally, as a sync `formatVersion` bump requires;
session 2 runs the frontend's `npm run check:quick` (root `CLAUDE.md` §"Pre-commit checks"). A
session's PR is titled *Group order: session N — title*. Never merge session 2's PR before session
1's.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The overlay on the desk | planned | | | |
| 2 | The sheets and the reviews | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — The overlay on the desk

- `models/showGroupOrders.kt`: the table, `ShowGroupOrders.put` (deleting a row equal to the
  venue's), added to `Schema.kt`'s `ALL_TABLES`. `models/groupOrder.kt`: `GroupOrder.merge`, with
  `reorderGroupMembers` rebuilt on it.
- `models/RigScope.kt`: `groupOrders` by group uuid (lazy, like `colours`), `members` answering the
  effective order, `orderOwnerOf`. Every reader in D4 moves onto it.
- `routes/projectPatchGroups.kt`: the overlay write (D5) and `orderOwner`. `routes/projects.kt`'s
  project delete deletes the rows.
- Sync: `ShowGroupOrderJson`, exporter, importer, cloner, v44, `RichProjectFixture`,
  `SyncCoverageTest`, `ProjectRoundTripTest`.
- Venue: Detach (D10), Attach pairing and write (D11), Update (D12).
- MCP: `edit_groups` and `describe_rig` (D13).
- Tests: `GroupOrder.merge` (added, removed and hidden members, an empty overlay), the route on a
  venue group in a show (write, partial order, `null`, equal-to-venue clearing, hidden group,
  operator), loader order, Detach, Attach (order-only with and without an origin, membership
  differing), Update (group removed, venue reordered under each answer), MCP.
- Docs: `docs/groups-engineering.md` §"Member order", `docs/fixtures-engineering.md` §"Shows on a
  venue", `docs/sync-engineering.md` §"Version 44", the root `CLAUDE.md`'s venue paragraph.
- Run the full suites locally: a `formatVersion` bump.

### Session 2 — The sheets and the reviews

- `components/groups/useGroupMemberReorder.ts`: editable on a venue group in a show, read-only
  only for an operator in a venue.
- `GroupMembersSection.tsx` and `patches/EditGroupSheet.tsx`: the drag on a venue group, *Your
  order* and *Take venue's* (`memberOrder: null`). The Edit Group sheet keeps name and members
  locked.
- The Update dialog's `order:` decisions (D12) and the `ORDER` dropped row; the Attach review's
  order line.
- Tests beside the existing `GroupMembersSection.test.tsx` and `memberOrder.test.ts`.

## 6. Operator checks

One row in `manual-validation.md` per merged session: a venue group reordered in a show, chased on
the rig, and taken back; the venue's order unchanged in a second show on the same venue; an Update
across a venue reorder.

## 7. Explicitly out of scope

- A show changing a venue group's membership, per-member pan/tilt offsets or name (D1).
- **Make it the show's own**: hide the venue group and add a show group of the same name, members,
  offsets and colour, re-pointing busk rig tiles, which name a group by id. That is the answer for
  membership, and a follow-up rather than part of this plan; it goes in `followups.md` as
  Trigger-gated until a show needs a venue group's membership changed.
- Reordering a show's own groups, which works today.
