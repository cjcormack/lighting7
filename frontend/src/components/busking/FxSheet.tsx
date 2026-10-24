import { Fragment, useMemo, useRef, useState } from 'react'
import { Pause, Play, Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EditedMark } from '@/components/fx/FxEditor'
import { SplitFxEditor, type SplitSession } from '@/components/fx/SplitFxEditor'
import { defaultParameters, differsFromPalette, draftOf, isCueFx, isPaletteLayerInstance } from '@/components/fx/fxEditorModel'
import { targetEmitters } from '@/components/fixtures-list/rowModel'
import { ApplyPicker } from '@/components/press/ApplyPicker'
import type { PressPair } from '@/hooks/usePress'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { PaletteFit } from '@/lib/paletteOffer'
import { useFxDetail } from '@/components/fixtureSheet/fxLabels'
import { fxReaching } from '@/components/fixtureSheet/rowSource'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import {
  useActiveFxQuery,
  useFxLibraryQuery,
  usePauseFxMutation,
  useRemoveFxMutation,
  useResumeFxMutation,
  type ActiveFx,
} from '@/store/fixtureFx'
import { usePauseGroupFxMutation, useRemoveGroupFxMutation, useResumeGroupFxMutation } from '@/store/groups'
import { useMaster1Uuid } from '@/store/speedMasters'
import { useIsDeskConnected } from '@/store/status'
import { usePaletteListQuery } from '@/store/palettes'
import type { Fixture } from '@/store/fixtures'
import { findFxEntry, lookLayerTarget, selectedHeadCount, type BuskingTarget } from './buskingTypes'
import { writeTargetsOf } from './ColourSheet'
import { fxTargetLabel, selectionItems } from '@/lib/fxTargets'
import type { CueTarget } from '@/api/cuesApi'

const EMPTY: readonly ActiveFx[] = []
const EMPTY_FIXTURES: readonly Fixture[] = []

/**
 * The effects reaching the busk selection (fixture-fx-sheets plan D20, apply-model plan §3.4): on a
 * selected fixture or one of its heads, on a selected cell or its fixture, on a selected group or any
 * of its members — and on any group those fixtures are in. What the programmer runs — a pad's effect
 * and the operator's own — and a cue's, which the tab draws read-only, its home being the cue. A
 * manual effect (a script's) is left out, and so is one fading out after a GO or a release, which is
 * nobody's. Pure, so the reach is a table a test can read.
 */
export function fxOnSelection(
  effects: readonly ActiveFx[] | undefined,
  selected: readonly BuskingTarget[],
  fixtures: readonly Fixture[],
): ActiveFx[] {
  if (effects == null || selected.length === 0) return []
  const keys = new Set<string>()
  const groups = new Set<string>()
  const addFixture = (fixture: Fixture) => {
    keys.add(fixture.key)
    for (const element of fixture.elements ?? []) keys.add(element.key)
    for (const group of fixture.groups) groups.add(group)
  }
  for (const target of selected) {
    if (target.type === 'group') {
      groups.add(target.name)
      for (const fixture of fixtures) if (fixture.groups.includes(target.name)) addFixture(fixture)
    } else if (target.element != null) {
      keys.add(target.element.key)
      keys.add(target.fixture.key)
      for (const group of target.fixture.groups) groups.add(group)
    } else {
      addFixture(target.fixture)
    }
  }
  return fxReaching(effects, [...keys], [...groups]).filter((e) => !e.leaving && (e.programmerOwned || isCueFx(e)))
}

/**
 * The busk view's **Effects** tab, between Spread and Speed (fixture-fx-sheets plan D20,
 * `BuskProgrammer.dc.html`; apply-model plan §4.5). It lists what is running on the selection — pad
 * effects and the operator's own, and a cue's read-only — with the tray's two-line rows, and a row's
 * name opens the live editor under it, as the fixture sheet's tray does.
 *
 * **The editor follows the busk selection** — `SplitFxEditor`, the one the FX popover draws: with
 * part of a Local effect selected it says *Hex 1–2 of Hex 1–4*, holds the edit and offers *Apply to
 * Hex 1–2* (the desk splits those heads off, each where it was) and *Edit all 4*. **+ Effect…** is
 * the one picker's effects arm over the busk pair — a bare library effect pressed into Local, taking
 * those heads off the effects of its family they run.
 *
 * **A pad's effect is edited as the running instance only.** The editor writes the instance
 * (`updateFx`), never the palette; the row and the editor say ***edited*** while the instance
 * differs from the palette's effect — the palette list's summary DTO, which this tab reads once
 * for every row (`differsFromPalette`) — and the editor's footer is **Update palette** and **Reset
 * to palette** in place of Revert. Pressing the pad off and on spawns the palette's settings again.
 * A pad's effect has no stop here: the pad is what releases it, and stopping the instance alone would
 * leave the layer to respawn it at its next recook. The operator's own effect keeps pause and stop.
 */
export function FxSheet({
  projectId,
  selectedTargets,
  families,
}: {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  /** The busk pair's mask, which *+ Effect…* presses under. Null is every attribute. */
  families: AttributeFamily[] | null
}) {
  const { data: all } = useActiveFxQuery()
  const { fixtures: maybeFixtures, fixtureByKey } = useFixtureLookup()
  const fixtures = maybeFixtures ?? EMPTY_FIXTURES
  const selected = useMemo(() => [...selectedTargets.values()], [selectedTargets])
  const effects = useMemo(() => fxOnSelection(all ?? EMPTY, selected, fixtures), [all, selected, fixtures])
  const heads = selectedHeadCount(selected)
  // The busk pair: a group stays one group effect, its distribution intact.
  const pair = useMemo<PressPair>(() => ({ targets: selected.map(lookLayerTarget), families }), [selected, families])
  const fit = useMemo<PaletteFit>(
    () => ({ families: null, emitters: targetEmitters(writeTargetsOf(selected, fixtures)) }),
    [selected, fixtures],
  )
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerMounted, setPickerMounted] = useState(false)
  const addRef = useRef<HTMLButtonElement>(null)

  const { data: templates } = usePaletteListQuery({ projectId })
  const { data: library } = useFxLibraryQuery()
  const master1Uuid = useMaster1Uuid()
  const paletteById = useMemo(() => new Map((templates ?? []).map((t) => [t.id, t])), [templates])
  const edited = (e: ActiveFx): boolean => {
    if (!isPaletteLayerInstance(e)) return false
    const effect = paletteById.get(e.paletteId!)?.fx
    if (effect == null) return false
    const entry = findFxEntry(library, e.fxType)
    return differsFromPalette(draftOf(e), effect, master1Uuid, entry ? defaultParameters(entry) : {}, {
      order: e.order != null,
    })
  }

  const connected = useIsDeskConnected()
  const fxDetail = useFxDetail()
  const [editingId, setEditingId] = useState<number | null>(null)
  // The split's session lives here, not in the editor: a split moves the editor to the copy's row.
  const [splitSession, setSplitSession] = useState<SplitSession>(null)
  const edit = (id: number | null) => {
    setSplitSession(null)
    setEditingId(id)
  }
  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [removeFx] = useRemoveFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const togglePause = (e: ActiveFx) => {
    const request = e.isGroupTarget
      ? (e.isRunning ? pauseGroupFx : resumeGroupFx)({ id: e.id, groupName: e.targetKey })
      : (e.isRunning ? pauseFx : resumeFx)({ id: e.id, fixtureKey: e.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
  }
  /**
   * A group's effect listed because a member is selected, not the group, or a selection's that names
   * something not selected: its stop reaches further.
   */
  const isSelected = (t: CueTarget) =>
    selected.some((s) => (t.type === 'group' ? s.type === 'group' && s.name === t.key : s.type !== 'group' && (s.element?.key ?? s.fixture.key) === t.key))
  const viaGroup = (e: ActiveFx) => {
    const items = selectionItems(e)
    if (items != null) return items.every(isSelected) ? null : fxTargetLabel(e, nameOfTarget)
    return e.isGroupTarget && !selected.some((t) => t.type === 'group' && t.name === e.targetKey) ? e.targetKey : null
  }
  const stop = (e: ActiveFx) => {
    // It stops the effect on every member of the group, not only the selected heads — the fixture
    // sheet's tray asks first for the same case (Chris's call, session 2 review), and so does this.
    const group = viaGroup(e)
    if (group != null && !confirm(`Stop ${e.fxType} on every fixture in ${group}?`)) return
    const request = e.isGroupTarget ? removeGroupFx({ id: e.id, groupName: e.targetKey }) : removeFx({ id: e.id, fixtureKey: e.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
    if (editingId === e.id) edit(null)
  }
  /** A fixture's, a head's or a group's name. */
  function nameOfTarget(t: CueTarget): string {
    if (t.type === 'group') return t.key
    const fixture = fixtureByKey.get(t.key)
    if (fixture) return fixture.name
    for (const f of fixtures) {
      const element = f.elements?.find((el) => el.key === t.key)
      if (element) return `${f.name} ${element.displayName}`
    }
    return t.key
  }
  /** Where an effect runs, by name — a fixture, a head, a group, or a selection's items. */
  const onWhat = (e: ActiveFx): string => {
    if (selectionItems(e) != null) return fxTargetLabel(e, nameOfTarget)
    if (e.isGroupTarget) return e.targetKey
    const fixture = fixtureByKey.get(e.targetKey)
    if (fixture) return fixture.name
    for (const f of fixtures) {
      const element = f.elements?.find((el) => el.key === e.targetKey)
      if (element) return `${f.name} ${element.displayName}`
    }
    return e.targetKey
  }

  return (
    <div data-effects-sheet className="flex min-h-0 flex-col border-l bg-card/40">
      <div className="flex h-9 shrink-0 items-center gap-1.5 border-b px-3">
        <EditorLabel>FX</EditorLabel>
        <span className="min-w-0 truncate text-[11px] text-muted-foreground">
          {selected.length === 0
            ? 'nothing selected'
            : `Running on ${heads} ${heads === 1 ? 'head' : 'heads'} · ${effects.length} FX`}
        </span>
        <Button
          ref={addRef}
          type="button"
          variant="ghost"
          size="sm"
          className="ml-auto h-6 shrink-0 gap-1 px-1.5 text-[11px]"
          aria-expanded={pickerOpen}
          disabled={selected.length === 0}
          title="A library FX on the selection — Local, taking these heads off the FX of its family they run"
          onClick={() => {
            setPickerMounted(true)
            setPickerOpen((wasOpen) => !wasOpen)
          }}
        >
          <Plus className="size-3" />
          FX…
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-1.5" data-effects-list>
        {selected.length === 0 ? (
          <p className="px-2 py-3 text-xs text-muted-foreground">Select heads on the rig to see the FX running on them.</p>
        ) : effects.length === 0 ? (
          <p className="px-2 py-3 text-xs text-muted-foreground">
            Nothing runs on the selection — press an FX pad, or start one with + FX.
          </p>
        ) : null}
        {effects.map((e) => {
          const pad = e.programmerLayerId != null
          const cue = isCueFx(e)
          const isEditing = !cue && editingId === e.id
          const isEdited = edited(e)
          return (
            <Fragment key={e.id}>
              <div
                data-effect-row={e.id}
                className={cn(
                  'flex flex-col gap-0.5 rounded-lg border border-transparent px-2.5 py-1.5',
                  isEditing && 'border-violet-500/55 bg-violet-500/[0.08]',
                )}
              >
                <div className="flex items-center gap-1.5 text-[12.5px] font-medium">
                  <span className={cn('size-[7px] shrink-0 rounded-full', e.isRunning ? 'bg-violet-500' : 'bg-muted-foreground')} />
                  {cue ? (
                    <span className="min-w-0 truncate" title={`${e.fxType} — its cue’s; edit it in the cue`}>
                      {e.fxType}
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="min-w-0 truncate text-left hover:underline"
                      aria-expanded={isEditing}
                      onClick={() => edit(isEditing ? null : e.id)}
                    >
                      {e.fxType}
                    </button>
                  )}
                  <span className="inline-flex h-4 shrink-0 items-center rounded border px-1 text-[9.5px] text-muted-foreground">
                    {fxDetail(e)}
                  </span>
                  {isEdited && <EditedMark paletteName={e.sourceName ?? undefined} />}
                  <span className="flex-1" />
                  {!cue && (
                  <button
                    type="button"
                    aria-label={e.isRunning ? `Pause ${e.fxType}` : `Resume ${e.fxType}`}
                    disabled={!connected}
                    onClick={() => togglePause(e)}
                    className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
                  >
                    {e.isRunning ? <Pause className="size-3" /> : <Play className="size-3" />}
                  </button>
                  )}
                  {!pad && !cue && !isEditing && (
                    <button
                      type="button"
                      aria-label={`Stop ${e.fxType}`}
                      disabled={!connected}
                      onClick={() => stop(e)}
                      className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
                    >
                      <X className="size-3" />
                    </button>
                  )}
                </div>
                <div className="truncate pl-[13px] text-[11px] text-muted-foreground">
                  → {e.attribute} · {viaGroup(e) != null ? `via ${viaGroup(e)}` : onWhat(e)} ·{' '}
                  {cue ? `cue${e.sourceName ? ` · ${e.sourceName}` : ''}` : pad ? `pad${e.sourceName ? ` · ${e.sourceName}` : ''}` : 'programmer'}
                </div>
              </div>
              {isEditing && (
                <div data-effects-editor className="mx-1 mt-1 mb-2 rounded-lg border bg-background p-2.5">
                  <SplitFxEditor
                    effect={e}
                    selection={pair.targets}
                    session={splitSession}
                    onSessionChange={setSplitSession}
                    onClose={() => edit(null)}
                    onFollow={setEditingId}
                    onStop={pad ? undefined : stop}
                  />
                </div>
              )}
            </Fragment>
          )
        })}
      </div>
      {pickerMounted && (
        <ApplyPicker
          projectId={projectId}
          open={pickerOpen}
          onOpenChange={setPickerOpen}
          anchorRef={addRef}
          library="fx"
          family={families?.length === 1 ? families[0] : null}
          pair={pair}
          fit={fit}
          offerNew={false}
          scope="programmer"
        />
      )}
    </div>
  )
}
