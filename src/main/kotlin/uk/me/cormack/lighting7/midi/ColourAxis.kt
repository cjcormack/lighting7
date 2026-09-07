package uk.me.cormack.lighting7.midi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which axis of a colour property a continuous control drives. Colour is HSV here — the model
 * [PropertyChannelResolver] has always preserved on a hue turn — and each axis is one of its three
 * components, plus a fine trim on hue, the one axis coarse enough at 128 steps to want one.
 *
 * **Null means [HUE].** A binding target carries `colourAxis: ColourAxis? = null`, and the wire
 * omits the field for a hue binding (`BindingTargetJson` has `encodeDefaults = false`), so every
 * row written before axes existed means what it always meant and a new hue binding is
 * byte-identical to an old one. The enum still has a `hue` member so a hand-built payload can say
 * it; everything that *compares* an axis goes through [effective] so the two spellings of hue can
 * never read as two axes. `lib/colourAxis.ts` in `lighting-react` mirrors the four serial names.
 */
@Serializable
enum class ColourAxis {
    /** The colour wheel in [PropertyChannelResolver.HUE_STEPS] steps, 0 red. */
    @SerialName("hue") HUE,

    /**
     * A centred trim of ±½ coarse hue step: 64 is rest and leaves the head on its coarse step, so
     * the coarse control's read (which rounds to the nearest step) is unmoved by any fine position.
     */
    @SerialName("hueFine") HUE_FINE,

    /** HSV saturation, 0 white at the head's brightness, 127 the pure hue. */
    @SerialName("saturation") SATURATION,

    /** HSV value — the level of the RGB mix, 0 black, 127 the head's brightest at its hue and saturation. */
    @SerialName("brightness") BRIGHTNESS,
}

/** The axis a nullable one means: null is hue. Compare axes through this, never directly. */
val ColourAxis?.effective: ColourAxis get() = this ?: ColourAxis.HUE
