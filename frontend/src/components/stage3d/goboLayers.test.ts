// @vitest-environment jsdom
//
// jsdom only because store/fixtures imports the API facade, which reads `window` at module load.
import { describe, expect, it } from 'vitest'
import { buildGoboAtlasData, GOBO_BLUR_LEVELS, GOBO_TILE_PX } from './goboAtlas'
import {
  atlasSampler,
  GOBO_ANGLE_STEPS,
  GOBO_FOOTPRINT_FILTER,
  GOBO_LAYERS_GLSL,
  goboLod,
  goboPair,
  packGobos,
  unpackGobos,
} from './goboLayers'
import { goboLayerFor, GOBO_SLOT_COUNT } from './goboPatterns'
import { focusBlur } from './beamMask'
import {
  makeGoboLayers,
  makeGoboRotation,
  resolveGoboRotation,
  resolveGoboSlot,
  stepGoboLayers,
  type GoboRotation,
} from './beamOptics'
import { frameFromBasis, frameInBasis } from './scene/lightTable'
import { LAMBERT_LOBES } from './scene/lobes'
import { FOCUS_LOD_MAX, GOBO_BLUR_TEXELS } from './washConfig'
import { findGoboProperties, findGoboRotationProperty, goboRotationWheel } from '../../store/fixtures'
import { fittedProperties, type FittedMedia } from '../../lib/fittedMedia'
import { EMPTY_GELS } from '../../lib/gels'
import { chan, settingProp, sliderProp } from '../../test/fixtureFactories'

const SAMPLE = atlasSampler(buildGoboAtlasData(), GOBO_TILE_PX)
const SPOKES = goboLayerFor('spokes')!
/** Half a spoke: `spokes` is open along each of its six arms and dark between them. */
const BETWEEN_SPOKES = Math.PI / 6

/** One pattern on layer A at [angle] (turned), layer B open. */
function one(layer: number, angle = 0): number {
  return packGobos(layer, 0, 0, angle)
}

/** The gobo term at a point `r` field radii out at angle `a` in the beam's frame. */
function at(r: number, a: number, gobos: number, lod = 0): number {
  return goboPair(r * Math.cos(a), r * Math.sin(a), gobos, lod, SAMPLE)
}

/** Brightest minus darkest round a ring of the pattern: how much of it survives the blur. */
function contrast(gobos: number, lod: number): number {
  let lo = 1
  let hi = 0
  for (let i = 0; i < 72; i++) {
    const v = at(0.5, (i / 72) * Math.PI * 2, gobos, lod)
    lo = Math.min(lo, v)
    hi = Math.max(hi, v)
  }
  return hi - lo
}

describe('the gobo packing', () => {
  it('packs two patterns and the turned one\'s angle into one float32 exactly, and back', () => {
    for (let a = 0; a < GOBO_SLOT_COUNT; a++) {
      for (const b of [0, 1, 7, GOBO_SLOT_COUNT - 1]) {
        for (const turned of [0, 1]) {
          for (const angle of [0, 0.001, 1.2, Math.PI, 6.28]) {
            const packed = packGobos(a, b, turned, angle)
            if (a === 0 && b === 0) {
              expect(packed).toBe(0)
              continue
            }
            expect(Math.fround(packed)).toBe(packed)
            expect(packed).toBeLessThan(2 ** 24)
            const back = unpackGobos(packed)!
            expect([back.layerA, back.layerB]).toEqual([a, b])
            // Only the turned layer carries the angle, to a 13-bit step; an open one carries none.
            const want = (turned === 0 ? a : b) > 0 ? angle : 0
            const step = (Math.PI * 2) / GOBO_ANGLE_STEPS
            expect(Math.abs((turned === 0 ? back.angleA : back.angleB) - want) % (Math.PI * 2)).toBeLessThanOrEqual(step / 2 + 1e-9)
            expect(turned === 0 ? back.angleB : back.angleA).toBe(0)
          }
        }
      }
    }
  })

  it('fits every atlas layer in the 5-bit pattern fields', () => {
    // Layer A and layer B take 5 bits each (`packGobos`); a 33rd atlas layer would spill into the
    // neighbouring field and push the float past float32's 24 exact bits. Widening the fields means
    // giving bits up from the angle, in the GLSL and the twin together.
    expect(GOBO_SLOT_COUNT).toBeLessThanOrEqual(32)
    expect(packGobos(GOBO_SLOT_COUNT - 1, GOBO_SLOT_COUNT - 1, 1, Math.PI * 2 - 1e-6)).toBeLessThan(2 ** 24)
  })

  it('wraps the angle to one turn, either way round', () => {
    expect(unpackGobos(one(3, -Math.PI / 2))!.angleA).toBeCloseTo((3 * Math.PI) / 2, 3)
    expect(unpackGobos(one(3, 5 * Math.PI))!.angleA).toBeCloseTo(Math.PI, 3)
    // A turn exactly is no turn, not the step past the top.
    expect(one(3, Math.PI * 2)).toBe(one(3, 0))
  })

  it('packs open as 0, and a layer outside the atlas as open', () => {
    expect(packGobos(0, 0, 0, 1.5)).toBe(0)
    expect(packGobos(GOBO_SLOT_COUNT, 0, 0, 0)).toBe(0)
    expect(packGobos(-1, 0, 0, 0)).toBe(0)
    expect(unpackGobos(0)).toBeNull()
    // No turned layer (no rotation channel): neither carries an angle.
    expect(unpackGobos(packGobos(3, 4, -1, 1.2))).toEqual({ layerA: 3, layerB: 4, angleA: 0, angleB: 0 })
    // An open layer passes the light whole.
    expect(goboPair(0.5, 0, 0, 0, SAMPLE)).toBe(1)
  })

  it('writes the GLSL from the same constants as the twin', () => {
    expect(GOBO_LAYERS_GLSL).toContain(`floor(rest / ${GOBO_ANGLE_STEPS}.0)`)
    expect(GOBO_LAYERS_GLSL).toContain(`floor(packed / ${GOBO_ANGLE_STEPS * 2 * 32}.0)`)
    expect(GOBO_LAYERS_GLSL).toContain(((Math.PI * 2) / GOBO_ANGLE_STEPS).toExponential(10))
    expect(GOBO_LAYERS_GLSL).toContain('* 0.5 + 0.5')
    expect(GOBO_LAYERS_GLSL).toContain(`max(blur, ${GOBO_FOOTPRINT_FILTER.toFixed(1)} * footprint)`)
    expect(GOBO_LAYERS_GLSL).toContain(`rot.z * ${GOBO_BLUR_LEVELS}.0 + level`)
  })
})

describe('a gobo on a surface', () => {
  it("lights a fragment in the pattern's dark less than one in its open", () => {
    const spokes = one(SPOKES)
    expect(at(0.5, 0, spokes)).toBeGreaterThan(0.9)
    expect(at(0.5, BETWEEN_SPOKES, spokes)).toBeLessThan(0.1)
  })

  it("blurs it off the focal plane by the edge's own blur, and keeps it sharp on it", () => {
    const spokes = one(SPOKES)
    // A Revolution-like head, depth of field 3, focused on a wall 24 m from its lens.
    const lodAt = (d: number) => goboLod(focusBlur(d, 24, 3), 0, GOBO_BLUR_TEXELS, FOCUS_LOD_MAX)
    expect(lodAt(24)).toBe(0)
    expect(lodAt(21)).toBeGreaterThan(lodAt(23))
    expect(lodAt(27)).toBeGreaterThan(lodAt(25))
    expect(contrast(spokes, lodAt(24))).toBeGreaterThan(0.8)
    // As soft as the pool's edge at that blur (goboAtlas.test.ts): readable 3 m off, plainly softer.
    expect(contrast(spokes, lodAt(22.5))).toBeLessThan(contrast(spokes, lodAt(24)))
    expect(contrast(spokes, lodAt(21))).toBeLessThan(contrast(spokes, lodAt(22.5)))
    expect(contrast(spokes, lodAt(21))).toBeLessThan(contrast(spokes, lodAt(24)) * 0.7)
    // A sharp pattern far away is read at its pixel's footprint, not aliased at level 0.
    expect(goboLod(0, 0.1, GOBO_BLUR_TEXELS, FOCUS_LOD_MAX)).toBeGreaterThan(2)
    expect(goboLod(0.3, 0.1, GOBO_BLUR_TEXELS, FOCUS_LOD_MAX)).toBe(goboLod(0.3, 0, GOBO_BLUR_TEXELS, FOCUS_LOD_MAX))
  })

  it("turns with the wheel's index", () => {
    // Index the wheel half a spoke round: the open arm now lies where the dark was.
    const indexed = one(SPOKES, BETWEEN_SPOKES)
    expect(at(0.5, BETWEEN_SPOKES, indexed)).toBeGreaterThan(0.9)
    expect(at(0.5, 0, indexed)).toBeLessThan(0.1)
  })

  it('turns with the gate: the frame turns, and the pattern lands turned in the world', () => {
    // A beam straight down, its frame turned a quarter by the gate (the right axis turned about the
    // beam before it is written, as the director does). A world point's place in the pattern is
    // its offset from the axis in that frame — the surface shader's dot(v, bx), dot(v, by).
    const axis = [0, -1, 0] as const
    const cs = new Float32Array(2)
    const sampleWorld = (rightX: number, rightZ: number, px: number, pz: number) => {
      frameInBasis(axis[0], axis[1], axis[2], rightX, 0, rightZ, cs)
      const [ux, uy, uz, vx, vy, vz] = frameFromBasis(axis[0], axis[1], axis[2], cs[0], cs[1])
      return goboPair(px * ux + pz * uz + 0 * uy, px * vx + pz * vz + 0 * vy, one(SPOKES), 0, SAMPLE)
    }
    const r = 0.5
    const between = [r * Math.cos(BETWEEN_SPOKES), r * Math.sin(BETWEEN_SPOKES)]
    // Unturned, the point along the right axis is on an arm and the one half a spoke round is dark.
    expect(sampleWorld(1, 0, r, 0)).toBeGreaterThan(0.9)
    // Turn the gate by half a spoke: the arm follows the frame, onto the point that was dark.
    const turned = [Math.cos(BETWEEN_SPOKES), Math.sin(BETWEEN_SPOKES)]
    expect(Math.abs(sampleWorld(turned[0], turned[1], between[0], between[1]) - 1)).toBeLessThan(0.1)
    expect(sampleWorld(turned[0], turned[1], r, 0)).toBeLessThan(0.1)
  })

  it('multiplies two layers: in series a point passes only what both pass', () => {
    // Layer A still, layer B (the turned one) half a spoke round: their arms never meet.
    const both = packGobos(SPOKES, SPOKES, 1, BETWEEN_SPOKES)
    const bAlone = packGobos(0, SPOKES, 1, BETWEEN_SPOKES)
    expect(at(0.5, 0, one(SPOKES))).toBeGreaterThan(0.9)
    expect(at(0.5, 0, both)).toBeLessThan(0.1)
    for (const angle of [0.2, 1.1, 2.9]) {
      expect(at(0.4, angle, both)).toBeCloseTo(at(0.4, angle, one(SPOKES)) * at(0.4, angle, bAlone), 10)
    }
    const rings = goboLayerFor('rings')!
    expect(at(0.3, 0.7, packGobos(SPOKES, rings, -1, 0))).toBeCloseTo(at(0.3, 0.7, packGobos(rings, SPOKES, -1, 0)), 10)
  })
})

describe('stepping the layers', () => {
  const still: GoboRotation = { indexRad: null, spinRevPerSec: 0 }
  const spin: GoboRotation = { indexRad: null, spinRevPerSec: 0.5 }

  it('asks for frames only while the turned wheel shows a pattern and spins', () => {
    const out = makeGoboLayers()
    expect(stepGoboLayers(4, 0, 0, spin, 0, 1 / 60, out).spinning).toBe(true)
    // Open on the turned wheel: nothing on screen moves, however the rotation channel sits.
    expect(stepGoboLayers(0, 7, 0, spin, 1, 1 / 60, out).spinning).toBe(false)
    expect(out.angle).toBe(0)
    // Indexed, or stopped: still.
    expect(stepGoboLayers(4, 0, 0, { indexRad: 0.8, spinRevPerSec: 0 }, 0, 1 / 60, out).spinning).toBe(false)
    expect(stepGoboLayers(4, 0, 0, still, 0, 1 / 60, out).spinning).toBe(false)
    // No rotation channel at all.
    expect(stepGoboLayers(4, 7, -1, spin, 0, 1 / 60, out).spinning).toBe(false)
  })

  it("integrates the turned wheel's spin, clamped and wrapped, and holds the other still", () => {
    const out = makeGoboLayers()
    stepGoboLayers(4, 7, 1, spin, 0, 0.05, out)
    expect(out.angle).toBeCloseTo(0.5 * Math.PI * 2 * 0.05, 10)
    const back = unpackGobos(out.packed)!
    expect([back.layerA, back.angleA, back.layerB]).toEqual([4, 0, 7])
    expect(back.angleB).toBeCloseTo(out.angle, 3)
    // A tab back from the background hands back seconds: a tenth at most.
    stepGoboLayers(4, 7, 1, spin, 0, 5, out)
    expect(out.angle).toBeCloseTo(0.5 * Math.PI * 2 * 0.1, 10)
  })

  it("indexes the turned wheel to the rotation channel's angle", () => {
    // The Revolution's front wheel in INDEX: DMX over 0..indexDegMax.
    const rot = sliderProp('fbWheelRot', 'gobo_rotation', chan(18), { indexDegMax: 360 })
    const mode = settingProp('fbWheelFunction', 'gobo_rotation_mode', chan(17), [
      { name: 'INDEX', level: 0, displayName: 'Index' },
      { name: 'ROTATE_FWD', level: 128, displayName: 'Rotate' },
    ])
    const rotation = resolveGoboRotation(rot, 64, mode, 0, makeGoboRotation())
    const out = stepGoboLayers(SPOKES, 0, 0, rotation, 0, 1 / 60, makeGoboLayers())
    const back = unpackGobos(out.packed)!
    expect(back.angleA).toBeCloseTo(((64 / 255) * 360 * Math.PI) / 180, 3)
    expect(back.layerB).toBe(0)
  })
})

// The Robe ColorSpot 575 in mode 2, as `GET /fixture-types` carries its wheels — in the order the
// backend's reflection emits them, rotating before static.
const ROBE_ROTATING = settingProp('rotatingGobo', 'gobo', chan(10), [
  { name: 'OPEN', level: 0, displayName: 'Open' },
  { name: 'INDEX_GOBO_1', level: 4, displayName: 'Gobo 1', gobo: 'swirl' },
])
const ROBE_STATIC = settingProp('staticGobo', 'gobo', chan(9), [
  { name: 'OPEN', level: 0, displayName: 'Open' },
  { name: 'GOBO_1', level: 65, displayName: 'Gobo 1', gobo: 'dots' },
])
const ROBE_ROTATION = sliderProp('goboRotation', 'gobo_rotation', chan(11))
const ROBE = [ROBE_ROTATING, ROBE_ROTATION, ROBE_STATIC]

describe('which wheel is which layer', () => {
  it("orders the wheels by DMX channel, whatever the descriptors' order", () => {
    expect(findGoboProperties(ROBE).map((p) => p.name)).toEqual(['staticGobo', 'rotatingGobo'])
    expect(findGoboProperties([...ROBE].reverse()).map((p) => p.name)).toEqual(['staticGobo', 'rotatingGobo'])
  })

  it('gives the rotation channel to the wheel it follows', () => {
    const wheels = findGoboProperties(ROBE)
    expect(goboRotationWheel(wheels, findGoboRotationProperty(ROBE))).toBe(1)
    // The MAC 250: one wheel, its rotation on the next channel.
    const mac = [settingProp('gobo', 'gobo', chan(4)), sliderProp('goboRotation', 'gobo_rotation', chan(5))]
    expect(goboRotationWheel(findGoboProperties(mac), findGoboRotationProperty(mac))).toBe(0)
    // No rotation channel turns nothing; one before every wheel turns the first.
    expect(goboRotationWheel(wheels, undefined)).toBe(-1)
    expect(goboRotationWheel(wheels, sliderProp('r', 'gobo_rotation', chan(2)))).toBe(0)
  })

  it("stacks the Robe's static and rotating wheels, turning only the rotating one", () => {
    const wheels = findGoboProperties(ROBE)
    const turned = goboRotationWheel(wheels, findGoboRotationProperty(ROBE))
    const out = stepGoboLayers(
      resolveGoboSlot(wheels[0], 65),
      resolveGoboSlot(wheels[1], 4),
      turned,
      { indexRad: null, spinRevPerSec: 1 },
      0,
      0.05,
      makeGoboLayers(),
    )
    const back = unpackGobos(out.packed)!
    expect([back.layerA, back.angleA, back.layerB]).toEqual([goboLayerFor('dots'), 0, goboLayerFor('swirl')])
    expect(back.angleB).toBeGreaterThan(0)
    expect(out.spinning).toBe(true)
    // The static wheel alone: drawn, and nothing turns.
    const alone = stepGoboLayers(resolveGoboSlot(wheels[0], 65), resolveGoboSlot(wheels[1], 0), turned, { indexRad: null, spinRevPerSec: 1 }, 0, 0.05, makeGoboLayers())
    expect(unpackGobos(alone.packed)).toEqual({ layerA: goboLayerFor('dots'), layerB: 0, angleA: 0, angleB: 0 })
    expect(alone.spinning).toBe(false)
  })
})

describe("a Revolution's module wheel", () => {
  // The front wheel as the desk carries it: loadable, its stock slots empty (fixture-optics plan
  // session 3), so every slot draws open until a unit is fitted.
  const WHEEL = {
    ...settingProp('fbWheelPos', 'gobo', chan(16), [
      { name: 'OPEN', level: 0, displayName: 'Open', loadable: false },
      { name: 'SLOT_1', level: 14, displayName: 'Slot 1', loadable: true },
      { name: 'SLOT_2', level: 26, displayName: 'Slot 2', loadable: true },
    ]),
    media: 'GOBO_OR_GEL' as const,
  }
  const slotOn = (media: FittedMedia | null, level: number) => {
    const props = fittedProperties([WHEEL], media, EMPTY_GELS)
    const wheels = findGoboProperties(props)
    return stepGoboLayers(resolveGoboSlot(wheels[0], level), 0, 0, makeGoboRotation(), 0, 0, makeGoboLayers()).packed
  }

  it('lands a fitted gobo', () => {
    const media: FittedMedia = { slots: { fbWheelPos: { SLOT_1: { gobo: 'stars' } } } }
    expect(unpackGobos(slotOn(media, 14))!.layerA).toBe(goboLayerFor('stars'))
    // The slot beside it is not fitted, and the stock is empty.
    expect(slotOn(media, 26)).toBe(0)
  })

  it('lands none on its stock (empty) wheel: never an index guess', () => {
    expect(slotOn(null, 14)).toBe(0)
    expect(slotOn(null, 26)).toBe(0)
  })
})

describe('one sampler, two programs', () => {
  it('samples gobos through goboPair in the haze and on every surface, in the frame the mask cuts in', async () => {
    const { makeVolumeMaterial } = await import('./beamShaders')
    const { makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms } = await import('./scene/surfaceShader')
    const { getGoboTexture } = await import('./goboAtlas')
    const haze = makeVolumeMaterial(getGoboTexture()).fragmentShader
    const surface = makeSurfaceMaterial(makeSurfaceUniforms(makeLightTexture().texture), {
      colour: '#808080',
      pattern: 'PLAIN',
      emissive: false,
      lobes: LAMBERT_LOBES,
    }).fragmentShader
    for (const program of [haze, surface]) {
      expect(program).toContain(GOBO_LAYERS_GLSL)
      expect(program).toContain('goboPair(g, ')
    }
    // The surface reads its frame from texel 4's (cos, sin), and its gobos from the float beside it.
    expect(surface).toContain('beamFrame(axis.xyz, frame.xy, bx, by)')
    expect(surface).toContain('goboRots(frame.z, rotA, rotB)')
  })
})
