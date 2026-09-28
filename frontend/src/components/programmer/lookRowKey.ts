/** The separator: a NUL, because neither a fixture key nor a property name can contain one. */
const SEP = '\u0000'

/**
 * How a Look row is addressed from the grid: target key and property name, joined.
 *
 * NUL-separated for the same reason `cellSelectionModel` uses one — a fixture key is not
 * guaranteed to be space- or punctuation-free, and [splitLookRowKey] has to recover the pair
 * exactly. A visible separator would work until the first head named "Front Left".
 *
 * Its own module so the draft store and the read path can share it without either importing the
 * other's React surface.
 */
export function lookRowKey(targetKey: string, propertyName: string): string {
  return `${targetKey}${SEP}${propertyName}`
}

/** The inverse. Returns null for anything that isn't a key this module made. */
export function splitLookRowKey(key: string): { targetKey: string; propertyName: string } | null {
  const at = key.indexOf(SEP)
  if (at === -1) return null
  return { targetKey: key.slice(0, at), propertyName: key.slice(at + 1) }
}
