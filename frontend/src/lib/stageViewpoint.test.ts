// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import {
  STAGE_VIEWPOINT_KEY,
  applyStageViewOptions,
  consumeLaunchViewpoint,
  isOrthoViewpoint,
  resetStageViewpointStore,
  setStageViewpoint,
  stageViewOptions,
  stageViewpoint,
} from './stageViewpoint'
import {
  EYE_POSE_KEY,
  noteOrbitPose,
  readEyePose,
  readOrbitPose,
  resetLiveOrbitPose,
  writeEyePose,
  writeOrbitPose,
} from './stageCameraPoses'

afterEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetStageViewpointStore()
  resetLiveOrbitPose()
})

describe('the Stage viewpoint (stage-view plan session 1)', () => {
  it('is Orbit until moved, and per tab: sessionStorage, never localStorage', () => {
    expect(stageViewpoint()).toBe('orbit')
    setStageViewpoint('front')
    expect(stageViewpoint()).toBe('front')
    expect(window.sessionStorage.getItem(STAGE_VIEWPOINT_KEY)).toBe('"front"')
    expect(window.localStorage.getItem(STAGE_VIEWPOINT_KEY)).toBeNull()
    // A reload reads it back.
    resetStageViewpointStore()
    expect(stageViewpoint()).toBe('front')
  })

  it('reads a stored value it does not know as Orbit', () => {
    window.sessionStorage.setItem(STAGE_VIEWPOINT_KEY, '"row-f"')
    expect(stageViewpoint()).toBe('orbit')
  })

  it('applies a windows.viewOptions viewpoint, and ignores one outside the vocabulary rather than reading it as Orbit', () => {
    setStageViewpoint('side')
    expect(applyStageViewOptions({ viewpoint: 'plan' })).toBe('plan')
    expect(stageViewpoint()).toBe('plan')
    expect(applyStageViewOptions({ viewpoint: 'row-f' })).toBeUndefined()
    expect(applyStageViewOptions({ focus: 'pads' })).toBeUndefined()
    expect(stageViewpoint()).toBe('plan')
  })

  it('applies ?viewpoint= on arrival and answers the search without it, keeping every other parameter', () => {
    const next = consumeLaunchViewpoint(new URLSearchParams('viewpoint=side&cue=4'))
    expect(stageViewpoint()).toBe('side')
    expect(next?.toString()).toBe('cue=4')
    // A value outside the vocabulary is stripped and not applied; no parameter writes nothing.
    expect(consumeLaunchViewpoint(new URLSearchParams('viewpoint=row-f'))?.toString()).toBe('')
    expect(stageViewpoint()).toBe('side')
    expect(consumeLaunchViewpoint(new URLSearchParams('cue=4'))).toBeNull()
  })

  it('announces the viewpoint under its one key', () => {
    expect(stageViewOptions('eye')).toEqual({ viewpoint: 'eye' })
  })

  it('forgets the eye’s pose when moving into Eye from another camera, and keeps it on a remount already on Eye', () => {
    const eye = { position: [1, 1.7, 9] as const, yaw: 0.2, pitch: -0.1, fov: 40 }
    writeEyePose(eye)
    setStageViewpoint('eye')
    // Seeded afresh from the orbit camera, by the rig, because there is no pose to restore.
    expect(readEyePose()).toBeNull()
    writeEyePose(eye)
    setStageViewpoint('eye')
    expect(readEyePose()).toEqual(eye)
  })

  it('knows the three sections', () => {
    expect(['orbit', 'eye', 'plan', 'front', 'side'].filter((v) => isOrthoViewpoint(v as never))).toEqual(['plan', 'front', 'side'])
  })
})

describe('the camera poses', () => {
  it('round-trips the orbit and eye poses through sessionStorage', () => {
    expect(readOrbitPose()).toBeNull()
    writeOrbitPose({ position: [0, 4, 14], target: [0, 1.5, 0] })
    expect(readOrbitPose()).toEqual({ position: [0, 4, 14], target: [0, 1.5, 0] })
    writeEyePose({ position: [0, 1.7, 12], yaw: 0, pitch: 0, fov: 50 })
    expect(readEyePose()).toEqual({ position: [0, 1.7, 12], yaw: 0, pitch: 0, fov: 50 })
  })

  it('answers the orbit pose noted as the camera moves, ahead of the storage the save writes later', () => {
    writeOrbitPose({ position: [0, 4, 14], target: [0, 1.5, 0] })
    noteOrbitPose({ position: [3, 5, 9], target: [1, 1, 0] })
    // An eye seeded in the render that unmounts the orbit rig sees where the camera is now.
    expect(readOrbitPose()).toEqual({ position: [3, 5, 9], target: [1, 1, 0] })
    expect(JSON.parse(window.sessionStorage.getItem('stage.orbitPose')!)).toEqual({ position: [0, 4, 14], target: [0, 1.5, 0] })
    resetLiveOrbitPose()
    expect(readOrbitPose()).toEqual({ position: [0, 4, 14], target: [0, 1.5, 0] })
  })

  it('reads a malformed pose as none, so a bad value cannot put the camera at NaN', () => {
    window.sessionStorage.setItem('stage.orbitPose', JSON.stringify({ position: [0, 'x', 1], target: [0, 0, 0] }))
    expect(readOrbitPose()).toBeNull()
    window.sessionStorage.setItem(EYE_POSE_KEY, JSON.stringify({ position: [0, 1, 2], yaw: null, pitch: 0, fov: 50 }))
    expect(readEyePose()).toBeNull()
    window.sessionStorage.setItem(EYE_POSE_KEY, '{nope')
    expect(readEyePose()).toBeNull()
  })
})
