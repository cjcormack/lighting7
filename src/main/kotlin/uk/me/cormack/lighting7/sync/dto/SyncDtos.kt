package uk.me.cormack.lighting7.sync.dto

import uk.me.cormack.lighting7.fixture.lantern.ShutterBlade
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.fx.FxMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.LookFxSpec
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.TIMING_CYCLE
import uk.me.cormack.lighting7.models.TriggerType
import uk.me.cormack.lighting7.scripts.ScriptType

/**
 * Phase-1 sync DTOs. Every record references peers by **UUID, not int id** — int ids are
 * local-only handles. Property names mirror the DAO column names where reasonable so reading
 * a JSON file maps directly back to the schema. See `docs/sync-engineering.md`.
 *
 * Note: deliberately separate from the existing API DTOs in `models/` — the sync wire format
 * must be stable across schema-internal refactors (e.g. renaming a REST DTO field shouldn't
 * silently change the synced JSON shape).
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FormatVersionJson(
    // v40: a stored FX's `elementMode` may be `WHOLE` (FX editor follow-ups plan session 2), which a
    // v39 reader cannot parse. `minReader` moves to **40**: a v39 reader would run each such FX as
    // its stored default, `PER_FIXTURE`, warning only in its log. See `docs/sync-engineering.md`
    // §"Version 40 — Fixtures".
    // v39: a surface binding's attribute targets are `fixtureAttribute`, `groupAttribute` and
    // `selectionAttribute`, and theirs and `encoderBankSet`'s `propertyName` is `attribute`, a
    // flash's nested target too; Circle's, Figure 8's and Random Position's `panCenter` and
    // `tiltCenter` are `panCentre` and `tiltCentre` (terminology plan session 6). Read through
    // `sync/LegacyNames.kt`. `minReader` moves to **39**: a v38 reader would hold every such binding
    // as one it does not know and run each centre at its default. See `docs/sync-engineering.md`
    // §"Version 39 — attributes and centres".
    // v38: the archive in the glossary's words (terminology plan session 4). `templates/` is
    // `palettes/`, `cueAdHocEffects/` `cueAdHocFx/` and `cuePropertyAssignments/`
    // `cueAttributeAssignments/`; a stored FX's fields are `fxType`, `family`, `attribute`, `cycle`,
    // `order`, `timing` (`CYCLE` / `HEAD`, once the boolean `stepTiming`), `amount`, `runs`,
    // `segments` and `parts`, a Look's and a palette's FX are `fx`, a palette is a cue layer's and a
    // pad's `paletteUuid`, and the Order, family, `pal:` and binding values moved with them. Every old
    // name is read through `sync/LegacyNames.kt`, so the reader takes any archive from v5. `minReader`
    // moves to **38**: a v37 reader would find every renamed required field missing, and v38 also
    // implies `ProjectJson.fxLibrary`, which it no longer writes. See `docs/sync-engineering.md`
    // §"Version 38 — the glossary's words".
    // v30: Size, direction and N-shot (effects plan session 1, venue-visit R4.1). `LookFxJson`,
    // `PaletteFxJson` and `CueAdHocFxJson` gain `size` (default 1.0), `direction` (default
    // `FORWARD`) and `nShot` (default null), each omitted at its default. The writer's number moves by
    // the sharp-edge rule: a v29 reader would run a 40 % breathe at full size, a backward cycle
    // forward and a one-shot forever, and its next wipe-then-export push would write all three away.
    // `minReader` stays at **5** — every field defaults to what an older row meant. See
    // `docs/sync-engineering.md` §"Version 30 — Amount, direction and runs".
    // v31: stored locate. `FixturePatchJson` gains `locate` (default empty, omitted): the pan and tilt
    // bytes Locate points each of a patch's units at (venue-visit plan D6, D17). A patch with none
    // exports byte-for-byte as at v30. The writer's number moves by the sharp-edge rule: a v30 reader
    // would import every patch with no stored locate and its next wipe-then-export push would write
    // them away for every peer. `minReader` stays at **5** — a missing locate is the centre. A venue
    // patch's locate is part of its Focus facet, so a show's override of it travels in `showOverrides/`
    // under the same number. See `docs/sync-engineering.md` §"Version 31 — stored locate".
    // v32: cue stack entries (cue model plan session 3, §3.9). A stack's list leaves the cue files:
    // `cues/{uuid}.json` drops `cueStackUuid`, `sortOrder`, `cueNumber`, `cueNumberAuto`,
    // `autoAdvance`, `autoAdvanceDelayMs` and `cueType`, and each place in a list is its own record,
    // `cueStackEntries/{uuid}.json` ([CueStackEntryJson]) — a double `ordinal` (P6), the number only
    // when explicit or not derivable (P5), and a marker as an entry with a label. `promptBookAnchors/` names its entry
    // (`entryUuid`) instead of a cue. A v31 reader would import every cue with no stack and its next
    // wipe-then-export push would write every stack empty for every peer, so the writer moves;
    // `minReader` stays at **5** — the reader still takes a v5–v31 archive's lists from its cue
    // files ([LegacyCuePlacementJson]). See `docs/sync-engineering.md` §"Version 32 — cue stack
    // entries".
    // v33: the spread's shape (effects plan session 2, venue-visit R4.2). `LookFxJson`,
    // `PaletteFxJson` and `CueAdHocFxJson` gain `spread` (default 1.0), `wings` and `blocks`
    // (default 1) and `groups` (default 0, off), each omitted at its default. The writer's number moves
    // by the sharp-edge rule: a v32 reader would run a 50 % ripple over the whole cycle and two wings
    // as one, and its next wipe-then-export push would write all four away. `minReader` stays at
    // **5** — every field defaults to what an older row meant. See `docs/sync-engineering.md`
    // §"Version 33 — the spread's shape".
    // v34: one effect over a selection (effects plan session 3, venue-visit R4.3). A `LookFxJson`
    // or `CueAdHocFxJson` may say `targetType: "selection"` with `targetKey` the list's canonical
    // JSON. The writer's number moves by the sharp-edge rule: a v33 reader would store a target it
    // cannot spawn, so every cue and Look holding one would skip that effect. `minReader`
    // stays at **5** — no field changed. See `docs/sync-engineering.md` §"Version 34 — one FX over a
    // selection".
    // v35: speed masters on a cue (venue-visit plan S16). `CueJson` gains `speedMasters` (default
    // empty, omitted): the masters the cue sets on GO, each a master's uuid and a BPM
    // ([CueSpeedMasterJson]). A cue that sets none exports byte-for-byte as at v34. The writer's number
    // moves by the sharp-edge rule: a v34 reader would import every cue without its tempos and its next
    // wipe-then-export push would write them away for every peer. `minReader` stays at **5** — a missing
    // list sets nothing. See `docs/sync-engineering.md` §"Version 35 — speed masters on a cue".
    // v36: fixture colours (tables-colours plan D7, D10). `FixturePatchJson` and `FixtureGroupJson` gain
    // `colour` (default null, omitted), and a show's recolours of its venue's patches and groups travel as
    // `showColours/{uuid}.json` ([ShowColourJson]). A project with no colours exports byte-for-byte as at
    // v35. The writer's number moves by the sharp-edge rule: a v35 reader would import every patch and
    // group without its colour and its next wipe-then-export push would write them away for every peer.
    // `minReader` stays at **5** — a missing colour is none. See `docs/sync-engineering.md`
    // §"Version 36 — fixture colours".
    // v37: effect runs (stack-timeline plan D7, §3.4). `CueAdHocFxJson` gains `runUuid` (default
    // null, omitted): the run key ad-hoc effects of neighbouring cues share, for a GO to hand the running
    // effect on (stack-timeline plan session 2). A show with no runs exports byte-for-byte as at v36. The writer's number moves by the
    // sharp-edge rule: a v36 reader would import every effect out of its run and its next
    // wipe-then-export push would write the keys away for every peer. `minReader` stays at **5** — a
    // missing key is no run. See `docs/sync-engineering.md` §"Version 37 — FX runs".
    //
    // v29: Seconds. `LookFxJson`, `PaletteFxJson` and `CueAdHocFxJson` gain `seconds`
    // (default false, omitted): an effect on its own cycle, with no speed master, its `beatDivision`
    // then seconds. The writer's number moves by the sharp-edge rule: a v28 reader would import a
    // ten-second breathe as ten beats of master 1 and its next wipe-then-export push would write the
    // flag away for every peer. `minReader` stays at **5** — a missing flag is a master. See
    // `docs/sync-engineering.md` §"Version 29 — Seconds".
    //
    // v28: centre-relative positions. A palette's position row may say `pos:<pan>,<tilt>` —
    // centre-relative coarse DMX, -128..127 about DMX 128 (venue-visit plan D3) — beside the `deg:`
    // travel degrees every earlier row holds. No field moved (`PaletteRowJson.value` is a string),
    // but its meaning did: a v27 reader parses a `pos:` row as no intent and runs every new position
    // palette as nothing. `minReader` stays at **5** — a `deg:` row still resolves as angles. See
    // `docs/sync-engineering.md` §"Version 28 — centre-relative positions".
    //
    // v27: venue parks. A venue's `parkedChannels/` were already in its versions; from v27 a show on a
    // venue **inherits** its pinned version's parks, overlaid by its own (a re-park at another value),
    // and gains `showParkReleases/{uuid}.json`, the venue parks it releases for its run, keyed by
    // universe and channel. The writer's number moves by the sharp-edge rule: a v26 reader would drop
    // every release and its next wipe-then-export push would write them away for every peer, and would
    // run a show on a venue without the venue's parks. `minReader` stays at **5** — a missing folder is
    // no releases. See `docs/sync-engineering.md` §"Version 27 — venue parks".
    //
    // v26: lamps. `FixturePatchJson` gains `lampWatts` and `lampKelvin`: a lantern-hung patch's own
    // lamp power and colour temperature, null — omitted — taking the lantern library's. A patch with
    // neither exports byte-for-byte as at v25. The writer's number moves by the sharp-edge rule: a v25
    // reader imports every lamp as its lantern's and its next wipe-then-export push writes them away
    // for every peer. `minReader` stays at **5** — a missing lamp is the lantern's. See
    // `docs/sync-engineering.md` §"Version 26 — lamps".
    //
    // v25: model textures. No DTO changes: a `MODEL` object's `parts` ride in `StageElementJson.params`,
    // and a part's `texture.image` names a file in `sceneImages/`. The writer's number moves for v23's
    // reason: a v24 reader reconciles that folder to the paint it reads as referenced, so its next push
    // would delete every model texture. `minReader` stays at **5**: a v24 archive imports unchanged.
    //
    // v24: venues sync (venue plan session 4, §3.4). `project.json` gains `kind` (absent = SHOW, so a
    // standalone show's is unchanged), a show's pin — `venueUuid`, `venueVersion` — and `venueRepo`, a
    // hint naming where its venue syncs; a show on a venue gains `showHides/{uuid}.json`, its hides of
    // venue records. A show's archive carries only its own rows: its links into the venue are the
    // venue records' uuids, resolved on import against the pinned version's snapshot, which must be on
    // the desk (`VENUE_MISSING` otherwise). A venue syncs as its own repo, one tagged commit per
    // published version, `venueVersion.json` beside the usual layout. The number moves for v4's reason
    // — a v23 reader would import a pinned show as a standalone one whose links name rows it does not
    // have, and its next push would write the pin and the hides away. `showOverrides/{uuid}.json` (session
    // 5), a show's facet overrides of venue patches, was reserved by the first v24 reader — which refuses
    // an archive holding one — so session 5 writes it without a bump, and a session-4 desk refuses a show
    // carrying overrides rather than loading a different rig.
    // `minReader` stays at **5**: every key defaults, and a v23 archive imports unchanged.
    //
    // v23: painted cloths (scrim plan session 1, P2). No DTO changes: a drape's `fabric` and a drape's
    // or a flat's `paint` (`{front?, back?}`, each an image's SHA-256) ride inside `StageElementJson`'s
    // params, which already travel whole. What moves the number is the new top-level
    // `sceneImages/{sha256}.{png|jpg}` folder — the images, raw bytes with no JSON record, the file
    // its own identity — for v4's reason: a v22 reader would not preserve the folder through its
    // wipe-then-export push and would delete every peer's images. `minReader` stays at **5**: a
    // missing folder is no images, and a v22 archive imports unchanged.
    //
    // v22: fitted media (fixture optics plan session 3). `FixturePatchJson` and `PatchPlacementJson`
    // each gain `media` ([FittedMedia]): what a unit has loaded in its loadable settings, as a nested
    // `{slots: {<property>: {<option>: {gel?, gobo?}}}}` naming only the options that differ from the
    // type's stock. Null is omitted, so a rig with nothing fitted exports byte-for-byte as at v21. The
    // writer's number moves by the sharp-edge rule: a v21 reader imports every unit with the stock
    // string and its next wipe-then-export push writes the fitted media away for every peer.
    // `minReader` stays at **5** — missing media is the stock. See `docs/sync-engineering.md`
    // §"Version 22 — fitted media".
    //
    // v21: cue events (stage-view plan session 9). `CueJson` gains an `events` list
    // ([CueEventJson]): a patch by uuid, the trigger's name on its fixture type, an `offsetMs` and a
    // sort order. Empty is omitted, so a show with no cannon cues exports byte-for-byte as at v20.
    // The writer's number moves by the sharp-edge rule: a v20 reader imports every cue without its
    // events and its next wipe-then-export push writes them away for every peer. `minReader` stays
    // at **5** — missing events are none. Spent tubes (`effect_tube_state`) are machine-local and
    // never travel. Went without `FU-AUTH-ATTRIBUTION`'s columns, as v18–v20 did (plan §11 Q2).
    // See `docs/sync-engineering.md` §"Version 21 — cue events".
    //
    // v20: scenery on cues, stacks and Looks (stage-view plan session 8). `CueJson`, `CueStackJson`
    // and `LookJson` each gain a `scenery` list ([SceneryChangeJson]): an element by uuid, its
    // states as a nested object, and on a cue a `transitionMs`. Empty is omitted, so a show with no
    // scenery exports byte-for-byte as at v19. The writer's number moves by the sharp-edge rule: a
    // v19 reader imports every cue, stack and Look without its scenery and its next wipe-then-export
    // push writes it away for every peer. `minReader` stays at **5** — missing scenery is none.
    // Went without `FU-AUTH-ATTRIBUTION`'s columns, as v18 and v19 did (plan §11 Q2).
    // See `docs/sync-engineering.md` §"Version 20 — scenery on cues, stacks and Looks".
    //
    // v19: the lantern and its focus (stage-view plan session 7). `FixturePatchJson` and
    // `PatchPlacementJson` each gain `lanternType`, `zoomDeg`, `lampRotationDeg`, `shutters` (four
    // `{depth, angleDeg}`, nested, not a string), `gateRotationDeg`, `iris` and `focusSoftness`,
    // every one null by default and omitted then, so a patch with no lantern exports byte-for-byte as
    // at v18. The writer's number moves by the sharp-edge rule: a v18 reader imports every lantern as
    // its kind's default with its blades out, and its next wipe-then-export push writes the focus
    // away for every peer. `minReader` stays at **5** — a missing focus is an unfocused lantern.
    // See `docs/sync-engineering.md` §"Version 19 — lanterns and focus".
    //
    // v18: the scene document (stage-view plan session 2). Two new record folders,
    // `stageElements/` and `stageViewpoints/`: named venue and set elements with each kind's
    // `params` as a nested object, and saved viewpoints, a seat view naming its seating element by
    // `seatElementUuid`. `minReader` stays at **5** — both folders read as empty when missing, so
    // every older archive imports with no scene. The writer's number moves by the sharp-edge rule:
    // a v17 reader imports no scene and its next wipe-then-export push writes none back, deleting
    // every peer's venue. Went without `FU-AUTH-ATTRIBUTION`'s columns (plan §11 Q2: passed).
    //
    // v17: a body's roll. See `docs/sync-engineering.md` §"Version 17 — fixture roll".
    //
    // v16: the operator's head number. `FixturePatchJson.headNumber` carries it (null — omitted —
    // when a head is unnumbered, so an export with none is byte-identical to v15). It bumps for
    // v14's reason: a v15 reader imports every patch unnumbered and writes the numbers away on its
    // next wipe-then-export push. `minReader` stays at **5** — a missing number is unnumbered.
    //
    // v15: a variable-length fixture's length. `FixturePatchJson.lengthM` and
    // `PatchPlacementJson.lengthM` carry a lightstrip's installed length and each of its segments'
    // (`docs/fixtures-engineering.md` §"Variable-length fixtures"). Both default to null and are
    // omitted then, so an export with no lengths is byte-identical to v14. It still bumps, for v14's
    // reason: a v14 reader imports the strip at its type's default and writes the length away on its
    // next wipe-then-export push. `minReader` stays at **5** — a missing length is the default.
    //
    // v12: the busk rig (`docs/plans/completed/busk-further-plan.md` §3.7). A new `buskRig/`
    // folder — one document per **row** with its tiles nested, tiles naming a group or a patch by
    // uuid and a cell by its element key — beside `buskPages/`. The plan's first draft said one
    // `buskRig.json` at the top level; session 2 amended that, because `RecordHasher` treats every
    // top-level file as metadata and a rig outside the record scan would never propagate through
    // the three-way diff. `minReader` stays at **5**: the folder reads as empty when missing, so a
    // v11 archive imports with an empty rig — which is today's band (D1). The writer's number
    // moves for v10's reason: a rig changes what the band offers and what *Next* walks, so a v11
    // reader — which would import no rig and on its next wipe-then-export push write none back,
    // deleting every peer's — must refuse the repo instead.
    //
    // v11: control-surface binding payloads. No folder or field changes — `targetPayload` stays
    // an opaque string — but what it may *contain* grows in two ways. New `type` discriminators
    // (`selectionProperty`, `selectTarget`, `clearSelection`, `locateSelection`; more in later
    // sessions of `docs/plans/completed/midi-surface-plan.md`), and a `cueUuid` / `stackUuid` beside the
    // int on `fireCue` / `cueStack*`, which is what finally lets a clone or a cross-install import
    // keep its cue bindings (`FU-SYNC-BINDING-PAYLOAD-UUIDS`, first half). `minReader` stays at
    // **5**: the reader decodes each binding row on its own and keeps an undecodable one as a
    // dead, rebindable `unknown` target rather than refusing the project, and a uuid it does not
    // read is a field it ignores. The writer's number moves because a v10 reader had neither
    // tolerance — one new discriminator failed its whole binding load — so it must refuse the repo.
    //
    // v10: the busk layout, and a Look on a cue slot. A new `buskPages/` folder — one document per
    // page with its columns, banks and pads nested, pads naming templates, Looks and cues by uuid
    // — and `CueSlotJson.lookUuid` beside `cueUuid`. `minReader` stays at **5**: the folder reads
    // as empty when missing and the field is optional with a null default, so every older archive
    // still imports unchanged.
    //
    // The writer's version moves for v9's reason, verbatim: a layout changes what a busk press
    // *does* on stage (a solo bank releases its siblings), so a v9 reader — which would import no
    // pages, never read the folder, and on its next wipe-then-export push write none back — must
    // refuse the repo rather than silently delete every peer's pages.
    //
    // Session 3 of the same plan **removed** `templateGroups/`, `TemplateJson.groupUuid` and
    // `sortOrder`, `LookJson.sortOrder`, `CueJson.pinnedToBusk` and `CueSlotJson.cueStackUuid`
    // under this same number, rather than bumping again: nothing but the dev desk wrote v10
    // between the two sessions, so no peer can hold a repo the later reader would import wrong and
    // push back. A v10 archive written *before* that session may still carry `cueStackUuid`; the
    // reader drops that slot with a warning rather than failing the pull, which is the same posture
    // a pad naming a missing record takes (a slot, like a pad, is an enrichment).
    //
    // v9: template groups. A new `templateGroups/` folder, and `TemplateJson.groupUuid` naming
    // one. `minReader` stays at **5**: the folder is read as empty when missing and the field is
    // optional with a null default, so every older archive still imports unchanged.
    //
    // The writer's version moves for v6's reason, not v8's. A v8 reader would import a v9 repo with
    // every template ungrouped and never read the folder — and its next push is wipe-then-export,
    // which writes no `templateGroups/` and no `groupUuid`, deleting every peer's groups. That is
    // not the SpeedMaster "null is a valid state" case: a group changes what a busk press *does*
    // (siblings release), so silently dropping one changes behaviour on stage. Bumping the writer
    // makes a v8 install refuse the repo instead, which is the only outcome that loses nothing.
    //
    // v8: a template may hold an **effect** instead of values. `template_effects` travels inline
    // as `TemplateJson.effect`. `minReader` stays at **5**: the field is optional with a null
    // default, so every older archive still imports unchanged.
    //
    // The writer's version moves even though the rule of thumb ("new optional field → no bump")
    // would let it stand still, and for the reason v6 spells out below: this field is the
    // *record's whole content*, not an enrichment. A v7 reader would import an effect template as
    // a hollow rows-less template — one the write boundary would refuse — and then write that back
    // over the effect on its next push. Bumping the writer makes a v7 install refuse the repo
    // instead, which is the only outcome that does not lose data. Contrast `SpeedMasterJson`'s four
    // routing fields, which took no bump: a null usage there is a *valid state* ("routes nothing").
    //
    // v7: the positional colour list is gone. `palette` leaves `looks/`, `cues/` and `cueStacks/`,
    // and `updateGlobalPalette` leaves `cues/`; an effect that wants a named colour references a
    // **template** from its own parameters instead. `minReader` stays at **5**: every removed field
    // has a default, so a v5 or v6 archive still imports — it simply drops colour lists nothing
    // reads any more. Only the writer's version moves, which is what makes an older install refuse
    // a v7 repo rather than silently write those fields back on its next push.
    //
    // v13: a rig row carries `flow` and `width` (the bank's two layout facts), and `BuskFlow` gains
    // `SCROLL` for banks and rows alike. The two new fields default, so on their own this would be
    // the `SpeedMasterJson` no-bump case — but `SCROLL` widens an **existing** field's value space:
    // a v12 reader imports a `SCROLL` bank as `WRAP` (`ProjectImporter`'s fallback) and writes that
    // back on its next push, which is `docs/sync-engineering.md`'s "degraded record" answer. Only
    // the writer moves, so a v12 install refuses a v13 repo rather than re-flowing every peer's
    // banks; `minReader` stays at **5**, since every earlier archive still decodes.
    //
    // v6: templates become their own entity — a `templates/` folder, and `CueLayerJson.lookUuid`
    // becomes optional beside a new `templateUuid`. `minReader` stays at **5**, deliberately: a v5
    // repo has no `templates/` folder (the importer reads a missing directory as empty) and every
    // one of its cue layers carries a `lookUuid`, so a v5 archive still imports exactly as before.
    // Only the writer's version moves, which is what makes a v5 install refuse a v6 repo — where a
    // cue layer may carry `templateUuid` alone, and a v5 reader would take `lookUuid`'s null
    // straight into `UUID.fromString` (a Java platform type, so no compile-time stop) and fail with
    // an NPE naming nothing.
    //
    // v5: FX presets and named palettes collapse into `looks/`, and `cuePresetApplications/`
    // becomes `cueLayers/`. `minReader` jumps to 5 because `CuePresetApplicationJson.presetUuid`
    // — a required field — is gone: an older reader would fail to parse a v5 repo rather than
    // silently drop the composition, so there is nothing to be gained by letting it try.
    //
    // v4: prompt-book script PDFs travel in the repo as `promptScripts/{hash}.pdf`.
    //
    // @EncodeDefault(ALWAYS) forces these to be written despite the canonical encoder's
    // `encodeDefaults = false`. Without it the whole object serialises to `{}` and every
    // reader falls back to its OWN compiled-in default — so the version gate can never see
    // the writer's version and never rejects a too-new repo. Forcing the value is what
    // makes a pre-v4 install actually refuse a v4 repo (and stop it wiping the PDFs).
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val formatVersion: Int = 40,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val minReader: Int = 40,
)

/**
 * Marker written to `tombstones/{tableName}/{uuid}.json` when a previously-synced record
 * has been deleted locally. Body is intentionally minimal and timestamp-free so the
 * file's hash stays stable across re-snapshots — forensics come from `git log` on the
 * path, not from the file contents.
 */
@Serializable
data class TombstoneJson(val tombstone: Boolean = true)

@Serializable
data class InstallsJson(
    val installs: Map<String, String> = emptyMap(),
)

@Serializable
data class ProjectJson(
    val uuid: String,
    val name: String,
    val description: String? = null,
    val stageWidthM: Double? = null,
    val stageDepthM: Double? = null,
    val stageHeightM: Double? = null,
    /** v24: what the project is; a show (every project before venues) is the default and is omitted. */
    val kind: ProjectKind = ProjectKind.SHOW,
    /** v24: a show's pin — its venue project's uuid and the version it sits on. Both or neither. */
    val venueUuid: String? = null,
    val venueVersion: Int? = null,
    /** v24: where the pinned venue syncs, `owner/repo` (or a remote URL) — a hint for a desk without it. */
    val venueRepo: String? = null,
    /**
     * Where a standalone show was detached from (venue-attach plan D1): the venue project's uuid and
     * the version it was pinned to. Both or neither; omitted when absent, and on any project but a
     * standalone show. No version of its own: absent is a show with no origin, which every show
     * detached before these fields were written already is.
     */
    val detachedFromVenueUuid: String? = null,
    val detachedFromVersion: Int? = null,
    /**
     * Which FX library a v37 archive's FX were written against ([LegacyNames.FX_LIBRARY]); absent is
     * 1, every archive written before the terminology plan's session 3. It tells the importer an FX
     * that names no `pingPong` from before the parameter went, which ran with it on, from one written
     * since. Read, never written: from v38 the `formatVersion` says it
     * ([LegacyNames.absentPingPongIsOn]).
     */
    val fxLibrary: Int? = null,
)

/**
 * v24: one of a show's hides (venue plan §3.2) — a venue record it leaves out of its rig, named by
 * kind and uuid. `recordUuid` names a record of the pinned venue, never of the show's own archive, so
 * a clone keeps it as it is (only `uuid`, the hide's own identity, is remapped).
 */
@Serializable
data class ShowHideJson(
    val uuid: String,
    val recordKind: HideKind,
    val recordUuid: String,
)

/**
 * v36: one of a show's recolours of a venue patch or group, `showColours/{uuid}.json` (tables-colours
 * plan D10). `recordUuid` names a record of the pinned venue, never of the show's own archive, so a clone
 * keeps it (only `uuid`, the recolour's own identity, is remapped). `colour` is carried as stored.
 */
@Serializable
data class ShowColourJson(
    val uuid: String,
    val recordUuid: String,
    val colour: String,
)

/**
 * v27: one of a show's releases of its venue's parks, `showParkReleases/{uuid}.json` — a channel the
 * pinned version parks that the show leaves unparked. Keyed by `universe` and `channel`, as a park is,
 * never by the venue park's uuid; a clone remaps only `uuid`, the release's own identity.
 */
@Serializable
data class ShowParkReleaseJson(
    val uuid: String,
    val universe: Int,
    val channel: Int,
)

/**
 * v24 (venue plan session 5, no bump — session 4 reserved the folder): one of a show's facet overrides
 * of a venue patch, `showOverrides/{uuid}.json`. `recordUuid` names the venue patch, never a record of
 * the show's own archive, so a clone keeps it; `value` and `base` are the facet's fields as
 * `models/patchFacets.kt` shapes them — a universe or rigging named by uuid, which a clone remaps where
 * it is the show's own and keeps where it is the venue's.
 */
@Serializable
data class ShowOverrideJson(
    val uuid: String,
    val recordUuid: String,
    val facet: PatchFacet,
    /** The show's value of the facet. */
    val value: JsonElement,
    /** The venue's value of the facet when it was overridden (or last kept). */
    val base: JsonElement,
)

/**
 * v24: `venueVersion.json` at the root of a venue repo's tagged commit — which version the commit is
 * (venue plan §3.4). Never in a show's repo. A desk that materialises the tag records these in its own
 * `venue_versions` row, so History reads the same on every desk.
 */
@Serializable
data class VenueVersionJson(
    val number: Int,
    val note: String,
    /** ISO-8601 UTC, as `toIsoUtc` writes it. */
    val publishedAt: String,
    val publishedBy: String? = null,
)

@Serializable
data class ScriptMetaJson(
    val uuid: String,
    val name: String,
    /** A [ScriptType] by name, read through [uk.me.cormack.lighting7.sync.LegacyNames.scriptType]. */
    val scriptType: String = ScriptType.GENERAL.name,
)

@Serializable
data class FxDefinitionJson(
    val uuid: String,
    val fxId: String,
    val name: String,
    val family: String,
    val outputType: FxOutputType,
    /** An [FxMode] by name, read through [uk.me.cormack.lighting7.sync.LegacyNames.fxMode]. */
    val fxMode: String = FxMode.STANDARD.name,
    val parameters: JsonElement? = null,
    val compatibleAttributes: List<String> = emptyList(),
    val script: String,
    /** `CYCLE` or `HEAD` (terminology plan D22). */
    val defaultTiming: String = TIMING_CYCLE,
    val timingSource: TimingSource = TimingSource.BEAT,
)

@Serializable
data class LookRowJson(
    val uuid: String,
    /** A `TargetRef` discriminator — `fixture` or `group`. A Look row is always bound. */
    val targetType: String,
    val targetKey: String,
    val attribute: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val elementKey: String? = null,
    val sortOrder: Int = 0,
)

/**
 * The fields every archive home of a stored FX shares — a Look's, a palette's and a cue's — which
 * `sync/LegacyNames.kt`'s rewrites read.
 */
interface StoredFxFields {
    val fxType: String
    val family: String
    val cycle: Double
    val order: String
    val phaseOffset: Double
    val parameters: Map<String, String>
    val direction: String
    val runs: Int?
    val spread: Double
}

@Serializable
data class LookFxJson(
    val uuid: String,
    /**
     * `fixture`, `group`, or since v34 `selection` (its key the ordered list's canonical JSON). An older
     * archive's `deferred` is dropped on import (absolute-looks plan D2).
     */
    val targetType: String,
    val targetKey: String,
    override val fxType: String,
    override val family: String,
    val attribute: String? = null,
    override val cycle: Double,
    val blendMode: String,
    override val order: String,
    override val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    /** Timing (D22), `CYCLE` or `HEAD`; absent is the FX type's own. */
    val timing: String? = null,
    override val parameters: Map<String, String> = emptyMap(),
    /** Speed master uuid, remapped by ExportUuidRemapper like any uuid. */
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    /** D7's *Seconds* — the effect follows no master (formatVersion 29). Omitted when false. */
    val seconds: Boolean = false,
    /** Amount (effects plan D2, terminology plan D2), 0–1 (formatVersion 30). Omitted at 1. */
    val amount: Double = 1.0,
    /** Direction (D5) by name (formatVersion 30). Omitted when `FORWARD`. */
    override val direction: String = "FORWARD",
    /** Runs (D9), the cycles an N-shot runs (formatVersion 30). Omitted when forever. */
    override val runs: Int? = null,
    /**
     * The spread's shape (effects plan D4, formatVersion 33): Spread 0–2, Wings, Segments, Parts (0
     * off). Each omitted at its default, so a show that shapes no spread exports as before.
     */
    override val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    val sortOrder: Int = 0,
) : StoredFxFields

/**
 * A Look — portable show content, rows and effects embedded inline.
 *
 * Rows address fixtures and groups by *key*, the same as cue assignments do, so nothing inside
 * needs reference remapping. The record's own `uuid` does matter beyond identity: a cue layer
 * points at it, and until the `ref:` grammar is retired a stored value may still name it as
 * `ref:{uuid}`. [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites both, which is why the
 * reference is a uuid rather than an int id.
 *
 * There is deliberately **no attribute-type field**: which families a Look touches is derived from
 * its rows, so a Look can grow from one family to several with no format change.
 */
@Serializable
data class LookJson(
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val rows: List<LookRowJson> = emptyList(),
    val fx: List<LookFxJson> = emptyList(),
    /** v20: what the Look shows while it is live. */
    val scenery: List<SceneryChangeJson> = emptyList(),
)

/**
 * One template row: a target (or none) and the intent it holds.
 *
 * Carries its own [uuid] like [LookRowJson] does, so a re-export of unchanged data is byte-identical
 * and the sync engine sees no change — a regenerated uuid per export would make every snapshot a
 * diff.
 */
@Serializable
data class PaletteRowJson(
    val uuid: String,
    /** `deferred` for a generic row, `fixture` for a per-fixture one. Never `group`. */
    val targetType: String,
    val targetKey: String,
    val attribute: String,
    /**
     * A `PaletteIntent` in serialised form — `#FF9D4A;policy=extract`, `pct:75`, `pos:-12,30` (v28; a
     * centre-relative position), or the `deg:45,12.5` travel degrees a row written before v28 holds.
     */
    val value: String,
    val sortOrder: Int = 0,
)

/**
 * A template's one effect — [LookFxJson] minus `targetType` / `targetKey` (an effect template
 * is always generic, fx-templates D3) and `sortOrder` (at most one, D2).
 *
 * `parameters` may hold a `pal:{uuid}` colour reference. It needs no special handling here for
 * the same reason `LookFxJson`'s does not: `ExportUuidRemapper` rewrites every uuid *string*
 * in the export text, so the reference and the two master uuids are re-pointed on clone with no
 * field-aware code.
 */
@Serializable
data class PaletteFxJson(
    val uuid: String,
    override val fxType: String,
    override val family: String,
    val attribute: String? = null,
    override val cycle: Double,
    val blendMode: String,
    override val order: String,
    override val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    /** Timing (D22), `CYCLE` or `HEAD`; absent is the FX type's own. */
    val timing: String? = null,
    override val parameters: Map<String, String> = emptyMap(),
    /** Speed master uuid, remapped by ExportUuidRemapper like any uuid. */
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    /** D7's *Seconds* — the effect follows no master (formatVersion 29). Omitted when false. */
    val seconds: Boolean = false,
    /** Amount (effects plan D2, terminology plan D2), 0–1 (formatVersion 30). Omitted at 1. */
    val amount: Double = 1.0,
    /** Direction (D5) by name (formatVersion 30). Omitted when `FORWARD`. */
    override val direction: String = "FORWARD",
    /** Runs (D9), the cycles an N-shot runs (formatVersion 30). Omitted when forever. */
    override val runs: Int? = null,
    /**
     * The spread's shape (effects plan D4, formatVersion 33): Spread 0–2, Wings, Segments, Parts (0
     * off). Each omitted at its default, so a show that shapes no spread exports as before.
     */
    override val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
) : StoredFxFields

/**
 * A template — portable show content, rows *or* one effect embedded inline.
 *
 * Rows address fixtures by *key*, the same as a Look's do, so nothing inside needs reference
 * remapping; the record's own `uuid` matters because a cue layer points at it.
 *
 * [rows] and [effect] are mutually exclusive (fx-templates D1), enforced at the write boundary
 * rather than by the format — the importer writes what it is given, as it does for every other
 * record.
 *
 * There is deliberately **no family field and no fixture type**. The family is derived — from the
 * rows, or from the effect's library `category` — and validated to be exactly one at the write
 * boundary; a template has no fixture type by design, since the values are intents resolved per
 * head at cook. Both would be second sources of truth for something the contents already say.
 *
 * A template carries no position and no group. Both were template *groups*, removed at v10 when the
 * busk page took ownership of the layout: order is a pad's place in a bank, and exclusivity is a
 * solo bank's. `GET /palettes` lists by name.
 */
@Serializable
data class PaletteJson(
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val fadeDurationMs: Long? = null,
    val rows: List<PaletteRowJson> = emptyList(),
    val fx: PaletteFxJson? = null,
)

/**
 * Speed master — portable show content. The exported [bpm] is the master's starting tempo
 * (the live value is written through on change, so this is "wherever the tempo was last
 * set"). Preset and cue effects reference a master by uuid inside their own records;
 * [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites those occurrences along with
 * this record's `uuid`, which is why the reference is a uuid rather than an int id.
 */
@Serializable
data class SpeedMasterJson(
    val uuid: String,
    val masterIndex: Int,
    val name: String,
    val bpm: Double = 120.0,
    val source: String = "MANUAL",
    val notes: String? = null,
    /**
     * Routing/follow settings (busking-view plan, session 1). All four are additive optional
     * fields with null defaults — no formatVersion bump, per the "new optional field" rule in
     * docs/sync-engineering.md.
     *
     * [followTargetUuid] is the leader's uuid and travels as a uuid for the usual reason —
     * `ExportUuidRemapper` rewrites it on clone, where an int id or a master index would
     * dangle or silently re-point. Null with a ratio set means master 1, which is what every
     * export written before follow targets existed says.
     */
    val family: String? = null,
    val followNum: Int? = null,
    val followDen: Int? = null,
    val followTargetUuid: String? = null,
)

/**
 * Universe config — portable subset only. The `address` column (machine-local controller IP)
 * is intentionally omitted; Phase 2 introduces machine_override for per-install IPs.
 */
@Serializable
data class UniverseConfigJson(
    val uuid: String,
    val subnet: Int = 0,
    val universe: Int,
    val controllerType: String = "ARTNET",
)

@Serializable
data class FixturePatchJson(
    val uuid: String,
    val universeConfigUuid: String,
    val fixtureTypeKey: String,
    val key: String,
    val displayName: String,
    /** The operator's head number (v16); null is unnumbered. Imported as stored — see
     *  `docs/sync-engineering.md` §"Version 16 — head numbers". */
    val headNumber: Int? = null,
    val startChannel: Int,
    val sortOrder: Int = 0,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    /** Body roll about its local Z (v17); null is 0. See `docs/sync-engineering.md`
     *  §"Version 17 — fixture roll". */
    val baseRollDeg: Double? = null,
    val riggingUuid: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
    /** A variable-length type's installed length in metres (v15); null is the type's default. */
    val lengthM: Double? = null,
    val stageHidden: Boolean = false,
    val infrastructure: Boolean = false,
    /** The lantern, by library id, and its focus (v19); every field null is an unfocused lantern of
     *  the kind's default. See `docs/sync-engineering.md` §"Version 19 — lanterns and focus". */
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
    /** The lamp's power and colour temperature at full (v26); null is the lantern's. See
     *  `docs/sync-engineering.md` §"Version 26 — lamps". */
    val lampWatts: Int? = null,
    val lampKelvin: Int? = null,
    /** The unit's stored locate (v31): the pan and tilt bytes Locate points each unit at, one entry
     *  per unit; empty — omitted — is none. See `docs/sync-engineering.md` §"Version 31 — stored locate". */
    val locate: List<UnitLocate> = emptyList(),
    /** The unit's fitted media (v22); null is the type's stock in every slot. See
     *  `docs/sync-engineering.md` §"Version 22 — fitted media". */
    val media: FittedMedia? = null,
    /** The unit's colour (v36), carried as stored — a key this desk does not know survives a round
     *  trip. See `docs/sync-engineering.md` §"Version 36 — fixture colours". */
    val colour: String? = null,
    /**
     * The other places this fixture hangs — a paired dimmer's second lantern. Embedded in
     * list order (so no `sortOrder`), like a group's `members`, and omitted by canonical JSON
     * when empty, so an export with no pairs is byte-identical to one written before the field
     * existed. It still bumped formatVersion to 14: a v13 reader would import a paired patch
     * without its lanterns and write them away on its next push (docs/sync-engineering.md
     * §"Version 14 — paired placements").
     */
    val extraPlacements: List<PatchPlacementJson> = emptyList(),
)

/**
 * One of a patch's extra placements (`fixture_patch_placements`). Same geometry and
 * rigging-relative rule as the patch's own `stageX`… fields. [riggingUuid] is a reference,
 * so a clone's `ExportUuidRemapper` rewrites it alongside the placement's own [uuid].
 */
@Serializable
data class PatchPlacementJson(
    val uuid: String,
    val label: String? = null,
    val riggingUuid: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    /** This lantern's body roll (v17); null is 0. */
    val baseRollDeg: Double? = null,
    /** This segment's own length (v15), for a variable-length type laid in segments; null takes
     *  the patch's `lengthM`. */
    val lengthM: Double? = null,
    /** This lantern (v19); null takes the patch's `lanternType`. The six after it are this
     *  lantern's own focus, never inherited. */
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
    /** This unit's own fitted media (v22), layered over the patch's option by option; null fits
     *  nothing of its own. */
    val media: FittedMedia? = null,
)

/**
 * A first-class rigging — truss, bar, boom, pipe, or floor stand. Carries a 3D pose
 * (position + yaw/pitch/roll) so fixture patches with [FixturePatchJson.riggingUuid]
 * set can express their stage_x/y/z as offsets in the rigging's local frame. See
 * `docs/fixtures-engineering.md` for the v3 Z-up FOH-relative coordinate system.
 */
@Serializable
data class RiggingJson(
    val uuid: String,
    val name: String,
    val kind: String? = null,
    val positionX: Double? = null,
    val positionY: Double? = null,
    val positionZ: Double? = null,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val lengthM: Double? = null,
    val sortOrder: Int = 0,
)

/**
 * A rectangular platform forming part of the playable stage surface. Multiple regions
 * describe thrusts, raised platforms, pits, and multi-level stages. Project-level
 * [ProjectJson.stageWidthM] / depth / height stays as a coarse fallback bounding box.
 * [centerZ] = 0 means deck level; > 0 raises the top surface above the deck.
 */
@Serializable
data class StageRegionJson(
    val uuid: String,
    val name: String,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val centerZ: Double? = null,
    val widthM: Double? = null,
    val depthM: Double? = null,
    val heightM: Double? = null,
    val yawDeg: Double? = null,
    val sortOrder: Int = 0,
)

/**
 * One scene element (v18). [params] is the kind's document as the desk stores it — a nested object,
 * so the canonical encoder sorts its keys and a platform's `regionUuid` is remapped on clone like
 * any other reference. Absent when the kind has nothing to say (an empty document). The pose and
 * size default to 0, and are omitted then.
 */
@Serializable
data class StageElementJson(
    val uuid: String,
    val name: String,
    val kind: String,
    val layer: String,
    val positionX: Double = 0.0,
    val positionY: Double = 0.0,
    val positionZ: Double = 0.0,
    val yawDeg: Double = 0.0,
    val widthM: Double = 0.0,
    val depthM: Double = 0.0,
    val heightM: Double = 0.0,
    val finishColour: String? = null,
    val finishPattern: String? = null,
    val emissive: Boolean = false,
    val params: JsonObject? = null,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * One saved viewpoint (v18). A seat view carries [seatElementUuid] and [seatId] instead of an eye;
 * the reference is to a `stageElements/` record, and a dangling one reads as no seat.
 */
@Serializable
data class StageViewpointJson(
    val uuid: String,
    val name: String,
    val kind: String,
    val eyeX: Double? = null,
    val eyeY: Double? = null,
    val eyeZ: Double? = null,
    val targetX: Double? = null,
    val targetY: Double? = null,
    val targetZ: Double? = null,
    val fovDeg: Double? = null,
    val seatElementUuid: String? = null,
    val seatId: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class FixtureGroupMemberJson(
    val uuid: String,
    val fixturePatchUuid: String,
    val sortOrder: Int = 0,
    val panOffset: Double = 0.0,
    val tiltOffset: Double = 0.0,
)

@Serializable
data class FixtureGroupJson(
    val uuid: String,
    val name: String,
    val members: List<FixtureGroupMemberJson> = emptyList(),
    /** The group's colour (v36), as stored, like a patch's. */
    val colour: String? = null,
)

@Serializable
data class CueStackJson(
    val uuid: String,
    val name: String,
    val loop: Boolean = false,
    val sortOrder: Int = 0,
    /** "STACK" (default) or "SEPARATOR". */
    val type: String = "STACK",
    val label: String? = null,
    /** v20: the stack's *set* — the states its elements hold while it is live. */
    val scenery: List<SceneryChangeJson> = emptyList(),
)

/**
 * One scenery change (stage-view plan session 8), embedded in the cue, stack or Look that owns it,
 * as a Look's rows are: an owner says one thing about each element, and the change is part of the
 * owner's own record. [elementUuid] names a `stageElements/` record — a uuid like every other
 * cross-record reference here, so [uk.me.cormack.lighting7.sync.ExportUuidRemapper] re-points it on
 * clone. [state] is the states as a nested object (`visible`, `open`, `trimM`), stored as the
 * archive holds it. [transitionMs] is a cue's own clock only.
 */
@Serializable
data class SceneryChangeJson(
    val uuid: String,
    val elementUuid: String,
    val state: JsonObject = JsonObject(emptyMap()),
    val transitionMs: Long? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class CueAttributeAssignmentJson(
    val uuid: String,
    val cueUuid: String,
    val targetType: String,
    val targetKey: String,
    val attribute: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
    val moveInDark: Boolean = false,
)

/**
 * One line of a cue's ordered layer composition. Its own top-level folder, the way the retired
 * `CuePresetApplicationJson` was — a cue child that points at a second entity, so it cannot be
 * embedded in either one.
 *
 * **Exactly one of [lookUuid] / [paletteUuid]** is set: a layer applies a Look or a template. Both
 * are uuids rather than ids for the usual reason — int PKs are re-minted on import, and
 * [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites uuid occurrences across the export.
 */
@Serializable
data class CueLayerJson(
    val uuid: String,
    val cueUuid: String,
    val lookUuid: String? = null,
    val paletteUuid: String? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    /** Comma-separated `AttributeFamily` names; null = every property. */
    val attributeMask: String? = null,
    val blendMode: String = "OVERRIDE",
    val amount: Double = 1.0,
    val stomp: Boolean = false,
    /** Per-layer speed-master override, remapped by ExportUuidRemapper like any uuid. */
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
)

@Serializable
data class CueAdHocFxJson(
    val uuid: String,
    val cueUuid: String,
    /** `fixture`, `group`, or since v34 `selection` (its key the ordered list's canonical JSON). */
    val targetType: String,
    val targetKey: String,
    override val fxType: String,
    override val family: String,
    val attribute: String? = null,
    override val cycle: Double,
    val blendMode: String,
    override val order: String,
    override val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    /** Timing (D22), `CYCLE` or `HEAD`; absent is the FX type's own. */
    val timing: String? = null,
    override val parameters: Map<String, String> = emptyMap(),
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). */
    val rateSpeedMasterUuid: String? = null,
    /** D7's *Seconds* — the effect follows no master (formatVersion 29). Omitted when false. */
    val seconds: Boolean = false,
    /** Amount (effects plan D2, terminology plan D2), 0–1 (formatVersion 30). Omitted at 1. */
    val amount: Double = 1.0,
    /** Direction (D5) by name (formatVersion 30). Omitted when `FORWARD`. */
    override val direction: String = "FORWARD",
    /** Runs (D9), the cycles an N-shot runs (formatVersion 30). Omitted when forever. */
    override val runs: Int? = null,
    /**
     * The spread's shape (effects plan D4, formatVersion 33): Spread 0–2, Wings, Segments, Parts (0
     * off). Each omitted at its default, so a show that shapes no spread exports as before.
     */
    override val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    /**
     * The run key (stack-timeline plan D7, formatVersion 37): rows of neighbouring cues sharing one
     * are one run. Omitted when null. Not an identity, so a clone's remap leaves it as it is.
     */
    val runUuid: String? = null,
) : StoredFxFields

@Serializable
data class CueTriggerJson(
    val uuid: String,
    val cueUuid: String,
    val triggerType: TriggerType,
    val scriptUuid: String,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
)

/**
 * A cue: what a state is (cue model plan D2) — never where it sits. From v32 its places in stacks
 * are [CueStackEntryJson] records; a cue no entry names is loose. A v5–v31 archive's cue file also
 * carries its one place, read through [LegacyCuePlacementJson].
 */
@Serializable
data class CueJson(
    val uuid: String,
    val name: String,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val notes: String? = null,
    val stomp: Boolean = false,
    /** v20: what the cue moves on GO, each change on its own clock. */
    val scenery: List<SceneryChangeJson> = emptyList(),
    /** v21: the tubes the cue fires on GO into it, each at its offset, while the desk is armed. */
    val events: List<CueEventJson> = emptyList(),
    /** v35: the masters the cue sets on every GO into it, in order. */
    val speedMasters: List<CueSpeedMasterJson> = emptyList(),
)

/**
 * v35: one master a cue sets on GO (venue-visit plan S16) — the master by uuid, so
 * `ExportUuidRemapper` re-points it on a clone, and its BPM. Imported as stored: a uuid the archive's
 * masters lack is skipped at GO, as a master deleted under the cue is.
 */
@Serializable
data class CueSpeedMasterJson(
    val speedMasterUuid: String,
    val bpm: Double,
)

/**
 * The place a v5–v31 archive's cue file gave its cue: its stack, its position, its number and
 * follow, and whether it was a marker. **Read only, never written** — decoded from the same file as
 * [CueJson] when the archive is older than v32, where each becomes one [CueStackEntryJson]-shaped
 * entry (`ProjectImporter`): `ordinal = sortOrder`, the number explicit only when
 * [cueNumberAuto] is false, a MARKER cue a marker entry labelled with its name, and a cue with no
 * stack loose. Its fields default, so a v32 cue file decodes as no place at all.
 */
@Serializable
data class LegacyCuePlacementJson(
    val cueStackUuid: String? = null,
    val sortOrder: Int = 0,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val cueNumber: String? = null,
    val cueNumberAuto: Boolean = false,
    val cueType: String = "STANDARD",
)

/**
 * v32: one place in a stack's list (cue model plan §3.9) — `cueStackEntries/{uuid}.json`, one file
 * per entry, which is the merge story: two desks adding to one stack add two records, a drag
 * rewrites one (its [ordinal]), and a renumber rewrites only the entries whose explicit number
 * changed.
 *
 *  * [cueUuid] names the cue a cue entry places; a marker has none. Several entries may name one
 *    cue, in one stack or several (D1, D7).
 *  * [kind] is omitted for a cue entry; a marker says `MARKER` and carries its [label].
 *  * [ordinal] is the entry's position (P6): ascending, ties broken by [uuid], so two desks that
 *    inserted into the same gap — the same midpoint — play the two entries in the same order. No
 *    default, so it is always written.
 *  * [cueNumber] is written when explicit, and an auto number only where a reader could not derive
 *    it from position (P5, as amended: `heldAutoNumbers`); a reader takes every number it finds as
 *    explicit. The rest are derived by every reader, so a drag changes one file rather than every
 *    auto-numbered entry after it.
 *  * [autoAdvance] and [autoAdvanceDelayMs] are the entry's follow (D2).
 */
@Serializable
data class CueStackEntryJson(
    val uuid: String,
    val cueStackUuid: String,
    val cueUuid: String? = null,
    val kind: String? = null,
    val label: String? = null,
    val ordinal: Double,
    val cueNumber: String? = null,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
)

/**
 * v21: one cue event — a one-shot trigger on a patch, fired [offsetMs] after GO into its cue. The
 * patch by uuid, so `ExportUuidRemapper` re-points it on a clone; [trigger] is the trigger's name on
 * the patch's fixture type (`output1`). An event at GO omits [offsetMs], as a zero sort order is
 * omitted.
 */
@Serializable
data class CueEventJson(
    val uuid: String,
    val patchUuid: String,
    val trigger: String,
    val offsetMs: Long = 0,
    val sortOrder: Int = 0,
)

@Serializable
data class ShowEntryJson(
    val uuid: String,
    val cueStackUuid: String? = null,
    val entryType: String = "STACK",
    val sortOrder: Int,
    val label: String? = null,
)

/**
 * One tile of the FX cue slots overlay. **Exactly one of [cueUuid] / [lookUuid]** is set, enforced
 * by the importer (a slot naming none or two is malformed input, not a state). [lookUuid] is v10: a
 * Look, pressed whole.
 *
 * A cue *stack* used to be a third arm. It went with the overlay's own assign flow, under the same
 * v10 (see [FormatVersionJson]); a pre-session-3 v10 archive may still carry `cueStackUuid`, and
 * the importer drops such a slot with a warning rather than failing the pull.
 */
@Serializable
data class CueSlotJson(
    val uuid: String,
    val page: Int,
    val slotIndex: Int,
    val cueUuid: String? = null,
    val lookUuid: String? = null,
)

/**
 * A busk page (v10) — portable show content, its columns, banks and pads embedded inline, the way
 * a Look carries its rows. One document per page: a drag changes one file.
 *
 * Nothing here is patch-shaped; a pad names a library record by uuid ([BuskPadJson]), so
 * `ExportUuidRemapper` re-points every reference on clone with no field-aware code, and a page
 * survives a repatch untouched.
 *
 * The structural fields (`row`, `sortOrder`, `width`, `name`, `solo`, `flow`) have **no defaults**
 * so canonical JSON always writes them: a layout is positions, and a position omitted because it
 * happened to be zero would read as a document with holes.
 */
@Serializable
data class BuskPageJson(
    val uuid: String,
    val name: String,
    val sortOrder: Int,
    val columns: List<BuskColumnJson> = emptyList(),
)

/** A column of a busk page: which row it is in, its place in that row, and its width share in twelfths. */
@Serializable
data class BuskColumnJson(
    val uuid: String,
    val row: Int,
    val sortOrder: Int,
    val width: Int,
    val banks: List<BuskBankJson> = emptyList(),
)

/** A bank in a column: `solo` releases siblings on a press; `flow` is `WRAP`, `COLUMN` or `SCROLL`. */
@Serializable
data class BuskBankJson(
    val uuid: String,
    val sortOrder: Int,
    val name: String,
    val solo: Boolean,
    val flow: String,
    val pads: List<BuskPadJson> = emptyList(),
)

/**
 * A pad: an ordered reference to **exactly one** of a template, a Look or a cue, by uuid — the
 * [CueLayerJson] pattern, three-armed. A pad whose record the archive does not carry, or which
 * names none or two, is **dropped with a warning** on import rather than aborting the pull: a pad
 * is an enrichment of its record (a place on a page), not content, so a page that has lost a pad
 * is still a whole page. Contrast a cue layer, which is nothing without its source and does abort.
 */
@Serializable
data class BuskPadJson(
    val uuid: String,
    val sortOrder: Int,
    val paletteUuid: String? = null,
    val lookUuid: String? = null,
    val cueUuid: String? = null,
)

/**
 * A busk rig row (v12): its tiles nested, in `sortOrder`, the way a busk page carries its columns.
 * One document per row so a drag on the band changes one file.
 */
@Serializable
data class BuskRigRowJson(
    val uuid: String,
    val name: String,
    val sortOrder: Int,
    /**
     * The row's layout (2026-09-21): a `BuskFlow` name and a width share in twelfths, the bank's
     * two facts. Both default so an archive written before them decodes; the importer reads an
     * unknown flow as `SCROLL` and a width outside `BUSK_WIDTHS` as 12, as the route's read does.
     */
    val flow: String = "SCROLL",
    val width: Int = 12,
    val tiles: List<BuskRigTileJson> = emptyList(),
)

/**
 * A rig tile: an ordered reference to **exactly one** of a group or a patch, by uuid — the
 * [BuskPadJson] pattern, two-armed — plus how a multi-head fixture shows its cells (busk-further plan
 * D3). [elementKey] is the cell's own key for a tile that is one cell, carried verbatim: element
 * keys are opaque, and the write boundary validates one against the live fixture on the next write.
 * A tile whose group or patch the archive does not carry, or which names none or both, is
 * **dropped with a warning** on import rather than aborting the pull: a tile is an enrichment of its
 * record (a place on the band), not content, and a row that loses every tile goes with them.
 */
@Serializable
data class BuskRigTileJson(
    val uuid: String,
    val sortOrder: Int,
    val groupUuid: String? = null,
    val patchUuid: String? = null,
    val elementKey: String? = null,
    val cellMode: String = "PIPS",
    val cellSplit: Int? = null,
    val label: String? = null,
)

/**
 * Parked DMX channel — a channel locked at a fixed output value that overrides every other
 * source. Parking is portable show content: operators routinely use it to pin "house lights at
 * 50%" or to protect a channel that drives a hard-powered fixture plugged into a dimmer, both
 * of which travel with the project.
 *
 * `(universe, channel)` is the natural key on disk and in the DB unique index; `uuid` exists
 * solely to give the sync engine a stable record identity across renames-of-value.
 */
@Serializable
data class ParkedChannelJson(
    val uuid: String,
    val universe: Int,
    val channel: Int,
    val value: Int,
)

@Serializable
data class ControlSurfaceBindingJson(
    val uuid: String,
    val deviceTypeKey: String,
    val controlId: String,
    val bank: String? = null,
    val targetType: String,
    val targetPayload: String,
    val takeoverPolicy: String? = null,
    val sortOrder: Int = 0,
)

/**
 * Prompt-book: binds an imported PDF script (identified by content hash) to the
 * project's show. As of format v4 the PDF bytes travel too, as a binary blob at
 * `promptScripts/{scriptHash}.pdf` moved by `PromptScriptRepoSync` (never through
 * this JSON path). On an install still missing the bytes — e.g. a book created
 * before v4 whose PDF reached no peer — the client offers a re-import by hash.
 */
@Serializable
data class PromptBookJson(
    val uuid: String,
    val scriptHash: String,
    val scriptFileName: String? = null,
    val pageCount: Int,
    /** Leading front-matter (cover/title) pages before the script's printed page 1. */
    val coverPages: Int = 0,
)

/**
 * An anchor marks an **entry** (cue model plan D4): from v32 [entryUuid], a `cueStackEntries/`
 * record. A v5–v31 archive names a cue instead ([cueUuid], read only, never written), which the
 * importer moves to that cue's one entry — or to the marker entry a MARKER cue's uuid became.
 */
@Serializable
data class PromptBookAnchorJson(
    val uuid: String,
    val entryUuid: String? = null,
    val cueUuid: String? = null,
    val region: List<PromptBookRectDto>,
    val label: String? = null,
)

@Serializable
data class PromptBookAnnotationJson(
    val uuid: String,
    val kind: String,
    val region: List<PromptBookRectDto>,
    val text: String? = null,
    val color: String? = null,
    val tone: String? = null,
)
