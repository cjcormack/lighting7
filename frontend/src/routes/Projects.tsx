import { useState } from "react"
import { useNavigate } from "react-router"
import { Card, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Alert, AlertDescription } from "@/components/ui/alert"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { Cloud, CloudDownload, MoreVertical, PlusCircle, Upload, XCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import {
  useProjectListQuery,
  useCurrentProjectQuery,
  useDeleteProjectMutation,
  useSetCurrentProjectMutation,
} from "../store/projects"
import { useCloudSyncConfigsQuery, type SyncConfig } from "../store/cloudSync"
import { useOauthGithubIdentityQuery } from "../store/oauthGithub"
import { useIsNavAdmin } from "../navigation"
import { formatRepoUrl } from "@/lib/formatRepoUrl"
import { ProjectSummary } from "../api/projectApi"
import CreateProjectDialog from "../CreateProjectDialog"
import DeleteProjectConfirmDialog from "../DeleteProjectConfirmDialog"
import ProjectSwitchConfirmDialog from "../ProjectSwitchConfirmDialog"
import CloneProjectDialog from "../CloneProjectDialog"
import ExportProjectDialog from "../ExportProjectDialog"
import ImportProjectDialog from "../ImportProjectDialog"
import { AddRemoteProjectDialog } from "../components/cloudSync/ImportFromRemoteDialog"

export default function Projects() {
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [importDialogOpen, setImportDialogOpen] = useState(false)
  const [addRemoteOpen, setAddRemoteOpen] = useState(false)
  // Skipped for operators: `/api/rest/oauth/` is admin-gated, so their refetch can only 403 —
  // the same reliance `store/users.ts` documents for `useUsersQuery`. They lose nothing, since
  // the button it gates is one they cannot use either.
  const isAdmin = useIsNavAdmin()
  const { data: identity } = useOauthGithubIdentityQuery(undefined, { skip: !isAdmin })
  // Usable, not merely present — importing a remote project needs a working token, so a
  // rejected identity must not offer the flow. See `OAuthIdentity.reauthRequired`.
  const oauthConnected = identity?.connected === true && identity.reauthRequired !== true

  const addRemoteButton = (
    <Button
      variant="outline"
      disabled={!oauthConnected}
      onClick={oauthConnected ? () => setAddRemoteOpen(true) : undefined}
    >
      <CloudDownload className="size-4" />
      Add remote project
    </Button>
  )

  return (
    <>
      <CreateProjectDialog
        open={createDialogOpen}
        setOpen={setCreateDialogOpen}
      />
      <ImportProjectDialog
        open={importDialogOpen}
        setOpen={setImportDialogOpen}
      />
      <AddRemoteProjectDialog
        open={addRemoteOpen}
        onOpenChange={setAddRemoteOpen}
        oauthConnected={oauthConnected}
      />
      <Card className="m-4 p-4">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-3xl font-bold">Projects</h1>
          <div className="flex gap-2">
            {isAdmin && (
              <Button variant="outline" onClick={() => setImportDialogOpen(true)}>
                <Upload className="size-4" />
                Import
              </Button>
            )}
            {oauthConnected ? (
              addRemoteButton
            ) : (
              // Radix tooltip needs a focusable wrapper to fire on a disabled button.
              <Tooltip>
                <TooltipTrigger asChild>
                  <span tabIndex={0}>{addRemoteButton}</span>
                </TooltipTrigger>
                <TooltipContent>
                  Connect GitHub in Install Settings &rsaquo; Sync to add a remote project.
                </TooltipContent>
              </Tooltip>
            )}
            <Button onClick={() => setCreateDialogOpen(true)}>
              <PlusCircle className="size-4" />
              Create Project
            </Button>
          </div>
        </div>
        <ProjectsContainer />
      </Card>
    </>
  )
}

function ProjectsContainer() {
  const { data: projects, isLoading, error } = useProjectListQuery()
  // Sync state for the per-card badge. Sparse map keyed by stringified project id;
  // projects that have never been synced are simply absent.
  const { data: syncConfigs } = useCloudSyncConfigsQuery()

  if (isLoading) {
    return <div>Loading...</div>
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <XCircle className="size-4" />
        <AlertDescription>Failed to load projects</AlertDescription>
      </Alert>
    )
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {projects?.map(project => (
        <ProjectCard
          key={project.id}
          project={project}
          syncConfig={syncConfigs?.[String(project.id)]}
        />
      ))}
    </div>
  )
}

function ProjectCard({
  project,
  syncConfig,
}: {
  project: ProjectSummary
  syncConfig?: SyncConfig
}) {
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [switchOpen, setSwitchOpen] = useState(false)
  const [cloneOpen, setCloneOpen] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const navigate = useNavigate()
  const isAdmin = useIsNavAdmin()

  const { data: currentProject } = useCurrentProjectQuery()

  const [deleteProject, { isLoading: isDeleting, error: deleteError }] =
    useDeleteProjectMutation()
  const [setCurrentProject, { isLoading: isSwitching }] =
    useSetCurrentProjectMutation()

  const handleActivate = () => {
    if (!project.isCurrent) {
      setSwitchOpen(true)
    }
  }

  const handleConfirmSwitch = async () => {
    await setCurrentProject(project.id)
    setSwitchOpen(false)
  }

  const handleDelete = async () => {
    try {
      await deleteProject(project.id).unwrap()
      setDeleteOpen(false)
    } catch {
      // Error handled by dialog
    }
  }

  const is409Error =
    deleteError && "status" in deleteError && deleteError.status === 409

  return (
    <>
      <DeleteProjectConfirmDialog
        open={deleteOpen}
        projectName={project.name}
        isCurrent={project.isCurrent}
        isDeleting={isDeleting}
        error={
          is409Error ? "Cannot delete the currently active project" : undefined
        }
        onConfirm={handleDelete}
        onCancel={() => setDeleteOpen(false)}
      />
      <ProjectSwitchConfirmDialog
        open={switchOpen}
        currentProjectName={currentProject?.name || ""}
        newProjectName={project.name}
        isSwitching={isSwitching}
        onConfirm={handleConfirmSwitch}
        onCancel={() => setSwitchOpen(false)}
      />
      <CloneProjectDialog
        open={cloneOpen}
        setOpen={setCloneOpen}
        sourceProjectId={project.id}
        sourceProjectName={project.name}
      />
      <ExportProjectDialog
        open={exportOpen}
        setOpen={setExportOpen}
        sourceProjectId={project.id}
        sourceProjectName={project.name}
      />
      <Card
        className={cn(
          "flex flex-col gap-0 py-3 cursor-pointer hover:bg-accent/50 transition-colors",
          project.isCurrent && "bg-blue-100 dark:bg-blue-900"
        )}
        onClick={() => navigate(`/projects/${project.id}`)}
      >
        <CardHeader className="p-0 px-3">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <CardTitle className="text-lg">{project.name}</CardTitle>
              <p className="text-sm text-muted-foreground">
                {project.description || "\u00A0"}
              </p>
            </div>
            <DropdownMenu>
              <DropdownMenuTrigger asChild onClick={e => e.stopPropagation()}>
                <Button variant="ghost" size="icon" className="-mr-2 -mt-1">
                  <MoreVertical className="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" onClick={e => e.stopPropagation()}>
                {!project.isCurrent && (
                  <DropdownMenuItem onClick={handleActivate}>
                    Activate
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem onClick={() => navigate(`/projects/${project.id}/scripts`)}>
                  Scripts
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => navigate(`/projects/${project.id}/settings`)}>
                  Configure
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => setCloneOpen(true)}>
                  Clone
                </DropdownMenuItem>
                {isAdmin && (
                  <DropdownMenuItem onClick={() => setExportOpen(true)}>
                    Export
                  </DropdownMenuItem>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  className="text-destructive focus:text-destructive"
                  onClick={() => setDeleteOpen(true)}
                  disabled={project.isCurrent}
                >
                  Delete
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </CardHeader>
        <CardFooter className="p-0 px-3 mt-auto flex items-center gap-2">
          <Badge variant={project.isCurrent ? "default" : "outline"}>
            {project.isCurrent ? "active" : "inactive"}
          </Badge>
          {syncConfig?.synced && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Badge variant="secondary" className="gap-1">
                  <Cloud className="size-3" />
                  synced
                </Badge>
              </TooltipTrigger>
              <TooltipContent>
                {formatRepoUrl(syncConfig.repoUrl) ?? "synced"}
                {syncConfig.lastSyncedAt
                  ? ` · last synced ${new Date(syncConfig.lastSyncedAt).toLocaleString()}`
                  : " · not yet synced"}
              </TooltipContent>
            </Tooltip>
          )}
        </CardFooter>
      </Card>
    </>
  )
}
