import { useCallback, useRef, useSyncExternalStore } from 'react'
import { getChannelValue, subscribeToChannels } from '@/hooks/usePropertyValues'
import { useChannelSource } from '@/hooks/useChannelSource'
import type { ChannelRef } from '@/store/fixtures'

const NONE: readonly number[] = []

/**
 * The live value of each of [refs], in order — a row over a pick of heads reads its heads' channels
 * through one subscription (`subscribeToChannels` coalesces a batch to one wake-up) rather than a
 * hook per head, which a pick of variable size could not call.
 *
 * The snapshot keeps its identity while no value moves, as `useSyncExternalStore` requires. Read at
 * the sheet's channel source, as the single-head rows' `useSliderValue` is.
 *
 * [refs] should be memoised by the caller: a new array resubscribes.
 */
export function useChannelValues(refs: readonly ChannelRef[]): readonly number[] {
  const source = useChannelSource()
  const cached = useRef<{ refs: readonly ChannelRef[]; values: readonly number[] } | null>(null)

  const subscribe = useCallback(
    (callback: () => void) => (refs.length === 0 ? () => {} : subscribeToChannels([...refs], callback, source)),
    [refs, source],
  )

  const getSnapshot = useCallback((): readonly number[] => {
    if (refs.length === 0) return NONE
    const values = refs.map((ref) => getChannelValue(ref, source))
    const last = cached.current
    if (last != null && last.refs === refs && last.values.length === values.length && last.values.every((v, i) => v === values[i])) {
      return last.values
    }
    cached.current = { refs, values }
    return values
  }, [refs, source])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
