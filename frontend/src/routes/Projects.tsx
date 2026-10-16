import { useState } from "react"
import { useNavigate } from "react-router"
import { Card, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Alert, AlertDescription } from "@/components/ui/alert"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { Cloud, CloudDownload, Landmark, PackageOpen, PlusCircle, Upload, XCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import { useProjectListQuery } from "../store/projects"
import { useCloudSyncConfigsQuery, type SyncConfig } from "../store/cloudSync"
import { useOauthGithubIdentityQuery } from "../store/oauthGithub"
import { useIsNavAdmin } from "../navigation"
import { formatRepoUrl } from "@/lib/formatRepoUrl"
import { ProjectSummary } from "../api/projectApi"
import CreateProjectDialog from "../CreateProjectDialog"
import ImportProjectDialog from "../ImportProjectDialog"
import { AddRemoteProjectDialog } from "../components/cloudSync/ImportFromRemoteDialog"
import { VenueTag } from "../components/venue/VenueTag"
import { ProjectActionsMenu, useProjectActions } from "../components/projectActions/ProjectActions"
import { ExtractVenueSheet } from "../components/venue/ExtractVenueSheet"
import { useVenueHistoryQuery } from "../store/venues"
import { draftChipLabel, publishedDate, venueBadge, venueSummaryLines } from "@/lib/venue"

export default function Projects() {
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [addVenueOpen, setAddVenueOpen] = useState(false)
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
      {/* *Add venue* (venue plan session 2): an empty venue, an admin's. Extracting one from a show's
          rig is the Venues row's *Extract a venue from a project…* (ProjectsContainer). */}
      <CreateProjectDialog
        open={addVenueOpen}
        setOpen={setAddVenueOpen}
        kind="VENUE"
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
        {/* Wraps: at phone width the five actions do not fit beside the title (venue plan session 7). */}
        <div className="flex flex-wrap items-center justify-between gap-2 mb-6">
          <h1 className="text-3xl font-bold">Projects</h1>
          <div className="flex flex-wrap gap-2">
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
            {isAdmin && (
              <Button variant="outline" onClick={() => setAddVenueOpen(true)}>
                <Landmark className="size-4" />
                Add venue
              </Button>
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

/**
 * The page's two rows (`Projects.dc.html`): **Venues** — each venue's card, and an admin's *Extract a
 * venue from a project…* — then **Shows**. A desk with no venues, seen by an operator, draws the shows
 * alone, as it always did.
 */
function ProjectsContainer() {
  const { data: projects, isLoading, error } = useProjectListQuery()
  // Sync state for the per-card badge. Sparse map keyed by stringified project id;
  // projects that have never been synced are simply absent.
  const { data: syncConfigs } = useCloudSyncConfigsQuery()
  const isAdmin = useIsNavAdmin()
  const [extractOpen, setExtractOpen] = useState(false)

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

  const venues = (projects ?? []).filter(p => p.kind === "VENUE")
  const shows = (projects ?? []).filter(p => p.kind !== "VENUE")
  // What an extract can start from: a show that is not on a venue already.
  const standalone = shows.filter(p => p.venue == null)
  const card = (project: ProjectSummary) => (
    <ProjectCard
      key={project.id}
      project={project}
      syncConfig={syncConfigs?.[String(project.id)]}
    />
  )

  return (
    <>
      {isAdmin && (
        <ExtractVenueSheet
          open={extractOpen}
          onOpenChange={setExtractOpen}
          shows={standalone}
        />
      )}
      {(venues.length > 0 || isAdmin) && (
        <section aria-labelledby="venues-heading" className="mb-6">
          <h2 id="venues-heading" className="mb-2 text-xs font-bold uppercase tracking-wider text-muted-foreground">
            Venues
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {venues.map(card)}
            {isAdmin && (
              <button
                type="button"
                onClick={() => setExtractOpen(true)}
                disabled={standalone.length === 0}
                title={standalone.length === 0 ? "Every show is already on a venue" : undefined}
                className="flex min-h-24 flex-col items-start gap-1 rounded-xl border border-dashed p-3 text-left transition-colors hover:bg-accent/50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <span className="flex items-center gap-2 text-sm font-medium">
                  <PackageOpen className="size-4" aria-hidden />
                  Extract a venue from a project…
                </span>
                <span className="text-xs text-muted-foreground">
                  Moves a project&apos;s room, house patch, universes and bars into a new venue at v1, and points the
                  project at it.
                </span>
              </button>
            )}
          </div>
        </section>
      )}
      <section aria-labelledby="shows-heading">
        {(venues.length > 0 || isAdmin) && (
          <h2 id="shows-heading" className="mb-2 text-xs font-bold uppercase tracking-wider text-muted-foreground">
            Shows
          </h2>
        )}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">{shows.map(card)}</div>
      </section>
    </>
  )
}

function ProjectCard({
  project,
  syncConfig,
}: {
  project: ProjectSummary
  syncConfig?: SyncConfig
}) {
  const navigate = useNavigate()
  const isVenue = project.kind === "VENUE"
  const { actions, dialogs } = useProjectActions(project, "card")

  return (
    <>
      {dialogs}
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
              <CardTitle className="flex items-center gap-2 text-lg">
                <span className="truncate">{project.name}</span>
                {isVenue && <VenueTag className="shrink-0" />}
              </CardTitle>
              <p className="text-sm text-muted-foreground">
                {project.description || "\u00A0"}
              </p>
              {isVenue && <VenueCardLines project={project} />}
            </div>
            <ProjectActionsMenu actions={actions} label={`${project.name} actions`} className="-mr-2 -mt-1" />
          </div>
        </CardHeader>
        <CardFooter className="p-0 px-3 mt-auto flex flex-wrap items-center gap-x-2 gap-y-1">
          <Badge variant={project.isCurrent ? "default" : "outline"}>
            {project.isCurrent ? "active" : "inactive"}
          </Badge>
          {!isVenue && <ShowVenueBadge project={project} />}
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

/**
 * A show card's venue badge (`Projects.dc.html`'s callout 2): the venue and its pinned version, amber
 * when a newer version is published, red-amber when the newer one changes output (the show opens
 * held); *No venue* for a standalone show.
 */
function ShowVenueBadge({ project }: { project: ProjectSummary }) {
  if (project.venue == null) {
    return (
      <Badge variant="outline" className="text-muted-foreground">
        No venue
      </Badge>
    )
  }
  const badge = venueBadge(project.venue)
  return (
    <>
      <Badge variant="outline" className="min-w-0 gap-1" data-venue-badge>
        <Landmark className="size-3 shrink-0" aria-hidden />
        <span className="truncate">{badge.label}</span>
      </Badge>
      {badge.newer && (
        <Badge
          variant="outline"
          data-venue-behind={badge.tone}
          title={
            badge.tone === "output"
              ? "A newer version changes output: this show opens held until it is updated, or run against its version"
              : "A newer version of the venue is published"
          }
          className={cn(
            "shrink-0",
            badge.tone === "output"
              ? "border-orange-500/60 bg-orange-500/10 text-orange-700 dark:text-orange-300"
              : "border-amber-500/60 bg-amber-500/10 text-amber-700 dark:text-amber-300",
          )}
        >
          {badge.newer}
        </Badge>
      )}
    </>
  )
}

/**
 * A venue card's lines (`Projects.dc.html`'s Venues row): its installed version and date, an admin's
 * draft count, what the version holds and how its shows stand.
 */
function VenueCardLines({ project }: { project: ProjectSummary }) {
  const isAdmin = useIsNavAdmin()
  // History is an admin's (an operator sees a venue read-only, with no draft).
  const { data: history } = useVenueHistoryQuery(project.id, { skip: !isAdmin })
  const summary = project.venueSummary
  if (!summary) return null
  const lines = venueSummaryLines(summary)
  return (
    <div className="mt-1 space-y-0.5 text-xs">
      <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <span className="font-mono tabular-nums">
          {summary.version != null
            ? `v${summary.version}${summary.publishedAt ? ` · ${publishedDate(summary.publishedAt)}` : ""}`
            : "Nothing published"}
        </span>
        {history && history.draft.changes > 0 && (
          <span className="rounded border border-teal-500/50 px-1 text-teal-700 dark:text-teal-300">{draftChipLabel(history)}</span>
        )}
      </p>
      <p className="text-muted-foreground">{lines.rig}</p>
      <p className="text-muted-foreground">{lines.shows}</p>
    </div>
  )
}
