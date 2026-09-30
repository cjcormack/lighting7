import type { StageElementDto } from '../../../../api/stageElementApi'
import { boxPart, elementFinish, type ElementBuild, type PartFinish, type ScenePart } from '../sceneParts'

/** One opening in a wall, along its width from its stage-right end (local −x), as the backend's `FlatOpening`. */
export interface WallOpening {
  fromM: number
  widthM: number
  heightM: number
  sillM: number
}

function finite(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v)
}

/** A flat's `openings`, narrowed; an entry that is not one is dropped. */
export function flatOpenings(element: Pick<StageElementDto, 'params'>): WallOpening[] {
  const raw = element.params.openings
  if (!Array.isArray(raw)) return []
  const out: WallOpening[] = []
  for (const o of raw) {
    if (o == null || typeof o !== 'object') continue
    const r = o as Record<string, unknown>
    if (!finite(r.fromM) || !finite(r.widthM) || !finite(r.heightM)) continue
    out.push({ fromM: r.fromM, widthM: r.widthM, heightM: r.heightM, sillM: finite(r.sillM) ? r.sillM : 0 })
  }
  return out
}

/**
 * A wall [w] wide, [t] thick and [h] tall, standing on z = 0 about x = 0, with [openings] cut
 * through it — as solid boxes: the full-height piers between openings, and under and over each
 * opening the sill and the head. Openings are taken in order along the wall; two that overlap cut
 * the overlap once. Shared by the flat and the proscenium, whose opening is one more of these.
 */
export function wallWithOpenings(
  keyPrefix: string,
  w: number,
  t: number,
  h: number,
  openings: readonly WallOpening[],
  finish: PartFinish,
): ScenePart[] {
  const parts: ScenePart[] = []
  const push = (p: ScenePart | null) => {
    if (p != null) parts.push(p)
  }
  const segment = (key: string, a: number, b: number, z0: number, z1: number) =>
    push(boxPart(`${keyPrefix}${key}`, -w / 2 + (a + b) / 2, 0, z0, b - a, t, z1 - z0, finish))
  let cursor = 0
  const sorted = [...openings]
    .map((o) => ({ ...o, fromM: Math.max(0, o.fromM), end: Math.min(w, o.fromM + o.widthM) }))
    .filter((o) => o.end > o.fromM)
    .sort((a, b) => a.fromM - b.fromM)
  sorted.forEach((o, i) => {
    const from = Math.max(cursor, o.fromM)
    segment(`pier-${i}`, cursor, from, 0, h)
    if (o.end > from) {
      const sill = Math.min(h, Math.max(0, o.sillM))
      const head = Math.min(h, sill + o.heightM)
      segment(`sill-${i}`, from, o.end, 0, sill)
      segment(`head-${i}`, from, o.end, head, h)
    }
    cursor = Math.max(cursor, o.end)
  })
  segment('pier-end', cursor, w, 0, h)
  return parts
}

/**
 * A `FLAT`: a panel [widthM] across, [depthM] thick and [heightM] tall, standing on its base about
 * its origin, with its `openings` — doors, windows, French windows, arches — cut through. Each
 * opening is drawn square-topped: an arch's curve is not modelled, only its clear opening.
 */
export function buildFlat(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const t = element.depthM
  const h = element.heightM
  if (!(w > 0 && t > 0 && h > 0)) return { parts: [], seats: [] }
  return { parts: wallWithOpenings('', w, t, h, flatOpenings(element), elementFinish(element)), seats: [] }
}
