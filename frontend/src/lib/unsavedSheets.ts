/**
 * How many open sheets are holding unsaved work — a module-level count fed by `ui/sheet.tsx`'s
 * provider, for a reader that is nowhere near any sheet (multi-screen plan §3.4).
 *
 * Every `Sheet` already computes `hasUnsaved` for its own *Discard changes?* guard; this is that
 * answer, summed, so a `windows.show` arriving from another screen can decline to navigate rather
 * than take a half-edited form with it. It is a count and not a flag because two sheets can be
 * open at once (a picker over an editor), and the second closing must not clear the first's claim.
 */

const unsaved = new Set<symbol>()

/** Called by each `Sheet` as its own dirty state moves. Idempotent per id. */
export function setSheetUnsaved(id: symbol, dirty: boolean): void {
  if (dirty) unsaved.add(id)
  else unsaved.delete(id)
}

export function unsavedSheetCount(): number {
  return unsaved.size
}

export function hasUnsavedSheets(): boolean {
  return unsaved.size > 0
}

/** Test seam. */
export function resetUnsavedSheets(): void {
  unsaved.clear()
}
