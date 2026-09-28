import { useState } from "react"
import { toast } from "sonner"
import {
  Loader2,
  CloudUpload,
  Check,
  RefreshCw,
  AlertTriangle,
} from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  useCloudSyncConfigQuery,
  useCloudSyncStatusQuery,
  useCloudSyncConflictsQuery,
  useCloudSyncSnapshotMutation,
  useCloudSyncRunMutation,
  type SyncConfig,
  type SyncStatus,
} from "@/store/cloudSync"
import { useOauthGithubIdentityQuery } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"

/** Working-tree status, "Take snapshot" and "Sync now", with the reason either is unavailable. */
export function StatusPanel({ projectId }: { projectId: number }) {
  const { data: status, isLoading } = useCloudSyncStatusQuery(projectId)
  const { data: config } = useCloudSyncConfigQuery(projectId)
  const { data: conflictsData } = useCloudSyncConflictsQuery(projectId)
  const { data: identity } = useOauthGithubIdentityQuery()
  const [snapshot, { isLoading: isSnapshotting }] = useCloudSyncSnapshotMutation()
  const [runSync, { isLoading: isSyncing }] = useCloudSyncRunMutation()
  const [snapshotPopoverOpen, setSnapshotPopoverOpen] = useState(false)
  const [message, setMessage] = useState("")

  if (isLoading || !status) {
    return (
      <Card className="p-4 flex items-center justify-center">
        <Loader2 className="size-5 animate-spin" />
      </Card>
    )
  }

  const handleSnapshot = async () => {
    try {
      const result = await snapshot({
        projectId,
        message: message.trim() || null,
      }).unwrap()
      setSnapshotPopoverOpen(false)
      setMessage("")
      if (result.noChanges) {
        toast("No changes since last snapshot")
      } else if (result.commit) {
        toast.success(`Snapshot ${result.commit.shortSha} committed`)
      }
    } catch (err) {
      toast.error(`Snapshot failed: ${formatError(err)}`)
    }
  }

  const handleSyncNow = async () => {
    try {
      const result = await runSync(projectId).unwrap()
      switch (result.outcome) {
        case "NO_OP":
          toast("Already in sync — nothing to push or pull")
          break
        case "PUSHED":
          toast.success(`Pushed ${result.pushed} commit(s) to remote`)
          break
        case "FAST_FORWARDED":
          toast.success(`Pulled ${result.pulled} commit(s) from remote`)
          break
        case "MERGED":
          toast.success(
            result.pushed + result.pulled > 0
              ? `Merged ${result.pushed + result.pulled} commit(s) cleanly`
              : "Merged with remote",
          )
          break
        case "CONFLICTS_PENDING":
          toast.warning(
            `Found ${result.conflictCount ?? 0} conflict(s) — resolve them below to continue`,
            { duration: 8000 },
          )
          break
      }
    } catch (err) {
      toast.error(`Sync failed: ${formatError(err)}`)
    }
  }

  // Sync-now needs all three prerequisites; the first failing one drives the tooltip text.
  // Phase 5: also blocked while a conflict session is open — the user has to resolve
  // (or abort) the existing one first, otherwise the run would 409 SESSION_PENDING.
  const sessionPending = conflictsData?.activeSession === true
  // A rejected OAuth identity is not a credential: `connected` alone would enable a
  // "Sync now" that can only fail. The PAT half still stands on its own — a desk with a
  // working PAT syncs fine while its OAuth connection is broken.
  const oauthUsable = identity?.connected === true && identity.reauthRequired !== true
  const hasCredentials = oauthUsable || config?.tokenPresent === true
  const syncDisabledReason = (() => {
    if (!config?.repoUrl) return "No repository attached — enable cloud sync first"
    if (identity?.reauthRequired === true && config?.tokenPresent !== true) {
      return "GitHub rejected this desk's authorisation — reconnect above"
    }
    if (!hasCredentials) return "Connect GitHub or store a Personal Access Token"
    if (sessionPending) return "Resolve or abort the open conflict session first"
    return null
  })()
  const syncEnabled = !syncDisabledReason && !isSyncing

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-2 min-w-0 flex-1">
          <h2 className="text-sm font-semibold">Status</h2>
          <div className="space-y-1">
            <Label className="text-muted-foreground text-xs">Working tree</Label>
            <div className="font-mono text-xs break-all">{status.workingTreePath}</div>
          </div>
          <RepoStatusBody status={status} />
          <LastSyncedBody config={config} />
        </div>
        <div className="flex gap-2 flex-wrap justify-end">
          <Popover open={snapshotPopoverOpen} onOpenChange={setSnapshotPopoverOpen}>
            <PopoverTrigger asChild>
              <Button disabled={isSnapshotting} variant="outline">
                <CloudUpload className="size-4 mr-1.5" />
                Take snapshot
              </Button>
            </PopoverTrigger>
            <PopoverContent className="w-80 p-3" align="end">
              <div className="space-y-2">
                <Label htmlFor="snapshot-message" className="text-xs">
                  Message (optional)
                </Label>
                <Input
                  id="snapshot-message"
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                  placeholder="What changed?"
                  className="text-xs h-8"
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleSnapshot()
                  }}
                  autoFocus
                />
                <div className="flex justify-end">
                  <Button size="sm" onClick={handleSnapshot} disabled={isSnapshotting}>
                    <Check className="size-3.5 mr-1" />
                    {isSnapshotting ? "Committing…" : "Commit"}
                  </Button>
                </div>
              </div>
            </PopoverContent>
          </Popover>
          <Button
            onClick={handleSyncNow}
            disabled={!syncEnabled}
            title={syncDisabledReason ?? undefined}
          >
            <RefreshCw className={`size-4 mr-1.5 ${isSyncing ? "animate-spin" : ""}`} />
            {isSyncing ? "Syncing…" : "Sync now"}
          </Button>
        </div>
      </div>
      {syncDisabledReason && (
        <div className="flex items-center gap-2 text-xs text-amber-600">
          <AlertTriangle className="size-3.5" />
          {syncDisabledReason}
        </div>
      )}
    </Card>
  )
}

function LastSyncedBody({ config }: { config: SyncConfig | undefined }) {
  if (!config?.lastSyncedSha) return null
  const when = config.lastSyncedAt ? new Date(config.lastSyncedAt).toLocaleString() : "—"
  return (
    <div className="space-y-1">
      <Label className="text-muted-foreground text-xs">Last synced</Label>
      <div className="text-xs">
        <span className="font-mono mr-2">{config.lastSyncedSha.slice(0, 7)}</span>
        <span className="text-muted-foreground">{when}</span>
      </div>
    </div>
  )
}

function RepoStatusBody({ status }: { status: SyncStatus }) {
  if (!status.hasRepo) {
    return (
      <div className="text-xs text-muted-foreground italic">
        No git repo yet — take the first snapshot to initialise it.
      </div>
    )
  }
  return (
    <>
      <div className="space-y-1">
        <Label className="text-muted-foreground text-xs">HEAD</Label>
        {status.head ? (
          <div className="text-xs">
            <span className="font-mono mr-2">{status.head.shortSha}</span>
            <span>{status.head.message.split("\n")[0]}</span>
          </div>
        ) : (
          <div className="text-xs text-muted-foreground italic">No commits yet</div>
        )}
      </div>
      {status.dirty ? (
        <Badge variant="secondary">Working tree dirty</Badge>
      ) : status.head ? (
        <Badge variant="outline">Clean</Badge>
      ) : null}
    </>
  )
}

