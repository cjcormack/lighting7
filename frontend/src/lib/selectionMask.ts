import { ATTRIBUTE_FAMILIES, formatFamilyList, type AttributeFamily } from './attributeFamily'

/**
 * The desk selection's **attribute mask** — the `families` half of the one selection fact
 * (multi-screen plan D2, D3) — as this side spells it.
 *
 * `null` is *every attribute*, and it is the only spelling of "no mask": an empty list and a list
 * naming all four both land as null, exactly as `serializePropertyMask` folds both to a null
 * `propertyMask` and as the desk's `DeskSelection.normalise` does. Keeping one spelling is what
 * lets a frame be compared to what was sent (`useDeskSelectionBridge`'s echo FIFO) and lets "has
 * the mask changed?" be one string comparison rather than a set algebra in three places.
 *
 * The vocabulary is `ATTRIBUTE_FAMILIES` — the four `PropertyMaskGroup` names — and an unknown
 * name is **dropped**, as `parsePropertyMask` drops one and as the desk's lenient parser does for
 * `selection.set`. `store/selection.test.ts` pins the parser against the list — the way
 * `maskPicker.test.ts` pins `MASK_GROUPS` — so a fifth family fails here as well as on the
 * backend's `PropertyMaskTest`.
 */

/** Fold a family list to the one spelling: declaration order, `null` for none or all. */
export function normaliseFamilies(
  families: readonly AttributeFamily[] | null | undefined,
): AttributeFamily[] | null {
  if (families == null) return null
  const selected = ATTRIBUTE_FAMILIES.filter((family) => families.includes(family))
  if (selected.length === 0 || selected.length === ATTRIBUTE_FAMILIES.length) return null
  return selected
}

/**
 * Read a wire `families` list — `selection.state`'s, or a press response's `skippedFamilies` —
 * dropping anything outside the vocabulary. Absent, empty or complete is `null`.
 */
export function parseFamilies(raw: unknown): AttributeFamily[] | null {
  if (!Array.isArray(raw)) return null
  const names = raw
    .filter((name): name is string => typeof name === 'string')
    .map((name) => name.trim().toUpperCase())
  return normaliseFamilies(ATTRIBUTE_FAMILIES.filter((family) => names.includes(family)))
}

/**
 * Read a wire list of family names as a **list**, not a mask: declaration order, unknown names
 * dropped, and — unlike [parseFamilies] — no fold of "all four" to none. For a press response's
 * `skippedFamilies`, where naming every family is a report, not the absence of one.
 */
export function parseFamilyList(raw: unknown): AttributeFamily[] {
  if (!Array.isArray(raw)) return []
  const names = raw
    .filter((name): name is string => typeof name === 'string')
    .map((name) => name.trim().toUpperCase())
  return ATTRIBUTE_FAMILIES.filter((family) => names.includes(family))
}

/** The comparison key of a mask: `''` for none, `COLOUR,POSITION` otherwise. */
export function familiesKey(families: readonly AttributeFamily[] | null | undefined): string {
  return normaliseFamilies(families)?.join(',') ?? ''
}

export function sameFamilies(
  a: readonly AttributeFamily[] | null | undefined,
  b: readonly AttributeFamily[] | null | undefined,
): boolean {
  return familiesKey(a) === familiesKey(b)
}

/**
 * The pressing window's toast for a Look pressed under a mask (multi-screen plan D6):
 * `Position rows skipped — the selection is Colour`.
 *
 * It says **rows** on purpose. The mask lands through the layer's own `propertyMask`, which the
 * cook applies to the layer's rows and not to its effects — so a Look's effect in a skipped family
 * still runs while being named here (documented at lighting7's `resolveLookMask`). A toast that
 * promised the whole family was held back would be promising more than the desk does.
 *
 * [skipped] is the response's list as the server spelled it; a name outside the vocabulary is
 * dropped rather than shown, since there is no label for it.
 */
export function skippedRowsMessage(skipped: readonly string[], mask: readonly AttributeFamily[] | null): string | null {
  // A list, not a mask: a skip naming every family is still a skip to report.
  const families = parseFamilyList(skipped)
  if (families.length === 0) return null
  const what = `${formatFamilyList(families, ' + ')} rows skipped`
  return mask == null || mask.length === 0
    ? what
    : `${what} — the selection is ${formatFamilyList(mask, ' + ')}`
}
