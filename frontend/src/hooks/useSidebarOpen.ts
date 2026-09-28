import { useCallback } from 'react'
import { useLocation } from 'react-router'
import { usePersistentState } from './usePersistentState'
import { isLiveViewPath } from '@/lib/liveViews'

/**
 * The app sidebar's open state, remembered **twice** — once for the four live views, once for
 * everywhere else (space plan D7; `lib/liveViews.ts` owns which routes are which, and why).
 *
 * Two preferences rather than one, because the two answers genuinely differ: on the Programmer,
 * Show, the Prompt Book and Busk, 176px of width is worth more than a list of destinations, and
 * `ShowHeader`'s pill switcher is how those four move between each other anyway. Everywhere else
 * the sidebar *is* the navigation. So they default apart — collapsed and open — and an operator who
 * opens it on the Programmer keeps it there without also forcing it shut on the fixtures list.
 *
 * A hook of its own rather than two `usePersistentState` calls inline in `Layout`, so the wiring
 * that actually matters — **which of the two the toggle writes** — is reachable by a test without
 * mounting the whole desk. Getting it wrong is silent: the sidebar would still open and close, it
 * would just forget on the next navigation, or forget on the wrong half of the app.
 *
 * Two keys rather than one object under one key, unlike `useStageView`'s flags: only one of the two
 * is ever read at a time, so there is nothing to read together, and keeping them apart means a
 * value written by an older build for one route group cannot change how the other is read.
 */
const SIDEBAR_KEY_LIVE = 'layout.sidebarOpen.live'
const SIDEBAR_KEY_OTHER = 'layout.sidebarOpen.other'

export function useSidebarOpen(): { open: boolean; toggle: () => void } {
  const location = useLocation()
  const [liveOpen, setLiveOpen] = usePersistentState<boolean>(SIDEBAR_KEY_LIVE, false)
  const [otherOpen, setOtherOpen] = usePersistentState<boolean>(SIDEBAR_KEY_OTHER, true)

  const onLiveView = isLiveViewPath(location.pathname)

  const toggle = useCallback(() => {
    if (onLiveView) setLiveOpen(prev => !prev)
    else setOtherOpen(prev => !prev)
  }, [onLiveView, setLiveOpen, setOtherOpen])

  return { open: onLiveView ? liveOpen : otherOpen, toggle }
}
