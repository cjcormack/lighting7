import { useCallback, useMemo, useRef, useState } from 'react'
import { Layers, LayoutGrid, ListOrdered, MoreHorizontal, Pause, Pencil, Play, Plus, Square } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { LibraryVerb, oneRecordReason } from '@/components/sheet/LibraryVerb'
import { PartitionChips } from '@/components/sheet/LibraryRow'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { useActiveEffectsQuery, useEffectLibraryQuery, type ActiveEffect } from '@/store/fixtureFx'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { useTemplateListQuery } from '@/store/templates'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import type { TemplateSummary } from '@/api/templatesApi'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { isHeldByBlind } from '@/lib/heldBack'
import { effectTargetLabel, selectionItems } from '@/lib/effectTargets'
import { isOnSeconds } from '@/lib/effectTiming'
import { isPaletteSteps, isSlotSteps, parseStepList, slotLabel } from '@/lib/stepEffects'
import { getBeatDivisionLabel, secondsLabel } from '@/components/fx/fxConstants'
import { parseTemplateRefUuid } from '@/components/fx/colourUtils'
import { FxEditorPopover } from '@/components/fx/FxEditorPopover'
import { HeldBlindMark } from '@/components/fx/HeldBlindMark'
import { useStopEffect, useToggleFxPause } from '@/components/fx/useEffectControls'
import { NewTemplateFromEffectSheet } from '@/components/programmer/NewTemplateFromEffectSheet'
import { homeOf, layerHomesOf, saveAsTemplateOffer } from '@/components/programmer/ProgrammerFxList'
import {
  FX_HOMES,
  FX_HOME_LABELS,
  FX_STATE_LABELS,
  fxHomeOf,
  fxStateOf,
  matchesFilter,
  parseFxHome,
  sortRows,
  type AppliedView,
  type FxHome,
  type FxState,
  type SortValue,
} from '@/lib/appliedTables'
import { AppliedTableShell, type AppliedBand } from './AppliedTableShell'

type FxColumnKey = 'property' | 'target' | 'timing' | 'speed' | 'timingSpeed' | 'size' | 'direction' | 'runs' | 'dynamics' | 'home' | 'state'

interface FxSheetRow extends SheetRow {
  effect: ActiveEffect
  home: ReturnType<typeof homeOf>
  homeKind: FxHome
  state: FxState
  heldByBlind: boolean
  target: string
  /** A step effect's palettes or slots, in order (effects plan §3.6), or null. */
  steps: string | null
}

const SORT_KEYS: ReadonlySet<string> = new Set(['name', 'property', 'target', 'speed', 'size', 'runs', 'home', 'state'])

/**
 * **Applied FX** (venue-visit plan S14): every running effect, one row per instance, exactly as the
 * rail's FX band lists them (`ProgrammerFxList`) — an effect over a selection one row with its reach
 * from `lib/effectTargets.ts`, a split edit one row per head copy, a step effect naming its palettes
 * or slots.
 *
 * **It reads what the desk reports and computes nothing**: timing is *Seconds* or the master the
 * instance names (`seconds`, `speedMasterUuid`, `rateSpeedMasterUuid`, read against the live bank),
 * size, direction and runs are the instance's fields, *spent* is its `spent`, and *held · Blind* is
 * `isHeldByBlind`, the rail's read.
 *
 * **A row's actions are the rail's** — *Edit…* (`FxEditorPopover`, so the split edit and pause come
 * with it), *Save as palette…* with the rail's gate, *Stop* — plus *Pause* / *Resume*, the editor's
 * own, lifted onto the row (`useToggleFxPause`). ⏎ and the name's double click open the editor.
 */
export function AppliedFxTable({
  projectId,
  view,
  onViewChange,
}: {
  projectId: number
  view: AppliedView
  onViewChange: (next: AppliedView) => void
}) {
  const { data: effects } = useActiveEffectsQuery()
  const { data: layers } = useProgrammerLayersQuery()
  const { data: library } = useEffectLibraryQuery()
  const { data: templates } = useTemplateListQuery({ projectId }, { skip: !Number.isFinite(projectId) })
  const blind = useProgrammerBlind()
  const togglePause = useToggleFxPause()
  const stop = useStopEffect()
  const controls = useMemo(() => ({ togglePause, stop }), [stop, togglePause])

  const [editingId, setEditingId] = useState<number | null>(null)
  const [saving, setSaving] = useState<ActiveEffect | null>(null)
  const editAnchor = useRef<HTMLElement | null>(null)

  const templatesByUuid = useMemo(() => new Map((templates ?? []).map((t) => [t.uuid, t])), [templates])
  const all = useMemo<FxSheetRow[]>(() => {
    const layerHomes = layerHomesOf(layers ?? [])
    return (effects ?? []).map((effect) => {
      const heldByBlind = isHeldByBlind(effect, blind)
      return {
        id: `fx:${effect.id}`,
        effect,
        home: homeOf(effect, layerHomes),
        homeKind: fxHomeOf(effect),
        state: fxStateOf(effect, heldByBlind),
        heldByBlind,
        target: effectTargetLabel(effect),
        steps: stepNames(effect, templatesByUuid),
      }
    })
  }, [blind, effects, layers, templatesByUuid])

  const chip = parseFxHome(view.chip)
  const rows = useMemo(() => {
    const shown = all.filter(
      (row) =>
        (chip == null || row.homeKind === chip) &&
        matchesFilter(view.q, [row.effect.effectType, row.effect.propertyName, row.target, row.home.label, row.steps]),
    )
    return sortRows(shown, view.sort, view.dir, sortValue)
  }, [all, chip, view.dir, view.q, view.sort])

  const editing = editingId != null ? (effects ?? []).find((e) => e.id === editingId) ?? null : null
  const openEditor = useCallback((row: FxSheetRow) => {
    editAnchor.current = document.querySelector<HTMLElement>(`[data-row-id="${row.id}"]`)
    setEditingId(row.effect.id)
  }, [])

  const columnsFor = useCallback((band: AppliedBand) => fxColumns(band), [])
  const firstColumnFor = useCallback(
    (band: AppliedBand) => ({
      label: 'Effect',
      width: band === 0 ? '210px' : '190px',
      openLabel: (row: FxSheetRow) => `Edit ${row.effect.effectType}`,
      render: (row: FxSheetRow) => (
        <FxNameCell
          row={row}
          showProperty={band >= 2}
          library={library}
          templates={templates}
          onOpen={() => openEditor(row)}
          onSave={() => setSaving(row.effect)}
          onTogglePause={() => controls.togglePause(row.effect)}
          onStop={() => controls.stop(row.effect)}
        />
      ),
    }),
    [controls, library, openEditor, templates],
  )

  const counts = useMemo(() => {
    const byHome = new Map<FxHome, number>()
    for (const row of all) byHome.set(row.homeKind, (byHome.get(row.homeKind) ?? 0) + 1)
    return byHome
  }, [all])
  const held = all.filter((row) => row.heldByBlind).length
  const spent = all.filter((row) => row.state === 'spent').length

  return (
    <>
      <AppliedTableShell<FxSheetRow, FxColumnKey>
        table="fx"
        rows={rows}
        total={all.length}
        columnsFor={columnsFor}
        firstColumnFor={firstColumnFor}
        sortKeys={SORT_KEYS}
        firstSortKey="name"
        view={view}
        onViewChange={onViewChange}
        filterLabel="Filter by effect, property, target or home"
        chips={
          <PartitionChips<FxHome>
            label="Where it lives"
            options={FX_HOMES.map((home) => ({ value: home, label: FX_HOME_LABELS[home], count: counts.get(home) ?? 0 }))}
            value={chip ?? 'ALL'}
            allCount={all.length}
            onChange={(next) => onViewChange({ ...view, chip: next === 'ALL' ? null : next })}
            fold={400}
          />
        }
        noun="effect"
        rowName={(row) => row.effect.effectType}
        onOpenRow={openEditor}
        verbs={(selected) => (
          <FxVerbs
            selected={selected}
            library={library}
            templates={templates}
            onEdit={openEditor}
            onSave={(row) => setSaving(row.effect)}
            controls={controls}
          />
        )}
        emptyNote="Nothing running. Effects arrive from a busking pad, a Look, or the cue on stage."
        footer={
          <span className="tabular-nums">
            {all.length} effect{all.length === 1 ? '' : 's'} running
            {held > 0 ? ` · ${held} held by Blind` : ''}
            {spent > 0 ? ` · ${spent} spent` : ''}
          </span>
        }
      />
      {editing != null && (
        <FxEditorPopover
          effect={editing}
          anchorRef={editAnchor}
          onClose={() => setEditingId(null)}
          onFollow={setEditingId}
          onStop={(effect) => {
            controls.stop(effect)
            setEditingId(null)
          }}
        />
      )}
      <NewTemplateFromEffectSheet
        open={saving != null}
        onOpenChange={(next) => !next && setSaving(null)}
        effect={saving}
      />
    </>
  )
}

function sortValue(row: FxSheetRow, key: string): SortValue {
  switch (key) {
    case 'name':
      return row.effect.effectType
    case 'property':
      return row.effect.propertyName
    case 'target':
      return row.target
    case 'speed':
      return row.effect.beatDivision
    case 'size':
      return row.effect.size ?? 1
    case 'runs':
      // Forever sorts after every count.
      return row.effect.nShot ?? Number.MAX_SAFE_INTEGER
    case 'home':
      return FX_HOMES.indexOf(row.homeKind) * 1000 + (row.effect.programmerLayerId ?? 0)
    case 'state':
      return FX_STATE_ORDER.indexOf(row.state)
    default:
      return null
  }
}

const FX_STATE_ORDER: readonly FxState[] = ['running', 'held', 'paused', 'leaving', 'spent']

/** A step effect's steps as an operator names them: its palettes by name, its slots as words. */
function stepNames(effect: ActiveEffect, templatesByUuid: ReadonlyMap<string, TemplateSummary>): string | null {
  if (isPaletteSteps(effect.effectType)) {
    const names = parseStepList(effect.parameters.palettes).map((ref) => {
      const uuid = parseTemplateRefUuid(ref)
      return (uuid != null ? templatesByUuid.get(uuid)?.name : undefined) ?? 'a palette'
    })
    return names.length > 0 ? names.join(', ') : null
  }
  if (isSlotSteps(effect.effectType)) {
    const slots = parseStepList(effect.parameters.slots).map(slotLabel)
    return slots.length > 0 ? slots.join(', ') : null
  }
  return null
}

function speedLabel(effect: ActiveEffect): string {
  return effect.timingSource === 'WALL_CLOCK' || effect.seconds === true
    ? secondsLabel(effect.beatDivision)
    : getBeatDivisionLabel(effect.beatDivision)
}

function sizeLabel(effect: ActiveEffect): string {
  return `${Math.round((effect.size ?? 1) * 100)}%`
}

const DIRECTION_GLYPH = { FORWARD: '→', BACKWARD: '←', BOUNCE: '↔' } as const
const DIRECTION_WORD = { FORWARD: 'Forward', BACKWARD: 'Backward', BOUNCE: 'Bounce' } as const

function runsLabel(effect: ActiveEffect): string {
  if (effect.nShot == null) return '∞'
  return effect.spent === true ? `${effect.nShot}× · spent` : `${effect.nShot}×`
}

function cellText(text: string, title?: string, mono = false) {
  return (
    <span className={cn('mx-1.5 truncate text-xs', mono && 'font-mono tabular-nums')} title={title ?? text}>
      {text}
    </span>
  )
}

function fxColumns(band: AppliedBand): { columns: SheetColumn<FxSheetRow, FxColumnKey>[]; minWidth: number } {
  const property: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'property',
    label: 'Property',
    width: band === 0 ? '96px' : '84px',
    value: () => undefined,
    display: (row) => cellText(row.effect.propertyName, undefined, true),
  }
  const target: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'target',
    label: 'Target',
    width: band === 0 ? 'minmax(150px, 1fr)' : 'minmax(140px, 1fr)',
    value: () => undefined,
    display: (row) => <TargetCell row={row} />,
  }
  const timing: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'timing',
    label: 'Timing',
    width: '104px',
    value: () => undefined,
    display: (row) => <TimingCell effect={row.effect} />,
  }
  const speed: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'speed',
    label: 'Speed',
    width: '80px',
    value: () => undefined,
    display: (row) => cellText(speedLabel(row.effect), undefined, true),
  }
  const timingSpeed: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'timingSpeed',
    label: 'Timing',
    width: '140px',
    value: () => undefined,
    display: (row) => <TimingCell effect={row.effect} speed />,
  }
  const size: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'size',
    label: 'Size',
    width: '64px',
    value: () => undefined,
    display: (row) => cellText(sizeLabel(row.effect), undefined, true),
  }
  const direction: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'direction',
    label: 'Dir',
    width: '52px',
    value: () => undefined,
    display: (row) => {
      const d = row.effect.direction ?? 'FORWARD'
      return cellText(DIRECTION_GLYPH[d], DIRECTION_WORD[d])
    },
  }
  const runs: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'runs',
    label: 'Runs',
    width: '96px',
    value: () => undefined,
    display: (row) => cellText(runsLabel(row.effect), row.effect.nShot == null ? 'Runs forever' : undefined, true),
  }
  const dynamics: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'dynamics',
    label: 'Dynamics',
    width: '120px',
    value: () => undefined,
    display: (row) => {
      const d = row.effect.direction ?? 'FORWARD'
      const text = [sizeLabel(row.effect), DIRECTION_GLYPH[d], runsLabel(row.effect)].join(' · ')
      return cellText(text, `Size ${sizeLabel(row.effect)} · ${DIRECTION_WORD[d]} · runs ${runsLabel(row.effect)}`, true)
    },
  }
  const home: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'home',
    label: 'Home',
    width: 'minmax(140px, 200px)',
    value: () => undefined,
    display: (row) => <HomeCell row={row} />,
  }
  const state: SheetColumn<FxSheetRow, FxColumnKey> = {
    key: 'state',
    label: 'State',
    width: '100px',
    value: () => undefined,
    display: (row) =>
      row.state === 'held' ? (
        <span className="mx-1.5 flex min-w-0">
          <HeldBlindMark />
        </span>
      ) : (
        <span className={cn('mx-1.5 truncate text-xs', row.state !== 'running' && 'text-muted-foreground')}>
          {FX_STATE_LABELS[row.state]}
        </span>
      ),
  }

  // Each set's floor — the name column and the tracks' minimums — fits its band: 0 within 1100, 1 within the iPad's 940.
  switch (band) {
    case 0:
      return { columns: [property, target, timing, speed, size, direction, runs, home, state], minWidth: 210 + 96 + 150 + 104 + 80 + 64 + 52 + 96 + 140 + 100 }
    case 1:
      return { columns: [property, target, timingSpeed, dynamics, home, state], minWidth: 190 + 84 + 140 + 140 + 120 + 140 + 100 }
    default:
      // The property rides the name column here (`FxNameCell`'s `showProperty`).
      return { columns: [target, timingSpeed, dynamics, home, state], minWidth: 190 + 140 + 140 + 120 + 140 + 100 }
  }
}

/**
 * *Seconds* or the master an effect follows, read off the live bank by uuid: a beat effect's null is
 * master 1, a wall-clock effect's rate master is its only master and none is its own Seconds.
 */
function TimingCell({ effect, speed = false }: { effect: ActiveEffect; speed?: boolean }) {
  const wallClock = effect.timingSource === 'WALL_CLOCK'
  const seconds = isOnSeconds(effect, effect.timingSource)
  const uuid = wallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid
  const { label, title } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => {
      if (seconds) return { label: 'Seconds', title: 'Its own cycle in seconds — follows no master' }
      const master = data?.find((m) => (uuid == null ? m.index === 1 : m.uuid === uuid))
      if (master == null) return { label: uuid == null ? 'M1' : 'M?', title: undefined }
      const prefix = wallClock ? 'Rate ' : ''
      return {
        label: `${prefix}M${master.index} · ${Math.round(master.bpm)}`,
        title: `${wallClock ? 'Cycle rate scaled by' : 'Runs on'} speed master ${master.index} — ${master.name}`,
      }
    },
  })
  return (
    <span className="mx-1.5 flex min-w-0 items-center gap-1.5">
      <Badge variant="outline" className="shrink-0 px-1 py-0 font-mono text-[10px]" title={title}>
        {label}
      </Badge>
      {speed && <span className="truncate font-mono text-xs tabular-nums">{speedLabel(effect)}</span>}
    </span>
  )
}

function TargetCell({ row }: { row: FxSheetRow }) {
  const { effect } = row
  const selection = selectionItems(effect)
  const Glyph = selection != null ? ListOrdered : effect.isGroupTarget ? Layers : LayoutGrid
  const title =
    selection != null
      ? `Over ${selection.map((t) => t.key).join(', ')}, in that order`
      : effect.isGroupTarget
        ? `Group ${effect.targetKey}`
        : `Fixture ${effect.targetKey}`
  return (
    <span className="mx-1.5 flex min-w-0 items-center gap-1 text-xs" title={row.steps != null ? `${title} · steps ${row.steps}` : title}>
      <Glyph className="size-3 shrink-0 text-muted-foreground" />
      <span className="truncate font-mono">{row.target}</span>
      {row.steps != null && <span className="truncate text-muted-foreground">· {row.steps}</span>}
    </span>
  )
}

function HomeCell({ row }: { row: FxSheetRow }) {
  const { home, effect } = row
  return (
    <span className="mx-1.5 flex min-w-0 items-center gap-1.5 text-xs" title={[home.label, home.detail].filter(Boolean).join(' · ')}>
      <Badge variant={effect.programmerOwned ? 'default' : 'secondary'} className="min-w-0 shrink text-[10px]">
        <span className="truncate">{home.label}</span>
      </Badge>
      {effect.programmerLayerId != null && home.detail && (
        <span className="shrink-0 text-muted-foreground">{home.detail}</span>
      )}
    </span>
  )
}

/** The name, the rail's dot and marks, and the row's menu — the rail row's own items. */
function FxNameCell({
  row,
  showProperty,
  library,
  templates,
  onOpen,
  onSave,
  onTogglePause,
  onStop,
}: {
  row: FxSheetRow
  /** The narrow bands' fold: the property beside the name, its column gone. */
  showProperty: boolean
  library: Parameters<typeof saveAsTemplateOffer>[1]
  templates: Parameters<typeof saveAsTemplateOffer>[2]
  onOpen: () => void
  onSave: () => void
  onTogglePause: () => void
  onStop: () => void
}) {
  const { effect } = row
  const offer = saveAsTemplateOffer(effect, library, templates)
  return (
    <>
      <span
        className={cn(
          'size-1.5 shrink-0 rounded-full',
          row.heldByBlind ? 'bg-amber-500' : row.state === 'running' ? 'bg-violet-500' : 'bg-muted-foreground',
        )}
      />
      <span
        className={cn('min-w-0 flex-1 truncate text-sm font-medium', !effect.isRunning && 'opacity-60')}
        title={!effect.isRunning ? `${effect.effectType} — paused` : effect.effectType}
        onDoubleClick={onOpen}
      >
        {effect.effectType}
      </span>
      {showProperty && (
        <span className="shrink-0 font-mono text-[10px] text-muted-foreground">{effect.propertyName}</span>
      )}
      {row.state === 'spent' && (
        <Badge variant="outline" className="shrink-0 border-dashed px-1 py-0 text-[10px] text-muted-foreground" title="Its runs are done">
          spent
        </Badge>
      )}
      <span className="shrink-0" onClick={(e) => e.stopPropagation()}>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" className="size-6" aria-label={`Actions for ${effect.effectType}`}>
              <MoreHorizontal className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          {/* No focus back to the trigger: *Edit…* opens a popover in the same gesture, as on the rail. */}
          <DropdownMenuContent align="end" onCloseAutoFocus={(e) => e.preventDefault()}>
            <DropdownMenuItem onClick={onOpen}>
              <Pencil className="size-3.5" />
              Edit…
            </DropdownMenuItem>
            <DropdownMenuItem onClick={onTogglePause}>
              {effect.isRunning ? <Pause className="size-3.5" /> : <Play className="size-3.5" />}
              {effect.isRunning ? 'Pause' : 'Resume'}
            </DropdownMenuItem>
            <DropdownMenuItem disabled={!offer.enabled} onClick={onSave} title={offer.reason}>
              <Plus className="size-3.5" />
              Save as palette…
            </DropdownMenuItem>
            <DropdownMenuItem variant="destructive" onClick={onStop}>
              <Square className="size-3.5" />
              Stop
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </span>
    </>
  )
}

/** The bar's verbs over the selected effects: the row menu's, a one-effect verb refused over several. */
function FxVerbs({
  selected,
  library,
  templates,
  onEdit,
  onSave,
  controls,
}: {
  selected: readonly FxSheetRow[]
  library: Parameters<typeof saveAsTemplateOffer>[1]
  templates: Parameters<typeof saveAsTemplateOffer>[2]
  onEdit: (row: FxSheetRow) => void
  onSave: (row: FxSheetRow) => void
  controls: { togglePause: (effect: ActiveEffect) => void; stop: (effect: ActiveEffect) => void }
}) {
  const count = selected.length
  const first = selected[0]
  const offer = first != null ? saveAsTemplateOffer(first.effect, library, templates) : null
  const running = selected.filter((row) => row.effect.isRunning)
  const paused = selected.filter((row) => !row.effect.isRunning)
  return (
    <>
      <LibraryVerb
        icon={Pencil}
        label="Edit…"
        title={`Edit ${first?.effect.effectType ?? 'this effect'} live`}
        disabledReason={oneRecordReason('Edit', 'effect', count)}
        onClick={() => onEdit(first)}
      />
      <LibraryVerb
        icon={Plus}
        label="Save as palette…"
        title="Save this effect as a palette"
        disabledReason={oneRecordReason('Save as palette', 'effect', count) ?? (offer?.enabled === false ? (offer.reason ?? 'Cannot be a palette') : null)}
        onClick={() => onSave(first)}
      />
      {paused.length === count ? (
        <LibraryVerb
          icon={Play}
          label="Resume"
          title={`Resume ${count} effect${count === 1 ? '' : 's'}`}
          onClick={() => paused.forEach((row) => controls.togglePause(row.effect))}
        />
      ) : (
        <LibraryVerb
          icon={Pause}
          label="Pause"
          title={`Pause ${running.length} running effect${running.length === 1 ? '' : 's'}`}
          onClick={() => running.forEach((row) => controls.togglePause(row.effect))}
        />
      )}
      <LibraryVerb
        icon={Square}
        label="Stop"
        destructive
        title={`Stop ${count} effect${count === 1 ? '' : 's'}`}
        onClick={() => selected.forEach((row) => controls.stop(row.effect))}
      />
    </>
  )
}
