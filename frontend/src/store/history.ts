import { restApi } from './restApi'
import type { FetchBaseQueryError } from '@reduxjs/toolkit/query/react'
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
  /** The record's row id, which the sheet links by; absent from an older desk. */
  rowId?: number
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

/**
 * One undo or redo as the key, the header, the sheet and the toasts send it (undo plan §3.5): no
 * `entryId` is the key's choice — the newer of the live programmer's top step and this window's
 * source's newest show entry (D1, D2) — and `programmerOnly` takes the step alone.
 */
export interface HistoryMoveArg {
  projectId: number
  direction: 'undo' | 'redo'
  entryId?: string
  programmerOnly?: boolean
  backToHere?: boolean
  confirm?: boolean
  fadeMs?: number
}

export interface HistoryMoveAnswer {
  /** The steps and entries it took, in the order it took them: newest first on an undo, oldest first on a redo. */
  moved: HistoryStepRef[]
}

/** A later change an undo collides with (`HISTORY_CONFLICT`), as the confirm lists it. */
export interface HistoryLater {
  id: string
  label: string
  source?: HistorySource
}

/** Why the desk refused an undo or redo, read from its 409 (or any other failure). */
export type HistoryRefusal =
  | { kind: 'conflict'; message: string; later: HistoryLater[] }
  | { kind: 'confirm'; message: string; moves: string[] }
  | { kind: 'blocked'; message: string; record?: string; reason?: string }
  | { kind: 'empty'; message: string }
  | { kind: 'other'; message: string }

export function historyRefusalOf(error: unknown): HistoryRefusal {
  const data = ((error as FetchBaseQueryError | undefined)?.data ?? {}) as Record<string, unknown>
  const message = typeof data.error === 'string' ? data.error : typeof data.message === 'string' ? data.message : 'The desk refused it'
  switch (data.code) {
    case 'HISTORY_CONFLICT':
      return { kind: 'conflict', message, later: Array.isArray(data.later) ? (data.later as HistoryLater[]) : [] }
    case 'HISTORY_CONFIRM':
      return { kind: 'confirm', message, moves: Array.isArray(data.moves) ? (data.moves as string[]) : [] }
    case 'HISTORY_UNDO_BLOCKED':
      return {
        kind: 'blocked',
        message,
        ...(typeof data.record === 'string' ? { record: data.record } : {}),
        ...(typeof data.reason === 'string' ? { reason: data.reason } : {}),
      }
    case 'HISTORY_EMPTY':
    case 'HISTORY_UNKNOWN':
      return { kind: 'empty', message }
    default:
      return { kind: 'other', message }
  }
}

/**
 * The entries holding [requestIds] (a gesture's `X-History-Entry`s), newest first — what that
 * gesture's Undo takes. A request that wrote nothing has no entry and drops out.
 */
export function entriesForRequests(page: HistoryPage, requestIds: readonly string[]): HistoryRow[] {
  const wanted = new Set(requestIds)
  return page.rows.filter((row) => row.kind === 'SHOW' && row.requestIds.some((id) => wanted.has(id)))
}

/** The caches a show entry's records may sit in, refetched when an undo or redo puts some back. */
const SHOW_RECORD_TAGS = [
  'Cue',
  'CueList',
  'CueStackList',
  'CueStackTimeline',
  'CueSlotList',
  'Look',
  'LookList',
  'PaletteList',
  'BuskPage',
  'BuskRig',
  'Patch',
  'UniverseConfig',
  'GroupList',
  'Fixture',
  'SpeedMaster',
  'SpeedMasterList',
  'Script',
  'PromptBook',
  'Rigging',
  'StageRegion',
  'StageElement',
  'StageViewpoint',
  'SurfaceBinding',
  'Project',
] as const

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
    historyMove: build.mutation<HistoryMoveAnswer, HistoryMoveArg>({
      query: ({ projectId, direction, ...body }) => ({
        url: `projects/${projectId}/history/${direction}`,
        method: 'POST',
        body: { ...body, fadeMs: body.fadeMs ?? 0 },
      }),
      transformResponse: (raw: Partial<HistoryUndoResponse & HistoryRedoResponse>) => ({
        moved: raw.undone ?? raw.redone ?? [],
      }),
      // A show entry puts stored records back, and not every reader of one is refreshed by a socket
      // frame: the cue editor's cue and its cooked rows (`Cue`) are not. A programmer step is the
      // socket's alone.
      invalidatesTags: (result) =>
        result?.moved.some((m) => m.id.startsWith('e-')) ? ['History', ...SHOW_RECORD_TAGS] : ['History'],
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

export const {
  useHistoryStateQuery,
  useHistoryPageQuery,
  useHistoryUndoMutation,
  useHistoryRedoMutation,
  useHistoryMoveMutation,
} = historySlice

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
