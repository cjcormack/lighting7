import { EyeOff, SlidersHorizontal } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { pathHasSegment } from '@/lib/navMatch'
import { useProgrammerSummaryQuery } from '../store/programmer'
import { useCurrentProjectQuery } from '../store/projects'

/**
 * "The programmer holds data" indicator — the console convention that stops an operator
 * wondering why the rig ignores their cues. Self-contained (it reads its own state) so it
 * can be dropped into any chrome without threading props.
 *
 * Silent when there is nothing to say: no entries and not blind. Blind alone is worth
 * shouting about, because a blind programmer looks *exactly* like a working one until you
 * notice the stage never changed.
 *
 * **This badge is a reporter, and it is not the toggle.** Blind is a programmer fact: the one
 * control that flips it is in the programmer's action bar (`PD-BLIND-ON-PROGRAMMER`), and every
 * other view learns the state from this badge — the app header's mount on every page, the
 * `ShowBar`'s on Show and the Prompt Book, and the busk view's Show tab's strip — or from a
 * reporter of its own reading the same field: the busk band's `BLIND` pill and the sheet's Show
 * glyph dot (`busking/BlindMarks.tsx`), which a folded sheet and an immersive window need and this
 * badge cannot reach. Do not make it the toggle: it is also the link to the programmer, and one
 * control cannot be both without one of the two jobs becoming a surprise.
 *
 * There used to be a `blindShownSeparately` prop, for the one host that drew its own amber BLIND
 * tile a couple of elements away — the `ShowBar`, from session 2b until the tile moved to the
 * programmer. It went with the tile rather than staying as an arm with no true caller: a badge
 * that could be told to stay quiet is a badge that could be silenced by a host with nothing else
 * saying it, and the app header's mount on the programmer is the case that had to stay loud. On
 * `/programmer` this badge and the action bar's toggle both show amber — that is the reporter and
 * the control, one row apart, which is the arrangement the programmer had before session 2b. On
 * the busk view in Split with the Show tab open, the band's pill and this badge both show amber
 * too: two reporters a region apart, deliberate — the pill says it where the press is made and
 * this badge carries the count — and neither may be given a `shownSeparately` arm, for the reason
 * that prop was deleted.
 */
export function ProgrammerIndicator({ className }: { className?: string }) {
  const { data: summary } = useProgrammerSummaryQuery()
  const { data: currentProject } = useCurrentProjectQuery()
  const location = useLocation()

  const entryCount = summary?.entryCount ?? 0
  const blind = summary?.blind ?? false
  if (entryCount === 0 && !blind) return null

  // The programmer is its own page again, so the link goes there. The "am I already there?" test
  // is segment-aware rather than a bare `startsWith`, which is a trap in both directions: while
  // this pointed at `/program`, the sibling `/projects/1/programmer` DID start with it — and would
  // have read as "already there" on the one page that needed the link least, and as "not there" the
  // other way round. It used to say so by hand; it now shares `pathHasSegment` with
  // `mostSpecificActiveId`, which is the third site to have wanted exactly this. A subroute still
  // counts as being here.
  const programmerPath = currentProject ? `/projects/${currentProject.id}/programmer` : null
  const onProgrammer = programmerPath != null && pathHasSegment(location.pathname, programmerPath)

  const body = (
    <span
      className={cn(
        'flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium tabular-nums',
        blind
          ? 'border-amber-500/60 bg-amber-500/15 text-amber-700 dark:text-amber-300'
          : 'border-primary/50 bg-primary/10 text-primary',
        className,
      )}
    >
      {blind ? <EyeOff className="size-3.5" /> : <SlidersHorizontal className="size-3.5" />}
      {/* The eye-off icon already says "blind"; spelling it out as well is what tips the
          app header onto a third row at phone widths. Keep the count, drop the word. */}
      {blind && <span className="hidden @[760px]:inline">Blind</span>}
      {blind && entryCount > 0 && <span className="hidden @[760px]:inline">·</span>}
      {entryCount > 0 && <span>{entryCount}</span>}
    </span>
  )

  const tip = [
    entryCount > 0
      ? `Programmer holds ${entryCount} value${entryCount === 1 ? '' : 's'}`
      : 'Programmer is empty',
    // The full sentence, because the badge itself spells out "Blind" only above 760px: "5 values,
    // and none of them reaching the stage" is the useful reading, and a tooltip costs no width.
    blind ? 'Blind — the programmer is gated out of the stage output' : null,
    // Only offer the trip if we aren't already there.
    programmerPath && !onProgrammer ? 'Go to the programmer to clear or edit' : null,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* `shrink-0` on the OUTER element: `className` lands on the inner body span, so without
            it the ShowBar can squeeze this to nothing while the badge inside keeps its width. */}
        {programmerPath && !onProgrammer ? (
          <Link to={programmerPath} aria-label={tip} className="flex shrink-0 items-center">
            {body}
          </Link>
        ) : (
          <div aria-label={tip} className="flex shrink-0 items-center">
            {body}
          </div>
        )}
      </TooltipTrigger>
      <TooltipContent>{tip}</TooltipContent>
    </Tooltip>
  )
}
