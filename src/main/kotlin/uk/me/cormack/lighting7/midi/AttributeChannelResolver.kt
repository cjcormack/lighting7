package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.DmxWhiteMixColour
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.AttributeChannelWriter
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.ColourTarget
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxTarget
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fx.PositionTarget
import uk.me.cormack.lighting7.fx.SliderTarget
import java.awt.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What a continuous control **means** on a fixture property: what a 7-bit position writes, and
 * what position the property's composed value reads back as. Both directions live here so that
 * a fader or encoder ring can never claim a position a move from it would not reproduce.
 *
 * Two property types are continuous-bindable, and each has one rule:
 *
 *   - [DmxSlider] → the 7-bit value scaled through the slider's own `min..max`, and read back by
 *     the inverse. A slider has no [ColourAxis]; a write or read that names one answers null, so
 *     a head on which a "colour" property turns out to be a slider drops the move and stays out
 *     of the feedback fold rather than darkening a ring for every other head.
 *   - [DmxColour] → one of four **HSV** axes, [ColourAxis.HUE] unless the binding says otherwise.
 *     Every axis writes a whole colour built from the head's *current* one, moving only its own
 *     component, and reads back that component from the head's composed value:
 *       - **Hue** — the wheel in [HUE_STEPS] steps, keeping the head's saturation and value; the
 *         ring reads the head's hue. Saturation is **floored** at [MIN_SATURATION] on the write
 *         and the read treats anything below [HUE_READ_MIN_SATURATION] as uncoloured, because a
 *         hue on a white is invisible in both directions: a turn that kept a warm white's few
 *         percent of saturation would sweep the wheel with nothing changing on stage, and a ring
 *         lit for that head would claim a colour the rig is not showing. So a white or grey reads
 *         as no value and a turn on it lands at the floor; a black head, with nothing to keep,
 *         starts from a fully saturated colour at full value; a pastel above the floor keeps its
 *         pastel.
 *       - **Hue fine** — a centred trim of ±½ coarse step: `(v − 64) / 128` of a step, added to
 *         the head's hue *rounded to its coarse step*, so 64 leaves the head exactly on that step
 *         and the coarse read (which also rounds) is unmoved by any fine position. Same floor and
 *         fallbacks as hue; the read is the offset within the step, and it is **circular** —
 *         0 and 127 are neighbours, not half a step apart. That is not a nicety: the read takes
 *         the offset from the *nearest* coarse step, and at the trim's ends channel rounding
 *         decides which step that is, so a head written at 0 reads back as 0 or as 127 depending
 *         on where 8-bit channels landed it (at full chroma that is a coin flip on about half
 *         the 128 steps). Read linearly, those two answers are the whole travel apart: a group
 *         all written at 0 would fail [commonValue] and darken its ring. Circular, they are one
 *         position apart, which is what they physically are — so the group agrees. What circularity
 *         cannot fix is the *value* a single head reports: feedback sends whichever of the two it
 *         read, so a motor fader let go at the very bottom can be driven to the top on touch-off
 *         (the same colour, the other spelling). That is the trim's one documented flip, and it is
 *         confined to its extremes. A step written into 8-bit channels lands a hair off it, so
 *         the rest reads as the centre *within the head's tolerance* (11 at full chroma), which
 *         is what a motor fader is sent. Round the same way in both directions, or the bottom of
 *         the trim flips the coarse step under it. The coarse ring can also flicker by one at the
 *         trim's extremes, for the same reason and absorbed the same way — [hueRead] is circular
 *         too.
 *       - **Saturation** — `v / 127` at the head's hue and value; a black or unreadable head has
 *         neither and starts from red at full value. Reads null on a black head (no saturation
 *         to report). Pulling it to 0 leaves a white, which the hue and fine reads then report as
 *         *no hue* — their rings darken and takeover disarms — and the next hue turn lifts it back
 *         to the floor. Coherent, and worth knowing before it is seen on a desk.
 *       - **Brightness** — HSV value, `v / 127`, at the head's hue and saturation; a black or
 *         unreadable head has neither and comes up **grey** (a white level, the flash's rule).
 *         Reads the largest channel, 0 for black rather than null. Note that brightness 0 *is*
 *         black: the programmer stores RGB, so the hue and saturation are gone with it and the
 *         next brightness turn comes up grey. A dimmer does not behave like this; on a head that
 *         has one, bind that instead.
 *     The write asserts the whole colour property and **carries the head's current white, amber
 *     and UV** ([currentExtended]) rather than zeroing them — those emitters have faders of their
 *     own now, and a hue turn that zeroed the white the operator just set beside it would be a
 *     control fighting its neighbour. A colour write from anywhere else in the programmer still
 *     states W/A/UV explicitly. Hue used to be "brightness on a colour": one 7-bit value fanned to
 *     R, G and B, with the ring reading red alone — which made red and yellow heads report as
 *     uniform (`FU-MIDI-SELECTION-COLOUR-RED-ONLY`) and left an encoder unable to reach a colour at
 *     all (`FU-MIDI-ENCODER-HUE`).
 *   - [DmxWhiteMixColour] → the same four axes, over the colour its two whites make
 *     (its `mix`); the writer lands each move on the nearest warm/cold mix.
 *   - [DmxFixtureSetting] → nothing. Faders on enum / setting properties are disallowed per the
 *     control-surface plan's Open Question 7; bind a button.
 *
 * A head is a fixture or one of its elements; every rule reads the attribute through
 * [AttributeChannelWriter.resolveAttribute], the one lookup for both. [surfaceWrite] is what a
 * surface writes: a head's pan or tilt moves its `position`, keeping the other axis, as
 * `programmer.setPosition` writes it (surface-setup plan D5), so a nudge holds back a position FX.
 *
 * [flashAttributeValue] is the one colour rule that is *not* an axis: a flash is a level, and on a
 * colour it asserts grey at the binding's percentage with W/A/UV 0.
 *
 * Both directions take the head's **composed value** for the key ([composedTarget]) rather than its
 * channels: what `LayerResolver.localOver` answers, Local over the cue layer without FX (surface-setup
 * plan D6). So a fader reads what the programmer holds — the staged value in Blind — and a hue turn
 * keeps the white, amber and UV Local holds rather than the stage's. The resolver reads no channel and
 * no controller, so it is safe from any thread and from a unit test with no show at all.
 */
object AttributeChannelResolver {

    /** One channel write consumed by the programmer's channel writer. */
    data class ChannelWrite(
        val universe: Universe,
        val channel: Int,
        /** The DMX value to write, already scaled to this channel's native 0..255 range. */
        val value: UByte,
        /** The property category this channel belongs to — used by the global scaler. */
        val category: AttributeCategory,
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

    /** The [ColourAxis.HUE_FINE] position that leaves a head on its coarse step: the trim's rest. */
    const val HUE_FINE_CENTRE = 64

    /** How many fine positions one coarse hue step spans — the trim's full travel. */
    const val HUE_FINE_STEPS = 128

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
     * The colour a 7-bit fine-hue trim writes on a head showing [current]: the head's hue rounded
     * to its coarse step, moved by `(v − 64) / 128` of a step, at the same saturation floor and
     * value as [colourAtHue]. A black or unknown head starts from red, so the trim lands on
     * red ± half a step at full saturation and value. `HSBtoRGB` wraps a hue below zero itself.
     */
    fun colourAtHueFine(fine7Bit: UByte, current: Color?): Color {
        val offset = (fine7Bit.toInt().coerceIn(0, 127) - HUE_FINE_CENTRE) / HUE_FINE_STEPS.toFloat()
        val hsb = current?.let { Color.RGBtoHSB(it.red, it.green, it.blue, null) }
        if (hsb == null || hsb[2] <= 0f) return Color(Color.HSBtoRGB(offset / HUE_STEPS, 1f, 1f))
        val step = (hsb[0] * HUE_STEPS).roundToInt()
        val hue = (step + offset) / HUE_STEPS
        return Color(Color.HSBtoRGB(hue, maxOf(hsb[1], MIN_SATURATION), hsb[2]))
    }

    /**
     * The colour a 7-bit saturation writes on a head showing [current]: `v / 127` at the head's
     * own hue and value. A black or unknown head has neither to keep and starts from red at full
     * value. No floor here — 0 is the point, a white at the head's level.
     */
    fun colourAtSaturation(sat7Bit: UByte, current: Color?): Color {
        val saturation = sat7Bit.toInt().coerceIn(0, 127) / 127f
        val hsb = current?.let { Color.RGBtoHSB(it.red, it.green, it.blue, null) }
        if (hsb == null || hsb[2] <= 0f) return Color(Color.HSBtoRGB(0f, saturation, 1f))
        return Color(Color.HSBtoRGB(hsb[0], saturation, hsb[2]))
    }

    /**
     * The colour a 7-bit brightness writes on a head showing [current]: HSV value `v / 127` at the
     * head's own hue and saturation. A black or unknown head has neither and comes up grey — a
     * white level, which is what a brightness fader on an empty head can honestly be.
     */
    fun colourAtBrightness(value7Bit: UByte, current: Color?): Color {
        val value = value7Bit.toInt().coerceIn(0, 127) / 127f
        val hsb = current?.let { Color.RGBtoHSB(it.red, it.green, it.blue, null) }
        if (hsb == null || hsb[2] <= 0f) return Color(Color.HSBtoRGB(0f, 0f, value))
        return Color(Color.HSBtoRGB(hsb[0], hsb[1], value))
    }

    /** The colour a 7-bit value writes on [axis], on a head showing [current]. */
    fun colourAtAxis(axis: ColourAxis, value7Bit: UByte, current: Color?): Color = when (axis) {
        ColourAxis.HUE -> colourAtHue(value7Bit, current)
        ColourAxis.HUE_FINE -> colourAtHueFine(value7Bit, current)
        ColourAxis.SATURATION -> colourAtSaturation(value7Bit, current)
        ColourAxis.BRIGHTNESS -> colourAtBrightness(value7Bit, current)
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
     * The fine-trim position a head's current colour reads back as: where its hue sits within its
     * coarse step, 64 being exactly on it. Null under the same no-hue rule as [hueRead]. The
     * tolerance is [hueRead]'s in fine units — one channel step of the minor channel is
     * `HUE_FINE_STEPS / (6 · chroma)` fine positions — and can exceed the whole travel on a pale
     * head, where every fine position is the same colour and every head agrees.
     *
     * **Circular**, over the same 128-position modulus [hueRead] uses. The offset is taken from
     * the *nearest* coarse step, and at the ends of the trim channel rounding decides which step
     * that is — so a head written at 0 reads back as 0 or as 127 for the same colour. They are
     * one position apart, not the whole travel; comparing them linearly is what would darken a
     * ring for a group that agrees and throw a motor fader to the far end.
     */
    fun hueFineRead(red: UByte, green: UByte, blue: UByte): HeadValue? {
        val r = red.toInt(); val g = green.toInt(); val b = blue.toInt()
        val max = maxOf(r, g, b)
        val chroma = max - minOf(r, g, b)
        if (max == 0 || chroma == 0) return null
        if (chroma.toFloat() / max < HUE_READ_MIN_SATURATION) return null
        val steps = Color.RGBtoHSB(r, g, b, null)[0] * HUE_STEPS
        val fraction = steps - steps.roundToInt()
        val fine = (HUE_FINE_CENTRE + (fraction * HUE_FINE_STEPS).roundToInt()).coerceIn(0, 127)
        val tolerance = ceil(HUE_STEPS * HUE_FINE_STEPS / (6f * chroma)).toInt()
        return HeadValue(fine.toUByte(), tolerance = maxOf(1, tolerance), circular = true)
    }

    /**
     * The saturation a head's current colour reads back as — chroma over its largest channel, in
     * 7 bits — or null for a black head, which has none. One step of the minor channel moves it by
     * `1 / max`, which is the tolerance.
     */
    fun saturationRead(red: UByte, green: UByte, blue: UByte): HeadValue? {
        val r = red.toInt(); val g = green.toInt(); val b = blue.toInt()
        val max = maxOf(r, g, b)
        if (max == 0) return null
        val chroma = max - minOf(r, g, b)
        val value7Bit = (chroma.toFloat() / max * 127).roundToInt().coerceIn(0, 127)
        val tolerance = ceil(127f / max).toInt()
        return HeadValue(value7Bit.toUByte(), tolerance = maxOf(1, tolerance), circular = false)
    }

    /** The brightness a head's current colour reads back as: its largest channel, in 7 bits. Never null. */
    fun brightnessRead(red: UByte, green: UByte, blue: UByte): HeadValue {
        val max = maxOf(red, green, blue)
        return HeadValue(scaleDmxTo7Bit(max), tolerance = 1, circular = false)
    }

    /** The position a head's current colour reads back as on [axis], or null when it has none there. */
    fun colourRead(axis: ColourAxis, red: UByte, green: UByte, blue: UByte): HeadValue? = when (axis) {
        ColourAxis.HUE -> hueRead(red, green, blue)
        ColourAxis.HUE_FINE -> hueFineRead(red, green, blue)
        ColourAxis.SATURATION -> saturationRead(red, green, blue)
        ColourAxis.BRIGHTNESS -> brightnessRead(red, green, blue)
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

    /** What a surface writes on one head: the programmer key and its value. */
    data class SurfaceWrite(val attribute: String, val value: CueAssignmentResolver.AttributeValue)

    /**
     * What a 7-bit move on [attribute] writes on [head]: [toAttributeValue] under [attribute], except
     * that a head's pan or tilt writes `position`, the other axis kept from [current] — the head's
     * composed `position` ([composedTarget]). Null when the head cannot take the move.
     */
    fun surfaceWrite(
        head: GroupableFixture,
        attribute: String,
        midiValue7Bit: UByte,
        current: FxOutput?,
        axis: ColourAxis? = null,
    ): SurfaceWrite? {
        val raw = rawAttribute(head, attribute)
        if (raw is DmxSlider && axis == null) {
            positionAxisOf(head, raw)?.let { moving ->
                val pan = (head as WithPosition).pan as DmxSlider
                val tilt = head.tilt as DmxSlider
                val moved = scaleWithinRange(midiValue7Bit, raw.min, raw.max)
                val held = current as? FxOutput.Position
                val panValue = if (moving == PositionAxis.PAN) moved else held?.pan ?: pan.min
                val tiltValue = if (moving == PositionAxis.TILT) moved else held?.tilt ?: tilt.min
                return SurfaceWrite(POSITION, CueAssignmentResolver.AttributeValue.Position(panValue, tiltValue))
            }
        }
        return toAttributeValue(head, attribute, midiValue7Bit, current, axis)?.let { SurfaceWrite(attribute, it) }
    }

    /** Which axis of [head]'s position [slider] is, or null when it is neither its pan nor its tilt channel. */
    private fun positionAxisOf(head: GroupableFixture, slider: DmxSlider): PositionAxis? {
        val pan = (head as? WithPosition)?.pan as? DmxSlider ?: return null
        val tilt = head.tilt as? DmxSlider ?: return null
        return when {
            slider.sameChannel(pan) -> PositionAxis.PAN
            slider.sameChannel(tilt) -> PositionAxis.TILT
            else -> null
        }
    }

    /** One axis of a head's `position`. */
    enum class PositionAxis { PAN, TILT }

    /**
     * The target [attribute] on [head] is composed, read and written under: `position` for its pan or
     * tilt (D5), else its own key as a slider or a colour. Null when the attribute is not
     * continuous-bindable.
     */
    fun composedTarget(head: GroupableFixture, attribute: String): FxTarget? {
        val ref = FxTargetRef.fixture(head.targetKey)
        return when (val raw = rawAttribute(head, attribute)) {
            is DmxSlider -> if (positionAxisOf(head, raw) != null) PositionTarget(ref, POSITION) else SliderTarget(ref, attribute)
            is DmxColour, is DmxWhiteMixColour -> ColourTarget(ref, attribute)
            else -> null
        }
    }

    private fun DmxSlider.sameChannel(other: DmxSlider) = universe == other.universe && channelNo == other.channelNo

    /** The programmer key a head's pan and tilt are written under. */
    const val POSITION = "position"

    /**
     * Convert a MIDI 7-bit value for [attribute] on [head] into a typed
     * [CueAssignmentResolver.AttributeValue] — the form the programmer store and cue assignments
     * both consume.
     *
     * - [DmxSlider] → [CueAssignmentResolver.AttributeValue.Slider], scaled through the slider's own
     *   `min..max` sub-range so a fader at 100% produces the slider's own max rather than
     *   raw DMX 255.
     * - [DmxColour] and [DmxWhiteMixColour] → [CueAssignmentResolver.AttributeValue.Colour] with the
     *   [axis] the value names moved and the rest of [current] kept ([colourAtAxis]); an RGB head's
     *   white, amber and UV ride along from [current] ([currentExtended]).
     * - [DmxFixtureSetting] and unknown types → `null`. Settings are bindable only to buttons.
     *
     * [current] is the head's composed value for the key. [axis] null is hue. A non-null axis on a
     * slider answers null: the move is dropped on that head, which is how a selection or group mixing
     * colour and slider heads under one name reaches the heads it can.
     */
    fun toAttributeValue(
        head: GroupableFixture,
        attribute: String,
        midiValue7Bit: UByte,
        current: FxOutput?,
        axis: ColourAxis? = null,
    ): CueAssignmentResolver.AttributeValue? = when (val raw = rawAttribute(head, attribute)) {
        is DmxSlider -> if (axis != null) null else CueAssignmentResolver.AttributeValue.Slider(
            scaleWithinRange(midiValue7Bit, raw.min, raw.max),
        )
        is DmxColour -> {
            val held = (current as? FxOutput.Colour)?.color
            val colour = colourAtAxis(axis.effective, midiValue7Bit, held?.color)
            CueAssignmentResolver.AttributeValue.Colour(currentExtended(head, colour, held))
        }
        is DmxWhiteMixColour -> {
            val held = (current as? FxOutput.Colour)?.color?.color
            CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(colourAtAxis(axis.effective, midiValue7Bit, held)))
        }
        else -> null
    }

    /**
     * The typed value a flash press asserts for [attribute] on [head] at [percent] of the head's own
     * range, as a fader scales (surface-setup plan D10).
     *
     * - [DmxSlider] → [CueAssignmentResolver.AttributeValue.Slider] at [percent] of the slider's own
     *   `min..max`, so a flash at 100 % lands on a capped dimmer's cap rather than past it.
     * - [DmxColour] and [DmxWhiteMixColour] → grey at [percent]. A flash is a level, not a hue — it
     *   is the one colour rule here that does not go through [colourAtHue]. The extended W/A/UV
     *   components are 0: a flash asserts the whole colour property, and because the programmer
     *   sits above cues, a cue-driven white/amber/UV on the fixture is forced to 0 for as long as
     *   the flash is held (release restores it). Deliberate: a flash press is "this colour, now",
     *   not a partial overlay.
     * - [DmxFixtureSetting] and unknown types → `null`. Flash on a setting is disallowed.
     */
    fun flashAttributeValue(
        head: GroupableFixture,
        attribute: String,
        percent: Int,
    ): CueAssignmentResolver.AttributeValue? {
        val share = percent.coerceIn(0, 100)
        return when (val raw = rawAttribute(head, attribute)) {
            is DmxSlider -> CueAssignmentResolver.AttributeValue.Slider(
                (raw.min.toInt() + ((raw.max.toInt() - raw.min.toInt()) * share + 50) / 100).toUByte(),
            )
            is DmxColour, is DmxWhiteMixColour -> {
                val v = (255 * share + 50) / 100
                CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(Color(v, v, v)))
            }
            else -> null
        }
    }

    /** Whether [attribute] on [head] is a colour; null when the head has no continuous [attribute] at all. */
    fun isColour(head: GroupableFixture, attribute: String): Boolean? =
        describeAttributeRead(head, attribute)?.let { it is AttributeRead.Colour || it is AttributeRead.WhiteMix }

    // --- Reads ---

    /**
     * What a continuous control stands on for one head: a slider's one channel, or a colour's
     * three read together on one [ColourAxis]. Built without reading a value, for a reverse
     * index; read with [readHead].
     */
    sealed interface AttributeRead {
        /** Every channel the read depends on — the index keys. */
        val channels: List<AttributeChannel>

        /** One channel; [positionAxis] when it is the head's pan or tilt, read from its composed `position`. */
        data class Slider(val channel: AttributeChannel, val positionAxis: PositionAxis? = null) : AttributeRead {
            override val channels: List<AttributeChannel> get() = listOf(channel)
        }

        data class Colour(
            val red: AttributeChannel,
            val green: AttributeChannel,
            val blue: AttributeChannel,
        ) : AttributeRead {
            override val channels: List<AttributeChannel> get() = listOf(red, green, blue)
        }

        /** A white-mix colour: its two whites, read as the colour [mix] says they make. */
        data class WhiteMix(
            val warm: AttributeChannel,
            val cold: AttributeChannel,
            val mix: uk.me.cormack.lighting7.fixture.attribute.WhiteMix,
        ) : AttributeRead {
            override val channels: List<AttributeChannel> get() = listOf(warm, cold)
        }
    }

    /** Structural description of one channel that backs a fixture property. */
    data class AttributeChannel(
        val universe: Universe,
        val channel: Int,
        val min: UByte,
        val max: UByte,
        val category: AttributeCategory,
    )

    /**
     * The [AttributeRead] behind [attribute] on [head], or null when the property is not
     * continuous-bindable (a setting, an unknown name, a type with no continuous rule).
     */
    fun describeAttributeRead(head: GroupableFixture, attribute: String): AttributeRead? {
        val resolved = AttributeChannelWriter.resolveAttribute(head, attribute) ?: return null
        return when (val raw = resolved.value) {
            is DmxSlider -> AttributeRead.Slider(
                AttributeChannel(raw.universe, raw.channelNo, raw.min, raw.max, resolved.category),
                positionAxisOf(head, raw),
            )
            is DmxColour -> AttributeRead.Colour(
                red = AttributeChannel(raw.universe, raw.redSlider.channelNo, 0u, 255u, AttributeCategory.COLOUR),
                green = AttributeChannel(raw.universe, raw.greenSlider.channelNo, 0u, 255u, AttributeCategory.COLOUR),
                blue = AttributeChannel(raw.universe, raw.blueSlider.channelNo, 0u, 255u, AttributeCategory.COLOUR),
            )
            is DmxWhiteMixColour -> AttributeRead.WhiteMix(
                warm = AttributeChannel(raw.warm.universe, raw.warm.channelNo, 0u, 255u, AttributeCategory.COLOUR),
                cold = AttributeChannel(raw.cold.universe, raw.cold.channelNo, 0u, 255u, AttributeCategory.COLOUR),
                mix = raw.mix,
            )
            else -> null
        }
    }

    /**
     * The channels that back [attribute] on [fixture] — the flat form of
     * [describeAttributeRead], empty when the property is not continuous-bindable.
     */
    fun describeFixtureAttribute(head: GroupableFixture, attribute: String): List<AttributeChannel> =
        describeAttributeRead(head, attribute)?.channels.orEmpty()

    /**
     * One head's feedback position from [value], its composed value for the key ([composedTarget]), on
     * [axis] for a colour (null is hue). Null when the head has no position to report on that axis (a
     * colour with no hue, a black with no saturation), when [value] is not the head's kind, or when a
     * non-null axis is asked of a slider — the mirror of [toAttributeValue]'s null, so a head the write
     * skips is a head the read skips too.
     */
    fun readHead(head: AttributeRead, value: FxOutput, axis: ColourAxis? = null): HeadValue? = when (head) {
        is AttributeRead.Slider -> {
            if (axis != null) return null
            val pc = head.channel
            val dmx = when (head.positionAxis) {
                PositionAxis.PAN -> (value as? FxOutput.Position)?.pan
                PositionAxis.TILT -> (value as? FxOutput.Position)?.tilt
                null -> (value as? FxOutput.Slider)?.value
            } ?: return null
            HeadValue(scaleWithinRangeTo7Bit(dmx, pc.min, pc.max))
        }
        is AttributeRead.Colour, is AttributeRead.WhiteMix -> {
            val colour = (value as? FxOutput.Colour)?.color?.color ?: return null
            colourRead(axis.effective, colour.red.toUByte(), colour.green.toUByte(), colour.blue.toUByte())
        }
    }

    private fun rawAttribute(head: GroupableFixture, attribute: String): Any? =
        AttributeChannelWriter.resolveAttribute(head, attribute)?.value

    /**
     * [colour] with [held]'s white, amber and UV beside it, for each emitter the head carries as a
     * `bundleWithColour` slider (an element's included); one it lacks is 0. What makes a hue turn leave
     * the white fader's level alone.
     */
    private fun currentExtended(head: GroupableFixture, colour: Color, held: ExtendedColour?): ExtendedColour {
        val bundled = FixtureAttributeCatalogue.of(head::class).bundledByCategory
        fun emitter(category: AttributeCategory, value: UByte?): UByte =
            if (bundled[category] == null) 0u else value ?: 0u
        return ExtendedColour(
            colour,
            white = emitter(AttributeCategory.WHITE, held?.white),
            amber = emitter(AttributeCategory.AMBER, held?.amber),
            uv = emitter(AttributeCategory.UV, held?.uv),
        )
    }
}
