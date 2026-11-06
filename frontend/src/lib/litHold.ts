import { useEffect } from 'react'

/**
 * Holds on the programmer's *Lit* filter: while any is taken, `useLitFixtureKeys` keeps answering
 * the set it had when the first was taken, and catches up when the last is released. A cell
 * editor, the add-FX picker and a marquee take one, so a dimmer going through 0 under them does
 * not take the row they are working on out of the list.
 *
 * A module-level count rather than a context: the takers sit in different subtrees (a cell's
 * popover, row C, the table's marquee) and none of them needs to know who else holds.
 */
let holds = 0
const listeners = new Set<() => void>()

function notify() {
  for (const listener of listeners) listener()
}

/** Take a hold; the answer releases it, once however often it is called. */
export function takeLitHold(): () => void {
  holds++
  if (holds === 1) notify()
  let released = false
  return () => {
    if (released) return
    released = true
    holds--
    if (holds === 0) notify()
  }
}

export function isLitHeld(): boolean {
  return holds > 0
}

/** Called when the filter goes from held to free or back. */
export function subscribeLitHold(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Hold *Lit* still for as long as [active] is true. */
export function useLitHold(active: boolean): void {
  useEffect(() => (active ? takeLitHold() : undefined), [active])
}
