import { useCallback, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { Ban, Eye, Grid3x3, Hand, Layers, MoreHorizontal, Palette, Pencil, Plus, SlidersHorizontal, Trash2, Unlink } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { LibraryVerb, oneRecordReason } from '@/components/sheet/LibraryVerb'
import { PartitionChips } from '@/components/sheet/LibraryRow'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import type { RowId } from '@/components/sheet/cellSelectionModel'
import { EnableButton, LayerSettingsPopover, describeStackSource, type LayerHandlers, type StackSourceInfo } from '@/components/looks/LookStack'
import { LookNameBadge } from '@/components/looks/LookNameBadge'
import { LookDetailSheet } from '@/components/looks/LookDetailSheet'
import { useReplacementNotes } from '@/components/programmer/useReplacementNotes'
import { focusedLayerId, useProgrammerScope, useProgrammerScopeActions } from '@/components/programmer/ProgrammerScope'
import { useProgrammerSheets } from '@/components/programmer/ProgrammerSheets'
import { useProgrammerTables } from '@/components/programmer/ProgrammerTableTabs'
import { useLocalValueCount } from '@/components/programmer/useLocalFamilyCounts'
import { ApplyLayerPicker } from '@/components/programmer/ApplyLayerPicker'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { useActiveEffectsQuery, type ActiveEffect } from '@/store/fixtureFx'
import {
  programmerDetachLayer,
  programmerMoveLayer,
  programmerPatchLayer,
  programmerRemoveLayer,
  useProgrammerLayersQuery,
  type ProgrammerLayer,
} from '@/store/programmer'
import { handPickUp } from '@/store/hand'
import { formatFamilyList, parsePropertyMask } from '@/lib/attributeFamily'
import { sourceKey } from '@/lib/replacementNotes'
import {
  STACK_KINDS,
  fxHomeOf,
  layerEffectState,
  layerMoveOf,
  matchesFilter,
  parseStackKind,
  sortRows,
  stackLayers,
  type AppliedLayer,
  type SortValue,
  type StackKind,
  type TableView,
} from '@/lib/programmerTables'
import { AppliedTableShell, PROGRAMMER_CHIPS_CLASS, type AppliedBand } from './AppliedTableShell'
import { EffectsBelowRule } from './EffectsBelowRule'

type StackColumnKey = 'position' | 'kind' | 'targets' | 'amount' | 'blend' | 'mask' | 'stomp' | 'settings' | 'effects' | 'note'

interface LayerRow extends SheetRow {
  kind: 'LOOK' | 'TEMPLATE'
  entry: AppliedLayer
  info: StackSourceInfo
  note: string | null
  /** The instances this layer spawned, in the desk's order. */
  effects: readonly ActiveEffect[]
  effectState: 'running' | 'spent' | null
  targetsLabel: string
}

interface LocalRow extends SheetRow {
  kind: 'LOCAL'
  /** The programmer's own effects — Local, not a layer's, a cue's or the show's. */
  effects: readonly ActiveEffect[]
}

type StackSheetRow = LayerRow | LocalRow

const LOCAL_ROW_ID = 'local'
const SORT_KEYS: ReadonlySet<string> = new Set(['name', 'position', 'kind', 'targets', 'amount', 'effects', 'note'])
const KIND_LABELS: Readonly<Record<StackKind, string>> = { looks: 'Looks', palettes: 'Palettes' }

/**
 * **The Stack table** (tables-colours plan D5): *Your values* pinned on top, then every layer of the
 * programmer's stack top first — Looks and palettes in one table, because the order between them is
 * what decides who wins. The kind chips (All · Looks · Palettes) give the split back as a filter.
 *
 * **Reorder by drag**, `SheetTable`'s `rowDrag` over `programmerMoveLayer`, a drop in the table's top-first order mapped back to the desk's
 * bottom-first index (`layerMoveOf`). The drag is refused while the rows are not the stack as it
 * is — a kind chip, a filter or a sort leaves gaps a drop could not be read against — and the footer
 * says so. *Your values* is pinned: it beats every layer and cannot be dragged.
 *
 * **⏎, the name's double click and *Edit in grid* point the Values grid at the row** — a layer's
 * scope, or Local for *Your values* — and switch to Values. *Make layer* sits on *Your values*'
 * row. A layer row's controls are the stack's own — its enable toggle, settings popover (amount,
 * blend, mask, stomp), *Detach*, *Pick up* and *Remove* — and the Note column names what a later
 * press took from it (`useReplacementNotes`). This table is the one place the programmer's stack is
 * listed and reordered (D4).
 */
export function StackTable({
  projectId,
  view,
  onViewChange,
}: {
  projectId: number
  view: TableView
  onViewChange: (next: TableView) => void
}) {
  const navigate = useNavigate()
  const { data: layerList } = useProgrammerLayersQuery()
  const { data: lookList } = useLookListQuery({ projectId }, { skip: !projectId })
  const { data: templateList } = useTemplateListQuery({ projectId }, { skip: !projectId })
  const { data: effects } = useActiveEffectsQuery()
  const notes = useReplacementNotes()
  const scopeActions = useProgrammerScopeActions()
  const scope = useProgrammerScope()
  const focusedLayer = focusedLayerId(scope)
  const tables = useProgrammerTables()
  const sheets = useProgrammerSheets()

  const looksById = useMemo(() => new Map((lookList ?? []).map((l) => [l.id, l])), [lookList])
  const templatesById = useMemo(() => new Map((templateList ?? []).map((t) => [t.id, t])), [templateList])
  const loaded = lookList != null && templateList != null
  const layers = useMemo(() => layerList ?? [], [layerList])

  const local = useMemo<LocalRow>(
    () => ({ id: LOCAL_ROW_ID, kind: 'LOCAL', effects: (effects ?? []).filter((e) => fxHomeOf(e) === 'yours') }),
    [effects],
  )
  const all = useMemo<LayerRow[]>(
    () =>
      stackLayers(layers).map((entry) => {
        const { layer } = entry
        const own = (effects ?? []).filter((e) => e.programmerLayerId === layer.layerId)
        return {
          id: layerRowId(layer),
          kind: layer.source.kind === 'TEMPLATE' ? 'TEMPLATE' : 'LOOK',
          entry,
          info: describeStackSource(layer.source, looksById, templatesById, loaded),
          note: notes.get(sourceKey(layer.source)) ?? null,
          effects: own,
          effectState: layerEffectState(layer.layerId, own),
          targetsLabel: targetsLabelOf(layer),
        }
      }),
    [effects, layers, loaded, looksById, notes, templatesById],
  )

  const kindChip = parseStackKind(view.chip)
  const rows = useMemo<StackSheetRow[]>(() => {
    const shown = all.filter(
      (row) =>
        (kindChip == null || (kindChip === 'looks') === (row.kind === 'LOOK')) &&
        matchesFilter(view.q, [row.info.name, row.targetsLabel, row.note, ...row.entry.layer.targets.map((t) => t.key)]),
    )
    return [local, ...sortRows(shown, view.sort, view.dir, sortValue)]
  }, [all, kindChip, local, view.dir, view.q, view.sort])
  // *Your values* is always listed, so the shell's own "nothing matches" never draws: the table
  // says it under the rule instead, with the pinned row still above.
  const noMatch = all.length > 0 && rows.length === 1

  // A drop is read against the stack as it stands: every layer shown, in its own order.
  const dragRefusal =
    kindChip != null ? 'Show All to reorder' : view.q.trim() !== '' ? 'Clear the filter to reorder' : view.sort != null ? 'Unsort to reorder' : null
  const onReorder = useCallback(
    (ids: RowId[]) => {
      const move = layerMoveOf(layers, ids, layerRowId)
      if (move != null) programmerMoveLayer(move.layerId, move.toIndex)
    },
    [layers],
  )
  const rowDrag = useMemo(
    () => ({ enabled: dragRefusal == null, onReorder, canDrag: (row: StackSheetRow) => row.kind !== 'LOCAL' }),
    [dragRefusal, onReorder],
  )

  const focusInGrid = useCallback(
    (row: StackSheetRow) => {
      scopeActions?.setScope(row.kind === 'LOCAL' ? { kind: 'local' } : { kind: 'layer', layerId: row.entry.layer.layerId })
      tables?.select('values')
    },
    [scopeActions, tables],
  )
  const makeLayer = useCallback(() => {
    // Promoting moves the Local entries into the new layer, so the grid it returns to shows Local.
    scopeActions?.setScope({ kind: 'local' })
    sheets.openMakeLayer()
  }, [scopeActions, sheets])

  const [settingsFor, setSettingsFor] = useState<number | null>(null)
  const [lookOpen, setLookOpen] = useState<number | null>(null)
  const [adding, setAdding] = useState<'looks' | 'palettes' | null>(null)
  const editRecord = useCallback(
    (row: LayerRow) => {
      const { source } = row.entry.layer
      if (source.kind === 'LOOK') setLookOpen(source.id)
      else navigate(`/projects/${projectId}/palettes?edit=${source.id}`)
    },
    [navigate, projectId],
  )

  const columnsFor = useCallback((band: AppliedBand) => stackColumns(band, makeLayer), [makeLayer])
  const firstColumnFor = useCallback(
    (band: AppliedBand) => ({
      label: 'Holds',
      width: `${STACK_NAME_WIDTH[band]}px`,
      render: (row: StackSheetRow) =>
        row.kind === 'LOCAL' ? (
          <LocalNameCell compact={band >= 2} onFocus={() => focusInGrid(row)} />
        ) : (
          <LayerNameCell
            row={row}
            compact={band >= 2}
            settingsOpen={settingsFor === row.entry.layer.layerId}
            onSettingsOpenChange={(open) => setSettingsFor(open ? row.entry.layer.layerId : null)}
            onEditRecord={() => editRecord(row)}
            onEditInGrid={() => focusInGrid(row)}
          />
        ),
    }),
    [editRecord, focusInGrid, settingsFor],
  )

  const looks = all.filter((row) => row.kind === 'LOOK').length
  const total = all.length
  const spent = all.filter((row) => row.effectState === 'spent').length
  const lookForSheet = lookOpen != null ? (lookList ?? []).find((l) => l.id === lookOpen) ?? null : null

  return (
    <>
      <AppliedTableShell<StackSheetRow, StackColumnKey>
        table="stack"
        rows={rows}
        total={rows.length}
        columnsFor={columnsFor}
        firstColumnFor={firstColumnFor}
        sortKeys={SORT_KEYS}
        firstSortKey="name"
        view={view}
        onViewChange={onViewChange}
        filterLabel="Filter by look, palette, target or note"
        chips={
          <PartitionChips<StackKind>
            label="Kind"
            options={STACK_KINDS.map((kind) => ({
              value: kind,
              label: KIND_LABELS[kind],
              count: kind === 'looks' ? looks : total - looks,
            }))}
            value={kindChip ?? 'ALL'}
            allCount={total}
            onChange={(next) => onViewChange({ ...view, chip: next === 'ALL' ? null : next })}
            className={PROGRAMMER_CHIPS_CLASS}
          />
        }
        create={<AddLayerMenu onAdd={setAdding} />}
        rule={
          <>
            <EffectsBelowRule>
              <EffectsElsewhere count={effects?.length ?? 0} onShow={() => tables?.select('fx')} />
            </EffectsBelowRule>
            {noMatch && (
              <p data-stack-no-match className="shrink-0 border-b px-3 py-1.5 text-xs text-muted-foreground">
                No layers match the filter.
              </p>
            )}
          </>
        }
        noun="layer"
        rowName={(row) => (row.kind === 'LOCAL' ? 'your values' : (row.info.name ?? 'layer'))}
        onOpenRow={focusInGrid}
        pencil={false}
        rowDrag={rowDrag}
        rowClass={(row) =>
          row.kind === 'LOCAL'
            ? 'bg-primary/[0.06]'
            : row.entry.layer.layerId === focusedLayer
              ? 'bg-teal-500/10 shadow-[inset_3px_0_0_var(--color-teal-500)]'
              : undefined
        }
        verbs={(selected) => (
          <StackVerbs
            selected={selected}
            onEditInGrid={focusInGrid}
            onMakeLayer={makeLayer}
            onSettings={(row) => setSettingsFor(row.entry.layer.layerId)}
            onEditRecord={editRecord}
          />
        )}
        emptyNote=""
        footer={
          <span className="tabular-nums">
            {total} layer{total === 1 ? '' : 's'} + your values · top wins
            {spent > 0 ? ` · ${spent} spent` : ''}
            {total > 1 ? (dragRefusal == null ? ' · drag ⋮⋮ to reorder' : ` · ${dragRefusal}`) : ''}
          </span>
        }
      />
      {adding != null && <ApplyLayerPicker projectId={projectId} library={adding} onClose={() => setAdding(null)} />}
      <LookDetailSheet
        open={lookForSheet != null}
        onOpenChange={(next) => !next && setLookOpen(null)}
        projectId={projectId}
        look={lookForSheet}
        onDuplicate={() => setLookOpen(null)}
      />
    </>
  )
}

function layerRowId(layer: ProgrammerLayer): string {
  return `layer:${layer.layerId}`
}

/** One layer's handlers, as `LookStack`'s index-based ones resolve to for that layer. */
function handlersFor(layer: ProgrammerLayer): LayerHandlers {
  return {
    onRemove: () => programmerRemoveLayer(layer.layerId),
    onMove: () => {},
    onSetEnabled: (_, enabled) => programmerPatchLayer(layer.layerId, { enabled }),
    onSetAmount: (_, amount) => programmerPatchLayer(layer.layerId, { amount }),
    onSetBlendMode: (_, blendMode) => programmerPatchLayer(layer.layerId, { blendMode }),
    // An unmasked layer travels as the empty string: an omitted field means "leave alone", and the
    // server reads the empty string as "no mask".
    onSetPropertyMask: (_, propertyMask) => programmerPatchLayer(layer.layerId, { propertyMask: propertyMask ?? '' }),
    onSetStomp: (_, stomp) => programmerPatchLayer(layer.layerId, { stomp }),
    onDetach: () => programmerDetachLayer(layer.layerId),
  }
}

/** The dense row's own targets wording. */
function targetsLabelOf(layer: ProgrammerLayer): string {
  if (layer.targets.length === 0) return 'own targets'
  if (layer.targets.length === 1) return layer.targets[0].key
  return `${layer.targets.length} targets`
}

function sortValue(row: LayerRow, key: string): SortValue {
  switch (key) {
    case 'name':
      return row.info.name ?? null
    case 'position':
      return row.entry.position
    case 'kind':
      return row.kind
    case 'targets':
      return row.targetsLabel
    case 'amount':
      return row.entry.layer.amount
    case 'effects':
      return row.effects.length > 0 ? row.effects.length : null
    case 'note':
      return row.note
    default:
      return null
  }
}

function settingsText(layer: ProgrammerLayer): string {
  const mask = parsePropertyMask(layer.propertyMask)
  const parts = [
    layer.blendMode !== 'OVERRIDE' ? layer.blendMode : null,
    mask.length > 0 ? `[${formatFamilyList(mask)}]` : null,
    layer.stomp ? 'stomp' : null,
  ].filter((p) => p != null)
  return parts.length > 0 ? parts.join(' · ') : '—'
}

function text(value: string, opts: { mono?: boolean; muted?: boolean; title?: string } = {}) {
  return (
    <span
      className={cn('mx-1.5 truncate text-xs', opts.mono && 'font-mono tabular-nums', opts.muted && 'text-muted-foreground')}
      title={opts.title ?? value}
    >
      {value}
    </span>
  )
}

const asLayer = (row: StackSheetRow): LayerRow | null => (row.kind === 'LOCAL' ? null : row)

function stackColumns(band: AppliedBand, onMakeLayer: () => void): { columns: SheetColumn<StackSheetRow, StackColumnKey>[]; minWidth: number } {
  const position: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'position',
    label: '#',
    width: '40px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)
      return layer == null ? text('—', { muted: true, title: 'Your values beat every layer' }) : <PositionBadge position={layer.entry.position} className="mx-1.5" />
    },
  }
  const kind: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'kind',
    label: 'Kind',
    width: band === 0 ? '80px' : '64px',
    value: () => undefined,
    display: (row) => <KindBadge row={row} />,
  }
  const targets: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'targets',
    label: 'Targets',
    width: band === 0 ? 'minmax(140px, 1fr)' : 'minmax(120px, 1fr)',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)
      if (layer == null) return <LocalValueCount />
      return text(layer.targetsLabel, {
        title:
          layer.entry.layer.targets.length === 0
            ? "No targets on the layer, so the look's own rows decide where it lands"
            : `On ${layer.entry.layer.targets.map((t) => t.key).join(', ')}`,
      })
    },
  }
  const amount: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'amount',
    label: 'Amount',
    width: band === 0 ? '72px' : '64px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)
      return layer == null ? null : text(`${Math.round(layer.entry.layer.amount * 100)}%`, { mono: true })
    },
  }
  const blend: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'blend',
    label: 'Blend',
    width: '84px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)?.entry.layer
      return layer == null ? null : text(layer.blendMode === 'OVERRIDE' ? '—' : layer.blendMode, { muted: layer.blendMode === 'OVERRIDE' })
    },
  }
  const mask: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'mask',
    label: 'Mask',
    width: '100px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)?.entry.layer
      if (layer == null) return null
      const families = parsePropertyMask(layer.propertyMask)
      return text(families.length > 0 ? `[${formatFamilyList(families)}]` : '—', { muted: families.length === 0 })
    },
  }
  const stomp: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'stomp',
    label: 'Stomp',
    width: '64px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)?.entry.layer
      if (layer == null) return null
      return layer.stomp ? (
        <span className="mx-1.5">
          <Badge variant="outline" className="border-amber-700 bg-amber-950/60 px-1 py-0 text-[10px] text-amber-300">
            stomp
          </Badge>
        </span>
      ) : (
        text('—', { muted: true })
      )
    },
  }
  const settings: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'settings',
    label: 'Settings',
    width: '110px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)?.entry.layer
      if (layer == null) return null
      const words = settingsText(layer)
      return text(words, { muted: words === '—' })
    },
  }
  const effectsColumn: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'effects',
    label: 'FX',
    width: band === 0 ? 'minmax(100px, 140px)' : '90px',
    value: () => undefined,
    display: (row) => <EffectsCell row={row} />,
  }
  const note: SheetColumn<StackSheetRow, StackColumnKey> = {
    key: 'note',
    label: 'Note',
    width: band === 0 ? 'minmax(140px, 1fr)' : '110px',
    value: () => undefined,
    display: (row) => {
      const layer = asLayer(row)
      if (layer == null) return <MakeLayerButton onMakeLayer={onMakeLayer} />
      return layer.note != null ? (
        <span className="mx-1.5 truncate text-xs text-amber-600 dark:text-amber-400" title={layer.note} data-layer-note>
          {layer.note}
        </span>
      ) : (
        text('—', { muted: true })
      )
    },
  }

  const name = STACK_NAME_WIDTH[band]
  const sum = (cols: SheetColumn<StackSheetRow, StackColumnKey>[]) =>
    name + cols.reduce((n, c) => n + (Number.parseInt(c.width.replace(/^minmax\(/, ''), 10) || 0), 0)
  const set = (cols: SheetColumn<StackSheetRow, StackColumnKey>[]) => ({ columns: cols, minWidth: sum(cols) })

  // Each set's floor fits its band: 0 within 1100, 1 within 860.
  if (band === 0) return set([position, kind, targets, amount, blend, mask, stomp, effectsColumn, note])
  if (band === 1) return set([position, kind, targets, amount, settings, effectsColumn, note])
  return set([targets, amount, note, kind, settings, effectsColumn])
}

/** The name column by band: the row's toggle and two buttons beside the badge, then the badge alone. */
const STACK_NAME_WIDTH: Readonly<Record<AppliedBand, number>> = { 0: 260, 1: 260, 2: 220, 3: 220 }

function PositionBadge({ position, className }: { position: number; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex size-[17px] shrink-0 items-center justify-center rounded bg-muted text-[9.5px] font-bold tabular-nums',
        className,
      )}
      title={`Layer ${position} — the higher wins`}
    >
      {position}
    </span>
  )
}

function KindBadge({ row }: { row: StackSheetRow }) {
  const word = row.kind === 'LOCAL' ? 'Local' : row.kind === 'LOOK' ? 'Look' : 'Palette'
  return (
    <span className="mx-1.5">
      <Badge
        variant="outline"
        className={cn('px-1 py-0 text-[10px]', row.kind === 'LOCAL' && 'border-primary/60 bg-primary/10 text-primary')}
      >
        {word}
      </Badge>
    </span>
  )
}

/** *n values*, subscribed here: the count moves on every programmer write, and only this cell has to. */
function LocalValueCount() {
  const count = useLocalValueCount()
  return text(count === 0 ? 'nothing yet' : `${count} value${count === 1 ? '' : 's'}`, { muted: count === 0 })
}

function MakeLayerButton({ onMakeLayer }: { onMakeLayer: () => void }) {
  const count = useLocalValueCount()
  return (
    <span className="mx-1.5 flex" onClick={(e) => e.stopPropagation()}>
      <Button
        variant="outline"
        size="sm"
        className="h-6 px-1.5 text-[11px]"
        disabled={count === 0}
        onClick={onMakeLayer}
        title={count === 0 ? 'Set some values first, then promote them into a shared look' : 'Save these values as a look and apply it here as a layer'}
      >
        <Layers className="size-3" />
        Make layer
      </Button>
    </span>
  )
}

function EffectsCell({ row }: { row: StackSheetRow }) {
  const { effects } = row
  if (row.kind !== 'LOCAL' && row.effectState === 'spent') {
    return (
      <span className="mx-1.5">
        <SpentBadge />
      </span>
    )
  }
  if (effects.length === 0) return text('—', { muted: true })
  const words =
    row.kind === 'LOCAL'
      ? `${effects.length} Local`
      : `${effects.length} · ${effects[0].effectType}${effects.length > 1 ? ` +${effects.length - 1}` : ''}`
  return text(words, { title: effects.map((e) => `${e.effectType} on ${e.targetKey}`).join(', ') })
}

function SpentBadge() {
  return (
    <Badge
      variant="outline"
      className="border-dashed px-1 py-0 text-[10px] text-muted-foreground"
      title="Its FX has run its count and paints nothing until the layer goes"
      data-spent
    >
      spent
    </Badge>
  )
}

/** *Your values*: the row that beats every layer. A press on the name points the grid at Local. */
function LocalNameCell({ compact, onFocus }: { compact: boolean; onFocus: () => void }) {
  return (
    <>
      <Hand className="size-3.5 shrink-0 text-primary" />
      <span className="min-w-0 flex-1 truncate text-sm font-semibold" onDoubleClick={onFocus} title="Your values — they beat every layer">
        Your values
      </span>
      {!compact && (
        <span className="flex shrink-0" onClick={(e) => e.stopPropagation()}>
          <Button variant="ghost" size="icon" className="size-6" aria-label="Edit your values in the grid" title="Edit in grid" onClick={onFocus}>
            <Grid3x3 className="size-3.5" />
          </Button>
        </span>
      )}
    </>
  )
}

/**
 * The enable toggle, the name, the settings popover and the menu — the stack row's controls. On the
 * narrow bands ([compact]) the toggle and the settings button live in the menu, the popover opening
 * at the row, so the name keeps its room; the stack position rides beside it, its column gone.
 */
function LayerNameCell({
  row,
  compact,
  settingsOpen,
  onSettingsOpenChange,
  onEditRecord,
  onEditInGrid,
}: {
  row: LayerRow
  compact: boolean
  settingsOpen: boolean
  onSettingsOpenChange: (open: boolean) => void
  onEditRecord: () => void
  onEditInGrid: () => void
}) {
  const { layer, position } = row.entry
  const handlers = handlersFor(layer)
  const enabled = layer.enabled !== false
  const isTemplate = layer.source.kind === 'TEMPLATE'
  return (
    <>
      {/* Clicks on the controls are theirs, not the row's selection. */}
      {!compact && (
        <span className="contents" onClick={(e) => e.stopPropagation()}>
          <EnableButton enabled={enabled} onToggle={() => handlers.onSetEnabled(0, !enabled)} />
        </span>
      )}
      {compact && <PositionBadge position={position} />}
      <span className={cn('min-w-0 flex-1', !enabled && 'opacity-55')} onDoubleClick={onEditInGrid}>
        <LookNameBadge
          name={row.info.name}
          missing={row.info.missing}
          isTemplate={isTemplate}
          isEffect={row.info.templateKind === 'effect'}
          className="max-w-full"
        />
      </span>
      {compact && row.effectState === 'spent' && <SpentBadge />}
      <span className="flex shrink-0 items-center" onClick={(e) => e.stopPropagation()}>
        <LayerSettingsPopover
          layer={layer}
          index={position - 1}
          handlers={handlers}
          open={settingsOpen}
          onOpenChange={onSettingsOpenChange}
          anchorOnly={compact}
        />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="size-6" aria-label={`Actions for ${row.info.name ?? 'this layer'}`}>
              <MoreHorizontal className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          {/* No focus back to the trigger: *Settings…* opens a popover in the same gesture. */}
          <DropdownMenuContent align="end" onCloseAutoFocus={(e) => e.preventDefault()}>
            <DropdownMenuItem onClick={onEditInGrid}>
              <Grid3x3 className="size-3.5" />
              Edit in grid
            </DropdownMenuItem>
            <DropdownMenuItem onClick={onEditRecord}>
              <Pencil className="size-3.5" />
              {isTemplate ? 'Edit palette…' : 'Edit look…'}
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => onSettingsOpenChange(true)}>
              <SlidersHorizontal className="size-3.5" />
              Settings…
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => handlers.onSetEnabled(0, !enabled)}>
              {enabled ? <Ban className="size-3.5" /> : <Eye className="size-3.5" />}
              {enabled ? 'Disable' : 'Enable'}
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => handlers.onDetach?.(0)}>
              <Unlink className="size-3.5" />
              Detach
            </DropdownMenuItem>
            <DropdownMenuItem onClick={() => handPickUp(layer.source.kind, layer.source.id)}>
              <Hand className="size-3.5" />
              Pick up {layer.source.name}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem variant="destructive" onClick={() => handlers.onRemove(0)}>
              <Trash2 className="size-3.5" />
              Remove
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </span>
    </>
  )
}

/** The bar's *Make layer*, refused with nothing to promote as the row's button is. */
function MakeLayerVerb({ onMakeLayer }: { onMakeLayer: () => void }) {
  const count = useLocalValueCount()
  return (
    <LibraryVerb
      icon={Layers}
      label="Make layer"
      title="Save your values as a look and apply it here as a layer"
      disabledReason={count === 0 ? 'Set some values first, then promote them into a shared look' : null}
      onClick={onMakeLayer}
    />
  )
}

/** *+ Look or palette*: the two pickers, as one create verb. */
function AddLayerMenu({ onAdd }: { onAdd: (library: 'looks' | 'palettes') => void }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="shrink-0">
          <Plus className="size-3.5" />
          <span className="hidden @[560px]:inline">Look or palette</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => onAdd('looks')}>
          <Layers className="size-3.5" />
          Look…
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => onAdd('palettes')}>
          <Palette className="size-3.5" />
          Palette…
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * The bar's verbs over the selected rows: *Edit in grid* over one row, *Make layer* with *Your
 * values* selected, and the row's verbs over the layers — a one-layer verb refused over several.
 */
function StackVerbs({
  selected,
  onEditInGrid,
  onMakeLayer,
  onSettings,
  onEditRecord,
}: {
  selected: readonly StackSheetRow[]
  onEditInGrid: (row: StackSheetRow) => void
  onMakeLayer: () => void
  onSettings: (row: LayerRow) => void
  onEditRecord: (row: LayerRow) => void
}) {
  const layers = selected.filter((row): row is LayerRow => row.kind !== 'LOCAL')
  const localSelected = layers.length < selected.length
  const first = selected[0]
  const firstLayer = layers[0]
  const count = layers.length
  const allOn = layers.every((row) => row.entry.layer.enabled !== false)
  const noun = count === 1 ? 'layer' : 'layers'
  const oneLayer = (verb: string) => oneRecordReason(verb, 'layer', count)
  return (
    <>
      <LibraryVerb
        icon={Grid3x3}
        label="Edit in grid"
        title={`Point the Values grid at ${first == null || first.kind === 'LOCAL' ? 'your values' : (first.info.name ?? 'this layer')}`}
        disabledReason={oneRecordReason('Edit in grid', 'row', selected.length)}
        onClick={() => onEditInGrid(first)}
      />
      {localSelected && <MakeLayerVerb onMakeLayer={onMakeLayer} />}
      {count > 0 && (
        <>
          <LibraryVerb
            icon={Pencil}
            label={firstLayer.kind === 'TEMPLATE' ? 'Edit palette…' : 'Edit look…'}
            title={`Open ${firstLayer.info.name ?? 'this layer'}`}
            disabledReason={oneLayer('Edit')}
            onClick={() => onEditRecord(firstLayer)}
          />
          <LibraryVerb
            icon={SlidersHorizontal}
            label="Settings…"
            title={`Amount, blend, mask and stomp for ${firstLayer.info.name ?? 'this layer'}`}
            disabledReason={oneLayer('Settings')}
            onClick={() => onSettings(firstLayer)}
          />
          <LibraryVerb
            icon={allOn ? Ban : Eye}
            label={allOn ? 'Disable' : 'Enable'}
            title={`${allOn ? 'Disable' : 'Enable'} ${count} ${noun}`}
            onClick={() => layers.forEach((row) => programmerPatchLayer(row.entry.layer.layerId, { enabled: !allOn }))}
          />
          <LibraryVerb
            icon={Unlink}
            label="Detach"
            title={`Turn ${count === 1 ? 'this layer' : `${count} layers`} into your own values and FX, with nothing on stage moving`}
            onClick={() => layers.forEach((row) => programmerDetachLayer(row.entry.layer.layerId))}
          />
          <LibraryVerb
            icon={Hand}
            label="Pick up"
            title={`Pick up ${firstLayer.info.name ?? 'this layer'} — place it on another screen`}
            disabledReason={oneLayer('Pick up')}
            onClick={() => handPickUp(firstLayer.entry.layer.source.kind, firstLayer.entry.layer.source.id)}
          />
          <LibraryVerb
            icon={Trash2}
            label="Remove"
            destructive
            title={`Take ${count} ${noun} off the programmer`}
            onClick={() => layers.forEach((row) => programmerRemoveLayer(row.entry.layer.layerId))}
          />
        </>
      )}
    </>
  )
}

/** The rule's second half on the Stack table: the effects under the stack, a press away on the FX tab. */
function EffectsElsewhere({ count, onShow }: { count: number; onShow: () => void }) {
  return (
    <span className="min-w-0 truncate text-[11px] text-amber-200/80">
      ·{' '}
      {count === 0 ? (
        'no FX running'
      ) : (
        <>
          {count} FX {count === 1 ? 'runs' : 'run'} under this stack,{' '}
          <button type="button" className="underline-offset-2 hover:underline" onClick={onShow}>
            on the FX tab
          </button>
        </>
      )}
    </span>
  )
}
