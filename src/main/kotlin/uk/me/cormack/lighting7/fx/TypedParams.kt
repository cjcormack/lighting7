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
 * Where `FxParamUtils.kt` already owns a parser, this class caches it rather than restating
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
 * val colour = params.colour("baseColor")   // literal, or a "pal:{uuid}" reference
 * ```
 */
class TypedParams(
    private val raw: Map<String, String>,
    private val schema: List<ParameterInfo>,
    /**
     * Resolves a colour string that names something rather than stating it — today a `pal:`
     * template reference; see [paletteColourSource]. Answers null for a literal, and for a
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
    private val choiceCache = ConcurrentHashMap<String, String>()
    private val slotStepsCache = ConcurrentHashMap<String, StepList>()

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
        val paletteSteps = ConcurrentHashMap<String, StepList>()
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
     * empty value over the schema default would leave every one of them dead — a `SineWave` parked
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
     * A `choice` parameter: one of its declared options, in the option's spelling. A value no option
     * matches — which every write boundary refuses ([choiceProblem]), so only an older row or a bare
     * `createFx` holds one — reads as the declared default, else the first option.
     */
    fun choice(name: String): String = choiceCache.getOrPut(name) {
        val param = schema.firstOrNull { it.name == name }
        val first = param?.options?.firstOrNull()
        if (param == null || first == null) {
            raw(name)
        } else {
            choiceOption(param, raw(name)) ?: choiceOption(param, param.defaultValue) ?: first.value
        }
    }

    /**
     * Parse a colour parameter, resolving a template reference (`pal:{uuid}`) through the colour
     * source. Results are cached and only re-resolved when the source's version changes.
     *
     * A reference the source refuses falls through to [parseExtendedColour], which reads an
     * unrecognised string as white. Loud rather than dark: a black head is indistinguishable from
     * an intentional blackout, and the refusal has already been logged with its reason.
     */
    fun colour(name: String): ExtendedColour {
        return currentColourCaches().colours.getOrPut(name) {
            val value = raw(name)
            // The blank case yields BLACK rather than *returning* it: a non-local return out of
            // `getOrPut` skips the `put`, so an effect whose colour parameter is blank re-ran this
            // lambda on every read for the life of the instance (sweep item C8).
            if (value.isBlank()) {
                ExtendedColour.BLACK
            } else {
                resolveColourSource?.invoke(value) ?: parseExtendedColour(value)
            }
        }
    }

    /**
     * Parse a comma-separated list of literal colours (terminology plan D14). A palette reference
     * names no colour here — Palette Steps is the FX that steps through palettes — so one is
     * **dropped** like any other entry that is no colour, as [colourListPaletteProblem] refuses it at
     * every write boundary. Cached as [colour] is.
     */
    fun colourList(name: String): List<ExtendedColour> {
        return currentColourCaches().colourLists.getOrPut(name) {
            val value = raw(name)
            // Yielded, not returned — see [colour] for why the non-local return defeated the cache.
            if (value.isBlank()) {
                emptyList()
            } else {
                value.split(",").mapNotNull { str ->
                    val trimmed = str.trim()
                    when {
                        trimmed.isEmpty() || isPaletteColourRef(trimmed) -> null
                        else -> parseExtendedColour(trimmed)
                    }
                }
            }
        }
    }

    /**
     * Palette Steps' list (effects plan §3.6): a comma-separated list of `pal:{uuid}` references to
     * generic value palettes, each read off the palette as its rows' intents and resolved per head by
     * the target. Cached against the colour source's version, as a colour list is, so retuning a
     * palette moves the running steps without rebuilding the effect.
     *
     * The palettes are read through the colour source when it is a [PaletteSource] — the one every
     * spawn site passes (`createFxWithPalettes`). Without one (a bare `createFx`, a test)
     * there is nothing to read them from, and the list is empty. An entry that cannot be a step is
     * **dropped**, as a colour list drops a reference it cannot honour.
     */
    fun paletteSteps(name: String): StepList {
        return currentColourCaches().paletteSteps.getOrPut(name) {
            val source = resolveColourSource as? PaletteSource
            val value = raw(name)
            if (source == null || value.isBlank()) {
                StepList.EMPTY
            } else {
                PaletteStepList(source.paletteStepsOf(value))
            }
        }
    }

    /**
     * Slot Steps' list (effects plan §3.6): a comma-separated list of a setting's option names
     * (`GOBO_3`, or as the editor shows it, `Gobo 3`), looked up per head in its own options.
     */
    fun slotSteps(name: String): StepList = slotStepsCache.getOrPut(name) {
        val names = raw(name).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) StepList.EMPTY else SlotStepList(names)
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
