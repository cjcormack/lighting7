// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SurfaceLightingProvider, useSurfaceMaterial } from './SurfaceLighting'

const FINISH = { colour: '#2a2a2d', pattern: 'PLAIN', emissive: false } as const

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
