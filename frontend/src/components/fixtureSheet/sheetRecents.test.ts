import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { lightingApi } from '@/api/lightingApi'
import { chan } from '@/test/fixtureFactories'
import { recentLevelKeyFor } from '../editor/recentValueSpelling'
import { sheetPositionRecent } from './sheetRecents'
import type { PositionResolution } from './sheetRows'

const head = (pan: number, tilt: number): PositionResolution => ({
  kind: 'position',
  pan: chan(pan),
  tilt: chan(tilt),
  panMin: 0,
  panMax: 255,
  tiltMin: 0,
  tiltMax: 255,
})
const TWO = [
  { key: 'a', resolution: head(1, 2) },
  { key: 'b', resolution: head(3, 4) },
]

/** The wire's bytes, and the programmer's held `position` entries, by head. */
let wire: Map<number, number>
let held: Map<string, string>
beforeEach(() => {
  wire = new Map()
  held = new Map()
  ;(lightingApi as unknown as { channels: Record<string, unknown> }).channels = {
    get: (_universe: number, channelNo: number) => wire.get(channelNo) ?? 0,
  }
  vi.spyOn(lightingApi.programmer, 'getKeyState').mockImplementation(((key: string) => {
    const value = held.get(key)
    return { entry: value == null ? undefined : { targetKey: key, attribute: 'position', value, owner: 'web', touched: true, owners: ['web'] } }
  }) as never)
})

describe('a position recent over a pick (D15)', () => {
  it('is the pair the write resolves once both axes agree across the heads', () => {
    wire = new Map([
      [2, 140],
      [4, 140],
    ])
    expect(sheetPositionRecent(TWO, { kind: 'position', panCentre: -20 })).toBe('pos:-20,12')
  })

  it('is nothing while the axis the commit does not say differs', () => {
    wire = new Map([
      [2, 140],
      [4, 100],
    ])
    expect(sheetPositionRecent(TWO, { kind: 'position', panCentre: -20 })).toBeNull()
  })

  it('takes the untouched axis from the held entry before the wire, as the write does', () => {
    // The wire is mid-fade, or held back in Blind; the programmer holds tilt at 140 on both.
    wire = new Map([
      [2, 30],
      [4, 200],
    ])
    held = new Map([
      ['a', '128,140'],
      ['b', '128,140'],
    ])
    expect(sheetPositionRecent(TWO, { kind: 'position', panCentre: -20 })).toBe('pos:-20,12')
  })

  it('reads a pad’s bytes on each head’s own travel', () => {
    expect(sheetPositionRecent(TWO, () => ({ kind: 'position', pan: 255, tilt: 0 }))).toBe('pos:127,-128')
  })
})

describe('a level’s list', () => {
  it('is named for the attribute, and absent for a pan or a tilt', () => {
    expect(recentLevelKeyFor({ name: 'zoom', category: 'zoom' })).toBe('level:zoom')
    expect(recentLevelKeyFor({ name: 'pan', category: 'pan' })).toBeNull()
    expect(recentLevelKeyFor({ name: 'tiltFine', category: 'tilt_fine' })).toBeNull()
  })
})
