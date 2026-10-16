import { useSyncExternalStore } from 'react'
import type { IncludedTarget } from '@/api/programmerWsApi'
import { createSyncStore, sessionStorageArea } from './syncStore'

/**
 * **The Include this window made from the Show view** (show-cue-editing plan D16): the cue and the
 * entry *Edit in programmer* or *Blind edit in programmer* loaded, so the programmer's source box
 * can offer *← Show* back to that entry's card.
 *
 * Per tab, in `sessionStorage`, like the Show position: two desk screens are two windows, and the
 * way back is the window's own. Only an Include the Show view made is recorded — the Prompt Book's
 * *Edit cue* goes to Show already, and the programmer's own Include sheet is not a trip from Show —
 * and *← Show* is drawn only while the programmer's include target is still this one
 * ([isShowInclude]), so a later Include from anywhere else takes the way back away.
 */
export interface ShowInclude {
  projectId: number
  cueId: number
  entryId: number
}

function isId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function parseShowInclude(parsed: unknown): ShowInclude | null {
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  const { projectId, cueId, entryId } = parsed as Record<string, unknown>
  return isId(projectId) && isId(cueId) && isId(entryId) ? { projectId, cueId, entryId } : null
}

const store = createSyncStore<ShowInclude | null>({
  key: 'show.include',
  fallback: null,
  parse: parseShowInclude,
  storage: sessionStorageArea,
})

/** Record that this window Included [entryId]'s cue from the Show view. */
export function setShowInclude(include: ShowInclude): void {
  store.set(include)
}

export function useShowInclude(): ShowInclude | null {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** Whether [target] — the programmer's current include — is the one [include] recorded. */
export function isShowInclude(
  include: ShowInclude | null,
  projectId: number,
  target: IncludedTarget | null,
): include is ShowInclude {
  return (
    include != null &&
    include.projectId === projectId &&
    target?.kind === 'CUE' &&
    target.cueId === include.cueId &&
    target.entryId === include.entryId
  )
}

/** Test-only: forget the record. */
export function resetShowIncludeForTests(): void {
  store.reset()
}
