package uk.me.cormack.lighting7.fx

import kotlin.math.roundToInt

/**
 * How a colour intent uses a head's white / amber / UV emitters, on the heads that have them.
 *
 * Stored on the intent rather than baked into its value, which is the whole difference between a
 * template and a recorded row. A recorded colour row carries explicit emitter bytes
 * (`#ff8000;w120;a64`) decided against **one** fixture at authoring time; a template carries the hex
 * plus this policy and lets each head answer for itself.
 *
 * This is the *derived* half of a template's emitter story. The other half is an explicit
 * [PaletteAttribute.WHITE] / [PaletteAttribute.AMBER] / [PaletteAttribute.UV] row carrying a
 * [PaletteIntent.Level], and the two must not both drive one emitter: an explicit white or amber
 * row forces the colour row to [RGB_ONLY], refused by name at the write boundary. UV is exempt,
 * because no policy has ever driven it.
 */
enum class WhitePolicy {
    /**
     * Pull `min(r, g, b)` out of RGB and into the white (and amber, where present) emitters.
     * Brighter and cleaner than RGB alone, and the sensible default for a wash.
     */
    EXTRACT,

    /** Drive the extra emitters *alongside* RGB rather than instead of part of it. */
    ADDITIVE,

    /** Leave the extra emitters at zero — for when a rig is being matched to camera. */
    RGB_ONLY,
    ;

    /** The serialised token, as it appears after `;policy=`. */
    val token: String get() = when (this) {
        EXTRACT -> "extract"
        ADDITIVE -> "additive"
        RGB_ONLY -> "rgbonly"
    }

    companion object {
        fun ofToken(raw: String): WhitePolicy? =
            entries.firstOrNull { it.token == raw.trim().lowercase() }
    }
}

/**
 * What a template row stores: an **intent**, not a literal.
 *
 * "Be amber" rather than "channels 4/5/6 = 255/157/74". [PaletteResolver] is the one thing that
 * turns one into channels, per head, and this file is the one thing that parses and serialises the
 * grammar. Nothing else does either — see the two deliberate degradations below.
 *
 * ## The grammar
 *
 * | Arm | Form | Family |
 * |---|---|---|
 * | [Colour] | `#FF9D4A;policy=extract` | colour |
 * | [Level] | `dmx:180` | colour — the bundled white / amber / UV emitters |
 * | [Percent] | `pct:75` | intensity (dimmer *and* strobe), and the continuous beam roles |
 * | [Position] | `pos:-12,30` | position — centre-relative coarse DMX, `-128..127`, 0 at DMX 128 |
 * | [Position] (stored only) | `deg:45.0,12.5` | position — travel **degrees**, the unit before `pos:` |
 * | [Switch] | `on` / `off` | prism |
 *
 * **[Level] is a literal where everything else here is an intent, and that is deliberate.** The
 * grammar exists so a value can mean the same thing on heads that differ; a percentage answers "how
 * far up its own range", a centre-relative position "how far along its own travel". An emitter has
 * neither — it is one LED of one colour, its slider is 0–255 on every head in this rig, and the
 * operator setting it is
 * matching a colour by eye against a rig that is already lit. There is nothing to re-derive per
 * head, so `pct:` would only have added a rounding trip. The one thing that *is* per head is
 * whether the emitter exists at all, and that is answered by resolution, not by the value.
 *
 * **Strobe is a percentage, not a rate**, and that is a departure from the design worth knowing.
 * `BeamColour.dc.html` promises "strobe in Hz, which is the only unit two fixtures agree on". Since
 * the fixture optics plan's session 6 every STROBE channel declares its bands and each flashing
 * band's `hzMin`/`hzMax` (`@FixtureAttribute(strobe = […])`) — but almost every rate is an estimate
 * (no manual but the Robe's states one), and a `hz:` intent would make the desk solve a DMX value
 * per head from them. It stays a percentage of each head's own strobe channel; the `Hertz` arm is
 * `FU-TMPL-STROBE-HZ`. (Strobe is also in the **intensity**
 * family, not beam — `AttributeCategory.STROBE.family()` is `INTENSITY`, because it is an
 * intensity modulation operators reach for alongside level.)
 *
 * There is no arm for a **slotted** property — gobo, colour wheel, the `*_macro` channels. "Gobo 3"
 * is a different pattern on every model, so a template cannot carry one without lying; those are
 * refused at the write boundary and live in a recorded Look, which names a head and can therefore
 * hold anything that head has.
 *
 * ## What a reader that does not know this grammar does
 *
 * Both failure modes are deliberate and neither should be "fixed" by teaching the literal parsers
 * about intents:
 *
 * - A [Colour] intent's head is plain `#RRGGBB`, and both colour parsers
 *   ([parseExtendedColour] here, `parseExtendedColour` in `components/fx/colourUtils.ts`) ignore an
 *   unrecognised `;`-token. So every existing swatch, preview and cell renderer already draws it —
 *   as the RGB-only reading, which is the safe one.
 * - `pct:` / `pos:` / `deg:` / `dmx:` / `on` all fail `toUByteParam()` in
 *   [CueAssignmentResolver.parseAssignmentValue], which returns null and makes its caller **skip
 *   the row with a warn**. Loudly doing nothing is right; a stray reader must never turn `pct:75`
 *   into a DMX 75. `dmx:180` keeps the prefix for exactly that reason even though its payload
 *   already *is* a DMX byte: a bare `180` would be read by the literal parser, so the two grammars
 *   would silently agree on some rows and disagree on others.
 */
sealed interface PaletteIntent {
    /** The serialised form, round-tripping through [parsePaletteIntent]. */
    fun serialize(): String

    data class Colour(val hex: String, val policy: WhitePolicy = WhitePolicy.EXTRACT) : PaletteIntent {
        override fun serialize(): String = "$hex;policy=${policy.token}"
    }

    /**
     * A proportion of whatever range the target property has, `0.0..100.0`.
     *
     * One arm for intensity *and* the continuous beam roles, because the resolution is identical:
     * scale to the property's own `min..max`. Which family a row is in comes from its property
     * name, not from the shape of its value, so splitting this would be two spellings of one thing.
     */
    data class Percent(val value: Double) : PaletteIntent {
        override fun serialize(): String = "pct:${trimNumber(value)}"
    }

    /**
     * Pan and tilt, in one of two units (venue-visit plan D3, P3).
     *
     * [PositionUnit.CENTRE] — `pos:` — is what every write makes: centre-relative coarse DMX,
     * `-128..127` with 0 at DMX 128, so a generic palette means the same *fraction of travel* on
     * every head, annotated or not. [PositionUnit.DEGREES] — `deg:` — is the unit palettes were
     * stored in before it: travel degrees through each head's own annotated range. It keeps
     * resolving as angles, because there are no migrations; nothing new writes it.
     */
    data class Position(
        val pan: Double,
        val tilt: Double,
        val unit: PositionUnit = PositionUnit.CENTRE,
    ) : PaletteIntent {
        override fun serialize(): String = when (unit) {
            PositionUnit.CENTRE -> "pos:${centreStep(pan)},${centreStep(tilt)}"
            PositionUnit.DEGREES -> "deg:${trimNumber(pan)},${trimNumber(tilt)}"
        }
    }

    /**
     * A DMX byte, written straight at the head's own slider — the bundled colour emitters only.
     *
     * The one literal in this grammar; see the interface doc for why an emitter is the case where
     * re-deriving per head would buy nothing. [value] is `0..255` at construction, and
     * [PaletteResolver] still clamps it into the slider's own `min..max` on the way out, because a
     * bundled emitter *may* declare a narrower range even though none in this rig does.
     */
    data class Level(val value: Int) : PaletteIntent {
        override fun serialize(): String = "dmx:$value"
    }

    /** A two-state beam role — prism in or out. */
    data class Switch(val on: Boolean) : PaletteIntent {
        override fun serialize(): String = if (on) "on" else "off"
    }
}

/**
 * The unit a [PaletteIntent.Position] is in.
 *
 * [CENTRE] is whole coarse DMX steps about the centre of travel — the fine channel is not part of
 * the scale and is left to its own writes (aim, a raw channel). [DEGREES] is the stored-only legacy
 * unit; see [PaletteIntent.Position].
 */
enum class PositionUnit { CENTRE, DEGREES }

/** The centre-relative scale's ends: DMX 0 is `-128`, DMX 255 is `127`, the centre (128) is 0. */
const val POSITION_CENTRE_MIN = -128
const val POSITION_CENTRE_MAX = 127

/** A centre-relative value as the whole coarse step it stands for, clamped to the scale. */
fun centreStep(value: Double): Int =
    value.roundToInt().coerceIn(POSITION_CENTRE_MIN, POSITION_CENTRE_MAX)

private const val PERCENT_PREFIX = "pct:"
private const val CENTRE_PREFIX = "pos:"
private const val DEGREES_PREFIX = "deg:"
private const val LEVEL_PREFIX = "dmx:"
private const val POLICY_TOKEN = "policy="

/**
 * Parse a stored template row value, or null when it is not an intent at all.
 *
 * Null is a real answer rather than an error: it is what a corrupt or hand-edited row produces, and
 * the caller's job is to skip that row with a warn — never to guess, and never to fall through to
 * the literal parser, which would read the row against a grammar it was not written in.
 */
fun parsePaletteIntent(raw: String): PaletteIntent? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("#")) {
        val parts = trimmed.split(";")
        val hex = parts[0].trim()
        // Six or three digits, matching what `parseExtendedColour` accepts. Anything else is not a
        // colour intent, and saying so here is what keeps the "#" prefix an unambiguous marker.
        val digits = hex.removePrefix("#")
        if (digits.length != 6 && digits.length != 3) return null
        if (!digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        // An absent policy reads as RGB_ONLY rather than EXTRACT, matching what every *other*
        // reader of this string already does with it — see the class doc. The editor always writes
        // one, so this arm is for a row that came from somewhere else.
        val policy = parts.drop(1)
            .map { it.trim().lowercase() }
            .firstOrNull { it.startsWith(POLICY_TOKEN) }
            ?.removePrefix(POLICY_TOKEN)
            ?.let { WhitePolicy.ofToken(it) }
            ?: WhitePolicy.RGB_ONLY
        return PaletteIntent.Colour(hex, policy)
    }

    val lower = trimmed.lowercase()
    if (lower == "on") return PaletteIntent.Switch(true)
    if (lower == "off") return PaletteIntent.Switch(false)

    if (lower.startsWith(PERCENT_PREFIX)) {
        val value = lower.removePrefix(PERCENT_PREFIX).trim().toDoubleOrNull() ?: return null
        return PaletteIntent.Percent(value.coerceIn(0.0, 100.0))
    }
    if (lower.startsWith(LEVEL_PREFIX)) {
        val value = lower.removePrefix(LEVEL_PREFIX).trim().toIntOrNull() ?: return null
        return PaletteIntent.Level(value.coerceIn(0, 255))
    }
    if (lower.startsWith(CENTRE_PREFIX)) {
        val (pan, tilt) = parseAxes(lower.removePrefix(CENTRE_PREFIX)) ?: return null
        return PaletteIntent.Position(
            centreStep(pan).toDouble(),
            centreStep(tilt).toDouble(),
            PositionUnit.CENTRE,
        )
    }
    if (lower.startsWith(DEGREES_PREFIX)) {
        val (pan, tilt) = parseAxes(lower.removePrefix(DEGREES_PREFIX)) ?: return null
        return PaletteIntent.Position(pan, tilt, PositionUnit.DEGREES)
    }
    return null
}

/** `a,b` as two finite numbers, or null. */
private fun parseAxes(raw: String): Pair<Double, Double>? {
    val axes = raw.split(",")
    if (axes.size != 2) return null
    val pan = axes[0].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val tilt = axes[1].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return pan to tilt
}

/** `45.0` → `"45"`, `12.5` → `"12.5"`. Keeps a stored intent readable and diff-stable. */
private fun trimNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
    else String.format("%.1f", value)

/**
 * The **closed vocabulary** a template row may name, and the shape of intent each one takes.
 *
 * An allow-list rather than a deny-list, which is what makes "a template cannot carry a gobo" a
 * property of the write boundary rather than a rule someone has to remember. Three things fall out
 * of it, all of them wanted:
 *
 *  - **Slotted roles are refused** — gobo, colour wheel, the `*_macro` channels. "Gobo 3" is a
 *    different pattern on every model, so a type-agnostic template cannot carry one without lying;
 *    they belong in a recorded Look, which names a head and can hold anything that head has.
 *  - **A misspelled property is refused too**, instead of being stored and then silently resolving
 *    to nothing on every head.
 *  - **The family is decided here**, from the property alone, with no fixture to ask — which is what
 *    lets a template's family be *derived* from its rows and still validated at write time.
 *
 * `strobe` sits under [AttributeFamily.INTENSITY], not BEAM, because that is where
 * `AttributeCategory.STROBE.family()` puts it: an intensity modulation, HTP like a dimmer.
 *
 * [WHITE], [AMBER] and [UV] are **colour**, matching `AttributeCategory.WHITE.family()` — they are
 * emitters of the same mixed colour, so a template naming a hex and an amber is still one family and
 * still one named thing. They are rows in their own right rather than fields on [PaletteIntent.Colour]
 * so that each writes only its own channel: that is what lets a UV-only template sit *over* a colour
 * rather than replacing it, since `ColourTarget.composeProgrammerOver` already arbitrates the bundled
 * emitters per component. It is also the only way to say "UV at 200" at all — no [WhitePolicy] has
 * ever driven UV.
 */
enum class PaletteAttribute(
    /** The property name as it is stored on the row and looked up on a head. */
    val attribute: String,
    val family: AttributeFamily,
    /** Operator-facing label, for the editor and the resolves-to panel. */
    val label: String,
) {
    DIMMER("dimmer", AttributeFamily.INTENSITY, "Level"),
    STROBE("strobe", AttributeFamily.INTENSITY, "Strobe"),
    POSITION("position", AttributeFamily.POSITION, "Position"),
    COLOUR("rgbColour", AttributeFamily.COLOUR, "Colour"),
    WHITE("white", AttributeFamily.COLOUR, "White"),
    AMBER("amber", AttributeFamily.COLOUR, "Amber"),
    UV("uv", AttributeFamily.COLOUR, "UV"),
    ZOOM("zoom", AttributeFamily.BEAM, "Zoom"),
    FOCUS("focus", AttributeFamily.BEAM, "Focus"),
    IRIS("iris", AttributeFamily.BEAM, "Iris"),
    FROST("frost", AttributeFamily.BEAM, "Frost"),
    PRISM("prism", AttributeFamily.BEAM, "Prism"),
    ;

    /** Does [intent] have the right shape for this property? */
    fun accepts(intent: PaletteIntent): Boolean = when (this) {
        COLOUR -> intent is PaletteIntent.Colour
        POSITION -> intent is PaletteIntent.Position
        PRISM -> intent is PaletteIntent.Switch
        WHITE, AMBER, UV -> intent is PaletteIntent.Level
        DIMMER, STROBE, ZOOM, FOCUS, IRIS, FROST -> intent is PaletteIntent.Percent
    }

    /** True for the three bundled colour emitters, which share every rule that is not the hex's. */
    val isEmitter: Boolean get() = this == WHITE || this == AMBER || this == UV

    companion object {
        /**
         * The bundled emitters, in the order the editor and the resolves-to panel show them.
         *
         * The property names are the categories, which is safe to rely on: `BundledEmitterNamesTest`
         * pins every `bundledAttribute(WHITE|AMBER|UV)` in the rig to exactly these spellings, and
         * `FxTarget.getSlider` already reaches them by the same names.
         */
        val EMITTERS: List<PaletteAttribute> = listOf(WHITE, AMBER, UV)

        /**
         * The vocabulary entry for a stored property name, or null when a template may not name it.
         *
         * Matched through [canonicalAttributeName] so `colour` / `color` / `rgbColour` all land on
         * [COLOUR] — the same collapse every other property lookup in this codebase applies.
         */
        fun ofOrNull(attribute: String): PaletteAttribute? {
            val canonical = canonicalAttributeName(attribute.trim())
            return entries.firstOrNull { it.attribute.equals(canonical, ignoreCase = true) }
        }

        /** The vocabulary for one family, in declaration order — what the editor offers. */
        fun forFamily(family: AttributeFamily): List<PaletteAttribute> =
            entries.filter { it.family == family }
    }
}
