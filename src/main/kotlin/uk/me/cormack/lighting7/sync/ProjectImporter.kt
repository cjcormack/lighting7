package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.ProjectKind
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
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
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
import uk.me.cormack.lighting7.routes.deleteBuskRig
import uk.me.cormack.lighting7.routes.deleteCueChildren
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson
import uk.me.cormack.lighting7.sync.dto.CueAdHocEffectJson
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CueStackEntryJson
import uk.me.cormack.lighting7.sync.dto.LegacyCuePlacementJson
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateEffect
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.sync.dto.TemplateJson
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.sync.dto.CuePropertyAssignmentJson
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
// v30 added `size`, `direction` and `nShot` to the three stored effect shapes — `LookEffectJson`,
// `TemplateEffectJson` and `CueAdHocEffectJson` (effects plan session 1, D2, D5, D9). SUPPORTED moved
// for v14's reason — a v29 reader would run a 40 % effect at full size, a backward one forward and a
// one-shot forever, and write all three away on its next push; MIN stays at 5 because each field
// defaults to what a row without it meant.
// v31 added `locate` to `FixturePatchJson` (venue-visit plan D6): each unit's stored pan and tilt
// bytes. SUPPORTED moved for v14's reason — a v30 reader would import every patch with no stored
// locate and write them away on its next push; MIN stays at 5 because the field defaults to empty.
// Imported as stored (canonicalised, no type check): the write boundary refuses a unit the type does
// not pan and tilt, and Locate ignores an entry it cannot place.
// v33 added `spread`, `wings`, `blocks` and `groups` to the three stored effect shapes —
// `LookEffectJson`, `TemplateEffectJson` and `CueAdHocEffectJson` (effects plan session 2, D4).
// SUPPORTED moved for v14's reason — a v32 reader would run a 50 % ripple over the whole cycle and two
// wings as one, and write all four away on its next push; MIN stays at 5 because each field defaults to
// what a row without it meant. Imported as stored: a value out of range is clamped when the effect
// spawns (`EffectSpecCoercion.Lenient.spreadShape`), not refused here.
// v34 gave an effect a third target (effects plan session 3, D3): `targetType: "selection"` on a
// `LookEffectJson` or `CueAdHocEffectJson`, its `targetKey` the canonical JSON of the ordered
// `[{key, type}]` fixtures and groups it spreads over. No field moved. SUPPORTED moved for v14's
// reason — a v33 reader would store a target it cannot spawn, and every cue holding one would skip
// that effect; MIN stays at 5 because nothing older uses the value. Imported as stored: a key that
// does not parse is refused where the effect spawns, not here.
// v35 added `speedMasters` to `CueJson` (venue-visit plan S16): the masters a cue sets on GO, each a
// master's uuid and a BPM. SUPPORTED moved for v14's reason — a v34 reader would import every cue
// without them and write them away on its next push; MIN stays at 5 because a missing list sets
// nothing. Imported as stored: a uuid the archive's masters lack is skipped at GO, not here.
//
// v29 added `seconds` to the three stored effect shapes — `LookEffectJson`, `TemplateEffectJson` and
// `CueAdHocEffectJson` — D7's *Seconds* (venue-visit plan S15): the effect follows no master, and a
// beat effect's `beatDivision` is then its cycle in seconds. SUPPORTED moved for v14's reason — a v28
// reader would import a ten-second breathe as ten beats of master 1 and write the flag away on its
// next push; MIN stays at 5 because the field defaults to false.
//
// v28 gave a palette's position row a second unit (venue-visit plan D3, P3): `pos:<pan>,<tilt>`,
// centre-relative coarse DMX, beside the `deg:` travel degrees every earlier row holds. No field
// moved — `TemplateRowJson.value` is a string, imported verbatim — but its meaning did: a v27 reader
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
// **These two constants are the real gate.** `FormatVersionJson.minReader` is *written* but never
// read by the importer — `loadAndValidateArchive` compares against these — so bumping the DTO
// alone leaves the gate wide open.
//
// v4 added `promptScripts/{hash}.pdf` binary blobs to the repo; the writer emitting 4 was what
// made a pre-v4 install refuse a v4 repo (it lacked the wipe-preserve logic and would delete the
// PDFs, reverting them onto peers).
internal const val SUPPORTED_FORMAT_VERSION = 36
internal const val MIN_SUPPORTED_FORMAT_VERSION = 5

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
         * each typed `14A` — each resolved by keeping the number on the entry with the lower uuid and
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
            }

            val clashes = populateProject(sourceDir, project, format.formatVersion)
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
    fun replaceFromWorkingTree(projectId: Int, sourceDir: Path): Result {
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
            project.templates.forEach { template ->
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

            val clashes = populateProject(sourceDir, project, format.formatVersion)

            Result(
                projectId = project.id.value,
                projectUuid = project.uuid.toString(),
                name = project.name,
                numberClashes = clashes,
            )
        }
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
    private fun populateProject(sourceDir: Path, project: DaoProject, formatVersion: Int): List<CueNumberClash> {
        // v24: a show on a venue links into the pinned snapshot's rows, which its archive does not
        // carry. Every map starts from them, and the archive's own rows are laid over the top — so a
        // link resolves to the show's own row where it has one (a Preset seating it copied keeps the
        // venue's uuid) and to the venue's otherwise. Hides are imported here too, before the strip
        // passes below judge rows through the rig scope.
        val venue = VenueSeed.of(project)
        importShowHides(sourceDir, project)
        importShowOverrides(sourceDir, project)
        importShowColours(sourceDir, project)
        // Elements first (v20): a Look's, a stack's and a cue's scenery name one, and an element
        // names nothing that needs importing before it (a platform's region is a uuid in its params).
        val elementMap = venue.elements + importStageElements(sourceDir, project)
        val scriptMap = importScripts(sourceDir, project)
        importFxDefinitions(sourceDir, project)
        val lookMap = importLooks(sourceDir, project, elementMap)
        val templateMap = importTemplates(sourceDir, project)
        importSpeedMasters(sourceDir, project)
        val universeMap = venue.universes + importUniverseConfigs(sourceDir, project)
        val riggingMap = venue.riggings + importRiggings(sourceDir, project)
        importStageRegions(sourceDir, project)
        // The viewpoints after the elements their seats name (v18), imported above.
        importStageViewpoints(sourceDir, project)
        val patchMap = venue.patches + importFixturePatches(sourceDir, project, universeMap, riggingMap)
        val groupMap = venue.groups + importFixtureGroups(sourceDir, project, patchMap)
        // The rig after the groups and patches its tiles name (v12).
        importBuskRig(sourceDir, project, groupMap, patchMap)
        val cueStackMap = importCueStacks(sourceDir, project, elementMap)
        val legacyPlaces = ArrayList<PendingEntry>()
        val cueMap = importCues(sourceDir, project, cueStackMap, elementMap, patchMap, formatVersion, legacyPlaces)
        val (entryMap, numberClashes) = importCueStackEntries(sourceDir, cueStackMap, cueMap, legacyPlaces)
        importCuePropertyAssignments(sourceDir, cueMap)
        importCueLayers(sourceDir, cueMap, lookMap, templateMap)
        importCueAdHocEffects(sourceDir, cueMap)
        importCueTriggers(sourceDir, cueMap, scriptMap)
        importLegacyShowOrder(sourceDir, project, cueStackMap)
        val promptBook = importPromptBook(sourceDir, project)
        importPromptBookAnchors(sourceDir, promptBook, entryMap)
        importPromptBookAnnotations(sourceDir, promptBook)
        importCueSlots(sourceDir, project, cueMap, lookMap)
        // Pages after every record a pad can name.
        importBuskPages(sourceDir, project, templateMap, lookMap, cueMap)
        importParkedChannels(sourceDir, project)
        importShowParkReleases(sourceDir, project)
        importControlSurfaceBindings(sourceDir, project)
        // Last, once the patches and groups exist to judge them by: an archive written before v21 may
        // still hold a row naming a one-shot trigger, and none may come back (stage-view session 9).
        val stripped = stripTriggerRows(project)
        if (stripped > 0) logger.warn("Import: stripped {} row(s) naming a one-shot trigger", stripped)
        // Likewise a fixture command (fixture optics plan session 7): a reset stored as a setting option
        // before the commands existed.
        val strippedCommands = stripCommandRows(project)
        if (strippedCommands > 0) logger.warn("Import: stripped {} row(s) holding a fixture command", strippedCommands)
        return numberClashes
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
    private fun importShowColours(dir: Path, project: DaoProject) {
        val seen = HashSet<UUID>()
        readDir(dir.resolve("showColours")) { json ->
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
                else -> DaoShowColour.new {
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
     * v24: a show's hides, stored as the archive has them. A hide naming no record of the pinned
     * version is kept — it hides nothing, and an Update to a version that has the record again
     * (session 6) honours it — and one an archive repeats (a merge that kept both sides) is kept once.
     */
    private fun importShowHides(dir: Path, project: DaoProject) {
        val seen = HashSet<Pair<HideKind, UUID>>()
        readDir(dir.resolve("showHides")) { json ->
            val h = canonicalDecode(ShowHideJson.serializer(), json)
            val uuid = runCatching { UUID.fromString(h.uuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("A show hide's uuid is not a uuid: ${h.uuid}")
            val recordUuid = runCatching { UUID.fromString(h.recordUuid) }.getOrNull()
                ?: throw ImportError.invalidArchive("Show hide ${h.uuid} names no record uuid: ${h.recordUuid}")
            if (project.kind != ProjectKind.SHOW || project.venueUuid == null) {
                logger.warn("Import: hide {} in a project on no venue; dropping it", h.uuid)
            } else if (seen.add(h.recordKind to recordUuid)) {
                DaoShowHide.new {
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
    private fun importShowOverrides(dir: Path, project: DaoProject) {
        val seen = HashSet<Pair<UUID, PatchFacet>>()
        readDir(dir.resolve("showOverrides")) { json ->
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
                DaoShowOverride.new {
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

    private fun importScripts(dir: Path, project: DaoProject): Map<UUID, DaoScript> {
        val sub = dir.resolve("scripts")
        if (!sub.exists()) return emptyMap()
        val map = mutableMapOf<UUID, DaoScript>()
        Files.list(sub).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".meta.json") }.forEach { metaFile ->
                val meta = canonicalDecode(ScriptMetaJson.serializer(), Files.readString(metaFile))
                val bodyFile = sub.resolve("${meta.uuid}.kts")
                val body = if (bodyFile.exists()) Files.readString(bodyFile) else ""
                val uuid = UUID.fromString(meta.uuid)
                val dao = DaoScript.new {
                    name = meta.name
                    script = body
                    this.project = project
                    scriptType = meta.scriptType
                    this.uuid = uuid
                }
                map[uuid] = dao
            }
        }
        return map
    }

    private fun importFxDefinitions(dir: Path, project: DaoProject): Map<UUID, DaoFxDefinition> =
        readDir(dir.resolve("fxDefinitions")) { json ->
            val d = canonicalDecode(FxDefinitionJson.serializer(), json)
            val uuid = UUID.fromString(d.uuid)
            val dao = DaoFxDefinition.new {
                effectId = d.effectId
                name = d.name
                category = d.category
                outputType = d.outputType
                effectMode = d.effectMode
                parameters = d.parameters?.let {
                    canonicalJson.decodeFromJsonElement(ListSerializer(ParameterInfo.serializer()), it)
                } ?: emptyList()
                compatibleProperties = d.compatibleProperties
                script = d.script
                this.project = project
                defaultStepTiming = d.defaultStepTiming
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
    private fun importLooks(dir: Path, project: DaoProject, elementMap: Map<UUID, DaoStageElement>): Map<UUID, DaoLook> =
        readDir(dir.resolve("looks")) { json ->
            val l = canonicalDecode(LookJson.serializer(), json)
            val uuid = UUID.fromString(l.uuid)
            val dao = DaoLook.new {
                this.project = project
                name = l.name
                notes = l.notes
                this.uuid = uuid
            }
            l.rows.forEach { r ->
                DaoLookRow.new {
                    look = dao
                    targetType = r.targetType
                    targetKey = r.targetKey
                    propertyName = r.propertyName
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
            // warn `EffectSpecCoercion.Lenient` logs when the effect eventually spawns.
            l.effects.forEach { e ->
                DaoLookEffect.new {
                    look = dao
                    targetType = e.targetType
                    targetKey = e.targetKey
                    effectType = e.effectType
                    category = e.category
                    propertyName = e.propertyName
                    beatDivision = e.beatDivision
                    blendMode = e.blendMode
                    distribution = e.distribution
                    phaseOffset = e.phaseOffset
                    elementMode = e.elementMode
                    elementFilter = e.elementFilter
                    stepTiming = e.stepTiming
                    parameters = e.parameters
                    speedMasterUuid = e.speedMasterUuid?.let { UUID.fromString(it) }
                    rateSpeedMasterUuid = e.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                    seconds = e.seconds
                    size = e.size
                    direction = e.direction
                    nShot = e.nShot
                    this.spread = e.spread
                    this.wings = e.wings
                    this.blocks = e.blocks
                    this.groups = e.groups
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
    private fun importSpeedMasters(dir: Path, project: DaoProject) {
        readDir(dir.resolve("speedMasters")) { json ->
            val m = canonicalDecode(SpeedMasterJson.serializer(), json)
            val uuid = UUID.fromString(m.uuid)
            val dao = DaoSpeedMaster.new {
                this.project = project
                masterIndex = m.masterIndex
                name = m.name
                bpm = m.bpm
                source = m.source
                notes = m.notes
                // Written verbatim like every other imported row — the write-boundary rules
                // don't run on import, and the bank degrades a malformed pair (or a ratio on
                // master 1) to "manual" rather than failing the show.
                usageCategory = m.usage
                followNum = m.followNum
                followDen = m.followDen
                followTargetUuid = m.followTargetUuid?.let { UUID.fromString(it) }
                this.uuid = uuid
            }
            uuid to dao
        }
    }

    private fun importUniverseConfigs(dir: Path, project: DaoProject): Map<UUID, DaoUniverseConfig> =
        readDir(dir.resolve("universeConfigs")) { json ->
            val u = canonicalDecode(UniverseConfigJson.serializer(), json)
            val uuid = UUID.fromString(u.uuid)
            val dao = DaoUniverseConfig.new {
                this.project = project
                subnet = u.subnet
                universe = u.universe
                controllerType = u.controllerType
                // address is machine-local — left null on import, set later via Phase 2 overrides.
                address = null
                this.uuid = uuid
            }
            uuid to dao
        }

    private fun importRiggings(dir: Path, project: DaoProject): Map<UUID, DaoRigging> =
        readDir(dir.resolve("riggings")) { json ->
            val r = canonicalDecode(RiggingJson.serializer(), json)
            val uuid = UUID.fromString(r.uuid)
            val dao = DaoRigging.new {
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

    private fun importStageRegions(dir: Path, project: DaoProject) {
        readDir(dir.resolve("stageRegions")) { json ->
            val s = canonicalDecode(StageRegionJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            DaoStageRegion.new {
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
    private fun importStageElements(dir: Path, project: DaoProject): Map<UUID, DaoStageElement> =
        readDir(dir.resolve("stageElements")) { json ->
            val e = canonicalDecode(StageElementJson.serializer(), json)
            val uuid = UUID.fromString(e.uuid)
            val dao = DaoStageElement.new {
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
    private fun importStageViewpoints(dir: Path, project: DaoProject) {
        readDir(dir.resolve("stageViewpoints")) { json ->
            val v = canonicalDecode(StageViewpointJson.serializer(), json)
            val uuid = UUID.fromString(v.uuid)
            DaoStageViewpoint.new {
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
        dir: Path,
        project: DaoProject,
        universeMap: Map<UUID, DaoUniverseConfig>,
        riggingMap: Map<UUID, DaoRigging>,
    ): Map<UUID, DaoFixturePatch> = readDir(dir.resolve("fixturePatches")) { json ->
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
        val dao = DaoFixturePatch.new {
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
        p.extraPlacements.forEachIndexed { index, pl ->
            val placementRigging = pl.riggingUuid?.let {
                val riggingUuid = UUID.fromString(it)
                riggingMap[riggingUuid]
                    ?: throw ImportError.invalidArchive("Placement ${pl.uuid} of fixture patch ${p.uuid} references unknown rigging $riggingUuid")
            }
            DaoFixturePatchPlacement.new {
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
        dir: Path,
        project: DaoProject,
        patchMap: Map<UUID, DaoFixturePatch>,
    ): Map<UUID, DaoFixtureGroup> = readDir(dir.resolve("fixtureGroups")) { json ->
        val g = canonicalDecode(FixtureGroupJson.serializer(), json)
        val uuid = UUID.fromString(g.uuid)
        val dao = DaoFixtureGroup.new {
            this.project = project
            name = g.name
            colour = importedColour(g.colour, "Fixture group ${g.uuid}")
            this.uuid = uuid
        }
        g.members.forEach { m ->
            val patchUuid = UUID.fromString(m.fixturePatchUuid)
            val patch = patchMap[patchUuid]
                ?: throw ImportError.invalidArchive("Group member ${m.uuid} references unknown patch $patchUuid")
            DaoFixtureGroupMember.new {
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
        dir: Path,
        project: DaoProject,
        elementMap: Map<UUID, DaoStageElement>,
    ): Map<UUID, DaoCueStack> =
        readDir(dir.resolve("cueStacks")) { json ->
            val s = canonicalDecode(CueStackJson.serializer(), json)
            val uuid = UUID.fromString(s.uuid)
            val dao = DaoCueStack.new {
                name = s.name
                this.project = project
                loop = s.loop
                sortOrder = s.sortOrder
                type = s.type
                label = s.label
                this.uuid = uuid
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
        dir: Path,
        project: DaoProject,
        cueStackMap: Map<UUID, DaoCueStack>,
        elementMap: Map<UUID, DaoStageElement>,
        patchMap: Map<UUID, DaoFixturePatch>,
        formatVersion: Int,
        legacyPlaces: MutableList<PendingEntry>,
    ): Map<UUID, DaoCue> = readDir(dir.resolve("cues")) { json ->
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
            return@readDir null
        }
        val dao = DaoCue.new {
            name = c.name
            this.project = project
            fadeDuration = c.fadeDurationMs.asDuration()
            fadeCurve = c.fadeCurve
            notes = c.notes
            stomp = c.stomp
            speedMasters = c.speedMasters.map { CueSpeedMasterDto(it.speedMasterUuid, it.bpm) }
            this.uuid = uuid
        }
        if (place != null && stack != null) {
            legacyPlaces += PendingEntry(
                uuid = uuid, stack = stack, cue = dao, marker = false, label = null,
                ordinal = place.sortOrder.toDouble(),
                cueNumber = place.cueNumber?.takeIf { !place.cueNumberAuto && it.isNotEmpty() },
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
     *  * **Two explicit numbers alike in one stack** — two desks that each typed `14A`, merged — keep
     *    the number on the entry with the lower uuid and make the other auto; the clash is answered so
     *    the sync result can name it. Resolved before a row is written, so `uq_entry_number_per_stack`
     *    never sees the pair and a pull never aborts on it.
     *  * **Auto numbers are derived, never read** (P5): each stack is numbered from scratch once its
     *    entries are in, from position and the numbers the archive carries, so every desk reading it
     *    numbers it alike. A number the archive carries is explicit — including one the writer held
     *    as auto but a reader could not derive (`heldAutoNumbers`).
     */
    private fun importCueStackEntries(
        dir: Path,
        cueStackMap: Map<UUID, DaoCueStack>,
        cueMap: Map<UUID, DaoCue>,
        legacyPlaces: List<PendingEntry>,
    ): Pair<Map<UUID, DaoCueStackEntry>, List<CueNumberClash>> {
        val pending = LinkedHashMap<UUID, PendingEntry>()
        readDir<Unit>(dir.resolve("cueStackEntries")) { json ->
            val e = canonicalDecode(CueStackEntryJson.serializer(), json)
            val uuid = UUID.fromString(e.uuid)
            val stack = cueStackMap[UUID.fromString(e.cueStackUuid)]
            if (stack == null) {
                logger.warn("Import: entry {} names cue stack {}, which the archive lacks; left out", e.uuid, e.cueStackUuid)
                return@readDir null
            }
            val marker = e.kind == CueStackEntryKind.MARKER.name
            val cue = if (marker) null else e.cueUuid?.let { cueMap[UUID.fromString(it)] }
            if (!marker && cue == null) {
                logger.warn("Import: entry {} names cue {}, which the archive lacks; left out", e.uuid, e.cueUuid)
                return@readDir null
            }
            if (!e.ordinal.isFinite()) throw ImportError.invalidArchive("Entry ${e.uuid} has no usable ordinal: ${e.ordinal}")
            pending[uuid] = PendingEntry(
                uuid = uuid, stack = stack, cue = cue, marker = marker,
                label = if (marker) e.label.orEmpty().take(255) else null,
                ordinal = e.ordinal,
                cueNumber = if (marker) null else e.cueNumber?.takeIf { it.isNotEmpty() },
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
        val entries = pending.values.associate { p ->
            p.uuid to DaoCueStackEntry.new {
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
        cueStackMap.values.forEach { renumberAutoEntries(it, emptyList()) }
        clashes.forEach { logger.warn("Import: {}", it.describe()) }
        return entries to clashes
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

    private fun importCuePropertyAssignments(dir: Path, cueMap: Map<UUID, DaoCue>) {
        readDir(dir.resolve("cuePropertyAssignments")) { json ->
            val a = canonicalDecode(CuePropertyAssignmentJson.serializer(), json)
            val cueUuid = UUID.fromString(a.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Property assignment ${a.uuid} references unknown cue $cueUuid")
            val uuid = UUID.fromString(a.uuid)
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = a.targetType
                targetKey = a.targetKey
                propertyName = a.propertyName
                value = a.value
                fadeDuration = a.fadeDurationMs.asDuration()
                sortOrder = a.sortOrder
                moveInDark = a.moveInDark
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importTemplates(
        dir: Path,
        project: DaoProject,
    ): Map<UUID, DaoTemplate> =
        readDir(dir.resolve("templates")) { json ->
            val t = canonicalDecode(TemplateJson.serializer(), json)
            val uuid = UUID.fromString(t.uuid)
            val dao = DaoTemplate.new {
                this.project = project
                name = t.name
                notes = t.notes
                fadeDuration = t.fadeDurationMs.asDuration()
                this.uuid = uuid
            }
            t.rows.forEach { r ->
                DaoTemplateRow.new {
                    template = dao
                    targetType = r.targetType
                    targetKey = r.targetKey
                    propertyName = r.propertyName
                    value = r.value
                    sortOrder = r.sortOrder
                    this.uuid = UUID.fromString(r.uuid)
                }
            }
            // Written verbatim, like an imported Look effect and for the same reason: the write
            // boundary's rules don't run on import, so a snapshot from a *newer* build whose
            // category or blend mode this one does not recognise still lands rather than aborting
            // the pull. `EffectSpecCoercion.Lenient` warns if it eventually fails to spawn.
            t.effect?.let { e ->
                DaoTemplateEffect.new {
                    template = dao
                    effectType = e.effectType
                    category = e.category
                    propertyName = e.propertyName
                    beatDivision = e.beatDivision
                    blendMode = e.blendMode
                    distribution = e.distribution
                    phaseOffset = e.phaseOffset
                    elementMode = e.elementMode
                    elementFilter = e.elementFilter
                    stepTiming = e.stepTiming
                    parameters = e.parameters
                    speedMasterUuid = e.speedMasterUuid?.let { m -> UUID.fromString(m) }
                    rateSpeedMasterUuid = e.rateSpeedMasterUuid?.let { m -> UUID.fromString(m) }
                    seconds = e.seconds
                    size = e.size
                    direction = e.direction
                    nShot = e.nShot
                    this.spread = e.spread
                    this.wings = e.wings
                    this.blocks = e.blocks
                    this.groups = e.groups
                    this.uuid = UUID.fromString(e.uuid)
                }
            }
            uuid to dao
        }

    private fun importCueLayers(
        dir: Path,
        cueMap: Map<UUID, DaoCue>,
        lookMap: Map<UUID, DaoLook>,
        templateMap: Map<UUID, DaoTemplate>,
    ) {
        readDir(dir.resolve("cueLayers")) { json ->
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
            val templateUuidRaw = l.templateUuid
            layerSourceShape(lookUuidRaw, templateUuidRaw).problem?.let { problem ->
                throw ImportError.invalidArchive(
                    "Cue layer ${l.uuid} names $problem; exactly one of lookUuid / templateUuid is required",
                )
            }
            var look: DaoLook? = null
            var template: DaoTemplate? = null
            if (lookUuidRaw != null) {
                val lookUuid = UUID.fromString(lookUuidRaw)
                look = lookMap[lookUuid]
                    ?: throw ImportError.invalidArchive("Cue layer ${l.uuid} references unknown look $lookUuid")
            } else {
                val templateUuid = UUID.fromString(templateUuidRaw)
                template = templateMap[templateUuid]
                    ?: throw ImportError.invalidArchive(
                        "Cue layer ${l.uuid} references unknown template $templateUuid",
                    )
            }
            val uuid = UUID.fromString(l.uuid)
            DaoCueLayer.new {
                this.cue = cue
                this.look = look
                this.template = template
                sortOrder = l.sortOrder
                enabled = l.enabled
                targets = l.targets
                propertyMask = l.propertyMask
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

    private fun importCueAdHocEffects(dir: Path, cueMap: Map<UUID, DaoCue>) {
        readDir(dir.resolve("cueAdHocEffects")) { json ->
            val e = canonicalDecode(CueAdHocEffectJson.serializer(), json)
            val cueUuid = UUID.fromString(e.cueUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Ad-hoc effect ${e.uuid} references unknown cue $cueUuid")
            val uuid = UUID.fromString(e.uuid)
            DaoCueAdHocEffect.new {
                this.cue = cue
                targetType = e.targetType
                targetKey = e.targetKey
                effectType = e.effectType
                category = e.category
                propertyName = e.propertyName
                beatDivision = e.beatDivision
                blendMode = e.blendMode
                distribution = e.distribution
                phaseOffset = e.phaseOffset
                elementMode = e.elementMode
                elementFilter = e.elementFilter
                stepTiming = e.stepTiming
                parameters = e.parameters
                delay = e.delayMs.asDuration()
                interval = e.intervalMs.asDuration()
                randomWindow = e.randomWindowMs.asDuration()
                sortOrder = e.sortOrder
                speedMasterUuid = e.speedMasterUuid?.let { UUID.fromString(it) }
                rateSpeedMasterUuid = e.rateSpeedMasterUuid?.let { UUID.fromString(it) }
                seconds = e.seconds
                size = e.size
                direction = e.direction
                nShot = e.nShot
                this.spread = e.spread
                this.wings = e.wings
                this.blocks = e.blocks
                this.groups = e.groups
                this.uuid = uuid
            }
            uuid to Unit
        }
    }

    private fun importCueTriggers(
        dir: Path,
        cueMap: Map<UUID, DaoCue>,
        scriptMap: Map<UUID, DaoScript>,
    ) {
        readDir(dir.resolve("cueTriggers")) { json ->
            val t = canonicalDecode(CueTriggerJson.serializer(), json)
            val cueUuid = UUID.fromString(t.cueUuid)
            val scriptUuid = UUID.fromString(t.scriptUuid)
            val cue = cueMap[cueUuid]
                ?: throw ImportError.invalidArchive("Trigger ${t.uuid} references unknown cue $cueUuid")
            val script = scriptMap[scriptUuid]
                ?: throw ImportError.invalidArchive("Trigger ${t.uuid} references unknown script $scriptUuid")
            val uuid = UUID.fromString(t.uuid)
            DaoCueTrigger.new {
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
        dir: Path,
        project: DaoProject,
        cueMap: Map<UUID, DaoCue>,
        lookMap: Map<UUID, DaoLook>,
    ) {
        readDir(dir.resolve("cueSlots")) { json ->
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
                return@readDir uuid to Unit
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
            DaoCueSlot.new {
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
        dir: Path,
        project: DaoProject,
        templateMap: Map<UUID, DaoTemplate>,
        lookMap: Map<UUID, DaoLook>,
        cueMap: Map<UUID, DaoCue>,
    ) {
        readDir(dir.resolve("buskPages")) { json ->
            val p = canonicalDecode(BuskPageJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            val page = DaoBuskPage.new {
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
                        if (buskPadKind(d.templateUuid, d.lookUuid, d.cueUuid) == null) {
                            logger.warn("Busk pad {} on page {} names no record or more than one; dropping the pad", d.uuid, p.name)
                            return@forEach
                        }
                        val template = d.templateUuid?.let { templateMap[UUID.fromString(it)] }
                        val look = d.lookUuid?.let { lookMap[UUID.fromString(it)] }
                        val cue = d.cueUuid?.let { cueMap[UUID.fromString(it)] }
                        if (template == null && look == null && cue == null) {
                            logger.warn(
                                "Busk pad {} on page {} names record {} which the archive does not carry; dropping the pad",
                                d.uuid, p.name, d.templateUuid ?: d.lookUuid ?: d.cueUuid,
                            )
                            return@forEach
                        }
                        DaoBuskPad.new {
                            this.bank = bank
                            sortOrder = d.sortOrder
                            this.template = template
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
        dir: Path,
        project: DaoProject,
        groupMap: Map<UUID, DaoFixtureGroup>,
        patchMap: Map<UUID, DaoFixturePatch>,
    ) {
        readDir(dir.resolve("buskRig")) { json ->
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
            if (tiles.isEmpty()) {
                logger.warn("Busk rig row {} ('{}') has no tiles left; dropping the row", r.uuid, r.name)
                return@readDir uuid to Unit
            }
            val row = DaoBuskRigRow.new {
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

    private fun importParkedChannels(dir: Path, project: DaoProject) {
        readDir(dir.resolve("parkedChannels")) { json ->
            val p = canonicalDecode(ParkedChannelJson.serializer(), json)
            val uuid = UUID.fromString(p.uuid)
            DaoParkedChannel.new {
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
    private fun importShowParkReleases(dir: Path, project: DaoProject) {
        val seen = HashSet<Pair<Int, Int>>()
        val parked = project.parkedChannels.map { it.universe to it.channel }.toSet()
        readDir(dir.resolve("showParkReleases")) { json ->
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
                else -> DaoShowParkRelease.new {
                    this.project = project
                    universe = r.universe
                    this.channel = r.channel
                    this.uuid = uuid
                }
            }
            uuid to Unit
        }
    }

    private fun importControlSurfaceBindings(dir: Path, project: DaoProject) {
        readDir(dir.resolve("controlSurfaceBindings")) { json ->
            val b = canonicalDecode(ControlSurfaceBindingJson.serializer(), json)
            val uuid = UUID.fromString(b.uuid)
            DaoControlSurfaceBinding.new {
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

    private fun importPromptBook(dir: Path, project: DaoProject): DaoPromptBook? {
        // Parse only inside readDir (no DB writes), so a legacy multi-book archive can't
        // insert several rows and trip the one-book-per-project unique index. A project
        // has at most one book; more than one file is an invalid archive.
        val parsed = readDir(dir.resolve("promptBooks")) { json ->
            val b = canonicalDecode(PromptBookJson.serializer(), json)
            UUID.fromString(b.uuid) to b
        }
        if (parsed.size > 1) {
            throw ImportError.invalidArchive("project has ${parsed.size} prompt books; expected at most one")
        }
        val b = parsed.values.firstOrNull() ?: return null
        return DaoPromptBook.new {
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
        dir: Path,
        promptBook: DaoPromptBook?,
        entryMap: Map<UUID, DaoCueStackEntry>,
    ) {
        val parsed = readDir(dir.resolve("promptBookAnchors")) { json ->
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
            DaoPromptBookAnchor.new {
                this.promptBook = book
                this.entry = entry
                region = a.region
                label = a.label
                this.uuid = uuid
            }
        }
    }

    private fun importPromptBookAnnotations(dir: Path, promptBook: DaoPromptBook?) {
        readDir(dir.resolve("promptBookAnnotations")) { json ->
            val n = canonicalDecode(PromptBookAnnotationJson.serializer(), json)
            val book = promptBook
                ?: throw ImportError.invalidArchive("Prompt book annotation ${n.uuid} has no prompt book to attach to")
            checkPromptBookRegion(n.region, book.pageCount)?.let {
                throw ImportError.invalidArchive("Prompt book annotation ${n.uuid} has an invalid region: $it")
            }
            val uuid = UUID.fromString(n.uuid)
            val dao = DaoPromptBookAnnotation.new {
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
     * Reads every `*.json` file under [dir], applying [block] inside the existing transaction.
     * Returns a UUID-keyed map of whatever [block] produces. No-op (empty map) if [dir] doesn't
     * exist — every section is optional, so an export with zero scripts produces no `scripts/`.
     */
    /** Read every record file under [dir]; a null answer from [block] reads the file but keeps nothing. */
    private fun <T> readDir(dir: Path, block: (String) -> Pair<UUID, T>?): Map<UUID, T> {
        if (!dir.exists() || !dir.isDirectory()) return emptyMap()
        val out = mutableMapOf<UUID, T>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") && !it.fileName.toString().endsWith(".meta.json") }
                .forEach { file ->
                    val (uuid, value) = block(Files.readString(file)) ?: return@forEach
                    out[uuid] = value
                }
        }
        return out
    }
}
