import type { StageElementDto } from '../../../../api/stageElementApi'
import {
  boxPart,
  elementFinish,
  paramEnum,
  paramNumber,
  type BuildContext,
  type ElementBuild,
  type PartFinish,
  type ScenePart,
} from '../sceneParts'

/** A rail's thickness, and its finish: a dark painted balustrade. */
const RAIL_THICKNESS_M = 0.06
const RAIL: PartFinish = { colour: '#24211f', pattern: 'PLAIN', emissive: false }

/**
 * A `PLATFORM`: a deck, rostrum or balcony whose **Z is its top surface** — the deck hangs
 * [heightM] below the frame's origin, as a region's box hangs below its `centerZ`. With `railHeightM`
 * and `railEdge` it carries a rail standing on that edge of the top.
 *
 * A platform linked to a region the view is drawing (`regionUuid`, D5) is that region's deck: the
 * region draws the deck and takes the pointer, and the platform adds only its rail. A link to a
 * region that is gone, or not drawn, reads as none.
 */
export function buildPlatform(element: StageElementDto, context: BuildContext): ElementBuild {
  const w = element.widthM
  const d = element.depthM
  const h = element.heightM
  if (!(w > 0 && d > 0 && h > 0)) return { parts: [], seats: [] }
  const parts: ScenePart[] = []
  const region = typeof element.params.regionUuid === 'string' ? element.params.regionUuid : null
  if (region == null || !context.drawnRegionUuids.has(region)) {
    const deck = boxPart('deck', 0, 0, -h, w, d, h, elementFinish(element))
    if (deck != null) parts.push(deck)
  }
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
