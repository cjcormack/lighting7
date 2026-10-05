// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

/**
 * The emitter expansion: a colour descriptor offers the colour **and** one slider per bundled
 * emitter its channels say the head has, because the fixture descriptor list folds `white` /
 * `amber` / `uv` into the colour rather than listing them — while the desk drives each as a slider
 * by that name. The names are the categories; lighting7's `BundledEmitterNamesTest` pins that end.
 */

const colour = (over: Record<string, unknown> = {}) => ({
  type: 'colour',
  name: 'rgbColour',
  displayName: 'colour',
  category: 'colour',
  redChannel: { universe: 0, channelNo: 2 },
  greenChannel: { universe: 0, channelNo: 3 },
  blueChannel: { universe: 0, channelNo: 4 },
  ...over,
})

const fixtures = [
  {
    key: 'hex-1',
    name: 'Hex 1',
    properties: [
      { type: 'slider', name: 'dimmer', displayName: 'dimmer', category: 'dimmer', channel: { universe: 0, channelNo: 1 }, min: 0, max: 255 },
      colour({ whiteChannel: { universe: 0, channelNo: 6 }, uvChannel: { universe: 0, channelNo: 7 } }),
      { type: 'slider', name: 'strobe', displayName: 'strobe', category: 'strobe', channel: { universe: 0, channelNo: 8 }, min: 0, max: 255 },
    ],
  },
  // An RGB-only head: its colour brings no emitters with it.
  { key: 'par-1', name: 'PAR 1', properties: [colour()] },
  // A head with a fixture command and a cannon with a trigger: both are listed with the properties
  // and neither is one, so no picker offers them (fixture optics session 7).
  {
    key: 'rev-1',
    name: 'Rev 1',
    properties: [
      { type: 'slider', name: 'dimmer', displayName: 'dimmer', category: 'dimmer', channel: { universe: 0, channelNo: 20 }, min: 0, max: 255 },
      { type: 'command', name: 'reset', displayName: 'Reset fixture', category: 'command', description: '', holdMs: 3000, confirm: true, channel: { universe: 0, channelNo: 31 }, dedicated: true },
      { type: 'trigger', name: 'output1', displayName: 'Tube A', category: 'trigger', label: 'A', channel: { universe: 0, channelNo: 32 }, armChannel: { universe: 0, channelNo: 33 }, armName: 'master' },
    ],
  },
  // Infrastructure, with a property no lighting head has: a target that names it still resolves
  // it, but the rig-wide vocabulary never offers its `fan`.
  {
    key: 'hazer-power',
    name: 'Hazer power',
    infrastructure: true,
    properties: [{ type: 'slider', name: 'fan', displayName: 'fan', category: 'other', channel: { universe: 0, channelNo: 40 }, min: 0, max: 255 }],
  },
]

const groupProperties = [
  {
    type: 'colour',
    name: 'rgbColour',
    displayName: 'colour',
    category: 'colour',
    memberColourChannels: [
      { fixtureKey: 'hex-1', redChannel: { universe: 0, channelNo: 2 }, greenChannel: { universe: 0, channelNo: 3 }, blueChannel: { universe: 0, channelNo: 4 }, amberChannel: { universe: 0, channelNo: 5 } },
      { fixtureKey: 'par-1', redChannel: { universe: 0, channelNo: 12 }, greenChannel: { universe: 0, channelNo: 13 }, blueChannel: { universe: 0, channelNo: 14 } },
    ],
  },
]

vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: fixtures }),
  useVisibleFixtureListQuery: () => ({ data: fixtures.filter((f) => !f.infrastructure) }),
}))
vi.mock('@/store/groups', () => ({ useGroupPropertiesQuery: () => ({ data: groupProperties }) }))

import { useRigProperties, useTargetProperties } from './useTargetProperties'

describe('useTargetProperties', () => {
  it('offers a colour with the emitters its channels declare, in colour order', () => {
    const { result } = renderHook(() => useTargetProperties({ type: 'fixture', key: 'hex-1' }))
    expect(result.current.properties.map((p) => p.name)).toEqual([
      'dimmer',
      'rgbColour',
      'white',
      'uv',
      'strobe',
    ])
    const white = result.current.properties.find((p) => p.name === 'white')
    expect(white).toMatchObject({ type: 'slider', category: 'white', continuous: true, displayName: 'white' })
  })

  it('offers no emitter an RGB-only head does not have', () => {
    const { result } = renderHook(() => useTargetProperties({ type: 'fixture', key: 'par-1' }))
    expect(result.current.properties.map((p) => p.name)).toEqual(['rgbColour'])
  })

  it('offers neither a fixture command nor a one-shot trigger', () => {
    const { result } = renderHook(() => useTargetProperties({ type: 'fixture', key: 'rev-1' }))
    expect(result.current.properties.map((p) => p.name)).toEqual(['dimmer'])
  })

  it('offers a group the emitter any member has', () => {
    const { result } = renderHook(() => useTargetProperties({ type: 'group', key: 'wash' }))
    expect(result.current.properties.map((p) => p.name)).toEqual(['rgbColour', 'amber'])
  })
})

describe('useRigProperties', () => {
  it('is the union of the patch, emitters included and deduplicated by name', () => {
    const { result } = renderHook(() => useRigProperties())
    expect(result.current.map((p) => p.name)).toEqual(['dimmer', 'rgbColour', 'white', 'uv', 'strobe'])
  })

  it('leaves out a property only an infrastructure fixture has, which a named target still resolves', () => {
    expect(renderHook(() => useRigProperties()).result.current.map((p) => p.name)).not.toContain('fan')
    const named = renderHook(() => useTargetProperties({ type: 'fixture', key: 'hazer-power' })).result.current
    expect(named.properties.map((p) => p.name)).toEqual(['fan'])
  })
})
