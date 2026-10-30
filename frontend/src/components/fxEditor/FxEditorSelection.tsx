import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ChevronDown, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { SheetPage } from '@/components/sheet/SheetPage'
import { ProgrammerTableTabs, useProgrammerTables } from '@/components/programmer/ProgrammerTableTabs'
import { DeskChip } from '@/components/desk/DeskChip'
import { SelectionMaskPill } from '@/components/programmer/SelectionMaskPill'
import { usePublishedRemask } from '@/components/fixtures-list/remaskStore'
import { RecordLookSheet } from '@/components/programmer/RecordLookSheet'
import { ProgrammerAddFxSheet, useProgrammerAddFx } from '@/components/programmer/ProgrammerAddFx'
import { useProgrammerScope } from '@/components/programmer/ProgrammerScope'
import { ToolbarMenu, ToolbarMenuItem, ToolbarMenuLabel } from '@/components/sheet/ToolbarMenu'
import { ColourSwatch } from '@/components/fixtureColours/ColourSwatches'
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
import type { CueTarget } from '@/api/cuesApi'
import type { UpdateFxRequest } from '@/api/fxApi'
import { fxTargetOf, useWindowFxPair } from '@/components/fx/selectionFxTarget'
import { useStopFx, useToggleFxPause } from '@/components/fx/useFxControls'
import { FxChipSheetContext } from '@/components/fx/fxChipSheet'
import { useCueLabel, useFxDetail } from '@/components/fixtureSheet/fxLabels'
import { selectWindowHeads } from '@/components/fixtures-list/programmerSelectRequest'
import { findFxEntry } from '@/components/busking/buskingTypes'
import { useContainerBand } from '@/hooks/useContainerBand'
import { useCoarsePointer } from '@/hooks/useCoarsePointer'
import { useFixtureColourIndex } from '@/hooks/useFixtureColourIndex'
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
import { runningCards, subjectForm, type SubjectFixture } from './fxEditorHead'
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
      <NothingSelected effects={running} library={library} fixtures={rig} detail={detail} onPick={pick} />
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
      empty={targets.length === 0 ? 'nothing-selected' : anchor == null ? 'nothing-reaches' : null}
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
              <Subject projectId={projectId} target={fxTarget} targets={targets} fixtures={rig} families={pair.families} asSheet={form === 'stacked'} />
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
                <FxChipSheetContext.Provider value={true}>{inspector}</FxChipSheetContext.Provider>
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
        // The lanes' families; with none, every attribute.
        initialMask={recordFamilies.length === 0 || recordFamilies.length === 4 ? null : recordFamilies}
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
 * The subject (FX editor follow-ups plan D2): a group's name and how many fixtures, one fixture's
 * name and its elements, or — for several fixtures — *4 fixtures · 12 elements ▾*, which opens
 * their list. Beside it the family pill and the desk chip, on one row; the pill writes through the
 * list's desk bridge while the window follows the desk. A name truncates beside them; the count
 * never does, and the pill and chip wrap below it only where the column is too narrow for all three.
 */
function Subject({
  projectId,
  target,
  targets,
  fixtures,
  families,
  asSheet,
}: {
  projectId: number
  target: ReturnType<typeof fxTargetOf>
  targets: readonly CueTarget[]
  fixtures: readonly Fixture[]
  families: readonly AttributeFamily[] | null
  asSheet: boolean
}) {
  const remask = usePublishedRemask()
  const name = target == null ? '' : target.type === 'fixture' ? target.fixture.name : target.type === 'group' ? target.group.name : target.label
  const form = useMemo(() => (target == null ? null : subjectForm(targets, fixtures, name)), [fixtures, name, target, targets])
  const row = useRef<HTMLDivElement>(null)
  const narrowed = useRef(false)
  // Only takes away the chip its list closes onto, so focus goes to the mask on the same row.
  useEffect(() => {
    if (!narrowed.current) return
    narrowed.current = false
    if (form?.kind === 'several' || (document.activeElement != null && document.activeElement !== document.body)) return
    row.current?.querySelector<HTMLElement>('[data-fx-subject-mask]')?.focus()
  }, [form?.kind, targets])
  if (form == null) return null
  return (
    <div
      ref={row}
      data-fx-subject
      className={cn('flex min-w-0 items-center gap-1.5 px-0.5 text-[11.5px]', form.kind === 'several' && 'flex-wrap')}
    >
      {form.kind === 'named' ? (
        <span className="flex min-w-0 flex-[1_1_auto] items-baseline gap-1">
          <span className="min-w-0 truncate font-medium" title={form.name}>
            {form.name}
          </span>
          <span className="shrink-0 text-muted-foreground">· {form.count}</span>
        </span>
      ) : (
        <SubjectFixtures
          projectId={projectId}
          label={form.label}
          list={form.fixtures}
          fixtures={fixtures}
          asSheet={asSheet}
          onOnly={() => (narrowed.current = true)}
        />
      )}
      {remask != null ? (
        <SelectionMaskPill families={families != null ? [...families] : null} onChange={remask} tone="bar" className="block min-w-0 shrink truncate" marker="data-fx-subject-mask" />
      ) : (
        // A window with its own selection: the mask is the marquee's, so it is read here, not set.
        <span data-fx-subject-mask tabIndex={-1} className="min-w-0 shrink truncate rounded border px-1 text-[10px] text-muted-foreground" title="The selection’s attribute mask">
          {families != null && families.length > 0 ? formatFamilyList(families, ' · ') : 'All'}
        </span>
      )}
      <DeskChip className="min-w-0 shrink" />
    </div>
  )
}

/**
 * *4 fixtures · 12 elements ▾* and the list it opens (D2, §4): each fixture's colour, name and
 * selected elements, a press narrowing the selection to it (*Only*, shown on the hovered row, and on
 * every row under a finger). A dropdown beside the chip, a bottom sheet of 44px rows below the
 * editor's 760 arm; the list takes focus as it opens and Escape hands it back to the chip.
 */
function SubjectFixtures({
  projectId,
  label,
  list,
  fixtures,
  asSheet,
  onOnly,
}: {
  projectId: number
  label: string
  list: readonly SubjectFixture[]
  fixtures: readonly Fixture[]
  asSheet: boolean
  onOnly: () => void
}) {
  const colours = useFixtureColourIndex(projectId)
  const coarse = useCoarsePointer()
  return (
    <ToolbarMenu
      asSheet={asSheet}
      title="Selected fixtures"
      contentClassName="w-64"
      trigger={
        <button
          type="button"
          data-fx-subject-fixtures
          title={label}
          className="inline-flex h-6 max-w-full shrink-0 items-center gap-1 rounded-full border bg-card px-2 font-medium tabular-nums hover:bg-accent"
        >
          <span className="min-w-0 truncate">{label}</span>
          <ChevronDown className="size-3 shrink-0 text-muted-foreground" />
        </button>
      }
    >
      {list.map(({ fixture, elements, targets }) => {
        const colour = colours.get(fixture.key)
        return (
          <ToolbarMenuItem
            key={fixture.key}
            onSelect={() => {
              onOnly()
              selectWindowHeads(targets, fixtures)
            }}
            title={`Select ${fixture.name} only`}
            className="group/only gap-2"
          >
            {colour != null ? <ColourSwatch colour={colour} className="h-4 w-1 rounded-sm" /> : <span aria-hidden className="h-4 w-1 shrink-0 rounded-sm bg-muted-foreground/25" />}
            <span className="min-w-0 flex-1 truncate">{fixture.name}</span>
            {elements > 0 && <span className="shrink-0 text-muted-foreground tabular-nums">{elements}</span>}
            <span
              className={cn(
                'shrink-0 text-[10.5px] text-violet-600 dark:text-violet-400',
                !asSheet && !coarse && 'opacity-0 group-hover/only:opacity-100 group-focus/only:opacity-100 group-data-[highlighted]/only:opacity-100',
              )}
            >
              Only
            </span>
          </ToolbarMenuItem>
        )
      })}
      <ToolbarMenuLabel>Elements counted under each fixture. Only narrows the selection; the desk’s while this window follows it.</ToolbarMenuLabel>
    </ToolbarMenu>
  )
}

/**
 * With nothing selected (D1): one line, then the running FX as cards by family and name — what it
 * runs on, its cycle and master — a press selecting its heads and picking it. With none running,
 * the way to the Values table, where heads are selected.
 */
function NothingSelected({
  effects,
  library,
  fixtures,
  detail,
  onPick,
}: {
  effects: readonly ActiveFx[]
  library: ReturnType<typeof useFxLibraryQuery>['data']
  fixtures: readonly Fixture[]
  detail: (effect: ActiveFx) => string
  onPick: (effect: ActiveFx) => void
}) {
  const tables = useProgrammerTables()
  const name = useCallback((effect: ActiveFx) => findFxEntry(library, effect.fxType)?.name ?? effect.fxType, [library])
  const cards = useMemo(() => runningCards(effects, fixtures, name), [effects, fixtures, name])
  const showValues = () => tables?.select('values')
  return (
    <div data-fx-nothing-selected className="flex flex-col gap-2 p-0.5">
      <p className="text-[12px] leading-snug">
        <span className="font-semibold">No heads selected.</span>
        {cards.length > 0 && <span className="text-muted-foreground"> Pick a running FX to edit it on its heads.</span>}
      </p>
      {cards.map(({ effect, drives, on }) => (
        <button
          key={effect.id}
          type="button"
          data-fx-running-card={effect.id}
          onClick={() => {
            selectWindowHeads(fxTargetsOf(effect), fixtures)
            onPick(effect)
          }}
          className="grid w-full grid-cols-[8px_minmax(0,1fr)_auto] items-center gap-x-2 gap-y-0.5 rounded-md border bg-card/40 px-2 py-1.5 text-left hover:border-violet-500/60"
        >
          <span aria-hidden className="row-span-2 size-2 rounded-full bg-violet-500" />
          <span className="truncate text-[12.5px] font-semibold">{name(effect)}</span>
          <span className="font-mono text-[10.5px] text-muted-foreground">{drives}</span>
          <span className="col-span-2 truncate text-[11px] text-muted-foreground">
            {on} · {detail(effect)}
          </span>
        </button>
      ))}
      {cards.length > 0 ? (
        <button type="button" onClick={showValues} className="self-start text-[11.5px] text-muted-foreground underline underline-offset-2 hover:text-foreground">
          or select heads in Values
        </button>
      ) : (
        <Button variant="outline" size="sm" className="h-7 self-start" onClick={showValues}>
          Select heads in Values
        </Button>
      )}
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
