import type { PropertyCategory, SliderPropertyDescriptor } from '../store/fixtures'
import { useSliderValue } from './usePropertyValues'
import { colourMagnitude } from '../lib/colourMath'

/**
 * Stable hook-call-order placeholder for `useSliderValue` when the optional
 * descriptor is absent. Channel u0c0 + name `__none__` is ignored downstream.
 */
function makeFallbackSlider(category: PropertyCategory): SliderPropertyDescriptor {
  return {
    type: 'slider',
    name: '__none__',
    displayName: '',
    category,
    channel: { universe: 0, channelNo: 0 },
    min: 0,
    max: 255,
  }
}

const FALLBACK_DIMMER = makeFallbackSlider('dimmer')

/**
 * Returns 0–1 fixture intensity from a dimmer slider, or 1 if the fixture
 * has no dimmer (assume "always on"). The fallback descriptor keeps the
 * hook-call order stable when `dimmerProp` is undefined.
 */
export function useNormalizedIntensity(
  dimmerProp: SliderPropertyDescriptor | undefined,
): number {
  const value = useSliderValue(dimmerProp ?? FALLBACK_DIMMER)
  if (!dimmerProp) return 1
  return Math.max(0, Math.min(1, value / 255))
}

/**
 * Colour-derived brightness 0..1 (a "virtual dimmer" — the brightest channel).
 * The effective stage intensity is `dimmerFactor * colourFactor`, so a
 * colour-only fixture at RGB 0 reads as dark rather than beaming at full once
 * beams are broadened. White/amber/UV all count as emitters. For fixtures with
 * no colour channel pass nothing → 1.
 */
export function colourFactor(
  r: number,
  g: number,
  b: number,
  w?: number,
  a?: number,
  uv?: number,
): number {
  return colourMagnitude(r, g, b, w, a, uv)
}
