package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.*
import uk.me.cormack.lighting7.fixture.trait.*
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookEffects
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry

internal fun Fixture.details(fixtures: Fixtures, compatibleLookIds: List<Int> = emptyList()): FixtureDetails {
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
            val patchMetadata = fixtures.patchMetadataFor(this.key)

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
                compatibleLookIds = compatibleLookIds,
                gelCode = patchMetadata?.gelCode,
                infrastructure = patchMetadata?.infrastructure == true,
            )
        }
        is HueFixture -> {
            HueFixtureDetails(this.fixtureName, this.key, this.typeKey, fixtureGroups, compatibleLookIds)
        }
    }
}

private fun DmxFixture.detectCapabilities(): List<String> {
    val caps = mutableListOf<String>()
    if (this is WithDimmer) caps.add("dimmer")
    if (this is WithColour) caps.add("colour")
    if (this is WithPosition) caps.add("position")
    if (this is WithWhite) caps.add("white")
    if (this is WithAmber) caps.add("amber")
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
 * Infer which capability categories a Look's effects require.
 *
 * Only `dimmer` / `colour` / `position` are recognised; anything else — notably `controls` —
 * contributes nothing, so it never narrows compatibility.
 */
internal fun inferLookCapabilities(categories: List<String>): Set<String> {
    val caps = mutableSetOf<String>()
    for (category in categories) {
        when (category) {
            "dimmer" -> caps.add("dimmer")
            "colour" -> caps.add("colour")
            "position" -> caps.add("position")
        }
    }
    return caps
}

/**
 * What compatibility filtering needs to know about one Look.
 *
 * Just the effect categories now. There was an `editorFixtureType` beside them, and dropping it is
 * D6: compatibility is **capability-only** — "can this head mix a colour at all", never "was this
 * authored against that model". The type gate is what made one colour need one Look per fixture
 * type, and it went with the column when templates became their own entity.
 */
internal data class LookCompatibilityInfo(
    val id: Int,
    val effectCategories: List<String>,
)

/**
 * Load the compatibility metadata for every Look in [projectId]. Two DB round-trips, not one per
 * Look: a preset's effects were a JSON column that came along with the parent row, but a Look's are
 * a table, so touching `look.effects` per Look would be a lazy query each — on every `GET /fixtures`
 * and `GET /groups`.
 */
internal fun loadLookCompatibilityInfos(state: State, projectId: Int): List<LookCompatibilityInfo> =
    transaction(state.database) {
        val looks = DaoLook.find { DaoLooks.project eq projectId }.toList()
        if (looks.isEmpty()) return@transaction emptyList()
        val categoriesByLook = DaoLookEffect
            .find { DaoLookEffects.look inList looks.map { it.id } }
            .groupBy({ it.look.id.value }, { it.category })
        looks.map { look ->
            LookCompatibilityInfo(
                id = look.id.value,
                effectCategories = categoriesByLook[look.id.value].orEmpty(),
            )
        }
    }

/**
 * The ids of Looks offerable for a target with these [capabilities].
 *
 * **Capability-only** (D6). There used to be a type gate here as well — a deferred Look was refused
 * unless its `editorFixtureType` was the target's own type, exactly as an FX preset was — and that
 * gate is the whole reason "Amber Key" authored against a MAC Aura was refused to the LED bar beside
 * it. It is gone with the column: what remains is the honest question, whether the head can do the
 * thing at all. An effect needing colour is still no use on a fixture that cannot mix one.
 *
 * `allowedTypeKeys` went with it. Every caller passed the target's own type(s) and nothing else
 * reads them, so keeping the parameter would only preserve the shape of a filter that no longer
 * exists.
 */
internal fun List<LookCompatibilityInfo>.compatibleIdsFor(
    capabilities: Set<String>,
): List<Int> = filter { look ->
    inferLookCapabilities(look.effectCategories).all { it in capabilities }
}.map { it.id }

internal fun Route.routeApiRestLightsFixtures(state: State) {
    // Two sibling resources, not one tree: `/fixtures` is the patched rig, `/fixture-types` is a
    // vocabulary enumeration of every type the build knows about — most of which are not patched.
    // It sits alongside `/control-surface-types` rather than under the rig it doesn't describe.
    route("/fixtures") {
        get {
            val fixtures = state.show.fixtures
            val currentProject = state.projectManager.currentProject

            val looks = loadLookCompatibilityInfos(state, currentProject.id.value)

            call.respond(fixtures.fixtures.map { fixture ->
                val capabilities = when (fixture) {
                    is DmxFixture -> fixture.detectCapabilities().toSet()
                    else -> emptySet()
                }
                val compatibleIds = looks.compatibleIdsFor(capabilities)
                fixture.details(fixtures, compatibleIds)
            })
        }

        get<FixtureKey> {
            val fixtures = state.show.fixtures
            call.respond(fixtures.fixture<Fixture>(it.key).details(fixtures))
        }
    }

    get("/fixture-types") {
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
                acceptsBeamAngle = info.acceptsBeamAngle,
                acceptsGel = info.acceptsGel,
                gelCompactDisplay = info.gelCompactDisplay.serialized(),
                kind = info.kind.name,
                lengthM = info.lengthM,
                widthM = info.widthM,
                heightM = info.heightM,
                acceptsLength = info.acceptsLength,
                beamShape = info.beamShape.name,
                beamEdge = info.beamEdge.name,
                body = info.body,
                acceptsLantern = info.acceptsLantern,
                depthOfField = info.depthOfField,
            )
        })
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

    /**
     * Ids of the **Looks** this fixture can be pointed at, filtered by inferred capability alone
     * — see [compatibleIdsFor], and the D6 note on its KDoc for the type gate that used to be here
     * too. A Look's *rows* name their own targets, so the question is only ever about its effects:
     * a Look holding no effect is compatible with everything.
     */
    val compatibleLookIds: List<Int>
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
    override val compatibleLookIds: List<Int> = emptyList(),
    val gelCode: String? = null,
    /**
     * The patch is infrastructure (a power dimmer, a relay), not a lighting fixture. The list still
     * carries it — a cue or group that names it must still resolve — and every operator surface but
     * the Patches and Channels views filters it out.
     */
    val infrastructure: Boolean = false,
): FixtureDetails

@Serializable
data class HueFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
    override val groups: List<String>,
    override val compatibleLookIds: List<Int> = emptyList()
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
    override val compactDisplay: String? = null,
    val axis: String? = null,
    val degMin: Double? = null,
    val degMax: Double? = null,
    val inverted: Boolean? = null,
    val focusNearM: Double? = null,
    val focusFarM: Double? = null,
    /** The coarse property this slider is the low byte of (`@FixtureProperty.fineOf`), or null. */
    val fineOf: String? = null,
    /** A GOBO_ROTATION slider's speed at DMX max in a rotate band, in RPM. */
    val rpmMax: Double? = null,
    /** A GOBO_ROTATION slider's angle at DMX max in the index band, in degrees. */
    val indexDegMax: Double? = null,
    /** A SHUTTER / SHUTTER_ROTATION slider's blade (`TOP`, `BOTTOM`, `LEFT`, `RIGHT`), or null. */
    val blade: String? = null,
    /** A SHUTTER slider's blade depth at DMX max, as a fraction of the field's diameter (0.5 the centre). */
    val depthMax: Double? = null,
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

/**
 * A **one-shot trigger** (stage-view plan session 9, D15) — a cannon's tube. Not a control: nothing
 * sets it. It is fired as an event (`POST …/patches/{id}/fire`, a cue event, a MIDI `FireTrigger`),
 * only while the desk is armed, and its [armChannel] follows the desk's arm. Listed so a client can
 * draw the cannon's panel and the DMX sheet can name the channels; a client that draws controls from
 * the property list must skip it.
 */
@Serializable
@kotlinx.serialization.SerialName("trigger")
data class TriggerPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    /** The short name an event row and a button use ("A"). */
    val label: String,
    val channel: ChannelRef,
    val armChannel: ChannelRef,
    /** The arm channel's name — never a property, refused by name wherever a row names it. */
    val armName: String,
) : PropertyDescriptor {
    override val category: String = "trigger"
    override val compactDisplay: String? = null
}

@Serializable
data class SettingOption(
    val name: String,
    val level: Int,
    val displayName: String,
    val colourPreview: String? = null,
    /** Lowercase [uk.me.cormack.lighting7.fixture.dmx.GoboPattern] name, or null when open/no-op. */
    val gobo: String? = null,
    /** Prism facet count at this position, or null when the prism is out. */
    val prismFacets: Int? = null,
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
    val acceptsBeamAngle: Boolean = false,
    val acceptsGel: Boolean = false,
    val gelCompactDisplay: String? = null,
    val kind: String = "GENERIC",
    // Physical bounding size in metres (lengthM = long axis) + beam geometry.
    // Defaults keep older clients/payloads valid.
    val lengthM: Double? = null,
    val widthM: Double? = null,
    val heightM: Double? = null,
    /** A patch of this type may carry its own `lengthM`; [lengthM] is then the default. */
    val acceptsLength: Boolean = false,
    val beamShape: String = "NONE",
    val beamEdge: String = "SOFT",
    /** The body the type declares (`@FixtureType.body`); null when the view defaults it from [kind]. */
    val body: FixtureBodyInfo? = null,
    /** A patch of this type names a lantern from `GET /lanterns` and carries its focus. */
    val acceptsLantern: Boolean = false,
    /** The type's depth-of-field constant (`@FixtureType.depthOfField`); null for the family's. */
    val depthOfField: Double? = null,
)
