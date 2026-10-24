package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.fixture.locate.StoredLocate
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.routes.patchMediaRefusal
import uk.me.cormack.lighting7.routes.PatchFocusWrite
import uk.me.cormack.lighting7.routes.placementFocusRefusal
import uk.me.cormack.lighting7.routes.reassertLocates
import uk.me.cormack.lighting7.routes.syncStoredLocates
import uk.me.cormack.lighting7.routes.resolvePatchFocus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
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
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.group.GroupAttributeDescriptor
import uk.me.cormack.lighting7.fixture.group.GroupSettingAttributeDescriptor
import uk.me.cormack.lighting7.fixture.group.GroupWhiteMixAttributeDescriptor
import uk.me.cormack.lighting7.routes.ColourAttributeDescriptor
import uk.me.cormack.lighting7.routes.PositionAttributeDescriptor
import uk.me.cormack.lighting7.routes.AttributeDescriptor
import uk.me.cormack.lighting7.routes.SettingAttributeDescriptor
import uk.me.cormack.lighting7.routes.WhiteMixAttributeDescriptor
import uk.me.cormack.lighting7.routes.SliderAttributeDescriptor
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.MAX_CUE_NUMBER_LENGTH
import uk.me.cormack.lighting7.routes.listedProjects
import uk.me.cormack.lighting7.routes.PlacementInput
import uk.me.cormack.lighting7.routes.applyExtraPlacements
import uk.me.cormack.lighting7.routes.clearStaleInheritedZooms
import uk.me.cormack.lighting7.routes.writeCueChildren
import uk.me.cormack.lighting7.routes.deleteFixtureGroupRows
import uk.me.cormack.lighting7.routes.fixedLengthRefusal
import uk.me.cormack.lighting7.routes.lamplessRefusal
import uk.me.cormack.lighting7.routes.normaliseGelCode
import uk.me.cormack.lighting7.routes.parseHeadNumber
import uk.me.cormack.lighting7.routes.normaliseKindOverride
import uk.me.cormack.lighting7.routes.placeCueInStack
import uk.me.cormack.lighting7.routes.announceEntriesPlaced
import uk.me.cormack.lighting7.routes.cueEntriesInOrder
import uk.me.cormack.lighting7.routes.cueEntryIds
import uk.me.cormack.lighting7.routes.renumberAutoEntries
import uk.me.cormack.lighting7.routes.unlinkRegionFromPlatforms
import uk.me.cormack.lighting7.routes.validateCueChildren
import uk.me.cormack.lighting7.routes.validateRiggingPose
import uk.me.cormack.lighting7.routes.validateStageDimensions
import uk.me.cormack.lighting7.routes.validateStageMetadata
import uk.me.cormack.lighting7.routes.validateStageRegion
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.RiggingPose
import uk.me.cormack.lighting7.show.worldPosition
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.routes.ProjectVenueDto
import uk.me.cormack.lighting7.routes.venueDto
import uk.me.cormack.lighting7.venue.SeatingPresets
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenueBehind
import uk.me.cormack.lighting7.venue.VenueVersions
import uk.me.cormack.lighting7.venue.ShowOverrides
import uk.me.cormack.lighting7.venue.VenueRigGuard
import java.time.Duration
import java.util.UUID

/**
 * The MCP server's show-setup tools ([setupToolDefs]): build a project, its patch, its stage and
 * the show's cue stacks and prompt-book markup from material the model has read — a patch export,
 * a plot, a photo, a script and a designer's notes.
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
    val toolDefs: List<ToolDef> = setupToolDefs
    private val scene = SceneSetupTools(state)
    private val render = RenderViewTool(state)
    private val venues = VenueSetupTools(state)
    private val venueFixtures = VenueFixtureTools(state)
    private val patchEdits = PatchEditTools(state)

    fun handles(name: String): Boolean = toolDefs.any { it.name == name }

    /** Run [name]; [caller] is the signed-in user's display name, which signs what a tool publishes. */
    suspend fun executeTool(name: String, input: JsonObject, caller: String? = null): ToolExecutionResult = try {
        val refusal = toolDefs.firstOrNull { it.name == name }?.let { undeclaredArgumentRefusal(it, input) }
        if (refusal != null) failure(refusal) else when (name) {
            listProjectsTool.name -> listProjects()
            createProjectTool.name -> createProject(input)
            switchProjectTool.name -> switchProject(input)
            listVenuesTool.name -> venues.listVenues()
            extractVenueTool.name -> venues.extractVenue(input, caller)
            attachToVenueTool.name -> venues.attachToVenue(input)
            publishVenueTool.name -> venues.publishVenue(input, caller)
            copyToProjectTool.name -> venues.copyToProject(input)
            deleteCueStacksTool.name -> venues.deleteCueStacks(input)
            deleteCuesTool.name -> venues.deleteCues(input)
            copyCuesTool.name -> venues.copyCues(input)
            listFixtureTypesTool.name -> listFixtureTypes(input)
            getPatchTool.name -> getPatch(state.toolProject(input))
            patchFixturesTool.name -> patchFixtures(input, state.toolProject(input))
            deleteGroupsTool.name -> deleteGroups(input, state.toolProject(input))
            unpatchFixturesTool.name -> patchEdits.unpatchFixtures(input, state.toolProject(input))
            renameFixturesTool.name -> patchEdits.renameFixtures(input, state.toolProject(input))
            editGroupsTool.name -> patchEdits.editGroups(input, state.toolProject(input))
            setStageTool.name -> setStage(input, state.toolProject(input))
            setSceneTool.name -> scene.setScene(input, state.toolProject(input))
            getSceneTool.name -> scene.getScene(state.toolProject(input))
            uploadSceneImageTool.name -> scene.uploadSceneImage(input, state.toolProject(input))
            renderViewTool.name -> render.renderView(input)
            placeFixturesTool.name -> placeFixtures(input, state.toolProject(input))
            overrideVenueFixturesTool.name -> venueFixtures.overrideVenueFixtures(input, state.toolProject(input))
            getPromptBookTool.name -> getPromptBook(state.toolProject(input))
            buildCueStackTool.name -> buildCueStack(input, state.toolProject(input))
            markUpPromptBookTool.name -> markUpPromptBook(input)
            else -> failure("Unknown tool: $name")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ToolProjectException) {
        failure(e.message ?: "No such project")
    } catch (e: VenueException) {
        // A venue rule (venue plan session 3), refused with its code as the REST route refuses it.
        ToolExecutionResult(
            success = false,
            description = e.message ?: e.code,
            result = buildJsonObject {
                put("error", e.message ?: e.code)
                put("code", e.code)
            }.toString(),
        )
    } catch (e: Exception) {
        failure("Error executing $name: ${e.message ?: e::class.simpleName}")
    }

    // ─── Projects ───────────────────────────────────────────────────────

    private fun listProjects(): ToolExecutionResult {
        val projects = transaction(state.database) {
            // A venue version is never listed (venue plan P2): it is frozen, and reached through its venue.
            listedProjects().orderBy(DaoProjects.name to SortOrder.ASC).map { p ->
                buildJsonObject {
                    put("id", p.id.value)
                    put("name", p.name)
                    p.description?.let { put("description", it) }
                    put("isCurrent", p.isCurrent)
                    if (p.kind != ProjectKind.SHOW) put("kind", p.kind.name)
                    p.venueDto(state)?.let { putVenue(it) }
                    if (p.kind == ProjectKind.VENUE) {
                        VenueVersions.versionsOf(p.id.value).firstOrNull()?.let { put("installedVersion", it.number) }
                    }
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
        val venueId = input.int("venueId")
        val seatingPreset = input.string("seatingPreset")?.trim()?.takeIf { it.isNotEmpty() }
        val problems = buildList {
            if (name.isEmpty()) add("name must not be blank")
            if (name.length > 50) add("name must be at most 50 characters")
            if ((description?.length ?: 0) > 255) add("description must be at most 255 characters")
            addAll(malformedNumbers(input, listOf("stageWidthM", "stageDepthM", "stageHeightM", "venueId"), "project"))
            validateStageDimensions(width, depth, height)?.let { add(it) }
            if (venueId != null && listOf(width, depth, height).any { it != null }) {
                add("a show on a venue takes the venue's stage size: leave stageWidthM, stageDepthM and stageHeightM out")
            }
            if (seatingPreset != null && venueId == null) add("seatingPreset is a venue's: give venueId too")
        }
        if (problems.isNotEmpty()) return rejected(problems)

        data class Created(val id: Int, val venue: String?, val version: Int?, val seating: String?)
        val created = transaction(state.database) {
            if (!DaoProject.find { DaoProjects.name eq name }.empty()) return@transaction null
            // As POST /projects with venueId (venue plan session 3): pinned to the installed version.
            val pin = venueId?.let { id ->
                val venue = VenueVersions.requireVenue(id)
                val installed = VenueVersions.versionsOf(id).firstOrNull()
                    ?: throw VenueException.conflict(
                        VenueVersions.CODE_NOTHING_PUBLISHED,
                        "${venue.name} has no published version yet: publish it before a show can sit on it",
                    )
                venue to installed.number
            }
            val presetUuid = seatingPreset?.let { preset ->
                val offered = SeatingPresets.list(pin!!.first).presets
                (offered.firstOrNull { it.name == preset } ?: offered.firstOrNull { it.uuid == preset })?.uuid
                    ?: throw VenueException.badRequest(
                        SeatingPresets.CODE_PRESET_UNKNOWN,
                        "${pin.first.name} v${pin.second} has no seating preset '$preset'" +
                            if (offered.isEmpty()) " (it offers none)" else " (it offers: ${offered.joinToString { "'${it.name}'" }})",
                    )
            }
            val project = DaoProject.new {
                this.name = name
                this.description = description
                isCurrent = false
                if (pin == null) {
                    stageWidthM = width
                    stageDepthM = depth
                    stageHeightM = height
                }
                venueUuid = pin?.first?.uuid
                venueVersion = pin?.second
            }
            ensureDefaultSpeedMasters(project)
            val seating = presetUuid?.let { SeatingPresets.copyOnCreate(project, pin!!.first, it).name }
            Created(project.id.value, pin?.first?.name, pin?.second, seating)
        } ?: return failure("A project named '$name' already exists")

        val switched = input.boolean("switchTo") == true
        if (switched) state.projectManager.switchProject(created.id)
        return success(
            "Created project '$name' (id=${created.id})" +
                (created.venue?.let { " on $it v${created.version}" } ?: "") +
                if (switched) " and made it current" else "",
            buildJsonObject {
                put("projectId", created.id)
                put("name", name)
                put("isCurrent", switched)
                created.venue?.let {
                    putJsonObject("venue") {
                        put("name", it)
                        put("version", created.version)
                    }
                    created.seating?.let { s -> put("seating", s) }
                    put(
                        "note",
                        "The show reads the venue's rig and scene at v${created.version}; its own set, cues and prompt book " +
                            "start empty. Build it with projectId=${created.id} on the setup tools, or copy_to_project.",
                    )
                }
            },
        )
    }

    /** A show's venue, as list_projects answers it. */
    private fun JsonObjectBuilder.putVenue(v: ProjectVenueDto) {
        putJsonObject("venue") {
            v.id?.let { put("id", it) }
            put("name", v.name ?: "(not on this desk)")
            put("version", v.version)
            v.installed?.let { put("installed", it) }
            if (v.behind != VenueBehind.NONE) put("behind", v.behind.name)
        }
    }

    private suspend fun switchProject(input: JsonObject): ToolExecutionResult {
        val projectId = input.int("projectId") ?: return failure("Missing 'projectId'")
        val name = transaction(state.database) {
            // A venue version answers as absent, as it is absent from list_projects.
            DaoProject.findById(projectId)?.takeIf { it.kind != ProjectKind.VENUE_VERSION }?.name
        } ?: return failure("Project not found: $projectId")
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
        val withAttributes = input["attributes"]?.jsonPrimitive?.booleanOrNull == true
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
                        if (t.acceptsLength) {
                            put("acceptsLength", true)
                            put("defaultLengthM", t.lengthM)
                        }
                        if (t.acceptsLantern) put("acceptsLantern", true)
                        // Its loadable settings — what `media` may name — and each one's slots.
                        val loadable = FittedMedia.loadableSettings(t.typeKey)
                        if (loadable.isNotEmpty()) {
                            putJsonObject("loadable") {
                                loadable.values.forEach { setting ->
                                    putJsonObject(setting.name) {
                                        put("takes", setting.media)
                                        putJsonArray("slots") {
                                            setting.options.filter { it.loadable == true }.forEach { o ->
                                                addJsonObject {
                                                    put("option", o.name)
                                                    o.colourPreview?.let { put("stockColour", it) }
                                                    o.gobo?.let { put("stockGobo", it) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        putJsonArray("capabilities") { t.capabilities.forEach { add(it) } }
                        if (withAttributes) {
                            putJsonArray("attributes") { t.attributes.forEach { addTypeAttribute(it) } }
                            t.elementGroupAttributes?.takeIf { it.isNotEmpty() }?.let { cells ->
                                putJsonArray("cellAttributes") { cells.forEach { addCellAttribute(it) } }
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * One settable property of a type, as a Look value names it. One-shot triggers and fixture
     * commands are left out: no stored value may hold either.
     */
    private fun JsonArrayBuilder.addTypeAttribute(attribute: AttributeDescriptor) {
        val kind = when (attribute) {
            is SliderAttributeDescriptor -> "slider"
            is ColourAttributeDescriptor -> "colour"
            is WhiteMixAttributeDescriptor -> "whiteMix"
            is PositionAttributeDescriptor -> "position"
            is SettingAttributeDescriptor -> "setting"
            else -> return
        }
        addJsonObject {
            put("name", attribute.name)
            put("kind", kind)
            put("category", attribute.category)
            if (attribute is SettingAttributeDescriptor) putJsonArray("options") { attribute.options.forEach { add(it.name) } }
            if (attribute is WhiteMixAttributeDescriptor) whiteMix(attribute.warmKelvin, attribute.coldKelvin)
        }
    }

    /** One property every cell (head) of a multi-head type has, for a value on a cell's own key. */
    private fun JsonArrayBuilder.addCellAttribute(attribute: GroupAttributeDescriptor) {
        addJsonObject {
            put("name", attribute.name)
            put("category", attribute.category)
            if (attribute is GroupSettingAttributeDescriptor) putJsonArray("options") { attribute.options.forEach { add(it.name) } }
            if (attribute is GroupWhiteMixAttributeDescriptor) whiteMix(attribute.warmKelvin, attribute.coldKelvin)
        }
    }

    /** A warm/cold white pair takes any colour as the nearest mix of its two whites. */
    private fun JsonObjectBuilder.whiteMix(warmKelvin: Int, coldKelvin: Int) {
        put("whites", "warm $warmKelvin K, cold $coldKelvin K — a colour lands as their nearest mix")
    }

    private fun getPatch(target: ToolProject): ToolExecutionResult {
        val current = target.project
        val body = transaction(state.database) {
            // Re-read the row: `currentProject` is the entity loaded at the last project switch,
            // and its columns are that load's values — a set_stage since then would read stale.
            val project = DaoProject.findById(current.id) ?: current
            val rig = RigScope.of(project)
            val riggings = rig.riggings()
                .orderBy(DaoRiggings.sortOrder to SortOrder.ASC).toList()
            val riggingNames = riggings.associate { it.id.value to it.name }
            val riggingPoses = riggings.associate { r ->
                r.id.value to RiggingPose(r.positionX, r.positionY, r.positionZ, r.yawDeg, r.pitchDeg, r.rollDeg)
            }
            val regions = rig.stageRegions()
                .orderBy(DaoStageRegions.sortOrder to SortOrder.ASC).toList()
            val universes = rig.universeConfigs()
                .sortedWith(compareBy({ it.subnet }, { it.universe }))
            val universeById = universes.associateBy { it.id.value }
            // As the rig sees them: a venue patch's facet overrides (venue plan session 5) laid over it.
            val patchRows = rig.patches()
                .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC).toList()
            val patches = rig.effective(patchRows)
            val groups = rig.groups()
                .orderBy(DaoFixtureGroups.name to SortOrder.ASC).toList()
            val keyByPatchId = patches.associate { it.id.value to it.key }
            val placementsByPatch = rig.effectivePlacementsByPatch(patchRows)
            val driftByPatch = ShowOverrides.drift(project).filter { !it.missing }.groupBy { it.recordUuid }
            val membersByGroup = groups.associate { g ->
                g.name to rig.members(g).sortedBy { it.sortOrder }.mapNotNull { keyByPatchId[it.fixturePatch.id.value] }
            }
            val groupsByKey = mutableMapOf<String, MutableList<String>>()
            membersByGroup.forEach { (group, keys) -> keys.forEach { groupsByKey.getOrPut(it) { mutableListOf() }.add(group) } }

            // A show on a venue (venue plan session 3): every record says whose it is, and the stage's
            // size is the venue's.
            val venue = rig.venue
            fun JsonObjectBuilder.putOwner(rowProject: EntityID<Int>) {
                if (venue != null) put("owner", if (rig.ownerOf(rowProject) == RigOwner.VENUE) "venue" else "show")
            }
            val bounds = rig.stageBounds()
            buildJsonObject {
                put("project", project.name)
                venue?.let {
                    putJsonObject("venue") {
                        put("name", it.venueName)
                        put("version", it.version)
                        put(
                            "note",
                            "Records with owner=venue are the venue's: the setup tools refuse a write to one (VENUE_OWNED). A show " +
                                "can make a venue fixture's address, position, focus or media its own, or hide it, with " +
                                "override_venue_fixtures; place_fixtures then edits the position, focus and media this show owns. " +
                                "A fixture's `overrides` lists those facets; its fields are already the overridden ones (but for a " +
                                "`dormant` one, which names a universe or rigging the rig lacks, so the venue's stands), and `drifted` " +
                                "marks a facet the venue has changed since (answer it with KEEP_MINE or VENUE). `hiddenFixtures` are " +
                                "the venue's fixtures this show hides.",
                        )
                        val hidden = hiddenVenuePatches(rig)
                        if (hidden.isNotEmpty()) {
                            putJsonArray("hiddenFixtures") {
                                hidden.forEach { p -> addJsonObject { put("key", p.key); put("name", p.displayName) } }
                            }
                        }
                        // A show held behind its venue (session 6) composes and draws but sends zero:
                        // without this a model reads every apply_cue as working while the rig stays dark.
                        val hold = state.venueHold.status.value
                        if (hold.projectId == project.id.value && hold.held) {
                            put(
                                "held",
                                "Output is held: this show is on v${it.version} and v${hold.installed} changes its patch, so every " +
                                    "controller sends zero on every unparked channel. Cues, looks and the programmer still compose and draw, but nothing reaches " +
                                    "the rig until the operator updates the show to the venue's version or runs against v${it.version}.",
                            )
                        }
                    }
                }
                putJsonObject("stage") {
                    putOptional("widthM", bounds.widthM)
                    putOptional("depthM", bounds.depthM)
                    putOptional("heightM", bounds.heightM)
                }
                putJsonArray("regions") {
                    regions.forEach { r ->
                        addJsonObject {
                            put("name", r.name)
                            putOwner(r.project.id)
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
                            putOwner(r.project.id)
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
                            putOwner(u.project.id)
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
                            putOwner(p.project.id)
                            p.headNumber?.let { put("headNumber", it) }
                            put("fixtureTypeKey", p.fixtureTypeKey)
                            universe?.let { put("universe", it.universe) }
                            put("startChannel", p.startChannel)
                            put("endChannel", p.startChannel + count - 1)
                            groupsByKey[p.key]?.let { g -> putJsonArray("groups") { g.forEach { add(it) } } }
                            driftByPatch[p.uuid]?.takeIf { rig.ownerOf(p.project.id) == RigOwner.VENUE }?.let { facets ->
                                putJsonArray("overrides") {
                                    facets.forEach { d ->
                                        addJsonObject {
                                            put("facet", d.facet.name)
                                            if (d.drifted) put("drifted", true)
                                            if (d.dormant) put("dormant", true)
                                        }
                                    }
                                }
                            }
                            val riggingId = p.rigging?.id?.value
                            riggingId?.let { riggingNames[it] }?.let { put("rigging", it) }
                            putOptional("x", p.stageX); putOptional("y", p.stageY); putOptional("z", p.stageZ)
                            putWorld(p.stageX, p.stageY, p.stageZ, riggingId?.let { riggingPoses[it] })
                            putOptional("yawDeg", p.baseYawDeg); putOptional("pitchDeg", p.basePitchDeg)
                            putOptional("rollDeg", p.baseRollDeg)
                            p.beamAngleDeg?.let { put("beamAngleDeg", it) }
                            p.gelCode?.let { put("gelCode", it) }
                            p.lampWatts?.let { put("lampWatts", it) }
                            p.lampKelvin?.let { put("lampKelvin", it) }
                            p.kindOverride?.let { put("kind", it) }
                            putOptional("lengthM", p.lengthM)
                            putFocus(p.focus)
                            putMedia(p.fittedMedia)
                            putLocate(p.locate)
                            if (p.stageHidden) put("stageHidden", true)
                            if (p.infrastructure) put("infrastructure", true)
                            rig.colourOf(p.row)?.let { put("colour", it) }
                            if (rig.colourOwnerOf(p.project.id, p.uuid) == ColourOwner.SHOW) put("colourOwner", "show")
                            val alsoAt = placementsByPatch[p.id.value].orEmpty()
                            if (alsoAt.isNotEmpty()) {
                                putJsonArray("alsoAt") {
                                    alsoAt.forEach { pl ->
                                        addJsonObject {
                                            pl.label?.let { put("label", it) }
                                            val placementRiggingId = pl.rigging?.id?.value
                                            placementRiggingId?.let { riggingNames[it] }?.let { put("rigging", it) }
                                            putOptional("x", pl.stageX); putOptional("y", pl.stageY); putOptional("z", pl.stageZ)
                                            putWorld(pl.stageX, pl.stageY, pl.stageZ, placementRiggingId?.let { riggingPoses[it] })
                                            putOptional("yawDeg", pl.baseYawDeg); putOptional("pitchDeg", pl.basePitchDeg)
                                            putOptional("rollDeg", pl.baseRollDeg)
                                            putOptional("lengthM", pl.lengthM)
                                            putFocus(pl.focus)
                                            putMedia(pl.fittedMedia)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonObject("groups") {
                    membersByGroup.forEach { (group, keys) -> putJsonArray(group) { keys.forEach { add(it) } } }
                }
            }
        }
        return success("Patch and stage of '${body["project"]!!.jsonPrimitive.content}'", body)
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
        /** Null when the row did not carry the key, so an update leaves the flag as it was. */
        val infrastructure: Boolean?,
        /** Held only when the row carried `headNumber`; its value null is an explicit clear. */
        val headNumber: Optional<Int?>?,
        /** The lantern, its focus and the kind it derives, as this row leaves them. */
        val focusWrite: PatchFocusWrite,
        /** The row turns a patch that could hold a lantern into a type that cannot: its focus, its
         *  placements' and the kind a lantern derived all go, whatever the row sent. */
        val dropsLantern: Boolean,
        /** The row changes an existing patch's type: stored media the new type cannot hold goes. */
        val typeChanged: Boolean,
    )

    private fun patchFixtures(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val rows = input["fixtures"] as? JsonArray ?: return failure("Missing 'fixtures'")
        if (rows.isEmpty()) return failure("'fixtures' is empty")
        val dryRun = input.boolean("dryRun") == true
        val project = target.project

        data class Existing(
            val id: Int, val key: String, val universe: Int, val start: Int, val end: Int, val headNumber: Int?,
            val focus: LanternFocus, val kindOverride: String?, val typeKey: String,
            /** The patch is the venue's (venue plan session 3): in the rig, not this show's to change. */
            val venue: Boolean,
        )

        // The scope comes back resolved (its venue read inside the transaction) for the refusals' words.
        val (lists, venueScope) = transaction(state.database) {
            val rig = RigScope.of(project).also { it.venue }
            val universes = rig.universeConfigs()
                .associate { it.id.value to it.universe }
            Triple(
                // As the rig sees them, so an overlap is judged where an Address override put a venue unit.
                rig.effective(rig.patches().toList()).map { p ->
                    val count = FixtureTypeRegistry.channelCountForTypeKey(p.fixtureTypeKey) ?: 1
                    Existing(
                        p.id.value, p.key, universes[p.universeConfig.id.value] ?: -1, p.startChannel,
                        p.startChannel + count - 1, p.headNumber, p.focus, p.kindOverride, p.fixtureTypeKey,
                        venue = rig.ownerOf(p.project.id) == RigOwner.VENUE,
                    )
                },
                riggingIdsByName(project),
                rig.groups().filter { rig.ownerOf(it.project.id) == RigOwner.VENUE }.map { it.name }.toSet(),
            ) to rig
        }
        val (existing, riggingIds, venueGroups) = lists
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
            // A venue's patch is the venue's (hide it in this show to patch your own under its key),
            // and a venue's group never holds a show's fixture (D12).
            if (existingByKey[key]?.venue == true) {
                rowProblems += VenueRigGuard.ownedProblem(venueScope, "Patch '$key'", VenueRigGuard.VENUE_FIXTURE_HINT)
            }
            groups.filter { it in venueGroups }.forEach {
                rowProblems += VenueRigGuard.ownedProblem(venueScope, "Group '$it'")
            }
            val placement = parsePlacement(row, riggingIds, where, rowProblems)
            if (placement.setsLength && FixtureTypeRegistry.typeInfoForKey(typeKey) != null) {
                fixedLengthRefusal(typeKey)?.let { rowProblems += it }
            }
            if (placement.setsLamp && FixtureTypeRegistry.typeInfoForKey(typeKey) != null) {
                lamplessRefusal(typeKey)?.let { rowProblems += it }
            }
            val infrastructure = optionalBoolean(row, "infrastructure", rowProblems)
            val headNumber = row["headNumber"]?.let { element ->
                parseHeadNumber(element).fold({ Optional(it) }, { rowProblems += it.message.orEmpty(); null })
            }
            // A row that turns a patch into a type that takes no lantern leaves no lantern behind: not
            // its focus, not its placements', and not the kind the lantern derived (unless the row
            // names one) — `dropsLantern`, applied below.
            val before = existingByKey[key]
            val acceptsLantern = FixtureTypeRegistry.typeInfoForKey(typeKey)?.acceptsLantern == true
            val dropsLantern = before != null && !acceptsLantern && before.typeKey != typeKey
            val stored = before?.takeIf { acceptsLantern }
            val focusWrite = if (rowProblems.isEmpty()) {
                placement.resolveFocus(typeKey, stored?.focus ?: LanternFocus(), before?.kindOverride, rowProblems)
                    ?.let { w ->
                        if (dropsLantern && placement.kind == null && before.focus.lanternType != null) w.copy(kindOverride = null) else w
                    }
            } else null

            if (rowProblems.isNotEmpty()) {
                problems += rowProblems.map { if (VenueRigGuard.codeOf(listOf(it)) != null) it else "$where: $it" }
            } else {
                parsed += PatchRow(
                    index, key, name, typeKey, universe!!, start!!, end!!, groups, placement, infrastructure, headNumber,
                    focusWrite!!, dropsLantern, typeChanged = before != null && before.typeKey != typeKey,
                )
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
        // Head numbers, likewise against the patch as it will stand: an updated row that does not
        // carry the key keeps its number, a new one without it is unnumbered, and every head that
        // shares a number is named — so a list that swaps two heads' numbers goes through.
        if (problems.isEmpty()) {
            val numberByKey = existing.associate { it.key to it.headNumber }.toMutableMap()
            val labelByKey = existing.associate { it.key to "already-patched '${it.key}'" }.toMutableMap()
            for (row in parsed) {
                labelByKey[row.key] = "fixtures[${row.index}] ('${row.key}')"
                when {
                    row.headNumber != null -> numberByKey[row.key] = row.headNumber.value
                    row.key !in existingByKey -> numberByKey[row.key] = null
                }
            }
            numberByKey.entries.filter { it.value != null }.groupBy({ it.value!! }, { it.key })
                .filterValues { it.size > 1 }.toSortedMap()
                .forEach { (number, keys) ->
                    val clash = "head number $number is on ${keys.joinToString(" and ") { labelByKey.getValue(it) }}"
                    // A venue patch's number is the venue's, as REST says it (KEY_TAKEN_BY_VENUE).
                    problems += if (keys.any { existingByKey[it]?.venue == true }) {
                        "${VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE}: $clash — keys and head numbers are one namespace " +
                            "across a show and its venue; to replace a venue fixture, hide it in this show and add your own"
                    } else {
                        clash
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
            var sortOrder = (RigScope.of(project).own.patches()
                .maxOfOrNull { it.sortOrder } ?: -1) + 1
            // Loaded once rather than per row per group: a patch export runs to hundreds of rows,
            // and the pool's one connection serialises every lookup.
            val groupsByName = RigScope.of(project).own.groups()
                .associateBy { it.name }.toMutableMap()
            val groupsBefore = groupsByName.keys.toSet()
            val members = groupsByName.values.flatMap { g -> g.members.map { g.id.value to it } }
            val memberships = members.map { (groupId, member) -> groupId to member.fixturePatch.id.value }.toMutableSet()
            val nextMemberOrder = groupsByName.values.associate { g ->
                g.id.value to (members.filter { it.first == g.id.value }.maxOfOrNull { it.second.sortOrder } ?: -1) + 1
            }.toMutableMap()
            for (row in parsed) {
                val universeConfig = findUniverse(project, row.universe) ?: DaoUniverseConfig.new {
                    this.project = project
                    subnet = 0
                    universe = row.universe
                    controllerType = "ARTNET"
                    address = null
                }
                // `existing` is the rig as patched, for the clash checks above; the row an update
                // changes is one this project owns (`RigScope.own`), never another's in place.
                val patch = existingByKey[row.key]?.let {
                    RigScope.of(project).own.patches(DaoFixturePatches.id eq it.id).single()
                } ?: DaoFixturePatch.new {
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
                row.placement.applyTo(patch, row.focusWrite, force = row.dropsLantern)
                if (row.dropsLantern) {
                    extraPlacementsOf(patch).forEach { it.focus = LanternFocus() }
                    patch.lampWatts = null
                    patch.lampKelvin = null
                }
                // A type change leaves no media the new type cannot hold — the fixture's (unless the
                // row sends its own) and each lantern's (unless the row sends `alsoAt`) — so the next
                // write does not trip on a string loaded into a scroller the unit no longer has.
                if (row.typeChanged) {
                    // A stored locate keeps only the units the new type pans and tilts.
                    patch.storedLocate = StoredLocate.fitTo(row.typeKey, patch.storedLocate)
                    if (row.placement.media == null && patch.fittedMedia?.typeProblems(row.typeKey)?.isNotEmpty() == true) {
                        patch.fittedMedia = null
                    }
                    if (row.placement.alsoAt == null) {
                        extraPlacementsOf(patch)
                            .filter { it.fittedMedia?.typeProblems(row.typeKey)?.isNotEmpty() == true }
                            .forEach { it.fittedMedia = null }
                    }
                }
                row.infrastructure?.let { patch.infrastructure = it }
                row.headNumber?.let { patch.headNumber = it.value }
                for (groupName in row.groups) {
                    val group = groupsByName.getOrPut(groupName) {
                        DaoFixtureGroup.new {
                            this.project = project
                            name = groupName
                        }
                    }
                    if (memberships.add(group.id.value to patch.id.value)) {
                        val order = nextMemberOrder[group.id.value] ?: 0
                        nextMemberOrder[group.id.value] = order + 1
                        DaoFixtureGroupMember.new {
                            this.group = group
                            fixturePatch = patch
                            this.sortOrder = order
                        }
                    }
                }
            }
            groupsByName.keys.filter { it !in groupsBefore }
        }

        if (target.isCurrent) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
            // A stored locate a row sent reaches a unit that is located now, at once.
            reassertLocates(state, parsed.filter { it.placement.locate != null }.map { it.key }.toSet())
        }
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

    private fun deleteGroups(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val names = input["names"] as? JsonArray ?: return failure("Missing 'names'")
        if (names.isEmpty()) return failure("'names' is empty")
        val problems = mutableListOf<String>()
        val force = optionalBoolean(input, "force", problems) == true
        val project = target.project
        val (groups, venueGroups, scope) = transaction(state.database) {
            val rig = RigScope.of(project)
            Triple(
                rig.own.groups().associate { it.name to (it.id.value to it.members.count()) },
                rig.groups().filter { rig.ownerOf(it.project.id) == RigOwner.VENUE }.map { it.name }.toSet(),
                rig,
            )
        }

        val wanted = mutableListOf<Int>()
        val seen = mutableSetOf<String>()
        names.forEachIndexed { index, element ->
            val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name == null) {
                problems += "names[$index] is not a string"
                return@forEachIndexed
            }
            if (name in venueGroups) {
                problems += VenueRigGuard.ownedProblem(scope, "Group '$name'")
                return@forEachIndexed
            }
            val (groupId, members) = groups[name] ?: run {
                problems += "no group named '$name'" +
                    (if (groups.isEmpty()) " (this project has no groups)" else " (known: ${groups.keys.sorted().joinToString()})")
                return@forEachIndexed
            }
            if (!seen.add(name)) {
                problems += "group '$name' is listed twice"
                return@forEachIndexed
            }
            if (members > 0 && !force) {
                problems += "group '$name' still has $members member(s): pass force to delete it anyway (the fixtures stay patched)"
                return@forEachIndexed
            }
            wanted += groupId
        }
        if (problems.isNotEmpty()) return rejected(problems)

        val (sweptTiles, unlinked) = transaction(state.database) {
            var tiles = 0
            var members = 0
            for (groupId in wanted) {
                val group = DaoFixtureGroup.findById(groupId) ?: continue
                members += group.members.count().toInt()
                tiles += deleteFixtureGroupRows(group)
            }
            tiles to members
        }
        if (sweptTiles > 0) state.show.fixtures.buskRigChanged()
        if (target.isCurrent) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
        }
        state.show.fixtures.patchListChanged()
        return success(
            "Deleted ${wanted.size} group(s)",
            buildJsonObject {
                put("deleted", wanted.size)
                put("membersUnlinked", unlinked)
                put("buskRigTilesRemoved", sweptTiles)
            },
        )
    }

    private fun findUniverse(project: DaoProject, universe: Int): DaoUniverseConfig? = RigScope.of(project).universeConfigs(
        (DaoUniverseConfigs.subnet eq 0) and (DaoUniverseConfigs.universe eq universe)
    ).firstOrNull()

    /** The venue's patches [rig] hides, in the venue's order; empty off a venue. Inside a transaction. */
    private fun hiddenVenuePatches(rig: RigScope): List<DaoFixturePatch> {
        val venue = rig.venue ?: return emptyList()
        val uuids = venue.hides[HideKind.PATCH].orEmpty()
        if (uuids.isEmpty()) return emptyList()
        return DaoFixturePatch.find { (DaoFixturePatches.project eq venue.snapshotId) and (DaoFixturePatches.uuid inList uuids) }
            .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC).toList()
    }

    private fun riggingIdsByName(project: DaoProject): Map<String, Int> =
        RigScope.of(project).riggings().associate { it.name to it.id.value }

    // ─── Placement ──────────────────────────────────────────────────────

    /**
     * The placement fields of one row, each held only when the row carried its key, so an update
     * touches exactly what was sent. [rigging] is `Some(null)` for an explicit detach.
     */
    private class Placement(
        val rigging: Optional<Int?>?,
        val x: Optional<Double?>?, val y: Optional<Double?>?, val z: Optional<Double?>?,
        val yawDeg: Optional<Double?>?, val pitchDeg: Optional<Double?>?, val rollDeg: Optional<Double?>?,
        val beamAngleDeg: Optional<Int?>?,
        val lampWatts: Optional<Int?>?,
        val lampKelvin: Optional<Int?>?,
        val gelCode: Optional<String?>?,
        val kind: Optional<String?>?,
        val lengthM: Optional<Double?>?,
        val stageHidden: Boolean?,
        /** The whole `alsoAt` list when the row carried it (empty for an explicit clear). */
        val alsoAt: List<AlsoAt>?,
        /** The focus keys the row carried, and their values (range-checked). */
        val focusSent: Set<String>,
        val focus: LanternFocus,
        /** The fixture's fitted media when the row carried `media` (`Some(null)` clears it). */
        val media: Optional<FittedMedia?>?,
        /** The stored locate when the row carried `locate` (empty — `null` or `[]` — clears it). */
        val locate: Optional<List<UnitLocate>>?,
        /** The fixture's colour when the row carried `colour` (a null value clears it). */
        val colour: SentColour?,
    ) {
        /**
         * The fixture's lantern, focus and kind once this row lands on [stored] — the REST routes'
         * rule ([resolvePatchFocus]) — and its `alsoAt` lanterns checked against them. Adds any
         * refusal to [problems] and answers null then.
         */
        fun resolveFocus(typeKey: String, stored: LanternFocus, storedKind: String?, problems: MutableList<String>): PatchFocusWrite? {
            val write = resolvePatchFocus(typeKey, stored, storedKind, focusSent, focus, kind != null, kind?.value)
                .getOrElse { problems += it.message.orEmpty(); return null }
            val inputs = alsoAt?.map { a ->
                PlacementInput(null, a.label, null, a.x, a.y, a.z, a.yawDeg, a.pitchDeg, a.lengthM, a.rollDeg, a.focus, a.media)
            }
            inputs?.let { placementFocusRefusal(typeKey, write.kindOverride, write.focus.lanternType, it) }
                ?.let { problems += it.replace("extraPlacements", "alsoAt"); return null }
            // Fitted media, the fixture's and each `alsoAt` lantern's: the REST routes' rule.
            patchMediaRefusal(typeKey, media?.value, media != null, inputs)
                ?.let { problems += it.replace("extraPlacements", "alsoAt"); return null }
            // A stored locate only on the units the type pans and tilts (venue-visit plan D6).
            locate?.value?.let { StoredLocate.typeProblems(typeKey, it) }?.takeIf { it.isNotEmpty() }
                ?.let { problems += it; return null }
            return write
        }

        /** Whether the row writes a non-null length — on the fixture or on any `alsoAt` segment —
         *  which only a type that takes one may hold (`fixedLengthRefusal`). */
        val setsLength: Boolean
            get() = lengthM?.value != null || alsoAt.orEmpty().any { it.lengthM != null }

        /** Whether the row writes a non-null lamp, which only a lantern-hung type may hold (`lamplessRefusal`). */
        val setsLamp: Boolean
            get() = lampWatts?.value != null || lampKelvin?.value != null

        /**
         * Lands the row on [patch]. The kind and the focus are written only when the row carried a
         * focus key or a kind (or [force] — a type change that drops the lantern), as the REST PUT
         * guards them: [focusWrite] was resolved from a read in an earlier transaction, and a
         * geometry-only row must not write that snapshot back over a focus changed since.
         */
        fun applyTo(patch: DaoFixturePatch, focusWrite: PatchFocusWrite, force: Boolean = false) {
            rigging?.let { r -> patch.rigging = r.value?.let { DaoRigging.findById(it) } }
            x?.let { patch.stageX = it.value }
            y?.let { patch.stageY = it.value }
            z?.let { patch.stageZ = it.value }
            yawDeg?.let { patch.baseYawDeg = it.value }
            pitchDeg?.let { patch.basePitchDeg = it.value }
            rollDeg?.let { patch.baseRollDeg = it.value }
            beamAngleDeg?.let { patch.beamAngleDeg = it.value }
            lampWatts?.let { patch.lampWatts = it.value }
            lampKelvin?.let { patch.lampKelvin = it.value }
            gelCode?.let { patch.gelCode = it.value }
            val writesFocus = force || focusSent.isNotEmpty() || kind != null
            if (writesFocus) {
                patch.kindOverride = focusWrite.kindOverride
                patch.focus = focusWrite.focus
            }
            lengthM?.let { patch.lengthM = it.value }
            media?.let { patch.fittedMedia = it.value }
            locate?.let { patch.storedLocate = it.value }
            colour?.let { patch.colour = it.value }
            stageHidden?.let { patch.stageHidden = it }
            alsoAt?.let { list ->
                // Positional identity: the n-th lantern listed keeps the n-th placement's uuid, so
                // re-placing a pair edits it rather than deleting and re-adding it (which a cloud
                // sync would show as two changes). The routes key by uuid; this tool has none.
                val existing = extraPlacementsOf(patch)
                val riggings = list.mapNotNull { it.riggingId }.distinct()
                    .mapNotNull { DaoRigging.findById(it) }.associateBy { it.uuid.toString() }
                val byId = riggings.values.associateBy { it.id.value }
                val inputs = list.mapIndexed { i, a ->
                    PlacementInput(
                        uuid = existing.getOrNull(i)?.uuid,
                        label = a.label,
                        riggingUuid = a.riggingId?.let { byId[it]?.uuid?.toString() },
                        stageX = a.x, stageY = a.y, stageZ = a.z,
                        baseYawDeg = a.yawDeg, basePitchDeg = a.pitchDeg, baseRollDeg = a.rollDeg,
                        lengthM = a.lengthM,
                        focus = a.focus,
                        media = a.media,
                    )
                }
                applyExtraPlacements(patch, inputs, riggings)
            }
            // A lantern changed without `alsoAt`: the inheriting placements' zooms follow the REST rule.
            if (writesFocus && alsoAt == null) {
                clearStaleInheritedZooms(patch.fixtureTypeKey, focusWrite.kindOverride, focusWrite.focus.lanternType, extraPlacementsOf(patch))
            }
        }
    }

    /** One `alsoAt` entry: a whole placement, every field plain (absent means none). */
    private class AlsoAt(
        val label: String?,
        val riggingId: Int?,
        val x: Double?, val y: Double?, val z: Double?,
        val yawDeg: Double?, val pitchDeg: Double?, val rollDeg: Double?,
        val lengthM: Double?,
        val focus: LanternFocus,
        val media: FittedMedia?,
    )

    /** A present value, which may itself be null (an explicit clear). */
    private class Optional<T>(val value: T)

    /**
     * An optional boolean field of a row: null when absent (leave it as it is), the value when a
     * JSON boolean, and a problem — never a silent false — for anything else, `null` included.
     */
    private fun optionalBoolean(row: JsonObject, name: String, problems: MutableList<String>): Boolean? =
        row[name]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: run { problems += "$name must be a boolean"; null } }

    private fun parsePlacement(row: JsonObject, riggingIds: Map<String, Int>, where: String, problems: MutableList<String>): Placement {
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
        val roll = field("rollDeg") { it.doubleOrNull }
        val beam = field("beamAngleDeg") { it.doubleOrNull?.toInt() }
        val length = field("lengthM") { it.doubleOrNull }
        val lampWatts = field("lampWatts") { it.doubleOrNull?.toInt() }
        val lampKelvin = field("lampKelvin") { it.doubleOrNull?.toInt() }
        validateStageMetadata(
            x?.value, y?.value, z?.value, yaw?.value, pitch?.value, beam?.value, length?.value, roll?.value,
            lampWatts?.value, lampKelvin?.value,
        )?.let { problems += it }
        val gel = field("gelCode") { it.contentOrNull }?.let { Optional(normaliseGelCode(it.value)) }
        val kind = field("kind") { it.contentOrNull }?.let {
            try {
                Optional(normaliseKindOverride(it.value))
            } catch (e: IllegalArgumentException) {
                problems += e.message ?: "invalid kind"
                null
            }
        }
        val hidden = optionalBoolean(row, "stageHidden", problems)
        val alsoAt = parseAlsoAt(row["alsoAt"], riggingIds, problems)
        val focus = LanternFocus.parse(row).getOrElse { problems += it.message.orEmpty(); LanternFocus() }
        val focusSent = row.keys.filter { it in LanternFocus.KEYS }.toSet()
        val media = if ("media" in row) {
            FittedMedia.parse(row["media"]).fold({ Optional(it) }, { problems += it.message.orEmpty(); null })
        } else null
        // Checked against the type in `resolveFocus`, where the row's type is known.
        val locate = if ("locate" in row) {
            StoredLocate.parse(row["locate"]).fold({ Optional(it) }, { problems += it.message.orEmpty(); null })
        } else null
        val colour = if (FixtureColours.FIELD in row) FixtureColours.parseSent(row[FixtureColours.FIELD], where, problems) else null
        return Placement(rigging, x, y, z, yaw, pitch, roll, beam, lampWatts, lampKelvin, gel, kind, length, hidden, alsoAt, focusSent, focus, media, locate, colour)
    }

    /** `alsoAt`: absent → untouched (null), null or [] → cleared, an array → the new list. */
    private fun parseAlsoAt(element: JsonElement?, riggingIds: Map<String, Int>, problems: MutableList<String>): List<AlsoAt>? {
        if (element == null) return null
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: run { problems += "alsoAt must be an array"; return null }
        if (array.size > MAX_EXTRA_PLACEMENTS) {
            problems += "alsoAt has ${array.size} entries (at most $MAX_EXTRA_PLACEMENTS)"
            return null
        }
        val out = mutableListOf<AlsoAt>()
        array.forEachIndexed { i, entryElement ->
            val entry = entryElement as? JsonObject ?: run { problems += "alsoAt[$i] is not an object"; return@forEachIndexed }
            fun number(name: String): Double? {
                val e = entry[name] ?: return null
                if (e is JsonNull) return null
                return (e as? JsonPrimitive)?.doubleOrNull ?: run { problems += "alsoAt[$i].$name must be a number"; null }
            }
            fun text(name: String): String? {
                val e = entry[name] ?: return null
                if (e is JsonNull) return null
                val p = e as? JsonPrimitive
                if (p == null || !p.isString) {
                    problems += "alsoAt[$i].$name must be a string"
                    return null
                }
                return p.content.trim().takeIf { it.isNotEmpty() }
            }
            val label = text("label")
            if (label != null && label.length > MAX_PLACEMENT_LABEL_LENGTH) {
                problems += "alsoAt[$i].label is longer than $MAX_PLACEMENT_LABEL_LENGTH characters"
            }
            val riggingName = text("rigging")
            val riggingId = riggingName?.let { name ->
                riggingIds[name] ?: run {
                    problems += "alsoAt[$i]: no rigging named '$name'" +
                        (if (riggingIds.isEmpty()) " (create riggings with set_stage first)" else " (known: ${riggingIds.keys.joinToString()})")
                    null
                }
            }
            val focus = LanternFocus.parse(entry, "alsoAt[$i]").getOrElse { problems += it.message.orEmpty(); LanternFocus() }
            val media = FittedMedia.parse(entry["media"], "alsoAt[$i].media").getOrElse { problems += it.message.orEmpty(); null }
            val a = AlsoAt(
                label, riggingId, number("x"), number("y"), number("z"), number("yawDeg"), number("pitchDeg"),
                number("rollDeg"), number("lengthM"), focus, media,
            )
            validateStageMetadata(a.x, a.y, a.z, a.yawDeg, a.pitchDeg, null, a.lengthM, a.rollDeg)?.let { problems += "alsoAt[$i]: $it" }
            out += a
        }
        return out
    }

    private fun placeFixtures(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val rows = input["placements"] as? JsonArray ?: return failure("Missing 'placements'")
        if (rows.isEmpty()) return failure("'placements' is empty")
        val project = target.project
        data class Stored(val id: Int, val typeKey: String, val focus: LanternFocus, val kindOverride: String?)
        val (lists, hiddenVenueKeys) = transaction(state.database) {
            val rig = RigScope.of(project)
            Triple(
                rig.own.patches().associate { it.key to Stored(it.id.value, it.fixtureTypeKey, it.focus, it.kindOverride) },
                riggingIdsByName(project),
                rig.patches().filter { rig.ownerOf(it.project.id) == RigOwner.VENUE }.map { it.key }.toSet(),
            ) to hiddenVenuePatches(rig).map { it.key }.toSet()
        }
        val (patches, riggingIds, venueKeys) = lists

        val problems = mutableListOf<String>()
        val parsed = mutableListOf<Triple<Int, Placement, PatchFocusWrite>>()
        val venueRows = mutableListOf<Triple<Int, String, JsonObject>>()
        val seen = mutableSetOf<String>()
        rows.forEachIndexed { index, element ->
            val row = element as? JsonObject ?: run { problems += "placements[$index]: not an object"; return@forEachIndexed }
            val key = row.string("key")?.trim().orEmpty()
            val rowProblems = mutableListOf<String>()
            val stored = patches[key]
            if (key in venueKeys) {
                // A venue fixture is placed through this show's facet overrides, resolved below.
                if (!seen.add(key)) problems += "placements[$index] ('$key'): key '$key' is listed twice"
                else venueRows += Triple(index, key, row)
                return@forEachIndexed
            }
            if (stored == null) {
                rowProblems += if (key in hiddenVenueKeys) {
                    "'$key' is the venue's and hidden in this show: bring it back with override_venue_fixtures (hidden: false) first"
                } else {
                    "no patched fixture has key '$key'"
                }
            }
            if (!seen.add(key)) rowProblems += "key '$key' is listed twice"
            val placement = parsePlacement(row, riggingIds, "placements[$index] ('$key')", rowProblems)
            if (placement.setsLength && stored != null) fixedLengthRefusal(stored.typeKey)?.let { rowProblems += it }
            if (placement.setsLamp && stored != null) lamplessRefusal(stored.typeKey)?.let { rowProblems += it }
            val focusWrite = if (rowProblems.isEmpty() && stored != null) {
                placement.resolveFocus(stored.typeKey, stored.focus, stored.kindOverride, rowProblems)
            } else null
            if (rowProblems.isEmpty()) parsed += Triple(stored!!.id, placement, focusWrite!!)
            else problems += rowProblems.map { if (VenueRigGuard.codeOf(listOf(it)) != null) it else "placements[$index] ('$key'): $it" }
        }
        val venuePlacements = if (venueRows.isEmpty()) emptyList() else transaction(state.database) {
            val show = DaoProject[project.id]
            venueRows.mapNotNull { (index, key, row) ->
                venueFixtures.resolvePlacement(show, key, row, "placements[$index] ('$key')", problems)
            }
        }
        if (problems.isNotEmpty()) return rejected(problems)

        val gels = transaction(state.database) {
            val show = DaoProject[project.id]
            for (v in venuePlacements) {
                for (value in v.values) ShowOverrides.put(show, ShowOverrides.target(show, v.patchId), value.facet, value)
                // A venue fixture's colour is the show's recolour of it (tables-colours plan D10).
                v.colour?.let { ShowColours.put(show.id, DaoFixturePatch[v.patchId].uuid, it.value) }
            }
            parsed.map { (patchId, placement, focusWrite) ->
                val patch = DaoFixturePatch.findById(patchId)!!
                placement.applyTo(patch, focusWrite)
                patch.key to Fixtures.FixturePatchMetadata(
                    gelCode = patch.gelCode, infrastructure = patch.infrastructure, media = patch.fittedMedia,
                    locate = patch.storedLocate,
                )
            }
        }
        // Metadata only — the same set `METADATA_ONLY_PUT_KEYS` lets the patch route skip the
        // fixture rebuild for — so the runtime rig is untouched. What the running show does cache
        // from these columns is the gel (`GET /fixtures` reads it) and the fitted media (the
        // template resolver's snap), refreshed here as both REST placement paths refresh them.
        // The running rig's cache is the current project's; another project's keys may collide with it.
        if (target.isCurrent) {
            for ((key, metadata) in gels) {
                state.show.fixtures.setPatchMetadata(key, metadata)
            }
            // A stored locate the rows sent reaches a unit located now, at once; a venue fixture's
            // (its Focus facet's) through the same re-read.
            // `gels` is `parsed` row for row, so the keys of the rows that sent one are paired by index.
            reassertLocates(state, parsed.zip(gels).filter { (row, _) -> row.second.locate != null }.map { (_, g) -> g.first }.toSet())
            syncStoredLocates(state, project.id.value, venuePlacements.map { it.key })
            // A venue fixture's media is its facet's, which the loader lays over the venue's row.
            if (venuePlacements.any { it.reloads }) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
            }
        }
        state.show.fixtures.patchListChanged()
        val placed = parsed.size + venuePlacements.size
        return success("Placed $placed fixture(s)", buildJsonObject {
            put("placed", placed)
            if (venuePlacements.isNotEmpty()) putJsonArray("venueFixtures") { venuePlacements.forEach { add(it.key) } }
        })
    }

    // ─── Stage ──────────────────────────────────────────────────────────

    private fun setStage(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val project = target.project
        val problems = unknownFields(
            input, listOf("stage", "regions", "riggings", "removeRegions", "removeRiggings", "projectId"), "set_stage",
        ).toMutableList()

        for ((name, shape) in listOf("stage" to "an object", "regions" to "an array", "riggings" to "an array")) {
            val element = input[name] ?: continue
            val ok = if (name == "stage") element is JsonObject else element is JsonArray
            if (!ok) problems += "$name must be $shape"
        }
        val stage = input["stage"] as? JsonObject
        val width = stage?.double("widthM")
        val depth = stage?.double("depthM")
        val height = stage?.double("heightM")
        stage?.let {
            problems += malformedNumbers(it, STAGE_NUMBERS, "stage")
            problems += unknownFields(it, STAGE_NUMBERS, "stage")
            if (it.isEmpty()) problems += "stage: send at least one of ${STAGE_NUMBERS.joinToString()}"
        }
        validateStageDimensions(width, depth, height)?.let { problems += "stage: $it" }

        val regions = (input["regions"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "regions[$index]: not an object"; return@mapIndexedNotNull null }
            val name = row.string("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 100) problems += "regions[$index]: name must be 1–100 characters"
            problems += malformedNumbers(row, REGION_NUMBERS, "regions[$index] ('$name')")
            problems += unknownFields(row, REGION_NUMBERS + "name", "regions[$index] ('$name')")
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
            problems += malformedNumbers(row, RIGGING_NUMBERS, "riggings[$index] ('$name')")
            problems += unknownFields(row, RIGGING_NUMBERS + listOf("name", "kind"), "riggings[$index] ('$name')")
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
            // What a remove may name: the rows this project owns, not the whole rig.
            val rig = RigScope.of(project)
            val own = rig.own
            // A show on a venue: the venue's stage, regions and riggings are the venue's (session 3).
            val venueRegions = rig.stageRegions().filter { rig.ownerOf(it.project.id) == RigOwner.VENUE }.map { it.name }.toSet()
            val venueRiggings = rig.riggings().filter { rig.ownerOf(it.project.id) == RigOwner.VENUE }.map { it.name }.toSet()
            if (stage != null && DaoProject[project.id].isVenueBound) problems += VenueRigGuard.ownedProblem(rig, "The stage's size")
            (regions.map { it.first } + removeRegions).filter { it in venueRegions }.distinct()
                .forEach { problems += VenueRigGuard.ownedProblem(rig, "Stage region '$it'") }
            (riggings.map { it.first } + removeRiggings).filter { it in venueRiggings }.distinct()
                .forEach { problems += VenueRigGuard.ownedProblem(rig, "Rigging '$it'") }
            own.stageRegions().map { it.name }.toSet() + venueRegions to own.riggings().map { it.name }.toSet() + venueRiggings
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
        var platformsUnlinked = 0
        // The stage box as stored after the write, answered so the caller sees what landed.
        var storedStage: Triple<Double?, Double?, Double?>? = null
        transaction(state.database) {
            val own = RigScope.of(project).own
            if (stage != null) {
                val p = DaoProject.findById(project.id)!!
                if ("widthM" in stage) p.stageWidthM = width
                if ("depthM" in stage) p.stageDepthM = depth
                if ("heightM" in stage) p.stageHeightM = height
                storedStage = Triple(p.stageWidthM, p.stageDepthM, p.stageHeightM)
            }
            var regionOrder = (own.stageRegions().maxOfOrNull { it.sortOrder } ?: -1) + 1
            for ((name, row) in regions) {
                val region = own.stageRegions(DaoStageRegions.name eq name).firstOrNull() ?: DaoStageRegion.new {
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
            var riggingOrder = (own.riggings().maxOfOrNull { it.sortOrder } ?: -1) + 1
            for ((name, row) in riggings) {
                val rigging = own.riggings(DaoRiggings.name eq name).firstOrNull() ?: DaoRigging.new {
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
                own.stageRegions(DaoStageRegions.name eq name)
                    .forEach { region ->
                        platformsUnlinked += unlinkRegionFromPlatforms(project, region.uuid)
                        region.delete()
                    }
            }
            for (name in removeRiggings) {
                own.riggings(DaoRiggings.name eq name).forEach { rigging ->
                    // As the rigging route's delete: detach first, since Exposed does not enforce
                    // the FK's SET NULL.
                    DaoFixturePatch.find { DaoFixturePatches.rigging eq rigging.id }.forEach { it.rigging = null; detached++ }
                    detached += detachPlacementsFromRigging(rigging)
                    // And a venue patch the show hangs on it through a Position override (session 5).
                    detached += ShowOverrides.detachRigging(project.id, rigging.uuid)
                    rigging.delete()
                }
            }
        }
        if (stage != null) state.show.fixtures.projectDetailsChanged(project.id.value)
        if (regions.isNotEmpty() || removeRegions.isNotEmpty()) state.show.fixtures.stageRegionListChanged()
        if (platformsUnlinked > 0) state.show.fixtures.stageElementListChanged()
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
                storedStage?.let { (w, d, h) ->
                    putJsonObject("stage") {
                        putOptional("widthM", w)
                        putOptional("depthM", d)
                        putOptional("heightM", h)
                    }
                }
            },
        )
    }

    // ─── The show: prompt book and cue stacks ───────────────────────────

    private fun noPromptBookMessage(): String {
        val where = deskUrl()?.let { "$it/prompt-book" } ?: "the desk's Prompt Book view (/prompt-book)"
        return "This project has no prompt book yet. The desk must hold the script PDF itself, and a tool cannot " +
            "upload a file: ask the operator to open $where and import the PDF, then call get_prompt_book again."
    }

    private fun getPromptBook(target: ToolProject): ToolExecutionResult {
        val project = target.project
        val body = transaction(state.database) {
            val book = DaoPromptBook.find { DaoPromptBooks.project eq project.id }.firstOrNull()
                ?: return@transaction null
            val stacks = DaoCueStack.find { DaoCueStacks.project eq project.id }.toList()
            val stackNames = stacks.associate { it.id.value to it.name }
            // An anchor marks an entry (cue model plan D4): its cue, its number and its stack.
            val entries = stacks.flatMap { entriesOf(it) }.associateBy { it.id.value }
            val anchors = book.anchors.toList()
            buildJsonObject {
                book.scriptFileName?.let { put("fileName", it) }
                put("pageCount", book.pageCount)
                put("coverPages", book.coverPages)
                putJsonArray("anchors") {
                    anchors.sortedWith(compareBy({ it.region.firstOrNull()?.page }, { it.region.firstOrNull()?.y })).forEach { a ->
                        val entry = entries[a.entryId]
                        addJsonObject {
                            put("entryId", a.entryId)
                            entry?.cueId?.let { put("cueId", it) }
                            entry?.let {
                                it.cueNumber?.let { n -> put("cueNumber", n) }
                                put("cueName", it.displayName)
                                put("stackId", it.stackId)
                                stackNames[it.stackId]?.let { s -> put("stack", s) }
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
                val anchored = anchors.map { it.entryId }.toSet()
                put("unanchoredCueCount", entries.values.count { !it.isMarker && it.id.value !in anchored })
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

    /** One cue of a build_cue_stack call, validated: a new cue, a marker, or an existing cue shared ([shareCueId]). */
    private data class ShowCue(
        val number: String?,
        val name: String,
        val notes: String?,
        val fade: Duration?,
        val fadeCurve: String,
        val follow: Duration?,
        val marker: Boolean,
        val layers: List<CueLayerDto>,
        val values: ResolvedLookValues?,
        val place: PromptBookRectDto?,
        val scenery: List<SceneryWrite>,
        val events: List<CueEventWrite>,
        val speedMasters: List<CueSpeedMasterDto>,
        val shareCueId: Int? = null,
    )

    private fun buildCueStack(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val cueRows = input["cues"] as? JsonArray ?: return failure("Missing 'cues'")
        if (cueRows.isEmpty()) return failure("'cues' is empty")
        val stackId = input.int("stackId")
        val stackName = input.string("stackName")?.trim()
        val project = target.project
        val problems = mutableListOf<String>()

        if (stackId == null && stackName.isNullOrEmpty()) problems += "give stackName for a new stack, or stackId to append to one"
        if (stackName != null && stackName.length > 255) problems += "stackName must be at most 255 characters"

        data class Context(
            val stackError: String?,
            val takenNumbers: Set<String>,
            val pageCount: Int?,
            val elements: Map<UUID, SceneryElementInfo>,
            val patches: Map<Int, CueEventPatchInfo>,
            val speedMasters: List<SpeedMasterRef>,
            /** The project's cues by id, for a row sharing one. */
            val cueNames: Map<Int, String>,
        )
        val context = transaction(state.database) {
            val pageCount = DaoPromptBook.find { DaoPromptBooks.project eq project.id }.firstOrNull()?.pageCount
            val elements = sceneryElementsOf(project)
            val patches = cueEventPatchesOf(project)
            val masters = speedMasterRefsOf(project)
            val cueNames = DaoCue.find { DaoCues.project eq project.id }.associate { it.id.value to it.name }
            if (stackId != null) {
                val stack = DaoCueStack.findById(stackId)
                val error = when {
                    stack == null || stack.project.id != project.id -> "no cue stack $stackId in this project"
                    stack.type != CueStackType.STACK.name -> "cue stack $stackId is a separator, not a stack"
                    else -> null
                }
                val numbers = stack?.let { cueEntriesInOrder(it) }?.mapNotNull { it.cueNumber }?.toSet().orEmpty()
                Context(error, numbers, pageCount, elements, patches, masters, cueNames)
            } else {
                val clash = !DaoCueStack.find {
                    (DaoCueStacks.project eq project.id) and (DaoCueStacks.name eq stackName.orEmpty()) and
                        (DaoCueStacks.type eq CueStackType.STACK.name)
                }.empty()
                Context(if (clash) "a cue stack named '$stackName' already exists — pass its stackId to append to it" else null, emptySet(), pageCount, elements, patches, masters, cueNames)
            }
        }
        context.stackError?.let { problems += it }
        val stackSet = (input["scenery"] as? JsonArray)?.let {
            parseToolSceneryList(it, context.elements, SceneryOwnerKind.STACK, "scenery", problems)
        }

        val seenNumbers = mutableMapOf<String, Int>()
        val cues = cueRows.mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: run { problems += "cues[$index]: not an object"; return@mapIndexedNotNull null }
            val rawShare = row["cueId"]?.takeIf { it !is JsonNull }
            val shareId = rawShare?.let { row.int("cueId") }
            if (rawShare != null && shareId == null) {
                problems += "cues[$index]: cueId must be an integer"
                return@mapIndexedNotNull null
            }
            if (shareId != null) return@mapIndexedNotNull sharedRow(index, row, shareId, context.cueNames, context.takenNumbers, seenNumbers, target, context.pageCount, problems)
            val name = row.string("name")?.trim().orEmpty()
            val where = "cues[$index] ('$name')"
            val rowProblems = mutableListOf<String>()
            val marker = row.boolean("marker") == true
            if (name.isEmpty()) rowProblems += "name must not be blank (or cueId, to share an existing cue)"
            if (name.length > 255) rowProblems += "name must be at most 255 characters"
            val number = row.string("number")?.trim()?.takeIf { it.isNotEmpty() }
            if (marker && number != null) rowProblems += "a marker cannot carry a number"
            if (number != null && !marker) checkRowNumber(number, index, context.takenNumbers, seenNumbers, rowProblems)
            val fade = seconds(row, "fadeSeconds", rowProblems)
            val follow = seconds(row, "followSeconds", rowProblems)
            val curve = row.string("fadeCurve")?.trim()?.uppercase() ?: EasingCurve.LINEAR.name
            if (EasingCurve.entries.none { it.name == curve }) {
                rowProblems += "fadeCurve must be one of ${EasingCurve.entries.joinToString { it.name }}"
            }
            val layers = try {
                aiTools.parseCueLayers(row["layers"] as? JsonArray).also { parsed ->
                    validateCueChildren(emptyList(), parsed, registry = state.show.fxRegistry)?.let { rowProblems += it }
                    rowProblems += aiTools.layerSourceProblems(project, parsed)
                    rowProblems += aiTools.untargetedFxLayerProblems(parsed)
                }
            } catch (e: Exception) {
                rowProblems += "layers: ${e.message ?: "malformed"}"
                emptyList()
            }
            if (marker && layers.isNotEmpty()) rowProblems += "a marker cannot carry layers"
            val values = row["values"]?.takeIf { it !is JsonNull }?.let { raw ->
                when {
                    raw !is JsonArray -> { rowProblems += "values must be an array"; null }
                    marker -> { rowProblems += "a marker cannot carry values: it is never live"; null }
                    // Resolved per head against the live rig, which only the current project has.
                    !target.isCurrent -> {
                        rowProblems += "values are resolved against the live rig, which only the current project has — " +
                            "leave them out, or switch_project first"
                        null
                    }
                    else -> resolveLookValues(state.show.fixtures, raw).also { rowProblems += it.problems }
                }
            }
            val scenery = (row["scenery"] as? JsonArray)?.let {
                if (marker) rowProblems += "a marker cannot carry scenery: it is never live"
                parseToolSceneryList(it, context.elements, SceneryOwnerKind.CUE, "scenery", rowProblems)
            }.orEmpty()
            val events = (row["events"] as? JsonArray)?.let {
                if (marker) rowProblems += "a marker cannot carry events: it is never gone to"
                parseToolCueEventList(it, context.patches, "events", rowProblems)
            }.orEmpty()
            val speedMasters = (row["speedMasters"] as? JsonArray)?.let {
                if (marker) rowProblems += "a marker cannot set speed masters: it is never gone to"
                parseCueSpeedMasters(it, context.speedMasters, emptyList(), "speedMasters", rowProblems, byIndex = true)
            }.orEmpty()
            val place = rowPlace(row, target, context.pageCount, rowProblems)
            if (rowProblems.isNotEmpty()) {
                problems += rowProblems.map { "$where: $it" }
                null
            } else ShowCue(number.takeUnless { marker }, name, row.string("notes")?.trim()?.takeIf { it.isNotEmpty() }, fade, curve, follow, marker, layers, values, place, scenery, events, speedMasters)
        }
        if (problems.isNotEmpty()) return rejected(problems)

        /** One row built: a cue entry ([id] its cue's) or a marker entry ([id] null — a marker is no cue). */
        data class BuiltCue(val id: Int?, val entryId: Int, val number: String?, val name: String, val skipped: List<LookValueSkip>, val shared: Boolean)
        data class Built(val stackId: Int, val stackName: String, val cues: List<BuiltCue>, val anchors: Int)
        val built = transaction(state.database) {
            val stack = stackId?.let { DaoCueStack.findById(it)!! } ?: DaoCueStack.new {
                name = stackName!!
                this.project = project
                loop = input.boolean("loop") == true
                type = CueStackType.STACK.name
                sortOrder = (project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1
            }
            // Each row is an entry appended to the stack: a marker entry labelled with its name (a
            // marker is no cue — cue model plan D12), or a new cue placed by `placeCueInStack`, which
            // takes its number from its numbered neighbours when it has none.
            val before = cueEntryIds(stack)
            val created = cues.map { spec ->
                if (spec.marker) {
                    val marker = DaoCueStackEntry.new {
                        this.stack = stack
                        kind = CueStackEntryKind.MARKER.name
                        label = spec.name.take(255)
                        ordinal = nextOrdinal(stack)
                    }
                    return@map Triple(null, marker, spec)
                }
                // A shared row adds an entry and leaves the cue as it is (cue model plan D1).
                val cue = spec.shareCueId?.let { DaoCue[it] } ?: DaoCue.new {
                    name = spec.name
                    this.project = project
                    notes = spec.notes
                    fadeDuration = spec.fade
                    fadeCurve = spec.fadeCurve
                    speedMasters = spec.speedMasters
                }.also { cue ->
                    writeCueChildren(cue, attributeAssignments = spec.values?.asCueAssignments().orEmpty(), layers = spec.layers, created = true, registry = state.show.fxRegistry)
                    replaceCueScenery(cue, spec.scenery)
                    replaceCueEvents(cue, spec.events)
                }
                val entry = placeCueInStack(
                    stack, cue,
                    cueNumber = spec.number,
                    autoAdvance = spec.follow != null,
                    autoAdvanceDelay = spec.follow,
                    renumber = false,
                )
                Triple(cue, entry, spec)
            }
            stackSet?.let { replaceStackScenery(stack, it) }
            // Unnumbered cues take their labels from their numbered neighbours.
            renumberAutoEntries(stack, before)

            var anchors = 0
            val book = if (created.any { it.third.place != null }) {
                DaoPromptBook.find { DaoPromptBooks.project eq project.id }.first()
            } else null
            for ((_, entry, spec) in created) {
                val place = spec.place ?: continue
                DaoPromptBookAnchor.new {
                    promptBook = book!!
                    this.entry = entry
                    region = listOf(place)
                    label = anchorLabel(entry)
                }
                anchors++
            }
            Built(
                stack.id.value, stack.name,
                created.map { (cue, entry, spec) ->
                    BuiltCue(cue?.id?.value, entry.id.value, entry.cueNumber, entry.displayName, spec.values?.skipped.orEmpty(), spec.shareCueId != null)
                },
                anchors,
            )
        }
        // Appended, so no entry already there changed place: nothing to restamp.
        if (target.isCurrent) {
            announceEntriesPlaced(state, built.stackId, emptyMap())
        } else {
            state.show.fixtures.cueListChanged()
            state.show.fixtures.cueStackListChanged()
        }
        if (built.anchors > 0) state.show.fixtures.promptBookChanged()

        val sharedCount = built.cues.count { it.shared }
        return success(
            "${if (stackId == null) "Created" else "Appended to"} stack '${built.stackName}' (id=${built.stackId}): " +
                "${built.cues.size} cue(s)" + (if (sharedCount > 0) ", $sharedCount of them shared" else "") +
                ", ${built.anchors} anchored in the prompt book",
            buildJsonObject {
                put("stackId", built.stackId)
                put("stackName", built.stackName)
                put("anchorsCreated", built.anchors)
                putJsonArray("cues") {
                    built.cues.forEach { cue ->
                        addJsonObject {
                            if (cue.id != null) put("cueId", cue.id) else put("marker", true)
                            if (cue.shared) put("shared", true)
                            put("entryId", cue.entryId)
                            cue.number?.let { put("number", it) }
                            put("name", cue.name)
                            with(aiTools) { putValueSkips(cue.skipped) }
                        }
                    }
                }
            },
        )
    }

    /**
     * A build_cue_stack row naming an existing [cueId] (cue model plan §3.10): the cue is shared into
     * the stack as a new entry, its content untouched, so only the entry's own fields — number, follow
     * and the prompt-book place — may come with it. Null after its problems.
     */
    private fun sharedRow(
        index: Int,
        row: JsonObject,
        cueId: Int,
        cueNames: Map<Int, String>,
        takenNumbers: Set<String>,
        seenNumbers: MutableMap<String, Int>,
        target: ToolProject,
        pageCount: Int?,
        problems: MutableList<String>,
    ): ShowCue? {
        val cueName = cueNames[cueId] ?: run { problems += "cues[$index]: no cue $cueId in this project"; return null }
        val where = "cues[$index] (cue $cueId, '$cueName')"
        val rowProblems = mutableListOf<String>()
        row.string("name")?.trim()?.takeIf { it.isNotEmpty() && it != cueName }?.let {
            rowProblems += "a shared cue keeps its own name ('$cueName'): leave name out"
        }
        val content = listOf("notes", "fadeSeconds", "fadeCurve", "marker", "layers", "values", "scenery", "events", "speedMasters")
            .filter { row[it]?.let(::saysNothing) == false }
        if (content.isNotEmpty()) {
            rowProblems += "${content.joinToString()} would change the cue in every place it plays: a shared row takes only number, " +
                "followSeconds and at — edit the cue itself to change it"
        }
        val number = row.string("number")?.trim()?.takeIf { it.isNotEmpty() }
        if (number != null) checkRowNumber(number, index, takenNumbers, seenNumbers, rowProblems)
        val follow = seconds(row, "followSeconds", rowProblems)
        val place = rowPlace(row, target, pageCount, rowProblems)
        if (rowProblems.isNotEmpty()) {
            problems += rowProblems.map { "$where: $it" }
            return null
        }
        return ShowCue(
            number = number, name = cueName, notes = null, fade = null, fadeCurve = EasingCurve.LINEAR.name, follow = follow,
            marker = false, layers = emptyList(), values = null, place = place, scenery = emptyList(), events = emptyList(),
            speedMasters = emptyList(), shareCueId = cueId,
        )
    }

    /** A row's field that changes nothing: absent, null, an empty list, a blank text or `false`. */
    private fun saysNothing(element: JsonElement): Boolean = when (element) {
        is JsonNull -> true
        is JsonArray -> element.isEmpty()
        is JsonPrimitive -> (element.isString && element.content.isBlank()) || element.booleanOrNull == false
        else -> false
    }

    /** A build_cue_stack row's cue [number], checked against the stack's and the call's other rows. */
    private fun checkRowNumber(number: String, index: Int, takenNumbers: Set<String>, seenNumbers: MutableMap<String, Int>, problems: MutableList<String>) {
        if (number.length > MAX_CUE_NUMBER_LENGTH) problems += "number must be at most $MAX_CUE_NUMBER_LENGTH characters"
        seenNumbers[number]?.let { problems += "number '$number' is also used by cues[$it]" }
        seenNumbers.putIfAbsent(number, index)
        if (number in takenNumbers) problems += "the stack already has a cue numbered '$number'"
    }

    /** A build_cue_stack row's `at`, or null — refused, as the Prompt Book routes refuse it, outside the current project. */
    private fun rowPlace(row: JsonObject, target: ToolProject, pageCount: Int?, problems: MutableList<String>): PromptBookRectDto? {
        val at = row["at"] ?: return null
        return when {
            !target.isCurrent -> {
                problems += "'at' anchors the cue in the prompt book, which only the current project's can be marked up — leave 'at' out and anchor later with mark_up_prompt_book"
                null
            }
            pageCount == null -> {
                problems += "'at' needs a prompt book, and this project has none — import the PDF first, or leave 'at' out and anchor later with mark_up_prompt_book"
                null
            }
            else -> parsePlace(at, pageCount, "at", problems)
        }
    }

    private fun markUpPromptBook(input: JsonObject): ToolExecutionResult {
        val project = state.projectManager.currentProject
        data class Book(val id: Int, val pageCount: Int)
        val book = transaction(state.database) {
            DaoPromptBook.find { DaoPromptBooks.project eq project.id }.firstOrNull()?.let { Book(it.id.value, it.pageCount) }
        } ?: return failure(noPromptBookMessage())

        val problems = mutableListOf<String>()
        val coverPages = input.int("coverPages")
        if (input["coverPages"] != null && (coverPages == null || coverPages !in 0 until book.pageCount)) {
            problems += "coverPages must be 0–${book.pageCount - 1}"
        }
        // An anchor marks an entry (cue model plan D4): by its id, or by its stack and number. Read here
        // so an entry of another project, or a number its stack lacks, is refused with the rest.
        // Problems are gathered per attempt: a retried transaction must not report one twice.
        val (anchors, anchorProblems) = transaction(state.database) {
            val found = mutableListOf<String>()
            (input["anchors"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
                val where = "anchors[$index]"
                val row = element as? JsonObject ?: run { found += "$where: not an object"; return@mapIndexedNotNull null }
                anchorEntry(row, project, where, found::add)?.let { entryId ->
                    parsePlace(row["at"], book.pageCount, "$where (entry $entryId)", found)?.let { entryId to it }
                }
            } to found
        }
        problems += anchorProblems
        anchors.groupBy { it.first }.filter { it.value.size > 1 }.keys.forEach { problems += "entry $it is anchored twice" }
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
            for ((entryId, place) in anchors) {
                val entry = DaoCueStackEntry[entryId]
                val anchor = DaoPromptBookAnchor.find {
                    (DaoPromptBookAnchors.promptBook eq dao.id) and (DaoPromptBookAnchors.entry eq entry.id)
                }.firstOrNull() ?: DaoPromptBookAnchor.new {
                    promptBook = dao
                    this.entry = entry
                    region = listOf(place)
                }
                anchor.region = listOf(place)
                anchor.label = anchorLabel(entry)
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
            "Prompt book updated: ${anchors.size} entr${if (anchors.size == 1) "y" else "ies"} anchored, ${notes.size} note(s) added" +
                (coverPages?.let { ", $it cover page(s)" } ?: ""),
            buildJsonObject {
                put("anchored", anchors.size)
                put("notesAdded", notes.size)
                coverPages?.let { put("coverPages", it) }
            },
        )
    }

    /**
     * The entry an anchor row names — `entryId`, or `stackId` and `number` — in [project], or null after
     * [problem]. Inside a transaction.
     */
    private fun anchorEntry(row: JsonObject, project: DaoProject, where: String, problem: (String) -> Unit): Int? {
        return findProjectEntry(project, row, where, problem)?.id?.value
    }

    /** The anchor's cached label, as the Prompt Book view writes it: `Q<number>`, else the name. */
    private fun anchorLabel(entry: DaoCueStackEntry): String =
        (entry.cueNumber?.let { "Q$it" } ?: entry.displayName).take(64)

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
                VenueRigGuard.codeOf(problems)?.let { put("code", it) }
                putJsonArray("problems") { shown.forEach { add(it) } }
                if (problems.size > shown.size) put("moreProblems", problems.size - shown.size)
            }.toString(),
        )
    }

    /**
     * A field this tool does not know. set_stage writes only the fields it names, so a misspelt one
     * (`width` for `widthM`) would otherwise be skipped and the call still answer success.
     */
    private fun unknownFields(row: JsonObject, known: List<String>, where: String): List<String> =
        row.keys.filter { it !in known }.map { "$where: unknown field '$it' (known: ${known.joinToString()})" }

    /**
     * The fields among [names] that are present, not an explicit `null`, and not a number. The
     * `double()` reader answers null for all three of absent, `null` and malformed, and the writes
     * that follow it treat null as "clear": without this, `"centerX": "DSC"` would silently wipe a
     * stored position where it should have been refused.
     */
    private fun malformedNumbers(row: JsonObject, names: List<String>, where: String): List<String> =
        names.filter { name ->
            val element = row[name] ?: return@filter false
            element !is JsonNull && (element as? JsonPrimitive)?.doubleOrNull == null
        }.map { "$where: $it must be a number" }

    /** A unit's fitted media, as `patch_fixtures` takes it back — absent when nothing is fitted. */
    private fun JsonObjectBuilder.putMedia(media: FittedMedia?) {
        val text = FittedMedia.toText(media) ?: return
        put("media", Json.parseToJsonElement(text))
    }

    /** A unit's stored locate (venue-visit plan D6), as DMX bytes per unit — absent when it has none. */
    private fun JsonObjectBuilder.putLocate(locate: List<UnitLocate>) {
        val text = StoredLocate.toText(locate) ?: return
        put("locate", Json.parseToJsonElement(text))
    }

    /** A lantern and its focus, each field only where set — an unfocused lantern adds nothing. */
    private fun JsonObjectBuilder.putFocus(f: LanternFocus) {
        f.lanternType?.let { id ->
            put("lanternType", id)
            LanternLibrary.byId(id)?.let { put("lantern", it.name) }
        }
        putOptional("zoomDeg", f.zoomDeg)
        putOptional("lampRotationDeg", f.lampRotationDeg)
        f.shutters?.let { blades ->
            putJsonArray("shutters") {
                blades.forEach { b -> addJsonObject { put("depth", b.depth); put("angleDeg", b.angleDeg) } }
            }
        }
        putOptional("gateRotationDeg", f.gateRotationDeg)
        putOptional("iris", f.iris)
        putOptional("focusSoftness", f.focusSoftness)
    }

    private fun JsonObjectBuilder.putOptional(name: String, value: Double?) {
        value?.let { put(name, it) }
    }

    /**
     * `world`: where a placement actually is on stage, as the Stage view draws it
     * ([worldPosition]) — the answer to "where is it" that a rigging-relative x/y/z is not. Absent
     * for an unplaced one (no x or no y), which the Stage view draws nowhere. Rounded to the
     * millimetre, so a rotation's floating-point dust (a `1e-16` for a zero) does not read as a
     * position.
     */
    private fun JsonObjectBuilder.putWorld(x: Double?, y: Double?, z: Double?, rigging: RiggingPose?) {
        val world = worldPosition(x, y, z, rigging) ?: return
        putJsonObject("world") {
            put("x", roundToMillimetre(world.x))
            put("y", roundToMillimetre(world.y))
            put("z", roundToMillimetre(world.z))
        }
    }

    // Through a Long, so a rounded `-1e-16` is a plain 0 rather than `-0.0`.
    private fun roundToMillimetre(value: Double): Double = Math.round(value * 1000) / 1000.0

    private companion object {
        const val MAX_PROBLEMS = 100
        val STAGE_NUMBERS = listOf("widthM", "depthM", "heightM")
        val REGION_NUMBERS = listOf("centerX", "centerY", "centerZ", "widthM", "depthM", "heightM", "yawDeg")
        val RIGGING_NUMBERS = listOf("x", "y", "z", "yawDeg", "pitchDeg", "rollDeg", "lengthM")
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
