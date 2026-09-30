import { Color } from 'three'

// Shared palette for fixture bodies. Pull tonal changes through here so a rig-wide nudge is one
// edit.
//
// The rig reads as black silhouettes above the stage, as lanterns do in a hall: matt black
// housings, and a lens that is dark glass until the lamp is lit. Grey housings and a lens that
// glowed at half strength at dimmer zero (then bloomed, at threshold 0.15) were the "glowing balls"
// of the stage-view design record.

/** The lens at rest: dark glass. */
export const BODY_LENS_COLOR = '#0e1013'

/** Housing, yoke and hanger: matt near-black, lifted to a slate while selected or hovered. */
export const HOUSING_COLOR = '#16181c'
export const HOUSING_ACTIVE_COLOR = '#3a424d'

const DARK_GLASS = new Color(BODY_LENS_COLOR)

/**
 * A lens's colour: dark glass at level 0, the full hue at level 1, and never anything brighter
 * than the level says — so an unlit lamp cannot bloom.
 *
 * `level` is the *perceptual* 0..1 brightness (the lens is the colour indicator, and a linear level
 * crushes a dim-but-lit lamp to nothing), and `hue` the full-brightness colour. The lens is opaque:
 * it is a surface on the body, not a glow, and a translucent lens over a black housing read as grey.
 */
export function lensColour(out: Color, hue: Color, level: number): Color {
  return out.copy(DARK_GLASS).lerp(hue, Math.max(0, Math.min(1, level)))
}
