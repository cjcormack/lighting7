// @vitest-environment jsdom
//
// jsdom only because store/fixtures imports the API facade, which reads `window` at module load.
import { describe, expect, it } from 'vitest'
import type { Fixture, FixtureTypeInfo } from '../../store/fixtures'
import { MAX_PRISM_LOBES } from './emitterLayout'
import { bodySpecOf, emitterNeedsForSpec } from './emitterNeeds'

function emitterNeedsFor(
  patch: { kindOverride: string | null; lengthM: number | null },
  fixture: Fixture | undefined,
  fixtureType: FixtureTypeInfo | undefined,
) {
  return emitterNeedsForSpec(bodySpecOf(patch, fixture, fixtureType), fixture)
}
import { chan, colourProp, element } from '../../test/fixtureFactories'

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

/** A bar of `n` RGB cells, each an element with a colour of its own. */
function bar(n: number): Fixture {
  return fixture({
    elements: Array.from({ length: n }, (_, i) =>
      element(i, `f.cell-${i}`, [colourProp('rgbColour', chan(10 + i * 3), chan(11 + i * 3), chan(12 + i * 3))]),
    ),
  })
}

const NO_OVERRIDE = { kindOverride: null, lengthM: null }

describe('emitterNeedsFor', () => {
  it('gives a plain beam fixture one lobe and one light', () => {
    expect(emitterNeedsFor(NO_OVERRIDE, fixture({}), type({ acceptsBeamAngle: true, kind: 'PAR' }))).toEqual({
      lobes: 1,
      lights: 1,
    })
  })

  it('gives a prism fixture the full lobe block, each lobe its own light', () => {
    const f = fixture({ properties: [PRISM] as unknown as Fixture['properties'] })
    expect(emitterNeedsFor(NO_OVERRIDE, f, type({ acceptsBeamAngle: true, kind: 'MOVING_HEAD' }))).toEqual({
      lobes: MAX_PRISM_LOBES,
      lights: MAX_PRISM_LOBES,
    })
  })

  it('gives a fixture without a beam nothing — FixtureModel draws no beam for it', () => {
    expect(emitterNeedsFor(NO_OVERRIDE, fixture({}), type({ kind: 'PAR' }))).toEqual({ lobes: 0, lights: 0 })
    expect(emitterNeedsFor(NO_OVERRIDE, undefined, undefined)).toEqual({ lobes: 0, lights: 0 })
  })

  it('gives a batten a lobe per cell and at most four lights, averaging runs of cells', () => {
    expect(emitterNeedsFor(NO_OVERRIDE, bar(3), type({ kind: 'STRIP' }))).toEqual({ lobes: 3, lights: 3 })
    expect(emitterNeedsFor(NO_OVERRIDE, bar(12), type({ kind: 'STRIP' }))).toEqual({ lobes: 12, lights: 4 })
    // A patch's kind override decides the body, and so how its cells are drawn.
    expect(emitterNeedsFor({ kindOverride: 'STRIP', lengthM: null }, bar(8), type({ kind: 'WASH' }))).toEqual({
      lobes: 8,
      lights: 4,
    })
  })

  it('draws a strip of tape, which takes its length per install, as a glow with no beam', () => {
    expect(emitterNeedsFor(NO_OVERRIDE, fixture({}), type({ kind: 'STRIP', acceptsLength: true }))).toEqual({
      lobes: 0,
      lights: 0,
    })
  })
})
