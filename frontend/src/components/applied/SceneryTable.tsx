import { useCallback, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { SlidersHorizontal, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { ElementIcon } from '@/components/scenery/SceneryReadout'
import {
  DEFAULT_SCENERY_FILTERS,
  NO_SCENERY_YET,
  SceneryElementRow,
  sceneryGroups,
  useSceneryFilters,
  useSceneryScope,
  type SceneryFilters,
  type SceneryScope,
} from '@/components/programmer/ProgrammerSceneryList'
import { listNames, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import { NumberCell } from '@/components/sheet/cells/NumberCell'
import { OptionCell, type SheetOption } from '@/components/sheet/cells/OptionCell'
import type { CellActionCopy, CellKeyboardPermission } from '@/components/sheet/cellEntry'
import { useStageElementListQuery } from '@/store/stageElements'
import type { StageElementDto } from '@/api/stageElementApi'
import type { SceneryState } from '@/api/sceneryApi'
import { sceneryKindLabel, sceneryRangeOf } from '@/lib/scenery'
import { cn } from '@/lib/utils'
import { matchesFilter, type TableView } from '@/lib/programmerTables'
import { AppliedTableShell, type AppliedBand } from './AppliedTableShell'

type SceneryColumnKey = 'shown' | 'open' | 'trim' | 'source' | 'kind'

interface ElementRow extends SheetRow {
  element?: StageElementDto
  /** What the row draws: the stage's state, a focused Look's own over it. */
  state?: SceneryState
  readout?: string
  held?: boolean
  staged?: boolean
}

const NO_SORT: ReadonlySet<string> = new Set()

/**
 * A held piece's row: a primary tint, laid as an inset shadow so it sits over the selection's own
 * background tint rather than replacing it. The name cell draws the tint again with a 3px edge
 * ([HELD_EDGE_CLASS]), since the sticky cell's opaque ground hides anything the row paints there; a
 * selected row leaves the edge to the selection's.
 */
const HELD_TINT = 'shadow-[inset_0_0_0_100vmax_color-mix(in_oklab,var(--color-primary)_6%,transparent)]'
const HELD_EDGE_CLASS = `pointer-events-none absolute inset-0 ${HELD_TINT}`
const HELD_EDGE_LINE_CLASS = 'pointer-events-none absolute inset-y-0 left-0 w-[3px] bg-primary'

/**
 * **The Scenery table** (tables-colours plan D6): every scene element, **Venue then Set** and by
 * name under dividers, with four filters — *Set*, *Venue*, *Moving only*, *Changed* — as chips and
 * *All* their unfiltered state. A piece the scope holds is listed whatever they say, so its release
 * × stays in reach.
 *
 * **One scope reading** (`useSceneryScope`), shared with the Stage view's scenery popover: Local
 * writes the programmer's own scenery, a focused Look layer that Look's, Output and a palette layer
 * nothing. A row's state, what holds it and whether Blind is staging it are read-outs; ⏎ or the
 * row's sliders open the element's own `SceneryElementRow`, control and all, at the row.
 *
 * The filters are `useSceneryFilters`, per window.
 */
export function SceneryTable({
  projectId,
  view,
  onViewChange,
}: {
  projectId: number
  view: TableView
  onViewChange: (next: TableView) => void
}) {
  const { data: elements } = useStageElementListQuery(projectId)
  const scope = useSceneryScope(projectId)
  const [filters, setFilter] = useSceneryFilters()
  const [editing, setEditing] = useState<string | null>(null)

  const heldKeys = useMemo(() => new Set(scope.held.keys()), [scope.held])
  const rows = useMemo<ElementRow[]>(() => {
    const matching = (elements ?? []).filter((e) => heldKeys.has(e.uuid) || matchesFilter(view.q, [e.name, sceneryKindLabel(e)]))
    return sceneryGroups(matching, filters, heldKeys, scope.isChanged).flatMap((group) => [
      { id: `divider:${group.layer}`, divider: group.layer === 'VENUE' ? 'Venue' : 'Set' },
      ...group.elements.map((element) => {
        const face = scope.rowOf(element)
        return {
          id: element.uuid,
          element,
          state: face.state,
          readout: face.readout,
          held: face.held,
          staged: face.staged,
        }
      }),
    ])
  }, [elements, filters, heldKeys, scope, view.q])

  // `useSceneryScope` answers a fresh object every render, so the columns read it through a ref and
  // stay put; the cells' gate is rebuilt only when what it says moves.
  const scopeRef = useRef(scope)
  scopeRef.current = scope
  const columnsFor = useCallback((band: AppliedBand) => sceneryColumns(band, scopeRef), [])
  const armKey = scope.arm.kind === 'readOnly' ? `r:${scope.arm.reason}` : scope.arm.kind === 'look' ? `l:${scope.arm.name}` : 'local'
  // Complete: `sceneryCells` reads only the scope's `arm` and `ready`, which `armKey` and `ready` say.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const cells = useMemo(() => sceneryCells(scopeRef.current), [armKey, scope.ready])
  const firstColumnFor = useCallback(
    (band: AppliedBand) => ({
      label: 'Element',
      width: band >= 2 ? '200px' : '240px',
      render: (row: ElementRow, selected: boolean) =>
        row.element == null ? null : (
          <ElementNameCell
            element={row.element}
            row={row}
            selected={selected}
            scope={scope}
            editing={editing === row.element.uuid}
            onEditingChange={(open) => setEditing(open ? row.element!.uuid : null)}
          />
        ),
    }),
    [editing, scope],
  )

  const total = (elements ?? []).length
  const held = heldKeys.size
  const lookName = scope.arm.kind === 'look' ? scope.arm.name : null

  return (
    <AppliedTableShell<ElementRow, SceneryColumnKey>
      table="scenery"
      rows={rows}
      total={total}
      columnsFor={columnsFor}
      firstColumnFor={firstColumnFor}
      sortKeys={NO_SORT}
      firstSortKey="name"
      view={view}
      onViewChange={onViewChange}
      filterLabel="Filter by element or kind"
      chips={<SceneryFilterChips filters={filters} onChange={setFilter} />}
      noun="element"
      rowName={(row) => row.element?.name ?? 'element'}
      onOpenRow={(row) => row.element != null && setEditing(row.element.uuid)}
      pencil={false}
      rowClass={(row) => (row.held ? HELD_TINT : undefined)}
      verbs={(selected) => <SceneryVerbs selected={selected} scope={scope} />}
      cells={cells}
      emptyNote={NO_SCENERY_YET}
      loading={elements == null}
      footer={
        <span className="tabular-nums">
          {total} element{total === 1 ? '' : 's'} ·{' '}
          {lookName != null ? `${held} in ${lookName}` : `${held} held · top wins`}
          {scope.arm.kind === 'readOnly' ? ` · ${scope.arm.reason}` : ''}
        </span>
      }
    />
  )
}

const SHOWN_OPTIONS: readonly SheetOption[] = [
  { value: 'shown', label: 'Shown' },
  { value: 'hidden', label: 'Hidden' },
]

const RELEASE_INSTEAD = 'A piece is let go whole — Release it from the bar or its ×'

const names = (rows: readonly ElementRow[]) => listNames(rows.map((r) => r.element?.name ?? 'element'), 'element')
const has = (rows: readonly ElementRow[]) => (rows.length === 1 ? 'has' : 'have')

/**
 * The state as three columns, one per kind of state: *Shown* on every piece,
 * *Open* on a drawn drape, *Trim* on a flown piece with somewhere to fly. A marquee down a column
 * and Set write that state to every selected piece that has it, through the scope as a row's own
 * control does — one write, since a focused Look's scenery is written whole. A piece without the
 * state is skipped and named. There is no Clear (a piece is released whole) and no Spread.
 */
function sceneryColumns(
  band: AppliedBand,
  scope: RefObject<SceneryScope>,
): { columns: SheetColumn<ElementRow, SceneryColumnKey>[]; minWidth: number } {
  const writeAll = (rows: readonly ElementRow[], patch: (row: ElementRow) => SceneryState) =>
    scope.current.writeMany(rows.filter((row) => row.element != null).map((row) => ({ elementUuid: row.element!.uuid, state: patch(row) })))

  const shown: SheetColumn<ElementRow, SceneryColumnKey> = {
    key: 'shown',
    label: 'Shown',
    kind: 'shown',
    width: '104px',
    value: (row) => (row.state == null ? undefined : row.state.visible === false ? 'hidden' : 'shown'),
    cell: (_row, props) => <OptionCell {...(props as React.ComponentProps<typeof OptionCell>)} options={SHOWN_OPTIONS} />,
    write: (rows, value) => {
      if (value !== 'shown' && value !== 'hidden') return false
      writeAll(rows, () => ({ visible: value === 'shown' }))
      return true
    },
    clearRefusal: RELEASE_INSTEAD,
  }
  const open: SheetColumn<ElementRow, SceneryColumnKey> = {
    key: 'open',
    label: 'Open',
    kind: 'open',
    width: '96px',
    align: 'right',
    value: (row) => (row.state?.open == null ? undefined : Math.round(row.state.open * 100)),
    cell: (_row, props) => (
      <NumberCell {...(props as React.ComponentProps<typeof NumberCell>)} unit="%" min={0} max={100} />
    ),
    skipNote: (rows) => `${names(rows)} ${has(rows)} nothing to draw · skipped`,
    write: (rows, value) => {
      if (typeof value !== 'number' || value < 0 || value > 100) return false
      writeAll(rows, () => ({ open: value / 100 }))
      return true
    },
    clearRefusal: RELEASE_INSTEAD,
  }
  const trim: SheetColumn<ElementRow, SceneryColumnKey> = {
    key: 'trim',
    label: 'Trim',
    kind: 'trim',
    width: '96px',
    align: 'right',
    value: (row) =>
      row.element == null || row.state?.trimM == null || sceneryRangeOf(row.element)?.key !== 'trimM' ? undefined : row.state.trimM,
    cell: (_row, props) => (
      <NumberCell
        {...(props as React.ComponentProps<typeof NumberCell>)}
        unit="m"
        step={0.01}
        format={metres}
        note="Each piece stops at its own in and out"
      />
    ),
    skipNote: (rows) => `${names(rows)} ${has(rows)} nowhere to fly · skipped`,
    write: (rows, value) => {
      if (typeof value !== 'number' || !Number.isFinite(value)) return false
      writeAll(rows, (row) => {
        const range = sceneryRangeOf(row.element!)!
        return { trimM: Math.min(range.max, Math.max(range.min, value)) }
      })
      return true
    },
    clearRefusal: RELEASE_INSTEAD,
  }
  const source: SheetColumn<ElementRow, SceneryColumnKey> = {
    key: 'source',
    label: 'Held by',
    width: 'minmax(160px, 1fr)',
    value: () => undefined,
    display: (row) => (row.readout == null ? null : cellText(row.readout, true)),
  }
  const kind: SheetColumn<ElementRow, SceneryColumnKey> = {
    key: 'kind',
    label: 'Kind',
    width: '120px',
    value: () => undefined,
    display: (row) => (row.element == null ? null : cellText(sceneryKindLabel(row.element), true)),
  }
  const name = band >= 2 ? 200 : 240
  return { columns: [shown, open, trim, source, kind], minWidth: name + 104 + 96 + 96 + 160 + 120 }
}

const metres = (v: number) => (Math.round(v * 100) / 100).toFixed(2)

/** The scope's gate for the state cells: Output and a palette layer write nothing, a loading Look waits. */
function sceneryCells(scope: SceneryScope): {
  permission: CellKeyboardPermission
  copy: (cellCount: number) => CellActionCopy
  cellDisabled: () => boolean
} {
  const refusal =
    scope.arm.kind === 'readOnly' ? scope.arm.reason : !scope.ready ? 'The Look’s scenery is still loading' : null
  const where = scope.arm.kind === 'look' ? `into ${scope.arm.name}` : 'in the programmer'
  return {
    permission: { entry: refusal == null, clear: false },
    copy: (count) => ({
      setTitle: refusal ?? `Set ${count === 1 ? 'this cell' : `${count} cells`} ${where}`,
      clearTitle: RELEASE_INSTEAD,
    }),
    cellDisabled: () => refusal != null,
  }
}

function cellText(text: string, muted = false) {
  return (
    <span className={cn('mx-1.5 truncate text-xs', muted && 'text-muted-foreground')} title={text}>
      {text}
    </span>
  )
}

/**
 * The element's glyph and name, and its two controls: the sliders, opening the band's own row for
 * the element at the row, and — where the scope holds it and can write — the release ×.
 */
function ElementNameCell({
  element,
  row,
  selected,
  scope,
  editing,
  onEditingChange,
}: {
  element: StageElementDto
  row: ElementRow
  selected: boolean
  scope: SceneryScope
  editing: boolean
  onEditingChange: (open: boolean) => void
}) {
  const writable = scope.arm.kind !== 'readOnly' && scope.ready
  return (
    <>
      {row.held && (
        <span aria-hidden data-held-edge className={HELD_EDGE_CLASS}>
          {!selected && <span className={HELD_EDGE_LINE_CLASS} />}
        </span>
      )}
      <ElementIcon element={element} />
      <span className="relative min-w-0 flex-1 truncate text-sm font-medium" title={element.name}>
        {element.name}
        {row.staged && <span className="ml-1.5 inline-block size-1.5 rounded-full bg-amber-500 align-middle" title="Blind is staging this" />}
      </span>
      <span className="flex shrink-0 items-center" onClick={(e) => e.stopPropagation()}>
        <EditorSurface
          open={editing}
          onOpenChange={onEditingChange}
          title={element.name}
          align="end"
          contentClassName="w-72 p-2"
          trigger={
            <Button variant="ghost" size="icon" className="size-6" aria-label={`Move ${element.name}`} title={`Move ${element.name}`}>
              <SlidersHorizontal className="size-3.5" />
            </Button>
          }
        >
          <SceneryElementRow element={element} scope={scope} />
        </EditorSurface>
        {row.held && writable && (
          <Button
            variant="ghost"
            size="icon"
            className="size-6"
            aria-label={`Release ${element.name}`}
            title={scope.arm.kind === 'look' ? `Take ${element.name} out of ${scope.arm.name}` : `Release ${element.name}`}
            onClick={() => scope.release(element.uuid)}
          >
            <X className="size-3.5" />
          </Button>
        )}
      </span>
    </>
  )
}

/** *All · Set · Venue · Moving only · Changed*: four independent filters, and *All* their unfiltered state. */
function SceneryFilterChips({ filters, onChange }: { filters: SceneryFilters; onChange: (next: SceneryFilters) => void }) {
  const all = filters.set && filters.venue && !filters.movingOnly && !filters.changedOnly
  const chip = (on: boolean, label: ReactNode, key: string, next: SceneryFilters, title?: string) => (
    <button
      key={key}
      type="button"
      aria-pressed={on}
      title={title}
      onClick={() => onChange(next)}
      className={cn(
        'inline-flex h-7 shrink-0 items-center rounded-md px-2.5 text-xs font-semibold',
        on ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {label}
    </button>
  )
  return (
    // No `min-w-0`: the chips push the filter back rather than run off the row.
    <div className="flex flex-1">
      <div role="group" aria-label="Scenery filters" className="inline-flex items-center gap-0.5 rounded-lg border bg-card p-0.5">
        {chip(all, 'All', 'all', DEFAULT_SCENERY_FILTERS)}
        {chip(filters.set, 'Set', 'set', { ...filters, set: !filters.set })}
        {chip(filters.venue, 'Venue', 'venue', { ...filters, venue: !filters.venue })}
        {chip(
          filters.movingOnly,
          <>
            Moving<span className="hidden @[600px]:inline">&nbsp;only</span>
          </>,
          'moving',
          { ...filters, movingOnly: !filters.movingOnly },
        )}
        {chip(
          filters.changedOnly,
          'Changed',
          'changed',
          { ...filters, changedOnly: !filters.changedOnly },
          'Show only scenery moved off its base',
        )}
      </div>
    </div>
  )
}

/** The bar's verb over the selected elements: release every one the scope holds. */
function SceneryVerbs({ selected, scope }: { selected: readonly ElementRow[]; scope: SceneryScope }) {
  const releasable = selected.filter((row) => row.held && row.element != null)
  // A Look's scenery is written whole per gesture, so two releases in one press would each send a
  // list holding the other's piece.
  const reason =
    scope.arm.kind === 'readOnly'
      ? scope.arm.reason
      : !scope.ready
        ? 'The Look’s scenery is still loading'
        : releasable.length === 0
          ? 'None of these is held'
          : scope.arm.kind === 'look' && releasable.length > 1
            ? `Take pieces out of ${scope.arm.name} one at a time`
            : null
  return (
    <Button
      variant="outline"
      size="sm"
      disabled={reason != null}
      title={reason ?? `Release ${releasable.length} piece${releasable.length === 1 ? '' : 's'}`}
      onClick={() => releasable.forEach((row) => scope.release(row.element!.uuid))}
    >
      <X className="size-3.5" />
      Release
    </Button>
  )
}
