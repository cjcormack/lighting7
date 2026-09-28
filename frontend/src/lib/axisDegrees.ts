import type { SliderPropertyDescriptor } from '../store/fixtures'

/**
 * A pan/tilt (or zoom) axis between its raw DMX value and the degrees its descriptor annotates.
 *
 * The mapping is the descriptor's `degMin` / `degMax`, set on the movers (10 of the 28 fixture
 * models today) and absent everywhere else — and where it is absent both functions answer null,
 * because a range is never invented: the 3D view treats such a head as static, and the position
 * editor keeps bytes in its fields (editor-kit plan D14). The result is a position along the
 * axis's *travel range* (a mover declares pan 0–540, tilt 0–210), not a signed angle; callers
 * aiming a head want `dmxToSignedDegrees` in `stageCoords.ts`. Reused verbatim for ZOOM sliders,
 * where `degMin` / `degMax` mean "full beam angle at DMX min / max".
 */
export function dmxToDegrees(dmx: number, slider: SliderPropertyDescriptor): number | null {
  if (slider.degMin == null || slider.degMax == null) return null
  const span = slider.max - slider.min
  if (span <= 0) return null
  const t = Math.max(0, Math.min(1, (dmx - slider.min) / span))
  const tt = slider.inverted ? 1 - t : t
  return slider.degMin + tt * (slider.degMax - slider.degMin)
}

/**
 * The inverse: a travel-degree position back to the axis's raw DMX value, clamped to the slider's
 * own range and rounded to a whole step. Null on a head that carries no annotation, so a degree
 * can never land on a head the editor never meant it for.
 */
export function degreesToDmx(deg: number, slider: SliderPropertyDescriptor): number | null {
  if (slider.degMin == null || slider.degMax == null) return null
  const degSpan = slider.degMax - slider.degMin
  const span = slider.max - slider.min
  if (span <= 0 || degSpan === 0) return null
  const t = Math.max(0, Math.min(1, (deg - slider.degMin) / degSpan))
  const tt = slider.inverted ? 1 - t : t
  return Math.round(slider.min + tt * span)
}

/** Both axes annotate — the position editor's test for typing degrees at all. */
export function annotatesDegrees(slider: SliderPropertyDescriptor | undefined): slider is SliderPropertyDescriptor & { degMin: number; degMax: number } {
  return slider != null && slider.degMin != null && slider.degMax != null && slider.max > slider.min
}
