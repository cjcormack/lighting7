import { useMemo, useState } from 'react'
import { ChevronDown, ChevronUp, Pause, Play, Plus, X } from 'lucide-react'
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
import { useIsDeskConnected } from '@/store/status'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import { ActiveEffectSheet } from '../busking/ActiveEffectSheet'
import { toEffectContext } from '../busking/buskingTypes'
import { AddEditFxSheet, type SheetMode } from '../fx/AddEditFxSheet'
import { LookTogglePicker } from '../fx/LookTogglePicker'
import { getElementModeLabel } from '../fx/fxConstants'
import { EditorLabel } from '../editor/EditorLabel'
import { fixtureHeadKeys } from './useRelease'
import { useEffectDetail } from './effectLabels'
import { effectsReaching } from './rowSource'

export type FxTrayTarget = { type: 'fixture'; fixture: Fixture } | { type: 'group'; group: GroupSummary }

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
 * The FX tray (D9's first half, issue 6): pinned to the foot of the sheet, **outside** the
 * properties' scroller, so the effects never scroll away after the last property — the Stage view's
 * complaint. Folded it is one 40px row — the count, a chip per running effect (dimmed while paused,
 * *+n* past three) and **+ Effect**; open it lists the programmer rail's two-line rows with pause
 * and stop, up to half the sheet's height, scrolling itself.
 *
 * Until session 3's live editor a row edits through `ActiveEffectSheet`, and **+ Effect** opens
 * `AddEditFxSheet` with `programmerOwned: true` — a sheet's effect is a programmer effect (D10), so
 * it plays over the programmer's values instead of being held back by them.
 */
export function FxTray({ target }: { target: FxTrayTarget }) {
  const { data: all } = useActiveEffectsQuery()
  const effects = useMemo(() => effectsOnTarget(all ?? EMPTY, target), [all, target])
  const connected = useIsDeskConnected()
  const effectDetail = useEffectDetail()
  const [open, setOpen] = useState(false)
  const [adding, setAdding] = useState<SheetMode | undefined>(undefined)
  const [editing, setEditing] = useState<ActiveEffect | null>(null)
  // Memoised on `editing`: `ActiveEffectSheet` re-seeds its draft whenever the context's identity
  // changes, and this tray re-renders on every effect-list update (`FxSheet`'s reason).
  // An effect stopped elsewhere while its sheet is open closes the sheet rather than editing an id
  // the desk no longer has.
  const editingLive = editing != null && effects.some((e) => e.id === editing.id) ? editing : null
  const editingContext = useMemo(() => (editingLive == null ? null : toEffectContext(editingLive)), [editingLive])

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
  }

  const lookTarget =
    target.type === 'fixture'
      ? { targetType: 'fixture' as const, targetKey: target.fixture.key, compatibleLookIds: target.fixture.compatibleLookIds }
      : { targetType: 'group' as const, targetKey: target.group.name, compatibleLookIds: target.group.compatibleLookIds }
  const addTarget = target.type === 'fixture' ? { type: 'fixture' as const, fixture: target.fixture } : { type: 'group' as const, group: target.group }

  const shown = effects.slice(0, FOLDED_CHIPS)
  const more = effects.length - shown.length

  return (
    <div
      data-fx-tray
      data-open={open || undefined}
      className={cn('flex flex-none flex-col border-t bg-muted/40', open && 'max-h-[50%] min-h-0')}
    >
      <div className={cn('flex h-10 shrink-0 items-center gap-1.5 pr-2 pl-3', open && 'border-b')}>
        <EditorLabel>Effects</EditorLabel>
        <span className="font-mono text-[11px] text-muted-foreground">{effects.length > 0 ? effects.length : 'none'}</span>
        {!open && (
          <div className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden">
            {shown.map((e) => (
              <button
                key={e.id}
                type="button"
                title={`${e.effectType} on ${e.propertyName}${via(e) ? ` via ${via(e)}` : ''} — edit`}
                onClick={() => {
                  setOpen(true)
                  setEditing(e)
                }}
                className={cn(
                  'inline-flex h-6 min-w-0 shrink items-center gap-1.5 rounded-full border px-2 text-[11px] whitespace-nowrap',
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
              <button type="button" className="shrink-0 px-1 text-[11px] text-muted-foreground" onClick={() => setOpen(true)}>
                +{more}
              </button>
            )}
          </div>
        )}
        {open && <span className="flex-1" />}
        <LookTogglePicker {...lookTarget} />
        <Button
          variant="outline"
          size="sm"
          className="h-6 shrink-0 gap-1 px-2 text-[11px]"
          disabled={!connected}
          onClick={() => setAdding({ mode: 'add' })}
        >
          <Plus className="size-3" />
          Effect
        </Button>
        <button
          type="button"
          aria-expanded={open}
          aria-label={open ? 'Fold the effects' : 'Open the effects'}
          onClick={() => setOpen((o) => !o)}
          className="grid size-7 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          {open ? <ChevronDown className="size-4" /> : <ChevronUp className="size-4" />}
        </button>
      </div>
      {open && (
        <div className="min-h-0 flex-1 overflow-y-auto p-1.5" data-fx-tray-list>
          {effects.length === 0 && (
            <p className="px-2 py-3 text-xs text-muted-foreground">No effects on this {target.type === 'group' ? 'group' : 'fixture'}.</p>
          )}
          {effects.map((e) => {
            const group = via(e)
            const home = e.programmerOwned ? 'programmer' : e.cueId != null ? 'cue' : e.sourceName ?? null
            return (
              <div
                key={e.id}
                data-effect-row={e.id}
                className={cn(
                  'flex flex-col gap-0.5 rounded-lg border border-transparent px-2.5 py-1.5',
                  editingLive?.id === e.id && 'border-violet-500/55 bg-violet-500/[0.08]',
                )}
              >
                <div className="flex items-center gap-1.5 text-[12.5px] font-medium">
                  <span className={cn('size-[7px] shrink-0 rounded-full', e.isRunning ? 'bg-violet-500' : 'bg-muted-foreground')} />
                  <button type="button" className="min-w-0 truncate text-left hover:underline" disabled={!connected} onClick={() => setEditing(e)}>
                    {e.effectType}
                  </button>
                  <span className="inline-flex h-4 items-center rounded border px-1 text-[9.5px] text-muted-foreground">
                    {effectDetail(e)}
                  </span>
                  <span className="flex-1" />
                  <button
                    type="button"
                    aria-label={e.isRunning ? `Pause ${e.effectType}` : `Resume ${e.effectType}`}
                    disabled={!connected}
                    onClick={() => togglePause(e)}
                    className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
                  >
                    {e.isRunning ? <Pause className="size-3" /> : <Play className="size-3" />}
                  </button>
                  <button
                    type="button"
                    aria-label={`Stop ${e.effectType}`}
                    disabled={!connected}
                    onClick={() => stop(e)}
                    className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
                  >
                    <X className="size-3" />
                  </button>
                </div>
                <div className="pl-[13px] text-[11px] text-muted-foreground">
                  → {e.propertyName}
                  {group && ` · via ${group}`}
                  {e.isGroupTarget && e.elementMode && ` · ${getElementModeLabel(e.elementMode)}`}
                  {home && ` · ${home}`}
                </div>
              </div>
            )
          })}
        </div>
      )}
      <AddEditFxSheet target={addTarget} mode={adding} onClose={() => setAdding(undefined)} programmerOwned />
      <ActiveEffectSheet context={editingContext} onClose={() => setEditing(null)} />
    </div>
  )
}
