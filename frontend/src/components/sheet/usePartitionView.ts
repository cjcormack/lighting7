import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'

/**
 * **A library's partition as a view, never a route** (library-sheets plan D3) — a `?param=` plus a
 * remembered value, both the route's, as `routes/Templates.tsx` owns `?family=` and `looks.family`.
 * Written for the Scripts and FX Library routes, which partition by type and by category.
 *
 * - The first render reads the remembered value — once, never written back on arrival, since a
 *   hook that wrote on mount would make the *arrival* the choice (`getStoredLookFamily`'s reason).
 * - A `?param=` arriving (a bookmark, a Cmd+K deep link) wins and is remembered.
 * - A change is remembered and mirrored into the URL with `replace`, so a reload or a shared link
 *   lands on the same partition and flipping chips is not a history entry. *All* drops the param.
 *
 * [parse] reads a slug back to a partition, null for one the library does not know — which leaves
 * the view where it was rather than filtering to nothing. [slug] is the URL spelling.
 */
export function usePartitionView<V extends string>({
  param,
  storageKey,
  parse,
  slug,
}: {
  param: string
  storageKey: string
  parse: (raw: string) => V | null
  slug: (value: V) => string
}): [V | 'ALL', (next: V | 'ALL') => void] {
  const [searchParams, setSearchParams] = useSearchParams()
  const [value, setValue] = useState<V | 'ALL'>(() => readStored(storageKey, parse))

  const change = useCallback(
    (next: V | 'ALL') => {
      setValue(next)
      writeStored(storageKey, next === 'ALL' ? 'all' : slug(next))
      const params = new URLSearchParams(searchParams)
      if (next === 'ALL') params.delete(param)
      else params.set(param, slug(next))
      setSearchParams(params, { replace: true })
    },
    [param, searchParams, setSearchParams, slug, storageKey],
  )

  const arrived = searchParams.get(param)
  useEffect(() => {
    if (arrived == null) return
    const parsed = arrived.toLowerCase() === 'all' ? 'ALL' : parse(arrived)
    if (parsed == null) return
    setValue(parsed)
    writeStored(storageKey, parsed === 'ALL' ? 'all' : slug(parsed))
  }, [arrived, parse, slug, storageKey])

  return [value, change]
}

function readStored<V extends string>(key: string, parse: (raw: string) => V | null): V | 'ALL' {
  try {
    const raw = localStorage.getItem(key)
    if (raw == null || raw.toLowerCase() === 'all') return 'ALL'
    return parse(raw) ?? 'ALL'
  } catch {
    return 'ALL'
  }
}

function writeStored(key: string, raw: string) {
  try {
    localStorage.setItem(key, raw)
  } catch {
    // Storage unavailable (private mode, quota) — stickiness just degrades.
  }
}
