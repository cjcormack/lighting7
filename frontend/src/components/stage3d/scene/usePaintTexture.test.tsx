// @vitest-environment jsdom
import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Vector2 } from 'three'
import { createPaintTextureCache, type PaintImageLoader } from './paintTextures'
import { usePaintTexture } from './usePaintTexture'
import { makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms, setPaintTextures } from './surfaceShader'
import { FINISH_LOBES } from './sceneParts'

const HASH = 'e'.repeat(64)
const image = () => ({ width: 4, height: 2, close: vi.fn() }) as unknown as TexImageSource

function material() {
  return makeSurfaceMaterial(
    makeSurfaceUniforms(makeLightTexture().texture),
    { colour: '#e9e5da', pattern: 'PLAIN', emissive: false, lobes: FINISH_LOBES.MATTE },
    { doubleSided: true, painted: true },
  )
}

describe('a painted face’s texture in the scene (scrim plan D4)', () => {
  it('answers the image once it has loaded, and the detail copy once Full detail is on and it is in', async () => {
    const load = vi.fn<PaintImageLoader>(async () => image())
    const cache = createPaintTextureCache({ load })
    const { result, rerender, unmount } = renderHook(
      ({ variant }: { variant: 'display' | 'detail' }) => usePaintTexture(7, HASH, variant, cache),
      { initialProps: { variant: 'display' as 'display' | 'detail' } },
    )
    await waitFor(() => expect(result.current).not.toBeNull())
    expect(result.current).toBe(cache.texture(HASH, 'display'))
    rerender({ variant: 'detail' })
    await waitFor(() => expect(result.current).toBe(cache.texture(HASH, 'detail')))
    expect(load.mock.calls.map(([u]) => u)).toEqual([
      `/api/rest/projects/7/scene-images/${HASH}?variant=display`,
      `/api/rest/projects/7/scene-images/${HASH}?variant=detail`,
    ])
    unmount()
  })

  it('draws a cloth whose image is missing on this machine unpainted, and never throws', async () => {
    const cache = createPaintTextureCache({ load: async () => null })
    const { result, unmount } = renderHook(() => usePaintTexture(7, HASH, 'display', cache))
    await act(() => cache.settled())
    expect(cache.state(HASH, 'display')).toBe('missing')
    expect(result.current).toBeNull()
    // Bound as it would be: nothing on, so the shader draws the finish.
    const m = material()
    setPaintTextures(m, result.current, null)
    expect(m.uniforms.uPaintOn.value).toEqual(new Vector2(0, 0))
    unmount()
  })

  it('holds nothing without a project or an image', () => {
    const load = vi.fn<PaintImageLoader>(async () => image())
    const cache = createPaintTextureCache({ load })
    renderHook(() => usePaintTexture(null, HASH, 'display', cache)).unmount()
    renderHook(() => usePaintTexture(7, undefined, 'display', cache)).unmount()
    expect(load).not.toHaveBeenCalled()
    expect(cache.size()).toBe(0)
  })
})
