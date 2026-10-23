import { createContext, memo, useContext, useLayoutEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react'
import { AudioWaveform } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { lightingApi } from '@/api/lightingApi'
import type { CueTarget } from '@/api/cuesApi'
import { stackEffectSizeOf } from '@/api/stackEffectSizeApi'
import { isHeldBack, isHeldByBlind } from '@/lib/heldBack'
import { useActiveEffectsQuery, type ActiveEffect } from '@/store/fixtureFx'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useStableCallback } from '@/hooks/useStableCallback'
import { effectSpeedLabel } from '../fx/fxConstants'
import { FxEditorPopover } from '../fx/FxEditorPopover'
import { useStopEffect } from '../fx/useEffectControls'
import {
  EMPTY_CELL_EFFECT_INDEX,
  buildCellEffectIndex,
  cellEffectKey,
  createCellEffectStore,
  fixturesOfRows,
  useCellEffects,
  type CellEffectStore,
} from './cellEffects'
import { useFxRinged } from './fxSelection'
import { LENS_CHIP_FACE, lensChipTone } from './lensChip'
import type { Row } from './rowModel'
import type { CellPropertyKey } from './useRowValues'

/**
 * **The FX lens** (tables-colours plan D3): a Values cell an effect drives carries a violet chip
 * with the division, struck through when the effect is held back there, the reason on its tooltip;
 * a click opens the live editor (`FxEditorPopover`, the split edit with it) on that effect. With an
 * FX row selected on the FX tab (`fxSelection.ts`), the cells it paints are ringed.
 *
 * **One index, read per cell.** [CellFxLensHost] subscribes to the effect list once for the grid
 * and builds `cellEffects.ts`'s index over the whole rig, as the desk expands an effect; each cell reads its
 * own effects through the index's selector store, so an `fxChanged` re-renders the indexer and the
 * cells whose effects moved, and never a row.
 *
 * **The chip takes the corner's other two marks over.** The effect badge said *an effect wins
 * here* and the amber dot *your value holds an effect back here*; the chip says both — plain, or
 * struck through with the reason — and names the effect, so a cell the lens covers draws neither.
 * They stay as [fallback] where the lens draws nothing: a cell no indexed effect paints, which
 * provenance can still answer (an effect under a sibling key).
 */

/** Why an effect is held back on a cell — the desk's `heldBackReason` vocabulary. */
export type LensHeldReason = 'PROGRAMMER' | 'STOMP' | 'BLIND'

export const LENS_REASON_WORDS: Readonly<Record<LensHeldReason, string>> = {
  PROGRAMMER: 'your value holds it back',
  STOMP: 'a layer above stomps it',
  BLIND: 'held · Blind',
}

/** What the lens reads of the desk at render — the programmer's keys, Blind and the stacks' FX amount. */
export interface LensDesk {
  blind: boolean
  holds: (headKey: string, propertyName: string) => boolean
  /** The source provenance names on a key, or null. */
  source: (headKey: string, propertyName: string) => string | null
  stackSize: (stackId: number) => number
}

const liveDesk = (): LensDesk => ({
  blind: lightingApi.programmer.isBlind(),
  holds: (head, property) => lightingApi.programmer.getKeyState(head, property).entry != null,
  source: (head, property) => lightingApi.programmer.getKeyState(head, property).provenance?.source ?? null,
  stackSize: (stackId) => stackEffectSizeOf(lightingApi.stackEffectSize.get(), stackId),
})

/**
 * Why [effect] is held back on [heads] — the heads of a cell it paints — or null where it paints.
 *
 * Blind and *your value* are `lib/heldBack.ts`'s rules, the desk's own. A **stomp** the client
 * cannot see directly, so it is read off provenance: the desk names no effect on a key whose every
 * painter it skipped, so a running, weighted effect under a key provenance gives to a cue is one a
 * layer above has stomped. The weight's other factors are checked first, so an effect at no size,
 * an envelope at nothing, a leaving or spent one, or a stack at FX amount 0 is never called stomped.
 */
export function lensHeldReason(effect: ActiveEffect, heads: readonly string[], desk: LensDesk): { reason: LensHeldReason; on: number } | null {
  if (isHeldByBlind(effect, desk.blind)) return { reason: 'BLIND', on: heads.length }
  const mine = heads.filter((head) => isHeldBack(effect, head, desk.holds, desk.blind)).length
  if (mine > 0) return { reason: 'PROGRAMMER', on: mine }
  if (!couldPaint(effect, desk)) return null
  const stomped = heads.filter((head) => desk.source(head, effect.propertyName) === 'CUE').length
  return stomped > 0 ? { reason: 'STOMP', on: stomped } : null
}

function couldPaint(effect: ActiveEffect, desk: LensDesk): boolean {
  if (!effect.isRunning || effect.spent === true || effect.leaving === true) return false
  if ((effect.size ?? 1) <= 0 || effect.intensityMultiplier <= 0) return false
  return effect.cueStackId == null || desk.stackSize(effect.cueStackId) > 0
}

/** The division as the chip says it: a fraction of a beat as its glyph alone (`½`), else the label. */
export function lensDivision(effect: ActiveEffect): string {
  const label = effectSpeedLabel(effect.beatDivision, effect.timingSource, effect.seconds === true) ?? ''
  const fraction = /^([⅛¼½¾⅓⅔]) beat$/.exec(label)
  return fraction != null ? fraction[1] : label
}

interface LensActions {
  store: CellEffectStore
  /** Open the live editor on [effectId] at [anchor], splitting for [selection]'s heads. */
  open: (effectId: number, anchor: HTMLElement, selection: readonly CueTarget[] | undefined) => void
}

const LensActionsContext = createContext<LensActions | null>(null)

/**
 * The grid's lens: the store the cells read, the index built into it from [rows], and the one
 * editor a chip opens. Off ([enabled] false) it provides nothing and mounts nothing that
 * subscribes, so the cells draw their [fallback] — the plain lists, the cue grid, a focused layer.
 * The tree is the same either way, so a scope moving the lens on or off re-renders the cells and
 * never remounts a row under an open editor.
 */
export function CellFxLensHost({ rows, enabled, children }: { rows: readonly Row[]; enabled: boolean; children: ReactNode }) {
  const [store] = useState(createCellEffectStore)
  const [editing, setEditing] = useState<LensEditing | null>(null)
  const anchor = useRef<HTMLElement | null>(null)
  const actions = useMemo<LensActions>(
    () => ({
      store,
      open: (id, at, selection) => {
        anchor.current = at
        setEditing({ id, selection })
      },
    }),
    [store],
  )
  // An editor opened before the lens went off (a layer focused) is not reopened when it comes back.
  if (!enabled && editing != null) setEditing(null)
  return (
    <LensActionsContext.Provider value={enabled ? actions : null}>
      {children}
      {enabled && <LensIndexer rows={rows} store={store} editing={editing} onEditingChange={setEditing} anchor={anchor} />}
    </LensActionsContext.Provider>
  )
}

interface LensEditing {
  id: number
  selection: readonly CueTarget[] | undefined
}

/**
 * The effect list's one subscription for the grid, and the index built from it: a sibling of the
 * grid's rows rather than their parent, so an `fxChanged` re-renders this and the cells whose
 * effects moved, and nothing else.
 */
function LensIndexer({
  rows,
  store,
  editing,
  onEditingChange,
  anchor,
}: {
  rows: readonly Row[]
  store: CellEffectStore
  editing: LensEditing | null
  onEditingChange: (next: LensEditing | null) => void
  anchor: RefObject<HTMLElement | null>
}) {
  const { data: effects } = useActiveEffectsQuery()
  // The whole rig, not the filtered rows: a group's first member and a head filter's halves are
  // read over every member, so a filter hiding some must not move the cells an effect paints.
  const { fixtures: rig } = useFixtureLookup()
  const fixtures = useMemo(() => rig ?? fixturesOfRows(rows), [rig, rows])
  const index = useMemo(() => buildCellEffectIndex(effects, fixtures), [effects, fixtures])
  // Before paint, so a cell never draws a frame of the index before last; emptied when the lens
  // goes off, so a cell that outlives it reads nothing stale.
  useLayoutEffect(() => store.set(index), [index, store])
  useLayoutEffect(() => () => store.set(EMPTY_CELL_EFFECT_INDEX), [store])
  const stop = useStopEffect()
  // The live instance each render, so the editor closes when its effect stops. Not cleared then: a
  // split can take the edited instance off the list a moment before naming the copy to follow.
  const effect = editing != null ? (effects ?? []).find((e) => e.id === editing.id) ?? null : null
  if (effect == null) return null
  return (
    <FxEditorPopover
      effect={effect}
      anchorRef={anchor}
      selection={editing?.selection}
      onClose={() => onEditingChange(null)}
      onFollow={(id) => onEditingChange(editing == null ? null : { ...editing, id })}
      onStop={(stopped) => {
        stop(stopped)
        onEditingChange(null)
      }}
    />
  )
}

/** Read by a cell where no grid lens is mounted, so the cell's hooks run the same either way. */
const NO_EFFECTS_STORE = createCellEffectStore()

/**
 * One cell's lens: the chip and the ring, or [fallback] where the lens is off or no indexed effect
 * paints the cell. One subscription per cell; the chip, mounted only where an effect paints the
 * cell, is memoised on what it draws, and why it is held back is read at the row's render — the
 * row's ownership subscription moves on the keys' entries, provenance and Blind, which is
 * everything [lensHeldReason] reads.
 */
export function CellFxLens({
  keys,
  selection,
  fallback,
}: {
  keys: readonly CellPropertyKey[]
  /** The heads a split edit is for — the cell's batch, read at the click. */
  selection: () => readonly CueTarget[] | undefined
  fallback: ReactNode
}) {
  const actions = useContext(LensActionsContext)
  const effects = useCellEffects(actions?.store ?? NO_EFFECTS_STORE, keys)
  const select = useStableCallback(selection)
  if (actions == null || effects.length === 0) return <>{fallback}</>

  const desk = liveDesk()
  const index = actions.store.get()
  const lines = effects.map((effect) => {
    const heads = keys
      .filter((k) => k.propertyName === effect.propertyName && index.byKey.get(cellEffectKey(k.targetKey, k.propertyName))?.some((e) => e.id === effect.id))
      .map((k) => k.targetKey)
    return { effect, heads, held: lensHeldReason(effect, heads, desk) }
  })
  // The chip names the effect on stage: the first in the desk's order that is not held back.
  const shown = Math.max(0, lines.findIndex((line) => line.held == null))
  const title = [
    ...lines.map(({ effect, heads, held }) =>
      [
        `${effect.effectType} · ${effect.propertyName} · ${lensDivision(effect)}`,
        !effect.isRunning ? 'paused' : null,
        held == null ? null : `${LENS_REASON_WORDS[held.reason]}${heads.length > 1 ? ` on ${held.on} of ${heads.length}` : ''}`,
      ]
        .filter(Boolean)
        .join(' — '),
    ),
    effects.length > 1 ? 'Click to choose one to edit' : 'Click to edit',
  ].join('\n')
  return (
    <LensChip
      actions={actions}
      effects={effects}
      reasons={lines.map((line) => line.held?.reason ?? '').join(',')}
      shown={shown}
      title={title}
      selection={select}
    />
  )
}

const LensChip = memo(function LensChip({
  actions,
  effects,
  reasons,
  shown,
  title,
  selection,
}: {
  actions: LensActions
  effects: readonly ActiveEffect[]
  /** Each effect's held-back reason, comma-joined, empty where it paints — a string, for the memo. */
  reasons: string
  shown: number
  title: string
  selection: () => readonly CueTarget[] | undefined
}) {
  const chipRef = useRef<HTMLButtonElement>(null)
  const ids = useMemo(() => effects.map((e) => e.id), [effects])
  const ringed = useFxRinged(ids)
  const held = reasons.split(',').map((reason) => (reason === '' ? null : (reason as LensHeldReason)))
  const effect = effects[shown]
  const reason = held[shown]
  const more = effects.length - 1

  const chip = (
    <button
      ref={chipRef}
      type="button"
      data-fx-lens={effect.id}
      data-held-reason={reason ?? undefined}
      aria-label={`${effect.effectType}${reason != null ? `, ${LENS_REASON_WORDS[reason]}` : ''} — edit`}
      title={title}
      // Its own gesture: neither the marquee's drag nor the cell's selection.
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => {
        e.stopPropagation()
        if (effects.length === 1) actions.open(effect.id, e.currentTarget, selection())
      }}
      className={cn(
        // The corner the badge took, absolutely placed so the value never moves; 12px tall at the
        // top, clear of a value drawn on the cell's middle line, and opaque where it widens past
        // the 18px gutter. Above an Output cell's jump overlay, and live where the cell is not.
        'pointer-events-auto absolute top-0 right-px z-10',
        LENS_CHIP_FACE,
        lensChipTone(reason != null),
      )}
    >
      <AudioWaveform className="size-2 shrink-0" aria-hidden />
      {lensDivision(effect)}
      {more > 0 && <span className="font-normal">+{more}</span>}
    </button>
  )

  return (
    <>
      {ringed && (
        <span
          data-fx-ring
          aria-hidden
          className="pointer-events-none absolute inset-0.5 z-10 rounded-sm ring-2 ring-violet-500 ring-inset"
        />
      )}
      {effects.length === 1 ? (
        chip
      ) : (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>{chip}</DropdownMenuTrigger>
          <DropdownMenuContent align="end" onCloseAutoFocus={(e) => e.preventDefault()}>
            {effects.map((each, i) => (
              <DropdownMenuItem
                key={each.id}
                onClick={() => {
                  if (chipRef.current != null) actions.open(each.id, chipRef.current, selection())
                }}
              >
                <AudioWaveform className="size-3.5 text-violet-400" />
                <span className={cn('flex-1', held[i] != null && 'line-through text-muted-foreground')}>{each.effectType}</span>
                <span className="font-mono text-[10px] text-muted-foreground">{lensDivision(each)}</span>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </>
  )
})
