import { useRef } from 'react'
import { useDraggable, useDroppable } from '@dnd-kit/core'
import { AudioWaveform, Eye, Hand, Layers, ListChecks, Undo2, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import { dispatchSyntheticContextMenu, useLongPress } from '@/hooks/useLongPress'
import { registerDragOverlay } from '@/components/dnd/dragOverlayRegistry'
import type { BuskPad } from '@/api/buskApi'
import type { CueRing } from '@/api/cueHoldsApi'
import { buskPadId, type PadAddress } from '@/lib/buskLayout'
import { useBuskEdit } from './BuskEditProvider'
import type { FxPresence } from './buskingTypes'
import { FxPadDetail } from './FxPadDetail'
import {
  FX_GLYPH_CLASS,
  PAD_FACE_SHELL,
  PAD_SHELL,
  padFaceOf,
  padPresenceClass,
  type PadFace,
} from './padFace'
import { buskDragData, DROP_DEPTH, type BuskDropData, type BuskPadDragData } from './buskDnd'
import { tapVerb } from '@/components/press/PressGestures'
import type { PressInto } from '@/hooks/usePress'

/** A busk pad presses the programmer: it has no focused Look to write into. */
const PROGRAMMER_INTO: PressInto = { kind: 'programmer' }

/**
 * One pad, whatever it holds.
 *
 * There were two pad components before the layout: the library pool's three-state toggle and the
 * pinned-cue grid's two-state one. A bank mixes all three kinds in one grid, so they converge here
 * — one shell, one geometry, one edit affordance, and two *faces* inside it, because a cue really
 * does say something different (a number, a name and a stack, lit green when it is on stage) from a
 * template or a Look (a name, a detail line, and a three-rung presence ring in the accent colour).
 */

function LookFace({
  face,
  pad,
  presence,
  live,
}: {
  face: PadFace
  pad: BuskPad
  presence: FxPresence
  live: boolean
}) {
  return (
    <>
      <span className="flex items-center gap-1.5">
        {/* An effect template has no value to preview, so the FX glyph stands where a swatch would
            — the same substitution the template sheet's Value column and `PaletteStrip` make. */}
        {face.isFx && <AudioWaveform className={FX_GLYPH_CLASS} />}
        {face.swatch && (
          <span
            aria-hidden
            className="size-3 shrink-0 rounded shadow-[inset_0_0_0_1px_rgba(255,255,255,0.15)]"
            style={{ background: face.swatch }}
          />
        )}
        <span
          className={cn(
            'text-sm font-medium leading-tight',
            presence !== 'none' ? 'text-primary' : 'text-foreground',
          )}
        >
          {face.name}
        </span>
      </span>
      <span className="mt-0.5 line-clamp-1 text-[10px] leading-tight text-muted-foreground">
        {live && pad.kind === 'PALETTE' && pad.palette?.kind === 'fx' ? (
          <FxPadDetail palette={pad.palette} />
        ) : (
          face.detail
        )}
      </span>
    </>
  )
}

function CueFace({ face }: { face: PadFace }) {
  return (
    <>
      {/* No dimming for an auto number: `BuskCue` does not carry `cueNumberAuto`, and a pad names
          one cue rather than a run of them, so the provisional/typed distinction has nothing to
          contrast against here. */}
      <span className="font-mono text-[13px] font-bold tabular-nums">{face.cueNumber ?? '—'}</span>
      <span className="line-clamp-1 text-[11px] text-muted-foreground">{face.name}</span>
      <span className="line-clamp-1 text-[9px] text-muted-foreground/70">{face.stackName}</span>
    </>
  )
}

const CUE_SHELL = 'flex-col items-start gap-0.5 px-2.5 py-2 text-left'

export interface BuskPadButtonProps {
  pad: BuskPad
  at: PadAddress
  /** For a template or Look pad. A cue pad reads {@link cueRing} instead. */
  presence: FxPresence
  /**
   * For a cue pad (D11): `loose` — a press holds it, drawn solid, and the next press lets it go;
   * `stack` — live only because a stack holds it, drawn as an outline, and a press takes it loose;
   * null — off.
   */
  cueRing: CueRing
  editing: boolean
  onPress: () => void
  /** ⌥click and the menu's *Apply as values* — a palette or Look pad's literals, with no layer. */
  onApplyValues?: () => void
  /** False when the busk pair names no target, which the menu's first verb has to say. */
  hasSelection?: boolean
  onRemove: () => void
  /** The hold menu's *View* — the library is where a record is actually edited. */
  onInspect: () => void
  /** The hold menu's *Pick up* — this record into the desk's hand (multi-screen plan §3.5). */
  onPickUp: () => void
}

export function BuskPadButton({
  pad,
  at,
  presence,
  cueRing,
  editing,
  onPress,
  onApplyValues,
  hasSelection = true,
  onRemove,
  onInspect,
  onPickUp,
}: BuskPadButtonProps) {
  const face = padFaceOf(pad)
  const isCue = face.kind === 'CUE'
  const id = buskPadId(at)
  const { source, foreign } = useBuskEdit()
  const draggingBank = source?.type === 'busk-bank'

  const { attributes, listeners, setNodeRef: setDragRef, isDragging } = useDraggable({
    id,
    data: { type: 'busk-pad', at, face } satisfies BuskPadDragData,
    disabled: !editing,
  })
  // Not a droppable while a bank is lifted — a bank lands on the bank zones, and a pad that stayed
  // droppable would win the collision (it is the deepest thing there) for a drop that then refuses.
  const { setNodeRef: setDropRef } = useDroppable({
    id,
    data: { type: 'busk-drop', target: { kind: 'pad', at }, depth: DROP_DEPTH.pad } satisfies BuskDropData,
    disabled: !editing || draggingBank || foreign,
  })
  // One node carries the drag ref, the drop ref and the context-menu trigger. Merging them here —
  // rather than wrapping `body` in a `<div ref={triggerRef}>` — is what keeps the pad itself the
  // direct child of the bank's CSS Grid: an extra wrapper becomes the grid item and takes the row's
  // `align-items: stretch` for itself, leaving the pad's visible shell sized to its own content in
  // a taller row. `CueSlotCell` merges its three refs onto one node for the same reason.
  const triggerRef = useRef<HTMLElement | null>(null)
  const setRef = (node: HTMLElement | null) => {
    setDragRef(node)
    setDropRef(node)
    triggerRef.current = node
  }

  /**
   * The hold opens the pad's **menu**, where it used to navigate straight to the library.
   *
   * On a palette or Look pad it names the tap's verb first (*Put on* / *Take off these heads*), then
   * *Apply as values*, *Pick up* and *View*; a cue pad's is *Pick up* and *View*. A hold cannot mean
   * two things, so it is the menu and never a press. The dispatch is {@link dispatchSyntheticContextMenu}, shared with
   * `CueSlotCell`: it reaches a Radix context menu from a touch hold, leaves right-click working on
   * a mouse for free, and — the part that is not merely convenience — **clears Radix's own 700ms
   * touch timer** through the trigger's `onContextMenu`, so one finger cannot arm two hold
   * detectors. (`PalettePressButton`'s chip has no such dispatch, which is exactly why it must not
   * use a `ContextMenuTrigger` at all; see the note there.)
   *
   * ⌥click is *Apply as values* on a palette or Look pad (apply-model plan D8). The press fires on
   * `pointerup`, which carries no modifier of its own here, so the down's is kept in `altRef`.
   */
  const altRef = useRef(false)
  const valuesOnAlt = !isCue && onApplyValues != null
  const { handlers: holdHandlers } = useLongPress({
    onLongPress: (origin) => dispatchSyntheticContextMenu(triggerRef.current, origin),
    onPress: () => (valuesOnAlt && altRef.current ? onApplyValues?.() : onPress()),
    disabled: editing,
  })
  const handlers = {
    ...holdHandlers,
    onPointerDown: (e: React.PointerEvent) => {
      altRef.current = e.altKey
      holdHandlers.onPointerDown(e)
    },
  }

  const body = (
    <div ref={setRef} className="relative">
      <button
        type="button"
        {...(editing ? attributes : {})}
        {...(editing ? listeners : handlers)}
        // The notes are off the pad's face — the mock has room for a name and one line of detail.
        title={editing ? face.name : (face.notes ?? face.name)}
        aria-pressed={isCue ? cueRing === 'loose' : presence !== 'none'}
        className={cn(
          PAD_SHELL,
          'w-full',
          isCue ? CUE_SHELL : PAD_FACE_SHELL,
          isCue
            ? cn(
                'bg-card',
                cueRing === 'loose' && 'border-green-500 bg-green-500/25 ring-1 ring-green-500/50',
                cueRing === 'stack' && 'border-green-500/80 ring-1 ring-green-500/35',
              )
            : padPresenceClass(presence),
          editing
            ? 'cursor-grab'
            : cn('active:scale-95', isCue ? 'hover:bg-accent' : 'hover:brightness-110'),
          // The source stays in place and ghosted while it is lifted; the dashed slot elsewhere in
          // the bank is where it would land.
          isDragging && 'opacity-40',
        )}
      >
        {isCue ? <CueFace face={face} /> : <LookFace face={face} pad={pad} presence={presence} live />}
        {!isCue && presence !== 'none' && (
          <div
            className={cn(
              'absolute top-1.5 right-1.5 size-2 rounded-full',
              presence === 'all' ? 'bg-primary' : 'bg-primary/50',
            )}
          />
        )}
      </button>
      {editing && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove ${face.name}`}
          // Top-*left*, because the presence dot owns the other corner.
          className="absolute -top-[7px] -left-[7px] grid size-[18px] place-items-center rounded-full border bg-muted text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <X className="size-2.5" strokeWidth={2.5} />
        </button>
      )}
    </div>
  )

  const verb = tapVerb(
    PROGRAMMER_INTO,
    presence,
    hasSelection,
    !(pad.kind === 'PALETTE' && (pad.palette?.isGeneric ?? true)),
    pad.kind === 'LOOK',
  )

  // No menu while editing: there the pad is a drag handle, `useLongPress` is disabled and the only
  // gestures are drag and the cross.
  if (editing) return body

  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>{body}</ContextMenuTrigger>
      <ContextMenuContent>
        {/* The tap's verb, named, then *Apply as values* — the press gestures every chip and pad
            share (apply-model plan §4.1). A cue pad is playback, outside the model. */}
        {!isCue && (
          <>
            <ContextMenuItem onSelect={onPress}>
              {verb.gesture === 'off' ? <Undo2 className="mr-2 size-4" /> : <Layers className="mr-2 size-4" />}
              {verb.label}
            </ContextMenuItem>
            {onApplyValues != null && (
              <ContextMenuItem onSelect={onApplyValues}>
                <ListChecks className="mr-2 size-4" />
                Apply as values
              </ContextMenuItem>
            )}
            <ContextMenuSeparator />
          </>
        )}
        <ContextMenuItem onSelect={onPickUp}>
          <Hand className="mr-2 size-4" />
          Pick up
        </ContextMenuItem>
        <ContextMenuSeparator />
        <ContextMenuItem onSelect={onInspect}>
          <Eye className="mr-2 size-4" />
          View
        </ContextMenuItem>
      </ContextMenuContent>
    </ContextMenu>
  )
}

/** The pad-shaped placeholder that opens where a drop would land. */
export function BuskDropSlot({ tall = false }: { tall?: boolean }) {
  return (
    <div
      aria-hidden
      // Never a droppable, and never hit-testable: if the slot could be the thing you are over, it
      // would open under the pointer, shift the layout, and take itself away again.
      className={cn(
        'pointer-events-none rounded-lg border-2 border-dashed border-primary bg-primary/5',
        tall ? 'min-h-[56px]' : 'min-h-[56px]',
      )}
    />
  )
}

/** The lifted pad under the cursor. Hookless by contract — see `PadFace`. */
function PadGhost({ face }: { face: PadFace }) {
  const isCue = face.kind === 'CUE'
  return (
    <div
      className={cn(
        PAD_SHELL,
        isCue ? CUE_SHELL : PAD_FACE_SHELL,
        'w-[120px] border-primary bg-card opacity-90 shadow-lg',
      )}
      style={{ transform: 'rotate(-2deg)' }}
    >
      {isCue ? <CueFace face={face} /> : <LookFace face={face} pad={{ kind: face.kind }} presence="none" live={false} />}
    </div>
  )
}

/**
 * Registered at module scope so `Layout.tsx`'s overlay can draw a busk pad without the app shell
 * ever importing one. A busk drag cannot happen without this module being loaded.
 */
registerDragOverlay((active) => {
  const tab = active.data.current
  if (tab?.type === 'busk-page-tab') {
    return (
      <div
        className="rounded-lg border border-primary bg-card px-4 py-1.5 text-[13px] font-semibold opacity-90 shadow-lg"
        style={{ transform: 'rotate(-2deg)' }}
      >
        {String(tab.name ?? 'Page')}
      </div>
    )
  }
  const data = buskDragData(active)
  if (data == null) return null
  if (data.type === 'busk-bank') {
    return (
      <div
        className="w-[130px] rounded-[10px] border border-primary bg-card p-2.5 opacity-90 shadow-lg"
        style={{ transform: 'rotate(-2deg)' }}
      >
        <div className="truncate text-[11px] font-semibold">{data.name || 'Bank'}</div>
        <div className="mt-1 text-[10px] text-muted-foreground">
          {data.padCount} {data.padCount === 1 ? 'pad' : 'pads'}
        </div>
      </div>
    )
  }
  return <PadGhost face={data.face} />
})
