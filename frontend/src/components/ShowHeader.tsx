import { useState, type ReactNode } from 'react'
import { Menu, Play, Square } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import { CHROME_ROW_CLASS } from './sheet/sheetFrame'
import { useImmersive } from '@/lib/immersive'
import { MD_BREAKPOINT, useMediaQuery } from '@/hooks/useMediaQuery'
import { useIsDeskConnected } from '@/store/status'
import { Breadcrumbs } from './Breadcrumbs'
import { useOpenMobileDrawer } from './mobileDrawerContext'
import { ImmersiveToggle } from './ImmersiveToggle'
import { SaveStatusIndicator } from './SaveStatusIndicator'
import { ViewSwitcher, type ShowView } from './ViewSwitcher'

const PAGE_LABEL: Record<ShowView, string> = {
  programmer: 'Programmer',
  show: 'Show',
  'prompt-book': 'Prompt Book',
  busk: 'Busk',
}

interface ShowHeaderProps {
  /** Drives BOTH the breadcrumb's current-page label and the switcher's active pill. */
  view: ShowView
  projectId: number
  projectName: string
  /**
   * Called when the current-page segment is clicked — Show uses it to leave a drilled stack for the
   * stack list.
   *
   * There used to be an `extra` prop for breadcrumb drill segments, which only Show passed and only
   * to append the drilled stack's name. It was dropped so the live views read identically:
   * `Projects > Project > <View>` everywhere. `Breadcrumbs` kept `extra` for the busk view, which
   * listed the selected targets there and opened the target picker from them; the target band
   * carries both jobs now, so the prop is gone from `Breadcrumbs` too.
   */
  onCurrentPageClick?: () => void
  isShowActive: boolean
  /** Only consulted when the show is stopped — gates the Start button. */
  canStart: boolean
  onStart: () => void
  /** Awaited on confirm; the dialog closes on resolve and stays open on throw. */
  onStop: () => Promise<void>
  /**
   * View-specific buttons, rendered to the LEFT of the switcher so an appearing one never shifts
   * the common controls between views.
   *
   * Held open through two sessions for exactly this: the merged Show view puts its edit-lock toggle
   * and re-lock countdown here (`ShowLockControl`). The lock belongs beside the view switcher rather
   * than in the page body because it changes what the whole view will accept.
   */
  actions?: ReactNode
  /**
   * The show is running and the operator has unlocked it.
   *
   * Washes the header amber, matching what the Prompt Book's toolbar used to do on its own. The
   * signal is for the *unlocked* state, not the locked one: locked is the quiet default, and
   * believing you are locked when you are not is how a show gets edited by accident. With the show
   * stopped there is no lock to be wrong about, so no wash.
   */
  unlockedWarning?: boolean
}

/**
 * Shared header for the four live-show views (Programmer · Show · Prompt Book · Busk): breadcrumbs
 * on the left, then any view-specific actions, the immersive glyph, the view switcher, a single
 * Start/Stop button, and an always-visible status dot. The switcher, Start/Stop button, and dot
 * are right-anchored so they hold the same position across views regardless of which view
 * contributes actions. Owns the stop-confirmation dialog so every view gets the same guard.
 *
 * **It is the row immersive leaves standing, and it carries the way back** (busk-chrome plan
 * D7, D11, D12; `Immersive.dc.html`). Three things it draws itself, so all four hosts get them
 * with no per-host wiring:
 *
 * - **The expand glyph** (`ImmersiveToggle`), after the host's `actions` and before the switcher:
 *   outward arrows with the app drawn, inward arrows lit while immersive. One control, both ways
 *   — folding this row too would take the switcher, Stop, the dot and the way out with it, and
 *   the way back would be a floating button over a live view, which the full-screen work already
 *   refused (D11). It is not full screen and the two compose (D8). The four hosts' loading and
 *   not-found arms render no header, so they draw `ImmersiveEscape` — the same glyph — instead.
 * - **A red *Offline* chip, only while immersive and the socket is down** (D10). The app header's
 *   connection pill goes with the header; a chip that reads *Connected* all night is what
 *   immersive exists to remove, so only the state that matters comes back.
 * - **The mobile drawer's button, below `md` while immersive** (D10): there the hamburger in the
 *   app header is the only navigation, so it moves to this row's left edge. The drawer itself
 *   stays `Layout`'s; `mobileDrawerContext.ts` lends the opener.
 */
export function ShowHeader({
  view,
  projectId,
  projectName,
  onCurrentPageClick,
  isShowActive,
  canStart,
  onStart,
  onStop,
  actions,
  unlockedWarning = false,
}: ShowHeaderProps) {
  const [stopConfirmOpen, setStopConfirmOpen] = useState(false)
  const [stopping, setStopping] = useState(false)
  const immersive = useImmersive()
  const connected = useIsDeskConnected()
  const isDesktop = useMediaQuery(MD_BREAKPOINT)
  const openDrawer = useOpenMobileDrawer()

  const handleConfirmStop = async () => {
    setStopping(true)
    try {
      await onStop()
      setStopConfirmOpen(false)
    } catch {
      // Keep the dialog open so the operator can retry.
    } finally {
      setStopping(false)
    }
  }

  return (
    // The transparent border is always present so colouring it cannot shift the layout by a pixel
    // when the lock flips.
    //
    // `CHROME_ROW_CLASS` at every height — the shell's own 40px chrome row, `h-10 px-3` with its
    // 1px border inside the 40, holding 32px controls (busk-chrome plan D12; `Immersive.dc.html`
    // draws it at `height: 40px` border-box, border included). It was `p-4`, then `px-3 py-2`
    // (the chrome tidy-up in `lighting7/docs/plans/programmer-chrome-design/`, whose "48 at every
    // height" D12 revises): every other chrome row on these views is 40 with 32px controls, and
    // this one was the last at 48 — 8px above and below the same controls, spent on nothing, on
    // the row every other row's left edge is read against. The plan spelled the change `py-1`,
    // which measured **41** on the box in the browser (32 + 8 + the border); the class the other
    // rows use is the height, not the padding, so this row takes the same class rather than a
    // second statement of the number. Chrome above the busk band went 152 → 88 → 40 with the bar
    // gone and this row at 40; immersive, it is the only chrome above the band. The switcher's
    // pills are 30 (24px items in a 2px pad and a 1px border), Start/Stop and the glyph 32, so
    // nothing inside is taller than the row's inset allows. Nothing is removed — the same header,
    // tighter, and it is shared, so all four live views take it.
    //
    // Show and the Prompt Book carry a `ShowBar` under it (Busk did too, until the busk-chrome
    // plan's session A moved its transport into the side sheet's Show tab), which keeps its own
    // `@[440px]:px-4` (see the note in `ShowBar.tsx` for why that class is the bar's business):
    // on those views the header and
    // the bar now differ by 4px of gutter. Deliberate — the bar's ladder was measured at 16 and
    // is left alone.
    <div
      className={cn(
        CHROME_ROW_CLASS,
        '@container gap-3 border-transparent transition-colors',
        unlockedWarning && UNLOCKED_WARNING_CLASS,
      )}
    >
      {/* Below `md` and immersive, the drawer's button (D10): the app header that held the
          hamburger is not drawn, and this row is the only navigation left on the screen. */}
      {immersive && !isDesktop && openDrawer != null && (
        <Button variant="ghost" size="icon-sm" className="-ml-1 shrink-0" onClick={openDrawer} aria-label="Open navigation">
          <Menu className="size-5" />
        </Button>
      )}
      <div className="flex-1 min-w-0">
        <Breadcrumbs
          projectName={projectName}
          currentPage={PAGE_LABEL[view]}
          collapsedLabel={PAGE_LABEL[view]}
          onCurrentPageClick={onCurrentPageClick}
        />
      </div>

      <div className="flex items-center gap-2 shrink-0">
        {/* Offline, and only offline, and only while immersive (D10): with the app header drawn its
            connection pill says this already. First in the group for the save pill's reason. */}
        {immersive && !connected && (
          <Badge variant="destructive" className="h-5 shrink-0 px-2 text-[10px] uppercase tracking-wide" title="The desk's socket is down">
            Offline
          </Badge>
        )}
        {/* Ahead of the view-specific actions: everything to its right is fixed-position, so the
            pill appearing and clearing can't shift the Start/Stop button under the cursor. */}
        <SaveStatusIndicator />
        {actions}
        <ImmersiveToggle immersive={immersive} />
        <ViewSwitcher current={view} projectId={projectId} />
        {isShowActive ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="destructive"
                size="sm"
                onClick={() => setStopConfirmOpen(true)}
                aria-label="Stop show"
              >
                <Square className="size-3.5" />
                <span className="hidden @[420px]:inline">Stop</span>
              </Button>
            </TooltipTrigger>
            <TooltipContent side="bottom">Stop show</TooltipContent>
          </Tooltip>
        ) : (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button size="sm" onClick={onStart} disabled={!canStart} aria-label="Start show">
                <Play className="size-3.5" />
                <span className="hidden @[420px]:inline">Start</span>
              </Button>
            </TooltipTrigger>
            <TooltipContent side="bottom">Start show</TooltipContent>
          </Tooltip>
        )}
        <span
          className={cn(
            'size-3 shrink-0 rounded-full ml-1',
            isShowActive ? 'bg-green-500 shadow-[0_0_6px_#22c55e]' : 'bg-muted-foreground/40',
          )}
          aria-label={isShowActive ? 'Show is running' : 'Show is stopped'}
          title={isShowActive ? 'Show is running' : 'Show is stopped'}
        />
      </div>

      <Dialog
        open={stopConfirmOpen}
        onOpenChange={(open) => {
          if (!stopping) setStopConfirmOpen(open)
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Stop the show?</DialogTitle>
          </DialogHeader>
          <DialogDescription>
            This will deactivate the show and clear the active cue. You can start it again from any
            view at any time.
          </DialogDescription>
          <DialogFooter>
            <Button variant="outline" onClick={() => setStopConfirmOpen(false)} disabled={stopping}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleConfirmStop} disabled={stopping}>
              {stopping ? 'Stopping…' : 'Stop Show'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
