import { createContext, useContext, useSyncExternalStore } from 'react'
import { createValueStore, type ValueStore } from './valueStore'

/**
 * **The FX table's selection, published** for the Values grid's rings (tables-colours plan D3):
 * with an FX row selected, the cells that effect paints are ringed on the Values tab. The
 * `marqueeContext.ts` pattern — a stable store `ProgrammerPage` provides, which the FX table writes
 * and each ringable cell reads through a selector, so a selection change re-renders only the cells
 * whose ring moved, and the hidden grid (whose rows are not drawn) none.
 *
 * The store outlives the FX table, which mounts on demand: the selection made there is what the
 * rings show after a switch to Values, and the table takes it back when it mounts again. It holds
 * the selected rows' effect ids, and is per window, as the table's selection is.
 */
export type FxSelectionStore = ValueStore<ReadonlySet<number>>

const NONE: ReadonlySet<number> = new Set()

const sameIds = (a: ReadonlySet<number>, b: ReadonlySet<number>) => a.size === b.size && [...a].every((id) => b.has(id))

export function createFxSelectionStore(): FxSelectionStore {
  return createValueStore(NONE, sameIds)
}

export const FxSelectionStoreContext = createContext<FxSelectionStore | null>(null)

/** The page's store, or null off the programmer — the plain lists and the cue grid ring nothing. */
export function useFxSelectionStore(): FxSelectionStore | null {
  return useContext(FxSelectionStoreContext)
}

const NO_STORE: FxSelectionStore = { get: () => NONE, set: () => {}, subscribe: () => () => {} }

/** Whether any of [effectIds] is selected on the FX table — a boolean, so a cell re-renders only as its ring moves. */
export function useFxRinged(effectIds: readonly number[]): boolean {
  const store = useFxSelectionStore() ?? NO_STORE
  const read = () => {
    const selected = store.get()
    return selected.size > 0 && effectIds.some((id) => selected.has(id))
  }
  return useSyncExternalStore(store.subscribe, read, read)
}
