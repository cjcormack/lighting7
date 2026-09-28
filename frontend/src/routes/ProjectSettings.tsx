import { useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router"
import { toast } from "sonner"
import { Card } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Loader2 } from "lucide-react"
import { useProjectQuery, useUpdateProjectMutation } from "@/store/projects"
import { Breadcrumbs } from "@/components/Breadcrumbs"
import { formatError } from "@/lib/formatError"
import { parseNullableNumber } from "@/lib/utils"
import { SurfacesContent } from "./Surfaces"
import { StageRegionsContent } from "@/components/stage/StageRegionsContent"
import { RiggingsContent } from "@/components/rigging/RiggingsContent"
import { ProjectSyncContent } from "./CloudSync"
import { CurrentProjectRedirect } from "@/components/CurrentProjectRedirect"

// No `patches`: the patch list is a routed page again at `/projects/:id/patches` (CLAUDE.md
// §List shell), and `App.tsx` redirects `/settings/patches` there.
const TABS = ["general", "surfaces", "stage", "rigging", "sync"] as const
type Tab = (typeof TABS)[number]

function isTab(value: string | undefined): value is Tab {
  return TABS.includes(value as Tab)
}

export function ProjectSettingsRedirect() {
  return <CurrentProjectRedirect to="settings" />
}

export function ProjectSettings() {
  const { projectId, tab } = useParams()
  const navigate = useNavigate()
  const projectIdNum = Number(projectId)
  const { data: project, isLoading } = useProjectQuery(projectIdNum)

  const activeTab: Tab = isTab(tab) ? tab : "general"

  if (isLoading) {
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }
  if (!project) {
    return <Card className="m-4 p-4"><p className="text-destructive">Project not found</p></Card>
  }

  const handleTabChange = (value: string) => {
    const next = isTab(value) ? value : "general"
    const path = next === "general"
      ? `/projects/${projectIdNum}/settings`
      : `/projects/${projectIdNum}/settings/${next}`
    navigate(path, { replace: true })
  }

  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="p-4 space-y-3 border-b">
        <Breadcrumbs projectName={project.name} currentPage="Settings" />
        <div>
          <h1 className="text-lg font-semibold">Project Settings</h1>
          <p className="text-sm text-muted-foreground">
            Configure this project&rsquo;s metadata, control surfaces, stage and rigging.
          </p>
        </div>
        <Tabs value={activeTab} onValueChange={handleTabChange}>
          <TabsList>
            <TabsTrigger value="general">General</TabsTrigger>
            <TabsTrigger value="surfaces">Surfaces</TabsTrigger>
            <TabsTrigger value="stage">Stage</TabsTrigger>
            <TabsTrigger value="rigging">Rigging</TabsTrigger>
            <TabsTrigger value="sync">Sync</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>
      <div className="flex-1 overflow-y-auto min-h-0">
        {activeTab === "general" && (
          <GeneralTab projectId={projectIdNum} />
        )}
        {activeTab === "surfaces" && <SurfacesContent projectId={projectIdNum} />}
        {activeTab === "stage" && <StageTab projectId={projectIdNum} />}
        {activeTab === "rigging" && <RiggingTab projectId={projectIdNum} />}
        {activeTab === "sync" && <ProjectSyncContent projectId={projectIdNum} />}
      </div>
    </div>
  )
}

function GeneralTab({ projectId }: { projectId: number }) {
  const { data: project } = useProjectQuery(projectId)
  const [updateProject, { isLoading: isUpdating }] = useUpdateProjectMutation()
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")

  // Seed once per project identity. Depending on the whole `project` object
  // would clobber in-progress edits whenever the cache refreshes.
  useEffect(() => {
    if (project) {
      setName(project.name)
      setDescription(project.description || "")
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project?.id])

  if (!project) {
    return (
      <div className="p-4 flex justify-center">
        <Loader2 className="size-5 animate-spin" />
      </div>
    )
  }

  const trimmedName = name.trim()
  const dirty =
    trimmedName !== project.name || (description || "") !== (project.description || "")
  const isValid = trimmedName.length > 0

  const handleSave = async () => {
    if (!isValid) return
    try {
      await updateProject({
        id: projectId,
        name: trimmedName,
        description: description || null,
      }).unwrap()
      toast.success("Project saved")
    } catch (err) {
      toast.error(`Failed to save project: ${formatError(err)}`)
    }
  }

  return (
    <div className="p-4 max-w-2xl">
      <Card className="p-4 space-y-4">
        <div className="space-y-2">
          <Label htmlFor="project-name">Project name *</Label>
          <Input
            id="project-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="project-desc">Description</Label>
          <Textarea
            id="project-desc"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
          />
        </div>
        <div className="flex justify-end">
          <Button onClick={handleSave} disabled={!dirty || !isValid || isUpdating}>
            {isUpdating ? "Saving…" : "Save"}
          </Button>
        </div>
      </Card>
    </div>
  )
}

function StageTab({ projectId }: { projectId: number }) {
  return (
    <div className="p-4 space-y-4 max-w-3xl">
      <StageDimensionsCard projectId={projectId} />
      <StageRegionsContent projectId={projectId} />
    </div>
  )
}

function RiggingTab({ projectId }: { projectId: number }) {
  return (
    <div className="p-4 space-y-4 max-w-3xl">
      <RiggingsContent projectId={projectId} />
    </div>
  )
}

function StageDimensionsCard({ projectId }: { projectId: number }) {
  const { data: project } = useProjectQuery(projectId)
  const [updateProject, { isLoading: isUpdating }] = useUpdateProjectMutation()
  const [widthM, setWidthM] = useState<number | null>(null)
  const [depthM, setDepthM] = useState<number | null>(null)
  const [heightM, setHeightM] = useState<number | null>(null)

  // Seed once per project identity; refetches must not clobber edits in flight.
  useEffect(() => {
    if (project) {
      setWidthM(project.stageWidthM)
      setDepthM(project.stageDepthM)
      setHeightM(project.stageHeightM)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project?.id])

  if (!project) {
    return (
      <Card className="p-4 flex justify-center">
        <Loader2 className="size-5 animate-spin" />
      </Card>
    )
  }

  const dirty =
    widthM !== project.stageWidthM ||
    depthM !== project.stageDepthM ||
    heightM !== project.stageHeightM

  const handleSave = async () => {
    try {
      await updateProject({
        id: projectId,
        stageWidthM: widthM,
        stageDepthM: depthM,
        stageHeightM: heightM,
      }).unwrap()
      toast.success("Stage dimensions saved")
    } catch (err) {
      toast.error(`Failed to save stage dimensions: ${formatError(err)}`)
    }
  }

  return (
    <Card className="p-4 space-y-4">
      <div>
        <h2 className="text-sm font-semibold">Stage Dimensions</h2>
        <p className="text-xs text-muted-foreground">
          Bounding box (metres) used to frame the 3D view. Leave blank to fall back to defaults.
        </p>
      </div>
      <div className="grid grid-cols-3 gap-3">
        <DimensionField
          id="stage-width"
          label="Width"
          value={widthM}
          onChange={setWidthM}
        />
        <DimensionField
          id="stage-depth"
          label="Depth"
          value={depthM}
          onChange={setDepthM}
        />
        <DimensionField
          id="stage-height"
          label="Height"
          value={heightM}
          onChange={setHeightM}
        />
      </div>
      <div className="flex justify-end">
        <Button onClick={handleSave} disabled={!dirty || isUpdating}>
          {isUpdating ? "Saving…" : "Save"}
        </Button>
      </div>
    </Card>
  )
}

function DimensionField({
  id,
  label,
  value,
  onChange,
}: {
  id: string
  label: string
  value: number | null
  onChange: (v: number | null) => void
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label} (m)</Label>
      <Input
        id={id}
        type="number"
        min={0}
        value={value ?? ""}
        onChange={(e) => onChange(parseNullableNumber(e.target.value))}
        onFocus={(e) => e.target.select()}
      />
    </div>
  )
}
