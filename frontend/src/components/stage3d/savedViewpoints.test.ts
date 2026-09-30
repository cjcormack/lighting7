import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../api/stageElementApi'
import type { StageViewpointDto } from '../../api/stageViewpointApi'
import { resolveSavedViewpoint, savedViewNote, viewpointFromCamera } from './savedViewpoints'
import { eyeTarget } from './stageCameras'

const stalls: StageElementDto = {
  id: 1, uuid: 'stalls', name: 'Stalls', kind: 'SEATING', layer: 'VENUE',
  positionX: 0, positionY: -2.4, positionZ: -0.95, yawDeg: 0,
  widthM: 0, depthM: 0, heightM: 0, finishColour: null, finishPattern: null, emissive: false,
  params: { rows: 12, seatsPerRow: 12, rowPitchM: 0.95, seatPitchM: 0.52 }, hidden: false, sortOrder: 0,
}

function row(fields: Partial<StageViewpointDto>): StageViewpointDto {
  return {
    id: 1, uuid: '5b1f7a52-9c3e-4d8a-8f2e-1a2b3c4d5e6f', name: 'View', kind: 'EYE',
    eyeX: null, eyeY: null, eyeZ: null, targetX: null, targetY: null, targetZ: null,
    fovDeg: null, seatElementUuid: null, seatId: null, sortOrder: 0, ...fields,
  }
}

describe('a saved viewpoint, landed (stage-view plan session 2)', () => {
  it('lands an eye view where it stands, facing its target, in three.js space', () => {
    const desk = row({ kind: 'EYE', eyeX: 1.25, eyeY: -17, eyeZ: 2.6, targetX: 0, targetY: 2.6, targetZ: 0.8, fovDeg: 50 })
    const landing = resolveSavedViewpoint(desk, [])!
    expect(landing.camera).toBe('eye')
    if (landing.camera !== 'eye') return
    expect(landing.pose.position).toEqual([1.25, 2.6, 17])
    expect(landing.pose.fov).toBe(50)
    // Looking upstage (three's −z) and a little down and to the left.
    expect(Math.abs(landing.pose.yaw)).toBeLessThan(0.1)
    expect(landing.pose.pitch).toBeLessThan(0)
  })

  it('lands a seat view at the seat’s seated eye, looking at the stage when it names no target', () => {
    const rowF = row({ kind: 'SEAT', seatElementUuid: 'stalls', seatId: 'F6' })
    const landing = resolveSavedViewpoint(rowF, [stalls])!
    expect(landing.camera).toBe('eye')
    if (landing.camera !== 'eye') return
    const [x, y, z] = landing.pose.position
    expect(x).toBeCloseTo(-0.26, 9)
    expect(y).toBeCloseTo(-0.95 + 1.15, 9)
    expect(z).toBeCloseTo(2.4 + 5 * 0.95 + 0.05, 9)
    expect(landing.pose.fov).toBe(52)
    expect(savedViewNote(rowF)).toBe('seat F6')
  })

  it('cannot land a seat whose seating is gone or has no such seat, nor an eye view missing a point', () => {
    expect(resolveSavedViewpoint(row({ kind: 'SEAT', seatElementUuid: 'stalls', seatId: 'F6' }), [])).toBeNull()
    expect(resolveSavedViewpoint(row({ kind: 'SEAT', seatElementUuid: 'stalls', seatId: 'Z1' }), [stalls])).toBeNull()
    expect(resolveSavedViewpoint(row({ kind: 'EYE', eyeX: 1, eyeY: 2, eyeZ: 3 }), [])).toBeNull()
  })

  it('saves the camera it is given, and lands back on it', () => {
    const pose = { position: [1.25, 2.6, 17] as const, yaw: 0.3, pitch: -0.2, fov: 48 }
    const request = viewpointFromCamera('Balcony · desk', { kind: 'eye', pose })
    expect(request).toMatchObject({ name: 'Balcony · desk', kind: 'EYE', eyeX: 1.25, eyeY: -17, eyeZ: 2.6, fovDeg: 48 })
    const landed = resolveSavedViewpoint(row({ ...request, name: request.name }) as StageViewpointDto, [])!
    if (landed.camera !== 'eye') throw new Error('eye')
    expect(landed.pose.yaw).toBeCloseTo(0.3, 3)
    expect(landed.pose.pitch).toBeCloseTo(-0.2, 3)

    const orbit = viewpointFromCamera('Wide', { kind: 'orbit', pose: { position: [0, 4, 14], target: [0, 1.5, 0] } })
    expect(orbit).toMatchObject({ kind: 'ORBIT', eyeX: 0, eyeY: -14, eyeZ: 4, targetX: 0, targetY: -0, targetZ: 1.5 })
    expect(orbit.fovDeg).toBeUndefined()
  })

  it('saves a look round from a seat as a seat view of the same seat, with the head turned', () => {
    const rowF = row({ kind: 'SEAT', seatElementUuid: 'stalls', seatId: 'F6' })
    const pose = { position: [-0.26, 0.2, 7.2] as const, yaw: 0.5, pitch: 0, fov: 52 }
    const request = viewpointFromCamera('Row F, looking SL', { kind: 'eye', pose, seat: rowF })
    expect(request).toMatchObject({ kind: 'SEAT', seatElementUuid: 'stalls', seatId: 'F6', fovDeg: 52 })
    expect(request.eyeX).toBeUndefined()
    const ahead = eyeTarget(pose)
    expect(request.targetX).toBeCloseTo(ahead[0], 3)
  })
})
