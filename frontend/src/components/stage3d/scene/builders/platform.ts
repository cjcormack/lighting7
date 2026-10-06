import type { StageElementDto } from '../../../../api/stageElementApi'
import {
  boxPart,
  elementFinish,
  finishLobes,
  paramEnum,
  paramNumber,
  type ElementBuild,
  type PartFinish,
  type ScenePart,
} from '../sceneParts'

/** A rail's thickness, and its finish: a dark painted balustrade. */
const RAIL_THICKNESS_M = 0.06
const RAIL: PartFinish = { colour: '#24211f', pattern: 'PLAIN', emissive: false, lobes: finishLobes('PLATFORM', null, 'rail') }

/**
 * A `PLATFORM`: a deck, rostrum or balcony whose **Z is its top surface** — the deck hangs
 * [heightM] below the frame's origin, as a region's box hangs below its `centerZ`. With `railHeightM`
 * and `railEdge` it carries a rail standing on that edge of the top.
 *
 * A platform linked to a region (`regionUuid`, D5) draws its own deck in its own finish all the
 * same: the region it stands for is drawn only as an outline while editing (`StageRegionMeshes`).
 */
export function buildPlatform(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const d = element.depthM
  const h = element.heightM
  if (!(w > 0 && d > 0 && h > 0)) return { parts: [], seats: [] }
  const parts: ScenePart[] = []
  const deck = boxPart('deck', 0, 0, -h, w, d, h, elementFinish(element))
  if (deck != null) parts.push(deck)
  const railH = paramNumber(element, 'railHeightM', 0)
  const edge = paramEnum(element, 'railEdge')
  if (railH > 0 && edge != null) {
    const t = RAIL_THICKNESS_M
    const rail =
      edge === 'DOWNSTAGE'
        ? boxPart('rail', 0, -d / 2 + t / 2, 0, w, t, railH, RAIL)
        : edge === 'UPSTAGE'
          ? boxPart('rail', 0, d / 2 - t / 2, 0, w, t, railH, RAIL)
          : edge === 'STAGE_LEFT'
            ? boxPart('rail', w / 2 - t / 2, 0, 0, t, d, railH, RAIL)
            : edge === 'STAGE_RIGHT'
              ? boxPart('rail', -w / 2 + t / 2, 0, 0, t, d, railH, RAIL)
              : null
    if (rail != null) parts.push(rail)
  }
  return { parts, seats: [] }
}
