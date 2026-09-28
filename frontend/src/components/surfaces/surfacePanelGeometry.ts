import type { LayoutRegion, StripDefinition } from "@/store/surfaces"
import type { StripControl } from "@/lib/surfaceResolve"

/**
 * The arithmetic behind the panel picture, kept out of the components so it can be checked
 * without rendering one. Every number here comes from `midi-surface-design/`, which is the
 * authority on this view's layout.
 */

/** Fader frame: 150px tall, the rail inset 6px top and bottom. */
export const FADER_FRAME_HEIGHT = 150
export const FADER_RAIL_INSET = 6
export const FADER_TRAVEL = FADER_FRAME_HEIGHT - FADER_RAIL_INSET * 2

/** A MIDI 7-bit value is 0..127, so a full fader is 127 and not 128. */
export const MIDI_MAX = 127

/** `0..127` → `0..1`, clamped, because a profile can declare a control the desk over-drives. */
export function midiFraction(value: number): number {
  return Math.min(1, Math.max(0, value / MIDI_MAX))
}

export function midiPercent(value: number): number {
  return Math.round(midiFraction(value) * 100)
}

/** Height of the filled part of the rail, in px. */
export function faderFillHeight(value: number): number {
  return midiFraction(value) * FADER_TRAVEL
}

/**
 * `top` of a fader cap, in px. The cap is centred on the value (it carries `margin-top: -8px`),
 * so this is the centre line rather than the top edge of the cap.
 */
export function faderCapTop(value: number): number {
  return FADER_RAIL_INSET + (1 - midiFraction(value)) * FADER_TRAVEL
}

/** Encoder ring: 13 LEDs over a 270° arc, the same geometry an X-Touch's ring has. */
export const RING_DOTS = 13
const RING_RADIUS = 19
const RING_CENTRE = 22
const RING_SWEEP_DEG = 270

/** The centre of dot `i`, in the ring SVG's own 44×44 coordinates. */
export function ringDotPosition(i: number): { x: number; y: number } {
  const step = RING_SWEEP_DEG / (RING_DOTS - 1)
  const deg = -RING_SWEEP_DEG / 2 + i * step
  const rad = (deg * Math.PI) / 180
  return {
    x: RING_CENTRE + RING_RADIUS * Math.sin(rad),
    y: RING_CENTRE - RING_RADIUS * Math.cos(rad),
  }
}

/** Which dot a 7-bit value lights. `SINGLE_DOT` rings light exactly one. */
export function ringDotIndex(value: number): number {
  return Math.round(midiFraction(value) * (RING_DOTS - 1))
}

/** The end of the knob's pointer line, pointing at the lit dot. */
export function knobPointer(dotIndex: number): { x: number; y: number } {
  const step = RING_SWEEP_DEG / (RING_DOTS - 1)
  const deg = -RING_SWEEP_DEG / 2 + dotIndex * step
  const rad = (deg * Math.PI) / 180
  const length = 9
  return {
    x: RING_CENTRE + length * Math.sin(rad),
    y: RING_CENTRE - length * Math.cos(rad),
  }
}

/**
 * Which strip, if any, each column of a region is a picture of.
 *
 * Derived from the data rather than from the region being called `strips`, so a second device's
 * profile needs no component here and the right-hand block correctly answers nothing.
 *
 * The rule is **claimed by exactly one strip**, not *every control belongs to it*. A strip declares
 * four roles and a physical channel strip has more buttons than that: the X-Touch's column 0 is
 * `enc-1`, `btn-1` (the strip's flash), `btn-9`, `btn-17`, `fader-1`, `btn-25` (its select) — two
 * of six unclaimed, because the profile leaves them free for fixed bindings. Requiring every cell
 * meant this function returned an **empty map** for the only profile that has strips, so the
 * backdrop it exists to place never rendered on real hardware and there was nothing to outline as
 * a drop target. Unclaimed controls sit in the strip's column and read as part of it; two different
 * strips in one column would not, and still answer null.
 */
export function columnStrips(
  region: LayoutRegion,
  stripControls: Map<string, StripControl>,
): Map<number, StripDefinition> {
  const byColumn = new Map<number, StripDefinition | null>()
  for (const cell of region.cells) {
    const strip = stripControls.get(cell.controlId)?.strip
    if (strip == null) continue
    if (!byColumn.has(cell.col)) {
      byColumn.set(cell.col, strip)
      continue
    }
    // A column two strips share is a picture of neither.
    if (byColumn.get(cell.col)?.id !== strip.id) byColumn.set(cell.col, null)
  }

  const result = new Map<number, StripDefinition>()
  for (const [col, strip] of byColumn) if (strip) result.set(col, strip)
  return result
}

/** How many rows a region's cells occupy — the grid needs it to span a column backdrop. */
export function regionRowCount(region: LayoutRegion): number {
  return region.cells.reduce((max, cell) => Math.max(max, cell.row + 1), 0)
}
