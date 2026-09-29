import { describe, expect, it } from 'vitest'
import type { FixturePatch, PatchPlacement } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import { FREE_STANDING_NAME, FREE_STANDING_ROW, positionRows, shortLabel, stageEdgeIndex } from './positionRows'

function rig(uuid: string, name: string, y: number, extra: Partial<RiggingDto> = {}): RiggingDto {
  return {
    id: 0,
    uuid,
    name,
    kind: 'TRUSS',
    positionX: 0,
    positionY: y,
    positionZ: 5,
    yawDeg: 0,
    pitchDeg: 0,
    rollDeg: 0,
    lengthM: 8,
    sortOrder: 0,
    ...extra,
  }
}

let nextId = 1
function patch(key: string, displayName: string, at: Partial<FixturePatch>): FixturePatch {
  return {
    id: nextId++,
    key,
    displayName,
    fixtureTypeKey: 'generic-dimmer',
    startChannel: 1,
    channelCount: 1,
    manufacturer: null,
    model: null,
    modeName: null,
    universe: 0,
    subnet: 0,
    sortOrder: 0,
    groups: [],
    stageX: 0,
    stageY: 0,
    stageZ: 0,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
    ...at,
  }
}

function lantern(uuid: string, riggingUuid: string | null, stageX: number, stageY = 0): PatchPlacement {
  return { uuid, label: null, riggingUuid, stageX, stageY, stageZ: 0, baseYawDeg: null, basePitchDeg: null }
}

const LX1 = rig('lx1', 'LX1', 3.7)
const LX3 = rig('lx3', 'LX3', 8.7)
const ADV2 = rig('adv2', 'ADV2', -3.3)
const EMPTY = rig('lx2', 'LX2', 6.7)

describe('positionRows', () => {
  it('is one row per rigging with units on it, upstage first, and none for an empty rigging', () => {
    const rows = positionRows(
      [
        patch('a', 'ADV2 Front C', { riggingUuid: 'adv2' }),
        patch('b', 'LX1 Fresnel C', { riggingUuid: 'lx1' }),
        patch('c', 'LX3 Robe 575 SR', { riggingUuid: 'lx3' }),
      ],
      [LX1, LX3, ADV2, EMPTY],
    )
    expect(rows.map((r) => [r.name, r.depthM])).toEqual([
      ['LX3', 8.7],
      ['LX1', 3.7],
      ['ADV2', -3.3],
    ])
  })

  it('orders a row’s units by world X, left to right as the desk sees them, and takes the row’s name off each', () => {
    const [row] = positionRows(
      [
        patch('sl', 'LX3 Robe 575 SL', { riggingUuid: 'lx3', stageX: 2 }),
        patch('sr', 'LX3 Robe 575 SR', { riggingUuid: 'lx3', stageX: -2 }),
        patch('c', 'LX3 Centre', { riggingUuid: 'lx3', stageX: 0 }),
      ],
      [LX3],
    )
    expect(row!.chips.map((c) => c.label)).toEqual(['Robe 575 SR', 'Centre', 'Robe 575 SL'])
  })

  it('composes a unit through its rigging’s yaw before ordering it', () => {
    // A bar turned 180°: its own +X runs to the desk's left, so the unit at +2 on the bar is leftmost.
    const turned = rig('t', 'Turned', 5, { yawDeg: 180 })
    const [row] = positionRows(
      [
        patch('p', 'Plus', { riggingUuid: 't', stageX: 2 }),
        patch('m', 'Minus', { riggingUuid: 't', stageX: -2 }),
      ],
      [turned],
    )
    expect(row!.chips.map((c) => c.label)).toEqual(['Plus', 'Minus'])
  })

  it('draws a paired dimmer as one chip per row with its count, and a lantern on another rigging in that row', () => {
    const rows = positionRows(
      [
        patch('front-c', 'Front C', {
          riggingUuid: 'adv2',
          stageX: -0.4,
          extraPlacements: [lantern('two', 'adv2', 0.4), lantern('three', 'lx1', 1)],
        }),
      ],
      [ADV2, LX1],
    )
    const adv2 = rows.find((r) => r.id === 'adv2')!
    expect(adv2.chips).toHaveLength(1)
    expect(adv2.chips[0]).toMatchObject({ count: 2, x: -0.4 })
    const lx1 = rows.find((r) => r.id === 'lx1')!
    expect(lx1.chips[0]).toMatchObject({ count: 1, patch: { key: 'front-c' } })
  })

  it('puts units on no rigging in one free-standing row at their mean depth, and leaves out hidden and unplaced ones', () => {
    const rows = positionRows(
      [
        patch('floor-1', 'Floor 1', { stageY: 1 }),
        patch('floor-2', 'Floor 2', { stageY: 3 }),
        patch('hidden', 'Hidden', { stageY: 2, stageHidden: true }),
        patch('nowhere', 'Nowhere', { stageX: null, stageY: null }),
        patch('b', 'LX1 Fresnel', { riggingUuid: 'lx1' }),
      ],
      [LX1],
    )
    expect(rows.map((r) => r.id)).toEqual(['lx1', FREE_STANDING_ROW])
    const free = rows[1]!
    expect(free).toMatchObject({ name: FREE_STANDING_NAME, kind: null, depthM: 2 })
    expect(free.chips.map((c) => c.patch.key)).toEqual(['floor-1', 'floor-2'])
  })

  it('reads two rows at one depth left to right as the desk sees them, whatever their names', () => {
    const sl = rig('sl', 'Wall SL', -6.8, { positionX: 5.5 })
    const sr = rig('sr', 'Wall SR', -6.8, { positionX: -5.5 })
    const rows = positionRows(
      [patch('a', 'Orbit SL', { riggingUuid: 'sl' }), patch('b', 'Orbit SR', { riggingUuid: 'sr' })],
      [sl, sr],
    )
    expect(rows.map((r) => r.name)).toEqual(['Wall SR', 'Wall SL'])
  })

  it('reads a unit whose rigging is unknown as free-standing, at its own coordinates', () => {
    const [row] = positionRows([patch('x', 'X', { riggingUuid: 'gone', stageY: -1 })], [])
    expect(row).toMatchObject({ id: FREE_STANDING_ROW, depthM: -1 })
  })
})

describe('stageEdgeIndex', () => {
  it('is the first row downstage of Y = 0, or the end when every row is on stage', () => {
    const rows = positionRows(
      [
        patch('a', 'A', { riggingUuid: 'lx3' }),
        patch('b', 'B', { riggingUuid: 'adv2' }),
      ],
      [LX3, ADV2],
    )
    expect(stageEdgeIndex(rows)).toBe(1)
    expect(stageEdgeIndex(rows.slice(0, 1))).toBe(1)
    expect(stageEdgeIndex(rows.slice(1))).toBe(0)
    expect(stageEdgeIndex([])).toBe(0)
  })
})

describe('shortLabel', () => {
  it('takes the row’s name off the front, whatever its case', () => {
    expect(shortLabel('LX3 Robe 575 SR', 'LX3')).toBe('Robe 575 SR')
    expect(shortLabel('lx3 Robe', 'LX3')).toBe('Robe')
  })

  it('keeps a name the row does not start, and one that would be left a bare number', () => {
    expect(shortLabel('Robe 575 SR', 'LX3')).toBe('Robe 575 SR')
    expect(shortLabel('LX3 4', 'LX3')).toBe('LX3 4')
    expect(shortLabel('LX3', 'LX3')).toBe('LX3')
    expect(shortLabel('LX30 Wash', 'LX3')).toBe('LX30 Wash')
    expect(shortLabel('Front', undefined)).toBe('Front')
  })
})
