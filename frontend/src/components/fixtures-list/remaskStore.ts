import { createContext, useContext, useEffect, useSyncExternalStore } from 'react'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { createValueStore, type ValueStore } from './valueStore'

/**
 * **The programmer list's family-pill write, published** for the FX editor's header (FX editor
 * plan §4.3): the list's `remask` — `selection.setFamilies` through its desk bridge, so the bridge
 * drops a marquee whose columns no longer say the mask — or null while the window has its own
 * selection, when the pill is a read-out. `ProgrammerPage` provides it; the grid's toolbar fills it.
 */
export type Remask = (families: readonly AttributeFamily[] | null) => void
export type RemaskStore = ValueStore<Remask | null>

export function createRemaskStore(): RemaskStore {
  return createValueStore<Remask | null>(null)
}

export const RemaskStoreContext = createContext<RemaskStore | null>(null)

/** Publish [remask] while mounted. */
export function usePublishRemask(remask: Remask | null): void {
  const store = useContext(RemaskStoreContext)
  useEffect(() => {
    if (store == null) return
    store.set(remask)
    return () => {
      if (store.get() === remask) store.set(null)
    }
  }, [remask, store])
}

const NO_STORE: RemaskStore = { get: () => null, set: () => {}, subscribe: () => () => {} }

/** The published write, or null: off the programmer, or on a window with its own selection. */
export function usePublishedRemask(): Remask | null {
  const store = useContext(RemaskStoreContext) ?? NO_STORE
  return useSyncExternalStore(store.subscribe, store.get, store.get)
}
