import type { StageElementDto } from '../../../../api/stageElementApi'
import { pleatShape } from '../pleat'
import { elementFinish, elementStates, paramEnum, type ElementBuild, type ScenePart } from '../sceneParts'

/**
 * How much of its half a drawn curtain still covers when fully open: a pair of tabs gathers into
 * about a sixth of its width at each side, not into nothing.
 */
export const DRAWN_GATHER = 0.16

/**
 * The half-widths a `DRAW` drape covers at [open] (0 closed … 1 drawn), each from its own side: at
 * 0 each half covers half the width, meeting in the middle; at 1 each is gathered to
 * [DRAWN_GATHER] of that at its side.
 */
export function drawnHalfWidth(widthM: number, open: number): number {
  const o = Math.min(1, Math.max(0, open))
  return (widthM / 2) * (1 - (1 - DRAWN_GATHER) * o)
}

/**
 * A `DRAPE` — a leg, border, pair of tabs, cyc or backcloth — as pleated cloth [widthM] across and
 * [heightM] tall, standing on its base (a flown one at its `trimM`, [elementBaseZ]) in the plane
 * y = 0 of its frame. Its `role` sets nothing but a default colour; its `operation` sets how it
 * moves:
 *
 * - `DRAW` — a pair of tabs on a track: two halves, each gathered towards its own side by its
 *   `open` state (0 closed, 1 drawn, closed when unstated), so a Look or a cue that opens the tabs
 *   (session 8) moves the cloth and the gap between them.
 * - `FLY` — its Z is its trim.
 * - `DEAD`, or none — hangs where it is.
 *
 * `depthM` is the depth of the cloth's folds, which sets its fullness too ([pleatShape]), not a box.
 */
export function buildDrape(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const h = element.heightM
  if (!(w > 0 && h > 0)) return { parts: [], seats: [] }
  const base = elementFinish(element)
  const role = paramEnum(element, 'role')
  const finish = element.finishColour == null && role === 'CYC' ? { ...base, colour: '#d6dbe2' } : base
  if (paramEnum(element, 'operation') !== 'DRAW') {
    return { parts: [{ key: 'cloth', geometry: { shape: 'pleat', w, h, pleat: pleatShape(element) }, at: { x: 0, y: 0, z: h / 2 }, finish, collides: true }], seats: [] }
  }
  const half = drawnHalfWidth(w, elementStates(element).open ?? 0)
  // Each half's folds are measured from its outer edge, which stays put as it gathers.
  const parts: ScenePart[] = [
    { key: 'cloth-sr', geometry: { shape: 'pleat', w: half, h, pleat: pleatShape(element, 'sr'), anchor: 'left' }, at: { x: -w / 2 + half / 2, y: 0, z: h / 2 }, finish, collides: true },
    { key: 'cloth-sl', geometry: { shape: 'pleat', w: half, h, pleat: pleatShape(element, 'sl'), anchor: 'right' }, at: { x: w / 2 - half / 2, y: 0, z: h / 2 }, finish, collides: true },
  ]
  return { parts, seats: [] }
}
