// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

// The canvas's `invalidate`, recorded: a work-lights switch is a uniform write and must ask for a frame.
const invalidate = vi.fn()
vi.mock('../stageInvalidate', () => ({ useStageInvalidate: () => invalidate }))

import { LAMBERT_LOBES } from './lobes'
import type { PartFinish } from './sceneParts'
import { SurfaceLightingProvider, useSurfaceLighting, useSurfaceMaterial, useWorkLightLevels } from './SurfaceLighting'
import { LIFT_ALBEDO_FLOOR, SURFACE_AMBIENT, SURFACE_LIGHT_GAIN } from './surfaceShader'
import { WORK_LIGHT_LEVELS, type WorkLights } from './workLights'

const FINISH: PartFinish = { colour: '#2a2a2d', pattern: 'PLAIN', emissive: false, lobes: LAMBERT_LOBES }

describe('a surface behind', () => {
  it('loses a depth tie to a coincident surface, and only when asked', () => {
    const behind = renderHook(() => useSurfaceMaterial(FINISH, { behind: true }), { wrapper: SurfaceLightingProvider })
    const plain = renderHook(() => useSurfaceMaterial(FINISH), { wrapper: SurfaceLightingProvider })
    expect(behind.result.current.polygonOffset).toBe(true)
    expect(behind.result.current.polygonOffsetFactor).toBeGreaterThan(0)
    expect(behind.result.current.polygonOffsetUnits).toBeGreaterThan(0)
    expect(plain.result.current.polygonOffset).toBe(false)
    behind.unmount()
    plain.unmount()
  })

  it('rebuilds the material when the option changes', () => {
    const { result, rerender, unmount } = renderHook(
      ({ behind }: { behind: boolean }) => useSurfaceMaterial(FINISH, { behind }),
      { wrapper: SurfaceLightingProvider, initialProps: { behind: false } },
    )
    expect(result.current.polygonOffset).toBe(false)
    rerender({ behind: true })
    expect(result.current.polygonOffset).toBe(true)
    unmount()
  })
})

describe('work lights on a canvas (stage-view menu plan D6)', () => {
  beforeEach(() => invalidate.mockClear())

  const withWorkLights = (workLights: WorkLights) =>
    function Wrapper({ children }: { children: ReactNode }) {
      return <SurfaceLightingProvider workLights={workLights}>{children}</SurfaceLightingProvider>
    }

  it('writes off as today’s room: ambient and no lift', () => {
    const { result, unmount } = renderHook(() => useSurfaceMaterial(FINISH), { wrapper: SurfaceLightingProvider })
    expect(result.current.uniforms.uAmbient.value).toBe(SURFACE_AMBIENT)
    expect(result.current.uniforms.uLift.value).toBe(0)
    expect(result.current.uniforms.uLiftAlbedoFloor.value).toBe(LIFT_ALBEDO_FLOOR)
    expect(result.current.uniforms.uLightGain.value).toBe(SURFACE_LIGHT_GAIN)
    unmount()
  })

  it('writes on into every material from its first frame — the uniforms are shared, not copied', () => {
    const { result, unmount } = renderHook(
      () => ({ a: useSurfaceMaterial(FINISH), b: useSurfaceMaterial(FINISH, { fill: 0.2 }), lighting: useSurfaceLighting() }),
      { wrapper: withWorkLights('on') },
    )
    for (const material of [result.current.a, result.current.b]) {
      expect(material.uniforms.uAmbient.value).toBe(WORK_LIGHT_LEVELS.on.ambient)
      expect(material.uniforms.uLift.value).toBe(WORK_LIGHT_LEVELS.on.lift)
      expect(material.uniforms.uLift).toBe(result.current.lighting.uniforms.uLift)
    }
    // The material's own fill is its own: the lift adds to it in the shader, never replaces it.
    expect(result.current.b.uniforms.uFill.value).toBe(0.2)
    unmount()
  })

  it('switches by writing the uniforms, recompiles nothing, and asks for a frame', () => {
    let workLights: WorkLights = 'off'
    const { result, rerender, unmount } = renderHook(
      () => ({ material: useSurfaceMaterial(FINISH), levels: useWorkLightLevels() }),
      {
        wrapper: function Wrapper({ children }: { children: ReactNode }) {
          return <SurfaceLightingProvider workLights={workLights}>{children}</SurfaceLightingProvider>
        },
      },
    )
    const material = result.current.material
    const defines = { ...material.defines }
    invalidate.mockClear()

    workLights = 'on'
    rerender()
    expect(result.current.material).toBe(material)
    expect(material.defines).toEqual(defines)
    expect(material.uniforms.uAmbient.value).toBe(WORK_LIGHT_LEVELS.on.ambient)
    expect(material.uniforms.uLift.value).toBe(WORK_LIGHT_LEVELS.on.lift)
    expect(result.current.levels).toBe(WORK_LIGHT_LEVELS.on)
    expect(invalidate).toHaveBeenCalled()

    invalidate.mockClear()
    workLights = 'off'
    rerender()
    expect(material.uniforms.uAmbient.value).toBe(SURFACE_AMBIENT)
    expect(material.uniforms.uLift.value).toBe(0)
    expect(invalidate).toHaveBeenCalled()
    unmount()
  })
})
