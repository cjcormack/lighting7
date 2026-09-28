/**
 * The two sentences every programmer cell editor says, written once so no editor can count heads
 * or name a skip differently from its neighbours (editor-kit plan D8).
 */

/** *4 heads · Local* — the label line's left half. */
export function headsLine(count: number, scope: string): string {
  return `${count} ${count === 1 ? 'head' : 'heads'} · ${scope}`
}

/**
 * *2 heads have no gobo · skipped* — the read-out's skip count, or null where every head in the
 * batch takes the property. A commit over a batch skips such heads silently at write time
 * (`planBatchWrites`); this is where it is said.
 */
export function skippedLine(skipped: number, noun: string): string | null {
  if (skipped <= 0) return null
  return `${skipped} ${skipped === 1 ? 'head has' : 'heads have'} no ${noun} · skipped`
}
