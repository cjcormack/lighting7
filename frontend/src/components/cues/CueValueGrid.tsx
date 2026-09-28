import { useMemo } from 'react'
import { Layers } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { COLUMN_DEFS, columnLabel } from '@/components/fixtures-list/columns'
import { buildRows } from '@/components/fixtures-list/rowModel'
import { buildRowCells } from '@/components/fixtures-list/useRowValues'
import { cellValueFromParts, stagedPartFor } from '@/components/fixtures-list/scopedCellValue'
import { lookRowKey, splitLookRowKey } from '@/components/programmer/lookRowKey'
import { ColourCell } from '@/components/fixtures-list/cells/ColourCell'
import { PositionCell } from '@/components/fixtures-list/cells/PositionCell'
import { SettingCell } from '@/components/fixtures-list/cells/SettingCell'
import { SliderCell } from '@/components/fixtures-list/cells/SliderCell'
import { useFixtureListQuery } from '@/store/fixtures'
import { useProjectCueCookedQuery } from '@/store/cues'
import { buildStaticRows } from './cueCookedRows'
import { elementParents } from './targetUtils'
import type { CellValue, RowCell } from '@/components/fixtures-list/useRowValues'
import type { ColumnKey } from '@/components/fixtures-list/columns'

/** How many heads a collapsed grid shows before it stops and counts the rest. */
const PREVIEW_ROWS = 6

/**
 * What a cue asserts, in the grid's language, read-only.
 *
 * The cue read surface. Same cell renderers as the programmer's value grid — the same fill bars,
 * swatches and crosshairs — so a cue and the programmer that made it read identically. That was the
 * §1 complaint this answers: the three-pane editor expressed the same state a second, differently
 * shaped way, and two renderings of one thing do not stay in step.
 *
 * **Not `FixturesListContainer`.** Mounting that here was the obvious move and is wrong three ways:
 * it owns a filter, checkboxes and a drag-select marquee, none of which mean anything about a cue;
 * its selection is Redux-scoped and there are only three scopes, so a cue card would either clobber
 * the programmer's selection or need a fourth that nothing ever acts on; and a stack of expanded
 * cards would mount several copies of a several-hundred-row virtualized table. What a cue needs is
 * the *value language*, not the spreadsheet — so this borrows the cells and leaves the rest.
 *
 * Only the heads the cue actually touches, and only the columns that carry a value: a cue over four
 * washes should not be read against a grid of the whole rig.
 */
export function CueValueGrid({
  projectId,
  cueId,
  enabled = true,
}: {
  projectId: number
  cueId: number
  enabled?: boolean
}) {
  const { data, isSuccess, isError } = useProjectCueCookedQuery(
    { projectId, cueId },
    { skip: !enabled },
  )
  const { data: allFixtures } = useFixtureListQuery()

  const model = useMemo(() => {
    const cooked = buildStaticRows(data?.rows, allFixtures, isSuccess)
    // The heads to show are the ones the cook mentions — derived from the expanded map, so a
    // group-targeted row correctly brings its members in rather than a group nobody can point at.
    // A cell-target row keys the cook by a head's element key, which is no fixture's key: it brings
    // its parent in *expanded*, so the head's own row is there to carry the value.
    const parentOf = elementParents(allFixtures)
    const touched = new Set<string>()
    const expandedFixtures = new Set<string>()
    for (const key of cooked.rows.keys()) {
      const parts = splitLookRowKey(key)
      if (!parts) continue
      const parent = parentOf.get(parts.targetKey)
      touched.add(parent ?? parts.targetKey)
      if (parent) expandedFixtures.add(parent)
    }
    const fixtures = (allFixtures ?? []).filter((f) => touched.has(f.key))
    const rows = buildRows({
      fixtures,
      groups: [],
      expandedGroups: new Set(),
      expandedFixtures,
      textFilter: '',
    })

    const lookup = (targetKey: string, propertyName: string) =>
      cooked.rows.get(lookRowKey(targetKey, propertyName))

    // Two passes: resolve every row's cells, then keep the columns something actually filled. A
    // cue with no position values should not be read against an empty Position column.
    const everyRow = rows.map((row) => {
      const cells = buildRowCells(row, ALL_COLUMNS)
      const values = new Map<ColumnKey, CellValue>()
      for (const cell of cells) {
        const parts = cell.resolutions.map((res, i) =>
          stagedPartFor(res, cell.targetKeys[i], lookup),
        )
        const value = cellValueFromParts(cell.resolutions, parts)
        if (value) values.set(cell.col, value)
      }
      return { row, cells, values, layer: layerFor(cells, cooked.layerByKey) }
    })
    // Expanding a parent brings every head; only the heads the cue holds are worth a row.
    const resolved = everyRow.filter((r) => r.row.kind !== 'element' || r.values.size > 0)
    const columns = ALL_COLUMNS.filter((col) => resolved.some((r) => r.values.has(col)))
    return { resolved, columns, loaded: isSuccess }
  }, [data, allFixtures, isSuccess])

  if (!enabled) return null
  // Said, not spun on forever: `loaded` is `isSuccess`, so a failed read would otherwise sit on
  // "Reading the cue…" for as long as the card stayed open.
  if (isError) {
    return (
      <p className="px-1 py-2 text-xs text-muted-foreground">
        The cue&rsquo;s composed values could not be read.
      </p>
    )
  }
  if (!model.loaded) {
    return <p className="px-1 py-2 text-xs text-muted-foreground">Reading the cue…</p>
  }
  if (model.resolved.length === 0 || model.columns.length === 0) {
    return (
      <p className="px-1 py-2 text-xs text-muted-foreground">
        This cue asserts no values of its own. Anything on stage from it is coming from its effects.
      </p>
    )
  }

  const shown = model.resolved.slice(0, PREVIEW_ROWS)
  const hidden = model.resolved.length - shown.length
  const template = `minmax(0, 1.4fr) repeat(${model.columns.length}, minmax(64px, 1fr))`

  return (
    <div className="space-y-1">
      <div className="overflow-x-auto">
        <div className="min-w-[22rem]">
          <div
            className="grid border-b text-[10.5px] font-medium uppercase tracking-wider text-muted-foreground"
            style={{ gridTemplateColumns: template }}
          >
            <div className="px-1.5 py-1">Fixture</div>
            {model.columns.map((col) => (
              <div key={col} className="px-1.5 py-1">
                {columnLabel(col)}
              </div>
            ))}
          </div>
          {shown.map(({ row, cells, values, layer }) => (
            <div
              key={row.id}
              className="grid h-8 items-center border-b border-border/60 text-xs last:border-0"
              style={{ gridTemplateColumns: template }}
            >
              <div className="flex min-w-0 items-center gap-1 px-1.5">
                <span className="truncate">{rowName(row)}</span>
                {layer && (
                  <span
                    className="shrink-0 text-muted-foreground"
                    title={`from layer “${layer}”`}
                    aria-label={`from layer ${layer}`}
                  >
                    <Layers className="size-2.5" />
                  </span>
                )}
              </div>
              {model.columns.map((col) => {
                const value = values.get(col)
                const cell = cells.find((c) => c.col === col)
                return (
                  <div key={col} className="h-full min-w-0 py-0.5">
                    {value && cell ? (
                      <ReadOnlyCell cell={cell} label={columnLabel(col)} value={value} />
                    ) : (
                      <span className="flex h-full items-center px-1.5 text-muted-foreground/50">
                        —
                      </span>
                    )}
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </div>
      {hidden > 0 && (
        <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
          + {hidden} more
        </Badge>
      )}
    </div>
  )
}

const ALL_COLUMNS: readonly ColumnKey[] = COLUMN_DEFS.map((d) => d.key)
function rowName(row: ReturnType<typeof buildRows>[number]): string {
  if (row.kind === 'fixture') return row.fixture.name
  if (row.kind === 'element') return `${row.fixture.name} ${row.element.displayName}`
  if (row.kind === 'group') return row.name
  return ''
}

/** The one layer name this row's values came from, when they agree on one. */
function layerFor(
  cells: readonly RowCell[],
  layerByKey: ReadonlyMap<string, { name?: string | null }>,
): string | null {
  const names = new Set<string>()
  for (const cell of cells) {
    for (const key of cell.keys) {
      const hit = layerByKey.get(lookRowKey(key.targetKey, key.propertyName))
      if (hit?.name) names.add(hit.name)
    }
  }
  return names.size === 1 ? [...names][0] : null
}

/**
 * A cell editor with its writes taken away.
 *
 * The real components rather than a read-only lookalike, so a cue's colour swatch and the
 * programmer's are the same swatch. `pointer-events-none` is what makes it a read: the editors are
 * Popover triggers, and a cue is edited by Including it into the programmer, not here.
 */
function ReadOnlyCell({ cell, label, value }: { cell: RowCell; label: string; value: CellValue }) {
  const shared = {
    resolutions: cell.resolutions,
    label,
    onCommit: () => {},
    onBeginEdit: () => {},
  }
  return (
    <div className="pointer-events-none h-full">
      {value.kind === 'slider' && <SliderCell {...shared} value={value} />}
      {value.kind === 'colour' && <ColourCell {...shared} value={value} />}
      {value.kind === 'position' && <PositionCell {...shared} value={value} />}
      {value.kind === 'setting' && <SettingCell {...shared} value={value} />}
    </div>
  )
}
