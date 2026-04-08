package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed accessor for effect parameters.
 *
 * Constructed once per effect instance (in the factory), not per tick.
 * Non-colour values are parsed eagerly and cached. Colour values are
 * resolved lazily with palette version caching — they only re-resolve
 * when the palette changes.
 *
 * Usage in FX calc scripts:
 * ```kotlin
 * val min = params.ubyte("min")
 * val max = params.ubyte("max")
 * val colour = params.colour("baseColor")
 * ```
 */
class TypedParams(
    private val raw: Map<String, String>,
    private val schema: List<ParameterInfo>,
    private val paletteSupplier: (() -> List<ExtendedColour>)? = null,
    private val paletteVersionSupplier: (() -> Long)? = null,
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

    // Colour caches with palette version tracking
    @Volatile private var cachedPaletteVersion = -1L
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
     * Parse a colour parameter, resolving palette references (P1, P2, ...).
     * Results are cached and only re-resolved when the palette version changes.
     */
    fun colour(name: String): ExtendedColour {
        invalidateColourCacheIfStale()
        return colourCache.getOrPut(name) {
            val value = raw(name)
            if (value.isBlank()) return ExtendedColour.BLACK
            val palette = paletteSupplier?.invoke() ?: emptyList()
            resolveColour(value, palette)
        }
    }

    /**
     * Parse a comma-separated list of colour strings, resolving palette references.
     * Results are cached and only re-resolved when the palette version changes.
     */
    fun colourList(name: String): List<ExtendedColour> {
        invalidateColourCacheIfStale()
        return colourListCache.getOrPut(name) {
            val value = raw(name)
            if (value.isBlank()) return emptyList()
            val palette = paletteSupplier?.invoke() ?: emptyList()
            value.split(",").flatMap { str ->
                val trimmed = str.trim()
                if (isAllPaletteRef(trimmed) && palette.isNotEmpty()) palette
                else listOf(resolveColour(trimmed, palette))
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
        val currentVersion = paletteVersionSupplier?.invoke() ?: 0L
        if (currentVersion != cachedPaletteVersion) {
            colourCache.clear()
            colourListCache.clear()
            cachedPaletteVersion = currentVersion
        }
    }
}
