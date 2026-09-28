/**
 * The name a **Duplicate** gives its copy — `Warm Wash (Copy)`, then `(Copy 2)`, `(Copy 3)` — worked
 * out client-side against the names the library already holds, as the Look library always has
 * (library-sheets plan D10). The copy route refuses a clash with a 409, so a name the client picks
 * badly costs a toast, never a second record.
 *
 * [taken] is **mutated**: the minted name is added to it, so a batch that duplicates two records of
 * one name — or a record and its own earlier copy — mints distinct names without asking the list,
 * which has not refetched between two copies of one batch.
 */
export function duplicateName(name: string, taken: Set<string>): string {
  let next = `${name} (Copy)`
  if (taken.has(next)) {
    let n = 2
    while (taken.has(`${name} (Copy ${n})`)) n++
    next = `${name} (Copy ${n})`
  }
  taken.add(next)
  return next
}
