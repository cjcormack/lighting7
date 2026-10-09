import { useEffect, useLayoutEffect, useMemo } from 'react'
import type { StageStatsStore } from './stageStats'
import { maskImagesOf, sceneMasks, type SceneMaskCache } from './sceneMasks'
import { partTransmit } from './sceneParts'
import type { SceneBuild } from './StageSceneElements'

/**
 * Hold the masks the drawn scene's painted cloths are cut by (scrim plan D5), from the module-level
 * cache (`sceneMasks.ts`), for as long as the scene names them. In a **layout** effect, as the paint
 * is asked for (`usePaintTexture`), so a `render_view` capture's scene has asked for every mask by
 * the time it reports ready and `StageRenderJob` waits for them. The emitters hear each one land
 * (`StageEmitters`' pack) and ask for the frame; nothing here re-renders on it.
 */
export function useSceneMaskHolds(
  projectId: number | null,
  builds: readonly SceneBuild[],
  cache: SceneMaskCache = sceneMasks,
): void {
  const key = useMemo(
    () => maskImagesOf(builds.flatMap(({ build }) => build.parts.map(partTransmit))).join(','),
    [builds],
  )
  useLayoutEffect(() => {
    if (projectId == null || key === '') return
    const releases = key.split(',').map((hash) => cache.acquire(projectId, hash))
    return () => releases.forEach((release) => release())
  }, [cache, projectId, key])
}

/**
 * Report on a canvas's stats store how many masks with holes the atlas has no layer for — drawn and
 * lit solid (D8's cap of 32) — so the Performance tab can name them.
 */
export function useMasksOverCapStat(stats: StageStatsStore | null | undefined, cache: SceneMaskCache = sceneMasks): void {
  useEffect(() => {
    if (stats == null) return
    const report = () => stats.setMasksOverCap(cache.overCap())
    report()
    const unsubscribe = cache.subscribe(report)
    return () => {
      unsubscribe()
      stats.setMasksOverCap(0)
    }
  }, [stats, cache])
}
