import { describe, expect, it, vi } from 'vitest'

// useGroupPropertyValues reaches usePropertyValues, which opens a WebSocket at import time —
// replace the API before anything imports it.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { computeGroupColourValues } from './useGroupPropertyValues'
import { aggregateCellValue } from '@/components/fixtures-list/useRowValues'
import { chan, colourProp } from '@/test/fixtureFactories'
import type { ChannelSource } from '@/api/channelSource'
import type { CellResolution } from '@/components/fixtures-list/columns'
import type { ChannelRef } from '@/store/fixtures'
import type { GroupColourPropertyDescriptor } from '@/api/groupsApi'

const keyOf = (ref: ChannelRef) => `${ref.universe}:${ref.channelNo}`

/** A [ChannelSource] over a plain `{ 'universe:channelNo': value }` table. */
const sourceOver = (values: Record<string, number>): ChannelSource => ({
  get: (universe, channelNo) => values[`${universe}:${channelNo}`] ?? 0,
  getByKey: (key) => values[key] ?? 0,
  subscribeToChannel: () => ({ unsubscribe: () => {} }),
})

const readerOver = (values: Record<string, number>) => (ref: ChannelRef) =>
  values[keyOf(ref)] ?? 0

/** Two RGBW heads at full white beside two RGB-only heads. */
const MIXED_EMITTERS: GroupColourPropertyDescriptor = {
  type: 'colour',
  name: 'rgbColour',
  displayName: 'Colour',
  category: 'colour',
  memberColourChannels: [
    { fixtureKey: 'rgbw-1', redChannel: chan(1), greenChannel: chan(2), blueChannel: chan(3), whiteChannel: chan(4) },
    { fixtureKey: 'rgbw-2', redChannel: chan(5), greenChannel: chan(6), blueChannel: chan(7), whiteChannel: chan(8) },
    { fixtureKey: 'rgb-1', redChannel: chan(9), greenChannel: chan(10), blueChannel: chan(11) },
    { fixtureKey: 'rgb-2', redChannel: chan(12), greenChannel: chan(13), blueChannel: chan(14) },
  ],
}

const MIXED_VALUES: Record<string, number> = {
  '0:4': 255,
  '0:8': 255,
}

/** The same four heads as fixtures-list resolutions — what a group *row* resolves to. */
const mixedResolutions: NonNullable<CellResolution>[] = [
  { kind: 'colour', property: colourProp('rgbColour', chan(1), chan(2), chan(3), { whiteChannel: chan(4) }) },
  { kind: 'colour', property: colourProp('rgbColour', chan(5), chan(6), chan(7), { whiteChannel: chan(8) }) },
  { kind: 'colour', property: colourProp('rgbColour', chan(9), chan(10), chan(11)) },
  { kind: 'colour', property: colourProp('rgbColour', chan(12), chan(13), chan(14)) },
]

describe('group colour aggregation', () => {
  it('averages white over the heads that have a white emitter, not over every member', () => {
    const group = computeGroupColourValues(MIXED_EMITTERS, sourceOver(MIXED_VALUES))

    // Both white emitters are at full, so the group is throwing full white — the two RGB heads
    // have no opinion about white and must not halve it.
    expect(group.avgW).toBe(255)
  })

  it('reports the same swatch as the fixtures table for the same heads', () => {
    const group = computeGroupColourValues(MIXED_EMITTERS, sourceOver(MIXED_VALUES))
    const cell = aggregateCellValue(mixedResolutions, readerOver(MIXED_VALUES))

    // The one aggregation, read through two surfaces. These used to be two implementations,
    // and this is the case where they disagreed.
    expect(cell?.kind).toBe('colour')
    if (cell?.kind !== 'colour') return
    expect({
      r: group.avgR,
      g: group.avgG,
      b: group.avgB,
      w: group.avgW,
      a: group.avgA,
      uv: group.avgUv,
      isUniform: group.isUniform,
      combinedCss: group.combinedCss,
    }).toEqual({
      r: cell.r,
      g: cell.g,
      b: cell.b,
      w: cell.w,
      a: cell.a,
      uv: cell.uv,
      isUniform: cell.isUniform,
      combinedCss: cell.combinedCss,
    })
  })

  it('leaves an emitter undefined when no member has it', () => {
    const group = computeGroupColourValues(MIXED_EMITTERS, sourceOver(MIXED_VALUES))
    expect(group.avgA).toBeUndefined()
    expect(group.avgUv).toBeUndefined()
  })

  it('keeps the beam derivation separate from the swatch', () => {
    // One pixel at full red on an otherwise dark bar. The swatch is the mean (dim red); the beam
    // is intensity-weighted hue at peak-blended level, so the bar still reads as red and lit.
    const values = { '0:1': 255 }
    const group = computeGroupColourValues(MIXED_EMITTERS, sourceOver(values))

    expect(group.avgR).toBe(64)
    expect(group.beamR).toBe(255)
    expect(group.beamIntensity).toBeGreaterThan(0.5)
  })

  it('returns an identity-stable snapshot for a memberless group', () => {
    const empty: GroupColourPropertyDescriptor = { ...MIXED_EMITTERS, memberColourChannels: [] }
    expect(computeGroupColourValues(empty)).toBe(computeGroupColourValues(empty))
  })
})
