import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { ALL_FULL, stackEffectSizeOf, type StackEffectSizeState } from '../api/stackEffectSizeApi'

/**
 * Each cue stack's effect size — see `api/stackEffectSizeApi.ts`.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"), the grand master's shape: a stream with
 * nothing to refetch, in one cache entry fed by the WS layer and seeded from its last frame. The
 * slider writes over WS, because it moves at frame rate; REST's
 * `GET/PUT projects/{id}/cue-stacks/{sid}/effect-size` is for tools, and nothing here calls it.
 */
const stackEffectSizeSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    stackEffectSizes: build.query<StackEffectSizeState, void>({
      queryFn: () => ({ data: lightingApi.stackEffectSize.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.stackEffectSize.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useStackEffectSizesQuery } = stackEffectSizeSlice

/** [stackId]'s effect size, 0–1; full before the first frame. Re-renders only when that stack's moves. */
export function useStackEffectSize(stackId: number): number {
  const { size } = useStackEffectSizesQuery(undefined, {
    selectFromResult: ({ data }) => ({ size: stackEffectSizeOf(data ?? ALL_FULL, stackId) }),
  })
  return size
}

/** Set one of the live project's stacks' effect size over WS. */
export function setStackEffectSize(stackId: number, size: number) {
  lightingApi.stackEffectSize.set(stackId, size)
}
