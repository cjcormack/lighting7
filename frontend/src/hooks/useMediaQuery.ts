import { useState, useEffect } from "react"

export const SM_BREAKPOINT = "(min-width: 640px)"
/**
 * Tailwind's `md`: where `Layout` swaps the mobile drawer for the desktop sidebar, and so where
 * the drawer's button is drawn on the `ShowHeader` while a window is immersive (busk-chrome plan
 * D10). One constant so the two cannot disagree about which side of the line a width is on.
 */
export const MD_BREAKPOINT = "(min-width: 768px)"

export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window !== "undefined") {
      return window.matchMedia(query).matches
    }
    return false
  })

  useEffect(() => {
    const mediaQuery = window.matchMedia(query)
    const handler = (event: MediaQueryListEvent) => setMatches(event.matches)

    mediaQuery.addEventListener("change", handler)
    return () => mediaQuery.removeEventListener("change", handler)
  }, [query])

  return matches
}
