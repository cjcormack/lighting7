import type { CueTarget } from '@/api/cuesApi'
import type { CueReferenceRow } from './CueRowStore'

/**
 * The cue's reference rows the Values selection reaches (palette-references plan D22), by uuid for
 * *Copy the values*: a row on a selected target, or one on — or resolved onto — a head the selection
 * stands for ([heads]: a group as its members, a fixture as itself, a cell as its cell).
 */
export function cueReferencesIn(
  rows: readonly CueReferenceRow[],
  selection: readonly CueTarget[],
  heads: readonly string[],
): Set<string> {
  const picked = new Set<string>()
  if (selection.length === 0 && heads.length === 0) return picked
  const named = new Set(selection.map((t) => `${t.type}\u0000${t.key}`))
  const selectedHeads = new Set(heads)
  for (const row of rows) {
    const reached =
      named.has(`${row.targetType}\u0000${row.targetKey}`) ||
      (row.targetType === 'fixture' && selectedHeads.has(row.targetKey)) ||
      row.heads.some((head) => selectedHeads.has(head))
    if (reached) picked.add(row.uuid)
  }
  return picked
}
