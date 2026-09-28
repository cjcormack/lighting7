import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip"
import { Lock, LockOpen } from "lucide-react"
import type { Fixture } from "@/store/fixtures"
import { useFixturePark } from "@/hooks/useFixturePark"

/**
 * Park / unpark every channel of a fixture.
 *
 * Parking is always available — it only locks output where it already is. Unparking hands
 * live control back to the show, so it is gated on the surrounding Edit mode and confirmed:
 * park is what holds hard-powered fixtures on a dimmer at a safe level, and a stray click
 * on a read-only page must not release it.
 */
export function FixtureParkButton({
  fixture,
  isEditing,
  iconOnly = false,
}: {
  fixture: Fixture
  /** Edit state of the surrounding view. Unpark is only offered when true. */
  isEditing: boolean
  iconOnly?: boolean
}) {
  const { parkedCount, totalChannels, isPartiallyParked, isAnyParked, parkFixture, unparkFixture } =
    useFixturePark(fixture)

  const canUnpark = isAnyParked && isEditing

  const handleClick = () => {
    if (!isAnyParked) {
      parkFixture()
      return
    }
    if (!canUnpark) return
    if (confirm(`Unpark ${parkedCount} channel(s) on ${fixture.name}?`)) {
      unparkFixture()
    }
  }

  const tooltip = !isAnyParked
    ? `Park all ${totalChannels} channels at current values`
    : canUnpark
      ? `Unpark all ${parkedCount} channel(s)`
      : `${parkedCount} channel(s) parked — enable Edit mode to unpark`

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/* Wrapper span keeps the tooltip reachable while the button is disabled. */}
        <span className="inline-flex">
          <Button
            variant={isAnyParked ? "default" : "outline"}
            size={iconOnly ? "icon" : "sm"}
            disabled={isAnyParked && !canUnpark}
            className={[
              isAnyParked ? "bg-amber-500 hover:bg-amber-600 text-white disabled:opacity-100" : "",
              iconOnly ? "size-8" : "",
            ].filter(Boolean).join(" ")}
            onClick={handleClick}
          >
            {canUnpark ? <LockOpen className="size-3.5" /> : <Lock className="size-3.5" />}
            {!iconOnly && (isAnyParked ? (canUnpark ? " Unpark" : " Parked") : " Park")}
            {!iconOnly && isPartiallyParked && (
              <Badge variant="secondary" className="ml-1 px-1 py-0 text-[10px]">
                {parkedCount}/{totalChannels}
              </Badge>
            )}
          </Button>
        </span>
      </TooltipTrigger>
      <TooltipContent>{tooltip}</TooltipContent>
    </Tooltip>
  )
}
