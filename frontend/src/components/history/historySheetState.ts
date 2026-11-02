import { useSyncExternalStore } from 'react'
import type { HistoryRow } from '@/store/history'

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
