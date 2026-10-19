/**
 * ⌘K *Apply FX → On the selection* on the programmer opens the programmer's *+ Effect* rather than
 * a picker of its own: the palette is mounted in `Layout`, outside the programmer's marquee store
 * and scope, so a picker there could read neither the window's mask nor a focused Look layer. The
 * programmer's tables register the door while they are mounted (`ProgrammerAddEffectDoor`); the
 * palette asks for it first.
 */
const doors: (() => void)[] = []

/** Register [open] while the programmer is mounted; the returned function unregisters it. */
export function registerAddEffectDoor(open: () => void): () => void {
  doors.push(open)
  return () => {
    const at = doors.lastIndexOf(open)
    if (at >= 0) doors.splice(at, 1)
  }
}

/** Open the programmer's *+ Effect*, if the programmer is mounted. False when it is not. */
export function openAddEffectDoor(): boolean {
  const open = doors.at(-1)
  if (open == null) return false
  open()
  return true
}
