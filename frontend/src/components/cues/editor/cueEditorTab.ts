import { createSyncStore, sessionStorageArea } from '@/lib/syncStore'

/** The cue editor's tabs, in the order they are drawn (show-cue-editing plan §4.1). */
export const CUE_EDITOR_TABS = ['values', 'looks', 'fx', 'scenery', 'events', 'cue'] as const
export type CueEditorTab = (typeof CUE_EDITOR_TABS)[number]

export function isCueEditorTab(value: unknown): value is CueEditorTab {
  return typeof value === 'string' && (CUE_EDITOR_TABS as readonly string[]).includes(value)
}

/**
 * The tab this window last chose (`sessionStorage cueEditor.tab`). Per window, as every fact about
 * how a window is arranged is; an open editor keeps its own tab, and only a **new** editor opens on
 * this one, so two open cards do not switch together.
 */
export const cueEditorTabStore = createSyncStore<CueEditorTab>({
  key: 'cueEditor.tab',
  fallback: 'values',
  parse: (parsed) => (isCueEditorTab(parsed) ? parsed : 'values'),
  storage: sessionStorageArea,
})
