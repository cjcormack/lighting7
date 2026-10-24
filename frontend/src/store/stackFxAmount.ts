import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { ALL_FULL, stackFxAmountOf, type StackFxAmountState } from '../api/stackFxAmountApi'

/**
 * Each cue stack's FX amount — see `api/stackFxAmountApi.ts`.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"), the grand master's shape: a stream with
 * nothing to refetch, in one cache entry fed by the WS layer and seeded from its last frame. The
 * slider writes over WS, because it moves at frame rate; REST's
 * `GET/PUT projects/{id}/cue-stacks/{sid}/fx-amount` is for tools, and nothing here calls it.
 */
const stackFxAmountSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    stackFxAmounts: build.query<StackFxAmountState, void>({
      queryFn: () => ({ data: lightingApi.stackFxAmount.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.stackFxAmount.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useStackFxAmountsQuery } = stackFxAmountSlice

/** [stackId]'s FX amount, 0–1; full before the first frame. Re-renders only when that stack's moves. */
export function useStackFxAmount(stackId: number): number {
  const { amount } = useStackFxAmountsQuery(undefined, {
    selectFromResult: ({ data }) => ({ amount: stackFxAmountOf(data ?? ALL_FULL, stackId) }),
  })
  return amount
}

/** Set one of the live project's stacks' FX amount over WS. */
export function setStackFxAmount(stackId: number, amount: number) {
  lightingApi.stackFxAmount.set(stackId, amount)
}
