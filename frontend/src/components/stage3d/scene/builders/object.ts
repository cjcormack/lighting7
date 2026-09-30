import type { StageElementDto } from '../../../../api/stageElementApi'
import { boxPart, elementFinish, paramEnum, type ElementBuild, type ScenePart } from '../sceneParts'

/**
 * An `OBJECT` — furniture, a plant, a fireplace, an exit sign — by its `shape`, standing on its
 * base about its origin:
 *
 * - `BOX` (the default): [widthM] × [depthM] × [heightM].
 * - `CYLINDER`: [widthM] across, [heightM] tall.
 * - `SHADE`: a lamp shade — a cone cut off at a little over half its base width, [heightM] tall.
 * - `DISC`: a disc facing the house, [widthM] across and [depthM] thick, its bottom at the base —
 *   a moon, a clock face.
 *
 * A flown object (`flies`) stands at its `trimM` instead of its Z (the renderer's
 * [elementBaseZ]); an `emissive` one glows at its colour rather than taking light.
 */
export function buildObject(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const d = element.depthM
  const h = element.heightM
  if (!(w > 0 && d > 0 && h > 0)) return { parts: [], seats: [] }
  const finish = elementFinish(element)
  const shape = paramEnum(element, 'shape') ?? 'BOX'
  let part: ScenePart | null
  switch (shape) {
    case 'CYLINDER':
      part = { key: 'body', geometry: { shape: 'cylinder', rTop: w / 2, rBottom: w / 2, h }, at: { x: 0, y: 0, z: h / 2 }, finish, collides: true }
      break
    case 'SHADE':
      part = { key: 'body', geometry: { shape: 'cylinder', rTop: w * 0.28, rBottom: w / 2, h }, at: { x: 0, y: 0, z: h / 2 }, finish, collides: true }
      break
    case 'DISC':
      part = { key: 'body', geometry: { shape: 'disc', r: w / 2, d }, at: { x: 0, y: 0, z: w / 2 }, finish, collides: true }
      break
    default:
      part = boxPart('body', 0, 0, 0, w, d, h, finish)
  }
  return { parts: part == null ? [] : [part], seats: [] }
}
