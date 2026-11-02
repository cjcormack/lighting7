import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { EMPTY_HISTORY, type HistoryState } from '../api/historyApi'

/**
 * The desk's history (undo plan §3.7) — see `api/historyApi.ts`.
 *
 * **Form 3** (CLAUDE.md §"Where a WS bridge subscribes"): `history.state` is a stream with nothing to
 * refetch, in one cache entry fed by the WS layer and seeded from its last frame.
 *
 * Undo and redo are REST and answer the steps they took; the frame that follows is what every window's
 * buttons redraw from. A refusal (`HISTORY_EMPTY`, `HISTORY_UNKNOWN`, the 409 off the current project)
 * reaches the screen through `errorToastMiddleware`.
 */
export interface HistoryStepRef {
  id: string
  label: string
}

export interface HistoryUndoResponse {
  undone: HistoryStepRef[]
  history: unknown
}

export interface HistoryRedoResponse {
  redone: HistoryStepRef[]
  history: unknown
}

interface HistoryArg {
  projectId: number
  /** A step to go back to before (undo) or forward through (redo); absent takes one. */
  entryId?: string
  /** The programmer fade, which the restore moves the stage over. */
  fadeMs?: number
}

const historySlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    historyState: build.query<HistoryState, void>({
      queryFn: () => ({ data: lightingApi.history.get() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.history.subscribe((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
    historyUndo: build.mutation<HistoryUndoResponse, HistoryArg>({
      query: ({ projectId, entryId, fadeMs }) => ({
        url: `projects/${projectId}/history/undo`,
        method: 'POST',
        body: { programmerOnly: true, fadeMs: fadeMs ?? 0, ...(entryId ? { entryId } : {}) },
      }),
    }),
    historyRedo: build.mutation<HistoryRedoResponse, HistoryArg>({
      query: ({ projectId, entryId, fadeMs }) => ({
        url: `projects/${projectId}/history/redo`,
        method: 'POST',
        body: { programmerOnly: true, fadeMs: fadeMs ?? 0, ...(entryId ? { entryId } : {}) },
      }),
    }),
  }),
  overrideExisting: false,
})

export const { useHistoryStateQuery, useHistoryUndoMutation, useHistoryRedoMutation } = historySlice

/** The live show's history; empty before the first frame. */
export function useHistoryState(): HistoryState {
  const { data } = useHistoryStateQuery()
  return data ?? EMPTY_HISTORY
}

/**
 * The step a gesture just made, once the history says so: the newest step after [revision], labelled
 * [label]. Resolves null after [timeoutMs] — the gesture changed nothing, or the frame was lost.
 * The Clear toast's Undo names its step by this, so it undoes the Clear and not whatever came after.
 */
export function awaitHistoryStep(revision: number, label: string, timeoutMs = 2000): Promise<string | null> {
  return new Promise((resolve) => {
    // A frame already past [revision] answers during `subscribe`, before it has returned a
    // subscription to drop, so the handle is filled in after and dropped then.
    const held: { subscription?: { unsubscribe(): void }; settled: boolean } = { settled: false }
    const finish = (id: string | null) => {
      if (held.settled) return
      held.settled = true
      clearTimeout(timer)
      held.subscription?.unsubscribe()
      resolve(id)
    }
    const timer = setTimeout(() => finish(null), timeoutMs)
    held.subscription = lightingApi.history.subscribe((state) => {
      if (state.revision <= revision) return
      const newest = state.programmer.undo[0]
      if (newest?.label === label) finish(newest.id)
    })
    if (held.settled) held.subscription.unsubscribe()
  })
}
