import { useSyncExternalStore } from 'react'
import type { SpreadOver } from '@/store/programmerOps'
import { createSyncStore, sessionStorageArea } from './syncStore'

/**
 * **The Spread panel's *Over* — Heads or Cells — as the operator last chose it.**
 *
 * It lives here rather than in the panel's form because the panel does not outlive the selection:
 * row C's Spread popover is mounted only while there is a marquee, and the rail's Spread tab draws
 * an empty state in the panel's place when the marquee holds nothing it can spread. Kept in the
 * form, *Cells* was lost every time the operator deselected on the way to the next thing to spread.
 * Kept here, a panel that mounts again opens on the choice.
 *
 * **One fact for every host**, the way `sidePanelMode.ts` is one fact for both panels: the busk
 * tab, the rail tab and the popover are one panel, and a Spread that said Cells on one surface and
 * Heads on the next would be two answers to one question. A host with nothing to split — the kit
 * sheets, any selection of single-head fixtures — draws Cells chosen and spreads as Heads
 * (`SpreadPanel`'s `sendFor`, and the desk's `programmerSpread.kt`).
 *
 * **Per tab, in `sessionStorage`**, like every other fact about how this window is being worked:
 * two desk screens are two windows of one profile.
 */

const store = createSyncStore<SpreadOver>({
  key: 'desk.spread.over',
  fallback: 'HEADS',
  parse: (parsed) => (parsed === 'CELLS' ? 'CELLS' : 'HEADS'),
  storage: sessionStorageArea,
})

export function useSpreadOver(): SpreadOver {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

export function getSpreadOver(): SpreadOver {
  return store.getSnapshot()
}

export function setSpreadOver(over: SpreadOver): void {
  store.set(over)
}

/** Test seam — a module-level cache outlives `sessionStorage.clear()`. */
export function resetSpreadOverStore(): void {
  store.reset()
}
