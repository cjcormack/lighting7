import { Fragment, useMemo, useState } from 'react'
import { ChevronDown, ChevronLeft, ChevronUp, Pause, Play, Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  useActiveEffectsQuery,
  usePauseFxMutation,
  useRemoveFxMutation,
  useResumeFxMutation,
  type ActiveEffect,
} from '@/store/fixtureFx'
import { usePauseGroupFxMutation, useRemoveGroupFxMutation, useResumeGroupFxMutation } from '@/store/groups'
import { useCurrentProjectQuery } from '@/store/projects'
import { useIsDeskConnected } from '@/store/status'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import { FxEditor } from '../fx/FxEditor'
import { FxPicker, type AuditionedEffect, type FxTarget } from '../fx/FxPicker'
import { LookTogglePicker } from '../fx/LookTogglePicker'
import type { PickerFamily } from '../fx/fxEditorModel'
import { getElementFilterLabel, getElementModeLabel } from '../fx/fxConstants'
import { EditorLabel } from '../editor/EditorLabel'
import { OwnerEditor } from '../stage/OwnerEditor'
import { fixtureHeadKeys } from './useRelease'
import { useCueLabel, useEffectDetail } from './effectLabels'
import { effectsReaching } from './rowSource'
import { FINGER_CHIP_CLASS, useFingerSized } from './sheetContext'

export type FxTrayTarget = { type: 'fixture'; fixture: Fixture } | { type: 'group'; group: GroupSummary }

/**
 * The head strip's pick, which the tray follows (fixture-fx-sheets plan D13, note 5): it lists what
 * reaches the picked heads or members, and **+ Effect** starts there.
 */
export interface TrayPick {
  /** `effectsReaching`'s arguments for the pick — its keys (heads, and their fixture) and their groups. */
  reach: { keys: readonly string[]; groups: readonly string[] }
  /** Where **+ Effect** starts — the picker's target and any element filter — or why it cannot. */
  start: { target: FxTarget; elementFilter?: string } | { reason: string }
  /** An effect on one head or member names it: `on Head 3`. */
  nameOf: (key: string) => string | undefined
  /** The empty list's words — `these 4 heads`. */
  noun: string
}

/**
 * **A cue's effect is read-only in the tray** (Fx board, *Open*; fixture-fx-sheets session 5): it
 * names its cue and has no editor, pause or stop — its home is the cue, whose next GO would put
 * back anything changed here (W5 refuses a cue's instance for the same reason). Session 3 left it
 * editable, as `ActiveEffectSheet` had; a *pad's* instance is edited (D20), being the programmer's.
 * The open row's naming of the cue is the board's *opens it*: a button that opens the cue's **Cue
 * properties** in place, through `OwnerEditor` — the Stage view's *Moves with* path, not a second one.
 */
export function isCueEffect(effect: Pick<ActiveEffect, 'programmerOwned' | 'cueId'>): boolean {
  return !effect.programmerOwned && effect.cueId != null
}

/** How many chips the folded tray draws before *+n*. */
const FOLDED_CHIPS = 3

const EMPTY: readonly ActiveEffect[] = []

/**
 * The effects running on a fixture — on it, on one of its heads, or on a group it is in — or on a
 * group. Group effects reach a fixture as *via <group>*: they are the group's, not this sheet's.
 */
export function effectsOnTarget(effects: readonly ActiveEffect[] | undefined, target: FxTrayTarget): ActiveEffect[] {
  if (!effects) return []
  if (target.type === 'group') return effects.filter((e) => e.isGroupTarget && e.targetKey === target.group.name)
  return effectsReaching(effects, [...fixtureHeadKeys(target.fixture)], target.fixture.groups)
}

/**
 * The FX tray (D9): pinned to the foot of the sheet, **outside** the properties' scroller, so the
 * effects never scroll away after the last property — the Stage view's complaint. It has four
 * states (Fx board): **empty** and **folded** are one 40px row — the count, a chip per running
 * effect (dimmed while paused, *+n* past three) and **+ Effect**; **open** lists the programmer
 * rail's two-line rows with pause and stop, up to half the sheet's height, scrolling itself; and
 * **adding** is `FxPicker` in the open tray.
 *
 * A row's name — or a folded chip — opens `FxEditor` **inline**, under its row (Main board B), and
 * *Edit <name>* in the picker does the same for the effect the picker started. The editor is live:
 * every change lands as it is made, and **Done** comes back to the list. An effect stopped
 * elsewhere while its editor is open closes the editor rather than editing an id the desk no
 * longer has.
 *
 * **+ Effect** starts a programmer effect (D10), so it plays over the programmer's values instead
 * of being held back by them; the picker opens on the family of the row the sheet came from.
 *
 * On the phone host its row is 48px and its chips and **+ Effect** 32px, a finger's size (§4).
 */
export function FxTray({
  target,
  pick,
  initialFamily,
  preferredProperty,
}: {
  target: FxTrayTarget
  /** The strip's pick; absent, the tray is the whole target's. */
  pick?: TrayPick
  /** The family of the row open on the sheet, which the picker opens on. */
  initialFamily?: PickerFamily | null
  /** That row's property, preferred over an effect's first compatible one. */
  preferredProperty?: string | null
}) {
  const { data: all } = useActiveEffectsQuery()
  const effects = useMemo(
    () => (pick != null ? effectsReaching(all ?? EMPTY, pick.reach.keys, pick.reach.groups) : effectsOnTarget(all ?? EMPTY, target)),
    [all, target, pick],
  )
  const connected = useIsDeskConnected()
  const finger = useFingerSized()
  const effectDetail = useEffectDetail()
  const cueLabel = useCueLabel()
  const [open, setOpen] = useState(false)
  const [adding, setAdding] = useState(false)
  // The auditioned effect belongs to the start it was made on: a new pick is a new picker session,
  // so a tap there starts afresh rather than swapping an effect on the heads picked before.
  const start = pick?.start
  const startKey =
    start == null ? 'whole' : 'reason' in start ? null : `${start.target.type === 'group' ? start.target.group.name : start.target.fixture.key}\u0000${start.elementFilter ?? ''}`
  const [audition, setAudition] = useState<{ startKey: string | null; effect: AuditionedEffect } | null>(null)
  const auditioned = audition != null && audition.startKey === startKey ? audition.effect : null
  const setAuditioned = (effect: AuditionedEffect | null) => setAudition(effect == null ? null : { startKey, effect })
  const [editingId, setEditingId] = useState<number | null>(null)
  // The cue whose properties are open over the sheet. Held here, not on its row, so the sheet stays
  // open if the cue's effect stops under it.
  const [openCueId, setOpenCueId] = useState<number | null>(null)
  const [cueSheetMounted, setCueSheetMounted] = useState(false)
  const openCue = (cueId: number) => {
    setCueSheetMounted(true)
    setOpenCueId(cueId)
  }
  const editingLive = editingId != null ? effects.find((e) => e.id === editingId) ?? null : null

  const edit = (e: ActiveEffect) => {
    setOpen(true)
    setAdding(false)
    setEditingId(e.id)
  }
  const startAdding = () => {
    setOpen(true)
    setEditingId(null)
    setAuditioned(null)
    setAdding(true)
  }

  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [removeFx] = useRemoveFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const ownGroup = target.type === 'group' ? target.group.name : null
  const via = (e: ActiveEffect) => (e.isGroupTarget && e.targetKey !== ownGroup ? e.targetKey : null)

  const togglePause = (e: ActiveEffect) => {
    const request = e.isGroupTarget
      ? (e.isRunning ? pauseGroupFx : resumeGroupFx)({ id: e.id, groupName: e.targetKey })
      : (e.isRunning ? pauseFx : resumeFx)({ id: e.id, fixtureKey: e.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
  }
  const stop = (e: ActiveEffect) => {
    // A group's effect reaching this fixture is the group's: stopping it here stops it on every
    // member, so it asks first (Chris's call, session 2 review). The fixture's own stop does not.
    const group = via(e)
    if (group && !confirm(`Stop ${e.effectType} on every fixture in ${group}?`)) return
    const request = e.isGroupTarget
      ? removeGroupFx({ id: e.id, groupName: e.targetKey })
      : removeFx({ id: e.id, fixtureKey: e.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
    if (editingId === e.id) setEditingId(null)
  }

  // The Look picker follows the pick by + Effect's rule (Chris's call, session 4): the whole target
  // on *All*, the one head or member when one is picked — a Look press takes a head's key as a target
  // — and absent for any other pick, where a press would land on more than the rows below edit. A
  // filtered start (*first half*) has no one target for a Look either.
  const lookFrom: FxTarget | null =
    start == null
      ? target.type === 'fixture'
        ? { type: 'fixture', fixture: target.fixture }
        : { type: 'group', group: target.group }
      : 'reason' in start || start.elementFilter != null
        ? null
        : start.target
  const lookTarget =
    lookFrom == null
      ? null
      : lookFrom.type === 'fixture'
        ? { targetType: 'fixture' as const, targetKey: lookFrom.fixture.key, compatibleLookIds: lookFrom.fixture.compatibleLookIds }
        : { targetType: 'group' as const, targetKey: lookFrom.group.name, compatibleLookIds: lookFrom.group.compatibleLookIds }
  const addTarget: FxTarget | null =
    start == null
      ? target.type === 'fixture'
        ? { type: 'fixture', fixture: target.fixture }
        : { type: 'group', group: target.group }
      : 'reason' in start
        ? null
        : start.target
  const cannotStart = start != null && 'reason' in start ? start.reason : null
  const ownKey = target.type === 'fixture' ? target.fixture.key : null
  // An effect on one head or one member, rather than on the sheet's own target, says which.
  const onWhom = (e: ActiveEffect) => (e.isGroupTarget || e.targetKey === ownKey ? null : (pick?.nameOf(e.targetKey) ?? null))

  const shown = effects.slice(0, FOLDED_CHIPS)
  const more = effects.length - shown.length

  return (
    <div
      data-fx-tray
      data-open={open || undefined}
      className={cn('flex flex-none flex-col border-t bg-muted/40', open && 'max-h-[50%] min-h-0')}
    >
      <div className={cn('flex h-10 shrink-0 items-center gap-1.5 pr-2 pl-3', finger && 'h-12', open && 'border-b')}>
        {open && adding ? (
          <>
            <button
              type="button"
              aria-label="Back to the effects"
              onClick={() => setAdding(false)}
              className="-ml-1.5 grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <ChevronLeft className="size-4" />
            </button>
            <EditorLabel>Add an effect</EditorLabel>
            <span className="flex-1" />
          </>
        ) : (
          <>
            <EditorLabel>Effects</EditorLabel>
            <span className="font-mono text-[11px] text-muted-foreground">{effects.length > 0 ? effects.length : 'none'}</span>
          </>
        )}
        {!open && (
          <div className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden">
            {shown.map((e) => (
              <button
                key={e.id}
                type="button"
                title={`${e.effectType} on ${e.propertyName}${via(e) ? ` via ${via(e)}` : ''}${isCueEffect(e) ? ' — the cue’s' : ' — edit'}`}
                onClick={() => (isCueEffect(e) ? setOpen(true) : edit(e))}
                className={cn(
                  'inline-flex h-6 min-w-0 shrink items-center gap-1.5 rounded-full border px-2 text-[11px] whitespace-nowrap',
                  finger && cn(FINGER_CHIP_CLASS, 'px-2.5 text-xs'),
                  e.isRunning
                    ? 'border-violet-500/50 bg-violet-500/10'
                    : 'border-border text-muted-foreground',
                )}
              >
                <span className={cn('size-[7px] shrink-0 rounded-full', e.isRunning ? 'bg-violet-500' : 'bg-muted-foreground')} />
                <span className="truncate">{e.effectType}</span>
                <span className="truncate text-muted-foreground">· {e.propertyName}</span>
              </button>
            ))}
            {more > 0 && (
              <button
                type="button"
                className={cn('shrink-0 px-1 text-[11px] text-muted-foreground', finger && cn(FINGER_CHIP_CLASS, 'px-2 text-xs'))}
                onClick={() => setOpen(true)}
              >
                +{more}
              </button>
            )}
          </div>
        )}
        {open && !adding && <span className="flex-1" />}
        {!(open && adding) && (
          <>
            {lookTarget != null && <LookTogglePicker key={lookTarget.targetKey} {...lookTarget} />}
            <Button
              variant="outline"
              size="sm"
              className={cn('h-6 shrink-0 gap-1 px-2 text-[11px]', finger && cn(FINGER_CHIP_CLASS, 'px-2.5 text-xs'))}
              disabled={!connected || cannotStart != null}
              title={cannotStart ?? undefined}
              onClick={startAdding}
            >
              <Plus className="size-3" />
              Effect
            </Button>
          </>
        )}
        <button
          type="button"
          aria-expanded={open}
          aria-label={open ? 'Fold the effects' : 'Open the effects'}
          onClick={() => setOpen((o) => !o)}
          className={cn(
            'grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground',
            finger && 'size-9',
          )}
        >
          {open ? <ChevronDown className="size-4" /> : <ChevronUp className="size-4" />}
        </button>
      </div>
      {open && adding && cannotStart != null && (
        // The pick moved under the picker to one no single effect can start on: say why, in place.
        <p className="px-3 py-3 text-xs text-muted-foreground" data-fx-tray-list>
          {cannotStart}.
        </p>
      )}
      {open && adding && addTarget != null && (
        <div className="min-h-0 flex-1 overflow-y-auto p-2.5" data-fx-tray-list>
          <FxPicker
            key={startKey ?? ''}
            target={addTarget}
            elementFilter={start != null && !('reason' in start) ? start.elementFilter : undefined}
            initialFamily={initialFamily}
            preferredProperty={preferredProperty}
            current={auditioned}
            onCurrent={setAuditioned}
            onEdit={() => {
              if (auditioned == null) return
              setAdding(false)
              setEditingId(auditioned.effectId)
            }}
          />
        </div>
      )}
      {open && !adding && (
        <div className="min-h-0 flex-1 overflow-y-auto p-1.5" data-fx-tray-list>
          {effects.length === 0 && (
            <p className="px-2 py-3 text-xs text-muted-foreground">
              {pick != null ? `No effects reach ${pick.noun}.` : `No effects on this ${target.type === 'group' ? 'group' : 'fixture'}.`}
            </p>
          )}
          {effects.map((e) => {
            const group = via(e)
            const cue = isCueEffect(e)
            // A button only once the cue is named: its name comes from the current project's stack
            // list, so a named cue means the project the cue sheet opens in has loaded too.
            const cueId = cue ? e.cueId : null
            const cueName = cueId != null ? cueLabel(cueId) : undefined
            const home = e.programmerOwned
              ? 'programmer'
              : e.cueId != null
                ? `on ${cueLabel(e.cueId) ?? 'a cue'}`
                : (e.sourceName ?? null)
            const isEditing = !cue && editingLive?.id === e.id
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
                      <span className="min-w-0 truncate" data-read-only title="The cue’s effect — edit it in the cue">
                        {e.effectType}
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="min-w-0 truncate text-left hover:underline"
                        aria-expanded={isEditing}
                        onClick={() => setEditingId(isEditing ? null : e.id)}
                      >
                        {e.effectType}
                      </button>
                    )}
                    <span className="inline-flex h-4 items-center rounded border px-1 text-[9.5px] text-muted-foreground">
                      {effectDetail(e)}
                    </span>
                    <span className="flex-1" />
                    {!cue && (
                      <button
                        type="button"
                        aria-label={e.isRunning ? `Pause ${e.effectType}` : `Resume ${e.effectType}`}
                        disabled={!connected}
                        onClick={() => togglePause(e)}
                        className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
                      >
                        {e.isRunning ? <Pause className="size-3" /> : <Play className="size-3" />}
                      </button>
                    )}
                    {!isEditing && !cue && (
                      <button
                        type="button"
                        aria-label={`Stop ${e.effectType}`}
                        disabled={!connected}
                        onClick={() => stop(e)}
                        className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
                      >
                        <X className="size-3" />
                      </button>
                    )}
                  </div>
                  <div className="pl-[13px] text-[11px] text-muted-foreground">
                    → {e.propertyName}
                    {onWhom(e) && ` · on ${onWhom(e)}`}
                    {e.elementFilter && e.elementFilter !== 'ALL' && ` · ${getElementFilterLabel(e.elementFilter).toLowerCase()} heads`}
                    {group && ` · via ${group}`}
                    {e.isGroupTarget && e.elementMode && ` · ${getElementModeLabel(e.elementMode)}`}
                    {cueId != null && cueName != null ? (
                      <>
                        {' · '}
                        <button
                          type="button"
                          data-open-cue={cueId}
                          // Dotted, since a touch screen has no hover to say it is a press; on the
                          // phone host a finger's height of hit area, as inline padding so the line
                          // itself does not grow.
                          className={cn('underline decoration-dotted underline-offset-2 hover:text-foreground hover:decoration-solid', finger && 'py-2.5')}
                          title={`The cue’s effect — open ${cueName}’s properties to change it`}
                          onClick={() => openCue(cueId)}
                        >
                          {home}
                        </button>
                      </>
                    ) : (
                      home && ` · ${home}`
                    )}
                  </div>
                </div>
                {isEditing && (
                  <div data-fx-tray-editor className="mx-1 mt-1 mb-2 rounded-lg border bg-background p-2.5">
                    <FxEditor
                      key={`${e.id}:${e.effectType}`}
                      effect={e}
                      onDone={() => setEditingId(null)}
                      onStop={() => stop(e)}
                    />
                  </div>
                )}
              </Fragment>
            )
          })}
        </div>
      )}
      {cueSheetMounted && <CueSheet cueId={openCueId} onClose={() => setOpenCueId(null)} />}
    </div>
  )
}

/**
 * A cue's **Cue properties** over the sheet, mounted the first time a cue row is pressed and kept so
 * it can animate closed — a card page holds a tray per card, and each would otherwise mount the
 * owner editor's three closed sheets. A cue effect is the current project's, so that is the project.
 * Rendered inside the sheet's own tree, so Radix layers it over the host — the pop-up, the docked
 * panel, either phone sheet or a card — and Escape or a click outside closes it alone.
 */
function CueSheet({ cueId, onClose }: { cueId: number | null; onClose: () => void }) {
  const { data: project } = useCurrentProjectQuery()
  if (project == null) return null
  return <OwnerEditor projectId={project.id} entry={cueId == null ? null : { kind: 'cue', id: cueId }} onClose={onClose} />
}
