import { useMemo } from "react"
import { useNavigate, useParams } from "react-router"
import { Sliders } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { cn } from "@/lib/utils"
import { useSurfaceBindingsQuery, useControlSurfaceTypeListQuery } from "@/store/surfaces"
import type { ControlSurfaceBinding } from "@/store/surfaces"
import { controlLabel, matchesBindingTarget, shortDeviceLabel, type BindingTargetMatcher } from "./targetUtils"

interface BoundControlBadgeProps {
  match: BindingTargetMatcher
  /** Pre-filtered bindings for this match. If provided, skips the global filter pass. */
  preFiltered?: ControlSurfaceBinding[]
  className?: string
}

/**
 * Small chip that renders when a control surface binding maps to this element.
 * Clicking it navigates to `/surfaces` with the binding pre-selected.
 */
export function BoundControlBadge({ match, preFiltered, className }: BoundControlBadgeProps) {
  const { projectId } = useParams()
  const navigate = useNavigate()
  const projectIdNum = Number(projectId)
  const skipQuery = !projectIdNum || preFiltered != null
  const { data: bindings } = useSurfaceBindingsQuery(projectIdNum, { skip: skipQuery })
  const { data: types } = useControlSurfaceTypeListQuery()

  const matched = useMemo(
    () =>
      preFiltered ??
      (bindings ?? []).filter((b) => matchesBindingTarget(b.target, match)),
    [preFiltered, bindings, match],
  )
  if (matched.length === 0) return null

  return (
    <TooltipProvider delayDuration={200}>
      <div className={cn("inline-flex items-center gap-1", className)}>
        {matched.map((binding) => {
          const deviceLabel = shortDeviceLabel(binding.deviceTypeKey)
          const profile = (types ?? []).find((t) => t.typeKey === binding.deviceTypeKey)
          return (
            <Tooltip key={binding.id}>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation()
                    e.preventDefault()
                    navigate(`/projects/${projectIdNum}/settings/surfaces?binding=${binding.id}`)
                  }}
                  className="inline-flex"
                >
                  <Badge
                    variant="outline"
                    className="h-5 px-1.5 text-[10px] gap-1 font-mono cursor-pointer hover:bg-accent"
                  >
                    <Sliders className="size-2.5" />
                    {deviceLabel} {controlLabel(profile, binding.controlId)}
                  </Badge>
                </button>
              </TooltipTrigger>
              <TooltipContent>
                <div className="text-xs">
                  <div>{binding.deviceTypeKey}</div>
                  <div className="text-muted-foreground">
                    {binding.controlId}
                    {binding.bank ? ` · bank ${binding.bank}` : " · global"}
                  </div>
                </div>
              </TooltipContent>
            </Tooltip>
          )
        })}
      </div>
    </TooltipProvider>
  )
}
