import { useCallback, useSyncExternalStore } from 'react'

const media = (query: string): MediaQueryList | null =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query) : null

/**
 * Whether a media query matches, followed as it changes. False where nothing can say — no window,
 * or no `matchMedia` (jsdom) — unlike `useMediaQuery`, which assumes one.
 */
export function useMediaMatch(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = media(query)
      list?.addEventListener('change', onChange)
      return () => list?.removeEventListener('change', onChange)
    },
    [query],
  )
  return useSyncExternalStore(subscribe, () => media(query)?.matches ?? false, () => false)
}
