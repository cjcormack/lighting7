import { useSyncExternalStore } from 'react'
import { ATTRIBUTE_FAMILIES, type AttributeFamily } from './attributeFamily'
import { createSyncStore, sessionStorageArea, type SyncStore } from './syncStore'

/**
 * **The FX editor's view state** (FX editor plan P6): which view the programmer's FX tab shows —
 * *Selection*, the FX editor, or *All*, the table of every running FX — the FX picked in the list,
 * the one Solo marks and the lanes folded to their headers. Per window (`sessionStorage`), as the
 * busk view's focus is; nothing here reaches the desk.
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

/** Test seam. */
export function resetFxEditorViewStores(): void {
  viewStore.reset()
  pickedStore.reset()
  soloStore.reset()
  foldedStore.reset()
}
