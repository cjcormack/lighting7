import { describe, expect, it, vi } from 'vitest'
import type { StageRegionDto } from '@/api/stageRegionApi'
import type { StageElementDto } from '@/api/stageElementApi'
import {
  createPlanAimClicks,
  floorUnder,
  HEAD_HEIGHT_M,
  houseFloorOf,
  planAimPoint,
  planAimTakesPointer,
  planPointOf,
  regionUnder,
  roomFootprint,
  skipsByReason,
} from './planAim'

/** Aim from the floor plan (venue-visit plan S13): a Plan click's point and height, and the skips by reason. */

const region = (over: Partial<StageRegionDto>): StageRegionDto => ({
  id: 1, uuid: 'r', name: 'Deck', centerX: 0, centerY: 2, centerZ: 0,
  widthM: 4, depthM: 2, heightM: 1, yawDeg: null, sortOrder: 0, ...over,
})

describe('planPointOf', () => {
  it('reads the Plan section in stage metres: h is X, v is downstage', () => {
    expect(planPointOf({ h: 2, v: -3 })).toEqual({ x: 2, y: 3 })
    expect(planPointOf({ h: -1.5, v: 12 })).toEqual({ x: -1.5, y: -12 })
    expect(Object.is(planPointOf({ h: 0, v: 0 }).y, 0)).toBe(true)
  })
})

describe('regionUnder', () => {
  it('finds the region whose footprint holds the point, its yaw included', () => {
    const deck = region({})
    expect(regionUnder([deck], { x: 1.9, y: 2.9 })).toBe(deck)
    expect(regionUnder([deck], { x: 2.1, y: 2 })).toBeNull()
    // Turned a quarter: 4 m up and down stage, 2 m across.
    const turned = region({ uuid: 't', yawDeg: 90 })
    expect(regionUnder([turned], { x: 0, y: 3.9 })).toBe(turned)
    expect(regionUnder([turned], { x: 1.5, y: 2 })).toBeNull()
  })

  it('takes the highest top where regions overlap, and never one off the stage', () => {
    const deck = region({ uuid: 'd', name: 'Deck', widthM: 10, depthM: 10 })
    const rostrum = region({ uuid: 'r', name: 'Rostrum', centerZ: 0.6, widthM: 2, depthM: 2 })
    expect(regionUnder([rostrum, deck], { x: 0, y: 2 })?.name).toBe('Rostrum')
    expect(regionUnder([deck, rostrum], { x: 0, y: 2 })?.name).toBe('Rostrum')
    expect(regionUnder([region({ centerX: null })], { x: 0, y: 2 })).toBeNull()
  })
})

describe('planAimPoint', () => {
  const rostrum = region({ name: 'Rostrum', centerZ: 0.6 })

  it('is head height above the region under the click', () => {
    expect(planAimPoint({ x: 1, y: 2 }, 'surface', 4, [rostrum])).toEqual({
      point: { x: 1, y: 2, z: 2.3 },
      region: rostrum,
    })
  })

  it('is head height above the deck off every region — the house included', () => {
    expect(planAimPoint({ x: 3, y: -14 }, 'surface', 4, [rostrum])).toEqual({
      point: { x: 3, y: -14, z: HEAD_HEIGHT_M },
      region: null,
    })
  })

  it('is head height above the house floor where the click is off the stage', () => {
    expect(planAimPoint({ x: 3, y: -14 }, 'surface', 4, [rostrum], -1).point.z).toBeCloseTo(0.7, 9)
  })

  it('is the typed Z on At Z, wherever the click lands', () => {
    expect(planAimPoint({ x: 1, y: 2 }, 'z', 3.25, [rostrum])).toEqual({ point: { x: 1, y: 2, z: 3.25 }, region: null })
  })
})

describe('skipsByReason', () => {
  it('is one line per reason, the heads in the desk’s order', () => {
    const skip = (key: string, reason: string) => ({ target: { type: 'fixture' as const, key }, reason })
    expect(
      skipsByReason(
        [skip('par-1', 'fixed head — no pan or tilt'), skip('spot-3', 'outside its travel'), skip('par-2', 'fixed head — no pan or tilt')],
        (key) => key.toUpperCase(),
      ),
    ).toEqual([
      { reason: 'fixed head — no pan or tilt', names: ['PAR-1', 'PAR-2'] },
      { reason: 'outside its travel', names: ['SPOT-3'] },
    ])
  })
})

describe('createPlanAimClicks', () => {
  it('hands a click to every listener until it lets go', () => {
    const clicks = createPlanAimClicks()
    const heard = vi.fn()
    const stop = clicks.subscribe(heard)
    clicks.emit({ x: 1, y: 2 })
    stop()
    clicks.emit({ x: 3, y: 4 })
    expect(heard.mock.calls).toEqual([[{ x: 1, y: 2 }]])
  })
})

describe('planAimTakesPointer', () => {
  const armedOnPlan = { armed: true, camera: 'plan', editMode: false, placing: false, capturing: false, seatPicking: false }

  it('takes the pointer only while armed on the Plan in view mode', () => {
    expect(planAimTakesPointer(armedOnPlan)).toBe(true)
  })

  it('leaves the fixture click and the scenery popover alone otherwise', () => {
    expect(planAimTakesPointer({ ...armedOnPlan, armed: false })).toBe(false)
    expect(planAimTakesPointer({ ...armedOnPlan, camera: 'orbit' })).toBe(false)
    expect(planAimTakesPointer({ ...armedOnPlan, camera: 'front' })).toBe(false)
    // Edit wins: the section edit layer keeps the pointer.
    expect(planAimTakesPointer({ ...armedOnPlan, editMode: true })).toBe(false)
    expect(planAimTakesPointer({ ...armedOnPlan, placing: true })).toBe(false)
    expect(planAimTakesPointer({ ...armedOnPlan, seatPicking: true })).toBe(false)
    expect(planAimTakesPointer({ ...armedOnPlan, capturing: true })).toBe(false)
  })
})

describe('floorUnder and houseFloorOf', () => {
  const room = (over: Partial<StageElementDto>) => ({ kind: 'ROOM', hidden: false, positionZ: -1, ...over }) as StageElementDto

  it('is the deck on the stage’s footprint and the house floor off it', () => {
    const stage = { width: 10, depth: 8 }
    expect(floorUnder({ x: 4.9, y: 7.9 }, stage, -1)).toBe(0)
    expect(floorUnder({ x: 0, y: -0.1 }, stage, -1)).toBe(-1)
    expect(floorUnder({ x: 5.1, y: 4 }, stage, -1)).toBe(-1)
  })

  it('is the lowest shown room’s floor, else the deck', () => {
    expect(houseFloorOf([room({}), room({ positionZ: -1.4 }), room({ positionZ: -3, hidden: true })])).toBe(-1.4)
    expect(houseFloorOf([room({ kind: 'FLAT' })])).toBe(0)
    expect(houseFloorOf([])).toBe(0)
  })
})

describe('roomFootprint', () => {
  const room = (over: Partial<StageElementDto>) =>
    ({ kind: 'ROOM', positionX: 0, positionY: -6, positionZ: -1, yawDeg: 0, widthM: 30, depthM: 43, ...over }) as StageElementDto

  it('is the room’s own turned box, not a bounding circle of its parts', () => {
    expect(roomFootprint([room({})])).toEqual({ min: { x: -15, y: -27.5, z: -1 }, max: { x: 15, y: 15.5, z: -1 } })
    const turned = roomFootprint([room({ positionY: 0, yawDeg: 90, widthM: 4, depthM: 2 })])!
    expect(turned.min.x).toBeCloseTo(-1, 9)
    expect(turned.max.x).toBeCloseTo(1, 9)
    expect(turned.min.y).toBeCloseTo(-2, 9)
    expect(turned.max.y).toBeCloseTo(2, 9)
  })

  it('is null where no room is drawn — a subset canvas, the Venue layer off', () => {
    expect(roomFootprint([room({ kind: 'DRAPE' })])).toBeNull()
    expect(roomFootprint([])).toBeNull()
  })
})
