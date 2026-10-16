import { useNavigate, useParams } from 'react-router'
import { ChevronRight } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { useProjectListQuery } from '@/store/projects'
import { VenueTag } from '@/components/venue/VenueTag'

interface BreadcrumbsProps {
  projectName: string
  /** Current page name (e.g. "Fixtures", "Scripts"). Omit for project overview. */
  currentPage?: string
  /**
   * Called when the currentPage segment is clicked — Show uses it to leave a drilled stack.
   *
   * There used to be an `extra` array of trailing segments beside this, for a drill trail. Its
   * last consumer was the busk view, which listed the selected targets there and re-opened the
   * target picker from a click on one. The target band says both of those in the page itself, so
   * the trail is back to `Projects > Project > <View>` on every route.
   */
  onCurrentPageClick?: () => void
  /** When set, the full trail collapses to just this label below the `@[640px]` container width
   *  (needs a `@container` ancestor). Used by the show views so the breadcrumb can't overflow. */
  collapsedLabel?: string
}

/**
 * The trail is **one line, and the project name is what gives**. It was `flex-wrap`, which on a
 * header that grew with its content merely made the row taller for a band of widths — which is
 * how it went unnoticed. The `ShowHeader` is a fixed 40px chrome row since the busk-chrome plan's
 * session B, and an immersive window (no sidebar) puts that header at the full window width, so
 * the band above the `@[640px]` collapse where the full trail and the controls do not both fit is
 * reached on an ordinary narrow window; a second line there overflowed the row onto the band
 * below. So: `flex-nowrap`, the trail `min-w-0 overflow-hidden`, and the one segment of
 * unbounded length — the project name — truncates, the way the collapsed label already did. The
 * `overflow-hidden` would clip the segments' focus outlines, which the UA paints outside the box,
 * so each button draws its outline inset (`focus-visible:outline-offset-[-2px]`).
 *
 * Off the rig the project segment also carries *Not on the rig*, which must never shrink to a
 * stub or paint over the chevron after it. So the segment wraps, clipped to its first line
 * (`NAME_GROUP_CLASS`): the name's `8ch` basis is what it keeps beside the badge, and once the
 * two no longer fit the badge wraps out of sight and the name has the segment to itself.
 */

const NAME_GROUP_CLASS = 'flex flex-wrap items-center gap-x-2 gap-y-4 h-[22px] min-w-0 overflow-hidden'

export function Breadcrumbs({ projectName, currentPage, onCurrentPageClick, collapsedLabel }: BreadcrumbsProps) {
  const navigate = useNavigate()
  const { projectId } = useParams()
  // A venue says so on every view of it (venue plan session 2): the tag beside its name.
  const { data: projects } = useProjectListQuery()
  const viewed = projects?.find((p) => String(p.id) === projectId)
  const isVenue = viewed?.kind === 'VENUE'
  const notOnRig = viewed !== undefined && !viewed.isCurrent

  const nameParts = (
    <>
      <span className="truncate basis-[8ch] grow">{projectName}</span>
      {isVenue && <VenueTag className="shrink-0" />}
      {notOnRig && <Badge variant="outline">Not on the rig</Badge>}
    </>
  )

  return (
    <>
      {collapsedLabel && (
        <span className="@[640px]:hidden text-sm font-medium truncate max-w-full">
          {collapsedLabel}
        </span>
      )}
      <nav
        className={cn(
          'items-center gap-1 text-sm flex-nowrap min-w-0 overflow-hidden whitespace-nowrap',
          collapsedLabel ? 'hidden @[640px]:flex' : 'flex',
        )}
      >
      <button
        onClick={() => navigate('/projects')}
        className="text-muted-foreground hover:text-foreground transition-colors shrink-0 focus-visible:outline-offset-[-2px]"
      >
        Projects
      </button>
      <ChevronRight className="size-4 text-muted-foreground flex-shrink-0" />

      {/* Project name - final segment if no currentPage */}
      {!currentPage ? (
        <span className={cn('font-medium', NAME_GROUP_CLASS)}>{nameParts}</span>
      ) : (
        <>
          <button
            onClick={() => navigate(`/projects/${projectId}`)}
            className={cn(
              'text-muted-foreground hover:text-foreground transition-colors focus-visible:outline-offset-[-2px]',
              NAME_GROUP_CLASS,
            )}
          >
            {nameParts}
          </button>
          <ChevronRight className="size-4 text-muted-foreground flex-shrink-0" />

          {onCurrentPageClick ? (
            <button
              onClick={onCurrentPageClick}
              className="font-medium hover:text-muted-foreground transition-colors shrink-0 focus-visible:outline-offset-[-2px]"
            >
              {currentPage}
            </button>
          ) : (
            <span className="font-medium shrink-0">{currentPage}</span>
          )}
        </>
      )}
      </nav>
    </>
  )
}
