import { useCallback, useEffect, useState } from 'react'
import { useStableCallback } from '@/hooks/useStableCallback'

/** How long Duplicate's copies wait for the stack's refetch before they are forgotten. */
export const LAND_TIMEOUT_MS = 5_000

/**
 * Duplicate's copies landing in the Show view. The answer names the copies' entries before the
 * stack's refetch lists them, so they wait until every one is among the stack's [entries], then go
 * to [land] once. They are dropped on leaving the stack, or after {@link LAND_TIMEOUT_MS}.
 *
 * Returns the `onDuplicated` a host passes to `CueActions`.
 */
export function useLandCopies(
  stackId: number,
  entries: readonly number[],
  land: (entryIds: number[]) => void,
): (entryIds: number[]) => void {
  const [pending, setPending] = useState<{ stackId: number; entryIds: number[] } | null>(null)
  const onLand = useStableCallback(land)
  useEffect(() => {
    if (pending == null) return
    if (pending.stackId !== stackId) {
      setPending(null)
      return
    }
    const present = new Set(entries)
    if (!pending.entryIds.every((id) => present.has(id))) return
    setPending(null)
    onLand(pending.entryIds)
  }, [entries, onLand, pending, stackId])
  useEffect(() => {
    if (pending == null) return
    const timer = setTimeout(() => setPending(null), LAND_TIMEOUT_MS)
    return () => clearTimeout(timer)
  }, [pending])
  return useCallback((entryIds: number[]) => setPending({ stackId, entryIds }), [stackId])
}
