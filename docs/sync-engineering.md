# Cloud Sync — Engineering

This is the engineering reference for the cloud-sync subsystem. The big-picture
design and rationale live in [`docs/plans/completed/cloud-sync.md`](plans/completed/cloud-sync.md);
this doc captures the contracts and operational details that current code
relies on so that future contributors don't reinvent them.

The subsystem ships in eight phases (see the design doc). **Today, Phases 1
through 8 are in-tree** — UUID columns, canonical JSON, manual export/import,
the install-identity / `machine_override` tables, a per-project local git
working tree, remote push/pull with PAT credentials, the multi-master
three-way diff with a flat conflict-resolution flow, Phase 6's polished
conflict UX (MANUAL resolution + crash recovery for sessions caught mid-apply),
Phase 7's correctness-corner work (tombstones for deletion propagation,
`EDIT_DELETE` / `DELETE_EDIT` conflict kinds, and an in-engine push-rejected
retry), and Phase 8's quality-of-life additions: a periodic auto-sync
scheduler, a persisted activity log, parsed commit attribution, and history
pagination.

## Architecture overview

The eventual delivery mechanism is a per-project GitHub repo — each install
keeps a working tree under `appDataDir()/sync/{projectUuid}/repo/`, pushes and
pulls via JGit, and resolves conflicts in the UI. Phase 1 defined the file
format. Phase 3 puts a real on-disk JGit-backed working tree behind it so
every change can be captured as a local commit; phase 4 adds the remote.

The repo split is:

* **Portable** (synced, lives in JSON): project content — cues, presets, fixture
  patches, scripts, etc.
* **Machine-local** (never synced): controller IPs and similar per-rig
  configuration. From Phase 2, the canonical storage is the
  `machine_overrides` table accessed via the `Overrides` helper. The
  legacy `DaoUniverseConfigs.address` column is retained in the schema
  but is unused. The one-shot startup migration that moved prior values
  into `machine_overrides` was removed on 2026-08-24 along with the rest
  of the migrations — see
  [`InstallBootstrap.kt`](../src/main/kotlin/uk/me/cormack/lighting7/state/InstallBootstrap.kt).
* **Machine-local runtime state** (never synced): grand master level and blackout, per project in
  `project_scaler_states`, written through so a restart keeps them.
  (AI conversation history was here until the in-app chat was removed; its
  `ai_conversations` table is no longer declared, and an existing database
  keeps the table as an orphan nothing reads.)

Parked DMX channels are portable: operators use park to pin "house lights at
50%" or to protect a hot-powered fixture plugged into a dimmer, both of which
need to follow the project. They sit alongside the rest of the project graph in
`parkedChannels/{uuid}.json` and three-way diff with the same record-level
semantics as cues or fixture patches.

## Repo layout (Phase 1)

Phase 1 ships a flatter layout than the eventual git-repo layout. Everything
lives directly under the export folder:

```
formatVersion.json
project.json                   # v24+: `kind` (absent = SHOW), and a show's pin — `venueUuid`,
                               # `venueVersion`, `venueRepo` (a hint); see §"Version 24"; a
                               # standalone show's origin — `detachedFromVenueUuid`,
                               # `detachedFromVersion` — where it was detached from (no version)
installs.json                  # populated with the local install's identity (Phase 2)
showHides/{uuid}.json          # v24+: a show on a venue's hides of venue records (recordKind,
                               # recordUuid)
showOverrides/{uuid}.json      # v24+ (venue plan session 5): a show's facet overrides of venue
                               # patches (recordUuid, facet, value, base)
showParkReleases/{uuid}.json   # v27+: a show on a venue's releases of venue parks (universe,
                               # channel)
showColours/{uuid}.json        # v36+: a show on a venue's recolours of venue patches and groups
                               # (recordUuid, colour)
venueVersion.json              # v24+, a venue repo only: which version the tagged commit is
showEntries/{uuid}.json
cueStacks/{uuid}.json          # v20+: its set embedded as `scenery` (elementUuid, state)
cues/{uuid}.json               # what a cue is; v20+: `scenery` embedded, each change an
                               # elementUuid, a state object and an optional transitionMs. Before
                               # v32 it also carried its place: cueStackUuid, sortOrder, cueNumber,
                               # cueNumberAuto, autoAdvance(DelayMs), cueType
cueStackEntries/{uuid}.json    # v32+: one place in a stack's list — cueStackUuid, cueUuid (a cue
                               # entry) or kind MARKER + label, a double ordinal, the number only
                               # when explicit, the follow; see §"Version 32"
cueAttributeAssignments/{uuid}.json  # carries cueUuid; `cuePropertyAssignments/` before v38
cueLayers/{uuid}.json          # carries cueUuid + lookUuid or paletteUuid + optional speedMasterUuid
cueAdHocFx/{uuid}.json         # carries cueUuid + optional speedMasterUuid; `cueAdHocEffects/` before v38
cueTriggers/{uuid}.json        # carries cueUuid + scriptUuid
fixturePatches/{uuid}.json     # carries universeConfigUuid + optional riggingUuid; a paired
                               # dimmer's other lanterns embedded as `extraPlacements` (v14+);
                               # a lightstrip's `lengthM`, and each placement's (v15+);
                               # the operator's `headNumber` (v16+); the lantern and its
                               # focus — `lanternType`, `zoomDeg`, `lampRotationDeg`, `shutters`
                               # (four `{depth, angleDeg}`), `gateRotationDeg`, `iris`,
                               # `focusSoftness` — on the patch and on each placement (v19+);
                               # the unit's fitted `media` — `{slots: {<setting>: {<option>:
                               # {gel?, gobo?}}}}` — on the patch and on each placement (v22+)
universeConfigs/{uuid}.json    # `address` deliberately omitted (machine-local)
riggings/{uuid}.json           # truss/bar/boom pose; fixtures hang off these (v3+)
stageRegions/{uuid}.json       # rectangular platforms describing the deck (v3+)
stageElements/{uuid}.json      # v18+: the scene document — venue and set elements, each kind's
                               # `params` a nested object (a platform's `regionUuid` inside it)
stageViewpoints/{uuid}.json    # v18+: saved views and seats; a seat view names its seating by
                               # `seatElementUuid`
fixtureGroups/{uuid}.json      # members embedded inline
looks/{uuid}.json              # rows and FX (`fx`, `effects` before v38) embedded inline; v20+: `scenery` too
palettes/{uuid}.json           # rows or one FX (`fx`) embedded inline; one attribute family each
                               # (v6+). `templates/` before v38
speedMasters/{uuid}.json       # named tempo buses; bpm is the starting default. Also carries
                               # optional `family` (`usage` before v38) + `followNum`/`followDen` + `followTargetUuid`
                               # (busk-view routing and follow — additive nullable fields, no
                               # formatVersion bump; the exporter writes the ratio through the
                               # sanitising `followRatio` getter, the importer writes it verbatim
                               # and the bank degrades a malformed pair, a dangling target or a
                               # cycle to manual). `followTargetUuid` is a uuid reference like
                               # any other, so ExportUuidRemapper re-points it on clone; null
                               # with a ratio set means master 1, which is what every export
                               # written before follow targets says
fxDefinitions/{uuid}.json
cueSlots/{uuid}.json           # exactly one of cueUuid / lookUuid (lookUuid v10+)
buskPages/{uuid}.json          # v10+: the busk layout — columns, banks and pads embedded inline;
                               # a pad names a palette (`paletteUuid`), Look or cue by uuid
buskRig/{uuid}.json            # v12+: the busk rig — one document per row, tiles embedded inline;
                               # a tile names a group or a patch by uuid and an element by its element key.
                               # v13+: a row also carries `flow` (a BuskFlow name, default SCROLL)
                               # and `width` (a share in twelfths, default 12) — the bank's two
                               # layout facts; both default so a v12 archive decodes
parkedChannels/{uuid}.json     # (universe, channel, value) — the channel's parked output
controlSurfaceBindings/{uuid}.json
scripts/{uuid}.kts             # raw Kotlin script body for git-friendly diffs
scripts/{uuid}.meta.json       # metadata sidecar (name, scriptType)
promptBooks/{uuid}.json        # script reference by content hash (scriptHash)
promptBookAnchors/{uuid}.json  # carries entryUuid (v32+; cueUuid before); normalized region rects
promptBookAnnotations/{uuid}.json  # carries promptBookUuid; note/strikethrough/freetext
promptScripts/{sha256}.pdf     # the script PDF itself — binary blob, content-addressed (v4+)
sceneImages/{sha256}.{png|jpg} # an image a cloth is painted with (v23+) or a model's part textured with (v25+) — binary blob, content-addressed
```

### Prompt books

Prompt-book records sync as JSON like everything else. **As of format v4 the
script PDF travels too**, as a binary blob at `promptScripts/{sha256}.pdf`
(see [Version 4](#version-4--prompt-book-pdf-binaries)). `promptBooks/{uuid}.json`
carries the PDF's SHA-256 (`scriptHash`), which is the script's identity (never
the filename); the blob's filename *is* that hash. Anchor and annotation regions
are validated on import against the book's `pageCount` (same invariant as the
REST routes — see `checkPromptBookRegion`).

The bytes live in a per-install content-addressed store
(`<appDataDir>/prompt-scripts/{projectUuid}/{hash}.pdf`, `State.promptScriptPath`)
and are copied to/from the repo as **raw bytes only** — never through the
text/`walkTree` machinery. See [Version 4](#version-4--prompt-book-pdf-binaries)
for the full contract. The legacy "PDF missing on this install" re-import card
(re-attach by hash) survives as a fallback for books whose bytes reached no peer
(e.g. created before v4).

A future phase may re-nest cue children under their parent stack folder
(`cueStacks/{stackUuid}/cues/{cueUuid}/...`) so `git diff` scopes to the
edited stack — that's a `formatVersion` bump when it lands.

## Canonical JSON contract

Round-trip stability is a correctness concern, not a stylistic one. Two
exports of semantically identical state must produce byte-identical files;
otherwise every commit is dominated by incidental key reorderings, breaking
diff readability and making three-way merge useless.

The contract is implemented in
[`sync/CanonicalJson.kt`](../src/main/kotlin/uk/me/cormack/lighting7/sync/CanonicalJson.kt):

* **`prettyPrint = true`, 2-space indent** — diffs are human-readable.
* **`explicitNulls = false`** — null fields are omitted. Adding a new optional
  field is therefore forward-compatible: existing repos serialise without it,
  older readers skip it (`ignoreUnknownKeys = true`).
* **`encodeDefaults = false`** — fields equal to their default are omitted.
  Combined with the previous setting, a fresh DTO with default state encodes
  to `{}`.
* **Recursive key sort** — kotlinx.serialization's `JsonObject` is a
  `LinkedHashMap` that preserves insertion order, which depends on
  `@Serializable` property declaration order. We re-walk the encoded
  `JsonElement` and sort keys alphabetically before stringifying. Without this
  step, a property reorder in source code produces noisy diffs.
* **Trailing newline** — POSIX-friendly; also avoids the "no newline at end
  of file" diff marker.

Anything that round-trips through the sync layer must use `canonicalEncode()`,
not a raw `Json.encodeToString`.

## UUID contract

Every synced DAO carries a `uuid: UUID` column. JSON foreign keys are by UUID
(e.g. `cueStackUuid`, `presetUuid`); int ids are local-only handles that
never appear in exported files.

The DAO declaration is:

```kotlin
import org.jetbrains.exposed.v1.core.java.javaUUID

val uuid = javaUUID("uuid").autoGenerate()
```

Use `javaUUID()`, **not** `uuid()`. Since Exposed 1.x, `Table.uuid()` returns a
`Column<kotlin.uuid.Uuid>`, whereas this whole identity layer — `ProjectExporter`,
`ProjectImporter`, the sync DTOs and the canonical JSON — is built on
`java.util.UUID`. `javaUUID()` is the `java.util.UUID` column type. Both share the
same `BasicUuidColumnType` (which is where `sqlType()` lives), so the two store
identically on disk; the difference is purely the Kotlin type you get back, and
mixing them would fracture the identity layer.

`autoGenerate()` ensures every new row gets a UUID. UUID-collision protection
on import is enforced at the application layer in `ProjectImporter` (a
fast `find { uuid eq targetUuid }` lookup before insert), not via a unique
index — random UUIDs make accidental collisions astronomically unlikely.

New tables default to **not synced** until explicitly added to
`ProjectExporter` / `ProjectImporter`. Don't rely on auto-discovery.

**An edit keeps a child's uuid.** A record whose children are written as whole lists — a cue's
layers, ad-hoc FX, attribute assignments and script hooks (`writeCueChildren`), a Look's rows
and FX (`writeLookChildren`) — reconciles the incoming list against what is stored, by each
child's natural key, rather than deleting every child and creating it again. A child that survives
the write is updated in place and keeps its uuid, so a one-value edit is one changed file in the
working tree, one changed record in the three-way diff and one line in the history, not "every
child replaced". The keys and the pairing rule are in `docs/cues-engineering.md` §"Key Design
Decisions". Each child already carried its uuid in the archive, so this needed no `formatVersion`
change; a palette's rows still churn (`FU-SYNC-CHILD-UUID-CHURN`).

## Ordinal contract

**Cue stack entries** (`cueStackEntries/`, v32) carry `ordinal: Double` (cue model plan P6). Every
desk plays a stack's list in `(ordinal, uuid)` order — `ENTRY_ORDER` in `models/cueStackEntries.kt`,
the uuid compared as its canonical text, which is its 16 bytes unsigned — never by the local int id,
which a desk mints in whatever order an import reads its files.

* **A write changes as few entries as it can.** `writeEntryOrder` keeps the longest run of entries
  whose ordinals already ascend in the new order and gives the others room between their kept
  neighbours (`planEntryOrdinals`): an insert or a drag takes the midpoint, an append counts on in
  whole numbers. The caller names a **fresh** entry — just made, or moved in from another stack — so
  its placeholder or old-stack ordinal never counts as in order and only it is written. So a drag or
  an insert rewrites one file. It never writes a tie, and when doubles leave no room it
  renormalises the stack `1.0, 2.0, …` — still the order given.
* **Two desks inserting into one gap** both take the same midpoint. The three-way diff sees two new
  records and keeps both, and the tie plays in uuid order on every desk. The next write to that stack
  that keeps one of the pair moves the other off the shared ordinal.
* **A copy re-mints uuids.** `copy_to_project` writes the source's order out after copying a stack,
  so a tie cannot flip. A clone (`ProjectCloner`) copies ordinals verbatim under fresh uuids, so two
  entries a merge left on one ordinal may play the other way round in the clone — the only order a
  clone does not keep, and only for entries that were inserted into one gap at once.

**Every other ordered table** still keeps `sortOrder: Int` (`FU-SYNC-ORDINAL-DOUBLE`); exports sort
their embedded child arrays by `(sortOrder, uuid)` so the canonical output is deterministic.

## Format versioning

`formatVersion.json` at repo root carries `{ formatVersion, minReader }`.
Current writer emits `formatVersion = 42`, `minReader = 42`. Rules for future
phases:

* New optional field → no version bump (`ignoreUnknownKeys = true`).
* New required field, removed field, or semantic change → bump
  `formatVersion`.
* Truly breaking change → bump both `formatVersion` and `minReader`.

**The first rule has a sharp edge, and v8 is where it showed.** "Optional" means *optional to the
parser*; the question the rule is really asking is what an older reader does with the field
missing. Where the answer is a valid state, no bump is needed — `SpeedMasterJson`'s four
routing/follow fields are the worked example: a null family means "routes nothing", so a v7 reader
handles a v8 master correctly by ignoring them. Where the answer is a **degraded record**, the
writer must bump so the older install refuses the repo instead. Ask "would an older reader import
this record wrong, and then write its mistake back?" rather than "is the field nullable?".

**v13 is the worked example of a semantic change on an existing field.** The rig row's two new
fields alone would have been the `SpeedMasterJson` case — absent is a valid state (every row was a
whole scrolling line) — but the same change widened `BuskBankJson.flow` (and the row's) with
`SCROLL`, and a v12 reader imports an unknown flow as `WRAP` and writes that back on its next
wipe-then-export push, re-flowing the bank for every peer. That is the degraded-record answer, so
the writer bumped; `minReader` stays at 5 because every earlier archive still decodes.

**A detached show's origin (venue-attach plan Att.1) took no bump, by the same question.** A v35
reader ignores `detachedFromVenueUuid` and `detachedFromVersion` and imports the show as what it is, a
standalone show, which loads, outputs and draws exactly as on a desk that reads them; its next push
writes them away. What that loses is the origin alone, and a show with no origin is a valid state: every
show detached before Att.1 is one, and an attach of one falls back to the no-origin rule (every venue
record the show lacks comes in, each offered a hide in the review) rather than doing anything silently.
So no record degrades, and the writer is still v35.

**An FX parameter's editor metadata (fx-settings plan session 1) took no bump, by the same
question.** A user FX definition's `parameters` may carry `label`, `min`, `max`, `step`, `unit`,
`options`, `role`, `shownWhen` and `pair`. A v40 reader ignores them and imports the definition with
none, which is a valid state — the editor guesses for a parameter that declares nothing — and its next
push writes them away. The one record that would degrade is a `choice` stripped of its options, and
nothing on the desk authors a `choice` yet: no built-in declares one and the definition sheet does not
offer the type. Session 2's `minReader` 41 closed that before one could exist (§"Version 41 — Wave");
a choice with no options takes any value (`choiceProblem`, `TypedParams.choice`). The writer stayed v40.

**Deferred Look FX going (absolute-looks plan D2) took no bump, by the same question.** Nothing
new is written: every Look FX this desk exports names a fixture, a group or a selection, which a
v37 reader imports as what it is. The change is on the reading side. A `deferred` FX, which ran on
whatever applied its Look, is dropped from any archive by `importLooks` with a warning, as
`loadLookSnapshot` drops one an older database holds. An older desk that still writes one loses it
here, and this desk's next push writes the Look without it; that is the decision, not a degraded
record, and the Look it leaves is a valid v37 state. So the writer is still v37.

A Look layer naming no targets (absolute-looks plan D1, P8) took none either, by the same question.
Every Look layer this desk exports names none, which a v37 reader imports as what it is: a layer of
the whole Look. `importCueLayers` drops the targets of a Look layer from any archive with a warning,
naming its cue and Look, as `state/LookLayerTargetsMigration.kt` cleared the ones this database held;
an older desk's targeted Look layer comes in whole, and this desk's next push writes it naming none.

**A master's `usage` gaining `beam` (effects plan session 6) took no bump, by the same question.**
`usage` is a free string on the wire: an older reader imports `beam` verbatim, its bank routes
nothing on it (no FX in its library is `beam`), its write boundary re-validates a usage only when
a PUT changes it, and its exporter writes the column back as it found it. Nothing degrades, so a v34
writer is still v34.

**The FX library's changes (terminology plan session 3) took no bump either, because the old names
are read rather than written.** Four FX ids were renamed (`Triangle`, `ColourPulse`, `Strobe` and
`ZoomBreathe` are `Ramp`, `ColourWave`, `DimmerStrobe` and `ZoomWave`), Breathe went, the `pingPong`
parameter became Direction Bounce, Colour Cycle took literal colours only, `SPLIT` folded into
`EDGES_IN` and the composite mode and category went. Every archive's FX goes through
`sync/LegacyNames.kt` on import (§"Old names"), whatever its `formatVersion`, so an older archive
imports onto today's names. An older desk reading a newer archive meets an id or Order it lacks and
runs nothing for that FX until it is updated, and plays a bouncing FX it knows wrong (§"Old names"):
the cases session 4's `formatVersion` 38 closes.

### Old names

`sync/LegacyNames.kt` is the one place an old name lives (terminology plan P3, P5): the registry, the
REST layer and the client know only today's. It maps by **field**, never by version, because an old
name never returns with a new meaning in the same field — so it applies to every archive, and to a
tree that mixes two. `ProjectImporter.readDir` reads each folder under its old names too and passes
every record through `LegacyNames.record` before decoding it, a record in today's folder winning over
its namesake in an old one; then every FX goes through `LegacyNames.rewriteFx`, then `consolidateFx`. `RecordHasher` keys a
record in an old folder by today's name, so a merge across the rename pairs a record with itself.

| Where | Old | Today |
|---|---|---|
| folder | `templates/`, `cueAdHocEffects/`, `cuePropertyAssignments/` | `palettes/`, `cueAdHocFx/`, `cueAttributeAssignments/` |
| a stored FX, in any home | `effectType`, `category`, `propertyName`, `beatDivision`, `distribution`, `stepTiming` (boolean), `size`, `nShot`, `blocks`, `groups` | `fxType`, `family`, `attribute`, `cycle`, `order`, `timing` (`HEAD` / `CYCLE`), `amount`, `runs`, `segments`, `parts` |
| a Look's and a palette's FX | `effects`, `effect` | `fx` |
| a value row (Look, palette, cue) | `propertyName` | `attribute` |
| a cue layer | `templateUuid`, `propertyMask` | `paletteUuid`, `attributeMask` |
| a busk pad | `templateUuid` | `paletteUuid` |
| a speed master | `usage` | `family` |
| an FX definition | `effectId`, `category`, `effectMode`, `compatibleProperties`, `defaultStepTiming` (boolean) | `fxId`, `family`, `fxMode`, `compatibleAttributes`, `defaultTiming` |
| `fxType` | `Triangle`, `ColourPulse`, `Strobe`, `ZoomBreathe` | `Ramp`, `ColourWave`, `DimmerStrobe`, `ZoomWave` |
| a stored FX's `fxType`, `family`, `parameters` and `attribute` (v41) | `SineWave`, `RampUp`, `RampDown`, `Ramp`, `SquareWave`, `Pulse`, `DimmerStrobe`, `ZoomWave`, `IrisPulse`, `FrostPulse`, `FocusPull`, and their parameters | `Wave` on its shape, with Wave's parameters (§"Version 41 — Wave") |
| a stored FX's `fxType`, `family` and `parameters` (v42) | `Circle`, `Figure8`, `RandomPosition`, `Sweep`, `PanSweep`, `TiltSweep`, and their parameters | `Path` on its shape, with Path's parameters (§"Version 42 — Path") |
| a stored FX's `parameters` (v42) | Flicker's `min`, `max`; Candle Flicker's `baseLevel`, `min`, `max`, `smoothing`; Fluorescent Flicker's `offLevel`, `onLevel`, `flickerDurationMs`, `flickerSpeed`; Lightning Strike's `minBrightness`, `maxBrightness`; Colour Strobe's `onRatio`; Colour Cycle's `fadeRatio` | `low`, `high`; `base`, `low`, `high`, `settle`; `low`, `high`, `burstMs`, `speed`; `low`, `high`; `width`; `between` and `fade` |
| `order` | `LINEAR`, `CENTER_OUT`, `EDGES_IN`, `SPLIT` | `NORMAL`, `CENTRE_OUT`, `CENTRE_IN`, `CENTRE_IN` |
| `family`, a master's too | `dimmer`, `palette` | `intensity`, `steps` |
| an FX's `family` | `composite` | the family of its output: `intensity` for Lightning Strike and any slider, else `colour` or `position` |
| a palette reference, in an FX parameter or a row's value | `tmpl:{uuid}`, any case | `pal:{uuid}` |
| a surface binding's `targetType` and payload `type` | `pressTemplate`, `stackEffectSize`, `fixtureProperty`, `groupProperty`, `selectionProperty` | `pressPalette`, `stackFxAmount`, `fixtureAttribute`, `groupAttribute`, `selectionAttribute` |
| a surface binding's attribute target, a flash's nested one included | `propertyName` | `attribute` |
| Circle's, Figure 8's and Random Position's parameters | `panCenter`, `tiltCenter` | `panCentre`, `tiltCentre` |
| a palette press's payload | `templateUuid` | `paletteUuid` |
| an FX definition's `fxMode` | `COMPOSITE` | `STANDARD` (its script must then return one output; logged) |
| a script's `scriptType` | `FX_CALC_COMPOSITE` | `FX_CALC` (likewise) |

A cue's `fadeCurve` is an easing curve, not an Order, and its `LINEAR` stays. The attribute
bindings and the centre parameters are v39's (§"Version 39 — attributes and centres").

`LegacyNames.rewriteFx` is §4.2's rewrite of one stored FX, shared with database step 4
(`state/FxLibraryMigration.kt`, `PRAGMA user_version` 4), which ran it once over every Look, cue and
palette FX this database held, each named in the log:

- Breathe becomes Sine Wave a quarter-cycle on (phase offset + 0.75, Direction Forward), the same curve.
- A `pingPong` on Colour Fade, Focus Pull or a sweep becomes Direction Bounce at half the cycle, its
  phase offset (kept modulo 2, Bounce's parity), spread and runs doubled, which plays the same
  (`LegacyFxOutputTest`). A spread above 1 stops at 2 and the Ping-pong Order sweeps the halved cycle;
  both are logged, as their output moves. `pingPong: false` is dropped.
- A Colour Cycle over palettes alone becomes Palette Steps over them, keeping `fadeRatio` — each
  palette now lands on each head for itself, the one deliberate change of output (D14). A mixed one
  takes each palette's colour now.
- A script FX's `colourList` takes each palette's colour now; a list left with none is black, as it
  played. A script FX's own id and parameters are never renamed.
- Lightning Strike drops the colour parameters it computed and never applied.

What keeps the maps by field is that nothing written today holds an old name: every boundary that
writes or starts an FX refuses a retired id and a `pingPong` on the five FX that bounce
(`LegacyNames.writeProblem`, through `routes/storedFxRules.kt`), no FX definition may take a retired
id (`fxDefinitions.kt`, `registerFx`), and every field and value the v38 rename moved is written
only under its new name.

**An absent `pingPong` is the one rewrite not by name.** Before session 3 a bouncing FX naming none
ran with it on, its default; since, one naming none means what its Direction says. Step 4 ran once,
before this database could hold the second kind, so it read an absent one as on. A v37 archive can
hold either, so a session-3 desk stamped `project.json` with `fxLibrary: 2` (`LegacyNames.FX_LIBRARY`):
a v37 archive without it reads an absent `pingPong` as on, one with it as today's. From v38 the
version says it — every v38 archive is written against library 2 — so `project.json` no longer
carries `fxLibrary`, and `ProjectJson.fxLibrary` is read, never written
(`LegacyNames.absentPingPongIsOn`). A merge with a v37 remote that says no library first writes an
explicit `pingPong: "true"` into each bouncing FX that names none in the records the remote supplies,
then drops the remote's `fxLibrary` (`RemoteSyncEngine.spellOutRemotePingPong`), before the merged
tree takes this desk's `formatVersion`.

The two mixed-version cases session 3 left open — an older desk playing a bouncing FX written since as
`pingPong` on, and writing `project.json` back without `fxLibrary` — are closed by v38: no desk older
than v38 reads a v38 archive (§"Version 38").

**v5 is the worked example of a truly breaking change.** FX presets and named palettes collapsed
into `looks/`, and `cuePresetApplications/` became `cueLayers/`. `CuePresetApplicationJson.presetUuid`
— a *required* field — disappeared, so both numbers moved. A v4 repo's preset applications name a
`presetUuid` that resolves to nothing, and there is no in-place upgrade: reading one would import a
project whose cues had lost their composition entirely, which is worse than refusing it. Hence
`MIN_SUPPORTED_FORMAT_VERSION = 5` as well.

Note the gate lives in **two compiled-in constants** (`SUPPORTED_FORMAT_VERSION` and
`MIN_SUPPORTED_FORMAT_VERSION` in `ProjectImporter.kt`), not in the DTO. `FormatVersionJson.minReader`
is *written* but never read by the importer, so bumping the DTO alone leaves the gate wide open. It
also cuts the other way, and session 3 proved it: bumping the DTO to 6 while `SUPPORTED` stayed at 5
made the importer **refuse its own export**, which surfaced as every clone and round-trip test failing
with an `ImportError`. Move both, or neither.

**v7 is the worked example of a removed field.** The positional colour list went: `palette` left
`looks/`, `cues/` and `cueStacks/`, and `updateGlobalPalette` left `cues/`. `minReader` stayed at
**5**, because every removed field has a default — a v5 or v6 archive still imports and simply drops
colour lists nothing reads any more. Only the writer's number moved, which is what makes an older
install refuse a v7 repo rather than silently write those fields back on its next push.

**Fixture optics session 7 is the worked example of a data change that needs no version.** Five
reset options left their settings and became fixture commands (`docs/fixtures-engineering.md`
§"@FixtureCommand"), and the Varytec's and the Shehds' `reset` settings left their types. No field of
any DTO changed: a row stores a setting by its level (`"251"`), never by an option's name, so an older
archive decodes exactly as before. What changed is which stored rows the desk keeps — every import ends
with `stripCommandRows` beside `stripTriggerRows`, dropping a row that names a command or holds a
command's band on an attribute sharing its channel, and logging how many went. An older reader that
imports such a row back would hold nothing worse than it held before; on this desk the output's band
guard sends it as idle until the next import strips it. `remote_access_settings.allow_commands` is a
column on a machine-local table, so `SyncCoverageTest`'s dispositions are unchanged.

**FX editor session 3b is the worked example of an order that needed no version.** A Look's FX
already carried `sortOrder` (`LookFxJson.sortOrder`, omitted at 0), the exporter wrote them by
`(sortOrder, uuid)` and the importer read the field; what changed is that Record now writes it in
apply order and a press spawns by it, ties in id order (`docs/fx-engineering.md` §"Programmer
suppression and the priority band"). An archive whose Look FX all say 0, as every Look did before
Record numbered them, imports them in file order, since the importer inserts them as listed. Record
and an explicit list now write distinct orders (one whose orders do not rise is renumbered by
position); a tie survives only in a home written before, and in its copies and imports. A desk plays
such a tie in id order, but an export lists it by uuid, so a clone or an import of a tied home may
play it the other way round.

### Version 42 — Path

fx-settings plan session 3: Path replaces Circle, Figure 8, Random Position, Sweep, Pan Sweep and
Tilt Sweep; Colour Cycle's `fadeRatio` is `between` (`CUT` where it was 0, else `FADE`) and `fade`; and
the Flicker family's, Lightning Strike's and Colour Strobe's parameters take their normalised names
(`docs/fx-engineering.md` §"Path, Colour Cycle and the renamed parameters"). A stored FX holding any of
them is read through `LegacyNames.absorbFx` after `consolidateFx`, whatever the archive's
`formatVersion`, and database step 8 (`state/FxSiblingsMigration.kt`) does the same to a database's
rows; nothing a head does moves (`LegacyEquivalenceTest`). Colour Wave and Colour Fade are not
rewritten — no Colour Cycle draws either to the DMX step — and stay as they are written, still
registered as legacy. A record below 42 that holds a key under a new name its old FX never read (a
stray `low` on a Flicker) loses it, since the new script would start reading it. As at v41, no folder or field is renamed, so a converted record reads as this
desk's change and is pushed in the new form.

`SUPPORTED_FORMAT_VERSION = 42`; `MIN_SUPPORTED_FORMAT_VERSION` stays 5. **`minReader` moves to 42**:
a v41 reader knows no Path, and would read a renamed parameter as absent, so a Flicker would run at its
defaults. `RichProjectFixture`'s Swirl Look holds a Path Circle and a Path Line with both ends set.

### Version 41 — Wave

fx-settings plan session 2: Wave replaces Sine Wave, Ramp Up, Ramp Down, Ramp, Square Wave, Pulse,
Dimmer Strobe, Zoom Wave, Iris Pulse, Frost Pulse and Focus Pull (`docs/fx-engineering.md` §"Wave").
A stored FX on one of those ids, in a Look, a cue or a palette, is read through
`LegacyNames.consolidateFx` after `rewriteFx`, whatever the archive's `formatVersion`: its `fxType`
is `Wave`, its `family` `intensity`, its parameters Wave's at the values the old script read (its
shape, `low` and `high` for `min` and `max` or `offValue` and `onValue` or `start` and `end`, `width`,
`attack`, `hold`, `curve`), and a beam one naming no `attribute` names the one it drove (so on a group
where only some heads have it, it now runs on those, where it ran on none; logged as output that moves). Database step
7 (`state/FxConsolidationMigration.kt`) does the same to a database's rows. No folder or field is
renamed, so `RecordHasher` hashes an older desk's record as it was written: after the upgrade a
converted record reads as this desk's change and is pushed in the Wave form, which every desk
converting it writes byte for byte, and one an older desk edited meanwhile is a conflict, as any record
both sides changed is.

`SUPPORTED_FORMAT_VERSION = 41`; `MIN_SUPPORTED_FORMAT_VERSION` stays 5. **`minReader` moves to 41**:
a v40 reader knows no Wave, so would refuse to start or skip every level FX in the show. Wave's
`shape` is the first `choice` a stored FX holds, which session 1's note above waited on.
`RichProjectFixture`'s level FX are Waves: Sines, one with its `low` set, and Pulses with their `hold` set.

### Version 40 — Fixtures

FX editor follow-ups plan session 2: a stored FX's `elementMode` — a cue's ad-hoc FX, a Look's, a
palette's — may be `WHOLE`, *Over: Fixtures* (`docs/fx-engineering.md` §"Element Mode: what *Over*
counts"). Nothing is renamed or moved, and an FX without the field still imports as `PER_FIXTURE`.
`SUPPORTED_FORMAT_VERSION = 40`; `MIN_SUPPORTED_FORMAT_VERSION` stays 5. **`minReader` moves to 40**:
a v39 reader keeps the string but its lenient parse runs each `WHOLE` FX as `PER_FIXTURE`, so the
show would play differently there with nothing but a warning in its log. `RichProjectFixture`'s selection FX is `WHOLE`.

### Version 39 — attributes and centres

Terminology plan session 6: the two stored names session 5 left behind. A surface binding's attribute
targets are `fixtureAttribute`, `groupAttribute` and `selectionAttribute` (`targetType` and the
payload's `type`), and their `propertyName` — `encoderBankSet`'s too, and a flash's nested target's —
is `attribute`. Circle's, Figure 8's and Random Position's `panCenter` and `tiltCenter` are
`panCentre` and `tiltCentre` in every Look, cue and palette FX (D20); a script FX's own parameters
keep their author's words. `LegacyNames` reads the old names in any archive (`bindingPayload`,
`centreParameters`), and every write boundary refuses an old centre (`LegacyNames.writeProblem`).

`SUPPORTED_FORMAT_VERSION = 39`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**. `LegacyV38ArchiveTest`
imports `src/test/resources/sync/legacy-v38/`, `RichProjectFixture` as main exported it at v38 with a
Circle, a group binding and a flash added for the names that moved (frozen: never regenerate it), and
re-exports it with only those names moved. **`minReader` moves to 39**: a v38 reader would hold every
attribute binding as one it does not know, dead, and run each centre at its default. Database step 6,
`state/AttributeNamesMigration.kt`, renames a database's copies (CLAUDE.md §Database), and names
in a warning each stored script and FX definition that still says an old centre, which it leaves as
it is.

A base `sync_state` took at a commit before v39 is re-hashed from that commit's blob, as one before v38
is (below), so a binding or a Circle nobody touched reads as unchanged rather than as a conflict
(`PreV39BaseSyncTest`).

### Version 38 — the glossary's words

Terminology plan session 4 (§4.4, the storage half of §4.3): the archive says what the screen says.
Three folders and every field §"Old names" lists take their new names, and four stored values that are
also wire values move with them — the Order constants (`NORMAL`, `CENTRE_OUT`, `CENTRE_IN`), palette
references (`pal:{uuid}`), the family values (`intensity`, and `steps` for the Steps FX's library
family) and the two surface binding names (`pressPalette`, `stackFxAmount`). A stored FX's Timing is
`timing`, `CYCLE` or `HEAD`, where it was the boolean `stepTiming`; an FX definition's is `defaultTiming`.
`project.json` no longer writes `fxLibrary`, since the version implies it.

`SUPPORTED_FORMAT_VERSION = 38`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**, because every old name is
read through `LegacyNames` — `LegacyV37ArchiveTest` imports `src/test/resources/sync/legacy-v37/`,
`RichProjectFixture` as main exported it at v37 (frozen: never regenerate it), and re-exports it as the
same archive with only its names moved. **`minReader` moves to 38**, the truly breaking case: a v37
reader would find every renamed required field (`effectType`, `beatDivision`, `distribution`) missing
and refuse each FX, or read a renamed optional one (`size`, `nShot`, `templateUuid`) as absent and
write it away on its next push. As the doc above says, what actually stops an older desk is its own
`SUPPORTED_FORMAT_VERSION` of 37, which refuses a v38 repo before the working tree moves; `minReader`
says the same to a reader that checks it.

The database moved in the same session — database step 5, `state/TerminologyMigration.kt`,
`PRAGMA user_version` 5, backed up first to `<db>.pre-terminology-<utc>.db` (CLAUDE.md §Database) —
and so did the sync engine's machine-local record keys (`sync_state.table_name`,
`sync_session_conflict.table_name`), so the first sync after the upgrade sees each record under its
new folder rather than a deletion and an addition.

A record is **hashed in today's words** (`RecordHasher.recordHash`, the record put through
`LegacyNames.record` first), so a v37 file and this desk's export of the same record hash alike. The
hash `sync_state` holds for a commit made before v38 is of the old bytes, so the three-way diff
re-hashes such a base from that commit's own blob (`RemoteSyncEngine.readSyncStateMeta`, which since
v39 re-hashes a base from any commit before v39); without it
every record would read as changed here, and a peer's edit to one this desk never touched would be a
conflict. `PreV38BaseSyncTest` is that case.

### Version 37 — FX runs

Stack-timeline plan session 1 (D7, §3.4; `docs/cues-engineering.md` §"FX runs"):
`CueAdHocFxJson` gains `runUuid`, the run key ad-hoc FX of neighbouring cues share, omitted
when null, so a project with no runs exports byte-for-byte as at v36 (only `formatVersion.json` says 37).
`SUPPORTED_FORMAT_VERSION = 37`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5** — a missing key is no run,
so a v36 archive imports with none (C5: nothing joins its FX for it). The writer's number moves by
the sharp-edge rule: a v36 reader would import every FX out of its run, and its next
wipe-then-export push would write the keys away for every peer.

**Imported as stored**: nothing checks that a key's rows sit in neighbouring cues or that a cue holds
it once. A key is not an identity — the field is `runUuid`, never `uuid` — so `ExportUuidRemapper`
collects none and a clone keeps every key as it is, its runs whole; the cue copier is what gives a
copy fresh ones. `RichProjectFixture` keys the Pulse in `open` and `build`, neighbours in `show-1`.

### Version 36 — fixture colours

Tables-colours plan session 3 (D7, D10; `docs/fixtures-engineering.md` §"Fixture colours"):
`FixturePatchJson` and `FixtureGroupJson` gain `colour`, one of the desk's ten keys, omitted when
none, and a show on a venue's recolours of its venue's patches and groups travel as
**`showColours/{uuid}.json`** (`ShowColourJson`: `uuid`, `recordUuid` — the venue patch or group —
`colour`). `show_colours` is `Portable("showColours")` in `SyncCoverageTest`, read from
`seedShowOnVenue`'s export like the hides. A project with no colours exports byte-for-byte as at v35
(only `formatVersion.json` says 36). `SUPPORTED_FORMAT_VERSION = 36`; `MIN_SUPPORTED_FORMAT_VERSION`
**stays 5** — a missing colour is none, so every older archive imports as before. The writer's number
moves by the sharp-edge rule: a v35 reader would import every patch and group without its colour, and
every show without its recolours, and its next wipe-then-export push would write them away for every
peer.

**Imported as stored**, as a gel code is: a key this desk does not know (another desk's) is kept, reads
as none here (`FixtureColours.read`) and is written back unchanged, so it survives a round trip
through an older or newer desk; one longer than the column (16) is dropped with a warning. A recolour
naming a record the pinned version lacks is kept, as a hide is, one an archive repeats for a record is
kept once, and one in a project on no venue is dropped. The recolour names a venue record, never one
of the show's archive, so a clone remaps only its own `uuid`. `replaceFromWorkingTree` and the
project delete sweep the show's recolours with its hides and overrides. `RichProjectFixture` colours
`hex-1` and `front-wash`; `seedShowOnVenue` recolours its overridden venue patch and the venue's
first group.

### Version 35 — speed masters on a cue

Venue-visit plan S16 (D20; `docs/cues-engineering.md` §"Speed masters on a cue"): `CueJson` gains
`speedMasters` ([`CueSpeedMasterJson`]: `speedMasterUuid`, `bpm`), the masters the cue sets on every
GO into it, in order. Empty is omitted, so a project whose cues set no tempo exports byte-for-byte as at
v34 (only `formatVersion.json` says 35). The master is named by uuid, so `ExportUuidRemapper`
re-points it on a clone with no change there. `SUPPORTED_FORMAT_VERSION = 35`;
`MIN_SUPPORTED_FORMAT_VERSION` **stays 5**, since a missing list sets nothing. The writer's number
moves by the sharp-edge rule: a v34 reader would import every cue without its tempos and its next
wipe-then-export push would write them away for every peer. Imported as stored: a uuid the archive's
masters lack is kept, and skipped at GO as a master deleted under the cue is. Stored as a JSON column
on `cues` (`speed_masters`), not a table, so `SyncCoverageTest` gained no row. `RichProjectFixture`'s
*open* cue sets master 1 and a follower.

### Version 34 — one FX over a selection

Effects plan session 3 (venue-visit R4.3; `docs/fx-engineering.md` §"Over a selection"): an FX's
target may be a **selection**, an ordered list of fixtures and groups. No field moved and no column was
added — `LookFxJson` and `CueAdHocFxJson` carry it in the `targetType` / `targetKey` they
always had, as `targetType: "selection"` and a `targetKey` holding the list's canonical JSON,
`[{"key":"par-1","type":"fixture"},{"key":"Front wash","type":"group"}]` (keys alphabetical, no
whitespace, in selection order — `FxTargetsRef.Selection.key`). `SUPPORTED_FORMAT_VERSION = 34`;
`MIN_SUPPORTED_FORMAT_VERSION` **stays 5**, since nothing older uses the value. The writer's number
moves by the sharp-edge rule: a v33 reader stores a target it cannot spawn, so every cue and Look
holding one would skip that FX. Imported as stored; a key that does not parse is refused where
the FX spawns. `target_key` is `text` rather than `varchar(255)` on `look_effects` and
`cue_ad_hoc_effects`, since a selection's key outgrows 255 characters; SQLite enforces no width, so
an existing database needs no migration. The list names fixtures and groups by key, not uuid, so a clone needs no remap —
and a rename rewrites the keys inside it (`ai/RigKeyReferences.kt`). `RichProjectFixture` carries one
Look FX and one cue ad-hoc FX over a selection. A project with none exports byte-for-byte as
at v33 (only `formatVersion.json` says 34).

### Version 33 — the spread's shape

Effects plan session 2 (venue-visit R4.2; `docs/fx-engineering.md` §"The spread's shape"):
`LookFxJson`, `PaletteFxJson` and `CueAdHocFxJson` gain `spread` (default 1.0), `wings`
and `blocks` (default 1) and `groups` (default 0, off), each omitted at its default, and the three
tables gain the columns `spread`, `spread_wings`, `spread_blocks` and `spread_groups` with the same
defaults (prefixed because `GROUPS` is an SQLite keyword). `SUPPORTED_FORMAT_VERSION = 33`;
`MIN_SUPPORTED_FORMAT_VERSION` **stays 5** — each field defaults to what a row without it meant, so
every older archive decodes and plays as before. The writer's number moves by the sharp-edge rule: a
v32 reader would run a 50 % ripple over the whole cycle and two wings as one, and its next
wipe-then-export push would write all four away for every peer. Imported as stored: a value out of
range is clamped where the FX spawns, not refused here. `RichProjectFixture` sets all four to
non-defaults on every stored shape. A project that shapes no spread exports byte-for-byte as at v32
(only `formatVersion.json` says 33).

### Version 32 — cue stack entries

Cue model plan session 3 (venue-visit R1.3, §3.9): a stack's list leaves the cue files. A cue is what
a state is (D2), and each place in a list is its own record.

* `cues/{uuid}.json` drops `cueStackUuid`, `sortOrder`, `cueNumber`, `cueNumberAuto`, `autoAdvance`,
  `autoAdvanceDelayMs` and `cueType`.
* `cueStackEntries/{uuid}.json` (`CueStackEntryJson`), one file per entry: `{uuid, cueStackUuid,
  cueUuid?, kind?, label?, ordinal, cueNumber?, autoAdvance?, autoAdvanceDelayMs?}`. `kind` is
  omitted for a cue entry; a marker says `MARKER` and carries its `label` (D12). `ordinal` is a double
  (§"Ordinal contract"). **`cueNumber` is written when explicit, and an auto number only where a
  reader could not derive it** (P5, as amended 2026-10-16): every reader derives the rest from position
  (`renumberAutoEntries` from scratch, per stack, after the entries land), so a drag changes one file
  rather than every auto-numbered entry after it. The exception is a number venue-visit D11 holds in
  place — an explicit renumber still waiting for *Fix Order* keeps its neighbours' labels (1, 4.5, 3, 4,
  5) where a reader starting from nothing would anchor on the 4.5 (4, 4.5, 4.6, 4.7, 4.8).
  `heldAutoNumbers` finds those, growing the set until the reader's derivation gives every number left
  out, and the exporter writes them; the reader takes them as explicit, so a pull or a clone shows the
  labels this desk does, and a held number stops renumbering by itself from the first import on. A
  stack nothing holds writes no auto number at all. The exporter writes
  entries from the rows themselves, never through a cue's "one" entry, so a shared or loose
  cue (session 4) already travels: several entries may name one cue, and a cue no entry names is loose.
* `promptBookAnchors/` names the entry it marks (`entryUuid`) instead of a cue (D4).
* One file per entry is the merge story: two desks adding to one stack add two records, a drag
  rewrites one, and a renumber rewrites only the entries whose written number changed — the one
  renumbered, and any neighbour whose number it now holds.

`SUPPORTED_FORMAT_VERSION = 32`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**. The writer's number moves
because a v31 reader would import every cue with no stack and its next wipe-then-export push would
write every stack empty for every peer.

**The pre-v32 reader.** A v5–v31 archive (`formatVersion < CUE_ENTRIES_FORMAT_VERSION`) still imports.
Its cue files are decoded a second time as `LegacyCuePlacementJson`, read only, and each cue becomes
one entry in its `cueStackUuid` stack with `ordinal = sortOrder` — strictly, the place's 0-based rank
by `(sortOrder, uuid)` in its stack, which *is* the sortOrder for any archive a desk with entries wrote
(each cue's index in its list) and closes an older archive's gaps the same way on every desk — its
number (explicit only when
`cueNumberAuto` is false), its follow, and **its cue's uuid as the entry's uuid**. A MARKER cue becomes
a marker entry labelled with its name, under the file's uuid. A cue with no stack is loose — the old
"Unsorted" fallback is gone. An anchor's `cueUuid` resolves to the entry with that uuid, which is its
cue's one entry or the marker. Naming the entry after its cue is what lets two desks that read one v31
repo write v32 that agree: each writes the same `cueStackEntries/{uuid}.json` for the same place.

**The entries already on a desk** were minted at random — by the cue model migration and by every v31
import — so the desk names them the same way once, at startup (`state/CueEntryUuidMigration.kt`):
every cue's one entry takes its cue's uuid, every entry's ordinal becomes its 0-based position in its
stack (the session-1 migration renormalised a desk's own rows to `1.0, 2.0, …`, a pull since then
stored the archive's `0.0, 1.0, …`, and the two must write the same bytes), and `PRAGMA user_version`
is set to 1 so it never runs again. The positions follow the order the desk played until then —
ordinal, then **id** — not the uuid order it plays from then on, so two entries an older archive left
on one ordinal keep their places on stage; two desks that played such a tie differently write different
positions, which their first sync settles as an ordinary change. It must not: from session 4 a cue's remaining entry after *Remove from stack* is not the place
it had before the bump, and renaming it would resurrect the uuid its removed sibling's tombstone
retired. Without it, the first sync of two desks after the bump would merge both desks' entries for
every cue, and every cue would sit twice in its stack.

**After a merge two explicit numbers can clash** in one stack (two desks each typed `14A`). The
importer resolves every stack before a row is written: the entry with the lower uuid keeps the number
and the other is made auto, so `uq_entry_number_per_stack` never sees the pair and a pull never aborts
on it. Numbers compare exactly, as the index does. Each clash is answered in
`ProjectImporter.Result.numberClashes`, returned by the engine's import (`importTree`) to the path that
ran it, which puts it on the `SyncRunResult` it answers (a push-rejected retry that imports again
answers its own), carried by `SyncRunResult.numberClashes` and said in the run's
message — which the activity log and the sync-done notification (`SyncNotifications.tsx`) show; the
Sync tab's own success toasts are fixed strings and do not — and on the two import responses. The merge
commit itself still holds both numbers; the merging desk's next snapshot writes the loser without one,
and every desk that reads the merged tree resolves it alike in the meantime.

**What an archive can no longer abort a pull with.** An entry naming a stack or a cue the archive
lacks — a peer's new entry merged with this side's delete — is dropped with a warning, and so is an
anchor naming an entry that is not there, or naming none at all; of two anchors on one entry (two desks each marked it) the
lower uuid is kept. The next snapshot writes each drop as a deletion. A v31 archive's cue naming an
unknown stack is still refused, as before.

`ExportUuidRemapper`, `ProjectCloner` and `KeptUuidCopy` needed no cue code: an entry's links are
`…Uuid` fields and its identity is its `uuid`, so a clone re-points them blind (the order caveat is
in §"Ordinal contract"). `copy_to_project` copies whole stacks, so a cue two copied stacks share is
copied once and shared in the target. `RichProjectFixture` carries two markers, an explicit number
beside an auto one, a follow, two entries sharing an ordinal, a cue in two stacks anchored at both
places, a cue twice in one stack and a loose cue on a busk pad (§3.2 of the plan).

### Version 31 — stored locate

`FixturePatchJson` gains `locate` (venue-visit plan D6, D17; `docs/fixtures-engineering.md` §"Stored
locate"): the pan and tilt bytes Locate points each of a patch's units at,
`[{head?, pan, tilt, panFine?, tiltFine?}]`, empty — omitted — for none.
`SUPPORTED_FORMAT_VERSION = 31`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5** — a missing locate is the
centre, so every older archive decodes as before. The writer's number moves by the sharp-edge rule: a
v30 reader would import every patch with no stored locate and its next wipe-then-export push would
write them away for every peer. Imported as stored, canonicalised, with no type check: the write
boundary refuses a unit the type does not pan and tilt, and Locate ignores an entry it cannot place. A
venue patch's locate is part of its **Focus** facet, so a show's override of it travels in
`showOverrides/` under the same number. A patch with no stored locate exports byte-for-byte as at v30.

### Version 30 — Amount, direction and runs

Effects plan session 1 (venue-visit R4.1; `docs/fx-engineering.md` §"Amount, direction and runs"):
`LookFxJson`, `PaletteFxJson` and `CueAdHocFxJson` gain `size` (default 1.0), `direction`
(default `FORWARD`) and `nShot` (default null), each omitted at its default, and the three tables
(`look_effects`, `template_effects`, `cue_ad_hoc_effects`) gain the columns `size`, `direction` and
`n_shot` with the same defaults. `SUPPORTED_FORMAT_VERSION = 30`; `MIN_SUPPORTED_FORMAT_VERSION`
**stays 5** — each field defaults to what a row without it meant, so every older archive decodes and
plays as before. The writer's number moves by the sharp-edge rule: a v29 reader would run a 40 %
Sine Wave at full amount, a backward cycle forward and a single run forever, and its next wipe-then-export
push would write all three away for every peer. `RichProjectFixture` sets all three to non-defaults
on every stored shape. A project that uses none exports byte-for-byte as at v29 (only
`formatVersion.json` says 30).

### Version 29 — Seconds

An FX may run on *Seconds* (venue-visit plan D7, S15; `docs/fx-engineering.md` §"Seconds"): its
own cycle, with no speed master. `LookFxJson`, `PaletteFxJson` and `CueAdHocFxJson` gain
`seconds` (default false, so omitted), and a beat FX's `beatDivision` is then its cycle in
**seconds**. `SUPPORTED_FORMAT_VERSION = 29`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5** — the field
defaults to false, so every older archive decodes as before. The writer's number moves by the
sharp-edge rule, and it is a semantic change on an existing field as much as a new one: a v28 reader
would import a ten-second Sine Wave as ten beats of master 1 and write the flag away on its next push.
A row the desk writes holds no master beside the flag (`RichProjectFixture` keeps its masters there,
dormant, only so the clone test's remap still has a reference to rewrite). A wall-clock FX with no
rate master is on Seconds with or without the flag, so an older archive's wall-clock rows mean what they
meant. A project with no FX on Seconds exports byte-for-byte as at v28 (only `formatVersion.json`
says 29).

### Version 28 — centre-relative positions

A palette's position row gains a second unit (venue-visit plan D3, P3): `pos:<pan>,<tilt>`,
centre-relative coarse DMX — `-128..127` on each axis, 0 the centre of travel (DMX 128), the same
fraction of travel on every head — beside the `deg:` travel degrees every earlier row holds. **No
field moved**: `PaletteRowJson.value` is a string the importer stores verbatim, and a `deg:` row
exports byte-for-byte as before. What moved is the value's meaning, which is the sharp-edge rule's
semantic-change arm: a v27 reader parses a `pos:` row as no intent, so `CueComposer`, a press and
the resolves-to panel skip it with a warn — it would run every new position palette, and every aim
saved as one, as nothing — while its next push wrote the row back unchanged. `SUPPORTED_FORMAT_VERSION
= 28` so a v27 install refuses the repo instead; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**, because
a `deg:` row still resolves as angles (`PaletteResolver.resolvePosition` takes both units) and there
are no migrations — a stored row becomes `pos:` only when an editor saves a moved position.
`RichProjectFixture`'s per-fixture position palette holds one row in each unit, so a round trip that
rewrote either would show.

### Version 27 — venue parks

A venue's parks are its rig, versioned with it (`docs/fixtures-engineering.md` §"Venue parks"): a
venue's `parkedChannels/` were already in every version it froze, and from v27 a show on a venue
**inherits** its pinned version's, overlaid by its own `parkedChannels/` (a re-park of a venue park at
another value) and less its new **`showParkReleases/{uuid}.json`** (`ShowParkReleaseJson`: `uuid`,
`universe`, `channel`) — the venue parks it releases for its run. A release is keyed by **channel**,
never by the venue park's uuid, so it survives the venue unparking a channel and parking it again; one
with no venue park on its channel at the pin is dormant and kept. `show_park_releases` is
`Portable("showParkReleases")` in `SyncCoverageTest`, read from `seedShowOnVenue`'s export like the
hides. `SUPPORTED_FORMAT_VERSION = 27`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5** — a missing folder
is no releases. The writer's number moves by the sharp-edge rule: a v26 reader would import a show
without its releases and write them away on its next push, and would run the show without its venue's
parks. The importer drops a release in a project on no venue (with a warn, as a hide), keeps one
repeated for a channel once, and keeps the park where an archive both parks and releases a channel (a
merge). `replaceFromWorkingTree` and a project delete sweep the show's releases; Detach writes the
venue parks the show ran into its own rows, uuids kept, and drops its releases, so a detached show
exports no `showParkReleases/`. A clone remaps only a release's own `uuid`. A show with none writes no
folder, so its records export byte-for-byte as at v26 (only `formatVersion.json` says 27).

### Version 26 — lamps

`FixturePatchJson` gains `lampWatts` and `lampKelvin`: a lantern-hung patch's own lamp power and
colour temperature at full (`docs/fixtures-engineering.md` §"Lamps"), each null — omitted — taking the
lantern library's. `SUPPORTED_FORMAT_VERSION = 26`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**. A
patch with neither exports byte-for-byte as at v25. The writer's number moves by the sharp-edge rule:
a v25 reader imports every lamp as its lantern's and its next wipe-then-export push writes the patch
file without them, putting every peer's lamps back to the library's. Imported as stored, with no type
check: the write boundary refuses a lamp on a type with no lantern. A venue patch's lamp is part of
its **Focus** facet, so a show's override of it travels in `showOverrides/` under the same number.

### Version 25 — model textures

Bumped when a scene image could texture a part of a `MODEL` object (`docs/fixtures-engineering.md`
§"Model objects"). `SUPPORTED_FORMAT_VERSION = 25`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**. The
parts are keys of the object's `params` — `shape: MODEL` and `parts`, each part's `texture.image` a
scene image's SHA-256 — which `StageElementJson` already carries whole, so no DTO changed and the keys
alone would need no bump (§"A params key added later needs no bump"). The images do, for v23's reason
one step on: `SceneImageRepoSync.referencedHashes` now reads part textures beside paint
(`sceneImageHashesOf`), and a v24 install reconciles `sceneImages/` to the hashes **it** reads as
referenced — paint only — so its next push would delete every model texture while its re-export kept
the parts that name them. Emitting v25 makes such an install refuse the repo as too new instead. A
model with no texture changes nothing else in the archive.

### Version 24 — venues and shows on them

Bumped when venues began to sync (venue plan session 4, §3.4, D8, P7 — `docs/plans/completed/venue-plan.md`).
`SUPPORTED_FORMAT_VERSION = 24`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 5**, so a v24 install reads
every earlier repo: every new key defaults, and a missing folder is no hides. The writer emits
`formatVersion = 24`. What lands under the one number:

* **`project.json` says what the project is.** `kind` — `SHOW | VENUE | VENUE_VERSION` — omitted for
  a show, its default, so a standalone show's `project.json` is byte-identical to v23's. A show on a
  venue adds its **pin**, `venueUuid` (the venue project's uuid) and `venueVersion`, and `venueRepo`,
  a hint naming where the venue syncs — `owner/repo` for a GitHub remote, else the URL. The hint the
  show arrived with is kept and written back as it is (`projects.venue_repo`, a column added for it),
  so every desk that imported the show writes the same bytes whatever it has attached; a show made on
  this desk, with none, names the venue's own attached repo when there is one.
* **A show's archive is its own rows only** — which is all the exporter ever wrote, since every
  table there reads the project's own back-reference and never `RigScope`'s effective rig. Its links
  into the venue (a patch on a venue universe or rigging, a group member, a busk tile, a cue event, a
  scenery change on a venue element) are the venue records' uuids, as every link already is.
  **`showHides/{uuid}.json`** carries the show's hides (`ShowHideJson`: `uuid`, `recordKind`,
  `recordUuid`); `show_hides` is `Portable("showHides")` in `SyncCoverageTest`.
* **The importer seeds its maps from the pinned snapshot.** Before a row lands, `populateProject`
  fills `universeMap`, `riggingMap`, `elementMap`, `patchMap` and `groupMap` with every row of the
  pinned version — hidden or not, since a show group may hold a hidden venue patch — and lays the
  archive's own rows over them, so a link resolves to the show's own row where it has one (a Preset
  seating it copied keeps the venue's uuid) and the venue's otherwise. A pin whose snapshot is not on
  the desk is refused before anything is written: **409 `VENUE_MISSING`** (`ImportError.code`),
  naming the venue — by name when an older version of it is here, else by uuid — the versions held
  and the repo hint (`venue/VenuePins.kt`). It is refused on the manual import, the cloud-sync import
  (add a remote project), a clone, and a pull (below).
* **`showOverrides/{uuid}.json`** carries a show's facet overrides of venue patches (venue plan
  session 5; `ShowOverrideJson`: `uuid`, `recordUuid` — the venue patch — `facet`, and `value` and
  `base`, each the facet's fields as `models/patchFacets.kt` shapes them; `fixtures-engineering.md`
  §"Facet overrides"). `show_overrides` is `Portable("showOverrides")` in `SyncCoverageTest`. **Still
  v24, no bump**: session 4's reader reserved the folder — it wrote none and refused an archive
  holding any file in it (`RESERVED_FOLDERS`, 422 on import, `FORMAT_TOO_NEW` before a pull moves the
  working tree), because dropping one would load a different rig, an overridden address. Session 5's
  reader imports it (`importShowOverrides`: guarded uuid parsing, each value and base decoded and
  normalised, an override naming a patch the pinned version lacks kept, a repeat for one patch and
  facet kept once) and `RESERVED_FOLDERS` is empty, the mechanism kept for the next folder that needs
  it. **So a session-4 desk refuses a session-5 show that carries overrides** — intended: every v24
  desk either applies them or refuses the show, and none silently drops one. A show with none writes
  no folder and still syncs with a session-4 desk.
* **A venue syncs as its own repo** of tagged versions — §"Venue repos".

Why the number moves: v4's reason, twice. A v23 reader would import a pinned show as a standalone one
whose links name rows it does not have (and fail on the first, or import a rig with holes), and its
next wipe-then-export push would write the pin and every hide away.

**A pull checks the pin before it moves the tree.** Beside the pre-pull `formatVersion.json` check,
`RemoteSyncEngine` reads the fetched `project.json` and refuses — before `resetHard` — a remote of
another kind than the local project (`PROJECT_MISMATCH`), a show pinned to a version this desk lacks
(`SyncErrorCode.VENUE_MISSING`), and a remote holding a reserved folder (`FORMAT_TOO_NEW` — none
since session 5, which reads `showOverrides/`). Moving the
tree first would let the next snapshot write the old pin back over the remote's.
`replaceFromWorkingTree` takes the pin from the tree (a peer's Update moves it, venue plan session 6),
refuses an archive of another kind, sweeps the show's hides, facet overrides and recolours, and now also deletes a show's cue events and scenery **through
their owners** (cue, stack, Look) — they may name venue patches and elements, which the old sweep
through the project's own patches and elements never reached, leaving orphans with
`PRAGMA foreign_keys` off. The project delete takes the same sweep.

**A clone keeps the pin.** `ProjectCloner` is export → fresh uuids → import as before: the remapper
mints a uuid for every identity in the archive — the show's own rows and its hides' own `uuid` — and
the venue's records are no identity of the show's archive, so every link into the venue (and a hide's
or an override's `recordUuid`) keeps naming the venue's records. An override's value is remapped by the
same blind substitution: a show-owned rigging or universe it names becomes the clone's copy, a venue's
is kept. The clone is a show on the same version, loading the
same rig. The archive carries `kind`, so the importer takes it from there (a venue's clone is still a
venue with no versions). `VENUE_CLONE_PENDING` is gone.

**A detached show is a standalone show** (venue plan session 7, `docs/fixtures-engineering.md`
§"Detach"), and needs no `formatVersion` change. Detach writes the venue's rig into the show's own
rows, uuids kept, and clears its pin, so the next export is the v24 layout of a standalone show: no
`venueUuid` / `venueVersion` / `venueRepo` in `project.json`, no `showHides/` or `showOverrides/`, the
venue's records among the show's own. A peer still pinned pulls it as any change: `mergeProjectPin`
takes the remote's pin (none) when its own is the merge base's, `replaceFromWorkingTree` writes the
show's rows and the empty pin and sweeps its hides, overrides and recolours, and the importer seeds no maps from
a snapshot because the archive names none. A desk that never had the venue imports it as any
standalone show (`VenueRepoSyncTest`). The venue's repo is not touched.

**A detached show records its origin** (venue-attach plan D1, §4.3): `project.json` gains
`detachedFromVenueUuid` and `detachedFromVersion`, the venue and the version it was detached from,
both optional and omitted when absent, and written only for a standalone show (a pinned show's pin
stands in their place, and an importer reads neither for one). `mergeProjectPin` merges them with the
pin, as one value, since Detach writes both in one transaction. The venue uuid names a project outside
the archive, so `ExportUuidRemapper` leaves it alone and a clone keeps it. An importer that finds only
one of the two, or a uuid that does not parse, reads no origin rather than failing: the origin only
steers an attach's matching.

**What a loaded show does with a missing snapshot.** Every way in refuses one, and a venue a show
pins refuses delete, so it takes a hand-edited database. Then `RigScope` still answers the show's own
rows (its lists and writes work), but nothing loads it: `ProjectManager.switchProject` refuses it with
`VENUE_MISSING` (the set-current route, and MCP's `switch_project`, answer 409), and a desk that
**starts** with one current loads **no rig** — no fixture, no controller — logging the refusal, so
the desk comes up, outputs nothing, and can switch away. Refusing to output is better than outputting
the show's additions without the rig they sit on.

`RichProjectFixture` stays a standalone show; the venue half is `testsupport/VenueTestSupport.kt`'s
`seedShowOnVenue` — the rich project extracted into a venue, a v2 published, and the show given a
hide, an addition on a venue universe, a show group holding a venue patch, and (session 5) three
facet overrides of one venue patch — Address, Position on a show-owned boom, Media — every value
non-default. `SyncCoverageTest` exports both. `ProjectRoundTripTest` round-trips the show (its
overrides with it), round-trips a venue's versions through its repo onto a fresh desk (and refuses the
show there until the venue is in, `VENUE_MISSING`), imports a v23 archive and imports an archive
holding `showOverrides/`; `ProjectCloneTest` clones the show; `VenueRepoSyncTest` covers the venue
repo between two desks.

### Version 23 — painted cloths

Bumped when the images painted on scene cloths began travelling in the repo (scrim plan session 1,
P2 — `docs/plans/completed/scrim-plan.md`). `SUPPORTED_FORMAT_VERSION = 23`; `MIN_SUPPORTED_FORMAT_VERSION`
**stays 5**, so a v23 install still reads every earlier repo: a missing folder is no images, and
every key defaults. The writer emits `formatVersion = 23`.

Three things land together under the one number, so no later scrim session touches sync:

* a drape's `fabric` (`CANVAS · MUSLIN · SHARKSTOOTH · BOBBINET`, absent velour) and a drape's or a
  flat's `paint` (`{front?, back?}`, each an image's SHA-256), as keys of the element's `params`,
  which `StageElementJson` already carries whole — no DTO changes;
* a new top-level `sceneImages/{sha256}.{png|jpg}` directory holding the raw image bytes. There is
  **no JSON DTO** — the file is the content, keyed by its own SHA-256, which is what a `paint` side
  names;
* the `.gitattributes` rule `sceneImages/** binary`.

The params keys alone would need no bump (§"A params key added later needs no bump" below). The
folder does, for v4's reason exactly: a v22 install lacks the wipe-preserve and reconcile logic, so
on its next snapshot it would delete `sceneImages/` and **revert every image onto its peers** — while
re-exporting the elements, keys intact, that name them. Emitting v23 makes such an install refuse the
repo as too-new instead.

The images follow the prompt-book PDFs' contract (§"Version 4") point for point, through
[`SceneImageRepoSync`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SceneImageRepoSync.kt):

* **Only referenced images travel.** The referenced set is every hash a stage element's
  `params.paint` names, and since v25 every `params.parts[].texture.image`
  (`SceneImageRepoSync.referencedHashes`, read leniently with `sceneImageHashesOf`), so an upload
  whose element was never saved stays on the machine that made it
  until the store's prune takes it.
* **store → tree** (`reconcileTree`) on every export and snapshot, and on the auto-merge path after
  the DB is merged: copies each referenced image the tree lacks, deletes every file whose hash is no
  longer referenced (or whose name is not an image's), and **never deletes a referenced hash's file
  even when the store lacks the bytes**, so a store-less install cannot drop the repo's copy.
* **tree → store** (`hydrateStore`) on every pull, after a fast-forward or a merge, and on manual
  import — which is also what fills a **clone's** store, since cloning is export → import
  (`ProjectCloner` needs nothing of its own). The store takes a file only when its bytes are what its
  name says: a PNG or a JPEG whose SHA-256 is the name, at most 25 MB. A file that is not is logged
  and skipped; the element then reads "Image missing on this machine" and draws unpainted.
* **Binaries never touch the text machinery.** `RecordHasher.BINARY_DIRS` is the one list of the
  two binary folders: the record scan, `JGitClient.walkTree` and `ExportUuidRemapper` skip both, and
  `SyncWorkingTree.cleanTrackedFiles` preserves both through the snapshot wipe. The binary
  `.gitattributes` rule is **back-filled** onto existing repos like the PDFs' was.
* **Content-addressed, so git merges them trivially** — a path always holds the same bytes, so an
  image is only ever added or removed, never conflicted, and needs no UUID remap on clone.

What does **not** travel: the derived copies (the 2048 and 4096 px display copies and the 256 px
mask, made per machine on first request) and an element's *Full detail* switch, a
`machine_overrides` row (§"Machine-local data"). `RichProjectFixture` paints a muslin drape front
(a PNG with holes) and back (a JPEG) and a flat front, the images made in code; `ProjectRoundTripTest`
asserts the images' bytes survive an export, a wiped store and an import, and that a v22 archive
still imports. No table is added, so `SyncCoverageTest`'s dispositions are unchanged.

### Version 22 — fitted media

**v22 adds one optional field in two places** (fixture optics plan session 3, D6; P2 is the plan's
one bump): `FixturePatchJson` and `PatchPlacementJson` each carry `media` — what a unit has loaded in
its loadable settings, `docs/fixtures-engineering.md` §"Fitted media". It is null by default and
omitted then, so a rig with nothing fitted exports byte-for-byte as at v21. `RichProjectFixture`
patches a Revolution (`rev-1`) whose patch fits a gel, a gobo and an empty frame and whose placement
fits its own frame and a media-frame gel, all off the default. Two things about the shape:

- **`media` travels as a nested object**, `{slots: {<attribute>: {<option>: {gel?, gobo?}}}}`, not as
  the JSON text the column stores, so a diff reads per slot; canonical JSON sorts its keys like any
  other object's. An empty slot travels as `{}` — fitted with nothing, which is not the same as no
  entry — and is kept. A column that will not parse exports as nothing fitted, the tolerance every
  reader of it has.
- **A placement's media is its own**, layered over the patch's option by option when it is drawn,
  so the round trip carries each placement's as it stands; nothing is flattened into the patch.

**Imported as stored**: neither the gel library nor the fixture type is consulted, so a gel a newer
desk's library holds — or a slot a newer definition declares — survives a round trip through this
one, and the unit draws that slot as its stock. The write boundary checks what an operator or a model
writes, not what an archive carries.

**It bumped `formatVersion`** by the sharp-edge rule, v19's reason exactly: a v21 reader ignores the
key, imports every unit with the type's stock string, and its next wipe-then-export push rewrites the
patch files without it — reloading every peer's scrollers with ETC's stock string. `minReader` stays
at 5: missing media is the stock. No table is added (`media` is a column on two portable tables), so
`SyncCoverageTest`'s dispositions are unchanged; cloning carries it because the exporter does.

### Version 21 — cue events

**v21 adds one list in one place** (stage-view plan session 9, D15, D16): `CueJson` carries `events`,
a list of `CueEventJson` — `uuid`, `patchUuid`, `trigger` (the trigger's name on its fixture type,
`output1`), `offsetMs` and `sortOrder`. The rows are the `cue_events` table, embedded in their cue
as its scenery is, so `SyncCoverageTest` records it `Portable("cues", "events")`, and
`RichProjectFixture` patches a Twin Shot (`cannon-1`) and gives a cue two events, both off the
default offset and the second off the default sort order. An empty list is omitted, so a project with no
cannon cues exports byte-for-byte as at v20; an event at GO omits its `offsetMs` as a zero sort order
is omitted. Three things about the shape:

- **The patch is a uuid on the wire and a foreign key in the table.** Patches already import before
  cues, so each cue's events resolve their patch directly; the clone remapper re-points `patchUuid`
  like any other uuid string. An event is **dropped with a warning**, the cue kept, when the archive
  lacks its patch (the patch's delete would have swept it on the writing desk), when the patch's type
  has no such trigger, or when it names a tube the cue already fires (one row per tube, a unique
  index). Its offset is clamped into 0–10 minutes.
- **The record is the event, never its firing.** Whether a tube is **spent** is the physical cannon
  in this hall, not the project — `effect_tube_state` is machine-local (§"Machine-local data") — and the
  desk's **arm** is runtime only. Neither travels, and a restart disarms.
- **An import strips any row naming a trigger.** Until v21 the Twin Shot's `output1`, `output2` and
  `master` were plain sliders, so a v20 archive may hold a Look, palette or cue row (or an FX)
  raising one; every import ends with `stripTriggerRows` over the imported project, the same pass the
  one-off startup strip ran, and logs how many went. A trigger can no longer be stored at all
  (`TRIGGER_NOT_STORABLE`), so the pass has nothing to find in a v21 archive.

**It bumped `formatVersion`** by the sharp-edge rule: a v20 reader imports every cue without its
events, and its next wipe-then-export push writes them away for every peer. `minReader` stays at 5:
missing events are none. **It went without `FU-AUTH-ATTRIBUTION`'s columns, as v18–v20 did** (stage-view
plan §11 Q2, passed again on Chris's instruction for this session): attribution still needs its own
design for machine-local users, and this was the plan's last bump to fold it into, so the gate now
waits for its own trigger rather than a planned bump.

### Version 20 — scenery on cues, stacks and Looks

**v20 adds one list in three places** (stage-view plan session 8, D11–D13): `CueJson`,
`CueStackJson` and `LookJson` each carry `scenery`, a list of `SceneryChangeJson` — `uuid`,
`elementUuid`, `state` and `sortOrder`, and on a cue `transitionMs`. The rows are the
`cue_scenery`, `cue_stack_scenery` and `look_scenery` tables, embedded in their owner as a Look's
rows are (an owner says one thing about each element, and the change is part of the owner's
record), so `SyncCoverageTest` records them `Portable("cues", "scenery")`, `("cueStacks",
"scenery")` and `("looks", "scenery")`, and `RichProjectFixture` seeds two on each owner with every
optional field off its default. An empty list is omitted, so a project with no scenery exports
byte-for-byte as at v19. Three things about the shape:

- **The element is a uuid on the wire and a foreign key in the table.** The importer brings
  elements in **first** now — they reference nothing that has to precede them (a platform's region
  is a uuid inside its params) — so each owner's import resolves its changes' elements directly. A
  change naming an element the archive lacks is dropped with a warning: its element's delete would
  have swept it on the writing desk (`deleteSceneryForElements`), and the column cannot dangle. The
  clone remapper re-points `elementUuid` like any other uuid string.
- **`state` travels as a nested object** (`visible`, `open`, `trimM`), not as the JSON text the
  column stores, and is stored as the archive holds it, unparsed; every reader tolerates one it
  cannot read, and the resolver ignores a state the element's kind no longer takes.
- **`transitionMs` is a cue's only** — its own clock for the move; absent moves with the cue's
  fade. A stack's set and a Look's scenery have none.

**It bumped `formatVersion`** by the sharp-edge rule: a v19 reader imports every cue, stack and Look
without its scenery, and its next wipe-then-export push writes it away for every peer. `minReader`
stays at 5: missing scenery is none. It went without `FU-AUTH-ATTRIBUTION`'s columns, as v18 and v19
did (stage-view plan §11 Q2) — the plan's last bump, 21, is still there to fold it into.

### Version 19 — lanterns and focus

**v19 adds seven optional fields in two places** (stage-view plan session 7, D9 and D14):
`FixturePatchJson` and `PatchPlacementJson` each carry `lanternType` (a lantern id from the desk's
library, `docs/fixtures-engineering.md` §"Lanterns and focus"), `zoomDeg`, `lampRotationDeg`,
`shutters`, `gateRotationDeg`, `iris` and `focusSoftness`. Every one is null by default and omitted
then, so a project that names no lantern exports byte-for-byte as it did at v18. Two things about
the shape:

- **`shutters` travels as a list of four objects** — `{depth, angleDeg}`, top · bottom · left ·
  right — not as the JSON text the column stores, so a diff reads per blade. The exporter parses the
  column and the importer writes it back as compact JSON; a column that will not parse exports as
  no shutters, the same tolerance every reader of it has.
- **A placement's fields are its own.** A null `lanternType` there means *the fixture's lantern*,
  but the six focus fields are never inherited — each lantern of a pair is focused separately — so
  the round trip carries each placement's values as it stands.

**`kindOverride` is unchanged on the wire** but is now derived at the write boundary from a patch's
lantern (a Source Four makes it a `PROFILE`); the importer writes it verbatim, as it writes every
record, and does not re-derive it — the archive already holds what the writing desk derived.

**It bumped `formatVersion`** by the sharp-edge rule, for v17's reason: a v18 reader ignores the
keys, imports every unit as its kind's default lantern with no focus, and its next wipe-then-export
push rewrites the patch files without them — unfocusing every peer's rig. `minReader` stays at 5: a
missing lantern is the kind's default and missing focus is open. A lantern id this desk's library
does not hold (an archive from a newer desk) is imported as stored and drawn as the kind's default.
It went without `FU-AUTH-ATTRIBUTION`'s columns, as v18 did (stage-view plan §11 Q2).

### Version 18 — the scene document

**v18 adds two record folders** (stage-view plan session 2, D2 and D6): `stageElements/{uuid}.json`,
the named venue and set elements the Stage view draws, and `stageViewpoints/{uuid}.json`, the saved
views and seats. Both tables are portable — the venue is project content, and "Row F" is the venue's,
not this Mac's — so both are wired through the exporter and importer, `SyncCoverageTest` records
them `Portable`, and `RichProjectFixture` seeds a seating, a platform and a drape with every optional
column off its default.

Three things about the shape:

- **An element's `params` travels as a nested object**, not a string. The canonical encoder sorts its
  keys like any other object's, a diff reads per field, and — the reason it had to be an object — the
  clone remapper's blind uuid substitution reaches a platform's `regionUuid` inside it. The desk
  stores it as canonical compact JSON (`models/stageScene.kt`); the importer stores what the archive
  holds **without reparsing it**, as it stores every other record verbatim, and every reader of the
  params tolerates a document it cannot read (a seating that will not parse simply has no seats).
- **A seat view names its seating by `seatElementUuid`**, a uuid rather than an FK, like every other
  cross-record reference here. Elements are imported before viewpoints, and a reference the archive
  lacks is kept and dangles; a reader treats it as no seat, and the Stage view's picker lists such a
  view disabled.
- **Pose and size default to 0 and are omitted then**, so a seating (whose size comes from its rows)
  carries no `widthM` / `depthM` / `heightM` at all.

**A params key added later needs no bump**, because of the first point. `travelS` (scenery-programmer
plan D6, P2; written at v22) is an optional key on a `DRAW`/`FLY` drape's and a flown object's params —
the seconds for the piece's full travel. Absent it is omitted, so a scene without one exports as before;
an older desk stores the params it receives verbatim, decodes them with `ignoreUnknownKeys` and so
keeps a `travelS` it does not read, and writes it back unchanged on its next export. It cannot
*edit* such an element, though: its own write boundary (`parseElementParams`) refuses the key it does
not know (`unknown field 'travelS'`), from its form and its `set_scene` alike, until it is updated —
mixed-version editing, not sync. It is portable —
how long the venue's tabs take to draw is the venue's — and `RichProjectFixture`'s house tabs carry
one, so the round trip and the clone test it. A drawn drape's `drawFrom` (`STAGE_LEFT · STAGE_RIGHT`,
a one-way traveller's side; absent is bi-parting) rides the same way, and the house tabs carry that too.
The programmer's scenery (`state/ProgrammerScenery.kt`)
is runtime only and never reaches an archive.

**It bumped `formatVersion`** by the sharp-edge rule: a v17 reader ignores both folders, imports every
project with no scene, and its next wipe-then-export push writes none back — deleting every peer's
venue. `minReader` stays at 5: both folders read as empty when missing, so every older archive
imports with an empty scene (`ProjectRoundTripTest` pins that).

**It went without `FU-AUTH-ATTRIBUTION`'s columns** (stage-view plan §11 Q2, passed on 2026-09-29):
attribution needs its own design for machine-local users, and the plan's three later bumps (19–21)
are still there to fold it into.

### Version 17 — fixture roll

**v17 adds one optional field in two places.** `FixturePatchJson.baseRollDeg` and
`PatchPlacementJson.baseRollDeg` are a body's roll about its local Z — the third turn of
its `YXZ` Euler, which is what stands a lightstrip on end (a ring's upright side), since yaw and
pitch alone keep its length level (`docs/fixtures-engineering.md` §"Per-patch fields"). Both are
null by default and omitted then, so a project with no roll exports byte-for-byte as it did at v16.

**It bumped `formatVersion`** by the sharp-edge rule, for v15's reason: a v16 reader ignores the
key, imports every rolled body lying flat, and its next wipe-then-export push rewrites the patch
file without it — laying every peer's ring down again. `minReader` stays at 5: a missing roll is 0.

### Version 16 — head numbers

**v16 adds one optional field.** `FixturePatchJson.headNumber` is the operator's number for a head —
a ChamSys head number carried across a migration (`docs/fixtures-engineering.md` §"Head numbers").
Null by default and omitted then, so a project with no numbers exports byte-for-byte as it did at
v15.

**It bumped `formatVersion`** by the sharp-edge rule, for v15's reason: a v15 reader ignores the
key, imports every patch unnumbered, and its next wipe-then-export push rewrites every patch file
without it — unnumbering every peer's rig. `minReader` stays at 5: a missing number is unnumbered.
The importer writes the number as stored, **without a uniqueness check**: numbers are unique per
project only at the write boundary, and a merge of two peers' renumbers must import rather than
refuse the project. The patch list rings a shared number so the operator can fix it.

### Version 15 — variable-length fixtures

**v15 adds one optional field in two places.** `FixturePatchJson.lengthM` is a variable-length
fixture's installed length (a lightstrip cut to its run — `docs/fixtures-engineering.md`
§"Variable-length fixtures"), and `PatchPlacementJson.lengthM` is one side's own length when the run
is laid in segments (a ring round the stage edge: one patch, a placement per side). Both are null
by default and omitted then, so a project with no lengths exports byte-for-byte as it did at v14.

**It still bumped `formatVersion`**, by the sharp-edge rule: a v14 reader ignores the key, imports
the strip at its type's default length, and its next wipe-then-export push rewrites the patch file
without it — shortening every peer's ring back to a metre. That is a degraded record written back,
so a v14 install must refuse a v15 repo. `minReader` stays at 5: a missing length is the default.
The importer writes the value as stored and does not check the type; the write boundary is where a
length on a fixed-length type is refused, and the stage views ignore one that arrives anyway.

### Version 14 — paired placements

**v14 adds one embedded array.** `FixturePatchJson.extraPlacements` carries a patch's other
lanterns — a paired dimmer driving an SL and an SR unit from one address — from the
`fixture_patch_placements` table (`docs/fixtures-engineering.md` §"Extra placements (paired
dimmers)"). Each entry is `{uuid, label?, riggingUuid?, stageX?, stageY?, stageZ?, baseYawDeg?,
basePitchDeg?}`, in list order, so the table's `sort_order` is not on the wire. The entry's own
`uuid` is an identity and `riggingUuid` a reference, so a clone's `ExportUuidRemapper` rewrites
both with no change of its own; the importer resolves the rigging against the archive's
`riggings/` and refuses an archive naming one it lacks, as it does for the patch's own
`riggingUuid`.

The list is omitted when empty, so a project with no pairs exports byte-for-byte as it did at v13.
**It still bumped `formatVersion`**, by the sharp-edge rule above: a v13 reader ignores the key,
imports a paired patch as a single lantern, and its next wipe-then-export push rewrites the patch
file without the list — deleting every peer's lanterns. That is a degraded record written back,
so a v13 install must refuse a v14 repo. `minReader` stays at 5: a missing list is an empty one.

### Version 12 — the busk rig

**v12 adds one folder.** `buskRig/{uuid}.json` is the busk view's target band as the operator
built it (`docs/plans/completed/busk-further-plan.md` §3.1–3.2,
`docs/lighting-composition-model.md` §"The rig"): one document per **row** with its tiles nested, the way a page carries its columns, so a drag
on the band changes one file. A tile names its group or patch by uuid as `groupUuid` / `patchUuid`,
exactly one set — the `BuskPadJson` pattern, two-armed — plus `cellMode`, `cellSplit`, `label` and,
for a tile that is one element, its `elementKey`, carried **verbatim**: element keys are opaque, the
write boundary validates one against the live fixture on the next write, and the importer cannot
(the project being imported is not the live show).

The plan's first draft put the rig in a single top-level `buskRig.json`. Session 2 amended that
before writing it, because `RecordHasher` treats every top-level file as metadata and filters it
out of the record scan: a rig outside `{table}/{uuid}.json` would export and import but never
propagate through the three-way diff, so a rig built on one desk would silently never reach
another. A record folder needs no special case anywhere — hashing, tombstones,
`ExportUuidRemapper` and `SyncCoverageTest` all see it as they see `buskPages/`.

`minReader` stays at **5**: a missing folder reads as an empty rig, which is today's band (D1), so
every older archive imports untouched. The writer's number moves for v10's reason: a rig changes
what the band offers and what *Next* walks, so a v11 reader — which would import no rig and on its
next wipe-then-export push write none back, deleting every peer's — must refuse the repo instead.

Three importer details. The rig imports **after** the patches and groups its tiles name, and the
replace path sweeps the existing rig **before** it deletes the existing groups and patches — a tile
is a plain FK onto both with no cascade, so the old order would block them. A tile whose group or
patch the archive does not carry, or which names none or both, **warns and is dropped** while the
row imports without it (the pad's posture: a tile is an enrichment of its record); a row left with
no tiles is dropped too, because the write boundary refuses an empty row and one must never be read
back. A `cellMode` the desk does not know falls back to `PIPS` rather than losing the tile.

### Version 11 — tolerant binding payloads, and uuids beside the ints

**v11 changes no folder and no field.** `controlSurfaceBindings/{uuid}.json` still carries
`targetPayload` as the opaque string it always was; what moves is what that string may *contain*,
in two ways.

New `type` discriminators. Session 1 of `docs/plans/completed/midi-surface-plan.md` adds the
selection-relative targets (`selectionProperty`, `selectTarget`, `clearSelection`,
`locateSelection`), and later sessions add more (strips, the encoder bank, records on buttons).
Rather than bump the writer for each, the **reader** changed: `ControlSurfaceBindingService`
decodes each row on its own and keeps an undecodable one as a `BindingTarget.Unknown` — health
`unknownTarget`, dead in the list, rebindable, and re-written byte-for-byte — instead of failing
the whole project load on the first discriminator it does not know. That is what lets `minReader`
stay at **5**: a v11 archive read by a v11 desk from before a later session loads with those rows
dead and everything else live.

Uuids beside the ints. `fireCue` carries `cueUuid` beside `cueId`, and `cueStackGo` / `Back` /
`Pause` carry `stackUuid` beside `stackId`. The service fills the uuid on every create and update
(the REST picker and MIDI Learn still address rows by int), dispatch and health resolve by the uuid
alone when one is present, and `ExportUuidRemapper`'s blind substitution rewrites it on a clone —
so a cloned or cross-install-imported project keeps its cue bindings, which is the first half of
`FU-SYNC-BINDING-PAYLOAD-UUIDS`. A pre-v11 row with no uuid is dispatched by its int exactly as
before. The int is kept for one version so the tolerant decode never sees a row it cannot read;
dropping it is a later `formatVersion`.

The writer's number moves because a v10 reader had neither tolerance: one new discriminator failed
its whole binding load (and, because `ensureLoaded` never marked the project loaded, retried the DB
read on every MIDI event), so it must refuse the repo rather than import a project whose surface
is dead.

### Version 10 — busk pages and Look slots

**v10 adds one folder and one field.** `buskPages/{uuid}.json` is the busk layout
(`docs/plans/completed/busk-layout-plan.md`): one document per page with its columns, banks and pads
embedded inline the way a Look carries its rows, so a drag changes one file. A pad names its
palette, Look or cue by uuid as `templateUuid` / `lookUuid` / `cueUuid`, exactly one set — the
`CueLayerJson` pattern, three-armed. And `CueSlotJson.lookUuid` joins `cueUuid` on a cue slot: a
Look, pressed whole.

`minReader` stays at **5**: a missing folder reads as empty and the field defaults to null, so
every older archive imports untouched. The writer's number moves for v9's reason, verbatim: a
layout changes what a busk press *does* on stage (a solo bank releases its siblings), so a v9
reader — which would import no pages, never read the folder, and on its next wipe-then-export push
write none back, deleting every peer's pages — must refuse the repo instead.

Three importer details. Pages import **after** palettes, Looks and cues so every pad resolves in
one pass. A pad whose record the archive does not carry, or which names none or two, **warns and is
dropped** while the page imports without it, because a pad is an enrichment of its record (a place
on a page), not content; the stored sort orders are kept as written, and the gap a dropped pad
leaves is harmless because every reader sorts rather than indexes. A cue **slot** naming *two* arms,
or a Look the archive lacks, still **aborts** — a slot claiming to be two things is malformed input,
not a state. A slot naming **neither** is the one lenient case, and it is the removal below:
`ignoreUnknownKeys` turns a pre-removal stack slot into a slot with no arm, so that one warns and
drops with the pad's reasoning (a slot, like a pad, is an enrichment of the record it names).
`ExportUuidRemapper` needs no change: every reference is a `{table}Uuid` string and every identity a
`uuid`, so a clone's pads point at the clone's records by construction.

The structural fields of a page (`row`, `sortOrder`, `width`, `name`, `solo`, `flow`) have **no
defaults** in their DTOs, so canonical JSON always writes them — a layout is positions, and a
position omitted for being zero would read as a document with holes. Only the three pad references
default.

**Session 3 of the same plan removed, under this same number**: `templateGroups/`,
`PaletteJson.groupUuid` and `sortOrder`, `LookJson.sortOrder`, `CueJson.pinnedToBusk` and
`CueSlotJson.cueStackUuid`. No v11, because nothing but the dev desk wrote v10 between the two
sessions, so no peer could hold a repo the later reader would import wrong and push back.

A v10 archive written *before* that session may still carry `cueStackUuid` on a slot. The key is
unknown now, `ignoreUnknownKeys` drops it, and the slot arrives naming nothing — so the importer
**warns and drops that slot** rather than failing the pull. That is the pad's rule applied to a
slot, and it is why the arity check splits: two arms aborts, none warns.

### Version 9 — palette groups *(removed at v10)*

> Palette groups no longer exist: v10's session 3 deleted the folder, the field and the feature
> when the busk page took over ordering and exclusivity. Kept as the record, the way §"Version 7"
> keeps the retired `palette` story — a reader meeting a v9 archive still imports it, ignoring the
> folder and the key.

**v9 was a second instance of the v6 shape**: a new folder, `templateGroups/{uuid}.json` (name and
top-level `sortOrder` only), plus one optional reference on the palette, `PaletteJson.groupUuid`.
Membership lives on the palette rather than in a member list on the group, so a group document
never changes when a palette joins or leaves it — one record moves per drag, not two.

`minReader` stays at **5**: a missing folder reads as empty and the field defaults to null, so every
older archive imports untouched. Only the writer's number moves, and the reason is the one the v8
note above frames as the real question — *would an older reader import this record wrong, and then
write its mistake back?* A v8 reader would import every palette ungrouped and never look at the
folder; its next push is wipe-then-export, which writes no `templateGroups/` and no `groupUuid`,
deleting every peer's groups. That is not the SpeedMaster "null is a valid state" case: a group
changes what a busk press *does* (its siblings release), so silently dropping one changes behaviour
on stage. Bumping the writer makes a v8 install refuse the repo, which is the only outcome that
loses nothing.

Two importer details, both deliberate: groups import **before** palettes so the reference
resolves in one pass, and a `groupUuid` naming a group the archive does not carry **warns and
imports the palette ungrouped** rather than aborting the pull — a group is an enrichment of the
palette (its place and its siblings), not its content, so a palette that has lost its group is
still a whole palette. Contrast a cue layer, which is nothing without its source and does abort.
`ExportUuidRemapper` needs no change for the same reason it needed none at v8: `groupUuid` is a
uuid string, so a clone's palettes point at the clone's groups by construction.

### Version 8 — a palette may hold an FX

**v8 is the worked example of an optional field that still needs a bump.** `template_effects`
travels inline as `TemplateJson.effect: TemplateEffectJson? = null` (now `PaletteJson.fx`) — one new optional field with a
null default, which the rule of thumb above would wave through.

It bumps anyway, because that field is the *record's whole content* rather than an enrichment. A
palette holds values **or** one FX (fx-templates D1), so an FX palette has no rows at all:
a v7 reader would import it as a hollow, rows-less palette — one its own write boundary would
refuse — and then write that hollow record back over the FX on its next push. `minReader` stays
at **5**, since every older archive still imports untouched; only the writer's number moves, which
is exactly the v6/v7 shape.

Nothing else in the sync layer needed changing, and that is worth recording: `ExportUuidRemapper`
collects identities from *any* field named `uuid` at any nesting depth and substitutes blindly
across the JSON text, so the nested `effect.uuid`, both speed-master references, and a `tmpl:{uuid}`
colour reference **inside the `parameters` map** are all re-pointed on clone with no field-aware
code. A new inline child is remapped correctly by construction.

### Version 6 — palettes as their own entity

**v6 is the worked example of a one-directional break.** Palettes got their own tables, so the
export gained a `templates/` folder and `CueLayerJson.lookUuid` became *optional* beside a new
`templateUuid` — exactly one of the two is set.

Only `formatVersion` moved; **`minReader` deliberately stayed at 5**. A v5 repo still imports
unchanged: it has no `templates/` folder (a missing directory reads as empty) and every one of its cue
layers carries a `lookUuid`. What the bump buys is the *other* direction — a v5 install must refuse a
v6 repo, where a cue layer may carry `templateUuid` alone and a v5 reader would take `lookUuid`'s null
straight into `UUID.fromString`. That is a **Java platform type**, so Kotlin permits the null with no
compile-time stop and it fails as a bare NPE naming nothing. The importer's own null check is written
out longhand for that reason.

### Version 2 — FOH stage geometry

Bumped when the FOH 3D stage view landed. Additive changes only; `minReader`
stayed at `1` because v1 readers tolerate the new optional fields via
`ignoreUnknownKeys`. Specifically:

* `FixturePatchJson` gained `stageZ` (depth, metres), `baseYawDeg`,
  `basePitchDeg` — see `docs/fixtures-engineering.md` for the FOH-relative
  coordinate system. The pre-existing `stageX` / `stageY` fields are
  reinterpreted as **metres** in the same coordinate system; before v2 they
  were a UI-internal 0–100 percentage.
* `ProjectJson` gained `stageWidthM`, `stageDepthM`, `stageHeightM` —
  per-venue stage bounds for the renderer.

Reinterpreting `stageX` / `stageY` is the closest thing to a semantic break
in v2; bumping `formatVersion` makes that explicit even though the wire
shape is technically backward-compatible. There is no live data migration —
the project repo this lighting rig was bootstrapped against had only test
coordinates, and they are expected to be re-placed in metres.

### Version 3 — Riggings, stage regions, Z-up

Bumped when riggings became a first-class entity, stage regions arrived, and
the coordinate system flipped from Y-up to Z-up. Both `SUPPORTED_FORMAT_VERSION`
and `MIN_SUPPORTED_FORMAT_VERSION` are 3 — older repos are **rejected on
import** (HTTP 422 with a "re-export from a newer install" message). No
graceful v2-to-v3 migration code: this project hasn't shipped beyond the dev
box, so backwards-reader plumbing would be dead weight. (Once the project
runs on a real lighting console, the next breaking bump should add a
v3-to-v4 reader instead of repeating this shortcut.)

Wire-format changes:

* `FixturePatchJson` lost `riggingPosition` (the legacy free-text string)
  and gained `riggingUuid` (FK to a Rigging by UUID).
* `FixturePatchJson.stageY` / `stageZ` swap meaning — `stageY` is now depth
  (was height), `stageZ` is now height (was depth). Same field names,
  different axes.
* New `RiggingJson` table — pose (position + yaw/pitch/roll), name, optional
  `kind` advisory label.
* New `StageRegionJson` table — rectangular platforms describing the
  playable surface beyond the project bounding box.

Local DB migration (removed): `State.migrateRiggingsV3` ran on PG installs that
still had a `rigging_position` column — it went with the PostgreSQL-gated
migrations, before the 2026-08-24 removal of the rest. It read each distinct legacy string, minted
a Rigging row per `(projectId, riggingPosition)` pair (name = the legacy
string, geometry null), updated `fixture_patches.rigging_id` to point at the
new row, swapped `stage_y` ↔ `stage_z` on every patch (Y-up → Z-up), and dropped
the `rigging_position` column. Idempotent — gated on the column's existence.

On import (manual fresh-import or post-pull replace), an export whose
`formatVersion` is greater than `SUPPORTED_FORMAT_VERSION` is rejected (HTTP 422);
same if it is less than `MIN_SUPPORTED_FORMAT_VERSION`. On pull, the remote
`formatVersion.json` is read straight from the fetched git ref **before** the
working tree is touched (see "Pre-pull formatVersion check" under
[Remote sync (Phase 4)](#remote-sync-phase-4)) so a too-new repo can't corrupt
the local working tree. When Phase 5+ migrations land, they live at
`sync/migrations/V{n}_to_V{n+1}.kt` and run before the importer's three-way diff.

### Version 4 — Prompt-book PDF binaries

Bumped when the script PDF a prompt book is bound to began travelling in the
repo. `SUPPORTED_FORMAT_VERSION = 4`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 3**
so a v4 install still reads pre-v4 (JSON-only) repos. The writer always emits
`formatVersion = 5`.

The bump is deliberately a hard gate rather than a graceful additive change: a
pre-v4 install lacks the wipe-preserve/reconcile logic below, so on its next
snapshot it would delete `promptScripts/` and **revert the PDF onto every peer**.
Emitting v4 makes such an install refuse the repo as too-new (the same
`formatVersion > SUPPORTED` check used everywhere else), which is the protection.
For that to work the value must actually be on disk: `FormatVersionJson`
force-encodes both fields with `@EncodeDefault(ALWAYS)`, because the canonical
encoder's `encodeDefaults = false` would otherwise omit them (serialising the
whole object to `{}`) and every reader would fall back to its own compiled-in
default — silently defeating the gate. This also fixes that latent gap for good.

Wire-format / repo-layout change: a new top-level `promptScripts/{sha256}.pdf`
directory holding the raw PDF bytes. There is **no JSON DTO** — the file is the
content, keyed by its own SHA-256 (which is also the book record's `scriptHash`).

**Binaries never touch the text machinery.** PDFs are content-addressed and
immutable, so they are *not records*: they're excluded from `RecordHasher`, the
three-way diff, and `JGitClient.walkTree` (which UTF-8-decodes blobs and would be
lossy — and, at up to 100 MB each, expensive). They move only as raw bytes via
[`PromptScriptRepoSync`](../src/main/kotlin/uk/me/cormack/lighting7/sync/PromptScriptRepoSync.kt):

* **store → tree** (`reconcileTree`) — on every export/snapshot, and on the
  auto-merge path after the DB is merged. Copies the referenced hash's PDF from
  the local store into `promptScripts/`, deletes orphaned hashes (a `scriptHash`
  change or a deleted book), and — critically — **never deletes a referenced
  hash's file even if the store lacks the bytes**, so a store-less install can't
  drop the repo copy. The merge call is what puts the *winning* side's PDF into
  the merge commit, since the record overlay only writes JSON.
* **tree → store** (`hydrateStore`) — on every pull (from the real git checkout,
  post-`resetHard`) and on manual import (from the export folder). Copies
  `promptScripts/*.pdf` into the local store so the book renders without a manual
  re-import.

Supporting pieces:

* `promptScripts/` is exempt from the snapshot **wipe**
  (`SyncWorkingTree.isPreserved`) — see the no-data-loss rule above. Unchanged
  PDFs keep their mtime, so `stageAll` treats them as no-change and they aren't
  re-committed.
* `.gitattributes` gains `promptScripts/** binary` so git skips EOL
  normalisation and textual diffing. The rule is **back-filled** onto
  already-initialised repos (`writeMetadataFiles` appends it if missing).
* `walkTree` prunes the `promptScripts/` subtree, so the diff input and the
  fast-forward scratch reconstruction never see (and never corrupt) the bytes.

There is no live migration: a pre-v4 repo simply has no `promptScripts/` dir;
the first v4 push adds it and stamps `formatVersion = 5`.

## Machine-local data

Per-rig fields (controller IPs and similar) live in the `machine_overrides`
table — keyed by `(projectId, tableName, recordUuid, fieldName)` with a
canonically-encoded `valueJson`. Reads and writes go through the
`Overrides` helper in
[`sync/Overrides.kt`](../src/main/kotlin/uk/me/cormack/lighting7/sync/Overrides.kt),
which serialises values via the same `canonicalEncode` / `canonicalDecode`
pair used for synced documents. Typed accessors exist for strings
(`getString`/`setString`) and ints (`getInt`/`setInt`) over a shared generic
core; a malformed row decodes to `null` rather than throwing, because an
override is advisory data in a table a user can hand-edit.

The universe-configs route is the consumer, with two fields:

| Field | Accessors | Runtime effect |
|-------|-----------|----------------|
| `address` | `resolveUniverseAddress` / `setUniverseAddress` | Controller IP. Changing it rebuilds controllers via `DbFixtureLoader`. |
| `refreshIntervalMs` | `resolveUniverseRefreshIntervalMs` / `setUniverseRefreshIntervalMs` | Art-Net transmit interval, `23..1000` ms, default 25. Hot-swaps on the running controller — no rebuild. See [dmx-engineering.md §Refresh interval](dmx-engineering.md#refresh-interval). |

`PUT /api/rest/projects/{id}/universe-configs/{configId}` upserts either; `GET`
reads them back, reporting `refreshIntervalMs` as an *effective* value plus a
`refreshIntervalOverridden` flag so the UI can distinguish "the default" from
"pinned on this desk". Clearing the interval needs the explicit
`resetRefreshInterval` flag rather than a `null`, because kotlinx-serialization
cannot tell an absent property from an explicit null — `address` sidesteps that
with an empty-string sentinel, which has no `Int` equivalent.

Both are dropped when the universe row is deleted: overrides FK to the
*project*, not the universe, so anything left behind is an orphan keyed by a
UUID that no longer exists.

The transmit interval is machine-local for the same reason the IP is — it
exists to suit the node and fixtures physically present at one venue, and must
not follow the project to the next rig. Runtime DMX output picks both up in
[`DbFixtureLoader`](../src/main/kotlin/uk/me/cormack/lighting7/show/DbFixtureLoader.kt).

The legacy `DaoUniverseConfigs.address` column is retained for schema
compatibility but is no longer the source of truth. The one-shot startup
migration in `State.initDatabase` that moved pre-Phase-2 values into
`machine_overrides` and nulled the column was removed on 2026-08-24 with the
rest of the migrations (see
[`InstallBootstrap.kt`](../src/main/kotlin/uk/me/cormack/lighting7/state/InstallBootstrap.kt)),
having already run everywhere it needed to. A future phase can drop the column.

The exporter never serialises override values: machine-local fields stay
in the local SQLite DB by construction. On import, no override rows are
created — the operator re-enters per-rig values via the universe-configs
UI on the importing machine.

This is the precedent for any future per-install field. The decision tree
in `CLAUDE.md` §"Database changes and cloud sync" guides which side of the
portable/machine-local line a new field falls on.

**A scene element's *Full detail* is an override** (scrim plan D12): `stage_elements` /
`displayDetail` = `"4096"` on the element's uuid, set by `PUT stage-elements/{id}/display-detail` and
answered as `StageElementDto.fullDetail`, so a hero cloth loads its 4096 px copy on this desk only.
Deleting the element deletes the row; a clone carries it with every other override. The images'
derived copies are machine-local too, as files beside the store's originals (`derived/`), and never
reach a repo.

**`effect_tube_state` is machine-local in its own table** (stage-view plan session 9, §3.2) — the
"wholly machine-local" branch of that tree, like `sync_configs`, rather than an override: a row is a
spent one-shot tube (`patch_uuid`, `trigger`, `spent_at` as a `utcInstant`), and loaded is the absence
of a row. Whether a confetti tube on this rig has been fired is a fact about the physical cannon in
this hall, not about the project, so it never travels, is never cloned (`SyncCoverageTest` records it
`MachineLocal`) and is never wiped by an import. It is keyed by the patch's **uuid** with no foreign
key, so a row survives a re-import of its project (an import keeps uuids) and simply stops mattering
for a patch that is gone. `state/EffectsService.kt` reads it into memory when a show starts and writes
it off the firing path, on its own worker thread, so a fire never waits on SQLite.

## Install identity

Each install carries a stable identity — one row in the `installs` table,
bootstrapped on first DB init by
[`State.ensureInstallRow`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
with `friendlyName` defaulting to the system hostname. The user can rename
it via `PUT /api/rest/install`; the UUID is immutable.

The exporter writes the local install's identity into `installs.json` so
exported folders carry a "produced by" stamp for human readers and for
future cloud-sync attribution. Phase 1 wrote an empty stub; Phase 2 fills
it. The importer treats `installs.json` as informational — it doesn't
copy the foreign install's identity into the local install row.

**A change confined to `installs.json` is not a commit.** Every snapshot
unions the local install into the registry, so a new install's first snapshot
of a project it pulled — and any install's snapshot after a rename — differs
from HEAD in that file and nothing else. `SnapshotEngine` puts the file back
to HEAD and reports no changes, so a desk that has edited nothing has nothing
to push; the registration rides along with the install's first real commit,
which is the first commit the history view needs to credit it for. Until
then a sync from it is `NO_OP`. When that first commit reaches the remote as
an auto-merge, the merge tree starts from the remote's `installs.json`, so
`autoMerge` unions the local side's registry back in — but only into a merge
that commits anyway, or it would be the registration-only commit again.
`SnapshotEngineTest` and `PullOnlySyncTest` pin both halves, the merge's
included.

The install row is machine-local; it never leaves the local SQLite DB.

## Project import semantics

The Phase 1 importer is intentionally restrictive — it's the manual-backup
case, not multi-master sync. Import:

1. Validates `formatVersion == 3` (422 otherwise — too old or too new).
2. Refuses (409) if a project with the imported UUID already exists. The
   operator can delete that project or skip the import; merge is Phase 5+.
3. Refuses (409) if the imported name collides with an existing project's
   name (unique index). The operator supplies a `nameOverride` to
   disambiguate.
4. Forces `isCurrent = false` and `activeEntryId = null` on insert. The
   operator puts the imported project on the rig explicitly, with *Make
   current*.
5. Resolves UUID-keyed FKs in topological order: `projects → scripts →
   fxDefinitions → looks → … → cues → cue-children → showEntries →
   cueSlots → controlSurfaceBindings`.
6. Wraps the whole sequence in a single
   `transaction(state.database) { … }` — any FK or validation failure
   rolls back atomically.

## Project cloning

`POST /project/{id}/clone` is implemented **on top of this export format**, in
`sync/ProjectCloner.kt`: export the source to a temp dir → mint fresh UUIDs →
import under the new name. There is deliberately no table-by-table clone
walker. The previous hand-written one covered 5 of 21 project-owned tables, so
clones silently lost their patch list, fixture groups, universes, cue-layer
assignments, cue triggers, cue slots, FX definitions, riggings, stage regions,
parked channels, surface bindings and prompt book. Routing clone through
export/import means a table is cloned the moment it's wired into the exporter
and importer — no third place to remember.

`ExportUuidRemapper` does the identity minting, schema-agnostically in both
directions. **Which identities exist** is read out of the documents: the value
of any field named `uuid`, at any nesting depth. That deliberately does *not*
use `RecordHasher.scanRecordKeys` — that enumerates records by filename, and a
record embedded in a parent document (a fixture group's `members`, an FX
preset's `propertyAssignments`) is not one, so those identities went unremapped
until this was fixed. Every reference field is named `{table}Uuid` rather than
`uuid`, so a reference never mints an identity of its own. **How they're
replaced** is a blind UUID-for-UUID substitution across the JSON, which
preserves the graph without knowing a single field name; a table added later,
as a folder or as an embedded array, is handled with no change to the remapper.

Because the substitution isn't field-aware, a UUID inside a free-text column
(`cues.notes`, an FX definition's `script`, a binding's `targetPayload`) is
rewritten too. For a reference that's correct; for prose it's a cosmetic edit
to a string naming a record that no longer exists in this project. Field-level
exclusions would buy that back at the cost of the per-table knowledge the class
exists to avoid.

Left untouched: `installs.json` (install identities, not records — exempt from
both collection and substitution), the `scripts/{uuid}.kts` bodies and any
other non-JSON sidecar (only `.json` documents are parsed and rewritten;
filenames are still renamed), and the `promptScripts` PDFs and `sceneImages`
images (binary, content-addressed — `RecordHasher.BINARY_DIRS`).

A clone is a **distinct sync identity**: new project UUID, new record UUIDs, no
`sync_configs` / `sync_state` / linked repo / session history. It is not
`isCurrent` and doesn't inherit `activeStackId`.

**Known limitation, narrowed at v11.** `control_surface_bindings.targetPayload` addresses
cues and stacks by *integer row id* as well as by uuid. A row written **before v11** carries only
the int, which nothing can translate: a clone's or a cross-install import's pre-v11 cue/stack
bindings resolve to `MissingCue` / `MissingStack` and must be rebound (or re-saved, which fills the
uuid). Every row written at v11 or later carries the uuid and clones correctly. The second half of
`FU-SYNC-BINDING-PAYLOAD-UUIDS` — project-scoping `CueStackManager`'s lookups so a stale int can
never reach another project's row — is still open in
[`docs/plans/followups.md`](plans/followups.md).

One deliberate exception to "the export is the whole story": clones **do**
inherit `machine_overrides`, notably the per-universe controller IPs. Those are
excluded from the export because they're per-rig, but a clone lands on the same
machine as its source, so carrying them means a cloned project can output DMX
without re-entering every address. The copy translates each `recordUuid`
through the clone's UUID mapping, and is best-effort after the import commits —
mirroring the PDF hydrate, since a missing IP is fixable in the universe editor
and not worth discarding a clone over.

## Guarding coverage

Three tests form a closed loop over "everything is carried everywhere". Each
pins a different pair, and they share one fixture — `testsupport/
RichProjectFixture.kt`, which seeds a project with rows in every portable table
and non-default values in the interesting nullable columns:

| Test | Pins | Catches |
| --- | --- | --- |
| `ProjectRoundTripTest` | importer → exporter | importer drops a field the exporter writes |
| `ProjectCloneTest` | clone → exporter | clone drops a table, field, or mis-wires an FK |
| `SyncCoverageTest` | exporter → schema | a new table has no recorded disposition, or is declared portable but produces no export output |

`SyncCoverageTest` exists because the round-trip test has a blind spot worth
naming: it compares two *exports*, so a field missing from both the exporter
and the importer is missing symmetrically and passes. It closes that from the
other end, asserting against `models/Schema.kt`'s `ALL_TABLES` — the same list
`State` builds the schema from. Adding a table there without recording a
disposition (portable / machine-local / excluded) fails the build.

Its granularity is the table, not the column: a portable table whose fields are
partly missing from the exporter still satisfies it. Field-level fidelity is
the fixture's job, which is why the fixture sets non-default values — a column
left at its default is omitted from canonical JSON, so a copier that drops it
looks correct.

`ProjectCloneTest` compares source and clone by inverting the clone's UUID
mapping and demanding byte-identical exports. That's stronger than normalising
UUIDs away: it pins every FK to the *corresponding* record, so a clone that
wired two group members to the same patch would still fail.

Its companion test — that source and clone share no identities — scans both
exports for UUIDs with a plain regex rather than asking `ExportUuidRemapper`
what it considers an identity. That independence is the whole point: the first
version of that assertion compared the remapper's own returned mapping, whose
keys and values are disjoint by construction, so it passed while embedded-child
UUIDs were being copied verbatim. A guard computed from the thing it guards
proves nothing.

## Working tree (Phase 3)

Each project gets its own per-UUID JGit working tree at
`<state.syncWorkingTreeRoot>/{projectUuid}/repo/`. The default root is
`appDataDir().resolve("sync")` (so `~/Library/Application Support/lighting7/sync/…`
on macOS); override via the `sync.workingTreeRoot` knob in `local.conf` to
point at a different volume or, in tests, at a temp directory.

Two metadata files live at the working-tree root and are written-once by
[`SyncWorkingTree.ensureInitialised`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncWorkingTree.kt):

* **`.gitignore`** — excludes OS junk (`.DS_Store`, `Thumbs.db`, `desktop.ini`).
* **`.gitattributes`** — `* text=auto eol=lf`. This is load-bearing: without
  it, a Windows install committing into the same repo as a macOS install
  would produce a diff for every file on first push.

The repo is initialised with `--initial-branch=main` so HEAD points at
`refs/heads/main` from the unborn state — JGit's default is `master`
regardless of the host's git config, which would surprise anyone running
`git log` on the result.

### Wipe-then-export idempotent strategy

Snapshots use a wipe-then-re-export approach in
[`SnapshotEngine`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SnapshotEngine.kt):

1. Open or init the repo via `SyncWorkingTree.ensureInitialised`.
2. **Delete** every file under the working tree except `.git/`,
   `.gitignore`, `.gitattributes` (`SyncWorkingTree.cleanTrackedFiles`).
3. Re-run `ProjectExporter.export(projectId, path)` to repopulate the
   tree from the current DB state.
4. `git add -A` (modelled by `JGitClient.addAll`, which combines
   `AddCommand` for new/modified files with `RmCommand` for missing
   tracked files).
5. If `git status` is clean (nothing staged), short-circuit and return
   `SnapshotResult.NoChanges`. Otherwise, commit.

The wipe is what makes deletions surface: if a cue stack is deleted from
the DB and we only re-ran the exporter without the wipe, the stale
`cueStacks/{uuid}.json` would linger and `git status` would report no
change. The wipe + re-export forces the tree to mirror the DB exactly,
so deletions show up as removed files in the next commit.

### Snapshot commit format

Author identity comes from the `installs` row:

* **author/committer name**: `friendlyName`
* **author/committer email**: `{shortInstallUuid}@lighting7.local` where
  `shortInstallUuid` is the first 8 hex chars of the install UUID.

Commit message format: `{friendlyName}: {summary} [install:{shortUuid}]`,
where `summary` is the user-supplied message or, if absent,
`Snapshot {ISO-8601 timestamp}`. The `[install:{shortUuid}]` suffix is the
attribution marker that future cloud-sync phases will use to thread
multi-install history together — keep the format stable.

### `sync_configs` table

```
sync_configs
  id PK
  project_id FK -> projects (UNIQUE)
  repo_url VARCHAR? NULL          -- populated in phase 4
  branch VARCHAR DEFAULT 'main'
  enabled BOOLEAN DEFAULT false   -- toggled in phase 4
  auto_sync_enabled BOOLEAN DEFAULT false
  auto_sync_interval BIGINT? NULL -- java.time.Duration, nanoseconds
  last_synced_sha VARCHAR? NULL   -- last successful push sha (phase 4+)
  last_synced_at TEXT? NULL      -- UTC instant, 'YYYY-MM-DD HH:MM:SS.mmmZ'
```

Phase 3 reads/writes only `branch`. The other fields exist so the form on
`/projects/{id}/sync` can collect remote details ahead of phase 4, but the
backend ignores them today. The table is **machine-local** — never
serialised to the cloud repo (storing PATs or remote URLs in synced JSON
would be a credential leak waiting to happen).

The `sync_configs` row for a project is created lazily on first read by
`ensureSyncConfig` in
[`routes/cloudSync.kt`](../src/main/kotlin/uk/me/cormack/lighting7/routes/cloudSync.kt),
so the UI form always has data to render even on the very first visit.

### REST surface

All endpoints live under `/api/rest/projects/{projectId}/sync/...`:

| Method | Path | What it does |
|---|---|---|
| GET | `/config` | `sync_configs` row + `tokenPresent` |
| PUT | `/config` | Update `repo_url` / `branch` / `enabled` |
| PUT | `/credentials` | Store a PAT against the configured `repo_url` (Advanced fallback). |
| DELETE | `/credentials` | Clear the stored PAT |
| GET | `/api/rest/oauth/github/identity` | Connected GitHub user (or 404-shape "not connected") |
| DELETE | `/api/rest/oauth/github/identity` | Clear the install-wide OAuth identity |
| GET | `/api/rest/oauth/github/start?projectId=…` | Kick off the OAuth web flow |
| GET | `/api/rest/oauth/github/callback` | OAuth web-flow return path |
| POST | `/api/rest/oauth/github/device/start` | Begin device flow |
| POST | `/api/rest/oauth/github/device/poll` | Poll until user authorises |
| GET | `/api/rest/oauth/github/repositories?query=` | List repos accessible to the App |
| POST | `/api/rest/oauth/github/repositories` | Create a new repo under the user |
| GET | `/status` | `{ workingTreePath, hasRepo, head, dirty }` |
| GET | `/log?limit=50` | Recent `CommitInfo[]` |
| POST | `/snapshot` | Take a snapshot commit (refused while session active) |
| POST | `/run` | Run the full pipeline (returns `CONFLICTS_PENDING` if same-record conflicts) |
| GET | `/conflicts` | Active session + flat conflict list |
| POST | `/resolve` | Set `LOCAL` / `REMOTE` per record |
| POST | `/apply` | Apply resolutions, commit, push |
| POST | `/abort` | Drop the active session |

JGit calls are blocking and run inside `withContext(Dispatchers.IO) {…}`;
the snapshot engine wraps that boundary itself, route handlers wrap their
own log/status calls.

## Remote sync (Phase 4)

Phase 4 turns the local-only working tree into a sharing surface: one project,
one GitHub repo, push and pull via [JGit][jgit]. The user clicks **Sync now**
on `/projects/{id}/sync` and a single REST endpoint —
`POST /api/rest/projects/{id}/sync/run` — owns the entire pipeline.

### `sync/run` pipeline

Implemented by
[`RemoteSyncEngine`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngine.kt).
Inside `Dispatchers.IO`, holding a per-project mutex so concurrent clicks
serialise:

1. **Validate config**: `sync_configs.repoUrl` non-blank, `enabled = true`. Else
   400 with `REPO_URL_MISSING` / `SYNC_DISABLED`.
2. **Resolve credentials** via [`AuthResolver`](#github-oauth) — OAuth first,
   PAT fallback. Else 401 with `MISSING_CREDENTIALS` (or
   `OAUTH_REAUTH_REQUIRED` if an OAuth identity exists but its refresh token
   has expired).
3. **Snapshot** local DB → working tree → commit (reuses the phase 3
   `SnapshotEngine` verbatim — every sync starts by capturing whatever changes
   the user has made since the last snapshot).
4. **Configure remote** (`origin` → `repoUrl`). Idempotent and rewrites the URL
   if a typo was corrected.
5. **Fetch** `origin/{branch}` with auth. JGit treats "remote exists but branch
   doesn't" as a transport error; we sniff the message and treat it as
   `RemoteAbsent` so the first sync into a fresh repo works.
6. **Pre-pull format check**: read `formatVersion.json` *from the fetched ref*
   (without checking out) via `JGitClient.readBlob`. If the remote's
   `formatVersion > SUPPORTED_FORMAT_VERSION`, abort with `FORMAT_TOO_NEW`. The
   working tree and DB are untouched.
7. **Classify** the relationship between local HEAD and `origin/{branch}` via
   `JGitClient.classify`, which returns one of `Equal`, `RemoteAbsent`,
   `LocalAhead(n)`, `RemoteAhead(n)`, `Diverged(ahead, behind)`.
8. **Reconcile** based on classification (table below).
9. **Update `sync_configs`**: `lastSyncedSha = HEAD-after-sync`,
   `lastSyncedAt = now`.
10. **Hot-reload show** if the just-pulled project happens to be the active
    one — `ProjectManager.switchProject(currentId, reload = true)` tears down +
    rebuilds the show so fixtures, cues, etc. reflect the new DB state
    immediately, **carrying the running show across**: the rig holds its levels,
    each live cue is GO'd again, the programmer and hand-run FX come back
    (FX restart their phase), and whatever the pull changed crossfades in —
    `docs/dmx-engineering.md` §"Handing the rig from one show to the next".

### Pull-only installs

`sync.push = false` in `local.conf` (default `true`) makes an install
pull-only. It is read once at startup (`State.syncPushEnabled`) and is
deliberately in neither the UI nor `example.local.conf`: it exists for
throwaway installs — a cloud dev container that imports a real project — that
must never write to the project's repo. Don't rely on read-only repo access for
that instead: the cloud session's git proxy was seen letting a push through
on a repo attached read-only.

A pull-only sync runs the whole pipeline — snapshot, fetch, classify,
fast-forward, three-way merge, conflict sessions — and skips only the push:

| Relation | Pull-only outcome |
|---|---|
| `RemoteAbsent` / `LocalAhead(n)` | `NO_OP`, `pushed = 0`; the commits stay local |
| `Diverged` (auto-merge, or applying a conflict session) | `MERGED`, `pushed = 0`; the merge commit stays local |
| `Equal` / `RemoteAhead` | unchanged — nothing to push |

The result's `message` starts with `RemoteSyncEngine.PUSH_DISABLED_PREFIX`.
No new `SyncOutcome` was added, so the client needed no change; its
`NO_OP` toast ("Already in sync") is the one place the UI undersells a
skipped push, which is acceptable for a setting the UI never offers.

**The bookkeeping records the remote tip, not the local commit.** A skipped
push leaves `sync_state` where it was, and a local merge bootstraps it at the
remote tip that was merged — the last point both sides actually agree on —
with `lastSyncedSha` naming the same commit. Recording the local merge
commit instead would make this install's own unpushed edits read as
"unchanged locally" in the next three-way diff, so the remote's older value
would win and the edit would be lost. `PullOnlySyncTest` pins that with a
rename that must survive two remote advances.

### History classification → action

| Relation | Action | Outcome |
|---|---|---|
| `Equal` | nothing | `NO_OP` |
| `RemoteAbsent` | regular push (creates the remote branch) | `PUSHED` |
| `LocalAhead(n)` | regular push | `PUSHED`, `pushed = n` |
| `RemoteAhead(n)` | `resetHard origin/{branch}` + `replaceFromWorkingTree` | `FAST_FORWARDED`, `pulled = n` |
| `Diverged(a, b)` | **force push** | `FORCE_PUSHED`, `pushed = a`, `replaced = b` |

### Diverged history → three-way diff (Phase 5)

Phase 4 force-pushed on divergence and silently dropped peer commits. **Phase 5
replaces that branch** with a record-level three-way diff and (where
necessary) a user-driven conflict-resolution flow. Force-push is gone from
the engine; the only ways to advance HEAD are now no-op, regular push,
fast-forward, auto-merge, or post-resolution merge — all linear or two-parent
merges, never history-rewriting.

See [Three-way diff](#three-way-diff-phase-5) and [Sync session lifecycle
(Phase 5)](#sync-session-lifecycle-phase-5) below for the contract.

### Pull → DB

A fast-forward step changes the working tree on disk. The DB has to follow.
We use [`ProjectImporter.replaceFromWorkingTree`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ProjectImporter.kt)
inside one transaction:

* Validate `project.json`'s UUID matches the existing `DaoProject.uuid` —
  refuse to clobber a different project by accident.
* Cascade-delete every child row (cues + their preset/attribute/FX/trigger
  children, cue stacks, fxPresets, fxDefinitions, scripts, fixture patches,
  groups, universe configs, etc.) using the same FK-safe order as the
  project-delete flow in `routes/projects.kt`.
* Re-run the per-table import loops binding everything to the existing
  project row, preserving its int `id` so non-synced FKs (`machine_overrides`,
  `sync_configs`) survive.
* Update the project's `name` + `description` from JSON; `isCurrent`, `uuid`,
  `activeEntryId` are left alone.

`machine_overrides` survive because they're keyed by `(projectId,
recordUuid, fieldName)` and `recordUuid` is preserved across re-import. The
user's per-rig ArtNet IPs stay put when remote changes land.

[jgit]: https://www.eclipse.org/jgit/

### Pre-pull `formatVersion.json` check

The check has to happen **before** the working tree is moved. If we
fast-forwarded first, a too-new repo would corrupt the working tree before we
could refuse — and the working tree is what the next snapshot+import cycle
reads. So `JGitClient.readBlob(repo, "refs/remotes/origin/{branch}",
"formatVersion.json")` reads the version straight from the fetched git
object, no checkout required. If too new, abort and the working tree is left
as-is.

## Auth / credential storage

Two paths:

1. **GitHub OAuth (primary)** — install-wide identity established via the web
   flow or device flow. Auto-refreshing user-to-server tokens; per-repo
   permission grants chosen by the user when they install the GitHub App.
2. **Personal Access Tokens (Advanced fallback)** — per-repo PATs for headless
   rigs, GitHub Enterprise, or as an explicit override. Existing PAT-only
   configurations from before the OAuth migration keep working unchanged.

### GitHub OAuth

Architecture lives in
[`sync/auth/oauth/`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/oauth/):

| Class | Role |
|---|---|
| `OAuthGitHubClient` | Ktor HTTP client for `github.com/login/...` and `api.github.com/user/...`. Methods: `exchangeCode`, `refresh`, `startDeviceFlow`, `pollDeviceFlow`, `getAuthenticatedUser`, `listInstallationRepositories`, `createUserRepo`. |
| `OAuthTokenStore` | Persists the install-wide identity as a JSON blob in [`CredentialStore`](#credential-store) under `oauth:github:default`. Refresh always overwrites the whole blob so a partial write can't leave a fresh access token paired with a stale refresh token. |
| `OAuthTokenProvider` | Refresh-on-demand wrapper. A `Mutex` guards the read-refresh-write so concurrent git ops don't race-burn the (single-use) refresh token. Throws `OAuthReauthRequiredException` when the refresh token is expired or rejected, and [records that on the identity](#re-auth-marking). |
| `AuthResolver` | The single throat callers use to get JGit credentials. Prefers OAuth, falls back to the per-repo PAT if OAuth is missing or its refresh token has expired. |

Identity metadata mirror table:

```
oauth_identities      (machine-local; never synced)
  id PK
  provider VARCHAR    -- "github"
  scope VARCHAR       -- "default"
  github_login VARCHAR
  github_user_id BIGINT
  access_expires_at TEXT?         -- UTC instant
  refresh_expires_at TEXT?        -- UTC instant
  connected_at TEXT               -- UTC instant
  reauth_required_at TEXT?        -- UTC instant; see "Re-auth marking"
  reauth_reason VARCHAR?
  UNIQUE(provider, scope)
```

The row carries only non-secret display metadata so the UI can render
"Connected as @login" without round-tripping the credential store. Tokens
themselves never go in this table — only `CredentialStore`.

#### Web flow vs. device flow

* **Web flow** (primary): the user clicks "Connect GitHub" in the sync UI,
  which hits `GET /api/rest/oauth/github/start`. The backend mints a CSRF
  cookie carrying the originating `projectId`/`returnTo`, redirects the
  browser to `github.com/login/oauth/authorize`, and waits for the callback at
  `/api/rest/oauth/github/callback`. The exchange happens server-side and
  the user is bounced back to `/projects/{id}/sync` with the identity stored.
* **Device flow** (fallback): for rigs accessed from a host that doesn't match
  the GitHub App's registered Callback URL (e.g. iPad on the LAN browsing
  the studio rig). The UI shows a short user code and a verification URI;
  the backend polls until GitHub observes the user's authorisation.

Both paths yield the same `StoredOAuthIdentity` blob and the same DB mirror
row.

#### Refresh

Access tokens last 8 hours; refresh tokens last 6 months and are *single-use*
(each refresh issues a new pair). The provider refreshes proactively when an
access token has less than 60 s of remaining lifetime, and is single-flight
under a Mutex so two concurrent git ops don't race the same refresh.

The `onIdentityUpdated` hook (wired up in `State`) writes the new expiries into
the `oauth_identities` row so the UI's "expires in" badge stays accurate without
polling, and broadcasts `oauthIdentityChanged`. The broadcast matters because
refreshes happen off-request — an auto-sync tick, a repo listing — so without it
an open sync page keeps rendering a stale identity.

#### Re-auth marking

A refresh token can stop working long before its nominal 6-month expiry: the
authorisation gets revoked, the App is reinstalled, the client secret rotates, or
the token is rotated out from under this install. GitHub answers
`bad_refresh_token` / `invalid_grant`, and **only the user can fix it**.

When that happens `OAuthTokenProvider` stamps `reauthRequiredAt` +
`reauthReason` onto the stored blob (and, via the mirror hook, the DB row and a
WS frame). `GET /oauth/github/identity` exposes them as `reauthRequired` /
`reauthReason` / `reauthRequiredAt`.

The frontend surfaces that in three places, because the original failure was one
of *visibility* rather than detection — the desk knew nothing was syncing, and
said so nowhere anyone looked:

* `IdentityRow` leads with a "Reconnect GitHub" alert instead of "Connected as
  @login", on both sync pages.
* A destructive dot on the **Sync** nav entries (`ProjectSwitcher`).
* An app-wide dismissible banner (`SyncReauthBanner`), dismissed against *this*
  rejection's timestamp so a later one is never pre-dismissed.

All three are admin-only, which is not a policy choice so much as a consequence:
the `/api/rest/oauth/` routes are mounted inside `adminOnly {}`, so an operator's
client can only 403 — and per the rule the update panel follows, whoever is
running a show does not get nagged about something they cannot fix.

Cleared by any success: `applyRefresh` nulls both fields explicitly (it's a
`copy`, so a stale flag would otherwise survive the very refresh that proved the
identity healthy), and `persistIdentity` clears the mirror row on re-connect.

Three deliberate choices:

* **Only `OAuthReauthRequiredException` marks.** A network error, a 5xx or a rate
  limit leaves the identity unmarked so it keeps retrying — marking those would
  strand a working desk over a blip.
* **The marking is a record, not a gate.** `accessToken()` does *not* fast-fail on
  it; a spurious rejection would then wedge the identity until someone
  re-connected by hand, and the manual-retry path would need a `force` flag
  threaded through `AuthResolver`. Every wasted request came from a background
  loop, so [that loop](#auto-sync-phase-8) is where the retry storm is fixed.
* **`AuthResolver` still prefers a PAT over nothing.** A desk with a stored PAT
  keeps syncing while its OAuth identity is marked; that path is unchanged.

Re-marking an already-marked identity is a no-op, so a backed-off retry neither
rewrites the blob nor re-broadcasts, and `reauthRequiredAt` keeps meaning
*first* seen — which is what the UI shows as "since".

**`failReauth` re-reads the store before marking, and marks the copy it just
read.** `refresh()` is a network round-trip and `persistIdentity` writes the blob
directly — outside `refreshMutex`, which only serialises `OAuthTokenProvider`. So
a user can finish re-connecting while GitHub is still deciding about the *old*
token. Writing the captured snapshot back would overwrite the fresh identity with
the dead one and broadcast `reauthRequired`, silently undoing a re-connect seconds
after it succeeded. A changed refresh token means "re-connected under us" and the
rejection is discarded. There is no CAS on the credential store, so a sliver of a
window survives between that read and the write — microseconds rather than a
round-trip, and no stale token material is ever written back.

> **Why this exists.** A desk ran for 25 days with a rejected refresh token,
> POSTing to GitHub's token endpoint once a minute per project (~70k rejected
> requests) while the UI showed "Connected as @user" and a permanent "refreshing
> soon" badge, because nothing persisted the rejection and nothing backed off.

#### Configuration

```hocon
sync.oauth.github {
    clientId      = ""    # blank → fall back to bundled credentials (see below)
    clientSecret  = ""    # sensitive; treat local.conf as a secret file
    publicBaseUrl = ""    # default "http://localhost:8413"; appended with
                          # /api/rest/oauth/github/callback
}
```

`clientId` and `clientSecret` resolve as an atomic pair through
[`State.resolveOAuthCredentialPair`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt):

1. If `local.conf` provides both, use that pair.
2. Otherwise, if `BundledOAuthCredentials` (generated at build time) provides
   both, use that pair.
3. Otherwise OAuth is disabled and the UI offers PAT-only auth.

We never mix `clientId` from one source with `clientSecret` from another —
they belong to different GitHub Apps and pairing them across sources would
produce an invalid client.

##### Build-time bundled credentials

Installer distributions (the Windows .msi built by `.github/workflows/windows-build.yml`)
can't ship a `local.conf` with secrets, so the workflow injects credentials
from repository secrets via Gradle properties:

```bash
./gradlew packageWindows \
  -PghOauthClientId=$GH_OAUTH_CLIENT_ID \
  -PghOauthClientSecret=$GH_OAUTH_CLIENT_SECRET
```

The `generateBundledOAuthCredentials` task writes
`build/generated/source/oauth/main/kotlin/.../BundledOAuthCredentials.kt`
with the values as `const val` strings; that file is on the main source set's
classpath, so the values end up in `lighting7.jar` and the resulting .msi.
Local builds leave the props empty (the file generates with empty strings)
and rely on `local.conf` as before.

The repository secrets are `LIGHTING7_GH_OAUTH_CLIENT_ID` and
`LIGHTING7_GH_OAUTH_CLIENT_SECRET`. The credentials end up in the binary;
that's the standard "public client" trade-off for OAuth desktop apps where
PKCE isn't available (GitHub doesn't support PKCE on OAuth/GitHub Apps).
Mitigations: a GitHub App is scoped per-install (a leaked secret can't
pivot beyond what each user explicitly authorised), permissions are kept
minimal, and rotation is fast (regenerate the App's client_secret →
re-run the workflow → ship a new installer).

GitHub App registration steps (one-time, by the install operator):

* Repository permissions: `Contents: Read & write`, `Metadata: Read-only`,
  `Administration: Read & write` (the last is needed for the inline
  "Create new private repo" affordance — drop it if you only ever pick
  existing repos).
* Enable "Request user authorization (OAuth) during installation".
* Enable "Device Flow" so the device-flow fallback works.
* Set Callback URL to `${publicBaseUrl}/api/rest/oauth/github/callback`.
* Webhooks: off.

No private key (`.pem`) is needed — that's only required for server-to-server
operations (acting as the App without a logged-in user), which lighting7
doesn't do.

### Credential store

The
[`CredentialStore`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/CredentialStore.kt)
interface holds both PATs (under `pat:<repoUrl>`) and the OAuth blob (under
`oauth:github:default`). The PAT-keyed methods are convenience wrappers over
the more general `getBlob`/`setBlob`/`deleteBlob`/`containsBlob` API.

| Backend | Used when | Notes |
|---|---|---|
| `KeyringCredentialStore` | `sync.credentialStore = "keychain"` (default) | macOS Security framework, libsecret on Linux, Windows Credential Manager — wrapped by [java-keyring][jk] (which uses JNA under the hood). Service name `lighting7`, account = the blob key. |
| `FileCredentialStore` | `sync.credentialStore = "file"` *or* keychain init failed | AES-GCM-encrypted JSON at `<appDataDir>/credentials.enc`. Key derived from the install UUID + the machine hostname (single SHA-256 round). Defeats casual file-copy leaks; not a real secret store. |

The factory ([`CredentialStoreFactory`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/CredentialStoreFactory.kt))
falls back from keychain to file silently if the native backend isn't
available (typical on a headless Linux box). The fallback is logged at WARN.

### PAT (Advanced fallback)

PATs are keyed per-`repoUrl` so two projects pointing at different remotes
can hold different credentials. Changing a project's `repoUrl` invalidates
the old PAT lookup; the user has to re-enter the token under the new URL.

REST surface:

* `PUT /api/rest/projects/{id}/sync/credentials` — body `{ pat }`. The repo
  URL must be set first; the PAT is keyed against it.
* `DELETE /api/rest/projects/{id}/sync/credentials` — clears the entry.
  No-op if none exists.
* `GET /api/rest/projects/{id}/sync/config` returns `tokenPresent: Boolean`
  so the UI can render "✓ stored" without round-tripping the secret.

The PAT itself is **never** sent to the client. Rotation = clear + re-enter.

GitHub PAT scope: `repo` is required for private repos. JGit surfaces a 401
on push/fetch if the scope is missing.

### JGit transport

Both PAT and OAuth user-to-server tokens authenticate identically over
HTTPS — the username is the literal placeholder `x-access-token`, the
password is the token. `GitCredentials.forGitHubToken(...)` (formerly
`forGitHubPat`) builds the JGit credentials provider for either source.

**Engine commits are never signed.** JGit reads the machine user's
`~/.gitconfig`, so a desk whose owner signs their own commits
(`commit.gpgsign = true`) would otherwise sign snapshots and merges too — and
with `gpg.format = ssh` JGit has no signer, so every snapshot failed with
"No signer for ssh signatures". `JGitClient.commit` and `commitWithParents`
call `setSign(false)`: these commits are authored as the install, not as a
person.

### Error codes

| Code | Mapped HTTP | Cause |
|---|---|---|
| `MISSING_CREDENTIALS` | 401 | No OAuth identity AND no PAT for this repo. |
| `OAUTH_REAUTH_REQUIRED` | 401 | OAuth identity present but refresh token rejected — user must re-connect. |
| `AUTH_FAILED` | 401 | Credentials present but GitHub rejected the push/fetch. |
| `MISSING_PAT` | 401 | Legacy alias for `MISSING_CREDENTIALS`; retained for one release. |

[jk]: https://github.com/javakeyring/java-keyring

## Three-way diff (Phase 5)

Implemented by [`ThreeWayDiff`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ThreeWayDiff.kt)
and the surrounding [`RecordHasher`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RecordHasher.kt).

For every record on either side, the engine computes a SHA-256 hash of its
canonical JSON (or, for scripts, the canonical concatenation of meta + body)
and consults the per-record `sync_state` row to determine the relationship
between local, remote, and the last-known shared base.

### `RecordKey` & hashing

* `RecordKey(tableName, uuid)` — `tableName` is the export folder name
  (`"cues"`, `"cueStacks"`, `"scripts"`, …) and matches the string used by
  the `machine_overrides` row.
* For most tables, the JSON file at `{tableName}/{uuid}.json` is one record.
  For **scripts**, `RecordKey("scripts", uuid)` covers both `scripts/{uuid}.kts`
  and `scripts/{uuid}.meta.json`; the hash is over their deterministic
  concatenation so a body-only edit bumps the hash.
* Top-level metadata (`formatVersion.json`, `project.json`, `installs.json`,
  `.gitignore`, `.gitattributes`) isn't a record and isn't part of the diff.

### Outcome matrix

Phase 7 added a per-snapshot `isDeleted` bit (the snapshot is either a live
record or a tombstone). The matrix now branches on that bit so a deletion can
be distinguished from "we never had this." Each side's input is `(hash,
isDeleted)`; same-hash + same-isDeleted is `local == remote` for the matrix's
`NoOp` row.

| Local | Remote | sync_state | Hash relations | Outcome |
|---|---|---|---|---|
| any | any | any | `local == remote` (kind+hash both equal) | `NoOp` (covers concurrent identical edits AND concurrent identical deletes) |
| live | live | yes | `local == base, remote ≠ base` | `TakeRemote` |
| live | live | yes | `remote == base, local ≠ base` | `TakeLocal` |
| live | live | none | `local ≠ remote` | `Conflict (EDIT_EDIT)` (Phase 4 → 5 upgrade) |
| live | live | yes | both moved, different values | `Conflict (EDIT_EDIT)` |
| **tombstone** | **live** | yes, both moved | — | `Conflict (DELETE_EDIT)` |
| **live** | **tombstone** | yes, both moved | — | `Conflict (EDIT_DELETE)` |
| **tombstone** | **live** | yes, remote unchanged from base | — | `TakeLocal` (push the tombstone, deletion wins) |
| **live** | **tombstone** | yes, local unchanged from base | — | `TakeRemote` (accept the deletion) |
| live | absent | yes (live base) | — | `TakeLocal` with `WARN` (history rewrite or pre-Phase-7 peer dropped a record without a tombstone) |
| live | absent | none | — | `TakeLocal` (new local record, no tombstone awareness needed) |
| absent | live | any | — | `TakeRemote` (new remote record) |
| tombstone | absent | any | — | `NoOp` (deletion already not on the remote side) |
| absent | tombstone | any | — | `NoOp` (we never had it, nothing to delete) |
| absent | absent | yes | — | `NoOp` (orphan; bootstrap GCs it) |

The "no shared base" rows cover the **first sync after upgrading from
Phase 4**: until `bootstrapSyncStateAtHead` populates `sync_state` for the
first time, every disagreement is conservatively a conflict, surfacing in
the UI rather than silently picking a side.

### Auto-merge (no conflicts)

When `Diverged` produces zero conflicts, the engine merges automatically:

1. `resetHard origin/{branch}` — working tree now matches remote tip.
2. For each `TakeLocal` outcome, write the local snapshot's bytes (one or
   two files) over the remote tree.
3. `ProjectImporter.replaceFromWorkingTree` updates the local DB to match.
4. `git add -A`, then commit using `Repository.writeMergeHeads(localSha)` so
   JGit's `CommitCommand` produces a real two-parent merge commit
   (`origin tip` × `local snapshot tip`).
5. Regular `push` — we're now strictly ahead of remote.
6. `bootstrapSyncStateAtHead` walks the new HEAD and rewrites every
   `sync_state` row.

If step 2 produces no tree-level diff (everything was `TakeRemote`), step 4
is skipped and HEAD remains at remote tip — equivalent to a fast-forward.

### Sync-state bootstrap

`bootstrapSyncStateAtHead(projectId, sha)` runs at the end of every terminal
outcome that advances HEAD: `NO_OP`, `PUSHED`, `FAST_FORWARDED`, and the
post-merge / post-apply paths. It deletes every `sync_state` row for the
project and re-creates one row per record on disk at `sha`. This is the
**bootstrap** path: the very first Phase-5 sync on a Phase-4 working tree
finds zero rows and seeds them all. Two other writers exist, both below: the
import of a remote project seeds the rows at the clone, and a snapshot of a
project with none seeds them at its last-synced commit.

The deliberate corollary: **the first sync after upgrade can't tell prior
local edits from remote ones**, so any disagreement at that moment surfaces
as a conflict. The engineering trade-off is one extra round of operator
attention immediately after upgrade in exchange for never silently dropping
peer work.

That corollary must never reach a project added **from its remote repo**: its
DB is exactly the cloned tree, so that tree is already agreed. `CloudSyncImporter`
therefore seeds `sync_state` at the clone's HEAD (`SyncStateBookkeeping.bootstrapAt`,
the same body as `bootstrapSyncStateAtHead`). Before it did, an imported desk had no
rows until a sync ended in a terminal bootstrap, and a pull-only desk whose every run
ended local-ahead never got any. The first sync that diverged — any local commit, even
one the operator didn't knowingly make — then conflicted on **every record the remote
had changed**, ones never touched locally included (local equal to the merge base, yet
`base == null`), and a deletion made meanwhile shipped with no tombstone, so a peer with
a commit of its own kept the record and pushed it back.

Desks imported before the fix are repaired by `SyncStateBookkeeping.seedIfMissing`,
which `SnapshotEngine.snapshot` runs before deriving tombstones: a project with no rows
whose `sync_configs.lastSyncedSha` is in the working tree is bootstrapped there. It inserts
only while the rows are still empty, in the transaction that checks, because the manual
snapshot route does not hold the engine's project lock and a sync may have bootstrapped at a
newer commit meanwhile. The import's own seed is past the import's commit, so a failure there
only logs: the watermark is the same commit, and the first snapshot seeds from it. That
watermark names the last agreed tree on every path that sets it (the import's clone, the
remote tip a pull-only run keeps, the HEAD a push or merge leaves). `ImportedProjectSyncTest`
covers both, push-enabled and pull-only.

## Sync session lifecycle (Phase 5)

Phase 5 ships the persistent half of the design's session state machine —
`CONFLICTS_PENDING → APPLYING → DONE/FAILED/ABORTED` — backed by
`sync_session` and `sync_session_conflict` rows. The `FETCHING` state and
crash-resume on startup are deliberately deferred to Phase 6: they require
recovery UX that's not yet built.

```
runSync                ┌──── no conflicts ───────► auto-merge → push
   │                   │                              │
   │  Diverged ────► three-way diff                  ▼
   │                   │                          DONE (no session)
   │                   │
   │                   └─── conflicts ──► sync_session(CONFLICTS_PENDING)
   │                                            │       │
   │                                       resolve   abort
   │                                            ▼       ▼
   │                                          apply   sync_session(ABORTED)
   │                                            ▼
   │                                  sync_session(APPLYING)
   │                                            ▼
   │                                       autoMerge
   │                                            ▼
   │                                  sync_session(DONE)
```

Tables (machine-local, never serialised):

| Table | What it carries |
|---|---|
| `sync_state` | One row per `(project, table, uuid)`: `last_synced_sha` / `last_synced_hash`. Rebuilt by `bootstrapSyncStateAtHead` after every advance; seeded at the clone by `CloudSyncImporter`, and at `lastSyncedSha` by `SyncStateBookkeeping.seedIfMissing` when a snapshot finds none. |
| `sync_session` | One row per session: `state`, `local_sha`, `remote_sha`, `base_sha`, `error_message`. Historical `DONE`/`FAILED`/`ABORTED` rows accumulate as audit trail. |
| `sync_session_conflict` | One row per conflicting record in the session: `conflict_kind` (`EDIT_EDIT` only in Phase 5), `resolution` (`LOCAL`/`REMOTE`/`MANUAL`/null), `manual_value_json` (Phase 6, populated when `resolution = MANUAL`), and the canonical-JSON snapshots (`local_json` / `remote_json` / `base_json`) captured at session-open time so resolution remains stable across stale fetches. |

Only one **active** session per project is allowed — defined as
`state IN (CONFLICTS_PENDING, APPLYING)`. While a session is active:

* `runSync` returns 409 `SESSION_PENDING` — apply or abort first.
* `snapshot` returns 409 `SESSION_PENDING` — a snapshot would race the
  `local_json` data the resolution UI is showing.
* `apply` is allowed once every conflict has a `LOCAL` or `REMOTE`
  resolution; otherwise 422 `UNRESOLVED_CONFLICTS`. If local HEAD has moved
  since the session opened (e.g. another process snuck in a snapshot), 409
  `SESSION_STALE` and the user must abort and re-run.
* `abort` deletes the conflict rows, marks the session `ABORTED`, and
  hard-resets the working tree to `local_sha`. Local DB is untouched (we
  never wrote to it for a conflict path).

The **apply** path runs the same auto-merge code as the no-conflicts branch;
the user's resolutions override each conflicting record's outcome. The
resulting commit's message is `Resolve …` instead of `Merge …` so it's
distinguishable in `git log`.

### Tombstones and deletion propagation (Phase 7)

Phase 7 closed the resurrection gap with three pieces: snapshot-time
tombstone derivation, `(hash, isDeleted)` plumbing through the diff, and a
push-rejected retry that absorbs peer races. See [Tombstones](#tombstones-phase-7)
below for the full contract.

## MANUAL resolution (Phase 6)

Phase 5 only let the user pick whole-record `LOCAL` or `REMOTE`. Phase 6 adds a
third option: **MANUAL** — supply the replacement payload directly. Useful when
neither side is right but the user can hand-merge the JSON.

* **Storage.** A new `manual_value_json` column on `sync_session_conflict`
  carries the user's edit. It's populated only when `resolution = MANUAL`;
  switching the resolution back to `LOCAL` / `REMOTE` clears the column so a
  stale draft can never sneak through `apply`.
* **Apply.** During `applyMergeFromSession`, MANUAL outcomes are surfaced as a
  new [`DiffOutcome.TakeManual(content)`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ThreeWayDiff.kt)
  variant. `autoMerge` overlays `content` verbatim onto the working-tree path
  the record would normally occupy, then runs the same
  `replaceFromWorkingTree` + commit + push pipeline as `LOCAL` / `REMOTE`.
* **Single-file restriction.** Phase 6 only allows MANUAL on records that
  serialise to a single file — i.e. everything except `scripts`, which is split
  into `scripts/{uuid}.kts` + `scripts/{uuid}.meta.json`. The route layer
  rejects MANUAL for `scripts` with HTTP 400 and the conflict DTO carries a
  `manualEditAllowed: Boolean` flag so the UI can hide the option for those
  rows. A richer per-table editor for scripts is on the Phase 7+ list.
* **Validation.** `POST /sync/resolve` rejects MANUAL with a missing
  `manualValueJson` (400). `POST /sync/apply` re-checks at apply time and
  raises `UNRESOLVED_CONFLICTS` if a row has somehow ended up MANUAL with
  null content.

The resolution choice + saved manual content round-trip through
`GET /sync/conflicts` so the UI can re-render a half-typed draft if the user
navigates away and back.

## Crash recovery (Phase 6)

The `applyMergeFromSession` pipeline isn't a single atomic transaction — the
DB importer commits before the git commit + push. A crash mid-apply can
therefore leave the local DB partly merged without a corresponding remote
push, so a naive resume would either re-do work that already landed in the
DB or push something the remote already has.

Phase 6's policy: on every [`State`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
construction (i.e. process startup), [`ConflictSession.recoverFromCrash`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ConflictSession.kt)
demotes any `APPLYING` session to `FAILED` with an explanatory error message.
The user's recovery path is the existing `POST /sync/abort` (which resets the
working tree to `local_sha` and clears the conflict rows) followed by another
`POST /sync/run` — the three-way diff naturally reconciles whatever shape the
partial apply produced.

`CONFLICTS_PENDING` sessions are deliberately **not** demoted: at that point
the DB and working tree are still consistent and the user can pick up where
they left off via the same `/conflicts` and `/apply` endpoints they'd have
used before the crash.

The `FETCHING` enum value is reserved on `SessionState` against a future
phase that opens a session row at the start of `runSync` (so a crash mid-fetch
is observable), but Phase 6 doesn't write `FETCHING` rows — `runSync` is
mutex-locked and a crash mid-fetch leaves no row at all. Reserving the value
now means Phase 7+ can adopt it without a schema migration.

## Tombstones (Phase 7)

Phase 7 introduces deletion markers in the repo so the diff can distinguish
"local has X, remote deleted X" from "local has X, remote never had X." The
former propagates the deletion; the latter pushes the new record. Without
this, a record deleted on install A could be resurrected on the next sync
from install B that still had it.

### File format

Path: `tombstones/{tableName}/{uuid}.json`. Body:

```json
{
  "tombstone": true
}
```

Hash-stable: the body never carries a timestamp or attribution, so re-running
a snapshot doesn't churn the file. Forensics (when, by whom) come from `git
log` on the tombstone path. The DTO is
[`TombstoneJson`](../src/main/kotlin/uk/me/cormack/lighting7/sync/dto/SyncDtos.kt).

### Snapshot-time derivation (and carry-forward)

`SnapshotEngine.snapshot` writes tombstones for every `sync_state` row not
matched by a live record in the freshly-exported tree. The set is the union of:

1. **Locally deleted between snapshots** — `sync_state` row had a live record
   last time but the live DB no longer has that UUID.
2. **Carry-forward** — `sync_state` row already has `lastSyncedIsDeleted = true`
   (the install previously pulled a tombstone). The wipe-then-export step
   nuked the on-disk tombstone before re-export; the carry-forward step writes
   it back. **Without this, the install would silently drop tombstones on its
   next push and a peer who never saw the deletion would resurrect the record.**
   The three-install propagation regression test in
   `RemoteSyncEngineTombstonePropagationTest` enforces this rule.

The deletion set is derived purely by walking
[`RecordHasher.scanRecordKeys(workingTreePath)`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RecordHasher.kt)
and computing `sync_state \ on-disk` — no per-DAO delete hooks. The cost is
one DB read per snapshot.

### `sync_state.lastSyncedIsDeleted`

Phase 7 adds one column on `sync_state`:

```kotlin
val lastSyncedIsDeleted = bool("last_synced_is_deleted").default(false)
```

`bootstrapSyncStateAtHead` writes the bit from each snapshot's `isDeleted`
field, so a tombstone-at-HEAD seeds a `sync_state` row with `isDeleted = true`.
The diff reads this back so a record that's tombstoned on both sides reads as
`NoOp` rather than ambiguous-deletion.

### Defensive invariants

* If a snapshot ever sees both a live record AND a tombstone for the same
  `(tableName, uuid)`, the live record wins and `RecordHasher` logs a `WARN`.
  The wipe-and-export pipeline can't normally produce this state.
* If remote is absent and `sync_state` says the base was a live record (i.e.
  remote dropped a record without a tombstone — history rewrite, manual `rm`,
  pre-Phase-7 peer), the diff falls back to `TakeLocal` with a `WARN`. Same
  semantics as Phase 5/6, so the user-visible behaviour doesn't regress.

### Pre-Phase-7 deletion forensics

Deletions that happened before this upgrade can't retroactively get
tombstones — they were silently dropped from `sync_state`. The first
post-upgrade sync that finds a record on local but absent remotely with no
matching `sync_state` row treats it as a brand-new local record (push it).
Operators who care about pre-upgrade deletions need to apply them on every
install manually before re-syncing.

### Conflict kinds

Phase 7 produces two new conflict kinds in addition to `EDIT_EDIT`:

* **`DELETE_EDIT`** — local deleted, remote edited. The user picks LOCAL
  (keep the deletion) or REMOTE (restore the remote edit). MANUAL is
  disabled — there's no live local-side content to hand-edit; the route
  layer rejects MANUAL with HTTP 400.
* **`EDIT_DELETE`** — local edited, remote deleted. The user picks LOCAL
  (keep the edit), REMOTE (accept the deletion), or MANUAL (rescue the
  record with hand-edited content). MANUAL on `EDIT_DELETE` is fully
  supported.

The route layer's MANUAL gate is in
[`isManualEditAllowed(tableName, conflictKind)`](../src/main/kotlin/uk/me/cormack/lighting7/routes/cloudSync.kt) —
multi-file records (scripts) and `DELETE_EDIT` conflicts both return false.

### Auto-merge with path removal

When the merge winner's set of files differs from the loser's (a
record-↔-tombstone flip), the engine deletes paths that exist only on the
remote side before writing the local snapshot's files. Without that step the
remote-side stale file would linger in the merged tree.

```kotlin
val remotePaths = remoteSnapshots[key]?.files?.keys ?: emptySet()
val localPaths = localSnap.files.keys
for (path in remotePaths - localPaths) Files.deleteIfExists(workingTree.resolve(path))
for ((p, c) in localSnap.files) writeWorkingTreeFile(workingTree, p, c)
```

`replaceFromWorkingTree` only walks the named record subdirs (`cues/`,
`cueStacks/`, …) and ignores `tombstones/`, so a tombstone on disk naturally
suppresses DB import for that UUID — no importer changes needed.

### Tombstone GC (deferred)

Tombstone files are tiny (~25 bytes) but accumulate forever in `sync_state`
and on disk. GC is deliberately not in Phase 7 — it's a tree-size
optimisation, not a correctness issue.

A naïve age-based sweep (e.g. "prune `tombstones/{path}` whose `git log -1
--format=%ct` is older than 90 days") **is not safe in this multi-master
model**. An install that's been offline longer than the cutoff would, on
catch-up, see `live record locally / no remote file / no sync_state row`
and treat the record as a brand-new local insert, resurrecting the
deletion. That's the exact scenario `RemoteSyncEngineTombstonePropagationTest`
prevents. Any GC needs a peer-aware low-water mark — for example, an
extension to `installs.json` recording each install's last-synced
timestamp, with GC bounded by the *oldest* peer's view of history.

The design sketch (safe shape, where the maintenance loop lives, ordering
of `git rm` vs. `sync_state` row drop) lives in
[`docs/plans/followups.md` → `FU-SYNC-TOMBSTONE-GC`](plans/followups.md).

## Push-rejected retry (Phase 7)

When push is rejected by the remote (a peer pushed in the window between our
fetch and our push), the engine retries up to
[`RemoteSyncEngine.MAX_PUSH_RETRIES`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngine.kt)
= 3 times before surfacing `PUSH_REJECTED` to the caller.

The retry wrapper is around the IO sub-step (the contents of `withContext(Dispatchers.IO)`),
not the whole orchestrator — so DB-side post-success bookkeeping
(`lastSyncedSha` update, `cloudSyncDone` event, session→DONE) runs exactly
once after the IO step succeeds. Two distinct retry paths:

* **`LocalAhead` / `RemoteAbsent`** — `pushAheadOrRedispatch`. On rejection,
  re-fetch and recurse into `configureAndExchange` with
  `attemptsRemaining - 1`. The new history relation may turn out to be
  `Diverged` (peer's commit means we now need to merge), in which case the
  recursive call dispatches to `handleDiverged` for full merge handling.
* **`Diverged` auto-merge** — `retryAfterPushReject`. Re-fetch, re-classify,
  rebuild the diff against the new remote tip. If zero new conflicts, redo
  `autoMerge` (resetHard new-remote, overlay local-wins, replaceFromWorkingTree,
  commitWithParents with the original `localSha` as parent #2, push). If new
  conflicts emerge: open a fresh conflict session (or, on the
  apply-from-session retry path, throw `SESSION_STALE` — the user must abort
  and re-run because their stored resolutions may now be wrong against the
  newer remote).

The DB churn is real: `replaceFromWorkingTree` rewrites the project's row set
on every retry. For the small project sizes this engine targets that's
acceptable, but a future optimiser could budget here if it ever bites.

### Coverage limitation

Fully-deterministic push-retry coverage requires inserting a peer-push
between our fetch and our push, which has no test seam in the current
synchronous IO block. `RemoteSyncEnginePushRetryTest` exercises the retry
path opportunistically via concurrent `runSync` calls from two engines
against a shared bare repo, asserting eventual consistency rather than
verifying the retry counter directly. A `JGitClient` interface refactor
would unlock fully-deterministic coverage and is tracked as a follow-up.

## Auto-sync (Phase 8)

Periodic syncs are driven by [`AutoSyncScheduler`](../src/main/kotlin/uk/me/cormack/lighting7/sync/AutoSyncScheduler.kt).
Lifecycle:

* Started from `Application.module()` once `State` is fully constructed; stopped from
  [`State.shutdown`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt) so the
  pollers don't outlive the DB.
* Owns one coroutine per project where `sync_configs.autoSyncEnabled = true`. The loop
  delays `autoSyncIntervalMs` (clamped to [`MIN_INTERVAL_MS`](../src/main/kotlin/uk/me/cormack/lighting7/sync/AutoSyncScheduler.kt)
  = 60s) before its first tick — a freshly-enabled auto-sync doesn't fire mid-form-submit.
* `PUT /sync/config` calls `scheduler.reschedule(projectId)` after committing. That cancels
  any existing loop and (re-)launches one if the new config still has auto-sync enabled,
  so toggling the flag or changing the interval takes effect immediately without a
  restart.
* Each tick re-reads the config, refuses to run when `enabled = false` or
  `autoSyncEnabled = false`, and **skips when a conflict session is pending** —
  auto-syncing under an active conflict would either bounce a 409 off the engine or
  silently re-enter the diff against stale resolutions. The skip is logged as
  `AUTO_SYNC_SKIPPED` once, on entry, not once per tick.
* `SyncException` from `engine.runSync` is caught (the engine has already written
  `RUN_FAILED`); unexpected `Throwable`s write a fresh `RUN_FAILED` and keep the loop
  alive. A single tick failure must never kill the project's auto-sync.
* Runs go through the engine with `SyncTrigger.AUTO`, which keeps a tick that changed
  nothing [out of the activity log entirely](#activity-log-phase-8).

The scheduler shares the engine's per-project mutex with manual `Sync now` clicks, so a
human-driven sync and an auto-sync tick can never run concurrently against the same
project.

### Backoff

Any failing tick — a rejected OAuth token, a missing credential, an offline network, a
pending conflict session — puts the project's loop into
[`nextDelayMs`](../src/main/kotlin/uk/me/cormack/lighting7/sync/AutoSyncScheduler.kt)
backoff: the base interval doubled per consecutive failure, capped at
`BACKOFF_CEILING_MS` = 1 h. From the 60s floor the waits run 2m, 4m, 8m, 16m, 32m, then
hourly. The cap is never allowed below the configured interval, so a 6-hourly sync is
never *shortened* into an hourly retry.

Two properties worth preserving:

* **Transition-only logging.** Entering the failing state logs one WARN naming the reason
  and the new interval; subsequent attempts log at DEBUG. Recovery logs one INFO plus an
  `AUTO_SYNC_RECOVERED` row — needed because the recovering tick is usually a quiet
  `NO_OP` that writes nothing, which would otherwise leave the activity feed ending on an
  ERROR with no way to tell "fixed" from "still broken".
* **Failure rows are *not* suppressed.** The engine's per-attempt `RUN_FAILED` is the
  "still broken as of an hour ago" signal whose absence caused the incident below; the
  backoff is what makes its volume reasonable (~24/day rather than ~1,440).

Counted per loop, in coroutine-local state, so `reschedule(projectId)` resets it for free.
That is why re-connecting GitHub (`persistIdentity`) and storing a PAT
(`PUT /sync/credentials`) both call `reschedule` — the backoff self-heals regardless, but
without them the reward for fixing credentials could be up to an hour of apparent silence.
A successful manual `Sync now` deliberately does *not* reset it: cancelling and relaunching
the loop on every manual sync would shift its phase for no real gain, and the next backed-off
tick recovers on its own.

> **Why this exists.** With a dead credential and the 60s floor, two projects generated
> ~2,880 failed runs and ~8,600 log rows a day, indefinitely — which also meant the 500-row
> activity log held nothing but that one failure. Note the flood was never OAuth-specific: a
> network failure reaches the scheduler as a bare `RuntimeException` (only 401/403 become
> `GitAuthException` in `JGitClient`), so an offline desk produced the same storm by a
> different route. That is why backoff covers *every* failure rather than the auth codes.

## Activity log (Phase 8)

`sync_log_entry` (machine-local) carries the operator-visible activity feed: one row per
noteworthy event, written by [`SyncLogger`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt).
Capped per project at [`MAX_ENTRIES_PER_PROJECT`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt)
= 2000; older rows are pruned on every write so the table never accumulates without
bound. (Injectable via the `SyncLogger` constructor, so a test can exercise the prune
boundary without writing 2000 rows in that many transactions.)

**A tick that changed nothing writes no rows at all.** `SyncTrigger.AUTO` suppresses the
three rows that only say "a run happened and found nothing" — `RUN_STARTED`,
`SNAPSHOT_NOOP`, and `RUN_DONE` when the outcome is `NO_OP`. Everything else still lands:
`SNAPSHOT_TAKEN`, a non-`NO_OP` `RUN_DONE`, `CONFLICTS_PENDING`, `RUN_FAILED`. `MANUAL` (the
default, so a new call site is loud until it opts out) logs exactly as it always has.

Without this the feature didn't work: at the 60s auto-sync floor a *healthy* project wrote
four rows a minute, so the cap held ~2 hours and every manual run or failure worth reading
had already scrolled out. The corollary is that a healthy desk's feed now sits still — "is
auto-sync alive?" is answered by `sync_configs.lastSyncedAt`, which is updated on every
non-conflict outcome including `NO_OP`, not by the presence of recent rows.

```
sync_log_entry
  id PK
  project_id FK -> projects (indexed)
  ts TEXT (indexed)               -- UTC instant
  level VARCHAR             -- INFO | WARN | ERROR
  event VARCHAR             -- stable code, see SyncLogEvent
  message TEXT
```

Stable event codes (see [`SyncLogEvent`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt)):

| Event | Emitted by | Notes |
|---|---|---|
| `RUN_STARTED` | `RemoteSyncEngine.runSync` (start) | Manual runs only — suppressed for `AUTO`. |
| `RUN_DONE` | `RemoteSyncEngine.runSync` (success) | Outcome + summary in the message. Suppressed for an `AUTO` run whose outcome is `NO_OP`. |
| `RUN_FAILED` | `RemoteSyncEngine.runSync` (catch) | `{code}: {message}` shape. |
| `CONFLICTS_PENDING` | `RemoteSyncEngine.runSync` | Conflict count + session id. |
| `APPLY_DONE` | `RemoteSyncEngine.applySession` (success) | Post-resolve commit pushed. |
| `APPLY_FAILED` | `RemoteSyncEngine.applySession` (catch) | Same shape as `RUN_FAILED`. |
| `SESSION_ABORTED` | `RemoteSyncEngine.abortSession` | Working tree reset. |
| `SNAPSHOT_TAKEN` | `SnapshotEngine.snapshot` | Carries short SHA + summary. |
| `SNAPSHOT_NOOP` | `SnapshotEngine.snapshot` | Nothing to commit. Suppressed for an `AUTO` run. |
| `AUTO_SYNC_SKIPPED` | `AutoSyncScheduler` | Conflict session pending. Written once on entry, not per tick. |
| `AUTO_SYNC_RECOVERED` | `AutoSyncScheduler` | Ticks succeeding again after one or more failures. |

`AUTO_SYNC_TICK` was retired — it was written immediately before `RUN_STARTED` and carried
nothing extra. The frontend renders `event` as an opaque string, so rows written before the
retirement still display.

Each write fans out a `cloudSyncLogAppended` WebSocket message
(see [`Sockets.kt`](../src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt))
so the frontend's activity feed updates live. Consumers don't need to re-fetch
`/sync/activity` while the WS is connected — but a fresh page-load reads from the
endpoint, so the persisted ring is the source of truth.

`GET /api/rest/projects/{id}/sync/activity?limit=…&beforeId=…` paginates newest-first.
`beforeId` is the smallest id from the previous page; `limit` is clamped to
[`MAX_LIST_LIMIT`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt) = 500.

## Attribution rendering (Phase 8)

Every snapshot / merge / resolve commit has a stable `[install:{shortUuid}]` marker in
its message (see "Snapshot commit format" above). The `installs.json` registry tracks
the friendly names that have ever pushed to the repo:

* On every snapshot, [`SnapshotEngine`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SnapshotEngine.kt)
  reads the current `installs.json` from the working tree **before** the wipe step,
  passes the entries to [`ProjectExporter.export`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ProjectExporter.kt)
  as `knownInstalls`, and the exporter writes back the union of `knownInstalls` and
  the local install row. Local install always wins on key clash so a renamed install
  propagates its new `friendlyName`.
* `git log` walks back through prior commits and the history is the long-term record;
  `installs.json` at HEAD is the union the diff-and-merge layer cares about.

`GET /sync/log` enriches each [`CommitInfo`](../src/main/kotlin/uk/me/cormack/lighting7/sync/JGitClient.kt)
with `installShortUuid` and `installFriendlyName` parsed via
[`CommitInfo.withAttribution`](../src/main/kotlin/uk/me/cormack/lighting7/sync/JGitClient.kt).
Commits without the marker (e.g. ones authored outside the engine) keep both fields
null. Unknown `shortUuid`s — peers who never showed up in `installs.json` at HEAD —
keep `installFriendlyName = null` so the UI can fall back to "(unknown @ short)".

## History pagination (Phase 8)

`GET /sync/log?limit=…&before={fullSha}` walks history backwards from `HEAD` (default)
or from `{fullSha}`'s **first parent** when the cursor is supplied. The first-parent
hop is what makes `before = lastSha-of-prev-page` advance the page without overlapping
the boundary commit. Limit is clamped to `MAX_LOG_LIMIT` = 500. The response is the
same `CommitInfo[]` shape as before, with the added attribution fields described above.

## Operational notes

* **Default export path**: `appDataDir().resolve("exports").resolve(projectUuid)`
  (`~/Library/Application Support/lighting7/exports/{projectUuid}/` on macOS).
  Caller-supplied paths are honoured verbatim.
* **Default sync working-tree path**: `appDataDir().resolve("sync")`,
  per-project at `<root>/{projectUuid}/repo/`. Override via
  `sync.workingTreeRoot = "/some/path"` in `local.conf`. Tests pass an
  explicit value to keep snapshots out of the user's real `appDataDir`.
* **JGit version**: pinned to `org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r`
  in `build.gradle.kts`. The 7.x line bumps to JDK 17+ which we already meet,
  but 6.10 has a smaller transitive footprint on the jlink runtime used by
  the Windows distribution.
* **java-keyring version**: pinned to
  `com.github.javakeyring:java-keyring:1.0.4`. Brings JNA in transitively;
  no separate JNA dep is declared. Used by the cloud-sync phase 4
  credential store.
* **Credential file path**: when `sync.credentialStore = "file"` (or the
  keychain backend isn't available), PATs are stored at
  `<appDataDir>/credentials.enc`. The file is created with permissions
  `0600` on POSIX filesystems.
* **DB**: SQLite on the lighting7 install path.
* **Sync-cycle memory**: peak heap during `runSync` is roughly bounded by
  2× the working tree's blob size — `JGitClient.walkTree` materialises
  every blob into a `Map<String, String>` on both the local and remote
  sides for the three-way diff. At ~5 KB per record this comfortably
  stays under 50 MB up to ~5000 records, which is well above any expected
  project size. Stress-test before acting on this number — the
  benchmark harness shape is sketched in
  [`docs/plans/followups.md` → `FU-SYNC-JGIT-STRESS-BENCH`](plans/followups.md);
  the trigger to run it is a real project crossing ~1000 records or a
  perceptible (>5s) `runSync` wall-clock.
* **Tests**: `src/test/kotlin/uk/me/cormack/lighting7/sync/` covers
  canonical-JSON determinism, round-trip byte-identity, UUID-collision /
  name-collision refusal, FK-resolution failure rollback, machine-local
  field stripping, `isCurrent` reset, JGit init/commit/log smoke tests,
  working-tree clean preserving metadata files, and snapshot end-to-end
  (commit on first run, no-op on second, deletion surfaces in the next
  commit).

## How to add a new synced field

1. Add the column to the relevant DAO file.
2. Decide portable vs. machine-local using the CLAUDE.md decision tree.
3. **Portable**: add the field to the matching DTO in `sync/dto/SyncDtos.kt`,
   pass it through `ProjectExporter` and `ProjectImporter`, and give it a
   non-default value in `testsupport/RichProjectFixture.kt` so the round-trip
   and clone tests actually exercise it. Project cloning needs no change — see
   "Project cloning".
4. **Machine-local**: don't put it in the sync DTO at all. Read and write it
   through the `Overrides` helper (`sync/Overrides.kt`); add a typed
   convenience accessor there if more than one call site needs it. The
   field never appears in exported JSON.
5. If the change isn't covered by `explicitNulls = false` /
   `encodeDefaults = false` (i.e. it's a required field with no default),
   that's a `formatVersion` bump — see "Format versioning".

## Venue repos

A venue (`projects.kind = VENUE`, venue plan sessions 2–3) syncs **as its own repo of published
versions** (D8, P7; `sync/VenueRepoSync.kt`). Its draft — the venue project's own rows — never
syncs (`FU-VENUE-DRAFT-SYNC`); a version is a frozen `VENUE_VERSION` project, and on the repo it is
a tagged commit.

**The layout.** The branch holds the latest published version in the usual export layout — with the
venue's own `project.json` (its uuid, name and description, `kind: VENUE`, the version's stage size)
— plus `venueVersion.json` (`VenueVersionJson`: `number`, `note`, `publishedAt`, `publishedBy`). Each
version is one commit, message `vN: note`, tagged `vN` (an annotated tag). Its tree is exactly the
version's export, scene images and prompt PDFs included.

**How a repo is attached.** The same `sync_configs` row as a show's, through the same Sync tab and
routes (`PUT …/sync/config`, credentials, disconnect, reconnect, auto-sync): one way to attach a
repo, whatever the project. What differs is what the routes do with it:

* `POST …/sync/run` (*Sync now*, and every auto-sync tick) on a venue runs `VenueRepoSync.exchange`
  instead of the snapshot pipeline: fetch the branch and the tags (into
  `refs/remotes/origin/tags/`, beside the local tags, never over them), refuse a remote that is not
  this venue's (`PROJECT_MISMATCH`) or too new (`FORMAT_TOO_NEW`), **materialise** every tag this
  desk lacks, reset the local branch onto the remote's, and **push** every version this desk holds
  that the remote lacks, oldest first, each as a commit on top tagged with its number. The result is
  the ordinary `SyncRunResult` — `FAST_FORWARDED` with `pulled` versions, `PUSHED` with `pushed`
  versions, `NO_OP` — its `message` naming them (`Fetched v3; the draft follows v3`).
* `POST …/sync/snapshot` on a venue is refused, 409 `VENUE_DRAFT_NOT_SYNCED`: the draft is never
  committed to the repo. Conflict sessions never open on a venue.
* A venue **version** has no repo of its own: the whole sync subtree answers it 409
  `VENUE_VERSION_READ_ONLY` (`refuseVersionSync`, reads included, since `GET …/sync/config` creates
  the row it answers with), and `AutoSyncScheduler` skips one.

**Materialising a tag** (`materialise`): the tag's tree is written out byte for byte
(`JGitClient.extractTree`), checked — `venueVersion.json` present and naming the tag's number, the
`project.json` this venue's — and imported through `KeptUuidCopy.fromArchive` as a `VENUE_VERSION`
project with a fresh uuid and every record uuid kept, its `venue_versions` row (number, note,
`publishedAt`, `publishedBy` from `venueVersion.json`) written in the same transaction. So History
reads the same on every desk, and **`venue_versions` is rebuilt from the tags** — it stays the
machine-local index (`MachineLocal` in `SyncCoverageTest`). **A rebuild only ever adds rows**: a
local version the remote lacks is pushed (or, on a pull-only desk, kept on this desk), never
deleted. A number both sides hold must be one publish — the same `note` and `publishedAt` — and a
pair that differs (two desks published it apart, before sharing a repo) is refused as
`VENUE_VERSION_TAKEN`, never overwritten: one venue's history has to be chosen by a person
(`FU-VENUE-DRAFT-SYNC`). Materialising never moves which project is current.

**What a pull does to the draft.** A draft with **no changes** against the installed version before
the pull follows the newest version in — `VenueVersions.replaceDraftWithInstalled`, a discard's work —
and a live venue reloads. A draft with changes of its own is kept as it is, and the result says so;
it is now based on an older version, and publishing it as the next would overwrite the newer one's
changes, which is the two-admins case the plan leaves to `FU-VENUE-DRAFT-SYNC`.

**Publish** (`POST /venues/{id}/publish`, P7). Without a repo attached it is session 2's local
publish (`KeptUuidCopy` of the draft). With one, under the venue lock and the venue's sync lock
(`RemoteSyncEngine.publishVenue`):

1. The number the draft was going to take is read **before** the pull: installed + 1.
2. **Pull first** — the whole exchange above. If that brought the number in (another desk published
   it while this draft was open), the publish is refused, 409 **`VENUE_VERSION_TAKEN`**, naming the
   other desk's note and publisher; the version is in History now, and the draft wants checking
   against it before publishing again (as the next number).
3. The draft is exported into the working tree with `venueVersion.json`, committed `vN: note` and
   tagged `vN`.
4. The branch and the tag are pushed together. A refusal of either — the tag taken in the race, or
   the branch moved on, which only another publish does — drops the local tag, puts the branch back
   on the remote's and refuses `VENUE_VERSION_TAKEN`. Nothing is left behind but the fetch.
5. Only then is the version made on this desk — **materialised from the tagged tree**, exactly as
   every other desk will read it.

**A pull-only install** (`sync.push = false`, §"Pull-only installs") exchanges without pushing —
`NO_OP` with the `PUSH_DISABLED_PREFIX` message when it holds versions the remote lacks — and
**refuses to publish a venue with a repo attached**, 409 `VENUE_PUBLISH_PULL_ONLY`: a `vN` made here
could never reach the repo, and would clash with the real `vN` the moment another desk published one.
A pull-only desk can still publish a venue with no repo, locally.

**Adding a venue's repo as a remote project** (`POST /cloud-sync/import`): the clone becomes a venue
whose draft is the branch's tip — the latest version — and every tag is materialised beside it
(`VenueRepoSync.materialiseAll`). A failure there leaves the venue and its repo attached; *Sync now*
finishes the job. A manual folder import of a venue's tree makes a venue with that draft and no
versions (a folder holds one version).

**The import order a fresh desk needs:** the venue's repo first, then each show on it — a show's
import (and its sync) is refused with `VENUE_MISSING` until its pinned version is here, naming the
venue and the repo it syncs from. `scripts/cloud-session.sh` reads each repo's `project.json` kind
and imports venues first.

`JGitClient` gained `tag` / `deleteTag` / `listTags`, `fetchTags` / `listRemoteTags`,
`pushWithTag`, `extractTree` and `hasPath` for it. `VenueRepoSyncTest` runs two desks on one bare
remote: publish and its tags, `VENUE_VERSION_TAKEN` on publish and on a clash, the clean and dirty
draft, a pull-only desk, and a show's pull refused until its venue is synced.

## How to add a new table

1. Add the `IntIdTable` / DAO pair in `models/`, with a `uuid` column if it's
   portable.
2. Add it to `ALL_TABLES` in `models/Schema.kt`. `SyncCoverageTest` now fails
   until step 3 is done — that's the forcing function.
3. Record its disposition in `SyncCoverageTest.dispositions`: `Portable` (with
   its export folder, and the parent's field name if the rows are embedded),
   `MachineLocal`, or `Excluded`. The last two need a stated reason.
4. **Portable only**: add a DTO to `sync/dto/SyncDtos.kt`, write it in
   `ProjectExporter`, read it in `ProjectImporter`, seed rows in
   `testsupport/RichProjectFixture.kt` **with a non-default value on every
   optional field** (canonical JSON omits defaults, so a field left at its
   default is invisible to the round-trip and clone tests), and add the
   project-delete cascade in `routes/projects.kt` plus
   `ProjectImporter.replaceFromWorkingTree`. Cloning is automatic. Renaming or
   removing a JSON field later is a `formatVersion` change; removing a required
   one is a `minReader` bump — and see v8 under "Format versioning" for when a
   merely *added* optional field needs a bump too.
5. **Machine-local**: prefer a `machine_overrides` row via `sync/Overrides.kt`
   for per-record fields. Give it its own local-only table only when the data
   is wholly machine-local rather than a per-record override (e.g.
   `sync_configs`). Either way it stays out of `ProjectExporter` /
   `ProjectImporter`. Decide explicitly whether `ProjectCloner` should carry it
   — it copies `machine_overrides` because a clone is same-machine, but that is
   a judgement per table, not a rule.
