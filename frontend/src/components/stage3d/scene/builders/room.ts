import type { StageElementDto } from '../../../../api/stageElementApi'
import { elementFinish, paramsFinish, type ElementBuild, type ScenePart } from '../sceneParts'

/**
 * How far outside its box a room's faces are drawn. Anything standing on the floor or against a
 * wall — a region whose top is the hall floor, a platform, a flat — then wins the depth test rather
 * than flickering against the face it shares a plane with; 4 mm is nothing to the eye.
 */
export const ROOM_FACE_INSET_M = 0.004

/**
 * A `ROOM`: an inward-facing shell, [widthM] × [depthM] about its origin and [heightM] up from its
 * base. Every face is a quad drawn from inside only, so from an orbit outside the room the near
 * walls vanish and the far ones stay — the dollhouse (design record §"2. A scene document") — and an
 * orthographic section standing outside still sees in. `omit` leaves a side out: the one the
 * proscenium stands in, a stage house's floor that a platform is.
 *
 * Sides are theatre sides, as the backend's `StageSide`: downstage is −y, stage left +x.
 */
export function buildRoom(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const d = element.depthM
  const h = element.heightM
  if (!(w > 0 && d > 0 && h > 0)) return { parts: [], seats: [] }
  const omit = new Set(
    Array.isArray(element.params.omit)
      ? element.params.omit.filter((s): s is string => typeof s === 'string').map((s) => s.toUpperCase())
      : [],
  )
  const walls = elementFinish(element)
  const floor = paramsFinish(element.params.floor, elementFinish(element, 'floor'))
  const ceiling = paramsFinish(element.params.ceiling, elementFinish(element, 'ceiling'))
  const parts: ScenePart[] = []
  const face = (side: string, part: Omit<ScenePart, 'key' | 'collides'>) => {
    if (!omit.has(side)) parts.push({ key: side.toLowerCase(), collides: true, ...part })
  }
  const e = ROOM_FACE_INSET_M
  face('FLOOR', { geometry: { shape: 'quad', w, h: d, facing: 'up' }, at: { x: 0, y: 0, z: -e }, finish: floor })
  face('CEILING', { geometry: { shape: 'quad', w, h: d, facing: 'down' }, at: { x: 0, y: 0, z: h + e }, finish: ceiling })
  face('DOWNSTAGE', { geometry: { shape: 'quad', w, h, facing: 'upstage' }, at: { x: 0, y: -d / 2 - e, z: h / 2 }, finish: walls })
  face('UPSTAGE', { geometry: { shape: 'quad', w, h, facing: 'downstage' }, at: { x: 0, y: d / 2 + e, z: h / 2 }, finish: walls })
  face('STAGE_RIGHT', { geometry: { shape: 'quad', w: d, h, facing: 'left' }, at: { x: -w / 2 - e, y: 0, z: h / 2 }, finish: walls })
  face('STAGE_LEFT', { geometry: { shape: 'quad', w: d, h, facing: 'right' }, at: { x: w / 2 + e, y: 0, z: h / 2 }, finish: walls })
  return { parts, seats: [] }
}
