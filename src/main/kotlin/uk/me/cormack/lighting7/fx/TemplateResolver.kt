package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns a [TemplateIntent] into a value for **one head**.
 *
 * The single implementation, and that is a requirement rather than tidiness. Three callers ask the
 * same question and must get the same answer:
 *
 *  1. **cook** — [CueComposer.applyLayer], for a layer tracking a template;
 *  2. **click-apply** — `POST /{projectId}/templates/{id}/apply`, writing literals into the
 *     programmer;
 *  3. **the editor's "resolves to" panel** — `POST /{projectId}/templates/resolve`.
 *
 * If (3) computed its own ΔE the editor would be promising something the rig does not do, which is
 * exactly the failure the template idea exists to prevent: the panel's whole job is to let an
 * operator read the degradation *before* saving.
 *
 * ## Resolution goes by name, then by category
 *
 * A template names a property (`rgbColour`, `dimmer`, `zoom`, `position`) and the same name resolves
 * on nearly every head in this rig. Colour is the exception and it is not cosmetic: the MAC 250's
 * colour *wheel* is `val colour`, and [canonicalPropertyName] rewrites `colour` → `rgbColour`, so a
 * name-only lookup misses it and the head silently drops out of every colour template. So a colour
 * intent falls back to **whichever property carries the colour**, and [Resolution.propertyName]
 * reports the name that was actually resolved — which the cook must then use, because that is the
 * name [PropertyChannelWriter] will be handed downstream.
 */
object TemplateResolver {
    private val logger = LoggerFactory.getLogger(TemplateResolver::class.java)

    /**
     * What happened to the intent on the way to this head. Reported to the operator, not just
     * logged: "it works" and "it works, roughly" are different answers and the editor shows both.
     */
    sealed interface Note {
        /** Resolved with nothing lost. */
        data object Exact : Note

        /** Outside this head's range, so it was pulled to the edge. [to] is operator-facing. */
        data class Clamped(val to: String) : Note

        /**
         * A continuous intent landed on a discrete wheel. [deltaE] is CIE76 in Lab against the
         * slot's declared preview — see [nearestColourSlot] for what that number is worth.
         */
        data class Snapped(val slot: String, val deltaE: Double) : Note

        /** Resolved, but through fewer emitters than the intent asked for. */
        data class Degraded(val how: String) : Note

        /** This head cannot take this intent at all, and here is why. */
        data class Unsupported(val reason: String) : Note
    }

    /**
     * One head's answer. [value] is null exactly when [note] is [Note.Unsupported] — a head that
     * cannot take the intent contributes nothing rather than contributing a default.
     */
    data class Resolution(
        val value: CueAssignmentResolver.PropertyValue?,
        val propertyName: String,
        val note: Note,
    ) {
        val isSupported: Boolean get() = value != null
    }

    fun resolve(
        fixture: GroupableFixture,
        propertyName: String,
        intent: TemplateIntent,
    ): Resolution = when (intent) {
        is TemplateIntent.Colour -> resolveColour(fixture, propertyName, intent)
        is TemplateIntent.Position -> resolvePosition(fixture, propertyName, intent)
        is TemplateIntent.Percent -> resolvePercent(fixture, propertyName, intent)
        is TemplateIntent.Switch -> resolveSwitch(fixture, propertyName, intent)
    }

    // ─── Colour ─────────────────────────────────────────────────────────────

    /**
     * The **fixture-free** reading of a colour intent, for consumers holding no head.
     *
     * An FX colour parameter is one of them and the reason this exists: an effect's output is a
     * single [ExtendedColour] applied to every head it targets, so there is no fixture to ask
     * whether it has a white emitter. This resolves as though the head were RGBW — the common case
     * in this rig — which makes a template referenced from an effect produce **the same channels**
     * as the same template applied as a layer on any RGBW or RGBWA head.
     *
     * **Know what that costs a head with no white emitter**, because it is sharper than "one
     * emitter short". Under [WhitePolicy.EXTRACT] the neutral is taken *out of RGB* here, and
     * `ColourTarget.applyExtendedChannel` then silently drops the white byte on a head that has no
     * white property — so the head receives the reduced RGB with nothing compensating for it, and
     * reads both **dimmer and more saturated** than the hex asked for (`#FF9D4A` lands as
     * `#B55300`). It is not the RGB-only reading; it is worse than the RGB-only reading. An
     * amber-but-no-white head fares the same way, since [mixColour] routes the neutral to amber
     * only when there is no white to route it to, and here there always notionally is.
     *
     * That is the accepted trade for matching the layer exactly on the common head. If a rig is
     * mostly RGB-only, resolving with [WhitePolicy.RGB_ONLY] here instead is a one-line change and
     * inverts which class of head is exact.
     *
     * It goes through the same [mixColour] as [resolve] rather than repeating the arithmetic,
     * because the value an editor previews and the value the rig receives drifting apart is the
     * failure this whole area is built to avoid.
     */
    fun resolveColourGeneric(intent: TemplateIntent.Colour): ExtendedColour? {
        val target = parseHex(intent.hex) ?: return null
        return mixColour(target, intent.policy, hasWhite = true, hasAmber = true).first
    }

    private fun resolveColour(
        fixture: GroupableFixture,
        propertyName: String,
        intent: TemplateIntent.Colour,
    ): Resolution {
        val target = parseHex(intent.hex)
            ?: return Resolution(null, propertyName, Note.Unsupported("'${intent.hex}' is not a colour"))

        // One walk of the head's properties, not one per lookup (sweep item C8):
        // `resolvedProperties` reflects — a `KProperty1.call` per declared property — and the cook
        // asks this once per head per template row.
        val properties = PropertyChannelWriter.resolvedProperties(fixture)

        // Mixed colour first, whatever it is called on this head.
        val mixed = properties.firstOrNull { it.value is DmxColour }
        if (mixed != null) {
            val hasWhite = (fixture as? WithWhite)?.white is DmxSlider
            val hasAmber = (fixture as? WithAmber)?.amber is DmxSlider
            val (colour, note) = mixColour(target, intent.policy, hasWhite, hasAmber)
            return Resolution(CueAssignmentResolver.PropertyValue.Colour(colour), mixed.name, note)
        }

        // Then a colour wheel: a discrete set of slots, each with a declared preview.
        val wheel = properties
            .firstOrNull { it.category == PropertyCategory.COLOUR && it.value is DmxFixtureSetting<*> }
        if (wheel != null) {
            val setting = wheel.value as DmxFixtureSetting<*>
            val snapped = nearestColourSlot(setting, target)
                ?: return Resolution(
                    null, wheel.name,
                    Note.Unsupported("colour wheel has no annotated slot previews"),
                )
            return Resolution(
                CueAssignmentResolver.PropertyValue.Setting(snapped.level),
                wheel.name,
                Note.Snapped(snapped.name, snapped.deltaE),
            )
        }

        return Resolution(null, propertyName, Note.Unsupported("no colour"))
    }

    /**
     * Distribute one target colour across R/G/B plus whatever extra emitters this head has.
     *
     * [WhitePolicy.EXTRACT] moves `min(r, g, b)` — the neutral part of the colour — out of RGB and
     * into the **white** emitter, which is what makes the result brighter and cleaner at the same
     * hue. It goes to **amber only when there is no white**: amber is not neutral, so extracting
     * into it shifts the result warm, which is a different (and useful) trick that needs a
     * colorimetric fit rather than a subtraction. UV is never part of a colour match and stays at
     * zero under every policy — which is why a head's UV channel is not even asked about here.
     */
    private fun mixColour(
        target: Color,
        policy: WhitePolicy,
        hasWhite: Boolean,
        hasAmber: Boolean,
    ): Pair<ExtendedColour, Note> {
        val extraEmitter = hasWhite || hasAmber
        if (policy == WhitePolicy.RGB_ONLY || !extraEmitter) {
            // Not `Degraded`: on a head with no extra emitters, "RGB only" and every other policy
            // produce identical channels, so there is nothing for the operator to know.
            val note = if (policy != WhitePolicy.RGB_ONLY && !extraEmitter) {
                Note.Degraded("RGB only — no white or amber emitter")
            } else {
                Note.Exact
            }
            return ExtendedColour(target) to note
        }

        val neutral = minOf(target.red, target.green, target.blue)
        val toWhite = if (hasWhite) neutral else 0
        val toAmber = if (!hasWhite && hasAmber) neutral else 0
        val rgb = when (policy) {
            WhitePolicy.EXTRACT -> Color(
                target.red - neutral,
                target.green - neutral,
                target.blue - neutral,
            )
            // Additive drives the emitters *alongside* RGB rather than instead of part of it, so
            // RGB is untouched and the head reads brighter than the hex alone.
            else -> target
        }
        val colour = ExtendedColour(
            color = rgb,
            white = toWhite.toUByte(),
            amber = toAmber.toUByte(),
            uv = 0u,
        )
        val note = when {
            hasWhite -> Note.Exact
            else -> Note.Degraded("no white emitter — neutral driven through amber")
        }
        return colour to note
    }

    /** One wheel slot and how far it is from what was asked for. */
    private data class SnappedSlot(val name: String, val level: UByte, val deltaE: Double)

    /**
     * The wheel slot closest to [target] by CIE76 ΔE in Lab.
     *
     * ΔE is only as good as the annotation: `colourPreview` values are documented as "best-effort
     * approximations for the UI" (see `RobeColorSpot575Fixture`), so this number says "how close
     * the desk *believes* it got", which is exactly what the editor needs to show before a save.
     * Slots with no preview are not candidates — a null preview means "nobody annotated this",
     * never "this slot is black".
     */
    private fun nearestColourSlot(setting: DmxFixtureSetting<*>, target: Color): SnappedSlot? {
        val targetLab = toLab(target)
        var best: SnappedSlot? = null
        for (slot in setting.sortedValues) {
            val preview = (slot as? DmxFixtureColourSettingValue)?.colourPreview ?: continue
            val colour = parseHex(preview) ?: continue
            val delta = labDistance(targetLab, toLab(colour))
            if (best == null || delta < best.deltaE) {
                best = SnappedSlot(slot.name, slot.level, delta)
            }
        }
        return best
    }

    // ─── Position ───────────────────────────────────────────────────────────

    /**
     * Degrees to DMX, through this head's own annotated range.
     *
     * A head with no `degMin`/`degMax` on its pan or tilt reports [Note.Unsupported] rather than
     * guessing a range. That is visible in the editor's panel, which is the point: a silent
     * mid-range default would aim a light somewhere nobody asked for.
     */
    private fun resolvePosition(
        fixture: GroupableFixture,
        propertyName: String,
        intent: TemplateIntent.Position,
    ): Resolution {
        if (fixture !is WithPosition) {
            return Resolution(null, propertyName, Note.Unsupported("fixed head — no pan or tilt"))
        }
        // The degree range lives on the `@FixtureProperty` annotation, which only the fixture-level
        // catalogue carries — elements keep name and category alone. A template has no element
        // rows, so this is the whole story rather than a gap.
        val catalogue = (fixture as? Fixture)?.fixtureProperties
            ?: return Resolution(null, propertyName, Note.Unsupported("no property catalogue"))
        val pan = catalogue.firstOrNull { it.category == PropertyCategory.PAN }
        val tilt = catalogue.firstOrNull { it.category == PropertyCategory.TILT }
        val panSlider = fixture.pan as? DmxSlider
        val tiltSlider = fixture.tilt as? DmxSlider
        if (pan == null || tilt == null || panSlider == null || tiltSlider == null) {
            return Resolution(null, propertyName, Note.Unsupported("position is not DMX-backed"))
        }
        val panDmx = degreesToDmx(intent.panDeg, pan.degMin, pan.degMax, pan.inverted, panSlider)
            ?: return Resolution(null, propertyName, Note.Unsupported("pan has no degree range annotated"))
        val tiltDmx = degreesToDmx(intent.tiltDeg, tilt.degMin, tilt.degMax, tilt.inverted, tiltSlider)
            ?: return Resolution(null, propertyName, Note.Unsupported("tilt has no degree range annotated"))

        val clamps = buildList {
            panDmx.clampedTo?.let { add("pan ${formatDegrees(it)}") }
            tiltDmx.clampedTo?.let { add("tilt ${formatDegrees(it)}") }
        }
        return Resolution(
            CueAssignmentResolver.PropertyValue.Position(panDmx.value, tiltDmx.value),
            // "position" is the synthetic pan/tilt pair every other path already special-cases;
            // resolving it to one of the two axis names would break that.
            "position",
            if (clamps.isEmpty()) Note.Exact else Note.Clamped(clamps.joinToString(" · ")),
        )
    }

    private data class AxisValue(val value: UByte, val clampedTo: Double?)

    private fun degreesToDmx(
        degrees: Double,
        degMin: Double?,
        degMax: Double?,
        inverted: Boolean,
        slider: DmxSlider,
    ): AxisValue? {
        if (degMin == null || degMax == null || degMin == degMax) return null
        val low = minOf(degMin, degMax)
        val high = maxOf(degMin, degMax)
        val clamped = degrees.coerceIn(low, high)
        val fraction = (clamped - degMin) / (degMax - degMin)
        val effective = if (inverted) 1.0 - fraction else fraction
        val min = slider.min.toInt()
        val max = slider.max.toInt()
        val dmx = (min + effective * (max - min)).roundToInt().coerceIn(min, max)
        return AxisValue(dmx.toUByte(), clamped.takeIf { abs(it - degrees) > 0.05 })
    }

    // ─── Percent ────────────────────────────────────────────────────────────

    /**
     * A proportion of this head's own range for the named property.
     *
     * A dimmerless head reports [Note.Unsupported]. `BeamColour.dc.html` promises "a head with no
     * dimmer takes it as a virtual dimmer over its colour emitters — the existing virtual-dimmer
     * path", and **there is no such path** on this backend: the only virtual dimmer is a *group*
     * gesture the client fans out to members (see `plugins/ProgrammerSocket`). Saying so is better
     * than inventing one inside a resolver; it is recorded as a follow-up.
     */
    private fun resolvePercent(
        fixture: GroupableFixture,
        propertyName: String,
        intent: TemplateIntent.Percent,
    ): Resolution {
        val resolved = PropertyChannelWriter.resolveProperty(fixture, canonicalPropertyName(propertyName))
            ?: return Resolution(null, propertyName, Note.Unsupported("no $propertyName"))
        val fraction = (intent.value / 100.0).coerceIn(0.0, 1.0)
        return when (val raw = resolved.value) {
            is DmxSlider -> {
                val min = raw.min.toInt()
                val max = raw.max.toInt()
                val dmx = (min + fraction * (max - min)).roundToInt().coerceIn(min, max)
                Resolution(
                    CueAssignmentResolver.PropertyValue.Slider(dmx.toUByte()),
                    propertyName,
                    Note.Exact,
                )
            }
            // A continuous intent on a wheel-backed property: pick the slot nearest the requested
            // proportion of the wheel's own level span. Reported as a snap, because it is one.
            is DmxFixtureSetting<*> -> {
                val slots = raw.sortedValues
                if (slots.isEmpty()) {
                    return Resolution(null, propertyName, Note.Unsupported("$propertyName has no settings"))
                }
                val wanted = fraction * 255.0
                val slot = slots.minBy { abs(it.level.toInt() - wanted) }
                Resolution(
                    CueAssignmentResolver.PropertyValue.Setting(slot.level),
                    propertyName,
                    Note.Snapped(slot.name, 0.0),
                )
            }
            else -> {
                logger.debug(
                    "template percent targeted '{}' with no single backing channel on '{}'",
                    propertyName, fixture.targetKey,
                )
                Resolution(null, propertyName, Note.Unsupported("$propertyName is not a single channel"))
            }
        }
    }

    // ─── Switch ─────────────────────────────────────────────────────────────

    /**
     * A two-state beam role — prism in or out.
     *
     * On a wheel this is "the first slot that engages it" (or the lowest level, for off), which is
     * as far as a type-agnostic template can honestly go: a wheel mixing prisms with different
     * facet counts has no single "on".
     */
    private fun resolveSwitch(
        fixture: GroupableFixture,
        propertyName: String,
        intent: TemplateIntent.Switch,
    ): Resolution {
        val resolved = PropertyChannelWriter.resolveProperty(fixture, canonicalPropertyName(propertyName))
            ?: return Resolution(null, propertyName, Note.Unsupported("no $propertyName"))
        return when (val raw = resolved.value) {
            is DmxSlider -> Resolution(
                CueAssignmentResolver.PropertyValue.Slider(if (intent.on) raw.max else raw.min),
                propertyName,
                Note.Exact,
            )
            is DmxFixtureSetting<*> -> {
                val slots = raw.sortedValues
                if (slots.isEmpty()) {
                    return Resolution(null, propertyName, Note.Unsupported("$propertyName has no settings"))
                }
                val slot = if (intent.on) slots.last() else slots.first()
                Resolution(
                    CueAssignmentResolver.PropertyValue.Setting(slot.level),
                    propertyName,
                    Note.Snapped(slot.name, 0.0),
                )
            }
            else -> Resolution(null, propertyName, Note.Unsupported("$propertyName is not a single channel"))
        }
    }

    // ─── Colour maths ───────────────────────────────────────────────────────

    private fun parseHex(raw: String): Color? {
        val hex = raw.trim().removePrefix("#")
        return when (hex.length) {
            6 -> runCatching { Color(hex.toInt(16)) }.getOrNull()
            3 -> runCatching {
                Color(
                    hex.substring(0, 1).toInt(16) * 17,
                    hex.substring(1, 2).toInt(16) * 17,
                    hex.substring(2, 3).toInt(16) * 17,
                )
            }.getOrNull()
            else -> null
        }
    }

    private data class Lab(val l: Double, val a: Double, val b: Double)

    /** sRGB → CIE Lab (D65), for a perceptual distance rather than a channel-space one. */
    private fun toLab(colour: Color): Lab {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = linear(colour.red)
        val g = linear(colour.green)
        val b = linear(colour.blue)
        // D65 reference white, and the standard sRGB matrix.
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = (r * 0.2126 + g * 0.7152 + b * 0.0722)
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double): Double = if (t > 0.008856) cbrt(t) else (7.787 * t) + (16.0 / 116.0)
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun labDistance(a: Lab, b: Lab): Double =
        sqrt((a.l - b.l) * (a.l - b.l) + (a.a - b.a) * (a.a - b.a) + (a.b - b.b) * (a.b - b.b))

    private fun formatDegrees(value: Double): String =
        if (value == Math.floor(value)) "${value.toLong()}°" else String.format("%.1f°", value)
}
