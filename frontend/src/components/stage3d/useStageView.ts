import { useCallback, useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../../lib/syncStore'
import { toStageLabelMode, type StageLabelMode } from './stageLabels'

/**
 * The View menu's **Show** toggles and **Labels**, per window in `sessionStorage`, not announced. A
 * window with nothing of its own starts from the old per-browser `localStorage stageViewFlags`, read
 * once and never written, so a build rolled back finds it as it was (`useVisSource.ts` moved alike).
 */
export interface StageViewFlags {
  regions: boolean
  riggings: boolean
  fixtures: boolean
  /** Which labels the label layer draws. */
  labels: StageLabelMode
  /** The menu's *Light*: off unmounts the emitters, so no beam and no pool. */
  beamCones: boolean
}

/** The flags that are plain on/off switches — everything but the label mode. */
export type StageViewToggle = Exclude<keyof StageViewFlags, 'labels'>

export const DEFAULT_VIEW_FLAGS: StageViewFlags = {
  regions: true,
  riggings: true,
  fixtures: true,
  labels: 'positions',
  beamCones: true,
}

export const STAGE_VIEW_FLAGS_KEY = 'stage.viewFlags'
/** The profile-wide key the flags lived under before they were per window; read once, as a seed. */
export const LEGACY_STAGE_VIEW_FLAGS_KEY = 'stageViewFlags'

const TOGGLES: readonly StageViewToggle[] = ['regions', 'riggings', 'fixtures', 'beamCones']

/**
 * Stored flags, field by field over the defaults: a value an older or later build wrote must not
 * switch a layer off by omission. `labels` was a boolean before the label layer, and a desk's
 * storage may still hold one.
 */
export function parseStageViewFlags(parsed: unknown): StageViewFlags {
  const p = (parsed != null && typeof parsed === 'object' ? parsed : {}) as Partial<Record<string, unknown>>
  const flags = { ...DEFAULT_VIEW_FLAGS, labels: toStageLabelMode(p.labels) }
  for (const key of TOGGLES) {
    if (typeof p[key] === 'boolean') flags[key] = p[key] as boolean
  }
  return flags
}

function legacyFlags(): StageViewFlags {
  try {
    const raw = window.localStorage.getItem(LEGACY_STAGE_VIEW_FLAGS_KEY)
    return raw == null ? DEFAULT_VIEW_FLAGS : parseStageViewFlags(JSON.parse(raw))
  } catch {
    return DEFAULT_VIEW_FLAGS
  }
}

function makeStore() {
  return createSyncStore<StageViewFlags>({
    key: STAGE_VIEW_FLAGS_KEY,
    fallback: typeof window === 'undefined' ? DEFAULT_VIEW_FLAGS : legacyFlags(),
    parse: parseStageViewFlags,
    storage: sessionStorageArea,
  })
}

let store = makeStore()

export function useStageView() {
  const flags = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)

  const setFlag = useCallback((key: StageViewToggle, value: boolean) => {
    store.set({ ...store.getSnapshot(), [key]: value })
  }, [])
  const setLabelMode = useCallback((labels: StageLabelMode) => {
    store.set({ ...store.getSnapshot(), labels })
  }, [])

  return { flags, setFlag, setLabelMode }
}

/**
 * Test seam: drop the cached value and any listeners so each test starts clean — and re-read the
 * legacy seed, which a test may have just written.
 */
export function resetStageViewStore(): void {
  store.reset()
  store = makeStore()
}
