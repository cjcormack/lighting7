import { useState } from "react"
import { toast } from "sonner"
import {
  Loader2,
  CloudUpload,
  ChevronDown,
  ChevronRight,
  Unlink,
} from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { GithubIcon } from "@/components/GithubIcon"
import { IdentityRow } from "@/components/cloudSync/IdentityRow"
import { CreateRepoDialog } from "@/components/cloudSync/CreateRepoDialog"
import { AutoSyncForm } from "@/components/cloudSync/AutoSyncForm"
import { PatPanel } from "@/components/cloudSync/PatPanel"
import { DisconnectConfirmDialog } from "@/components/cloudSync/DisconnectConfirmDialog"
import {
  useCloudSyncConfigQuery,
  useUpdateCloudSyncConfigMutation,
  useCloudSyncReconnectMutation,
  useCloudSyncRunMutation,
  type SyncConfig,
} from "@/store/cloudSync"
import { useOauthGithubIdentityQuery, type GithubRepo } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"
import { formatRepoUrl } from "@/lib/formatRepoUrl"

/**
 * Cloud-sync configuration. A project is synced iff a repository is attached, so this
 * panel has two shapes: an attach call-to-action when not synced, and a read-only
 * summary + disconnect when synced.
 */
export function ConfigPanel({ projectId }: { projectId: number }) {
  const { data: config, isLoading } = useCloudSyncConfigQuery(projectId)

  if (isLoading || !config) {
    return (
      <Card className="p-4 flex items-center justify-center">
        <Loader2 className="size-5 animate-spin" />
      </Card>
    )
  }

  return config.synced
    ? <SyncedConfigPanel projectId={projectId} config={config} />
    : <AttachConfigPanel projectId={projectId} config={config} />
}

/** Not-synced state: connect GitHub, create a new repo (or reconnect a remembered one). */
function AttachConfigPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const { data: identity } = useOauthGithubIdentityQuery()
  const [updateConfig, { isLoading: isAttaching }] = useUpdateCloudSyncConfigMutation()
  const [reconnect, { isLoading: isReconnecting }] = useCloudSyncReconnectMutation()
  const [runSync] = useCloudSyncRunMutation()
  const [createOpen, setCreateOpen] = useState(false)
  // Gates the repo picker and "create repo", both of which go through the GitHub API. With a
  // rejected identity this panel falls back to the "Previously linked" Reconnect list only —
  // there is no manual repo-URL entry here, and PatPanel lives in SyncedConfigPanel, so a
  // never-synced project genuinely cannot attach a repo until GitHub is reconnected. That
  // limitation predates the re-auth work (the old gate was `connected` alone); it is not
  // something this flag introduced.
  const oauthConnected = identity?.connected === true && identity.reauthRequired !== true

  // Fire the first push so a freshly-attached repo doesn't sit empty. Non-fatal: if it
  // fails (e.g. the GitHub App can't see the new repo yet) sync is still on and the user
  // can retry "Sync now".
  const firstSync = async () => {
    try {
      await runSync(projectId).unwrap()
    } catch (err) {
      toast.error(`Initial sync didn't complete: ${formatError(err)}`)
    }
  }

  const handleAttach = async (repo: GithubRepo) => {
    try {
      await updateConfig({
        projectId,
        body: { repoUrl: repo.cloneUrl, branch: repo.defaultBranch || "main" },
      }).unwrap()
      toast.success(`Cloud sync enabled — ${repo.fullName}`)
      void firstSync()
    } catch (err) {
      toast.error(`Failed to enable cloud sync: ${formatError(err)}`)
    }
  }

  const handleReconnect = async (repoUrl: string) => {
    try {
      await reconnect({ projectId, body: { repoUrl } }).unwrap()
      toast.success("Reconnected — cloud sync re-enabled")
      void firstSync()
    } catch (err) {
      toast.error(`Reconnect failed: ${formatError(err)}`)
    }
  }

  const enableButton = (
    <Button onClick={() => setCreateOpen(true)} disabled={!oauthConnected || isAttaching}>
      <CloudUpload className="size-4 mr-1.5" />
      Enable cloud sync
    </Button>
  )

  return (
    <Card className="p-4 space-y-4">
      <div>
        <h2 className="text-sm font-semibold">Cloud sync</h2>
        <p className="text-xs text-muted-foreground">
          This project isn&rsquo;t synced yet. Create a repository to start syncing —
          changes are pushed and pulled automatically.
        </p>
      </div>

      {/* Identity row — connecting GitHub is a prerequisite for creating a repo. */}
      <div className="border rounded-md p-3 bg-muted/20">
        <IdentityRow projectId={projectId} />
      </div>

      <div className="flex flex-wrap items-center gap-3">
        {oauthConnected ? (
          enableButton
        ) : (
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={0}>{enableButton}</span>
            </TooltipTrigger>
            <TooltipContent>Connect GitHub above to create a repository.</TooltipContent>
          </Tooltip>
        )}
        <span className="text-xs text-muted-foreground">
          Creates a new private repository for this project.
        </span>
      </div>

      {config.linkedRepos.length > 0 && (
        <div className="border-t pt-3 space-y-2">
          <Label className="text-xs text-muted-foreground">Previously linked</Label>
          <ul className="space-y-1">
            {config.linkedRepos.map((r) => (
              <li key={r.repoUrl} className="flex items-center justify-between gap-2">
                <span className="text-xs font-mono truncate">
                  {formatRepoUrl(r.repoUrl) ?? r.repoUrl}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleReconnect(r.repoUrl)}
                  disabled={isReconnecting}
                >
                  Reconnect
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <CreateRepoDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(repo) => {
          setCreateOpen(false)
          void handleAttach(repo)
        }}
      />
    </Card>
  )
}

/** Synced state: read-only repo summary, Advanced (auto-sync + PAT), and Disconnect. */
function SyncedConfigPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [disconnectOpen, setDisconnectOpen] = useState(false)
  const repoLabel = formatRepoUrl(config.repoUrl) ?? config.repoUrl ?? "—"

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold">Cloud sync</h2>
          <p className="text-xs text-muted-foreground">
            Synced to a GitHub repository. Changes push and pull automatically.
          </p>
        </div>
        <Badge variant="secondary" className="text-[10px] shrink-0">synced</Badge>
      </div>

      {/* Identity row — surfaces re-auth if the GitHub connection lapses. */}
      <div className="border rounded-md p-3 bg-muted/20">
        <IdentityRow projectId={projectId} />
      </div>

      <div className="space-y-1">
        <Label className="text-xs text-muted-foreground">Repository</Label>
        <div className="flex items-center gap-2 min-w-0">
          <GithubIcon className="size-4 shrink-0 opacity-70" />
          <span className="text-sm font-medium truncate">{repoLabel}</span>
          <Badge variant="outline" className="text-[10px] shrink-0">{config.branch}</Badge>
        </div>
        {config.repoUrl && (
          <p className="text-[10px] text-muted-foreground font-mono break-all">
            {config.repoUrl}
          </p>
        )}
        <p className="text-[10px] text-muted-foreground">
          The repository can&rsquo;t be changed — disconnect to link a different one.
        </p>
      </div>

      {/* Advanced — auto-sync cadence + access token, collapsed by default. */}
      <div className="border-t pt-3">
        <button
          type="button"
          onClick={() => setAdvancedOpen((o) => !o)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          {advancedOpen ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
          Advanced — auto-sync &amp; access token
          {config.tokenPresent && (
            <Badge variant="secondary" className="ml-2 text-[10px]">PAT stored</Badge>
          )}
        </button>
        {advancedOpen && (
          <div className="mt-3 space-y-4">
            <AutoSyncForm projectId={projectId} config={config} />
            <div className="border-t pt-4">
              <PatPanel projectId={projectId} config={config} />
            </div>
          </div>
        )}
      </div>

      <div className="flex justify-end border-t pt-3">
        <Button variant="outline" onClick={() => setDisconnectOpen(true)}>
          <Unlink className="size-3.5 mr-1.5" />
          Disconnect repository
        </Button>
      </div>

      <DisconnectConfirmDialog
        open={disconnectOpen}
        onOpenChange={setDisconnectOpen}
        projectId={projectId}
        repoLabel={repoLabel}
      />
    </Card>
  )
}
