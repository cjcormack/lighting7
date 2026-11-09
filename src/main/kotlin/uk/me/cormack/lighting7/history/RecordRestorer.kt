package uk.me.cormack.lighting7.history

import kotlinx.coroutines.withContext
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
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.routes.EntryRemoval
import uk.me.cormack.lighting7.routes.removeEntries
import uk.me.cormack.lighting7.routes.stageContent
import uk.me.cormack.lighting7.sync.ImportError
import uk.me.cormack.lighting7.sync.RecordFolders
import uk.me.cormack.lighting7.sync.jsonObjectOrNull
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.venue.VenueRigChecks
import uk.me.cormack.lighting7.routes.cuesReferencingLook
import uk.me.cormack.lighting7.routes.cuesReferencingPalette
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.MapSink
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.canonicalDecode
import uk.me.cormack.lighting7.sync.dto.CueStackJson
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

        /** It puts back a script or an FX definition, which compiles code, for a caller remote scripts are off for. */
        data object ScriptsRefused : Outcome
        data class Done(val entries: List<HistoryEntry>) : Outcome
    }

    /** [scriptsAllowed] is whether the caller may author a script (`ApplicationCall.scriptsAllowed`). */
    suspend fun undo(
        projectId: Int, entryId: String?, caller: HistorySource, window: String?, backToHere: Boolean, confirm: Boolean,
        scriptsAllowed: Boolean = true,
    ): Outcome =
        apply(projectId, caller, window, WriteOrigin.Kind.UNDO, confirm, scriptsAllowed) { state.history.planUndo(projectId, entryId, caller.key, backToHere) }

    suspend fun redo(
        projectId: Int, entryId: String?, caller: HistorySource, window: String?, confirm: Boolean, scriptsAllowed: Boolean = true,
    ): Outcome =
        apply(projectId, caller, window, WriteOrigin.Kind.REDO, confirm, scriptsAllowed) { state.history.planRedo(projectId, entryId, caller.key) }

    /**
     * Records brought back from a cloud-sync snapshot (sync-restore plan session 3, D10): [plan] answers
     * them, inside the transaction, as the snapshot holds them, and they are upserted with fresh row ids
     * (an image read from git has none of its own) under [origin], a tracked write the journal makes one
     * show entry of, which ⌘Z takes back. Refused as an undo is: a name taken since, a key the venue
     * holds, a record that would not land exactly. [plan] may throw to refuse; nothing is then written.
     * Done answers the entry the journal made.
     */
    suspend fun bringBack(
        projectId: Int,
        origin: WriteOrigin,
        scriptsAllowed: Boolean,
        plan: () -> Map<RecordKey, RecordImage>,
    ): Outcome {
        val current = state.projectManager.currentProjectOrNull?.id?.value == projectId
        var applied: Applied? = null
        var attempted: Map<RecordKey, RecordImage?> = emptyMap()
        var playheadCleared = false
        val bands = HashMap<Int, Boolean>()
        val outcome = try {
            WriteOrigin.with(origin) {
                transaction(state.database) {
                    val project = DaoProject.findById(projectId) ?: return@transaction Outcome.Empty(false)
                    val targets = plan()
                    if (targets.isEmpty()) return@transaction Outcome.Empty(false)
                    attempted = targets
                    if (!scriptsAllowed && targets.keys.any { it.tableName in SCRIPT_FOLDERS }) return@transaction Outcome.ScriptsRefused
                    val folders = targets.keys.mapTo(HashSet()) { it.tableName }
                    val before = exportFolders(projectId, folders)
                    val stageBefore = if (current) heldStage(project, targets) else emptyMap()
                    val clashesBefore = venueClashes(project, targets)
                    ProjectImporter(state).restore(project, targets, keepRowIds = false)
                    TransactionManager.current().flushCache()
                    newClash(project, targets, clashesBefore)?.let { throw BlockedException(it) }
                    val after = exportFolders(projectId, folders)
                    verifyBroughtBack(before, targets, after)?.let { throw BlockedException(it) }
                    val playhead = project.activeStackId
                    for (key in targets.keys.filter { it.tableName == RecordFolders.CUE_STACKS }) {
                        val stack = DaoCueStack.find { DaoCueStacks.uuid eq key.uuid }.firstOrNull { it.project.id == project.id } ?: continue
                        bands[stack.id.value] = stack.inRunningOrder
                        if (project.activeStackId == stack.id.value && !stack.inRunningOrder) project.activeStackId = null
                    }
                    playheadCleared = playhead != null && project.activeStackId == null
                    val stageChanged = stageBefore.filter { (cueId, content) -> DaoCue.findById(cueId)?.stageContent() != content }.keys
                    applied = Applied(
                        targets = targets.keys.associateWith { after[it] },
                        before = targets.keys.associateWith { before[it] },
                        removal = EntryRemoval.NONE,
                        stageChanged = stageChanged,
                    )
                    Outcome.Done(emptyList())
                }
            }
        } catch (e: Exception) {
            refused(projectId, e) { attempted }
        }
        val done = applied
        if (outcome !is Outcome.Done || done == null) return outcome
        announceStacks(projectId, current, playheadCleared, bands)
        runEffects(projectId, current, WriteOrigin(origin.source, origin.window, kind = WriteOrigin.Kind.UNDO), done)
        return Outcome.Done(listOfNotNull(state.history.entries(projectId).lastOrNull { origin.requestId in it.requestIds }))
    }

    /**
     * What putting [targets] back would move on the live stage while a cue is held, as an undo's confirm
     * names it ([movesOf]); empty off the live project.
     */
    fun movesFor(projectId: Int, targets: Map<RecordKey, RecordImage?>): List<String> =
        if (state.projectManager.currentProjectOrNull?.id?.value == projectId) movesOf(projectId, targets) else emptyList()

    private fun exportFolders(projectId: Int, folders: Set<String>): Map<RecordKey, RecordImage> =
        MapSink().also { ProjectExporter(state).exportKinds(projectId, folders, it) }.images

    /**
     * The folders a bring-back wrote, exported again, against [before] with [targets] laid over it:
     * every record as the snapshot holds it and none other moved. Row ids are new, so only hashes count.
     */
    private fun verifyBroughtBack(
        before: Map<RecordKey, RecordImage>,
        targets: Map<RecordKey, RecordImage>,
        after: Map<RecordKey, RecordImage>,
    ): Outcome.Blocked? {
        val expected = before + targets
        for (key in expected.keys + after.keys) {
            val want = expected[key]
            val got = after[key]
            if (want?.hash == got?.hash) continue
            log.warn("Bringing back {} would leave it as {} rather than {}", key, got?.json, want?.json)
            return Outcome.Blocked(RecordLabeler.name(key, want ?: got), "It could not be put back exactly")
        }
        return null
    }

    /** Thrown inside the transaction to roll it back with a [Outcome.Blocked]. */
    private class BlockedException(val blocked: Outcome.Blocked) : RuntimeException(blocked.reason)

    private suspend fun apply(
        projectId: Int,
        caller: HistorySource,
        window: String?,
        kind: WriteOrigin.Kind,
        confirm: Boolean,
        scriptsAllowed: Boolean,
        plan: () -> HistoryService.Plan,
    ): Outcome {
        val current = state.projectManager.currentProjectOrNull?.id?.value == projectId
        val origin = WriteOrigin(caller, window, kind = kind)
        var applied: Applied? = null
        var playheadCleared = false
        // Each restored stack's running-order setting, which moves a live one to that band's top (D5).
        val bands = HashMap<Int, Boolean>()
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
                    // Putting a script or an FX definition back authors code, which the remote gate holds as its own routes do.
                    if (!scriptsAllowed && ready.targets.keys.any { it.tableName in SCRIPT_FOLDERS }) return@transaction Outcome.ScriptsRefused
                    if (current && !confirm) {
                        val moves = movesOf(projectId, ready.targets)
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
                    val clashesBefore = venueClashes(project, ready.targets)
                    ProjectImporter(state).restore(project, ready.targets)
                    TransactionManager.current().flushCache()
                    newClash(project, ready.targets, clashesBefore)?.let { throw BlockedException(it) }
                    verify(projectId, ready.targets)?.let { throw BlockedException(it) }
                    // Nothing below can refuse the undo, so a stack is stopped only once it surely goes.
                    playheadCleared = stopLeavingStacks(project, ready.targets, current, bands)
                    val stageChanged = stageBefore.filter { (cueId, content) -> DaoCue.findById(cueId)?.stageContent() != content }.keys
                    applied = Applied(ready.targets, before, removal, stageChanged)
                    Outcome.Done(ready.entries)
                }
            }
        } catch (e: Exception) {
            refused(projectId, e) { pendingTargets(origin) }
        }
        val done = applied
        if (outcome is Outcome.Done) announceStacks(projectId, current, playheadCleared, bands)
        if (outcome is Outcome.Done && done != null) {
            // Its commit marked them unless it wrote nothing: records already where the entries put them.
            state.history.mark(projectId, outcome.entries.map { it.id }, undone = kind == WriteOrigin.Kind.UNDO)
            runEffects(projectId, current, WriteOrigin(caller, window, kind = kind), done)
        }
        return outcome
    }

    /** A refusal the transaction threw, as an [Outcome.Blocked]; anything else is rethrown. */
    private fun refused(projectId: Int, e: Exception, targets: () -> Map<RecordKey, RecordImage?>): Outcome.Blocked = when (e) {
        is BlockedException -> e.blocked
        is ImportError -> {
            log.info("History of project {}: putting records back names one gone since: {}", projectId, e.message)
            Outcome.Blocked(blockedRecord(projectId, e.message, targets()), "Something it names has gone since")
        }
        is ExposedSQLException -> {
            log.info("History of project {}: putting records back was refused by the database", projectId, e)
            Outcome.Blocked(blockedRecord(projectId, e.message, targets()), "Something else has taken its name or its place since")
        }
        else -> throw e
    }

    /** After a committed restore: the playhead it cleared, and each restored stack's band. */
    private fun announceStacks(projectId: Int, current: Boolean, playheadCleared: Boolean, bands: Map<Int, Boolean>) {
        // Every window follows the playhead from `showChanged`, which the restore's own effects do not send.
        if (playheadCleared && current) state.show.fixtures.showChanged(projectId, null, null)
        if (current) for ((stackId, inRunningOrder) in bands) state.show.cueStackManager.onBandChanged(stackId, inRunningOrder)
    }

    /** What the restored records' writes would set off, under [effectsOrigin]: part of it, no programmer step, no entry. */
    private suspend fun runEffects(projectId: Int, current: Boolean, effectsOrigin: WriteOrigin, done: Applied) {
        withContext(effectsOrigin) {
            KindEffects.afterRestore(state, projectId, current, done.before, done.targets, done.removal, done.stageChanged)
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
     * The clashes with the venue in the rig of a show on one (`VenueRigChecks.clashesOf`), read when
     * [targets] touch a record whose key or name the venue's share, or a hide; null when they cannot.
     */
    private fun venueClashes(project: DaoProject, targets: Map<RecordKey, RecordImage?>): Set<VenueRigChecks.Clash>? {
        if (targets.keys.none { it.tableName in IDENTITY_FOLDERS }) return null
        val rig = RigScope.of(project)
        val venue = rig.venue ?: return null
        return VenueRigChecks.clashesOf(rig, venue.snapshotId, "v${venue.version}").toSet()
    }

    /**
     * A key, head number or name the show's own records now share with its venue's that they did not
     * before the restore — one a venue record took while the show's was gone, or a hide taken away from
     * under one the show reused (keys and names are one namespace). Null when there is none.
     */
    private fun newClash(project: DaoProject, targets: Map<RecordKey, RecordImage?>, before: Set<VenueRigChecks.Clash>?): Outcome.Blocked? {
        if (before == null) return null
        val after = venueClashes(project, targets).orEmpty()
        val clash = (after - before).firstOrNull() ?: return null
        val record = targets.keys.firstOrNull { it.uuid.toString() == clash.mine.uuid }?.let { RecordLabeler.name(it, targets[it]) }
            ?: "“${clash.mine.name}”"
        return Outcome.Blocked(record, "It would clash with the venue: ${clash.detail}")
    }

    /**
     * What putting [targets] back would move on stage while the show runs (D6): a change to the rig,
     * which reloads the fixtures, or to the parks, which moves output; a Look or palette that a held
     * cue's stored layer or a programmer layer uses, which republishes. A layer the undo itself puts back
     * is not on stage: its cue is marked edited since GO instead. Nothing while no cue is held.
     */
    private fun movesOf(projectId: Int, targets: Map<RecordKey, RecordImage?>): List<String> {
        val live = state.show.cueStackManager.holds.heldCueIds()
        if (live.isEmpty()) return emptyList()
        val moves = ArrayList<String>()
        fun named(folders: Set<String>): String? = targets.keys.filter { it.tableName in folders }
            .map { RecordLabeler.name(it, targets[it] ?: state.history.recordOf(projectId, it)) }
            .takeIf { it.isNotEmpty() }
            ?.let { names -> if (names.size > 3) names.take(3).joinToString(", ") + " and ${names.size - 3} more" else names.joinToString(", ") }
        val rebuilding = targets.keys.filter { it.tableName in RecordFolders.RIG }
            .filter { KindEffects.rigReach(it, state.history.recordOf(projectId, it), targets[it]) == KindEffects.RigReach.REBUILD }
        if (rebuilding.isNotEmpty()) {
            val names = rebuilding.map { RecordLabeler.name(it, targets[it] ?: state.history.recordOf(projectId, it)) }
            moves += "The fixtures reload: " + if (names.size > 3) names.take(3).joinToString(", ") + " and ${names.size - 3} more" else names.joinToString(", ")
        }
        named(RecordFolders.PARKS)?.let { moves += "Parked channels change: $it" }
        val sources = targets.keys.filter { it.tableName == RecordFolders.LOOKS || it.tableName == RecordFolders.PALETTES }
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
     * playhead on any — as a stack delete does. One it leaves out of the running order (busk-stack plan
     * D4) is no longer the playhead either, and runs on. Every stack the undo keeps has its setting put in
     * [bands], for a running one to take its band's top once the undo is done. Inside the transaction,
     * once the restore cannot refuse.
     */
    private fun stopLeavingStacks(
        project: DaoProject,
        targets: Map<RecordKey, RecordImage?>,
        current: Boolean,
        bands: MutableMap<Int, Boolean>,
    ): Boolean {
        val manager = state.show.cueStackManager
        val before = project.activeStackId
        for ((key, image) in targets.filter { it.key.tableName == RecordFolders.CUE_STACKS }) {
            val id = state.history.recordOf(project.id.value, key)?.rowId ?: continue
            if (image == null) {
                if (current) {
                    manager.deactivateStack(id, state)
                    manager.forgetStack(id)
                }
                if (project.activeStackId == id) project.activeStackId = null
            } else {
                val inRunningOrder = canonicalDecode(CueStackJson.serializer(), image.json).inRunningOrder
                bands[id] = inRunningOrder
                if (project.activeStackId == id && !inRunningOrder) project.activeStackId = null
            }
        }
        return before != null && project.activeStackId == null
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
            "cueStacks" to "cue_stacks.", "fixturePatches" to "fixture_patches.", "fixtureGroups" to "fixture_groups.",
            "universeConfigs" to "universe_configs.", "riggings" to "riggings.", "stageRegions" to "stage_regions.",
            "stageElements" to "stage_elements.", "stageViewpoints" to "stage_viewpoints.", "scripts" to "scripts.",
            "fxDefinitions" to "fx_definitions.", "speedMasters" to "speed_masters.", "parkedChannels" to "parked_channels.",
            "showParkReleases" to "show_park_releases.", "controlSurfaceBindings" to "control_surface_bindings.", "surfaceJobs" to "surface_jobs.",
            "promptBooks" to "prompt_books.", "promptBookAnnotations" to "prompt_book_annotations.", "showHides" to "show_hides.",
            "showOverrides" to "show_overrides.", "showColours" to "show_colours.", "showGroupOrders" to "show_group_orders.",
        )

        private val SCRIPT_FOLDERS = setOf(RecordFolders.SCRIPTS, RecordFolders.FX_DEFINITIONS)

        /** The folders whose records hold a key or name the venue's share, and the hides that let a show reuse one. */
        private val IDENTITY_FOLDERS = with(RecordFolders) {
            setOf(FIXTURE_PATCHES, FIXTURE_GROUPS, RIGGINGS, STAGE_ELEMENTS, STAGE_REGIONS, STAGE_VIEWPOINTS, SHOW_HIDES)
        }
    }
}

private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.content

private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
