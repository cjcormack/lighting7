import { useCallback, useEffect, useState } from 'react'

/**
 * State backed by localStorage, JSON-encoded.
 *
 * Reads once on mount (lazily, via useState's initialiser) and writes on every
 * change. All storage access is wrapped: `localStorage` throws on quota
 * exhaustion and in some private-browsing modes, and a persisted UI preference
 * is never worth taking the app down for.
 *
 * **One mounted instance per key.** There is no `storage` listener and no cross-instance sync, so
 * two components sharing a key hold two snapshots and drift apart the moment one writes. Where two
 * surfaces genuinely share a preference, build it on `lib/syncStore.ts` instead —
 * `lib/programmerFade.ts` and `hooks/useVisSource.ts` are the two that had to.
 *
 * `merge` matters for object-shaped state: a value written by an older build
 * won't have keys added since, so the raw parse is spread over the fallback
 * rather than used directly. Without it, adding a field to a persisted object
 * silently yields `undefined` for every existing user.
 */
export function usePersistentState<T>(
  key: string,
  fallback: T,
  options: { merge?: boolean } = {},
): [T, (next: T | ((prev: T) => T)) => void] {
  const { merge = false } = options
  // The initialiser runs once on mount; `key` and `fallback` are module
  // constants at every call site, so a later change to either doesn't re-read.
  const [value, setValue] = useState<T>(() => readStored(key, fallback, merge))

  useEffect(() => {
    try {
      window.localStorage.setItem(key, JSON.stringify(value))
    } catch {
      // Quota exhausted or storage unavailable — the in-memory value still works.
    }
  }, [key, value])

  return [value, setValue]
}

function readStored<T>(key: string, fallback: T, merge: boolean): T {
  if (typeof window === 'undefined') return fallback
  try {
    const raw = window.localStorage.getItem(key)
    if (raw == null) return fallback
    const parsed = JSON.parse(raw) as T
    if (
      merge &&
      parsed != null &&
      typeof parsed === 'object' &&
      fallback != null &&
      typeof fallback === 'object'
    ) {
      return { ...fallback, ...parsed }
    }
    return parsed
  } catch {
    // Absent, unparseable, or written by an incompatible build.
    return fallback
  }
}

/**
 * The collapsible-panel pattern: one persisted boolean plus `toggle` / `hide`.
 * Called once per panel from `components/overviewPanels.tsx`, which is the one
 * owner of that list — stage, fixture, speed masters and cue slots today. They
 * were four byte-identical copies of this before, and the list is named here
 * rather than counted, since it has already outlived one panel (Effects
 * Overview, deleted) and gained another.
 *
 * Booleans round-trip through JSON as the bare strings `"true"` / `"false"`,
 * which is exactly what those copies wrote, so previously-stored preferences
 * are read back unchanged.
 */
export function usePersistentToggle(key: string, fallback = false) {
  const [isVisible, setIsVisible] = usePersistentState<boolean>(key, fallback)

  const toggle = useCallback(() => setIsVisible((prev) => !prev), [setIsVisible])
  const show = useCallback(() => setIsVisible(true), [setIsVisible])
  const hide = useCallback(() => setIsVisible(false), [setIsVisible])

  return { isVisible, setIsVisible, toggle, show, hide }
}
