package uk.me.cormack.lighting7.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.fx.stackEntryPriorities
import uk.me.cormack.lighting7.routes.CODE_CUE_IN_USE
import uk.me.cormack.lighting7.routes.CopyCuesFromRequest
import uk.me.cormack.lighting7.routes.CopyCuesOutcome
import uk.me.cormack.lighting7.routes.CueCopier
import uk.me.cormack.lighting7.routes.DeleteCuesOutcome
import uk.me.cormack.lighting7.routes.DeleteCuesRequest
import uk.me.cormack.lighting7.routes.afterCuesDeleted
import uk.me.cormack.lighting7.routes.announceCuesCopied
import uk.me.cormack.lighting7.routes.afterAttach
import uk.me.cormack.lighting7.routes.announceCueStacksDeleted
import uk.me.cormack.lighting7.routes.copyCuesFrom
import uk.me.cormack.lighting7.routes.cueInUseMessage
import uk.me.cormack.lighting7.routes.deleteCuesChecked
import uk.me.cormack.lighting7.routes.cueEntriesInOrder
import uk.me.cormack.lighting7.routes.deleteCueStackRows
import uk.me.cormack.lighting7.routes.listedProjects
import uk.me.cormack.lighting7.routes.sceneImageStoresOf
import uk.me.cormack.lighting7.routes.showsOnVenue
import uk.me.cormack.lighting7.routes.stageElementsOf
import uk.me.cormack.lighting7.routes.venueDto
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.SeatingPresets
import uk.me.cormack.lighting7.venue.ShowHides
import uk.me.cormack.lighting7.venue.UpdateAnswer
import uk.me.cormack.lighting7.venue.UpdateDecisionAnswer
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import uk.me.cormack.lighting7.venue.VenueVersions
import java.util.UUID

/**
 * The venue and cross-project tools (`list_venues`, `extract_venue`, `attach_to_venue`, `publish_venue`,
 * `copy_to_project`, `copy_cues`, `delete_cue_stacks`, `delete_cues`): what a model needs to split a hall from the show built in
 * it, and to prepare one project while another runs. Split from [SetupTools] for length; its rules
 * hold — validate everything, answer every problem at once, write nothing unless all of it passes.
 *
 * Extract and publish are the venue plan's own operations ([uk.me.cormack.lighting7.venue.VenueExtract],
 * [VenueVersions.publish]) called as their REST routes call them; the copy is this file's.
 */
internal class VenueSetupTools(private val state: State) {

    // ─── list_venues ────────────────────────────────────────────────────

    fun listVenues(): ToolExecutionResult {
        val rows = transaction(state.database) {
            listedProjects().orderBy(DaoProjects.name to SortOrder.ASC).filter { it.kind == ProjectKind.VENUE }.map { venue ->
                val installed = VenueVersions.versionsOf(venue.id.value).firstOrNull()
                val rig = RigScope.of(installed?.snapshotProject?.id ?: venue.id)
                val elements = rig.stageElements().toList()
                val patches = rig.patches().count().toInt()
                Triple(venue, installed, buildJsonObject {
                    put("id", venue.id.value)
                    put("name", venue.name)
                    if (installed != null) {
                        put("installedVersion", installed.number)
                        put("installedNote", installed.note)
                    } else {
                        put("installedVersion", JsonNull)
                    }
                    putJsonObject(if (installed != null) "atInstalledVersion" else "draft") {
                        put("elements", elements.size)
                        put("viewpoints", rig.stageViewpoints().count().toInt())
                        put("patches", patches)
                        put("universes", rig.universeConfigs().count().toInt())
                        put("riggings", rig.riggings().count().toInt())
                        put("groups", rig.groups().count().toInt())
                        putJsonArray("fixedSeating") {
                            elements.filter { it.kind == StageElementKind.SEATING.name && VenueLayer.isFixedSeating(it.params) }.forEach { add(it.name) }
                        }
                    }
                    put("hasRig", patches > 0)
                    if (installed != null) {
                        putJsonArray("seatingPresets") { SeatingPresets.list(venue).presets.forEach { add(it.name) } }
                    }
                    putJsonArray("shows") {
                        showsOnVenue(venue).forEach { show ->
                            val pin = show.venueDto(state)
                            addJsonObject {
                                put("id", show.id.value)
                                put("name", show.name)
                                pin?.let {
                                    put("version", it.version)
                                    if (it.behind != uk.me.cormack.lighting7.venue.VenueBehind.NONE) put("behind", it.behind.name)
                                }
                            }
                        }
                    }
                })
            }
        }
        // The draft's diff exports the venue, so it is read outside the listing's transaction.
        val venues = rows.map { (venue, installed, body) ->
            if (installed == null) body
            else JsonObject(body + ("unpublishedChanges" to JsonPrimitive(state.venueVersions.draftChanges(venue.id.value))))
        }
        return success("${venues.size} venue(s)", buildJsonObject {
            put("venues", JsonArray(venues))
            put(
                "note",
                "A show sits on a venue at a published version and reads its rig and scene from it. Edit a venue with the " +
                    "setup tools and projectId = its id (its draft), then publish_venue; each show moves to the new version " +
                    "when the operator updates it on the desk's Projects page.",
            )
        })
    }

    // ─── extract_venue ──────────────────────────────────────────────────

    suspend fun extractVenue(input: JsonObject, caller: String?): ToolExecutionResult {
        val problems = unknownFields(input, listOf("fromProjectId", "name", "include", "dryRun", "reloadLiveShow"), "extract_venue")
            .toMutableList()
        val from = input.int("fromProjectId")
        if (from == null) problems += "fromProjectId is required: the show whose room and rig become the venue"
        val name = input.string("name")?.trim().orEmpty()
        if (name.isEmpty()) problems += "name is required: the venue's name, unique on the desk"
        val include = when (val raw = input["include"]) {
            null, is JsonNull -> ExtractKind.entries.toSet()
            is JsonArray -> raw.mapNotNull { e ->
                val text = (e as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
                ExtractKind.entries.firstOrNull { it.name == text }
                    ?: run { problems += "include: '$text' is not one of ${ExtractKind.entries.joinToString()}"; null }
            }.toSet()
            else -> { problems += "include must be an array"; emptySet() }
        }
        val dryRun = input.bool("dryRun") == true
        if (problems.isNotEmpty()) return rejected(problems)

        val current = state.projectManager.currentProject.id.value
        if (from == current && !dryRun && input.bool("reloadLiveShow") != true) {
            return failure(
                "Project $from is the current project: extracting moves its rig into the venue and reloads it: the " +
                    "rig holds its levels and each live cue, the programmer and hand-run FX come back, but every FX " +
                    "restarts and a cue's one-shot events do not fire again. Run it with dryRun first; send reloadLiveShow: true " +
                    "only when the operator has said the desk is not in use for a show.",
            )
        }
        val result = state.venueExtract.extract(from!!, name, include, dryRun, publishedBy = caller)
        if (!result.dryRun) {
            if (from == current) state.projectManager.switchProject(from, reload = true)
            state.show.fixtures.projectDetailsChanged(from)
            result.venueId?.let { state.show.fixtures.projectDetailsChanged(it) }
        }
        val body = Json.encodeToJsonElement(result) as JsonObject
        return success(
            if (result.dryRun) "Checked: extracting '$name' would move ${result.moved.elements.size} element(s) and ${result.moved.patches.size} patch(es)"
            else "Extracted venue '$name' (id=${result.venueId}) as v1; project $from now sits on it",
            JsonObject(
                body + ("note" to JsonPrimitive(
                    "The show keeps its own set (SET-layer elements), cues, Looks, palettes and prompt book, and now reads the room and rig " +
                        "from the venue at v1, so nothing it draws or outputs changes. Preset seating stays the show's own copy; a new show " +
                        "on the venue copies one with create_project's seatingPreset. New shows: create_project with venueId.",
                )),
            ),
        )
    }

    // ─── attach_to_venue ────────────────────────────────────────────────

    suspend fun attachToVenue(input: JsonObject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("projectId", "venueId", "version", "decisions", "dryRun", "reloadLiveShow"), "attach_to_venue")
            .toMutableList()
        val projectId = input.int("projectId")
        if (projectId == null) problems += "projectId is required: the standalone show to attach (list_projects)"
        val venueId = input.int("venueId")
        if (venueId == null) problems += "venueId is required: the venue to attach it to (list_venues)"
        val version = input.int("version")
        if (version == null && input["version"] != null && input["version"] !is JsonNull) problems += "version must be an integer: the version to attach to"
        val decisions = when (val raw = input["decisions"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> raw.mapIndexedNotNull { i, e ->
                runCatching { Json.decodeFromJsonElement(UpdateDecisionAnswer.serializer(), e) }.getOrElse {
                    problems += "decisions[$i] must be {id, answer, startChannel?}, answer one of ${UpdateAnswer.entries.joinToString()}"
                    null
                }
            }
            else -> { problems += "decisions must be an array"; emptyList() }
        }
        val dryRun = input.bool("dryRun") == true
        if (problems.isNotEmpty()) return rejected(problems)

        val current = state.projectManager.currentProject.id.value
        if (projectId == current && !dryRun && input.bool("reloadLiveShow") != true) {
            return failure(
                "Project $projectId is the current project: attaching reloads it onto the venue: the rig holds its levels and " +
                    "each live cue, the programmer and hand-run FX come back, but every FX restarts, and the programmer's values " +
                    "and FX on a fixture the attach renames do not come back (the review's notes name them). Run it with dryRun " +
                    "first; send reloadLiveShow: true only when the operator has said the desk is not in use for a show.",
            )
        }
        val result = state.venueAttach.attach(projectId!!, venueId!!, version, decisions, dryRun)
        val review = result.review
        if (!review.dryRun) afterAttach(state, result)
        val summary = when {
            review.dryRun && review.blockers.isNotEmpty() -> "Checked: attaching to ${review.venueName} v${review.to} is blocked (${review.blockers.size})"
            review.dryRun -> "Checked: attaching to ${review.venueName} v${review.to} inherits ${review.inherited.values.sum()} record(s), " +
                "with ${review.decisions.size} decision(s) and ${review.overlaps.count { !it.decided }} overlap(s) to answer"
            else -> "Attached project $projectId to ${review.venueName} v${review.to}"
        }
        return success(summary, Json.encodeToJsonElement(review) as JsonObject)
    }

    // ─── publish_venue ──────────────────────────────────────────────────

    suspend fun publishVenue(input: JsonObject, caller: String?): ToolExecutionResult {
        val problems = unknownFields(input, listOf("venueId", "note"), "publish_venue").toMutableList()
        val venueId = input.int("venueId")
        if (venueId == null) problems += "venueId is required (list_venues)"
        val note = input.string("note")?.trim().orEmpty()
        if (note.isEmpty()) problems += "note is required: what changed, as the version's history will read"
        if (problems.isNotEmpty()) return rejected(problems)
        val version = state.venueVersions.publish(venueId!!, note, caller)
        state.show.fixtures.projectDetailsChanged(venueId)
        // A live show on this venue is now behind it: the hold service lets a running show run on.
        withContext(Dispatchers.IO) { state.venueHold.refreshCurrent() }
        val shows = transaction(state.database) { showsOnVenue(DaoProject[venueId]).map { it.name } }
        return success(
            "Published v${version.number}",
            buildJsonObject {
                put("venueId", venueId)
                put("version", version.number)
                put("note", version.note)
                putJsonArray("shows") { shows.forEach { add(it) } }
                put(
                    "next",
                    "Each show stays on the version it was built against until the operator updates it to v${version.number} on the " +
                        "desk's Projects page, which reviews what changes for that show first.",
                )
            },
        )
    }

    // ─── delete_cue_stacks ──────────────────────────────────────────────

    fun deleteCueStacks(input: JsonObject): ToolExecutionResult {
        val target = state.toolProject(input)
        val problems = unknownFields(input, listOf("projectId", "stackIds", "dryRun"), "delete_cue_stacks").toMutableList()
        val ids = intList(input, "stackIds", problems)
        if (ids.isEmpty() && problems.isEmpty()) problems += "stackIds is required: the stacks to delete"
        val dryRun = input.bool("dryRun") == true
        val manager = state.show.cueStackManager

        data class Found(val id: Int, val name: String, val cues: Int)
        val found = transaction(state.database) {
            ids.distinct().mapNotNull { id ->
                val stack = DaoCueStack.findById(id)?.takeIf { it.project.id == target.project.id && it.type == CueStackType.STACK.name }
                when {
                    stack == null -> { problems += "no cue stack $id in project ${target.project.id.value}"; null }
                    target.isCurrent && manager.isStackActive(id) -> {
                        problems += "cue stack $id ('${stack.name}') is running: deactivate_cue_stack it first"
                        null
                    }
                    else -> Found(id, stack.name, cueEntriesInOrder(stack).size)
                }
            }
        }
        if (problems.isNotEmpty()) return rejected(problems)
        val loose = if (dryRun) {
            transaction(state.database) { cuesLeftLoose(found.map { it.id }) }
        } else {
            val deleted = transaction(state.database) {
                val project = DaoProject[target.project.id]
                found.map { deleteCueStackRows(state, project, DaoCueStack[it.id]) }
            }
            for (d in deleted) announceCueStacksDeleted(state, d)
            deleted.flatMap { it.looseCueIds }.distinct().size
        }
        return success(
            "${if (dryRun) "Would delete" else "Deleted"} ${found.size} cue stack(s); their cues stay, " +
                "$loose ${if (dryRun) "would be" else "now"} in no stack",
            buildJsonObject {
                put("dryRun", dryRun)
                put("cuesLeftLoose", loose)
                putJsonArray("stacks") {
                    found.forEach { addJsonObject { put("id", it.id); put("name", it.name); put("cues", it.cues) } }
                }
            },
        )
    }

    /** How many cues placed in [stackIds] have no entry in any other stack. Inside a transaction. */
    private fun cuesLeftLoose(stackIds: List<Int>): Int {
        val entries = stackIds.flatMap { entriesOfStack(EntityID(it, DaoCueStacks)) }
        val cueIds = entries.mapNotNull { it.cueId }.toSet()
        val elsewhere = cueIds.filter { cueId ->
            DaoCueStackEntry.find { DaoCueStackEntries.cue eq cueId }.any { it.stackId !in stackIds }
        }
        return cueIds.size - elsewhere.size
    }

    // ─── delete_cues ────────────────────────────────────────────────────

    /** `POST /cues/delete` as a tool (cue model plan D8): the same guard, through [deleteCuesChecked]. */
    fun deleteCues(input: JsonObject): ToolExecutionResult {
        val target = state.toolProject(input)
        val problems = unknownFields(input, listOf("projectId", "cueIds", "force", "dryRun"), "delete_cues").toMutableList()
        val ids = intList(input, "cueIds", problems)
        if (ids.isEmpty() && problems.isEmpty()) problems += "cueIds is required: the cues to delete"
        if (problems.isNotEmpty()) return rejected(problems)
        val request = DeleteCuesRequest(ids, force = input.bool("force") == true, dryRun = input.bool("dryRun") == true)
        val projectId = target.project.id.value
        return when (val outcome = transaction(state.database) { deleteCuesChecked(state, DaoProject[target.project.id], request) }) {
            is DeleteCuesOutcome.NotFound -> rejected(outcome.missing.map { "no cue $it in project $projectId" })
            is DeleteCuesOutcome.InUse -> ToolExecutionResult(
                success = false,
                description = "Nothing was deleted: ${outcome.uses.size} cue(s) in use",
                result = buildJsonObject {
                    put("error", "Nothing was deleted. ${cueInUseMessage(outcome.uses)}")
                    put("code", CODE_CUE_IN_USE)
                    put("uses", answerJson.encodeToJsonElement(outcome.uses))
                }.toString(),
            )
            is DeleteCuesOutcome.Done -> {
                afterCuesDeleted(state, outcome.deletion)
                val uses = outcome.response.uses
                success(
                    if (request.dryRun) {
                        "Would delete ${ids.size} cue(s); ${uses.size} of them in use" + if (uses.isEmpty()) "" else " — they need force"
                    } else {
                        "Deleted ${outcome.response.deleted.size} cue(s)" + if (uses.isEmpty()) "" else ", with what named ${uses.size} of them"
                    },
                    buildJsonObject {
                        put("dryRun", request.dryRun)
                        putJsonArray("deleted") { outcome.response.deleted.forEach { add(it) } }
                        put("uses", answerJson.encodeToJsonElement(uses))
                    },
                )
            }
        }
    }

    // ─── copy_cues ──────────────────────────────────────────────────────

    /**
     * `POST /cues/copy-from` as a tool (cue model plan §3.8), on [copyCuesFrom] and its [CueCopier]. The
     * target is the tool's project, current or not; the holds and the busk pads are the live show's, so
     * only a copy into the current project restamps the one and announces the other.
     */
    fun copyCues(input: JsonObject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("projectId", "fromProjectId", "cueIds", "stackId", "dryRun"), "copy_cues").toMutableList()
        if (input.int("fromProjectId") == null) problems += "fromProjectId is required: a project id from list_projects"
        val ids = intList(input, "cueIds", problems)
        if (ids.isEmpty() && "cueIds" !in input) problems += "cueIds is required: the source's cues to copy"
        val stackId = input.int("stackId")
        if (input["stackId"].let { it != null && it !is JsonNull } && stackId == null) problems += "stackId must be an integer"
        if (problems.isNotEmpty()) return rejected(problems)
        val from = state.toolProject(input, "fromProjectId")
        val target = state.toolProject(input)
        val dryRun = input.bool("dryRun") == true

        var priorities = emptyMap<Int, Int>()
        val outcome = transaction(state.database) {
            val request = CopyCuesFromRequest(from.project.id.value, ids, stackId, dryRun = dryRun)
            copyCuesFrom(state, DaoProject[target.project.id], request).also { o ->
                if (o is CopyCuesOutcome.Copied && !dryRun && target.isCurrent) {
                    o.response.stackId?.let { priorities = stackEntryPriorities(DaoCueStack[it]) }
                }
                if (o !is CopyCuesOutcome.Copied || dryRun) TransactionManager.current().rollback()
            }
        }
        val response = when (outcome) {
            is CopyCuesOutcome.Refused -> return rejected(listOf(outcome.message))
            is CopyCuesOutcome.Copied -> outcome.response
        }
        if (!dryRun) announceCuesCopied(state, response, priorities, live = target.isCurrent)
        return success(
            "${if (dryRun) "Checked" else "Copied"} ${response.copied.size} cue(s) into project ${target.project.id.value}" +
                (response.stackId?.let { ", at the end of stack $it" } ?: ", loose") +
                if (response.unresolved.isEmpty()) "" else "; ${response.unresolved.size} reference(s) did not resolve",
            JsonObject(
                answerJson.encodeToJsonElement(response) as JsonObject +
                    ("note" to JsonPrimitive(
                        if (dryRun) "Nothing was written: the ids in copied name nothing. Run it again without dryRun to copy."
                        else "Copies are new cues with new ids; a copied cue is not shared with its source.",
                    )),
            ),
        )
    }

    // ─── copy_to_project ────────────────────────────────────────────────

    /** What a copy did beside the cue copier's own report: the elements, stacks and images. */
    private class CopyTally {
        val elements = mutableListOf<String>()
        val stacks = mutableListOf<Pair<String, Int>>()
        var images = 0
    }

    fun copyToProject(input: JsonObject): ToolExecutionResult {
        val problems = unknownFields(
            input, listOf("fromProjectId", "toProjectId", "sceneElements", "cueStacks", "looks", "dryRun"), "copy_to_project",
        ).toMutableList()
        // Both named outright: an absent or null id would otherwise mean the live project.
        if (input.int("fromProjectId") == null) problems += "fromProjectId is required: a project id from list_projects"
        if (input.int("toProjectId") == null) problems += "toProjectId is required: a project id from list_projects"
        if (problems.isNotEmpty()) return rejected(problems)
        val from = state.toolProject(input, "fromProjectId")
        val to = state.toolProject(input, "toProjectId")
        val elementNames = input.stringList("sceneElements")
        val stackIds = intList(input, "cueStacks", problems)
        val lookIds = intList(input, "looks", problems)
        val dryRun = input.bool("dryRun") == true
        if (from.project.id == to.project.id) problems += "fromProjectId and toProjectId are the same project"
        if (elementNames.isEmpty() && stackIds.isEmpty() && lookIds.isEmpty()) {
            problems += "nothing to copy: name sceneElements, cueStacks or looks"
        }
        if (problems.isNotEmpty()) return rejected(problems)

        val tally = CopyTally()
        // A dry run is the same run rolled back. Images are files, so only a real run copies them; one
        // whose transaction then fails leaves images nothing names, which the store's prune removes.
        val report = transaction(state.database) {
            val copier = CueCopier(DaoProject[from.project.id], DaoProject[to.project.id])
            copy(copier, elementNames, stackIds, lookIds, dryRun, tally, problems)
            if (problems.isNotEmpty() || dryRun) TransactionManager.current().rollback()
            copier.report
        }
        if (problems.isNotEmpty()) return rejected(problems)

        if (!dryRun) {
            val fixtures = state.show.fixtures
            if (tally.elements.isNotEmpty()) fixtures.stageElementListChanged()
            if (report.looks.isNotEmpty()) fixtures.lookListChanged()
            if (report.palettes.isNotEmpty()) fixtures.paletteListChanged()
            if (tally.stacks.isNotEmpty()) {
                fixtures.cueStackListChanged()
                fixtures.cueListChanged()
            }
        }
        return success(
            "${if (dryRun) "Checked" else "Copied"}: ${tally.elements.size} element(s), ${report.looks.size} Look(s), " +
                "${report.palettes.size} palette(s), ${tally.stacks.size} stack(s) into project ${to.project.id.value}" +
                if (report.unresolved.isEmpty()) "" else "; ${report.unresolved.size} reference(s) did not resolve",
            buildJsonObject {
                put("dryRun", dryRun)
                put("toProjectId", to.project.id.value)
                putJsonArray("elements") { tally.elements.forEach { add(it) } }
                putJsonArray("looks") { report.looks.forEach { add(it) } }
                if (report.looksLinked.isNotEmpty()) putJsonArray("looksLinked") { report.looksLinked.forEach { add(it) } }
                if (report.palettes.isNotEmpty()) putJsonArray("palettes") { report.palettes.forEach { add(it) } }
                if (report.palettesLinked.isNotEmpty()) putJsonArray("palettesLinked") { report.palettesLinked.forEach { add(it) } }
                putJsonArray("stacks") {
                    tally.stacks.forEach { (name, cues) -> addJsonObject { put("name", name); put("cues", cues) } }
                }
                put("imagesCopied", tally.images)
                putJsonArray("unresolved") {
                    report.unresolved.forEach { (what, by) ->
                        addJsonObject { put("problem", what); putJsonArray("in") { by.forEach { add(it) } } }
                    }
                }
                put(
                    "note",
                    "Copies are new records with new ids, named as the source names them. A cue two copied stacks share is copied " +
                        "once and shared in the target. A Look or palette a copied cue layers or an FX parameter names is " +
                        "copied with it, or — when the target already has one of that name — linked to the target's (looksLinked). " +
                        "Rows naming a fixture or group the target lacks are kept and read as missing; a scenery change, cue event " +
                        "or script trigger that cannot resolve is left out. Prompt-book anchors are never copied: anchor the cues " +
                        "again with mark_up_prompt_book once the target is current.",
                )
            },
        )
    }

    /** The copy itself, inside the caller's transaction, which rolls it back on any problem. */
    private fun copy(
        copier: CueCopier,
        elementNames: List<String>,
        stackIds: List<Int>,
        lookIds: List<Int>,
        dryRun: Boolean,
        tally: CopyTally,
        problems: MutableList<String>,
    ) {
        val source = copier.source
        val target = copier.target
        // ── What is copied ──
        val sourceElements = stageElementsOf(source).associateBy { it.name }
        val elements = elementNames.distinct().mapNotNull { name ->
            sourceElements[name] ?: run { problems += "sceneElements: project ${source.id.value} has no element named '$name'"; null }
        }
        val stacks = stackIds.distinct().mapNotNull { id ->
            DaoCueStack.findById(id)?.takeIf { it.project.id == source.id && it.type == CueStackType.STACK.name }
                ?: run { problems += "cueStacks: no cue stack $id in project ${source.id.value}"; null }
        }
        val namedLooks = lookIds.distinct().mapNotNull { id ->
            DaoLook.findById(id)?.takeIf { it.project.id == source.id }
                ?: run { problems += "looks: no Look $id in project ${source.id.value}"; null }
        }

        // ── The target's names ──
        val targetScene = stageElementsOf(target).associateBy { it.name }
        val targetHidden = ShowHides.list(target).filter { it.kind == HideKind.ELEMENT }.mapNotNull { it.name }.toSet()
        val targetLooks = DaoLook.find { DaoLooks.project eq target.id }.map { it.name }.toSet()
        val targetStacks = DaoCueStack.find { (DaoCueStacks.project eq target.id) and (DaoCueStacks.type eq CueStackType.STACK.name) }
            .map { it.name }.toSet()
        for (e in elements) {
            if (e.name in targetScene || e.name in targetHidden) {
                problems += "sceneElements: project ${target.id.value}'s scene already has an element named '${e.name}' — leave it out, or rename one first"
            }
        }
        for (l in namedLooks) {
            if (l.name in targetLooks) problems += "looks: project ${target.id.value} already has a Look named '${l.name}'"
        }
        for (s in stacks) {
            if (s.name in targetStacks) problems += "cueStacks: project ${target.id.value} already has a cue stack named '${s.name}'"
        }
        if (problems.isNotEmpty()) return

        val targetRegions = RigScope.of(target).stageRegions().associate { it.name to it.uuid.toString() }
        val sourceRegionNames = RigScope.of(source).stageRegions().associate { it.uuid.toString() to it.name }

        // ── Elements, and the images they are painted with ──
        val sourceStores = sceneImageStoresOf(source)
        val targetUuid = target.uuid.toString()
        var order = (RigScope.of(target).own.stageElements().maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (e in elements) {
            var params = storedParamsObject(e.params)
            val region = (params["regionUuid"] as? JsonPrimitive)?.contentOrNull
            if (region != null) {
                val mapped = sourceRegionNames[region]?.let { targetRegions[it] }
                params = if (mapped != null) JsonObject(params + ("regionUuid" to JsonPrimitive(mapped))) else {
                    copier.report.unresolved("stage region '${sourceRegionNames[region] ?: region}' is not in project ${target.id.value}; the platform decks no region", "element '${e.name}'")
                    JsonObject(params - "regionUuid")
                }
            }
            for (hash in sceneImageHashesOf(e.params)) {
                val src = sourceStores.firstNotNullOfOrNull { state.sceneImages.original(it, hash)?.first }
                if (src == null) {
                    copier.report.unresolved("image $hash is missing on this machine", "element '${e.name}'")
                } else if (!state.sceneImages.exists(targetUuid, hash)) {
                    if (dryRun || state.sceneImages.hydrate(targetUuid, hash, src)) tally.images++
                }
            }
            copier.elementAdded(
                DaoStageElement.new {
                    project = target
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
                    this.params = storedParamsText(params)
                    hidden = e.hidden
                    sortOrder = order++
                },
            )
            tally.elements += e.name
        }

        // ── Looks named outright, then stacks and their cues ──
        namedLooks.forEach(copier::copyLook)
        // A cue is copied once however many copied entries place it (cue model plan §3.9): two copied
        // stacks that share a cue — or one that holds it twice — share its copy in the target.
        val copiedCues = HashMap<Int, DaoCue>()
        var stackOrder = (DaoCueStack.find { DaoCueStacks.project eq target.id }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (s in stacks) {
            val copy = DaoCueStack.new {
                name = s.name
                project = target
                loop = s.loop
                type = CueStackType.STACK.name
                label = s.label
                sortOrder = stackOrder++
            }
            DaoCueStackSceneryRow.find { DaoCueStackScenery.stack eq s.id }.forEach { row ->
                val el = copier.element(row.element, "stack '${s.name}'") ?: return@forEach
                DaoCueStackSceneryRow.new {
                    stack = copy
                    element = el
                    stateJson = row.stateJson
                    sortOrder = row.sortOrder
                }
            }
            // Each stack's list is its entries, markers included (cue model plan §3.2).
            val stackEntries = entriesOf(s)
            val placedEntries = ArrayList<DaoCueStackEntry>(stackEntries.size)
            for (sourceEntry in stackEntries) {
                // The entry is copied as it stands — its place, number and follow — and a marker
                // entry is nothing more.
                val placed = DaoCueStackEntry.new {
                    stack = copy
                    kind = sourceEntry.kind
                    label = sourceEntry.label
                    ordinal = sourceEntry.ordinal
                    cueNumber = sourceEntry.cueNumber
                    cueNumberAuto = sourceEntry.cueNumberAuto
                    autoAdvance = sourceEntry.autoAdvance
                    autoAdvanceDelay = sourceEntry.autoAdvanceDelay
                }
                placedEntries += placed
                val c = sourceEntry.cue ?: continue
                val anchors = DaoPromptBookAnchor.find { DaoPromptBookAnchors.entry eq sourceEntry.id }.count()
                if (anchors > 0) copier.report.unresolved("prompt-book anchors are not copied", "stack '${s.name}'")
                placed.cue = copiedCues.getOrPut(c.id.value) {
                    copier.copyCue(c, c.name, "cue ${sourceEntry.cueNumber ?: c.name} of '${s.name}'")
                }
            }
            // The copies keep the source's ordinals but not its uuids, and a uuid breaks an ordinal tie
            // (P6): write the source's order out, which moves only an entry a tie would now misplace.
            writeEntryOrder(placedEntries)
            tally.stacks += s.name to stackEntries.count { !it.isMarker }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun intList(input: JsonObject, name: String, problems: MutableList<String>): List<Int> {
        val raw = input[name] ?: return emptyList()
        if (raw is JsonNull) return emptyList()
        val array = raw as? JsonArray ?: run { problems += "$name must be an array of ids"; return emptyList() }
        return array.mapIndexedNotNull { i, e ->
            (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: run { problems += "$name[$i] must be an integer id"; null }
        }
    }

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun failure(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )

    private fun rejected(problems: List<String>): ToolExecutionResult = ToolExecutionResult(
        success = false,
        description = "Nothing was written: ${problems.size} problem(s)",
        result = buildJsonObject {
            put("error", "Nothing was written: fix these ${problems.size} problem(s) and send the request again")
            put("problems", buildJsonArray { problems.distinct().forEach { add(it) } })
        }.toString(),
    )

    private fun unknownFields(row: JsonObject, known: List<String>, where: String): List<String> =
        row.keys.filter { it !in known }.map { "$where: unknown field '$it' (known: ${known.joinToString()})" }

    private companion object {
        /** An answer DTO in full: an empty list says "none" rather than being left out. */
        val answerJson = Json { encodeDefaults = true }

        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.stringList(name: String): List<String> =
            (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
    }
}
