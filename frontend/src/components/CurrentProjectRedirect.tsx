import { useEffect } from "react"
import { useLocation, useNavigate } from "react-router"
import { Card } from "@/components/ui/card"
import { Loader2 } from "lucide-react"
import { useCurrentProjectQuery } from "../store/projects"

/**
 * Hands off a legacy project-less route (`/fixtures`, `/looks`, …) to the same page scoped
 * under the current project (`/projects/:id/fixtures`). Every plain "follow the current
 * project" redirect in `routes/` delegates here instead of repeating the loading/navigate
 * body — see FS-DUP-REDIRECTS. Route redirects that already know their project id from a URL
 * param (the `/projects/:id/run` → `/projects/:id/show` style) don't need this: they navigate
 * straight there.
 */
export function CurrentProjectRedirect({
  to,
  preserveSearch = false,
  fullHeight = false,
}: {
  /** Path under `/projects/:id/`, e.g. `"fixtures"` or `` `channels/${universe}` ``. */
  to: string
  /** Carry the current URL's query string across (deep links like `?select=`). */
  preserveSearch?: boolean
  /** Fill the flex parent instead of the default boxed spinner — for a route mounted where a small margined `Card` would sit oddly next to full-height sibling content. */
  fullHeight?: boolean
}) {
  const { data: currentProject, isLoading } = useCurrentProjectQuery()
  const navigate = useNavigate()
  const { search } = useLocation()

  useEffect(() => {
    if (!isLoading && currentProject) {
      navigate(`/projects/${currentProject.id}/${to}${preserveSearch ? search : ""}`, {
        replace: true,
      })
    }
  }, [currentProject, isLoading, navigate, to, preserveSearch, search])

  if (isLoading) {
    if (fullHeight) {
      return (
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="size-6 animate-spin text-muted-foreground" />
        </div>
      )
    }
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }

  return null
}
