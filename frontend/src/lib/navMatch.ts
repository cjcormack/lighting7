/** The shape `mostSpecificActiveId` needs: everything else on a nav item is irrelevant here. */
export interface PathMatchable {
  id: string
  pathMatch: string
}

/**
 * Whether `pathname` carries `segment` as a whole trailing *segment* — `endsWith(s)` or
 * `includes(s + '/')`, deliberately **not** `startsWith` and not a bare `includes`.
 *
 * A pathname is `/projects/7/looks` rather than `/looks`, so a prefix test is the wrong shape to
 * start with; and an unanchored substring or regex test cannot keep routes whose names prefix one
 * another apart. `/programmer` does not match `/program` here, because it neither ends with it nor
 * contains `/program/` — and `/fx-library` does not match `/fx`, which is the trap this exists to
 * close. The tree has fallen into it three times over: `ProgrammerIndicator` carries a warning
 * about the same reasoning done by hand, and `Layout`'s FX lock was doing it with an unanchored
 * regex until this became shared.
 */
export function pathHasSegment(pathname: string, segment: string): boolean {
  return pathname.endsWith(segment) || pathname.includes(segment + '/')
}

/**
 * Among all visible nav items whose `pathMatch` appears in the current pathname, return the id of
 * the most specific one — longest `pathMatch` wins. This keeps a parent like "Project Settings"
 * from staying active while the operator is on one of its child tabs (e.g. "Surfaces").
 *
 * Matching is [pathHasSegment] — see there for why it has to be segment-aware.
 *
 * Extracted from `ProjectSwitcher` so that guarantee can be pinned by a test rather than described
 * in a comment. It was load-bearing twice over: it is why a `programmer` nav entry is safe to add
 * beside the cue-authoring one, and the same reasoning done by hand with `startsWith` is what the
 * warning in `ProgrammerIndicator` is about.
 */
export function mostSpecificActiveId<T extends PathMatchable>(
  items: readonly T[],
  pathname: string,
): string | null {
  let winner: T | null = null
  for (const item of items) {
    const m = item.pathMatch
    if (!pathHasSegment(pathname, m)) continue
    if (!winner || m.length > winner.pathMatch.length) winner = item
  }
  return winner?.id ?? null
}
