import { useEffect, useState, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** Must match the `duration-200` on the grid transition below. */
const COLLAPSE_MS = 200

interface CollapsiblePanelProps {
  isVisible: boolean
  children: ReactNode
  /** Extra classes on the grid wrapper — margins that should only apply while open. */
  className?: string
  /**
   * Keep the body mounted even while collapsed. For the case where unmounting would destroy
   * something in flight — a drag whose drop targets live inside the body. Clearing it lets the
   * normal collapse timer run.
   */
  holdMounted?: boolean
}

/**
 * The shared shell for the overview panels Layout mounts under the header.
 *
 * The open/close animation is a CSS grid-rows collapse, so the *wrapper* has to stay mounted for
 * the panel to animate out at all — which is why Layout renders all four unconditionally. The
 * body must not stay with it: a collapsed panel that keeps rendering is a live rig's worth of
 * work behind a zero-height container on every route (the mini-stage's markers re-rendering at
 * frame rate, the effects panel's beat interval, the cue-slot panel's queries and its wheel and
 * pointer listeners), all of it for a panel nobody can see.
 *
 * So the body is mounted while the panel is open and for one collapse's worth of time after it
 * closes, then unmounted. Reopening does not flash empty: RTK Query keeps an unsubscribed
 * cache entry for a minute, so a remount inside that window renders from cache with no loading
 * state, and past it the refetch is the same one a fresh page load would do.
 *
 * `children` must therefore be a component, not inline JSX with hooks in the caller — the point
 * is that the subscribing hooks live below this boundary.
 */
export function CollapsiblePanel({
  isVisible,
  children,
  className,
  holdMounted = false,
}: CollapsiblePanelProps) {
  const [isMounted, setIsMounted] = useState(isVisible)

  useEffect(() => {
    if (isVisible || holdMounted) {
      setIsMounted(true)
      return
    }
    const timer = setTimeout(() => setIsMounted(false), COLLAPSE_MS)
    return () => clearTimeout(timer)
  }, [isVisible, holdMounted])

  return (
    <div
      className={cn(
        'grid transition-all duration-200 ease-in-out',
        isVisible ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]',
        className,
      )}
    >
      <div className="overflow-hidden">{isMounted ? children : null}</div>
    </div>
  )
}
