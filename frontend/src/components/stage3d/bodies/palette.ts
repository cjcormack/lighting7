import { Color, SRGBColorSpace } from 'three'
import type { FixtureColour } from '../../../lib/fixtureColours'

// Shared palette for fixture bodies. Pull tonal changes through here so a rig-wide nudge is one
// edit.
//
// The rig reads as black silhouettes above the stage: matt black housings with a little fill of
// their own, and a lens that is dark glass until the lamp is lit.

/** The lens at rest: dark glass. */
export const BODY_LENS_COLOR = '#0c0d10'

/** Housing, yoke and hanger: matt near-black. */
export const HOUSING_COLOR = '#2a2d33'
/** A selected housing: the desk's blue, lit. */
export const HOUSING_ACTIVE_COLOR = '#4262d0'
/** How much light of its own a housing has, so the rig reads against a dark room. */
export const HOUSING_FILL = 0.225
/** A selected housing's fill. One material draws every housing, so the difference rides the tint. */
export const HOUSING_ACTIVE_FILL = 0.4

/**
 * A housing under *Bodies · By colour* (tables-colours plan D12): each key's dark `--tag-<key>` from
 * `index.css` — the canvas is dark whatever the theme — pinned to it by `palette.test.ts`.
 */
export const TAG_HOUSING_COLORS: Readonly<Record<FixtureColour, string>> = {
  red: '#f87171',
  orange: '#fb923c',
  yellow: '#facc15',
  lime: '#a3e635',
  green: '#4ade80',
  cyan: '#67e8f9',
  pink: '#f472b6',
  brown: '#c08457',
  white: '#e4e4e7',
  grey: '#8b8b94',
}
/**
 * How much of its colour a tinted housing reflects: low enough that the brightest key, white, is
 * still dimmer than a selected housing's blue (`palette.test.ts`), so selection reads first.
 */
export const TAG_HOUSING_ALBEDO = 0.28

const DARK_SRGB = new Color(BODY_LENS_COLOR).getRGB(new Color(), SRGBColorSpace)
const HUE_SRGB = new Color()

/**
 * A lens's colour: dark glass at level 0, the full hue at level 1, and never anything brighter
 * than the level says. `level` is the linear 0..1 level and `hue` the full-brightness colour; the
 * mix is `level^0.6` in display space, so a dim-but-lit lamp still reads. The lens
 * is opaque: it is a surface on the body, not a glow.
 */
export function lensColour(out: Color, hue: Color, level: number): Color {
  const k = Math.max(0, Math.min(1, level)) ** 0.6
  hue.getRGB(HUE_SRGB, SRGBColorSpace)
  return out.setRGB(
    DARK_SRGB.r + (HUE_SRGB.r - DARK_SRGB.r) * k,
    DARK_SRGB.g + (HUE_SRGB.g - DARK_SRGB.g) * k,
    DARK_SRGB.b + (HUE_SRGB.b - DARK_SRGB.b) * k,
    SRGBColorSpace,
  )
}
