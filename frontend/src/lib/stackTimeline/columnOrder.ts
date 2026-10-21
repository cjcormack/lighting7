import type { CueTarget } from '@/api/cuesApi'
import { createSyncStore, sessionStorageArea, type SyncStore } from '@/lib/syncStore'
import { TIMELINE_KINDS, type TimelineKind } from './model'

function parseIds(parsed: unknown): string[] | null {
  return Array.isArray(parsed) && parsed.every((id) => typeof id === 'string') ? parsed : null
}

const orderStores = new Map<number, SyncStore<string[] | null>>()

/**
 * This window's column order for one stack (C7): `sessionStorage timeline.columns.<stackId>`, every
 * column id in the order last arranged. Per window and never stored on the desk — the desk derives
 * the columns, and a window only rearranges them.
 */
export function timelineColumnOrderStore(stackId: number): SyncStore<string[] | null> {
  let store = orderStores.get(stackId)
  if (store == null) {
    store = createSyncStore<string[] | null>({
      key: `timeline.columns.${stackId}`,
      fallback: null,
      parse: parseIds,
      storage: sessionStorageArea,
    })
    orderStores.set(stackId, store)
  }
  return store
}

export interface StoredTimelineFilters {
  kinds: TimelineKind[]
  family: string | null
  selectionOnly: boolean
}

const ALL_KINDS: StoredTimelineFilters = { kinds: [...TIMELINE_KINDS], family: null, selectionOnly: false }

/** Row B's filters, per window (§4.3): `sessionStorage timeline.filters`. */
export const timelineFiltersStore = createSyncStore<StoredTimelineFilters>({
  key: 'timeline.filters',
  fallback: ALL_KINDS,
  parse: (parsed) => {
    if (typeof parsed !== 'object' || parsed == null) return ALL_KINDS
    const p = parsed as Partial<StoredTimelineFilters>
    const kinds = Array.isArray(p.kinds) ? p.kinds.filter((k): k is TimelineKind => TIMELINE_KINDS.includes(k as TimelineKind)) : ALL_KINDS.kinds
    return {
      kinds,
      family: typeof p.family === 'string' ? p.family : null,
      selectionOnly: p.selectionOnly === true,
    }
  },
  storage: sessionStorageArea,
})

/** A Look or palette column a window added with *+ Column*: empty until a cell is filled (§4.4). */
export interface StoredExtraColumn {
  kind: 'look' | 'palette'
  sourceId: number
  name: string
  target: CueTarget | null
  families: string[]
}

function parseExtras(parsed: unknown): StoredExtraColumn[] {
  if (!Array.isArray(parsed)) return []
  return parsed.filter(
    (c): c is StoredExtraColumn =>
      typeof c === 'object' &&
      c != null &&
      (c.kind === 'look' || c.kind === 'palette') &&
      typeof c.sourceId === 'number' &&
      typeof c.name === 'string' &&
      Array.isArray(c.families) &&
      (c.target === null || (typeof c.target === 'object' && typeof c.target.key === 'string' && typeof c.target.type === 'string')),
  )
}

const extraStores = new Map<number, SyncStore<StoredExtraColumn[]>>()

/** This window's added columns for one stack: `sessionStorage timeline.extra.<stackId>`. */
export function timelineExtraColumnsStore(stackId: number): SyncStore<StoredExtraColumn[]> {
  let store = extraStores.get(stackId)
  if (store == null) {
    store = createSyncStore<StoredExtraColumn[]>({
      key: `timeline.extra.${stackId}`,
      fallback: [],
      parse: parseExtras,
      storage: sessionStorageArea,
    })
    extraStores.set(stackId, store)
  }
  return store
}
