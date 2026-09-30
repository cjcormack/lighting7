// @vitest-environment jsdom
//
// jsdom only because store/fixtures imports the API facade, which reads `window` at module load.
import { describe, expect, it } from 'vitest'
import type { Fixture, FixtureTypeInfo, PropertyDescriptor } from '../../../store/fixtures'
import { chan, colourProp, element, sliderProp } from '../../../test/fixtureFactories'
import { bodySpecOf } from '../emitterNeeds'
import { apexDistanceM, archetypeFor, bodyInputFor, lightRuns, MAX_CELLS, SOFTNESS, type BodyInput } from './archetype'

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
    universe: 0,
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

function input(over: Partial<BodyInput>): BodyInput {
  return {
    kind: 'GENERIC',
    typeKey: 't',
    typeName: '',
    hasTilt: false,
    colourElements: [],
    acceptsBeamAngle: true,
    acceptsLength: false,
    beamEdge: undefined,
    lengthM: null,
    widthM: null,
    heightM: null,
    ...over,
  }
}

const TILT = sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 })

/**
 * The Kam Liteobar 252 as the desk sends it: a master dimmer and strobe, then three RGB cells as
 * elements (`KamLiteobar252Fixture.kt:117`) — not twelve.
 */
function liteobar(): { fixture: Fixture; type: FixtureTypeInfo } {
  const cells = [0, 1, 2].map((i) =>
    element(i, `liteobar.cell-${i}`, [colourProp('rgbColour', chan(3 + i * 3), chan(4 + i * 3), chan(5 + i * 3))]),
  )
  return {
    fixture: fixture({
      typeKey: 'kam-liteobar-252-11ch',
      properties: [sliderProp('dimmer', 'dimmer', chan(1)), sliderProp('strobe', 'strobe', chan(2))],
      elements: cells,
    }),
    type: type({ typeKey: 'kam-liteobar-252-11ch', kind: 'STRIP', lengthM: 1, widthM: 0.08, heightM: 0.08 }),
  }
}

/** The 2-cell LED blinder: its cells are warm and cold white sliders, not colours. */
function blinder(): { fixture: Fixture; type: FixtureTypeInfo } {
  const cell = (i: number): PropertyDescriptor[] => [
    sliderProp('warmWhite', 'white', chan(2 + i * 2), { displayName: 'Warm white' }),
    sliderProp('coldWhite', 'white', chan(3 + i * 2), { displayName: 'Cold white' }),
  ]
  return {
    fixture: fixture({
      typeKey: 'china-2-cell-led-blinder-8ch',
      properties: [sliderProp('dimmer', 'dimmer', chan(1))],
      elements: [element(0, 'b.cell-0', cell(0)), element(1, 'b.cell-1', cell(1))],
    }),
    type: type({ typeKey: 'china-2-cell-led-blinder-8ch', kind: 'BLINDER', lengthM: 0.3, widthM: 0.3, heightM: 0.15 }),
  }
}

describe('the archetype a fixture is drawn as', () => {
  it('follows the kind, the patch override first', () => {
    const patch = { kindOverride: 'FRESNEL' as const, lengthM: null }
    const f = fixture({ typeKey: 'generic-dimmer' })
    expect(bodySpecOf(patch, f, type({ typeKey: 'generic-dimmer', kind: 'GENERIC', acceptsBeamAngle: true })).archetype).toBe(
      'fresnel',
    )
    expect(archetypeFor(input({ kind: 'PROFILE' })).archetype).toBe('profile')
    expect(archetypeFor(input({ kind: 'PAR' })).archetype).toBe('par')
    expect(archetypeFor(input({ kind: 'WASH' })).archetype).toBe('flood')
    expect(archetypeFor(input({ kind: 'GENERIC' })).archetype).toBe('downlight')
    expect(archetypeFor(input({ kind: 'BLINDER' })).archetype).toBe('blinder')
    expect(archetypeFor(input({ kind: 'EFFECT' })).archetype).toBe('effect')
    expect(archetypeFor(input({ kind: 'LASER' })).archetype).toBe('effect')
  })

  it('makes anything that tilts a mover — a Source Four Revolution is a profile that tilts', () => {
    expect(archetypeFor(input({ kind: 'PROFILE', hasTilt: true }))).toEqual({ archetype: 'mover', head: 'profile' })
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', typeKey: 'imgstageline-wash-42led-13ch' }))).toEqual({
      archetype: 'mover',
      head: 'wash',
    })
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', typeKey: 'robe-color-spot-575-mode-2' })).head).toBe('spot')
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', typeKey: 'martin-mac-250-mode-4' })).head).toBe('spot')
    // Nothing in the words: the type's beam edge decides.
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', beamEdge: 'SOFT' })).head).toBe('wash')
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', beamEdge: 'HARD' })).head).toBe('spot')
    // A mover of several coloured heads is a bar of them.
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', colourElements: [0, 1, 2, 3] })).head).toBe('bar')
  })

  it('reads the tilt off the fixture itself', () => {
    const spec = bodySpecOf(
      { kindOverride: null, lengthM: null },
      fixture({ properties: [TILT] }),
      type({ kind: 'PROFILE', acceptsBeamAngle: true }),
    )
    expect(spec.archetype).toBe('mover')
    expect(spec.emitAxis).toBe(1)
  })

  it('draws a Twin Shot as the cannon, and a strip that takes its length as tape', () => {
    expect(archetypeFor(input({ kind: 'EFFECT', typeKey: 'equinox-twin-shot-mkii' })).archetype).toBe('cannon')
    expect(archetypeFor(input({ kind: 'STRIP', acceptsLength: true })).archetype).toBe('tape')
    expect(archetypeFor(input({ kind: 'STRIP' })).archetype).toBe('batten')
  })

  it('takes edge softness from the family: profiles and spots hard, everything else soft', () => {
    expect(SOFTNESS.profile).toBeLessThan(0.3)
    expect(SOFTNESS['mover:spot']).toBeLessThan(0.3)
    for (const soft of ['fresnel', 'par', 'flood', 'batten', 'blinder', 'mover:wash'] as const) {
      expect(SOFTNESS[soft]).toBeGreaterThan(0.5)
    }
  })
})

describe('cells', () => {
  it('gives the Liteobar three segments, one per element, not twelve', () => {
    const { fixture: f, type: t } = liteobar()
    const spec = bodySpecOf({ kindOverride: null, lengthM: null }, f, t)
    expect(spec.archetype).toBe('batten')
    expect(spec.cells).toHaveLength(3)
    expect(spec.cells.map((c) => c.element)).toEqual([0, 1, 2])
    expect(spec.cells.every((c) => c.shape === 'segment')).toBe(true)
    // Laid evenly across the bar, left to right, on the face that emits.
    expect(spec.cells[0].x).toBeLessThan(spec.cells[1].x)
    expect(spec.cells[1].x).toBeCloseTo(0, 9)
    expect(spec.cells[0].y).toBeCloseTo(-0.04, 9)
    // A batten throws beams though its type has no beam angle to set.
    expect(spec.emits).toBe(true)
  })

  it('gives the 2-cell blinder two discs, from white-only elements', () => {
    const { fixture: f, type: t } = blinder()
    const spec = bodySpecOf({ kindOverride: null, lengthM: null }, f, t)
    expect(spec.archetype).toBe('blinder')
    expect(spec.cells.map((c) => [c.shape, c.element])).toEqual([
      ['disc', 0],
      ['disc', 1],
    ])
    expect(spec.softness).toBeGreaterThan(0.5)
  })

  it('gives a single-lens body one cell that shares the fixture colour', () => {
    const spec = bodySpecOf(
      { kindOverride: 'PROFILE', lengthM: null },
      fixture({}),
      type({ kind: 'GENERIC', acceptsBeamAngle: true, lengthM: 0.64, widthM: 0.26, heightM: 0.26 }),
    )
    expect(spec.cells).toHaveLength(1)
    expect(spec.cells[0]).toMatchObject({ shape: 'disc', element: null })
    // The lens is at the barrel's front, facing down it.
    expect(spec.cells[0].y).toBeCloseTo(-0.32, 9)
    expect(spec.emitAxis).toBe(-1)
  })

  it('ignores elements with nothing of their own, and caps a long bar', () => {
    const blank = fixture({ elements: [element(0, 'x.0', []), element(1, 'x.1', [])] })
    expect(bodyInputFor({ kindOverride: null }, blank, type({ kind: 'STRIP' }), null).colourElements).toEqual([])
    expect(bodySpecOf({ kindOverride: null, lengthM: null }, blank, type({ kind: 'STRIP' })).cells).toHaveLength(1)
    const long = fixture({
      elements: Array.from({ length: 30 }, (_, i) => element(i, `x.${i}`, [sliderProp('d', 'dimmer', chan(i + 1))])),
    })
    expect(bodySpecOf({ kindOverride: null, lengthM: null }, long, type({ kind: 'STRIP' })).cells).toHaveLength(MAX_CELLS)
  })

  it('gives equal bodies one key and different ones another', () => {
    const a = bodySpecOf({ kindOverride: 'PROFILE', lengthM: null }, fixture({}), type({ kind: 'GENERIC' }))
    const b = bodySpecOf({ kindOverride: 'PROFILE', lengthM: null }, fixture({}), type({ kind: 'GENERIC' }))
    const c = bodySpecOf({ kindOverride: 'FRESNEL', lengthM: null }, fixture({}), type({ kind: 'GENERIC' }))
    expect(a.key).toBe(b.key)
    expect(a.key).not.toBe(c.key)
  })
})

describe('the apex behind the aperture', () => {
  it('sits at the aperture radius over tan(half-field)', () => {
    expect(apexDistanceM(0.1, 90)).toBeCloseTo(0.1, 9)
    expect(apexDistanceM(0.1, 60)).toBeCloseTo(0.1 / Math.tan(Math.PI / 6), 9)
  })

  it('puts a 19° Source Four’s apex at its lamp, about half a metre behind its 170 mm lens', () => {
    const near = apexDistanceM(0.085, 19)
    expect(near).toBeGreaterThan(0.45)
    expect(near).toBeLessThan(0.55)
    // So the cone passes the lens at the lens's own width: radius at the aperture = aperture radius.
    expect(near * Math.tan((19 * Math.PI) / 360)).toBeCloseTo(0.085, 9)
  })

  it('puts a wide LED face’s far behind, so the beam leaves as a column', () => {
    // A Liteobar cell, ~0.15 m wide across a 30° field.
    expect(apexDistanceM(0.15, 30)).toBeGreaterThan(0.5)
    expect(apexDistanceM(0, 30)).toBe(0)
  })
})

describe('light runs', () => {
  it('gives every cell its own light while there are at most four', () => {
    expect(lightRuns(1)).toEqual([[0, 1]])
    expect(lightRuns(3)).toEqual([
      [0, 1],
      [1, 2],
      [2, 3],
    ])
  })

  it('averages a 12-pixel bar in four runs of three', () => {
    expect(lightRuns(12)).toEqual([
      [0, 3],
      [3, 6],
      [6, 9],
      [9, 12],
    ])
  })

  it('covers every cell exactly once for any count', () => {
    for (let n = 0; n <= 16; n++) {
      const runs = lightRuns(n)
      expect(runs.length).toBe(Math.min(n, 4))
      const covered = runs.flatMap(([a, b]) => Array.from({ length: b - a }, (_, i) => a + i))
      expect(covered).toEqual(Array.from({ length: n }, (_, i) => i))
    }
  })
})
