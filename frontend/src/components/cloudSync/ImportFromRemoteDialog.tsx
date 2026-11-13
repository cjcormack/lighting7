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
import { CloudRepoPicker } from "./CloudRepoPicker"
import { AdoptToSync } from "./AdoptToSync"
import { useCloudAdopted } from "./useCloudAdopted"
import {
  useCloudSyncImportMutation,
  type CloudRepo,
} from "@/store/cloudSync"
import { useProjectListQuery } from "@/store/projects"
import { formatError } from "@/lib/formatError"
import { venueSyncRefusal } from "@/lib/venueSyncRefusal"

interface AddRemoteProjectDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * *Import from the cloud* (lighting7 cloud-p2 plan D6): pick one of the org's cloud repos and a name,
 * and POST to `/cloud-sync/import` to add it as a new
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
}: AddRemoteProjectDialogProps) {
  const navigate = useNavigate()
  const [importFromRemote, { isLoading }] = useCloudSyncImportMutation()
  const { data: projects } = useProjectListQuery()
  const { adopted } = useCloudAdopted()
  const [repo, setRepo] = useState<CloudRepo | null>(null)
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

  // Existing local project names for the pre-import collision check. Case-sensitive to
  // match the backend's `DaoProjects.name eq targetName` (SQLite varchar, no NOCASE) — a
  // case-only difference is a distinct name the backend accepts, so we mustn't block it.
  const existingNames = useMemo(
    () => new Set((projects ?? []).map((p) => p.name.trim())),
    [projects],
  )

  const handleRepoChange = (next: CloudRepo) => {
    setRepo(next)
    if (!nameTouched) setProjectName(next.name)
  }

  const trimmedName = projectName.trim()
  const nameCollision = trimmedName.length > 0 && existingNames.has(trimmedName)
  const canSubmit = !!repo && trimmedName.length > 0 && !nameCollision && !isLoading

  const handleImport = async () => {
    if (!repo) return
    try {
      const result = await importFromRemote({
        repoUrl: repo.url,
        branch: "main",
        projectName: projectName.trim(),
      }).unwrap()
      toast.success(`Added "${result.name}" from ${repo.name}`)
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
            <Label>Project *</Label>
            {adopted === false ? (
              <AdoptToSync what="import the org's projects" />
            ) : (
              <CloudRepoPicker
                value={repo?.url ?? null}
                onChange={handleRepoChange}
                skip={adopted !== true}
              />
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
              placeholder={repo?.name ?? "Project name"}
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
