import { useNavigate, useParams } from 'react-router'
import { ChevronRight } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

interface BreadcrumbsProps {
  projectName: string
  isActive?: boolean
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
 */

export function Breadcrumbs({ projectName, isActive = true, currentPage, onCurrentPageClick, collapsedLabel }: BreadcrumbsProps) {
  const navigate = useNavigate()
  const { projectId } = useParams()

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
        <span className="font-medium flex items-center gap-2 min-w-0">
          <span className="truncate">{projectName}</span>
          <Badge variant={isActive ? "default" : "outline"} className="text-xs">
            {isActive ? "active" : "inactive"}
          </Badge>
        </span>
      ) : (
        <>
          <button
            onClick={() => navigate(`/projects/${projectId}`)}
            className="text-muted-foreground hover:text-foreground transition-colors flex items-center gap-2 min-w-0 focus-visible:outline-offset-[-2px]"
          >
            <span className="truncate">{projectName}</span>
            <Badge variant={isActive ? "default" : "outline"} className="text-xs">
              {isActive ? "active" : "inactive"}
            </Badge>
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
