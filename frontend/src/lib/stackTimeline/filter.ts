import { TIMELINE_KINDS, type TimelineColumn, type TimelineKind } from './model'

export interface TimelineFilter {
  /** The kind chips that are on. */
  kinds: ReadonlySet<TimelineKind>
  /** A `AttributeFamily` name, or null for All. */
  family: string | null
  /** The desk selection's heads, groups expanded, for *Selection only*; null leaves it off. */
  selectionHeads: ReadonlySet<string> | null
  /** Scenery elements' uuids this window hides; absent hides none. */
  hiddenScenery?: ReadonlySet<string>
}

/** Whether [column] passes the family and *Selection only* filters — the kind chips aside. */
function passesFacets(column: TimelineColumn, filter: Pick<TimelineFilter, 'family' | 'selectionHeads' | 'hiddenScenery'>): boolean {
  // Scenery is no attribute and lights no head: both filters keep it, and only its own pick hides it.
  if (column.kind === 'scenery') return column.elementUuid == null || !(filter.hiddenScenery?.has(column.elementUuid) ?? false)
  if (filter.family != null && !column.families.includes(filter.family)) return false
  if (filter.selectionHeads != null && !column.heads.some((head) => filter.selectionHeads!.has(head))) return false
  return true
}

/**
 * The columns Row B leaves (§3.7, §4.3): the kind chips; the family filter keeping a Look, palette
 * or effect column whose families include it; *Selection only* keeping one whose heads meet the
 * desk selection; the scenery pick leaving out the elements it hides. Order is kept.
 */
export function filter(columns: readonly TimelineColumn[], f: TimelineFilter): TimelineColumn[] {
  return columns.filter((c) => f.kinds.has(c.kind) && passesFacets(c, f))
}

/** Each kind chip's count: the columns of that kind the other filters leave. */
export function kindCounts(
  columns: readonly TimelineColumn[],
  f: Pick<TimelineFilter, 'family' | 'selectionHeads' | 'hiddenScenery'>,
): Record<TimelineKind, number> {
  const out = Object.fromEntries(TIMELINE_KINDS.map((k) => [k, 0])) as Record<TimelineKind, number>
  for (const column of columns) if (passesFacets(column, f)) out[column.kind] += 1
  return out
}
