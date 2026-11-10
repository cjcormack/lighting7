package uk.me.cormack.lighting7.history

import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.fx.stackEntryPositions
import uk.me.cormack.lighting7.fx.StackEntryPositions
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.routes.registerUserFx
import uk.me.cormack.lighting7.routes.PUT_KEYS_WITHOUT_REBUILD
import uk.me.cormack.lighting7.routes.toDto
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.routes.reassertLocates
import uk.me.cormack.lighting7.routes.republishCueIfLive
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.routes.CueDeletion
import uk.me.cormack.lighting7.routes.EntryRemoval
import uk.me.cormack.lighting7.routes.afterCuesDeleted
import uk.me.cormack.lighting7.routes.afterEntriesRemoved
import uk.me.cormack.lighting7.routes.announceCuePadsChanged
import uk.me.cormack.lighting7.routes.announceEntriesPlaced
import uk.me.cormack.lighting7.routes.republishForLookDelete
import uk.me.cormack.lighting7.routes.republishForLookEdit
import uk.me.cormack.lighting7.routes.republishForPaletteEdit
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.RecordFolders
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.SyncDispositions
import uk.me.cormack.lighting7.sync.jsonObjectOrNull
import java.util.UUID

/**
 * What an undo or redo sets off once it has committed (undo plan §5, D9): for each folder it put back,
 * what the write it reverses ran, through the routes' own after-commit helpers — the change frames,
 * and on the live project the runtime: a Look or palette republished to every live cue and the
 * programmer, one taken away released, a cue taken away let go, an entry taken away detached and its
 * standby dropped, a held cue whose stage content moved marked edited since GO, every stack it
 * touched restamped and its run state published, the fixtures reloaded for a change to the rig, the
 * parks for a park, the speed masters, the FX registry and the binding cache for theirs.
 *
 * [before] is each record as it stood before the undo, [after] as it now stands (null for one gone);
 * [removal] is what taking its entries away did, and [stageChanged] the held cues whose *Update
 * stage* content it moved.
 */
internal object KindEffects {

    /** Every folder this answers for; `RecordRestoreTest` holds it to [uk.me.cormack.lighting7.sync.RESTORABLE_FOLDERS]. */
    val folders: Set<String> = with(RecordFolders) {
        setOf(
            SyncDispositions.PROJECT_FOLDER, LOOKS, PALETTES, CUE_STACKS, CUE_STACK_ENTRIES, CUES, CUE_SLOTS, BUSK_PAGES, BUSK_RIG,
            RIGGINGS, STAGE_REGIONS, STAGE_ELEMENTS, STAGE_VIEWPOINTS, SPEED_MASTERS, SCRIPTS, FX_DEFINITIONS, PROMPT_BOOKS,
            CONTROL_SURFACE_BINDINGS, SURFACE_JOBS,
        ) + CUE_CHILDREN + PROMPT_BOOK_CHILDREN + RIG + PARKS
    }

    suspend fun afterRestore(
        state: State,
        projectId: Int,
        current: Boolean,
        before: Map<RecordKey, RecordImage?>,
        after: Map<RecordKey, RecordImage?>,
        removal: EntryRemoval,
        stageChanged: Set<Int>,
    ) {
        val keys = after.keys
        fun touched(vararg folders: String) = keys.filter { it.tableName in folders }
        fun images(key: RecordKey) = listOfNotNull(before[key], after[key])
        val fixtures = state.show.fixtures

        val cueKeys = touched(RecordFolders.CUES, *RecordFolders.CUE_CHILDREN.toTypedArray())
        val entryKeys = touched(RecordFolders.CUE_STACK_ENTRIES)
        val stackKeys = touched(RecordFolders.CUE_STACKS)
        val cueUuids = cueKeys.flatMap { key -> if (key.tableName == RecordFolders.CUES) listOf(key.uuid) else images(key).mapNotNull { it.uuidField("cueUuid") } }
            .toSet() + entryKeys.flatMap(::images).mapNotNull { it.uuidField("cueUuid") }
        val stackUuids = stackKeys.map { it.uuid }.toSet() + entryKeys.flatMap(::images).mapNotNull { it.uuidField("cueStackUuid") }
        val rigKeys = touched(*RecordFolders.RIG.toTypedArray())
        val reach = rigKeys.associateWith { rigReach(it, before[it], after[it]) }
        val rebuilt = RigReach.REBUILD in reach.values
        class Rows(val cueIds: Map<UUID, Int>, val stacks: List<Pair<Int, StackEntryPositions>>, val patchKeys: Set<String>)
        val rows = transaction(state.database) {
            // The units a patch or override the undo moved stands for, by key, whether it put them back or took them away.
            val overridden = touched(RecordFolders.SHOW_OVERRIDES).flatMap(::images).mapNotNullTo(HashSet()) { it.uuidField("recordUuid") }
            Rows(
                cueIds = cueUuids.mapNotNull { uuid ->
                    DaoCue.find { DaoCues.uuid eq uuid }.firstOrNull { it.project.id.value == projectId }?.let { uuid to it.id.value }
                }.toMap(),
                stacks = stackUuids.mapNotNull { uuid -> DaoCueStack.find { DaoCueStacks.uuid eq uuid }.firstOrNull { it.project.id.value == projectId } }
                    .map { it.id.value to (if (current) stackEntryPositions(it) else emptyMap()) },
                patchKeys = touched(RecordFolders.FIXTURE_PATCHES).flatMap(::images).mapNotNullTo(HashSet()) { it.stringField("key") } +
                    (if (overridden.isEmpty()) emptySet() else RigScope.of(DaoProject[projectId]).patches().filter { it.uuid in overridden }.map { it.key }),
            )
        }

        // The rig and the parks first, so a Look, palette or cue republished below resolves its keys
        // against the fixtures as they now stand — the order `rename_fixtures` announces its rewrite in.
        // Each record reaches the live fixtures as its own route's write does ([rigReach]).
        if (rigKeys.isNotEmpty()) {
            if (current) {
                if (rebuilt) {
                    DbFixtureLoader.loadFixtures(projectId, fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
                } else {
                    refreshMetadata(state, projectId, reach.filterValues { it == RigReach.METADATA }.keys, before, after)
                    if (RigReach.LIST in reach.values) fixtures.announceFixturesChanged()
                }
                // A stored locate or Focus override put back moves a located head, as its own write does.
                reassertLocates(state, rows.patchKeys)
            }
            fixtures.patchListChanged()
        }
        if (touched(*RecordFolders.PARKS.toTypedArray()).isNotEmpty() && current) {
            state.show.parkManager.reload()
            state.show.dropRefusedParks()
        }

        touched(RecordFolders.LOOKS).takeIf { it.isNotEmpty() }?.let { looks ->
            if (current) {
                for (key in looks) {
                    if (after[key] != null) {
                        republishForLookEdit(state, key.uuid)
                        continue
                    }
                    // As a Look delete: its programmer layers released first, then the cues that layered it.
                    state.show.programmerLayerStack.release(setOf(key.uuid))
                    before[key]?.rowId?.let { state.show.programmerStore.clearIncludeTargetForLook(it) }
                    val layering = touched(RecordFolders.CUE_LAYERS)
                        .filter { after[it] == null && before[it]?.uuidField("lookUuid") == key.uuid }
                        .mapNotNull { before[it]?.uuidField("cueUuid")?.let(rows.cueIds::get) }.toSet()
                    republishForLookDelete(state, key.uuid, layering)
                }
            }
            fixtures.lookListChanged()
        }
        touched(RecordFolders.PALETTES).takeIf { it.isNotEmpty() }?.let { palettes ->
            if (current) {
                for (key in palettes) {
                    if (after[key] == null) {
                        state.show.programmerStore.layers
                            .filter { it.source.isPalette && it.source.uuid == key.uuid }
                            .forEach { state.show.programmerLayerStack.remove(it.layerId) }
                    }
                    republishForPaletteEdit(state, key.uuid)
                }
            }
            fixtures.paletteListChanged()
        }

        if (cueKeys.isNotEmpty() || entryKeys.isNotEmpty() || stackKeys.isNotEmpty()) {
            afterEntriesRemoved(state, removal)
            val gone = cueKeys.filter { it.tableName == RecordFolders.CUES && after[it] == null }.mapNotNullTo(HashSet()) { before[it]?.rowId }
            afterCuesDeleted(state, CueDeletion(gone, EntryRemoval.NONE, emptySet(), current))
            if (current) {
                stageChanged.forEach { state.show.cueStackManager.holds.markEdited(it) }
                // With the rig, a held cue's rows follow it onto the stage, as a rename's rewrite republishes them.
                if (rebuilt) rows.cueIds.values.forEach { republishCueIfLive(state, it) }
                for ((stackId, priorities) in rows.stacks) announceEntriesPlaced(state, stackId, priorities)
            }
            fixtures.cueListChanged()
            fixtures.cueStackListChanged()
            announceCuePadsChanged(state, rows.cueIds.values)
        }

        if (touched(RecordFolders.CUE_SLOTS).isNotEmpty()) fixtures.cueSlotListChanged()
        touched(RecordFolders.BUSK_PAGES).mapNotNull { (after[it] ?: before[it])?.rowId }.takeIf { it.isNotEmpty() }?.let { fixtures.buskLayoutChanged(it) }
        val rigTiles = touched(RecordFolders.FIXTURE_PATCHES, RecordFolders.FIXTURE_GROUPS, RecordFolders.SHOW_COLOURS, RecordFolders.SHOW_GROUP_ORDERS)
        if (touched(RecordFolders.BUSK_RIG).isNotEmpty() || rigTiles.isNotEmpty()) fixtures.buskRigChanged()
        val book = touched(RecordFolders.PROMPT_BOOKS, *RecordFolders.PROMPT_BOOK_CHILDREN.toTypedArray())
        if (book.isNotEmpty() || removal.anchorsRemoved > 0) fixtures.promptBookChanged()
        if (touched(SyncDispositions.PROJECT_FOLDER).isNotEmpty()) fixtures.projectDetailsChanged(projectId)

        // A hide's frame is its record's kind's.
        val hiddenKinds = touched(RecordFolders.SHOW_HIDES).flatMap(::images).mapNotNullTo(HashSet()) { it.stringField("recordKind") }
        if (touched(RecordFolders.RIGGINGS).isNotEmpty() || HideKind.RIGGING.name in hiddenKinds) {
            fixtures.riggingListChanged()
            fixtures.patchListChanged()
        }
        if (touched(RecordFolders.STAGE_REGIONS).isNotEmpty() || HideKind.REGION.name in hiddenKinds) fixtures.stageRegionListChanged()
        // The scenery recomputes on this frame, so a piece the undo takes away or puts back moves with it.
        if (touched(RecordFolders.STAGE_ELEMENTS).isNotEmpty() || HideKind.ELEMENT.name in hiddenKinds) fixtures.stageElementListChanged()
        if (touched(RecordFolders.STAGE_VIEWPOINTS).isNotEmpty() || HideKind.VIEWPOINT.name in hiddenKinds) fixtures.stageViewpointListChanged()

        if (touched(RecordFolders.SPEED_MASTERS).isNotEmpty()) {
            if (current) state.show.reloadSpeedMasters()
            fixtures.speedMasterListChanged()
        }
        if (touched(RecordFolders.SCRIPTS).isNotEmpty()) fixtures.scriptListChanged()
        touched(RecordFolders.FX_DEFINITIONS).takeIf { it.isNotEmpty() }?.let { definitions ->
            if (current) reregister(state, projectId, definitions, before, after)
            fixtures.fxDefinitionListChanged()
        }
        if (touched(RecordFolders.CONTROL_SURFACE_BINDINGS, RecordFolders.SURFACE_JOBS).isNotEmpty()) {
            state.controlSurfaceBindingService.invalidate(projectId)
        }
    }

    /** How putting one rig record back reaches the live fixtures. */
    internal enum class RigReach { REBUILD, METADATA, LIST, NONE }

    /**
     * How a rig record going from [before] to [after] reaches the live fixtures, as its own route's write
     * does: a patch or group added or taken away, a universe, an order, an Address or Media override, and a
     * patch or group hide rebuild them; a patch's drawn fields alone (`PUT_KEYS_WITHOUT_REBUILD`, its locate)
     * and a Focus override refresh its metadata; a recolour, a group's colour alone, announces the fixture
     * list; a Position override and a hide of anything else are drawn only. What rebuilds is what moves
     * the stage, which the undo's confirm asks about.
     */
    internal fun rigReach(key: RecordKey, before: RecordImage?, after: RecordImage?): RigReach = when (key.tableName) {
        RecordFolders.FIXTURE_PATCHES -> when {
            before == null || after == null -> RigReach.REBUILD
            changedFields(before, after).all { it in PATCH_METADATA_FIELDS } -> RigReach.METADATA
            else -> RigReach.REBUILD
        }
        RecordFolders.FIXTURE_GROUPS -> when {
            before == null || after == null -> RigReach.REBUILD
            changedFields(before, after).all { it == FixtureColours.FIELD } -> RigReach.LIST
            else -> RigReach.REBUILD
        }
        RecordFolders.SHOW_HIDES ->
            if ((after ?: before)?.stringField("recordKind") in setOf(HideKind.PATCH.name, HideKind.GROUP.name)) RigReach.REBUILD else RigReach.NONE
        RecordFolders.SHOW_OVERRIDES -> when ((after ?: before)?.stringField("facet")) {
            PatchFacet.ADDRESS.name, PatchFacet.MEDIA.name, PatchFacet.NAME.name -> RigReach.REBUILD
            PatchFacet.FOCUS.name -> RigReach.METADATA
            else -> RigReach.NONE
        }
        RecordFolders.SHOW_COLOURS -> RigReach.LIST
        else -> RigReach.REBUILD
    }

    /** The fields of a patch the loader does not build from: the patch PUT's, and its stored locate. */
    private val PATCH_METADATA_FIELDS: Set<String> by lazy { PUT_KEYS_WITHOUT_REBUILD + "locate" }

    private fun changedFields(before: RecordImage, after: RecordImage): Set<String> {
        val a = before.jsonObjectOrNull() ?: return setOf("*")
        val b = after.jsonObjectOrNull() ?: return setOf("*")
        return (a.keys + b.keys).filterTo(HashSet()) { a[it] != b[it] }
    }

    /**
     * The patch metadata of each unit [keys] (patches, or a show's Focus overrides) stands for, read from
     * the effective rig and set in place, as a drawn-only patch write sets it; a flipped infrastructure
     * flag announces the fixture list, which carries it.
     */
    private fun refreshMetadata(
        state: State, projectId: Int, keys: Set<RecordKey>, before: Map<RecordKey, RecordImage?>, after: Map<RecordKey, RecordImage?>,
    ) {
        if (keys.isEmpty()) return
        val fixtures = state.show.fixtures
        // An override names its patch; one the undo took away is gone from the cache, so read its images.
        val uuids = keys.mapNotNullTo(HashSet()) { key ->
            if (key.tableName == RecordFolders.SHOW_OVERRIDES) (after[key] ?: before[key])?.uuidField("recordUuid") else key.uuid
        }
        val patches = transaction(state.database) {
            val rig = RigScope.of(DaoProject[projectId])
            rig.patches().filter { it.uuid in uuids }.map { it.toDto(rig = rig) }
        }
        var flipped = false
        for (patch in patches) {
            val was = fixtures.patchMetadataFor(patch.key) ?: continue
            if (was.infrastructure != patch.infrastructure) flipped = true
            fixtures.setPatchMetadata(
                patch.key,
                Fixtures.FixturePatchMetadata(gelCode = patch.gelCode, infrastructure = patch.infrastructure, media = patch.media, locate = patch.locate),
            )
        }
        if (flipped) fixtures.announceFixturesChanged()
    }

    /**
     * The live registry after an undo of FX definitions, as their routes keep it: a definition taken
     * away or renamed unregistered under its old id, and every one that stands compiled and registered
     * again. One that no longer compiles is logged and unregistered, so the registry runs no code the
     * database does not hold.
     */
    private fun reregister(state: State, projectId: Int, keys: List<RecordKey>, before: Map<RecordKey, RecordImage?>, after: Map<RecordKey, RecordImage?>) {
        // Every id that goes first, then every one that stands: two definitions that traded ids keep both.
        for (key in keys) {
            val was = before[key]?.stringField("fxId")
            if (was != null && was != after[key]?.stringField("fxId")) state.show.fxRegistry.unregister(was)
        }
        for (key in keys) {
            val now = after[key]?.stringField("fxId") ?: continue
            val definition = transaction(state.database) {
                DaoFxDefinition.find { DaoFxDefinitions.uuid eq key.uuid }.firstOrNull { it.project.id.value == projectId }
            } ?: continue
            val result = registerUserFx(state, definition)
            if (!result.success) {
                state.show.fxRegistry.unregister(now)
                log.warn("History: FX definition '{}' put back by an undo does not compile; unregistered", now)
            }
        }
    }

    private val log = LoggerFactory.getLogger(KindEffects::class.java)
}

private fun RecordImage.uuidField(name: String): UUID? =
    stringField(name)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun RecordImage.stringField(name: String): String? = (jsonObjectOrNull()?.get(name) as? JsonPrimitive)?.content
