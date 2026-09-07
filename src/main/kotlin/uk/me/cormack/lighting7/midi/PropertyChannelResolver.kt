package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/** Reads the current DMX value of a channel; null when the universe has no controller. */
typealias ChannelReader = (Universe, Int) -> UByte?

/**
 * What a continuous control **means** on a fixture property: what a 7-bit position writes, and
 * what position the property's current channels read back as. Both directions live here so that
 * a fader or encoder ring can never claim a position a move from it would not reproduce.
 *
 * Two property types are continuous-bindable, and each has one rule:
 *
 *   - [DmxSlider] → the 7-bit value scaled through the slider's own `min..max`, and read back by
 *     the inverse.
 *   - [DmxColour] → **hue**. A turn writes a colour at the new hue keeping the head's current
 *     saturation and value, so an encoder sweeps the colour wheel on whatever the head is already
 *     showing; the ring reads the head's current hue. Saturation is **floored** at
 *     [MIN_SATURATION] on the write and the read treats anything below [HUE_READ_MIN_SATURATION]
 *     as uncoloured, because a hue on a white is invisible in both directions: a turn that kept a
 *     warm white's few percent of saturation would sweep the wheel with nothing changing on stage,
 *     and a ring lit for that head would claim a colour the rig is not showing. So a white or
 *     grey reads as no value and a turn on it lands at the floor; a black head, with nothing to keep,
 *     starts from a fully saturated colour at full value; a pastel above the floor keeps its
 *     pastel. The write asserts the whole colour property, so
 *     W/A/UV go to 0 as any programmer colour write does. It used to be "brightness on a
 *     colour": one 7-bit value fanned to R, G and B, with the ring reading red alone — which
 *     made red and yellow heads report as uniform (`FU-MIDI-SELECTION-COLOUR-RED-ONLY`) and left
 *     an encoder unable to reach a colour at all (`FU-MIDI-ENCODER-HUE`).
 *   - [DmxFixtureSetting] → nothing. Faders on enum / setting properties are disallowed per the
 *     control-surface plan's Open Question 7; bind a button.
 *
 * [flashPropertyValue] is the one colour rule that is *not* hue: a flash is a level, and on a
 * colour it asserts grey at the binding's max.
 *
 * The resolver never touches [uk.me.cormack.lighting7.dmx.ControllerTransaction]; it reads
 * current channels only through the reader a caller hands it, so it is safe from the MIDI input
 * coroutine and from a unit test with no controller at all.
 */
object PropertyChannelResolver {

    /** One channel write consumed by the programmer's channel writer. */
    data class ChannelWrite(
        val universe: Universe,
        val channel: Int,
        /** The DMX value to write, already scaled to this channel's native 0..255 range. */
        val value: UByte,
        /** The property category this channel belongs to — used by the global scaler. */
        val category: PropertyCategory,
    )

    /**
     * How many distinct hues a 7-bit control can name. Hue is `value / HUE_STEPS` of the circle,
     * so 0 is red and 127 is one step short of red again — the two ends of the ring are
     * neighbours on the wheel rather than the same colour twice.
     */
    const val HUE_STEPS = 128

    /**
     * The least saturation a hue write leaves a head at. A head below it is treated as
     * uncoloured — the write lifts it here rather than preserving a tint too faint to see move —
     * and one above it keeps its own. A quarter is a clear tint at any brightness while leaving
     * an authored pastel alone; it is a control choice, not a colour-science constant.
     */
    const val MIN_SATURATION = 0.25f

    /**
     * Below this saturation a head reads as having no hue. Deliberately under [MIN_SATURATION]
     * rather than equal to it: a write at the floor on a dim head rounds into channels that can
     * read back a little under it, and the head it just coloured must not then read as white.
     */
    const val HUE_READ_MIN_SATURATION = 0.1f

    /** A [ChannelReader] over the show's controllers. */
    fun channelReader(fixtures: Fixtures): ChannelReader = { universe, channel ->
        fixtures.controllerOrNull(universe)?.getValue(channel)
    }

    /**
     * Scale a 7-bit MIDI value to a clamped `[min..max]` DMX sub-range. The caller passes the
     * slider's native `min` and `max`; we interpolate linearly between them.
     */
    fun scaleWithinRange(midi: UByte, min: UByte, max: UByte): UByte {
        if (min == max) return min
        val m = midi.toInt().coerceIn(0, 127)
        val span = max.toInt() - min.toInt()
        val dmx = min.toInt() + (m * span + 63) / 127
        return dmx.coerceIn(0, 255).toUByte()
    }

    /** [scaleWithinRangeTo7Bit] over the full range. 0 → 0, 255 → 127. */
    fun scaleDmxTo7Bit(dmx: UByte): UByte {
        val v = dmx.toInt().coerceIn(0, 255)
        return ((v * 127 + 127) / 255).toUByte()
    }

    /**
     * Inverse of [scaleWithinRange]: given a raw DMX value and the slider's `[min..max]`,
     * return the equivalent 7-bit position. Values outside the range clamp to 0 / 127.
     */
    fun scaleWithinRangeTo7Bit(dmx: UByte, min: UByte, max: UByte): UByte {
        if (min == max) return 0u
        val v = dmx.toInt().coerceIn(min.toInt(), max.toInt())
        val span = max.toInt() - min.toInt()
        return (((v - min.toInt()) * 127 + span / 2) / span).coerceIn(0, 127).toUByte()
    }

    // --- Hue ---

    /**
     * The colour a 7-bit hue writes on a head currently showing [current]: the new hue at the
     * head's own value and saturation, the latter floored at [MIN_SATURATION]. A black head, or
     * one whose colour is unknown, has nothing to keep at all and starts from a fully saturated
     * colour at full value — see the class doc.
     */
    fun colourAtHue(hue7Bit: UByte, current: Color?): Color {
        val hue = hue7Bit.toInt().coerceIn(0, HUE_STEPS - 1) / HUE_STEPS.toFloat()
        val hsb = current?.let { Color.RGBtoHSB(it.red, it.green, it.blue, null) }
        if (hsb == null || hsb[2] <= 0f) return Color(Color.HSBtoRGB(hue, 1f, 1f))
        return Color(Color.HSBtoRGB(hue, maxOf(hsb[1], MIN_SATURATION), hsb[2]))
    }

    /**
     * The hue a head's current colour reads back as, or null when it has none: black, and
     * anything under [HUE_READ_MIN_SATURATION] — a grey, a white, a tint too faint to see.
     *
     * A head cannot say its hue more precisely than its channels can hold it — one DMX step of
     * the minor channel moves the hue by a sixth of the **chroma**'s reciprocal, where chroma is
     * the spread between its largest and smallest channel — so the read carries that as its
     * [HeadValue.tolerance], and two heads written at one hue still agree after each has rounded
     * it into its own channels. It is chroma and not value: a pale head at full brightness holds
     * its hue as coarsely as a dim saturated one.
     */
    fun hueRead(red: UByte, green: UByte, blue: UByte): HeadValue? {
        val r = red.toInt(); val g = green.toInt(); val b = blue.toInt()
        val max = maxOf(r, g, b)
        val chroma = max - minOf(r, g, b)
        if (max == 0 || chroma == 0) return null
        if (chroma.toFloat() / max < HUE_READ_MIN_SATURATION) return null
        val hue = Color.RGBtoHSB(r, g, b, null)[0]
        val value7Bit = (hue * HUE_STEPS).roundToInt() % HUE_STEPS
        // Hue resolution at this chroma: one channel step is 1/(6 * chroma) of the circle.
        val steps = (HUE_STEPS.toFloat() / (6 * chroma) + 0.5f).toInt()
        return HeadValue(value7Bit.toUByte(), tolerance = maxOf(1, steps), circular = true)
    }

    /**
     * The one value a set of heads agree on, or null when they do not. Every pair must agree —
     * [HeadValue.agreesWith] is a tolerance test and so not transitive, and anchoring on the
     * first head would make the answer depend on the order the heads were enumerated in. The
     * value reported is the first head's; every other is within tolerance of it. Null for no
     * heads at all.
     */
    fun commonValue(reads: List<HeadValue>): UByte? {
        if (reads.isEmpty()) return null
        for (i in reads.indices) {
            for (j in i + 1 until reads.size) {
                if (!reads[i].agreesWith(reads[j])) return null
            }
        }
        return reads[0].value7Bit
    }

    /**
     * One head's contribution to a control's feedback position, with how far another head's may
     * differ and still be the same setting. A slider is exact; a hue is [circular] and carries the
     * quantisation its value allows.
     */
    data class HeadValue(val value7Bit: UByte, val tolerance: Int = 0, val circular: Boolean = false) {
        fun agreesWith(other: HeadValue): Boolean {
            if (circular != other.circular) return false
            val d = abs(value7Bit.toInt() - other.value7Bit.toInt())
            val distance = if (circular) minOf(d, HUE_STEPS - d) else d
            return distance <= maxOf(tolerance, other.tolerance)
        }
    }

    // --- Writes ---

    /**
     * Convert a MIDI 7-bit value for [propertyName] on [fixture] into a typed
     * [CueAssignmentResolver.PropertyValue] — the form the programmer store and cue assignments
     * both consume.
     *
     * - [DmxSlider] → [CueAssignmentResolver.PropertyValue.Slider], scaled through the slider's own
     *   `min..max` sub-range so a fader at 100% produces the slider's own max rather than
     *   raw DMX 255.
     * - [DmxColour] → [CueAssignmentResolver.PropertyValue.Colour] at the hue the value names,
     *   keeping the head's current saturation and value as [read] reports them ([colourAtHue]).
     * - [DmxFixtureSetting] and unknown types → `null`. Settings are bindable only to buttons.
     */
    fun toPropertyValue(
        fixture: Fixture,
        propertyName: String,
        midiValue7Bit: UByte,
        read: ChannelReader,
    ): CueAssignmentResolver.PropertyValue? = when (val raw = rawProperty(fixture, propertyName)) {
        is DmxSlider -> CueAssignmentResolver.PropertyValue.Slider(
            scaleWithinRange(midiValue7Bit, raw.min, raw.max),
        )
        is DmxColour -> {
            val current = currentColour(raw, read)
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(colourAtHue(midiValue7Bit, current)))
        }
        else -> null
    }

    /**
     * The typed value a flash press should assert for [propertyName] on [fixture] at the
     * binding's 0..255 [max] level.
     *
     * - [DmxSlider] → [CueAssignmentResolver.PropertyValue.Slider] clamped to the slider's own
     *   `max` so a flash never writes past a dimmer's configured cap.
     * - [DmxColour] → grey at [max]. A flash is a level, not a hue — it is the one colour rule
     *   here that does not go through [colourAtHue]. The extended W/A/UV components are 0: a
     *   flash asserts the whole colour property, where the old channel-level press left W/A/UV
     *   untouched — and because the programmer sits above cues, a cue-driven white/amber/UV on
     *   the fixture is forced to 0 for as long as the flash is held (release restores it).
     *   Deliberate: a flash press is "this colour, now", not a partial overlay.
     * - [DmxFixtureSetting] and unknown types → `null`. Flash on a setting is disallowed.
     */
    fun flashPropertyValue(
        fixture: Fixture,
        propertyName: String,
        max: UByte,
    ): CueAssignmentResolver.PropertyValue? = when (val raw = rawProperty(fixture, propertyName)) {
        is DmxSlider -> CueAssignmentResolver.PropertyValue.Slider(minOf(max, raw.max))
        is DmxColour -> {
            val v = max.toInt()
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color(v, v, v)))
        }
        else -> null
    }

    // --- Reads ---

    /**
     * What a continuous control stands on for one head: a slider's one channel, or a colour's
     * three read together as a hue. Built without reading a value, for a reverse index; read
     * with [readHead].
     */
    sealed interface PropertyRead {
        /** Every channel the read depends on — the index keys. */
        val channels: List<PropertyChannel>

        data class Slider(val channel: PropertyChannel) : PropertyRead {
            override val channels: List<PropertyChannel> get() = listOf(channel)
        }

        data class Colour(
            val red: PropertyChannel,
            val green: PropertyChannel,
            val blue: PropertyChannel,
        ) : PropertyRead {
            override val channels: List<PropertyChannel> get() = listOf(red, green, blue)
        }
    }

    /** Structural description of one channel that backs a fixture property. */
    data class PropertyChannel(
        val universe: Universe,
        val channel: Int,
        val min: UByte,
        val max: UByte,
        val category: PropertyCategory,
    )

    /**
     * The [PropertyRead] behind [propertyName] on [fixture], or null when the property is not
     * continuous-bindable (a setting, an unknown name, a type with no continuous rule).
     */
    fun describePropertyRead(fixture: Fixture, propertyName: String): PropertyRead? {
        val property = fixture.fixtureProperty(propertyName) ?: return null
        val raw = try {
            property.classProperty.call(fixture)
        } catch (_: Exception) {
            return null
        } ?: return null
        return when (raw) {
            is DmxSlider -> PropertyRead.Slider(
                PropertyChannel(raw.universe, raw.channelNo, raw.min, raw.max, property.category),
            )
            is DmxColour -> PropertyRead.Colour(
                red = PropertyChannel(raw.universe, raw.redSlider.channelNo, 0u, 255u, PropertyCategory.COLOUR),
                green = PropertyChannel(raw.universe, raw.greenSlider.channelNo, 0u, 255u, PropertyCategory.COLOUR),
                blue = PropertyChannel(raw.universe, raw.blueSlider.channelNo, 0u, 255u, PropertyCategory.COLOUR),
            )
            else -> null
        }
    }

    /**
     * The channels that back [propertyName] on [fixture] — the flat form of
     * [describePropertyRead], empty when the property is not continuous-bindable.
     */
    fun describeFixtureProperty(fixture: Fixture, propertyName: String): List<PropertyChannel> =
        describePropertyRead(fixture, propertyName)?.channels.orEmpty()

    /**
     * Read one head's feedback position through [read]. Null when a channel cannot be read, or
     * when the head has no position to report (a colour with no hue).
     */
    fun readHead(head: PropertyRead, read: ChannelReader): HeadValue? = when (head) {
        is PropertyRead.Slider -> {
            val pc = head.channel
            val dmx = read(pc.universe, pc.channel) ?: return null
            HeadValue(scaleWithinRangeTo7Bit(dmx, pc.min, pc.max))
        }
        is PropertyRead.Colour -> {
            val r = read(head.red.universe, head.red.channel) ?: return null
            val g = read(head.green.universe, head.green.channel) ?: return null
            val b = read(head.blue.universe, head.blue.channel) ?: return null
            hueRead(r, g, b)
        }
    }

    private fun rawProperty(fixture: Fixture, propertyName: String): Any? {
        val property = fixture.fixtureProperty(propertyName) ?: return null
        return try {
            property.classProperty.call(fixture)
        } catch (_: Exception) {
            null
        }
    }

    private fun currentColour(raw: DmxColour, read: ChannelReader): Color? {
        val r = read(raw.universe, raw.redSlider.channelNo) ?: return null
        val g = read(raw.universe, raw.greenSlider.channelNo) ?: return null
        val b = read(raw.universe, raw.blueSlider.channelNo) ?: return null
        return Color(r.toInt(), g.toInt(), b.toInt())
    }
}
