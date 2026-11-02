package uk.me.cormack.lighting7.history

import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.stackEntryPriorities
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
 * standby dropped, a held cue whose stage content moved marked edited since GO, and every stack it
 * touched restamped and its run state published.
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
            PROMPT_BOOK_ANCHORS,
        ) + CUE_CHILDREN
    }

    fun afterRestore(
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
        class Rows(val cueIds: Map<UUID, Int>, val stacks: List<Pair<Int, Map<Int, Int>>>)
        val rows = transaction(state.database) {
            Rows(
                cueIds = cueUuids.mapNotNull { uuid ->
                    DaoCue.find { DaoCues.uuid eq uuid }.firstOrNull { it.project.id.value == projectId }?.let { uuid to it.id.value }
                }.toMap(),
                stacks = stackUuids.mapNotNull { uuid -> DaoCueStack.find { DaoCueStacks.uuid eq uuid }.firstOrNull { it.project.id.value == projectId } }
                    .map { it.id.value to (if (current) stackEntryPriorities(it) else emptyMap()) },
            )
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
                for ((stackId, priorities) in rows.stacks) announceEntriesPlaced(state, stackId, priorities)
            }
            fixtures.cueListChanged()
            fixtures.cueStackListChanged()
            announceCuePadsChanged(state, rows.cueIds.values)
        }

        if (touched(RecordFolders.CUE_SLOTS).isNotEmpty()) fixtures.cueSlotListChanged()
        touched(RecordFolders.BUSK_PAGES).mapNotNull { (after[it] ?: before[it])?.rowId }.takeIf { it.isNotEmpty() }?.let { fixtures.buskLayoutChanged(it) }
        if (touched(RecordFolders.BUSK_RIG).isNotEmpty()) fixtures.buskRigChanged()
        if (touched(RecordFolders.PROMPT_BOOK_ANCHORS).isNotEmpty() || removal.anchorsRemoved > 0) fixtures.promptBookChanged()
        if (touched(SyncDispositions.PROJECT_FOLDER).isNotEmpty()) fixtures.projectDetailsChanged(projectId)
    }
}

private fun RecordImage.uuidField(name: String): UUID? =
    (jsonObjectOrNull()?.get(name) as? JsonPrimitive)?.content?.let { runCatching { UUID.fromString(it) }.getOrNull() }
