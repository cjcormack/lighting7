import { useCallback, useMemo } from 'react'
import { Blinds, Layers, Sparkles, X, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { CountReadOut, ReadOnlyNote } from '@/components/sheet/LibraryReadOuts'
import { libraryNameColumn } from '@/components/sheet/LibraryNameColumn'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetTable, type SheetSort } from '@/components/sheet/SheetTable'
import { TextCell } from '@/components/sheet/cells/TextCell'
import { libraryPermission } from '@/components/sheet/libraryScope'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { useSheet } from '@/components/sheet/useSheet'
import { CueActions, type CueActionRow } from '@/components/runner/CueActions'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { formatFadeText } from '@/lib/cueUtils'
import { CUES_SORT_KEYS, describeUsedBy, usedByTitle, type CuesView } from '@/lib/cuesTable'
import { nextSort } from '@/lib/programmerTables'
import { cn } from '@/lib/utils'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { usePatchProjectCueMutation } from '@/store/cues'
import { useCueRings } from '@/store/cueHolds'
import type { Cue } from '@/api/cuesApi'

export type CueColumnKey = 'in' | 'fade' | 'layers' | 'fx' | 'scenery' | 'events' | 'usedBy' | 'notes'

export interface CueSheetRow extends SheetRow {
  cue: Cue
}

export function cueRowId(cueId: number): string {
  return `cue:${cueId}`
}

export interface CueLibrarySheetProps {
  projectId: number
  /** The rows to draw, filtered and sorted by [view]. */
  cues: readonly Cue[]
  view: CuesView
  onViewChange: (next: CuesView) => void
  /**
   * Why nothing here may be changed, or null. Off the current project the table is read-only and
   * draws no actions; locked, its cells are inert and its actions ask to unlock.
   */
  readOnlyReason: string | null
  /** Another project's cues: no action set at all, since every one of them is the running show's. */
  otherProject: boolean
  /** The show-editing lock, while the show runs (cue model plan §10, 20). */
  locked: boolean
  onRequestUnlock?: () => void
  onOpenCue?: (cue: Cue) => void
}

/**
 * **The Cues table** (lighting7 cue model plan §3.7, D13) — every cue of the project once, a library
 * sheet beside Looks and Palettes. A cue is a project record and a stack only names it, so this is
 * the one place a cue in no stack is found.
 *
 * - **In** is each entry as `Act 2 · Q14`, a chip per place, *Loose* when there is none; the dot by
 *   the name is D11's two looks from `cueHolds.state` — solid held loose, an outline live only in a
 *   stack. **Used by** is what else names it: its busk pads, cue slots and bindings.
 * - **Name and notes are the cells** (library-sheets plan D5); everything a cue holds is changed in
 *   its editor, which a row opens.
 * - **The bar is the cue action set** (`CueActions`, its fourth host), never verbs of the table's
 *   own: *Duplicate*, *Delete…* (`CueDeleteDialog`), *Add to stack…* over several rows, and the
 *   one-cue actions over one. A row stands for the cue, so it hands the set its entry only when it
 *   has exactly one.
 * - **It honours the show-editing lock while the show runs**, as the cue sheet does: locked, the
 *   cells are inert and every action but *Blind edit* asks to unlock.
 */
export function CueLibrarySheet({
  projectId,
  cues,
  view,
  onViewChange,
  readOnlyReason,
  otherProject,
  locked,
  onRequestUnlock,
  onOpenCue,
}: CueLibrarySheetProps) {
  const [patchCue] = usePatchProjectCueMutation()
  const rings = useCueRings(projectId)
  const readOnly = readOnlyReason != null

  const rows = useMemo<CueSheetRow[]>(() => cues.map((cue) => ({ id: cueRowId(cue.id), cue })), [cues])

  const patch = useCallback(
    (cue: Cue, body: { name?: string; notes?: string | null }) => {
      patchCue({ projectId, cueId: cue.id, ...body })
        .unwrap()
        .catch(ignoreReportedError)
    },
    [patchCue, projectId],
  )

  const columns = useMemo<SheetColumn<CueSheetRow, CueColumnKey>[]>(
    () => [
      {
        key: 'in',
        label: 'In',
        width: 'minmax(160px, 260px)',
        value: () => undefined,
        display: (row) => <CuePlaces cue={row.cue} />,
      },
      {
        key: 'fade',
        label: 'Fade',
        width: '88px',
        value: () => undefined,
        display: (row) => (
          <span className="mx-2 font-mono text-xs tabular-nums text-muted-foreground">
            {formatFadeText(row.cue.fadeDurationMs, row.cue.fadeCurve)}
          </span>
        ),
      },
      {
        key: 'layers',
        label: 'Layers',
        width: '72px',
        value: () => undefined,
        display: (row) => (
          <CountReadOut count={row.cue.layers.length} icon={<Layers className="size-3" />} title="Look and palette layers" />
        ),
      },
      {
        key: 'fx',
        label: 'FX',
        width: '64px',
        value: () => undefined,
        display: (row) => (
          <CountReadOut count={row.cue.adHocEffects.length} icon={<Sparkles className="size-3" />} title="The cue’s own effects" />
        ),
      },
      {
        key: 'scenery',
        label: 'Scenery',
        width: '80px',
        value: () => undefined,
        display: (row) => (
          <CountReadOut count={row.cue.scenery?.length ?? 0} icon={<Blinds className="size-3" />} title="Scenery the cue moves on GO" />
        ),
      },
      {
        key: 'events',
        label: 'Events',
        width: '72px',
        value: () => undefined,
        display: (row) => (
          <CountReadOut count={row.cue.events?.length ?? 0} icon={<Zap className="size-3" />} title="Effects the cue fires on GO" />
        ),
      },
      {
        key: 'usedBy',
        label: 'Used by',
        width: 'minmax(110px, 170px)',
        value: () => undefined,
        display: (row) => {
          const text = describeUsedBy(row.cue)
          return text == null ? (
            <span className="mx-2 text-xs text-muted-foreground/45">—</span>
          ) : (
            <span className="mx-2 truncate text-xs text-muted-foreground" title={usedByTitle(row.cue)}>
              {text}
            </span>
          )
        },
      },
      {
        key: 'notes',
        label: 'Notes',
        kind: 'notes',
        width: 'minmax(140px, 1fr)',
        value: (row) => row.cue.notes ?? '',
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            allowEmpty
            face={
              row.cue.notes ? (
                <span className="mx-1.5 truncate text-xs text-muted-foreground">{row.cue.notes}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : value.trim()
          for (const row of batch) if (next !== (row.cue.notes ?? null)) patch(row.cue, { notes: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.cue.notes) patch(row.cue, { notes: null })
        },
      },
    ],
    [patch],
  )

  const { permission, copy } = useMemo(
    () => libraryPermission(!readOnly, null, readOnlyReason ?? undefined),
    [readOnly, readOnlyReason],
  )

  const openRow = useCallback((row: CueSheetRow) => onOpenCue?.(row.cue), [onOpenCue])
  const sheet = useSheet<CueSheetRow, CueColumnKey>({
    rows,
    columns,
    permission,
    copy,
    cellDisabled: readOnly ? cellsInert : undefined,
    noun: 'cue',
    rowName: cueRowName,
    onOpenRow: onOpenCue != null ? openRow : undefined,
  })
  const { selectedRows, cellCount, clearByLadder } = sheet

  const actionRows = useMemo<CueActionRow[]>(() => selectedRows.map((row) => actionRowOf(row.cue)), [selectedRows])

  const sort = useMemo<SheetSort>(
    () => ({
      key: view.sort,
      dir: view.dir,
      keys: CUES_SORT_KEYS,
      firstKey: 'name',
      onSort: (key) => onViewChange(nextSort(view, key)),
    }),
    [onViewChange, view],
  )

  const firstColumn = useMemo(
    () =>
      libraryNameColumn<CueSheetRow>({
        label: 'Cue',
        width: `${NAME_WIDTH}px`,
        noun: 'cue',
        name: (row) => row.cue.name,
        rename: (row, next) => patch(row.cue, { name: next }),
        renameDisabled: readOnly ? () => true : undefined,
        badges: (row) => <LiveDot ring={rings.get(row.cue.id) ?? null} />,
        onOpen: onOpenCue != null ? openRow : undefined,
        openLabel: (row) => `Open ${row.cue.name}`,
      }),
    [onOpenCue, openRow, patch, readOnly, rings],
  )

  const count = selectedRows.length
  const noun = count === 1 ? 'cue' : 'cues'
  const verbs =
    count > 0 ? (
      <div className="flex shrink-0 items-center gap-1.5">
        {cellCount > 0 && (
          <CellSelectionActions
            copy={sheet.copy}
            permission={sheet.permission}
            setRef={sheet.setButtonRef}
            onSet={sheet.toggleCellEditor}
            onClear={sheet.clearSelectedCells}
          />
        )}
        {!otherProject && (
          <CueActions projectId={projectId} rows={actionRows} host="table" locked={locked} onRequestUnlock={onRequestUnlock} />
        )}
        <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="@container">
        <SelectionBar
          rowLabel={count > 0 ? `${count} ${noun}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          cellTitle={`${count} ${noun} · ${sheet.family ?? ''} — edit once, applies to all`}
          family={sheet.family}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: cellCount > 0 && sheet.permission.clear }}
          strip={otherProject && readOnlyReason != null ? <ReadOnlyNote reason={readOnlyReason} /> : undefined}
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
          foldForStrip={false}
        />
      </div>
      <SheetTable<CueSheetRow, CueColumnKey>
        {...sheet.tableProps}
        minWidth={`${NAME_WIDTH + MIN_TRACKS}px`}
        firstColumn={firstColumn}
        sort={sort}
      />
    </div>
  )
}

/** The name column's track; the other tracks' floors sum to [MIN_TRACKS]. */
const NAME_WIDTH = 220
const MIN_TRACKS = 160 + 88 + 72 + 64 + 80 + 72 + 110 + 140

function cueRowName(row: CueSheetRow): string {
  return row.cue.name
}

function cellsInert(): boolean {
  return true
}

/**
 * The row the cue action set acts on: the cue, with its entry where it has exactly one. A cue in
 * several places has none to name here — the table draws the cue, not a place — and says so.
 */
export function actionRowOf(cue: Cue): CueActionRow {
  const entries = cue.entries ?? []
  const only = entries.length === 1 ? entries[0] : null
  return {
    cueId: cue.id,
    name: cue.name,
    cueNumber: only?.cueNumber ?? null,
    entryId: only?.entryId ?? null,
    stackId: only?.stackId ?? null,
    placeCount: entries.length,
  }
}

/** *In*: a chip per entry, `Act 2 · Q14`, or *Loose*. */
function CuePlaces({ cue }: { cue: Cue }) {
  const entries = cue.entries ?? []
  if (entries.length === 0) {
    return (
      <span className="mx-1.5 inline-flex h-[18px] items-center rounded-full border border-dashed px-1.5 text-[10px] font-medium text-muted-foreground" data-cue-loose>
        Loose
      </span>
    )
  }
  const all = entries.map((e) => (e.cueNumber ? `${e.stackName} · Q${e.cueNumber}` : e.stackName)).join(', ')
  return (
    <span className="mx-1.5 flex min-w-0 gap-1 overflow-hidden" title={all}>
      {entries.map((e) => (
        <span
          key={e.entryId}
          data-cue-place={e.entryId}
          className="inline-flex h-[18px] max-w-[11rem] shrink-0 items-center gap-1 rounded-full bg-muted px-1.5 text-[10px] font-medium"
        >
          <span className="truncate">{e.stackName}</span>
          {e.cueNumber && (
            <span className={cn('font-mono tabular-nums', e.cueNumberAuto && AUTO_CUE_NUMBER_CLASS)}>· Q{e.cueNumber}</span>
          )}
        </span>
      ))}
    </span>
  )
}

/** D11's two looks: solid while a press holds the cue loose, an outline while only a stack does. */
function LiveDot({ ring }: { ring: 'loose' | 'stack' | null }) {
  if (ring == null) return null
  return (
    <span
      data-cue-live={ring}
      title={ring === 'loose' ? 'Live — held loose' : 'Live in a stack'}
      className={cn('size-2 shrink-0 rounded-full', ring === 'loose' ? 'bg-green-500' : 'border border-green-500')}
    />
  )
}
