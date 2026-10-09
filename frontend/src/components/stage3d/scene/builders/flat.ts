import type { StageElementDto } from '../../../../api/stageElementApi'
import { boxPart, elementFinish, paintTransmit, paramPaint, type ElementBuild, type PartFinish, type PartUv, type ScenePart } from '../sceneParts'

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
 *
 * A finish carrying paint (a flat's, scrim plan D4) gives each piece its share of the face as its
 * [ScenePart.uv] — the wall's width and height mapped onto the image once — so an opening cuts a
 * hole in the picture and the piers, sills and heads round it each show the part of it they cover.
 * The same rect is the piece's mask (D5, D7): where the image's alpha is a hole, light passes it.
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
  const painted = finish.paint != null
  const segment = (key: string, a: number, b: number, z0: number, z1: number) => {
    const uv: PartUv = { u0: a / w, u1: b / w, v0: z0 / h, v1: z1 / h }
    // A painted piece's alpha cuts it for light too (scrim plan D5, D7), over its own share of the face.
    const part = boxPart(`${keyPrefix}${key}`, -w / 2 + (a + b) / 2, 0, z0, b - a, t, z1 - z0, finish, paintTransmit(finish.paint, uv) ?? 'solid')
    push(part != null && painted ? { ...part, uv } : part)
  }
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
 * opening is drawn square-topped: an arch's curve is not modelled, only its clear opening. Its
 * `paint` (scrim plan D4) is stretched over its downstage and upstage faces, never its edges.
 */
export function buildFlat(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const t = element.depthM
  const h = element.heightM
  if (!(w > 0 && t > 0 && h > 0)) return { parts: [], seats: [] }
  const paint = paramPaint(element)
  const finish = paint != null ? { ...elementFinish(element), paint } : elementFinish(element)
  return { parts: wallWithOpenings('', w, t, h, flatOpenings(element), finish), seats: [] }
}
