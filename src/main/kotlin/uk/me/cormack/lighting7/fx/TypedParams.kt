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
 * Where `EffectParamUtils.kt` already owns a parser, this class caches it rather than restating
 * it: [ubyte] and [easingCurve] call [toUByteParam] / [toEasingCurveParam], which an FX calc
 * script can equally apply to `params.string(…)` itself. They used to reimplement those bodies
 * inline — identically, but with nothing keeping them so.
 *
 * [int], [double], [float] and [boolean] have no such extension and parse inline, and they differ
 * from [ubyte] in one way worth knowing: a present-but-unparsable value falls back to the schema
 * default, where [ubyte] yields `0u`. So `{"speed":"abc"}` reads as the declared speed while
 * `{"min":"abc"}` reads as zero.
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

    /**
     * The colour caches and the source version they resolve against, published as ONE
     * volatile reference. As separate fields (clear one cache, clear the other, stamp the
     * version — three independent mutations), a reader racing the invalidation could observe
     * the new version already stamped over entries resolved against the old source, and
     * serve them until the next template edit.
     */
    private class ColourCaches(val sourceVersion: Long) {
        val colours = ConcurrentHashMap<String, ExtendedColour>()
        val colourLists = ConcurrentHashMap<String, List<ExtendedColour>>()
    }

    @Volatile private var colourCaches = ColourCaches(sourceVersion = -1L)

    /**
     * Get the raw string value for a parameter, falling back to the schema default.
     *
     * A **blank** stored value counts as absent, not as an override. That is not tidiness: while
     * `FxFileLoader` was dropping every built-in's declared default (see its `parseSimpleYaml`),
     * `GET /fx/library` served `defaultValue: ""`, the Add FX sheet seeded its form from that, and
     * the resulting `{"min":"","max":""}` was persisted into `cue_effects` / `look_effects` /
     * `cue_ad_hoc_effects` verbatim. Those rows exist on the desk today. Preferring a present-but-
     * empty value over the schema default would leave every one of them dead — a `Breathe` parked
     * at 0, a `Circle` with no radius — while an identical newly-added effect worked, with nothing
     * in the UI to tell them apart. No effect parameter has a meaningful empty value, so treating
     * blank as "not set" heals the stored rows and costs nothing.
     */
    fun raw(name: String): String =
        raw[name]?.takeIf { it.isNotBlank() } ?: defaults[name] ?: ""

    /** Parse a UByte parameter (0-255). */
    fun ubyte(name: String): UByte = ubyteCache.getOrPut(name) {
        raw(name).toUByteParam() ?: 0u
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
        return currentColourCaches().colours.getOrPut(name) {
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
        return currentColourCaches().colourLists.getOrPut(name) {
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
        raw(name).toEasingCurveParam() ?: EasingCurve.LINEAR
    }

    /**
     * The caches for the source's current version, replacing them wholesale when the version
     * moved. Racing callers may each build a fresh [ColourCaches]; every published pair is
     * self-consistent, and one that lost the race carries the same version, so at worst a
     * resolve is repeated — never served from the wrong version.
     */
    private fun currentColourCaches(): ColourCaches {
        val currentVersion = colourSourceVersion?.invoke() ?: 0L
        var caches = colourCaches
        if (caches.sourceVersion != currentVersion) {
            caches = ColourCaches(currentVersion)
            colourCaches = caches
        }
        return caches
    }
}
