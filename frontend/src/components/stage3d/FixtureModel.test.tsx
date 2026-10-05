// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { Color, Euler, MathUtils, Matrix4, OrthographicCamera, PerspectiveCamera, Quaternion, Vector3 } from 'three'
import {
  beamBlades,
  CellColourSync,
  ColourSync,
  composeBeamHull,
  coneLandingDepth,
  edgeLanding,
  focusRangeM,
  landBeam,
  lanternBladesFor,
  lensLocalMatrix,
  makeBladeScratch,
  pixelsPerMetre,
  resolveCellColour,
  staticHeadQuaternion,
  makeBeamLevels,
  readBeamLevels,
} from './FixtureModel'
import { BEAM_LENGTH } from './emitterLayout'
import { beginTravelFrame, makeBeamTravel } from './beamTravel'
import { NO_TIMING, travelRates } from '../../lib/travel'
import { resolveDeclaredFocusDistance, resolveFocusDistance } from './beamOptics'
import { beamReach, boxCollider, type BeamHit } from './scene/beamReach'
import { apexDistanceM, DEPTH_OF_FIELD } from './bodies/archetype'
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
import { chan, colourProp, revolutionShutterProps, sliderProp } from '../../test/fixtureFactories'
import { findShutterProperties, type ElementDescriptor } from '../../store/fixtures'
import type { Cell } from './bodies/archetype'
import { createColourTicker } from './colourTicker'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket
// at import time. The reads all go through the injected ChannelSource, not the mock.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// Cone and pool opacity are the linear intensity times these fixed scales — see applyColour.
const CONE_SCALE = 1
const POOL_SCALE = 40

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
    const dof = DEPTH_OF_FIELD['mover:spot']
    expect(focusBlur(downstage, focusDist, dof)).toBeCloseTo(0, 9)
    expect(focusBlur(upstage, focusDist, dof)).toBeGreaterThan(0.01)
  })
})

describe('a beam split across an edge', () => {
  // The Commemoration Hall's stage: a deck 0.95 m above the stalls floor, its riser facing the house
  // at z 0, the deck running 7 m upstage and the floor 20 m downstage.
  const stage = [boxCollider(0, -0.475, -3.5, 5, 0.475, 3.5), boxCollider(0, -0.96, 10, 5, 0.01, 10)]
  const emitters = {
    reach: (o: Vector3, d: Vector3, maxT: number, out: BeamHit) => beamReach(o.x, o.y, o.z, d.x, d.y, d.z, stage, maxT, out),
  }
  const balcony = new Vector3(0, 2.8, 17.3)
  const tanHalf = Math.tan((10 * Math.PI) / 360)

  function frame(dir: Vector3): { bx: Vector3; by: Vector3 } {
    const bx = new Vector3(1, 0, 0).addScaledVector(dir, -dir.x).normalize()
    return { bx, by: new Vector3().crossVectors(dir, bx) }
  }
  function behind(face: { px: number; py: number; pz: number; nx: number; ny: number; nz: number }, p: Vector3): boolean {
    return face.nx * (p.x - face.px) + face.ny * (p.y - face.py) + face.nz * (p.z - face.pz) < -0.03
  }
  function land(target: Vector3, tan = tanHalf) {
    const dir = target.clone().sub(balcony).normalize()
    const hit = { ...landBeam(emitters, balcony, dir).hit! }
    const { bx, by } = frame(dir)
    const edge = edgeLanding(emitters, balcony, dir, bx, by, tan, tan, false, 0, hit)
    return { dir, hit, edge: edge && { ...edge } }
  }

  it('lands a follow spot aimed at the riser on the deck too, and stops only inside the stage', () => {
    const { hit, edge } = land(new Vector3(0, -0.4, 0))
    expect(hit.nz).toBe(1)
    expect(edge).not.toBeNull()
    expect(edge!.ny).toBe(1)
    expect(edge!.py).toBeCloseTo(0, 6)
    // The deck upstage of the riser is lit and its haze drawn; inside the stage nothing is.
    const deck = new Vector3(0, 0, -2)
    expect(behind(hit, deck) && behind(edge!, deck)).toBe(false)
    const inside = new Vector3(0, -0.5, -1)
    expect(behind(hit, inside) && behind(edge!, inside)).toBe(true)
  })

  it("carries the hit collider's skin to both faces, and lands on the face itself", () => {
    const skinned = [boxCollider(0, -0.475, -3.5, 5, 0.475, 3.5, 0, 0.08), stage[1]]
    const reach = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) => beamReach(o.x, o.y, o.z, d.x, d.y, d.z, skinned, maxT, out)
    const dir = new Vector3(0, -0.4, 0).sub(balcony).normalize()
    const hit = { ...landBeam({ reach }, balcony, dir).hit! }
    expect(hit.skin).toBe(0.08)
    // The skin moves the plane the shaders cut at (`landing.ts`), never the landed point.
    expect(hit.pz).toBeCloseTo(0, 9)
    const { bx, by } = frame(dir)
    expect(edgeLanding({ reach }, balcony, dir, bx, by, tanHalf, tanHalf, false, 0, hit)!.skin).toBe(0.08)
  })

  it('lands one aimed just over the edge on the riser too, from the other side', () => {
    const { hit, edge } = land(new Vector3(0, 0, -0.3))
    expect(hit.ny).toBe(1)
    expect(edge).not.toBeNull()
    expect(edge!.nz).toBe(1)
    const riser = new Vector3(0, -0.3, 0)
    expect(behind(hit, riser) && behind(edge!, riser)).toBe(false)
  })

  it("prefers the face next to the riser over a rostrum further upstage", () => {
    // A rostrum 0.7 m high, 3–5 m upstage: the upper rim lands on its top, deeper behind the riser
    // than the deck is. The deck is the face the riser's edge belongs to.
    const rostrum = [...stage, boxCollider(0, 0.35, -4, 5, 0.35, 1)]
    const reach = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) => beamReach(o.x, o.y, o.z, d.x, d.y, d.z, rostrum, maxT, out)
    const dir = new Vector3(0, -0.4, 0).sub(balcony).normalize()
    const hit = { ...landBeam({ reach }, balcony, dir).hit! }
    expect(hit.nz).toBe(1)
    const { bx, by } = frame(dir)
    const edge = edgeLanding({ reach }, balcony, dir, bx, by, tanHalf, tanHalf, false, 0, hit)
    expect(edge).not.toBeNull()
    expect(edge!.ny).toBe(1)
    expect(edge!.py).toBeCloseTo(0, 6)
  })

  it('finds no edge for a beam the riser takes whole', () => {
    expect(land(new Vector3(0, -0.5, 0), Math.tan((2 * Math.PI) / 360)).edge).toBeNull()
  })

  it('finds no edge in a corner, where the rim lands in front of the face the axis hit', () => {
    // Deck and back wall: a concave corner, which the single plane already cuts right.
    const room = [boxCollider(0, -0.5, -3.5, 5, 0.5, 3.5), boxCollider(0, 3, -7.01, 5, 3, 0.01)]
    const reach = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) => beamReach(o.x, o.y, o.z, d.x, d.y, d.z, room, maxT, out)
    const above = new Vector3(0, 6, -3)
    const dir = new Vector3(0, 0, -6.5).sub(above).normalize()
    const hit = { ...landBeam({ reach }, above, dir).hit! }
    const { bx, by } = frame(dir)
    expect(edgeLanding({ reach }, above, dir, bx, by, 0.4, 0.4, false, 0, hit)).toBeNull()
  })

  describe("a flat in front of a wall: the flat's edge draws its shadow", () => {
    // A flat 2 m wide (x ±1) with its face at z −1.99, the wall behind it at z −7.
    const set = [boxCollider(0, 1.5, -2, 1, 1.5, 0.01), boxCollider(0, 3, -7.01, 6, 3, 0.01)]
    const reach = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) => beamReach(o.x, o.y, o.z, d.x, d.y, d.z, set, maxT, out)
    const front = new Vector3(0, 1.5, 10)
    const aimAt = (x: number) => {
      const dir = new Vector3(x, 1.5, -2).sub(front).normalize()
      const hit = { ...landBeam({ reach }, front, dir).hit! }
      const { bx, by } = frame(dir)
      const beyond = { px: 0, py: 0, pz: 0, nx: 0, ny: 0, nz: 0, skin: 0, collider: null }
      const edge = edgeLanding({ reach }, front, dir, bx, by, 0.1, 0.1, false, 0, hit, beyond)
      return { hit, edge: edge && { ...edge }, beyond }
    }
    const height = (f: { px: number; py: number; pz: number; nx: number; ny: number; nz: number }, p: Vector3) =>
      f.nx * (p.x - f.px) + f.ny * (p.y - f.py) + f.nz * (p.z - f.pz)

    it('is the plane through the lamp and the edge the rim passed, the flat behind it', () => {
      const { hit, edge, beyond } = aimAt(0.8)
      expect(hit.nz).toBe(1)
      expect(edge).not.toBeNull()
      expect(edge!.ny).toBe(0)
      expect(height(edge!, front)).toBeCloseTo(0, 9)
      expect(height(edge!, new Vector3(1, 0, -1.99))).toBeCloseTo(0, 9)
      expect(height(edge!, new Vector3(0, 1.5, -2))).toBeLessThan(0)
      // Where the rim landed past it is the wall: the beam's length reaches that.
      expect(beyond.pz).toBeCloseTo(-7, 6)
    })

    it('lights the wall beside the flat and leaves its shadow dark, along the true shadow line', () => {
      const { hit, edge } = aimAt(0.8)
      // The ray from the lamp grazing the edge reaches the wall at the shadow line.
      const graze = new Vector3(1, 1.5, -1.99).sub(front)
      const line = front.clone().addScaledVector(graze, (-7 - front.z) / graze.z)
      const lit = line.clone().add(new Vector3(0.05, 0, 0))
      const shadow = line.clone().add(new Vector3(-0.05, 0, 0))
      const behindBoth = (p: Vector3) => height(hit, p) < -0.03 && height(edge!, p) < -0.03
      expect(behindBoth(lit)).toBe(false)
      expect(behindBoth(shadow)).toBe(true)
    })

    it('keeps the same plane at every aim while the pool overhangs the edge', () => {
      for (let i = 0; i <= 40; i++) {
        const { edge } = aimAt(0.2 + (0.75 * i) / 40)
        expect(edge).not.toBeNull()
        expect(height(edge!, front)).toBeCloseTo(0, 9)
        expect(height(edge!, new Vector3(1, 0, -1.99))).toBeCloseTo(0, 9)
      }
    })

    it('finds the edge of a turned flat', () => {
      const yaw = Math.PI / 6
      const turned = [boxCollider(0, 1.5, -2, 1, 1.5, 0.01, yaw), set[1]]
      const reachTurned = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) =>
        beamReach(o.x, o.y, o.z, d.x, d.y, d.z, turned, maxT, out)
      const dir = new Vector3(0.7, 1.5, -2.3).sub(front).normalize()
      const hit = { ...landBeam({ reach: reachTurned }, front, dir).hit! }
      const { bx, by } = frame(dir)
      const edge = edgeLanding({ reach: reachTurned }, front, dir, bx, by, 0.1, 0.1, false, 0, hit)
      expect(edge).not.toBeNull()
      // The box's +x, +z corner turned by +yaw: (cos·1 + sin·0.01, −sin·1 + cos·0.01) about its centre.
      const corner = new Vector3(Math.cos(yaw) + Math.sin(yaw) * 0.01, 0, -2 - Math.sin(yaw) + Math.cos(yaw) * 0.01)
      expect(height(edge!, corner)).toBeCloseTo(0, 9)
      expect(height(edge!, front)).toBeCloseTo(0, 9)
    })

    it('lets a beam wider than the box pass it on both sides, landing beyond with no plane', () => {
      // A column 0.4 m across in a beam 2.4 m across at it: two shadow lines, and room for one.
      const column = [boxCollider(0, 1.5, -2, 0.2, 1.5, 0.2), set[1]]
      const reachColumn = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) =>
        beamReach(o.x, o.y, o.z, d.x, d.y, d.z, column, maxT, out)
      const dir = new Vector3(0, 1.5, -2).sub(front).normalize()
      const hit = { ...landBeam({ reach: reachColumn }, front, dir).hit! }
      expect(hit.pz).toBeCloseTo(-1.8, 6)
      const { bx, by } = frame(dir)
      expect(edgeLanding({ reach: reachColumn }, front, dir, bx, by, 0.1, 0.1, false, 0, hit)).toBeNull()
      // The beam lands on the wall: the column stands in front of that plane and stays lit.
      expect(hit.nz).toBe(1)
      expect(hit.pz).toBeCloseTo(-7, 6)
    })

    it('does not pass a border the beam spills over and under: over and under is neither side', () => {
      // A border 6 m wide and 0.6 m tall in a beam 2.4 m across at it, aimed at its middle.
      const border = [boxCollider(0, 3, -2, 3, 0.3, 0.01), set[1]]
      const reachBorder = (o: Vector3, d: Vector3, maxT: number, out: BeamHit) =>
        beamReach(o.x, o.y, o.z, d.x, d.y, d.z, border, maxT, out)
      const above = new Vector3(0, 3, 10)
      const dir = new Vector3(0, 3, -2).sub(above).normalize()
      const hit = { ...landBeam({ reach: reachBorder }, above, dir).hit! }
      const { bx, by } = frame(dir)
      expect(edgeLanding({ reach: reachBorder }, above, dir, bx, by, 0.1, 0.1, false, 0, hit)).toBeNull()
      expect(hit.pz).toBeCloseTo(-1.99, 6)
    })

    it('keeps a level edge to the face past it: a beam over the top of the flat finds no shadow line', () => {
      const dir = new Vector3(0, 2.95, -2).sub(front).normalize()
      const hit = { ...landBeam({ reach }, front, dir).hit! }
      const { bx, by } = frame(dir)
      expect(edgeLanding({ reach }, front, dir, bx, by, 0.05, 0.05, false, 0, hit)).toBeNull()
    })
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

  // The Liteobar's strobe is the fixture's, not a cell's (fixture-optics plan D12): it gates every
  // cell as a master, as a dimmer does.
  it("gates every cell by the fixture's strobe: closed dark, open lit, a strobe flashing", () => {
    const strobe = sliderProp('strobe', 'strobe', chan(2), {
      strobeBands: [
        { from: 0, to: 0, kind: 'OPEN' },
        { from: 1, to: 9, kind: 'CLOSED' },
        { from: 10, to: 255, kind: 'STROBE', hzMin: 1, hzMax: 1 },
      ],
    })
    const elements: ElementDescriptor[] = [0, 1, 2].map((i) => ({
      index: i,
      key: `bar.cell-${i}`,
      displayName: `Cell ${i + 1}`,
      properties: [colourProp('rgbColour', chan(3 + i * 3), chan(4 + i * 3), chan(5 + i * 3))],
    }))
    const cells: Cell[] = [0, 1, 2].map((i) => ({ x: i * 0.2, y: 0, z: 0, shape: 'disc', halfWidthM: 0.05, halfDepthM: 0.05, element: i }))
    const pools = (strobeLevel: number, timeS: number) => {
      const ticker = createColourTicker(() => {})
      ticker.frame(timeS)
      const cellStateRef = {
        current: { count: 3, colors: new Float32Array(9), cone: new Float32Array(3), pool: new Float32Array(3) },
      }
      const view = render(
        <ChannelSourceProvider source={source({ '0:2': strobeLevel, '0:3': 255, '0:7': 255, '0:11': 255 })}>
          <CellColourSync
            elements={elements}
            cells={cells}
            dimmerProp={undefined}
            strobes={[strobe]}
            fallbackHex="#ffffff"
            lensRef={{ current: null }}
            cellStateRef={cellStateRef}
            ticker={ticker}
          />
        </ChannelSourceProvider>,
      )
      const listening = ticker.listening
      view.unmount()
      return { pool: [...cellStateRef.current.pool], listening }
    }
    expect(pools(0, 0)).toEqual({ pool: [POOL_SCALE, POOL_SCALE, POOL_SCALE].map(Math.fround), listening: 0 })
    expect(pools(5, 0)).toEqual({ pool: [0, 0, 0], listening: 0 })
    expect(pools(100, 0.05)).toEqual({ pool: [POOL_SCALE, POOL_SCALE, POOL_SCALE].map(Math.fround), listening: 1 })
    expect(pools(100, 0.5)).toEqual({ pool: [0, 0, 0], listening: 1 })
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
 * The beam's cross-section frame, as the surface shader resolves it (`scene/surfaceShader.ts`): `u`
 * the head's right axis turned by the frame's turn, `v = axis × u`, normalised so the field edge is
 * at 1 — and an oval's (or a segment's) `v` divided by `|aspect|`. The shader gets `u` from the light
 * table's `(cos, sin)` in a basis built from the axis (`lightTable.ts`'s `frameInBasis`), which is the
 * same direction; this computes it straight from the right axis. The GLSL is the authority, so the
 * tests below read the rig the way the pool does.
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
      { depth: 21 / 62, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
    ])
    const lit = (x: number, z: number) => {
      const [u, v] = beamUv(new Vector3(x, 0, z), apex, dir, right, 19)
      return beamMask(u, v, 0, 1, 0.04, 0, a, b)
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
        return beamMask(u, v, 0, 1, 0.04, 0, a0, b0)
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

describe("a DMX head's framing shutters, in the head's own frame", () => {
  // The Revolution's frames, found as the director finds them.
  const shutters = findShutterProperties(revolutionShutterProps())!
  // A mover's rig as FixtureModel builds it: the mount's base turn (YXZ), the yoke panning about its
  // Y, the head tilting about its X, the beam along the head's +Y (a mover's emitAxis).
  const moverHead = (basePitchDeg: number, panDeg: number, tiltDeg: number) => {
    const m = new Matrix4()
      .makeRotationFromEuler(new Euler(MathUtils.degToRad(basePitchDeg), 0, 0, 'YXZ'))
      .multiply(new Matrix4().makeRotationY(MathUtils.degToRad(panDeg)))
      .multiply(new Matrix4().makeRotationX(MathUtils.degToRad(tiltDeg)))
    return {
      dir: new Vector3(0, 1, 0).transformDirection(m),
      right: new Vector3(1, 0, 0).transformDirection(m),
    }
  }
  // One blade's DMX levels at full, every rotation square — as the director reads them.
  const oneBladeIn = (blade: number, rotation = [127.5, 127.5, 127.5, 127.5]) => {
    const scratch = makeBladeScratch()
    scratch.depthLevels = [0, 0, 0, 0]
    scratch.depthLevels[blade] = 255
    scratch.rotationLevels = [...rotation]
    return [...beamBlades([0, 0], shutters, scratch, true)] as [number, number]
  }
  // Where a point offset from the beam's middle, `reach` metres out, falls in the light.
  const litAt = (head: { dir: Vector3; right: Vector3 }, packed: [number, number], offset: Vector3) => {
    const apex = new Vector3(0, 6, 0)
    const reach = 10
    const point = apex.clone().addScaledVector(head.dir, reach).addScaledVector(offset, reach * Math.tan((19 * Math.PI) / 360) * 0.6)
    const [u, v] = beamUv(point, apex, head.dir, head.right, 19)
    return beamMask(u, v, 0, 1, 0.04, 0, packed[0], packed[1])
  }
  const sides = (head: { dir: Vector3 }) => {
    const up = new Vector3(0, 1, 0)
    const viewersRight = new Vector3().crossVectors(head.dir, up).normalize()
    return [up, up.clone().negate(), viewersRight.clone().negate(), viewersRight]
  }

  for (const [label, pitch, pan, tilt] of [
    // The balcony's: hung, at pan centre, tilted out positive to the stage — what aim solves.
    ['hung, tilted out positive', 180, 0, 90],
    ['hung, panned round and tilted out positive', 180, 180, 90],
    // A standing head is a hung one turned over, so it reads right tilted out the other way.
    ['standing, tilted out negative', 0, 0, -90],
  ] as const) {
    it(`cuts the side each blade is named for, ${label}`, () => {
      const head = moverHead(pitch, pan, tilt)
      expect(Math.abs(head.dir.y)).toBeLessThan(1e-9)
      const [top, bottom, left, right] = sides(head)
      const named = [top, bottom, left, right]
      for (let blade = 0; blade < 4; blade++) {
        const packed = oneBladeIn(blade)
        // Its own side is dark, the opposite side lit: a blade at full reaches the centre, no further.
        expect(litAt(head, packed, named[blade])).toBe(0)
        const opposite = named[blade ^ 1]
        expect(litAt(head, packed, opposite)).toBeGreaterThan(0.99)
      }
    })
  }

  it('keeps its blades in the head, so a head swung over the top cuts the other way', () => {
    // The same beam reached by tilting out negative: the head has turned over, and its top blade
    // with it — as a standing head tilted out positive is.
    for (const [pitch, pan, tilt] of [
      [180, 180, -90],
      [180, 0, -90],
      [0, 0, 90],
    ] as const) {
      const head = moverHead(pitch, pan, tilt)
      const [top, bottom, left, right] = sides(head)
      expect(litAt(head, oneBladeIn(0), bottom)).toBe(0)
      expect(litAt(head, oneBladeIn(0), top)).toBeGreaterThan(0.99)
      expect(litAt(head, oneBladeIn(2), right)).toBe(0)
      expect(litAt(head, oneBladeIn(2), left)).toBeGreaterThan(0.99)
    }
  })

  it('turns a rotated blade about its own edge', () => {
    // Frame 1 half in and turned to +45° (DMX 255): the edge's middle stays on the beam's vertical
    // axis, half way up, and the two ends of the edge sit either side of it.
    const head = moverHead(180, 0, 90)
    const scratch = makeBladeScratch()
    scratch.depthLevels = [127.5, 0, 0, 0]
    scratch.rotationLevels = [255, 127.5, 127.5, 127.5]
    const turned = [...beamBlades([0, 0], shutters, scratch, true)] as [number, number]
    const unturnedScratch = makeBladeScratch()
    unturnedScratch.depthLevels = [127.5, 0, 0, 0]
    unturnedScratch.rotationLevels = [127.5, 127.5, 127.5, 127.5]
    const square = [...beamBlades([0, 0], shutters, unturnedScratch, true)] as [number, number]
    // On the axis, the cut is where it was square.
    const [up] = sides(head)
    const onAxis = (packed: [number, number], f: number) => litAt(head, packed, up.clone().multiplyScalar(f))
    for (const f of [0.6, 0.9]) expect(onAxis(turned, f)).toBeCloseTo(onAxis(square, f), 6)
    // Off the axis, square it cuts both sides alike; turned, one side and not the other.
    const [, , leftSide, rightSide] = sides(head)
    const at = (packed: [number, number], side: Vector3) =>
      litAt(head, packed, up.clone().multiplyScalar(0.75).addScaledVector(side, 0.6))
    expect(at(square, leftSide)).toBeCloseTo(at(square, rightSide), 6)
    expect(Math.abs(at(turned, leftSide) - at(turned, rightSide))).toBeGreaterThan(0.9)
    // A positive turn is clockwise as seen from behind the head: the top blade comes down on the
    // viewer's right.
    expect(at(turned, rightSide)).toBe(0)
    expect(at(turned, leftSide)).toBeGreaterThan(0.99)
  })

  it("prefers the head's own blades, and never draws a lantern's beside them", () => {
    const lanternBlades = [
      { depth: 0.3, angleDeg: 10 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
    ]
    const spec = { cells: [{} as never], blades: lanternBlades }
    // A lantern's blades are the beam's only where the fixture drives none of its own…
    expect(lanternBladesFor(spec, undefined)).toBe(lanternBlades)
    expect(lanternBladesFor(spec, shutters)).toBeNull()
    // …and never on a body of several cells.
    expect(lanternBladesFor({ cells: [{} as never, {} as never], blades: lanternBlades }, undefined)).toBeNull()

    const lanternPacked = packBlades(lanternBlades)
    const scratch = makeBladeScratch()
    // A DMX head with every blade out draws no blade — not the lantern's.
    expect([...beamBlades(lanternPacked, shutters, scratch)]).toEqual([0, 0])
    // With none of its own, the lantern's packed pair passes through untouched.
    expect(beamBlades(lanternPacked, undefined, scratch)).toBe(lanternPacked)
    // A DMX blade in is exactly that blade, packed.
    scratch.depthLevels = [0, 0, 255, 0]
    scratch.rotationLevels = [127.5, 127.5, 127.5, 127.5]
    expect([...beamBlades(lanternPacked, shutters, scratch)]).toEqual(
      packBlades([
        { depth: 0, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
        { depth: 0.5, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
      ]),
    )
    // On a mover the frame is turned a half-turn: the left blade sits in the right's slot.
    expect([...beamBlades(lanternPacked, shutters, scratch, true)]).toEqual(
      packBlades([
        { depth: 0, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
        { depth: 0.5, angleDeg: 0 },
      ]),
    )
  })
})

describe('the beam levels a frame draws from (travel time, fixture-optics D14)', () => {
  const KEYS = {
    focus: null, zoom: null, iris: null, frost: null, gobo: null, gobo2: null,
    goboRot: '0:18', goboRotFine: null,
  }
  // One source for every frame: a new one would read as a vis-source switch, which lands.
  let rotLevel = 0
  const src = {
    get: () => 0,
    getByKey: (key: string) => (key === '0:18' ? rotLevel : 0),
    subscribeToChannel: () => ({ unsubscribe: () => {} }),
  }
  const RATES = travelRates({ beamMs: 1000 })

  it('turns an indexed gobo to its angle, and draws a spin speed as sent', () => {
    const bt = makeBeamTravel()
    const levels = makeBeamLevels()
    const frame = (rot: number, t: number, indexing: boolean) => {
      rotLevel = rot
      beginTravelFrame(bt, src, KEYS, t, RATES, NO_TIMING, { position: null, beam: null })
      return readBeamLevels(src, KEYS, false, indexing, bt, levels).goboRot
    }
    expect(frame(0, 0, true)).toBe(0)
    expect(frame(255, 1, true)).toBe(0) // the index move is planned this frame
    expect(frame(255, 1.5, true)).toBeCloseTo(127.5) // and turns over the beam time
    expect(bt.moving).toBe(true)
    // In a rotate band the channel is a speed: forward-fast to reverse-fast is drawn at once, not
    // ramped through the stop band.
    expect(frame(10, 2, false)).toBe(10)
    expect(frame(250, 2.1, false)).toBe(250)
    expect(bt.moving).toBe(false)
    // Back to index: lands on the angle rather than travelling from the speed's value.
    expect(frame(40, 3, true)).toBe(40)
  })
})

