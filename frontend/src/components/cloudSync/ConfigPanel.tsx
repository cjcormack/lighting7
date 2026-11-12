import { useState } from "react"
import { toast } from "sonner"
import {
  Cloud,
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
import { AdoptToSync } from "@/components/cloudSync/AdoptToSync"
import { useCloudAdopted } from "@/components/cloudSync/useCloudAdopted"
import {
  useCloudReposQuery,
  useCloudSyncConfigQuery,
  useCloudSyncToCloudMutation,
  useUpdateCloudSyncConfigMutation,
  useCloudSyncReconnectMutation,
  useCloudSyncRunMutation,
  type LinkedRepo,
  type SyncConfig,
} from "@/store/cloudSync"
import { useOauthGithubIdentityQuery, type GithubRepo } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"
import { formatRepoUrl, repoLabel as repoLabelOf } from "@/lib/formatRepoUrl"

/**
 * Cloud-sync configuration. A project is synced iff a repository is attached, so this
 * panel has two shapes: an attach call-to-action when not synced, and a read-only
 * summary + disconnect when synced.
 */
/** [isVenue]: the venue's repo of published versions (venue plan session 4) — only the copy differs. */
export function ConfigPanel({ projectId, isVenue = false }: { projectId: number; isVenue?: boolean }) {
  const { data: config, isLoading } = useCloudSyncConfigQuery(projectId)

  if (isLoading || !config) {
    return (
      <Card className="p-4 flex items-center justify-center">
        <Loader2 className="size-5 animate-spin" />
      </Card>
    )
  }

  return config.synced
    ? <SyncedConfigPanel projectId={projectId} config={config} isVenue={isVenue} />
    : <AttachConfigPanel projectId={projectId} config={config} />
}

/**
 * Not-synced state (lighting7 cloud-p2 plan D6): *Sync to the cloud* and the cloud repos this project
 * synced before, or, unadopted, a way to the Cloud tab. GitHub's attach stays under *GitHub (legacy)*
 * for projects already there.
 */
function AttachConfigPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const { adopted } = useCloudAdopted()
  const [syncToCloud, { isLoading: isSyncing }] = useCloudSyncToCloudMutation()
  const [reconnect, { isLoading: isReconnecting }] = useCloudSyncReconnectMutation()
  const [runSync] = useCloudSyncRunMutation()
  const cloudLinked = config.linkedRepos.filter((r) => r.cloud)
  const githubLinked = config.linkedRepos.filter((r) => !r.cloud)
  const { data: cloudRepos } = useCloudReposQuery(undefined, {
    skip: adopted !== true || cloudLinked.length === 0,
  })
  const nameOf = (url: string) => cloudRepos?.find((r) => r.url === url)?.name ?? "Cloud repo"

  const handleSyncToCloud = async () => {
    try {
      const result = await syncToCloud(projectId).unwrap()
      const name = result.config.repoName ?? "its cloud repo"
      if (result.syncError) {
        toast.error(`Attached ${name}, but the first sync didn't complete`, { description: result.syncError.error })
      } else if (result.existed) {
        toast.success(`Attached ${name}, which the cloud already had for this project`)
      } else {
        toast.success(`Syncing to the cloud — ${name}`)
      }
    } catch (err) {
      toast.error(`Couldn't sync to the cloud: ${formatError(err)}`)
    }
  }

  // Non-fatal: sync is on once reconnected, and Sync now retries.
  const handleReconnect = async (repoUrl: string) => {
    try {
      await reconnect({ projectId, body: { repoUrl } }).unwrap()
      toast.success("Reconnected — cloud sync re-enabled")
      try {
        await runSync(projectId).unwrap()
      } catch (err) {
        toast.error(`Initial sync didn't complete: ${formatError(err)}`)
      }
    } catch (err) {
      toast.error(`Reconnect failed: ${formatError(err)}`)
    }
  }

  return (
    <Card className="p-4 space-y-4">
      <div>
        <h2 className="text-sm font-semibold">Cloud sync</h2>
        <p className="text-xs text-muted-foreground">
          This project isn&rsquo;t synced yet. Sync it to the cloud and changes push and pull
          automatically, between every desk in the org.
        </p>
      </div>

      {adopted == null ? (
        <Loader2 className="size-4 animate-spin" />
      ) : adopted ? (
        <>
          <Button onClick={() => void handleSyncToCloud()} disabled={isSyncing}>
            {isSyncing ? <Loader2 className="size-4 mr-1.5 animate-spin" /> : <CloudUpload className="size-4 mr-1.5" />}
            Sync to the cloud
          </Button>
          <RememberedRepos
            repos={cloudLinked}
            labelOf={nameOf}
            onReconnect={handleReconnect}
            disabled={isReconnecting}
          />
        </>
      ) : (
        <AdoptToSync />
      )}

      <GitHubLegacyAttach
        projectId={projectId}
        linked={githubLinked}
        onReconnect={handleReconnect}
        reconnecting={isReconnecting}
      />
    </Card>
  )
}

function RememberedRepos({
  repos,
  labelOf,
  onReconnect,
  disabled,
}: {
  repos: LinkedRepo[]
  labelOf: (url: string) => string
  onReconnect: (repoUrl: string) => void
  disabled: boolean
}) {
  if (repos.length === 0) return null
  return (
    <div className="border-t pt-3 space-y-2">
      <Label className="text-xs text-muted-foreground">Previously linked</Label>
      <ul className="space-y-1">
        {repos.map((r) => (
          <li key={r.repoUrl} className="flex items-center justify-between gap-2">
            <span className="text-xs truncate">{labelOf(r.repoUrl)}</span>
            <Button variant="outline" size="sm" onClick={() => onReconnect(r.repoUrl)} disabled={disabled}>
              Reconnect
            </Button>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** GitHub's attach, for projects still synced there until they move to the cloud. */
function GitHubLegacyAttach({
  projectId,
  linked,
  onReconnect,
  reconnecting,
}: {
  projectId: number
  linked: LinkedRepo[]
  onReconnect: (repoUrl: string) => void
  reconnecting: boolean
}) {
  const [open, setOpen] = useState(false)
  const { data: identity } = useOauthGithubIdentityQuery(undefined, { skip: !open })
  const [updateConfig, { isLoading: isAttaching }] = useUpdateCloudSyncConfigMutation()
  const [runSync] = useCloudSyncRunMutation()
  const [createOpen, setCreateOpen] = useState(false)
  // A never-synced project cannot attach a GitHub repo while the identity is rejected: there is no
  // manual URL entry here.
  const oauthConnected = identity?.connected === true && identity.reauthRequired !== true

  const handleAttach = async (repo: GithubRepo) => {
    try {
      await updateConfig({
        projectId,
        body: { repoUrl: repo.cloneUrl, branch: repo.defaultBranch || "main" },
      }).unwrap()
      toast.success(`Cloud sync enabled — ${repo.fullName}`)
      try {
        await runSync(projectId).unwrap()
      } catch (err) {
        toast.error(`Initial sync didn't complete: ${formatError(err)}`)
      }
    } catch (err) {
      toast.error(`Failed to enable cloud sync: ${formatError(err)}`)
    }
  }

  const enableButton = (
    <Button variant="outline" onClick={() => setCreateOpen(true)} disabled={!oauthConnected || isAttaching}>
      <GithubIcon className="size-4 mr-1.5" />
      Create a GitHub repository
    </Button>
  )

  return (
    <div className="border-t pt-3">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        aria-expanded={open}
      >
        {open ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
        GitHub (legacy)
      </button>
      {open && (
        <div className="mt-3 space-y-3">
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
          </div>
          <RememberedRepos
            repos={linked}
            labelOf={(url) => formatRepoUrl(url) ?? url}
            onReconnect={onReconnect}
            disabled={reconnecting}
          />
          <CreateRepoDialog
            open={createOpen}
            onOpenChange={setCreateOpen}
            onCreated={(repo) => {
              setCreateOpen(false)
              void handleAttach(repo)
            }}
          />
        </div>
      )}
    </div>
  )
}

/** Synced state: read-only repo summary, Advanced (auto-sync + PAT), and Disconnect. */
function SyncedConfigPanel({
  projectId,
  config,
  isVenue,
}: {
  projectId: number
  config: SyncConfig
  isVenue: boolean
}) {
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [disconnectOpen, setDisconnectOpen] = useState(false)
  const cloud = config.cloud === true
  const repoLabel = repoLabelOf(config) ?? config.repoUrl ?? "—"
  const where = cloud ? "a Lighting7 Cloud repo" : "a GitHub repository"

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold">Cloud sync</h2>
          <p className="text-xs text-muted-foreground">
            {isVenue
              ? `Synced to ${where}. Published versions push and pull; the draft stays here.`
              : `Synced to ${where}. Changes push and pull automatically.`}
          </p>
        </div>
        <Badge variant="secondary" className="text-[10px] shrink-0">synced</Badge>
      </div>

      {/* Identity row — surfaces re-auth if the GitHub connection lapses. */}
      {!cloud && (
        <div className="border rounded-md p-3 bg-muted/20">
          <IdentityRow projectId={projectId} />
        </div>
      )}

      <div className="space-y-1">
        <Label className="text-xs text-muted-foreground">Repository</Label>
        <div className="flex items-center gap-2 min-w-0">
          {cloud ? (
            <Cloud className="size-4 shrink-0 opacity-70" />
          ) : (
            <GithubIcon className="size-4 shrink-0 opacity-70" />
          )}
          <span className="text-sm font-medium truncate">{repoLabel}</span>
          <Badge variant="outline" className="text-[10px] shrink-0">{config.branch}</Badge>
        </div>
        {config.repoUrl && !cloud && (
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
          {cloud ? "Advanced — auto-sync" : <>Advanced — auto-sync &amp; access token</>}
          {config.tokenPresent && (
            <Badge variant="secondary" className="ml-2 text-[10px]">PAT stored</Badge>
          )}
        </button>
        {advancedOpen && (
          <div className="mt-3 space-y-4">
            <AutoSyncForm projectId={projectId} config={config} />
            {!cloud && (
              <div className="border-t pt-4">
                <PatPanel projectId={projectId} config={config} />
              </div>
            )}
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
