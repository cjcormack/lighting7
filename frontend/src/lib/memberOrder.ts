/**
 * A group's member order, rearranged the way the desk rearranges it (`reorderGroupMembers`,
 * lighting7 `models/fixtureGroups.kt`; venue-visit plan S7): the members [ordered] names fill, in
 * that order, the places the members it names held, and every member it does not name keeps its
 * place. That is what lets a view reorder only what it shows — the Groups views leave an
 * infrastructure head out, a show cannot see a venue patch it hides — and send just that. A key
 * that is not a member, and a repeat, are ignored.
 *
 * The client's copy exists for the optimistic update alone; the PUT's answer and the reload after
 * it are the authority.
 */
export function placeInOrder<T>(all: readonly T[], ordered: readonly string[], keyOf: (item: T) => string): T[] {
  const byKey = new Map(all.map((item) => [keyOf(item), item]))
  const named: T[] = []
  const seen = new Set<string>()
  for (const key of ordered) {
    const item = byKey.get(key)
    if (item === undefined || seen.has(key)) continue
    seen.add(key)
    named.push(item)
  }
  let next = 0
  return all.map((item) => (seen.has(keyOf(item)) ? named[next++] : item))
}

/**
 * [keys] — a group's members as this side found them — in the group's own order, `memberKeys`
 * (`GET /groups`, venue-visit plan S7). A key the order does not name keeps its place after the
 * ones it does, in the order given; with no order, [keys] as they are. What lets a client copy of
 * the desk's rig order (`state/BuskRigOrder.kt` walks a group's members in this order) agree with
 * the desk once a group has been reordered.
 */
export function inGroupOrder(keys: readonly string[], memberKeys: readonly string[] | undefined): string[] {
  if (memberKeys == null || memberKeys.length === 0) return [...keys]
  const position = new Map(memberKeys.map((key, i) => [key, i]))
  return keys
    .map((key, i) => ({ key, at: position.get(key) ?? memberKeys.length + i }))
    .sort((a, b) => a.at - b.at)
    .map(({ key }) => key)
}
