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
 * Stack, FX and Scenery carry their live counts — layers, running effects, held scenery. A press replaces the address's `?table=`; it never
 * pushes, as a busk page's tab does not.
 *
 * **Words go before counts.** From 1040px of the row every tab has its word; below it a closed tab
 * is its glyph and count, and below 600 the open one is too (tables-colours plan §3, the phone's
 * `▦ ≡4 ~5 ⛰2`), the count riding the glyph's corner there so a tab is 22px whatever it says —
 * measured, the phone's Values row has no more room than that. Nothing is hidden at any width. [compact] (the short-height row, which already
 * carries row A) draws one menu button instead — the open table's glyph, and the four tables with
 * their counts inside. The widths are the row's: its `@container` is the host's.
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
        className={cn('shrink-0 items-center gap-0.5 rounded-lg border bg-card p-0.5 @max-[600px]:gap-0', compact ? 'hidden' : 'inline-flex')}
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
                'relative inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs font-semibold transition-colors @max-[600px]:px-1',
                open ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              <Glyph className="size-3.5" />
              <span className={open ? 'hidden @[600px]:inline' : 'hidden @[1040px]:inline'}>{label}</span>
              {count != null && <Count count={count} corner />}
            </button>
          )
        })}
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className={cn('shrink-0 gap-1', !compact && 'hidden')}
            aria-label={`Table: ${PROGRAMMER_TABLE_LABELS[choice.table]}`}
            title="Values, Stack, FX or Scenery"
            data-programmer-tables-menu
          >
            <OpenGlyph className="size-3.5" />
            <span className="sr-only">{PROGRAMMER_TABLE_LABELS[choice.table]}</span>
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

/**
 * A table's count. On the segment ([corner]) it rides the glyph's top-right below 600px of the row,
 * so a tab is its glyph's width whatever the count — the phone's Values row has no other room.
 */
function Count({ count, corner = false }: { count: number; corner?: boolean }) {
  return (
    <span
      className={cn(
        'font-mono text-[10px] font-normal tabular-nums text-muted-foreground',
        corner && '@max-[600px]:absolute @max-[600px]:top-0 @max-[600px]:right-0 @max-[600px]:rounded-full @max-[600px]:bg-card @max-[600px]:px-0.5 @max-[600px]:text-[9px] @max-[600px]:leading-[11px]',
      )}
    >
      {count}
    </span>
  )
}

function countSentence(table: ProgrammerTable, count: number): string {
  switch (table) {
    case 'stack':
      return `${count} layer${count === 1 ? '' : 's'}`
    case 'fx':
      return `${count} FX running`
    case 'scenery':
      return `${count} piece${count === 1 ? '' : 's'} of scenery held`
    default:
      return String(count)
  }
}
