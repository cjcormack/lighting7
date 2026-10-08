import { Button } from "@/components/ui/button"
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip"
import { Crosshair } from "lucide-react"
import { useLocate } from "@/hooks/useLocate"
import { useLocateStateQuery, useToggleLocateMutation, type LocateTargetType } from "@/store/locate"

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
            // The tooltip is not an accessible name, so the icon-only form carries its own.
            aria-label={iconOnly ? tooltip : undefined}
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

/**
 * Locate over several targets at once — the fixture sheet's header acting on its head strip's pick
 * (HeadsGroups board, note 1: "Locate and Highlight act on the pick"): the picked heads of a fixture,
 * or the picked members of a group, each its own locate target. Lit while every one is located; a
 * press locates the ones that are not, or releases them all when every one is.
 */
export function LocateTargetsButton({
  targets,
  name,
}: {
  targets: readonly { type: LocateTargetType; key: string }[]
  name: string
}) {
  const { data, isFetching } = useLocateStateQuery()
  const [toggleLocate, { isLoading }] = useToggleLocateMutation()
  const located = (t: { type: LocateTargetType; key: string }) =>
    data?.targets.some((l) => l.type === t.type && l.key === t.key) ?? false
  const isActive = targets.length > 0 && targets.every(located)
  const tooltip = isActive ? `Release locate on ${name}` : `Locate ${name}: white beam at centre position`
  const toggle = () => {
    for (const t of targets) {
      if (isActive || !located(t)) {
        toggleLocate({ type: t.type, key: t.key })
          .unwrap()
          .catch((err) => console.error(`Locate toggle failed for ${t.type} '${t.key}'`, err))
      }
    }
  }
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="inline-flex">
          <Button
            variant={isActive ? "default" : "outline"}
            size="icon"
            // Busy until the invalidated refetch lands, as `useLocate` is, so a quick second press
            // does not toggle against a stale state.
            disabled={isLoading || isFetching || targets.length === 0}
            aria-label={tooltip}
            className={[isActive ? "bg-sky-500 hover:bg-sky-600 text-white" : "", "size-8"].filter(Boolean).join(" ")}
            onClick={toggle}
          >
            <Crosshair className="size-3.5" />
          </Button>
        </span>
      </TooltipTrigger>
      <TooltipContent>{tooltip}</TooltipContent>
    </Tooltip>
  )
}
