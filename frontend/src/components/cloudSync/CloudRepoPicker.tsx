import { Check, Loader2 } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { useCloudReposQuery, type CloudRepo } from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"
import { cn } from "@/lib/utils"

/**
 * The org's cloud repos to pick one from, venues badged, each one a project here already syncs
 * marked and not pickable (lighting7 cloud-p2 plan D6).
 */
export function CloudRepoPicker({
  value,
  onChange,
  skip = false,
}: {
  value: string | null
  onChange: (repo: CloudRepo) => void
  skip?: boolean
}) {
  const { data: repos, error, isLoading } = useCloudReposQuery(undefined, { skip })

  if (error != null) return <p className="text-sm text-destructive">{formatError(error)}</p>
  if (skip || isLoading || repos == null) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Loader2 className="size-3.5 animate-spin" />
        Listing the org&rsquo;s projects…
      </div>
    )
  }
  if (repos.length === 0) {
    return <p className="text-sm text-muted-foreground">The org has no cloud projects yet.</p>
  }
  return (
    <ul role="listbox" aria-label="Cloud projects" className="max-h-64 divide-y overflow-y-auto rounded-md border">
      {repos.map((repo) => {
        const selected = repo.url === value
        return (
          <li key={repo.repoId}>
            <button
              type="button"
              role="option"
              aria-selected={selected}
              disabled={repo.syncedHere}
              onClick={() => onChange(repo)}
              className={cn(
                "flex w-full items-center gap-2 px-3 py-2 text-left text-sm disabled:opacity-60",
                selected ? "bg-accent" : "hover:bg-muted/50",
              )}
            >
              <span className="min-w-0 flex-1 truncate font-medium">{repo.name}</span>
              {repo.kind === "VENUE" && (
                <Badge variant="outline" className="text-[10px]">
                  Venue
                </Badge>
              )}
              {repo.syncedHere ? (
                <span className="text-xs text-muted-foreground">Already on this desk</span>
              ) : selected ? (
                <Check className="size-4" />
              ) : null}
            </button>
          </li>
        )
      })}
    </ul>
  )
}
