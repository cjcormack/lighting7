import type { SheetRow } from './sheetModel'

/**
 * Interleave **divider rows** by partition, in the partition's declared order — the grouping a
 * library sheet draws under *All* (library-sheets plan D3: chips filter, dividers group). A script
 * type, an effect category, a template family: each partition present gets one divider row above
 * its members, and a partition with no members gets none.
 *
 * Pure, and generic over the row: the caller mints the divider through [divider], because a
 * sheet's row type is its own and `SheetTable` already has the divider arm (a row with `divider`
 * set draws its label across the row and cannot be selected). Rows keep their order within a
 * partition; a row whose partition is not in [order] goes last, under [unlisted] when that is given
 * and ungrouped otherwise, so a partition the vocabulary does not know yet is never dropped.
 */
export function groupRows<Row extends SheetRow, K extends string>(
  rows: readonly Row[],
  {
    partition,
    order,
    divider,
    unlisted,
  }: {
    /** Which partition a row belongs to. */
    partition: (row: Row) => K | string
    /** The partitions in their declared order. */
    order: readonly K[]
    /** Mint the divider row for a partition — its id must not collide with a member's. */
    divider: (key: K | string) => Row
    /** The divider over rows in no declared partition. Absent, those rows follow ungrouped. */
    unlisted?: string
  },
): Row[] {
  const buckets = new Map<string, Row[]>()
  for (const row of rows) {
    if (row.divider != null) continue
    const key = partition(row)
    const bucket = buckets.get(key)
    if (bucket) bucket.push(row)
    else buckets.set(key, [row])
  }
  const out: Row[] = []
  const declared = new Set<string>(order)
  for (const key of order) {
    const members = buckets.get(key)
    if (!members || members.length === 0) continue
    out.push(divider(key), ...members)
  }
  const rest = [...buckets.entries()].filter(([key]) => !declared.has(key)).flatMap(([, members]) => members)
  if (rest.length > 0) {
    if (unlisted != null) out.push(divider(unlisted))
    out.push(...rest)
  }
  return out
}
