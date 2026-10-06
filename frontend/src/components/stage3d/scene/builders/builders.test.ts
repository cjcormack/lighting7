import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../../../api/stageElementApi'
import { seatBase, seatingParams } from '../../../../lib/stageSeats'
import { buildElement } from '.'
import { DRAWN_GATHER, drawnHalfWidth } from './drape'
import { ROOM_FACE_INSET_M } from './room'
import { elementBaseZ, FINISH_LOBES, type ScenePart } from '../sceneParts'

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
    const solid = parts.filter((p) => p.collides)
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
    expect(parts.filter((p) => !p.collides).map((p) => p.key).sort()).toEqual(['surround-head', 'surround-sl', 'surround-sr'])
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
