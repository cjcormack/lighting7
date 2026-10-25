import type { PaletteSummary } from '@/api/palettesApi'

/** How many palettes the picker's Recent section shows. */
export const RECENT_PALETTE_LIMIT = 8

/**
 * When a template was last pressed, as a number, or null if never.
 *
 * **Parsed, never compared as text.** The server sends `Instant.toString()`, which omits the
 * fractional part altogether on an exact second — so `…:34Z` compares *after* `…:34.500Z`, and a
 * lexicographic sort would put a press half a second older at the front roughly once in a
 * thousand. An unparseable or absent stamp reads as "never pressed", which is also how a desk
 * mid-upgrade that serves no such field reads.
 */
function pressedAt(template: PaletteSummary): number | null {
  const raw = template.lastPressedAt
  if (raw == null) return null
  const parsed = Date.parse(raw)
  return Number.isNaN(parsed) ? null : parsed
}

/**
 * The offerable templates this desk has actually pressed, most recent first.
 *
 * "For the selected family" is not a parameter, and does not need to be: the caller hands in the
 * list it is already offering, which the caller has filtered by family, emitters and
 * generic-vs-per-fixture. So the per-family scoping the design asks for falls out of the filter
 * that was there anyway, and no second history has to be kept per family.
 *
 * **Ties keep the input's order**, which is the library's own name order: `Array.sort` is stable,
 * and two templates pressed inside the same millisecond have no more meaningful order than that.
 */
export function recentPalettes(
  offerable: readonly PaletteSummary[],
  limit: number = RECENT_PALETTE_LIMIT,
): PaletteSummary[] {
  if (limit <= 0) return []
  return offerable
    .filter((template) => pressedAt(template) != null)
    .sort((a, b) => (pressedAt(b) ?? 0) - (pressedAt(a) ?? 0))
    .slice(0, limit)
}

/**
 * Every palette given, the pressed ones most recent first and then the rest in the order given (the
 * library's, which is by name) — a popup band's order. The band shows its family whole, and recency only says
 * which come first.
 */
export function recentFirst(offerable: readonly PaletteSummary[]): PaletteSummary[] {
  return [...recentPalettes(offerable, offerable.length), ...offerable.filter((template) => pressedAt(template) == null)]
}
