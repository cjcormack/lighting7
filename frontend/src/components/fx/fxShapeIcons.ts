/**
 * The small pictures a Wave's and a Path's `shape` segment draws (fx-settings plan D9), keyed by
 * the option's stored value. Each is an SVG path in a 28 × 14 box, drawn by the segment in
 * `currentColor`. A picture is the option's idea, never the FX's output: the desk's preview is what
 * the rig runs (P2). An option the table does not name draws its word alone.
 */

const W = 28
const H = 14
const PAD = 2

/** A level over one cycle, 0–1, as a path across the box. */
function wave(level: (phase: number) => number): string {
  const steps = 48
  let d = ''
  for (let i = 0; i <= steps; i++) {
    const p = i / steps
    const x = PAD + p * (W - 2 * PAD)
    const y = H - PAD - level(p) * (H - 2 * PAD)
    d += `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
  }
  return d
}

/** A movement over one cycle, each axis −1..1 about the box's centre. */
function path(pan: (t: number) => number, tilt: (t: number) => number): string {
  const steps = 60
  let d = ''
  for (let i = 0; i <= steps; i++) {
    const t = i / steps
    const x = W / 2 + pan(t) * (W / 2 - PAD)
    const y = H / 2 - tilt(t) * (H / 2 - PAD)
    d += `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
  }
  return d
}

const TAU = 2 * Math.PI

const ICONS: Readonly<Record<string, string>> = {
  SINE: wave((p) => 0.5 - 0.5 * Math.cos(p * TAU)),
  RAMP_UP: wave((p) => p),
  RAMP_DOWN: wave((p) => 1 - p),
  TRIANGLE: wave((p) => (p < 0.5 ? p * 2 : 2 - p * 2)),
  SQUARE: wave((p) => (p < 0.5 ? 1 : 0)),
  PULSE: wave((p) => (p < 0.1 ? p / 0.1 : p < 0.4 ? 1 : p < 0.7 ? 1 - (p - 0.4) / 0.3 : 0)),
  CIRCLE: path((t) => Math.cos(t * TAU) * 0.5, (t) => Math.sin(t * TAU)),
  FIGURE_8: path((t) => Math.cos(t * TAU), (t) => Math.sin(2 * t * TAU)),
  LINE: path((t) => 2 * t - 1, () => 0),
  RANDOM: path((t) => Math.sin(t * 23) * 0.9, (t) => Math.sin(t * 41) * 0.9),
}

/** The 28 × 14 box every picture is drawn in. */
export const SHAPE_ICON_BOX = { width: W, height: H } as const

/** The picture for a shape option, by its stored value (any case); null where the table has none. */
export function shapeIconPath(option: string): string | null {
  return ICONS[option.trim().toUpperCase()] ?? null
}
