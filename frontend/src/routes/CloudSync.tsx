import { useEffect, useState } from "react"
import { Navigate } from "react-router"
import { useDispatch } from "react-redux"
import { Loader2 } from "lucide-react"
import { lightingApi } from "@/api/lightingApi"
import { restApi } from "@/store/restApi"
import { Card } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useProjectListQuery } from "@/store/projects"
import {
  useCloudSyncConfigQuery,
  useCloudSyncConfigsQuery,
} from "@/store/cloudSync"
import { useOauthGithubIdentityQuery } from "@/store/oauthGithub"
import { ConflictPanel } from "@/components/cloudSync/ConflictPanel"
import { IdentityRow } from "@/components/cloudSync/IdentityRow"
import { AddRemoteProjectDialog } from "@/components/cloudSync/ImportFromRemoteDialog"
import { AddRemoteProjectButton } from "@/components/cloudSync/AddRemoteProjectButton"
import { ProjectSyncRow } from "@/components/cloudSync/ProjectSyncRow"
import { ConfigPanel } from "@/components/cloudSync/ConfigPanel"
import { StatusPanel } from "@/components/cloudSync/StatusPanel"
import { ActivityPanel } from "@/components/cloudSync/ActivityPanel"
import { HistoryPanel } from "@/components/cloudSync/HistoryPanel"

// ─── Hub body (rendered as the Sync tab inside Install Settings) ─────

export function CloudSyncHubBody() {
  const { data: projects, isLoading: projectsLoading } = useProjectListQuery()
  const { data: configs, isLoading: configsLoading } = useCloudSyncConfigsQuery()
  const { data: identity } = useOauthGithubIdentityQuery()
  const [importOpen, setImportOpen] = useState(false)
  const dispatch = useDispatch()
  const isLoading = projectsLoading || configsLoading
  // Usable, not merely present: a rejected identity can only 401 the repo-listing call
  // this button leads to, so it stays disabled until the user reconnects.
  const oauthConnected = identity?.connected === true && identity.reauthRequired !== true

  // Pick up imports done from another tab — the importing tab itself relies on the
  // mutation's invalidatesTags to refresh, but a WS-only listener catches the cross-tab
  // case without polling.
  //
  // No identity listener here: the module-scope bridge in `store/oauthGithub` keeps that cache
  // live for the whole app, which it has to now that the sidebar badge and the global banner
  // depend on it too.
  useEffect(() => {
    const subImported = lightingApi.cloudSync.subscribeProjectImported(() => {
      dispatch(restApi.util.invalidateTags(['ProjectList', 'CloudSyncConfig']))
    })
    return () => {
      subImported.unsubscribe()
    }
  }, [dispatch])

  return (
    <div className="space-y-4 max-w-5xl">
      <Card className="p-4 space-y-3">
        <div>
          <h2 className="text-sm font-semibold">GitHub</h2>
          <p className="text-xs text-muted-foreground">
            Connect once — the same identity is used by every project.
          </p>
        </div>
        <IdentityRow projectId={null} />
      </Card>

      <Card className="p-4 space-y-3">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold">Projects</h2>
            <p className="text-xs text-muted-foreground">
              Select a project to manage its sync, take snapshots, or resolve conflicts.
            </p>
          </div>
          <AddRemoteProjectButton
            oauthConnected={oauthConnected}
            onClick={() => setImportOpen(true)}
          />
        </div>
        {isLoading ? (
          <div className="flex justify-center py-6">
            <Loader2 className="size-5 animate-spin" />
          </div>
        ) : !projects || projects.length === 0 ? (
          <p className="text-xs text-muted-foreground italic">
            No projects yet — create one from the Projects page.
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Project</TableHead>
                <TableHead>Sync</TableHead>
                <TableHead className="hidden md:table-cell">Repository</TableHead>
                <TableHead className="hidden sm:table-cell">Branch</TableHead>
                <TableHead className="hidden lg:table-cell">Last synced</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {projects.map((project) => (
                <ProjectSyncRow
                  key={project.id}
                  projectId={project.id}
                  projectName={project.name}
                  isActive={project.isCurrent}
                  config={configs?.[String(project.id)]}
                />
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      <AddRemoteProjectDialog
        open={importOpen}
        onOpenChange={setImportOpen}
        oauthConnected={oauthConnected}
      />
    </div>
  )
}

// ─── Hub redirect (legacy /sync paths land here) ──────────────────────

export function CloudSyncHubRedirect() {
  return <Navigate to="/install/sync" replace />
}

// ─── Per-project sync (Project Settings → Sync tab) ──────────────────

/**
 * The per-project cloud-sync UI, rendered inside Project Settings → Sync. The status /
 * conflict / activity / history panels only make sense once a repo is attached, so they
 * are gated on `config.synced`; the config panel handles both the not-synced (attach)
 * and synced states.
 */
export function ProjectSyncContent({ projectId }: { projectId: number }) {
  const dispatch = useDispatch()
  const { data: config, isLoading } = useCloudSyncConfigQuery(projectId)

  // Refresh status / log / config when the backend broadcasts a sync completion. The
  // mutation already invalidates these tags on success, but a sync triggered from a
  // *different* tab arrives only via WebSocket — this listener catches that case.
  useEffect(() => {
    const onDone = () => {
      dispatch(restApi.util.invalidateTags(['CloudSyncStatus', 'CloudSyncLog', 'CloudSyncConfig']))
    }
    const onFailed = () => {
      // Refresh status/log on failure too — the snapshot step inside the pipeline may
      // have committed before the network step failed.
      dispatch(restApi.util.invalidateTags(['CloudSyncStatus', 'CloudSyncLog']))
    }
    const subDone = lightingApi.cloudSync.subscribeDone(onDone)
    const subFailed = lightingApi.cloudSync.subscribeFailed(onFailed)
    return () => {
      subDone.unsubscribe()
      subFailed.unsubscribe()
    }
  }, [dispatch])

  if (isLoading && !config) {
    return (
      <div className="p-4 max-w-4xl">
        <Card className="p-4 flex items-center justify-center">
          <Loader2 className="size-6 animate-spin" />
        </Card>
      </div>
    )
  }

  return (
    <div className="p-4 space-y-4 max-w-4xl">
      <ConfigPanel projectId={projectId} />
      {config?.synced && (
        <>
          <StatusPanel projectId={projectId} />
          <ConflictPanel projectId={projectId} />
          {/* key= forces a fresh mount per project so accumulated paged state resets cleanly. */}
          <ActivityPanel key={`activity-${projectId}`} projectId={projectId} />
          <HistoryPanel key={`history-${projectId}`} projectId={projectId} />
        </>
      )}
    </div>
  )
}
