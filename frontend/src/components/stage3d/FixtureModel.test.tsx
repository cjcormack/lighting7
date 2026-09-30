// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { Color, Euler, MathUtils, Matrix4, OrthographicCamera, PerspectiveCamera, Quaternion, Vector3 } from 'three'
import { ColourSync, composeBeamHull, lensLocalMatrix, pixelsPerMetre, resolveCellColour, staticHeadQuaternion } from './FixtureModel'
import { apexDistanceM } from './bodies/archetype'
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
