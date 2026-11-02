package uk.me.cormack.lighting7.history

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.routes.cueEntriesInOrder
import uk.me.cormack.lighting7.routes.renumberAutoEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.routes.EntryRemoval
import uk.me.cormack.lighting7.routes.removeEntries
import uk.me.cormack.lighting7.routes.stageContent
import uk.me.cormack.lighting7.sync.ImportError
import uk.me.cormack.lighting7.sync.RecordFolders
import uk.me.cormack.lighting7.sync.jsonObjectOrNull
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.routes.cuesReferencingLook
import uk.me.cormack.lighting7.routes.cuesReferencingPalette
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.MapSink
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.sync.RESTORABLE_FOLDERS
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import java.util.UUID

/**
 * Undo and redo of the show history (undo plan §3.5, D3, D6, D9): plans against the record cache,
 * puts the records back through the importer's upsert ([ProjectImporter.restore]), ids kept and in one
 * transaction, and runs what the original writes ran ([KindEffects]). The transaction's commit is under
 * an [WriteOrigin.Kind.UNDO] or [WriteOrigin.Kind.REDO] origin, which marks the entries rather than
 * making one.
 *
 * Before it commits, the folders it wrote are exported again and held to what the entries say: every
 * record put back exactly, with its row id, and nothing else in those folders moved. Anything else
 * rolls the whole undo back, as a constraint the database holds (a name someone has since taken) does.
 */
internal class RecordRestorer(private val state: State) {

    sealed interface Outcome {
        data class Empty(val already: Boolean) : Outcome
        data object Unknown : Outcome
        data class Conflict(val entry: HistoryEntry, val later: List<HistoryEntry>) : Outcome
        data class Confirm(val moves: List<String>) : Outcome
        data class Blocked(val record: String, val reason: String) : Outcome
        data class Done(val entries: List<HistoryEntry>) : Outcome
    }

    fun undo(projectId: Int, entryId: String?, caller: HistorySource, window: String?, backToHere: Boolean, confirm: Boolean): Outcome =
        apply(projectId, caller, window, WriteOrigin.Kind.UNDO, confirm) { state.history.planUndo(projectId, entryId, caller.key, backToHere) }

    fun redo(projectId: Int, entryId: String?, caller: HistorySource, window: String?, confirm: Boolean): Outcome =
        apply(projectId, caller, window, WriteOrigin.Kind.REDO, confirm) { state.history.planRedo(projectId, entryId, caller.key) }

    /** Thrown inside the transaction to roll it back with a [Outcome.Blocked]. */
    private class BlockedException(val blocked: Outcome.Blocked) : RuntimeException(blocked.reason)

    private fun apply(
        projectId: Int,
        caller: HistorySource,
        window: String?,
        kind: WriteOrigin.Kind,
        confirm: Boolean,
        plan: () -> HistoryService.Plan,
    ): Outcome {
        val current = state.projectManager.currentProjectOrNull?.id?.value == projectId
        val origin = WriteOrigin(caller, window, kind = kind)
        var applied: Applied? = null
        val outcome = try {
            WriteOrigin.with(origin) {
                transaction(state.database) {
                    // A statement first: the pool's one connection is ours from here, so no commit lands
                    // between reading the cache and writing against it.
                    val project = DaoProject.findById(projectId) ?: return@transaction Outcome.Empty(false)
                    val ready = when (val p = plan()) {
                        is HistoryService.Plan.Empty -> return@transaction Outcome.Empty(p.already)
                        HistoryService.Plan.Unknown -> return@transaction Outcome.Unknown
                        is HistoryService.Plan.Conflict -> return@transaction Outcome.Conflict(p.entry, p.later)
                        is HistoryService.Plan.InUse -> return@transaction Outcome.Blocked(p.record, "${p.by} uses it")
                        is HistoryService.Plan.Ready -> p
                    }
                    ready.targets.keys.firstOrNull { it.tableName !in RESTORABLE_FOLDERS }?.let { key ->
                        return@transaction Outcome.Blocked(
                            RecordLabeler.name(key, ready.targets[key] ?: state.history.recordOf(projectId, key)),
                            "Undoing this kind of change is not available yet",
                        )
                    }
                    if (current && !confirm) {
                        val moves = movesOf(ready.targets)
                        if (moves.isNotEmpty()) return@transaction Outcome.Confirm(moves)
                    }
                    origin.entryIds = ready.entries.map { it.id }
                    val before = ready.targets.keys.associateWith { state.history.recordOf(projectId, it) }
                    val stageBefore = if (current) heldStage(project, ready.targets) else emptyMap()
                    // The entries it takes away leave their stacks as *Remove from stack* does: a live one
                    // detached, so its stack plays on from where it stood.
                    val removal = removeEntries(
                        state, project,
                        ready.targets.filter { it.key.tableName == RecordFolders.CUE_STACK_ENTRIES && it.value == null }.keys
                            .mapNotNull { key -> DaoCueStackEntry.find { DaoCueStackEntries.uuid eq key.uuid }.firstOrNull { it.stack.project.id == project.id } },
                    )
                    ProjectImporter(state).restore(project, ready.targets)
                    renumberAsWritten(project, ready.targets)
                    TransactionManager.current().flushCache()
                    verify(projectId, ready.targets)?.let { throw BlockedException(it) }
                    // Nothing below can refuse the undo, so a stack is stopped only once it surely goes.
                    stopLeavingStacks(project, ready.targets, current)
                    val stageChanged = stageBefore.filter { (cueId, content) -> DaoCue.findById(cueId)?.stageContent() != content }.keys
                    applied = Applied(ready.targets, before, removal, stageChanged)
                    Outcome.Done(ready.entries)
                }
            }
        } catch (e: BlockedException) {
            e.blocked
        } catch (e: ImportError) {
            log.info("History of project {}: an undo names a record gone since: {}", projectId, e.message)
            Outcome.Blocked(blockedRecord(projectId, e.message, pendingTargets(origin)), "Something it names has gone since")
        } catch (e: ExposedSQLException) {
            log.info("History of project {}: an undo was refused by the database", projectId, e)
            Outcome.Blocked(blockedRecord(projectId, e.message, pendingTargets(origin)), "Something else has taken its name or its place since")
        }
        val done = applied
        if (outcome is Outcome.Done && done != null) {
            // Its commit marked them unless it wrote nothing: records already where the entries put them.
            state.history.mark(projectId, outcome.entries.map { it.id }, undone = kind == WriteOrigin.Kind.UNDO)
            // What the undo sets off is part of it: no programmer step, no entry of its own.
            WriteOrigin.with(WriteOrigin(caller, window, kind = kind)) {
                KindEffects.afterRestore(state, projectId, current, done.before, done.targets, done.removal, done.stageChanged)
            }
        }
        return outcome
    }

    /**
     * Every stack whose entries [targets] touch, renumbered so each auto entry holds the number its
     * export says: one whose image carries a number (an auto number no reader could derive) keeps it,
     * and every other is derived from its neighbours, as an import derives it. The write being undone
     * renumbered by position, which a restore cannot replay.
     */
    private fun renumberAsWritten(project: DaoProject, targets: Map<RecordKey, RecordImage?>) {
        val entryKeys = targets.keys.filter { it.tableName == RecordFolders.CUE_STACK_ENTRIES }
        val stackUuids = entryKeys.flatMap { listOfNotNull(targets[it], state.history.recordOf(project.id.value, it)) }
            .mapNotNull { it.jsonObjectOrNull()?.string("cueStackUuid")?.let(::uuidOrNull) }
            .toSet() + targets.keys.filter { it.tableName == RecordFolders.CUE_STACKS && targets[it] != null }.map { it.uuid }
        for (uuid in stackUuids) {
            val stack = DaoCueStack.find { DaoCueStacks.uuid eq uuid }.firstOrNull { it.project.id == project.id } ?: continue
            val entries = cueEntriesInOrder(stack)
            val keep = entries.map { entry ->
                val key = RecordKey(RecordFolders.CUE_STACK_ENTRIES, entry.uuid)
                val image = if (key in targets) targets[key] else state.history.recordOf(project.id.value, key)
                if (image?.jsonObjectOrNull()?.containsKey("cueNumber") == true) entry.id.value else -1
            }
            renumberAutoEntries(stack, keep)
        }
    }

    /** What a committed restore did, for [KindEffects]. */
    private class Applied(
        val targets: Map<RecordKey, RecordImage?>,
        val before: Map<RecordKey, RecordImage?>,
        val removal: EntryRemoval,
        val stageChanged: Set<Int>,
    )

    /** The targets of the entries [origin] was putting back, read again from the history to name a refusal. */
    private fun pendingTargets(origin: WriteOrigin): Map<RecordKey, RecordImage?> =
        state.history.entriesById(origin.entryIds).flatMap { e -> e.records.map { it.key to it.before } }.toMap()

    /**
     * What *Update stage* would send of each held cue [targets] touch — its own record or a child's —
     * before the restore, so a cue is marked edited since GO only when that moves, as a cue PATCH marks it.
     */
    private fun heldStage(project: DaoProject, targets: Map<RecordKey, RecordImage?>): Map<Int, Any> {
        val held = state.show.cueStackManager.holds.heldCueIds()
        if (held.isEmpty()) return emptyMap()
        val uuids = targets.flatMap { (key, image) ->
            when (key.tableName) {
                RecordFolders.CUES -> listOf(key.uuid)
                in RecordFolders.CUE_CHILDREN -> listOfNotNull(image, state.history.recordOf(project.id.value, key))
                    .mapNotNull { it.jsonObjectOrNull()?.string("cueUuid")?.let(::uuidOrNull) }
                else -> emptyList()
            }
        }.toSet()
        return uuids.mapNotNull { uuid -> DaoCue.find { DaoCues.uuid eq uuid }.firstOrNull { it.project.id == project.id } }
            .filter { it.id.value in held }
            .associate { it.id.value to it.stageContent() }
    }

    /**
     * What putting [targets] back would move on stage while the show runs (D6): a Look or palette that a
     * held cue's stored layer or a programmer layer uses, which republishes. A layer the undo itself puts
     * back is not on stage: its cue is marked edited since GO instead. Nothing while no cue is held.
     */
    private fun movesOf(targets: Map<RecordKey, RecordImage?>): List<String> {
        val live = state.show.cueStackManager.holds.heldCueIds()
        if (live.isEmpty()) return emptyList()
        val sources = targets.keys.filter { it.tableName == RecordFolders.LOOKS || it.tableName == RecordFolders.PALETTES }
        val moves = ArrayList<String>()
        for (key in sources) {
            val name = RecordLabeler.name(key, targets[key] ?: state.history.recordOf(state.projectManager.currentProject.id.value, key))
            val cues = if (key.tableName == RecordFolders.LOOKS) cuesReferencingLook(state, key.uuid, live) else cuesReferencingPalette(state, key.uuid, live)
            if (cues.isNotEmpty()) {
                val names = transaction(state.database) { cues.mapNotNull { DaoCue.findById(it)?.name } }.sorted()
                moves += "Live ${if (names.size == 1) "cue" else "cues"} ${names.joinToString(", ") { "“$it”" }} ${if (names.size == 1) "uses" else "use"} $name"
            }
            if (state.show.programmerStore.layers.any { it.source.uuid == key.uuid }) moves += "The programmer uses $name"
        }
        return moves
    }

    /**
     * A stack the undo takes away is stopped, on the live project, and is no longer the project's
     * playhead on any — as a stack delete does. Inside the transaction, once the restore cannot refuse.
     */
    private fun stopLeavingStacks(project: DaoProject, targets: Map<RecordKey, RecordImage?>, current: Boolean) {
        val manager = state.show.cueStackManager
        for (key in targets.filter { it.key.tableName == RecordFolders.CUE_STACKS && it.value == null }.keys) {
            val id = state.history.recordOf(project.id.value, key)?.rowId ?: continue
            if (current) {
                manager.deactivateStack(id, state)
                manager.forgetStack(id)
            }
            if (project.activeStackId == id) project.activeStackId = null
        }
    }

    /**
     * The folders [targets] touch, exported again inside the transaction, against the cache with the
     * targets laid over it: every record as its target says, with its row id, and none other moved.
     * Null when they agree.
     */
    private fun verify(projectId: Int, targets: Map<RecordKey, RecordImage?>): Outcome.Blocked? {
        val folders = targets.keys.mapTo(HashSet()) { it.tableName }
        val sink = MapSink()
        ProjectExporter(state).exportKinds(projectId, folders, sink)
        val expected = state.history.recordsIn(projectId, folders).toMutableMap()
        for ((key, image) in targets) if (image == null) expected.remove(key) else expected[key] = image
        for (key in expected.keys + sink.images.keys) {
            val want = expected[key]
            val got = sink.images[key]
            if (want?.hash == got?.hash && (want == null || key !in targets || want.rowId == got?.rowId)) continue
            log.warn("History of project {}: an undo would leave {} as {} rather than {}", projectId, key, got?.json, want?.json)
            return Outcome.Blocked(RecordLabeler.name(key, want ?: got), "It could not be put back exactly")
        }
        return null
    }

    /** The record a refused write most likely names: one of [targets] the [message] names, or in the table it names. */
    private fun blockedRecord(projectId: Int, message: String?, targets: Map<RecordKey, RecordImage?>): String {
        val text = message.orEmpty()
        val key = targets.keys.firstOrNull { text.contains(it.uuid.toString()) }
            ?: targets.keys.firstOrNull { key -> TABLE_HINTS[key.tableName]?.let { text.contains(it) } == true }
            ?: targets.keys.firstOrNull { targets[it] != null }
            ?: return "A record"
        return RecordLabeler.name(key, targets[key] ?: state.history.recordOf(projectId, key))
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecordRestorer::class.java)

        /** A folder's table as SQLite names it in a constraint failure. */
        private val TABLE_HINTS = mapOf(
            "looks" to "looks.", "palettes" to "palettes.", "cueSlots" to "cue_slots.", "buskPages" to "busk_pages.",
            "cueStackEntries" to "cue_stack_entries.", "promptBookAnchors" to "prompt_book_anchors.", "project" to "projects.",
            "cueStacks" to "cue_stacks.",
        )
    }
}

private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.content

private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
