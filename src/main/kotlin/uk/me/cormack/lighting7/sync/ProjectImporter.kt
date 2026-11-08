package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.FxMode
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.normalizeFxType
import uk.me.cormack.lighting7.fx.StoredWriteClock
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.timingSourceByName
import uk.me.cormack.lighting7.fx.wallClockFxTypes
import uk.me.cormack.lighting7.fx.storedWriteClock
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkWhen
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.models.CueSpeedMasterDto
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.BUSK_WIDTHS
import uk.me.cormack.lighting7.models.BuskFlow
import uk.me.cormack.lighting7.models.buskPadKind
import uk.me.cormack.lighting7.models.buskRigTileKind
import uk.me.cormack.lighting7.models.BuskRigCellMode
import uk.me.cormack.lighting7.models.DaoBuskRigRow
import uk.me.cormack.lighting7.models.DaoBuskRigTile
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.deletePlacementsOf
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoShowColour
import uk.me.cormack.lighting7.models.DaoShowColours
import uk.me.cormack.lighting7.models.DaoShowGroupOrder
import uk.me.cormack.lighting7.models.DaoShowGroupOrders
import uk.me.cormack.lighting7.models.ShowGroupOrders
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.encodeFacet
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.sync.dto.ShowColourJson
import uk.me.cormack.lighting7.sync.dto.ShowGroupOrderJson
import uk.me.cormack.lighting7.sync.dto.ShowHideJson
import uk.me.cormack.lighting7.sync.dto.ShowParkReleaseJson
import uk.me.cormack.lighting7.sync.dto.ShowOverrideJson
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenuePins
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBooks
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoPromptBookAnchors
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotation
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotations
import uk.me.cormack.lighting7.models.checkPromptBookRegion
import uk.me.cormack.lighting7.models.layerSourceShape
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoCueSceneryRow
import uk.me.cormack.lighting7.models.DaoCueStackSceneryRow
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.deleteSceneryForElements
import uk.me.cormack.lighting7.models.deleteCueEventsForPatches
import uk.me.cormack.lighting7.models.deleteCueEvents
import uk.me.cormack.lighting7.models.deleteCueScenery
import uk.me.cormack.lighting7.models.deleteStackScenery
import uk.me.cormack.lighting7.models.deleteLookScenery
import uk.me.cormack.lighting7.models.DaoCueEvent
import uk.me.cormack.lighting7.models.stripTriggerRows
import uk.me.cormack.lighting7.models.stripCommandRows
import uk.me.cormack.lighting7.models.MAX_CUE_EVENT_OFFSET
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.sync.dto.CueEventJson
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.storedParamsText
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.routes.deleteBuskPage
import uk.me.cormack.lighting7.routes.deleteBuskPageContents
import uk.me.cormack.lighting7.routes.releaseAutoNumber
import uk.me.cormack.lighting7.routes.canonicalCueNumber
import uk.me.cormack.lighting7.routes.flushPendingEntryWrites
import uk.me.cormack.lighting7.routes.deleteBuskRig
import uk.me.cormack.lighting7.routes.deleteCueChildren
import org.jetbrains.exposed.v1.dao.entityCache
import uk.me.cormack.lighting7.state.PaletteLayerConversion
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.logConverted
import uk.me.cormack.lighting7.state.logKept
import uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson
import uk.me.cormack.lighting7.sync.dto.CueAdHocFxJson
import uk.me.cormack.lighting7.sync.dto.StoredFxFields
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CueStackEntryJson
import uk.me.cormack.lighting7.sync.dto.LegacyCuePlacementJson
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteFxEntity
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.sync.dto.PaletteJson
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.sync.dto.CueAttributeAssignmentJson
import uk.me.cormack.lighting7.sync.dto.BuskPageJson
import uk.me.cormack.lighting7.sync.dto.BuskRigRowJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.CueStackJson
import uk.me.cormack.lighting7.sync.dto.CueTriggerJson
import uk.me.cormack.lighting7.sync.dto.SpeedMasterJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnchorJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnnotationJson
import uk.me.cormack.lighting7.sync.dto.PromptBookJson
import uk.me.cormack.lighting7.sync.dto.FixtureGroupJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.FxDefinitionJson
import uk.me.cormack.lighting7.sync.dto.ParkedChannelJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.RiggingJson
import uk.me.cormack.lighting7.sync.dto.ScriptMetaJson
import uk.me.cormack.lighting7.sync.dto.ShowEntryJson
import uk.me.cormack.lighting7.sync.dto.SceneryChangeJson
import uk.me.cormack.lighting7.sync.dto.StageElementJson
import uk.me.cormack.lighting7.sync.dto.StageRegionJson
import uk.me.cormack.lighting7.sync.dto.StageViewpointJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import java.time.Duration
import uk.me.cormack.lighting7.models.asDuration
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.CueStackEntryKind
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.routes.renumberAutoEntries
import uk.me.cormack.lighting7.routes.cueEntryIds
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignments
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCueTriggers
import uk.me.cormack.lighting7.models.DaoCueSlots
import uk.me.cormack.lighting7.models.DaoBuskPages
import uk.me.cormack.lighting7.models.DaoBuskRigRows
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacements
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.dao.flushCache
import uk.me.cormack.lighting7.fx.PaletteReferenceInvalidException

// v12 added `buskRig/` — one document per rig row, tiles nested, naming groups and patches by uuid
// (busk-further plan §3.7). MIN stays at 5: a missing folder reads as an empty rig.
//
// v14 embedded `FixturePatchJson.extraPlacements` (a paired dimmer's other lanterns). SUPPORTED
// moved for v6's reason — a v13 reader would import a paired patch without its lanterns and write
// them away on its next push; MIN stays at 5 because the field defaults to empty.
//
// v15 added `lengthM` to `FixturePatchJson` and `PatchPlacementJson` (a lightstrip's installed
// length, and each segment's). SUPPORTED moved for v14's reason — a v14 reader would import the
// strip at its type's default and write the length away on its next push; MIN stays at 5 because
// both fields default to null. Imported as stored, with no type check: the write boundary refuses
// a length on a fixed-length type, and the stage views draw one only for a type that takes it.
//
// v16 added `FixturePatchJson.headNumber` (the operator's head number, as a patch migrated from
// another console carries it). SUPPORTED moved for v14's reason — a v15 reader would import every
// patch unnumbered and write the numbers away on its next push; MIN stays at 5 because the field
// defaults to null. Imported as stored, with no uniqueness check: a merge of two peers' numbers
// must import, and the patch list marks a shared number rather than refusing the project.
//
// v17 added `baseRollDeg` to `FixturePatchJson` and `PatchPlacementJson` (a body's roll about its
// local Z — what stands a ring's side on end). SUPPORTED moved for v14's reason — a v16 reader
// would import every rolled body flat and write the roll away on its next push; MIN stays at 5
// because both fields default to null.
//
// v19 added the lantern and its focus to `FixturePatchJson` and `PatchPlacementJson` —
// `lanternType`, `zoomDeg`, `lampRotationDeg`, `shutters`, `gateRotationDeg`, `iris`,
// `focusSoftness` (stage-view plan session 7). SUPPORTED moved for v14's reason — a v18 reader would
// import every lantern unfocused as its kind's default and write the focus away on its next push;
// MIN stays at 5 because every field defaults to null. Imported as stored: the library is not
// consulted, so an id a newer desk's library holds survives a round trip through this one.
//
// v30 added `size`, `direction` and `nShot` to the three stored effect shapes — `LookFxJson`,
// `PaletteFxJson` and `CueAdHocFxJson` (effects plan session 1, D2, D5, D9). SUPPORTED moved
// for v14's reason — a v29 reader would run a 40 % effect at full size, a backward one forward and a
// one-shot forever, and write all three away on its next push; MIN stays at 5 because each field
// defaults to what a row without it meant.
// v31 added `locate` to `FixturePatchJson` (venue-visit plan D6): each unit's stored pan and tilt
// bytes. SUPPORTED moved for v14's reason — a v30 reader would import every patch with no stored
// locate and write them away on its next push; MIN stays at 5 because the field defaults to empty.
// Imported as stored (canonicalised, no type check): the write boundary refuses a unit the type does
// not pan and tilt, and Locate ignores an entry it cannot place.
// v33 added `spread`, `wings`, `blocks` and `groups` to the three stored effect shapes —
// `LookFxJson`, `PaletteFxJson` and `CueAdHocFxJson` (effects plan session 2, D4).
// SUPPORTED moved for v14's reason — a v32 reader would run a 50 % ripple over the whole cycle and two
// wings as one, and write all four away on its next push; MIN stays at 5 because each field defaults to
// what a row without it meant. Imported as stored: a value out of range is clamped when the effect
// spawns (`FxSpecCoercion.Lenient.spreadShape`), not refused here.
// v34 gave an effect a third target (effects plan session 3, D3): `targetType: "selection"` on a
// `LookFxJson` or `CueAdHocFxJson`, its `targetKey` the canonical JSON of the ordered
// `[{key, type}]` fixtures and groups it spreads over. No field moved. SUPPORTED moved for v14's
// reason — a v33 reader would store a target it cannot spawn, and every cue holding one would skip
// that effect; MIN stays at 5 because nothing older uses the value. Imported as stored: a key that
// does not parse is refused where the effect spawns, not here.
// v35 added `speedMasters` to `CueJson` (venue-visit plan S16): the masters a cue sets on GO, each a
// master's uuid and a BPM. SUPPORTED moved for v14's reason — a v34 reader would import every cue
// without them and write them away on its next push; MIN stays at 5 because a missing list sets
// nothing. Imported as stored: a uuid the archive's masters lack is skipped at GO, not here.
//
// v29 added `seconds` to the three stored effect shapes — `LookFxJson`, `PaletteFxJson` and
// `CueAdHocFxJson` — D7's *Seconds* (venue-visit plan S15): the effect follows no master, and a
// beat effect's `beatDivision` is then its cycle in seconds. SUPPORTED moved for v14's reason — a v28
// reader would import a ten-second breathe as ten beats of master 1 and write the flag away on its
// next push; MIN stays at 5 because the field defaults to false.
//
// v28 gave a palette's position row a second unit (venue-visit plan D3, P3): `pos:<pan>,<tilt>`,
// centre-relative coarse DMX, beside the `deg:` travel degrees every earlier row holds. No field
// moved — `PaletteRowJson.value` is a string, imported verbatim — but its meaning did: a v27 reader
// parses a `pos:` row as no intent and skips it with a warn, so it would run every new position
// palette as nothing while its push wrote the row back unchanged. SUPPORTED moves so a v27 install
// refuses the repo instead; MIN stays at 5 because a `deg:` row still resolves as angles.
//
// v27 made a show on a venue inherit its pinned version's parks and added `showParkReleases/`, the
// venue parks it releases, keyed by universe and channel. SUPPORTED moved by the sharp-edge rule — a
// v26 reader would drop every release and write them away on its next push, and run a show without its
// venue's parks; MIN stays at 5 because a missing folder is no releases.
//
// v26 added `lampWatts` and `lampKelvin` to `FixturePatchJson` (a lantern-hung patch's own lamp).
// SUPPORTED moved for v14's reason — a v25 reader would import every lamp as its lantern's and write
// them away on its next push; MIN stays at 5 because both fields default to null. Imported as stored:
// the write boundary refuses a lamp on a type with no lantern, and the stage views draw one only there.
//
// v25 let a scene image texture a model object's part (`ObjectParams.parts[].texture.image`), so the
// `sceneImages/` folder holds images no cloth names. The params keys alone would need no bump; the
// images do, for v4's reason: a v24 reader reconciles the folder to the hashes *it* reads as
// referenced — a cloth's paint only — and its next push would delete every model texture while its
// re-export kept the parts naming them. MIN stays at 5: every key defaults.
//
// v36 added fixture colours (tables-colours plan D7, D10): `colour` on `FixturePatchJson` and
// `FixtureGroupJson`, and `showColours/`, a show's recolours of its venue's patches and groups. SUPPORTED
// moved for v14's reason — a v35 reader would import every patch and group without its colour and write
// them away on its next push. MIN stays at 5: a missing colour is none. Imported as stored (a key this
// desk does not know reads as none, and survives the round trip), as a gel code is.
//
// v37 added `runUuid` to `CueAdHocFxJson` (stack-timeline plan D7): the run key ad-hoc effects of
// neighbouring cues share, for a GO to hand the running effect on (session 2). SUPPORTED moved for v14's reason — a v36
// reader would import every effect out of its run and write the keys away on its next push. MIN stays at
// 5: a missing key is no run. Imported as stored: nothing checks that a key's rows are neighbours, or
// that a cue holds it once.
//
// v45 added a stack's *Move in dark* setting to `CueStackJson` (move-in-dark plan D2). SUPPORTED moved
// for v14's reason — a v44 reader would import every stack off and write the setting away on its next
// push. MIN stays at 5: every field defaults to off, which is what an older stack reads as. Imported as
// stored, except that a delay or time outside 0–30 s is clamped into it and an unknown `when` or family
// is dropped (no family left is all three), so a stored stack always satisfies the write boundary.
//
// v44 added `showGroupOrders/`, a show's orders of its venue's groups' members (group-order plan D9).
// SUPPORTED moved for v14's reason — a v43 reader would import the show without them and write them away
// on its next push. MIN stays at 5: a missing folder is no orders. Imported as stored, as a recolour is.
//
// v24 made venues sync (venue plan session 4): `project.json`'s `kind`, a show's pin (`venueUuid`,
// `venueVersion`) and `venueRepo` hint, and `showHides/`. A show's archive holds only its own rows, so
// its links into the venue — a patch on a venue universe, a group member, a cue event, a scenery change,
// a busk tile — name venue records the archive lacks: [populateProject] seeds its uuid maps from the
// pinned snapshot first, and a pin whose snapshot this desk lacks is refused (`VENUE_MISSING`).
// SUPPORTED moved for v4's reason — a v23 reader would import a pinned show as a standalone one and
// fail on its first venue link, or write the pin and hides away. `showOverrides/` was reserved by the
// first v24 reader ([RESERVED_FOLDERS], which refused an archive holding one) and is read since venue
// plan session 5, still at v24: a show's facet overrides of venue patches. MIN stays at 5: every key
// defaults, and a v23 archive imports unchanged.
//
// v32 moved a stack's list out of the cue files (cue model plan session 3, §3.9): each entry is its
// own `cueStackEntries/{uuid}.json` — a double `ordinal`, the number only when explicit or not derivable, a marker as
// a labelled entry — and an anchor names its entry. SUPPORTED moved because a v31 reader would import
// every cue with no stack and its next push would write every stack empty. MIN stays at 5: a v5–v31
// archive's lists still import from its cue files (`CUE_ENTRIES_FORMAT_VERSION`).
//
// v23 added the scene's painted cloths (scrim plan P2): `DrapeParams.fabric` and the drape's and the
// flat's `paint`, inside the element's params, and a top-level `sceneImages/{sha256}.{png|jpg}`
// folder of the images they name — raw bytes, content-addressed, moved by `SceneImageRepoSync`. The
// params keys alone would need no bump; the folder does, for v4's reason: a v22 reader lacks the
// wipe-preserve and its next wipe-then-export push would delete every peer's images (and its
// re-export of a painted element would keep the keys while the images went). MIN stays at 5: a
// missing folder is no images, and every key defaults.
//
// v22 added fitted media to `FixturePatchJson` and `PatchPlacementJson` — `media`, a nested
// `{slots: {<property>: {<option>: {gel?, gobo?}}}}` (fixture optics plan session 3). SUPPORTED moved
// for v14's reason — a v21 reader would import every unit with the stock string and write the fitted
// media away on its next push; MIN stays at 5 because the field defaults to null. Imported as stored:
// neither the gel library nor the type is consulted, so a gel a newer desk's library holds survives a
// round trip through this one, and a unit draws such a slot as its stock.
//
// v9 added `templateGroups/` and `TemplateJson.groupUuid`; v10 removed both again when the busk
// page took over ordering and exclusivity. SUPPORTED moved for v6's reason (a v8 reader would
// import every template ungrouped and write the groups away on its next push); MIN stays at 5
// because both folders read as empty when missing and every field defaults.
//
// v6 gave templates their own entity: a `templates/` folder, and `CueLayerJson.lookUuid` optional
// beside a new `templateUuid`. Only SUPPORTED moves — **MIN deliberately stays at 5**, because a v5
// repo still imports unchanged (no `templates/` folder reads as empty, and every v5 cue layer names a
// `lookUuid`). Moving SUPPORTED is what makes a v5 install refuse a v6 repo, where a cue layer may
// carry `templateUuid` alone and a v5 reader would take `lookUuid`'s null straight into
// `UUID.fromString` — a Java platform type, so no compile-time stop — and fail with a bare NPE.
//
// v5 collapsed FX presets and named palettes into `looks/`, and `cuePresetApplications/` into
// `cueLayers/`. MIN jumped to 5 with it: a v4 repo's `cuePresetApplications` records name a
// `presetUuid` that no longer resolves to anything, and there is no in-place upgrade — reading one
// would import a project whose cues had lost their composition entirely, which is worse than
// refusing it. Bumping both is therefore deliberate, not an oversight.
//
// v47 let a cue row's or a Look row's value be a palette reference, `pal:{uuid}` (palette-references
// plan D7). SUPPORTED moved because a v46 reader would refuse such a Look row and read such a cue row
// as nothing; MIN stays at 5, since an older archive's rows are all literals. A reference to a
// palette the archive does not hold is refused by name (`PALETTE_REFERENCE_INVALID`).
//
// v49 gave the stored FX shapes `ownBpm` and retired `seconds` (fx-speed plan D5). SUPPORTED moved
// because a v48 reader would run an Own tempo effect on master 1; MIN stays at 5, since an older
// archive's `seconds` is read as Own tempo or Natural.
//
// **These two constants are the real gate.** `FormatVersionJson.minReader` is *written* but never
// read by the importer — `loadAndValidateArchive` compares against these — so bumping the DTO
// alone leaves the gate wide open.
//
// v4 added `promptScripts/{hash}.pdf` binary blobs to the repo; the writer emitting 4 was what
// made a pre-v4 install refuse a v4 repo (it lacked the wipe-preserve logic and would delete the
// PDFs, reverting them onto peers).
internal const val SUPPORTED_FORMAT_VERSION = 49
internal const val MIN_SUPPORTED_FORMAT_VERSION = 5

/**
 * Every record folder in the order [ProjectImporter.restore] upserts them — the importer's own,
 * parents first; it deletes in reverse.
 */
private val RESTORE_ORDER = with(RecordFolders) {
    listOf(
        SyncDispositions.PROJECT_FOLDER, SHOW_HIDES, SHOW_OVERRIDES, SHOW_COLOURS, SHOW_GROUP_ORDERS, STAGE_ELEMENTS,
        SCRIPTS, FX_DEFINITIONS, LOOKS, PALETTES, SPEED_MASTERS, UNIVERSE_CONFIGS, RIGGINGS, STAGE_REGIONS,
        STAGE_VIEWPOINTS, FIXTURE_PATCHES, FIXTURE_GROUPS, BUSK_RIG, CUE_STACKS, CUES, CUE_STACK_ENTRIES,
        CUE_ATTRIBUTE_ASSIGNMENTS, CUE_LAYERS, CUE_AD_HOC_FX, CUE_TRIGGERS, PROMPT_BOOKS, PROMPT_BOOK_ANCHORS,
        PROMPT_BOOK_ANNOTATIONS, CUE_SLOTS, BUSK_PAGES, PARKED_CHANNELS, SHOW_PARK_RELEASES, CONTROL_SURFACE_BINDINGS,
    )
}

/** The folders [ProjectImporter.restore] can put back: every portable one, and `project.json` (undo plan §5). */
internal val RESTORABLE_FOLDERS: Set<String> = RESTORE_ORDER.toSet()

private const val CUE_STACK_ENTRIES = RecordFolders.CUE_STACK_ENTRIES
private const val PROMPT_BOOK_ANCHORS = RecordFolders.PROMPT_BOOK_ANCHORS

/**
 * The first `formatVersion` whose stacks' lists are `cueStackEntries/` records (cue model plan
 * §3.9). An older archive's lists are in its cue files ([LegacyCuePlacementJson]), and its anchors
 * name cues; the importer reads both shapes, so `MIN_SUPPORTED_FORMAT_VERSION` stays 5.
 */
internal const val CUE_ENTRIES_FORMAT_VERSION = 32

/**
 * An explicit cue number an archive held twice in one stack, and how the import resolved it (cue
 * model plan §3.9): [kept] — the cue of entry [keptEntryUuid], the lower uuid — keeps [number], and
 * [cue] (entry [entryUuid]) was made auto. Named in the sync result ([SyncRunResult.numberClashes]).
 */
@Serializable
data class CueNumberClash(
    val stack: String,
    val number: String,
    val keptEntryUuid: String,
    val kept: String,
    val entryUuid: String,
    val cue: String,
) {
    /** One line for a log, a toast or the activity log. */
    fun describe(): String =
        "cue number $number was taken twice in '$stack': '$kept' keeps it, '$cue' is numbered automatically"
}

/**
 * Import-time error with the HTTP status the route layer should report. Carrying the status
 * on the exception keeps the catch block in `routes/projectExport.kt` to a single arm.
 */
class ImportError(
    val status: HttpStatusCode,
    message: String,
    /** A machine-readable code for a refusal a client branches on (`VENUE_MISSING`), else null. */
    val code: String? = null,
) : RuntimeException(message) {
    companion object {
        fun conflict(message: String, code: String? = null) = ImportError(HttpStatusCode.Conflict, message, code)
        fun unsupportedFormat(message: String) = ImportError(HttpStatusCode.UnprocessableEntity, message)
        fun invalidArchive(message: String) = ImportError(HttpStatusCode.BadRequest, message)
        fun notFound(message: String) = ImportError(HttpStatusCode.NotFound, message)

        /** A venue rule's refusal ([VenuePins.missing]), carried with its status and code. */
        fun of(e: VenueException) = ImportError(e.status, e.message ?: e.code, e.code)
    }
}

/**
 * Folders a later archive may hold that this reader cannot apply, refused by name rather than by
 * `formatVersion` (so the session that fills one needs no bump). Empty today: `showOverrides/` was
 * reserved here by venue plan session 4 and is read since session 5 ([ProjectImporter]'s
 * `importShowOverrides`) — which is why a session-4 desk refuses a show carrying overrides (422 on
 * import, `FORMAT_TOO_NEW` before a pull) rather than loading a different rig. Kept for the next folder
 * that needs the same treatment.
 */
internal val RESERVED_FOLDERS = emptyList<String>()

class ProjectImporter(private val state: State) {

    private val logger = LoggerFactory.getLogger(ProjectImporter::class.java)

    data class Result(
        val projectId: Int,
        val projectUuid: String,
        val name: String,
        /**
         * Explicit cue numbers the archive held twice in one stack — after a merge, two desks that
         * each typed `14.5` — each resolved by keeping the number on the entry with the lower uuid and
         * making the other auto (cue model plan §3.9). Empty for an archive any one desk wrote.
         */
        val numberClashes: List<CueNumberClash> = emptyList(),
    )

    /**
     * Imports a project from [sourceDir]. Single transaction — any failure rolls back. Refuses
     * (Conflict) if a project with the same UUID already exists, or if the imported name
     * collides with an existing project (use [nameOverride] to disambiguate). Forces
     * `isCurrent = false` on the new project.
     *
     * [descriptionOverride] replaces the archive's description when non-null; null keeps the
     * archive's. Used by [ProjectCloner] so a clone can be re-described at creation time.
     */
    fun import(
        sourceDir: Path,
        nameOverride: String?,
        descriptionOverride: String? = null,
        /**
         * The new project's kind, overriding the archive's (v24) — what a venue snapshot passes
         * (`venue/VenueVersions.kt`, `sync/VenueRepoSync.kt`). Null takes the archive's: a show,
         * unless it says otherwise. An archive of a version is never imported as one by itself: a
         * version is a tag of its venue's repo, with no index row of its own here.
         */
        kind: ProjectKind? = null,
        /**
         * Run inside the import's own transaction once every row has landed, so whatever the caller
         * records about the new project (a venue version's index row) commits or rolls back with it.
         */
        inTransaction: (DaoProject) -> Unit = {},
    ): Result {
        val (format, projectJson) = loadAndValidateArchive(sourceDir)
        val targetName = nameOverride ?: projectJson.name
        val targetUuid = UUID.fromString(projectJson.uuid)
        val targetKind = kind ?: projectJson.kind
        if (kind == null && targetKind == ProjectKind.VENUE_VERSION) {
            throw ImportError.invalidArchive(
                "This archive is a venue version: import its venue's repo, which brings every version with it",
            )
        }
        val pin = pinOf(projectJson, targetKind)

        val result = transaction(state.database) {
            // UUID-collision check first — a same-UUID project means we'd be merging, which
            // Phase 1 deliberately doesn't support.
            val uuidCollision = DaoProject.find { DaoProjects.uuid eq targetUuid }.firstOrNull()
            if (uuidCollision != null) {
                throw ImportError.conflict(
                    "A project with UUID $targetUuid already exists (\"${uuidCollision.name}\"). " +
                        "Phase 1 does not support merge; remove the existing project first."
                )
            }
            val nameCollision = DaoProject.find { DaoProjects.name eq targetName }.firstOrNull()
            if (nameCollision != null) {
                throw ImportError.conflict(
                    "A project named \"$targetName\" already exists. Provide a different name."
                )
            }

            // The pinned version must be here before a row lands: the show's links into it resolve
            // against its snapshot (v24).
            pin?.let { requireSnapshot(it, targetName) }

            // isCurrent forced false: importing must never silently switch which project the
            // operator is operating on. activeStackId stays null — importing doesn't preserve
            // operator UI state (which stack is live).
            val project = DaoProject.new {
                name = targetName
                description = descriptionOverride ?: projectJson.description
                isCurrent = false
                activeStackId = null
                stageWidthM = projectJson.stageWidthM
                stageDepthM = projectJson.stageDepthM
                stageHeightM = projectJson.stageHeightM
                uuid = targetUuid
                this.kind = targetKind
                venueUuid = pin?.venueUuid
                venueVersion = pin?.version
                venueRepo = pin?.hint
                val origin = originOf(projectJson, targetKind, pin)
                detachedFromVenueUuid = origin?.first
                detachedFromVersion = origin?.second
                inProduction = projectJson.inProduction
            }

            val clashes = populateProject(sourceDir, project, format.formatVersion, projectJson.fxLibrary)
            inTransaction(project)

            Result(
                projectId = project.id.value,
                projectUuid = targetUuid.toString(),
                name = targetName,
                numberClashes = clashes,
            )
        }

        // Copy any script PDF(s) shipped in the export folder into the local content
        // store (byte-accurate) so the prompt book renders without a manual re-import.
        // Done AFTER the transaction and best-effort: the store is only a UI cache with a
        // missing-PDF re-import fallback, and the copy can be up to 100MB — neither a copy
        // failure nor its duration should roll back or stall the committed import. The
        // sync-pull path hydrates in the engine instead, from the real git checkout.
        runCatching { PromptScriptRepoSync.hydrateStore(state, targetUuid, sourceDir) }
            .onFailure { logger.warn("Prompt-book PDF hydrate failed for imported project {}: {}", targetUuid, it.message) }
        // The scene images (v23) the same way, and for the same reasons: an image missing here
        // reads as an unpainted cloth, never as a failed import. This is also what fills a clone's
        // store — cloning is export → import — so `ProjectCloner` needs nothing of its own.
        runCatching { SceneImageRepoSync.hydrateStore(state, targetUuid, sourceDir) }
            .onFailure { logger.warn("Scene image hydrate failed for imported project {}: {}", targetUuid, it.message) }

        return result
    }

    /**
     * Replace an existing project's data with whatever is on disk at [sourceDir]. Used by
     * the cloud-sync pull path: the working tree has just been fast-forwarded to a new
     * remote SHA, and the DB needs to match.
     *
     *  * Validates the JSON's project UUID against the existing row — refuses to clobber a
     *    different project by accident.
     *  * Cascade-deletes child rows (cues, stacks, presets, etc.) and re-imports them from
     *    JSON, preserving the existing project row's `id` so non-synced FK references
     *    (`machine_overrides`, `sync_configs`) survive.
     *  * Updates the project's name + description from JSON.
     *
     * Runs in a single transaction — partial pulls can't leave the DB inconsistent.
     */
    fun replaceFromWorkingTree(projectId: Int, sourceDir: Path, historyReason: String = "Pulled from the sync repo"): Result =
        // A whole-project replace mints new row ids, so no entry before it can be put back (D5).
        replaceInTransaction(projectId, sourceDir).also { state.history.clear(projectId, historyReason) }

    /**
     * [replaceFromWorkingTree]'s import without its history clear, in the caller's transaction when
     * there is one: a snapshot restore's review runs it and rolls back, and its restore clears the
     * history itself once the transaction commits.
     */
    internal fun replaceInTransaction(projectId: Int, sourceDir: Path): Result {
        val (format, projectJson) = loadAndValidateArchive(sourceDir)
        val incomingUuid = UUID.fromString(projectJson.uuid)

        return transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw ImportError.invalidArchive("Project $projectId not found")
            if (project.uuid != incomingUuid) {
                throw ImportError.conflict(
                    "Working tree's project UUID ($incomingUuid) does not match local project ${project.id.value} (${project.uuid}). " +
                        "Refusing to clobber a different project."
                )
            }
            // A project is what it was made as: a show's repo never turns it into a venue.
            if (project.kind != projectJson.kind) {
                throw ImportError.conflict(
                    "Working tree's project is a ${projectJson.kind.name.lowercase()}, but local project ${project.id.value} " +
                        "is a ${project.kind.name.lowercase()}. Refusing to replace it.",
                )
            }
            val pin = pinOf(projectJson, project.kind)
            pin?.let { requireSnapshot(it, project.name) }

            // Mirrors the project-delete cascade in `routes/projects.kt` (same FK-safe
            // order) but leaves the DaoProject row plus non-synced child tables (machine
            // overrides, sync configs) alone.
            project.activeStackId = null
            // Iterate every book row for the project (not just project.promptBook) so a
            // pre-collapse project with leftover extra books is fully cleaned before its
            // cues go, avoiding orphaned anchors pointing at deleted cue rows.
            DaoPromptBook.find { DaoPromptBooks.project eq project.id }.forEach { book ->
                DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.promptBook eq book.id }
                DaoPromptBookAnnotations.deleteWhere { DaoPromptBookAnnotations.promptBook eq book.id }
                book.delete()
            }
            // Busk pages before the records their pads point at — a pad is a plain FK with no
            // cascade (`DaoBuskPads`), so it has to go first, and by hand: pads → banks → columns → page.
            project.buskPages.forEach { deleteBuskPage(it) }
            // The rig too, and **before** the group and patch deletes below: a tile is a plain FK
            // onto a group or a patch with no cascade (`DaoBuskRigTiles`), so it would block them.
            deleteBuskRig(project)
            // Scenery before its owners and its elements: all three tables, through the elements.
            deleteSceneryForElements(project.stageElements.map { it.id })
            // Cue events before their cues and patches, through the patches (v21).
            deleteCueEventsForPatches(project.fixturePatches.map { it.id })
            // And through their owners, for a show on a venue (v24): its cues fire venue patches and
            // its cues, stacks and Looks move venue elements, which the sweeps above never reach.
            // A stack's entries before its cues and itself: plain FKs onto both, with no cascade.
            project.cueStacks.forEach { stack -> DaoCueStackEntries.deleteWhere { DaoCueStackEntries.stack eq stack.id } }
            project.cues.forEach { cue ->
                deleteCueEvents(cue.id)
                deleteCueScenery(cue.id)
                deleteCueChildren(cue)
                cue.delete()
            }
            project.cueStacks.forEach { deleteStackScenery(it.id); it.delete() }
            project.cueSlots.forEach { it.delete() }
            // Layers before cues would be redundant — deleteCueChildren already drops them — but
            // looks must go after every layer that points at one.
            project.looks.forEach { look ->
                deleteLookScenery(look.id)
                look.rows.forEach { it.delete() }
                look.effects.forEach { it.delete() }
                look.delete()
            }
            // Templates after cues (deleteCueChildren already dropped any layer pointing at one).
            // Without this, the surviving row is untouched and `populateProject` below re-imports
            // the JSON templates on top of it, tripping `uniqueIndex(project, name)`.
            project.palettes.forEach { template ->
                template.rows.forEach { it.delete() }
                template.effects.forEach { it.delete() }
                template.delete()
            }
            project.speedMasters.forEach { it.delete() }
            project.fixtureGroups.forEach { group ->
                group.members.forEach { it.delete() }
                group.delete()
            }
            project.fixturePatches.forEach { deletePlacementsOf(it); it.delete() }
            project.riggings.forEach { it.delete() }
            project.stageRegions.forEach { it.delete() }
            project.stageViewpoints.forEach { it.delete() }
            project.stageElements.forEach { it.delete() }
            project.universeConfigs.forEach { it.delete() }
            project.parkedChannels.forEach { it.delete() }
            project.fxDefinitions.forEach { it.delete() }
            project.scripts.forEach { it.delete() }
            project.controlSurfaceBindings.forEach { it.delete() }
            DaoShowHides.deleteWhere { DaoShowHides.project eq project.id }
            DaoShowOverrides.deleteWhere { DaoShowOverrides.project eq project.id }
            DaoShowColours.deleteWhere { DaoShowColours.project eq project.id }
            DaoShowGroupOrders.deleteWhere { DaoShowGroupOrders.project eq project.id }
            DaoShowParkReleases.deleteWhere { DaoShowParkReleases.project eq project.id }

            project.name = projectJson.name
            project.description = projectJson.description
            project.stageWidthM = projectJson.stageWidthM
            project.stageDepthM = projectJson.stageDepthM
            project.stageHeightM = projectJson.stageHeightM
            // The pin follows the repo (a peer's Update moves it, venue plan session 6), and the hint
            // with it.
            project.venueUuid = pin?.venueUuid
            project.venueVersion = pin?.version
            project.venueRepo = pin?.hint
            val origin = originOf(projectJson, project.kind, pin)
            project.detachedFromVenueUuid = origin?.first
            project.detachedFromVersion = origin?.second
            project.inProduction = projectJson.inProduction

            val clashes = populateProject(sourceDir, project, format.formatVersion, projectJson.fxLibrary)

            Result(
                projectId = project.id.value,
                projectUuid = project.uuid.toString(),
                name = project.name,
                numberClashes = clashes,
            )
        }
    }

    /**
     * Put [targets] back into [project] (undo plan §3.5, D9), inside the caller's transaction: every
     * record whose target is null deleted, in reverse foreign-key order, then every other one upserted
     * in the importer's order — found by uuid and updated in place, its embedded children replaced, or
     * inserted with the row id its image keeps. What a restored record names resolves against the
     * project's rows as they then stand, the venue's under them for a show on one. Only the folders in
     * [RESTORABLE_FOLDERS].
     */
    internal fun restore(project: DaoProject, targets: Map<RecordKey, RecordImage?>) {
        val unknown = targets.keys.mapTo(HashSet()) { it.tableName } - RESTORABLE_FOLDERS
        require(unknown.isEmpty()) { "No restore for folder(s) $unknown" }

        val leaving = targets.filterValues { it == null }.keys
        // A stack that loses an entry renumbers from its order before, as the write that added it did.
        val shrunk = leaving.filter { it.tableName == CUE_STACK_ENTRIES }
            .mapNotNull { findEntry(project, it.uuid)?.stack }
            .distinctBy { it.id.value }
            .associateWith { cueEntryIds(it) }
        for (folder in RESTORE_ORDER.asReversed()) {
            leaving.filter { it.tableName == folder }.forEach { deleteRecord(project, it) }
        }
        shrunk.forEach { (stack, before) -> renumberAutoEntries(stack, before) }

        val images = targets.mapNotNull { (key, image) -> image?.let { key to it } }.toMap()
        if (images.isEmpty()) return
        freeSwappedUniques(project, images)
        val run = Run(ImageSource(images), upsert = true, rowIds = images.mapValues { it.value.rowId })
        val folders = images.keys.mapTo(HashSet()) { it.tableName }
        fun has(vararg folder: String) = folder.any { it in folders }
        // Each map is read only by a folder the undo restores, and built from the rows as they then stand,
        // each restored one overlaid in case its insert has not reached the table yet.
        val venue by lazy { VenueSeed.of(project) }
        // A show's hides, overrides, recolours and orders first, as an import takes them: nothing names them.
        with(RecordFolders) {
            if (has(SHOW_HIDES)) importShowHides(run, project)
            if (has(SHOW_OVERRIDES)) importShowOverrides(run, project)
            if (has(SHOW_COLOURS)) importShowColours(run, project)
            if (has(SHOW_GROUP_ORDERS)) importShowGroupOrders(run, project)
        }
        val elements = if (has(RecordFolders.STAGE_ELEMENTS)) importStageElements(run, project) else emptyMap()
        val elementMap by lazy { venue.elements + project.stageElements.associateBy { it.uuid } + elements }
        val scripts = if (has(RecordFolders.SCRIPTS)) importScripts(run, project) else emptyMap()
        val definitions = if (has(RecordFolders.FX_DEFINITIONS)) importFxDefinitions(run, project) else emptyMap()
        val legacy by lazy {
            legacyContext(run.source, (project.fxDefinitions.associateBy { it.uuid } + definitions).values, SUPPORTED_FORMAT_VERSION, null)
        }

        val looks = if (has(RecordFolders.LOOKS)) importLooks(run, project, elementMap, legacy) else emptyMap()
        val palettes = if (has(RecordFolders.PALETTES)) importPalettes(run, project, legacy) else emptyMap()
        if (has(RecordFolders.SPEED_MASTERS)) importSpeedMasters(run, project)
        val universes = if (has(RecordFolders.UNIVERSE_CONFIGS)) importUniverseConfigs(run, project) else emptyMap()
        val riggings = if (has(RecordFolders.RIGGINGS)) importRiggings(run, project) else emptyMap()
        if (has(RecordFolders.STAGE_REGIONS)) importStageRegions(run, project)
        if (has(RecordFolders.STAGE_VIEWPOINTS)) importStageViewpoints(run, project)
        val patches = if (has(RecordFolders.FIXTURE_PATCHES)) {
            importFixturePatches(
                run, project,
                venue.universes + project.universeConfigs.associateBy { it.uuid } + universes,
                venue.riggings + project.riggings.associateBy { it.uuid } + riggings,
            )
        } else {
            emptyMap()
        }
        val patchMap by lazy { venue.patches + project.fixturePatches.associateBy { it.uuid } + patches }
        val groups = if (has(RecordFolders.FIXTURE_GROUPS)) importFixtureGroups(run, project, patchMap) else emptyMap()
        val groupMap by lazy { venue.groups + project.fixtureGroups.associateBy { it.uuid } + groups }
        if (has(RecordFolders.BUSK_RIG)) importBuskRig(run, project, groupMap, patchMap)
        val stacks = if (has(RecordFolders.CUE_STACKS)) importCueStacks(run, project, elementMap) else emptyMap()
        val lookMap by lazy { project.looks.associateBy { it.uuid } + looks }
        val paletteMap by lazy { project.palettes.associateBy { it.uuid } + palettes }
        val cueStackMap by lazy { project.cueStacks.associateBy { it.uuid } + stacks }
        val cues = if (has(RecordFolders.CUES)) {
            importCues(run, project, cueStackMap, elementMap, patchMap, SUPPORTED_FORMAT_VERSION, ArrayList())
        } else {
            emptyMap()
        }
        val cueMap by lazy { project.cues.associateBy { it.uuid } + cues }
        val entries = if (has(CUE_STACK_ENTRIES)) importCueStackEntries(run, cueStackMap, cueMap, emptyList()).first else emptyMap()
        if (has(RecordFolders.CUE_ATTRIBUTE_ASSIGNMENTS)) importCueAttributeAssignments(run, cueMap)
        if (has(RecordFolders.CUE_LAYERS)) importCueLayers(run, cueMap, lookMap, paletteMap)
        if (has(RecordFolders.CUE_AD_HOC_FX)) importCueAdHocFx(run, cueMap, legacy)
        if (has(RecordFolders.CUE_TRIGGERS)) importCueTriggers(run, cueMap, project.scripts.associateBy { it.uuid } + scripts)
        val book = if (has(RecordFolders.PROMPT_BOOKS)) importPromptBook(run, project) else null
        val promptBook by lazy { book ?: project.promptBook }
        if (has(PROMPT_BOOK_ANCHORS)) {
            val entryMap = cueStackMap.values.flatMap { entriesOf(it) }.associateBy { it.uuid } + entries
            importPromptBookAnchors(run, promptBook, entryMap)
        }
        if (has(RecordFolders.PROMPT_BOOK_ANNOTATIONS)) importPromptBookAnnotations(run, promptBook)
        if (has(RecordFolders.CUE_SLOTS)) importCueSlots(run, project, cueMap, lookMap)
        if (has(RecordFolders.BUSK_PAGES)) importBuskPages(run, project, paletteMap, lookMap, cueMap)
        if (has(RecordFolders.PARKED_CHANNELS)) importParkedChannels(run, project)
        if (has(RecordFolders.SHOW_PARK_RELEASES)) {
            // The release import reads the show's parks, so the ones just put back must be in the table.
            TransactionManager.current().flushCache()
            importShowParkReleases(run, project)
        }
        if (has(RecordFolders.CONTROL_SURFACE_BINDINGS)) importControlSurfaceBindings(run, project)
        images[RecordKey(SyncDispositions.PROJECT_FOLDER, project.uuid)]?.let { restoreProjectRow(project, it) }
    }

    /**
     * Before an upsert writes a key or name the project holds unique, every standing row it rewrites to
     * another value is moved off its old one, so two records that traded (a `rename_fixtures` swap, a
     * renumbered master) can each take the other's back without meeting the table's unique index —
     * as `renameRows` lets the original swap through.
     */
    private fun freeSwappedUniques(project: DaoProject, images: Map<RecordKey, RecordImage>) {
        TransactionManager.current().flushCache()
        for (u in UNIQUE_TEXT) {
            for ((key, image) in images) {
                if (key.tableName != u.folder) continue
                val want = (image.jsonObjectOrNull()?.get(u.field) as? JsonPrimitive)?.content ?: continue
                val row = u.table.select(u.table.id, u.column).where { (u.uuid eq key.uuid) and (u.project eq project.id) }.firstOrNull() ?: continue
                if (row[u.column] == want) continue
                u.table.update({ u.table.id eq row[u.table.id] }) { it[u.column] = "~undo:${key.uuid}" }
            }
        }
        for ((key, image) in images) {
            if (key.tableName != RecordFolders.SPEED_MASTERS) continue
            val want = (image.jsonObjectOrNull()?.get("masterIndex") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val row = DaoSpeedMasters.select(DaoSpeedMasters.id, DaoSpeedMasters.masterIndex)
                .where { (DaoSpeedMasters.uuid eq key.uuid) and (DaoSpeedMasters.project eq project.id) }.firstOrNull() ?: continue
            if (row[DaoSpeedMasters.masterIndex] == want) continue
            DaoSpeedMasters.update({ DaoSpeedMasters.id eq row[DaoSpeedMasters.id] }) { it[masterIndex] = -1 - row[DaoSpeedMasters.id].value }
        }
    }

    /** A unique text column of a restorable folder's table, by its record's field. */
    private class UniqueText(
        val folder: String,
        val table: IntIdTable,
        val uuid: Column<UUID>,
        val project: Column<EntityID<Int>>,
        val column: Column<String>,
        val field: String,
    )

    private val UNIQUE_TEXT = listOf(
        UniqueText(RecordFolders.FIXTURE_PATCHES, DaoFixturePatches, DaoFixturePatches.uuid, DaoFixturePatches.project, DaoFixturePatches.key, "key"),
        UniqueText(RecordFolders.FIXTURE_GROUPS, DaoFixtureGroups, DaoFixtureGroups.uuid, DaoFixtureGroups.project, DaoFixtureGroups.name, "name"),
        UniqueText(RecordFolders.RIGGINGS, DaoRiggings, DaoRiggings.uuid, DaoRiggings.project, DaoRiggings.name, "name"),
        UniqueText(RecordFolders.STAGE_REGIONS, DaoStageRegions, DaoStageRegions.uuid, DaoStageRegions.project, DaoStageRegions.name, "name"),
        UniqueText(RecordFolders.STAGE_ELEMENTS, DaoStageElements, DaoStageElements.uuid, DaoStageElements.project, DaoStageElements.name, "name"),
        UniqueText(RecordFolders.STAGE_VIEWPOINTS, DaoStageViewpoints, DaoStageViewpoints.uuid, DaoStageViewpoints.project, DaoStageViewpoints.name, "name"),
        UniqueText(RecordFolders.SCRIPTS, DaoScripts, DaoScripts.uuid, DaoScripts.project, DaoScripts.name, "name"),
        UniqueText(RecordFolders.SPEED_MASTERS, DaoSpeedMasters, DaoSpeedMasters.uuid, DaoSpeedMasters.project, DaoSpeedMasters.name, "name"),
        UniqueText(RecordFolders.FX_DEFINITIONS, DaoFxDefinitions, DaoFxDefinitions.uuid, DaoFxDefinitions.project, DaoFxDefinitions.fxId, "fxId"),
        UniqueText(RecordFolders.LOOKS, DaoLooks, DaoLooks.uuid, DaoLooks.project, DaoLooks.name, "name"),
        UniqueText(RecordFolders.PALETTES, DaoPalettes, DaoPalettes.uuid, DaoPalettes.project, DaoPalettes.name, "name"),
        UniqueText(RecordFolders.BUSK_PAGES, DaoBuskPages, DaoBuskPages.uuid, DaoBuskPages.project, DaoBuskPages.name, "name"),
    )

    /** `project.json`'s own fields put back; its kind, pin and origin move only through a clear point. */
    private fun restoreProjectRow(project: DaoProject, image: RecordImage) {
        val json = canonicalDecode(ProjectJson.serializer(), image.json)
        project.name = json.name
        project.description = json.description
        project.stageWidthM = json.stageWidthM
        project.stageDepthM = json.stageDepthM
        project.stageHeightM = json.stageHeightM
        project.inProduction = json.inProduction
    }

    private fun findEntry(project: DaoProject, uuid: UUID): DaoCueStackEntry? =
        DaoCueStackEntry.find { DaoCueStackEntries.uuid eq uuid }.firstOrNull { it.stack.project.id == project.id }

    /** Delete one record of [project] with its embedded children; one already gone is nothing to do. */
    private fun deleteRecord(project: DaoProject, key: RecordKey) {
        val uuid = key.uuid
        when (key.tableName) {
            RecordFolders.LOOKS -> DaoLook.find { (DaoLooks.uuid eq uuid) and (DaoLooks.project eq project.id) }.firstOrNull()
                ?.let { deleteLookChildren(it); it.delete() }
            RecordFolders.PALETTES -> DaoPalette.find { (DaoPalettes.uuid eq uuid) and (DaoPalettes.project eq project.id) }.firstOrNull()
                ?.let { deletePaletteChildren(it); it.delete() }
            RecordFolders.CUE_STACKS -> DaoCueStack.find { (DaoCueStacks.uuid eq uuid) and (DaoCueStacks.project eq project.id) }.firstOrNull()
                ?.let { deleteStackScenery(it.id); it.delete() }
            RecordFolders.CUES -> DaoCue.find { (DaoCues.uuid eq uuid) and (DaoCues.project eq project.id) }.firstOrNull()
                ?.let { deleteCueScenery(it.id); deleteCueEvents(it.id); it.delete() }
            CUE_STACK_ENTRIES -> findEntry(project, uuid)?.delete()
            RecordFolders.CUE_ATTRIBUTE_ASSIGNMENTS -> DaoCueAttributeAssignment.find { DaoCueAttributeAssignments.uuid eq uuid }
                .firstOrNull { it.cue.project.id == project.id }?.delete()
            RecordFolders.CUE_LAYERS -> DaoCueLayer.find { DaoCueLayers.uuid eq uuid }.firstOrNull { it.cue.project.id == project.id }?.delete()
            RecordFolders.CUE_AD_HOC_FX -> DaoCueAdHocFxEntity.find { DaoCueAdHocFx.uuid eq uuid }.firstOrNull { it.cue.project.id == project.id }?.delete()
            RecordFolders.CUE_TRIGGERS -> DaoCueTrigger.find { DaoCueTriggers.uuid eq uuid }.firstOrNull { it.cue.project.id == project.id }?.delete()
            RecordFolders.CUE_SLOTS -> DaoCueSlot.find { (DaoCueSlots.uuid eq uuid) and (DaoCueSlots.project eq project.id) }.firstOrNull()?.delete()
            RecordFolders.BUSK_PAGES -> DaoBuskPage.find { (DaoBuskPages.uuid eq uuid) and (DaoBuskPages.project eq project.id) }.firstOrNull()
                ?.let { deleteBuskPage(it) }
            RecordFolders.BUSK_RIG -> DaoBuskRigRow.find { (DaoBuskRigRows.uuid eq uuid) and (DaoBuskRigRows.project eq project.id) }.firstOrNull()
                ?.let { deleteBuskRigRow(it) }
            PROMPT_BOOK_ANCHORS -> DaoPromptBookAnchor.find { DaoPromptBookAnchors.uuid eq uuid }
                .firstOrNull { it.promptBook.project.id == project.id }?.delete()
            // What else a record's own delete route sweeps — tiles, members, events, anchors — is a
            // record of its own, which the undo takes away beside it or refuses as in use. Machine-local
            // rows naming it (an address, a display detail) are left, so a redo finds them again.
            RecordFolders.FIXTURE_PATCHES -> DaoFixturePatch.find { (DaoFixturePatches.uuid eq uuid) and (DaoFixturePatches.project eq project.id) }
                .firstOrNull()?.let { deletePlacementsOf(it); it.delete() }
            RecordFolders.FIXTURE_GROUPS -> DaoFixtureGroup.find { (DaoFixtureGroups.uuid eq uuid) and (DaoFixtureGroups.project eq project.id) }
                .firstOrNull()?.let { group -> DaoFixtureGroupMembers.deleteWhere { DaoFixtureGroupMembers.group eq group.id }; group.delete() }
            RecordFolders.UNIVERSE_CONFIGS -> DaoUniverseConfig.find { (DaoUniverseConfigs.uuid eq uuid) and (DaoUniverseConfigs.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.RIGGINGS -> DaoRigging.find { (DaoRiggings.uuid eq uuid) and (DaoRiggings.project eq project.id) }.firstOrNull()?.delete()
            RecordFolders.STAGE_REGIONS -> DaoStageRegion.find { (DaoStageRegions.uuid eq uuid) and (DaoStageRegions.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.STAGE_ELEMENTS -> DaoStageElement.find { (DaoStageElements.uuid eq uuid) and (DaoStageElements.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.STAGE_VIEWPOINTS -> DaoStageViewpoint.find { (DaoStageViewpoints.uuid eq uuid) and (DaoStageViewpoints.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.SCRIPTS -> DaoScript.find { (DaoScripts.uuid eq uuid) and (DaoScripts.project eq project.id) }.firstOrNull()?.delete()
            RecordFolders.FX_DEFINITIONS -> DaoFxDefinition.find { (DaoFxDefinitions.uuid eq uuid) and (DaoFxDefinitions.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.SPEED_MASTERS -> DaoSpeedMaster.find { (DaoSpeedMasters.uuid eq uuid) and (DaoSpeedMasters.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.PARKED_CHANNELS -> DaoParkedChannel.find { (DaoParkedChannels.uuid eq uuid) and (DaoParkedChannels.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.SHOW_PARK_RELEASES -> DaoShowParkRelease.find { (DaoShowParkReleases.uuid eq uuid) and (DaoShowParkReleases.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.CONTROL_SURFACE_BINDINGS -> DaoControlSurfaceBinding.find {
                (DaoControlSurfaceBindings.uuid eq uuid) and (DaoControlSurfaceBindings.project eq project.id)
            }.firstOrNull()?.delete()
            RecordFolders.PROMPT_BOOKS -> DaoPromptBook.find { (DaoPromptBooks.uuid eq uuid) and (DaoPromptBooks.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.PROMPT_BOOK_ANNOTATIONS -> DaoPromptBookAnnotation.find { DaoPromptBookAnnotations.uuid eq uuid }
                .firstOrNull { it.promptBook.project.id == project.id }?.delete()
            RecordFolders.SHOW_HIDES -> DaoShowHide.find { (DaoShowHides.uuid eq uuid) and (DaoShowHides.project eq project.id) }.firstOrNull()?.delete()
            RecordFolders.SHOW_OVERRIDES -> DaoShowOverride.find { (DaoShowOverrides.uuid eq uuid) and (DaoShowOverrides.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.SHOW_COLOURS -> DaoShowColour.find { (DaoShowColours.uuid eq uuid) and (DaoShowColours.project eq project.id) }
                .firstOrNull()?.delete()
            RecordFolders.SHOW_GROUP_ORDERS -> DaoShowGroupOrder.find { (DaoShowGroupOrders.uuid eq uuid) and (DaoShowGroupOrders.project eq project.id) }
                .firstOrNull()?.delete()
            else -> error("An undo never deletes a ${key.tableName} record")
        }
    }

    private fun deleteLookChildren(look: DaoLook) {
        DaoLookRows.deleteWhere { DaoLookRows.look eq look.id }
        DaoLookFx.deleteWhere { DaoLookFx.look eq look.id }
        deleteLookScenery(look.id)
    }

    private fun deletePaletteChildren(palette: DaoPalette) {
        DaoPaletteRows.deleteWhere { DaoPaletteRows.palette eq palette.id }
        DaoPaletteFx.deleteWhere { DaoPaletteFx.palette eq palette.id }
    }

    private fun deleteBuskRigTiles(row: DaoBuskRigRow) {
        DaoBuskRigTiles.deleteWhere { DaoBuskRigTiles.row eq row.id }
    }

    private fun deleteBuskRigRow(row: DaoBuskRigRow) {
        deleteBuskRigTiles(row)
        row.delete()
    }

    /**
     * Common up-front validation for both [import] and [replaceFromWorkingTree]. Reads
     * `formatVersion.json` and `project.json`, version-checks, and returns the parsed
     * pair. Done before opening a DB transaction so a malformed archive never
     * partially-mutates the DB.
     */
    private fun loadAndValidateArchive(sourceDir: Path): Pair<FormatVersionJson, ProjectJson> {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw ImportError.invalidArchive("Folder not found: $sourceDir")
        }
        val formatPath = sourceDir.resolve("formatVersion.json")
        if (!formatPath.exists()) {
            throw ImportError.invalidArchive("Missing formatVersion.json")
        }
        val format = canonicalDecode(FormatVersionJson.serializer(), Files.readString(formatPath))
        if (format.formatVersion > SUPPORTED_FORMAT_VERSION) {
            throw ImportError.unsupportedFormat(
                "Repo format v${format.formatVersion} is newer than this install supports (v$SUPPORTED_FORMAT_VERSION). Upgrade lighting7."
            )
        }
        if (format.formatVersion < MIN_SUPPORTED_FORMAT_VERSION) {
            throw ImportError.unsupportedFormat(
                "Repo format v${format.formatVersion} is older than this install supports (v$MIN_SUPPORTED_FORMAT_VERSION). Re-export from a newer install."
            )
        }
        val projectPath = sourceDir.resolve("project.json")
        if (!projectPath.exists()) {
            throw ImportError.invalidArchive("Missing project.json")
        }
        val projectJson = canonicalDecode(ProjectJson.serializer(), Files.readString(projectPath))
        RESERVED_FOLDERS.firstOrNull { folder ->
            val dir = sourceDir.resolve(folder)
            dir.isDirectory() && Files.list(dir).use { it.findAny().isPresent }
        }?.let { folder ->
            throw ImportError.unsupportedFormat(
                "This archive holds $folder/, which a newer lighting7 writes and this install cannot apply. Upgrade lighting7."
            )
        }
        return format to projectJson
    }

    /** A show's pin as an archive states it (v24), or null for a standalone show, a venue and a version. */
    private data class Pin(val venueUuid: UUID, val version: Int, val hint: String?)

    private fun pinOf(projectJson: ProjectJson, kind: ProjectKind): Pin? {
        if (kind != ProjectKind.SHOW) return null
        val venueUuid = projectJson.venueUuid ?: return null
        val uuid = runCatching { UUID.fromString(venueUuid) }.getOrNull()
            ?: throw ImportError.invalidArchive("project.json's venueUuid is not a uuid: $venueUuid")
        val version = projectJson.venueVersion
            ?: throw ImportError.invalidArchive("project.json names venue $venueUuid but no venueVersion")
        if (version < 1) throw ImportError.invalidArchive("project.json's venueVersion must be at least 1: $version")
        return Pin(uuid, version, projectJson.venueRepo?.takeIf { it.isNotBlank() })
    }

    /**
     * A standalone show's origin as an archive states it (venue-attach plan D1), or null — for a show
     * on a venue, a venue, a version, and an archive naming none. Both fields or neither; a half or an
     * unparseable one reads as none rather than failing the import: the origin only steers an attach.
     */
    private fun originOf(projectJson: ProjectJson, kind: ProjectKind, pin: Pin?): Pair<UUID, Int>? {
        if (kind != ProjectKind.SHOW || pin != null) return null
        val uuid = projectJson.detachedFromVenueUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val version = projectJson.detachedFromVersion?.takeIf { it >= 1 } ?: return null
        return uuid to version
    }

    /** Refuse (409 `VENUE_MISSING`) a pin whose snapshot is not on this desk. Inside a transaction. */
    private fun requireSnapshot(pin: Pin, showName: String) {
        if (VenuePins.snapshotOf(pin.venueUuid, pin.version) == null) {
            throw ImportError.of(VenuePins.missing(pin.venueUuid, pin.version, pin.hint, showName))
        }
    }

    /**
     * Read every per-table directory under [sourceDir] and create the corresponding rows
     * under [project]. Shared by both the fresh-import path and the working-tree-replace
     * path; the topological order matters because parent rows must exist before children
     * dereference them through the maps returned by each step.
     */
    private fun populateProject(sourceDir: Path, project: DaoProject, formatVersion: Int, fxLibrary: Int?): List<CueNumberClash> {
        val run = Run(DirSource(sourceDir))
        // v24: a show on a venue links into the pinned snapshot's rows, which its archive does not
        // carry. Every map starts from them, and the archive's own rows are laid over the top — so a
        // link resolves to the show's own row where it has one (a Preset seating it copied keeps the
        // venue's uuid) and to the venue's otherwise. Hides are imported here too, before the strip
        // passes below judge rows through the rig scope.
        val venue = VenueSeed.of(project)
        importShowHides(run, project)
        importShowOverrides(run, project)
        importShowColours(run, project)
        importShowGroupOrders(run, project)
        // Elements first (v20): a Look's, a stack's and a cue's scenery name one, and an element
        // names nothing that needs importing before it (a platform's region is a uuid in its params).
        val elementMap = venue.elements + importStageElements(run, project)
        val scriptMap = importScripts(run, project)
        val legacy = legacyContext(run.source, importFxDefinitions(run, project).values, formatVersion, fxLibrary)
        val lookMap = importLooks(run, project, elementMap, legacy)
        val paletteMap = importPalettes(run, project, legacy)
        importSpeedMasters(run, project)
        val universeMap = venue.universes + importUniverseConfigs(run, project)
        val riggingMap = venue.riggings + importRiggings(run, project)
        importStageRegions(run, project)
        // The viewpoints after the elements their seats name (v18), imported above.
        importStageViewpoints(run, project)
        val patchMap = venue.patches + importFixturePatches(run, project, universeMap, riggingMap)
        val groupMap = venue.groups + importFixtureGroups(run, project, patchMap)
        // The rig after the groups and patches its tiles name (v12).
        importBuskRig(run, project, groupMap, patchMap)
        val cueStackMap = importCueStacks(run, project, elementMap)
        val legacyPlaces = ArrayList<PendingEntry>()
        val cueMap = importCues(run, project, cueStackMap, elementMap, patchMap, formatVersion, legacyPlaces)
        val (entryMap, numberClashes) = importCueStackEntries(run, cueStackMap, cueMap, legacyPlaces)
        importCueAttributeAssignments(run, cueMap)
        importCueLayers(run, cueMap, lookMap, paletteMap)
        importCueAdHocFx(run, cueMap, legacy)
        importCueTriggers(run, cueMap, scriptMap)
        importLegacyShowOrder(sourceDir, project, cueStackMap)
        val promptBook = importPromptBook(run, project)
        importPromptBookAnchors(run, promptBook, entryMap)
        importPromptBookAnnotations(run, promptBook)
        importCueSlots(run, project, cueMap, lookMap)
        // Pages after every record a pad can name.
        importBuskPages(run, project, paletteMap, lookMap, cueMap)
        importParkedChannels(run, project)
        importShowParkReleases(run, project)
        importControlSurfaceBindings(run, project)
        // Last, once the patches and groups exist to judge them by: an archive written before v21 may
        // still hold a row naming a one-shot trigger, and none may come back (stage-view session 9).
        val stripped = stripTriggerRows(project)
        if (stripped > 0) logger.warn("Import: stripped {} row(s) naming a one-shot trigger", stripped)
        // Likewise a fixture command (fixture optics plan session 7): a reset stored as a setting option
        // before the commands existed.
        val strippedCommands = stripCommandRows(project)
        if (strippedCommands > 0) logger.warn("Import: stripped {} row(s) holding a fixture command", strippedCommands)
        if (formatVersion < LegacyNames.PALETTE_REFERENCES_FORMAT_VERSION) convertPaletteLayers(project)
        checkPaletteReferenceRows(project)
        return numberClashes
    }

    /**
     * Below v47: the archive's plain cue palette layers become reference rows, by the decision
     * database step 10 makes ([PaletteLayerConversion]); every other one stays a layer, logged.
     */
    private fun convertPaletteLayers(project: DaoProject) {
        val transaction = TransactionManager.current()
        transaction.flushCache()
        val plans = with(PaletteLayerConversion) { transaction.plan(setOf(project.id.value)) }
        logKept(plans, logger)
        if (plans.none { it.decision.changes }) return
        with(PaletteLayerConversion) { transaction.apply(plans) }
        transaction.entityCache.data.remove(DaoCueLayers)
        transaction.entityCache.clearReferrersCache()
        logConverted(plans.filter { it.decision.changes }, logger)
    }

    /**
     * v47: every cue row and Look row whose value is a palette reference names a palette the project
     * now holds, else the import is refused naming each row (palette-references plan D7) — a desk
     * never stores a reference that cannot resolve.
     */
    private fun checkPaletteReferenceRows(project: DaoProject) {
        TransactionManager.current().flushCache()
        val held = DaoPalette.find { DaoPalettes.project eq project.id }.mapTo(HashSet()) { it.uuid }
        val problems = ArrayList<String>()
        for (cue in project.cues) {
            for (row in cue.attributeAssignments) {
                val uuid = row.paletteUuid ?: continue
                if (uuid !in held) problems += "cue '${cue.name}' row ${row.uuid} (${row.targetKey} ${row.attribute}) names palette $uuid, which the archive does not hold"
            }
        }
        for (look in DaoLook.find { DaoLooks.project eq project.id }) {
            for (row in look.rows) {
                val uuid = row.paletteUuid ?: continue
                if (uuid !in held) problems += "Look '${look.name}' row ${row.uuid} (${row.targetKey} ${row.attribute}) names palette $uuid, which the archive does not hold"
            }
        }
        if (problems.isNotEmpty()) {
            throw ImportError(HttpStatusCode.BadRequest, problems.joinToString("; "), PaletteReferenceInvalidException.CODE)
        }
    }

    /** v36: a stored colour, as the archive has it; one too long for the column is dropped, and said. */
    private fun importedColour(raw: String?, what: String): String? {
        val stored = FixtureColours.storable(raw)
        if (raw != null && raw.isNotBlank() && stored == null) {
            logger.warn("Import: {}'s colour '{}' is longer than {} characters; dropping it", what, raw, FixtureColours.MAX_LENGTH)
        }
        return stored
    }

    /**
     * v36: a show's recolours of its venue's patches and groups, stored as the archive has them. One
     * naming no record of the pinned version is kept, as a hide is; one an archive repeats for a record
     * (a merge that kept both sides) is kept once.
     */
    private fun importShowColours(run: Run, project: DaoProject) {
        val seen = HashSet<UUID>()
        run.read("showColours") { json ->
            val c = canonicalDecode(ShowColourJson.serializer(), json)
            val uuid = runCatching { UUID.fromString(c.uuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("A show colour's uuid is not a uuid: ${c.uuid}")
            val recordUuid = runCatching { UUID.fromString(c.recordUuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("Show colour ${c.uuid} names no record uuid: ${c.recordUuid}")
            val colour = importedColour(c.colour, "Show colour ${c.uuid}")
            when {
                project.kind != ProjectKind.SHOW || project.venueUuid == null ->
                    logger.warn("Import: show colour {} in a project on no venue; dropping it", c.uuid)
                colour == null -> Unit
                !seen.add(recordUuid) ->
                    logger.warn("Import: show colour {} names {} a second time; dropping it", c.uuid, c.recordUuid)
                else -> run.row(
                    DaoShowColour, "showColours", uuid,
                    find = { DaoShowColour.find { (DaoShowColours.uuid eq uuid) and (DaoShowColours.project eq project.id) }.firstOrNull() },
                ) {
                    this.project = project
                    this.recordUuid = recordUuid
                    this.colour = colour
                    this.uuid = uuid
                }
            }
            uuid to Unit
        }
    }

    /**
     * v44: a show's orders of its venue's groups, stored as the archive has them: the members name the
     * pinned version's patches, which the merge reads, so one naming a patch the version lacks is kept
     * and skipped there. One naming no group of the pinned version is kept, as a hide is; one an archive
     * repeats for a group is kept once.
     */
    private fun importShowGroupOrders(run: Run, project: DaoProject) {
        val seen = HashSet<UUID>()
        run.read("showGroupOrders") { json ->
            val o = canonicalDecode(ShowGroupOrderJson.serializer(), json)
            val uuid = runCatching { UUID.fromString(o.uuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("A show group order's uuid is not a uuid: ${o.uuid}")
            val groupUuid = runCatching { UUID.fromString(o.groupUuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("Show group order ${o.uuid} names no group uuid: ${o.groupUuid}")
            val members = o.members.map { m ->
                runCatching { UUID.fromString(m) }.getOrNull()
                    ?: throw ImportError.invalidArchive("Show group order ${o.uuid} names a member that is not a uuid: $m")
            }
            when {
                project.kind != ProjectKind.SHOW || project.venueUuid == null ->
                    logger.warn("Import: show group order {} in a project on no venue; dropping it", o.uuid)
                !seen.add(groupUuid) ->
                    logger.warn("Import: show group order {} names {} a second time; dropping it", o.uuid, o.groupUuid)
                else -> run.row(
                    DaoShowGroupOrder, "showGroupOrders", uuid,
                    find = { DaoShowGroupOrder.find { (DaoShowGroupOrders.uuid eq uuid) and (DaoShowGroupOrders.project eq project.id) }.firstOrNull() },
                ) {
                    this.project = project
                    this.groupUuid = groupUuid
                    this.members = ShowGroupOrders.encode(members)
                    this.uuid = uuid
                }
            }
            uuid to Unit
        }
    }

    /**
     * v24: a show's hides, stored as the archive has them. A hide naming no record of the pinned
     * version is kept — it hides nothing, and an Update to a version that has the record again
     * (session 6) honours it — and one an archive repeats (a merge that kept both sides) is kept once.
     */
    private fun importShowHides(run: Run, project: DaoProject) {
        val seen = HashSet<Pair<HideKind, UUID>>()
        run.read("showHides") { json ->
            val h = canonicalDecode(ShowHideJson.serializer(), json)
            val uuid = runCatching { UUID.fromString(h.uuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("A show hide's uuid is not a uuid: ${h.uuid}")
            val recordUuid = runCatching { UUID.fromString(h.recordUuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("Show hide ${h.uuid} names no record uuid: ${h.recordUuid}")
            if (project.kind != ProjectKind.SHOW || project.venueUuid == null) {
                logger.warn("Import: hide {} in a project on no venue; dropping it", h.uuid)
            } else if (seen.add(h.recordKind to recordUuid)) {
                run.row(
                    DaoShowHide, "showHides", uuid,
                    find = { DaoShowHide.find { (DaoShowHides.uuid eq uuid) and (DaoShowHides.project eq project.id) }.firstOrNull() },
                ) {
                    this.project = project
                    recordKind = h.recordKind
                    this.recordUuid = recordUuid
                    this.uuid = uuid
                }
            } else {
                logger.warn("Import: hide {} names {} {} a second time; dropping it", h.uuid, h.recordKind, h.recordUuid)
            }
            uuid to Unit
        }
    }

    /**
     * v24 (venue plan session 5): a show's facet overrides, stored as the archive has them, each value
     * and base normalised through the facet's shape. One naming a patch the pinned version lacks is kept
     * — it overrides nothing, and an Update to a version that has the patch again honours it — as a hide
     * is; one an archive repeats for a patch and facet (a merge that kept both sides) is kept once.
     */
    private fun importShowOverrides(run: Run, project: DaoProject) {
        val seen = HashSet<Pair<UUID, PatchFacet>>()
        run.read("showOverrides") { json ->
            val o = canonicalDecode(ShowOverrideJson.serializer(), json)
            val uuid = runCatching { UUID.fromString(o.uuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("A show override's uuid is not a uuid: ${o.uuid}")
            val recordUuid = runCatching { UUID.fromString(o.recordUuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("Show override ${o.uuid} names no patch uuid: ${o.recordUuid}")
            val value = decodeFacet(o.facet, o.value)
                ?: throw ImportError.invalidArchive("Show override ${o.uuid}'s value is not a ${o.facet} facet")
            val base = decodeFacet(o.facet, o.base)
                ?: throw ImportError.invalidArchive("Show override ${o.uuid}'s base is not a ${o.facet} facet")
            if (project.kind != ProjectKind.SHOW || project.venueUuid == null) {
                logger.warn("Import: override {} in a project on no venue; dropping it", o.uuid)
            } else if (seen.add(recordUuid to o.facet)) {
                run.row(
                    DaoShowOverride, "showOverrides", uuid,
                    find = { DaoShowOverride.find { (DaoShowOverrides.uuid eq uuid) and (DaoShowOverrides.project eq project.id) }.firstOrNull() },
                ) {
                    this.project = project
                    this.recordUuid = recordUuid
                    facet = o.facet
                    this.value = encodeFacet(value)
                    this.base = encodeFacet(base)
                    this.uuid = uuid
                }
            } else {
                logger.warn("Import: override {} names {} of {} a second time; dropping it", o.uuid, o.facet, o.recordUuid)
            }
            uuid to Unit
        }
    }

    /**
     * The pinned snapshot's rows a show's links may name (v24), by uuid: every row of the version,
     * hidden or not — a show group may hold a venue patch the show hides, and the rig scope filters
     * it. Empty for anything but a show on a venue. Inside the import's transaction.
     */
    private class VenueSeed(
        val universes: Map<UUID, DaoUniverseConfig>,
        val riggings: Map<UUID, DaoRigging>,
        val elements: Map<UUID, DaoStageElement>,
        val patches: Map<UUID, DaoFixturePatch>,
        val groups: Map<UUID, DaoFixtureGroup>,
    ) {
        companion object {
            private val NONE = VenueSeed(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

            fun of(project: DaoProject): VenueSeed {
                if (project.kind != ProjectKind.SHOW) return NONE
                val venueUuid = project.venueUuid ?: return NONE
                val snapshot = VenuePins.snapshotOf(venueUuid, project.venueVersion ?: return NONE)
                    ?: throw ImportError.of(VenuePins.missing(project))
                return VenueSeed(
                    universes = snapshot.universeConfigs.associateBy { it.uuid },
                    riggings = snapshot.riggings.associateBy { it.uuid },
                    elements = snapshot.stageElements.associateBy { it.uuid },
                    patches = snapshot.fixturePatches.associateBy { it.uuid },
                    groups = snapshot.fixtureGroups.associateBy { it.uuid },
                )
            }
        }
    }

    /** Scripts: each record is its meta with its body as `body` ([RecordSource]). */
    private fun importScripts(run: Run, project: DaoProject): Map<UUID, DaoScript> =
        run.read("scripts") { json ->
            val meta = canonicalDecode(ScriptMetaJson.serializer(), json)
            val body = (canonicalJson.parseToJsonElement(json).jsonObject["body"] as? JsonPrimitive)?.content.orEmpty()
            val uuid = UUID.fromString(meta.uuid)
            val dao = run.row(
                DaoScript, "scripts", uuid,
                find = { DaoScript.find { (DaoScripts.uuid eq uuid) and (DaoScripts.project eq project.id) }.firstOrNull() },
            ) {
                name = meta.name
                script = body
                this.project = project
                scriptType = ScriptType.valueOf(LegacyNames.scriptType(meta.scriptType))
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importFxDefinitions(run: Run, project: DaoProject): Map<UUID, DaoFxDefinition> =
        run.read("fxDefinitions") { json ->
            val d = canonicalDecode(FxDefinitionJson.serializer(), json)
            if (LegacyNames.fxMode(d.fxMode) != d.fxMode) {
                logger.warn("Import: FX definition '{}': the composite mode is gone, so its script must now return one output", d.fxId)
            }
            val uuid = UUID.fromString(d.uuid)
            val dao = run.row(
                DaoFxDefinition, "fxDefinitions", uuid,
                find = { DaoFxDefinition.find { (DaoFxDefinitions.uuid eq uuid) and (DaoFxDefinitions.project eq project.id) }.firstOrNull() },
            ) {
                fxId = d.fxId
                name = d.name
                family = LegacyNames.familyOf(d.family, d.outputType.name)
                outputType = d.outputType
                fxMode = FxMode.valueOf(LegacyNames.fxMode(d.fxMode))
                parameters = d.parameters?.let {
                    canonicalJson.decodeFromJsonElement(ListSerializer(ParameterInfo.serializer()), it)
                } ?: emptyList()
                compatibleAttributes = d.compatibleAttributes
                script = d.script
                this.project = project
                defaultTiming = d.defaultTiming
                timingSource = d.timingSource
                this.uuid = uuid
            }
            uuid to dao
        }

    /**
     * Looks, with their rows and effects. Returns a uuid → DAO map because a cue *layer* references
     * its Look through a real FK — unlike a named palette, which was only ever named by a `ref:{uuid}`
     * string inside an opaque `value` column and so needed no map.
     */
    private fun importLooks(
        run: Run,
        project: DaoProject,
        elementMap: Map<UUID, DaoStageElement>,
        legacy: LegacyNames.Context,
    ): Map<UUID, DaoLook> =
        run.read("looks") { json ->
            val l = canonicalDecode(LookJson.serializer(), json)
            val uuid = UUID.fromString(l.uuid)
            val dao = run.row(
                DaoLook, "looks", uuid,
                find = { DaoLook.find { (DaoLooks.uuid eq uuid) and (DaoLooks.project eq project.id) }.firstOrNull() },
                clear = { deleteLookScenery(it.id) },
            ) {
                this.project = project
                name = l.name
                notes = l.notes
                this.uuid = uuid
            }
            val rows = run.children(dao.rows, { it.uuid }, l.rows.mapTo(HashSet()) { UUID.fromString(it.uuid) })
            val fxRows = run.children(dao.effects, { it.uuid }, l.fx.mapTo(HashSet()) { UUID.fromString(it.uuid) })
            l.rows.forEach { r ->
                rows.row(DaoLookRow, UUID.fromString(r.uuid)) {
                    look = dao
                    targetType = r.targetType
                    targetKey = r.targetKey
                    attribute = r.attribute
                    value = r.value
                    fadeDuration = r.fadeDurationMs.asDuration()
                    elementKey = r.elementKey
                    sortOrder = r.sortOrder
                    this.uuid = UUID.fromString(r.uuid)
                }
            }
            // No strict effect-enum check here, deliberately, unlike the authoring routes: a
            // snapshot can come from a *newer* build whose blend modes this one does not know
            // yet, and refusing the whole import over one unrecognised string is worse than the
            // warn `FxSpecCoercion.Lenient` logs when the effect eventually spawns.
            //
            // A deferred effect is dropped: a Look effect names its own target (absolute-looks plan
            // D2), and one an older desk wrote ran on whatever applied the Look.
            l.fx.forEach { stored ->
                if (stored.targetType == DEFERRED_TARGET_TYPE) {
                    logger.warn("Import: Look '{}' ({}): effect {} ({}) names no target of its own; dropping it", l.name, l.uuid, stored.uuid, stored.fxType)
                    return@forEach
                }
                val e = legacyFx("Look '${l.name}'", stored.storedFx(), legacy)?.let { stored.with(it) } ?: stored
                fxRows.row(DaoLookFxEntity, UUID.fromString(e.uuid)) {
                    look = dao
                    targetType = e.targetType
                    targetKey = e.targetKey
                    fxType = e.fxType
                    family = e.family
                    attribute = e.attribute
                    // An older archive's Seconds as Own tempo, or Natural, with no master, as step 12 writes it (fx-speed plan D5).
                    val clock = importedClock(e, legacy)
                    cycle = clock.cycle
                    blendMode = e.blendMode
                    order = e.order
                    phaseOffset = e.phaseOffset
                    elementMode = e.elementMode
                    elementFilter = e.elementFilter
                    timing = e.timing
                    parameters = e.parameters
                    speedMasterUuid = if (clock.free && e.seconds) null else e.speedMasterUuid?.let { UUID.fromString(it) }
                    rateSpeedMasterUuid = if (clock.free && e.seconds) null else e.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                    ownBpm = clock.ownBpm
                    amount = e.amount
                    direction = e.direction
                    runs = e.runs
                    this.spread = e.spread
                    this.wings = e.wings
                    this.segments = e.segments
                    this.parts = e.parts
                    this.spreadUnit = uk.me.cormack.lighting7.fx.SpreadUnit.canonical(e.spreadUnit)
                    sortOrder = e.sortOrder
                    this.uuid = UUID.fromString(e.uuid)
                }
            }
            importScenery(l.scenery, elementMap, "Look ${l.uuid}") { element, s ->
                DaoLookSceneryRow.new {
                    look = dao
                    this.element = element
                    stateJson = storedParamsText(s.state)
                    sortOrder = s.sortOrder
                    this.uuid = UUID.fromString(s.uuid)
                }
            }
            uuid to dao
        }

    /**
     * v20: one owner's scenery list, each change's element resolved through [elementMap]. A change
     * naming an element the archive lacks is dropped with a warning — the column is a real FK, and
     * the element's delete would have swept it on the writing desk — and its state is stored as the
     * archive holds it, as every record here is; every reader tolerates one it cannot read.
     */
    private fun importScenery(
        scenery: List<SceneryChangeJson>,
        elementMap: Map<UUID, DaoStageElement>,
        owner: String,
        create: (DaoStageElement, SceneryChangeJson) -> Unit,
    ) {
        // An owner says one thing about each element (a unique index), and an archive that says two —
        // a hand edit, a merge that kept both sides — keeps the first rather than failing the import.
        val seen = HashSet<UUID>()
        for (s in scenery.sortedBy { it.sortOrder }) {
            val element = runCatching { UUID.fromString(s.elementUuid) }.getOrNull()?.let { elementMap[it] }
            if (element == null) {
                logger.warn("{}: scenery change {} names no stage element in the archive ({}); dropping it", owner, s.uuid, s.elementUuid)
                continue
            }
            if (!seen.add(element.uuid)) {
                logger.warn("{}: scenery change {} names {} a second time; dropping it", owner, s.uuid, s.elementUuid)
                continue
            }
            create(element, s)
        }
    }

    /**
     * v21: one cue's events, each patch resolved through [patchMap]. Dropped with a warning, rather
     * than failing the pull, when the archive lacks the patch (its delete would have swept the event
     * on the writing desk), when the patch's type has no such trigger, or when a tube is named twice
     * (one row per tube, a unique index) — the record is kept as far as it still makes sense.
     */
    private fun importCueEvents(events: List<CueEventJson>, patchMap: Map<UUID, DaoFixturePatch>, cue: DaoCue, owner: String) {
        val seen = HashSet<Pair<Int, String>>()
        for (e in events.sortedBy { it.sortOrder }) {
            val patch = runCatching { UUID.fromString(e.patchUuid) }.getOrNull()?.let { patchMap[it] }
            if (patch == null) {
                logger.warn("{}: event {} names no patch in the archive ({}); dropping it", owner, e.uuid, e.patchUuid)
                continue
            }
            if (FixtureTriggers.specsForTypeKey(patch.fixtureTypeKey).none { it.name == e.trigger }) {
                logger.warn("{}: event {} names '{}', which '{}' has no trigger of; dropping it", owner, e.uuid, e.trigger, patch.key)
                continue
            }
            if (!seen.add(patch.id.value to e.trigger)) {
                logger.warn("{}: event {} fires '{}' on '{}' a second time; dropping it", owner, e.uuid, e.trigger, patch.key)
                continue
            }
            DaoCueEvent.new {
                this.cue = cue
                this.patch = patch
                trigger = e.trigger
                offset = java.time.Duration.ofMillis(e.offsetMs.coerceIn(0, MAX_CUE_EVENT_OFFSET.toMillis()))
                sortOrder = e.sortOrder
                this.uuid = UUID.fromString(e.uuid)
            }
        }
    }

    /**
     * Speed masters. Returns nothing: look-effect and cue-effect rows reference a master by
     * `speedMasterUuid` string, not through a FK, so no uuid → DAO map is needed downstream.
     */
    private fun importSpeedMasters(run: Run, project: DaoProject) {
        run.read("speedMasters") { json ->
            val m = canonicalDecode(SpeedMasterJson.serializer(), json)
            val uuid = UUID.fromString(m.uuid)
            val dao = run.row(
                DaoSpeedMaster, "speedMasters", uuid,
                find = { DaoSpeedMaster.find { (DaoSpeedMasters.uuid eq uuid) and (DaoSpeedMasters.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                masterIndex = m.masterIndex
                name = m.name
                bpm = m.bpm
                source = m.source
                notes = m.notes
                // Written verbatim like every other imported row — the write-boundary rules
                // don't run on import, and the bank degrades a malformed pair (or a ratio on
                // master 1) to "manual" rather than failing the show.
                family = m.family
                followNum = m.followNum
                followDen = m.followDen
                followTargetUuid = m.followTargetUuid?.let { UUID.fromString(it) }
                this.uuid = uuid
            }
            uuid to dao
        }
    }

    private fun importUniverseConfigs(run: Run, project: DaoProject): Map<UUID, DaoUniverseConfig> =
        run.read("universeConfigs") { json ->
            val u = canonicalDecode(UniverseConfigJson.serializer(), json)
            val uuid = UUID.fromString(u.uuid)
            val existing = if (run.upsert) {
                DaoUniverseConfig.find { (DaoUniverseConfigs.uuid eq uuid) and (DaoUniverseConfigs.project eq project.id) }.firstOrNull()
            } else {
                null
            }
            val dao = run.row(DaoUniverseConfig, "universeConfigs", uuid, find = { existing }) {
                this.project = project
                subnet = u.subnet
                universe = u.universe
                controllerType = u.controllerType
                // address is machine-local — left null on import, set later via Phase 2 overrides; an
                // undo leaves a standing row's as it is.
                if (existing == null) address = null
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importRiggings(run: Run, project: DaoProject): Map<UUID, DaoRigging> =
        run.read("riggings") { json ->
            val r = canonicalDecode(RiggingJson.serializer(), json)
            val uuid = UUID.fromString(r.uuid)
            val dao = run.row(
                DaoRigging, "riggings", uuid,
                find = { DaoRigging.find { (DaoRiggings.uuid eq uuid) and (DaoRiggings.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                name = r.name
                kind = r.kind
                positionX = r.positionX
                positionY = r.positionY
                positionZ = r.positionZ
                yawDeg = r.yawDeg
                pitchDeg = r.pitchDeg
                rollDeg = r.rollDeg
                lengthM = r.lengthM
                sortOrder = r.sortOrder
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importStageRegions(run: Run, project: DaoProject) {
        run.read("stageRegions") { json ->
            val s = canonicalDecode(StageRegionJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            run.row(
                DaoStageRegion, "stageRegions", uuid,
                find = { DaoStageRegion.find { (DaoStageRegions.uuid eq uuid) and (DaoStageRegions.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                name = s.name
                centerX = s.centerX
                centerY = s.centerY
                centerZ = s.centerZ
                widthM = s.widthM
                depthM = s.depthM
                heightM = s.heightM
                yawDeg = s.yawDeg
                sortOrder = s.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    /**
     * v18. Stored as the archive has it, params included, without reparsing: an import restores a
     * record, it does not re-author one (the same posture as a head number, `docs/sync-engineering.md`
     * §"Version 16"). Readers of the params tolerate a document they cannot read.
     */
    private fun importStageElements(run: Run, project: DaoProject): Map<UUID, DaoStageElement> =
        run.read("stageElements") { json ->
            val e = canonicalDecode(StageElementJson.serializer(), json)
            val uuid = UUID.fromString(e.uuid)
            val dao = run.row(
                DaoStageElement, "stageElements", uuid,
                find = { DaoStageElement.find { (DaoStageElements.uuid eq uuid) and (DaoStageElements.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                name = e.name
                kind = e.kind
                layer = e.layer
                positionX = e.positionX
                positionY = e.positionY
                positionZ = e.positionZ
                yawDeg = e.yawDeg
                widthM = e.widthM
                depthM = e.depthM
                heightM = e.heightM
                finishColour = e.finishColour
                finishPattern = e.finishPattern
                emissive = e.emissive
                params = e.params?.let { storedParamsText(it) } ?: "{}"
                hidden = e.hidden
                sortOrder = e.sortOrder
                this.uuid = uuid
            }
            uuid to dao
        }

    /** v18. A seat view's element reference is kept verbatim; one the archive lacks dangles. */
    private fun importStageViewpoints(run: Run, project: DaoProject) {
        run.read("stageViewpoints") { json ->
            val v = canonicalDecode(StageViewpointJson.serializer(), json)
            val uuid = UUID.fromString(v.uuid)
            run.row(
                DaoStageViewpoint, "stageViewpoints", uuid,
                find = { DaoStageViewpoint.find { (DaoStageViewpoints.uuid eq uuid) and (DaoStageViewpoints.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                name = v.name
                kind = v.kind
                eyeX = v.eyeX
                eyeY = v.eyeY
                eyeZ = v.eyeZ
                targetX = v.targetX
                targetY = v.targetY
                targetZ = v.targetZ
                fovDeg = v.fovDeg
                seatElementUuid = v.seatElementUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                seatId = v.seatId
                sortOrder = v.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importFixturePatches(
        run: Run,
        project: DaoProject,
        universeMap: Map<UUID, DaoUniverseConfig>,
        riggingMap: Map<UUID, DaoRigging>,
    ): Map<UUID, DaoFixturePatch> = run.read("fixturePatches") { json ->
        val p = canonicalDecode(FixturePatchJson.serializer(), json)
        val uuid = UUID.fromString(p.uuid)
        val universeUuid = UUID.fromString(p.universeConfigUuid)
        val universe = universeMap[universeUuid]
            ?: throw ImportError.invalidArchive("Fixture patch ${p.uuid} references unknown universe $universeUuid")
        val rigging = p.riggingUuid?.let {
            val riggingUuid = UUID.fromString(it)
            riggingMap[riggingUuid]
                ?: throw ImportError.invalidArchive("Fixture patch ${p.uuid} references unknown rigging $riggingUuid")
        }
        val dao = run.row(
            DaoFixturePatch, "fixturePatches", uuid,
            find = { DaoFixturePatch.find { (DaoFixturePatches.uuid eq uuid) and (DaoFixturePatches.project eq project.id) }.firstOrNull() },
        ) {
            this.project = project
            universeConfig = universe
            this.rigging = rigging
            fixtureTypeKey = p.fixtureTypeKey
            key = p.key
            displayName = p.displayName
            headNumber = p.headNumber
            startChannel = p.startChannel
            sortOrder = p.sortOrder
            stageX = p.stageX
            stageY = p.stageY
            stageZ = p.stageZ
            baseYawDeg = p.baseYawDeg
            basePitchDeg = p.basePitchDeg
            baseRollDeg = p.baseRollDeg
            beamAngleDeg = p.beamAngleDeg
            gelCode = p.gelCode
            kindOverride = p.kindOverride
            lengthM = p.lengthM
            stageHidden = p.stageHidden
            infrastructure = p.infrastructure
            lanternType = p.lanternType
            zoomDeg = p.zoomDeg
            lampRotationDeg = p.lampRotationDeg
            shutters = LanternFocus.shuttersToText(p.shutters)
            gateRotationDeg = p.gateRotationDeg
            iris = p.iris
            focusSoftness = p.focusSoftness
            lampWatts = p.lampWatts
            lampKelvin = p.lampKelvin
            storedLocate = p.locate
            fittedMedia = p.media
            colour = importedColour(p.colour, "Fixture patch ${p.uuid}")
            this.uuid = uuid
        }
        val placements = run.children(
            DaoFixturePatchPlacement.find { DaoFixturePatchPlacements.fixturePatch eq dao.id },
            { it.uuid },
            p.extraPlacements.mapTo(HashSet()) { UUID.fromString(it.uuid) },
        )
        p.extraPlacements.forEachIndexed { index, pl ->
            val placementRigging = pl.riggingUuid?.let {
                val riggingUuid = UUID.fromString(it)
                riggingMap[riggingUuid]
                    ?: throw ImportError.invalidArchive("Placement ${pl.uuid} of fixture patch ${p.uuid} references unknown rigging $riggingUuid")
            }
            placements.row(DaoFixturePatchPlacement, UUID.fromString(pl.uuid)) {
                fixturePatch = dao
                this.rigging = placementRigging
                label = pl.label
                stageX = pl.stageX
                stageY = pl.stageY
                stageZ = pl.stageZ
                baseYawDeg = pl.baseYawDeg
                basePitchDeg = pl.basePitchDeg
                baseRollDeg = pl.baseRollDeg
                lengthM = pl.lengthM
                lanternType = pl.lanternType
                zoomDeg = pl.zoomDeg
                lampRotationDeg = pl.lampRotationDeg
                shutters = LanternFocus.shuttersToText(pl.shutters)
                gateRotationDeg = pl.gateRotationDeg
                iris = pl.iris
                focusSoftness = pl.focusSoftness
                fittedMedia = pl.media
                sortOrder = index
                this.uuid = UUID.fromString(pl.uuid)
            }
        }
        uuid to dao
    }

    private fun importFixtureGroups(
        run: Run,
        project: DaoProject,
        patchMap: Map<UUID, DaoFixturePatch>,
    ): Map<UUID, DaoFixtureGroup> = run.read("fixtureGroups") { json ->
        val g = canonicalDecode(FixtureGroupJson.serializer(), json)
        val uuid = UUID.fromString(g.uuid)
        val dao = run.row(
            DaoFixtureGroup, "fixtureGroups", uuid,
            find = { DaoFixtureGroup.find { (DaoFixtureGroups.uuid eq uuid) and (DaoFixtureGroups.project eq project.id) }.firstOrNull() },
        ) {
            this.project = project
            name = g.name
            colour = importedColour(g.colour, "Fixture group ${g.uuid}")
            this.uuid = uuid
        }
        val members = run.children(dao.members, { it.uuid }, g.members.mapTo(HashSet()) { UUID.fromString(it.uuid) })
        g.members.forEach { m ->
            val patchUuid = UUID.fromString(m.fixturePatchUuid)
            val patch = patchMap[patchUuid]
                ?: throw ImportError.invalidArchive("Group member ${m.uuid} references unknown patch $patchUuid")
            members.row(DaoFixtureGroupMember, UUID.fromString(m.uuid)) {
                group = dao
                fixturePatch = patch
                sortOrder = m.sortOrder
                panOffset = m.panOffset
                tiltOffset = m.tiltOffset
                this.uuid = UUID.fromString(m.uuid)
            }
        }
        uuid to dao
    }

    private fun importCueStacks(
        run: Run,
        project: DaoProject,
        elementMap: Map<UUID, DaoStageElement>,
    ): Map<UUID, DaoCueStack> =
        run.read("cueStacks") { json ->
            val s = canonicalDecode(CueStackJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            val dao = run.row(
                DaoCueStack, "cueStacks", uuid,
                find = { DaoCueStack.find { (DaoCueStacks.uuid eq uuid) and (DaoCueStacks.project eq project.id) }.firstOrNull() },
                clear = { deleteStackScenery(it.id) },
            ) {
                name = s.name
                this.project = project
                loop = s.loop
                sortOrder = s.sortOrder
                type = s.type
                label = s.label
                this.uuid = uuid
                moveInDarkSettings = s.moveInDarkSettings()
            }
            importScenery(s.scenery, elementMap, "Cue stack ${s.uuid}") { element, change ->
                DaoCueStackSceneryRow.new {
                    stack = dao
                    this.element = element
                    stateJson = storedParamsText(change.state)
                    sortOrder = change.sortOrder
                    this.uuid = UUID.fromString(change.uuid)
                }
            }
            uuid to dao
        }

    /**
     * Cues: what each one is (cue model plan D2). From v32 a cue file says nothing about where the cue
     * sits — `cueStackEntries/` does ([importCueStackEntries]). An older archive's cue file also gave
     * its one place, which is collected into [legacyPlaces] as the entry it becomes (§3.9's pre-vN
     * reader): in its `cueStackUuid` stack at `ordinal = sortOrder` (ranked per stack, see
     * [importCueStackEntries]), its number explicit only when
     * `cueNumberAuto` is false, its follow, and a MARKER cue as a marker entry labelled with its name,
     * not a cue. A cue with no stack is loose — the "Unsorted" fallback is gone.
     *
     * The entry a legacy cue becomes takes **the cue's uuid**, and a marker keeps its file's: the same
     * archive gives every desk the same entries, so two desks that pull one v31 repo and then each
     * write v32 agree on every entry rather than each adding its own (`state/CueEntryUuidMigration.kt`
     * keys the entries already on a desk the same way).
     */
    private fun importCues(
        run: Run,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
        elementMap: Map<UUID, DaoStageElement>,
        patchMap: Map<UUID, DaoFixturePatch>,
        formatVersion: Int,
        legacyPlaces: MutableList<PendingEntry>,
    ): Map<UUID, DaoCue> = run.read("cues") { json ->
        val c = canonicalDecode(CueJson.serializer(), json)
        val uuid = UUID.fromString(c.uuid)
        val place = if (formatVersion < CUE_ENTRIES_FORMAT_VERSION) {
            canonicalDecode(LegacyCuePlacementJson.serializer(), json)
        } else {
            null
        }
        val stack = place?.cueStackUuid?.let {
            val stackUuid = UUID.fromString(it)
            cueStackMap[stackUuid]
                ?: throw ImportError.invalidArchive("Cue ${c.uuid} references unknown cue stack $stackUuid")
        }
        if (place != null && place.cueType == CueType.MARKER.name) {
            // A marker was never a cue; with no stack it marks nothing.
            if (stack != null) {
                legacyPlaces += PendingEntry(
                    uuid = uuid, stack = stack, cue = null, marker = true, label = c.name.take(255),
                    ordinal = place.sortOrder.toDouble(), cueNumber = null, autoAdvance = false, autoAdvanceDelayMs = null,
                )
            } else {
                logger.warn("Import: marker {} names no stack; left out", c.uuid)
            }
            return@read null
        }
        val dao = run.row(
            DaoCue, "cues", uuid,
            find = { DaoCue.find { (DaoCues.uuid eq uuid) and (DaoCues.project eq project.id) }.firstOrNull() },
            clear = { deleteCueScenery(it.id); deleteCueEvents(it.id) },
        ) {
            name = c.name
            this.project = project
            fadeDuration = c.fadeDurationMs.asDuration()
            fadeCurve = c.fadeCurve
            notes = c.notes
            stomp = c.stomp
            speedMasters = c.speedMasters.map { CueSpeedMasterDto(it.speedMasterUuid, it.bpm) }
            moveInDarkSettings = c.moveInDarkSettings()
            this.uuid = uuid
        }
        if (place != null && stack != null) {
            legacyPlaces += PendingEntry(
                uuid = uuid, stack = stack, cue = dao, marker = false, label = null,
                ordinal = place.sortOrder.toDouble(),
                cueNumber = place.cueNumber?.takeIf { !place.cueNumberAuto && it.isNotEmpty() }?.let { importedCueNumber(it, uuid) },
                autoAdvance = place.autoAdvance, autoAdvanceDelayMs = place.autoAdvanceDelayMs,
            )
        }
        importScenery(c.scenery, elementMap, "Cue ${c.uuid}") { element, s ->
            DaoCueSceneryRow.new {
                cue = dao
                this.element = element
                stateJson = storedParamsText(s.state)
                transition = s.transitionMs.asDuration()
                sortOrder = s.sortOrder
                this.uuid = UUID.fromString(s.uuid)
            }
        }
        importCueEvents(c.events, patchMap, dao, "Cue ${c.uuid}")
        uuid to dao
    }

    /** One stack entry read from the archive, held until its stack's numbers are checked. */
    private class PendingEntry(
        val uuid: UUID,
        val stack: DaoCueStack,
        val cue: DaoCue?,
        val marker: Boolean,
        val label: String?,
        var ordinal: Double,
        var cueNumber: String?,
        val autoAdvance: Boolean,
        val autoAdvanceDelayMs: Long?,
    )

    /**
     * v32: every stack's list, one `cueStackEntries/` record per entry (cue model plan §3.9), beside
     * the entries an older archive's cue files gave ([legacyPlaces]). Answers the entries by uuid —
     * what an anchor names — and the number clashes it resolved.
     *
     *  * **An entry naming a stack or a cue the archive lacks is dropped with a warning**, never the
     *    pull: one file per entry means a merge can pair a peer's new entry with a stack or cue this
     *    side deleted, and a place in a list is nothing without either. The next snapshot writes the
     *    drop as a deletion, so the deleting side wins everywhere.
     *  * **Two explicit numbers alike in one stack** — two desks that each typed `14.5`, merged — keep
     *    the number on the entry with the lower uuid and make the other auto; the clash is answered so
     *    the sync result can name it. Resolved before a row is written, so `uq_entry_number_per_stack`
     *    never sees the pair and a pull never aborts on it.
     *  * **Auto numbers are derived, never read** (P5): each stack is numbered from scratch once its
     *    entries are in, from position and the numbers the archive carries, so every desk reading it
     *    numbers it alike. A number the archive carries is explicit — including one the writer held
     *    as auto but a reader could not derive (`heldAutoNumbers`).
     */
    private fun importCueStackEntries(
        run: Run,
        cueStackMap: Map<UUID, DaoCueStack>,
        cueMap: Map<UUID, DaoCue>,
        legacyPlaces: List<PendingEntry>,
    ): Pair<Map<UUID, DaoCueStackEntry>, List<CueNumberClash>> {
        val pending = LinkedHashMap<UUID, PendingEntry>()
        run.read<Unit>("cueStackEntries") { json ->
            val e = canonicalDecode(CueStackEntryJson.serializer(), json)
            val uuid = UUID.fromString(e.uuid)
            val stack = cueStackMap[UUID.fromString(e.cueStackUuid)]
            if (stack == null) {
                logger.warn("Import: entry {} names cue stack {}, which the archive lacks; left out", e.uuid, e.cueStackUuid)
                return@read null
            }
            val marker = e.kind == CueStackEntryKind.MARKER.name
            val cue = if (marker) null else e.cueUuid?.let { cueMap[UUID.fromString(it)] }
            if (!marker && cue == null) {
                logger.warn("Import: entry {} names cue {}, which the archive lacks; left out", e.uuid, e.cueUuid)
                return@read null
            }
            if (!e.ordinal.isFinite()) throw ImportError.invalidArchive("Entry ${e.uuid} has no usable ordinal: ${e.ordinal}")
            pending[uuid] = PendingEntry(
                uuid = uuid, stack = stack, cue = cue, marker = marker,
                label = if (marker) e.label.orEmpty().take(255) else null,
                ordinal = e.ordinal,
                cueNumber = if (marker) null else e.cueNumber?.takeIf { it.isNotEmpty() }?.let { importedCueNumber(it, uuid) },
                autoAdvance = !marker && e.autoAdvance,
                autoAdvanceDelayMs = if (marker) null else e.autoAdvanceDelayMs,
            )
            null
        }
        // An older archive's places, each ordinal its 0-based rank in its stack by (sortOrder, uuid) —
        // the sortOrder itself for every archive a desk with entries wrote (each cue's index in its
        // list), and the same answer on every desk for an older one with gaps or ties, which is what
        // `state/CueEntryUuidMigration.kt` gave the desk's own rows. Unless a record already says it
        // (an archive never holds both).
        legacyPlaces.groupBy { it.stack.id.value }.values.forEach { places ->
            places.sortedWith(compareBy({ it.ordinal }, { it.uuid.toString() }))
                .forEachIndexed { rank, place -> place.ordinal = rank.toDouble() }
        }
        legacyPlaces.forEach { pending.putIfAbsent(it.uuid, it) }

        val clashes = resolveNumberClashes(pending.values)
        // An upsert renumbers only the stacks it touched — the ones an entry joins or leaves — from
        // their order before it, so an auto number elsewhere in the list stays where it was.
        val existing = if (run.upsert) {
            pending.values.associate { p ->
                p.uuid to DaoCueStackEntry.find { DaoCueStackEntries.uuid eq p.uuid }.firstOrNull { it.stack.project.id == p.stack.project.id }
            }
        } else {
            emptyMap()
        }
        val renumber = if (run.upsert) {
            (pending.values.map { it.stack } + existing.values.mapNotNull { it?.stack })
                .distinctBy { it.id.value }
                .associateWith { cueEntryIds(it) }
        } else {
            cueStackMap.values.associateWith { emptyList() }
        }
        if (run.upsert) {
            // Free every number the upsert writes before it writes one, as `renumberAutoEntries` does: two
            // entries may trade explicit numbers, and an auto number a neighbour took when one left gives way.
            existing.values.filterNotNull().forEach { it.cueNumber = null }
            flushPendingEntryWrites()
            pending.values.forEach { p -> p.cueNumber?.let { releaseAutoNumber(p.stack, it, existing[p.uuid]?.id?.value ?: -1) } }
        }
        val entries = pending.values.associate { p ->
            p.uuid to run.row(DaoCueStackEntry, "cueStackEntries", p.uuid, find = { existing[p.uuid] }) {
                stack = p.stack
                cue = p.cue
                kind = if (p.marker) CueStackEntryKind.MARKER.name else CueStackEntryKind.CUE.name
                label = p.label
                ordinal = p.ordinal
                cueNumber = p.cueNumber
                cueNumberAuto = false
                autoAdvance = p.autoAdvance
                autoAdvanceDelay = p.autoAdvanceDelayMs.asDuration()
                uuid = p.uuid
            }
        }
        renumber.forEach { (stack, before) -> renumberAutoEntries(stack, before) }
        clashes.forEach { logger.warn("Import: {}", it.describe()) }
        return entries to clashes
    }

    /**
     * An archive's cue [number] as this desk stores it: its canonical spelling (`2.10` is `2.1`), or
     * null — the entry auto-numbered — for one that is not a number, which an older desk could write.
     */
    private fun importedCueNumber(number: String, entryUuid: UUID): String? =
        canonicalCueNumber(number) ?: null.also {
            logger.warn("Import: entry {}'s cue number '{}' is not a number; it is numbered from its place", entryUuid, number)
        }

    /**
     * Keep each stack's explicit numbers unique (cue model plan §3.9): of the cue entries holding one
     * number, the entry with the lowest uuid keeps it and every other is made auto. Compared exactly,
     * as `uq_entry_number_per_stack` compares them.
     */
    private fun resolveNumberClashes(entries: Collection<PendingEntry>): List<CueNumberClash> {
        val clashes = ArrayList<CueNumberClash>()
        entries.filter { !it.marker && it.cueNumber != null }
            .groupBy { it.stack.id.value to it.cueNumber!! }
            .values
            .filter { it.size > 1 }
            .forEach { same ->
                val ordered = same.sortedBy { it.uuid.toString() }
                val kept = ordered.first()
                ordered.drop(1).forEach { loser ->
                    clashes += CueNumberClash(
                        stack = kept.stack.name,
                        number = kept.cueNumber!!,
                        keptEntryUuid = kept.uuid.toString(),
                        kept = kept.cue?.name.orEmpty(),
                        entryUuid = loser.uuid.toString(),
                        cue = loser.cue?.name.orEmpty(),
                    )
                    loser.cueNumber = null
                }
            }
        return clashes.sortedWith(compareBy({ it.stack }, { it.number }, { it.entryUuid }))
    }

    private fun importCueAttributeAssignments(run: Run, cueMap: Map<UUID, DaoCue>) {
        run.read("cueAttributeAssignments") { json ->
            val a = canonicalDecode(CueAttributeAssignmentJson.serializer(), json)
            val cueUuid = UUID.fromString(a.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Attribute assignment ${a.uuid} references unknown cue $cueUuid")
            val uuid = UUID.fromString(a.uuid)
            run.row(
                DaoCueAttributeAssignment, "cueAttributeAssignments", uuid,
                find = { DaoCueAttributeAssignment.find { DaoCueAttributeAssignments.uuid eq uuid }.firstOrNull { it.cue.project.id == cue.project.id } },
            ) {
                this.cue = cue
                targetType = a.targetType
                targetKey = a.targetKey
                attribute = a.attribute
                value = a.value
                fadeDuration = a.fadeDurationMs.asDuration()
                sortOrder = a.sortOrder
                this.uuid = uuid
            }
            // The retired row flag (v45 and older): its cue moves its heads Early (move-in-dark plan D13).
            if (a.moveInDark && cue.moveInDarkSettings.mode == CueMoveInDarkMode.STACK) {
                cue.moveInDarkSettings = cue.moveInDarkSettings.copy(mode = CueMoveInDarkMode.EARLY)
            }
            uuid to Unit
        }
    }

    private fun importPalettes(
        run: Run,
        project: DaoProject,
        legacy: LegacyNames.Context,
    ): Map<UUID, DaoPalette> =
        run.read("palettes") { json ->
            val t = canonicalDecode(PaletteJson.serializer(), json)
            val uuid = UUID.fromString(t.uuid)
            val dao = run.row(
                DaoPalette, "palettes", uuid,
                find = { DaoPalette.find { (DaoPalettes.uuid eq uuid) and (DaoPalettes.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                name = t.name
                notes = t.notes
                fadeDuration = t.fadeDurationMs.asDuration()
                this.uuid = uuid
            }
            val rows = run.children(dao.rows, { it.uuid }, t.rows.mapTo(HashSet()) { UUID.fromString(it.uuid) })
            val fxRows = run.children(dao.effects, { it.uuid }, listOfNotNull(t.fx?.uuid).mapTo(HashSet()) { UUID.fromString(it) })
            t.rows.forEach { r ->
                rows.row(DaoPaletteRow, UUID.fromString(r.uuid)) {
                    palette = dao
                    targetType = r.targetType
                    targetKey = r.targetKey
                    attribute = r.attribute
                    value = r.value
                    sortOrder = r.sortOrder
                    this.uuid = UUID.fromString(r.uuid)
                }
            }
            // Written verbatim, like an imported Look effect and for the same reason: the write
            // boundary's rules don't run on import, so a snapshot from a *newer* build whose
            // category or blend mode this one does not recognise still lands rather than aborting
            // the pull. `FxSpecCoercion.Lenient` warns if it eventually fails to spawn.
            t.fx?.let { stored ->
                val e = legacyFx("Palette '${t.name}'", stored.storedFx(), legacy)?.let { stored.with(it) } ?: stored
                fxRows.row(DaoPaletteFxEntity, UUID.fromString(e.uuid)) {
                    palette = dao
                    fxType = e.fxType
                    family = e.family
                    attribute = e.attribute
                    // An older archive's Seconds as Own tempo, or Natural, with no master, as step 12 writes it (fx-speed plan D5).
                    val clock = importedClock(e, legacy)
                    cycle = clock.cycle
                    blendMode = e.blendMode
                    order = e.order
                    phaseOffset = e.phaseOffset
                    elementMode = e.elementMode
                    elementFilter = e.elementFilter
                    timing = e.timing
                    parameters = e.parameters
                    speedMasterUuid = if (clock.free && e.seconds) null else e.speedMasterUuid?.let { m -> UUID.fromString(m) }
                    rateSpeedMasterUuid = if (clock.free && e.seconds) null else e.rateSpeedMasterUuid?.let { m -> UUID.fromString(m) }
                    ownBpm = clock.ownBpm
                    amount = e.amount
                    direction = e.direction
                    runs = e.runs
                    this.spread = e.spread
                    this.wings = e.wings
                    this.segments = e.segments
                    this.parts = e.parts
                    this.spreadUnit = uk.me.cormack.lighting7.fx.SpreadUnit.canonical(e.spreadUnit)
                    this.uuid = UUID.fromString(e.uuid)
                }
            }
            uuid to dao
        }

    private fun importCueLayers(
        run: Run,
        cueMap: Map<UUID, DaoCue>,
        lookMap: Map<UUID, DaoLook>,
        paletteMap: Map<UUID, DaoPalette>,
    ) {
        run.read("cueLayers") { json ->
            val l = canonicalDecode(CueLayerJson.serializer(), json)
            val cueUuid = UUID.fromString(l.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Cue layer ${l.uuid} references unknown cue $cueUuid")
            // Exactly one referent, checked explicitly. `UUID.fromString` takes a Java platform
            // type, so a null slips straight through it and fails later as an NPE with no archive
            // context — the reason the two nullable fields are inspected here rather than a
            // `fromString` running on whichever one happens to be set.
            //
            // The *verdict* is [layerSourceShape], shared with the read and REST-write paths so
            // there is one rule; the *severity* deliberately isn't. Everywhere else a malformed
            // layer warns and is dropped, because a desk mid-show has nowhere to report it. Archive
            // JSON is untrusted input arriving through a call that can fail, so here it fails —
            // silently losing a layer of someone's show on a sync pull is the worse outcome.
            val lookUuidRaw = l.lookUuid
            val paletteUuidRaw = l.paletteUuid
            layerSourceShape(lookUuidRaw, paletteUuidRaw).problem?.let { problem ->
                throw ImportError.invalidArchive(
                    "Cue layer ${l.uuid} names $problem; exactly one of lookUuid / paletteUuid is required",
                )
            }
            var look: DaoLook? = null
            var template: DaoPalette? = null
            if (lookUuidRaw != null) {
                val lookUuid = UUID.fromString(lookUuidRaw)
                look = lookMap[lookUuid]
                    ?: throw ImportError.invalidArchive("Cue layer ${l.uuid} references unknown look $lookUuid")
            } else {
                val paletteUuid = UUID.fromString(paletteUuidRaw)
                template = paletteMap[paletteUuid]
                    ?: throw ImportError.invalidArchive(
                        "Cue layer ${l.uuid} references unknown palette $paletteUuid",
                    )
            }
            // A Look lands whole (absolute-looks plan D1): a Look layer an older desk wrote with
            // targets comes in naming none.
            if (look != null && l.targets.isNotEmpty()) {
                logger.warn(
                    "Import: cue '{}' ({}): layer {} of Look '{}' named targets; dropping them",
                    cue.name, cue.uuid, l.uuid, look.name,
                )
            }
            val uuid = UUID.fromString(l.uuid)
            run.row(
                DaoCueLayer, "cueLayers", uuid,
                find = { DaoCueLayer.find { DaoCueLayers.uuid eq uuid }.firstOrNull { it.cue.project.id == cue.project.id } },
            ) {
                this.cue = cue
                this.look = look
                this.palette = template
                sortOrder = l.sortOrder
                enabled = l.enabled
                targets = if (look != null) emptyList() else l.targets
                attributeMask = l.attributeMask
                blendMode = l.blendMode
                amount = l.amount
                stomp = l.stomp
                speedMasterUuid = l.speedMasterUuid?.let { UUID.fromString(it) }
                rateSpeedMasterUuid = l.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                delay = l.delayMs.asDuration()
                interval = l.intervalMs.asDuration()
                randomWindow = l.randomWindowMs.asDuration()
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importCueAdHocFx(run: Run, cueMap: Map<UUID, DaoCue>, legacy: LegacyNames.Context) {
        run.read("cueAdHocFx") { json ->
            val stored = canonicalDecode(CueAdHocFxJson.serializer(), json)
            val cueUuid = UUID.fromString(stored.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Ad-hoc FX ${stored.uuid} references unknown cue $cueUuid")
            val e = legacyFx("Cue '${cue.name}'", stored.storedFx(), legacy)?.let { stored.with(it) } ?: stored
            val uuid = UUID.fromString(e.uuid)
            run.row(
                DaoCueAdHocFxEntity, "cueAdHocFx", uuid,
                find = { DaoCueAdHocFxEntity.find { DaoCueAdHocFx.uuid eq uuid }.firstOrNull { it.cue.project.id == cue.project.id } },
            ) {
                this.cue = cue
                targetType = e.targetType
                targetKey = e.targetKey
                fxType = e.fxType
                family = e.family
                attribute = e.attribute
                // An older archive's Seconds as Own tempo, or Natural, with no master, as step 12 writes it (fx-speed plan D5).
                val clock = importedClock(e, legacy)
                cycle = clock.cycle
                blendMode = e.blendMode
                order = e.order
                phaseOffset = e.phaseOffset
                elementMode = e.elementMode
                elementFilter = e.elementFilter
                timing = e.timing
                parameters = e.parameters
                delay = e.delayMs.asDuration()
                interval = e.intervalMs.asDuration()
                randomWindow = e.randomWindowMs.asDuration()
                sortOrder = e.sortOrder
                speedMasterUuid = if (clock.free && e.seconds) null else e.speedMasterUuid?.let { UUID.fromString(it) }
                rateSpeedMasterUuid = if (clock.free && e.seconds) null else e.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                ownBpm = clock.ownBpm
                amount = e.amount
                direction = e.direction
                runs = e.runs
                this.spread = e.spread
                this.wings = e.wings
                this.segments = e.segments
                this.parts = e.parts
                this.spreadUnit = uk.me.cormack.lighting7.fx.SpreadUnit.canonical(e.spreadUnit)
                this.uuid = uuid
                runUuid = e.runUuid?.let { UUID.fromString(it) }
            }
            uuid to Unit
        }
    }

    private fun importCueTriggers(
        run: Run,
        cueMap: Map<UUID, DaoCue>,
        scriptMap: Map<UUID, DaoScript>,
    ) {
        run.read("cueTriggers") { json ->
            val t = canonicalDecode(CueTriggerJson.serializer(), json)
            val cueUuid = UUID.fromString(t.cueUuid)
            val scriptUuid = UUID.fromString(t.scriptUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Trigger ${t.uuid} references unknown cue $cueUuid")
            val script = scriptMap[scriptUuid]
                ?: throw ImportError.invalidArchive("Trigger ${t.uuid} references unknown script $scriptUuid")
            val uuid = UUID.fromString(t.uuid)
            run.row(
                DaoCueTrigger, "cueTriggers", uuid,
                find = { DaoCueTrigger.find { DaoCueTriggers.uuid eq uuid }.firstOrNull { it.cue.project.id == cue.project.id } },
            ) {
                this.cue = cue
                triggerType = t.triggerType
                this.script = script
                delay = t.delayMs.asDuration()
                interval = t.intervalMs.asDuration()
                randomWindow = t.randomWindowMs.asDuration()
                sortOrder = t.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    /**
     * Legacy back-compat: older archives stored show order and separators in a `showEntries/`
     * directory. The collapsed model folds those into the cue-stacks collection, so we apply each
     * STACK entry's order onto its referenced stack's `sortOrder`, and materialise each MARKER
     * entry as a SEPARATOR stack. New archives have no `showEntries/` dir, so this is a no-op.
     */
    private fun importLegacyShowOrder(
        dir: Path,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
    ) {
        readDir(dir.resolve("showEntries")) { json ->
            val e = canonicalDecode(ShowEntryJson.serializer(), json)
            val uuid = UUID.fromString(e.uuid)
            if (e.entryType == "MARKER") {
                DaoCueStack.new {
                    this.project = project
                    name = e.label ?: "Separator"
                    label = e.label
                    loop = false
                    type = CueStackType.SEPARATOR.name
                    sortOrder = e.sortOrder
                    this.uuid = uuid
                }
            } else {
                val stack = e.cueStackUuid?.let { cueStackMap[UUID.fromString(it)] }
                stack?.sortOrder = e.sortOrder
            }
            uuid to Unit
        }
    }

    private fun importCueSlots(
        run: Run,
        project: DaoProject,
        cueMap: Map<UUID, DaoCue>,
        lookMap: Map<UUID, DaoLook>,
    ) {
        run.read("cueSlots") { json ->
            val s = canonicalDecode(CueSlotJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            val named = listOfNotNull(s.cueUuid, s.lookUuid)
            // Two arms aborts: archive JSON is untrusted input with a diagnostic channel of its own,
            // and a slot claiming to be two things is malformed, not a state.
            //
            // **None** is the one lenient case, and it exists for exactly one archive: a v10 repo
            // written before the cue-*stack* arm was removed, whose stack slots decode to nothing
            // (`ignoreUnknownKeys` drops the field). Dropping that slot with a warning rather than
            // failing the pull is the busk pad's posture — a slot, like a pad, is an enrichment of
            // the record it names, so a repo that has lost one is still a whole project.
            if (named.size > 1) {
                throw ImportError.invalidArchive("Cue slot ${s.uuid} must name exactly one of cueUuid or lookUuid")
            }
            if (named.isEmpty()) {
                logger.warn(
                    "Cue slot {} names neither a cue nor a Look (a pre-v10 stack slot?); dropping it",
                    s.uuid,
                )
                return@read uuid to Unit
            }
            val cue = s.cueUuid?.let {
                val cueUuid = UUID.fromString(it)
                cueMap[cueUuid]
                    ?: throw ImportError.invalidArchive("Cue slot ${s.uuid} references unknown cue $cueUuid")
            }
            val look = s.lookUuid?.let {
                val lookUuid = UUID.fromString(it)
                lookMap[lookUuid]
                    ?: throw ImportError.invalidArchive("Cue slot ${s.uuid} references unknown look $lookUuid")
            }
            run.row(
                DaoCueSlot, "cueSlots", uuid,
                find = { DaoCueSlot.find { (DaoCueSlots.uuid eq uuid) and (DaoCueSlots.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                page = s.page
                slotIndex = s.slotIndex
                this.cue = cue
                this.look = look
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    /**
     * `buskPages/` (v10): one document per page, columns, banks and pads nested.
     *
     * A pad whose record the archive does not carry — or which names none or two — is **dropped
     * with a warning** rather than aborting the pull, the template-group posture: a pad is an
     * enrichment of its record (a place on a page), not content, so a page that has lost a pad is
     * still a whole page. Stored sort orders are kept as written; a gap left by a dropped pad is
     * harmless (readers sort, they do not index) and the next layout write renumbers densely.
     * Structural fields are validated only as far as the enum goes: a width or flow the desk does
     * not know is not a reason to lose the page, and the layout route refuses it on the next write.
     */
    private fun importBuskPages(
        run: Run,
        project: DaoProject,
        paletteMap: Map<UUID, DaoPalette>,
        lookMap: Map<UUID, DaoLook>,
        cueMap: Map<UUID, DaoCue>,
    ) {
        run.read("buskPages") { json ->
            val p = canonicalDecode(BuskPageJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            val page = run.row(
                DaoBuskPage, "buskPages", uuid,
                find = { DaoBuskPage.find { (DaoBuskPages.uuid eq uuid) and (DaoBuskPages.project eq project.id) }.firstOrNull() },
                clear = ::deleteBuskPageContents,
            ) {
                this.project = project
                name = p.name
                sortOrder = p.sortOrder
                this.uuid = uuid
            }
            p.columns.forEach { c ->
                val column = DaoBuskColumn.new {
                    this.page = page
                    row = c.row
                    sortOrder = c.sortOrder
                    width = c.width
                    this.uuid = UUID.fromString(c.uuid)
                }
                c.banks.forEach { b ->
                    val bank = DaoBuskBank.new {
                        this.column = column
                        sortOrder = b.sortOrder
                        name = b.name
                        solo = b.solo
                        flow = BuskFlow.entries.firstOrNull { it.name == b.flow }?.name ?: BuskFlow.WRAP.name
                        this.uuid = UUID.fromString(b.uuid)
                    }
                    b.pads.forEach { d ->
                        if (buskPadKind(d.paletteUuid, d.lookUuid, d.cueUuid) == null) {
                            logger.warn("Busk pad {} on page {} names no record or more than one; dropping the pad", d.uuid, p.name)
                            return@forEach
                        }
                        val template = d.paletteUuid?.let { paletteMap[UUID.fromString(it)] }
                        val look = d.lookUuid?.let { lookMap[UUID.fromString(it)] }
                        val cue = d.cueUuid?.let { cueMap[UUID.fromString(it)] }
                        if (template == null && look == null && cue == null) {
                            logger.warn(
                                "Busk pad {} on page {} names record {} which the archive does not carry; dropping the pad",
                                d.uuid, p.name, d.paletteUuid ?: d.lookUuid ?: d.cueUuid,
                            )
                            return@forEach
                        }
                        DaoBuskPad.new {
                            this.bank = bank
                            sortOrder = d.sortOrder
                            this.palette = template
                            this.look = look
                            this.cue = cue
                            this.uuid = UUID.fromString(d.uuid)
                        }
                    }
                }
            }
            uuid to Unit
        }
    }

    /**
     * `buskRig/` (v12): one document per row, tiles nested. A tile whose group or patch the archive
     * does not carry — or which names none or both — is **dropped with a warning** rather than
     * aborting the pull, the busk pad's posture: a tile is an enrichment of its record, not content.
     * A row left with no tiles is dropped too, because the write boundary refuses an empty row and
     * one must never be read back. Stored sort orders are kept as written; the element key is
     * carried verbatim (opaque; validated against the live fixture on the next write) and the cell
     * mode only as far as the enum goes. A v11 archive has no folder and imports as an empty rig.
     */
    private fun importBuskRig(
        run: Run,
        project: DaoProject,
        groupMap: Map<UUID, DaoFixtureGroup>,
        patchMap: Map<UUID, DaoFixturePatch>,
    ) {
        run.read("buskRig") { json ->
            val r = canonicalDecode(BuskRigRowJson.serializer(), json)
            val uuid = UUID.fromString(r.uuid)
            val tiles = r.tiles.mapNotNull { t ->
                if (buskRigTileKind(t.groupUuid, t.patchUuid) == null) {
                    logger.warn("Busk rig tile {} on row {} names no record or both; dropping the tile", t.uuid, r.name)
                    return@mapNotNull null
                }
                val group = t.groupUuid?.let { groupMap[UUID.fromString(it)] }
                val patch = t.patchUuid?.let { patchMap[UUID.fromString(it)] }
                if (group == null && patch == null) {
                    logger.warn(
                        "Busk rig tile {} on row {} names record {} which the archive does not carry; dropping the tile",
                        t.uuid, r.name, t.groupUuid ?: t.patchUuid,
                    )
                    return@mapNotNull null
                }
                Triple(t, group, patch)
            }
            val existing = if (run.upsert) {
                DaoBuskRigRow.find { (DaoBuskRigRows.uuid eq uuid) and (DaoBuskRigRows.project eq project.id) }.firstOrNull()
            } else {
                null
            }
            if (tiles.isEmpty()) {
                logger.warn("Busk rig row {} ('{}') has no tiles left; dropping the row", r.uuid, r.name)
                existing?.let { deleteBuskRigRow(it) }
                return@read uuid to Unit
            }
            val row = run.row(DaoBuskRigRow, "buskRig", uuid, find = { existing }, clear = { deleteBuskRigTiles(it) }) {
                this.project = project
                name = r.name
                sortOrder = r.sortOrder
                flow = BuskFlow.entries.firstOrNull { it.name == r.flow }?.name ?: BuskFlow.SCROLL.name
                width = if (r.width in BUSK_WIDTHS) r.width else 12
                this.uuid = uuid
            }
            tiles.forEach { (t, group, patch) ->
                DaoBuskRigTile.new {
                    this.row = row
                    sortOrder = t.sortOrder
                    this.group = group
                    this.patch = patch
                    elementKey = t.elementKey
                    cellMode = BuskRigCellMode.entries.firstOrNull { it.name == t.cellMode }?.name ?: BuskRigCellMode.PIPS.name
                    cellSplit = t.cellSplit
                    label = t.label
                    this.uuid = UUID.fromString(t.uuid)
                }
            }
            uuid to Unit
        }
    }

    private fun importParkedChannels(run: Run, project: DaoProject) {
        run.read("parkedChannels") { json ->
            val p = canonicalDecode(ParkedChannelJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            run.row(
                DaoParkedChannel, "parkedChannels", uuid,
                find = { DaoParkedChannel.find { (DaoParkedChannels.uuid eq uuid) and (DaoParkedChannels.project eq project.id) }.firstOrNull() },
            ) {
                this.project = project
                universe = p.universe
                channel = p.channel
                value = p.value
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    /**
     * v27: a show's releases of its venue's parks, keyed by channel. One with no venue park on its
     * channel at the pinned version is kept — it is dormant, and an Update to a version that parks the
     * channel honours it — and one an archive repeats for a channel (a merge that kept both sides) is
     * kept once. A project on no venue has nothing to release: dropped with a warn, as a hide is.
     * After the parks: a channel the show both parks and releases (a merge again) keeps its park.
     */
    private fun importShowParkReleases(run: Run, project: DaoProject) {
        val seen = HashSet<Pair<Int, Int>>()
        val parked = project.parkedChannels.map { it.universe to it.channel }.toSet()
        run.read("showParkReleases") { json ->
            val r = canonicalDecode(ShowParkReleaseJson.serializer(), json)
            val uuid = runCatching { UUID.fromString(r.uuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("A show park release's uuid is not a uuid: ${r.uuid}")
            val channel = r.universe to r.channel
            when {
                project.kind != ProjectKind.SHOW || project.venueUuid == null ->
                    logger.warn("Import: park release {} in a project on no venue; dropping it", r.uuid)
                r.channel !in 1..512 || r.universe < 0 ->
                    throw ImportError.invalidArchive("Show park release ${r.uuid} names no channel: ${r.universe}/${r.channel}")
                channel in parked ->
                    logger.warn("Import: park release {} on a channel the show parks; keeping the park", r.uuid)
                !seen.add(channel) ->
                    logger.warn("Import: park release {} names {}/{} a second time; dropping it", r.uuid, r.universe, r.channel)
                else -> run.row(
                    DaoShowParkRelease, "showParkReleases", uuid,
                    find = {
                        DaoShowParkRelease.find { (DaoShowParkReleases.uuid eq uuid) and (DaoShowParkReleases.project eq project.id) }.firstOrNull()
                    },
                ) {
                    this.project = project
                    universe = r.universe
                    this.channel = r.channel
                    this.uuid = uuid
                }
            }
            uuid to Unit
        }
    }

    private fun importControlSurfaceBindings(run: Run, project: DaoProject) {
        run.read("controlSurfaceBindings") { json ->
            val b = canonicalDecode(ControlSurfaceBindingJson.serializer(), json)
            val uuid = UUID.fromString(b.uuid)
            run.row(
                DaoControlSurfaceBinding, "controlSurfaceBindings", uuid,
                find = {
                    DaoControlSurfaceBinding.find {
                        (DaoControlSurfaceBindings.uuid eq uuid) and (DaoControlSurfaceBindings.project eq project.id)
                    }.firstOrNull()
                },
            ) {
                this.project = project
                deviceTypeKey = b.deviceTypeKey
                controlId = b.controlId
                bank = b.bank
                targetType = b.targetType
                targetPayload = b.targetPayload
                takeoverPolicy = b.takeoverPolicy
                sortOrder = b.sortOrder
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importPromptBook(run: Run, project: DaoProject): DaoPromptBook? {
        // Parse only inside the read (no DB writes), so a legacy multi-book archive can't
        // insert several rows and trip the one-book-per-project unique index. A project
        // has at most one book; more than one file is an invalid archive.
        val parsed = run.read("promptBooks") { json ->
            val b = canonicalDecode(PromptBookJson.serializer(), json)
            UUID.fromString(b.uuid) to b
        }
        if (parsed.size > 1) {
            throw ImportError.invalidArchive("project has ${parsed.size} prompt books; expected at most one")
        }
        val b = parsed.values.firstOrNull() ?: return null
        val uuid = UUID.fromString(b.uuid)
        return run.row(
            DaoPromptBook, "promptBooks", uuid,
            find = { DaoPromptBook.find { (DaoPromptBooks.uuid eq uuid) and (DaoPromptBooks.project eq project.id) }.firstOrNull() },
        ) {
            this.project = project
            scriptHash = b.scriptHash
            scriptFileName = b.scriptFileName
            pageCount = b.pageCount
            coverPages = b.coverPages
            this.uuid = UUID.fromString(b.uuid)
        }
    }

    /**
     * Anchors mark entries (cue model plan D4). A v32 anchor names its entry; an older one names a
     * cue, which lands on that cue's one entry — the entry took the cue's uuid ([importCues]), and a
     * MARKER cue's uuid is its marker entry's — so both resolve through [entryMap].
     *
     * An anchor whose entry the archive lacks (an entry dropped by [importCueStackEntries], a loose
     * cue's) is dropped with a warning, and of two anchors on one entry — a merge of two desks that
     * each marked it — the lower uuid is kept: the one-per-entry index must never abort a pull.
     */
    private fun importPromptBookAnchors(
        run: Run,
        promptBook: DaoPromptBook?,
        entryMap: Map<UUID, DaoCueStackEntry>,
    ) {
        val parsed = run.read("promptBookAnchors") { json ->
            val a = canonicalDecode(PromptBookAnchorJson.serializer(), json)
            UUID.fromString(a.uuid) to a
        }
        val book = promptBook
        if (book == null) {
            parsed.keys.firstOrNull()?.let { throw ImportError.invalidArchive("Prompt book anchor $it has no prompt book to attach to") }
            return
        }
        val taken = HashSet<Int>()
        parsed.entries.sortedBy { it.key.toString() }.forEach { (uuid, a) ->
            val ref = a.entryUuid ?: a.cueUuid
            if (ref == null) {
                logger.warn("Import: prompt book anchor {} names no entry; left out", a.uuid)
                return@forEach
            }
            val entry = entryMap[UUID.fromString(ref)]
            if (entry == null) {
                logger.warn("Import: prompt book anchor {} names {}, which has no entry here; left out", a.uuid, ref)
                return@forEach
            }
            checkPromptBookRegion(a.region, book.pageCount)?.let {
                throw ImportError.invalidArchive("Prompt book anchor ${a.uuid} has an invalid region: $it")
            }
            if (!taken.add(entry.id.value)) {
                logger.warn("Import: prompt book anchor {} marks an entry another anchor already marks; left out", a.uuid)
                return@forEach
            }
            run.row(
                DaoPromptBookAnchor, "promptBookAnchors", uuid,
                find = { DaoPromptBookAnchor.find { DaoPromptBookAnchors.uuid eq uuid }.firstOrNull { it.promptBook.id == book.id } },
            ) {
                this.promptBook = book
                this.entry = entry
                region = a.region
                label = a.label
                this.uuid = uuid
            }
        }
    }

    private fun importPromptBookAnnotations(run: Run, promptBook: DaoPromptBook?) {
        run.read("promptBookAnnotations") { json ->
            val n = canonicalDecode(PromptBookAnnotationJson.serializer(), json)
            val book = promptBook
                ?: throw ImportError.invalidArchive("Prompt book annotation ${n.uuid} has no prompt book to attach to")
            checkPromptBookRegion(n.region, book.pageCount)?.let {
                throw ImportError.invalidArchive("Prompt book annotation ${n.uuid} has an invalid region: $it")
            }
            val uuid = UUID.fromString(n.uuid)
            val dao = run.row(
                DaoPromptBookAnnotation, "promptBookAnnotations", uuid,
                find = { DaoPromptBookAnnotation.find { DaoPromptBookAnnotations.uuid eq uuid }.firstOrNull { it.promptBook.id == book.id } },
            ) {
                this.promptBook = book
                kind = n.kind
                region = n.region
                text = n.text
                color = n.color
                tone = n.tone
                this.uuid = uuid
            }
            uuid to dao
        }
    }

    /**
     * An archive row's clock (fx-speed plan D5): its Own tempo, or an older archive's `seconds` read as
     * Own tempo 120 at twice its cycle on a beat effect and Natural on a wall-clock one — told apart by
     * name, a built-in or one of the archive's FX definitions on the wall clock ([LegacyNames.Context.wallClock]),
     * as the database step tells them, with no registry to ask.
     */
    private fun importedClock(e: StoredFxFields, legacy: LegacyNames.Context): StoredWriteClock =
        storedWriteClock(timingSourceByName(e.fxType, legacy.wallClock), e.ownBpm, e.seconds, e.cycle)

    /**
     * What [LegacyNames.rewriteFx] reads beside an archive's FX: its palettes, read only if an FX
     * names one; its script FX definitions; and whether an FX naming no `pingPong` predates it
     * ([LegacyNames.absentPingPongIsOn]).
     */
    private fun legacyContext(source: RecordSource, definitions: Collection<DaoFxDefinition>, formatVersion: Int, fxLibrary: Int?): LegacyNames.Context {
        val palettes by lazy {
            Run(source).read("palettes") { json ->
                val t = canonicalDecode(PaletteJson.serializer(), json)
                UUID.fromString(t.uuid) to LegacyNames.Palette(
                    name = t.name,
                    rows = t.rows.sortedBy { it.sortOrder }.map {
                        LegacyNames.PaletteRow(it.attribute, it.value, it.targetType == DEFERRED_TARGET_TYPE)
                    },
                    holdsFx = t.fx != null,
                )
            }
        }
        val byId = definitions.associate { d ->
            normalizeFxType(d.fxId) to
                LegacyNames.Definition(d.outputType.name, d.parameters.filter { it.type == "colourList" }.mapTo(HashSet()) { it.name })
        }
        return LegacyNames.Context(
            palettes = { palettes[it] },
            definitions = { byId[normalizeFxType(it)] },
            absentPingPongIsOn = LegacyNames.absentPingPongIsOn(formatVersion, fxLibrary),
            writtenBeforeSiblings = formatVersion < LegacyNames.SIBLINGS_FORMAT_VERSION,
            wallClock = wallClockFxTypes(definitions.filter { it.timingSource == TimingSource.WALL_CLOCK }.map { it.fxId }),
        )
    }

    /**
     * [fx] on today's FX library — the terminology plan's rewrites, then Wave's and the rest of
     * fx-settings plan D3's, in database steps 4, 7 and 8's order — or null when it holds no old name,
     * logged by [what].
     */
    private fun legacyFx(what: String, fx: LegacyNames.StoredFx, legacy: LegacyNames.Context): LegacyNames.StoredFx? {
        val steps = listOf<(LegacyNames.StoredFx) -> LegacyNames.Rewrite?>(
            { LegacyNames.rewriteFx(it, legacy) },
            { LegacyNames.consolidateFx(it, legacy.definitions) },
            { LegacyNames.absorbFx(it, legacy.writtenBeforeSiblings, legacy.definitions) },
        )
        val rewrite = steps.fold(null as LegacyNames.Rewrite?) { done, step ->
            val next = step(done?.fx ?: fx) ?: return@fold done
            if (done == null) next else LegacyNames.Rewrite(next.fx, done.changes + next.changes, done.notes + next.notes)
        } ?: return null
        if (rewrite.notes.isEmpty()) {
            logger.info("Import: {}: {} — {}", what, fx.fxType, rewrite.changes.joinToString("; "))
        } else {
            logger.warn(
                "Import: {}: {} — {}; output moves: {}",
                what, fx.fxType, rewrite.changes.joinToString("; "), rewrite.notes.joinToString("; "),
            )
        }
        return rewrite.fx
    }

    /**
     * Read every record file under [dir] through [DirSource], applying [block] inside the existing
     * transaction; a null answer from [block] reads the file but keeps nothing. Returns a UUID-keyed map
     * of whatever [block] produces, empty when no folder exists — every section is optional.
     */
    private fun <T> readDir(dir: Path, block: (String) -> Pair<UUID, T>?): Map<UUID, T> =
        Run(DirSource(dir.parent)).read(dir.fileName.toString(), block)

    /**
     * One pass of the importer over [source]: an archive's, which inserts every record, or an undo's
     * ([restore]), which **upserts** them — a record found by uuid is updated in place, its embedded
     * children replaced, and one not found is inserted with the row id the journal kept for it
     * ([rowIds]), so everything naming it by id still does (undo plan §3.5, D9).
     */
    private class Run(val source: RecordSource, val upsert: Boolean = false, private val rowIds: Map<RecordKey, Int> = emptyMap()) {
        fun <T> read(folder: String, block: (String) -> Pair<UUID, T>?): Map<UUID, T> {
            val out = mutableMapOf<UUID, T>()
            for (json in source.records(folder)) {
                val (uuid, value) = block(json) ?: continue
                out[uuid] = value
            }
            return out
        }

        /**
         * An upserted record's embedded rows of one kind, matched by uuid so their row ids hold — a Look's
         * FX is named by its id while it is included. Of [existing], those [keep] does not name are deleted
         * now; [Children.row] updates each other one in place and inserts a new one. In an insert every
         * row is new, and [existing] is never read.
         */
        fun <E : IntEntity> children(existing: Iterable<E>, uuidOf: (E) -> UUID, keep: Set<UUID>): Children<E> {
            if (!upsert) return Children(emptyMap())
            val rows = existing.toList()
            rows.filter { uuidOf(it) !in keep }.forEach { it.delete() }
            return Children(rows.filter { uuidOf(it) in keep }.associateBy(uuidOf))
        }

        class Children<E : IntEntity>(private val held: Map<UUID, E>) {
            fun row(cls: IntEntityClass<E>, uuid: UUID, init: E.() -> Unit): E = held[uuid]?.apply(init) ?: cls.new(init)
        }

        /**
         * The row for record [uuid] of [folder]: in an upsert the one [find] answers, [clear]ed of its
         * embedded children and updated by [init]; else a new one, with its kept id when it has one.
         */
        fun <E : IntEntity> row(
            cls: IntEntityClass<E>,
            folder: String,
            uuid: UUID,
            find: () -> E?,
            clear: (E) -> Unit = {},
            init: E.() -> Unit,
        ): E {
            val existing = if (upsert) find() else null
            if (existing != null) {
                clear(existing)
                return existing.apply(init)
            }
            val id = rowIds[RecordKey(folder, uuid)]
            return if (id != null) cls.new(id, init) else cls.new(init)
        }
    }
}

/** [CueStackJson]'s *Move in dark* fields as a setting, brought inside the write boundary's limits. */
private fun CueStackJson.moveInDarkSettings(): MoveInDarkSettings {
    fun clamp(ms: Long) = java.time.Duration.ofMillis(ms.coerceIn(0, MoveInDarkSettings.MAX.toMillis()))
    val families = MoveInDarkSettings.parseFamilies(moveInDarkFamilies.joinToString(","))
    return MoveInDarkSettings(
        on = moveInDark,
        whenMode = MoveInDarkWhen.entries.firstOrNull { it.name == moveInDarkWhen } ?: MoveInDarkWhen.EARLY,
        delay = clamp(moveInDarkDelayMs),
        time = clamp(moveInDarkTimeMs),
        families = families.ifEmpty { MoveInDarkSettings.MOVABLE },
    )
}

/** [this] cue's *Move in dark* setting (v46), an unknown mode read as *Stack* and timings clamped to 0–30 s. */
private fun CueJson.moveInDarkSettings(): CueMoveInDarkSettings {
    fun clamp(ms: Long?) = ms?.let { java.time.Duration.ofMillis(it.coerceIn(0, MoveInDarkSettings.MAX.toMillis())) }
    return CueMoveInDarkSettings(
        mode = CueMoveInDarkMode.entries.firstOrNull { it.name == moveInDarkMode } ?: CueMoveInDarkMode.STACK,
        delay = clamp(moveInDarkDelayMs),
        time = clamp(moveInDarkTimeMs),
        live = moveInDarkLive.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(CueMoveInDarkSettings.MAX_LIVE),
    )
}
