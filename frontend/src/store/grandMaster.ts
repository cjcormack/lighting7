import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { AT_FULL, type GrandMasterState } from '../api/grandMasterApi'

/**
 * The grand master (venue-visit plan S5) — see `api/grandMasterApi.ts`.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"): `grandMaster.state` is a stream with
 * nothing to refetch, in one cache entry fed by the WS layer and seeded from its last frame. The
 * app header's fader reads it, so this slice is on the earliest render path, which form 3 is safe
 * on: the `queryFn` touches `lightingApi` only when the first reader mounts.
 *
 * The fader writes over WS (`setGrandMasterLevel`), because it moves at frame rate; REST's
 * `GET/PUT projects/{id}/grand-master` is for scripts and tools, and nothing here calls it.
 */
const grandMasterSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    grandMaster: build.query<GrandMasterState, void>({
      queryFn: () => ({ data: lightingApi.grandMaster.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.grandMaster.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useGrandMasterQuery } = grandMasterSlice

/** The live project's grand master, 0–100; full before the first frame. */
export function useGrandMasterLevel(): number {
  const { data } = useGrandMasterQuery()
  return (data ?? AT_FULL).level
}

/** Set the live project's grand master over WS. */
export function setGrandMasterLevel(level: number) {
  lightingApi.grandMaster.set(level)
}
