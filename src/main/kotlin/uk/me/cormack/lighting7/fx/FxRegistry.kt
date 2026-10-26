package uk.me.cormack.lighting7.fx

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.Timing
import java.util.concurrent.ConcurrentHashMap

/**
 * Source of an effect registration.
 */
enum class FxSource {
    /** Built-in effect shipped with the application (from .fx.kts resource files) */
    BUILT_IN,
    /** User-created effect (from fx_definitions database table) */
    USER,
}

/**
 * Factory function that creates an [Fx] from string parameters.
 *
 * @param params Effect-specific parameters as string key-value pairs
 * @param resolveColourSource Optional resolver for a colour parameter that *names* a colour rather
 *        than stating one — a `pal:{uuid}` template reference. See [paletteColourSource].
 * @param colourSourceVersion Optional version counter, bumped when a referenced template is edited,
 *        so a running effect re-resolves without being rebuilt.
 */
typealias FxFactory = (
    params: Map<String, String>,
    resolveColourSource: ((String) -> ExtendedColour?)?,
    colourSourceVersion: (() -> Long)?,
) -> Fx

/**
 * Metadata for a registered effect type.
 *
 * Contains everything needed to list the effect in the library API,
 * create instances from parameters, and display in the UI.
 */
data class FxRegistration(
    /** Canonical identifier (e.g., "SineWave") */
    val id: String,
    /** Alternative names for lookup (normalized lowercase, no spaces) */
    val aliases: Set<String> = emptySet(),
    /** Human-readable display name */
    val name: String,
    /** UI category (e.g., "intensity", "colour", "position", "beam", "controls") */
    val family: String,
    /** Output type this effect produces */
    val outputType: FxOutputType,
    /** How this effect is evaluated (STANDARD, STATEFUL) */
    val fxMode: FxMode = FxMode.STANDARD,
    /** Parameter schema for UI and API */
    val parameters: List<ParameterInfo> = emptyList(),
    /** Which fixture properties this effect can target */
    val compatibleAttributes: List<String>,
    /** Whether this is built-in or user-created */
    val source: FxSource = FxSource.BUILT_IN,
    /** Database ID of the fx_definition (for USER source) */
    val sourceDefinitionId: Int? = null,
    /** The calculate script body (Kotlin source) */
    val script: String? = null,
    /** Default step-timing mode for new instances */
    val defaultTiming: Timing = Timing.CYCLE,
    /** Direction a new instance runs in when its author names none */
    val defaultDirection: FxDirection = FxDirection.FORWARD,
    /** Cycle, in beats, a new instance runs at when its author names none */
    val defaultCycle: Double = CycleLength.ONE_BAR,
    /** Timing source for this effect (BEAT or WALL_CLOCK) */
    val timingSource: TimingSource = TimingSource.BEAT,
    /** The picker's familiar names for this FX on one attribute (fx-settings plan D4). */
    val presets: List<FxPreset> = emptyList(),
    /** Parameters a new FX on an attribute starts with, by attribute — Wave's zoom at 64–192. */
    val attributeDefaults: Map<String, Map<String, String>> = emptyMap(),
    /** Factory to create an Effect instance from string parameters */
    val factory: FxFactory,
)

/**
 * DTO describing an available effect type for the API.
 */
@Serializable
data class FxTypeInfo(
    val name: String,
    val family: String,
    val outputType: String,
    val fxMode: String = "STANDARD",
    val parameters: List<ParameterInfo>,
    val compatibleAttributes: List<String>,
    val source: String? = null,
    val sourceDefinitionId: Int? = null,
    val script: String? = null,
    val timingSource: String = "BEAT",
    /** What a new instance's `direction` is when its author names none. */
    val defaultDirection: String = FxDirection.FORWARD.name,
    /** What a new instance's `cycle` is when its author names none. */
    val defaultCycle: Double = CycleLength.ONE_BAR,
    val presets: List<FxPreset> = emptyList(),
    val attributeDefaults: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * A familiar name the picker lists beside the FX types (fx-settings plan D4): this FX on
 * [attribute], starting with [parameters] over the FX's defaults, and its own [cycle] (beats) and
 * [direction] where it names them.
 */
@Serializable
data class FxPreset(
    val name: String,
    val attribute: String,
    val parameters: Map<String, String> = emptyMap(),
    val cycle: Double? = null,
    val direction: String? = null,
)

/**
 * DTO describing a single effect parameter: what it is to the FX ([name], [type], [defaultValue]) and,
 * optionally, how the editor draws it (fx-settings plan D1). Every metadata field may be absent; the
 * editor then guesses as it always has. [parameterMetadataProblems] is what a declaration must pass.
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String,
    val description: String = "",
    /** The editor's word for it — *Low*, *Attack*. */
    val label: String? = null,
    /** The lowest value the editor offers, in the stored value's terms (a byte's 0–255, a ratio's 0–1). */
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** How the stored value reads: one of [PARAMETER_UNITS]. */
    val unit: String? = null,
    /** A `choice` parameter's values, in the order drawn. */
    val options: List<ParameterOption>? = null,
    /** `main`, drawn with the FX, or `detail`, folded under *More*. */
    val role: String? = null,
    /** Drawn only while another parameter holds one of these values. */
    val shownWhen: ParameterCondition? = null,
    /** On a low, the high it is drawn with as one range. */
    val pair: String? = null,
)

/** One value a `choice` parameter may hold, and its label. */
@Serializable
data class ParameterOption(val value: String, val label: String)

/** "While [param] is one of [values]". */
@Serializable
data class ParameterCondition(val param: String, val values: List<String>)

/**
 * Unified registry for all effect types (built-in and script-defined).
 *
 * Provides a single source of truth for:
 * - Listing available effects (for the library API)
 * - Creating effect instances from type name + parameters
 * - Registering/unregistering effects at runtime (for scripts)
 *
 * Thread-safe via [ConcurrentHashMap].
 */
class FxRegistry {
    private val registrations = ConcurrentHashMap<String, FxRegistration>()
    private val aliasIndex = ConcurrentHashMap<String, String>()

    /**
     * Register an effect type.
     *
     * The canonical [FxRegistration.id] and all [FxRegistration.aliases]
     * are indexed for lookup. Aliases are normalized (lowercased, spaces removed).
     */
    fun register(registration: FxRegistration) {
        registrations[registration.id] = registration

        // Index the canonical id (normalized)
        aliasIndex[normalize(registration.id)] = registration.id

        // Index all aliases
        for (alias in registration.aliases) {
            aliasIndex[normalize(alias)] = registration.id
        }
    }

    /**
     * Unregister an effect type and all its aliases.
     */
    fun unregister(id: String) {
        val registration = registrations.remove(id) ?: return

        // Remove canonical id from alias index
        aliasIndex.remove(normalize(registration.id))

        // Remove all aliases
        for (alias in registration.aliases) {
            aliasIndex.remove(normalize(alias))
        }
    }

    /**
     * Create an effect instance from a type name and parameters.
     *
     * The type name is looked up by canonical id or alias (case-insensitive, spaces stripped).
     *
     * @throws IllegalArgumentException if the effect type is not registered
     */
    fun createFx(
        fxType: String,
        params: Map<String, String> = emptyMap(),
        resolveColourSource: ((String) -> ExtendedColour?)? = null,
        colourSourceVersion: (() -> Long)? = null,
    ): Fx {
        val registration = getRegistration(fxType)
            ?: throw IllegalArgumentException("Unknown FX type: $fxType")
        return registration.factory(params, resolveColourSource, colourSourceVersion)
    }

    /**
     * Look up a registration by canonical id or alias.
     */
    fun getRegistration(fxType: String): FxRegistration? {
        val canonicalId = aliasIndex[normalize(fxType)] ?: return null
        return registrations[canonicalId]
    }

    /**
     * Get the effect library as DTOs for the API.
     */
    fun getLibrary(): List<FxTypeInfo> {
        return registrations.values
            .sortedBy { it.id }
            .map { reg ->
                FxTypeInfo(
                    name = reg.id,
                    family = reg.family,
                    outputType = reg.outputType.name,
                    fxMode = reg.fxMode.name,
                    parameters = reg.parameters.map { it.withImpliedOptions() },
                    compatibleAttributes = reg.compatibleAttributes,
                    source = reg.source.name,
                    sourceDefinitionId = reg.sourceDefinitionId,
                    script = reg.script,
                    timingSource = reg.timingSource.name,
                    defaultDirection = reg.defaultDirection.name,
                    defaultCycle = reg.defaultCycle,
                    presets = reg.presets,
                    attributeDefaults = reg.attributeDefaults,
                )
            }
    }

    /**
     * Get all registrations (snapshot).
     */
    val allRegistrations: List<FxRegistration>
        get() = registrations.values.toList()

    /**
     * Number of registered effects.
     */
    val size: Int
        get() = registrations.size

    private fun normalize(name: String): String = normalizeFxType(name)
}

/** An effect type as [FxRegistry] matches one: case, spaces and underscores ignored. */
fun normalizeFxType(name: String): String =
    name.lowercase().replace(" ", "").replace("_", "")
