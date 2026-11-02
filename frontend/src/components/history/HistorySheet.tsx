import { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronRight, History } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { useViewedProject } from '@/ProjectSwitcher'
import { useHistoryPageQuery, useHistoryState, type HistoryRow } from '@/store/history'
import { useDeskWindows } from '@/store/windows'
import {
  HISTORY_FILTERS,
  filterHistoryRows,
  historyTime,
  openHistorySheet,
  setHistorySheetOpen,
  useHistorySheetOpen,
  type HistoryFilter,
} from './historySheetState'

/**
 * The History sheet (undo plan §4.2, session 3): the viewed project's show entries and, on the live
 * project, the programmer's steps, newest first, with the moments the history was cleared drawn as
 * rules. An activity log for now — undoing from it is session 6's. Mounted once, in `Layout`.
 */
export function HistorySheet() {
  const open = useHistorySheetOpen()
  const project = useViewedProject()
  return (
    <Sheet open={open} onOpenChange={setHistorySheetOpen}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>History</SheetTitle>
          <SheetDescription>{project ? project.name : 'No project'}</SheetDescription>
        </SheetHeader>
        <SheetBody>{open && project && <HistorySheetBody projectId={project.id} />}</SheetBody>
        <SheetFooter className="flex-col items-stretch gap-2 sm:flex-col">
          <p className="text-xs text-muted-foreground">
            Keeps the last 50 programmer steps and 500 show changes while the desk runs. A sync pull, a
            venue update, attach, detach, extract or discard, and a restart clear it.
          </p>
          <div className="flex justify-end">
            <SheetClose asChild>
              <Button variant="outline">Close</Button>
            </SheetClose>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

export function HistorySheetBody({ projectId }: { projectId: number }) {
  const [filter, setFilter] = useState<HistoryFilter>('all')
  // Read afresh each time the sheet opens, and again whenever either history moves while it is open.
  const { data, isLoading, refetch } = useHistoryPageQuery({ projectId }, { refetchOnMountOrArgChange: true })
  const { revision, showRevision } = useHistoryState()
  const moved = `${revision}:${showRevision}`
  const seen = useRef(moved)
  useEffect(() => {
    if (seen.current === moved) return
    seen.current = moved
    void refetch()
  }, [moved, refetch])
  const windows = useDeskWindows()
  const windowNames = useMemo(() => new Map(windows.map((w) => [w.id, w.name])), [windows])
  const rows = useMemo(() => filterHistoryRows(data?.rows ?? [], filter), [data, filter])
  const nothing = rows.every((row) => row.kind === 'CLEAR')

  return (
    <div className="flex flex-col gap-3">
      <ToggleGroup
        type="single"
        size="sm"
        value={filter}
        onValueChange={(next) => next !== '' && setFilter(next as HistoryFilter)}
        aria-label="Show"
        className="justify-start"
      >
        {HISTORY_FILTERS.map(({ value, label }) => (
          <ToggleGroupItem key={value} value={value} className="h-7 px-2.5 text-xs">
            {label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : (
        <ol className="flex flex-col" aria-label="History">
          {nothing && <li className="py-2 text-sm text-muted-foreground">Nothing yet.</li>}
          {rows.map((row) =>
            row.kind === 'CLEAR' ? (
              <ClearRule key={row.id} row={row} />
            ) : (
              <HistoryRowItem key={row.id} row={row} windowName={row.source?.window ? windowNames.get(row.source.window) : undefined} />
            ),
          )}
          {data?.more && <li className="py-2 text-xs text-muted-foreground">Older changes are not shown.</li>}
        </ol>
      )}
    </div>
  )
}

function ClearRule({ row }: { row: HistoryRow }) {
  return (
    <li className="flex items-center gap-2 py-2 text-xs text-muted-foreground" data-kind="CLEAR">
      <span className="h-px flex-1 bg-border" />
      <span>
        {row.label} · {historyTime(row.at)}
      </span>
      <span className="h-px flex-1 bg-border" />
    </li>
  )
}

function HistoryRowItem({ row, windowName }: { row: HistoryRow; windowName?: string }) {
  const [expanded, setExpanded] = useState(false)
  const show = row.kind === 'SHOW'
  const source = row.source ? (windowName ? `${row.source.name} · ${windowName}` : row.source.name) : null
  const canExpand = row.records.length > 0
  return (
    <li className={cn('border-b last:border-b-0', row.undone && 'opacity-50')} data-kind={row.kind}>
      <button
        type="button"
        className="flex w-full items-start gap-2 py-2 text-left disabled:cursor-default"
        onClick={() => setExpanded((v) => !v)}
        disabled={!canExpand}
        aria-expanded={canExpand ? expanded : undefined}
      >
        <span className="w-16 shrink-0 pt-0.5 font-mono text-xs text-muted-foreground">{historyTime(row.at)}</span>
        <span
          className={cn(
            'mt-0.5 grid size-5 shrink-0 place-items-center rounded text-[9px] font-bold',
            show ? 'bg-blue-600 text-white' : 'bg-violet-600 text-white',
          )}
          aria-label={show ? 'Show change' : 'Programmer step'}
        >
          {show ? 'S' : 'P'}
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className={cn('truncate text-sm', row.undone && 'line-through')}>{row.label}</span>
          <span className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
            {source && <span className="rounded border px-1.5 py-px">{source}</span>}
            {row.counts && <Counts counts={row.counts} />}
            {row.moves.map((move) => (
              <span key={move} className="text-amber-500">
                {move}
              </span>
            ))}
          </span>
        </span>
        {canExpand && (
          <ChevronRight className={cn('mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform', expanded && 'rotate-90')} />
        )}
      </button>
      {expanded && (
        <ul className="mb-2 ml-[5.5rem] flex flex-col gap-0.5 text-xs text-muted-foreground">
          {row.records.map((record) => (
            <li key={`${record.folder}/${record.uuid}`}>
              <span className="font-mono">{record.change === 'ADDED' ? '+' : record.change === 'REMOVED' ? '−' : '~'}</span>{' '}
              {record.label}
            </li>
          ))}
          {row.moreRecords > 0 && <li>and {row.moreRecords} more</li>}
        </ul>
      )}
    </li>
  )
}

function Counts({ counts }: { counts: NonNullable<HistoryRow['counts']> }) {
  return (
    <span className="font-mono">
      {counts.added > 0 && <span className="text-green-500">+{counts.added} </span>}
      {counts.removed > 0 && <span className="text-red-500">−{counts.removed} </span>}
      {counts.changed > 0 && <span>~{counts.changed}</span>}
    </span>
  )
}

/** The header's History icon (undo plan §4.1): opens the sheet. */
export function HistoryButton({ className }: { className?: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          onClick={openHistorySheet}
          aria-label="History"
          className={cn('text-primary-foreground hover:bg-primary-foreground/10', className)}
        >
          <History className="size-5" />
        </Button>
      </TooltipTrigger>
      <TooltipContent>History</TooltipContent>
    </Tooltip>
  )
}
