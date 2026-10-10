import type { StageElementDto } from '../../../../api/stageElementApi'
import { gatherShape, pleatShape } from '../pleat'
import { SCRIM_THREAD_SHARE } from '../scrimOpen'
import { fabricTranslucency } from '../seeThrough'
import {
  elementFinish,
  elementStates,
  FULL_UV,
  paintTransmit,
  paramEnum,
  paramFabric,
  paramPaint,
  type DrapeFabric,
  type ElementBuild,
  type PartFinish,
  type PartGeometry,
  type PartLight,
  type PartUv,
  type ScenePart,
} from '../sceneParts'

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
 * The width a one-way `DRAW` drape covers at [open], from the side it stacks at: at 0 the whole
 * width, at 1 gathered to [DRAWN_GATHER] of it — a bi-parting pair's two halves end to end.
 */
export function drawnOneWayWidth(widthM: number, open: number): number {
  return 2 * drawnHalfWidth(widthM, open)
}

/** A drawn drape's one side (`drawFrom`): the side it stacks at when open. Null is bi-parting. */
export function paramDrawFrom(element: Pick<StageElementDto, 'params'>): 'STAGE_LEFT' | 'STAGE_RIGHT' | null {
  const v = paramEnum(element, 'drawFrom')
  return v === 'STAGE_LEFT' || v === 'STAGE_RIGHT' ? v : null
}

/** A cyc's colour where it names none: a pale grey-blue sheet. */
const CYC_COLOUR = '#d6dbe2'
/** A scrim's or a muslin's where it names none (scrim plan session 2): off-white cloth. */
const OFF_WHITE = '#e9e5da'

/**
 * A drape's finish: the element's, with its role's or fabric's colour where it names none — a
 * net's or a muslin's off-white outranks a cyc's grey — its paint, if any, and a muslin's τ (D6,
 * `seeThrough.ts`), which lets light from behind through to its front.
 */
function drapeFinish(element: StageElementDto): PartFinish {
  const base = elementFinish(element)
  const fabric = paramFabric(element)
  const colour =
    element.finishColour != null
      ? base.colour
      : fabric === 'MUSLIN' || fabric === 'SHARKSTOOTH' || fabric === 'BOBBINET'
        ? OFF_WHITE
        : paramEnum(element, 'role') === 'CYC'
          ? CYC_COLOUR
          : base.colour
  const paint = paramPaint(element)
  const translucent = fabricTranslucency(fabric)
  return { ...base, colour, ...(paint != null && { paint }), ...(translucent != null && { translucent }) }
}

/**
 * How one piece of a drape meets light (scrim plan D7): a net (sharkstooth, bobbinet) passes
 * `open(θ)` of its thread share through [gather] layers — the piece's fullness, 1 hanging open — and
 * every other fabric is solid unless it is painted, when its paint's alpha cuts it over [uv]
 * ([paintTransmit]). A net keeps its angle even painted: its holes are its weave, and the one part
 * carries one share. Velour, canvas, muslin and an unpainted cloth stay solid.
 */
function drapeLight(fabric: DrapeFabric, finish: PartFinish, uv: PartUv, gather: number): PartLight {
  if (fabric === 'SHARKSTOOTH' || fabric === 'BOBBINET') return { kind: 'angle', r: SCRIM_THREAD_SHARE[fabric], gather }
  return paintTransmit(finish.paint, uv) ?? 'solid'
}

/**
 * A `DRAPE` — a leg, border, pair of tabs, cyc or backcloth — as cloth [widthM] across and
 * [heightM] tall, standing on its base (a flown one at its `trimM`, [elementBaseZ]) in the plane
 * y = 0 of its frame. Its `role` sets nothing but a default colour; its `operation` sets how it
 * moves:
 *
 * - `DRAW` — a pair of tabs on a track: two halves, each gathered towards its own side by its
 *   `open` state (0 closed, 1 drawn, closed when unstated), so a Look or a cue that opens the tabs
 *   (session 8) moves the cloth and the gap between them. A one-way traveller (`drawFrom`) is one
 *   cloth gathered towards that side instead, closing across the whole width.
 * - `FLY` — its Z is its trim.
 * - `DEAD`, or none — hangs where it is.
 *
 * **Only velour pleats** (scrim plan D2). A velour's `depthM` is the depth of its folds, which sets
 * its fullness too ([pleatShape]), not a box. Every other `fabric` hangs flat whatever its depth (a
 * `sheet`), and a drawn half of one folds only as it gathers ([gatherShape]): flat while closed,
 * deepening as it is drawn. A drawn velour keeps its hung pleats as it gathers, as it always has.
 *
 * **Paint** (D4) is stretched over the face: a dead, flown or one-way cloth carries the whole image,
 * and each drawn half its own half of it — compressed as the half gathers, so the picture stays whole however
 * far the tabs are open ([ScenePart.uv]). On velour it lies on the pleats.
 */
export function buildDrape(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const h = element.heightM
  if (!(w > 0 && h > 0)) return { parts: [], seats: [] }
  const finish = drapeFinish(element)
  const fabric = paramFabric(element)
  const velour = fabric === 'VELOUR'
  const painted = finish.paint != null
  if (paramEnum(element, 'operation') !== 'DRAW') {
    const geometry: PartGeometry = velour ? { shape: 'pleat', w, h, pleat: pleatShape(element) } : { shape: 'sheet', w, h }
    const light = drapeLight(fabric, finish, FULL_UV, 1)
    return {
      parts: [{ key: 'cloth', geometry, at: { x: 0, y: 0, z: h / 2 }, finish, light, ...(painted && { uv: FULL_UV }) }],
      seats: [],
    }
  }
  const open = elementStates(element).open ?? 0
  const drawFrom = paramDrawFrom(element)
  if (drawFrom != null) {
    const side = drawFrom === 'STAGE_LEFT' ? 'sl' : 'sr'
    const cover = drawnOneWayWidth(w, open)
    const fullness = w / cover
    const sign = side === 'sl' ? 1 : -1
    return {
      parts: [
        {
          key: `cloth-${side}`,
          geometry: {
            shape: 'pleat',
            w: cover,
            h,
            pleat: velour ? pleatShape(element, side) : gatherShape(element, side, fullness),
            anchor: side === 'sl' ? 'right' : 'left',
          },
          at: { x: sign * (w / 2 - cover / 2), y: 0, z: h / 2 },
          finish,
          light: drapeLight(fabric, finish, FULL_UV, fullness),
          ...(painted && { uv: FULL_UV }),
        },
      ],
      seats: [],
    }
  }
  const half = drawnHalfWidth(w, open)
  // How much cloth a half has against the width it is gathered into: 1 while closed.
  const fullness = w / 2 / half
  const fold = (side: 'sr' | 'sl') => (velour ? pleatShape(element, side) : gatherShape(element, side, fullness))
  const uvSr: PartUv = { u0: 0, u1: 0.5, v0: 0, v1: 1 }
  const uvSl: PartUv = { u0: 0.5, u1: 1, v0: 0, v1: 1 }
  // Each half's folds are measured from its outer edge, which stays put as it gathers. A net half
  // gathered into less width than it has stacks that many layers of itself (D3).
  const parts: ScenePart[] = [
    {
      key: 'cloth-sr',
      geometry: { shape: 'pleat', w: half, h, pleat: fold('sr'), anchor: 'left' },
      at: { x: -w / 2 + half / 2, y: 0, z: h / 2 },
      finish,
      light: drapeLight(fabric, finish, uvSr, fullness),
      ...(painted && { uv: uvSr }),
    },
    {
      key: 'cloth-sl',
      geometry: { shape: 'pleat', w: half, h, pleat: fold('sl'), anchor: 'right' },
      at: { x: w / 2 - half / 2, y: 0, z: h / 2 },
      finish,
      light: drapeLight(fabric, finish, uvSl, fullness),
      ...(painted && { uv: uvSl }),
    },
  ]
  return { parts, seats: [] }
}
