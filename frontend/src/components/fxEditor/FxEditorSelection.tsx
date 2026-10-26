import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
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
import { FxPicker } from '@/components/fx/FxPicker'
import { useAuditionedFx } from '@/components/fx/useAuditionedFx'
import { SplitFxEditor, type SplitSession } from '@/components/fx/SplitFxEditor'
import { useReplaceStack } from '@/components/fx/useReplaceStack'
import {
  FxGraphLinkContext,
  createHoveredRowStore,
  type FxGraphLink,
  type GraphMark,
  type LinkedEditor,
} from '@/components/fx/fxGraphLink'
import type { UpdateFxRequest } from '@/api/fxApi'
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
  setPhoneLane,
  setPickedFx,
  setSoloFx,
  toggleLaneFold,
  useFoldedLanes,
  useFxGraphOptions,
  usePhoneLane,
  usePickedFx,
  useSoloFx,
} from '@/lib/fxEditorView'
import { LANE_ATTRIBUTE, fxLanes, fxTargetsOf, heldBackReasons, type FxLane } from './fxEditorList'
import { FxLaneList } from './FxLaneList'
import { FxGraph } from './FxGraph'
import { pickedIndex, windowRequest } from './fxGraphModel'
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
  const phoneLane = usePhoneLane()
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
  // The inspector's unsent move on the picked FX, so the graph follows a gesture (§3.3's `{fxId, draft}`).
  const [unsent, setUnsent] = useState<{ fxId: number; request: UpdateFxRequest } | null>(null)
  const preview = useFxPreview(
    anchor == null
      ? null
      : {
          projectId,
          targets,
          fxIds,
          draft: unsent != null && unsent.fxId === anchor ? unsent.request : undefined,
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

  const cards = useMemo(() => lanes.flatMap((l) => l.cards), [lanes])
  const recordFamilies = lanes.filter((l) => l.cards.length > 0).map((l) => l.family)
  const recordable = cards.filter((c) => c.effect.programmerOwned && c.effect.programmerLayerId == null).map((c) => c.effect.id)

  const units = useMemo(() => selectionUnits(targets, rig), [rig, targets])
  const fxTarget = useMemo(() => fxTargetOf(targets, fixtures, groups), [fixtures, groups, targets])
  const underneathOf = (lane: FxLane) => underneathAt(lane, units)
  const layerName = (layerId: number) => layers?.find((l) => l.layerId === layerId)?.source.name

  const form = band === 0 ? 'column' : band === 1 ? 'side' : 'stacked'
  // Below 760 the list folds to a row of lane chips, one lane opened under them (§4.2).
  const listedLanes = form === 'stacked' ? lanes.filter((l) => l.family === phoneLane) : lanes
  const list =
    targets.length === 0 ? (
      <NothingSelected effects={running} library={library} fixtures={rig} />
    ) : (
      <FxLaneList
        lanes={listedLanes}
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

  const fxName = useCallback((fxType: string) => findFxEntry(library, fxType)?.name ?? fxType, [library])
  const fixtureNames = useMemo(() => new Map(rig.map((f) => [f.key, f.name])), [rig])
  const fixtureName = useCallback((key: string) => fixtureNames.get(key), [fixtureNames])
  const unsolo = useCallback(() => setSoloFx(null), [])
  const soloEffect = soloListed == null ? null : running.find((e) => e.id === soloListed)

  // What ties the inspector to the graph (D9, D12, D13): the lights, the ladder, Match and the handles.
  const [highlight, setHighlight] = useState<GraphMark | null>(null)
  const [ladderRow, setLadderRow] = useState<string | null>(null)
  const [graphRow] = useState(createHoveredRowStore)
  const [editor, setEditor] = useState<LinkedEditor | null>(null)
  const detach = useCallback((fxId: number) => setEditor((prev) => (prev?.fxId === fxId ? null : prev)), [])
  const answer = preview.answer
  const ladder = useMemo(() => {
    const index = answer == null ? null : pickedIndex(answer)
    const fx = index == null || answer == null ? null : answer.fx[index]
    return fx == null || picked == null || fx.fxId !== picked.id || answer == null ? null : { fx, rows: answer.rows }
  }, [answer, picked])
  const others = useMemo(
    () =>
      cards
        .filter((c) => c.effect.id !== picked?.id)
        .map((c) => {
          const entry = findFxEntry(library, c.effect.fxType)
          return { effect: c.effect, name: entry?.name ?? c.effect.fxType, wallClock: (entry?.timingSource ?? c.effect.timingSource) === 'WALL_CLOCK' }
        }),
    [cards, library, picked?.id],
  )
  const previewUnsent = useCallback((fxId: number, request: UpdateFxRequest | null) => {
    setUnsent((prev) => (request == null ? (prev?.fxId === fxId ? null : prev) : { fxId, request }))
  }, [])
  const link = useMemo(
    (): FxGraphLink => ({
      ladder,
      hoveredRow: graphRow,
      setLadderRow,
      setHighlight,
      others,
      attach: setEditor,
      detach,
      preview: previewUnsent,
    }),
    [detach, graphRow, ladder, others, previewUnsent],
  )

  const graph = (
    <FxGraph
      answer={anchor == null ? null : preview.answer}
      error={preview.error}
      empty={anchor == null}
      narrow={form === 'stacked'}
      scrollRows
      fxName={fxName}
      fixtureName={fixtureName}
      solo={soloEffect == null ? null : { name: fxName(soloEffect.fxType) }}
      onUnsolo={unsolo}
      bounce={running.find((e) => e.id === anchor)?.direction === 'BOUNCE'}
      highlight={highlight}
      onHighlight={setHighlight}
      linkedRow={ladderRow}
      onHoverRow={graphRow.set}
      editor={editor != null && editor.fxId === picked?.id ? editor : null}
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
      {/* The link reaches the list, the graph and the inspector only: the sheets opened from here
          (a cue's editor, + FX's sheet) host editors of their own, with no graph. */}
      <FxGraphLinkContext.Provider value={link}>
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
              {form === 'stacked' && targets.length > 0 && <LaneChips lanes={lanes} open={phoneLane} pickedId={picked?.id ?? null} />}
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
      </FxGraphLinkContext.Provider>
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

/**
 * The phone arm's list (§4.2): a chip per lane — its family, its FX count, lit while it holds the
 * picked FX — that opens that lane alone under the row, or folds it again.
 */
function LaneChips({ lanes, open, pickedId }: { lanes: readonly FxLane[]; open: AttributeFamily | null; pickedId: number | null }) {
  return (
    <div role="group" aria-label="Lanes" data-fx-lane-chips className="flex flex-wrap gap-1">
      {lanes.map((lane) => {
        const picked = pickedId != null && lane.cards.some((c) => c.effect.id === pickedId)
        return (
          <button
            key={lane.family}
            type="button"
            data-fx-lane-chip={lane.family}
            aria-expanded={open === lane.family}
            onClick={() => setPhoneLane(open === lane.family ? null : lane.family)}
            className={cn(
              'inline-flex h-8 items-center gap-1.5 rounded-full border px-3 text-xs',
              open === lane.family && 'bg-accent font-medium',
              picked && 'border-violet-500/60',
            )}
          >
            {FAMILY_LABELS[lane.family].singular}
            <span className="text-muted-foreground tabular-nums">{lane.cards.length}</span>
          </button>
        )
      })}
    </div>
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
    return (
      <AddFx
        key={adding}
        projectId={projectId}
        family={adding}
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
 * *+ FX* on a lane (FX editor plan §4.3, D6): the picker's press arm, opening on the lane's family,
 * asking `ReplaceStack` as the picker's FX arm does ([useReplaceStack]). Kept mounted while the
 * window's pair moves, so the FX a tap swaps is the one started over the selection it is on.
 */
function AddFx({
  projectId,
  family,
  pair,
  target,
  onPicked,
  onClose,
}: {
  projectId: number
  family: AttributeFamily
  pair: ReturnType<typeof useWindowFxPair>
  target: ReturnType<typeof fxTargetOf>
  onPicked: (fxId: number) => void
  onClose: () => void
}) {
  const { current, onCurrent } = useAuditionedFx(pair.targets)
  const { segment, onLanding, stackOn } = useReplaceStack(pair.targets, true, current)
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
      {segment}
      {target == null ? (
        <EditorReadout>Reading the rig…</EditorReadout>
      ) : (
        <FxPicker
          target={target}
          initialFamily={family}
          press={{ projectId, families: pair.families, stackOn }}
          onLanding={onLanding}
          current={current}
          onCurrent={onCurrent}
          onEdit={current != null ? () => onPicked(current.fxId) : undefined}
        />
      )}
    </div>
  )
}
