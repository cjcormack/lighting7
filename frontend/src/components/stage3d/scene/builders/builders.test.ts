import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../../../api/stageElementApi'
import { seatBase, seatingParams } from '../../../../lib/stageSeats'
import { buildElement } from '.'
import { DRAWN_GATHER, drawnHalfWidth, drawnOneWayWidth } from './drape'
import { SCRIM_THREAD_SHARE } from '../scrimOpen'
import { ROOM_FACE_INSET_M } from './room'
import { elementBaseZ, FINISH_LOBES, FULL_UV, type PartUv, type ScenePart } from '../sceneParts'
import { pleatShape } from '../pleat'
import { partGeometry } from '../StageSceneElements'
import { PAINT_FACE_ATTRIBUTE } from '../surfaceShader'

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'VENUE',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const keys = (parts: readonly ScenePart[]) => parts.map((p) => p.key).sort()
const part = (parts: readonly ScenePart[], key: string) => parts.find((p) => p.key === key)!

describe('the element builders (stage-view plan session 3)', () => {
  it('builds nothing for a hidden element, or one its visible state switches off', () => {
    expect(buildElement(element({ hidden: true })).parts).toEqual([])
    expect(buildElement(element({ params: { states: { visible: false } } })).parts).toEqual([])
    expect(buildElement(element({ kind: 'MESH' as never })).parts).toEqual([])
  })

  it('builds a room as inward-facing quads, leaving out the sides it omits, a hair outside its box', () => {
    const hall = element({
      kind: 'ROOM', widthM: 8.6, depthM: 18.4, heightM: 5.55, finishColour: '#4a4540',
      params: { omit: ['upstage'], floor: { colour: '#2b2724', pattern: 'BOARDS' } },
    })
    const { parts } = buildElement(hall)
    expect(keys(parts)).toEqual(['ceiling', 'downstage', 'floor', 'stage_left', 'stage_right'])
    // Each faces into the room.
    expect(part(parts, 'floor').geometry).toMatchObject({ shape: 'quad', facing: 'up' })
    expect(part(parts, 'ceiling').geometry).toMatchObject({ shape: 'quad', facing: 'down' })
    expect(part(parts, 'downstage').geometry).toMatchObject({ facing: 'upstage' })
    expect(part(parts, 'stage_right').geometry).toMatchObject({ facing: 'left' })
    expect(part(parts, 'stage_left').geometry).toMatchObject({ facing: 'right' })
    expect(part(parts, 'floor').at.z).toBeCloseTo(-ROOM_FACE_INSET_M, 9)
    expect(part(parts, 'stage_right').at.x).toBeCloseTo(-4.3 - ROOM_FACE_INSET_M, 9)
    // The floor takes its own finish; the walls the element's.
    expect(part(parts, 'floor').finish).toEqual({ colour: '#2b2724', pattern: 'BOARDS', emissive: false, lobes: FINISH_LOBES.DECK })
    expect(part(parts, 'downstage').finish.colour).toBe('#4a4540')
  })

  it('cuts a proscenium opening centred across the wall above its sill, with a surround that stops no beam', () => {
    const pros = element({
      kind: 'PROSCENIUM', positionY: 0.35, positionZ: -0.95, widthM: 8.6, depthM: 0.3, heightM: 5.55,
      params: { openingWidthM: 5.1, openingHeightM: 2.9, openingSillM: 0.95, surroundM: 0.18 },
    })
    const { parts } = buildElement(pros)
    const solid = parts.filter((p) => p.light === 'solid')
    // Two piers, the sill under the opening and the head over it.
    expect(solid).toHaveLength(4)
    const piers = solid.filter((p) => p.key.startsWith('pier'))
    expect(piers.map((p) => p.geometry)).toEqual([
      { shape: 'box', w: 1.75, d: 0.3, h: 5.55 },
      { shape: 'box', w: 1.75, d: 0.3, h: 5.55 },
    ])
    const sill = solid.find((p) => p.key.startsWith('sill'))!
    expect(sill.geometry).toMatchObject({ w: 5.1, h: 0.95 })
    const head = solid.find((p) => p.key.startsWith('head'))!
    expect(head.at.z).toBeCloseTo(0.95 + 2.9 + (5.55 - 3.85) / 2, 9)
    expect(parts.filter((p) => p.light === 'none').map((p) => p.key).sort()).toEqual(['surround-head', 'surround-sl', 'surround-sr'])
  })

  it("cuts a flat's openings from its stage-right end, sill and head around each", () => {
    const flat = element({
      kind: 'FLAT', widthM: 4.6, depthM: 0.1, heightM: 2.8,
      params: { openings: [{ kind: 'DOOR', fromM: 2.55, widthM: 1.2, heightM: 2.35 }] },
    })
    const { parts } = buildElement(flat)
    // A door has no sill: pier, head, pier.
    expect(keys(parts)).toEqual(['head-0', 'pier-0', 'pier-end'])
    expect(part(parts, 'pier-0').geometry).toMatchObject({ w: 2.55 })
    expect(part(parts, 'pier-0').at.x).toBeCloseTo(-2.3 + 2.55 / 2, 9)
    expect(part(parts, 'pier-end').geometry).toMatchObject({ w: 4.6 - 3.75 })
    const head = part(parts, 'head-0').geometry as { w: number; h: number }
    expect(head.w).toBeCloseTo(1.2, 9)
    expect(head.h).toBeCloseTo(2.8 - 2.35, 9)
  })

  it('draws a pair of tabs as two halves gathered to their sides by their open state, closed when unstated', () => {
    const tabs = (open?: number) =>
      element({
        kind: 'DRAPE', widthM: 5.7, heightM: 3.1, depthM: 0.2,
        params: { role: 'TABS', operation: 'DRAW', ...(open == null ? {} : { states: { open } }) },
      })
    const closed = buildElement(tabs()).parts
    expect(keys(closed)).toEqual(['cloth-sl', 'cloth-sr'])
    expect(part(closed, 'cloth-sr').geometry).toMatchObject({ shape: 'pleat', w: 2.85 })
    const open = buildElement(tabs(1)).parts
    expect((part(open, 'cloth-sr').geometry as { w: number }).w).toBeCloseTo(2.85 * DRAWN_GATHER, 9)
    // Each half hangs from its own side: its outer edge stays at the drape's edge.
    const sr = part(open, 'cloth-sr')
    expect(sr.at.x - (sr.geometry as { w: number }).w / 2).toBeCloseTo(-2.85, 9)
    expect(drawnHalfWidth(5.7, 0.5)).toBeCloseTo(2.85 * (1 - (1 - DRAWN_GATHER) * 0.5), 9)
    // A dead drape is one cloth, open state or not.
    expect(keys(buildElement(element({ kind: 'DRAPE', params: { role: 'LEG' } })).parts)).toEqual(['cloth'])
  })

  it('draws a one-way traveller as one cloth stacking at its drawFrom side, closed across the whole width', () => {
    const tabs = (drawFrom: string, open?: number, extra: Record<string, unknown> = {}) =>
      element({
        kind: 'DRAPE', widthM: 7.5, heightM: 4.2, depthM: 0.1,
        params: { role: 'TABS', operation: 'DRAW', drawFrom, ...(open == null ? {} : { states: { open } }), ...extra },
      })
    const closed = buildElement(tabs('STAGE_LEFT')).parts
    expect(keys(closed)).toEqual(['cloth-sl'])
    const whole = part(closed, 'cloth-sl')
    expect(whole.geometry).toMatchObject({ shape: 'pleat', w: 7.5, anchor: 'right' })
    expect(whole.at.x).toBeCloseTo(0, 9)

    // Open, it gathers into its side: the stage-left (+x) edge stays put.
    const sl = part(buildElement(tabs('stage_left', 1)).parts, 'cloth-sl')
    const slW = (sl.geometry as { w: number }).w
    expect(slW).toBeCloseTo(7.5 * DRAWN_GATHER, 9)
    expect(sl.at.x + slW / 2).toBeCloseTo(3.75, 9)

    const sr = part(buildElement(tabs('STAGE_RIGHT', 0.5)).parts, 'cloth-sr')
    const srW = (sr.geometry as { w: number }).w
    expect(sr.geometry).toMatchObject({ anchor: 'left' })
    expect(srW).toBeCloseTo(drawnOneWayWidth(7.5, 0.5), 9)
    expect(drawnOneWayWidth(7.5, 0.5)).toBeCloseTo(7.5 * (1 - (1 - DRAWN_GATHER) * 0.5), 9)
    expect(sr.at.x - srW / 2).toBeCloseTo(-3.75, 9)

    // A painted one-way cloth carries the whole image, as a dead one does; a net stacks its fullness.
    const painted = part(buildElement(tabs('STAGE_LEFT', 0.6, { paint: { front: FRONT } })).parts, 'cloth-sl')
    expect(painted.uv).toEqual(FULL_UV)
    expect(painted.light).toEqual({ kind: 'mask', image: FRONT, uv: FULL_UV })
    // Velour keeps its hung pleats as it gathers, folds measured from the stacking edge.
    const velour = part(buildElement(tabs('STAGE_LEFT', 1)).parts, 'cloth-sl')
    expect(velour.geometry).toMatchObject({ shape: 'pleat', pleat: pleatShape(tabs('STAGE_LEFT', 1), 'sl'), anchor: 'right' })
    const net = part(buildElement(tabs('STAGE_LEFT', 1, { fabric: 'BOBBINET' })).parts, 'cloth-sl')
    expect((net.light as { gather: number }).gather).toBeCloseTo(1 / DRAWN_GATHER, 9)

    // An unknown side is the bi-parting pair.
    expect(keys(buildElement(tabs('CENTRE')).parts)).toEqual(['cloth-sl', 'cloth-sr'])
  })

  it("hangs a platform's deck below its top, rails the edge it names, and draws its deck when linked to a region", () => {
    const balcony = element({
      kind: 'PLATFORM', positionZ: 1.9, widthM: 8.6, depthM: 2.2, heightM: 0.3,
      params: { railHeightM: 1, railEdge: 'UPSTAGE' },
    })
    const { parts } = buildElement(balcony)
    const deck = part(parts, 'deck')
    expect(deck.at.z - (deck.geometry as { h: number }).h / 2).toBeCloseTo(-0.3, 9)
    expect(deck.at.z + (deck.geometry as { h: number }).h / 2).toBeCloseTo(0, 9)
    expect(elementBaseZ(balcony)).toBe(1.9)
    const rail = part(parts, 'rail')
    expect(rail.at.y).toBeGreaterThan(0)
    expect(rail.at.z).toBeCloseTo(0.5, 9)
    const linked = element({ kind: 'PLATFORM', heightM: 0.95, params: { regionUuid: 'r1' } })
    expect(keys(buildElement(linked).parts)).toEqual(['deck'])
  })

  it("lists exactly lib/stageSeats.ts's seats for a seating block, and no parts", () => {
    const stalls = element({
      kind: 'SEATING', positionY: -2.4, positionZ: -0.95, widthM: 0, depthM: 0, heightM: 0,
      params: { rows: 12, seatsPerRow: 12, rowPitchM: 0.95, seatPitchM: 0.52, firstRow: 'A' },
    })
    const { parts, seats } = buildElement(stalls)
    expect(parts).toEqual([])
    expect(seats).toHaveLength(144)
    const params = seatingParams(stalls)!
    for (const seat of seats) expect(seat.base).toEqual(seatBase(stalls, params, seat.id))
  })

  it('shapes an object by its shape, and stands a flown one at its trim', () => {
    expect(buildElement(element({ params: { shape: 'CYLINDER' } })).parts[0].geometry).toMatchObject({ shape: 'cylinder' })
    expect(buildElement(element({ params: { shape: 'SHADE' } })).parts[0].geometry).toMatchObject({ shape: 'cylinder', rTop: 0.28 })
    const moon = element({ widthM: 0.9, depthM: 0.05, heightM: 0.9, positionZ: 0, params: { shape: 'DISC', flies: true, states: { trimM: 2.3 } } })
    expect(buildElement(moon).parts[0]).toMatchObject({ geometry: { shape: 'disc', r: 0.45 }, at: { z: 0.45 } })
    expect(elementBaseZ(moon)).toBe(2.3)
    // An exit sign glows at its colour.
    expect(buildElement(element({ emissive: true, finishColour: '#1bd760' })).parts[0].finish).toEqual({
      colour: '#1bd760', pattern: 'PLAIN', emissive: true, lobes: FINISH_LOBES.PAINT,
    })
  })
})

const FRONT = 'a'.repeat(64)
const BACK = 'b'.repeat(64)
const NON_VELOUR = ['CANVAS', 'MUSLIN', 'SHARKSTOOTH', 'BOBBINET'] as const

describe('cloth that hangs flat (scrim plan D2)', () => {
  const drape = (params: Record<string, unknown>, fields: Partial<StageElementDto> = {}) =>
    element({ kind: 'DRAPE', uuid: 'cloth', widthM: 6, heightM: 4, depthM: 0.2, params: { role: 'BACKCLOTH', ...params }, ...fields })

  it('draws every fabric but velour flat, dead or flown, whatever its depth', () => {
    for (const fabric of NON_VELOUR) {
      for (const operation of [undefined, 'DEAD', 'FLY']) {
        for (const depthM of [0.02, 0.2, 0.3]) {
          const { parts } = buildElement(drape({ fabric, operation }, { depthM }))
          expect(keys(parts)).toEqual(['cloth'])
          expect(part(parts, 'cloth').geometry).toEqual({ shape: 'sheet', w: 6, h: 4 })
        }
      }
    }
    // Velour — no fabric, or one this build does not know — still pleats.
    expect(part(buildElement(drape({})).parts, 'cloth').geometry).toMatchObject({ shape: 'pleat', pleat: pleatShape(drape({})) })
    expect(part(buildElement(drape({ fabric: 'SILK' })).parts, 'cloth').geometry).toMatchObject({ shape: 'pleat' })
  })

  it('folds a drawn half of any fabric only as it gathers: flat closed, deeper as it opens', () => {
    for (const fabric of NON_VELOUR) {
      let last = -1
      for (const open of [0, 0.25, 0.5, 0.75, 1]) {
        const { parts } = buildElement(drape({ role: 'TABS', operation: 'DRAW', fabric, states: { open } }))
        for (const key of ['cloth-sr', 'cloth-sl']) {
          const g = part(parts, key).geometry
          if (g.shape !== 'pleat') throw new Error('a drawn half folds')
          if (open === 0) expect(g.pleat.amplitudeM).toBe(0)
          else expect(g.pleat.amplitudeM).toBeGreaterThan(last)
        }
        last = (part(parts, 'cloth-sr').geometry as { pleat: { amplitudeM: number } }).pleat.amplitudeM
      }
    }
  })

  it('leaves velour as it was, dead and drawn: its pleats are its depth, open or closed', () => {
    const tabs = (open: number) => drape({ role: 'TABS', operation: 'DRAW', states: { open } })
    for (const open of [0, 0.5, 1]) {
      const { parts } = buildElement(tabs(open))
      expect(part(parts, 'cloth-sr').geometry).toMatchObject({ shape: 'pleat', pleat: pleatShape(tabs(open), 'sr') })
      expect(part(parts, 'cloth-sl').geometry).toMatchObject({ shape: 'pleat', pleat: pleatShape(tabs(open), 'sl') })
    }
  })

  it('finishes canvas and muslin matte and the nets as net, and dresses the nets and muslin off-white', () => {
    const finishOf = (params: Record<string, unknown>, fields: Partial<StageElementDto> = {}) =>
      part(buildElement(drape(params, fields)).parts, 'cloth').finish
    expect(finishOf({ fabric: 'CANVAS' }).lobes).toBe(FINISH_LOBES.MATTE)
    expect(finishOf({ fabric: 'MUSLIN' }).lobes).toBe(FINISH_LOBES.MATTE)
    expect(finishOf({ fabric: 'SHARKSTOOTH' }).lobes).toBe(FINISH_LOBES.NET)
    expect(finishOf({ fabric: 'BOBBINET' }).lobes).toBe(FINISH_LOBES.NET)
    expect(finishOf({}).lobes).toBe(FINISH_LOBES.VELOUR)
    expect(FINISH_LOBES.NET).toMatchObject({ sheen: 0, specular: 0 })
    const muslin = finishOf({ fabric: 'MUSLIN' }).colour
    expect(finishOf({ fabric: 'SHARKSTOOTH' }).colour).toBe(muslin)
    expect(finishOf({ fabric: 'BOBBINET', role: 'CYC' }).colour).toBe(muslin)
    expect(muslin).not.toBe(finishOf({}).colour)
    // Its own colour wins; canvas keeps its role's.
    expect(finishOf({ fabric: 'MUSLIN' }, { finishColour: '#203040' }).colour).toBe('#203040')
    expect(finishOf({ fabric: 'CANVAS' }).colour).toBe(finishOf({}).colour)
    expect(finishOf({ fabric: 'CANVAS', role: 'CYC' }).colour).toBe(finishOf({ role: 'CYC' }).colour)
  })
})

/** Every vertex's (u, v) and painted face in a part's drawn geometry. */
function vertexUvs(p: ScenePart): { u: number; v: number; face: number }[] {
  const g = partGeometry(p.geometry, p.uv)
  const uv = g.attributes.uv
  const face = g.getAttribute(PAINT_FACE_ATTRIBUTE)
  const out = Array.from({ length: uv.count }, (_, i) => ({ u: uv.getX(i), v: uv.getY(i), face: face.getX(i) }))
  g.dispose()
  return out
}

const extent = (rows: { u: number; v: number }[]) => ({
  u0: Math.min(...rows.map((r) => r.u)),
  u1: Math.max(...rows.map((r) => r.u)),
  v0: Math.min(...rows.map((r) => r.v)),
  v1: Math.max(...rows.map((r) => r.v)),
})

describe('paint on a drape and a flat (scrim plan D4)', () => {
  const painted = (params: Record<string, unknown>, fields: Partial<StageElementDto> = {}) =>
    element({
      kind: 'DRAPE', uuid: 'cloth', widthM: 6, heightM: 4, depthM: 0.1,
      params: { role: 'TABS', paint: { front: FRONT, back: BACK }, ...params }, ...fields,
    })

  it('carries the paint on the finish and the whole image on a dead cloth, and nothing on an unpainted one', () => {
    for (const fabric of [undefined, ...NON_VELOUR]) {
      const cloth = part(buildElement(painted({ fabric })).parts, 'cloth')
      expect(cloth.finish.paint).toEqual({ front: FRONT, back: BACK })
      expect(cloth.uv).toEqual(FULL_UV)
    }
    const plain = part(buildElement(element({ kind: 'DRAPE', params: { role: 'LEG' } })).parts, 'cloth')
    expect(plain.finish.paint).toBeUndefined()
    expect(plain.uv).toBeUndefined()
    // A malformed hash is no paint.
    expect(part(buildElement(painted({ paint: { front: 'nope' } })).parts, 'cloth').finish.paint).toBeUndefined()
  })

  it("tiles a drawn cloth's face exactly once across its two halves at any open, compressed as they gather", () => {
    for (const fabric of [undefined, 'CANVAS', 'SHARKSTOOTH']) {
      for (const open of [0, 0.1, 0.33, 0.5, 0.8, 1]) {
        const { parts } = buildElement(painted({ fabric, operation: 'DRAW', states: { open } }))
        const sr = part(parts, 'cloth-sr')
        const sl = part(parts, 'cloth-sl')
        // The rects: stage right's half of the image on stage right's half, meeting at the middle.
        expect(sr.uv).toEqual({ u0: 0, u1: 0.5, v0: 0, v1: 1 })
        expect(sl.uv).toEqual({ u0: 0.5, u1: 1, v0: 0, v1: 1 })
        // And the drawn vertices: each half's own edges reach exactly its share of the image.
        const a = extent(vertexUvs(sr))
        const b = extent(vertexUvs(sl))
        for (const [got, want] of [[a, sr.uv!], [b, sl.uv!]] as [PartUv, PartUv][]) {
          expect(got.u0).toBeCloseTo(want.u0, 6)
          expect(got.u1).toBeCloseTo(want.u1, 6)
          expect(got.v0).toBeCloseTo(0, 6)
          expect(got.v1).toBeCloseTo(1, 6)
        }
        // Once: the halves meet without a gap or an overlap, and together cover u 0…1.
        expect(Math.min(a.u0, b.u0)).toBeCloseTo(0, 6)
        expect(Math.max(a.u1, b.u1)).toBeCloseTo(1, 6)
        expect(a.u1 - a.u0 + (b.u1 - b.u0)).toBeCloseTo(1, 6)
        // u runs across each half with its width: compressed by the gather, never folded back.
        const w = (sr.geometry as { w: number }).w
        const g = partGeometry(sr.geometry, sr.uv)
        for (let i = 0; i < g.attributes.position.count; i++) {
          expect(g.attributes.uv.getX(i)).toBeCloseTo(((g.attributes.position.getX(i) + w / 2) / w) * 0.5, 6)
        }
        g.dispose()
      }
    }
  })

  it('lays velour\'s paint on its pleats: the same folds painted or not, the image across the flat width', () => {
    const plain = element({ kind: 'DRAPE', uuid: 'cloth', widthM: 6, heightM: 4, depthM: 0.1, params: { role: 'TABS' } })
    const cloth = part(buildElement(painted({})).parts, 'cloth')
    const bare = part(buildElement(plain).parts, 'cloth')
    expect(cloth.geometry).toEqual(bare.geometry)
    expect(cloth.geometry.shape).toBe('pleat')
    const withUv = partGeometry(cloth.geometry, cloth.uv)
    const without = partGeometry(bare.geometry)
    // The pleats hold: every vertex exactly where it was, folded out of the plane.
    expect(Array.from(withUv.attributes.position.array)).toEqual(Array.from(without.attributes.position.array))
    const zs = Array.from({ length: withUv.attributes.position.count }, (_, i) => withUv.attributes.position.getZ(i))
    expect(Math.max(...zs) - Math.min(...zs)).toBeGreaterThan(0.08)
    for (let i = 0; i < withUv.attributes.position.count; i++) {
      expect(withUv.attributes.uv.getX(i)).toBeCloseTo(withUv.attributes.position.getX(i) / 6 + 0.5, 6)
      expect(withUv.getAttribute(PAINT_FACE_ATTRIBUTE).getX(i)).toBe(1)
    }
    // Drawn velour keeps its hung pleats with its half of the image on them.
    const drawn = buildElement(painted({ operation: 'DRAW', states: { open: 0.6 } })).parts
    expect(part(drawn, 'cloth-sr').geometry).toMatchObject({ shape: 'pleat', pleat: pleatShape(painted({ operation: 'DRAW' }), 'sr') })
    withUv.dispose()
    without.dispose()
  })

  it("paints a flat's downstage and upstage faces, each piece its share, an opening cutting the picture", () => {
    const flat = element({
      kind: 'FLAT', widthM: 4, depthM: 0.1, heightM: 3,
      params: { paint: { front: FRONT }, openings: [{ fromM: 1, widthM: 1, heightM: 2, sillM: 0 }] },
    })
    const { parts } = buildElement(flat)
    expect(parts.every((p) => p.finish.paint?.front === FRONT)).toBe(true)
    // The pieces' rects tile the face less the opening: 1 − (1 × 2) / (4 × 3) of it, no overlap.
    const area = parts.reduce((sum, p) => sum + (p.uv!.u1 - p.uv!.u0) * (p.uv!.v1 - p.uv!.v0), 0)
    expect(area).toBeCloseTo(1 - 2 / 12, 9)
    expect(part(parts, 'pier-0').uv).toEqual({ u0: 0, u1: 0.25, v0: 0, v1: 1 })
    expect(part(parts, 'head-0').uv).toEqual({ u0: 0.25, u1: 0.5, v0: 2 / 3, v1: 1 })
    expect(part(parts, 'pier-end').uv).toEqual({ u0: 0.5, u1: 1, v0: 0, v1: 1 })
    // Downstage face 1, upstage −1, every edge and reveal 0; the faces reach their rect's corners.
    const rows = vertexUvs(part(parts, 'head-0'))
    expect(new Set(rows.map((r) => r.face))).toEqual(new Set([1, -1, 0]))
    for (const face of [1, -1]) {
      const e = extent(rows.filter((r) => r.face === face))
      expect(e.u0).toBeCloseTo(0.25, 6)
      expect(e.u1).toBeCloseTo(0.5, 6)
      expect(e.v0).toBeCloseTo(2 / 3, 6)
      expect(e.v1).toBeCloseTo(1, 6)
    }
    // Unpainted, a flat carries no rect.
    expect(buildElement(element({ kind: 'FLAT', params: {} })).parts.every((p) => p.uv == null)).toBe(true)
  })

  it('paints nothing but drapes and flats (D14)', () => {
    const paint = { front: FRONT, back: BACK }
    for (const kind of ['PROSCENIUM', 'ROOM', 'PLATFORM', 'OBJECT'] as const) {
      const { parts } = buildElement(element({ kind, widthM: 4, depthM: 2, heightM: 3, params: { paint } }))
      expect(parts.length).toBeGreaterThan(0)
      expect(parts.every((p) => p.finish.paint == null && p.uv == null)).toBe(true)
    }
  })
})

describe('how each part meets light (scrim plan D7)', () => {
  const drape = (params: Record<string, unknown>, fields: Partial<StageElementDto> = {}) =>
    element({ kind: 'DRAPE', uuid: 'cloth', widthM: 6, heightM: 4, depthM: 0.1, params: { role: 'BACKCLOTH', ...params }, ...fields })

  it('keeps velour, canvas, muslin and an unpainted cloth solid, dead, flown or drawn', () => {
    for (const fabric of [undefined, 'CANVAS', 'MUSLIN']) {
      for (const operation of [undefined, 'FLY', 'DRAW']) {
        const { parts } = buildElement(drape({ fabric, operation, states: { open: 0.5 } }))
        expect(parts.length).toBeGreaterThan(0)
        expect(parts.every((p) => p.light === 'solid')).toBe(true)
      }
    }
  })

  it('passes a net by angle — sharkstooth 0.30, bobbinet 0.15 — hung open as one layer', () => {
    for (const fabric of ['SHARKSTOOTH', 'BOBBINET'] as const) {
      for (const operation of [undefined, 'FLY', 'DEAD']) {
        const cloth = part(buildElement(drape({ fabric, operation })).parts, 'cloth')
        expect(cloth.light).toEqual({ kind: 'angle', r: SCRIM_THREAD_SHARE[fabric], gather: 1 })
      }
    }
    expect(SCRIM_THREAD_SHARE).toEqual({ SHARKSTOOTH: 0.3, BOBBINET: 0.15 })
    // A net keeps its angle painted: its holes are its weave.
    const painted = part(buildElement(drape({ fabric: 'SHARKSTOOTH', paint: { front: FRONT } })).parts, 'cloth')
    expect(painted.light).toMatchObject({ kind: 'angle' })
  })

  it("stacks a drawn net half's layers as it gathers: its fullness, 1 closed", () => {
    for (const open of [0, 0.25, 0.5, 1]) {
      const { parts } = buildElement(drape({ fabric: 'BOBBINET', operation: 'DRAW', states: { open } }))
      const half = drawnHalfWidth(6, open)
      for (const key of ['cloth-sr', 'cloth-sl']) {
        const light = part(parts, key).light
        expect(light).toMatchObject({ kind: 'angle', r: 0.15 })
        expect((light as { gather: number }).gather).toBeCloseTo(3 / half, 9)
      }
    }
    expect((part(buildElement(drape({ fabric: 'SHARKSTOOTH', operation: 'DRAW' })).parts, 'cloth-sr').light as { gather: number }).gather).toBe(1)
  })

  it("cuts a painted cloth by its front image's mask over its own uv, each drawn half its own half", () => {
    for (const fabric of [undefined, 'CANVAS', 'MUSLIN']) {
      const dead = part(buildElement(drape({ fabric, paint: { front: FRONT, back: BACK } })).parts, 'cloth')
      expect(dead.light).toEqual({ kind: 'mask', image: FRONT, uv: FULL_UV })
      const { parts } = buildElement(drape({ fabric, operation: 'DRAW', states: { open: 0.6 }, paint: { front: FRONT } }))
      for (const key of ['cloth-sr', 'cloth-sl']) {
        const p = part(parts, key)
        expect(p.light).toEqual({ kind: 'mask', image: FRONT, uv: p.uv })
      }
    }
  })

  it('cuts a cloth painted on its back only by the back, seen from downstage the other way round', () => {
    const { parts } = buildElement(drape({ operation: 'DRAW', paint: { back: BACK } }))
    expect(part(parts, 'cloth-sr').light).toEqual({ kind: 'mask', image: BACK, uv: { u0: 1, u1: 0.5, v0: 0, v1: 1 } })
    expect(part(parts, 'cloth-sl').light).toEqual({ kind: 'mask', image: BACK, uv: { u0: 0.5, u1: 0, v0: 0, v1: 1 } })
  })

  it("cuts each painted flat piece by its share of the face, and leaves an unpainted flat solid", () => {
    const flat = (params: Record<string, unknown>) =>
      element({ kind: 'FLAT', widthM: 4, depthM: 0.1, heightM: 2, params: { openings: [{ fromM: 1, widthM: 1, heightM: 1.5, sillM: 0 }], ...params } })
    for (const p of buildElement(flat({ paint: { front: FRONT } })).parts) {
      expect(p.light).toEqual({ kind: 'mask', image: FRONT, uv: p.uv })
    }
    expect(buildElement(flat({})).parts.every((p) => p.light === 'solid')).toBe(true)
  })

  it('keeps every other kind solid, the surround drawn only', () => {
    for (const kind of ['ROOM', 'PLATFORM', 'OBJECT', 'PROSCENIUM'] as const) {
      const { parts } = buildElement(element({ kind, widthM: 4, depthM: 2, heightM: 3, params: { openingWidthM: 2, openingHeightM: 2, surroundM: 0.1 } }))
      expect(parts.every((p) => p.light === 'solid' || (kind === 'PROSCENIUM' && p.key.startsWith('surround') && p.light === 'none'))).toBe(true)
    }
  })
})
