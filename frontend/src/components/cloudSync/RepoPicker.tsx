import { useEffect, useMemo, useRef, useState } from "react"
import { Check, ChevronDown, Plus, Lock, Loader2, AlertTriangle } from "lucide-react"
import { cn } from "@/lib/utils"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { useListGithubReposQuery, type GithubRepo } from "@/store/oauthGithub"
import { CreateRepoDialog } from "./CreateRepoDialog"

interface RepoPickerProps {
  /** Currently selected repo's clone URL (matches what gets stored in `sync_config.repoUrl`). */
  value: string | null
  onChange: (repo: GithubRepo) => void
  disabled?: boolean
  /**
   * When false (the GitHub OAuth identity isn't connected) the picker is rendered as a
   * disabled affordance prompting the user to connect first. We don't gate at the parent
   * level so the layout is stable while the identity loads.
   */
  oauthConnected: boolean
  /**
   * When true, only repos that are lighting projects are listed (the backend probes each
   * repo's `project.json`), rows show the project name/description, and the "Create new
   * repo" escape hatch is hidden — creating an empty repo to *import* makes no sense.
   */
  lightingOnly?: boolean
  /**
   * Clone URLs already attached to a local project. Matching repos are shown disabled with
   * an "Already added" badge so the user can't try to re-import an existing project.
   */
  linkedRepoUrls?: Set<string>
}

/**
 * Searchable picker over the repos the GitHub App can see for the connected user.
 * The bottom item is always "Create new private repo" — opens [CreateRepoDialog]
 * which posts to the create endpoint and (on success) auto-selects the result.
 *
 * Search is server-side via the `query` param so very large installations don't
 * pull every repo into the browser.
 */
export function RepoPicker({
  value,
  onChange,
  disabled,
  oauthConnected,
  lightingOnly = false,
  linkedRepoUrls,
}: RepoPickerProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [createOpen, setCreateOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  // Debounce the search → server query a touch so each keystroke isn't a round-trip.
  const [debouncedQuery, setDebouncedQuery] = useState("")
  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedQuery(search.trim()), 200)
    return () => window.clearTimeout(t)
  }, [search])

  const { data: repos, isLoading, error } = useListGithubReposQuery(
    { query: debouncedQuery || null, perPage: 50, lightingOnly },
    { skip: !oauthConnected || !open },
  )

  // Selected-label is reactive to whatever repos we have on hand. If the cloud-sync
  // config holds a URL but we haven't loaded that repo yet, fall back to the URL.
  const selectedRepo = useMemo<GithubRepo | undefined>(
    () => repos?.find((r) => r.cloneUrl === value),
    [repos, value],
  )
  const selectedLabel = selectedRepo?.fullName ?? value ?? null

  useEffect(() => {
    if (open) {
      setSearch("")
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [open])

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <button
            type="button"
            role="combobox"
            aria-expanded={open}
            disabled={disabled || !oauthConnected}
            className={cn(
              "flex h-9 w-full items-center justify-between rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-xs",
              "focus:outline-none focus:ring-1 focus:ring-ring",
              "disabled:cursor-not-allowed disabled:opacity-50",
              !selectedLabel && "text-muted-foreground",
            )}
          >
            <span className="truncate">
              {oauthConnected
                ? (selectedLabel ?? "Select repository…")
                : "Connect GitHub to pick a repo"}
            </span>
            <ChevronDown className="size-4 opacity-50 shrink-0 ml-2" />
          </button>
        </PopoverTrigger>
        <PopoverContent
          className="p-1 w-[var(--radix-popover-trigger-width)] max-h-[360px] overflow-hidden flex flex-col"
          align="start"
        >
          <Input
            ref={inputRef}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search repositories…"
            className="h-8 text-xs mb-1"
          />
          <div className="overflow-auto flex-1">
            {error ? (
              <div className="flex items-center gap-2 px-2 py-3 text-xs text-amber-600">
                <AlertTriangle className="size-3.5" />
                Failed to list repositories. Re-connect or try again.
              </div>
            ) : isLoading ? (
              <div className="flex items-center justify-center py-6">
                <Loader2 className="size-4 animate-spin" />
              </div>
            ) : !repos || repos.length === 0 ? (
              <div className="px-2 py-3 text-xs text-muted-foreground">
                {debouncedQuery
                  ? lightingOnly
                    ? "No matching lighting projects."
                    : "No matching repositories."
                  : lightingOnly
                    ? "No lighting projects found in the repos the lighting7 GitHub App can access."
                    : "No repositories accessible to the lighting7 GitHub App. Add some via Configure on github.com, or create one below."}
              </div>
            ) : (
              <ul className="space-y-0.5">
                {repos.map((repo) => {
                  const selected = repo.cloneUrl === value
                  const alreadyLinked = linkedRepoUrls?.has(repo.cloneUrl) ?? false
                  // Prefer the project.json name/description (lighting-only mode); fall back
                  // to the raw GitHub repo fields for the generic listing.
                  const primary = repo.projectName ?? repo.fullName
                  const secondary = repo.projectName ? repo.fullName : null
                  const detail = repo.projectDescription ?? repo.description
                  return (
                    <li key={repo.fullName}>
                      <button
                        type="button"
                        disabled={alreadyLinked}
                        onClick={() => {
                          onChange(repo)
                          setOpen(false)
                        }}
                        className={cn(
                          "w-full flex items-center justify-between gap-2 px-2 py-1.5 rounded text-left text-xs hover:bg-accent",
                          selected && "bg-accent/60",
                          alreadyLinked && "cursor-not-allowed opacity-50 hover:bg-transparent",
                        )}
                      >
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <span className="truncate font-medium">{primary}</span>
                            {repo.private && <Lock className="size-3 shrink-0 opacity-60" />}
                          </div>
                          {secondary && (
                            <div className="text-[10px] text-muted-foreground font-mono truncate">
                              {secondary}
                            </div>
                          )}
                          {detail && (
                            <div className="text-[10px] text-muted-foreground truncate">
                              {detail}
                            </div>
                          )}
                        </div>
                        {alreadyLinked ? (
                          <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                            Already added
                          </span>
                        ) : (
                          selected && <Check className="size-3.5 shrink-0" />
                        )}
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
          {!lightingOnly && (
            <div className="border-t mt-1 pt-1">
              <Button
                variant="ghost"
                size="sm"
                className="w-full justify-start text-xs"
                onClick={() => {
                  setOpen(false)
                  setCreateOpen(true)
                }}
              >
                <Plus className="size-3.5 mr-1.5" />
                Create new private repo&hellip;
              </Button>
            </div>
          )}
        </PopoverContent>
      </Popover>
      {!lightingOnly && (
        <CreateRepoDialog
          open={createOpen}
          onOpenChange={setCreateOpen}
          onCreated={(repo) => {
            onChange(repo)
            setCreateOpen(false)
          }}
        />
      )}
    </>
  )
}
