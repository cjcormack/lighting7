import { useMemo } from 'react'

/**
 * Drop infrastructure entries (`Fixture.infrastructure`, `FixturePatch.infrastructure`) from a
 * fixture or patch list — the one rule every view but Patches and Channels enumerates the rig
 * through. Hands back the same array when there is nothing to drop, so a memo keyed on the list
 * does not churn on a rig with no infrastructure in it.
 *
 * Pure, and kept out of `store/` so a `lib/` module can apply it without importing the store.
 */
export function withoutInfrastructure<L extends readonly { infrastructure?: boolean }[] | undefined>(list: L): L {
  if (list == null || !list.some((item) => item.infrastructure)) return list
  return list.filter((item) => !item.infrastructure) as unknown as L
}

/**
 * A list query's result with infrastructure left out of its `data` — the one body both
 * `useVisibleFixtureListQuery` and `useVisiblePatchListQuery` wrap. The result keeps its own
 * identity whenever nothing was dropped, and otherwise changes only when the result or the
 * filtered list does, so a caller that holds the whole object does not re-render every time.
 *
 * The list rides beside the result (`hook(result, result.data)`) and `data` is restated in the
 * return type, because RTK Query's hook result loses its `data` type (and, spread, every field but
 * `refetch`) once it passes through a generic.
 */
export function useWithoutInfrastructure<R extends object, T extends { infrastructure?: boolean }>(
  result: R,
  list: T[] | undefined,
): R & { data: T[] | undefined } {
  const data = useMemo(() => withoutInfrastructure(list), [list])
  return useMemo(
    () => (data === list ? (result as R & { data: T[] | undefined }) : Object.assign({}, result, { data })),
    [result, list, data],
  )
}
