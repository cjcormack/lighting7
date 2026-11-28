# Database

The embedded SQLite database: the pool, the schema migrations and one-shot data steps that run in `State.initDatabase`, and the time columns.

## Database

Uses Exposed ORM over an embedded SQLite file (`org.sqlite.JDBC`, pooled by HikariCP
with `maximumPoolSize = 1` — SQLite has a single writer and a larger pool produces
`SQLITE_BUSY` under load). The DB path comes from `database.path` in `local.conf`,
defaulting to `<appDataDir>/lighting7.db`. Tables auto-create on startup via
`SchemaUtils.createMissingTablesAndColumns`.

SQLite is the only supported backend. `StateMigrations.kt` is gone;
`state/InstallBootstrap.kt` holds only the install-identity row, and explains what
was removed and why.

**Two schema migrations exist**, and both run in `State.initDatabase` **before**
`createMissingTablesAndColumns` — the exception to `InstallBootstrap.kt`'s "after the schema is
created", for the reason their KDocs give: the reconciliation would otherwise create the new shape
empty beside the old one. Each backs the database up first (`VACUUM INTO`), then converts in one
transaction, rebuilding the tables it changes from the model:

- `state/CueModelMigration.kt` (cue model plan D5) moves a stack's list off `cues` onto
  `cue_stack_entries`, in raw SQL, rebuilding `cues` and `prompt_book_anchors`. It runs only while
  `cues` still has `cue_stack_id`; its backup is `<db>.pre-cue-model-<utc>.db`. `CueModelMigrationTest`
  runs it over `src/test/resources/db/pre-cue-model.sql`, the old shape frozen.
- `state/TerminologyMigration.kt` (5, terminology plan §5) renames the tables and columns that hold
  palettes, FX and attributes into the glossary's words — `templates`, `template_rows` and
  `template_effects` are `palettes`, `palette_rows` and `palette_fx`; `look_effects`,
  `cue_ad_hoc_effects` and `cue_property_assignments` are `look_fx`, `cue_ad_hoc_fx` and
  `cue_attribute_assignments`; columns `beat_division`, `size`, `n_shot`, `distribution`,
  `spread_blocks`, `spread_groups`, `template_id`, `property_name`, `property_mask`, `usage`,
  `category`, `effect_type`, `effect_id`, `effect_mode` and `compatible_properties` are `cycle`,
  `amount`, `runs`, `spread_order`, `spread_segments`, `spread_parts`, `palette_id`, `attribute`,
  `attribute_mask`, `family`, `family`, `fx_type`, `fx_id`, `fx_mode` and `compatible_attributes`,
  and the boolean `step_timing` / `default_step_timing` are `timing` / `default_timing`, `CYCLE` or
  `HEAD` — and renames the stored values that are also wire values through `sync/LegacyNames.kt`:
  Orders, `tmpl:` references, the `dimmer` and `palette` families, the `pressTemplate` and
  `stackEffectSize` surface bindings, and the sync engine's record keys. Its backup is
  `<db>.pre-terminology-<utc>.db`. It runs after `CueModelMigration`, whose raw SQL says the old
  names, and before steps 1–4, whose raw SQL says the new; it runs while the database is below 5 and
  still holds an old name, and records nothing itself — `markTerminologyRenamed` sets 5 once step 4
  is behind it, so a database at 3 still gets step 4. `TerminologyMigrationTest` runs it over
  `src/test/resources/db/pre-terminology.sql`, a database main wrote; never regenerate either frozen
  file from a later commit.

**Four one-shot data steps follow them**, inside `initDatabase`'s transaction after the schema
exists, each run once per database and recorded in **`PRAGMA user_version`** — the database
header's counter; a later one-shot step takes the next number:

- `state/CueEntryUuidMigration.kt` (1, cue model plan session 3) gives every cue's one stack
  entry its cue's uuid and every entry its 0-based position as its ordinal, so two desks write the
  same record for the same place once entries are sync records (`formatVersion` 32). It must
  never run twice: its KDoc says why.
- `state/DeferredLookEffectsMigration.kt` (2, absolute-looks plan P9) deletes every Look FX
  that names no target of its own, logging each Look by name.
- `state/LookLayerTargetsMigration.kt` (3, absolute-looks plan P9) clears the targets of every cue
  layer applying a Look, logging each cue by name.
- `state/FxLibraryMigration.kt` (4, terminology plan §5) rewrites every Look, cue and palette FX
  onto today's FX library through `sync/LegacyNames.kt`, which the importer shares, logging each by
  its owner's name. It must never run twice: it reads a sweep naming no `pingPong` as one written
  before the parameter went.

**Step 6 runs after that transaction**, once step 5 is recorded: `state/AttributeNamesMigration.kt`
(terminology plan session 6) renames the surface bindings' attribute targets (`fixtureProperty`,
`groupProperty`, `selectionProperty` and every `propertyName` in a binding, a flash's nested target
included) and Circle's, Figure 8's and Random Position's `panCenter` / `tiltCenter` parameters,
through `sync/LegacyNames.kt`. A database holding none of them is only marked 6; one that holds some
is backed up first to `<db>.pre-attribute-<utc>.db`, which `VACUUM INTO` cannot take inside a
transaction. A stored script or project FX definition still saying `panCenter` / `tiltCenter` is named
in a warning, never rewritten.

**Step 7 follows it**: `state/FxConsolidationMigration.kt` (fx-settings plan session 2) rewrites every
Look, cue and palette FX on one of the eleven level waves onto Wave through `LegacyNames.consolidateFx`,
which the importer shares — its shape and its parameters at the values the old script read, a beam FX
naming no attribute given the one it drove — logging each by its owner's name. A database holding none
is only marked 7; one that holds some is backed up first to `<db>.pre-fx-consolidation-<utc>.db`.
`FxConsolidationMigrationTest` runs it over `src/test/resources/db/pre-fx-consolidation.sql`, a database
at 6 holding every old id; never regenerate it. A script naming an old id is left alone: it still runs.

**Step 8 follows it**: `state/FxSiblingsMigration.kt` (fx-settings plan session 3) rewrites every
Look, cue and palette FX on one of the six movements onto Path, a Colour Cycle's `fadeRatio` as
`between` and `fade`, and the Flicker family's, Lightning Strike's and Colour Strobe's parameters at
their new names, through `LegacyNames.absorbFx`, which the importer runs after `consolidateFx`. A
database holding none is only marked 8; one that holds some is backed up first to
`<db>.pre-fx-siblings-<utc>.db`. `FxSiblingsMigrationTest` runs it over
`src/test/resources/db/pre-fx-siblings.sql`, a database at 7; never regenerate it. Colour Wave and
Colour Fade are left on their own ids, which stay registered as legacy.

**Step 9 follows it**: `state/MoveInDarkRowFlagMigration.kt` (move-in-dark plan D13) retires the
per-row `cue_attribute_assignments.move_in_dark` flag — every cue with a flagged row takes the cue
setting *Early*, then `ALTER TABLE … DROP COLUMN`, each cue logged by name. It runs once the
reconciliation has given `cues` its `move_in_dark_mode` column. A database with no such column is only
marked, one with no flagged row loses the column unbacked, one that flags some is backed up first to
`<db>.pre-move-in-dark-<utc>.db`. `MoveInDarkRowFlagMigrationTest` runs it over
`src/test/resources/db/pre-move-in-dark.sql`, a database at 8; never regenerate it.

**Step 10 follows it**: `state/PaletteLayerMigration.kt` (palette-references plan D17) turns every
**plain** cue palette layer — a value palette's, enabled, amount 1, `OVERRIDE`, no stomp, not timed,
masked to nothing or its palette's family, with no contributing layer above it asserting that family
on a head it reaches and no own row it would then beat — into `pal:` rows on the cue's own rows, one
per layer target and attribute, an own row on the same target and attribute leaving that one out, and
deletes the layer, each cue logged by name. Every other palette layer stays a layer, logged by cue and
palette with why. The decision is `state/PaletteLayerConversion.kt`, read from the stored patch with no
rig loaded, which the importer runs over a v46 archive. A database converting none is only marked 10;
one that converts some is backed up first to `<db>.pre-palette-references-<utc>.db`.
`PaletteLayerMigrationTest` runs it over `src/test/resources/db/pre-palette-references.sql`, a database
at 9; never regenerate it.

**Step 11 follows it**: `state/CueNumberMigration.kt` makes every cue number a number — 0 to
9999.9999, at most four decimal places, in its canonical spelling (`2.10` is `2.1`) — clearing one that
is not (`S1-3`, `14A`, `1.2.3`) and the later of two spellings of one number in a stack, each such entry
then numbered from its place, the stacks it touched renumbered, each change logged. A database holding none is only marked 11; one that holds some is
backed up first to `<db>.pre-cue-numbers-<utc>.db`. The importer clears an older archive's the same
way. `CueNumberMigrationTest` seeds a step-10 database through the model, since the schema is unchanged.

**Step 12 follows it**: `state/OwnTempoMigration.kt` (fx-speed plan D5) rewrites every Look, cue and
palette FX on *Seconds* as Own tempo — a beat FX `own_bpm` 120 at twice its `cycle`, the same output to
the bit (`OwnTempoEquivalenceTest`), a wall-clock FX (a built-in or a project definition on the wall clock, told by name) Natural, both
losing their masters — then drops the three `seconds` columns, each FX logged by its owner's name. A
database with no column is only marked 12, one whose columns flag nothing loses them unbacked, one that
flags some is backed up first to `<db>.pre-own-tempo-<utc>.db`. The importer reads an older archive's
`seconds` the same way. `OwnTempoMigrationTest` runs it over `src/test/resources/db/pre-own-tempo.sql`,
a database at 11; never regenerate it.

**Step 13 follows it**: `state/RetiredRemoteAccessMigration.kt` (cloud-p1 plan D18) drops what the ngrok
tunnel and the desk's own OAuth server stored — `mcp_oauth_clients`, `mcp_oauth_grants` and
`remote_access_settings`' `enabled` and `domain`, keeping its scripts, effects and commands switches — and,
once the credential store exists, deletes the `ngrok:authtoken` credential and the agents downloaded under
`<appDataDir>/ngrok`. A database holding no client or grant loses them unbacked; one that holds some is
backed up first to `<db>.pre-retired-remote-access-<utc>.db`. `RetiredRemoteAccessMigrationTest` runs it
over `src/test/resources/db/pre-retired-remote-access.sql`, a database at 12; never regenerate it.

**Step 14 follows it**: `state/BuskPadStackMigration.kt` (busk-stack plan D9) rebuilds `busk_pads` from the
model while its `busk_pad_exactly_one_ref` CHECK does not name `cue_stack_id`, rows copied by id, so a pad
may name a cue stack: the reconciliation adds the column, but SQLite cannot alter a CHECK, and the old
three-arm one refuses a row naming only a stack. The CHECK is read by its parentheses, since the added
column lands after it in the DDL. A database whose CHECK already names it, or that has none, is only
marked; an empty table is rebuilt unbacked; one holding pads is backed up first to
`<db>.pre-busk-stack-<utc>.db`. `BuskPadStackMigrationTest` runs it over
`src/test/resources/db/pre-busk-stack.sql`, a database at 13; never regenerate it.

**Step 15 follows it**: `state/SurfaceGrammarMigration.kt` (surface-setup plan session 1) puts every surface
binding in the grammar — a `Binding`, `{does, on, how}` — through `midi/SurfaceGrammar.kt`, the translate the
archive reader shares (P4): an encoder holding a button binding becomes its push part, an int-only cue or stack
its uuid in its project, a flash's max a percentage, each on/off form its toggle with a How; a row it cannot
translate keeps its bytes and reads as unknown. It drops `uq_surface_binding_slot`, which had no part: the
reconciliation has added `part` and `uq_surface_binding_part_slot` beside it, and SQLite drops an index without a
rebuild. A database with no binding to translate is only marked; one with some is backed up first to
`<db>.pre-surface-grammar-<utc>.db`. `SurfaceGrammarMigrationTest` runs it over
`src/test/resources/db/pre-surface-grammar.sql`, a database at 14 holding every old variant; never regenerate it.

**Step 16 follows it**: `state/AutoNumberTidyMigration.kt` (venue-rig-fixes plan D9) derives every stack's auto
numbers once from position and its typed numbers, as every stack write now does, so an auto number an earlier rule
kept in place (an auto Q40 after a typed Q30) follows the order; typed numbers are untouched, each change logged by
stack and cue. A database changing none is only marked; one that changes some is backed up first to
`<db>.pre-auto-numbers-<utc>.db`. `AutoNumberTidyMigrationTest` runs it over
`src/test/resources/db/pre-auto-numbers.sql`, a database at 15; never regenerate it.

**Step 17 follows it**: `state/SurfaceJobsMigration.kt` (surface-setup plan session 5) turns every project's strip
bindings into **jobs** (`surface_jobs`) through `midi/SurfaceStripJobs.kt`, the translate the archive importer shares
(P4): a region's strips on one layer become one job of the kind most of them name, a strip of the other kind its
controls' own bindings where they have none, and every strip row is deleted. A database with no strip row is only
marked; one with some is backed up first to `<db>.pre-surface-jobs-<utc>.db`. `SurfaceJobsMigrationTest` runs it over
`src/test/resources/db/pre-surface-jobs.sql`, a database at 16; never regenerate it.

**Step 18 follows it**: `state/GitHubRetirementMigration.kt` (cloud-p2 plan D8) drops `oauth_identities`, the
GitHub sync identity's mirror, and — once the credential store is open — deletes the OAuth tokens and the PAT
of every repo a project synced or once synced with. A database without the table is only marked; one with it
loses it — unbacked when it holds no identity, else after a backup to `<db>.pre-github-retirement-<utc>.db` — and
has its credentials deleted. `GitHubRetirementMigrationTest` runs it over
`src/test/resources/db/pre-github-retirement.sql`, a database at 17; never regenerate it.

A new one-shot step moves `SCHEMA_USER_VERSION` in `state/MigrationSupport.kt` to its number: a restore
refuses a backup whose database is past it (`BACKUP_TOO_NEW`), and `RestoreApplyTest` fails until it moves.

Otherwise the bet stands on there being exactly one database (the dev desk), and it
expires the moment a second install exists — the Windows MSI ships an upgrade path.
Before making a non-additive schema change once anything is deployed, write a
migration; `InstallBootstrap.kt` says where it plugs in and what ordering constraint
bit last time, and `CueModelMigration.kt` and `TerminologyMigration.kt` are the worked examples.

### Time columns

Every point in time is a `utcInstant("…")` column (`models/timeColumns.kt`) — timezone-aware
`TEXT`, always UTC, `'2026-09-14 12:34:56.789Z'`, millisecond resolution. **Not**
`exposed-java-time`'s plain `timestamp()`, which on SQLite writes *local* wall-clock text with no
offset, so the same row means a different moment after a DST change or a move between machines.

Every elapsed interval is `duration("…")` — `java.time.Duration`, stored as **nanoseconds** in a
`BIGINT`. That is the same SQL type the schema used for millisecond `Long`s, and
`SQLiteDialect.supportsColumnTypeChange` is `false`, so nothing would notice a column holding one
unit while the code reads the other: never give a `duration()` column an `_ms` name.
`TimeColumnsTest` asserts both rules.

Two helpers, and each exists to close a hole:

- `nowUtc()` — the clock for anything stored. Truncated to milliseconds, so an in-memory stamp
  and the row it was written to can never disagree.
- `Instant.toIsoUtc()` — the only way an instant reaches the wire. Fixed three-digit fraction, so
  the string is **sortable**; `Instant.toString()` is not, dropping the fraction on an exact second.

On the wire, instants are ISO-8601 `String` and durations stay `…Ms: Long`. Sync JSON carries
neither an instant nor a `Duration`: `ProjectExporter` / `ProjectImporter` convert durations to
`…Ms: Long` at the boundary, which is what keeps `formatVersion` at 11.

Key tables:
- `DaoProjects` - Project definitions
- `DaoScripts` - Lighting script source code
