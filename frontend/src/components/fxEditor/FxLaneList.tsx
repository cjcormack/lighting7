import { useState, type DragEvent, type KeyboardEvent } from 'react'
import { ChevronDown, ChevronRight, GripVertical, Headphones, Pause, Play, Plus, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { ActiveFx, FxLibraryEntry } from '@/store/fixtureFx'
import { moveLocalFx } from '@/store/fixtureFx'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { findFxEntry } from '@/components/busking/buskingTypes'
import { HeldBlindMark } from '@/components/fx/HeldBlindMark'
import { isHeldByBlind } from '@/lib/heldBack'
import { newFxDirection } from '@/lib/fxTiming'
import { palettePropertyFor } from '@/lib/paletteIntent'
import { LANE_ATTRIBUTE, coverageLabel, localMovesFor, type FxCard, type FxLane } from './fxEditorList'
import { underneathLabel, useUnderneath } from './useUnderneath'

/** A blend in its family's words (FX editor plan §4.3): *Within* a level, *Around* a position. */
export function blendWords(blendMode: string, family: AttributeFamily): string {
  switch (blendMode) {
    case 'OVERRIDE':
      return 'Replace'
    case 'MULTIPLY':
      return family === 'INTENSITY' ? 'Within' : 'Multiply'
    case 'ADDITIVE':
      return family === 'POSITION' ? 'Around' : 'Add'
    case 'MAX':
      return 'Max'
    case 'MIN':
      return 'Min'
    default:
      return blendMode.toLowerCase()
  }
}

const DIRECTION_GLYPH = { FORWARD: '→', BACKWARD: '←', BOUNCE: '↔' } as const
const DIRECTION_WORD = { FORWARD: 'Forward', BACKWARD: 'Backward', BOUNCE: 'Bounce' } as const

const HELD_BACK_WORDS: Readonly<Record<string, string>> = {
  STOMP: 'a layer above stomps it',
  PROGRAMMER: 'your value holds it back',
  BLIND: 'held · Blind',
}

export interface LaneListProps {
  lanes: readonly FxLane[]
  library: readonly FxLibraryEntry[] | undefined
  pickedId: number | null
  soloId: number | null
  folded: readonly AttributeFamily[]
  blind: boolean
  connected: boolean
  /** `1 bar · M2`. */
  detail: (effect: ActiveFx) => string
  cueLabel: (cueId: number) => string | undefined
  layerName: (layerId: number) => string | undefined
  heldBack: (fxId: number) => ReadonlySet<string>
  /** The first selected head that carries each lane's attribute, and that attribute: what *Underneath* asks. */
  underneathOf: (lane: FxLane) => { head: string; attribute: string } | null
  /** The lane whose + FX has the picker open. */
  adding: AttributeFamily | null
  addDisabled: string | null
  onPick: (effect: ActiveFx) => void
  onOpenCue: (cueId: number) => void
  onSolo: (fxId: number | null) => void
  onTogglePause: (effect: ActiveFx) => void
  onStop: (effect: ActiveFx) => void
  onToggleFold: (family: AttributeFamily) => void
  onAdd: (family: AttributeFamily) => void
}

/**
 * **The list** (FX editor plan D3, §4.3): a lane per family, its FX as cards top first in the
 * desk's apply order, then *Underneath*, then *+ FX*. A Local card drags within its lane's Local
 * cards — the drop is one `programmer.moveFx` (`localMovesFor`), the desk re-ranks and its
 * `fxChanged` redraws the list — and its grip moves it with ↑ / ↓. A layer's card names its layer and
 * has no grip; a cue's names its cue and opens the cue editor. Solo is a mark here: the graph is
 * what draws it (D14).
 */
export function FxLaneList(props: LaneListProps) {
  return (
    <div data-fx-lanes className="flex flex-col gap-2">
      {props.lanes.map((lane) => (
        <LaneView key={lane.family} lane={lane} {...props} />
      ))}
    </div>
  )
}

function LaneView({ lane, ...props }: LaneListProps & { lane: FxLane }) {
  const folded = props.folded.includes(lane.family)
  const label = FAMILY_LABELS[lane.family].singular
  const localIds = lane.cards.filter((c) => c.kind === 'local').map((c) => c.effect.id)
  const [dragging, setDragging] = useState<number | null>(null)

  const move = (fxId: number, toIndex: number) => {
    for (const [moved, above] of localMovesFor(localIds, fxId, toIndex)) moveLocalFx(moved, above)
  }

  return (
    <section data-fx-lane={lane.family} aria-label={`${label} FX`} className="rounded-md border bg-card/40">
      <button
        type="button"
        aria-expanded={!folded}
        onClick={() => props.onToggleFold(lane.family)}
        className="flex h-7 w-full items-center gap-1 px-2 text-left text-[11px] font-semibold tracking-wide text-muted-foreground uppercase hover:text-foreground"
      >
        {folded ? <ChevronRight className="size-3.5" /> : <ChevronDown className="size-3.5" />}
        <span className="flex-1">{label}</span>
        <span className="font-normal tabular-nums normal-case">{lane.cards.length > 0 ? lane.cards.length : ''}</span>
      </button>
      {!folded && (
        <div className="flex flex-col gap-1 px-1.5 pb-1.5">
          {lane.cards.map((card) => (
            <CardView
              key={card.effect.id}
              card={card}
              localIndex={localIds.indexOf(card.effect.id)}
              localCount={localIds.length}
              dragging={dragging}
              onDragStart={() => setDragging(card.effect.id)}
              onDragEnd={() => setDragging(null)}
              onDropAt={(toIndex) => {
                if (dragging != null) move(dragging, toIndex)
                setDragging(null)
              }}
              onMove={(toIndex) => move(card.effect.id, toIndex)}
              {...props}
            />
          ))}
          <Underneath lane={lane} {...props} />
          <span className="flex" title={props.addDisabled ?? undefined}>
            <button
              type="button"
              data-lane-add={lane.family}
              disabled={props.addDisabled != null || !props.connected}
              aria-pressed={props.adding === lane.family}
              onClick={() => props.onAdd(lane.family)}
              className={cn(
                'flex h-7 flex-1 items-center gap-1 rounded-md border border-dashed px-2 text-[11.5px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50',
                props.adding === lane.family && 'border-violet-500/60 text-foreground',
              )}
            >
              <Plus className="size-3.5" />
              FX
            </button>
          </span>
        </div>
      )}
    </section>
  )
}

function Underneath({ lane, underneathOf, cueLabel }: LaneListProps & { lane: FxLane }) {
  const at = underneathOf(lane)
  const layer = useUnderneath(at?.head ?? null, at?.attribute ?? null)
  const words = underneathLabel(layer, cueLabel)
  return (
    <div data-underneath className="truncate px-2 py-0.5 text-[11px] text-muted-foreground" title="What this lane's FX are folded over">
      Underneath · {words ?? '—'}
    </div>
  )
}

function CardView({
  card,
  localIndex,
  localCount,
  dragging,
  onDragStart,
  onDragEnd,
  onDropAt,
  onMove,
  library,
  pickedId,
  soloId,
  blind,
  connected,
  detail,
  cueLabel,
  layerName,
  heldBack,
  onPick,
  onOpenCue,
  onSolo,
  onTogglePause,
  onStop,
}: LaneListProps & {
  card: FxCard
  localIndex: number
  localCount: number
  dragging: number | null
  onDragStart: () => void
  onDragEnd: () => void
  onDropAt: (toIndex: number) => void
  onMove: (toIndex: number) => void
}) {
  const { effect, kind, family } = card
  const entry = findFxEntry(library, effect.fxType)
  const name = entry?.name ?? effect.fxType
  const local = kind === 'local'
  const cueName = effect.cueId != null ? (cueLabel(effect.cueId) ?? 'its cue') : null
  // Named where the lane's family has several, in words — *UV*, *Zoom*.
  const attribute =
    LANE_ATTRIBUTE[family] !== effect.propertyName ? (palettePropertyFor(effect.propertyName)?.label ?? effect.propertyName) : null
  const amount = Math.round((effect.amount ?? 1) * 100)
  // Only where it is not the FX type's own: the sweeps bounce by default.
  const ownDirection = effect.direction ?? 'FORWARD'
  const direction = ownDirection !== newFxDirection(entry) ? ownDirection : null
  const coverage = coverageLabel(card)
  const heldByBlind = isHeldByBlind(effect, blind)
  const reasons = [...heldBack(effect.id)].filter((r) => r !== 'BLIND' || !heldByBlind)
  const picked = pickedId === effect.id
  const solo = soloId === effect.id
  // A Local card is a drop target for the lane's other Local cards: the dragged one takes its place.
  const droppable = local && dragging != null && dragging !== effect.id

  const press = () => (kind === 'cue' && effect.cueId != null ? onOpenCue(effect.cueId) : onPick(effect))
  const gripKeys = (e: KeyboardEvent) => {
    if (e.key === 'ArrowUp' && localIndex > 0) {
      e.preventDefault()
      onMove(localIndex - 1)
    } else if (e.key === 'ArrowDown' && localIndex < localCount - 1) {
      e.preventDefault()
      onMove(localIndex + 1)
    }
  }
  const allowDrop = (e: DragEvent) => {
    if (!droppable) return
    e.preventDefault()
  }

  return (
    <div
      data-fx-card={effect.id}
      data-card-kind={kind}
      data-picked={picked || undefined}
      draggable={local && connected}
      onDragStart={(e) => {
        if (!local) return
        e.dataTransfer?.setData('text/plain', String(effect.id))
        onDragStart()
      }}
      onDragEnd={onDragEnd}
      onDragOver={allowDrop}
      onDrop={(e) => {
        if (!droppable) return
        e.preventDefault()
        onDropAt(localIndex)
      }}
      className={cn(
        'group flex min-w-0 items-start gap-1 rounded-md border bg-background px-1.5 py-1 text-xs',
        picked && 'border-violet-500/70 bg-violet-500/[0.06]',
        droppable && 'border-dashed',
        !effect.isRunning && 'opacity-70',
      )}
    >
      {local ? (
        <button
          type="button"
          aria-label={`Move ${name} — ↑ or ↓`}
          title="Drag, or ↑ / ↓, to change where it applies in the lane"
          onKeyDown={gripKeys}
          className="mt-0.5 cursor-grab text-muted-foreground hover:text-foreground"
        >
          <GripVertical className="size-3.5" />
        </button>
      ) : (
        <span className="w-3.5 shrink-0" />
      )}
      <button type="button" onClick={press} className="flex min-w-0 flex-1 flex-col items-start text-left" aria-label={kind === 'cue' ? `${name} on ${cueName} — open the cue` : `Edit ${name}`}>
        <span className="flex w-full min-w-0 items-center gap-1">
          <span className="truncate font-medium">{name}</span>
          {attribute != null && <span className="shrink-0 text-[10px] text-muted-foreground">{attribute}</span>}
          {solo && (
            <span data-solo-mark className="shrink-0 rounded border border-sky-500/60 px-1 text-[9.5px] font-semibold text-sky-600 dark:text-sky-300">
              solo
            </span>
          )}
        </span>
        <span className="flex w-full min-w-0 flex-wrap items-center gap-x-1.5 text-[10.5px] text-muted-foreground">
          <span className="font-mono tabular-nums">{detail(effect)}</span>
          <span>{blendWords(effect.blendMode, family)}</span>
          <span className="inline-flex items-center gap-1" title={`Amount ${amount} %`}>
            <span className="relative h-1 w-8 rounded bg-muted">
              <span className="absolute inset-y-0 left-0 rounded bg-violet-500" style={{ width: `${amount}%` }} />
            </span>
          </span>
          {direction != null && <span title={DIRECTION_WORD[direction]}>{DIRECTION_GLYPH[direction]}</span>}
          {effect.runs != null && <span className="tabular-nums">{effect.runs}×</span>}
          {coverage != null && (
            <span data-coverage title="The selected heads it reaches">
              {coverage}
            </span>
          )}
        </span>
        {kind === 'layer' && effect.programmerLayerId != null && (
          <span className="text-[10.5px] text-muted-foreground">{layerName(effect.programmerLayerId) ?? effect.sourceName ?? 'A layer'} · pressed</span>
        )}
        {kind === 'cue' && <span className="text-[10.5px] text-muted-foreground underline decoration-dotted">on {cueName}</span>}
        {(heldByBlind || reasons.length > 0) && (
          <span className="flex flex-wrap items-center gap-1">
            {heldByBlind && <HeldBlindMark />}
            {reasons.map((r) => (
              <span key={r} data-held-back={r} className="rounded border border-dashed px-1 text-[9.5px] text-muted-foreground" title={HELD_BACK_WORDS[r] ?? r}>
                held back
              </span>
            ))}
          </span>
        )}
      </button>
      <span className="flex shrink-0 items-center">
        <button
          type="button"
          aria-label={solo ? `Unsolo ${name}` : `Solo ${name}`}
          aria-pressed={solo}
          title="Solo — the graph draws the lane as this FX alone; the stage does not change"
          onClick={() => onSolo(solo ? null : effect.id)}
          className={cn('grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent', solo && 'text-sky-600 dark:text-sky-300')}
        >
          <Headphones className="size-3.5" />
        </button>
        {kind !== 'cue' && (
          <button
            type="button"
            aria-label={effect.isRunning ? `Pause ${name}` : `Resume ${name}`}
            disabled={!connected}
            onClick={() => onTogglePause(effect)}
            className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent disabled:opacity-50"
          >
            {effect.isRunning ? <Pause className="size-3.5" /> : <Play className="size-3.5" />}
          </button>
        )}
        {(kind === 'local' || kind === 'other') && (
          <button
            type="button"
            aria-label={`Stop ${name}`}
            disabled={!connected}
            onClick={() => onStop(effect)}
            className="grid size-6 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
          >
            <X className="size-3.5" />
          </button>
        )}
      </span>
    </div>
  )
}
