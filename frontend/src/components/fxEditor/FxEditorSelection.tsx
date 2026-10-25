import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { cn } from '@/lib/utils'
import { SheetPage } from '@/components/sheet/SheetPage'
import { ProgrammerTableTabs } from '@/components/programmer/ProgrammerTableTabs'
import { DeskChip } from '@/components/desk/DeskChip'
import { SelectionMaskPill } from '@/components/programmer/SelectionMaskPill'
import { usePublishedRemask } from '@/components/fixtures-list/remaskStore'
import { RecordLookSheet } from '@/components/programmer/RecordLookSheet'
import { ProgrammerAddFxSheet, useProgrammerAddFx } from '@/components/programmer/ProgrammerAddFx'
import { useProgrammerScope } from '@/components/programmer/ProgrammerScope'
import { OwnerEditor } from '@/components/stage/OwnerEditor'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { FxPicker, type AuditionedFx } from '@/components/fx/FxPicker'
import { SplitFxEditor, type SplitSession } from '@/components/fx/SplitFxEditor'
import { fxTargetOf, useWindowFxPair } from '@/components/fx/selectionFxTarget'
import { useStopFx, useToggleFxPause } from '@/components/fx/useFxControls'
import { useCueLabel, useFxDetail } from '@/components/fixtureSheet/fxLabels'
import { selectWindowHeads } from '@/components/fixtures-list/programmerSelectRequest'
import { findFxEntry } from '@/components/busking/buskingTypes'
import { useContainerBand } from '@/hooks/useContainerBand'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { useActiveFxQuery, useFxLibraryQuery, type ActiveFx } from '@/store/fixtureFx'
import { useGroupListQuery } from '@/store/groups'
import type { Fixture } from '@/store/fixtures'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { useIsDeskConnected } from '@/store/status'
import { FAMILY_LABELS, familyForCategory, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { selectionUnits } from '@/lib/fxTargets'
import { carriesFxAttribute } from '@/components/fx/fxEditorModel'
import {
  setPickedFx,
  setSoloFx,
  toggleLaneFold,
  useFoldedLanes,
  useFxGraphOptions,
  usePickedFx,
  useSoloFx,
} from '@/lib/fxEditorView'
import { LANE_ATTRIBUTE, fxLanes, fxTargetsOf, heldBackReasons, type FxLane } from './fxEditorList'
import { FxLaneList } from './FxLaneList'
import { FxGraph } from './FxGraph'
import { windowRequest } from './fxGraphModel'
import { useFxPreview } from './useFxPreview'

/**
 * The widths the editor folds at, widest first, by its own width (FX editor plan §4.2): three
 * columns — list 248 · graph · inspector 340 — from 1100; list · graph from 760, the inspector
 * over the graph's right half while something is open in it; one column below.
 */
export const FX_EDITOR_BAND_FLOORS: readonly number[] = [1100, 760, 0]

/**
 * **The FX editor** (FX editor plan D1, D2): the programmer FX tab's *Selection* view. The subject
 * is the window's pair (`useWindowFxPair`) — the desk's selection while the window follows it, else
 * the programmer list's rows — and the list is every FX reaching those heads, in family lanes in the
 * desk's apply order ([fxLanes]). Picking a card puts it in the inspector, the live editor over
 * `FxFields` in the inspector's order; *+ FX* puts the picker there, on that lane's family. The graph
 * column draws the desk's preview (`FxGraph`), which the list reads its apply order from.
 */
export function FxEditorSelection({ projectId, viewSwitch }: { projectId: number; viewSwitch: ReactNode }) {
  const pair = useWindowFxPair(true)
  const { fixtures } = useFixtureLookup()
  const { data: groups } = useGroupListQuery()
  const { data: effects } = useActiveFxQuery()
  const { data: layers } = useProgrammerLayersQuery()
  const { data: library } = useFxLibraryQuery()
  const blind = useProgrammerBlind()
  const connected = useIsDeskConnected()
  const detail = useFxDetail()
  const cueLabel = useCueLabel()
  const pickedId = usePickedFx()
  const soloId = useSoloFx()
  const folded = useFoldedLanes()
  const scope = useProgrammerScope()
  const offer = useProgrammerAddFx()
  const togglePause = useToggleFxPause()
  const stopFx = useStopFx()
  const [measure, band] = useContainerBand(FX_EDITOR_BAND_FLOORS)

  const rig = useMemo(() => fixtures ?? [], [fixtures])
  const running = useMemo(() => effects ?? [], [effects])
  const layerPosition = useMemo(() => new Map((layers ?? []).map((l, i) => [l.layerId, i])), [layers])
  const targets = pair.targets

  // The list before the desk has answered: which FX reach the selection, for the preview to ask.
  const reaching = useMemo(
    () => fxLanes({ effects: running, targets, fixtures: rig, layerPosition, answer: null }),
    [layerPosition, rig, running, targets],
  )
  const listed = useMemo(() => reaching.flatMap((l) => l.cards.map((c) => c.effect.id)).sort((a, b) => a - b), [reaching])
  // The picked FX while it is on the selection, else the list's top card: what the window counts.
  const anchor = pickedId != null && listed.includes(pickedId) ? pickedId : (reaching.flatMap((l) => l.cards)[0]?.effect.id ?? null)
  // Solo is an index into the ask, so a solo'd FX other than the picked one is asked for beside it.
  const graphOptions = useFxGraphOptions()
  const soloListed = soloId != null && listed.includes(soloId) ? soloId : null
  const fxIds = anchor == null ? [] : soloListed != null && soloListed !== anchor ? [anchor, soloListed] : [anchor]
  const preview = useFxPreview(
    anchor == null
      ? null
      : {
          projectId,
          targets,
          fxIds,
          listed,
          window: windowRequest(graphOptions.window),
          solo: soloListed == null ? undefined : fxIds.indexOf(soloListed),
        },
  )
  const lanes = useMemo(
    () => fxLanes({ effects: running, targets, fixtures: rig, layerPosition, answer: preview.answer }),
    [layerPosition, preview.answer, rig, running, targets],
  )

  const [adding, setAdding] = useState<AttributeFamily | null>(null)
  const [openCueId, setOpenCueId] = useState<number | null>(null)
  const [recording, setRecording] = useState(false)

  // Only an FX on the selection: the id is the window's, and outlives a selection or a desk restart.
  const picked = pickedId != null && listed.includes(pickedId) ? (running.find((e) => e.id === pickedId) ?? null) : null
  const pick = (effect: ActiveFx) => {
    setAdding(null)
    setPickedFx(effect.id)
  }
  const add = (family: AttributeFamily) => {
    setPickedFx(null)
    setAdding((prev) => (prev === family ? null : family))
  }
  // A focused Look layer takes a new FX into its Look, through `+ FX`'s own sheet; this column's
  // picker is the bare press, so it is the Local arm's.
  const lookFocused = scope?.kind === 'layer'
  const inspectorOpen = picked != null || (adding != null && !lookFocused)

  const cards = lanes.flatMap((l) => l.cards)
  const recordFamilies = lanes.filter((l) => l.cards.length > 0).map((l) => l.family)
  const recordable = cards.filter((c) => c.effect.programmerOwned && c.effect.programmerLayerId == null).map((c) => c.effect.id)

  const units = useMemo(() => selectionUnits(targets, rig), [rig, targets])
  const fxTarget = useMemo(() => fxTargetOf(targets, fixtures, groups), [fixtures, groups, targets])
  const underneathOf = (lane: FxLane) => underneathAt(lane, units)
  const layerName = (layerId: number) => layers?.find((l) => l.layerId === layerId)?.source.name

  const list =
    targets.length === 0 ? (
      <NothingSelected effects={running} library={library} fixtures={rig} />
    ) : (
      <FxLaneList
        lanes={lanes}
        library={library}
        pickedId={pickedId}
        soloId={soloId}
        folded={folded}
        blind={blind}
        connected={connected}
        detail={detail}
        cueLabel={cueLabel}
        layerName={layerName}
        heldBack={(fxId) => heldBackReasons(preview.answer, fxId)}
        underneathOf={underneathOf}
        adding={adding}
        addDisabled={offer.disabled ? offer.reason : null}
        onPick={pick}
        onOpenCue={setOpenCueId}
        onSolo={setSoloFx}
        onTogglePause={togglePause}
        onStop={(effect) => {
          if (effect.id === pickedId) setPickedFx(null)
          stopFx(effect)
        }}
        onToggleFold={toggleLaneFold}
        onAdd={add}
      />
    )

  const inspector = (
    <Inspector
      projectId={projectId}
      picked={picked}
      adding={lookFocused ? null : adding}
      lanes={lanes}
      pair={pair}
      target={fxTarget}
      cueLabel={cueLabel}
      onOpenCue={setOpenCueId}
      onPicked={(fxId) => {
        setAdding(null)
        setPickedFx(fxId)
      }}
      onClose={() => {
        setAdding(null)
        setPickedFx(null)
      }}
      onStop={(effect) => {
        setPickedFx(null)
        stopFx(effect)
      }}
    />
  )

  const form = band === 0 ? 'column' : band === 1 ? 'side' : 'stacked'
  const fxName = useCallback((fxType: string) => findFxEntry(library, fxType)?.name ?? fxType, [library])
  const fixtureNames = useMemo(() => new Map(rig.map((f) => [f.key, f.name])), [rig])
  const fixtureName = useCallback((key: string) => fixtureNames.get(key), [fixtureNames])
  const unsolo = useCallback(() => setSoloFx(null), [])
  const soloEffect = soloListed == null ? null : running.find((e) => e.id === soloListed)
  const graph = (
    <FxGraph
      answer={anchor == null ? null : preview.answer}
      error={preview.error}
      empty={anchor == null}
      narrow={form === 'stacked'}
      fxName={fxName}
      fixtureName={fixtureName}
      solo={soloEffect == null ? null : { name: fxName(soloEffect.fxType) }}
      onUnsolo={unsolo}
      bounce={running.find((e) => e.id === anchor)?.direction === 'BOUNCE'}
    />
  )

  return (
    <>
      <div className="@container">
        <SheetPage.Row>
          <ProgrammerTableTabs />
          {viewSwitch}
          <div className="flex-1" />
        </SheetPage.Row>
      </div>
      <div ref={measure} data-fx-editor data-fx-editor-band={band} className="flex min-h-0 flex-1 flex-col">
        <div
          className={cn(
            'grid min-h-0 flex-1',
            form === 'column' && 'grid-cols-[248px_minmax(0,1fr)_340px]',
            form === 'side' && 'grid-cols-[248px_minmax(0,1fr)]',
            // Each row its content's height, so the tall graph never draws over the list and the column scrolls.
            form === 'stacked' && 'grid-cols-1 auto-rows-max content-start overflow-y-auto',
          )}
        >
          <div data-fx-list className={cn('flex min-h-0 flex-col gap-2 p-2', form !== 'stacked' && 'overflow-y-auto border-r')}>
            <Subject target={fxTarget} units={units} families={pair.families} />
            {list}
          </div>
          {form === 'side' ? (
            <div className="flex min-h-0 min-w-0">
              <div className="min-w-0 flex-1">{graph}</div>
              {inspectorOpen && (
                <aside data-fx-inspector="side" aria-label="Inspector" className="w-1/2 min-w-[300px] overflow-y-auto border-l bg-background p-3 shadow-lg">
                  {inspector}
                </aside>
              )}
            </div>
          ) : (
            graph
          )}
          {form === 'column' && (
            <aside data-fx-inspector="column" aria-label="Inspector" className="overflow-y-auto border-l p-3">
              {inspector}
            </aside>
          )}
          {form === 'stacked' && (
            <aside data-fx-inspector="stacked" aria-label="Inspector" className="border-t p-3">
              {inspector}
            </aside>
          )}
        </div>
      </div>
      <SheetPage.Footer>
        <span className="flex items-center gap-3">
          <Button variant="outline" size="sm" className="h-7" disabled={targets.length === 0} onClick={() => setRecording(true)}>
            Record as Look…
          </Button>
          <span className="tabular-nums">{cards.length} FX</span>
        </span>
      </SheetPage.Footer>
      <RecordLookSheet
        open={recording}
        onOpenChange={setRecording}
        projectId={projectId}
        targets={[...targets]}
        // The lanes' families; with none, the window's own mask.
        initialMask={recordFamilies.length === 0 ? undefined : recordFamilies.length === 4 ? null : recordFamilies}
        initialFxIds={recordable}
      />
      {lookFocused && adding != null && (
        <ProgrammerAddFxSheet projectId={projectId} open offer={offer} onClose={() => setAdding(null)} />
      )}
      {openCueId != null && (
        <OwnerEditor projectId={projectId} entry={{ kind: 'cue', id: openCueId }} onClose={() => setOpenCueId(null)} />
      )}
    </>
  )
}

/** The first selected head carrying the lane's attribute — the lane's first FX's, else its family's own. */
function underneathAt(lane: FxLane, units: ReturnType<typeof selectionUnits>): { head: string; attribute: string } | null {
  const attribute =
    lane.cards[0]?.effect.attribute ??
    LANE_ATTRIBUTE[lane.family] ??
    units
      .flatMap((u) => [...u.attributes, ...(u.elements ?? []).flatMap((e) => e.attributes)])
      .find((p) => (p.type === 'slider' || p.type === 'setting') && familyForCategory(p.category) === 'BEAM')?.name
  if (attribute == null) return null
  for (const unit of units) {
    if (carriesFxAttribute(unit.attributes, attribute)) return { head: unit.key, attribute }
    const cell = unit.elements?.find((e) => carriesFxAttribute(e.attributes, attribute))
    if (cell != null) return { head: cell.key, attribute }
  }
  return null
}

/**
 * The subject (FX editor plan §4.3): *Hex 1–8 · 8 heads*, *Slender 1–2 · 8 elements*, the family
 * pill and the desk chip, as the Values grid's row C has them — the pill writing through the list's
 * desk bridge while the window follows the desk.
 */
function Subject({
  target,
  units,
  families,
}: {
  target: ReturnType<typeof fxTargetOf>
  units: ReturnType<typeof selectionUnits>
  families: readonly AttributeFamily[] | null
}) {
  const remask = usePublishedRemask()
  if (target == null) return null
  const name = target.type === 'fixture' ? target.fixture.name : target.type === 'group' ? target.group.name : target.label
  const elements = units.reduce((n, unit) => n + (unit.elements?.length ?? 0), 0)
  const count = elements > 0 ? `${elements} ${elements === 1 ? 'element' : 'elements'}` : `${units.length} ${units.length === 1 ? 'head' : 'heads'}`
  return (
    <div data-fx-subject className="flex min-w-0 flex-wrap items-center gap-1.5 px-0.5 text-[11.5px]">
      <span className="min-w-0 truncate font-medium">{name}</span>
      <span className="text-muted-foreground">· {count}</span>
      {remask != null ? (
        <SelectionMaskPill families={families != null ? [...families] : null} onChange={remask} tone="bar" />
      ) : (
        // A window with its own selection: the mask is the marquee's, so it is read here, not set.
        <span className="rounded border px-1 text-[10px] text-muted-foreground" title="The selection’s attribute mask">
          {families != null && families.length > 0 ? formatFamilyList(families, ' · ') : 'All'}
        </span>
      )}
      <DeskChip className="min-w-0 shrink" />
    </div>
  )
}

/** With nothing selected: say so, and offer the heads of a running FX (FX editor plan §4.1). */
function NothingSelected({
  effects,
  library,
  fixtures,
}: {
  effects: readonly ActiveFx[]
  library: ReturnType<typeof useFxLibraryQuery>['data']
  fixtures: readonly Fixture[]
}) {
  const choices = effects.filter((e) => e.leaving !== true)
  return (
    <div data-fx-nothing-selected className="flex flex-col items-start gap-2 p-2">
      <EditorReadout>Nothing is selected. Select heads to see the FX on them, top first in the order they apply.</EditorReadout>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" className="h-7" disabled={choices.length === 0}>
            Select the heads of a running FX…
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {choices.map((effect) => (
            <DropdownMenuItem
              key={effect.id}
              onClick={() => {
                selectWindowHeads(fxTargetsOf(effect), fixtures)
                setPickedFx(effect.id)
              }}
            >
              {findFxEntry(library, effect.fxType)?.name ?? effect.fxType}
              <span className="ml-auto pl-3 font-mono text-[10px] text-muted-foreground">{effect.attribute}</span>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function Inspector({
  projectId,
  picked,
  adding,
  lanes,
  pair,
  target,
  cueLabel,
  onOpenCue,
  onPicked,
  onClose,
  onStop,
}: {
  projectId: number
  picked: ActiveFx | null
  adding: AttributeFamily | null
  lanes: readonly FxLane[]
  pair: ReturnType<typeof useWindowFxPair>
  target: ReturnType<typeof fxTargetOf>
  cueLabel: (cueId: number) => string | undefined
  onOpenCue: (cueId: number) => void
  onPicked: (fxId: number) => void
  onClose: () => void
  onStop: (effect: ActiveFx) => void
}) {
  const [session, setSession] = useState<SplitSession>(null)

  if (adding != null) {
    const lane = lanes.find((l) => l.family === adding)
    const holdsLocal = (lane?.cards ?? []).some((c) => c.kind === 'local')
    return (
      <AddFx
        key={adding}
        projectId={projectId}
        family={adding}
        holdsLocal={holdsLocal}
        pair={pair}
        target={target}
        onPicked={onPicked}
        onClose={onClose}
      />
    )
  }
  if (picked == null) {
    return <EditorReadout>Pick an FX in the list to edit it here, or + FX on a lane to start one.</EditorReadout>
  }
  const cueId = picked.cueId
  if (cueId != null && !picked.programmerOwned) {
    const cue = cueLabel(cueId) ?? 'its cue'
    return (
      <div className="flex flex-col items-start gap-2">
        <EditorReadout>A cue’s FX — open {cue} to change it.</EditorReadout>
        <Button variant="outline" size="sm" className="h-7" onClick={() => onOpenCue(cueId)}>
          Open {cue}
        </Button>
      </div>
    )
  }
  return (
    <SplitFxEditor
      effect={picked}
      session={session}
      onSessionChange={setSession}
      onClose={() => {
        setSession(null)
        onClose()
      }}
      onStop={picked.programmerLayerId != null ? undefined : (effect) => {
        setSession(null)
        onStop(effect)
      }}
      onFollow={onPicked}
      layout="inspector"
    />
  )
}

/**
 * *+ FX* on a lane (FX editor plan §4.3, D6): the picker's press arm, opening on the lane's family.
 * Where the lane already holds a Local FX on a selected head it asks ***Replace* · *Stack***,
 * starting on Replace — today's press, which takes those heads off the family's Local FX — or Stack,
 * which adds it on top on the family's stacking blend and takes nothing off.
 */
function AddFx({
  projectId,
  family,
  holdsLocal,
  pair,
  target,
  onPicked,
  onClose,
}: {
  projectId: number
  family: AttributeFamily
  holdsLocal: boolean
  pair: ReturnType<typeof useWindowFxPair>
  target: ReturnType<typeof fxTargetOf>
  onPicked: (fxId: number) => void
  onClose: () => void
}) {
  const [stack, setStack] = useState(false)
  const [current, setCurrent] = useState<AuditionedFx | null>(null)
  return (
    <div data-fx-add={family} className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-[13px] font-medium">+ {FAMILY_LABELS[family].singular} FX</span>
        <button
          type="button"
          aria-label="Close the picker"
          onClick={onClose}
          className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent"
        >
          <X className="size-3.5" />
        </button>
      </div>
      {holdsLocal && (
        <ToggleGroup
          type="single"
          size="sm"
          value={stack ? 'stack' : 'replace'}
          aria-label="Over the Local FX already here"
          onValueChange={(v) => v && setStack(v === 'stack')}
          className="flex w-full"
        >
          <ToggleGroupItem value="replace" className="flex-1 text-xs" title="Take the selected heads off this family’s other Local FX">
            Replace
          </ToggleGroupItem>
          <ToggleGroupItem value="stack" className="flex-1 text-xs" title="Add it on top, taking nothing off">
            Stack
          </ToggleGroupItem>
        </ToggleGroup>
      )}
      {target == null ? (
        <EditorReadout>Reading the rig…</EditorReadout>
      ) : (
        <FxPicker
          target={target}
          initialFamily={family}
          press={{ projectId, families: pair.families, stack: holdsLocal && stack }}
          current={current}
          onCurrent={setCurrent}
          onEdit={current != null ? () => onPicked(current.fxId) : undefined}
        />
      )}
    </div>
  )
}
