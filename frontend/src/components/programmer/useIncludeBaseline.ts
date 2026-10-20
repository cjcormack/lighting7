import { useEffect, useRef, useState } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { diffAgainstBaseline, snapshotEntries, withSceneryChanges, type ValueSnapshot } from '@/lib/includeBaseline'
import { includedTargetKey } from '@/lib/includedTarget'
import { useProgrammerScenery } from '@/store/programmer'

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
 *
 * **The baseline is taken from a `programmer.state` frame.** A REST Include broadcasts the new
 * target at once (`programmer.includeTarget`), but the entries it staged reach this tab only with
 * the state refetch that follows, so a baseline taken at the target's own frame held the entries
 * from before the Include and counted every included value as a change. On that frame the hook
 * asks for the state itself — the desk answers after the target was set, so the answer holds the
 * Include — and reads no count until it lands.
 *
 * Held scenery adds to the count (scenery-programmer plan session 3): Update writes it back too, so
 * moving the tabs after Include must un-grey Update. That half the desk counts itself
 * (`changedSinceInclude` on `programmer.sceneryState`) — see `withSceneryChanges`.
 */
export function useIncludeBaseline(): number | null {
  const baselineRef = useRef<ValueSnapshot | null>(null)
  const targetKeyRef = useRef<string | null>(null)
  const seenFirstRef = useRef(false)
  const snapshotSeqRef = useRef<number | null>(null)
  // A target arrived without its entries; the baseline waits for the next state frame.
  const awaitingRef = useRef(false)
  const [dirty, setDirty] = useState<number | null>(null)

  useEffect(() => {
    const apply = (state: ReturnType<typeof lightingApi.programmer.getState>) => {
      const key = includedTargetKey(state.lastIncluded)
      const current = snapshotEntries(state.entries)
      const fresh = state.snapshotSeq !== snapshotSeqRef.current
      snapshotSeqRef.current = state.snapshotSeq

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
        baselineRef.current = state.lastIncluded == null || !fresh ? null : current
        awaitingRef.current = state.lastIncluded != null && !fresh
        if (awaitingRef.current) lightingApi.programmer.requestState()
      } else if (awaitingRef.current && fresh) {
        baselineRef.current = current
        awaitingRef.current = false
      }

      setDirty(awaitingRef.current ? 0 : diffAgainstBaseline(baselineRef.current, current))
    }

    apply(lightingApi.programmer.getState())
    const subscription = lightingApi.programmer.subscribe(apply)
    return () => subscription.unsubscribe()
  }, [])

  const scenery = useProgrammerScenery()
  return withSceneryChanges(dirty, scenery.changedSinceInclude)
}
