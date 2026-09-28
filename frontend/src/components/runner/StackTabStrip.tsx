import { useEffect, useRef } from 'react'
import { useScrollEdges } from '@/hooks/useScrollEdges'
import { ChevronLeft, ChevronRight, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import type { CueStack } from '@/api/cueStacksApi'

/** How much of the visible width a chevron press moves. */
const PAGE_FRACTION = 0.8

/**
 * The stack switcher: one tab per runnable stack, separators drawn inline.
 *
 * **Selecting a tab does not move the playhead.** It used to: the click ran
 * `deactivate(old) → goToStack(target) → deactivate(target)`, so one unconfirmed press took the
 * live cue off stage and repositioned every other client. That is indefensible on a surface whose
 * whole point is now that a stray click cannot change the show, so browsing and arming were split
 * (desk-simplification session 2b). Arming lives in `OffPlayheadBanner`, behind a labelled,
 * confirm-gated control; this strip only chooses what you are *looking at*.
 *
 * The split is why there are two ids rather than one. `selectedStackId` is what you are reading and
 * owns the underline; `liveStackId` is where the show actually is and owns the green pip. They used
 * to be the same value, which is why the pip was drawn only on *unselected* tabs — selected-==-live
 * made "the live tab" and "the selected tab" indistinguishable. Now the live tab can also be the
 * selected one, and must show both.
 *
 * Lifted out of `RunPage` to give it the two things it was missing, both of which matter most on
 * exactly the shows that have enough stacks to overflow:
 *
 *  - **The selected tab is scrolled into view.** The selection can change without a click — a
 *    playhead follow, a deep link, a second desk — and before this the strip would silently select
 *    a tab off the right-hand edge, so the operator saw no change at all. `behavior: 'auto'` rather
 *    than smooth: mid-show a determinate jump beats an animation, and `inline: 'nearest'` already
 *    no-ops when the tab is fully visible.
 *  - **An overflow affordance.** Every child is `shrink-0` and a tab runs 110-160px, so eight
 *    stacks overflow a 900px strip with nothing whatsoever saying so.
 *
 * Deliberately not `role="tablist"`: a tab here navigates rather than revealing a local panel, and
 * `aria-current` says which one you are on. And deliberately no scroll-snap — it fights the
 * programmatic `scrollIntoView` above, and the items are variable-width so it would land
 * off-centre anyway.
 *
 * Renders nothing for a single-stack show; the ShowBar carries the stack name in that case.
 */
export function StackTabStrip({
  stacks,
  selectedStackId,
  liveStackId,
  runnableStackCount,
  onSelectStack,
  unlockedWarning = false,
}: {
  stacks: CueStack[]
  /** The stack being read. Owns the underline and the scroll-into-view. */
  selectedStackId: number | null
  /** The project playhead — where GO acts, whether or not you are looking at it. */
  liveStackId: number | null
  runnableStackCount: number
  onSelectStack: (stack: CueStack) => void
  /** A running show is unlocked — tint with the rest of the chrome band. */
  unlockedWarning?: boolean
}) {
  const scrollerRef = useRef<HTMLDivElement>(null)
  // The shared hook, since session 4 of the space plan: this component, `TemplateStrip` and
  // `FixturesTable` each had their own copy of the same scroll/resize/measure machinery. It
  // observes the tab row inside the scroller as well as the scroller itself, which is what used
  // to be the `stacks` dependency on the layout effect here, and it owns the scroll listener that
  // was this element's `onScroll` prop.
  const { attach, ...overflow } = useScrollEdges(scrollerRef)

  // Reveal the selected tab, including when something other than a click moved it.
  useEffect(() => {
    if (selectedStackId == null) return
    const el = scrollerRef.current?.querySelector(`[data-stack-id="${selectedStackId}"]`)
    el?.scrollIntoView({ inline: 'nearest', block: 'nearest', behavior: 'auto' })
  }, [selectedStackId])

  const page = (direction: -1 | 1) => {
    const el = scrollerRef.current
    if (!el) return
    el.scrollBy({ left: direction * el.clientWidth * PAGE_FRACTION, behavior: 'smooth' })
  }

  if (runnableStackCount <= 1) return null

  return (
    <div
      className={cn(
        'relative flex h-12 shrink-0 items-stretch border-b transition-colors',
        unlockedWarning && UNLOCKED_WARNING_CLASS,
      )}
    >
      <div
        // `attach`, not `scrollerRef` — see `useScrollEdges`; it fills that ref too, which the
        // scroll-into-view and paging effects below still read.
        ref={attach}
        aria-label="Stack tabs"
        className="flex flex-1 items-stretch overflow-x-auto"
      >
        {stacks.map((s) => {
          if (s.type === 'SEPARATOR') {
            return (
              <div
                key={s.id}
                className="flex items-center h-full px-2 gap-1.5 shrink-0 pointer-events-none"
              >
                <div className="w-px h-4 bg-border" />
                <span className="text-xs font-medium uppercase text-muted-foreground whitespace-nowrap">
                  {s.label ?? s.name}
                </span>
                <div className="w-px h-4 bg-border" />
              </div>
            )
          }
          const standardCount = s.cues.filter((c) => c.cueType === 'STANDARD').length
          return (
            <Button
              key={s.id}
              data-stack-id={s.id}
              variant="ghost"
              onClick={() => onSelectStack(s)}
              aria-current={s.id === selectedStackId ? 'page' : undefined}
              className={cn(
                'flex items-center gap-2 px-5 h-full rounded-none border-r text-xs font-medium text-muted-foreground relative shrink-0',
                'hover:text-foreground hover:bg-muted/10',
                s.id === selectedStackId &&
                  'text-foreground bg-muted/20 after:absolute after:bottom-0 after:left-0 after:right-0 after:h-0.5 after:bg-primary',
              )}
            >
              {s.id === liveStackId && (
                <span
                  aria-label="Live"
                  className="size-1.5 rounded-full bg-green-500 shadow-[0_0_6px_currentColor]"
                />
              )}
              {s.name}
              {s.loop && <RotateCcw className="size-3 text-muted-foreground" />}
              <span className="font-mono text-[9.5px] rounded-full border bg-muted/40 px-1.5 text-muted-foreground/80 ml-0.5">
                {standardCount}
              </span>
            </Button>
          )
        })}
      </div>

      {/* Edge affordances — a fade so the cut-off tab reads as continuing, and a press target so
          it can be reached without a trackpad gesture. Both only while there is more that way. */}
      {overflow.left && <ScrollEdge side="left" onClick={() => page(-1)} />}
      {overflow.right && <ScrollEdge side="right" onClick={() => page(1)} />}
    </div>
  )
}

function ScrollEdge({ side, onClick }: { side: 'left' | 'right'; onClick: () => void }) {
  const Icon = side === 'left' ? ChevronLeft : ChevronRight
  return (
    <>
      <span
        aria-hidden="true"
        className={cn(
          'pointer-events-none absolute inset-y-0 w-10',
          side === 'left'
            ? 'left-0 bg-gradient-to-r from-background to-transparent'
            : 'right-0 bg-gradient-to-l from-background to-transparent',
        )}
      />
      <button
        type="button"
        onClick={onClick}
        aria-label={side === 'left' ? 'Scroll stacks left' : 'Scroll stacks right'}
        className={cn(
          'absolute inset-y-0 grid w-6 place-items-center text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground',
          side === 'left' ? 'left-0' : 'right-0',
        )}
      >
        <Icon className="size-4" />
      </button>
    </>
  )
}
