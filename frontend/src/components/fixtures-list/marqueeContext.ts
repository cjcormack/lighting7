import { createContext, useContext, useEffect, useSyncExternalStore } from 'react'
import type { CueTarget } from '../../api/cuesApi'
import type { AttributeFamily } from '../../lib/attributeFamily'
import { createValueStore, type ValueStore } from './valueStore'

/**
 * The programmer's marquee, **published** outside the list for the doors that press from beyond
 * the grid (editor-kit plan session 4).
 *
 * The cells are local state in `FixturesListContainer` and stay there — `useCellSelection`'s
 * docblock says why a marquee that moves at pointer rate does not belong in Redux — so the
 * container hands out what it has already derived from them rather than a reader deriving a
 * second copy.
 *
 * **A store, not a context value.** The snapshot changes on every marquee frame, and a context
 * provided above the grid would re-render everything under the provider per frame.
 * `ProgrammerPage` provides a stable store; only a mounted reader subscribes to it, through
 * `useSyncExternalStore`. The two plain lists mount no provider, so their container publishes into
 * nothing and a reader there gets null.
 */
export interface MarqueeSnapshot {
  /**
   * Where a press from this window lands — the selection's heads (`paletteTargetsFor`) — and the
   * families the marquee names, null for rows alone. `usePress`'s pair on the programmer, so a door
   * outside the list (the hand's place on the stack, the Stack table's *+ Look or palette*, Record ▾'s
   * *A new palette*) presses what the grid would. `targetFamilies` and `targetEmitters` are what those
   * heads can take, so that picker offers what Set's Palettes band does.
   */
  press: {
    targets: readonly CueTarget[]
    askedFamilies: readonly AttributeFamily[] | null
    targetFamilies: readonly AttributeFamily[]
    targetEmitters: readonly string[]
  }
}

export type MarqueeStore = ValueStore<MarqueeSnapshot | null>

export function createMarqueeStore(): MarqueeStore {
  return createValueStore<MarqueeSnapshot | null>(null)
}

export const MarqueeStoreContext = createContext<MarqueeStore | null>(null)

const NO_STORE: MarqueeStore = { get: () => null, set: () => {}, subscribe: () => () => {} }

/**
 * Publish the container's marquee while it is mounted. A no-op with no store above — the plain
 * lists. Withdrawn on unmount, so a reader that outlives the grid reads nothing rather than a
 * stale marquee.
 */
export function usePublishMarquee(snapshot: MarqueeSnapshot): void {
  const store = useContext(MarqueeStoreContext)
  useEffect(() => {
    store?.set(snapshot)
  }, [store, snapshot])
  useEffect(() => () => store?.set(null), [store])
}

/** The marquee as last published, or null — no list mounted, or no store (not the programmer). */
export function useMarquee(): MarqueeSnapshot | null {
  const store = useContext(MarqueeStoreContext) ?? NO_STORE
  return useSyncExternalStore(store.subscribe, store.get, store.get)
}
