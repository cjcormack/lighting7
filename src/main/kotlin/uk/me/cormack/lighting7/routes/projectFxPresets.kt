package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectFxPresets(state: State) {
    // GET /{projectId}/fx-presets - List presets for a project
    get<ProjectFxPresetsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val presets = transaction(state.database) {
            DaoFxPreset.find { DaoFxPresets.project eq project.id }
                .orderBy(DaoFxPresets.name to SortOrder.ASC)
                .map { it.toPresetDetails(isCurrentProject) }
        }
        call.respond(presets)
    }

    // POST /{projectId}/fx-presets - Create new preset (current project only)
    post<ProjectFxPresetsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot create presets in project '${project.name}' - only the current project can be modified")
            )
            return@post
        }

        val newPreset = call.receive<NewFxPreset>()
        val presetDetails = transaction(state.database) {
            DaoFxPreset.new {
                name = newPreset.name
                description = newPreset.description
                fixtureType = newPreset.fixtureType
                this.project = project
                effects = newPreset.effects
            }.toPresetDetails(isCurrentProject = true)
        }
        state.show.fixtures.presetListChanged()
        call.respond(HttpStatusCode.Created, presetDetails)
    }

    // GET /{projectId}/fx-presets/{presetId} - Get preset details (any project)
    get<ProjectFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val preset = transaction(state.database) {
            val preset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction null

            if (preset.project.id != project.id) {
                return@transaction null
            }

            preset.toPresetDetails(isCurrentProject)
        }

        if (preset != null) {
            call.respond(preset)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
        }
    }

    // PUT /{projectId}/fx-presets/{presetId} - Update preset (current project only)
    put<ProjectFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot modify presets in project '${project.name}' - only the current project can be modified")
            )
            return@put
        }

        val updatedData = call.receive<NewFxPreset>()
        val presetDetails = transaction(state.database) {
            val preset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction null

            if (preset.project.id != project.id) {
                return@transaction null
            }

            preset.name = updatedData.name
            preset.description = updatedData.description
            preset.fixtureType = updatedData.fixtureType
            preset.effects = updatedData.effects
            preset.toPresetDetails(isCurrentProject = true)
        }

        if (presetDetails != null) {
            state.show.fixtures.presetListChanged()
            call.respond(presetDetails)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
        }
    }

    // DELETE /{projectId}/fx-presets/{presetId} - Delete preset (current project only)
    delete<ProjectFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot delete presets in project '${project.name}' - only the current project can be modified")
            )
            return@delete
        }

        val found = transaction(state.database) {
            val preset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction false

            if (preset.project.id != project.id) {
                return@transaction false
            }

            preset.delete()
            true
        }

        if (found) {
            state.show.fixtures.presetListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
        }
    }

    // POST /{projectId}/fx-presets/{presetId}/copy - Copy preset to another project
    post<CopyFxPresetResource> { resource ->
        val sourceProject = state.resolveProject(resource.parent.projectId)
        if (sourceProject == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Source project not found"))
            return@post
        }

        val request = call.receive<CopyPresetRequest>()

        val result = transaction(state.database) {
            val sourcePreset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction null to "Preset not found"

            if (sourcePreset.project.id != sourceProject.id) {
                return@transaction null to "Preset does not belong to specified project"
            }

            val targetProject = DaoProject.findById(request.targetProjectId)
                ?: return@transaction null to "Target project not found"

            val presetName = request.newName ?: sourcePreset.name

            val sourceFixtureType = sourcePreset.fixtureType
            val existingPreset = DaoFxPreset.find {
                (DaoFxPresets.project eq targetProject.id) and
                (DaoFxPresets.name eq presetName) and
                (DaoFxPresets.fixtureType eq sourceFixtureType)
            }.firstOrNull()
            if (existingPreset != null) {
                return@transaction null to "A preset with name '$presetName' already exists in target project"
            }

            val newPreset = DaoFxPreset.new {
                name = presetName
                description = sourcePreset.description
                fixtureType = sourceFixtureType
                project = targetProject
                effects = sourcePreset.effects
            }

            CopyPresetResponse(
                presetId = newPreset.id.value,
                presetName = newPreset.name,
                targetProjectId = targetProject.id.value,
                targetProjectName = targetProject.name,
                message = "Preset copied successfully"
            ) to null
        }

        val (response, error) = result
        if (response != null) {
            state.show.fixtures.presetListChanged()
            call.respond(HttpStatusCode.Created, response)
        } else {
            val statusCode = when (error) {
                "Preset not found", "Target project not found" -> HttpStatusCode.NotFound
                "Preset does not belong to specified project" -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.Conflict
            }
            call.respond(statusCode, ErrorResponse(error ?: "Unknown error"))
        }
    }

    // POST /{projectId}/fx-presets/{presetId}/toggle - Toggle preset on/off for targets
    post<ToggleFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        val request = call.receive<TogglePresetRequest>()
        if (request.targets.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one target is required"))
            return@post
        }

        val preset = transaction(state.database) {
            val p = DaoFxPreset.findById(resource.presetId) ?: return@transaction null
            if (p.project.id != project.id) return@transaction null
            p.effects
        }
        if (preset == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
            return@post
        }

        try {
            val result = togglePresetOnTargets(state, resource.presetId, preset, request.targets, request.beatDivision)
            call.respond(result)
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to toggle preset"))
        }
    }
}

// Resource classes
@Resource("/{projectId}/fx-presets")
data class ProjectFxPresetsResource(val projectId: String)

@Resource("/{presetId}")
data class ProjectFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

@Resource("/{presetId}/copy")
data class CopyFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

@Resource("/{presetId}/toggle")
data class ToggleFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

// DTOs
@Serializable
data class NewFxPreset(
    val name: String,
    val description: String? = null,
    val fixtureType: String? = null,
    val effects: List<FxPresetEffectDto>,
)

@Serializable
data class FxPresetDetails(
    val id: Int,
    val name: String,
    val description: String?,
    val fixtureType: String?,
    val effects: List<FxPresetEffectDto>,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Serializable
data class CopyPresetRequest(
    val targetProjectId: Int,
    val newName: String? = null,
)

@Serializable
data class CopyPresetResponse(
    val presetId: Int,
    val presetName: String,
    val targetProjectId: Int,
    val targetProjectName: String,
    val message: String,
)

@Serializable
data class TogglePresetRequest(
    val targets: List<TogglePresetTarget>,
    val beatDivision: Double? = null,
)

@Serializable
data class TogglePresetTarget(
    val type: String,  // "group" or "fixture"
    val key: String,   // group name or fixture key
)

@Serializable
data class TogglePresetResponse(
    val action: String,  // "applied" or "removed"
    val effectCount: Int,
)

// Helper
internal fun DaoFxPreset.toPresetDetails(isCurrentProject: Boolean): FxPresetDetails {
    return FxPresetDetails(
        id = this.id.value,
        name = this.name,
        description = this.description,
        fixtureType = this.fixtureType,
        effects = this.effects,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}

/**
 * Normalize an effect type name for comparison (lowercase, no spaces/underscores).
 * Matches the frontend's normalizeEffectName function.
 */
private fun normalizeEffectName(name: String): String {
    return name.lowercase().replace(Regex("[\\s_]"), "")
}

/**
 * Check if a preset is active on a target by looking for effects tagged with the preset ID.
 */
private fun isPresetActiveOnTarget(
    engine: FxEngine,
    presetId: Int,
    targetType: String,
    targetKey: String,
): Boolean {
    val activeEffects = if (targetType == "group") {
        engine.getEffectsForGroup(targetKey)
    } else {
        engine.getEffectsForFixture(targetKey)
    }
    return activeEffects.any { it.presetId == presetId }
}

/**
 * Toggle a preset's effects on/off for the given targets.
 *
 * Uses the presetId tag on effects for deterministic matching:
 * - If ALL targets have at least one effect tagged with this presetId, removes those effects.
 * - Otherwise, applies all preset effects (tagged with the presetId) to each target.
 */
internal fun togglePresetOnTargets(
    state: State,
    presetId: Int,
    presetEffects: List<FxPresetEffectDto>,
    targets: List<TogglePresetTarget>,
    beatDivisionOverride: Double?,
): TogglePresetResponse {
    val engine = state.show.fxEngine

    // Check if all targets have this preset active
    val allActive = targets.all { target ->
        isPresetActiveOnTarget(engine, presetId, target.type, target.key)
    }

    if (allActive) {
        // Remove all effects tagged with this presetId from all targets
        var removedCount = 0
        for (target in targets) {
            val activeEffects = if (target.type == "group") {
                engine.getEffectsForGroup(target.key)
            } else {
                engine.getEffectsForFixture(target.key)
            }
            val matching = activeEffects.filter { it.presetId == presetId }
            for (fx in matching) {
                if (engine.removeEffect(fx.id)) {
                    removedCount++
                }
            }
        }
        return TogglePresetResponse(action = "removed", effectCount = removedCount)
    } else {
        // First remove any existing effects from this preset on all targets,
        // then apply all preset effects fresh
        for (target in targets) {
            val activeEffects = if (target.type == "group") {
                engine.getEffectsForGroup(target.key)
            } else {
                engine.getEffectsForFixture(target.key)
            }
            activeEffects.filter { it.presetId == presetId }.forEach { engine.removeEffect(it.id) }
        }

        var addedCount = 0
        for (target in targets) {
            for (presetEffect in presetEffects) {
                val effect = createEffectFromTypeAndParams(presetEffect.effectType, presetEffect.parameters)
                val beatDivision = beatDivisionOverride ?: presetEffect.beatDivision
                val timing = FxTiming(beatDivision)
                val blendMode = try {
                    BlendMode.valueOf(presetEffect.blendMode)
                } catch (_: Exception) {
                    BlendMode.OVERRIDE
                }

                if (target.type == "group") {
                    val group = state.show.fixtures.untypedGroup(target.key)
                    val propertyName = presetEffect.propertyName ?: resolvePresetEffectProperty(presetEffect, group.detectCapabilities())
                    if (propertyName == null) continue

                    val fxTarget = createGroupTarget(group.name, propertyName, group)
                    val distribution = try {
                        DistributionStrategy.fromName(presetEffect.distribution)
                    } catch (_: Exception) {
                        DistributionStrategy.LINEAR
                    }
                    val elementMode = try {
                        presetEffect.elementMode?.let { ElementMode.valueOf(it) } ?: ElementMode.PER_FIXTURE
                    } catch (_: Exception) {
                        ElementMode.PER_FIXTURE
                    }

                    val elFilter = try {
                        presetEffect.elementFilter?.let { ElementFilter.fromName(it) } ?: ElementFilter.ALL
                    } catch (_: Exception) {
                        ElementFilter.ALL
                    }

                    val instance = FxInstance(effect, fxTarget, timing, blendMode).apply {
                        this.presetId = presetId
                        phaseOffset = presetEffect.phaseOffset
                        distributionStrategy = distribution
                        this.elementMode = elementMode
                        this.elementFilter = elFilter
                    }
                    engine.addEffect(instance)
                    addedCount++
                } else {
                    val propertyName = presetEffect.propertyName ?: resolvePresetEffectPropertyForFixture(presetEffect, target.key, state)
                    if (propertyName == null) continue

                    val fxTarget = createFixtureTarget(target.key, propertyName, state)
                    val elFilter = try {
                        presetEffect.elementFilter?.let { ElementFilter.fromName(it) } ?: ElementFilter.ALL
                    } catch (_: Exception) {
                        ElementFilter.ALL
                    }
                    val instance = FxInstance(effect, fxTarget, timing, blendMode).apply {
                        this.presetId = presetId
                        phaseOffset = presetEffect.phaseOffset
                        this.elementFilter = elFilter
                    }
                    engine.addEffect(instance)
                    addedCount++
                }
            }
        }
        return TogglePresetResponse(action = "applied", effectCount = addedCount)
    }
}

/**
 * Resolve the property name for a preset effect based on its category.
 */
private fun resolvePresetEffectProperty(
    presetEffect: FxPresetEffectDto,
    capabilities: List<String>,
): String? {
    return when (presetEffect.category) {
        "dimmer" -> if ("dimmer" in capabilities) "dimmer" else null
        "colour" -> if ("colour" in capabilities) "colour" else null
        "position" -> if ("position" in capabilities) "position" else null
        "controls", "setting" -> presetEffect.propertyName
        else -> null
    }
}

/**
 * Resolve the property name for a preset effect on a specific fixture.
 */
private fun resolvePresetEffectPropertyForFixture(
    presetEffect: FxPresetEffectDto,
    fixtureKey: String,
    state: State,
): String? {
    return when (presetEffect.category) {
        "dimmer" -> "dimmer"
        "colour" -> "colour"
        "position" -> "position"
        "controls", "setting" -> presetEffect.propertyName
        else -> null
    }
}

/**
 * Create an FxTarget for a group based on property name.
 */
private fun createGroupTarget(
    groupName: String,
    propertyName: String,
    group: FixtureGroup<*>,
): FxTarget {
    return when (propertyName.lowercase()) {
        "dimmer" -> SliderTarget.forGroup(groupName, "dimmer")
        "colour", "color", "rgbcolour" -> ColourTarget.forGroup(groupName)
        "position" -> PositionTarget.forGroup(groupName)
        "uv" -> SliderTarget.forGroup(groupName, "uv")
        else -> {
            val firstFixture = group.fixtures.firstOrNull() as? Fixture
            val prop = firstFixture?.fixtureProperties?.find { it.name == propertyName }
            val propValue = prop?.classProperty?.call(firstFixture)
            if (propValue is Slider) {
                SliderTarget.forGroup(groupName, propertyName)
            } else {
                SettingTarget.forGroup(groupName, propertyName)
            }
        }
    }
}

/**
 * Create an FxTarget for a fixture based on property name.
 */
private fun createFixtureTarget(
    fixtureKey: String,
    propertyName: String,
    state: State,
): FxTarget {
    return when (propertyName.lowercase()) {
        "dimmer" -> SliderTarget(fixtureKey, "dimmer")
        "uv" -> SliderTarget(fixtureKey, "uv")
        "colour", "color", "rgbcolour" -> ColourTarget(fixtureKey)
        "position" -> PositionTarget(fixtureKey)
        else -> {
            val fixture = try {
                state.show.fixtures.untypedFixture(fixtureKey) as? Fixture
            } catch (_: Exception) { null }
            val prop = fixture?.fixtureProperties?.find { it.name == propertyName }
            val propValue = prop?.classProperty?.call(fixture)
            if (propValue is Slider) {
                SliderTarget(fixtureKey, propertyName)
            } else {
                SettingTarget(fixtureKey, propertyName)
            }
        }
    }
}
