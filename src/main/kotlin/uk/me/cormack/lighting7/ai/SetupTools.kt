package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.MAX_CUE_NUMBER_LENGTH
import uk.me.cormack.lighting7.routes.createCueChildren
import uk.me.cormack.lighting7.routes.normaliseGelCode
import uk.me.cormack.lighting7.routes.normaliseKindOverride
import uk.me.cormack.lighting7.routes.renumberAutoCues
import uk.me.cormack.lighting7.routes.validateCueChildren
import uk.me.cormack.lighting7.routes.validateRiggingPose
import uk.me.cormack.lighting7.routes.validateStageDimensions
import uk.me.cormack.lighting7.routes.validateStageMetadata
import uk.me.cormack.lighting7.routes.validateStageRegion
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import java.time.Duration

/**
 * The MCP server's show-setup tools ([setupToolDefs]): build a project, its patch, its stage and
 * the show's cue stacks and prompt-book markup from material the model has read — a patch export,
 * a plot, a photo, a script and a designer's notes.
 *
 * MCP only, not the in-app chat. The chat's conversation belongs to the current project, and
 * `switch_project` would move the show out from under it; the rest would work there, but the
 * source material these are for (PDFs and photos) arrives through an MCP client.
 *
 * Every tool **validates the whole request before writing anything**, and answers every problem
 * at once rather than the first: these calls carry tens or hundreds of rows transcribed from a
 * document, and a model that learns about row 40's typo only after rows 1–39 are written has to
 * work out what already landed before it can retry. All-or-nothing makes a retry of the corrected
 * request the whole recovery. Writes go through the same validators and change broadcasts the REST
 * routes use, so the desk's views update exactly as if the operator had made the edit.
 */
class SetupTools(
    private val state: State,
    private val aiTools: AiTools,
    /** The desk's public base URL, for pointing the operator at a page; null when unknown. */
    private val deskUrl: () -> String? = { null },
) {
    val toolDefs: List<AnthropicToolDef> = setupToolDefs

    fun handles(name: String): Boolean = toolDefs.any { it.name == name }

    suspend fun executeTool(name: String, input: JsonObject): ToolExecutionResult = try {
        when (name) {
            listProjectsTool.name -> listProjects()
            createProjectTool.name -> createProject(input)
            switchProjectTool.name -> switchProject(input)
            listFixtureTypesTool.name -> listFixtureTypes(input)
            getPatchTool.name -> getPatch()
            patchFixturesTool.name -> patchFixtures(input)
            setStageTool.name -> setStage(input)
            placeFixturesTool.name -> placeFixtures(input)
            getPromptBookTool.name -> getPromptBook()
            buildCueStackTool.name -> buildCueStack(input)
            markUpPromptBookTool.name -> markUpPromptBook(input)
            else -> failure("Unknown tool: $name")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        failure("Error executing $name: ${e.message ?: e::class.simpleName}")
    }

    // ─── Projects ───────────────────────────────────────────────────────

    private fun listProjects(): ToolExecutionResult {
        val projects = transaction(state.database) {
            DaoProject.all().orderBy(DaoProjects.name to SortOrder.ASC).map { p ->
                buildJsonObject {
                    put("id", p.id.value)
                    put("name", p.name)
                    p.description?.let { put("description", it) }
                    put("isCurrent", p.isCurrent)
                }
            }
        }
        return success("${projects.size} project(s)", buildJsonObject {
            put("projects", JsonArray(projects))
        })
    }

    private suspend fun createProject(input: JsonObject): ToolExecutionResult {
        val name = input.string("name")?.trim().orEmpty()
        val description = input.string("description")?.trim()?.takeIf { it.isNotEmpty() }
        val width = input.double("stageWidthM")
        val depth = input.double("stageDepthM")
        val height = input.double("stageHeightM")
        val problems = buildList {
            if (name.isEmpty()) add("name must not be blank")
            if (name.length > 50) add("name must be at most 50 characters")
            if ((description?.length ?: 0) > 255) add("description must be at most 255 characters")
            validateStageDimensions(width, depth, height)?.let { add(it) }
        }
        if (problems.isNotEmpty()) return rejected(problems)

        val created = transaction(state.database) {
            if (!DaoProject.find { DaoProjects.name eq name }.empty()) return@transaction null
            val project = DaoProject.new {
                this.name = name
                this.description = description
                isCurrent = false
                stageWidthM = width
                stageDepthM = depth
                stageHeightM = height
            }
            ensureDefaultSpeedMasters(project)
            project.id.value
        } ?: return failure("A project named '$name' already exists")

        val switched = input.boolean("switchTo") == true
        if (switched) state.projectManager.switchProject(created)
        return success(
            "Created project '$name' (id=$created)" + if (switched) " and made it current" else "",
            buildJsonObject {
                put("projectId", created)
                put("name", name)
                put("isCurrent", switched)
            },
        )
    }

    private suspend fun switchProject(input: JsonObject): ToolExecutionResult {
        val projectId = input.int("projectId") ?: return failure("Missing 'projectId'")
        val name = transaction(state.database) { DaoProject.findById(projectId)?.name }
            ?: return failure("Project not found: $projectId")
        if (state.projectManager.currentProject.id.value == projectId) {
            return success("'$name' is already the current project", buildJsonObject {
                put("projectId", projectId)
                put("name", name)
            })
        }
        state.projectManager.switchProject(projectId)
        return success("Switched to '$name' — call describe_rig for its rig", buildJsonObject {
            put("projectId", projectId)
            put("name", name)
        })
    }

    // ─── Fixture types and the patch ────────────────────────────────────

    private fun listFixtureTypes(input: JsonObject): ToolExecutionResult {
        val query = input.string("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val types = FixtureTypeRegistry.allTypes
            .filter { it.channelCount != null }
            .filter { t ->
                query == null || listOfNotNull(t.typeKey, t.manufacturer, t.model, t.modeName, t.kind.name)
                    .any { it.lowercase().contains(query) }
            }
            .sortedWith(compareBy({ it.manufacturer }, { it.model }, { it.channelCount }))
        return success("${types.size} fixture type(s)", buildJsonObject {
            putJsonArray("fixtureTypes") {
                types.forEach { t ->
                    addJsonObject {
                        put("typeKey", t.typeKey)
                        put("manufacturer", t.manufacturer)
                        put("model", t.model)
                        t.modeName?.let { put("mode", it) }
                        put("channelCount", t.channelCount)
                        put("kind", t.kind.name)
                        putJsonArray("capabilities") { t.capabilities.forEach { add(it) } }
                    }
                }
            }
        })
    }

    private fun getPatch(): ToolExecutionResult {
        val project = state.projectManager.currentProject
        val body = transaction(state.database) {
            val riggings = DaoRigging.find { DaoRiggings.project eq project.id }
                .orderBy(DaoRiggings.sortOrder to SortOrder.ASC).toList()
            val riggingNames = riggings.associate { it.id.value to it.name }
            val regions = DaoStageRegion.find { DaoStageRegions.project eq project.id }
                .orderBy(DaoStageRegions.sortOrder to SortOrder.ASC).toList()
            val universes = DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }
                .sortedWith(compareBy({ it.subnet }, { it.universe }))
            val universeById = universes.associateBy { it.id.value }
            val patches = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC).toList()
            val groups = DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }
                .orderBy(DaoFixtureGroups.name to SortOrder.ASC).toList()
            val keyByPatchId = patches.associate { it.id.value to it.key }
            val membersByGroup = groups.associate { g ->
                g.name to g.members.sortedBy { it.sortOrder }.mapNotNull { keyByPatchId[it.fixturePatch.id.value] }
            }
            val groupsByKey = mutableMapOf<String, MutableList<String>>()
            membersByGroup.forEach { (group, keys) -> keys.forEach { groupsByKey.getOrPut(it) { mutableListOf() }.add(group) } }

            buildJsonObject {
                put("project", project.name)
                putJsonObject("stage") {
                    putOptional("widthM", project.stageWidthM)
                    putOptional("depthM", project.stageDepthM)
                    putOptional("heightM", project.stageHeightM)
                }
                putJsonArray("regions") {
                    regions.forEach { r ->
                        addJsonObject {
                            put("name", r.name)
                            putOptional("centerX", r.centerX); putOptional("centerY", r.centerY); putOptional("centerZ", r.centerZ)
                            putOptional("widthM", r.widthM); putOptional("depthM", r.depthM); putOptional("heightM", r.heightM)
                            putOptional("yawDeg", r.yawDeg)
                        }
                    }
                }
                putJsonArray("riggings") {
                    riggings.forEach { r ->
                        addJsonObject {
                            put("name", r.name)
                            r.kind?.let { put("kind", it) }
                            putOptional("x", r.positionX); putOptional("y", r.positionY); putOptional("z", r.positionZ)
                            putOptional("yawDeg", r.yawDeg); putOptional("pitchDeg", r.pitchDeg); putOptional("rollDeg", r.rollDeg)
                            putOptional("lengthM", r.lengthM)
                        }
                    }
                }
                putJsonArray("universes") {
                    universes.forEach { u ->
                        addJsonObject {
                            put("universe", u.universe)
                            put("subnet", u.subnet)
                            put("controllerType", u.controllerType)
                            put("fixtureCount", patches.count { it.universeConfig.id.value == u.id.value })
                        }
                    }
                }
                putJsonArray("fixtures") {
                    patches.forEach { p ->
                        val count = FixtureTypeRegistry.channelCountForTypeKey(p.fixtureTypeKey) ?: 1
                        val universe = universeById[p.universeConfig.id.value]
                        addJsonObject {
                            put("key", p.key)
                            put("name", p.displayName)
                            put("fixtureTypeKey", p.fixtureTypeKey)
                            universe?.let { put("universe", it.universe) }
                            put("startChannel", p.startChannel)
                            put("endChannel", p.startChannel + count - 1)
                            groupsByKey[p.key]?.let { g -> putJsonArray("groups") { g.forEach { add(it) } } }
                            p.readValues.getOrNull(DaoFixturePatches.rigging)?.value
                                ?.let { riggingNames[it] }?.let { put("rigging", it) }
                            putOptional("x", p.stageX); putOptional("y", p.stageY); putOptional("z", p.stageZ)
                            putOptional("yawDeg", p.baseYawDeg); putOptional("pitchDeg", p.basePitchDeg)
                            p.beamAngleDeg?.let { put("beamAngleDeg", it) }
                            p.gelCode?.let { put("gelCode", it) }
                            p.kindOverride?.let { put("kind", it) }
                            if (p.stageHidden) put("stageHidden", true)
                        }
                    }
                }
                putJsonObject("groups") {
                    membersByGroup.forEach { (group, keys) -> putJsonArray(group) { keys.forEach { add(it) } } }
                }
            }
        }
        return success("Patch and stage of '${project.name}'", body)
    }

    /** One row of a patch_fixtures call, validated and resolved. */
    private data class PatchRow(
        val index: Int,
        val key: String,
        val name: String,
        val typeKey: String,
        val universe: Int,
        val startChannel: Int,
        val endChannel: Int,
        val groups: List<String>,
        val placement: Placement,
    )

    private fun patchFixtures(input: JsonObject): ToolExecutionResult {
        val rows = input["fixtures"] as? JsonArray ?: return failure("Missing 'fixtures'")
        if (rows.isEmpty()) return failure("'fixtures' is empty")
        val dryRun = input.boolean("dryRun") == true
        val project = state.projectManager.currentProject

        data class Existing(val id: Int, val key: String, val universe: Int, val start: Int, val end: Int)

        val (existing, riggingIds) = transaction(state.database) {
            val universes = DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }
                .associate { it.id.value to it.universe }
            DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.map { p ->
                val count = FixtureTypeRegistry.channelCountForTypeKey(p.fixtureTypeKey) ?: 1
                Existing(p.id.value, p.key, universes[p.universeConfig.id.value] ?: -1, p.startChannel, p.startChannel + count - 1)
            } to riggingIdsByName(project)
        }
        val existingByKey = existing.associateBy { it.key }

        val problems = mutableListOf<String>()
        val parsed = mutableListOf<PatchRow>()
        val seenKeys = mutableMapOf<String, Int>()
        rows.forEachIndexed { index, element ->
            val row = element as? JsonObject ?: run { problems += "fixtures[$index]: not an object"; return@forEachIndexed }
            val label = "fixtures[$index]"
            val name = row.string("name")?.trim().orEmpty()
            val key = row.string("key")?.trim()?.takeIf { it.isNotEmpty() } ?: slug(name)
            val where = "$label ('${key.ifEmpty { name }}')"
            val rowProblems = mutableListOf<String>()
            if (name.isEmpty()) rowProblems += "name must not be blank"
            if (name.length > 255) rowProblems += "name must be at most 255 characters"
            if (key.isEmpty()) rowProblems += "key must not be blank"
            if (key.length > 100) rowProblems += "key must be at most 100 characters"
            seenKeys[key]?.let { rowProblems += "key '$key' is also used by fixtures[$it]" }
            if (key.isNotEmpty()) seenKeys.putIfAbsent(key, index)

            val typeKey = row.string("fixtureTypeKey")?.trim().orEmpty()
            val channelCount = FixtureTypeRegistry.typeInfoForKey(typeKey)?.channelCount
            if (FixtureTypeRegistry.typeInfoForKey(typeKey) == null) {
                rowProblems += "unknown fixtureTypeKey '$typeKey' (see list_fixture_types)"
            } else if (channelCount == null) {
                rowProblems += "fixture type '$typeKey' has no channel count and cannot be patched"
            }
            val universe = row.int("universe")
            if (universe == null || universe !in 0..32767) rowProblems += "universe must be an integer 0–32767"
            val start = row.int("startChannel")
            if (start == null || start !in 1..512) rowProblems += "startChannel must be an integer 1–512"
            val end = if (start != null && channelCount != null) start + channelCount - 1 else null
            if (end != null && end > 512) {
                rowProblems += "a $channelCount-channel fixture at $start runs to channel $end (the highest start is ${512 - channelCount!! + 1})"
            }
            val groups = (row["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            groups.filter { it.length > 100 }.forEach { rowProblems += "group name '$it' is longer than 100 characters" }
            val placement = parsePlacement(row, riggingIds, rowProblems)

            if (rowProblems.isNotEmpty()) {
                problems += rowProblems.map { "$where: $it" }
            } else {
                parsed += PatchRow(index, key, name, typeKey, universe!!, start!!, end!!, groups, placement)
            }
        }

        // Overlaps, against the patch as it will stand: the rows being updated leave their old
        // address, and every row of this call claims its new one.
        if (problems.isEmpty()) {
            val updatingKeys = parsed.map { it.key }.toSet()
            data class Claim(val label: String, val universe: Int, val start: Int, val end: Int)
            val claims = existing.filter { it.key !in updatingKeys }
                .map { Claim("already-patched '${it.key}'", it.universe, it.start, it.end) } +
                parsed.map { Claim("fixtures[${it.index}] ('${it.key}')", it.universe, it.startChannel, it.endChannel) }
            claims.groupBy { it.universe }.forEach { (universe, inUniverse) ->
                val sorted = inUniverse.sortedBy { it.start }
                for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                    if (sorted[j].start > sorted[i].end) break
                    problems += "${sorted[j].label} (universe $universe, ${sorted[j].start}–${sorted[j].end}) " +
                        "overlaps ${sorted[i].label} (${sorted[i].start}–${sorted[i].end})"
                }
            }
        }
        if (problems.isNotEmpty()) return rejected(problems)

        val toCreate = parsed.filter { it.key !in existingByKey }
        val toUpdate = parsed.filter { it.key in existingByKey }
        val newUniverses = parsed.map { it.universe }.distinct()
            .filter { u -> existing.none { it.universe == u } }
            .let { candidates -> transaction(state.database) { candidates.filter { findUniverse(project, it) == null } } }
        val summary = buildJsonObject {
            put("dryRun", dryRun)
            put("created", toCreate.size)
            put("updated", toUpdate.size)
            putJsonArray("createdKeys") { toCreate.forEach { add(it.key) } }
            putJsonArray("updatedKeys") { toUpdate.forEach { add(it.key) } }
            putJsonArray("newUniverses") { newUniverses.forEach { add(it) } }
        }
        if (dryRun) {
            return success("Dry run: would patch ${toCreate.size} new and update ${toUpdate.size} fixture(s)", summary)
        }

        val newGroups = transaction(state.database) {
            var sortOrder = (DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                .maxOfOrNull { it.sortOrder } ?: -1) + 1
            val groupsBefore = DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }.map { it.name }.toSet()
            for (row in parsed) {
                val universeConfig = findUniverse(project, row.universe) ?: DaoUniverseConfig.new {
                    this.project = project
                    subnet = 0
                    universe = row.universe
                    controllerType = "ARTNET"
                    address = null
                }
                val patch = existingByKey[row.key]?.let { DaoFixturePatch.findById(it.id)!! } ?: DaoFixturePatch.new {
                    this.project = project
                    key = row.key
                    this.sortOrder = sortOrder++
                    // Required columns, set properly just below.
                    this.universeConfig = universeConfig
                    fixtureTypeKey = row.typeKey
                    displayName = row.name
                    startChannel = row.startChannel
                }
                patch.universeConfig = universeConfig
                patch.fixtureTypeKey = row.typeKey
                patch.displayName = row.name
                patch.startChannel = row.startChannel
                row.placement.applyTo(patch)
                for (groupName in row.groups) {
                    val group = DaoFixtureGroup.find {
                        (DaoFixtureGroups.project eq project.id) and (DaoFixtureGroups.name eq groupName)
                    }.firstOrNull() ?: DaoFixtureGroup.new {
                        this.project = project
                        name = groupName
                    }
                    val already = DaoFixtureGroupMember.find {
                        (DaoFixtureGroupMembers.group eq group.id) and (DaoFixtureGroupMembers.fixturePatch eq patch.id)
                    }.empty()
                    if (already) {
                        DaoFixtureGroupMember.new {
                            this.group = group
                            fixturePatch = patch
                            this.sortOrder = (group.members.maxOfOrNull { it.sortOrder } ?: -1) + 1
                        }
                    }
                }
            }
            DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }.map { it.name }.filter { it !in groupsBefore }
        }

        DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.parkManager)
        state.show.fixtures.patchListChanged()

        val result = buildJsonObject {
            summary.forEach { (k, v) -> put(k, v) }
            putJsonArray("newGroups") { newGroups.forEach { add(it) } }
            if (newUniverses.isNotEmpty()) {
                put("note", "New universes have no Art-Net node address yet: the operator sets it in the desk's universe settings.")
            }
        }
        return success("Patched ${toCreate.size} new and updated ${toUpdate.size} fixture(s)", result)
    }

    private fun findUniverse(project: DaoProject, universe: Int): DaoUniverseConfig? = DaoUniverseConfig.find {
        (DaoUniverseConfigs.project eq project.id) and (DaoUniverseConfigs.subnet eq 0) and
            (DaoUniverseConfigs.universe eq universe)
    }.firstOrNull()

    private fun riggingIdsByName(project: DaoProject): Map<String, Int> =
        DaoRigging.find { DaoRiggings.project eq project.id }.associate { it.name to it.id.value }

    // ─── Placement ──────────────────────────────────────────────────────

    /**
     * The placement fields of one row, each held only when the row carried its key, so an update
     * touches exactly what was sent. [rigging] is `Some(null)` for an explicit detach.
     */
    private class Placement(
        val rigging: Optional<Int?>?,
        val x: Optional<Double?>?, val y: Optional<Double?>?, val z: Optional<Double?>?,
        val yawDeg: Optional<Double?>?, val pitchDeg: Optional<Double?>?,
        val beamAngleDeg: Optional<Int?>?,
        val gelCode: Optional<String?>?,
        val kind: Optional<String?>?,
        val stageHidden: Boolean?,
    ) {
        fun applyTo(patch: DaoFixturePatch) {
            rigging?.let { r -> patch.rigging = r.value?.let { DaoRigging.findById(it) } }
            x?.let { patch.stageX = it.value }
            y?.let { patch.stageY = it.value }
            z?.let { patch.stageZ = it.value }
            yawDeg?.let { patch.baseYawDeg = it.value }
            pitchDeg?.let { patch.basePitchDeg = it.value }
            beamAngleDeg?.let { patch.beamAngleDeg = it.value }
            gelCode?.let { patch.gelCode = it.value }
            kind?.let { patch.kindOverride = it.value }
            stageHidden?.let { patch.stageHidden = it }
        }
    }

    /** A present value, which may itself be null (an explicit clear). */
    private class Optional<T>(val value: T)

    private fun parsePlacement(row: JsonObject, riggingIds: Map<String, Int>, problems: MutableList<String>): Placement {
        fun <T> field(name: String, read: (JsonPrimitive) -> T?): Optional<T?>? {
            val element = row[name] ?: return null
            if (element is JsonNull) return Optional(null)
            val primitive = element as? JsonPrimitive
            val value = primitive?.let(read)
            if (value == null) {
                problems += "$name has the wrong type"
                return null
            }
            return Optional(value)
        }

        val rigging = field("rigging") { it.contentOrNull?.trim() }?.let { named ->
            val riggingName = named.value?.takeIf { it.isNotEmpty() } ?: return@let Optional<Int?>(null)
            val id = riggingIds[riggingName]
            if (id == null) {
                problems += "no rigging named '$riggingName'" +
                    (if (riggingIds.isEmpty()) " (create riggings with set_stage first)" else " (known: ${riggingIds.keys.joinToString()})")
                null
            } else Optional<Int?>(id)
        }
        val x = field("x") { it.doubleOrNull }
        val y = field("y") { it.doubleOrNull }
        val z = field("z") { it.doubleOrNull }
        val yaw = field("yawDeg") { it.doubleOrNull }
        val pitch = field("pitchDeg") { it.doubleOrNull }
        val beam = field("beamAngleDeg") { it.doubleOrNull?.toInt() }
        validateStageMetadata(x?.value, y?.value, z?.value, yaw?.value, pitch?.value, beam?.value)?.let { problems += it }
        val gel = field("gelCode") { it.contentOrNull }?.let { Optional(normaliseGelCode(it.value)) }
        val kind = field("kind") { it.contentOrNull }?.let {
            try {
                Optional(normaliseKindOverride(it.value))
            } catch (e: IllegalArgumentException) {
                problems += e.message ?: "invalid kind"
                null
            }
        }
        val hidden = row["stageHidden"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: run { problems += "stageHidden must be a boolean"; null } }
        return Placement(rigging, x, y, z, yaw, pitch, beam, gel, kind, hidden)
    }

    private fun placeFixtures(input: JsonObject): ToolExecutionResult {
        val rows = input["placements"] as? JsonArray ?: return failure("Missing 'placements'")
        if (rows.isEmpty()) return failure("'placements' is empty")
        val project = state.projectManager.currentProject
        val (patchIds, riggingIds) = transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.associate { it.key to it.id.value } to
                riggingIdsByName(project)
        }

        val problems = mutableListOf<String>()
        val parsed = mutableListOf<Pair<Int, Placement>>()
        val seen = mutableSetOf<String>()
        rows.forEachIndexed { index, element ->
            val row = element as? JsonObject ?: run { problems += "placements[$index]: not an object"; return@forEachIndexed }
            val key = row.string("key")?.trim().orEmpty()
            val rowProblems = mutableListOf<String>()
            val patchId = patchIds[key]
            if (patchId == null) rowProblems += "no patched fixture has key '$key'"
            if (!seen.add(key)) rowProblems += "key '$key' is listed twice"
            val placement = parsePlacement(row, riggingIds, rowProblems)
            if (rowProblems.isEmpty()) parsed += patchId!! to placement
            else problems += rowProblems.map { "placements[$index] ('$key'): $it" }
        }
        if (problems.isNotEmpty()) return rejected(problems)

        transaction(state.database) {
            parsed.forEach { (patchId, placement) -> placement.applyTo(DaoFixturePatch.findById(patchId)!!) }
        }
        // Metadata only — the same set `METADATA_ONLY_PUT_KEYS` lets the patch route skip the
        // fixture rebuild for — so the runtime rig is untouched and only the views refresh.
        state.show.fixtures.patchListChanged()
        return success("Placed ${parsed.size} fixture(s)", buildJsonObject { put("placed", parsed.size) })
    }

    // ─── Stage ──────────────────────────────────────────────────────────

    private fun setStage(input: JsonObject): ToolExecutionResult {
        val project = state.projectManager.currentProject
        val problems = mutableListOf<String>()

        val stage = input["stage"] as? JsonObject
        val width = stage?.double("widthM")
        val depth = stage?.double("depthM")
        val height = stage?.double("heightM")
        validateStageDimensions(width, depth, height)?.let { problems += "stage: $it" }

        val regions = (input["regions"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "regions[$index]: not an object"; return@mapIndexedNotNull null }
            val name = row.string("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 100) problems += "regions[$index]: name must be 1–100 characters"
            validateStageRegion(
                row.double("centerX"), row.double("centerY"), row.double("centerZ"),
                row.double("widthM"), row.double("depthM"), row.double("heightM"), row.double("yawDeg"),
            )?.let { problems += "regions[$index] ('$name'): $it" }
            name to row
        }
        val riggings = (input["riggings"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "riggings[$index]: not an object"; return@mapIndexedNotNull null }
            val name = row.string("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 100) problems += "riggings[$index]: name must be 1–100 characters"
            val kind = row.string("kind")?.trim()?.uppercase()
            if (kind != null && kind !in RIGGING_KINDS) problems += "riggings[$index] ('$name'): kind must be one of ${RIGGING_KINDS.joinToString()}"
            validateRiggingPose(
                row.double("x"), row.double("y"), row.double("z"),
                row.double("yawDeg"), row.double("pitchDeg"), row.double("rollDeg"), row.double("lengthM"),
            )?.let { problems += "riggings[$index] ('$name'): $it" }
            name to row
        }
        regions.groupBy { it.first }.filter { it.value.size > 1 }.keys.forEach { problems += "region '$it' is listed twice" }
        riggings.groupBy { it.first }.filter { it.value.size > 1 }.keys.forEach { problems += "rigging '$it' is listed twice" }
        val removeRegions = input.stringList("removeRegions")
        val removeRiggings = input.stringList("removeRiggings")

        val (knownRegions, knownRiggings) = transaction(state.database) {
            DaoStageRegion.find { DaoStageRegions.project eq project.id }.map { it.name }.toSet() to
                riggingIdsByName(project).keys
        }
        removeRegions.filter { it !in knownRegions }.forEach { problems += "removeRegions: no region named '$it'" }
        removeRiggings.filter { it !in knownRiggings }.forEach { problems += "removeRiggings: no rigging named '$it'" }
        removeRegions.filter { r -> regions.any { it.first == r } }.forEach { problems += "region '$it' is both set and removed" }
        removeRiggings.filter { r -> riggings.any { it.first == r } }.forEach { problems += "rigging '$it' is both set and removed" }
        if (stage == null && regions.isEmpty() && riggings.isEmpty() && removeRegions.isEmpty() && removeRiggings.isEmpty()) {
            problems += "nothing to change: send stage, regions, riggings, removeRegions or removeRiggings"
        }
        if (problems.isNotEmpty()) return rejected(problems)

        var detached = 0
        transaction(state.database) {
            if (stage != null) {
                val p = DaoProject.findById(project.id)!!
                if ("widthM" in stage) p.stageWidthM = width
                if ("depthM" in stage) p.stageDepthM = depth
                if ("heightM" in stage) p.stageHeightM = height
            }
            var regionOrder = (DaoStageRegion.find { DaoStageRegions.project eq project.id }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for ((name, row) in regions) {
                val region = DaoStageRegion.find {
                    (DaoStageRegions.project eq project.id) and (DaoStageRegions.name eq name)
                }.firstOrNull() ?: DaoStageRegion.new {
                    this.project = project
                    this.name = name
                    sortOrder = regionOrder++
                }
                if ("centerX" in row) region.centerX = row.double("centerX")
                if ("centerY" in row) region.centerY = row.double("centerY")
                if ("centerZ" in row) region.centerZ = row.double("centerZ")
                if ("widthM" in row) region.widthM = row.double("widthM")
                if ("depthM" in row) region.depthM = row.double("depthM")
                if ("heightM" in row) region.heightM = row.double("heightM")
                if ("yawDeg" in row) region.yawDeg = row.double("yawDeg")
            }
            var riggingOrder = (DaoRigging.find { DaoRiggings.project eq project.id }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for ((name, row) in riggings) {
                val rigging = DaoRigging.find {
                    (DaoRiggings.project eq project.id) and (DaoRiggings.name eq name)
                }.firstOrNull() ?: DaoRigging.new {
                    this.project = project
                    this.name = name
                    sortOrder = riggingOrder++
                }
                if ("kind" in row) rigging.kind = row.string("kind")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                if ("x" in row) rigging.positionX = row.double("x")
                if ("y" in row) rigging.positionY = row.double("y")
                if ("z" in row) rigging.positionZ = row.double("z")
                if ("yawDeg" in row) rigging.yawDeg = row.double("yawDeg")
                if ("pitchDeg" in row) rigging.pitchDeg = row.double("pitchDeg")
                if ("rollDeg" in row) rigging.rollDeg = row.double("rollDeg")
                if ("lengthM" in row) rigging.lengthM = row.double("lengthM")
            }
            for (name in removeRegions) {
                DaoStageRegion.find { (DaoStageRegions.project eq project.id) and (DaoStageRegions.name eq name) }
                    .forEach { it.delete() }
            }
            for (name in removeRiggings) {
                DaoRigging.find { (DaoRiggings.project eq project.id) and (DaoRiggings.name eq name) }.forEach { rigging ->
                    // As the rigging route's delete: detach first, since Exposed does not enforce
                    // the FK's SET NULL.
                    DaoFixturePatch.find { DaoFixturePatches.rigging eq rigging.id }.forEach { it.rigging = null; detached++ }
                    rigging.delete()
                }
            }
        }
        if (regions.isNotEmpty() || removeRegions.isNotEmpty()) state.show.fixtures.stageRegionListChanged()
        if (riggings.isNotEmpty() || removeRiggings.isNotEmpty()) {
            state.show.fixtures.riggingListChanged()
            // Rig-mounted fixtures' world positions derive from the rigging pose.
            state.show.fixtures.patchListChanged()
        }
        return success(
            "Stage updated: ${regions.size} region(s) and ${riggings.size} rigging(s) set, " +
                "${removeRegions.size} region(s) and ${removeRiggings.size} rigging(s) removed",
            buildJsonObject {
                put("regionsSet", regions.size)
                put("riggingsSet", riggings.size)
                put("regionsRemoved", removeRegions.size)
                put("riggingsRemoved", removeRiggings.size)
                put("fixturesDetached", detached)
            },
        )
    }

    // ─── The show: prompt book and cue stacks ───────────────────────────

    private fun noPromptBookMessage(): String {
        val where = deskUrl()?.let { "$it/prompt-book" } ?: "the desk's Prompt Book view (/prompt-book)"
        return "This project has no prompt book yet. The desk must hold the script PDF itself, and a tool cannot " +
            "upload a file: ask the operator to open $where and import the PDF, then call get_prompt_book again."
    }

    private fun getPromptBook(): ToolExecutionResult {
        val project = state.projectManager.currentProject
        val body = transaction(state.database) {
            val book = DaoPromptBook.find { DaoPromptBooks.project eq project.id }.firstOrNull()
                ?: return@transaction null
            val cues = DaoCue.find { DaoCues.project eq project.id }.associateBy { it.id.value }
            val stackNames = DaoCueStack.find { DaoCueStacks.project eq project.id }.associate { it.id.value to it.name }
            val anchors = book.anchors.toList()
            buildJsonObject {
                book.scriptFileName?.let { put("fileName", it) }
                put("pageCount", book.pageCount)
                put("coverPages", book.coverPages)
                putJsonArray("anchors") {
                    anchors.sortedWith(compareBy({ it.region.firstOrNull()?.page }, { it.region.firstOrNull()?.y })).forEach { a ->
                        val cue = cues[a.cueId]
                        addJsonObject {
                            put("cueId", a.cueId)
                            cue?.let {
                                it.cueNumber?.let { n -> put("cueNumber", n) }
                                put("cueName", it.name)
                                stackNames[it.readValues[DaoCues.cueStack].value]?.let { s -> put("stack", s) }
                            }
                            a.region.firstOrNull()?.let { putPlace(it) }
                        }
                    }
                }
                putJsonArray("notes") {
                    book.annotations.forEach { n ->
                        addJsonObject {
                            put("kind", n.kind)
                            n.tone?.let { put("tone", it) }
                            n.text?.let { put("text", it) }
                            n.region.firstOrNull()?.let { putPlace(it) }
                        }
                    }
                }
                val anchored = anchors.map { it.cueId }.toSet()
                put("unanchoredCueCount", cues.values.count { it.cueType == CueType.STANDARD.name && it.id.value !in anchored })
            }
        } ?: return success(noPromptBookMessage(), buildJsonObject {
            put("hasPromptBook", false)
            put("message", noPromptBookMessage())
        })
        return success("Prompt book of '${project.name}'", body)
    }

    private fun JsonObjectBuilder.putPlace(rect: PromptBookRectDto) {
        put("pdfPage", rect.page + 1)
        put("y", rect.y)
    }

    /**
     * A tool's `at` object as one normalised rectangle, or null after adding the problem. Width and
     * height are clamped to the page rather than refused: the model aims at a line, and a default
     * height that runs off the bottom of the page is the default's fault, not the model's.
     */
    private fun parsePlace(element: JsonElement?, pageCount: Int, where: String, problems: MutableList<String>): PromptBookRectDto? {
        val at = element as? JsonObject ?: run { problems += "$where: 'at' must be an object"; return null }
        val page = at.int("pdfPage")
        val y = at.double("y")
        if (page == null || page !in 1..pageCount) {
            problems += "$where: pdfPage must be 1–$pageCount (the PDF's own page order)"
            return null
        }
        if (y == null || !y.isFinite() || y < 0.0 || y >= 1.0) {
            problems += "$where: y must be at least 0 and below 1"
            return null
        }
        val x = at.double("x") ?: DEFAULT_PLACE_X
        if (!x.isFinite() || x < 0.0 || x >= 1.0) {
            problems += "$where: x must be at least 0 and below 1"
            return null
        }
        val w = (at.double("width") ?: DEFAULT_PLACE_WIDTH).coerceAtMost(1.0 - x)
        val h = (at.double("height") ?: DEFAULT_PLACE_HEIGHT).coerceAtMost(1.0 - y)
        val rect = PromptBookRectDto(page = page - 1, x = x, y = y, w = w, h = h)
        checkPromptBookRegion(listOf(rect), pageCount)?.let { problems += "$where: $it"; return null }
        return rect
    }

    /** One cue of a build_cue_stack call, validated. */
    private data class ShowCue(
        val number: String?,
        val name: String,
        val notes: String?,
        val fade: Duration?,
        val fadeCurve: String,
        val follow: Duration?,
        val marker: Boolean,
        val layers: List<CueLayerDto>,
        val place: PromptBookRectDto?,
    )

    private fun buildCueStack(input: JsonObject): ToolExecutionResult {
        val cueRows = input["cues"] as? JsonArray ?: return failure("Missing 'cues'")
        if (cueRows.isEmpty()) return failure("'cues' is empty")
        val stackId = input.int("stackId")
        val stackName = input.string("stackName")?.trim()
        val project = state.projectManager.currentProject
        val problems = mutableListOf<String>()

        if (stackId == null && stackName.isNullOrEmpty()) problems += "give stackName for a new stack, or stackId to append to one"
        if (stackName != null && stackName.length > 255) problems += "stackName must be at most 255 characters"

        data class Context(val stackError: String?, val takenNumbers: Set<String>, val pageCount: Int?)
        val context = transaction(state.database) {
            val pageCount = DaoPromptBook.find { DaoPromptBooks.project eq project.id }.firstOrNull()?.pageCount
            if (stackId != null) {
                val stack = DaoCueStack.findById(stackId)
                val error = when {
                    stack == null || stack.project.id != project.id -> "no cue stack $stackId in this project"
                    stack.type != CueStackType.STACK.name -> "cue stack $stackId is a separator, not a stack"
                    else -> null
                }
                val numbers = stack?.cues?.filter { it.cueType == CueType.STANDARD.name }?.mapNotNull { it.cueNumber }?.toSet().orEmpty()
                Context(error, numbers, pageCount)
            } else {
                val clash = !DaoCueStack.find {
                    (DaoCueStacks.project eq project.id) and (DaoCueStacks.name eq stackName.orEmpty()) and
                        (DaoCueStacks.type eq CueStackType.STACK.name)
                }.empty()
                Context(if (clash) "a cue stack named '$stackName' already exists — pass its stackId to append to it" else null, emptySet(), pageCount)
            }
        }
        context.stackError?.let { problems += it }

        val seenNumbers = mutableMapOf<String, Int>()
        val cues = cueRows.mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "cues[$index]: not an object"; return@mapIndexedNotNull null }
            val name = row.string("name")?.trim().orEmpty()
            val where = "cues[$index] ('$name')"
            val rowProblems = mutableListOf<String>()
            val marker = row.boolean("marker") == true
            if (name.isEmpty()) rowProblems += "name must not be blank"
            if (name.length > 255) rowProblems += "name must be at most 255 characters"
            val number = row.string("number")?.trim()?.takeIf { it.isNotEmpty() && !marker }
            if (number != null) {
                if (number.length > MAX_CUE_NUMBER_LENGTH) rowProblems += "number must be at most $MAX_CUE_NUMBER_LENGTH characters"
                seenNumbers[number]?.let { rowProblems += "number '$number' is also used by cues[$it]" }
                seenNumbers.putIfAbsent(number, index)
                if (number in context.takenNumbers) rowProblems += "the stack already has a cue numbered '$number'"
            }
            val fade = seconds(row, "fadeSeconds", rowProblems)
            val follow = seconds(row, "followSeconds", rowProblems)
            val curve = row.string("fadeCurve")?.trim()?.uppercase() ?: EasingCurve.LINEAR.name
            if (EasingCurve.entries.none { it.name == curve }) {
                rowProblems += "fadeCurve must be one of ${EasingCurve.entries.joinToString { it.name }}"
            }
            val layers = try {
                aiTools.parseCueLayers(row["layers"] as? JsonArray).also { parsed ->
                    validateCueChildren(emptyList(), parsed)?.let { rowProblems += it }
                }
            } catch (e: Exception) {
                rowProblems += "layers: ${e.message ?: "malformed"}"
                emptyList()
            }
            if (marker && layers.isNotEmpty()) rowProblems += "a marker cannot carry layers"
            val place = row["at"]?.let { at ->
                if (context.pageCount == null) {
                    rowProblems += "'at' needs a prompt book, and this project has none — import the PDF first, or leave 'at' out and anchor later with mark_up_prompt_book"
                    null
                } else parsePlace(at, context.pageCount, "at", rowProblems)
            }
            if (rowProblems.isNotEmpty()) {
                problems += rowProblems.map { "$where: $it" }
                null
            } else ShowCue(number, name, row.string("notes")?.trim()?.takeIf { it.isNotEmpty() }, fade, curve, follow, marker, layers, place)
        }
        if (problems.isNotEmpty()) return rejected(problems)

        data class Built(val stackId: Int, val stackName: String, val cues: List<Triple<Int, String?, String>>, val anchors: Int)
        val built = transaction(state.database) {
            val stack = stackId?.let { DaoCueStack.findById(it)!! } ?: DaoCueStack.new {
                name = stackName!!
                this.project = project
                loop = input.boolean("loop") == true
                type = CueStackType.STACK.name
                sortOrder = (project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1
            }
            var sortOrder = (stack.cues.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val created = cues.map { spec ->
                val cue = DaoCue.new {
                    name = spec.name
                    this.project = project
                    cueStack = stack
                    this.sortOrder = sortOrder++
                    cueType = if (spec.marker) CueType.MARKER.name else CueType.STANDARD.name
                    cueNumber = spec.number
                    notes = spec.notes
                    fadeDuration = spec.fade
                    fadeCurve = spec.fadeCurve
                    autoAdvance = spec.follow != null
                    autoAdvanceDelay = spec.follow
                }
                createCueChildren(cue, emptyList(), layers = spec.layers)
                cue to spec
            }
            // Unnumbered cues take their labels from their numbered neighbours.
            renumberAutoCues(stack)

            var anchors = 0
            val book = if (created.any { it.second.place != null }) {
                DaoPromptBook.find { DaoPromptBooks.project eq project.id }.first()
            } else null
            for ((cue, spec) in created) {
                val place = spec.place ?: continue
                DaoPromptBookAnchor.new {
                    promptBook = book!!
                    this.cue = cue
                    region = listOf(place)
                    label = anchorLabel(cue)
                }
                anchors++
            }
            Built(stack.id.value, stack.name, created.map { (cue, _) -> Triple(cue.id.value, cue.cueNumber, cue.name) }, anchors)
        }
        state.show.fixtures.cueListChanged()
        state.show.fixtures.cueStackListChanged()
        if (built.anchors > 0) state.show.fixtures.promptBookChanged()

        return success(
            "${if (stackId == null) "Created" else "Appended to"} stack '${built.stackName}' (id=${built.stackId}): " +
                "${built.cues.size} cue(s), ${built.anchors} anchored in the prompt book",
            buildJsonObject {
                put("stackId", built.stackId)
                put("stackName", built.stackName)
                put("anchorsCreated", built.anchors)
                putJsonArray("cues") {
                    built.cues.forEach { (id, number, name) ->
                        addJsonObject {
                            put("cueId", id)
                            number?.let { put("number", it) }
                            put("name", name)
                        }
                    }
                }
            },
        )
    }

    private fun markUpPromptBook(input: JsonObject): ToolExecutionResult {
        val project = state.projectManager.currentProject
        data class Book(val id: Int, val pageCount: Int)
        val (book, cueIds) = transaction(state.database) {
            DaoPromptBook.find { DaoPromptBooks.project eq project.id }.firstOrNull()?.let { Book(it.id.value, it.pageCount) } to
                DaoCue.find { DaoCues.project eq project.id }.map { it.id.value }.toSet()
        }
        if (book == null) return failure(noPromptBookMessage())

        val problems = mutableListOf<String>()
        val coverPages = input.int("coverPages")
        if (input["coverPages"] != null && (coverPages == null || coverPages !in 0 until book.pageCount)) {
            problems += "coverPages must be 0–${book.pageCount - 1}"
        }
        val anchors = (input["anchors"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "anchors[$index]: not an object"; return@mapIndexedNotNull null }
            val cueId = row.int("cueId")
            if (cueId == null || cueId !in cueIds) {
                problems += "anchors[$index]: no cue $cueId in this project"
                return@mapIndexedNotNull null
            }
            parsePlace(row["at"], book.pageCount, "anchors[$index] (cue $cueId)", problems)?.let { cueId to it }
        }
        anchors.groupBy { it.first }.filter { it.value.size > 1 }.keys.forEach { problems += "cue $it is anchored twice" }
        val notes = (input["notes"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "notes[$index]: not an object"; return@mapIndexedNotNull null }
            val kind = row.string("kind")?.trim()?.uppercase() ?: PromptBookAnnotationKind.NOTE.name
            val tone = row.string("tone")?.trim()?.uppercase()
            val text = row.string("text")?.trim()?.takeIf { it.isNotEmpty() }
            val before = problems.size
            if (PromptBookAnnotationKind.entries.none { it.name == kind }) {
                problems += "notes[$index]: kind must be one of ${PromptBookAnnotationKind.entries.joinToString { it.name }}"
            }
            if (tone != null && (kind != PromptBookAnnotationKind.NOTE.name || PromptBookNoteTone.entries.none { it.name == tone })) {
                problems += "notes[$index]: tone is for NOTE only, one of ${PromptBookNoteTone.entries.joinToString { it.name }}"
            }
            if (text == null && kind != PromptBookAnnotationKind.STRIKETHROUGH.name) problems += "notes[$index]: a $kind needs text"
            val place = parsePlace(row["at"], book.pageCount, "notes[$index]", problems)
            if (problems.size > before || place == null) null
            else Triple(kind, tone, text.takeIf { kind != PromptBookAnnotationKind.STRIKETHROUGH.name }) to place
        }
        if (coverPages == null && anchors.isEmpty() && notes.isEmpty() && problems.isEmpty()) {
            problems += "nothing to change: send coverPages, anchors or notes"
        }
        if (problems.isNotEmpty()) return rejected(problems)

        transaction(state.database) {
            val dao = DaoPromptBook.findById(book.id)!!
            coverPages?.let { dao.coverPages = it }
            for ((cueId, place) in anchors) {
                val cue = DaoCue.findById(cueId)!!
                val anchor = DaoPromptBookAnchor.find {
                    (DaoPromptBookAnchors.promptBook eq dao.id) and (DaoPromptBookAnchors.cue eq cue.id)
                }.firstOrNull() ?: DaoPromptBookAnchor.new {
                    promptBook = dao
                    this.cue = cue
                    region = listOf(place)
                }
                anchor.region = listOf(place)
                anchor.label = anchorLabel(cue)
            }
            for ((note, place) in notes) {
                DaoPromptBookAnnotation.new {
                    promptBook = dao
                    kind = note.first
                    tone = note.second
                    text = note.third
                    region = listOf(place)
                }
            }
        }
        state.show.fixtures.promptBookChanged()
        return success(
            "Prompt book updated: ${anchors.size} cue(s) anchored, ${notes.size} note(s) added" +
                (coverPages?.let { ", $it cover page(s)" } ?: ""),
            buildJsonObject {
                put("anchored", anchors.size)
                put("notesAdded", notes.size)
                coverPages?.let { put("coverPages", it) }
            },
        )
    }

    /** The anchor's cached label, as the Prompt Book view writes it: `Q<number>`, else the name. */
    private fun anchorLabel(cue: DaoCue): String =
        (cue.cueNumber?.let { "Q$it" } ?: cue.name).take(64)

    private fun seconds(row: JsonObject, name: String, problems: MutableList<String>): Duration? {
        val element = row[name] ?: return null
        if (element is JsonNull) return null
        val value = (element as? JsonPrimitive)?.doubleOrNull
        if (value == null || !value.isFinite() || value < 0.0 || value > MAX_CUE_SECONDS) {
            problems += "$name must be a number of seconds, 0–${MAX_CUE_SECONDS.toInt()}"
            return null
        }
        return Duration.ofMillis(Math.round(value * 1000))
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun failure(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )

    /** Every problem at once — see the class doc for why this surface never stops at the first. */
    private fun rejected(problems: List<String>): ToolExecutionResult {
        val shown = problems.take(MAX_PROBLEMS)
        return ToolExecutionResult(
            success = false,
            description = "Nothing was written: ${problems.size} problem(s)",
            result = buildJsonObject {
                put("error", "Nothing was written: fix these ${problems.size} problem(s) and send the request again")
                putJsonArray("problems") { shown.forEach { add(it) } }
                if (problems.size > shown.size) put("moreProblems", problems.size - shown.size)
            }.toString(),
        )
    }

    private fun JsonObjectBuilder.putOptional(name: String, value: Double?) {
        value?.let { put(name, it) }
    }

    private companion object {
        const val MAX_PROBLEMS = 100
        const val MAX_CUE_SECONDS = 3600.0
        const val DEFAULT_PLACE_X = 0.06
        const val DEFAULT_PLACE_WIDTH = 0.88
        const val DEFAULT_PLACE_HEIGHT = 0.03

        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
        fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { p ->
            p.intOrNull ?: p.doubleOrNull?.takeIf { it == Math.floor(it) && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
        }
        fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.stringList(name: String): List<String> =
            (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

        /** A key from a display name: "FOH 1 (SL)" → "foh-1-sl". */
        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(100)
    }
}
