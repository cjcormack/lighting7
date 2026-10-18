import { createContext, useContext, useMemo, useRef, type KeyboardEvent } from 'react'
import { AudioWaveform, Blinds, ChevronDown, Grid3x3, Layers } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { PROGRAMMER_TABLES, PROGRAMMER_TABLE_LABELS, parseProgrammerTable, type ProgrammerTable } from '@/lib/programmerTables'
import { useHeldSceneryCount } from './ProgrammerSceneryList'

/**
 * The table a programmer window shows, the press that changes it and the tabs' counts —
 * `ProgrammerTables`'. The counts are read once there, so a tab segment mounting with its table
 * subscribes to nothing.
 */
export interface ProgrammerTablesChoice {
  table: ProgrammerTable
  select: (table: ProgrammerTable) => void
  counts: Readonly<Record<ProgrammerTable, number | null>>
}

/** Layers, running effects and held scenery: the counts on Stack, FX and Scenery. */
export function useProgrammerTableCounts(projectId: number): ProgrammerTablesChoice['counts'] {
  const { data: layers } = useProgrammerLayersQuery()
  const { data: effects } = useActiveEffectsQuery()
  const scenery = useHeldSceneryCount(projectId)
  const stack = layers?.length ?? 0
  const fx = effects?.length ?? 0
  return useMemo(() => ({ values: null, stack, fx, scenery }), [fx, scenery, stack])
}

export const ProgrammerTablesContext = createContext<ProgrammerTablesChoice | null>(null)

export function useProgrammerTables(): ProgrammerTablesChoice | null {
  return useContext(ProgrammerTablesContext)
}

/** The id of [table]'s panel in `ProgrammerTables`, which a tab names as the panel it controls. */
export function programmerTablePanelId(table: ProgrammerTable): string {
  return `programmer-table-${table}`
}

/**
 * Focus [table]'s own tab segment once it has drawn: the segment that was pressed belongs to the
 * table being left, which is now hidden and inert, or gone.
 */
function focusTabsOf(table: ProgrammerTable) {
  requestAnimationFrame(() => {
    const panel = document.getElementById(programmerTablePanelId(table))
    const tab = panel?.querySelector<HTMLElement>(`[data-programmer-tab="${table}"]`)
    const target = tab != null && tab.getClientRects().length > 0 ? tab : panel?.querySelector<HTMLElement>('[data-programmer-tables-menu]')
    target?.focus()
  })
}

const GLYPHS: Readonly<Record<ProgrammerTable, typeof Layers>> = {
  values: Grid3x3,
  stack: Layers,
  fx: AudioWaveform,
  scenery: Blinds,
}

/**
 * **Values · Stack · FX · Scenery**, at the start of each table's row B (tables-colours plan D1).
 * Stack, FX and Scenery carry their live counts — layers, running effects, held scenery — which is
 * all the rail's collapsed strip ever said. A press replaces the address's `?table=`; it never
 * pushes, as a busk page's tab does not.
 *
 * **Words go before counts, and the segment before the row.** From 1040px of the row every tab has
 * its word; below it a closed tab is its glyph and count, which keeps the Values row's filter wider
 * than its placeholder. Below 760px the four would push the scope pills off the row, so the segment
 * is one menu button — the open table's glyph, its word from 480px, and the four tables with their
 * counts inside — and [compact] (the short-height row) draws that button at every width, glyph
 * only. The widths are the row's: its `@container` is the host's.
 *
 * The segment is one tab stop: ←/→, Home and End move between the tables, and a press from the
 * keyboard lands focus on the new table's own segment. A pointer press leaves focus where the browser
 * puts it, so the grid's window keys still hear the next key.
 *
 * Draws nothing outside the programmer, where no table is chosen.
 */
export function ProgrammerTableTabs({ compact = false }: { compact?: boolean }) {
  const choice = useProgrammerTables()
  // The table a menu pick chose, so its close leaves focus to the pick rather than its own trigger.
  const picked = useRef<ProgrammerTable | null>(null)
  if (choice == null) return null
  const { counts } = choice
  const OpenGlyph = GLYPHS[choice.table]
  const selectFromKeys = (table: ProgrammerTable) => {
    choice.select(table)
    focusTabsOf(table)
  }
  const onTabKeyDown = (event: KeyboardEvent) => {
    const at = PROGRAMMER_TABLES.indexOf(choice.table)
    const last = PROGRAMMER_TABLES.length - 1
    const to =
      event.key === 'ArrowRight' ? (at === last ? 0 : at + 1)
      : event.key === 'ArrowLeft' ? (at === 0 ? last : at - 1)
      : event.key === 'Home' ? 0
      : event.key === 'End' ? last
      : null
    if (to == null || event.metaKey || event.ctrlKey || event.altKey) return
    event.preventDefault()
    selectFromKeys(PROGRAMMER_TABLES[to])
  }
  return (
    <>
      <div
        role="tablist"
        aria-label="Programmer tables"
        data-programmer-tabs
        onKeyDown={onTabKeyDown}
        className={cn('hidden shrink-0 items-center gap-0.5 rounded-lg border bg-card p-0.5', !compact && '@[760px]:inline-flex')}
      >
        {PROGRAMMER_TABLES.map((table) => {
          const open = table === choice.table
          const Glyph = GLYPHS[table]
          const count = counts[table]
          const label = PROGRAMMER_TABLE_LABELS[table]
          return (
            <button
              key={table}
              type="button"
              role="tab"
              aria-selected={open}
              aria-controls={programmerTablePanelId(table)}
              tabIndex={open ? 0 : -1}
              aria-label={count == null ? label : `${label} · ${countSentence(table, count)}`}
              title={count == null ? label : countSentence(table, count)}
              data-programmer-tab={table}
              onClick={(event) => (event.detail === 0 ? selectFromKeys(table) : choice.select(table))}
              className={cn(
                'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs font-semibold transition-colors',
                open ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              <Glyph className="size-3.5" />
              <span className={open ? undefined : 'hidden @[1040px]:inline'}>{label}</span>
              {count != null && <Count count={count} />}
            </button>
          )
        })}
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className={cn('shrink-0 gap-1', !compact && '@[760px]:hidden')}
            aria-label={`Table: ${PROGRAMMER_TABLE_LABELS[choice.table]}`}
            title="Values, Stack, FX or Scenery"
            data-programmer-tables-menu
          >
            <OpenGlyph className="size-3.5" />
            <span className={cn('hidden', !compact && '@[480px]:inline')}>{PROGRAMMER_TABLE_LABELS[choice.table]}</span>
            <ChevronDown className="size-3 text-muted-foreground" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="start"
          onCloseAutoFocus={(event) => {
            if (picked.current == null) return
            picked.current = null
            event.preventDefault()
          }}
        >
          <DropdownMenuRadioGroup
            value={choice.table}
            onValueChange={(next) => {
              const table = parseProgrammerTable(next)
              if (table == null) return
              picked.current = table
              selectFromKeys(table)
            }}
          >
            {PROGRAMMER_TABLES.map((table) => {
              const Glyph = GLYPHS[table]
              const count = counts[table]
              return (
                <DropdownMenuRadioItem key={table} value={table}>
                  <Glyph className="size-3.5" />
                  <span className="flex-1">{PROGRAMMER_TABLE_LABELS[table]}</span>
                  {count != null && <Count count={count} />}
                </DropdownMenuRadioItem>
              )
            })}
          </DropdownMenuRadioGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  )
}

function Count({ count }: { count: number }) {
  return <span className="font-mono text-[10px] font-normal tabular-nums text-muted-foreground">{count}</span>
}

function countSentence(table: ProgrammerTable, count: number): string {
  switch (table) {
    case 'stack':
      return `${count} layer${count === 1 ? '' : 's'}`
    case 'fx':
      return `${count} effect${count === 1 ? '' : 's'} running`
    case 'scenery':
      return `${count} piece${count === 1 ? '' : 's'} of scenery held`
    default:
      return String(count)
  }
}
