import { useParams, useNavigate, Navigate } from "react-router"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Alert, AlertDescription } from "@/components/ui/alert"
import {
  Braces,
  Sparkles,
  Settings,
  Loader2,
  XCircle,
  ArrowRight,
  Layers,
  LayoutGrid,
  SlidersHorizontal,
  AudioWaveform,
  SwatchBook,
  Clapperboard,
  TableProperties,
  Sliders,
} from "lucide-react"
import { Breadcrumbs } from "@/components/Breadcrumbs"
import { useProjectQuery, useCurrentProjectQuery } from "../store/projects"
import { useVisibleFixtureListQuery } from "../store/fixtures"
import { usePatchListQuery } from "../store/patches"
import { useLookListQuery } from "../store/looks"
import { useGroupListQuery } from "../store/groups"
import { useGetUniverseQuery } from "../store/universes"
import { useSurfaceBindingsQuery, useSurfaceDevices } from "../store/surfaces"
import { cn } from "@/lib/utils"

export default function ProjectOverview() {
  const { projectId } = useParams<{ projectId: string }>()
  const navigate = useNavigate()

  const { data: project, isLoading, error } = useProjectQuery(Number(projectId), {
    skip: !projectId,
  })

  // Fetch fixtures, groups, and universes only for the current project (they're global to active project)
  const { data: fixtures } = useVisibleFixtureListQuery({
    skip: !project?.isCurrent,
  })
  const { data: groups } = useGroupListQuery(undefined, {
    skip: !project?.isCurrent,
  })
  const { data: universes } = useGetUniverseQuery(undefined, {
    skip: !project?.isCurrent,
  })
  const { data: looks } = useLookListQuery(
    { projectId: Number(projectId) },
    { skip: !project?.isCurrent },
  )
  const { data: patches } = usePatchListQuery(Number(projectId), {
    skip: !project,
  })
  const { data: surfaceBindings } = useSurfaceBindingsQuery(Number(projectId), {
    skip: !project,
  })
  const surfaceDevices = useSurfaceDevices()
  const connectedSurfaces = project?.isCurrent ? surfaceDevices.length : 0

  if (!projectId) {
    return <Navigate to="/projects" replace />
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (error || !project) {
    return (
      <div className="p-4">
        <Alert variant="destructive">
          <XCircle className="size-4" />
          <AlertDescription>Failed to load project</AlertDescription>
        </Alert>
      </div>
    )
  }

  return (
    <>
      <div className="p-4 space-y-6">
        {/* Breadcrumbs */}
        <Breadcrumbs projectName={project.name} isActive={project.isCurrent} />

        {/* Header */}
        <div className="flex items-start justify-between">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-3xl font-bold">{project.name}</h1>
              <Badge variant={project.isCurrent ? "default" : "outline"}>
                {project.isCurrent ? "active" : "inactive"}
              </Badge>
            </div>
            {project.description && (
              <p className="text-muted-foreground mt-1">{project.description}</p>
            )}
          </div>
          <Button variant="outline" onClick={() => navigate(`/projects/${project.id}/settings`)}>
            <Settings className="size-4" />
            Configure
          </Button>
        </div>

        {/* Quick Stats */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <QuickNavCard
            title="Patch List"
            count={patches?.length}
            icon={<TableProperties className="size-5" />}
            description="Manage fixture patching"
            onClick={() => navigate(`/projects/${project.id}/patches`)}
          />
          <QuickNavCard
            title="Scripts"
            count={project.scriptCount}
            icon={<Braces className="size-5" />}
            description="Kotlin lighting scripts"
            onClick={() => navigate(`/projects/${project.id}/scripts`)}
          />
          <QuickNavCard
            title="FX Library"
            icon={<Sparkles className="size-5" />}
            description="Built-in and custom effects"
            onClick={() => navigate(`/projects/${project.id}/fx-library`)}
          />
          {project.isCurrent && (
            <>
              <QuickNavCard
                title="Fixtures"
                count={fixtures?.length}
                icon={<LayoutGrid className="size-5" />}
                description="DMX fixture definitions"
                onClick={() => navigate(`/projects/${project.id}/fixtures`)}
              />
              <QuickNavCard
                title="Groups"
                count={groups?.length}
                icon={<Layers className="size-5" />}
                description="Fixture groups for control"
                onClick={() => navigate(`/projects/${project.id}/groups`)}
              />
              <QuickNavCard
                title="Surfaces"
                count={surfaceBindings?.length}
                icon={<Sliders className="size-5" />}
                description={
                  connectedSurfaces > 0
                    ? `${connectedSurfaces} device${connectedSurfaces !== 1 ? 's' : ''} connected`
                    : "MIDI control surface bindings"
                }
                onClick={() => navigate(`/projects/${project.id}/settings/surfaces`)}
              />
              <QuickNavCard
                title="Busk"
                icon={<AudioWaveform className="size-5" />}
                description="Pads, targets and tempo on one performance surface"
                onClick={() => navigate(`/projects/${project.id}/busk`)}
              />
              <QuickNavCard
                title="Looks"
                count={looks?.length}
                icon={<SwatchBook className="size-5" />}
                description="Reusable bundles of values and effects"
                onClick={() => navigate(`/projects/${project.id}/looks`)}
              />
              <QuickNavCard
                title="Cues"
                count={project.cueCount}
                icon={<Clapperboard className="size-5" />}
                description={`${project.cueStackCount} stack${project.cueStackCount !== 1 ? 's' : ''}, ${project.cueCount} cue${project.cueCount !== 1 ? 's' : ''}`}
                onClick={() => navigate(`/projects/${project.id}/show`)}
              />
              {universes && universes.length >= 1 && (
                <QuickNavCard
                  title="Universes"
                  count={universes.length}
                  icon={<SlidersHorizontal className="size-5" />}
                  description="DMX channel control"
                  onClick={() => navigate(`/projects/${project.id}/channels/${universes[0]}`)}
                />
              )}
            </>
          )}
        </div>

      </div>
    </>
  )
}

// Redirect component for root path
export function ProjectOverviewRedirect() {
  const { data: currentProject, isLoading } = useCurrentProjectQuery()

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (currentProject) {
    return <Navigate to={`/projects/${currentProject.id}`} replace />
  }

  // No current project, go to projects list
  return <Navigate to="/projects" replace />
}

function QuickNavCard({
  title,
  count,
  icon,
  description,
  onClick,
}: {
  title: string
  count?: number
  icon: React.ReactNode
  description: string
  onClick: () => void
}) {
  return (
    <Card
      className="cursor-pointer hover:bg-accent/50 transition-colors"
      onClick={onClick}
    >
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-muted-foreground">
            {icon}
            <CardTitle className="text-base">{title}</CardTitle>
          </div>
          <Badge
            variant="secondary"
            className={cn("text-lg px-2", count === undefined && "invisible")}
          >
            {count ?? 0}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="pb-4">
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">{description}</p>
          <ArrowRight className="size-4 text-muted-foreground" />
        </div>
      </CardContent>
    </Card>
  )
}

