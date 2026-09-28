import { useEffect, useState } from "react"
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
import { Textarea } from "@/components/ui/textarea"
import { ExternalLink } from "lucide-react"
import { useCreateGithubRepoMutation, type GithubRepo } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"

interface CreateRepoDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (repo: GithubRepo) => void
}

/**
 * Inline create-new-repo flow. POSTs to the backend's create endpoint, which uses
 * the install-wide OAuth identity's `Administration: write` permission to create
 * a private repo under the authenticated user.
 *
 * Caveat surfaced inline: when the GitHub App is configured for "Selected
 * repositories" the new repo isn't automatically in the App's accessible set. We
 * show a deep-link to the App's installation Configure page after creation so the
 * user can fix this in one click.
 */
export function CreateRepoDialog({ open, onOpenChange, onCreated }: CreateRepoDialogProps) {
  const [createRepo, { isLoading }] = useCreateGithubRepoMutation()
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [isPrivate, setIsPrivate] = useState(true)
  const [createdRepo, setCreatedRepo] = useState<GithubRepo | null>(null)

  useEffect(() => {
    if (!open) {
      setName("")
      setDescription("")
      setIsPrivate(true)
      setCreatedRepo(null)
    }
  }, [open])

  const validName = /^[a-zA-Z0-9._-]+$/.test(name.trim())

  const handleCreate = async () => {
    if (!validName) return
    try {
      const repo = await createRepo({
        name: name.trim(),
        private: isPrivate,
        description: description.trim() || null,
      }).unwrap()
      toast.success(`Created ${repo.fullName}`)
      setCreatedRepo(repo)
    } catch (err) {
      toast.error(`Create repo failed: ${formatError(err)}`)
    }
  }

  // We don't know the installation ID, so link to the user's installation list and
  // let them pick the lighting7 App from there.
  const settingsInstallationsUrl = "https://github.com/settings/installations"

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create new repository</DialogTitle>
          <DialogDescription>
            Creates an empty private repository under your GitHub account. lighting7
            will push the first commit on the next sync.
          </DialogDescription>
        </DialogHeader>

        {createdRepo ? (
          <div className="space-y-3 py-2">
            <div className="text-sm">
              Created{" "}
              <strong className="font-mono text-xs">
                {createdRepo.fullName}
              </strong>
              .
            </div>
            <div className="rounded-md border bg-amber-50 dark:bg-amber-950/30 p-3 text-xs space-y-2">
              <div>
                If your GitHub App is set to <em>Selected repositories</em>, this new
                repo isn&rsquo;t accessible until you add it to the installation.
              </div>
              <a
                href={settingsInstallationsUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-xs underline underline-offset-2"
              >
                Configure on github.com
                <ExternalLink className="size-3" />
              </a>
            </div>
            <DialogFooter className="gap-2">
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => {
                  onCreated(createdRepo)
                  setCreatedRepo(null)
                }}
              >
                Use this repo
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <>
            <div className="space-y-3 py-2">
              <div className="space-y-1">
                <Label htmlFor="new-repo-name">Repository name *</Label>
                <Input
                  id="new-repo-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="lighting7-show"
                  autoFocus
                />
                {name && !validName && (
                  <p className="text-xs text-destructive">
                    Use letters, numbers, periods, hyphens or underscores.
                  </p>
                )}
              </div>
              <div className="space-y-1">
                <Label htmlFor="new-repo-desc">Description (optional)</Label>
                <Textarea
                  id="new-repo-desc"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  rows={2}
                />
              </div>
              <div className="flex items-center gap-2">
                <input
                  id="new-repo-private"
                  type="checkbox"
                  checked={isPrivate}
                  onChange={(e) => setIsPrivate(e.target.checked)}
                />
                <Label htmlFor="new-repo-private" className="text-xs">
                  Private repository (recommended)
                </Label>
              </div>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button
                onClick={handleCreate}
                disabled={!validName || isLoading}
              >
                {isLoading ? "Creating…" : "Create"}
              </Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
