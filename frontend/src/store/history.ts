import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { EMPTY_HISTORY, type HistorySource, type HistoryState } from '../api/historyApi'

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

/** One record an entry changed, as the History sheet lists it. */
export interface HistoryRecordRow {
  folder: string
  uuid: string
  change: 'ADDED' | 'REMOVED' | 'CHANGED'
  label: string
}

/**
 * One row of the History sheet (undo plan §3.7, §4.2): a show entry, a programmer step, or a clear
 * point (`CLEAR`, drawn as a rule). `source.window` is the registry row id of the window it came from.
 */
export interface HistoryRow {
  id: string
  kind: 'SHOW' | 'PROGRAMMER' | 'CLEAR'
  at: string
  label: string
  source?: HistorySource
  mine: boolean
  counts?: { added: number; removed: number; changed: number }
  records: HistoryRecordRow[]
  moreRecords: number
  /** The requests whose writes a show entry holds: a REST write's `X-History-Entry` is one of them. */
  requestIds: string[]
  undone: boolean
  redoable: boolean
  moves: string[]
}

export interface HistoryPage {
  projectId: number
  rows: HistoryRow[]
  more: boolean
  live: boolean
  revision: number
}

/** The desk's Json drops defaults, so every optional field is filled in here. */
export function parseHistoryPage(raw: unknown): HistoryPage {
  const r = (raw ?? {}) as Record<string, unknown>
  const rows = Array.isArray(r.rows) ? r.rows : []
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : 0,
    more: r.more === true,
    live: r.live === true,
    revision: typeof r.revision === 'number' ? r.revision : 0,
    rows: rows.flatMap((row): HistoryRow[] => {
      if (row == null || typeof row !== 'object') return []
      const o = row as Record<string, unknown>
      if (typeof o.id !== 'string' || typeof o.label !== 'string') return []
      const kind = o.kind === 'SHOW' || o.kind === 'CLEAR' ? o.kind : 'PROGRAMMER'
      const source = o.source as HistorySource | undefined
      const counts = o.counts as Record<string, unknown> | undefined
      return [
        {
          id: o.id,
          kind,
          at: typeof o.at === 'string' ? o.at : '',
          label: o.label,
          ...(source && typeof source.name === 'string' ? { source } : {}),
          mine: o.mine === true,
          ...(counts
            ? {
                counts: {
                  added: Number(counts.added ?? 0),
                  removed: Number(counts.removed ?? 0),
                  changed: Number(counts.changed ?? 0),
                },
              }
            : {}),
          records: Array.isArray(o.records) ? (o.records as HistoryRecordRow[]) : [],
          moreRecords: typeof o.moreRecords === 'number' ? o.moreRecords : 0,
          requestIds: Array.isArray(o.requestIds) ? (o.requestIds as string[]) : [],
          undone: o.undone === true,
          redoable: o.redoable === true,
          moves: Array.isArray(o.moves) ? (o.moves as string[]) : [],
        },
      ]
    }),
  }
}

interface HistoryArg {
  projectId: number
  /** A step to go back to before (undo) or forward through (redo); absent takes one. */
  entryId?: string
  /** The programmer fade, which the restore moves the stage over. */
  fadeMs?: number
}

export const historySlice = restApi.injectEndpoints({
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
    historyPage: build.query<HistoryPage, { projectId: number; limit?: number }>({
      query: ({ projectId, limit }) => `projects/${projectId}/history${limit ? `?limit=${limit}` : ''}`,
      transformResponse: parseHistoryPage,
      providesTags: ['History'],
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

export const { useHistoryStateQuery, useHistoryPageQuery, useHistoryUndoMutation, useHistoryRedoMutation } = historySlice

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
