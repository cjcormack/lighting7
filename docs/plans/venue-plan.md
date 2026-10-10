# Venues and shows — a versioned venue under each show

> **Document status: APPROVED — not started.** Scope agreed with Chris, 2026-10-10, over three
> rounds of questions (the answers are in §10 and in the design record). The boards and the
> survey are in [`venue-design/INDEX.md`](venue-design/INDEX.md). A one-page copy for human readers
> is [`venue-design/venue.html`](venue-design/venue.html), with a live copy at
> <https://claude.ai/artifact/LxMaLSktSQa73AkoXQzUvh> (the checked-in file is the authority).
>
> This document is the engineering half. Where it and the record disagree, this plan wins on
> behaviour and the boards on layout and copy. It disagrees once, on purpose: P3 replaces the
> boards' "uuid links first" session.

## 1. Context

Chris's brief, 2026-10-10: a project is one thing today, but a venue's material structure (the
stage, the hall) doesn't change between shows, some things always do (the set), and some sometimes
do (fixture positions). Two refinements followed: venue details are **versioned**, so changing the
house patch or universes never changes an old show, which instead warns and offers an update; and
**seating is per block**, because the Commemoration Hall has a fixed balcony over movable stalls.
This is `FU-STAGE-VENUE-SHARED`, whose trigger has fired: the panto is a second show in the hall.

The findings that shape the plan (the record's §"What was found in the code", plus this survey):

- **Show content names fixtures by key.** Look, template and cue rows, ad-hoc effects and surface
  bindings carry `targetType` + `targetKey`. They need no change to resolve against a venue's patch.
- **Twelve foreign keys in eight tables point into tables a venue will own**:
  `fixture_patches.universe_config_id` and `.rigging_id`, `fixture_patch_placements.rigging_id` and
  `.fixture_patch_id`, `fixture_group_members.group_id` and `.fixture_patch_id`,
  `busk_rig_tiles.group_id` and `.patch_id`,
  `cue_events.patch_id`, and the three scenery tables' `element_id`. A seat viewpoint already names
  its seating by uuid.
- **Every project-scoped rig query filters by one project id**: patches 23 sites in 10 files,
  riggings 11 / 4, universes 6 / 4, scene elements 9 / 6, regions 14 / 5, viewpoints 5 / 2, groups
  10 / 7. Two lookups by uuid are not scoped at all: `routes/projectPatches.kt` (a rigging) and
  `ai/AiTools.kt` (element names).
- **uuid columns are not unique-indexed** (0 of 43), and the importer resolves links through
  per-archive uuid maps (`patchMap`, `elementMap`), never a global uuid lookup. Only the project's
  own uuid is checked for collision.
- **Machine overrides are keyed by project id as well as uuid** (`Overrides.resolveUniverseAddress
  (projectId, universeUuid)`).
- **Programmer Blind gates the programmer only**; cues still play. Blackout and the grand master
  are `TransmitModifier`s, which run **after** park.
- **The rig loads in one place**, `DbFixtureLoader.loadFixtures`, which also builds the
  controllers from the universe configs. 89 routes resolve their project through `withProject`.

## 2. Decisions

From the design record, as agreed:

| # | Decision |
|---|---|
| D1 | **A venue is a project of kind VENUE.** A show references one, by venue uuid and version, or none. Every existing table keeps its project FK. Extracting a venue moves rows between projects, uuids and all. |
| D2 | **Pinned, not live.** A venue change reaches a show only through Update. |
| D3 | **Explicit publish** from one draft, with a note. Versions are immutable. Admins only; operators see a venue read-only. |
| D4 | **Overrides are per facet:** Address, Position, Focus, Media, each whole. Identity (name, key, type, head) is the venue's. |
| D5 | **The show layer** is additions, facet overrides and hides, over one key namespace. |
| D6 | **Seating is per block.** A Fixed block (the balcony) is the venue's, with its seat views. A Preset block (the stalls layouts) is copied into a show at creation or by *Replace from preset*, never referenced. |
| D7 | **Output-affecting drift opens held**; drawing-only drift is a banner. *Run against vN* lifts the hold for the session; the installed version's trigger and command channels stay guarded. |
| D8 | **A venue syncs as its own repo.** A version is a tagged commit. A show's `project.json` carries the venue's uuid and version; importing one whose venue is absent asks for the venue repo. |
| D9 | **No touring tool.** Detach flattens venue and show into a standalone project. |
| D10 | **The hall owns everything patched in project 9 today**, movers, LED kit and cannon included. A show adds what it brings in. |
| D11 | **The Programmer works in a venue** for testing and focus; Record is off. |
| D12 | **Venues have groups.** A show adds its own, which may hold venue fixtures; a venue group never holds a show's. |

The plan adds its own:

- **P1 — Seven sessions, one PR each,** in §5's order, per CLAUDE.md §"Git workflow". Each ends with
  `./gradlew test` and `npm run check` green, its engineering-doc paragraphs written, and its
  done-marker here: a one-line row with the merge SHA.
- **P2 — A version is a frozen project.** Publishing exports the venue's draft with
  `ProjectExporter` and imports it with `ProjectImporter` into a new project of kind `VENUE_VERSION`:
  a fresh project uuid, every record uuid kept. Snapshots never appear in project lists and refuse
  every write at `withProject`. The draft is the venue project itself.
- **P3 — Links are re-pointed, not converted.** The boards' first session turned the foreign
  keys into uuid columns. That is a non-additive change to eight tables, and there are no migrations.
  Instead the columns stay int FKs. A show row points at a row of its pinned snapshot, and Update,
  Extract and Detach re-point every link by uuid through one registry, `VenueLinks`. A guard test
  reads every table's foreign keys from Exposed and fails when one points into a venue-ownable table
  without a `VenueLinks` entry, as `SyncCoverageTest` does for sync.
- **P4 — One rig scope.** `RigScope(project)` is the only answer to "which rows make up this rig":
  the show's own rows, the pinned snapshot's rows less the show's hides and less Preset seating,
  with facet overrides applied. The loader and every query site in §1 read through it, and machine
  overrides for a venue's records resolve against the **venue** project's id, so a universe's
  address is the same in every version.
- **P5 — The hold is its own gate, above park.** A held show composes as normal, so the Stage view,
  channel views and Preview all draw, but every controller sends zero on every channel, park
  included: a park is the show's, and a v3 park on a v5 rig is as wrong as a v3 cue. Arming,
  firing and fixture commands are refused with `VENUE_HOLD`. The app bar's Blind chip carries a
  `venue` reason. Programmer Blind is unchanged.
- **P6 — Sync before real data.** Until session 4 lands, sync is refused for venue projects and
  venue-bound shows (409 `SYNC_VENUE_PENDING`), so an auto-sync can never push a show repo without
  its rig. Project 9 is extracted on the desk only after session 4, and on a clone first.
- **P7 — Only published versions sync.** A draft is the desk's own. Publishing pulls first and
  refuses when the remote already has that tag (`VENUE_VERSION_TAKEN`).
- **P8 — An address overlap is a decision, a key clash a blocker.** The Patch list allows overlaps
  today (a red ring and a legend). So an overlap that an update introduces is listed under *Needs a
  decision* with a fourth answer, *Keep both*. A key clash cannot be kept.

## 3. The model

### 3.1 Vocabulary

- **Venue**: a project of kind `VENUE`; its rows are the **draft**.
- **Version**: an immutable snapshot of the draft, number `n ≥ 1`, a project of kind
  `VENUE_VERSION`. The **installed** version is the latest published one.
- **Show**: a project of kind `SHOW` (every project today). A show **on a venue** pins
  `(venueUuid, venueVersion)`; a **standalone** show pins nothing and behaves exactly as today.
- **Behind**: a show whose pin is older than the installed version. **Output-behind** when any
  change between the two is output-affecting: a universe added, removed or re-addressed, or a patch
  added, removed, re-addressed or re-typed (type key or mode). Everything else is **drawing-only**.
- **Show layer**: additions (rows the show owns), overrides (`show_overrides`) and hides
  (`show_hides`).

### 3.2 Stored (portable)

| Where | What |
|---|---|
| `projects.kind` | `SHOW \| VENUE \| VENUE_VERSION`, default `SHOW`. |
| `projects.venue_uuid`, `.venue_version` | A show's pin; both null for a standalone show. |
| `venue_versions` | `venue_project_id`, `number`, `note`, `published_at` (`utcInstant`), `published_by`, `snapshot_project_id`. Unique on `(venue, number)`. |
| `show_overrides` | `project_id`, `record_uuid` (a venue patch), `facet` (`ADDRESS \| POSITION \| FOCUS \| MEDIA`), `value` (canonical JSON of the facet's fields), `base` (the venue's value when overridden, for drift). Unique on `(project, record, facet)`. |
| `show_hides` | `project_id`, `record_kind` (`PATCH \| RIGGING \| ELEMENT \| REGION \| GROUP \| VIEWPOINT`), `record_uuid`. |
| `SeatingParams.block` | `FIXED \| PRESET`, absent = `PRESET`. Meaningful in a venue only; a show's own seating ignores it. |

The facets' fields, primary placement and every extra placement alike:

| Facet | Fields |
|---|---|
| Address | `universeConfig`, `startChannel` |
| Position | `rigging`, `stageX/Y/Z`, `baseYaw/Pitch/RollDeg`, `lengthM`, the placements' positions |
| Focus | `lanternType`, `beamAngleDeg`, `zoomDeg`, `lampRotationDeg`, `shutters`, `gateRotationDeg`, `iris`, `focusSoftness`, and the placements' |
| Media | `gelCode`, `media`, and the placements' |

Only patches take facet overrides. A venue rigging, element, region, group or viewpoint can be
hidden by a show but not changed. Stage bounds are read from the venue project's own columns.
`SyncCoverageTest` gains three rows: `show_overrides` and `show_hides` portable with the show;
`venue_versions` a machine-local index rebuilt from the venue repo's tags (§3.4).

### 3.3 The rig scope (P4)

For a show on venue V at version n, with snapshot project S:

```
rows(kind) = show's own rows(kind)
           ∪ S's rows(kind) − show_hides(kind) − (kind = ELEMENT ? S's PRESET seating : ∅)
patch      = S's patch with each show_overrides facet's fields replaced
```

Keys are unique across the effective rig. A show addition may reuse the key of a venue patch the
show hides; anything else is refused with `KEY_TAKEN_BY_VENUE`. Groups follow D12: a show group's
members may be venue patches; a venue group's never a show's. A venue or a standalone show's scope
is its own rows, so session 1 changes no behaviour.

### 3.4 Sync (D8, P7)

- **The venue repo** holds the latest published version in the usual layout, plus
  `venueVersion.json` (`{number, note, publishedAt, publishedBy}`). Publishing writes it, commits
  `vN: note`, tags `vN` and pushes. Pulling materialises every tag the desk lacks as a snapshot.
  The draft never syncs.
- **A show repo** exports only the show's own rows, `showOverrides/{uuid}.json`,
  `showHides/{uuid}.json`, and `project.json`'s `venueUuid`, `venueVersion` and optional `venueRepo`
  (owner/repo, a hint). Links into the venue are uuids, as every link already is. The importer
  seeds its uuid maps from the pinned snapshot before importing, so `patchMap` and `elementMap`
  resolve venue rows. A missing snapshot is 409 `VENUE_MISSING`, naming the venue and the hint.
- **`formatVersion` 24**, refused by a v23 desk as v23 was by v22. `minReader` stays 5.
- `ProjectCloner` keeps the pin and remaps only the show's rows, so a clone is a show on the same
  venue.

### 3.5 The wire

| Route | Does |
|---|---|
| `POST /api/rest/projects/{id}/venue/extract` `{name, include, dryRun?}` | Admin. Moves the chosen rows (default: universes, patches, riggings, regions, VENUE-layer elements, groups, camera viewpoints, and seating marked Fixed or Preset) into a new venue, publishes v1, and pins the project to it. `dryRun` answers the move without making it. |
| `GET /api/rest/venues/{vid}/versions` · `GET …/versions/{a}/diff/{b}` | History, and a diff classified output / drawing. |
| `POST /api/rest/venues/{vid}/publish` `{note}` · `DELETE …/draft` | Admin. Publish (P7) or discard the draft. |
| `GET /api/rest/projects/{id}/venue/update?to=n` · `POST …` `{to, decisions}` | The review (carry over, drift, dropped, decisions, dead references), then the update in one transaction, re-pointing through `VenueLinks`. Refused with `VENUE_UPDATE_UNDECIDED` while a decision is open. |
| `POST /api/rest/projects/{id}/venue/run-against` `{on}` | Lift or restore the hold for this session. Current project only. |
| `PUT /api/rest/projects/{id}/patches/{pid}/facets/{facet}` `{value \| null}` · `PUT …/hidden` `{hidden}` | Override or revert a facet; hide or show a venue record. |
| `POST /api/rest/projects/{id}/seating/from-preset` `{elementUuid}` · `POST …/detach` | Replace the show's seating with a copy of a preset; flatten into a standalone project. |

`ProjectDto` gains `kind` and `venue: {uuid, name, version, installed, behind: NONE | DRAWING |
OUTPUT}`. The WS gains `venue.hold`, `StateFlow`-backed (`{projectId, held, pinned, installed,
runAgainst}`), outbound only. Publish stays REST, so `FU-AUTH-WS-PER-MESSAGE` does not fire. MCP's
setup tools refuse a write to a venue record from a show with `VENUE_OWNED`, naming the facet route;
`describe_rig` says whose each record is.

## 4. UX

The boards are the record: Projects (`Projects.dc.html`), venue mode and Publish (`Venue.dc.html`),
a show's Patch list and fixture sheet (`Show.dc.html`), the hold and the update review
(`Update.dc.html`). Two details the plan settles: the venue's rail hides Scripts, FX Library, Looks,
Templates, Speed Masters, Show, Prompt Book and Busk, and keeps the Programmer with Record disabled
(D11); and the source column's filter is a URL parameter, like the universe toggle.

## 5. Implementation — seven sessions

Each session is one PR. It ends with `./gradlew test` and `npm run check` green, the CLAUDE.md or
engineering-doc paragraphs written, and its done-marker here: a one-line row with the merge SHA.

### Session 1 — the rig scope and the link registry (backend)

- **`RigScope`** (P4), with every site in §1 reading through it, and the two unscoped uuid lookups
  scoped. A project's scope is still its own rows, so **nothing changes behaviour**.
- **`VenueLinks`** (P3): the twelve links, each with its re-point query, and the Exposed-metadata guard
  test.
- **Tests:** the guard test; a project's scope equals today's queries across `RichProjectFixture`.
- **Docs:** `fixtures-engineering.md` §"The rig scope".

### Session 2 — venues and versions (backend + venue mode)

- **Stored:** `projects.kind`, `venue_versions`, `SeatingParams.block`. The snapshot (P2), the
  `withProject` write guard, and snapshots left out of every list.
- **Routes:** history, diff, publish, discard, all admin; `SYNC_VENUE_PENDING` (P6).
- **Frontend:** venue mode (the editing band, the VENUE tag, the draft count, the rail, Record
  disabled), draft markers on the Patch list, the Publish sheet and History. A venue is made by
  *Add venue* here; Extract comes in session 3.
- **Tests:** a snapshot equals its draft record for record, by uuid; snapshots refuse writes; diff
  classification over each kind of change; operators refused.
- **Docs:** CLAUDE.md (a venue, and the routes), `desk-accounts.md` (admin-only), the diff rule in
  `fixtures-engineering.md`.

### Session 3 — shows on a venue (backend + Patch list)

- **The pin, hides and additions** through `RigScope`, with the key rule and D12's group rule. Fixed
  seating loads into a show and Preset seating doesn't. Stage bounds come from the venue.
- **Extract** (`dryRun` first), and a minimal *Venue* select on Create.
- **Frontend:** the Patch list's source column and filter, *Hide in this show*, the header's venue
  chip and *Open venue*, read-only identity on venue rows.
- **MCP:** `VENUE_OWNED` refusals and `describe_rig`'s ownership.
- **Tests:** the effective rig across hides, additions, keys and seating; Extract moves exactly its
  rows and the project then loads the same fixtures at the same addresses; the universe address
  override survives Extract.
- **Docs:** `fixtures-engineering.md` §"Venues", `mcp-engineering.md`.

### Session 4 — sync (backend)

- **`formatVersion` 24** (§3.4): the show export and import, the venue repo with tags, the importer
  seeding its maps from the snapshot, `VENUE_MISSING`, the clone keeping the pin. `JGitClient`
  gains tag create, push and list. `SYNC_VENUE_PENDING` is removed.
- **`scripts/cloud-session.sh`** imports venue repos before the shows that pin them.
- **Tests:** `ProjectRoundTripTest` for a show on a venue and for a venue's versions;
  `RichProjectFixture` gains a venue with two versions and a show with an override, a hide and an
  addition on a venue universe; a v23 archive still imports; a tag clash is refused.
- **Docs:** `sync-engineering.md` §"Version 24" and §"Venue repos".
- **Then:** Chris extracts project 9 into the Commemoration Hall, on a clone first (P6).

### Session 5 — facet overrides and drift (backend + fixture sheet)

- **`show_overrides`**: the facet route, `base` recorded at override time, and drift when the pinned
  venue's value differs from `base`.
- **Frontend:** the fixture sheet's Venue | This show switch per facet, *Revert to venue*, the
  drift note with *Keep mine* and *Take venue's*, the Patch list's facet chips.
- **Tests:** each facet's fields round-trip and nothing outside them moves; placements follow
  their facet; drift appears and clears.
- **Docs:** `fixtures-engineering.md` §"Venues" (facets).

### Session 6 — a show that's behind (backend + frontend)

- **Classification** of the gap between pin and installed version; `ProjectDto.venue.behind`.
- **The hold** (P5): a held flag on `DmxController`, checked above park, set by the loader for an
  output-behind show and cleared by *Run against* or Update; `VENUE_HOLD` on arm, fire and command;
  the installed version's trigger and command channels reserved while running against an older
  pin; `venue.hold`.
- **Update:** the review and the transaction, re-pointing through `VenueLinks`, dropping links to
  removed records and listing every key that will point at nothing (P8 for decisions).
- **Frontend:** both banners, the Blind chip's venue reason, the review dialog, the *Run against*
  confirm listing the moved addresses.
- **Tests:** the hold sends zeros with a park set; a held show's Stage view still composes; Update
  re-points every link kind and leaves no dangling FK; the reserved trigger channel stays idle
  while running against an older pin.
- **Docs:** `dmx-engineering.md` (the hold), `websocket-engineering.md` (`venue.hold`),
  `lighting-composition-model.md`.

### Session 7 — Projects page and the rest (frontend + backend)

- **Projects page:** the Venues row, show cards' venue badges, *Extract a venue…*, the show menu's
  *Update venue* and *Detach*.
- **Create:** the venue picker and the stalls preset (D6); *Replace from preset*.
- **Detach** (D9): the effective rig written into the show's own rows, overrides applied, links
  re-pointed, pin cleared.
- **Tests:** a detached show loads the same fixtures it loaded before; a preset copy is the show's.
- **Docs:** CLAUDE.md's project section; `followups.md` gets `FU-STAGE-VENUE-SHARED`'s Completed row.

## 6. Migration

- **No data migration.** Every schema change is additive: two nullable columns and one defaulted
  column on `projects`, three new tables, and a params key. Extract moves rows by operator action,
  through a route, not at startup. If a second install exists by then, this still holds.
- **`formatVersion` 24** in session 4 (§3.4).
- **Project 9** is extracted by Chris after session 4, on a clone first (P6).

## 7. Explicitly out of scope

- Moving a show to another venue by key mapping (D9; `FU-VENUE-TOURING`).
- Live inheritance (D2), a version per save (D3), per-field overrides (D4).
- Changing a venue rigging or element from a show, beyond hiding it (`FU-VENUE-ELEMENT-OVERRIDES`).
- Syncing a draft, or two desks drafting one venue (P7; `FU-VENUE-DRAFT-SYNC`).
- Pruning old snapshots (`FU-VENUE-VERSION-PRUNE`).

## 8. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-VENUE-TOURING`: a show moving venue by mapping its keys, gated on a show playing a second
  venue (session 7).
- `FU-VENUE-ELEMENT-OVERRIDES`: a show changing a venue rigging or element rather than hiding it,
  gated on a show that needs to (session 5).
- `FU-VENUE-DRAFT-SYNC`: a draft shared between desks, gated on two admins editing one venue
  (session 4).
- `FU-VENUE-VERSION-PRUNE`: snapshots no show pins, gated on about fifty versions of one venue
  (session 2).

## 9. Verification

Beyond the unit suites, at the desk:

- **Session 1.** Project 9 loads, patches, focuses and syncs exactly as before.
- **Session 2.** *Add venue* makes an empty venue; patching into it and publishing v1 and v2 shows
  both in History, and the diff names the change. An operator account sees no Publish.
- **Session 3.** On a clone of project 9: Extract, then the clone outputs the same channels as
  project 9 on the same nodes. Hide a dimmer, add a fixture on a venue universe, and its key is
  refused if it clashes.
- **Session 4.** The venue repo shows tags `v1`, `v2`. A cloud session imports the venue, then the
  show. Then project 9 is extracted for real.
- **Session 5.** The panto moves a mover onto a boom; the venue's draft and every other show are
  untouched. Re-gel it in the venue, publish, update the panto: drift shows.
- **Session 6.** Publish a re-patch, then activate an older show: nothing on the rig changes, the
  Stage view still draws, the chip says Blind · venue. *Run against* drives the old patch with the
  cannon's channels idle; Update clears the hold.
- **Session 7.** Create the panto from the Projects page with the stalls preset; detach a copy and
  check it outputs as before.

## 10. Questions

All answered by Chris, 2026-10-10:

1. **Live or pinned?** Pinned, with an update offered (D2).
2. **How is a version made?** Explicit publish with a note (D3).
3. **A show behind on output?** Opens held (D7).
4. **Seating presets?** Copied from the venue (D6). Then: some venues have fixed seating, so
   **per block** (D6).
5. **Touring?** Not for now; Detach (D9).
6. **Where does the venue sync?** Its own repo (D8).
7. **Who edits a venue?** Admins (D3).
8. **What does the hall own?** Everything patched today (D10).
9. **The Programmer in a venue?** Yes, Record off (D11).
10. **Venue groups?** Yes, with show groups beside them (D12).
11. **Do the four facets fit?** Yes (D4).
