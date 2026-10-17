package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.stackEntryPriorities
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.BindingTargetJson
import uk.me.cormack.lighting7.midi.discriminator
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

/**
 * Removing a cue's entries and deleting cues (cue model plan §3.6, D1, D8, D16, D17).
 *
 * An entry is a cue's place in a stack, so removing one never deletes its cue: the cue is loose once
 * its last entry goes. Deleting a cue is its own operation, refused while anything names it
 * ([cueUsesOf]) unless forced, when it takes every entry, anchor, pad and slot with it. A
 * `FireCue` binding is left in place and reports its cue missing, as a binding to any deleted cue
 * always has.
 *
 * Each operation is two halves: the rows, inside the caller's transaction, and the runtime and the
 * broadcasts once it has committed ([afterEntriesRemoved], [afterCuesDeleted]).
 */

internal const val CODE_CUE_IN_USE = "CUE_IN_USE"

@Serializable
data class CueUseEntryDto(val stackId: Int, val stackName: String, val entryId: Int, val cueNumber: String? = null)

@Serializable
data class CueUsePadDto(val padId: Int, val pageId: Int, val pageName: String)

@Serializable
data class CueUseSlotDto(val slotId: Int, val label: String)

@Serializable
data class CueUseAnchorDto(val entryId: Int, val label: String? = null)

@Serializable
data class CueUseBindingDto(val bindingId: Int, val label: String)

/** Everything that names one cue — what a delete without force refuses over, and what a confirm lists. */
@Serializable
data class CueUsesDto(
    val cueId: Int,
    val name: String,
    val entries: List<CueUseEntryDto> = emptyList(),
    val pads: List<CueUsePadDto> = emptyList(),
    val slots: List<CueUseSlotDto> = emptyList(),
    val anchors: List<CueUseAnchorDto> = emptyList(),
    val bindings: List<CueUseBindingDto> = emptyList(),
) {
    fun isUsed(): Boolean = entries.isNotEmpty() || pads.isNotEmpty() || slots.isNotEmpty() || anchors.isNotEmpty() || bindings.isNotEmpty()

    fun usedBy() = CueUsedByDto(pads, slots, bindings)
}

/** What names a cue beside its entries and their anchors: the cue list's *Used by* (cue model plan §3.7). */
@Serializable
data class CueUsedByDto(
    val pads: List<CueUsePadDto> = emptyList(),
    val slots: List<CueUseSlotDto> = emptyList(),
    val bindings: List<CueUseBindingDto> = emptyList(),
)

/** 409 `CUE_IN_USE`: the cues a delete without force would have taken in use, each with its uses. */
@Serializable
data class CueInUseResponse(val error: String, val code: String = CODE_CUE_IN_USE, val uses: List<CueUsesDto>)

@Serializable
data class DeleteCuesRequest(val cueIds: List<Int>, val force: Boolean = false, val dryRun: Boolean = false)

/** [deleted]: the cues deleted (none on a dry run). [uses]: every named cue that something names. */
@Serializable
data class DeleteCuesResponse(val deleted: List<Int>, val uses: List<CueUsesDto>)

/** A stack delete's answer: the cues it left in no stack, and those it deleted when asked to (D17). */
@Serializable
data class DeleteCueStackResponse(val looseCueIds: List<Int>, val deletedCueIds: List<Int>)

/** The in-use message for [uses], naming each cue and how many things name it. */
internal fun cueInUseMessage(uses: List<CueUsesDto>): String =
    uses.joinToString("; ", prefix = "In use — delete with force to remove these references too: ") { u ->
        val parts = listOfNotNull(
            u.entries.size.takeIf { it > 0 }?.let { "$it stack entr${if (it == 1) "y" else "ies"}" },
            u.pads.size.takeIf { it > 0 }?.let { "$it busk pad${if (it == 1) "" else "s"}" },
            u.slots.size.takeIf { it > 0 }?.let { "$it cue slot${if (it == 1) "" else "s"}" },
            u.anchors.size.takeIf { it > 0 }?.let { "$it prompt-book anchor${if (it == 1) "" else "s"}" },
            u.bindings.size.takeIf { it > 0 }?.let { "$it surface binding${if (it == 1) "" else "s"}" },
        )
        "'${u.name}' (${parts.joinToString()})"
    }

/**
 * What names each of [cues] of [project]: its entries (in show order), busk pads, cue slots, the
 * prompt-book anchors on its entries and the `FireCue` bindings to it. Must run inside a transaction.
 */
internal fun cueUsesOf(
    project: DaoProject,
    cues: List<DaoCue>,
    /** Their entries, when the caller has already read them. */
    slots: Map<Int, List<EntrySlot>> = entrySlotsOf(cues.map { it.id.value }),
): Map<Int, CueUsesDto> {
    if (cues.isEmpty()) return emptyMap()
    val ids = cues.map { it.id.value }
    val entryIds = slots.values.flatten().map { it.entry.id }
    val anchors = if (entryIds.isEmpty()) emptyList() else
        DaoPromptBookAnchor.find { DaoPromptBookAnchors.entry inList entryIds }.toList()
    val cueOfEntry = slots.values.flatten().associate { it.entry.id.value to it.entry.cueId!! }
    val pads = DaoBuskPad.find { DaoBuskPads.cue inList ids }.toList()
    val cueSlots = DaoCueSlot.find { DaoCueSlots.cue inList ids }.toList()
    val fireCue = BindingTarget.FireCue(0).discriminator()
    val byUuid = cues.associateBy { it.uuid.toString() }
    val bindings = DaoControlSurfaceBinding.find {
        (DaoControlSurfaceBindings.project eq project.id) and (DaoControlSurfaceBindings.targetType eq fireCue)
    }.mapNotNull { binding ->
        val target = runCatching { BindingTargetJson.decodeFromString<BindingTarget>(binding.targetPayload) }.getOrNull()
            as? BindingTarget.FireCue ?: return@mapNotNull null
        // Uuid first, as the binding's own health check reads it; the int only for a row with none.
        val cueId = target.cueUuid?.let { byUuid[it]?.id?.value } ?: target.cueId.takeIf { target.cueUuid == null && it in ids }
        cueId?.let { it to binding }
    }
    return cues.associate { cue ->
        val id = cue.id.value
        id to CueUsesDto(
            cueId = id,
            name = cue.name,
            entries = slots[id].orEmpty().map { CueUseEntryDto(it.entry.stackId, it.entry.stack.name, it.entry.id.value, it.entry.cueNumber) },
            pads = pads.filter { it.cueId == id }.map { pad ->
                val page = pad.bank.column.page
                CueUsePadDto(pad.id.value, page.id.value, page.name)
            },
            slots = cueSlots.filter { it.cueId == id }.map { CueUseSlotDto(it.id.value, "Page ${it.page + 1}, slot ${it.slotIndex + 1}") },
            anchors = anchors.filter { cueOfEntry[it.entryId] == id }.map { CueUseAnchorDto(it.entryId, it.label) },
            bindings = bindings.filter { it.first == id }.map { (_, b) ->
                CueUseBindingDto(b.id.value, listOfNotNull(b.deviceTypeKey, b.controlId, b.bank?.let { "bank $it" }).joinToString(" · "))
            },
        )
    }
}

private val DaoBuskPad.cueId: Int? get() = readValues[DaoBuskPads.cue]?.value
private val DaoCueSlot.cueId: Int? get() = readValues[DaoCueSlots.cue]?.value

/** What [removeEntries] did, for [afterEntriesRemoved] once the transaction has committed. */
internal data class EntryRemoval(
    val entryIds: Set<Int>,
    /** The cues the removed entries placed. */
    val cueIds: Set<Int>,
    /** The live stacks' ids among those that lost an entry — their run state moved. */
    val stackIds: Set<Int>,
    val anchorsRemoved: Int,
    /** The surviving entries' priorities in every stack that lost one: positions shifted. */
    val priorities: Map<Int, Int>,
) {
    companion object {
        val NONE = EntryRemoval(emptySet(), emptySet(), emptySet(), 0, emptyMap())
    }
}

/**
 * Remove [entries] from their stacks — never their cues — with the prompt-book anchors on them, and
 * renumber each stack they left. A live stack on one of them keeps holding its cue and plays on from
 * where it stood (D16). Inside the caller's transaction; [afterEntriesRemoved] once it commits.
 */
internal fun removeEntries(state: State, project: DaoProject, entries: List<DaoCueStackEntry>): EntryRemoval {
    if (entries.isEmpty()) return EntryRemoval.NONE
    val current = project.id == state.projectManager.currentProject.id
    val stacks = entries.map { it.stack }.distinctBy { it.id.value }
    val before = stacks.associate { it.id.value to cueEntryIds(it) }
    val lists = stacks.associate { it.id.value to entriesOf(it) }
    val removed = entries.mapTo(HashSet()) { it.id.value }
    var anchors = 0
    for (entry in entries) {
        anchors += DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.entry eq entry.id }
        if (current) {
            val list = lists.getValue(entry.stackId)
            val survivors = list.mapNotNullTo(HashSet()) { e -> e.id.value.takeIf { it !in removed } }
            state.show.cueStackManager.detachEntry(entry.stackId, entry.id.value, EntryPlace.of(entry, list, survivors))
        }
        entry.delete()
    }
    stacks.forEach { renumberAutoEntries(it, before.getValue(it.id.value)) }
    return EntryRemoval(
        entryIds = entries.mapTo(HashSet()) { it.id.value },
        cueIds = entries.mapNotNullTo(HashSet()) { it.cueId },
        stackIds = if (current) stacks.mapTo(HashSet()) { it.id.value } else emptySet(),
        anchorsRemoved = anchors,
        priorities = if (current) stacks.fold(emptyMap()) { acc, s -> acc + stackEntryPriorities(s) } else emptyMap(),
    )
}

/** The runtime half of [removeEntries]: priorities, standbys, the include target and run state. */
internal fun afterEntriesRemoved(state: State, removal: EntryRemoval) {
    if (removal.entryIds.isEmpty()) return
    val manager = state.show.cueStackManager
    manager.holds.restampEntries(removal.priorities)
    manager.runState.clearStandbyForEntries(state, removal.entryIds)
    state.show.programmerStore.forgetIncludedEntries(removal.entryIds)
    for (stackId in removal.stackIds) {
        if (manager.isStackActive(stackId)) manager.runState.publishRunState(state, stackId)
    }
}

/** What [deleteCues] did, for [afterCuesDeleted] once the transaction has committed. */
internal data class CueDeletion(
    val cueIds: Set<Int>,
    val removal: EntryRemoval,
    /** Busk pages that lost a pad. */
    val pageIds: Set<Int>,
    val current: Boolean,
) {
    companion object {
        val NONE = CueDeletion(emptySet(), EntryRemoval.NONE, emptySet(), false)
    }
}

/**
 * Delete [cues] of [project] with everything that names them (D8's force): their entries (each stack
 * renumbered, a live one detached), the anchors on those, their pads and slots, their children,
 * scenery and events. Inside the caller's transaction; [afterCuesDeleted] once it commits.
 */
internal fun deleteCues(state: State, project: DaoProject, cues: List<DaoCue>): CueDeletion {
    if (cues.isEmpty()) return CueDeletion.NONE
    val removal = removeEntries(state, project, cues.flatMap { entriesOfCue(it) })
    val pageIds = mutableSetOf<Int>()
    for (cue in cues) {
        deleteCueChildren(cue)
        // Its scenery and its events die with it, but not with an edit of its children.
        deleteCueScenery(cue.id)
        deleteCueEvents(cue.id)
        pageIds += deleteCueReferences(cue)
        cue.delete()
    }
    return CueDeletion(cues.mapTo(HashSet()) { it.id.value }, removal, pageIds, project.id == state.projectManager.currentProject.id)
}

/**
 * The runtime half of [deleteCues], and the broadcasts. Every hold on a deleted cue is let go —
 * nothing else ever would, since every republish rebuilds from rows that are gone — and the include
 * target dropped, so the programmer indicator stops offering a cue that no longer exists.
 */
internal fun afterCuesDeleted(state: State, deletion: CueDeletion) {
    if (deletion.cueIds.isEmpty()) return
    if (deletion.current) {
        afterEntriesRemoved(state, deletion.removal)
        for (cueId in deletion.cueIds) {
            state.show.programmerStore.clearIncludeTargetForCue(cueId)
            state.show.cueStackManager.holds.dropAll(state, cueId)
        }
    }
    state.show.fixtures.cueListChanged()
    state.show.fixtures.cueStackListChanged()
    if (deletion.removal.anchorsRemoved > 0) state.show.fixtures.promptBookChanged()
    if (deletion.pageIds.isNotEmpty()) state.show.fixtures.buskLayoutChanged(deletion.pageIds.toList())
    state.show.fixtures.cueSlotListChanged()
}

/**
 * Tell the busk pages holding a pad for one of [cueIds] to re-read: a pad's face shows its cue's
 * places (`BuskCueDto.entries`), which an add, a removal, a renumber or a stack delete moved. Its
 * own transaction; call once the write has committed.
 */
internal fun announceCuePadsChanged(state: State, cueIds: Collection<Int>) {
    if (cueIds.isEmpty()) return
    val pageIds = transaction(state.database) {
        DaoBuskPad.find { DaoBuskPads.cue inList cueIds.distinct() }
            .mapTo(HashSet()) { it.bank.column.page.id.value }
    }
    if (pageIds.isNotEmpty()) state.show.fixtures.buskLayoutChanged(pageIds.toList())
}
