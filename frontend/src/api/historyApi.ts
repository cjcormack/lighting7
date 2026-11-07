import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * The desk's **history** (undo plan §3.7; lighting7 `plugins/HistorySocket.kt` is the wire contract):
 * `history.state`, outbound only and a `StateFlow`, so the subscription is the connect snapshot. Undo
 * and redo are REST (`store/history.ts`). `undo` and `redo` — what ⌘Z and ⇧⌘Z take — are the newer
 * of the programmer's top step and this window's source's newest show entry (D1, D2). `revision` is
 * the programmer's and `showRevision` the show history's; the History sheet reads its page again when
 * either moves.
 */
export interface HistorySource {
  kind: 'ACCOUNT' | 'MCP' | 'SURFACE' | 'OSC' | 'DESK'
  name: string
  window?: string
}

/** One programmer step, as the steps menu lists it. */
export interface HistoryStep {
  id: string
  label: string
  at: string
  source: HistorySource
}

/** What the key would take: a programmer step, or a show entry of this window's source. */
export interface HistoryTop {
  id: string
  label: string
  kind: 'PROGRAMMER' | 'SHOW'
}

export interface HistoryState {
  projectId: number | null
  /** The programmer's revision: it restarts with each show. */
  revision: number
  /** The show history's revision, on every project: it only ever moves. */
  showRevision: number
  undo: HistoryTop | null
  redo: HistoryTop | null
  programmer: { undo: HistoryStep[]; redo: HistoryStep[] }
}

export const EMPTY_HISTORY: HistoryState = {
  projectId: null,
  revision: 0,
  showRevision: 0,
  undo: null,
  redo: null,
  programmer: { undo: [], redo: [] },
}

function parseTop(raw: unknown): HistoryTop | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (typeof r.id !== 'string' || typeof r.label !== 'string') return null
  return { id: r.id, label: r.label, kind: r.kind === 'SHOW' ? 'SHOW' : 'PROGRAMMER' }
}

function parseSteps(raw: unknown): HistoryStep[] {
  if (!Array.isArray(raw)) return []
  return raw.flatMap((step) => {
    if (step == null || typeof step !== 'object') return []
    const s = step as Record<string, unknown>
    if (typeof s.id !== 'string' || typeof s.label !== 'string') return []
    const source = (s.source ?? {}) as Record<string, unknown>
    return [
      {
        id: s.id,
        label: s.label,
        at: typeof s.at === 'string' ? s.at : '',
        source: {
          kind: (typeof source.kind === 'string' ? source.kind : 'DESK') as HistorySource['kind'],
          name: typeof source.name === 'string' ? source.name : '',
          ...(typeof source.window === 'string' ? { window: source.window } : {}),
        },
      },
    ]
  })
}

/** A `history.state` frame. The desk's Json drops defaults, so a missing top is nothing to take. */
export function parseHistoryFrame(raw: unknown): HistoryState {
  if (raw == null || typeof raw !== 'object') return EMPTY_HISTORY
  const r = raw as Record<string, unknown>
  const programmer = (r.programmer ?? {}) as Record<string, unknown>
  return {
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
    revision: typeof r.revision === 'number' ? r.revision : 0,
    showRevision: typeof r.showRevision === 'number' ? r.showRevision : 0,
    undo: parseTop(r.undo),
    redo: parseTop(r.redo),
    programmer: { undo: parseSteps(programmer.undo), redo: parseSteps(programmer.redo) },
  }
}

export interface HistoryWsApi {
  /** The history on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: HistoryState) => void): Subscription
  /** The last frame, or [EMPTY_HISTORY] before the first. */
  get(): HistoryState
}

export function createHistoryWsApi(conn: InternalApiConnection): HistoryWsApi {
  const state = createWsSubscribable<HistoryState>()
  let last: HistoryState = EMPTY_HISTORY
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'history.state') return
    last = parseHistoryFrame(message)
    seen = true
    state.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = state.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    get: () => last,
  }
}
