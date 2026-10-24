/**
 * Pan and tilt as the operator types and reads them: **centre-relative DMX** (venue-visit plan D3).
 *
 * `-128..127` on each axis, 0 at the centre of travel (DMX 128), one unit a coarse DMX step — so
 * DMX 1 reads -127, 255 reads +127 and 0 reads -128. The fine channel is not part of the scale: it
 * stays underneath, written by aim and raw channels, as it always was. Every surface that shows or
 * takes a position goes through here — the programmer's cells, the fixture sheet, cue values,
 * Spread, the FX editor and palettes — so one value reads one way everywhere.
 *
 * The literal on the wire is unchanged: a programmer entry, a cue row and a Look row still hold
 * `"pan,tilt"` bytes, and only the display moves. A palette's position intent is `pos:pan,tilt` in
 * this scale (`paletteIntent.ts`), which the desk resolves as the same fraction of each head's own
 * travel.
 *
 * `lib/axisDegrees.ts` is no longer for display or entry (`positionOnScale` reads a stored degree
 * through it once, to show it on this scale). Degree annotations stay on the fixture
 * types because aim and the Stage view still need them, and that module serves those.
 */

import type { SliderPropertyDescriptor } from '../store/fixtures'
import { degreesToDmx } from './axisDegrees'

/** The scale's ends: DMX 0 and DMX 255 on a 0–255 axis. */
export const CENTRE_MIN = -128
export const CENTRE_MAX = 127

/** An axis's own DMX range — a slider descriptor's `min` / `max`, 0–255 on every axis in this rig. */
export interface AxisRange {
  min: number
  max: number
}

const FULL: AxisRange = { min: 0, max: 255 }

/** A value clamped to the scale and rounded to a whole coarse step. */
export function clampCentre(value: number): number {
  // `|| 0` folds `Math.round(-0.4)`'s -0 into 0, which would otherwise read as `-0` in a field.
  return Math.min(CENTRE_MAX, Math.max(CENTRE_MIN, Math.round(value) || 0))
}

/**
 * A raw DMX value to the scale: exactly `dmx - 128` on a 0–255 axis. A narrower axis maps its own
 * range onto the scale, the same fraction of travel the desk's `PaletteResolver` resolves.
 */
export function dmxToCentre(dmx: number, range: AxisRange = FULL): number {
  const span = range.max - range.min
  if (span <= 0) return 0
  const t = Math.max(0, Math.min(1, (dmx - range.min) / span))
  return clampCentre(CENTRE_MIN + t * (CENTRE_MAX - CENTRE_MIN))
}

/** The inverse: a centre-relative value back to the axis's raw DMX, clamped and whole. */
export function centreToDmx(value: number, range: AxisRange = FULL): number {
  const t = (clampCentre(value) - CENTRE_MIN) / (CENTRE_MAX - CENTRE_MIN)
  return Math.round(range.min + t * (range.max - range.min))
}

/** `+12`, `0`, `-12` — a reading that says which side of centre it is on. */
export function formatCentre(value: number): string {
  const step = clampCentre(value)
  return step > 0 ? `+${step}` : String(step)
}

/**
 * The travel the palette editor authored degrees over before D3: 540° of pan and 270° of tilt.
 *
 * A stored generic `deg:` palette names no head, so it has no travel of its own to convert through.
 * It is shown in this scale through the range the old control ran over (venue-visit plan P3;
 * Chris's call, 2026-10-15), so 270° / 135° reads 0 / 0. It keeps resolving as angles on the desk
 * until it is edited, and saves centre-relative only then.
 */
export const LEGACY_PAN_DEGREES = 540
export const LEGACY_TILT_DEGREES = 270

/** A legacy degree on an axis of [spanDegrees] travel, read on the scale. */
export function legacyDegreesToCentre(degrees: number, spanDegrees: number): number {
  if (spanDegrees <= 0) return 0
  // The byte first, rounded as the desk's degree arm rounds it, so 270° of 540° is DMX 128 — 0.
  return dmxToCentre(Math.round(Math.max(0, Math.min(1, degrees / spanDegrees)) * 255))
}

/** The axes a per-fixture row names, for converting its stored degrees exactly. */
export interface PositionAxes {
  pan?: SliderPropertyDescriptor
  tilt?: SliderPropertyDescriptor
}

/**
 * A palette's position intent on the centre scale, for display and for seeding an editor.
 *
 * A `centre` intent is itself. A `degrees` one (stored before D3) is converted for display only:
 * through the row's own head where [axes] are given and annotate travel — a per-fixture row, exact
 * — and through the old editor's 540° / 270° otherwise. The stored intent is never rewritten here.
 */
export function positionOnScale(
  intent: { pan: number; tilt: number; unit: 'centre' | 'degrees' },
  axes?: PositionAxes,
): { pan: number; tilt: number } {
  if (intent.unit === 'centre') return { pan: clampCentre(intent.pan), tilt: clampCentre(intent.tilt) }
  const exact = (deg: number, slider: SliderPropertyDescriptor | undefined, legacySpan: number) => {
    const dmx = slider ? degreesToDmx(deg, slider) : null
    return dmx != null && slider ? dmxToCentre(dmx, slider) : legacyDegreesToCentre(deg, legacySpan)
  }
  return {
    pan: exact(intent.pan, axes?.pan, LEGACY_PAN_DEGREES),
    tilt: exact(intent.tilt, axes?.tilt, LEGACY_TILT_DEGREES),
  }
}
