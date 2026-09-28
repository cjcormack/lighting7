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
import { useCloudSyncImportMutation, useCloudSyncConfigsQuery } from "@/store/cloudSync"
import { useProjectListQuery } from "@/store/projects"
import type { GithubRepo } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"

interface AddRemoteProjectDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Drives the picker's connect-prompt — the trigger button itself is gated higher up. */
  oauthConnected: boolean
}

/**
 * Pick a remote repo + name and POST to `/cloud-sync/import` to add it as a new local
 * project that stays continuously synced (not a one-time import). On success, navigates
 * to the new project's Sync settings so the user can verify and run the first sync.
 *
 * The picker lists only lighting projects (repos with a `project.json`) and the name input
 * is pre-filled from that `project.json` name, but editable. Collisions with an existing
 * local project name are flagged inline before submit; `ProjectImporter` remains the
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
  const [repo, setRepo] = useState<GithubRepo | null>(null)
  const [projectName, setProjectName] = useState("")
  // Track whether the user has typed in the name field — once they have, stop
  // overwriting their text on repo re-pick.
  const [nameTouched, setNameTouched] = useState(false)

  useEffect(() => {
    if (!open) {
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

  const handleRepoChange = (next: GithubRepo) => {
    setRepo(next)
    // Prefer the real project.json name over the raw GitHub repo name.
    if (!nameTouched) setProjectName(next.projectName ?? next.name)
  }

  const branch = repo?.defaultBranch ?? "main"
  const trimmedName = projectName.trim()
  const nameCollision = trimmedName.length > 0 && existingNames.has(trimmedName)
  const canSubmit = !!repo && trimmedName.length > 0 && !nameCollision && !isLoading

  const handleImport = async () => {
    if (!repo) return
    try {
      const result = await importFromRemote({
        repoUrl: repo.cloneUrl,
        branch,
        projectName: projectName.trim(),
      }).unwrap()
      toast.success(`Added "${result.name}" from ${repo.fullName}`)
      onOpenChange(false)
      navigate(`/projects/${result.projectId}/settings/sync`)
    } catch (err) {
      toast.error(`Import failed: ${formatError(err)}`)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add remote project</DialogTitle>
          <DialogDescription>
            Add an existing GitHub repository as a new local project. The remote&rsquo;s
            default branch is used and the project stays continuously synced &mdash; changes
            push and pull automatically.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3 py-2">
          <div className="space-y-1">
            <Label>Repository *</Label>
            <RepoPicker
              value={repo?.cloneUrl ?? null}
              onChange={handleRepoChange}
              oauthConnected={oauthConnected}
              lightingOnly
              linkedRepoUrls={linkedRepoUrls}
            />
            {repo?.projectDescription && (
              <p className="text-xs text-muted-foreground">{repo.projectDescription}</p>
            )}
            {repo && (
              <p className="text-[10px] text-muted-foreground font-mono break-all">
                {repo.cloneUrl}
              </p>
            )}
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
              placeholder={repo?.projectName ?? repo?.name ?? "Project name"}
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
          {repo && (
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
