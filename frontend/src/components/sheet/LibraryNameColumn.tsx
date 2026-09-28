import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { TextCell } from './cells/TextCell'
import { firstColumnCellProps, type SheetRow } from './sheetModel'
import type { SheetTableProps } from './SheetTable'

/**
 * **The name column every library sheet uses** (library-sheets plan D4) — a `firstColumn` for
 * `SheetTable`, never a `SheetColumn`, so the name is never in the marquee: sticky, a single click
 * selects the row and a drag from it selects rows, as on every sheet.
 *
 * - **A double click renames**, in the kit's `TextCell` popover through `firstColumnCellProps` —
 *   the same popover and three forms as every value cell, and **one row at a time by construction**
 *   (it hands the commit to this row alone). A counted rename over a batch is out of scope (§7).
 * - **The pencil opens the record's editor**, and so does ⏎ with exactly one row selected and no
 *   cells — both are `SheetTable`'s (`firstColumn.onOpen`, `useSheet`'s `onOpenRow`); pass the
 *   same callback to both.
 * - A **prefix** before the name (`M2`) and **badges** after it (a lock, a source, *Global*).
 *
 * A render helper rather than a component so the sheet spreads the result straight into
 * `firstColumn` — the plan's word for it, and the reason the Kit board's `nameColumn` in
 * `sheetModel.ts` did not survive the fact-check.
 */
export function libraryNameColumn<Row extends SheetRow>({
  label = 'Name',
  width = '240px',
  noun,
  name,
  rename,
  renameDisabled,
  prefix,
  badges,
  onOpen,
  openLabel,
}: {
  label?: string
  width?: string
  /** The sheet's noun — the rename editor's label line (*1 master*). */
  noun: string
  name: (row: Row) => string
  /** Commit a new name for this one row. Absent, the name is plain text — nothing renames it. */
  rename?: (row: Row, next: string) => void
  /** This row's name cannot be edited here — another project's library, a built-in. */
  renameDisabled?: (row: Row) => boolean
  /** Drawn before the name — a master's `M2`. */
  prefix?: (row: Row) => ReactNode
  /** Drawn after the name — *Global*, a lock, a source glyph. */
  badges?: (row: Row) => ReactNode
  onOpen?: (row: Row) => void
  openLabel?: (row: Row) => string
}): SheetTableProps<Row, string>['firstColumn'] {
  return {
    label,
    width,
    onOpen,
    openLabel,
    render: (row, selected) => {
      const text = name(row)
      // Asked once, so the hint and the popover cannot disagree: a row the scope or its source
      // refuses the rename says nothing about double-clicking.
      const renames = rename != null && !renameDisabled?.(row)
      const face = (
        <span
          className={cn('mx-1 truncate text-sm', selected ? 'font-semibold' : 'font-medium')}
          title={renames ? 'Double-click to rename' : undefined}
        >
          {text}
        </span>
      )
      return (
        <>
          {prefix && (
            <span className="relative shrink-0 font-mono text-xs font-bold text-muted-foreground">{prefix(row)}</span>
          )}
          <span className="relative flex min-w-0 flex-1 items-center">
            {renames ? (
              <TextCell
                {...firstColumnCellProps<string>({
                  noun,
                  value: text,
                  label: `${noun[0].toUpperCase()}${noun.slice(1)} name`,
                  onCommit: (next) => {
                    if (next !== text) rename?.(row, next)
                  },
                })}
                face={face}
              />
            ) : (
              face
            )}
          </span>
          {badges && <span className="relative flex shrink-0 items-center gap-1">{badges(row)}</span>}
        </>
      )
    },
  }
}
