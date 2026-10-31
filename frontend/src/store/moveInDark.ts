import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { moveInDarkHeadsOf, NOTHING_MOVING, type MoveInDarkHead, type MoveInDarkState } from '../api/moveInDarkApi'

/**
 * What the live show's stacks move in the dark — see `api/moveInDarkApi.ts`.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"), the stack FX amount's shape: a stream
 * with nothing to refetch, in one cache entry fed by the WS layer and seeded from its last frame.
 */
const moveInDarkSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    moveInDark: build.query<MoveInDarkState, void>({
      queryFn: () => ({ data: lightingApi.moveInDark.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.moveInDark.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useMoveInDarkQuery } = moveInDarkSlice

/** The heads [stackId] is moving in the dark, or waiting to; none before the first frame. */
export function useMoveInDarkHeads(stackId: number): MoveInDarkHead[] {
  const { heads } = useMoveInDarkQuery(undefined, {
    selectFromResult: ({ data }) => ({ heads: moveInDarkHeadsOf(data ?? NOTHING_MOVING, stackId) }),
  })
  return heads
}
