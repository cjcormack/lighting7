// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { HOUSING_FILL } from '../bodies/palette'
import { makeLightTexture, makeSurfaceUniforms, SURFACE_AMBIENT } from './surfaceShader'
import {
  applyWorkLights,
  DEFAULT_WORK_LIGHTS,
  isWorkLights,
  resetWorkLightsStore,
  setWorkLights,
  VIEW_OPTION_WORK_LIGHTS,
  WORK_LIGHT_LEVELS,
  WORK_LIGHTS,
  WORK_LIGHTS_KEY,
  workLights,
} from './workLights'

/**
 * Work lights (stage-view menu plan D6, D8): the two-row table, the per-window store, and what a
 * row writes into a canvas's shared surface uniforms.
 */

afterEach(() => {
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetWorkLightsStore()
})

describe('the table', () => {
  it('is off and on, level a', () => {
    expect(WORK_LIGHTS).toEqual(['off', 'on'])
    expect(WORK_LIGHT_LEVELS.on).toEqual({ ambient: 0.02, lift: 0.04, housing: 0.8 })
  })

  it('draws off with today’s constants, which the module restates to stay free of three.js', () => {
    expect(WORK_LIGHT_LEVELS.off).toEqual({ ambient: SURFACE_AMBIENT, lift: 0, housing: HOUSING_FILL })
  })

  it('names its vocabulary, and nothing else is work lights', () => {
    expect(VIEW_OPTION_WORK_LIGHTS).toBe('workLights')
    expect(isWorkLights('off')).toBe(true)
    expect(isWorkLights('on')).toBe(true)
    for (const junk of ['On', 'bright', true, 1, null, undefined]) expect(isWorkLights(junk)).toBe(false)
  })
})

describe('the store', () => {
  it('is off until switched, per tab: sessionStorage, never localStorage', () => {
    expect(DEFAULT_WORK_LIGHTS).toBe('off')
    expect(workLights()).toBe('off')
    setWorkLights('on')
    expect(workLights()).toBe('on')
    expect(window.sessionStorage.getItem(WORK_LIGHTS_KEY)).toBe('"on"')
    expect(window.localStorage.getItem(WORK_LIGHTS_KEY)).toBeNull()
  })

  it('reads a stored value it does not know as off, and refuses to store one', () => {
    window.sessionStorage.setItem(WORK_LIGHTS_KEY, '"bright"')
    expect(workLights()).toBe('off')
    setWorkLights('bright' as never)
    expect(workLights()).toBe('off')
  })
})

describe('applyWorkLights', () => {
  it('writes each row’s ambient and lift into the shared uniforms, and nothing else', () => {
    const uniforms = makeSurfaceUniforms(makeLightTexture().texture)
    const before = { gain: uniforms.uLightGain.value, floor: uniforms.uLiftAlbedoFloor.value }
    expect(uniforms.uAmbient.value).toBe(SURFACE_AMBIENT)
    expect(uniforms.uLift.value).toBe(0)

    applyWorkLights(uniforms, WORK_LIGHT_LEVELS.on)
    expect(uniforms.uAmbient.value).toBe(0.02)
    expect(uniforms.uLift.value).toBe(0.04)
    // Exposure and the floor do not move (D6).
    expect({ gain: uniforms.uLightGain.value, floor: uniforms.uLiftAlbedoFloor.value }).toEqual(before)

    applyWorkLights(uniforms, WORK_LIGHT_LEVELS.off)
    expect(uniforms.uAmbient.value).toBe(SURFACE_AMBIENT)
    expect(uniforms.uLift.value).toBe(0)
  })
})
