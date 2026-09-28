import { Maximize2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { dismissReturnToFullscreen, enterFullscreen, useFullscreenState } from '@/lib/fullscreen'

/**
 * *Return to full screen* — the one-tap gesture for the two cases that cannot enter full screen by
 * themselves (multi-screen plan §3.6): a reload of a window that was full screen (the
 * `sessionStorage` flag), and a `windows.fullscreen {on:true}` from another screen. Both arrive
 * with no user activation to spend, and `requestFullscreen` needs one, so the page asks for it.
 *
 * At the top of `<main>`, in the scroll column beside `SyncReauthBanner`, so it shifts neither the
 * ShowBar nor the panels; nothing at all unless asked. A banner and not a toast, because a toast
 * times out and this is a state the window is in until the operator answers it.
 */
export function ReturnToFullscreenBanner() {
  const { active, wanted } = useFullscreenState()
  if (active || !wanted) return null
  return (
    <div
      role="status"
      className="flex items-center gap-3 border-b border-primary/30 bg-primary/10 px-3 py-1.5 text-sm"
    >
      <Maximize2 className="size-4 shrink-0 text-primary" />
      {/* Cause-neutral on purpose: the ask comes from a reload of a window that was full screen
          *and* from another window's request, and the state records only that it stands. */}
      <span className="min-w-0 flex-1 truncate">This window is meant to be full screen.</span>
      <Button size="sm" className="h-7" onClick={() => void enterFullscreen()}>
        Return to full screen
      </Button>
      <Button
        variant="ghost"
        size="icon"
        className="size-7"
        aria-label="Stay in the browser tab"
        onClick={dismissReturnToFullscreen}
      >
        <X className="size-4" />
      </Button>
    </div>
  )
}
