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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

/*
 * **The route function is gone; the DTOs and DAO helpers remain.**
 *
 * `routeApiRestProjectFxPresets` had been unmounted since the Looks migration (`routes/projects.kt`
 * explains why: a preset created through it would be invisible to every consumer). What removed it
 * now is that its preview and toggle routes were the last callers of the preset-toggle machinery,
 * which the programmer's Look-layer stack replaces — so keeping the function would have meant
 * keeping ~300 lines of superseded bookkeeping alive to serve routes nothing can reach.
 *
 * What is still here and still used:
 *
 * - `TogglePresetRequest` / `TogglePresetTarget` / `TogglePresetResponse` and
 *   `PresetPreviewRequest` / `PresetPreviewResponse` — the **wire shapes** the Look toggle and
 *   preview routes kept, so the desk did not have to change across the rewrite.
 * - `DaoFxPreset.toPresetDetails` and the property-assignment mapping, still reached from
 *   `programmerInclude.kt` until Include is rewritten against layers.
 *
 * The rest goes with `models/fxPresets.kt` in the retirement pass.
 */


// Resource classes
@Resource("/{projectId}/fx-presets")
data class ProjectFxPresetsResource(val projectId: String)

@Resource("/{presetId}")
data class ProjectFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

@Resource("/{presetId}/copy")
data class CopyFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

@Resource("/{presetId}/toggle")
data class ToggleFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

@Resource("/preview")
data class FxPresetPreviewResource(val parent: ProjectFxPresetsResource)

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
    val type: String,
    val key: String,
) {
    constructor(target: TargetRef) : this(target.discriminator, target.key)

    val target: TargetRef get() = TargetRef.of(type, key)
}

@Serializable
data class TogglePresetResponse(
    val action: String,  // "applied" or "removed"
    val effectCount: Int,
)

/**
 * Complete desired state for the project's preview slot — replaces any prior preview
 * writes. Empty `targets` (or `propertyAssignments`) collapses to a clear.
 */
@Serializable
data class PresetPreviewRequest(
    val propertyAssignments: List<FxPresetPropertyAssignmentDto> = emptyList(),
    val palette: List<String> = emptyList(),
    val targets: List<TogglePresetTarget> = emptyList(),
)

@Serializable
data class PresetPreviewResponse(
    val writeCount: Int,
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
            elementKey = it.elementKey,
            health = PersistedFixtureReferenceValidator.validatePresetPropertyReference(
                fixtureTypeKey, it.propertyName, it.elementKey,
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
            this.elementKey = assignment.elementKey
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
    target: TargetRef,
): Boolean {
    val activeEffects = when (target) {
        is TargetRef.Group -> engine.getEffectsForGroup(target.key)
        is TargetRef.Fixture -> engine.getEffectsForFixture(target.key)
    }
    return activeEffects.any { it.presetId == presetId }
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
    return when (target.target) {
        is TargetRef.Group -> {
            val group = state.show.fixtures.untypedGroup(target.key)
            val propertyName = presetEffect.propertyName
                ?: resolvePresetEffectProperty(presetEffect, group.detectCapabilities())
                ?: return null
            createGroupTarget(group.name, propertyName, group)
        }
        is TargetRef.Fixture -> {
            val propertyName = presetEffect.propertyName
                ?: resolvePresetEffectPropertyForFixture(presetEffect, target.key, state)
                ?: return null
            createFixtureTarget(target.key, propertyName, state)
        }
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
        speedMasterUuid = speedMasterUuidOrNull(presetEffect.speedMasterUuid)
        rateSpeedMasterUuid = speedMasterUuidOrNull(presetEffect.rateSpeedMasterUuid)
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
