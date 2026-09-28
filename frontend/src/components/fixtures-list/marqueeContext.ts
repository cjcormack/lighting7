import { createContext, useContext, useEffect, useSyncExternalStore } from 'react'
import type { CellKeyboardPermission } from '../sheet/cellEntry'
import type { ColumnKey } from './columns'
import type { CellBatch, CellCommit, WriteTarget } from './rowModel'
import type { SpreadColumn } from './SpreadPopover'

/**
 * The programmer's marquee, **published** outside the list for the rail's Colour and Spread tabs
 * (editor-kit plan session 4, `RailTabs.dc.html`).
 *
 * The cells are local state in `FixturesListContainer` and stay there — `useCellSelection`'s
 * docblock says why a marquee that moves at pointer rate does not belong in Redux — so the
 * container hands the rail what it has already derived from them, the same objects the cells'
 * own editors read, rather than the rail deriving a second copy. That sameness is the point: the
 * tab and the popover it replaces cannot count a batch, name a scope or plan a write two ways.
 *
 * **A store, not a context value.** The snapshot changes on every marquee frame, and a context
 * provided above both the grid and the rail would re-render everything under the provider per
 * frame. `ProgrammerPage` provides a stable store; only a mounted tab subscribes to it, through
 * `useSyncExternalStore`. The two plain lists mount no provider, so their container publishes into
 * nothing and a reader there gets null.
 */
export interface MarqueeSnapshot {
  /** One [CellBatch] per marquee column — `marqueeBatches`, what each column's cell editor is handed. */
  batches: ReadonlyMap<ColumnKey, CellBatch>
  /** The marquee by column, each with its heads in visible row order — `columnTargets`. Empty with no cells. */
  columns: readonly SpreadColumn[]
  /**
   * The rows' heads when rows are selected **and no cells** — `selectedTargets` then, empty
   * otherwise. A tab offers a rows-only selection what the plain lists' row Spread offers it: every
   * head, the column chosen in the panel.
   */
  rows: readonly WriteTarget[]
  /** The scope's gate — `cellKeyboardPermission` — which the tab disables itself on. */
  permission: CellKeyboardPermission
  /** *Local*, or the focused Look's name — the label line's scope, the container's own word. */
  scopeLabel: string
  /**
   * Write a commit to one column of the selection — the marquee's cells in `col`, or the rows'
   * heads where there are no cells — through the container's scope-aware writers **and its ~30 Hz
   * throttle**, the one every cell's commit goes through. Stable for the list's mount.
   */
  commit: (col: ColumnKey, commit: CellCommit) => void
}

export interface MarqueeStore {
  get: () => MarqueeSnapshot | null
  set: (next: MarqueeSnapshot | null) => void
  subscribe: (listener: () => void) => () => void
}

export function createMarqueeStore(): MarqueeStore {
  let current: MarqueeSnapshot | null = null
  const listeners = new Set<() => void>()
  return {
    get: () => current,
    set: (next) => {
      if (next === current) return
      current = next
      for (const listener of listeners) listener()
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

export const MarqueeStoreContext = createContext<MarqueeStore | null>(null)

const NO_STORE: MarqueeStore = { get: () => null, set: () => {}, subscribe: () => () => {} }

/**
 * Publish the container's marquee while it is mounted. A no-op with no store above — the plain
 * lists. Withdrawn on unmount, so a rail that outlives the grid reads nothing rather than a
 * marquee whose writers are gone.
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
