import { useEffect } from 'react'
import { useStableCallback } from '@/hooks/useStableCallback'

/**
 * **⌘K's doors into the programmer** (programmer-toolbar plan D13). The palette is mounted in
 * `Layout`, outside the programmer's stores — its marquee, its scope, its sheets — so it cannot run a
 * programmer command itself, and must not subscribe to the selection to say what one would do. The
 * component that owns each command registers a door while it is mounted: the commands it offers
 * (their words, what they act on, whether they can act now) and the handler each runs. ⌘K reads the
 * offers once when it opens, so nothing in `Layout` re-renders at selection rate.
 */

export type CommandGroup = 'programmer' | 'selection'

/** One row of a sub-page: a Look or a palette, pressed from ⌘K. */
export interface CommandPageItem {
  id: string
  label: string
  hint?: string
  /** A colour palette's swatch. */
  swatch?: string | null
  /** Why the selection's mask leaves it out — drawn dimmed, still pressable. */
  dimmed?: string | null
  /** A tap; `asValues` is ⌥⏎ or an ⌥-click, *Apply as values*. */
  press: (asValues: boolean) => void
}

export interface CommandPageSection {
  heading: string
  note?: string
  items: readonly CommandPageItem[]
}

/** A sub-page — *Add a Look…*, *Apply a palette…* — built when it opens. */
export interface CommandPage {
  title: string
  footer: string
  empty: string
  sections: readonly CommandPageSection[]
}

export interface ProgrammerCommand {
  /** Unique across every door; what a test or another door names it by. */
  id: string
  group: CommandGroup
  label: string
  /** What it will act on — *6 heads · Colour*, *over 1s*. */
  hint?: string
  keywords?: readonly string[]
  /** The grid key that does the same (⏎, ⌫). */
  kbd?: string
  /** False leaves it out of ⌘K: a command that cannot act is missing, not disabled. */
  canAct: boolean
  /** Run it, ⌘K closed. */
  run?: () => void
  /** Open a sub-page instead of running. */
  page?: () => CommandPage
}

/**
 * Every door, in the order ⌘K lists them (D13): *Programmer* first, then *Selection*. A door may
 * offer several commands, listed in its own order.
 */
export const COMMAND_DOORS = [
  'look',
  'include',
  'record',
  'source',
  'clear',
  'blind',
  'fade',
  'tables',
  'scope',
  'set',
  'palette',
  'fx',
  'fan',
  'release',
  'subsets',
  'mask',
  'rig',
  'follow',
  'deselect',
] as const

export type CommandDoor = (typeof COMMAND_DOORS)[number]

type Offer = () => readonly ProgrammerCommand[]

const doors = new Map<CommandDoor, Offer[]>()

/** Register [offer] as [door] while its owner is mounted; the returned function unregisters it. */
export function registerCommandDoor(door: CommandDoor, offer: Offer): () => void {
  const list = doors.get(door) ?? []
  list.push(offer)
  doors.set(door, list)
  return () => {
    const at = list.lastIndexOf(offer)
    if (at >= 0) list.splice(at, 1)
  }
}

/** Every command the mounted doors offer now, those that cannot act included, in ⌘K's order. */
export function readProgrammerCommands(): ProgrammerCommand[] {
  return COMMAND_DOORS.flatMap((door) => doors.get(door)?.at(-1)?.() ?? [])
}

/**
 * Run the command [id] whether or not it can act — its own handler says why not — and say whether
 * a mounted door offered it. ⌘K's *Apply FX → On the selection* asks for `selection.addFx` this way.
 */
export function runProgrammerCommand(id: string): boolean {
  const command = readProgrammerCommands().find((c) => c.id === id)
  if (command?.run == null) return false
  command.run()
  return true
}

/**
 * Register [offer] as [door] for the owner's life. The offer is read through a stable callback, so
 * it is never re-registered and always sees the owner's latest render. [enabled] false registers
 * nothing — the plain lists, which share the programmer's container.
 */
export function useCommandDoor(door: CommandDoor, offer: Offer, enabled = true): void {
  const read = useStableCallback(offer)
  useEffect(() => (enabled ? registerCommandDoor(door, () => read() ?? []) : undefined), [door, enabled, read])
}

/** *6 heads*, *1 cell*: what a command acts on. */
export function countOf(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`
}
