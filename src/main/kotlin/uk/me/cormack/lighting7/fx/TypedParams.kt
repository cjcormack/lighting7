package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed accessor for effect parameters.
 *
 * Constructed once per effect instance (in the factory), not per tick.
 * Non-colour values are parsed eagerly and cached. Colour values are
 * resolved lazily with colour-source version caching — they only re-resolve
 * when a referenced template is edited.
 *
 * Usage in FX calc scripts:
 * ```kotlin
 * val min = params.ubyte("min")
 * val max = params.ubyte("max")
 * val colour = params.colour("baseColor")   // literal, or a "tmpl:{uuid}" reference
 * ```
 */
class TypedParams(
    private val raw: Map<String, String>,
    private val schema: List<ParameterInfo>,
    /**
     * Resolves a colour string that names something rather than stating it — today a `tmpl:`
     * template reference; see [templateColourSource]. Answers null for a literal, and for a
     * reference it cannot honour, so a caller always has its own parser to fall back to.
     */
    private val resolveColourSource: ((String) -> ExtendedColour?)? = null,
    /** Bumps when a referenced template is edited, invalidating the colour caches below. */
    private val colourSourceVersion: (() -> Long)? = null,
) {
    private val defaults: Map<String, String> by lazy {
        schema.associate { it.name to it.defaultValue }
    }

    // Eagerly-parsed value caches (non-colour params don't change per tick)
    private val ubyteCache = ConcurrentHashMap<String, UByte>()
    private val intCache = ConcurrentHashMap<String, Int>()
    private val doubleCache = ConcurrentHashMap<String, Double>()
    private val floatCache = ConcurrentHashMap<String, Float>()
    private val booleanCache = ConcurrentHashMap<String, Boolean>()
    private val easingCurveCache = ConcurrentHashMap<String, EasingCurve>()

    // Colour caches with colour-source version tracking
    @Volatile private var cachedColourSourceVersion = -1L
    private val colourCache = ConcurrentHashMap<String, ExtendedColour>()
    private val colourListCache = ConcurrentHashMap<String, List<ExtendedColour>>()

    /** Get the raw string value for a parameter, falling back to the schema default. */
    fun raw(name: String): String = raw[name] ?: defaults[name] ?: ""

    /** Parse a UByte parameter (0-255). */
    fun ubyte(name: String): UByte = ubyteCache.getOrPut(name) {
        raw(name).toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
    }

    /** Parse an Int parameter. */
    fun int(name: String): Int = intCache.getOrPut(name) {
        raw(name).toIntOrNull() ?: defaults[name]?.toIntOrNull() ?: 0
    }

    /** Parse a Double parameter. */
    fun double(name: String): Double = doubleCache.getOrPut(name) {
        raw(name).toDoubleOrNull() ?: defaults[name]?.toDoubleOrNull() ?: 0.0
    }

    /** Parse a Float parameter. */
    fun float(name: String): Float = floatCache.getOrPut(name) {
        raw(name).toFloatOrNull() ?: defaults[name]?.toFloatOrNull() ?: 0f
    }

    /** Parse a Boolean parameter. */
    fun boolean(name: String): Boolean = booleanCache.getOrPut(name) {
        raw(name).toBooleanStrictOrNull() ?: defaults[name]?.toBooleanStrictOrNull() ?: false
    }

    /** Get a String parameter (no parsing needed). */
    fun string(name: String): String = raw(name)

    /**
     * Parse a colour parameter, resolving a template reference (`tmpl:{uuid}`) through the colour
     * source. Results are cached and only re-resolved when the source's version changes.
     *
     * A reference the source refuses falls through to [parseExtendedColour], which reads an
     * unrecognised string as white. Loud rather than dark: a black head is indistinguishable from
     * an intentional blackout, and the refusal has already been logged with its reason.
     */
    fun colour(name: String): ExtendedColour {
        invalidateColourCacheIfStale()
        return colourCache.getOrPut(name) {
            val value = raw(name)
            if (value.isBlank()) return ExtendedColour.BLACK
            resolveColourSource?.invoke(value) ?: parseExtendedColour(value)
        }
    }

    /**
     * Parse a comma-separated list of colour strings, resolving template references.
     * Results are cached and only re-resolved when the colour source's version changes.
     *
     * A reference the source refuses is **dropped** rather than replaced, which is the one place
     * this differs from [colour]: a list is allowed to be shorter, and a white entry in the middle
     * of a colour cycle reads as a deliberate flash.
     */
    fun colourList(name: String): List<ExtendedColour> {
        invalidateColourCacheIfStale()
        return colourListCache.getOrPut(name) {
            val value = raw(name)
            if (value.isBlank()) return emptyList()
            value.split(",").mapNotNull { str ->
                val trimmed = str.trim()
                when {
                    trimmed.isEmpty() -> null
                    isTemplateColourRef(trimmed) -> resolveColourSource?.invoke(trimmed)
                    else -> parseExtendedColour(trimmed)
                }
            }
        }
    }

    /** Parse an EasingCurve parameter. */
    fun easingCurve(name: String): EasingCurve = easingCurveCache.getOrPut(name) {
        val value = raw(name)
        try {
            EasingCurve.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            EasingCurve.LINEAR
        }
    }

    private fun invalidateColourCacheIfStale() {
        val currentVersion = colourSourceVersion?.invoke() ?: 0L
        if (currentVersion != cachedColourSourceVersion) {
            colourCache.clear()
            colourListCache.clear()
            cachedColourSourceVersion = currentVersion
        }
    }
}
