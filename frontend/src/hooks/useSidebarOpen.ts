import { useCallback, useState } from 'react'
import { usePersistentState } from './usePersistentState'

/**
 * The app sidebar's open state: **one** preference for the whole app (venue-visit plan D4).
 *
 * It used to be two — the four live views on the 64px rail, everywhere else open, each remembered
 * apart (space plan D7). On the night that read as a sidebar changing shape every time the operator
 * moved between the programmer and the fixtures list, so the split went. What hides chrome on
 * purpose is **immersive** (`lib/immersive.ts`, ⌘K and the `ShowHeader`'s expand glyph), which
 * `Layout` applies by *not drawing* the sidebar on a live view. It never writes this preference, so
 * a sidebar hidden by immersive comes back exactly as open or as collapsed as it was.
 *
 * Default open, as the rest of the app always was: off the live views the sidebar *is* the
 * navigation.
 *
 * **The old keys migrate by reading, once.** With nothing stored under the new key, the first mount
 * starts from `layout.sidebarOpen.other`, the half of the app where the sidebar is the navigation;
 * `usePersistentState` then writes the new key, so the old one is never read again. The live
 * half's answer is not consulted: `Layout` mounted both preferences on every route, so both keys
 * were written with their defaults whether or not anyone chose them, and the live default
 * (collapsed) is exactly the shape D4 removes. The old keys are left where they are, so a desk
 * rolled back to an older build finds its two answers intact.
 *
 * A hook of its own rather than a `usePersistentState` call inline in `Layout`, so the key and the
 * migration are reachable by a test without mounting the whole desk.
 */
const SIDEBAR_KEY = 'layout.sidebarOpen'
const LEGACY_SIDEBAR_KEY_OTHER = 'layout.sidebarOpen.other'

/** The pre-D4 non-live preference, if one is stored as a boolean; else open. */
function legacyOpen(): boolean {
  try {
    const raw = window.localStorage.getItem(LEGACY_SIDEBAR_KEY_OTHER)
    if (raw == null) return true
    const parsed: unknown = JSON.parse(raw)
    return typeof parsed === 'boolean' ? parsed : true
  } catch {
    return true
  }
}

export function useSidebarOpen(): { open: boolean; toggle: () => void } {
  // Read once: `usePersistentState` only consults its fallback in its own initialiser, and a
  // storage read on every `Layout` render would be paid for nothing.
  const [fallback] = useState(legacyOpen)
  const [open, setOpen] = usePersistentState<boolean>(SIDEBAR_KEY, fallback)

  const toggle = useCallback(() => setOpen(prev => !prev), [setOpen])

  return { open, toggle }
}
