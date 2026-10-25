import type { SettingOption } from '../store/fixtures'
import { bandOption, parseHex } from './colourBands'

/**
 * What an LED ring around a head's lens shows, as segments round the ring — the Stage view's
 * drawing of a `ring` setting and its `ring_speed` slider. Pure, with time passed in, as
 * `colourBands.ts` and `strobeBands.ts` are.
 *
 * A band is one of three things, as the desk declares it on the option: **off** (neither field),
 * a **colour** (`ringColour`) on every segment, or a **chase** (`ringChase`): an arc a quarter of
 * the ring long running round it, bright at its head and fading behind — in the band's colour where
 * it declares one, else each segment in the hue of its place on the ring. A type declares no chase
 * pattern, so every chase is drawn alike; the ring speed sets its rate.
 *
 * The ring is drawn beside the beam: the fixture's dimmer and strobe do not reach it.
 */

/** Segments a ring is drawn as. */
export const RING_SEGMENTS = 24

/** A ring's inner radius over its outer one: one geometry for every ring. */
export const RING_INNER = 0.8

/** A chase's revolutions a second at ring speed 0 and 255 — an estimate; no type declares one. */
export const RING_CHASE_MIN_RPS = 0.25
export const RING_CHASE_MAX_RPS = 1.5

/** How much of the ring a chase's arc covers. */
const CHASE_ARC = 0.25

/** One frame of a ring: each segment's sRGB hue (0..1, three per segment) and its 0..1 level. */
export interface RingPaint {
  rgb: Float32Array
  level: Float32Array
}

export function makeRingPaint(): RingPaint {
  return { rgb: new Float32Array(RING_SEGMENTS * 3), level: new Float32Array(RING_SEGMENTS) }
}

/** The band [level] sits in on the ring setting's [options]. */
export function ringBandAt(options: readonly SettingOption[], level: number): SettingOption | undefined {
  return bandOption(options, level)
}

/** A chase's revolutions a second at [speedLevel] (0..255); mid-range for a type with no speed channel. */
export function ringChaseRps(speedLevel: number | null): number {
  const t = speedLevel == null ? 0.5 : Math.max(0, Math.min(255, speedLevel)) / 255
  return RING_CHASE_MIN_RPS + (RING_CHASE_MAX_RPS - RING_CHASE_MIN_RPS) * t
}

/** A fully saturated hue at [h] (0..1), sRGB, into [out] at [i]. */
function hueInto(out: Float32Array, i: number, h: number): void {
  const k = (n: number) => (n + h * 6) % 6
  const f = (n: number) => 1 - Math.max(0, Math.min(k(n), 4 - k(n), 1))
  out[i] = f(5)
  out[i + 1] = f(3)
  out[i + 2] = f(1)
}

/**
 * Paint [out] for the ring in band [option] at [timeS], the chase at [speedLevel]. Answers whether
 * the picture moves with time — true for a chase alone — so the caller asks for frames only then.
 */
export function ringFrame(
  option: SettingOption | undefined,
  speedLevel: number | null,
  timeS: number,
  out: RingPaint,
): boolean {
  const colour = option?.ringColour != null ? parseHex(option.ringColour) : null
  const chase = option?.ringChase === true
  const head = chase ? (((timeS * ringChaseRps(speedLevel)) % 1) + 1) % 1 : 0
  for (let s = 0; s < RING_SEGMENTS; s++) {
    const at = (s + 0.5) / RING_SEGMENTS
    const behind = (((head - at) % 1) + 1) % 1
    out.level[s] = chase ? (behind < CHASE_ARC ? 1 - behind / CHASE_ARC : 0) : colour ? 1 : 0
    if (colour) {
      out.rgb[s * 3] = colour[0] / 255
      out.rgb[s * 3 + 1] = colour[1] / 255
      out.rgb[s * 3 + 2] = colour[2] / 255
    } else if (chase) {
      hueInto(out.rgb, s * 3, at)
    } else {
      out.rgb.fill(0, s * 3, s * 3 + 3)
    }
  }
  return chase
}
