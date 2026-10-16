import { useMemo } from 'react'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { NO_HOLDS, ringOf, type CueHoldsState, type CueRing } from '../api/cueHoldsApi'

/**
 * What holds each live cue (lighting7 cue model plan §3.4) — the `cueHolds.state` stream in one
 * cache entry.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"): a stream with nothing to refetch, fed by
 * the WS layer and seeded from its last frame. The cue-slot overlay reads it and is mounted on the
 * first paint, which form 3 is safe on: the `queryFn` touches `lightingApi` only when the first
 * reader mounts.
 */
const cueHoldsSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    cueHolds: build.query<CueHoldsState, void>({
      queryFn: () => ({ data: lightingApi.cueHolds.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.cueHolds.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useCueHoldsQuery } = cueHoldsSlice

/**
 * Every live cue of [projectId], as the desk last said. Empty before the first frame, for another
 * project's frame (a switch in flight), and with no project.
 */
export function useCueHolds(projectId: number | undefined): CueHoldsState {
  const { data } = useCueHoldsQuery(undefined, { skip: projectId == null })
  return data != null && data.projectId === projectId ? data : NO_HOLDS
}

/**
 * How each live cue of [projectId] draws on a pad (D11): `loose` when a press holds it, `stack` when
 * only a stack does. A cue absent from the map is off — so the map's keys are every cue on stage.
 *
 * It replaced `useActiveCueIds` (cue model plan session 2), and keeps that hook's rule: "is it on
 * stage?" comes from what holds the cue, never from the FX effect stream — a cue made of property
 * assignments and effect-free Look layers spawns no `FxInstance` at all, so an effect-derived answer
 * reads a rows-only cue as never running (FS-BUG-CUESLOT-LIVENESS) — and never from the stacks'
 * playheads either, because a cue a pad or slot holds loose is on stage with no stack behind it.
 */
export function useCueRings(projectId: number | undefined): Map<number, CueRing> {
  const holds = useCueHolds(projectId)
  return useMemo(() => {
    const rings = new Map<number, CueRing>()
    for (const cue of holds.cues) {
      const ring = ringOf(cue)
      if (ring != null) rings.set(cue.cueId, ring)
    }
    return rings
  }, [holds])
}
