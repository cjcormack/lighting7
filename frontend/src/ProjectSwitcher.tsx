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
import { useOAuthReauthState } from "./store/oauthGithub"
import { useNavItems, filterNavItems, useIsNavAdmin } from "./navigation"

interface ProjectSwitcherProps {
  collapsed?: boolean
}

/**
 * Nav entries that a rejected GitHub authorisation is about. Both, because either is a route to
 * the fix and which one a person reaches for depends on where they already are.
 *
 * Hard-coded ids rather than a field on the registry: this is the only attention source there is,
 * and inverting it into `navigation.ts` would mean the registry importing a store slice. If a
 * second source ever appears, that is the point to add a `useNavAttention()` returning a set of
 * ids and let this read from it.
 */
const SYNC_NAV_IDS: ReadonlySet<string> = new Set(["sync", "project-sync"])
const ATTENTION_LABEL = "GitHub needs reconnecting"

export default function ProjectSwitcher({ collapsed }: ProjectSwitcherProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const { data: projects, isLoading: projectsLoading } = useProjectListQuery()
  const allNavItems = useNavItems()
  const isNavAdmin = useIsNavAdmin()

  const { reauthRequired } = useOAuthReauthState()

  const viewedProject = useViewedProject()
  const activeProject = projects?.find((p) => p.isCurrent)
  const isViewingActiveProject = viewedProject?.id === activeProject?.id
  const visibleItems = filterNavItems(allNavItems, isViewingActiveProject, isNavAdmin)
  // Operators never see the Sync entries at all (they're adminOnly), and the hook reports
  // nothing wrong for them regardless — so this is only ever a dot on a row they can act on.
  const needsAttention = (id: string) => reauthRequired && SYNC_NAV_IDS.has(id)

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

        {/* Navigation items, grouped with separators */}
        <div className="flex flex-col gap-1 px-2">
          {visibleItems.map((item, idx) => (
            <React.Fragment key={item.id}>
              {idx > 0 && item.group !== visibleItems[idx - 1].group && (
                <Separator className="mx-1 my-1" />
              )}
              <NavItem
                icon={<item.icon className="size-5" />}
                label={item.label}
                isActive={activeId === item.id}
                collapsed
                onClick={() => navigate(item.path(viewedProject.id))}
                muted={item.group === "install"}
                attention={needsAttention(item.id)}
                attentionLabel={ATTENTION_LABEL}
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
          className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left transition-colors hover:bg-accent min-w-0"
        >
          <FolderOpen className="size-4 text-muted-foreground shrink-0" />
          <span className="font-semibold truncate">{viewedProject.name}</span>
          {!isViewingActiveProject && (
            <Badge variant="outline" className="text-[10px] px-1.5 py-0 flex-shrink-0 ml-auto">
              Inactive
            </Badge>
          )}
        </button>
      </div>

      {/* Navigation items, grouped with separators */}
      <div className="px-2 space-y-0.5">
        {visibleItems.map((item, idx) => (
          <React.Fragment key={item.id}>
            {idx > 0 && item.group !== visibleItems[idx - 1].group && (
              <Separator className="mx-1 my-2" />
            )}
            <NavItem
              icon={<item.icon className="size-4" />}
              label={item.label}
              isActive={activeId === item.id}
              collapsed={false}
              onClick={() => navigate(item.path(viewedProject.id))}
              muted={item.group === "install"}
              indent={item.parent != null}
              attention={needsAttention(item.id)}
              attentionLabel={ATTENTION_LABEL}
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
  indent?: boolean
  /** Show a destructive dot: this destination has something the user needs to deal with. */
  attention?: boolean
  /** Why, for the collapsed tooltip. Ignored unless [attention]. */
  attentionLabel?: string
}

export function NavItem({
  icon,
  label,
  isActive,
  collapsed,
  onClick,
  muted,
  indent,
  attention,
  attentionLabel,
}: NavItemProps) {
  // A dot rather than a count: what these carry is "something here needs you", and it has to
  // read the same collapsed (overlaid on the icon, the only space there is) as expanded.
  const dot = (
    <span
      aria-hidden
      className={cn(
        "size-2 rounded-full bg-destructive ring-2 ring-sidebar",
        collapsed ? "absolute right-1.5 top-1" : "ml-auto mr-0.5 flex-shrink-0"
      )}
    />
  )
  const button = (
    <button
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-2 rounded-md py-1.5 text-sm transition-colors",
        "hover:bg-accent hover:text-accent-foreground",
        isActive && "bg-accent text-accent-foreground",
        collapsed ? "justify-center px-2" : (indent ? "pl-7 pr-3" : "px-3"),
        muted && "text-muted-foreground",
        // Only positioned when it has to be — an unconditional `relative` would change
        // stacking for every row in the sidebar.
        attention && collapsed && "relative"
      )}
    >
      <span className="flex-shrink-0">{icon}</span>
      {!collapsed && <span className="truncate">{label}</span>}
      {attention && dot}
    </button>
  )

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{button}</TooltipTrigger>
        {/* Collapsed, the label *is* the tooltip, so the reason has to ride along with it —
            otherwise a red dot on an icon is a puzzle rather than a warning. */}
        <TooltipContent side="right">
          {attention && attentionLabel ? `${label} — ${attentionLabel}` : label}
        </TooltipContent>
      </Tooltip>
    )
  }

  return button
}
