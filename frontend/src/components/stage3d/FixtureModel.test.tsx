// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { Color, Euler, MathUtils, Matrix4, OrthographicCamera, PerspectiveCamera, Quaternion, Vector3 } from 'three'
import {
  ColourSync,
  composeBeamHull,
  coneLandingDepth,
  focusRangeM,
  landBeam,
  lensLocalMatrix,
  pixelsPerMetre,
  resolveCellColour,
  staticHeadQuaternion,
} from './FixtureModel'
import { BEAM_LENGTH } from './emitterLayout'
import { resolveDeclaredFocusDistance, resolveFocusDistance } from './beamOptics'
import { beamReach, boxCollider, type BeamHit } from './scene/beamReach'
import { apexDistanceM } from './bodies/archetype'
import { beamMask, focusBlur, packBlades } from './beamMask'
import { bodyShownFor, LOD_BILLBOARD_BELOW_PX, LOD_SIMPLE_BELOW_PX } from './bodies/StageBodies'
import { fromThree } from '../../lib/stageCoords'
import { longAxisLighting } from '../../lib/fixtureLength'
import {
  DEFAULT_FIXTURE_COLOUR,
  PLACEHOLDER_FIXTURE_COLOUR,
  PLACEHOLDER_FIXTURE_INTENSITY,
} from '../fixtures/fixtureAppearance'
import { ChannelSourceProvider } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import { chan, colourProp, sliderProp } from '../../test/fixtureFactories'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket
// at import time. The reads all go through the injected ChannelSource, not the mock.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// Cone and pool opacity are the linear intensity times these fixed scales — see applyColour.
const CONE_SCALE = 0.32
const POOL_SCALE = 0.55

interface Rendered {
  colour: string
  coneOpacity: number
  poolOpacity: number
  subscribed: number
}

/**
 * Render one arm of the dispatch and report what it wrote into the shared colour state.
 *
 * `lensRef` stays null: the lens is painted through the canvas's instanced bodies, which only exist
 * inside an R3F canvas, and `applyColour` already tolerates its absence (it does on the first render
 * of a real fixture too). The beam half — `colorStateRef` — is the half the bug was about.
 */
function renderArm(
  props: Omit<Parameters<typeof ColourSync>[0], 'lensRef' | 'colorStateRef'>,
  values: Record<string, number> = {},
): Rendered {
  const map = new Map(Object.entries(values))
  let subscribed = 0
  const source: ChannelSource = {
    get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: () => {
      subscribed += 1
      return { unsubscribe: () => {} }
    },
  }
  const colorStateRef = {
    current: { color: new Color('#000000'), coneOpacity: -1, poolOpacity: -1 },
  }
  render(
    <ChannelSourceProvider source={source}>
      <ColourSync
        {...props}
        lensRef={{ current: null }}
        colorStateRef={colorStateRef}
      />
    </ChannelSourceProvider>,
  )
  return {
    colour: `#${colorStateRef.current.color.getHexString()}`,
    coneOpacity: colorStateRef.current.coneOpacity,
    poolOpacity: colorStateRef.current.poolOpacity,
    subscribed,
  }
}

describe('ColourSync', () => {
  const DIMMER = sliderProp('dimmer', 'dimmer', chan(1))

  it('draws a patch with no fixture record as the shared dim placeholder, not a lit lamp', () => {
    const out = renderArm({
      hasFixture: false,
      colourSource: undefined,
      gel: null,
      dimmerProp: undefined,
    })
    expect(out.colour).toBe(`#${new Color(PLACEHOLDER_FIXTURE_COLOUR).getHexString()}`)
    expect(out.coneOpacity).toBeCloseTo(CONE_SCALE * PLACEHOLDER_FIXTURE_INTENSITY, 10)
    expect(out.poolOpacity).toBeCloseTo(POOL_SCALE * PLACEHOLDER_FIXTURE_INTENSITY, 10)
  })

  it('subscribes to nothing on the placeholder arm, even where a dimmer descriptor survives', () => {
    const out = renderArm({
      hasFixture: false,
      colourSource: undefined,
      gel: null,
      dimmerProp: DIMMER,
    })
    expect(out.subscribed).toBe(0)
    expect(out.coneOpacity).toBeCloseTo(CONE_SCALE * PLACEHOLDER_FIXTURE_INTENSITY, 10)
  })

  it('still beams warm white for a real fixture with no colour source and no gel', () => {
    const out = renderArm({
      hasFixture: true,
      colourSource: undefined,
      gel: null,
      dimmerProp: DIMMER,
    })
    expect(out.colour).toBe(`#${new Color(DEFAULT_FIXTURE_COLOUR).getHexString()}`)
    // Dimmer at full: the placeholder's dimness must come from the arm, not from a dark frame.
    expect(out.coneOpacity).toBeCloseTo(0, 10)
    expect(out.subscribed).toBe(1)
  })

  it('takes the gel colour ahead of the warm-white default for a real fixture', () => {
    const out = renderArm(
      {
        hasFixture: true,
        colourSource: undefined,
          gel: { color: '#ff0000' },
        dimmerProp: DIMMER,
      },
      { '0:1': 255 },
    )
    expect(out.colour).toBe('#ff0000')
    expect(out.coneOpacity).toBeCloseTo(CONE_SCALE, 10)
  })
})

describe('the beam hull and the lenses', () => {
  const apex = new Vector3(1, 3, -2)
  const dir = new Vector3(0, -1, 0)
  const bx = new Vector3(1, 0, 0)
  const by = new Vector3().crossVectors(dir, bx)

  it("lays the unit cone's apex at the beam's apex, down the beam, sized per axis", () => {
    const m = composeBeamHull(apex, dir, bx, by, 5, 2, 0.5, new Matrix4())
    // ConeGeometry(1, 1): apex at +y 0.5, base (radius 1) at −y 0.5.
    expect(new Vector3(0, 0.5, 0).applyMatrix4(m).distanceTo(apex)).toBeCloseTo(0, 9)
    const far = new Vector3(0, -0.5, 0).applyMatrix4(m)
    expect(far.distanceTo(apex.clone().addScaledVector(dir, 5))).toBeCloseTo(0, 9)
    // A segment's rectangle lines up with the head: its width along bx, its depth along by.
    expect(new Vector3(1, -0.5, 0).applyMatrix4(m).sub(far).dot(bx)).toBeCloseTo(2, 9)
    expect(new Vector3(0, -0.5, 1).applyMatrix4(m).sub(far).dot(by)).toBeCloseTo(0.5, 9)
    // Right-handed, so the hull's back faces stay the ones the march rasterises.
    expect(m.determinant()).toBeGreaterThan(0)
  })

  it("passes a 19° profile's aperture at the lens's own width", () => {
    const lensR = 0.085
    const near = apexDistanceM(lensR, 19)
    const tanHalf = Math.tan((19 * Math.PI) / 360)
    const length = near + 6
    const m = composeBeamHull(apex, dir, bx, by, length, length * tanHalf, length * tanHalf, new Matrix4())
    // The cone's radius at the aperture plane — axial `near` from the apex — is the lens's.
    const t = near / length // fraction of the way from apex (y 0.5) to base (y −0.5)
    const atAperture = new Vector3(t, 0.5 - t, 0).applyMatrix4(m)
    const onAxis = apex.clone().addScaledVector(dir, near)
    expect(atAperture.distanceTo(onAxis)).toBeCloseTo(lensR, 9)
  })

  it('turns a lens face to look down the beam, on either emit axis', () => {
    const cell = { x: 0.1, y: -0.3, z: 0, shape: 'segment' as const, halfWidthM: 0.2, halfDepthM: 0.05, element: null }
    const down = lensLocalMatrix(cell, -1, new Matrix4())
    // The unit face's normal (+Z) comes out along −Y; its width along X, its depth along Z.
    expect(new Vector3(0, 0, 1).transformDirection(down).y).toBeCloseTo(-1, 9)
    const origin = new Vector3().applyMatrix4(down)
    expect(new Vector3(1, 0, 0).applyMatrix4(down).sub(origin).x).toBeCloseTo(0.2, 9)
    expect(Math.abs(new Vector3(0, 1, 0).applyMatrix4(down).sub(origin).z)).toBeCloseTo(0.05, 9)
    expect(origin.y).toBeLessThan(-0.3)
    const up = lensLocalMatrix({ ...cell, y: 0.3 }, 1, new Matrix4())
    expect(new Vector3(0, 0, 1).transformDirection(up).y).toBeCloseTo(1, 9)
  })
})

describe('where a beam is drawn to', () => {
  // A stage deck's top at y 0, 7 m deep from the downstage edge (z 0) upstage (−z).
  const deck = [boxCollider(0, -0.5, -3.5, 5, 0.5, 3.5)]
  const emitters = {
    reach: (o: Vector3, d: Vector3, maxT: number, out: BeamHit) => beamReach(o.x, o.y, o.z, d.x, d.y, d.z, deck, maxT, out),
  }

  it('reaches the stage from a balcony 17 m out, however far past BEAM_LENGTH that is', () => {
    const balcony = new Vector3(0, 2.8, 17.3)
    const dir = new Vector3(0, 0, -2).sub(balcony).normalize()
    const landed = landBeam(emitters, balcony, dir)
    expect(landed.hit).not.toBeNull()
    expect(landed.length).toBeGreaterThan(BEAM_LENGTH)
    expect(landed.length).toBeCloseTo(new Vector3(0, 0, -2).distanceTo(balcony), 6)
  })

  it('keeps the stylised length in open air', () => {
    const landed = landBeam(emitters, new Vector3(0, 3, 0), new Vector3(0, 1, 0))
    expect(landed).toEqual({ hit: null, length: BEAM_LENGTH })
  })

  it("draws a grazing landing on until the cone's far rim meets the floor", () => {
    const balcony = new Vector3(0, 2.8, 17.3)
    const dir = new Vector3(0, 0, -2).sub(balcony).normalize()
    const hit = landBeam(emitters, balcony, dir).hit!
    const tanHalf = Math.tan((10 * Math.PI) / 360)
    const depth = coneLandingDepth(balcony, dir, tanHalf, hit, 100)
    // Further than the axis's own hit: the rim leaning away from the floor lands well upstage of it.
    expect(depth).toBeGreaterThan(new Vector3(0, 0, -2).distanceTo(balcony) + 1)
    const n = new Vector3(hit.nx, hit.ny, hit.nz)
    const away = n.clone().addScaledVector(dir, -n.dot(dir)).normalize()
    const rim = balcony.clone().addScaledVector(dir.clone().addScaledVector(away, tanHalf), depth)
    expect(rim.y).toBeCloseTo(0, 6)
  })

  it('stops at the axial hit for a beam square to the surface, and caps a rim that never lands', () => {
    const above = new Vector3(0, 4, -3)
    const down = new Vector3(0, -1, 0)
    const hit = landBeam(emitters, above, down).hit!
    expect(coneLandingDepth(above, down, Math.tan(0.3), hit, 100)).toBeCloseTo(4, 6)
    // 5° down onto the deck, with a 30° half-field: the upper rim climbs away and never comes down.
    const low = new Vector3(0, 0.5, 3)
    const shallow = new Vector3(0, -Math.sin(0.087), -Math.cos(0.087))
    const graze = landBeam(emitters, low, shallow).hit!
    expect(coneLandingDepth(low, shallow, Math.tan(Math.PI / 6), graze, 40)).toBe(40)
  })

  it('racks focus over a long throw where the type declares no range, so full focus is sharp where it lands', () => {
    const balcony = new Vector3(0, 2.8, 17.3)
    const landed = landBeam(emitters, balcony, new Vector3(0, 0, -2).sub(balcony).normalize())
    expect(resolveFocusDistance(1, focusRangeM(landed.length))).toBeCloseTo(landed.length, 6)
    // A throw inside BEAM_LENGTH racks over BEAM_LENGTH, as it always has.
    expect(focusRangeM(4)).toBe(BEAM_LENGTH)
  })

  it('focuses a declared range at one distance, so a mark further upstage goes soft', () => {
    const balcony = new Vector3(0, 2.8, 17.3)
    const focus = {
      type: 'slider', name: 'focus', displayName: 'Focus', category: 'focus',
      channel: { universe: 0, channelNo: 7 }, min: 0, max: 255, focusNearM: 2, focusFarM: 40,
    } as const
    const downstage = landBeam(emitters, balcony, new Vector3(0, 0, 2).sub(balcony).normalize()).length
    const upstage = landBeam(emitters, balcony, new Vector3(0, 0, -4).sub(balcony).normalize()).length
    // The DMX that focuses on the downstage mark, read back off the declared range.
    const param = (1 / 2 - 1 / downstage) / (1 / 2 - 1 / 40)
    const focusDist = resolveDeclaredFocusDistance(focus, param)!
    expect(focusDist).toBeCloseTo(downstage, 6)
    expect(focusBlur(downstage, focusDist, 0.65)).toBeCloseTo(0, 9)
    expect(focusBlur(upstage, focusDist, 0.65)).toBeGreaterThan(0.01)
  })
})

describe("a static lantern's yoke and head", () => {
  const turn = (yaw: number, pitch: number, roll: number) =>
    new Matrix4()
      .makeRotationFromEuler(new Euler(0, MathUtils.degToRad(yaw), 0))
      .multiply(
        new Matrix4().makeRotationFromQuaternion(
          staticHeadQuaternion(MathUtils.degToRad(pitch), MathUtils.degToRad(roll), true),
        ),
      )
  const beam = (m: Matrix4) => fromThree(new Vector3(0, -1, 0).transformDirection(m))

  it('throws where the columns are documented: pitch 0 level towards the yaw, +pitch aiming down', () => {
    // docs/fixtures-engineering.md: yaw 0 faces the audience (−y), +yaw turns towards audience
    // right (+x); pitch 0 is horizontal, +pitch aims down. As the Commemoration Hall's data reads.
    const near = (a: { x: number; y: number; z: number }, x: number, y: number, z: number) =>
      expect(Math.hypot(a.x - x, a.y - y, a.z - z)).toBeCloseTo(0, 9)
    near(beam(turn(0, 0, 0)), 0, -1, 0)
    near(beam(turn(0, 90, 0)), 0, 0, -1)
    near(beam(turn(90, 0, 0)), 1, 0, 0)
    near(beam(turn(180, 30, 0)), 0, Math.cos(Math.PI / 6), -0.5)
    // An ADV2 front (yaw −136, pitch 10) throws upstage at the stage, a little below level —
    // before session 6 it was drawn almost straight down.
    const adv2 = beam(turn(-136, 10, 0))
    expect(adv2.y).toBeGreaterThan(0.6)
    expect(adv2.z).toBeCloseTo(-Math.sin(MathUtils.degToRad(10)), 9)
  })

  it('keeps the long axis longAxisLighting reads, so roll still stands a strip on end', () => {
    for (const [yaw, pitch, roll] of [
      [-136, 10, 0],
      [175, 14, 0],
      [30, 57, 12],
      [0, 0, 90],
    ]) {
      const long = fromThree(new Vector3(1, 0, 0).transformDirection(turn(yaw, pitch, roll)))
      const axis = longAxisLighting(yaw, pitch, roll)
      expect(Math.hypot(long.x - axis.x, long.y - axis.y, long.z - axis.z)).toBeCloseTo(0, 9)
    }
    // Roll turns a lantern about its own beam: the throw is the unrolled one.
    const a = beam(turn(30, 20, 0))
    const b = beam(turn(30, 20, 70))
    expect(Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z)).toBeCloseTo(0, 9)
  })

  it("leaves a mover's head alone: its base orientation is its mount's", () => {
    expect(staticHeadQuaternion(1, 1, false).equals(new Quaternion())).toBe(true)
  })
})

describe('a cell of several takes its own element', () => {
  const source = (values: Record<string, number>): ChannelSource => ({
    get: (u, c) => values[`${u}:${c}`] ?? 0,
    getByKey: (k) => values[k] ?? 0,
    subscribeToChannel: () => ({ unsubscribe: () => {} }),
  })

  it("gives each of the Liteobar's three cells its element's colour", () => {
    const cells = [0, 1, 2].map((i) => ({
      colour: colourProp('rgbColour', chan(3 + i * 3), chan(4 + i * 3), chan(5 + i * 3)),
      setting: undefined,
      dimmer: undefined,
      whites: [],
    }))
    const values = { '0:3': 255, '0:7': 255, '0:11': 255, '0:10': 128 }
    const out = new Color()
    const got = cells.map((c) => {
      const level = resolveCellColour(c, new Color('#ffffff'), source(values), out)
      return [`#${out.getHexString()}`, level] as const
    })
    expect(got[0][0]).toBe('#ff0000')
    expect(got[1][0]).toBe('#00ff00')
    // Cell 3: blue at full with a little red — its own hue, not the bar's average.
    expect(got[2][1]).toBeCloseTo(1, 9)
    expect(got[2][0]).not.toBe(got[1][0])
  })

  it("mixes a blinder cell's warm and cold whites by level, as bright as the brighter", () => {
    const whites = [
      { slider: sliderProp('warmWhite', 'white', chan(2), { displayName: 'Warm white' }), colour: new Color('#ffb46b') },
      { slider: sliderProp('coldWhite', 'white', chan(3), { displayName: 'Cold white' }), colour: new Color('#e4ecff') },
    ]
    const cell = { colour: undefined, setting: undefined, dimmer: undefined, whites }
    const out = new Color()
    expect(resolveCellColour(cell, new Color(), source({ '0:2': 255 }), out)).toBeCloseTo(1, 9)
    expect(`#${out.getHexString()}`).toBe('#ffb46b')
    expect(resolveCellColour(cell, new Color(), source({ '0:2': 64, '0:3': 128 }), out)).toBeCloseTo(128 / 255, 9)
    expect(resolveCellColour(cell, new Color(), source({}), out)).toBe(0)
  })

  it("scales by the element's own dimmer", () => {
    const cell = { colour: undefined, setting: undefined, dimmer: sliderProp('dimmer', 'dimmer', chan(9)), whites: [] }
    expect(resolveCellColour(cell, new Color('#ff8800'), source({ '0:9': 51 }), new Color())).toBeCloseTo(0.2, 9)
  })
})

describe('the size a body is drawn at', () => {
  it('drops to the simple mesh, then to a billboard, as it shrinks on screen', () => {
    expect(bodyShownFor(200)).toBe('full')
    expect(bodyShownFor(LOD_SIMPLE_BELOW_PX - 1)).toBe('simple')
    expect(bodyShownFor(LOD_BILLBOARD_BELOW_PX - 1)).toBe('billboard')
  })

  it('reads pixels per metre off a perspective camera by distance, and off a section by zoom', () => {
    const persp = new PerspectiveCamera(50, 1, 0.1, 100)
    const near = pixelsPerMetre(persp, 800, 5)
    expect(pixelsPerMetre(persp, 800, 10)).toBeCloseTo(near / 2, 9)
    const ortho = new OrthographicCamera(-5, 5, 5, -5, 0.1, 100)
    ortho.zoom = 2
    expect(pixelsPerMetre(ortho, 800, 999)).toBeCloseTo(160, 9)
  })
})

/**
 * The beam's cross-section frame, as the surface shader builds it (`scene/surfaceShader.ts`): `u`
 * the head's right axis turned by the frame's turn, `v = axis × u`, normalised so the field edge is
 * at 1 — and an oval's (or a segment's) `v` divided by `|aspect|`. The GLSL is the authority; this is
 * its arithmetic, so the tests below read the rig the way the pool does.
 */
function beamUv(
  point: Vector3,
  apex: Vector3,
  dir: Vector3,
  right: Vector3,
  fieldDeg: number,
  aspect = 0,
): [number, number] {
  const v = point.clone().sub(apex)
  const axial = v.dot(dir)
  const bx = right.clone().addScaledVector(dir, -right.dot(dir)).normalize()
  const by = new Vector3().crossVectors(dir, bx)
  const tanHalf = Math.tan((fieldDeg * Math.PI) / 360)
  const uv: [number, number] = [v.dot(bx) / (axial * tanHalf), v.dot(by) / (axial * tanHalf)]
  if (aspect !== 0) uv[1] /= Math.abs(aspect)
  return uv
}

describe("a lantern's focus, through the frame the pool and the haze share", () => {
  const head = (yaw: number, pitch: number, roll: number) =>
    new Matrix4()
      .makeRotationFromEuler(new Euler(0, MathUtils.degToRad(yaw), 0))
      .multiply(
        new Matrix4().makeRotationFromQuaternion(
          staticHeadQuaternion(MathUtils.degToRad(pitch), MathUtils.degToRad(roll), true),
        ),
      )
  const axes = (m: Matrix4) => ({
    dir: new Vector3(0, -1, 0).transformDirection(m),
    right: new Vector3(1, 0, 0).transformDirection(m),
  })

  it("puts the top of the beam up for a level lantern, whichever way it faces, and turns it with roll", () => {
    for (const yaw of [0, 90, 180, -136]) {
      const { dir, right } = axes(head(yaw, 0, 0))
      const by = new Vector3().crossVectors(dir, right.clone().addScaledVector(dir, -right.dot(dir)).normalize())
      // three's +Y is up: the top blade (+v) cuts the top of the light.
      expect(by.y).toBeCloseTo(1, 9)
      // And +u is the left of someone behind the lantern looking along the beam.
      const viewersRight = new Vector3().crossVectors(dir, new Vector3(0, 1, 0))
      expect(right.dot(viewersRight)).toBeCloseTo(-1, 9)
    }
    // Rolled 90°, the frame — and every blade in it — has turned about the beam with the lantern.
    const { dir, right } = axes(head(0, 0, 90))
    const by = new Vector3().crossVectors(dir, right)
    expect(Math.abs(by.y)).toBeLessThan(1e-9)
  })

  it('cuts a straight edge on the floor with a shuttered profile', () => {
    // A 19° profile 6 m up and 4 m back, aimed down at the floor, its top blade a third in.
    const { dir, right } = axes(head(180, 50, 0))
    const apex = new Vector3(0, 6, 4)
    const [a, b] = packBlades([
      { depth: 21 / 63, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
    ])
    const lit = (x: number, z: number) => {
      const [u, v] = beamUv(new Vector3(x, 0, z), apex, dir, right, 19)
      return beamMask(u, v, 0, 1, 0.04, a, b)
    }
    // For several lines across the pool, find where the light ends along the floor's depth: with the
    // blade in, that edge is where the pool stops before its field circle would.
    const edgeAt = (x: number) => {
      let lo = -6
      let hi = 4
      // March from the lit middle towards the far edge, then bisect the transition.
      const centre = apex.clone().addScaledVector(dir, 6 / -dir.y)
      let z = centre.z
      while (lit(x, z) > 0.5 && z > lo) z -= 0.02
      lo = z
      hi = z + 0.02
      for (let i = 0; i < 40; i++) {
        const mid = (lo + hi) / 2
        if (lit(x, mid) > 0.5) hi = mid
        else lo = mid
      }
      return (lo + hi) / 2
    }
    const xs = [-0.4, -0.2, 0, 0.2, 0.4]
    const edges = xs.map(edgeAt)
    // Collinear: a straight edge, not the field's arc.
    const slope = (edges[4] - edges[0]) / (xs[4] - xs[0])
    for (let i = 0; i < xs.length; i++) {
      expect(edges[i]).toBeCloseTo(edges[0] + slope * (xs[i] - xs[0]), 3)
    }
    // Without the blade the same march finds the field's circle, which is not straight.
    const [a0, b0] = packBlades(null)
    const openEdge = (x: number) => {
      const centre = apex.clone().addScaledVector(dir, 6 / -dir.y)
      let z = centre.z
      const on = (zz: number) => {
        const [u, v] = beamUv(new Vector3(x, 0, zz), apex, dir, right, 19)
        return beamMask(u, v, 0, 1, 0.04, a0, b0)
      }
      while (on(z) > 0.5 && z > -20) z -= 0.02
      return z
    }
    const open = xs.map(openEdge)
    expect(Math.abs(open[0] - open[2])).toBeGreaterThan(0.02)
    // The blade took light away: its edge is inside the open field.
    expect(edges[2]).toBeGreaterThan(open[2])
  })

  it("turns an oval PAR's pool with its lamp", () => {
    // A CP62, 44 × 21, straight down from 5 m: the pool is wide along the frame's u.
    const { dir, right } = axes(head(0, 90, 0))
    const apex = new Vector3(0, 5, 0)
    const ratio = Math.tan((21 * Math.PI) / 360) / Math.tan((44 * Math.PI) / 360)
    const reach = 5 * Math.tan((44 * Math.PI) / 360) * 0.85
    const along = (r: Vector3, p: Vector3) => {
      const [u, v] = beamUv(p, apex, dir, r, 44, -ratio)
      return beamMask(u, v, -ratio, 1, 0.3)
    }
    const wide = new Vector3(reach, 0, 0)
    const across = new Vector3(0, 0, reach)
    // Unturned, the pool reaches the point along x and not the one along z.
    expect(along(right, wide)).toBeGreaterThan(0.2)
    expect(along(right, across)).toBe(0)
    // With the lamp turned 90°, the director turns the frame's right axis about the beam — and the
    // oval turns with it.
    const turned = right.clone().applyAxisAngle(dir, Math.PI / 2)
    expect(along(turned, wide)).toBe(0)
    expect(along(turned, across)).toBeGreaterThan(0.2)
  })
})
