import { useSyncExternalStore } from 'react'
import type { HistoryRecordRow, HistoryRow } from '@/store/history'

/**
 * Whether the History sheet is open — module-level, as the Screens sheet's is, because its openers
 * (the header's History icon, the user menu) are not ancestors of the one sheet `Layout` mounts.
 */
let open = false
const listeners = new Set<() => void>()

export function openHistorySheet(): void {
  setHistorySheetOpen(true)
}

export function setHistorySheetOpen(next: boolean): void {
  if (open === next) return
  open = next
  for (const fn of [...listeners]) fn()
}

function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function useHistorySheetOpen(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => open,
    () => false,
  )
}

/** The sheet's filter chips (undo plan §4.2). */
export type HistoryFilter = 'all' | 'mine' | 'show' | 'programmer' | 'claude'

export const HISTORY_FILTERS: ReadonlyArray<{ value: HistoryFilter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'mine', label: 'Mine' },
  { value: 'show', label: 'Show' },
  { value: 'programmer', label: 'Programmer' },
  { value: 'claude', label: 'Claude' },
]

/** The rows [filter] keeps. A clear point is kept by every filter: it is when the history starts. */
export function filterHistoryRows(rows: readonly HistoryRow[], filter: HistoryFilter): HistoryRow[] {
  return rows.filter((row) => {
    if (row.kind === 'CLEAR') return true
    switch (filter) {
      case 'all':
        return true
      case 'mine':
        return row.mine
      case 'show':
        return row.kind === 'SHOW'
      case 'programmer':
        return row.kind === 'PROGRAMMER'
      case 'claude':
        return row.source?.kind === 'MCP'
    }
  })
}

/** A row's clock time, as the sheet's mono column shows it. */
export function historyTime(at: string): string {
  const date = new Date(at.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

/** Where a record of each folder opens, by its row id where the page takes one. */
const RECORD_PAGES: Readonly<Record<string, (projectId: number, rowId: number | undefined) => string>> = {
  cueStacks: (p, id) => (id != null ? `/projects/${p}/show/stacks/${id}` : `/projects/${p}/show`),
  cues: (p) => `/projects/${p}/cues/all`,
  looks: (p) => `/projects/${p}/looks`,
  palettes: (p) => `/projects/${p}/palettes`,
  scripts: (p, id) => (id != null ? `/projects/${p}/scripts/${id}` : `/projects/${p}/scripts`),
  speedMasters: (p) => `/projects/${p}/speed-masters`,
  fixturePatches: (p) => `/projects/${p}/patches`,
  fixtureGroups: (p) => `/projects/${p}/groups`,
  universeConfigs: (p) => `/projects/${p}/patches`,
  buskPages: (p) => `/projects/${p}/busk`,
  controlSurfaceBindings: (p) => `/projects/${p}/surfaces`,
  stageElements: (p) => `/projects/${p}/stage`,
  stageViewpoints: (p) => `/projects/${p}/stage`,
  riggings: (p) => `/projects/${p}/stage`,
  stageRegions: (p) => `/projects/${p}/stage`,
}

/**
 * The page a sheet row's record opens (§4.2), or null for a record that is gone or has no page of
 * its own. A removed record has nowhere to go until it is undone.
 */
export function historyRecordHref(projectId: number, record: HistoryRecordRow): string | null {
  if (record.change === 'REMOVED') return null
  const page = RECORD_PAGES[record.folder]
  return page ? page(projectId, record.rowId) : null
}
