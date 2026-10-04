// @vitest-environment jsdom
//
// jsdom only because store/fixtures imports the API facade, which reads `window` at module load.
import { describe, expect, it } from 'vitest'
import type { Fixture, FixtureTypeInfo, PropertyDescriptor } from '../../../store/fixtures'
import { chan, colourProp, element, sliderProp } from '../../../test/fixtureFactories'
import { bodySpecOf } from '../emitterNeeds'
import { apexDistanceM, archetypeFor, bodyInputFor, DEPTH_OF_FIELD, lightRuns, MAX_CELLS, SOFTNESS, type BodyInput } from './archetype'
import { beamHardness, focusBlur, MASK_EDGE_SOFT } from '../beamMask'
import { resolveEdgeHardness } from '../beamOptics'
import { bodyFrames } from './bodyGeometry'
import focusInverse from '../../../../../src/test/resources/stage/focusInverse.fixture.json'
import { indexLanterns, type Lantern } from '../../../lib/lanterns'
// The desk's own library resource, read straight from the backend's tree — so the lanterns this
// test hangs are the ones the desk ships, and a datasheet fix there is a fix here.
import library from '../../../../../src/main/resources/lanterns/library.json'

const LANTERNS = indexLanterns(library as Lantern[])
const DIMMER = type({ typeKey: 'generic-dimmer', kind: 'GENERIC', acceptsBeamAngle: true, acceptsLantern: true })
const dimmer = fixture({ typeKey: 'generic-dimmer' })

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

describe('the lantern library decides a conventional', () => {
  const spec = (patch: Record<string, unknown>) =>
    bodySpecOf({ kindOverride: null, lengthM: null, ...patch }, dimmer, DIMMER, LANTERNS)

  it("draws the kind's default where the dimmer names no lantern", () => {
    expect(spec({ kindOverride: 'PROFILE' }).lantern?.id).toBe('s4-19')
    expect(spec({ kindOverride: 'FRESNEL' }).lantern?.id).toBe('cantata-f')
    expect(spec({ kindOverride: 'PAR' }).lantern?.id).toBe('par64-cp62')
    expect(spec({}).lantern?.id).toBe('downlight')
  })

  it('draws the lantern it names, over its kind', () => {
    const s = spec({ kindOverride: 'PROFILE', lanternType: 'rama-175' })
    expect(s.archetype).toBe('fresnel')
    expect(s.accessories.barnDoors).toBe(true)
    expect(bodyInputFor({ kindOverride: 'PROFILE', lanternType: 'rama-175' }, dimmer, DIMMER, null, LANTERNS).kind).toBe(
      'FRESNEL',
    )
    // A PC is the fresnel body without the barn doors, and a harder edge.
    const pc = spec({ lanternType: 'prelude-pc' })
    expect(pc.archetype).toBe('fresnel')
    expect(pc.accessories.barnDoors).toBe(false)
    expect(pc.softness).toBeLessThan(spec({ lanternType: 'rama-175' }).softness)
  })

  it("passes a Source Four 19°'s cone through the lens at the lens's width", () => {
    const s = spec({ lanternType: 's4-19' })
    expect(s.fieldDeg).toBe(19)
    const lens = s.cells[0]
    expect(lens.halfWidthM).toBeCloseTo(0.085, 9)
    // The apex behind it is the lamp, about half a metre back, and the cone there is the lens.
    const near = apexDistanceM(lens.halfWidthM, s.fieldDeg)
    expect(near).toBeGreaterThan(0.45)
    expect(near).toBeLessThan(0.55)
    expect(near * Math.tan((s.fieldDeg * Math.PI) / 360)).toBeCloseTo(0.085, 9)
  })

  it('zooms within the range, and keeps its own field without one', () => {
    expect(spec({ lanternType: 's4-zoom-25-50' }).fieldDeg).toBe(35)
    expect(spec({ lanternType: 's4-zoom-25-50', zoomDeg: 42 }).fieldDeg).toBe(42)
    // A stored zoom past the range is drawn at the range's end, never beyond.
    expect(spec({ lanternType: 's4-zoom-25-50', zoomDeg: 70 }).fieldDeg).toBe(50)
    expect(spec({ lanternType: 's4-19', zoomDeg: 30 }).fieldDeg).toBe(19)
  })

  it("takes an oval from a PAR lamp, and turns the frame by the lamp's turn", () => {
    const par = spec({ lanternType: 'par64-cp62', lampRotationDeg: 30 })
    expect(par.fieldDeg).toBe(44)
    expect(par.ovalRatio).toBeCloseTo(Math.tan((21 * Math.PI) / 360) / Math.tan((44 * Math.PI) / 360), 9)
    expect(par.frameTurnDeg).toBe(30)
    // A profile has no oval, so a stale lamp turn left from a PAR turns nothing.
    expect(spec({ lanternType: 's4-19', lampRotationDeg: 30 }).frameTurnDeg).toBe(0)
  })

  it("carries a profile's blades, gate and iris, and drops what the lantern cannot take", () => {
    const blades = [
      { depth: 0.3, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0.1, angleDeg: -10 },
      { depth: 0, angleDeg: 0 },
    ]
    const profile = spec({ lanternType: 's4-19', shutters: blades, gateRotationDeg: 12, iris: 0.6, focusSoftness: 1 })
    expect(profile.blades).toEqual(blades)
    expect(profile.frameTurnDeg).toBe(12)
    expect(profile.iris).toBe(0.6)
    // The focus knob softens a profile, within a profile's range.
    expect(profile.softness).toBeGreaterThan(spec({ lanternType: 's4-19', focusSoftness: 0 }).softness)
    // A PAR has neither blades nor an iris: the same focus data draws nothing on it.
    const par = spec({ lanternType: 'par64-cp62', shutters: blades, gateRotationDeg: 12, iris: 0.6 })
    expect(par.blades).toBeNull()
    expect(par.frameTurnDeg).toBe(0)
    expect(par.iris).toBe(1)
    // Blades all out are no blades at all.
    expect(spec({ lanternType: 's4-19', shutters: blades.map(() => ({ depth: 0, angleDeg: 5 })) }).blades).toBeNull()
  })

  it('is only for a type that takes a lantern — a DMX fixture keeps its own body', () => {
    const hex = type({ typeKey: 'hex', kind: 'PAR', acceptsBeamAngle: true })
    const s = bodySpecOf({ kindOverride: null, lengthM: null, lanternType: 's4-19' }, fixture({ typeKey: 'hex' }), hex, LANTERNS)
    expect(s.lantern).toBeNull()
    expect(s.archetype).toBe('par')
  })

  it("sizes the body by the lantern's own dimensions", () => {
    const s = spec({ lanternType: 's4-19' })
    expect(s.lengthM).toBe(0.64)
    expect(s.widthM).toBe(0.26)
  })
})

describe("a type's declared body decides before its words", () => {
  it('takes the archetype and the head the type declares', () => {
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', body: { archetype: 'mover', head: 'wash' } }))).toEqual({
      archetype: 'mover',
      head: 'wash',
    })
    // The words would say spot; the declaration says wash, and wins.
    expect(
      archetypeFor(input({ kind: 'MOVING_HEAD', typeKey: 'x-spot', body: { archetype: 'mover', head: 'wash' } })).head,
    ).toBe('wash')
    expect(archetypeFor(input({ kind: 'EFFECT', body: { archetype: 'cannon' } })).archetype).toBe('cannon')
  })

  it("falls back to the words for a head it leaves out, and ignores an archetype it does not know", () => {
    expect(archetypeFor(input({ kind: 'MOVING_HEAD', typeKey: 'mac-250', body: { archetype: 'mover' } })).head).toBe(
      'spot',
    )
    expect(archetypeFor(input({ kind: 'PAR', body: { archetype: 'hologram' } })).archetype).toBe('par')
  })

  it('draws a declared lens diameter', () => {
    const s = bodySpecOf(
      { kindOverride: null, lengthM: null },
      fixture({ properties: [TILT] }),
      type({ kind: 'MOVING_HEAD', acceptsBeamAngle: true, body: { archetype: 'mover', head: 'spot', lensDiameterM: 0.1 } }),
    )
    expect(s.cells[0].halfWidthM).toBeCloseTo(0.05, 9)
  })
})

describe('depth of field (fixture-optics plan D9)', () => {
  const revolution = type({
    typeKey: 'etc-source4-revolution-base-frame',
    kind: 'PROFILE',
    acceptsBeamAngle: true,
    body: { archetype: 'mover', head: 'profile', lensDiameterM: 0.15 },
  })

  it("is the family's where the type declares none, and the type's where it does", () => {
    const f = fixture({ properties: [TILT] })
    const family = bodySpecOf({ kindOverride: null, lengthM: null }, f, revolution)
    expect(family.depthOfField).toBe(DEPTH_OF_FIELD['mover:profile'])
    const declared = bodySpecOf({ kindOverride: null, lengthM: null }, f, { ...revolution, depthOfField: 4.5 })
    expect(declared.depthOfField).toBe(4.5)
    // Nothing but a positive number counts as declared.
    expect(bodySpecOf({ kindOverride: null, lengthM: null }, f, { ...revolution, depthOfField: 0 }).depthOfField).toBe(
      DEPTH_OF_FIELD['mover:profile'],
    )
  })

  it('makes a Revolution on a 24 m wall sharp there, a little soft a DMX step off, and visibly soft 3 m either side', () => {
    // The plan's session-1 check, in numbers, for an unfrosted mover:profile: the hardness the
    // shaders draw with focus on the wall, one DMX step either side (about 1 m at 24 m on 2–40 m:
    // DMX 245 is 23.0 m, 247 is 25.1 m) and 3 m either side.
    const dof = DEPTH_OF_FIELD['mover:profile']
    const lifted = resolveEdgeHardness(SOFTNESS['mover:profile'], undefined, 0, true)
    const hard = (focusM: number) => beamHardness(lifted, focusM, focusBlur(24, focusM, dof), MASK_EDGE_SOFT)
    expect(hard(24)).toBe(1)
    for (const step of [23.0, 25.1]) {
      expect(hard(step)).toBeLessThan(0.95)
      expect(hard(step)).toBeGreaterThan(0.75)
    }
    for (const off of [21, 27]) expect(hard(off)).toBeLessThan(0.4)
    // The cap the family used to hold it at, whatever the focus.
    expect(1 - SOFTNESS['mover:profile']).toBeCloseTo(0.88, 9)
  })
})

describe("where a mover's lens sits — the desk's shared vector", () => {
  // `src/test/resources/stage/focusInverse.fixture.json`'s `heads`: lighting7's `MoverLens` measures
  // a focus from this lens (show/FixtureFocus.kt), so the view and the desk agree on the distance.
  for (const h of focusInverse.heads) {
    it(`pivots and has its lens where the desk measures from — ${h.name}`, () => {
      const t = type({ kind: 'MOVING_HEAD', heightM: h.heightM, body: { archetype: 'mover', head: h.head } })
      const spec = bodySpecOf({ kindOverride: null, lengthM: null }, fixture({ properties: [TILT] }), t)
      expect(spec.head).toBe(h.head)
      expect(bodyFrames(spec, 'hang').pivotY).toBeCloseTo(h.pivotM, 9)
      // Every cell of the head sits on its face, the lens's distance along the beam from the pivot.
      for (const cell of spec.cells) expect(cell.y * spec.emitAxis).toBeCloseTo(h.lensM, 9)
    })
  }
})
