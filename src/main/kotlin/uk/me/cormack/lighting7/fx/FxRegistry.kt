package uk.me.cormack.lighting7.fx

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Source of an effect registration.
 */
enum class EffectSource {
    /** Built-in effect shipped with the application (from .fx.kts resource files) */
    BUILT_IN,
    /** User-created effect (from fx_definitions database table) */
    USER,
}

/**
 * Factory function that creates an [Effect] from string parameters.
 *
 * @param params Effect-specific parameters as string key-value pairs
 * @param paletteSupplier Optional supplier for current palette colours (for palette-aware effects)
 * @param paletteVersionSupplier Optional supplier for palette version counter (for caching)
 */
typealias EffectFactory = (
    params: Map<String, String>,
    paletteSupplier: (() -> List<ExtendedColour>)?,
    paletteVersionSupplier: (() -> Long)?,
) -> Effect

/**
 * Metadata for a registered effect type.
 *
 * Contains everything needed to list the effect in the library API,
 * create instances from parameters, and display in the UI.
 */
data class EffectRegistration(
    /** Canonical identifier (e.g., "SineWave") */
    val id: String,
    /** Alternative names for lookup (normalized lowercase, no spaces) */
    val aliases: Set<String> = emptySet(),
    /** Human-readable display name */
    val name: String,
    /** UI category (e.g., "dimmer", "colour", "position", "controls") */
    val category: String,
    /** Output type this effect produces */
    val outputType: FxOutputType,
    /** How this effect is evaluated (STANDARD, STATEFUL, COMPOSITE) */
    val effectMode: EffectMode = EffectMode.STANDARD,
    /** Parameter schema for UI and API */
    val parameters: List<ParameterInfo> = emptyList(),
    /** Which fixture properties this effect can target */
    val compatibleProperties: List<String>,
    /** Whether this is built-in or user-created */
    val source: EffectSource = EffectSource.BUILT_IN,
    /** Database ID of the fx_definition (for USER source) */
    val sourceDefinitionId: Int? = null,
    /** The calculate script body (Kotlin source) */
    val script: String? = null,
    /** Default step-timing mode for new instances */
    val defaultStepTiming: Boolean = false,
    /** Factory to create an Effect instance from string parameters */
    val factory: EffectFactory,
)

/**
 * DTO describing an available effect type for the API.
 */
@Serializable
data class EffectTypeInfo(
    val name: String,
    val category: String,
    val outputType: String,
    val effectMode: String = "STANDARD",
    val parameters: List<ParameterInfo>,
    val compatibleProperties: List<String>,
    val source: String? = null,
    val sourceDefinitionId: Int? = null,
    val script: String? = null,
)

/**
 * DTO describing a single effect parameter.
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String,
    val description: String = "",
)

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
    private val registrations = ConcurrentHashMap<String, EffectRegistration>()
    private val aliasIndex = ConcurrentHashMap<String, String>()

    /**
     * Register an effect type.
     *
     * The canonical [EffectRegistration.id] and all [EffectRegistration.aliases]
     * are indexed for lookup. Aliases are normalized (lowercased, spaces removed).
     */
    fun register(registration: EffectRegistration) {
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
    fun createEffect(
        effectType: String,
        params: Map<String, String> = emptyMap(),
        paletteSupplier: (() -> List<ExtendedColour>)? = null,
        paletteVersionSupplier: (() -> Long)? = null,
    ): Effect {
        val registration = getRegistration(effectType)
            ?: throw IllegalArgumentException("Unknown effect type: $effectType")
        return registration.factory(params, paletteSupplier, paletteVersionSupplier)
    }

    /**
     * Look up a registration by canonical id or alias.
     */
    fun getRegistration(effectType: String): EffectRegistration? {
        val canonicalId = aliasIndex[normalize(effectType)] ?: return null
        return registrations[canonicalId]
    }

    /**
     * Get the effect library as DTOs for the API.
     */
    fun getLibrary(): List<EffectTypeInfo> {
        return registrations.values
            .sortedBy { it.id }
            .map { reg ->
                EffectTypeInfo(
                    name = reg.id,
                    category = reg.category,
                    outputType = reg.outputType.name,
                    effectMode = reg.effectMode.name,
                    parameters = reg.parameters,
                    compatibleProperties = reg.compatibleProperties,
                    source = reg.source.name,
                    sourceDefinitionId = reg.sourceDefinitionId,
                    script = reg.script,
                )
            }
    }

    /**
     * Get all registrations (snapshot).
     */
    val allRegistrations: List<EffectRegistration>
        get() = registrations.values.toList()

    /**
     * Number of registered effects.
     */
    val size: Int
        get() = registrations.size

    private fun normalize(name: String): String =
        name.lowercase().replace(" ", "").replace("_", "")
}
