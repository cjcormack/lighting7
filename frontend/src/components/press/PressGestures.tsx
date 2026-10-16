import { useCallback, useMemo, useState, type MouseEvent as ReactMouseEvent, type ReactElement } from 'react'
import { useNavigate } from 'react-router'
import { Hand, Layers, ListChecks, Pencil, Undo2 } from 'lucide-react'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import type { EffectPresence } from '@/components/busking/buskingTypes'
import { useLongPress, type LongPressHandlers } from '@/hooks/useLongPress'
import type { PressGesture, PressInto } from '@/hooks/usePress'
import { handPickUp } from '@/store/hand'
import type { TemplateSummary } from '@/api/templatesApi'

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
 * nothing selected the ring cannot say — a per-fixture palette or a Look then lands on its own heads,
 * and comes off them if it is already there — so the verb names both.
 */
export function tapVerb(
  into: PressInto,
  presence: EffectPresence,
  hasTargets = true,
  ownHeads = true,
): { label: string; gesture: PressGesture } {
  if (into.kind === 'look') return { label: `Into ${into.lookName}`, gesture: 'press' }
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
export function templatePressTitle(template: TemplateSummary, into: PressInto): string {
  if (into.kind === 'look') return `Click to write “${template.name}” into ${into.lookName} · hold or right-click for more`
  return template.kind === 'effect'
    ? `Click to run “${template.name}” as a layer that tracks it · ⌥click to run a copy · hold or right-click for more`
    : `Click to put “${template.name}” on as a layer that tracks it · ⌥click to set its values · hold or right-click for more`
}

/**
 * A palette chip or pad with its hold menu: *Put on* or *Take off these heads* (the tap's verb),
 * *Apply as values*, *Pick up* and *Edit palette…* — *Into <Look>* alone in place of the first two
 * while a Look layer is focused, where every press writes the Look.
 *
 * **A `Popover` anchored on the button, never a `ContextMenu`.** Radix's `ContextMenuTrigger` arms
 * a ~700ms touch timer of its own, which this button's hold would race; `PopoverAnchor` registers
 * no handlers, so the button keeps one hold and one meaning. The menu body is mounted only while
 * open, which is also what keeps the router out of a surface that never opens it.
 */
export function PalettePressButton({
  projectId,
  template,
  into,
  presence,
  hasTargets = true,
  onGesture,
  children,
}: {
  projectId: number
  template: TemplateSummary
  into: PressInto
  /** The ring the button draws, which decides the menu's first verb. */
  presence: EffectPresence
  /** False when the press carries no targets — the ring cannot then say which way it goes. */
  hasTargets?: boolean
  onGesture: (template: TemplateSummary, gesture: PressGesture) => void
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
        <PressMenuItems
          projectId={projectId}
          template={template}
          into={into}
          presence={presence}
          hasTargets={hasTargets}
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

function PressMenuItems({
  projectId,
  template,
  into,
  presence,
  hasTargets,
  onGesture,
  onDone,
}: {
  projectId: number
  template: TemplateSummary
  into: PressInto
  presence: EffectPresence
  hasTargets: boolean
  onGesture: (gesture: PressGesture) => void
  onDone: () => void
}) {
  const navigate = useNavigate()
  const verb = tapVerb(into, presence, hasTargets, !template.isGeneric)
  return (
    <div role="menu" aria-label={template.name} className="flex flex-col">
      <MenuItem icon={verb.gesture === 'off' ? Undo2 : Layers} label={verb.label} onSelect={() => onGesture(verb.gesture)} />
      {into.kind === 'programmer' && (
        <MenuItem icon={ListChecks} label="Apply as values" onSelect={() => onGesture('asValues')} />
      )}
      <MenuItem
        icon={Hand}
        label="Pick up"
        onSelect={() => {
          onDone()
          handPickUp('TEMPLATE', template.id)
        }}
      />
      <MenuItem
        icon={Pencil}
        label="Edit palette…"
        onSelect={() => {
          onDone()
          navigate(`/projects/${projectId}/palettes?edit=${template.id}`)
        }}
      />
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
