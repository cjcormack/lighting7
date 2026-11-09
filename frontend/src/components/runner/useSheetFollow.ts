import { useCallback, useMemo } from 'react'
import type { CueStack } from '@/api/cueStacksApi'
import { useFollowShow } from './useFollowShow'
import { useLandCopies } from './useLandCopies'

/** A sheet row's id for a stack entry. */
export function entryRowId(entryId: number): string {
  return `entry:${entryId}`
}

/** A sheet's scroller under [root]: `SheetTable` marks it. */
export function sheetScroller(root: HTMLElement | null): HTMLElement | null {
  return root?.querySelector<HTMLElement>('[data-sheet-scroller]') ?? null
}

interface SheetFollowOptions {
  stack: Pick<CueStack, 'id' | 'cues'>
  /** The rows the sheet draws, keyed by {@link entryRowId}. */
  rows: readonly { id: string }[]
  activeEntryId: number | null
  standbyEntryId?: number | null
  linkedEntryId?: number | null
  sheetRef: React.RefObject<HTMLElement | null>
  setRows: (ids: readonly string[]) => void
  scrollTo: (rowId: string) => void
}

/**
 * The Table's and the Timeline's {@link useFollowShow}, and Duplicate's copies selected and scrolled
 * to once the stack lists them (those the sheet draws). Returns the `onDuplicated` for `CueActions`.
 */
export function useSheetFollow({
  stack,
  rows,
  activeEntryId,
  standbyEntryId = null,
  linkedEntryId = null,
  sheetRef,
  setRows,
  scrollTo,
}: SheetFollowOptions): (entryIds: number[]) => void {
  const drawn = useMemo(() => new Set(rows.map((r) => r.id)), [rows])
  const scrollToEntry = useCallback(
    (entryId: number) => {
      const id = entryRowId(entryId)
      if (drawn.has(id)) scrollTo(id)
    },
    [drawn, scrollTo],
  )
  useFollowShow({
    stackId: stack.id,
    activeEntryId,
    standbyEntryId,
    linkedEntryId,
    getScroller: () => sheetScroller(sheetRef.current),
    getRow: (entryId) => sheetScroller(sheetRef.current)?.querySelector(`[data-row-id="${entryRowId(entryId)}"]`) ?? null,
    scrollTo: scrollToEntry,
  })
  const entries = useMemo(() => stack.cues.map((c) => c.entryId), [stack.cues])
  return useLandCopies(stack.id, entries, (entryIds) => {
    const ids = entryIds.map(entryRowId).filter((id) => drawn.has(id))
    if (ids.length === 0) return
    setRows(ids)
    scrollTo(ids[0])
  })
}
