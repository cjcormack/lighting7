import { describe, expect, it } from 'vitest'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import {
  EYE_PITCH_LIMIT_RAD,
  MIN_FRAME_RADIUS_M,
  SECTION_MARGIN_M,
  boundingSphere,
  clampFov,
  defaultOrbitPose,
  eyeFromOrbit,
  fitZoom,
  framedOrbitPose,
  framedOrthoPosition,
  lookAngles,
  lookDirection,
  orthoSection,
  sceneBoundsLighting,
  type LightingBounds,
} from './stageCameras'

const STAGE = { width: 10, depth: 8, height: 6 }

const BOUNDS: LightingBounds = { min: { x: -6, y: -17, z: -1 }, max: { x: 6, y: 9, z: 7 } }

function close(actual: readonly number[], expected: readonly number[]) {
  expect(actual).toHaveLength(expected.length)
  actual.forEach((v, i) => expect(v).toBeCloseTo(expected[i]!, 9))
}

describe('sceneBoundsLighting', () => {
  it('is the stage envelope when there is nothing else', () => {
    expect(sceneBoundsLighting(STAGE, [], [], [])).toEqual({ min: { x: -5, y: 0, z: 0 }, max: { x: 5, y: 8, z: 6 } })
  })

  it('reaches every rigging’s ends, every fixture, and a region hanging down from centerZ', () => {
    const rig = { uuid: 'balc', positionX: 0, positionY: -16.2, positionZ: 2.8, lengthM: 4 } as RiggingDto
    // A deck whose top is at 0 and 0.95 m thick reaches down to −0.95, not up to +0.95.
    const region = { centerX: 0, centerY: 2, centerZ: 0, widthM: 6, depthM: 8, heightM: 0.95 } as StageRegionDto
    const b = sceneBoundsLighting(STAGE, [rig], [region], [{ x: 7, y: 1, z: 8 }])
    expect(b.min.y).toBeCloseTo(-18.2, 9)
    expect(b.min.z).toBeCloseTo(-0.95, 9)
    expect(b.max.z).toBe(8)
    expect(b.max.x).toBe(7)
  })
})

describe('orthoSection — the three sections agree with the 2D plot’s conventions', () => {
  it('Plan looks straight down from above everything, upstage at the top of the screen', () => {
    const s = orthoSection('plan', BOUNDS)
    close(s.position, [0, 7 + SECTION_MARGIN_M, 4])
    close(s.target, [0, -1, 4])
    // three's −z is lighting +Y: screen-up is upstage.
    close(s.up, [0, 0, -1])
    // The camera stands on the section plane: nothing of the scene is between it and the plane,
    // and the far plane reaches past the floor.
    expect(s.far).toBeGreaterThan(s.position[1] - BOUNDS.min.z)
    expect(s.width).toBeGreaterThan(12)
    expect(s.height).toBeGreaterThan(26)
  })

  it('Front stands in the house past the furthest position, looking upstage with +X to the right', () => {
    const s = orthoSection('front', BOUNDS)
    // Lighting Y −17 − margin is three z +17.5.
    close(s.position, [0, 3, 17 + SECTION_MARGIN_M])
    expect(s.target[2]).toBeLessThan(s.position[2])
    close(s.up, [0, 1, 0])
    expect(s.far).toBeGreaterThan(26)
  })

  it('Side stands at +X — audience right, stage left — looking across, the house to the left', () => {
    const s = orthoSection('side', BOUNDS)
    close(s.position, [6 + SECTION_MARGIN_M, 3, 4])
    expect(s.target[0]).toBeLessThan(s.position[0])
    // Looking along −x with y up, screen-right is three's −z: upstage, and the house on the left.
    const forward = [s.target[0] - s.position[0], 0, 0]
    const right = [forward[1] * s.up[2] - forward[2] * s.up[1], forward[2] * s.up[0] - forward[0] * s.up[2], forward[0] * s.up[1] - forward[1] * s.up[0]]
    expect(Math.sign(right[2]!)).toBe(-1)
    expect(s.width).toBeGreaterThan(26)
  })
})

describe('fitZoom', () => {
  it('is the pixels per metre that fits the tighter axis', () => {
    expect(fitZoom(10, 5, 1000, 1000)).toBe(100)
    expect(fitZoom(10, 5, 1000, 200)).toBe(40)
  })

  it('is 1 for a degenerate size rather than Infinity or NaN', () => {
    expect(fitZoom(0, 5, 1000, 1000)).toBe(1)
    expect(fitZoom(10, 5, 0, 0)).toBe(1)
  })
})

describe('the eye', () => {
  it('turns yaw and pitch into a direction and back', () => {
    for (const [yaw, pitch] of [
      [0, 0],
      [0.7, -0.3],
      [-2.5, 0.9],
    ] as const) {
      const d = lookDirection(yaw, pitch)
      const back = lookAngles([0, 0, 0], d)
      expect(back.yaw).toBeCloseTo(yaw, 9)
      expect(back.pitch).toBeCloseTo(pitch, 9)
    }
    // Yaw 0 faces upstage (three −z).
    close(lookDirection(0, 0), [0, 0, -1])
  })

  it('never looks straight up or down, and faces upstage when it has nowhere to look', () => {
    expect(lookAngles([0, 0, 0], [0, 10, 0]).pitch).toBeCloseTo(EYE_PITCH_LIMIT_RAD, 9)
    expect(lookAngles([1, 2, 3], [1, 2, 3])).toEqual({ yaw: 0, pitch: 0 })
  })

  it('is seeded standing where the orbit camera is, facing its target', () => {
    const orbit = defaultOrbitPose(STAGE)
    const eye = eyeFromOrbit(orbit)
    expect(eye.position).toEqual(orbit.position)
    const d = lookDirection(eye.yaw, eye.pitch)
    const to = orbit.target.map((v, i) => v - orbit.position[i]!)
    const n = Math.hypot(...to)
    close(d, to.map((v) => v / n))
  })

  it('keeps the lens between 15° and 90°', () => {
    expect(clampFov(5)).toBe(15)
    expect(clampFov(120)).toBe(90)
    expect(clampFov(50)).toBe(50)
  })
})

describe('framing the selection', () => {
  it('fits a sphere round the points, never smaller than a head and its bar', () => {
    expect(boundingSphere([])).toBeNull()
    expect(boundingSphere([[1, 2, 3]])).toEqual({ centre: [1, 2, 3], radius: MIN_FRAME_RADIUS_M })
    const s = boundingSphere([
      [-4, 5, 0],
      [4, 5, 0],
    ])!
    close(s.centre, [0, 5, 0])
    expect(s.radius).toBe(4)
  })

  it('pulls the orbit camera along the direction it already had until the sphere fills the lens', () => {
    const pose = framedOrbitPose({ position: [0, 0, 20], target: [0, 0, 0] }, [3, 4, 0], 2, 45)
    close(pose.target, [3, 4, 0])
    expect(pose.position[0]).toBeCloseTo(3, 9)
    expect(pose.position[1]).toBeCloseTo(4, 9)
    const distance = pose.position[2]
    expect(distance).toBeGreaterThan(2 / Math.sin((22.5 * Math.PI) / 180))
    expect(distance).toBeLessThan(20)
  })

  it('pulls further out on a canvas narrower than it is tall, where the horizontal field is the tighter', () => {
    const square = framedOrbitPose({ position: [0, 0, 20], target: [0, 0, 0] }, [0, 0, 0], 2, 45, 1)
    const portrait = framedOrbitPose({ position: [0, 0, 20], target: [0, 0, 0] }, [0, 0, 0], 2, 45, 0.5)
    const wide = framedOrbitPose({ position: [0, 0, 20], target: [0, 0, 0] }, [0, 0, 0], 2, 45, 2)
    expect(portrait.position[2]).toBeGreaterThan(square.position[2] * 1.8)
    // Wider than tall, the vertical field still decides.
    expect(wide.position[2]).toBeCloseTo(square.position[2], 9)
  })

  it('slides a section within its plane, so a frame never changes what is cut', () => {
    const plan = orthoSection('plan', BOUNDS)
    const next = framedOrthoPosition(plan.position, plan.target, [2, 5, -3])
    // The camera's height — the plan's section plane — is untouched; only x and z move.
    expect(next.position[1]).toBeCloseTo(plan.position[1], 9)
    close([next.position[0], next.position[2]], [2, -3])
    close([next.target[0], next.target[2]], [2, -3])
  })
})
