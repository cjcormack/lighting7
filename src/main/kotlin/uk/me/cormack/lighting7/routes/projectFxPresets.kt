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
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val presets = transaction(state.database) {
                DaoFxPreset.find { DaoFxPresets.project eq project.id }
                    .orderBy(DaoFxPresets.name to SortOrder.ASC)
                    .map { it.toPresetDetails(isCurrentProject) }
            }
            call.respond(presets)
        }
    }

    // POST /{projectId}/fx-presets - Create new preset (current project only)
    post<ProjectFxPresetsResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot create presets in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val newPreset = call.receive<NewFxPreset>()
            if (newPreset.fixtureType.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("fixtureType is required"))
                return@withCurrentProject
            }
            val presetDetails = transaction(state.database) {
                val preset = DaoFxPreset.new {
                    name = newPreset.name
                    description = newPreset.description
                    fixtureType = newPreset.fixtureType
                    this.project = project
                    effects = newPreset.effects
                    palette = newPreset.palette
                }
                createPresetChildren(preset, newPreset.propertyAssignments)
                preset.toPresetDetails(isCurrentProject = true)
            }
            state.show.fixtures.presetListChanged()
            call.respond(HttpStatusCode.Created, presetDetails)
        }
    }

    // GET /{projectId}/fx-presets/{presetId} - Get preset details (any project)
    get<ProjectFxPresetResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
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
    }

    // PUT /{projectId}/fx-presets/{presetId} - Update preset (current project only)
    put<ProjectFxPresetResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify presets in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val updatedData = call.receive<NewFxPreset>()
            if (updatedData.fixtureType.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("fixtureType is required"))
                return@withCurrentProject
            }
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
                preset.palette = updatedData.palette
                deletePresetChildren(preset)
                createPresetChildren(preset, updatedData.propertyAssignments)
                preset.toPresetDetails(isCurrentProject = true)
            }

            if (presetDetails != null) {
                state.show.fixtures.presetListChanged()
                call.respond(presetDetails)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
            }
        }
    }

    // DELETE /{projectId}/fx-presets/{presetId} - Delete preset (current project only)
    delete<ProjectFxPresetResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete presets in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val result = transaction(state.database) {
                val preset = DaoFxPreset.findById(resource.presetId)
                    ?: return@transaction "not_found"

                if (preset.project.id != project.id) {
                    return@transaction "not_found"
                }

                // Check if any cues reference this preset via FK
                val referencingCues = DaoCuePresetApplication.find {
                    DaoCuePresetApplications.preset eq preset.id
                }.map { it.cue.name }.distinct()

                if (referencingCues.isNotEmpty()) {
                    return@transaction "used_by_cues:${referencingCues.joinToString(", ")}"
                }

                deletePresetChildren(preset)
                preset.delete()
                "ok"
            }

            when {
                result == "ok" -> {
                    state.show.fixtures.presetListChanged()
                    call.respond(HttpStatusCode.OK)
                }
                result.startsWith("used_by_cues:") -> {
                    val cueNames = result.removePrefix("used_by_cues:")
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("Cannot delete preset - it is referenced by cues: $cueNames")
                    )
                }
                else -> {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
                }
            }
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
                palette = sourcePreset.palette
            }
            createPresetChildren(newPreset, sourcePreset.toPropertyAssignmentDtos())

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
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<TogglePresetRequest>()
            if (request.targets.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one target is required"))
                return@withProject
            }

            val presetData = transaction(state.database) {
                val p = DaoFxPreset.findById(resource.presetId) ?: return@transaction null
                if (p.project.id != project.id) return@transaction null
                p.effects to p.toPropertyAssignmentDtos()
            }
            if (presetData == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
                return@withProject
            }
            val (presetEffects, presetAssignments) = presetData

            try {
                val result = togglePresetOnTargets(
                    state, resource.presetId, presetEffects, presetAssignments,
                    request.targets, request.beatDivision,
                )
                call.respond(result)
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to toggle preset"))
            }
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
    val fixtureType: String,
    val effects: List<FxPresetEffectDto>,
    val propertyAssignments: List<FxPresetPropertyAssignmentDto> = emptyList(),
    val palette: List<String> = emptyList(),
)

@Serializable
data class FxPresetDetails(
    val id: Int,
    val name: String,
    val description: String?,
    val fixtureType: String,
    val effects: List<FxPresetEffectDto>,
    val propertyAssignments: List<FxPresetPropertyAssignmentDto>,
    val palette: List<String>,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val cannotDeleteReason: String? = null,
    val cueUsageCount: Int = 0,
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
    val presetId = this.id.value
    val cueUsageCount = DaoCuePresetApplication.find {
        DaoCuePresetApplications.preset eq this@toPresetDetails.id
    }.map { it.cue.id }.distinct().count()
    val usedByCues = cueUsageCount > 0
    return FxPresetDetails(
        id = presetId,
        name = this.name,
        description = this.description,
        fixtureType = this.fixtureType,
        effects = this.effects,
        propertyAssignments = this.toPropertyAssignmentDtos(),
        palette = this.palette,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject && !usedByCues,
        cannotDeleteReason = if (usedByCues) "Used by $cueUsageCount cue${if (cueUsageCount != 1) "s" else ""}" else null,
        cueUsageCount = cueUsageCount,
    )
}

/**
 * Map this preset's property-assignment rows to their sorted DTO form. Rows are tagged
 * server-side with [AssignmentHealth] resolved against the preset's declared [fixtureType]
 * so the UI can mark dead rows (property removed, fixture type reworked).
 *
 * Apply-path callers (toggle / cue apply) also invoke this — they ignore the `health`
 * field, so the extra lookup cost is negligible and keeps a single code path.
 */
internal fun DaoFxPreset.toPropertyAssignmentDtos(): List<FxPresetPropertyAssignmentDto> {
    val fixtureTypeKey = this.fixtureType
    return this.propertyAssignments.sortedBy { it.sortOrder }.map {
        FxPresetPropertyAssignmentDto(
            propertyName = it.propertyName,
            value = it.value,
            fadeDurationMs = it.fadeDurationMs,
            sortOrder = it.sortOrder,
            health = PersistedFixtureReferenceValidator.validatePresetPropertyReference(
                fixtureTypeKey, it.propertyName,
            ),
        )
    }
}

/** Create [DaoFxPresetPropertyAssignment] rows for a preset. */
internal fun createPresetChildren(
    preset: DaoFxPreset,
    propertyAssignments: List<FxPresetPropertyAssignmentDto>,
) {
    for (assignment in propertyAssignments) {
        DaoFxPresetPropertyAssignment.new {
            this.preset = preset
            this.propertyName = assignment.propertyName
            this.value = assignment.value
            this.fadeDurationMs = assignment.fadeDurationMs
            this.sortOrder = assignment.sortOrder
        }
    }
}

/** Delete all child entities (property assignments) for a preset. */
internal fun deletePresetChildren(preset: DaoFxPreset) {
    preset.propertyAssignments.forEach { it.delete() }
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
 * Record of one Layer 4 channel assertion made by a preset toggle. Group-scoped preset
 * assignments expand to one entry per member fixture. `propertyName` is canonical form.
 */
private data class PresetToggleWrite(
    val fixtureKey: String,
    val propertyName: String,
)

/**
 * In-memory tracking of which presets currently have property writes on Layer 4. Mutations
 * go through [java.util.concurrent.ConcurrentHashMap.compute] so decide + act + record is
 * atomic per preset — two concurrent toggle requests for the same preset won't interleave.
 *
 * Caveat: if a fixture gets rekeyed or a group is rebuilt between toggle-on and toggle-off,
 * the recorded keys go stale and the corresponding channels leak until cleared manually.
 */
private val presetToggleStates =
    java.util.concurrent.ConcurrentHashMap<Int, List<PresetToggleWrite>>()

/**
 * Toggle a preset's effects + property assignments on/off for the given targets.
 *
 * Effects are tagged with [presetId] on the [FxInstance]; property assignments land on
 * Layer 4 via [FxEngine.writeLayer4Property] and are recorded in [presetToggleStates].
 *
 * A preset is considered "active" when ALL targets have an effect tagged with [presetId]
 * (if the preset defines effects) AND a [presetToggleStates] entry is present (if the
 * preset defines property assignments). "Active" → remove; otherwise → apply.
 */
internal fun togglePresetOnTargets(
    state: State,
    presetId: Int,
    presetEffects: List<FxPresetEffectDto>,
    presetPropertyAssignments: List<FxPresetPropertyAssignmentDto>,
    targets: List<TogglePresetTarget>,
    beatDivisionOverride: Double?,
): TogglePresetResponse {
    val engine = state.show.fxEngine
    lateinit var response: TogglePresetResponse

    presetToggleStates.compute(presetId) { _, existing ->
        // Resolve each target's current preset-tagged effects once — used by both the
        // allActive check and the remove loop. Avoids two `getEffectsFor*` calls per target.
        val presetEffectsByTarget = targets.map { target ->
            val active = if (target.type == "group") {
                engine.getEffectsForGroup(target.key)
            } else {
                engine.getEffectsForFixture(target.key)
            }
            active.filter { it.presetId == presetId }
        }
        val effectsActive = presetEffects.isEmpty() || presetEffectsByTarget.all { it.isNotEmpty() }
        val assignmentsActive = presetPropertyAssignments.isEmpty() || existing != null
        val allActive = effectsActive && assignmentsActive &&
            (presetEffects.isNotEmpty() || presetPropertyAssignments.isNotEmpty())

        if (allActive) {
            var removedCount = 0
            for (effectsOnTarget in presetEffectsByTarget) {
                for (fx in effectsOnTarget) {
                    if (engine.removeEffect(fx.id)) removedCount++
                }
            }
            existing?.forEach { clearPresetToggleWrite(state, it) }
            response = TogglePresetResponse(action = "removed", effectCount = removedCount)
            null
        } else {
            // Remove any lingering matching effects, then re-apply cleanly.
            for (effectsOnTarget in presetEffectsByTarget) {
                effectsOnTarget.forEach { engine.removeEffect(it.id) }
            }
            existing?.forEach { clearPresetToggleWrite(state, it) }

            val writes = applyPresetLayer4Writes(state, presetId, presetPropertyAssignments, targets)

            var addedCount = 0
            for (target in targets) {
                for (presetEffect in presetEffects) {
                    val fxTarget = resolveTarget(state, target, presetEffect) ?: continue
                    val instance = createInstanceFromPreset(
                        presetEffect, fxTarget, presetId, beatDivisionOverride, state,
                    )
                    engine.addEffect(instance)
                    addedCount++
                }
            }
            response = TogglePresetResponse(action = "applied", effectCount = addedCount)
            writes.ifEmpty { null }
        }
    }

    return response
}

/**
 * Apply each of [presetPropertyAssignments] to [targets] as Layer-4 writes. Reuses
 * [buildLayer3AssignmentsForPreset] for property canonicalisation, category lookup, value
 * parsing, and group expansion — the returned rows never actually hit Layer 3, the synthetic
 * `cueId = -presetId` only shows up in the builder's log lines if parsing fails.
 */
private fun applyPresetLayer4Writes(
    state: State,
    presetId: Int,
    presetPropertyAssignments: List<FxPresetPropertyAssignmentDto>,
    targets: List<TogglePresetTarget>,
): List<PresetToggleWrite> {
    if (presetPropertyAssignments.isEmpty()) return emptyList()
    val engine = state.show.fxEngine

    val rows = buildLayer3AssignmentsForPreset(
        state.show.fixtures,
        cueId = -presetId,
        priority = 0,
        presetId = presetId,
        presetAssignments = presetPropertyAssignments,
        applyTargets = targets.map { CueTargetDto(type = it.type, key = it.key) },
    )

    val writes = ArrayList<PresetToggleWrite>(rows.size)
    for (row in rows) {
        val fixture = try {
            state.show.fixtures.untypedFixture(row.targetKey)
        } catch (_: IllegalStateException) {
            continue
        }
        val resolved = engine.writeLayer4Property(fixture, row.propertyName, row.value)
        if (resolved.isNotEmpty()) {
            writes += PresetToggleWrite(row.targetKey, row.propertyName)
        }
    }
    return writes
}

/** Clear a single recorded Layer-4 assertion. Silently tolerates a stale record. */
private fun clearPresetToggleWrite(state: State, write: PresetToggleWrite) {
    val fixture = try {
        state.show.fixtures.untypedFixture(write.fixtureKey)
    } catch (_: IllegalStateException) {
        return
    }
    state.show.fxEngine.clearLayer4Property(fixture, write.propertyName)
}

/**
 * Resolve the [FxTarget] for a preset effect on a given toggle target.
 *
 * Returns null if the target doesn't support the effect's property,
 * signalling that this effect should be skipped for this target.
 */
private fun resolveTarget(
    state: State,
    target: TogglePresetTarget,
    presetEffect: FxPresetEffectDto
): FxTarget? {
    return if (target.type == "group") {
        val group = state.show.fixtures.untypedGroup(target.key)
        val propertyName = presetEffect.propertyName
            ?: resolvePresetEffectProperty(presetEffect, group.detectCapabilities())
            ?: return null
        createGroupTarget(group.name, propertyName, group)
    } else {
        val propertyName = presetEffect.propertyName
            ?: resolvePresetEffectPropertyForFixture(presetEffect, target.key, state)
            ?: return null
        createFixtureTarget(target.key, propertyName, state)
    }
}

/**
 * Create a fully-configured [FxInstance] from preset effect data.
 *
 * All FxInstance fields are populated from the [FxPresetEffectDto] in one place
 * to avoid duplication between group and fixture target paths.
 */
private fun createInstanceFromPreset(
    presetEffect: FxPresetEffectDto,
    fxTarget: FxTarget,
    presetId: Int,
    beatDivisionOverride: Double?,
    state: State,
): FxInstance {
    val engine = state.show.fxEngine
    val effect = state.show.fxRegistry.createEffect(
        presetEffect.effectType,
        presetEffect.parameters,
        paletteSupplier = engine::getPalette,
        paletteVersionSupplier = { engine.paletteVersion },
    )
    val beatDivision = beatDivisionOverride ?: presetEffect.beatDivision
    val timing = FxTiming(beatDivision)
    val blendMode = try {
        BlendMode.valueOf(presetEffect.blendMode)
    } catch (_: Exception) {
        BlendMode.OVERRIDE
    }
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
    val elementFilter = try {
        presetEffect.elementFilter?.let { ElementFilter.fromName(it) } ?: ElementFilter.ALL
    } catch (_: Exception) {
        ElementFilter.ALL
    }

    // Propagate timing source from the effect's registration
    val registration = state.show.fxRegistry.getRegistration(presetEffect.effectType)
    val timingSource = registration?.timingSource ?: uk.me.cormack.lighting7.fx.TimingSource.BEAT

    return FxInstance(effect, fxTarget, timing, blendMode).apply {
        this.presetId = presetId
        phaseOffset = presetEffect.phaseOffset
        distributionStrategy = distribution
        this.elementMode = elementMode
        this.elementFilter = elementFilter
        this.timingSource = timingSource
        presetEffect.stepTiming?.let { this.stepTiming = it }
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
