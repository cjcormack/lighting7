package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.*
import uk.me.cormack.lighting7.fixture.trait.*
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.DaoFxPresets
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry

internal fun Fixture.details(fixtures: Fixtures, compatiblePresetIds: List<Int> = emptyList()): FixtureDetails {
    val fixtureGroups = fixtures.groupsForFixture(this.key)

    return when (this) {
        is DmxFixture -> {
            val channels = this.channelDescriptions().map { channel ->
                DmxFixtureChannelDetails(channel.key, channel.value)
            }

            val modeInfo = if (this is MultiModeFixtureFamily<*>) {
                ModeInfo(
                    modeName = this.mode.modeName,
                    channelCount = this.mode.channelCount
                )
            } else null

            val capabilities = detectCapabilities()

            DmxFixtureDetails(
                name = this.fixtureName,
                key = this.key,
                typeKey = this.typeKey,
                groups = fixtureGroups,
                manufacturer = this.manufacturer.ifEmpty { null },
                model = this.model.ifEmpty { null },
                universe = this.universe.universe,
                firstChannel = this.firstChannel,
                channelCount = this.channelCount,
                channels = channels,
                properties = this.generatePropertyDescriptors(),
                elements = this.generateElementDescriptors(),
                elementGroupProperties = this.generateElementGroupPropertyDescriptors(),
                mode = modeInfo,
                capabilities = capabilities,
                compatiblePresetIds = compatiblePresetIds
            )
        }
        is HueFixture -> {
            HueFixtureDetails(this.fixtureName, this.key, this.typeKey, fixtureGroups, compatiblePresetIds)
        }
    }
}

private fun DmxFixture.detectCapabilities(): List<String> {
    val caps = mutableListOf<String>()
    if (this is WithDimmer) caps.add("dimmer")
    if (this is WithColour) caps.add("colour")
    if (this is WithPosition) caps.add("position")
    if (this is WithUv) caps.add("uv")
    if (this is WithStrobe) caps.add("strobe")
    if (this is MultiElementFixture<*>) {
        caps.add("multi-element")
        // Also detect capabilities available via element group properties
        val egp = generateElementGroupPropertyDescriptors()
        if (egp != null) {
            if ("dimmer" !in caps && egp.any { it is GroupSliderPropertyDescriptor && it.category == "dimmer" }) caps.add("dimmer")
            if ("colour" !in caps && egp.any { it is GroupColourPropertyDescriptor }) caps.add("colour")
            if ("position" !in caps && egp.any { it is GroupPositionPropertyDescriptor }) caps.add("position")
        }
    }
    return caps
}

/**
 * Infer which capability categories a preset's effects require.
 */
internal fun inferPresetCapabilities(effects: List<FxPresetEffectDto>): Set<String> {
    val caps = mutableSetOf<String>()
    for (e in effects) {
        when (e.category) {
            "dimmer" -> caps.add("dimmer")
            "colour" -> caps.add("colour")
            "position" -> caps.add("position")
        }
    }
    return caps
}

internal fun Route.routeApiRestLightsFixtures(state: State) {
    route("/fixture") {
        get("/list") {
            val fixtures = state.show.fixtures
            val currentProject = state.projectManager.currentProject

            // Load all presets for the current project
            data class PresetInfo(val id: Int, val fixtureType: String?, val effects: List<FxPresetEffectDto>)
            val presets = transaction(state.database) {
                DaoFxPreset.find { DaoFxPresets.project eq currentProject.id }
                    .map { PresetInfo(it.id.value, it.fixtureType, it.effects) }
            }

            call.respond(fixtures.fixtures.map { fixture ->
                val capabilities = when (fixture) {
                    is DmxFixture -> fixture.detectCapabilities().toSet()
                    else -> emptySet()
                }
                val compatibleIds = presets.filter { preset ->
                    // Check fixture type compatibility
                    if (preset.fixtureType != null && preset.fixtureType != fixture.typeKey) return@filter false
                    // Check capability compatibility
                    val requiredCaps = inferPresetCapabilities(preset.effects)
                    requiredCaps.all { it in capabilities }
                }.map { it.id }
                fixture.details(fixtures, compatibleIds)
            })
        }

        get("/types") {
            val registeredTypeKeys = state.show.fixtures.fixtures.map { it.typeKey }.toSet()
            call.respond(FixtureTypeRegistry.allTypes.map { info ->
                FixtureTypeDetails(
                    typeKey = info.typeKey,
                    manufacturer = info.manufacturer.ifEmpty { null },
                    model = info.model.ifEmpty { null },
                    modeName = info.modeName,
                    channelCount = info.channelCount,
                    isRegistered = info.typeKey in registeredTypeKeys,
                    capabilities = info.capabilities,
                    properties = info.properties,
                    elementGroupProperties = info.elementGroupProperties,
                )
            })
        }

        get<FixtureKey> {
            val fixtures = state.show.fixtures
            call.respond(fixtures.fixture<Fixture>(it.key).details(fixtures))
        }
    }
}

@Resource("/{key}")
data class FixtureKey(val key: String)

@Serializable
sealed interface FixtureDetails {
    val name: String
    val key: String
    val typeKey: String
    val groups: List<String>
    val compatiblePresetIds: List<Int>
}

@Serializable
data class DmxFixtureChannelDetails(
    val channelNo: Int,
    val description: String,
)

@Serializable
data class DmxFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
    override val groups: List<String>,
    val manufacturer: String?,
    val model: String?,
    val universe: Int,
    val firstChannel: Int,
    val channelCount: Int,
    val channels: List<DmxFixtureChannelDetails>,
    val properties: List<PropertyDescriptor>,
    val elements: List<ElementDescriptor>?,
    val elementGroupProperties: List<GroupPropertyDescriptor>?,
    val mode: ModeInfo?,
    val capabilities: List<String>,
    override val compatiblePresetIds: List<Int> = emptyList()
): FixtureDetails

@Serializable
data class HueFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
    override val groups: List<String>,
    override val compatiblePresetIds: List<Int> = emptyList()
): FixtureDetails

// Property Descriptor Types

@Serializable
data class ChannelRef(
    val universe: Int,
    val channelNo: Int
)

@Serializable
sealed interface PropertyDescriptor {
    val name: String
    val displayName: String
    val category: String
    val compactDisplay: String?
}

@Serializable
@kotlinx.serialization.SerialName("slider")
data class SliderPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    override val category: String,
    val channel: ChannelRef,
    val min: Int = 0,
    val max: Int = 255,
    override val compactDisplay: String? = null
) : PropertyDescriptor

@Serializable
@kotlinx.serialization.SerialName("colour")
data class ColourPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val redChannel: ChannelRef,
    val greenChannel: ChannelRef,
    val blueChannel: ChannelRef,
    val whiteChannel: ChannelRef? = null,
    val amberChannel: ChannelRef? = null,
    val uvChannel: ChannelRef? = null,
    override val compactDisplay: String? = null
) : PropertyDescriptor {
    override val category: String = "colour"
}

@Serializable
@kotlinx.serialization.SerialName("position")
data class PositionPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val panChannel: ChannelRef,
    val tiltChannel: ChannelRef,
    val panMin: Int = 0,
    val panMax: Int = 255,
    val tiltMin: Int = 0,
    val tiltMax: Int = 255,
    override val compactDisplay: String? = null
) : PropertyDescriptor {
    override val category: String = "position"
}

@Serializable
@kotlinx.serialization.SerialName("setting")
data class SettingPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    override val category: String,
    val channel: ChannelRef,
    val options: List<SettingOption>,
    override val compactDisplay: String? = null
) : PropertyDescriptor

@Serializable
data class SettingOption(
    val name: String,
    val level: Int,
    val displayName: String,
    val colourPreview: String? = null
)

@Serializable
data class ElementDescriptor(
    val index: Int,
    val key: String,
    val displayName: String,
    val properties: List<PropertyDescriptor>
)

@Serializable
data class ModeInfo(
    val modeName: String,
    val channelCount: Int
)

@Serializable
data class FixtureTypeDetails(
    val typeKey: String,
    val manufacturer: String?,
    val model: String?,
    val modeName: String?,
    val channelCount: Int?,
    val isRegistered: Boolean,
    val capabilities: List<String>,
    val properties: List<PropertyDescriptor>,
    val elementGroupProperties: List<GroupPropertyDescriptor>?,
)
