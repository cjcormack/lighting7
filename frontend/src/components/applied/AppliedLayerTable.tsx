import { useCallback, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { Eye, Ban, Hand, MoreHorizontal, Pencil, SlidersHorizontal, Trash2, Unlink } from 'lucide-react'
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
import { EnableButton, LayerSettingsPopover, describeStackSource, type LayerHandlers, type StackSourceInfo } from '@/components/looks/LookStack'
import { LookNameBadge } from '@/components/looks/LookNameBadge'
import { LookDetailSheet } from '@/components/looks/LookDetailSheet'
import { TemplateValue } from '@/components/templates/TemplateValue'
import { useReplacementNotes } from '@/components/programmer/useReplacementNotes'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import {
  programmerDetachLayer,
  programmerPatchLayer,
  programmerRemoveLayer,
  useProgrammerLayersQuery,
  type ProgrammerLayer,
} from '@/store/programmer'
import { handPickUp } from '@/store/hand'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS, familySlug, formatFamilyList, parsePropertyMask, type AttributeFamily } from '@/lib/attributeFamily'
import { sourceKey } from '@/lib/replacementNotes'
import {
  layerEffectState,
  layersOfKind,
  matchesFilter,
  sortRows,
  type AppliedLayer,
  type AppliedView,
  type SortValue,
} from '@/lib/appliedTables'
import type { TemplateSummary } from '@/api/templatesApi'
import { AppliedTableShell, type AppliedBand } from './AppliedTableShell'

type LayerColumnKey =
  | 'position'
  | 'families'
  | 'family'
  | 'holds'
  | 'targets'
  | 'amount'
  | 'blend'
  | 'mask'
  | 'stomp'
  | 'settings'
  | 'effects'
  | 'effect'
  | 'note'

interface LayerSheetRow extends SheetRow {
  entry: AppliedLayer
  info: StackSourceInfo
  template: TemplateSummary | undefined
  note: string | null
  effectState: 'running' | 'spent' | null
  targetsLabel: string
}

const SORT_KEYS: ReadonlySet<string> = new Set(['name', 'position', 'families', 'family', 'targets', 'amount', 'effect', 'note'])

/**
 * **Applied Looks** and **Applied Palettes** (venue-visit plan S14): the programmer's layer stack,
 * split by what each layer applies, top wins and numbered as the rail numbers it
 * (`ProgrammerLookStack` over `LookStack`'s dense rows). Applied Palettes adds what each palette
 * holds and marks a layer whose effect is spent — every instance it spawned has run its count —
 * read off the instances' own `spent`.
 *
 * **A row's actions are the rail row's and call its mutations**: the enable toggle and the settings
 * popover (`LayerSettingsPopover` — amount, blend, mask, stomp, Detach, Remove, Pick up), plus
 * *Edit Look…* / *Edit palette…* in the menu (Chris's call, 2026-10-17). ⏎ and the name's double
 * click open the settings popover, as the rail row's own button does. Reorder stays in the rail: a
 * table sorted by name has no drag order to show.
 */
export function AppliedLayerTable({
  projectId,
  kind,
  view,
  onViewChange,
}: {
  projectId: number
  kind: 'LOOK' | 'TEMPLATE'
  view: AppliedView
  onViewChange: (next: AppliedView) => void
}) {
  const navigate = useNavigate()
  const { data: layerList } = useProgrammerLayersQuery()
  const { data: lookList } = useLookListQuery({ projectId }, { skip: !projectId })
  const { data: templateList } = useTemplateListQuery({ projectId }, { skip: !projectId })
  const { data: effects } = useActiveEffectsQuery()
  const notes = useReplacementNotes()

  const looksById = useMemo(() => new Map((lookList ?? []).map((l) => [l.id, l])), [lookList])
  const templatesById = useMemo(() => new Map((templateList ?? []).map((t) => [t.id, t])), [templateList])
  const loaded = lookList != null && templateList != null

  const all = useMemo<LayerSheetRow[]>(
    () =>
      layersOfKind(layerList ?? [], kind).map((entry) => {
        const { layer } = entry
        const info = describeStackSource(layer.source, looksById, templatesById, loaded)
        return {
          id: `layer:${layer.layerId}`,
          entry,
          info,
          template: layer.source.kind === 'TEMPLATE' ? templatesById.get(layer.source.id) : undefined,
          note: notes.get(sourceKey(layer.source)) ?? null,
          effectState: layerEffectState(layer.layerId, effects ?? []),
          targetsLabel: targetsLabelOf(layer),
        }
      }),
    [effects, kind, layerList, loaded, looksById, notes, templatesById],
  )

  const familyChip = kind === 'TEMPLATE' ? ATTRIBUTE_FAMILIES.find((f) => familySlug(f) === view.chip) ?? null : null
  const rows = useMemo(() => {
    const shown = all.filter(
      (row) =>
        (familyChip == null || row.info.families.includes(familyChip)) &&
        matchesFilter(view.q, [row.info.name, row.targetsLabel, row.note, ...row.entry.layer.targets.map((t) => t.key)]),
    )
    return sortRows(shown, view.sort, view.dir, sortValue)
  }, [all, familyChip, view.dir, view.q, view.sort])

  const [settingsFor, setSettingsFor] = useState<number | null>(null)
  const [lookOpen, setLookOpen] = useState<number | null>(null)
  const openSettings = useCallback((row: LayerSheetRow) => setSettingsFor(row.entry.layer.layerId), [])
  const editRecord = useCallback(
    (row: LayerSheetRow) => {
      const { source } = row.entry.layer
      if (source.kind === 'LOOK') setLookOpen(source.id)
      else navigate(`/projects/${projectId}/palettes?edit=${source.id}`)
    },
    [navigate, projectId],
  )

  const noun = kind === 'LOOK' ? 'look' : 'palette'
  const columnsFor = useCallback((band: AppliedBand) => layerColumns(kind, band), [kind])
  const firstColumnFor = useCallback(
    (band: AppliedBand) => ({
      label: kind === 'LOOK' ? 'Look' : 'Palette',
      width: `${LAYER_NAME_WIDTH[band]}px`,
      render: (row: LayerSheetRow) => (
        <LayerNameCell
          row={row}
          compact={band >= 2}
          settingsOpen={settingsFor === row.entry.layer.layerId}
          onSettingsOpenChange={(open) => setSettingsFor(open ? row.entry.layer.layerId : null)}
          onEditRecord={() => editRecord(row)}
        />
      ),
    }),
    [editRecord, kind, settingsFor],
  )

  const familyCounts = useMemo(() => {
    const counts = new Map<AttributeFamily, number>()
    for (const row of all) for (const f of row.info.families) counts.set(f, (counts.get(f) ?? 0) + 1)
    return counts
  }, [all])
  const total = layerList?.length ?? 0
  const spent = all.filter((row) => row.effectState === 'spent').length
  const lookForSheet = lookOpen != null ? (lookList ?? []).find((l) => l.id === lookOpen) ?? null : null

  return (
    <>
      <AppliedTableShell<LayerSheetRow, LayerColumnKey>
        table={kind === 'LOOK' ? 'looks' : 'palettes'}
        rows={rows}
        total={all.length}
        columnsFor={columnsFor}
        firstColumnFor={firstColumnFor}
        sortKeys={SORT_KEYS}
        firstSortKey="name"
        view={view}
        onViewChange={onViewChange}
        filterLabel={`Filter by ${noun}, target or note`}
        chips={
          kind === 'TEMPLATE' ? (
            <PartitionChips<AttributeFamily>
              label="Family"
              options={ATTRIBUTE_FAMILIES.map((f) => ({ value: f, label: FAMILY_LABELS[f].singular, count: familyCounts.get(f) ?? 0 }))}
              value={familyChip ?? 'ALL'}
              allCount={all.length}
              onChange={(next) => onViewChange({ ...view, chip: next === 'ALL' ? null : familySlug(next) })}
            />
          ) : undefined
        }
        noun={noun}
        rowName={(row) => row.info.name ?? noun}
        onOpenRow={openSettings}
        pencil={false}
        verbs={(selected) => <LayerVerbs selected={selected} noun={noun} onSettings={openSettings} />}
        emptyNote={
          kind === 'LOOK'
            ? 'No looks applied. Press a look on a busk pad, or add one from the programmer rail.'
            : 'No palettes applied. Press a palette chip or a busk pad onto a selection.'
        }
        footer={
          <span className="tabular-nums">
            {all.length} {noun}
            {all.length === 1 ? '' : 's'} applied · of {total} layer{total === 1 ? '' : 's'}
            {spent > 0 ? ` · ${spent} spent` : ''}
          </span>
        }
      />
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

/** One layer's handlers, as the rail's index-based ones resolve to for that layer. */
function handlersFor(layer: ProgrammerLayer): LayerHandlers {
  return {
    onRemove: () => programmerRemoveLayer(layer.layerId),
    onMove: () => {},
    onSetEnabled: (_, enabled) => programmerPatchLayer(layer.layerId, { enabled }),
    onSetAmount: (_, amount) => programmerPatchLayer(layer.layerId, { amount }),
    onSetBlendMode: (_, blendMode) => programmerPatchLayer(layer.layerId, { blendMode }),
    // An unmasked layer travels as the empty string, as the rail sends it.
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

function sortValue(row: LayerSheetRow, key: string): SortValue {
  switch (key) {
    case 'name':
      return row.info.name ?? null
    case 'position':
      return row.entry.position
    case 'families':
    case 'family':
      return row.info.families.length > 0 ? formatFamilyList(row.info.families) : null
    case 'targets':
      return row.targetsLabel
    case 'amount':
      return row.entry.layer.amount
    case 'effect':
      return row.effectState
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

function layerColumns(kind: 'LOOK' | 'TEMPLATE', band: AppliedBand): { columns: SheetColumn<LayerSheetRow, LayerColumnKey>[]; minWidth: number } {
  const position: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'position',
    label: '#',
    width: '48px',
    value: () => undefined,
    display: (row) => <PositionBadge position={row.entry.position} className="mx-1.5" />,
  }
  const families: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: kind === 'LOOK' ? 'families' : 'family',
    label: kind === 'LOOK' ? 'Families' : 'Family',
    width: kind === 'LOOK' ? (band === 0 ? '120px' : '110px') : '84px',
    value: () => undefined,
    display: (row) => text(row.info.families.length > 0 ? formatFamilyList(row.info.families, ' · ') : '—', { muted: true }),
  }
  const holds: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'holds',
    label: 'Holds',
    width: band === 0 ? 'minmax(160px, 230px)' : 'minmax(140px, 230px)',
    value: () => undefined,
    display: (row) => (row.template != null ? <TemplateValue template={row.template} /> : text('—', { muted: true })),
  }
  const targets: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'targets',
    label: 'Targets',
    width: kind === 'LOOK' ? 'minmax(130px, 1fr)' : band === 0 ? 'minmax(120px, 1fr)' : 'minmax(110px, 1fr)',
    value: () => undefined,
    display: (row) =>
      text(row.targetsLabel, {
        title:
          row.entry.layer.targets.length === 0
            ? "No targets on the layer, so the look's own rows decide where it lands"
            : `On ${row.entry.layer.targets.map((t) => t.key).join(', ')}`,
      }),
  }
  const amount: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'amount',
    label: 'Amount',
    width: '72px',
    value: () => undefined,
    display: (row) => text(`${Math.round(row.entry.layer.amount * 100)}%`, { mono: true }),
  }
  const blend: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'blend',
    label: 'Blend',
    width: '84px',
    value: () => undefined,
    display: (row) => text(row.entry.layer.blendMode === 'OVERRIDE' ? '—' : row.entry.layer.blendMode, { muted: row.entry.layer.blendMode === 'OVERRIDE' }),
  }
  const mask: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'mask',
    label: 'Mask',
    width: '100px',
    value: () => undefined,
    display: (row) => {
      const families = parsePropertyMask(row.entry.layer.propertyMask)
      return text(families.length > 0 ? `[${formatFamilyList(families)}]` : '—', { muted: families.length === 0 })
    },
  }
  const stomp: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'stomp',
    label: 'Stomp',
    width: '64px',
    value: () => undefined,
    display: (row) =>
      row.entry.layer.stomp ? (
        <span className="mx-1.5">
          <Badge variant="outline" className="border-amber-700 bg-amber-950/60 px-1 py-0 text-[10px] text-amber-300">
            stomp
          </Badge>
        </span>
      ) : (
        text('—', { muted: true })
      ),
  }
  const settings: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'settings',
    label: 'Settings',
    width: kind === 'LOOK' ? '130px' : '110px',
    value: () => undefined,
    display: (row) => text(settingsText(row.entry.layer), { muted: settingsText(row.entry.layer) === '—' }),
  }
  const effectsCount: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'effects',
    label: 'Effects',
    width: '64px',
    value: () => undefined,
    display: (row) => text(row.info.effectCount != null && row.info.effectCount > 0 ? String(row.info.effectCount) : '—', { mono: true, muted: !row.info.effectCount }),
  }
  const effect: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'effect',
    label: 'Effect',
    width: '80px',
    value: () => undefined,
    display: (row) =>
      row.effectState === 'spent' ? (
        <span className="mx-1.5">
          <SpentBadge />
        </span>
      ) : (
        text(row.effectState === 'running' ? 'Running' : '—', { muted: true })
      ),
  }
  const note: SheetColumn<LayerSheetRow, LayerColumnKey> = {
    key: 'note',
    label: 'Note',
    width: kind === 'LOOK' && band === 0 ? 'minmax(140px, 1fr)' : 'minmax(120px, 1fr)',
    value: () => undefined,
    display: (row) =>
      row.note != null ? (
        <span className="mx-1.5 truncate text-xs text-amber-600 dark:text-amber-400" title={row.note} data-layer-note>
          {row.note}
        </span>
      ) : (
        text('—', { muted: true })
      ),
  }

  const name = LAYER_NAME_WIDTH[band]
  const sum = (cols: SheetColumn<LayerSheetRow, LayerColumnKey>[]) =>
    name + cols.reduce((n, c) => n + (Number.parseInt(c.width.replace(/^minmax\(/, ''), 10) || 0), 0)
  const set = (cols: SheetColumn<LayerSheetRow, LayerColumnKey>[]) => ({ columns: cols, minWidth: sum(cols) })

  if (kind === 'LOOK') {
    if (band === 0) return set([position, families, targets, amount, blend, mask, stomp, effectsCount, note])
    if (band === 1) return set([position, families, targets, amount, settings, effectsCount, note])
    return set([targets, amount, note, families, settings, effectsCount])
  }
  if (band === 0) return set([position, families, holds, targets, amount, blend, stomp, effect, note])
  // The Note scrolls in at 1180: the eight tracks before it fill the iPad's 940.
  if (band === 1) return set([position, families, holds, targets, amount, settings, effect, note])
  return set([holds, targets, effect, families, amount, settings, note])
}

/** The name column by band: the row's toggle and two buttons beside the badge, then the badge alone. */
const LAYER_NAME_WIDTH: Readonly<Record<AppliedBand, number>> = { 0: 260, 1: 260, 2: 220, 3: 220 }

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

function SpentBadge() {
  return (
    <Badge
      variant="outline"
      className="border-dashed px-1 py-0 text-[10px] text-muted-foreground"
      title="Its effect has run its count and paints nothing until the layer goes"
      data-spent
    >
      spent
    </Badge>
  )
}

/**
 * The enable toggle, the name, the settings popover and the menu — the rail row's controls. On the
 * narrow bands ([compact]) the toggle and the settings button live in the menu, the popover opening
 * at the row, so the name keeps its room; the stack position rides beside it, its column gone.
 */
function LayerNameCell({
  row,
  compact,
  settingsOpen,
  onSettingsOpenChange,
  onEditRecord,
}: {
  row: LayerSheetRow
  compact: boolean
  settingsOpen: boolean
  onSettingsOpenChange: (open: boolean) => void
  onEditRecord: () => void
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
      <span
        className={cn('min-w-0 flex-1', !enabled && 'opacity-55')}
        onDoubleClick={() => onSettingsOpenChange(true)}
      >
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
            <DropdownMenuItem onClick={onEditRecord}>
              <Pencil className="size-3.5" />
              {isTemplate ? 'Edit palette…' : 'Edit look…'}
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

/** The bar's verbs over the selected layers: the row's, one-layer verbs refused over several. */
function LayerVerbs({
  selected,
  noun,
  onSettings,
}: {
  selected: readonly LayerSheetRow[]
  noun: string
  onSettings: (row: LayerSheetRow) => void
}) {
  const count = selected.length
  const first = selected[0]
  const allOn = selected.every((row) => row.entry.layer.enabled !== false)
  const plural = count === 1 ? noun : `${noun}s`
  return (
    <>
      <LibraryVerb
        icon={SlidersHorizontal}
        label="Settings…"
        title={`Amount, blend, mask and stomp for ${first?.info.name ?? `this ${noun}`}`}
        disabledReason={oneRecordReason('Settings', noun, count)}
        onClick={() => onSettings(first)}
      />
      <LibraryVerb
        icon={allOn ? Ban : Eye}
        label={allOn ? 'Disable' : 'Enable'}
        title={`${allOn ? 'Disable' : 'Enable'} ${count} ${plural}`}
        onClick={() => selected.forEach((row) => programmerPatchLayer(row.entry.layer.layerId, { enabled: !allOn }))}
      />
      <LibraryVerb
        icon={Unlink}
        label="Detach"
        title={`Turn ${count === 1 ? 'this layer' : `${count} layers`} into your own values and effects, with nothing on stage moving`}
        onClick={() => selected.forEach((row) => programmerDetachLayer(row.entry.layer.layerId))}
      />
      <LibraryVerb
        icon={Hand}
        label="Pick up"
        title={`Pick up ${first?.info.name ?? `this ${noun}`} — place it on another screen`}
        disabledReason={oneRecordReason('Pick up', noun, count)}
        onClick={() => handPickUp(first.entry.layer.source.kind, first.entry.layer.source.id)}
      />
      <LibraryVerb
        icon={Trash2}
        label="Remove"
        destructive
        title={`Take ${count} ${plural} off the programmer`}
        onClick={() => selected.forEach((row) => programmerRemoveLayer(row.entry.layer.layerId))}
      />
    </>
  )
}
