import { useMemo } from 'react'
import { skipToken } from '@reduxjs/toolkit/query'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { heldBackReach } from '@/lib/heldBack'
import { useActiveEffectsQuery } from '@/store/fixtureFx'

const NONE: ReadonlySet<string> = new Set()

/**
 * The programmer grid's held-back reach (fixture-fx-sheets plan D19): every `(head, property)` a
 * non-band effect paints, from the effect list — `lib/heldBack.ts`'s [heldBackReach]. One
 * subscription for the grid, handed to every row; the cell asks the rest of the rule of its own
 * keys. Off (an empty set, no subscription) where the grid draws no ownership.
 */
export function useHeldBackReach(enabled: boolean): ReadonlySet<string> {
  const { data: effects } = useActiveEffectsQuery(enabled ? undefined : skipToken)
  const { fixtures } = useFixtureLookup()
  return useMemo(() => (enabled ? heldBackReach(effects, fixtures ?? []) : NONE), [enabled, effects, fixtures])
}
