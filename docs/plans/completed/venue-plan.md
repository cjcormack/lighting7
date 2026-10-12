# Venues and shows — a versioned venue under each show

> **Document status: COMPLETE, 2026-10-12 — all seven sessions shipped (`a5f289b`, `d93e174`, `59b2263`, `39459ee`, `45afbef`, `c7fe1e7`, `174e5a1`).** Scope agreed with Chris, 2026-10-10, over three
> rounds of questions (the answers are in §10 and in the design record). The boards and the
> survey are in [`venue-design/INDEX.md`](venue-design/INDEX.md). A one-page copy for human readers
> is [`venue-design/venue.html`](venue-design/venue.html), with a live copy at
> <https://claude.ai/artifact/LxMaLSktSQa73AkoXQzUvh> (the checked-in file is the authority).
>
> The plan and its design record moved to `completed/` together in the close-out (`a2eec6c`).
> §8's follow-ups are in [`../followups.md`](../followups.md), with `FU-VENUE-UPDATE-BINDINGS` from
> session 6 beside them and `FU-STAGE-VENUE-SHARED` in Completed. §9's desk checks are in
> [`../manual-validation.md`](../manual-validation.md), one per session: `FU-MANUAL-VENUE-S1` and
> `-S3` to `-S7` outstanding, and `-S2` validated in PR #105's review.
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
  done-marker here — added inside the session's own PR, as its last plan commit once the code is
  committed and the PR open, citing the session's change commit(s), never the merge commit (merges
  are merge commits, so a branch's SHAs survive into `main`). A later fix to the session's code
  updates the marker to cite it too; pushed history is never rewritten to keep the marker last.
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
engineering-doc paragraphs written, and its done-marker here, inside its own PR: its heading ends
"— done," with the session's change commit(s), never the merge commit (P1).

### Session 1 — the rig scope and the link registry (backend) — done, `a5f289b`

- **`RigScope`** (P4), with every site in §1 reading through it, and the two unscoped uuid lookups
  scoped. A project's scope is still its own rows, so **nothing changes behaviour**.
- **`VenueLinks`** (P3): the twelve links, each with its re-point query, and the Exposed-metadata guard
  test.
- **Tests:** the guard test; a project's scope equals today's queries across `RichProjectFixture`.
- **Docs:** `fixtures-engineering.md` §"The rig scope".

### Session 2 — venues and versions (backend + venue mode) — done, `d93e174`

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
- **As shipped** (PR #105). What session 3 onward inherits that the bullets above do not say:
  - **The write guard is not in `withProject`.** It sits at the head of the whole `/projects/{id}/…`
    subtree (`routes/projectWriteGuard.kt`), so routes that resolve their project another way are
    covered too; the copy routes judge their body's target (`refusedCopyTarget`) and the FX
    definitions, written to the live project, judge it (`refusedLiveVenueWrite`). An operator's
    stored write to a venue is 403 `VENUE_ADMIN_ONLY`; opening one and runtime gestures pass.
  - **The snapshot** is `sync/KeptUuidCopy.kt` (the test helper now calls it). It carries its own
    scene images and prompt PDFs under its own uuid, and **no** machine overrides: they stay keyed
    by the venue's id, which is what P4 resolves a venue record's override against in session 3.
  - **The diff counts every exported table** but the default speed masters — the rig classified and
    named, anything else `OTHER`, drawing only — so nothing a publish freezes goes uncounted. It
    exports the draft per read: ~100 ms at 300 patches, measured; cache by a rig revision if venues
    grow ten times larger.
  - **Operators' read-only view** covers the Patch list, the Stage view's Edit, Rigging, Regions and
    Project Settings; Record, Update and *New template* are off in a venue for everyone. A hidden
    view's URL still renders — only the rail hides it.
  - **The Patch list's Draft column leads** the value columns (the board draws it last); History and
    Publish name no shows until session 3's pin exists.
  - **Fixed on the way:** `ProjectManager.switchProject` left no project current when it reloaded
    the live one (discard does; so did sync's pull).

### Session 3 — shows on a venue (backend + Patch list) — done, `59b2263`

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
- **As shipped** (PR #106). What session 4 onward inherits that the bullets above do not say:
  - **`show_hides` and the pin are stored but not exported.** `SyncCoverageTest` lists
    `show_hides` as `Excluded` naming session 4, which wires both through `ProjectExporter` /
    `ProjectImporter` at `formatVersion` 24. Until then a pinned show refuses the whole sync
    subtree and export (`SYNC_VENUE_PENDING`, auto-sync skips it) and clone (409
    `VENUE_CLONE_PENDING`), so nothing carries it off the desk without its rig. A venue a show pins
    refuses delete (409 `VENUE_IN_USE`).
  - **The rule lives in `RigScope` and `venue/VenueRigGuard.kt`.** `VenueLayer` resolves pin → venue
    by uuid → `venue_versions` → snapshot; a pin whose snapshot is not on the desk falls back to the
    show's own rows silently (session 4's `VENUE_MISSING`). `KEY_TAKEN_BY_VENUE` covers names and
    head numbers as well as keys, wherever the rig checks a clash; unhiding is `KEY_TAKEN_BY_SHOW`
    while the show's own holds the key, head number or any of the patch's channels.
  - **Writes to venue rows are `VENUE_OWNED`, with four deliberate exceptions**, each keyed by the
    venue's id: a universe's address and interval (P4's machine override, set through a show), an
    element's *Full detail*, and group membership into the show's own group (`addToGroup`,
    `removeFromGroupId`). Hiding is the only other change; facets are session 5.
  - **Hides take all six kinds on the backend** — `PUT …/patches/{pid}/hidden` plus a generic
    `PUT …/hides/{KIND}/{uuid}` — and refuse a record the show still uses (409
    `VENUE_HIDE_IN_USE`: a `VenueLinks` link from the show's own rows, a visible fixture on a
    rigging, a seat view in a seating). A show group's membership of a hidden patch is filtered, not
    refused. A platform's `regionUuid` is not checked: a dangling one reads as none.
  - **Extract** copies Preset seating into the venue (uuid kept) rather than leaving it behind,
    re-keys the moved rows' machine overrides to the venue's id, copies their scene images into the
    venue's store (the show's copies are left for the 7-day prune) and refuses to finish with a link
    left dangling. One database transaction; the file copies are the step outside it, removed on
    rollback. `dryRun` is the run rolled back. No UI until session 7: the §9 check reaches it through
    `/openapi` or curl on a clone of project 9.
  - **Frontend went a little past the Patch list:** Publish and History name the shows on a venue
    (`VenueShowsList`), the Stage card and the Sync tab say a pinned show's stage and sync are the
    venue's, and the venue fixture sheet (`VenuePatchPanel`) is also the Stage editor's for a venue
    patch. Rigging, Regions and the Scene editors are **not** read-only for venue rows — their DTOs
    carry no `owner` yet — so a write there is refused by name and toasted.

### Session 4 — sync (backend) — done, `39459ee`

- **`formatVersion` 24** (§3.4): the show export and import, the venue repo with tags, the importer
  seeding its maps from the snapshot, `VENUE_MISSING`, the clone keeping the pin. `JGitClient`
  gains tag create, push and list. `SYNC_VENUE_PENDING` is removed.
- **`scripts/cloud-session.sh`** imports venue repos before the shows that pin them.
- **Tests:** `ProjectRoundTripTest` for a show on a venue and for a venue's versions;
  `RichProjectFixture` gains a venue with two versions and a show with an override, a hide and an
  addition on a venue universe; a v23 archive still imports; a tag clash is refused.
- **Docs:** `sync-engineering.md` §"Version 24" and §"Venue repos".
- **Then:** Chris extracts project 9 into the Commemoration Hall, on a clone first (P6).
- **As shipped** (PR #107). What session 5 onward inherits that the bullets above do not say:
  - **`showOverrides/` is reserved at v24**: nothing writes it and a v24 reader refuses an archive
    holding a file in it (422 on import, `FORMAT_TOO_NEW` before a pull), so session 5 adds overrides
    without another `formatVersion` bump; its reader must drop the folder from
    `ProjectImporter.RESERVED_FOLDERS`.
  - **The fixture is `VenueTestSupport.seedShowOnVenue`, not `RichProjectFixture`** — a venue of two
    versions (extract → v1, a moved truss → v2) and a show with a hide and an addition on a venue
    universe, all non-default values. There is **no override** in it: those are session 5's.
    `SyncCoverageTest` exports it beside the rich fixture so `showHides` produces output.
  - **The pin travels in `project.json`** (`kind`, `venueUuid`, `venueVersion`, `venueRepo`), and the
    hint is stored in a new nullable `projects.venue_repo`; a stored hint wins on export, else the
    venue's attached repo. A diverged auto-merge three-way-merges the pin (`mergeProjectPin`),
    because `project.json` is not a record and the remote's would otherwise win.
  - **A missing pinned version** is `VENUE_MISSING` on import, add-remote-project, clone, a pull
    (before the tree moves) and set-current; a show current at startup with one loads **no rig**
    (`VenuePins.isMissing`), never a different one.
  - **A venue repo is attached through the show's Sync tab and `sync_configs` row.** Sync now on a
    venue is `VenueRepoSync.exchange`: tags in as snapshots (`venue_versions` only gains rows; the
    same number published differently is `VENUE_VERSION_TAKEN`; a `vN` tag that is not a published
    version is left alone), versions out, and a **clean** draft follows the newest pulled version
    (re-checked inside the replace's transaction). A dirty draft is kept — the draft never syncs
    (`VENUE_DRAFT_NOT_SYNCED`, `FU-VENUE-DRAFT-SYNC`).
  - **Publish with a repo** pulls first and refuses `VENUE_VERSION_TAKEN`; a `sync.push = false` desk
    pulls but refuses Publish (`VENUE_PUBLISH_PULL_ONLY`). Lock order is the venue lock, then the
    project's sync lock.
  - **Clone needed no venue code**: the archive carries the kind and pin, and hides keep their
    `recordUuid` through the uuid remap.
  - **Departure:** delete and replace now sweep cue events and scenery through their owners, closing
    an orphan for cues that fire venue patches — found while wiring the replace.

### Session 5 — facet overrides and drift (backend + fixture sheet) — done, `45afbef`

- **`show_overrides`**: the facet route, `base` recorded at override time, and drift when the pinned
  venue's value differs from `base`.
- **Frontend:** the fixture sheet's Venue | This show switch per facet, *Revert to venue*, the
  drift note with *Keep mine* and *Take venue's*, the Patch list's facet chips.
- **Tests:** each facet's fields round-trip and nothing outside them moves; placements follow
  their facet; drift appears and clears.
- **Docs:** `fixtures-engineering.md` §"Venues" (facets).
- **As shipped** (PR #108). What session 6 onward inherits that the bullets above do not say:
  - **The drift read is `ShowOverrides.drift(project, snapshotId?)`** (`venue/ShowOverrides.kt`): every
    override against the pinned version, or against any version's snapshot — what Update's review
    calls with the target version's — each `{recordUuid, patchKey, facet, value, base, current,
    missing, dormant}` with `drifted` (current ≠ base). `missing` is an override whose patch that
    version lacks; `dormant` is judged against the pinned version only. An override is keyed by the
    patch's uuid, so it survives a re-pin as hides do, and a re-pinned show reads the patch under the
    new version's id. **Update moves pins; nothing in session 5 rewrites a base** but *Keep mine*.
  - **The overlay is `RigScope.effective` / `effectivePlacements`**, an `EffectivePatch` view over the
    snapshot's row, not a write; every facet reader goes through it (loader, patch DTO, overlap checks,
    universe counts, aim, focus, hides, `describe_rig`, `get_patch`, `patch_fixtures`). Session 6's hold
    and *Run against* read the overridden address through the loader for free; Detach (session 7) must
    write `effective`'s fields into the show's own rows.
  - **Facet values are whole, placements by uuid** (`models/patchFacets.kt`): an unnamed placement keeps
    the venue's fields (how a placement a later version adds arrives); a named one the version lacks
    contributes nothing. An override naming a universe or rigging the rig lacks is **dormant** (the
    venue's whole facet stands, the DTO says `dormant`) — Update's dead-reference list should name
    those.
  - **`base` is kept when an override is edited** (the plan said "recorded at override time"; editing
    does not answer a drift). A hidden patch takes no override write but a revert
    (`FACET_PATCH_HIDDEN`), its overrides dormant until it is shown.
  - **Decisions the plan left open:** the route takes an Address universe by `universeConfigUuid` or by
    number (stored as the uuid; `UniverseConfigDto` gained `uuid`); *Keep mine* is its own route
    (`POST …/keep-mine`), *Take venue's* the `null` revert; operators may override (a stored show write,
    as a patch edit); refusals reuse `NOT_A_VENUE_RECORD` and add `FACET_INVALID`, `FACET_UNKNOWN`,
    `FACET_PATCH_HIDDEN`, `FACET_NOT_OVERRIDDEN`; the Stage editor's drag of a venue patch stays
    `VENUE_OWNED` (a Position override is made whole in the sheet); no MCP setup tool writes a facet —
    they refuse `VENUE_OWNED` naming the route, and `describe_rig` / `get_patch` name the overridden
    facets.
  - **Departures:** a show's own rigging delete (REST and `set_stage`) detaches an override hung on it
    and a show's own universe delete reverts an Address override onto it; a venue rigging an override
    hangs a unit on refuses its hide. The frontend's Position card edits the patch's own placement and
    carries the extra placements unchanged (Focus and Media edit theirs). Sync: `showOverrides/` at v24
    with no bump, so a session-4 desk refuses a show carrying overrides. `FU-VENUE-ELEMENT-OVERRIDES` is
    recorded.

### Session 6 — a show that's behind (backend + frontend) — done, `c7fe1e7`

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
- **As shipped** (PR #109). What session 7 inherits that the bullets above do not say:
  - **Behind is judged per show, not per venue** (`venue/ShowBehind.kt`): History's own diff between the
    pin and the installed version, less what the show's layer stands between — a change to a patch it
    hides, and a re-address of a patch whose Address it overrides where the override applies at both
    versions (the hold is for what an Update would change on the wire). A re-type, a universe change
    and an added patch always reach the show; a gap that cannot be read fails closed as `OUTPUT`.
    `ProjectDto.venue` carries `behind` and `drawingChanges` (the drawing-only banner's count).
  - **The hold is `dmx/OutputHold.kt`, one per show**, handed to every controller by the loader, which
    judges it first (`Show.rigHold` → `state/VenueHoldService.kt`); it zeroes the bytes handed to the
    transport and nothing else, so the listeners, `getValue` and every view keep the composed show.
    `MockDmxController.transmittedValue` is the wire for a test. Arm, fire and command are refused
    (`VENUE_HOLD`) while running against too, not only held: the installed version's cannon is reserved
    idle, so a fire there would be drawn and never happen. A rehearsal is not refused.
  - **Run against is runtime only, keyed to (project, pin, installed)**: a reload keeps it (patch
    edits, a pull that leaves the pin), another project, a moved pin, a newer version or a restart
    drops it. Lifting it from the public listener needs `requireEffectsAccess`; putting the hold back
    (`on: false`, the banner's *Hold output*) never does. **Departure, decided with Chris:** a publish
    or venue pull that puts a *running*, unheld show behind does not black it out — it carries on as if
    run against its pin, guarded, and opens held next time (`refreshCurrent`). D7's "opens held" is
    kept to opening.
  - **Update** (`venue/VenueUpdate.kt`) re-points link by link through `VenueLinks.ALL` (in
    `repointAll`'s order) so each link's unmatched rows drop as that link needs: a show patch or
    placement on a removed rigging comes off it, keeping its position; a member, tile, cue event or
    scenery row goes; a show patch on a removed universe is a blocker. Hides and overrides of records
    the target lacks are deleted. Drift keeps its base, so the session 5 chip lights after; `TAKE_VENUES`
    on a drift is a revert in the same request. Decisions (P8) are `KEEP_BOTH | READDRESS_MINE |
    HIDE_VENUES | REMOVE_MINE` — *Hide the venue's* only where `ShowHides.hideRefusal` allows it,
    re-addresses suggested and applied in turn so two never collide. Key and name clashes (the §3.3
    namespace) block. Forward only (`VENUE_UP_TO_DATE`); a live show reloads through `switchProject`.
    **Detach can reuse** `deleteOwnPatch`, the drop rules per link and the review-as-rollback pattern;
    it must write `RigScope.effective`'s fields (overrides applied) into the show's own rows before it
    re-points every link from the snapshot to them and clears the pin.
  - **Operators** may Update and Run against (`desk-accounts.md`); `projectWriteGuard` treats
    `venue/run-against` as a runtime gesture.
  - **Frontend:** `VenueBehindBanner` under the app header (held, running against, drawing-only with
    *Later* per session and installed version, and "opens held" on a non-live show),
    `ProgrammerIndicator` and the immersive `ShowHeader` say *Blind · venue*, `VenueUpdateSheet` (a
    Sheet, not the board's modal: its decisions are a form), `RunAgainstDialog`. The busk band's
    `BLIND` pill stays the programmer's. The Projects page's *Update venue* is session 7's.
  - **Sync:** no `formatVersion` change — a pulled pin move reloads the live show, which re-judges.
    Recorded `FU-VENUE-UPDATE-BINDINGS` (the review does not read surface bindings).
  - **Fixed on the way:** `Fixtures.changeListeners` is copy-on-write; a switch's collector
    registering while the next switch iterated it threw `ConcurrentModificationException`.

### Session 7 — Projects page and the rest (frontend + backend) — done, `174e5a1`

- **Projects page:** the Venues row, show cards' venue badges, *Extract a venue…*, the show menu's
  *Update venue* and *Detach*.
- **Create:** the venue picker and the stalls preset (D6); *Replace from preset*.
- **Detach** (D9): the effective rig written into the show's own rows, overrides applied, links
  re-pointed, pin cleared.
- **Tests:** a detached show loads the same fixtures it loaded before; a preset copy is the show's.
- **Docs:** CLAUDE.md's project section; `followups.md` gets `FU-STAGE-VENUE-SHARED`'s Completed row.
- **As shipped** (PR #110). Where it departed from the bullets above, and what is left:
  - **Detach keeps every uuid.** The copies are the venue's records under the show (Extract's move in
    reverse), so seat views, platform regions, scenery once re-pointed and a peer's importer maps keep
    resolving. Machine overrides are **copied** to the show's id, never moved (P4). A link to a record
    the show hides (only a show group's member can be one) is dropped and named. A copy whose key or
    name the show's own rows already hold is refused by name (`VENUE_DETACH_BLOCKED`), not as a
    constraint error. Its venue may then be deleted.
  - **Decisions the plan left open:**
    - Detach is **either role's**: it changes only the show, and an operator may already delete one.
    - **A behind show detaches at its pin** (what it runs). The answer and the confirm say how far
      behind it was.
    - *Replace from preset* replaces **every** seating block the show owns.
    - The show's own seat views move to the copy. One whose seat the copy lacks is
      `STAGE_ELEMENT_IN_USE`, and `force` leaves it without a seat (the element delete's rule).
    - The stalls' scenery follows a single block, and is swept when there were several.
    - A copy keeps the preset's uuid, as Extract's does.
    - A name clash is `SEATING_PRESET_NAME_TAKEN`.
    - `GET …/seating/presets` is the read both Create and Replace use, open to both roles.
  - **No new table and no `formatVersion` bump.** A detached show exports as a standalone show. A
    peer's pull clears its pin through `mergeProjectPin` and `replaceFromWorkingTree`, and a desk
    without the venue imports it (`VenueRepoSyncTest`).
  - **Frontend:**
    - Create defaults to the first published venue, per the board. *None — standalone* stays an
      option.
    - The list's venue rows carry `venueSummary` for their cards.
    - *Extract a venue…* is offered from the Venues row and from a standalone show's menu.
    - *Replace from preset* lives on Project Settings › Stage.
    - The Projects header now wraps at phone width (pre-existing: *Create Project* was out of reach
      there).
  - **Session 6's open items:**
    - The *Blind · venue* chip read dark red on the header's blue in light mode. It now has an opaque
      fill, the same colour elsewhere.
    - `describe_rig` and `get_patch` say when output is held.
    - `FU-VENUE-UPDATE-BINDINGS` stays Trigger-gated.
  - **Left for later:** `FU-VENUE-TOURING` is recorded (a show moving venue by key mapping).
    `FU-STAGE-VENUE-SHARED` is Completed. §9's session-7 check, the panto from the stalls preset and a
    detached copy outputting as before, is the desk's.

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

In [`followups.md`](../followups.md), when the session that creates them lands:

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
