// @vitest-environment jsdom
//
// jsdom only because store/fixtures imports the API facade, which reads `window` at module load.
import { describe, expect, it } from 'vitest'
import type { Fixture, FixtureTypeInfo } from '../../store/fixtures'
import { MAX_PRISM_LOBES, MAX_WASH_PIXELS } from './emitterLayout'
import { emitterNeedsFor, isPixelStrip } from './emitterNeeds'

function type(over: Partial<FixtureTypeInfo>): FixtureTypeInfo {
  return {
    typeKey: 't',
    manufacturer: null,
    model: null,
    modeName: null,
    channelCount: null,
    isRegistered: true,
    capabilities: [],
    properties: [],
    elementGroupProperties: null,
    ...over,
  }
}

function fixture(over: Partial<Fixture>): Fixture {
  return {
    key: 'f',
    name: 'F',
    typeKey: 't',
    universe: 1,
    firstChannel: 1,
    channelCount: 1,
    channels: [],
    properties: [],
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
    ...over,
  } as Fixture
}

const PRISM = { type: 'setting', category: 'prism', name: 'prism', channel: { universe: 1, channelNo: 5 } }

function pixels(n: number) {
  return {
    type: 'colour',
    name: 'rgbColour',
    memberColourChannels: Array.from({ length: n }, () => ({})),
  }
}

const NO_OVERRIDE = { kindOverride: null }

describe('emitterNeedsFor', () => {
  it('gives a plain beam fixture one lobe and no wash block', () => {
    expect(emitterNeedsFor(NO_OVERRIDE, fixture({}), type({ acceptsBeamAngle: true, kind: 'PAR' }))).toEqual({
      lobes: 1,
      washPixels: 0,
    })
  })

  it('gives a prism fixture the full lobe block', () => {
    const f = fixture({ properties: [PRISM] as unknown as Fixture['properties'] })
    expect(emitterNeedsFor(NO_OVERRIDE, f, type({ acceptsBeamAngle: true, kind: 'MOVING_HEAD' })).lobes).toBe(
      MAX_PRISM_LOBES,
    )
  })

  it('gives a fixture without a beam no lobes — FixtureModel draws no cone for it', () => {
    expect(emitterNeedsFor(NO_OVERRIDE, fixture({}), type({ kind: 'PAR' })).lobes).toBe(0)
    expect(emitterNeedsFor(NO_OVERRIDE, undefined, undefined)).toEqual({ lobes: 0, washPixels: 0 })
  })

  it('gives a pixel strip a wash block of its pixel count, capped', () => {
    const bar = (n: number) =>
      fixture({ elementGroupProperties: [pixels(n)] as unknown as Fixture['elementGroupProperties'] })
    expect(emitterNeedsFor(NO_OVERRIDE, bar(12), type({ kind: 'STRIP' })).washPixels).toBe(12)
    expect(emitterNeedsFor(NO_OVERRIDE, bar(40), type({ kind: 'STRIP' })).washPixels).toBe(MAX_WASH_PIXELS)
    // A patch's kind override decides the body, and so whether it washes per pixel.
    expect(emitterNeedsFor({ kindOverride: 'STRIP' }, bar(8), type({ kind: 'WASH' })).washPixels).toBe(8)
    expect(emitterNeedsFor(NO_OVERRIDE, bar(8), type({ kind: 'WASH' })).washPixels).toBe(0)
  })

  it('matches the body rule: a single-pixel strip is not a pixel strip', () => {
    expect(isPixelStrip('STRIP', 1)).toBe(false)
    expect(isPixelStrip('STRIP', 2)).toBe(true)
    expect(isPixelStrip('PAR', 12)).toBe(false)
  })
})
