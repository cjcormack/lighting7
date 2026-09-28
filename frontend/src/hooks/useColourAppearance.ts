import { useMemo } from 'react'
import type {
  ColourPropertyDescriptor,
  SliderPropertyDescriptor,
} from '../store/fixtures'
import { useColourValue } from './usePropertyValues'
import { useNormalizedIntensity, colourFactor } from './useNormalizedIntensity'
import { perceptualBrightness, computeAppearanceCss } from '../lib/colourMath'

export type ColourAppearance = {
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
  /** Raw hue-at-its-own-level, e.g. `rgb(20, 2, 0)`. Use to seed the picker. */
  combinedCss: string
  /** Pure hue at full brightness, baked at the perceptual display brightness —
   *  a single rgb() string for `backgroundColor`. */
  appearanceCss: string
}

/**
 * How a fixture's colour should *appear* on screen: its pure hue plus a
 * perceptually-scaled brightness derived from `dimmer × colour magnitude`.
 *
 * This treats a dimmerless fixture set to `r:20` exactly like a real dimmer at
 * 20 — both surface as a legible dim colour instead of near-black. Pass a small
 * `floor` where a swatch should stay faintly visible at zero level (e.g. a
 * colour-picker trigger). `combinedCss` is passed through unchanged for the
 * picker seed.
 */
export function useColourAppearance(
  colourProp: ColourPropertyDescriptor,
  dimmerProp?: SliderPropertyDescriptor,
  floor = 0,
): ColourAppearance {
  const colour = useColourValue(colourProp)
  const dimmerFactor = useNormalizedIntensity(dimmerProp)

  return useMemo(() => {
    const { r, g, b, w, a, uv } = colour
    const level = dimmerFactor * colourFactor(r, g, b, w, a, uv)
    const brightness = perceptualBrightness(level, floor)
    return {
      r,
      g,
      b,
      w,
      a,
      uv,
      combinedCss: colour.combinedCss,
      appearanceCss: computeAppearanceCss(r, g, b, w, a, uv, brightness),
    }
  }, [colour, dimmerFactor, floor])
}
