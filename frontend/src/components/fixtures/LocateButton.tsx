import { Button } from "@/components/ui/button"
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip"
import { Crosshair } from "lucide-react"
import { useLocate } from "@/hooks/useLocate"
import type { LocateTargetType } from "@/store/locate"

/**
 * Toggle Locate on a fixture or group: centre pan/tilt and force an open white beam so
 * the physical unit can be spotted in the rig. Backed by sticky Layer-4 writes on the
 * backend — releasing cascades the channels back to whatever the show is doing.
 *
 * Unlike unpark this needs no edit-mode gate: locate is self-reverting and cannot drop
 * a safety hold, so it stays one click in both directions.
 */
export function LocateButton({
  type,
  targetKey,
  name,
  iconOnly = false,
}: {
  type: LocateTargetType
  targetKey: string
  name: string
  iconOnly?: boolean
}) {
  const { isActive, toggle, isToggling } = useLocate(type, targetKey)

  const tooltip = isActive
    ? `Release locate on ${name}`
    : `Locate ${name}: white beam at centre position`

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* Wrapper span keeps the tooltip reachable while the button is disabled. */}
        <span className="inline-flex">
          <Button
            variant={isActive ? "default" : "outline"}
            size={iconOnly ? "icon" : "sm"}
            disabled={isToggling}
            className={[
              isActive ? "bg-sky-500 hover:bg-sky-600 text-white" : "",
              iconOnly ? "size-8" : "",
            ].filter(Boolean).join(" ")}
            onClick={toggle}
          >
            <Crosshair className="size-3.5" />
            {!iconOnly && (isActive ? " Located" : " Locate")}
          </Button>
        </span>
      </TooltipTrigger>
      <TooltipContent>{tooltip}</TooltipContent>
    </Tooltip>
  )
}
