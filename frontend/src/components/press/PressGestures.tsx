import { useCallback, useMemo, useState, type MouseEvent as ReactMouseEvent, type ReactElement } from 'react'
import { useNavigate } from 'react-router'
import { Hand, Layers, ListChecks, Pencil, Undo2 } from 'lucide-react'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import type { FxPresence } from '@/components/busking/buskingTypes'
import { useLongPress, type LongPressHandlers } from '@/hooks/useLongPress'
import type { PressGesture, PressInto } from '@/hooks/usePress'
import { handPickUp } from '@/store/hand'
import type { LookSummary } from '@/api/looksApi'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * The press **gestures** on a palette chip or pad, the same on every surface (apply-model plan D8,
 * §4.1):
 *
 *  - **tap** — the press: a layer that tracks the palette, or off the pressed heads when it already
 *    covers them all (the desk decides which);
 *  - **⌥click** — *Apply as values*;
 *  - **hold or right-click** — the menu, and never a press. A hold changes nothing on the rig any
 *    more, so it is armed for every pointer, a mouse's too, as a busk pad's is; the click its
 *    release generates is swallowed.
 *
 * Returns props to spread onto the button, so no caller can spread half of them.
 */
export function usePressGestures(
  onGesture: (gesture: PressGesture) => void,
  openMenu: () => void,
): LongPressHandlers & {
  onClick: (event: ReactMouseEvent) => void
  onContextMenu: (event: ReactMouseEvent) => void
} {
  const { handlers: hold, consumeLongPress } = useLongPress({ onLongPress: openMenu })
  return useMemo(
    () => ({
      ...hold,
      onClick: (event: ReactMouseEvent) => {
        if (consumeLongPress()) return
        onGesture(event.altKey ? 'asValues' : 'press')
      },
      onContextMenu: (event: ReactMouseEvent) => {
        event.preventDefault()
        openMenu()
      },
    }),
    [hold, consumeLongPress, onGesture, openMenu],
  )
}

/**
 * The menu's first item: the tap's verb for this selection, which the hold menu names out loud. With
 * nothing selected the ring cannot say — a per-fixture palette then lands on its own heads, and comes
 * off them if it is already there — so the verb names both. A Look is [whole]: it lands whole whatever
 * is selected, so its ring always says which way the tap goes.
 */
export function tapVerb(
  into: PressInto,
  presence: FxPresence,
  hasTargets = true,
  ownHeads = true,
  whole = false,
  /** An FX palette, which joins a focused Look rather than going on and off its rows. */
  fx = false,
): { label: string; gesture: PressGesture } {
  if (into.kind === 'look') return { label: whole || fx ? `Into ${into.lookName}` : `Put on or take off in ${into.lookName}`, gesture: 'press' }
  if (into.kind === 'cue') {
    return whole || hasTargets || ownHeads
      ? { label: `Put on or take off in ${into.cueLabel}`, gesture: 'press' }
      : { label: 'Put on — select fixtures first', gesture: 'press' }
  }
  if (whole) return presence === 'all' ? { label: 'Take off', gesture: 'off' } : { label: 'Put on', gesture: 'press' }
  if (!hasTargets) {
    return ownHeads
      ? { label: 'Put on or take off its own heads', gesture: 'press' }
      : { label: 'Put on — select fixtures first', gesture: 'press' }
  }
  return presence === 'all'
    ? { label: 'Take off these heads', gesture: 'off' }
    : { label: 'Put on', gesture: 'press' }
}

/** The chip's and the pad's shared `title`: every gesture stated, since ⌥click is not guessed. */
export function palettePressTitle(template: PaletteSummary, into: PressInto): string {
  if (into.kind === 'look') {
    return template.kind === 'fx'
      ? `Click to add “${template.name}” to ${into.lookName} · hold or right-click for more`
      : `Click to put “${template.name}” on in ${into.lookName} as references to it, or take it off · ⌥click to set its values as ${into.lookName}’s own · hold or right-click for more`
  }
  if (into.kind === 'cue') {
    return template.kind === 'fx'
      ? `Click to put “${template.name}” on in ${into.cueLabel} as a layer, or take it off · hold or right-click for more`
      : `Click to put “${template.name}” on in ${into.cueLabel} as references to it, or take it off · ⌥click to set its values as ${into.cueLabel}’s own · hold or right-click for more`
  }
  return template.kind === 'fx'
    ? `Click to run “${template.name}” as a layer that tracks it · ⌥click to run a copy · hold or right-click for more`
    : `Click to put “${template.name}” on as references that follow it · ⌥click to set its values as literals · hold or right-click for more`
}

/**
 * A palette chip or pad with its hold menu: *Put on* or *Take off these heads* (the tap's verb),
 * *Apply as values*, *Pick up* and *Edit palette…* — while a Look layer is focused, where every press
 * writes the Look, and in the cue editor, *Put on or take off in Night* (or *Q1*) and a value
 * palette's *Apply as values*, the owner's own rows; an FX palette's *Into Night* alone.
 *
 * **A `Popover` anchored on the button, never a `ContextMenu`.** Radix's `ContextMenuTrigger` arms
 * a ~700ms touch timer of its own, which this button's hold would race; `PopoverAnchor` registers
 * no handlers, so the button keeps one hold and one meaning. The menu body is mounted only while
 * open, which is also what keeps the router out of a surface that never opens it.
 */
export function PalettePressButton({
  projectId,
  palette: template,
  into,
  presence,
  hasTargets = true,
  reachLabel,
  onGesture,
  children,
}: {
  projectId: number
  palette: PaletteSummary
  into: PressInto
  /** The ring the button draws, which decides the menu's first verb. */
  presence: FxPresence
  /** False when the press carries no targets — the ring cannot then say which way it goes. */
  hasTargets?: boolean
  /** A per-fixture palette's *Put on Spot 2 and Spot 3 — its Spot 1 and Spot 4 are left out* (D21), for an on press. */
  reachLabel?: string | null
  onGesture: (template: PaletteSummary, gesture: PressGesture) => void
  /** The button itself, given the gesture handlers to spread. */
  children: (handlers: ReturnType<typeof usePressGestures>) => ReactElement
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const gesture = useCallback((g: PressGesture) => onGesture(template, g), [onGesture, template])
  const open = useCallback(() => setMenuOpen(true), [])
  const handlers = usePressGestures(gesture, open)
  return (
    // Modal, so the tap that dismisses the menu is not also a press on whatever chip is under it.
    <Popover modal open={menuOpen} onOpenChange={setMenuOpen}>
      <PopoverAnchor asChild>{children(handlers)}</PopoverAnchor>
      <PopoverContent align="start" className="w-auto min-w-44 p-1">
        <PalettePressMenu
          projectId={projectId}
          palette={template}
          into={into}
          presence={presence}
          hasTargets={hasTargets}
          reachLabel={reachLabel}
          onGesture={(g) => {
            setMenuOpen(false)
            gesture(g)
          }}
          onDone={() => setMenuOpen(false)}
        />
      </PopoverContent>
    </Popover>
  )
}

/** A palette's menu: the tap's verb, *Apply as values*, *Pick up*, *Edit palette…*. Mounted only while open. */
function PalettePressMenu({
  projectId,
  palette: template,
  into,
  presence,
  hasTargets,
  reachLabel,
  onGesture,
  onDone,
}: {
  projectId: number
  palette: PaletteSummary
  into: PressInto
  presence: FxPresence
  hasTargets: boolean
  reachLabel?: string | null
  onGesture: (gesture: PressGesture) => void
  onDone: () => void
}) {
  const navigate = useNavigate()
  return (
    <PressMenuItems
      name={template.name}
      into={into}
      presence={presence}
      hasTargets={hasTargets}
      reachLabel={reachLabel}
      ownHeads={!template.isGeneric}
      asValues={into.kind === 'programmer' || template.kind !== 'fx'}
      fx={template.kind === 'fx'}
      onGesture={onGesture}
      onPickUp={() => {
        onDone()
        handPickUp('PALETTE', template.id)
      }}
      onEdit={() => {
        onDone()
        navigate(`/projects/${projectId}/palettes?edit=${template.id}`)
      }}
      editLabel="Edit palette…"
    />
  )
}

/**
 * A Look's pad with the same gestures and menu as a palette's — *Put on* or *Take off*, *Apply as
 * values*, *Pick up* — for the one picker's Looks (apply-model plan §4.2). No *Edit* item: `/looks`
 * opens no single Look yet. A Look lands whole, whatever is selected (absolute-looks plan D1).
 */
export function LookPressButton({
  look,
  into,
  presence,
  onGesture,
  children,
}: {
  look: Pick<LookSummary, 'id' | 'name'>
  into: PressInto
  presence: FxPresence
  onGesture: (look: Pick<LookSummary, 'id' | 'name'>, gesture: PressGesture) => void
  children: (handlers: ReturnType<typeof usePressGestures>) => ReactElement
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const gesture = useCallback((g: PressGesture) => onGesture(look, g), [onGesture, look])
  const open = useCallback(() => setMenuOpen(true), [])
  const handlers = usePressGestures(gesture, open)
  return (
    <Popover modal open={menuOpen} onOpenChange={setMenuOpen}>
      <PopoverAnchor asChild>{children(handlers)}</PopoverAnchor>
      <PopoverContent align="start" className="w-auto min-w-44 p-1">
        <PressMenuItems
          name={look.name}
          into={into}
          presence={presence}
          hasTargets
          ownHeads
          whole
          onGesture={(g) => {
            setMenuOpen(false)
            gesture(g)
          }}
          onPickUp={() => {
            setMenuOpen(false)
            handPickUp('LOOK', look.id)
          }}
        />
      </PopoverContent>
    </Popover>
  )
}

function PressMenuItems({
  name,
  into,
  presence,
  hasTargets,
  ownHeads,
  whole = false,
  asValues = into.kind === 'programmer',
  fx = false,
  reachLabel,
  onGesture,
  onPickUp,
  onEdit,
  editLabel,
}: {
  /** Names the heads an on press lands on and leaves out, in place of the bare *Put on*. */
  reachLabel?: string | null
  name: string
  into: PressInto
  presence: FxPresence
  hasTargets: boolean
  /** A press with no targets lands on the palette's own heads — a per-fixture palette. */
  ownHeads: boolean
  /** A Look: it lands whole, whatever is selected ([tapVerb]). */
  whole?: boolean
  /** Offer *Apply as values* — the programmer's, and a value palette's in the cue or a focused Look. */
  asValues?: boolean
  /** An FX palette ([tapVerb]). */
  fx?: boolean
  onGesture: (gesture: PressGesture) => void
  onPickUp: () => void
  /** Absent, no edit item: a Look has no address to open it at yet. */
  onEdit?: () => void
  editLabel?: string
}) {
  const tap = tapVerb(into, presence, hasTargets, ownHeads, whole, fx)
  const verb =
    reachLabel != null && into.kind === 'programmer' && tap.gesture === 'press' ? { ...tap, label: reachLabel } : tap
  return (
    <div role="menu" aria-label={name} className="flex flex-col">
      <MenuItem icon={verb.gesture === 'off' ? Undo2 : Layers} label={verb.label} onSelect={() => onGesture(verb.gesture)} />
      {asValues && (
        <MenuItem icon={ListChecks} label="Apply as values" onSelect={() => onGesture('asValues')} />
      )}
      <MenuItem icon={Hand} label="Pick up" onSelect={onPickUp} />
      {onEdit != null && <MenuItem icon={Pencil} label={editLabel ?? 'Edit…'} onSelect={onEdit} />}
    </div>
  )
}

function MenuItem({
  icon: Icon,
  label,
  onSelect,
}: {
  icon: typeof Hand
  label: string
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      role="menuitem"
      className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-xs hover:bg-accent"
      onClick={onSelect}
    >
      <Icon className="size-3.5" />
      {label}
    </button>
  )
}
