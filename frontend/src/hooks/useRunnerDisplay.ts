import { useMemo } from 'react'
import type { RunnerDisplayState } from '../components/runner/mobile/RunMobile'
import { positionLabelFor } from '../lib/promptBook/geometry'
import { useProjectCueLocationsQuery, useProjectPromptBookQuery } from '../store/promptBooks'
import type { ShowBarState } from './useShowBarProps'

/**
 * What the phone runner draws — the live and armed cue entries, the stack a boundary GO crosses
 * into, and the transport's three cursors — as one memoised object.
 *
 * Lifted out of `ShowPage`, where it was built inline, so the busk view's Show tab
 * (`components/busking/ShowTab.tsx`) and the phone cannot disagree about which cue is next: both
 * mount `RunMobile` and both feed it from here (busk-chrome plan §3.3).
 *
 * It takes the `useShowBarProps` result rather than `(projectId, transport)` as the plan
 * sketched, because that hook already resolves `activeCue`, `standbyCue` and `nextStack` from the
 * same cache — re-deriving them here would be a second copy of the cue lookup, which is the drift
 * that hook's docblock exists to end. The caller holds one bar-props instance per route and hands
 * it in.
 *
 * Memoised on the eight inputs and not on `transport`, which is a fresh object literal every render:
 * a host re-rendering per fade frame would otherwise hand `RunMobile` a new object 60×/s.
 */
export function useRunnerDisplay({
  transport,
  activeCue,
  standbyCue,
  nextStack,
}: Pick<ShowBarState, 'transport' | 'activeCue' | 'standbyCue' | 'nextStack'>): RunnerDisplayState {
  const { activeCueId, standbyCueId, activeEntryId, standbyEntryId, completedCueIds } = transport
  return useMemo(
    () => ({ activeCue, standbyCue, nextStack, activeCueId, standbyCueId, activeEntryId, standbyEntryId, completedCueIds }),
    [activeCue, standbyCue, nextStack, activeCueId, standbyCueId, activeEntryId, standbyEntryId, completedCueIds],
  )
}

/**
 * Each cue's prompt-book reading position ("top of p. 9"), by cue id — what the runner's cards
 * carry under the fade. Empty when the project has no book, in which case the label simply does
 * not render. Lifted beside [useRunnerDisplay] for the same two hosts.
 */
export function useCueLocationLabels(projectId: number): Map<number, string> {
  const { data: cueLocations } = useProjectCueLocationsQuery(projectId)
  const { data: promptBook } = useProjectPromptBookQuery(projectId)
  const coverPages = promptBook?.coverPages ?? 0
  return useMemo(() => {
    // A shared cue has a position per anchored entry; the card says the earliest.
    const earliest = new Map<number, { page: number; y: number }>()
    for (const l of cueLocations ?? []) {
      const seen = earliest.get(l.cueId)
      if (seen == null || l.page < seen.page || (l.page === seen.page && l.y < seen.y)) earliest.set(l.cueId, l)
    }
    return new Map([...earliest].map(([cueId, l]) => [cueId, positionLabelFor(l.page, l.y, coverPages)]))
  }, [cueLocations, coverPages])
}
