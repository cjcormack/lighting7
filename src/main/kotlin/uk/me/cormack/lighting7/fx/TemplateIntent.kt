package uk.me.cormack.lighting7.fx

/**
 * How a colour intent uses a head's white / amber / UV emitters, on the heads that have them.
 *
 * Stored on the intent rather than baked into its value, which is the whole difference between a
 * template and a recorded row. A recorded colour row carries explicit emitter bytes
 * (`#ff8000;w120;a64`) decided against **one** fixture at authoring time; a template carries the hex
 * plus this policy and lets each head answer for itself.
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
 * "Be amber" rather than "channels 4/5/6 = 255/157/74". [TemplateResolver] is the one thing that
 * turns one into channels, per head, and this file is the one thing that parses and serialises the
 * grammar. Nothing else does either — see the two deliberate degradations below.
 *
 * ## The grammar
 *
 * | Arm | Form | Family |
 * |---|---|---|
 * | [Colour] | `#FF9D4A;policy=extract` | colour |
 * | [Percent] | `pct:75` | intensity (dimmer *and* strobe), and the continuous beam roles |
 * | [Position] | `deg:45.0,12.5` | position — **degrees**, never DMX |
 * | [Switch] | `on` / `off` | prism |
 *
 * **Strobe is a percentage, not a rate**, and that is a departure from the design worth knowing.
 * `BeamColour.dc.html` promises "strobe in Hz, which is the only unit two fixtures agree on" — but
 * nothing in this codebase's fixture definitions declares a Hz range for a strobe channel the way
 * `@FixtureProperty(degMin=, degMax=)` declares a pan range, so a `hz:` intent would have nothing to
 * resolve against and would be inventing a curve per head. It is a percentage of each head's own
 * strobe channel until a `hzMin`/`hzMax` annotation exists. (Strobe is also in the **intensity**
 * family, not beam — `PropertyCategory.STROBE.maskGroup()` is `INTENSITY`, because it is an
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
 * - `pct:` / `deg:` / `on` all fail `toUByteParam()` in
 *   [CueAssignmentResolver.parseAssignmentValue], which returns null and makes its caller **skip
 *   the row with a warn**. Loudly doing nothing is right; a stray reader must never turn `pct:75`
 *   into a DMX 75.
 */
sealed interface TemplateIntent {
    /** The serialised form, round-tripping through [parseTemplateIntent]. */
    fun serialize(): String

    data class Colour(val hex: String, val policy: WhitePolicy = WhitePolicy.EXTRACT) : TemplateIntent {
        override fun serialize(): String = "$hex;policy=${policy.token}"
    }

    /**
     * A proportion of whatever range the target property has, `0.0..100.0`.
     *
     * One arm for intensity *and* the continuous beam roles, because the resolution is identical:
     * scale to the property's own `min..max`. Which family a row is in comes from its property
     * name, not from the shape of its value, so splitting this would be two spellings of one thing.
     */
    data class Percent(val value: Double) : TemplateIntent {
        override fun serialize(): String = "pct:${trimNumber(value)}"
    }

    /** Pan and tilt in **degrees**, resolved through each head's own annotated range. */
    data class Position(val panDeg: Double, val tiltDeg: Double) : TemplateIntent {
        override fun serialize(): String = "deg:${trimNumber(panDeg)},${trimNumber(tiltDeg)}"
    }

    /** A two-state beam role — prism in or out. */
    data class Switch(val on: Boolean) : TemplateIntent {
        override fun serialize(): String = if (on) "on" else "off"
    }
}

private const val PERCENT_PREFIX = "pct:"
private const val DEGREES_PREFIX = "deg:"
private const val POLICY_TOKEN = "policy="

/**
 * Parse a stored template row value, or null when it is not an intent at all.
 *
 * Null is a real answer rather than an error: it is what a corrupt or hand-edited row produces, and
 * the caller's job is to skip that row with a warn — never to guess, and never to fall through to
 * the literal parser, which would read the row against a grammar it was not written in.
 */
fun parseTemplateIntent(raw: String): TemplateIntent? {
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
        return TemplateIntent.Colour(hex, policy)
    }

    val lower = trimmed.lowercase()
    if (lower == "on") return TemplateIntent.Switch(true)
    if (lower == "off") return TemplateIntent.Switch(false)

    if (lower.startsWith(PERCENT_PREFIX)) {
        val value = lower.removePrefix(PERCENT_PREFIX).trim().toDoubleOrNull() ?: return null
        return TemplateIntent.Percent(value.coerceIn(0.0, 100.0))
    }
    if (lower.startsWith(DEGREES_PREFIX)) {
        val axes = lower.removePrefix(DEGREES_PREFIX).split(",")
        if (axes.size != 2) return null
        val pan = axes[0].trim().toDoubleOrNull() ?: return null
        val tilt = axes[1].trim().toDoubleOrNull() ?: return null
        return TemplateIntent.Position(pan, tilt)
    }
    return null
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
 * `strobe` sits under [PropertyMaskGroup.INTENSITY], not BEAM, because that is where
 * `PropertyCategory.STROBE.maskGroup()` puts it: an intensity modulation, HTP like a dimmer.
 */
enum class TemplateProperty(
    /** The property name as it is stored on the row and looked up on a head. */
    val propertyName: String,
    val family: PropertyMaskGroup,
    /** Operator-facing label, for the editor and the resolves-to panel. */
    val label: String,
) {
    DIMMER("dimmer", PropertyMaskGroup.INTENSITY, "Level"),
    STROBE("strobe", PropertyMaskGroup.INTENSITY, "Strobe"),
    POSITION("position", PropertyMaskGroup.POSITION, "Position"),
    COLOUR("rgbColour", PropertyMaskGroup.COLOUR, "Colour"),
    ZOOM("zoom", PropertyMaskGroup.BEAM, "Zoom"),
    FOCUS("focus", PropertyMaskGroup.BEAM, "Focus"),
    IRIS("iris", PropertyMaskGroup.BEAM, "Iris"),
    FROST("frost", PropertyMaskGroup.BEAM, "Frost"),
    PRISM("prism", PropertyMaskGroup.BEAM, "Prism"),
    ;

    /** Does [intent] have the right shape for this property? */
    fun accepts(intent: TemplateIntent): Boolean = when (this) {
        COLOUR -> intent is TemplateIntent.Colour
        POSITION -> intent is TemplateIntent.Position
        PRISM -> intent is TemplateIntent.Switch
        DIMMER, STROBE, ZOOM, FOCUS, IRIS, FROST -> intent is TemplateIntent.Percent
    }

    companion object {
        /**
         * The vocabulary entry for a stored property name, or null when a template may not name it.
         *
         * Matched through [canonicalPropertyName] so `colour` / `color` / `rgbColour` all land on
         * [COLOUR] — the same collapse every other property lookup in this codebase applies.
         */
        fun ofOrNull(propertyName: String): TemplateProperty? {
            val canonical = canonicalPropertyName(propertyName.trim())
            return entries.firstOrNull { it.propertyName.equals(canonical, ignoreCase = true) }
        }

        /** The vocabulary for one family, in declaration order — what the editor offers. */
        fun forFamily(family: PropertyMaskGroup): List<TemplateProperty> =
            entries.filter { it.family == family }
    }
}
