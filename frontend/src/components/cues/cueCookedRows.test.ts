import { describe, expect, it, vi } from 'vitest'

// `parseProgrammerValue` reaches colourUtils, which reaches the api barrel.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { buildStaticRows } from './cueCookedRows'
import { lookRowKey } from '@/components/programmer/lookRowKey'
import type { CookedRow } from '@/api/cuesApi'

const row = (over: Partial<CookedRow>): CookedRow => ({
  targetType: 'fixture',
  targetKey: 'a',
  propertyName: 'dimmer',
  value: '128',
  ...over,
})

const FIXTURES = [
  { key: 'a', groups: ['Washes'] },
  { key: 'b', groups: ['Washes'] },
  { key: 'c', groups: [] },
]

describe('buildStaticRows', () => {
  it('reads a fixture row straight through', () => {
    const built = buildStaticRows([row({})], FIXTURES, true)
    expect(built.rows.get(lookRowKey('a', 'dimmer'))).toEqual({ kind: 'level', value: 128 })
  })

  it('expands a group row onto its members and no further', () => {
    const built = buildStaticRows([row({ targetType: 'group', targetKey: 'Washes' })], FIXTURES, true)
    expect(built.rows.get(lookRowKey('a', 'dimmer'))).toBeTruthy()
    expect(built.rows.get(lookRowKey('b', 'dimmer'))).toBeTruthy()
    expect(built.rows.get(lookRowKey('c', 'dimmer'))).toBeUndefined()
  })

  it('lets a fixture row beat the group row it overlaps', () => {
    // The specificity the backend applies. If the display got this backwards, a cell would show
    // the group's value for a head the cue overrode.
    const built = buildStaticRows(
      [row({ targetType: 'group', targetKey: 'Washes', value: '10' }), row({ value: '200' })],
      FIXTURES,
      true,
    )
    expect(built.rows.get(lookRowKey('a', 'dimmer'))).toEqual({ kind: 'level', value: 200 })
    expect(built.rows.get(lookRowKey('b', 'dimmer'))).toEqual({ kind: 'level', value: 10 })
  })

  it('names the winning layer, and clears it where the cue overrode one', () => {
    // A cue's own assignment beating a layer must also drop the attribution — otherwise the cell
    // credits a look for a value the operator set on the cue itself.
    const built = buildStaticRows(
      [
        row({ targetType: 'group', targetKey: 'Washes', layerId: 4, layerSource: { kind: 'LOOK', id: 4, uuid: 'u4', name: 'Warm Wash' } }),
        row({ targetKey: 'a', value: '200' }),
      ],
      FIXTURES,
      true,
    )
    expect(built.layerByKey.get(lookRowKey('a', 'dimmer'))).toBeUndefined()
    expect(built.layerByKey.get(lookRowKey('b', 'dimmer'))).toMatchObject({ name: 'Warm Wash' })
  })

  it('parses the three value shapes', () => {
    const built = buildStaticRows(
      [
        row({ propertyName: 'rgbColour', value: '#ff8800' }),
        row({ propertyName: 'position', value: '40,60' }),
        row({ propertyName: 'dimmer', value: '0' }),
      ],
      FIXTURES,
      true,
    )
    expect(built.rows.get(lookRowKey('a', 'rgbColour'))).toMatchObject({ kind: 'colour', r: 255 })
    expect(built.rows.get(lookRowKey('a', 'position'))).toEqual({
      kind: 'position',
      pan: 40,
      tilt: 60,
    })
    // A cue asserting zero is asserting something; it must not be mistaken for absence.
    expect(built.rows.get(lookRowKey('a', 'dimmer'))).toEqual({ kind: 'level', value: 0 })
  })

  it('skips a value nothing can read rather than guessing at one', () => {
    const built = buildStaticRows([row({ value: 'gobo-ish' })], FIXTURES, true)
    expect(built.rows.size).toBe(0)
  })

  it('handles a head whose name contains a space', () => {
    const built = buildStaticRows([row({ targetKey: 'Front Left' })], [{ key: 'Front Left', groups: [] }], true)
    expect(built.rows.get(lookRowKey('Front Left', 'dimmer'))).toBeTruthy()
  })
})
