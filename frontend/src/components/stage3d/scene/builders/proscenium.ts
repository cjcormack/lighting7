import type { StageElementDto } from '../../../../api/stageElementApi'
import { boxPart, elementFinish, finishLobes, paramNumber, type ElementBuild, type PartFinish, type ScenePart } from '../sceneParts'
import { wallWithOpenings } from './flat'

/** The surround round a proscenium opening: matt black, as a pros's is painted. */
const SURROUND: PartFinish = { colour: '#0e0e10', pattern: 'PLAIN', emissive: false, lobes: finishLobes('PROSCENIUM', null, 'surround') }
/** How far the surround stands proud of the wall's downstage face. */
const SURROUND_PROUD_M = 0.012

/**
 * A `PROSCENIUM`: a wall [widthM] across, [depthM] thick and [heightM] tall, with its opening —
 * `openingWidthM` × `openingHeightM`, centred across the wall, its bottom `openingSillM` above the
 * wall's base (the deck's height above the house floor) — and a black `surroundM` round the opening
 * on the house side. The surround is paint, not a surface a beam stops at.
 */
export function buildProscenium(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const t = element.depthM
  const h = element.heightM
  if (!(w > 0 && t > 0 && h > 0)) return { parts: [], seats: [] }
  const ow = Math.min(w, paramNumber(element, 'openingWidthM', 0))
  const sill = Math.max(0, paramNumber(element, 'openingSillM', 0))
  const oh = Math.min(h - sill, paramNumber(element, 'openingHeightM', 0))
  const finish = elementFinish(element)
  if (!(ow > 0 && oh > 0)) return { parts: wallWithOpenings('', w, t, h, [], finish), seats: [] }
  const parts = wallWithOpenings('', w, t, h, [{ fromM: (w - ow) / 2, widthM: ow, heightM: oh, sillM: sill }], finish)
  const s = Math.max(0, paramNumber(element, 'surroundM', 0))
  if (s > 0) {
    const y = -t / 2 - SURROUND_PROUD_M / 2
    const strips: Array<ScenePart | null> = [
      boxPart('surround-sr', -(ow + s) / 2, y, sill, s, SURROUND_PROUD_M, oh + s, SURROUND, false),
      boxPart('surround-sl', (ow + s) / 2, y, sill, s, SURROUND_PROUD_M, oh + s, SURROUND, false),
      boxPart('surround-head', 0, y, sill + oh, ow + 2 * s, SURROUND_PROUD_M, s, SURROUND, false),
    ]
    for (const strip of strips) if (strip != null) parts.push(strip)
  }
  return { parts, seats: [] }
}
