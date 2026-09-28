import { createContext, useContext } from 'react'

/**
 * The one way into the mobile navigation drawer from below `Layout` (busk-chrome plan D10).
 *
 * Below `md` the hamburger in the app header is the only navigation there is, and an immersive
 * window has no app header — so the `ShowHeader` draws the drawer's button at its left edge while
 * immersive. The drawer and its open state stay `Layout`'s; what is lifted is only the *opener*,
 * provided here so a header rendered under the `<Outlet>` can reach it without a prop threaded
 * through every route. Null outside `Layout` (a test, a public page), where there is no drawer
 * to open and the header draws no button.
 *
 * A `.ts` module holding a context and a hook, no component, so React Refresh keeps applying to
 * the files that import it (CLAUDE.md §React/Frontend Conventions).
 */
export const MobileDrawerContext = createContext<(() => void) | null>(null)

/** The drawer's opener, or null where no drawer is provided. */
export function useOpenMobileDrawer(): (() => void) | null {
  return useContext(MobileDrawerContext)
}
