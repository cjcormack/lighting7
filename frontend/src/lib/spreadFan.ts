import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'

/** Spread's two modes on Position: *Ends* (From → To) or *Fan*, an amount about each head's own position. */
export type SpreadMode = 'ENDS' | 'FAN'

/** Which axes a fan moves. */
export type FanAxes = 'PAN' | 'TILT' | 'BOTH'

export interface SpreadFanPrefs {
  mode: SpreadMode
  axes: FanAxes
}

/**
 * **The Spread panel's mode and fan axes as the operator last chose them** (venue-visit plan S12) —
 * `spreadOver.ts`'s fact and reason: the panel does not outlive the selection, its hosts are one
 * panel, and a position marquee should open in the mode it was last left in. The amounts are
 * not kept here: a fan's amount means something only against the anchor it was set on, which goes
 * with the gesture.
 */
const store = createSyncStore<SpreadFanPrefs>({
  key: 'desk.spread.fan',
  fallback: { mode: 'ENDS', axes: 'PAN' },
  parse: (parsed) => {
    const value = (parsed ?? {}) as Partial<SpreadFanPrefs>
    return {
      mode: value.mode === 'FAN' ? 'FAN' : 'ENDS',
      axes: value.axes === 'TILT' || value.axes === 'BOTH' ? value.axes : 'PAN',
    }
  },
  storage: sessionStorageArea,
})

export function useSpreadFanPrefs(): SpreadFanPrefs {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

export function getSpreadFanPrefs(): SpreadFanPrefs {
  return store.getSnapshot()
}

export function setSpreadFanPrefs(prefs: SpreadFanPrefs): void {
  store.set(prefs)
}

/** Test seam — a module-level cache outlives `sessionStorage.clear()`. */
export function resetSpreadFanStore(): void {
  store.reset()
}
