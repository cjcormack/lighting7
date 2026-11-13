import React from "react"
import { useLocation, useNavigate, useParams } from "react-router"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Loader2, FolderOpen } from "lucide-react"
import { cn } from "@/lib/utils"
import { mostSpecificActiveId } from './lib/navMatch'
import { useProjectListQuery } from "./store/projects"
import { useNavItems, filterNavItems, useIsNavAdmin, NAV_SECTIONS, OFF_RIG_LABEL, type NavItem as NavEntry, type NavSection } from "./navigation"
import { OffRigDot } from "./components/NotOnRig"
import { VenueTag } from "./components/venue/VenueTag"

interface ProjectSwitcherProps {
  collapsed?: boolean
}

function sectionLabel(section: NavSection): string {
  return NAV_SECTIONS.find((s) => s.id === section)?.label ?? section
}

/** Install Settings and its tabs are drawn muted, as the machine's rather than the project's. */
function isInstallRow(item: NavEntry): boolean {
  return item.id === "install-settings" || item.parent === "install-settings"
}

export default function ProjectSwitcher({ collapsed }: ProjectSwitcherProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const { data: projects, isLoading: projectsLoading } = useProjectListQuery()
  const allNavItems = useNavItems()
  const isNavAdmin = useIsNavAdmin()

  const viewedProject = useViewedProject()
  const activeProject = projects?.find((p) => p.isCurrent)
  const isViewingActiveProject = viewedProject?.id === activeProject?.id
  // A venue's rail is its own: the show's views go, the Programmer stays (venue plan §4).
  const isVenue = viewedProject?.kind === "VENUE"
  const visibleItems = filterNavItems(allNavItems, isViewingActiveProject, isNavAdmin, isVenue)

  if (projectsLoading) {
    return (
      <div className="flex justify-center p-4">
        <Loader2 className="size-5 animate-spin" />
      </div>
    )
  }

  if (!viewedProject) return null

  const activeId = mostSpecificActiveId(visibleItems, location.pathname)

  // Collapsed view - icon-only buttons
  if (collapsed) {
    return (
      <div className="flex flex-col">
        {/* Project link (collapsed) */}
        <div className="px-2 pb-2 mb-1 border-b">
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                className="w-full"
                onClick={() => navigate(`/projects/${viewedProject.id}`)}
              >
                <FolderOpen className="size-5" />
              </Button>
            </TooltipTrigger>
            <TooltipContent side="right">{viewedProject.name}</TooltipContent>
          </Tooltip>
        </div>

        {/* Navigation items: on the rail a section's heading is the rule above it */}
        <div className="flex flex-col gap-1 px-2">
          {visibleItems.map((item, idx) => (
            <React.Fragment key={item.id}>
              {idx > 0 && item.section !== visibleItems[idx - 1].section && (
                <Separator className="mx-1 my-1" />
              )}
              <NavItem
                icon={<item.icon className="size-5" />}
                label={item.label}
                isActive={activeId === item.id}
                collapsed
                onClick={() => navigate(item.path(viewedProject.id))}
                muted={isInstallRow(item)}
                offRig={item.offRig}
              />
            </React.Fragment>
          ))}
        </div>
      </div>
    )
  }

  // Expanded view
  return (
    <div className="flex flex-col">
      {/* Project header - read as metadata, not a nav row */}
      <div className="px-2 pb-2 mb-2 border-b">
        <button
          onClick={() => navigate(`/projects/${viewedProject.id}`)}
          // Wraps, so a venue's tag and *Not on the rig* drop to a second line before the name truncates.
          className="flex w-full flex-wrap items-center gap-x-2 gap-y-1 rounded-md px-2 py-1.5 text-left transition-colors hover:bg-accent min-w-0"
        >
          <FolderOpen className="size-4 text-muted-foreground shrink-0" />
          <span className="font-semibold truncate">{viewedProject.name}</span>
          {isVenue && <VenueTag className="flex-shrink-0" />}
          {!isViewingActiveProject && (
            <Badge variant="outline" className="text-[10px] px-1.5 py-0 flex-shrink-0 ml-auto text-muted-foreground">
              Not on the rig
            </Badge>
          )}
        </button>
      </div>

      {/* Navigation items under their section headings; a section with no row draws no heading */}
      <div className="px-2 space-y-0.5">
        {visibleItems.map((item, idx) => (
          <React.Fragment key={item.id}>
            {(idx === 0 || item.section !== visibleItems[idx - 1].section) && (
              <div
                data-nav-section={item.section}
                className={cn(
                  "px-3 pb-1 text-[9px] font-bold uppercase tracking-[0.09em] text-muted-foreground",
                  idx === 0 ? "pt-1" : "pt-3",
                )}
              >
                {sectionLabel(item.section)}
              </div>
            )}
            <NavItem
              icon={<item.icon className="size-4" />}
              label={item.label}
              isActive={activeId === item.id}
              collapsed={false}
              onClick={() => navigate(item.path(viewedProject.id))}
              muted={isInstallRow(item)}
              offRig={item.offRig}
              indent={item.parent != null}
            />
          </React.Fragment>
        ))}
      </div>
    </div>
  )
}

/** Hook to get the currently viewed project. */
export function useViewedProject() {
  const params = useParams()
  const { data: projects } = useProjectListQuery()

  const activeProject = projects?.find((p) => p.isCurrent)
  const viewedProjectId = params.projectId ? parseInt(params.projectId) : activeProject?.id
  return projects?.find((p) => p.id === viewedProjectId) ?? activeProject
}

// Reusable nav item component
interface NavItemProps {
  icon: React.ReactNode
  label: string
  isActive: boolean
  collapsed: boolean
  onClick: () => void
  muted?: boolean
  /**
   * The row needs its project on the rig and the viewed project is not on it (navigation plan D4):
   * muted, with a hollow dot, and [OFF_RIG_LABEL] on its tooltip.
   */
  offRig?: boolean
  indent?: boolean
}

export function NavItem({
  icon,
  label,
  isActive,
  collapsed,
  onClick,
  muted,
  offRig,
  indent,
}: NavItemProps) {
  const offRigDot = <OffRigDot className={collapsed ? "absolute right-1.5 top-1" : "ml-auto mr-0.5"} />
  const button = (
    <button
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-2 rounded-md py-1.5 text-sm transition-colors",
        "hover:bg-accent hover:text-accent-foreground",
        isActive && "bg-accent text-accent-foreground",
        collapsed ? "justify-center px-2" : (indent ? "pl-7 pr-3" : "px-3"),
        (muted || offRig) && "text-muted-foreground",
        // Only positioned when it has to be — an unconditional `relative` would change
        // stacking for every row in the sidebar.
        offRig && collapsed && "relative"
      )}
      data-off-rig={offRig ? "" : undefined}
      data-nav-child={indent ? "" : undefined}
    >
      <span className="flex-shrink-0">{icon}</span>
      {!collapsed && <span className="truncate">{label}</span>}
      {offRig && offRigDot}
    </button>
  )

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{button}</TooltipTrigger>
        {/* Collapsed, the label *is* the tooltip, so the reason has to ride along with it. */}
        <TooltipContent side="right">{offRig ? `${label} — ${OFF_RIG_LABEL}` : label}</TooltipContent>
      </Tooltip>
    )
  }

  if (offRig) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{button}</TooltipTrigger>
        <TooltipContent side="right">{OFF_RIG_LABEL}</TooltipContent>
      </Tooltip>
    )
  }

  return button
}
