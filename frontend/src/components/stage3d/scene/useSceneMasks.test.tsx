// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { StageElementDto } from '../../../api/stageElementApi'
import { buildElement } from './builders'
import { createSceneMaskCache, type SceneMaskCache } from './sceneMasks'
import { createStageStats } from './stageStats'
import type { SceneBuild } from './StageSceneElements'
import { useMasksOverCapStat, useSceneMaskHolds } from './useSceneMasks'

const A = 'a'.repeat(64)
const B = 'b'.repeat(64)

function drape(paint: Record<string, string> | null, fields: Partial<StageElementDto> = {}): SceneBuild {
  const element: StageElementDto = {
    id: 1, uuid: 'd', name: 'D', kind: 'DRAPE', layer: 'SET', positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0,
    widthM: 6, depthM: 0.1, heightM: 4, finishColour: null, finishPattern: null, emissive: false,
    params: { operation: 'DRAW', ...(paint && { paint }) }, hidden: false, sortOrder: 0, ...fields,
  }
  return { element, build: buildElement(element) }
}

function spyCache(): SceneMaskCache & { held: Map<string, number> } {
  const cache = createSceneMaskCache({ load: async () => null })
  const held = new Map<string, number>()
  const acquire = cache.acquire
  return Object.assign(cache, {
    held,
    acquire: vi.fn((projectId: number, hash: string) => {
      held.set(hash, (held.get(hash) ?? 0) + 1)
      const release = acquire(projectId, hash)
      return () => {
        held.set(hash, (held.get(hash) ?? 0) - 1)
        release()
      }
    }),
  })
}

describe('the scene’s masks (scrim plan D5)', () => {
  it('holds each painted image the scene names once, however many parts name it, and lets go on unmount', () => {
    const cache = spyCache()
    const builds = [drape({ front: A }), drape({ front: A }, { uuid: 'e' }), drape({ back: B }, { uuid: 'f' }), drape(null, { uuid: 'g' })]
    const { rerender, unmount } = renderHook(({ b }: { b: SceneBuild[] }) => useSceneMaskHolds(7, b, cache), { initialProps: { b: builds } })
    expect(cache.acquire).toHaveBeenCalledTimes(2)
    expect([...cache.held]).toEqual([[A, 1], [B, 1]])
    // A rebuild naming the same images holds nothing again.
    rerender({ b: [...builds] })
    expect(cache.acquire).toHaveBeenCalledTimes(2)
    // One cloth unpainted: its mask is let go.
    rerender({ b: [drape({ front: A })] })
    expect(cache.held.get(B)).toBe(0)
    unmount()
    expect(cache.held.get(A)).toBe(0)
  })

  it('holds nothing without a project', () => {
    const cache = spyCache()
    renderHook(() => useSceneMaskHolds(null, [drape({ front: A })], cache))
    expect(cache.acquire).not.toHaveBeenCalled()
  })

  it('reports the masks past the atlas on the stats store, and clears it on unmount', () => {
    const cache = createSceneMaskCache({ load: async () => null })
    const overCap = vi.spyOn(cache, 'overCap').mockReturnValue(3)
    const stats = createStageStats()
    const { unmount } = renderHook(() => useMasksOverCapStat(stats, cache))
    expect(stats.getSnapshot().masksOverCap).toBe(3)
    overCap.mockReturnValue(0)
    unmount()
    expect(stats.getSnapshot().masksOverCap).toBe(0)
  })
})
