import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../api/stageElementApi'
import { sceneElementBox } from './StageSceneBoxes'

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'VENUE',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

describe('a scene element drawn as a box (stage-view plan session 2)', () => {
  it('stands on its base, except a platform, which hangs down from its top surface', () => {
    expect(sceneElementBox(element({ positionZ: -0.95, heightM: 4.55 }))!.z).toBeCloseTo(-0.95 + 4.55 / 2, 9)
    const deck = sceneElementBox(element({ kind: 'PLATFORM', positionZ: 0, heightM: 0.95 }))!
    expect(deck.z).toBeCloseTo(-0.95 / 2, 9)
  })

  it('draws a room as its edges alone, a flown piece at its trim, and nothing hidden or switched off', () => {
    expect(sceneElementBox(element({ kind: 'ROOM' }))!.edgesOnly).toBe(true)
    const moon = sceneElementBox(element({ positionZ: 1.7, heightM: 0.6, params: { flies: true, states: { trimM: 5.2 } } }))!
    expect(moon.z).toBeCloseTo(5.2 + 0.3, 9)
    expect(sceneElementBox(element({ hidden: true }))).toBeNull()
    expect(sceneElementBox(element({ params: { states: { visible: false } } }))).toBeNull()
  })

  it('boxes a seating block from row A back, turned with it', () => {
    const stalls = element({
      kind: 'SEATING', positionY: -2.4, positionZ: -0.95, widthM: 0, depthM: 0, heightM: 0,
      params: { rows: 12, seatsPerRow: 12, rowPitchM: 0.95, seatPitchM: 0.52 },
    })
    const box = sceneElementBox(stalls)!
    expect(box.w).toBeCloseTo(12 * 0.52, 9)
    expect(box.d).toBeCloseTo(12 * 0.95, 9)
    expect(box.y).toBeCloseTo(-2.4 - 5.5 * 0.95, 9)
    // A bank that steps down 0.3 m a row: its box reaches down to the last row's seats.
    const sunken = sceneElementBox({ ...stalls, params: { ...stalls.params, rows: 4, rakeM: -0.3 } })!
    expect(sunken.z - sunken.h / 2).toBeCloseTo(-0.95 - 0.9, 9)
    expect(sunken.z + sunken.h / 2).toBeCloseTo(-0.95 + 0.9, 9)
    const turned = sceneElementBox({ ...stalls, positionY: 0, yawDeg: 90 })!
    expect(turned.x).toBeCloseTo(5.5 * 0.95, 9)
    expect(turned.y).toBeCloseTo(0, 9)
  })
})
