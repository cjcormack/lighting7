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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.announceCueStacksDeleted
import uk.me.cormack.lighting7.routes.deleteCueStackRows
import uk.me.cormack.lighting7.routes.listedProjects
import uk.me.cormack.lighting7.routes.sceneImageStoresOf
import uk.me.cormack.lighting7.routes.showsOnVenue
import uk.me.cormack.lighting7.routes.stageElementsOf
import uk.me.cormack.lighting7.routes.venueDto
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.SeatingPresets
import uk.me.cormack.lighting7.venue.ShowHides
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import uk.me.cormack.lighting7.venue.VenueVersions
import java.util.UUID

/**
 * The venue and cross-project tools (`list_venues`, `extract_venue`, `publish_venue`,
 * `copy_to_project`, `delete_cue_stacks`): what a model needs to split a hall from the show built in
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
                    "rig holds its levels and each live cue, the programmer and hand-run effects come back, but every effect " +
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
        if (ids.isEmpty() && problems.isEmpty()) problems += "stackIds is required: the stacks to delete, with their cues"
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
                    else -> Found(id, stack.name, stack.cues.count().toInt())
                }
            }
        }
        if (problems.isNotEmpty()) return rejected(problems)
        if (!dryRun) {
            val deleted = transaction(state.database) {
                val project = DaoProject[target.project.id]
                found.map { deleteCueStackRows(state, project, DaoCueStack[it.id]) }
            }
            for (d in deleted) announceCueStacksDeleted(state, d)
        }
        return success(
            "${if (dryRun) "Would delete" else "Deleted"} ${found.size} cue stack(s) and ${found.sumOf { it.cues }} cue(s)",
            buildJsonObject {
                put("dryRun", dryRun)
                putJsonArray("stacks") {
                    found.forEach { addJsonObject { put("id", it.id); put("name", it.name); put("cues", it.cues) } }
                }
            },
        )
    }

    // ─── copy_to_project ────────────────────────────────────────────────

    /** What a copy did, or would do — and every reference it could not carry. */
    private class CopyReport {
        val elements = mutableListOf<String>()
        val looks = mutableListOf<String>()
        val looksLinked = mutableListOf<String>()
        val templates = mutableListOf<String>()
        val templatesLinked = mutableListOf<String>()
        val stacks = mutableListOf<Pair<String, Int>>()
        var images = 0
        /** Problem → who has it, in the order met. */
        val unresolved = LinkedHashMap<String, LinkedHashSet<String>>()

        fun unresolved(what: String, by: String) {
            unresolved.getOrPut(what) { LinkedHashSet() } += by
        }
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

        val report = CopyReport()
        // A dry run is the same run rolled back. Images are files, so only a real run copies them; one
        // whose transaction then fails leaves images nothing names, which the store's prune removes.
        transaction(state.database) {
            copy(DaoProject[from.project.id], DaoProject[to.project.id], elementNames, stackIds, lookIds, dryRun, report, problems)
            if (problems.isNotEmpty() || dryRun) TransactionManager.current().rollback()
        }
        if (problems.isNotEmpty()) return rejected(problems)

        if (!dryRun) {
            val fixtures = state.show.fixtures
            if (report.elements.isNotEmpty()) fixtures.stageElementListChanged()
            if (report.looks.isNotEmpty()) fixtures.lookListChanged()
            if (report.templates.isNotEmpty()) fixtures.templateListChanged()
            if (report.stacks.isNotEmpty()) {
                fixtures.cueStackListChanged()
                fixtures.cueListChanged()
            }
        }
        return success(
            "${if (dryRun) "Checked" else "Copied"}: ${report.elements.size} element(s), ${report.looks.size} Look(s), " +
                "${report.templates.size} palette(s), ${report.stacks.size} stack(s) into project ${to.project.id.value}" +
                if (report.unresolved.isEmpty()) "" else "; ${report.unresolved.size} reference(s) did not resolve",
            buildJsonObject {
                put("dryRun", dryRun)
                put("toProjectId", to.project.id.value)
                putJsonArray("elements") { report.elements.forEach { add(it) } }
                putJsonArray("looks") { report.looks.forEach { add(it) } }
                if (report.looksLinked.isNotEmpty()) putJsonArray("looksLinked") { report.looksLinked.forEach { add(it) } }
                if (report.templates.isNotEmpty()) putJsonArray("palettes") { report.templates.forEach { add(it) } }
                if (report.templatesLinked.isNotEmpty()) putJsonArray("palettesLinked") { report.templatesLinked.forEach { add(it) } }
                putJsonArray("stacks") {
                    report.stacks.forEach { (name, cues) -> addJsonObject { put("name", name); put("cues", cues) } }
                }
                put("imagesCopied", report.images)
                putJsonArray("unresolved") {
                    report.unresolved.forEach { (what, by) ->
                        addJsonObject { put("problem", what); putJsonArray("in") { by.forEach { add(it) } } }
                    }
                }
                put(
                    "note",
                    "Copies are new records with new ids, named as the source names them. A Look or palette a copied cue layers is " +
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
        source: DaoProject,
        target: DaoProject,
        elementNames: List<String>,
        stackIds: List<Int>,
        lookIds: List<Int>,
        dryRun: Boolean,
        report: CopyReport,
        problems: MutableList<String>,
    ) {
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
        val cues = stacks.associateWith { s -> s.cues.sortedBy { it.sortOrder } }
        val layers = cues.values.flatten().associateWith { it.layers.sortedBy { l -> l.sortOrder } }

        // ── The target's names ──
        val targetScene = stageElementsOf(target).associateBy { it.name }
        val targetHidden = ShowHides.list(target).filter { it.kind == HideKind.ELEMENT }.mapNotNull { it.name }.toSet()
        val targetLooks = DaoLook.find { DaoLooks.project eq target.id }.associateBy { it.name }
        val targetTemplates = DaoTemplate.find { DaoTemplates.project eq target.id }.associateBy { it.name }
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
        // A Look or template a copied cue layers: the target's of that name, else copied with it.
        val layerLooks = layers.values.flatten().mapNotNull { it.look }.distinctBy { it.id }
        val layerTemplates = layers.values.flatten().mapNotNull { it.template }.distinctBy { it.id }
        val looksToCopy = (namedLooks + layerLooks.filter { it.name !in targetLooks }).distinctBy { it.id }
        val templatesToCopy = layerTemplates.filter { it.name !in targetTemplates }
        if (problems.isNotEmpty()) return

        // ── What the target can resolve ──
        val rig = RigScope.of(target)
        val fixtureKeys = rig.patches().map { it.key }.toSet()
        val groupNames = rig.groups().map { it.name }.toSet()
        val patchesByKey = rig.patches().associateBy { it.key }
        val scriptsByName = DaoScript.find { DaoScripts.project eq target.id }.associateBy { it.name }
        val masterByIndex = DaoSpeedMaster.find { DaoSpeedMasters.project eq target.id }.associate { it.masterIndex to it.uuid }
        val sourceMasterIndex = DaoSpeedMaster.find { DaoSpeedMasters.project eq source.id }.associate { it.uuid to it.masterIndex }
        val targetRegions = rig.stageRegions().associate { it.name to it.uuid.toString() }
        val sourceRegionNames = RigScope.of(source).stageRegions().associate { it.uuid.toString() to it.name }

        fun checkTarget(type: String, key: String, by: String) {
            val ok = when (type) {
                TargetRef.Fixture.TYPE -> fixtureKeys.any { key == it || key.startsWith("$it.") }
                TargetRef.Group.TYPE -> key in groupNames
                else -> true
            }
            if (!ok) report.unresolved("project ${target.id.value} has no ${if (type == TargetRef.Group.TYPE) "group" else "fixture"} '$key'", by)
        }
        fun master(uuid: UUID?, by: String): UUID? {
            uuid ?: return null
            val index = sourceMasterIndex[uuid]
            val mapped = index?.let { masterByIndex[it] }
            if (mapped == null) report.unresolved("speed master ${index?.plus(1) ?: uuid} is not in project ${target.id.value}; master 1 drives it", by)
            return mapped
        }

        // ── Elements, and the images they are painted with ──
        val sourceStores = sceneImageStoresOf(source)
        val targetUuid = target.uuid.toString()
        val copiedElements = mutableMapOf<String, DaoStageElement>()
        var order = (RigScope.of(target).own.stageElements().maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (e in elements) {
            var params = storedParamsObject(e.params)
            val region = (params["regionUuid"] as? JsonPrimitive)?.contentOrNull
            if (region != null) {
                val mapped = sourceRegionNames[region]?.let { targetRegions[it] }
                params = if (mapped != null) JsonObject(params + ("regionUuid" to JsonPrimitive(mapped))) else {
                    report.unresolved("stage region '${sourceRegionNames[region] ?: region}' is not in project ${target.id.value}; the platform decks no region", "element '${e.name}'")
                    JsonObject(params - "regionUuid")
                }
            }
            for (hash in sceneImageHashesOf(e.params)) {
                val src = sourceStores.firstNotNullOfOrNull { state.sceneImages.original(it, hash)?.first }
                if (src == null) {
                    report.unresolved("image $hash is missing on this machine", "element '${e.name}'")
                } else if (!state.sceneImages.exists(targetUuid, hash)) {
                    if (dryRun || state.sceneImages.hydrate(targetUuid, hash, src)) report.images++
                }
            }
            copiedElements[e.name] = DaoStageElement.new {
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
            }
            report.elements += e.name
        }
        // Scenery names an element by name across the projects: the target's scene, plus what this copy adds.
        fun element(named: DaoStageElement, by: String): DaoStageElement? =
            (copiedElements[named.name] ?: targetScene[named.name])
                ?: run { report.unresolved("element '${named.name}' is not in project ${target.id.value}'s scene; its scenery change is left out", by); null }

        // ── Looks and templates ──
        val lookMap = mutableMapOf<Int, DaoLook>()
        for (l in layerLooks) targetLooks[l.name]?.let { lookMap[l.id.value] = it; report.looksLinked += l.name }
        for (l in looksToCopy) {
            val by = "Look '${l.name}'"
            val copy = DaoLook.new {
                project = target
                name = l.name
                notes = l.notes
            }
            l.rows.forEach { r ->
                checkTarget(r.targetType, r.targetKey, by)
                DaoLookRow.new {
                    look = copy
                    targetType = r.targetType
                    targetKey = r.targetKey
                    propertyName = r.propertyName
                    value = r.value
                    fadeDuration = r.fadeDuration
                    elementKey = r.elementKey
                    sortOrder = r.sortOrder
                }
            }
            l.effects.forEach { e ->
                checkTarget(e.targetType, e.targetKey, by)
                DaoLookEffect.new {
                    look = copy
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
                    speedMasterUuid = master(e.speedMasterUuid, by)
                    rateSpeedMasterUuid = master(e.rateSpeedMasterUuid, by)
                    seconds = e.seconds
                    sortOrder = e.sortOrder
                }
            }
            DaoLookSceneryRow.find { DaoLookScenery.look eq l.id }.forEach { s ->
                val el = element(s.element, by) ?: return@forEach
                DaoLookSceneryRow.new {
                    look = copy
                    element = el
                    stateJson = s.stateJson
                    sortOrder = s.sortOrder
                }
            }
            lookMap[l.id.value] = copy
            report.looks += l.name
        }
        val templateMap = mutableMapOf<Int, DaoTemplate>()
        for (t in layerTemplates) targetTemplates[t.name]?.let { templateMap[t.id.value] = it; report.templatesLinked += t.name }
        for (t in templatesToCopy) {
            val by = "palette '${t.name}'"
            val copy = DaoTemplate.new {
                project = target
                name = t.name
                notes = t.notes
                fadeDuration = t.fadeDuration
            }
            t.rows.forEach { r ->
                checkTarget(r.targetType, r.targetKey, by)
                DaoTemplateRow.new {
                    template = copy
                    targetType = r.targetType
                    targetKey = r.targetKey
                    propertyName = r.propertyName
                    value = r.value
                    sortOrder = r.sortOrder
                }
            }
            t.effects.forEach { e ->
                DaoTemplateEffect.new {
                    template = copy
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
                    speedMasterUuid = master(e.speedMasterUuid, by)
                    rateSpeedMasterUuid = master(e.rateSpeedMasterUuid, by)
                    seconds = e.seconds
                }
            }
            templateMap[t.id.value] = copy
            report.templates += t.name
        }

        // ── Stacks and their cues ──
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
                val el = element(row.element, "stack '${s.name}'") ?: return@forEach
                DaoCueStackSceneryRow.new {
                    stack = copy
                    element = el
                    stateJson = row.stateJson
                    sortOrder = row.sortOrder
                }
            }
            val stackCues = cues.getValue(s)
            for (c in stackCues) {
                val by = "cue ${c.cueNumber ?: c.name} of '${s.name}'"
                val cue = DaoCue.new {
                    name = c.name
                    project = target
                    cueStack = copy
                    sortOrder = c.sortOrder
                    autoAdvance = c.autoAdvance
                    autoAdvanceDelay = c.autoAdvanceDelay
                    fadeDuration = c.fadeDuration
                    fadeCurve = c.fadeCurve
                    cueNumber = c.cueNumber
                    cueNumberAuto = c.cueNumberAuto
                    notes = c.notes
                    cueType = c.cueType
                    stomp = c.stomp
                }
                for (l in layers.getValue(c)) {
                    l.targets.forEach { t -> checkTarget(t.type, t.key, by) }
                    DaoCueLayer.new {
                        this.cue = cue
                        look = l.look?.let { lookMap.getValue(it.id.value) }
                        template = l.template?.let { templateMap.getValue(it.id.value) }
                        sortOrder = l.sortOrder
                        enabled = l.enabled
                        targets = l.targets
                        propertyMask = l.propertyMask
                        blendMode = l.blendMode
                        amount = l.amount
                        stomp = l.stomp
                        speedMasterUuid = master(l.speedMasterUuid, by)
                        rateSpeedMasterUuid = master(l.rateSpeedMasterUuid, by)
                        delay = l.delay
                        interval = l.interval
                        randomWindow = l.randomWindow
                    }
                }
                c.adHocEffects.forEach { e ->
                    checkTarget(e.targetType, e.targetKey, by)
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
                        delay = e.delay
                        interval = e.interval
                        randomWindow = e.randomWindow
                        sortOrder = e.sortOrder
                        speedMasterUuid = master(e.speedMasterUuid, by)
                        rateSpeedMasterUuid = master(e.rateSpeedMasterUuid, by)
                        seconds = e.seconds
                    }
                }
                c.propertyAssignments.forEach { a ->
                    checkTarget(a.targetType, a.targetKey, by)
                    DaoCuePropertyAssignment.new {
                        this.cue = cue
                        targetType = a.targetType
                        targetKey = a.targetKey
                        propertyName = a.propertyName
                        value = a.value
                        fadeDuration = a.fadeDuration
                        sortOrder = a.sortOrder
                        moveInDark = a.moveInDark
                    }
                }
                c.triggers.forEach { t ->
                    val script = scriptsByName[t.script.name]
                    if (script == null) {
                        report.unresolved("script '${t.script.name}' is not in project ${target.id.value}; its trigger is left out", by)
                        return@forEach
                    }
                    DaoCueTrigger.new {
                        this.cue = cue
                        triggerType = t.triggerType
                        this.script = script
                        delay = t.delay
                        interval = t.interval
                        randomWindow = t.randomWindow
                        sortOrder = t.sortOrder
                    }
                }
                DaoCueSceneryRow.find { DaoCueScenery.cue eq c.id }.forEach { row ->
                    val el = element(row.element, by) ?: return@forEach
                    DaoCueSceneryRow.new {
                        this.cue = cue
                        element = el
                        stateJson = row.stateJson
                        transition = row.transition
                        sortOrder = row.sortOrder
                    }
                }
                DaoCueEvent.find { DaoCueEvents.cue eq c.id }.forEach { ev ->
                    val patch = patchesByKey[ev.patch.key]?.takeIf { it.fixtureTypeKey == ev.patch.fixtureTypeKey }
                    if (patch == null) {
                        report.unresolved("project ${target.id.value} has no '${ev.patch.key}' of type ${ev.patch.fixtureTypeKey}; its ${ev.trigger} event is left out", by)
                        return@forEach
                    }
                    DaoCueEvent.new {
                        this.cue = cue
                        this.patch = patch
                        trigger = ev.trigger
                        offset = ev.offset
                        sortOrder = ev.sortOrder
                    }
                }
                val anchors = DaoPromptBookAnchor.find { DaoPromptBookAnchors.cue eq c.id }.count()
                if (anchors > 0) report.unresolved("prompt-book anchors are not copied", "stack '${s.name}'")
            }
            report.stacks += s.name to stackCues.size
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
        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.stringList(name: String): List<String> =
            (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
    }
}
