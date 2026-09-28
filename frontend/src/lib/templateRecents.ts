import type { TemplateSummary } from '@/api/templatesApi'

/**
 * How many chips the programmer's row keeps, and how many the picker's Recent section shows.
 *
 * Eight because that is what the row holds at a desk width once `All · n` and `New` have their
 * place (`TemplatesDesktop.dc.html`); the narrower arms simply scroll fewer of the same eight
 * rather than computing a smaller number, so the row and the sheet's first section agree about
 * what "recent" means whatever width they are drawn at.
 */
export const RECENT_TEMPLATE_LIMIT = 8

/**
 * When a template was last pressed, as a number, or null if never.
 *
 * **Parsed, never compared as text.** The server sends `Instant.toString()`, which omits the
 * fractional part altogether on an exact second — so `…:34Z` compares *after* `…:34.500Z`, and a
 * lexicographic sort would put a press half a second older at the front roughly once in a
 * thousand. An unparseable or absent stamp reads as "never pressed", which is also how a desk
 * mid-upgrade that serves no such field reads.
 */
function pressedAt(template: TemplateSummary): number | null {
  const raw = template.lastPressedAt
  if (raw == null) return null
  const parsed = Date.parse(raw)
  return Number.isNaN(parsed) ? null : parsed
}

/**
 * The offerable templates this desk has actually pressed, most recent first.
 *
 * "For the selected family" is not a parameter, and does not need to be: the caller hands in the
 * list it is already offering, which `TemplateStrip` has filtered by family, emitters and
 * generic-vs-per-fixture. So the per-family scoping the design asks for falls out of the filter
 * that was there anyway, and no second history has to be kept per family.
 *
 * **Ties keep the input's order**, which is the library's own name order: `Array.sort` is stable,
 * and two templates pressed inside the same millisecond have no more meaningful order than that.
 */
export function recentTemplates(
  offerable: readonly TemplateSummary[],
  limit: number = RECENT_TEMPLATE_LIMIT,
): TemplateSummary[] {
  if (limit <= 0) return []
  return offerable
    .filter((template) => pressedAt(template) != null)
    .sort((a, b) => (pressedAt(b) ?? 0) - (pressedAt(a) ?? 0))
    .slice(0, limit)
}

/**
 * What the programmer's row of chips draws: the recents, or the first few by name where there are
 * none yet.
 *
 * The fallback is the whole reason this is a function rather than a call to [recentTemplates] at
 * the one site. A fresh project has pressed nothing, and a row that was empty until the operator
 * had already found a template some other way would be a row that teaches nothing about itself —
 * so the first press comes off the row like every one after it, and the row simply reorders from
 * then on.
 *
 * It is **all or nothing**: one recent shows one chip rather than one recent padded out with seven
 * by name. A padded row would move its own contents under the operator's hand on the second press
 * — chip five becoming chip two — where a short row only grows.
 */
export function stripTemplates(
  offerable: readonly TemplateSummary[],
  limit: number = RECENT_TEMPLATE_LIMIT,
): TemplateSummary[] {
  const recents = recentTemplates(offerable, limit)
  return recents.length > 0 ? recents : offerable.slice(0, Math.max(0, limit))
}
