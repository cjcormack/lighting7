import { useEffect, useRef, useState } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { diffAgainstBaseline, snapshotEntries, type ValueSnapshot } from '@/lib/includeBaseline'
import { includedTargetKey } from '@/lib/includedTarget'

/**
 * How many values have moved since this tab watched Include load something — or `null` if it did
 * not watch.
 *
 * Subscribes to the raw programmer stream rather than an RTK Query cache entry because it has to
 * see the entry map itself, and because the *transition* is what matters: the moment
 * `includedTargetKey` changes, whatever the programmer holds at that instant becomes the baseline.
 *
 * The `null` case is not an edge case to tidy away — it is the honest answer for a reloaded tab or
 * a second tab, and `programmerSource.canClaimInSync` is built around never rendering "in sync"
 * without one. See `lib/includeBaseline.ts` for why the server's real answer is out of reach.
 */
export function useIncludeBaseline(): number | null {
  const baselineRef = useRef<ValueSnapshot | null>(null)
  const targetKeyRef = useRef<string | null>(null)
  const seenFirstRef = useRef(false)
  const [dirty, setDirty] = useState<number | null>(null)

  useEffect(() => {
    const apply = (state: ReturnType<typeof lightingApi.programmer.getState>) => {
      const key = includedTargetKey(state.lastIncluded)
      const current = snapshotEntries(state.entries)

      const first = !seenFirstRef.current
      seenFirstRef.current = true

      if (first) {
        // The FIRST observation is never a baseline, even when a cue is already included — that is
        // precisely the reloaded-tab case, and snapshotting here would make a page refresh report
        // "in sync" over unwritten work. Not knowing has to stay distinguishable from knowing zero.
        targetKeyRef.current = key
        baselineRef.current = null
      } else if (key !== targetKeyRef.current) {
        targetKeyRef.current = key
        // `B` is "nothing included": busking has no source to be dirty against, so drop the
        // baseline rather than keep diffing against a cue the operator has moved off.
        baselineRef.current = state.lastIncluded == null ? null : current
      }

      setDirty(diffAgainstBaseline(baselineRef.current, current))
    }

    apply(lightingApi.programmer.getState())
    const subscription = lightingApi.programmer.subscribe(apply)
    return () => subscription.unsubscribe()
  }, [])

  return dirty
}
