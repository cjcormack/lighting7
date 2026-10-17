/**
 * ⌘K *Apply FX → On the selection* on the programmer opens the rail's *+ Effect* rather than a
 * picker of its own: the palette is mounted in `Layout`, outside the programmer's marquee store and
 * scope, so a picker there could read neither the window's mask nor a focused Look layer. The rail
 * registers its door while it is mounted; the palette asks for it first.
 */
const doors: (() => void)[] = []

/** Register [open] while the programmer's rail is mounted; the returned function unregisters it. */
export function registerAddEffectDoor(open: () => void): () => void {
  doors.push(open)
  return () => {
    const at = doors.lastIndexOf(open)
    if (at >= 0) doors.splice(at, 1)
  }
}

/** Open the programmer's *+ Effect*, if a rail is mounted. False when there is none. */
export function openAddEffectDoor(): boolean {
  const open = doors.at(-1)
  if (open == null) return false
  open()
  return true
}
