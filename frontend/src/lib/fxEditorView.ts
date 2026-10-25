import { useSyncExternalStore } from 'react'
import { ATTRIBUTE_FAMILIES, type AttributeFamily } from './attributeFamily'
import { createSyncStore, sessionStorageArea, type SyncStore } from './syncStore'

/**
 * **The FX editor's view state** (FX editor plan P6): which view the programmer's FX tab shows —
 * *Selection*, the FX editor, or *All*, the table of every running FX — the FX picked in the list,
 * the one Solo marks, the lanes folded to their headers, the lane the phone arm opens, and the
 * graph's toolbar: its window, *Result · This FX*, *Rig order*, *Colour as seen* and the playhead's
 * pause. Per window
 * (`sessionStorage`), as the busk view's focus is; nothing here reaches the desk.
 */

export type FxTabView = 'selection' | 'all'

const viewStore = createSyncStore<FxTabView>({
  key: 'programmer.fx.view',
  fallback: 'selection',
  parse: (parsed) => (parsed === 'all' ? 'all' : 'selection'),
  storage: sessionStorageArea,
})

const fxIdOrNull = (parsed: unknown) => (typeof parsed === 'number' && Number.isInteger(parsed) ? parsed : null)

const pickedStore = createSyncStore<number | null>({
  key: 'programmer.fx.picked',
  fallback: null,
  parse: fxIdOrNull,
  storage: sessionStorageArea,
})

const soloStore = createSyncStore<number | null>({
  key: 'programmer.fx.solo',
  fallback: null,
  parse: fxIdOrNull,
  storage: sessionStorageArea,
})

const foldedStore = createSyncStore<readonly AttributeFamily[]>({
  key: 'programmer.fx.folded',
  fallback: [],
  parse: (parsed) => (Array.isArray(parsed) ? ATTRIBUTE_FAMILIES.filter((f) => parsed.includes(f)) : []),
  storage: sessionStorageArea,
})

/** The one lane the phone arm opens under its lane chips; null folds them all (§4.2). */
const phoneLaneStore = createSyncStore<AttributeFamily | null>({
  key: 'programmer.fx.phoneLane',
  fallback: null,
  parse: (parsed) => ATTRIBUTE_FAMILIES.find((f) => f === parsed) ?? null,
  storage: sessionStorageArea,
})

export type GraphWindowChoice = '1c' | '2c' | '2b' | '8b'
export type GraphViewChoice = 'result' | 'this'

export interface FxGraphOptions {
  window: GraphWindowChoice
  view: GraphViewChoice
  rigOrder: boolean
  asSeen: boolean
  paused: boolean
}

const GRAPH_DEFAULTS: FxGraphOptions = { window: '2c', view: 'result', rigOrder: false, asSeen: false, paused: false }
const WINDOWS: readonly GraphWindowChoice[] = ['1c', '2c', '2b', '8b']

const graphStore = createSyncStore<FxGraphOptions>({
  key: 'programmer.fx.graph',
  fallback: GRAPH_DEFAULTS,
  parse: (parsed) => {
    const p = (parsed ?? {}) as Partial<Record<keyof FxGraphOptions, unknown>>
    return {
      window: WINDOWS.includes(p.window as GraphWindowChoice) ? (p.window as GraphWindowChoice) : GRAPH_DEFAULTS.window,
      view: p.view === 'this' ? 'this' : 'result',
      rigOrder: p.rigOrder === true,
      asSeen: p.asSeen === true,
      paused: p.paused === true,
    }
  },
  storage: sessionStorageArea,
})

function useStore<T>(store: SyncStore<T>): T {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

export const useFxTabView = (): FxTabView => useStore(viewStore)
export const setFxTabView = (view: FxTabView): void => viewStore.set(view)

export const usePickedFx = (): number | null => useStore(pickedStore)
export const getPickedFx = (): number | null => pickedStore.getSnapshot()
export const setPickedFx = (fxId: number | null): void => pickedStore.set(fxId)

export const useSoloFx = (): number | null => useStore(soloStore)
export const setSoloFx = (fxId: number | null): void => soloStore.set(fxId)

export const useFoldedLanes = (): readonly AttributeFamily[] => useStore(foldedStore)
export function toggleLaneFold(family: AttributeFamily): void {
  const folded = foldedStore.getSnapshot()
  foldedStore.set(folded.includes(family) ? folded.filter((f) => f !== family) : [...folded, family])
}

export const usePhoneLane = (): AttributeFamily | null => useStore(phoneLaneStore)
export const setPhoneLane = (family: AttributeFamily | null): void => phoneLaneStore.set(family)

export const useFxGraphOptions = (): FxGraphOptions => useStore(graphStore)
export function setFxGraphOption<K extends keyof FxGraphOptions>(key: K, value: FxGraphOptions[K]): void {
  graphStore.set({ ...graphStore.getSnapshot(), [key]: value })
}

/** Test seam. */
export function resetFxEditorViewStores(): void {
  viewStore.reset()
  pickedStore.reset()
  soloStore.reset()
  foldedStore.reset()
  phoneLaneStore.reset()
  graphStore.reset()
}
