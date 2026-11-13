import { useState } from "react"
import { toast } from "sonner"
import {
  Cloud,
  Loader2,
  CloudUpload,
  ChevronDown,
  ChevronRight,
  TriangleAlert,
  Unlink,
} from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { AutoSyncForm } from "@/components/cloudSync/AutoSyncForm"
import { DisconnectConfirmDialog } from "@/components/cloudSync/DisconnectConfirmDialog"
import { AdoptToSync } from "@/components/cloudSync/AdoptToSync"
import { useCloudAdopted } from "@/components/cloudSync/useCloudAdopted"
import {
  useCloudReposQuery,
  useCloudSyncConfigQuery,
  useCloudMoveMutation,
  useCloudSyncToCloudMutation,
  useCloudSyncReconnectMutation,
  useCloudSyncRunMutation,
  type CloudMoveRow,
  type LinkedRepo,
  type SyncConfig,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"
import { formatRepoUrl, repoLabel as repoLabelOf } from "@/lib/formatRepoUrl"

/**
 * Cloud-sync configuration. A project is synced iff a repository is attached, so this
 * panel has two shapes: an attach call-to-action when not synced, and a read-only
 * summary + disconnect when synced. A project attached to a GitHub repo, which the desk
 * does not sync with, is offered *Move to the cloud* instead; one attached to any other repo
 * that is not this desk's cloud's is told so.
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

  if (!config.synced) return <AttachConfigPanel projectId={projectId} config={config} />
  if (config.github === true) return <MoveToCloudPanel projectId={projectId} config={config} />
  if (config.cloud !== true) return <NotACloudRepoPanel projectId={projectId} config={config} />
  return <SyncedConfigPanel projectId={projectId} config={config} isVenue={isVenue} />
}

/**
 * Not-synced state (lighting7 cloud-p2 plan D6): *Sync to the cloud* and the cloud repos this project
 * synced before, or, unadopted, a way to the Cloud tab.
 */
function AttachConfigPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const { adopted } = useCloudAdopted()
  const [syncToCloud, { isLoading: isSyncing }] = useCloudSyncToCloudMutation()
  const [reconnect, { isLoading: isReconnecting }] = useCloudSyncReconnectMutation()
  const [runSync] = useCloudSyncRunMutation()
  const cloudLinked = config.linkedRepos.filter((r) => r.cloud)
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

/** Synced state: read-only repo summary, Advanced (auto-sync), and Disconnect. */
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
  const repoLabel = repoLabelOf(config) ?? config.repoUrl ?? "—"

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold">Cloud sync</h2>
          <p className="text-xs text-muted-foreground">
            {isVenue
              ? "Synced to a Lighting7 Cloud repo. Published versions push and pull; the draft stays here."
              : "Synced to a Lighting7 Cloud repo. Changes push and pull automatically."}
          </p>
        </div>
        <Badge variant="secondary" className="text-[10px] shrink-0">synced</Badge>
      </div>

      <div className="space-y-1">
        <Label className="text-xs text-muted-foreground">Repository</Label>
        <div className="flex items-center gap-2 min-w-0">
          <Cloud className="size-4 shrink-0 opacity-70" />
          <span className="text-sm font-medium truncate">{repoLabel}</span>
          <Badge variant="outline" className="text-[10px] shrink-0">{config.branch}</Badge>
        </div>
        <p className="text-[10px] text-muted-foreground">
          The repository can&rsquo;t be changed — disconnect to link a different one.
        </p>
      </div>

      {/* Advanced — auto-sync cadence, collapsed by default. */}
      <div className="border-t pt-3">
        <button
          type="button"
          onClick={() => setAdvancedOpen((o) => !o)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          {advancedOpen ? <ChevronDown className="size-3" /> : <ChevronRight className="size-3" />}
          Advanced — auto-sync
        </button>
        {advancedOpen && (
          <div className="mt-3">
            <AutoSyncForm projectId={projectId} config={config} />
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

/**
 * A project attached to a repo neither GitHub's nor this desk's cloud's: another cloud's, or this
 * cloud's under a mistyped `cloud.baseUrl`. Nothing here can move it; disconnecting frees the project
 * to sync to this desk's cloud.
 */
function NotACloudRepoPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const [disconnectOpen, setDisconnectOpen] = useState(false)
  return (
    <Card className="p-4 space-y-4">
      <div>
        <h2 className="text-sm font-semibold">Cloud sync</h2>
        <p className="text-xs text-muted-foreground">
          This project&rsquo;s repository is not one of this desk&rsquo;s cloud&rsquo;s, so the desk
          cannot sync with it. Check the desk&rsquo;s cloud address, or disconnect it and sync it to
          the cloud.
        </p>
        {config.repoUrl && (
          <p className="text-[10px] text-muted-foreground font-mono break-all mt-1">
            {formatRepoUrl(config.repoUrl)}
          </p>
        )}
      </div>
      <Button variant="outline" onClick={() => setDisconnectOpen(true)}>
        <Unlink className="size-3.5 mr-1.5" />
        Disconnect repository
      </Button>
      <DisconnectConfirmDialog
        open={disconnectOpen}
        onOpenChange={setDisconnectOpen}
        projectId={projectId}
        repoLabel={repoLabelOf(config) ?? config.repoUrl ?? "—"}
      />
    </Card>
  )
}

/**
 * A project attached to a GitHub repo (cloud-p2 plan D8): the desk does not sync with it, so the one
 * thing to do is move it, history and all, to a cloud repo. A move that lands refetches the config,
 * which draws the synced panel in this one's place, so its outcome is a toast; a failure stays here.
 */
function MoveToCloudPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const { adopted, remote } = useCloudAdopted()
  const [move, { isLoading: moving, error, reset }] = useCloudMoveMutation()
  const [failed, setFailed] = useState<CloudMoveRow | null>(null)

  const handleMove = async () => {
    reset()
    setFailed(null)
    try {
      const row = (await move([projectId]).unwrap()).find((r) => r.projectId === projectId)
      if (row == null) {
        setFailed({ projectId, name: "", status: "FAILED", message: "The desk did not say how the move went: reload and look again." })
        return
      }
      const name = row.repoName ?? "its cloud repo"
      if (row.status === "FAILED") setFailed(row)
      else if (row.status === "MOVED_DIVERGED") {
        toast.success(`Moved to ${name}`, { description: "Another desk changed it there too: its next sync merges the two." })
      } else toast.success(`Moved to ${name}`)
    } catch {
      // Shown below.
    }
  }

  return (
    <Card className="p-4 space-y-4">
      <div>
        <h2 className="text-sm font-semibold">Cloud sync</h2>
        <p className="text-xs text-muted-foreground">
          This project is attached to GitHub, which the desk does not sync with. Moving it pushes its whole
          history to a cloud repo, every commit unchanged, then syncs it there.
        </p>
        {config.repoUrl && (
          <p className="text-[10px] text-muted-foreground font-mono break-all mt-1">
            {formatRepoUrl(config.repoUrl)}
          </p>
        )}
      </div>

      {adopted == null ? (
        <Loader2 className="size-4 animate-spin" />
      ) : !adopted ? (
        <AdoptToSync what="move it to the cloud" />
      ) : remote ? (
        <p className="text-sm text-muted-foreground">
          A project can only be moved to the cloud from the desk itself, never over remote access.
        </p>
      ) : (
        <Button onClick={() => void handleMove()} disabled={moving}>
          {moving ? <Loader2 className="size-4 mr-1.5 animate-spin" /> : <CloudUpload className="size-4 mr-1.5" />}
          {failed ? "Try it again" : "Move to the cloud"}
        </Button>
      )}

      {(failed != null || error != null) && (
        <Alert variant="destructive">
          <TriangleAlert className="size-4" />
          <AlertDescription>
            Not moved: {failed ? (failed.message ?? failed.code ?? "the move failed") : formatError(error)}
          </AlertDescription>
        </Alert>
      )}
    </Card>
  )
}
