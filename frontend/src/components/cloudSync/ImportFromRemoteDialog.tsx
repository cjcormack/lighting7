import { useEffect, useMemo, useState } from "react"
import { useNavigate } from "react-router"
import { toast } from "sonner"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { RepoPicker } from "./RepoPicker"
import { CloudRepoPicker } from "./CloudRepoPicker"
import { AdoptToSync } from "./AdoptToSync"
import { useCloudAdopted } from "./useCloudAdopted"
import {
  useCloudSyncImportMutation,
  useCloudSyncConfigsQuery,
  type CloudRepo,
} from "@/store/cloudSync"
import { useProjectListQuery } from "@/store/projects"
import type { GithubRepo } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"
import { venueSyncRefusal } from "@/lib/venueSyncRefusal"

interface AddRemoteProjectDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Drives the GitHub picker's connect-prompt. */
  oauthConnected: boolean
}

/** The repo picked, from the cloud's list or GitHub's, as the import reads it. */
interface PickedRepo {
  url: string
  /** What the toast and the placeholder call it. */
  label: string
  projectName: string
  description: string | null
  branch: string
}

function fromCloud(repo: CloudRepo): PickedRepo {
  return { url: repo.url, label: repo.name, projectName: repo.name, description: null, branch: "main" }
}

function fromGithub(repo: GithubRepo): PickedRepo {
  return {
    url: repo.cloneUrl,
    label: repo.fullName,
    projectName: repo.projectName ?? repo.name,
    description: repo.projectDescription,
    branch: repo.defaultBranch || "main",
  }
}

/**
 * *Import from the cloud* (lighting7 cloud-p2 plan D6): pick one of the org's cloud repos — or, under
 * *GitHub (legacy)*, a GitHub repo — and a name, and POST to `/cloud-sync/import` to add it as a new
 * local project that stays continuously synced. On success, navigates to the new project's Sync
 * settings.
 *
 * The name input is pre-filled from the repo's project name, but editable. Collisions with an
 * existing local project name are flagged inline before submit; `ProjectImporter` remains the
 * server-side source of truth and any error it returns is surfaced as a toast.
 */
export function AddRemoteProjectDialog({
  open,
  onOpenChange,
  oauthConnected,
}: AddRemoteProjectDialogProps) {
  const navigate = useNavigate()
  const [importFromRemote, { isLoading }] = useCloudSyncImportMutation()
  const { data: syncConfigs } = useCloudSyncConfigsQuery()
  const { data: projects } = useProjectListQuery()
  const { adopted } = useCloudAdopted()
  const [source, setSource] = useState<"cloud" | "github">("cloud")
  const [repo, setRepo] = useState<PickedRepo | null>(null)
  const [projectName, setProjectName] = useState("")
  // Track whether the user has typed in the name field — once they have, stop
  // overwriting their text on repo re-pick.
  const [nameTouched, setNameTouched] = useState(false)

  useEffect(() => {
    if (!open) {
      setSource("cloud")
      setRepo(null)
      setProjectName("")
      setNameTouched(false)
    }
  }, [open])

  // Clone URLs already attached to a local project — surfaced in the picker as
  // "Already added" so a repo can't be re-imported.
  const linkedRepoUrls = useMemo(
    () =>
      new Set(
        Object.values(syncConfigs ?? {})
          .map((c) => c.repoUrl)
          .filter((u): u is string => !!u),
      ),
    [syncConfigs],
  )

  // Existing local project names for the pre-import collision check. Case-sensitive to
  // match the backend's `DaoProjects.name eq targetName` (SQLite varchar, no NOCASE) — a
  // case-only difference is a distinct name the backend accepts, so we mustn't block it.
  const existingNames = useMemo(
    () => new Set((projects ?? []).map((p) => p.name.trim())),
    [projects],
  )

  const handleRepoChange = (next: PickedRepo) => {
    setRepo(next)
    if (!nameTouched) setProjectName(next.projectName)
  }

  const chooseSource = (next: "cloud" | "github") => {
    setSource(next)
    setRepo(null)
  }

  const branch = repo?.branch ?? "main"
  const trimmedName = projectName.trim()
  const nameCollision = trimmedName.length > 0 && existingNames.has(trimmedName)
  const canSubmit = !!repo && trimmedName.length > 0 && !nameCollision && !isLoading

  const handleImport = async () => {
    if (!repo) return
    try {
      const result = await importFromRemote({
        repoUrl: repo.url,
        branch,
        projectName: projectName.trim(),
      }).unwrap()
      toast.success(`Added "${result.name}" from ${repo.label}`)
      onOpenChange(false)
      navigate(`/projects/${result.projectId}/settings/sync`)
    } catch (err) {
      // A show whose venue is not on this desk (`VENUE_MISSING`): add the venue's repo first.
      const refusal = venueSyncRefusal(err)
      if (refusal) toast.error(refusal.title, { description: refusal.message })
      else toast.error(`Import failed: ${formatError(err)}`)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Import from the cloud</DialogTitle>
          <DialogDescription>
            Add one of the org&rsquo;s cloud projects to this desk. It stays continuously synced
            &mdash; changes push and pull automatically.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3 py-2">
          <div className="space-y-1">
            <Label>{source === "cloud" ? "Project *" : "Repository *"}</Label>
            {source === "cloud" ? (
              adopted === false ? (
                <AdoptToSync what="import the org's projects" />
              ) : (
                <CloudRepoPicker
                  value={repo?.url ?? null}
                  onChange={(next) => handleRepoChange(fromCloud(next))}
                  skip={adopted !== true}
                />
              )
            ) : (
              <RepoPicker
                value={repo?.url ?? null}
                onChange={(next) => handleRepoChange(fromGithub(next))}
                oauthConnected={oauthConnected}
                lightingOnly
                linkedRepoUrls={linkedRepoUrls}
              />
            )}
            {repo?.description && (
              <p className="text-xs text-muted-foreground">{repo.description}</p>
            )}
            {repo && source === "github" && (
              <p className="text-[10px] text-muted-foreground font-mono break-all">
                {repo.url}
              </p>
            )}
            <button
              type="button"
              className="text-xs text-muted-foreground underline-offset-2 hover:underline"
              onClick={() => chooseSource(source === "cloud" ? "github" : "cloud")}
            >
              {source === "cloud" ? "GitHub (legacy)…" : "Back to the cloud"}
            </button>
          </div>
          <div className="space-y-1">
            <Label htmlFor="import-project-name">Project name *</Label>
            <Input
              id="import-project-name"
              value={projectName}
              onChange={(e) => {
                setProjectName(e.target.value)
                setNameTouched(true)
              }}
              placeholder={repo?.projectName ?? "Project name"}
              disabled={!repo}
            />
            {nameCollision ? (
              <p className="text-[10px] text-destructive">
                A project named &ldquo;{trimmedName}&rdquo; already exists locally &mdash; choose a
                different name.
              </p>
            ) : (
              <p className="text-[10px] text-muted-foreground">
                Defaults to the project&rsquo;s name; rename if it would collide with an existing
                project.
              </p>
            )}
          </div>
          {repo && source === "github" && (
            <div className="text-xs text-muted-foreground">
              Branch: <span className="font-mono">{branch}</span>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={handleImport} disabled={!canSubmit}>
            {isLoading ? "Importing…" : "Import"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
