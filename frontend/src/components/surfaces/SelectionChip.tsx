import { Button } from "@/components/ui/button"
import { useIsDeskConnected } from "@/store/status"
import { DESK_OFFLINE_LABEL } from "@/api/wsGesture"
import { clearDeskSelection, useDeskSelection } from "@/store/selection"

/**
 * What the desk currently has selected — the thing every selection-relative control on the panel
 * is pointing at, so it belongs in the panel's own header rather than somewhere the operator has
 * to go and look.
 *
 * *Clear* is the one selection **write** this view carries. Adding to the selection from here
 * would be a second way to do what the fixtures list, the busk target band and the surface's own
 * select buttons already do; emptying it is the gesture that has no other home when the only
 * thing that filled it was a button on the desk.
 *
 * **The design's fixture count is deliberately absent, not forgotten.** Summing each selected
 * group's `memberCount` is wrong the moment two groups share a head — it says eight where the
 * desk will write six — and the client cannot do better: `GroupSummary` carries a count and no
 * member keys, so distinct coverage would mean a `GroupDetail` fetch per selected group from a
 * header chip. The desk already knows the answer (`DeskSelection.coverage()`); a truthful count
 * is that number reaching the wire, not arithmetic here. A wrong number on a desk is worse than
 * no number, and the names beside it already say what is selected.
 */
export function SelectionChip() {
  const targets = useDeskSelection()
  const connected = useIsDeskConnected()

  if (targets.length === 0) {
    return (
      <span className="text-xs text-muted-foreground">
        <span className="font-medium text-foreground/70">Selection</span> — nothing selected
      </span>
    )
  }

  return (
    <span className="flex items-center gap-2 text-xs">
      <span className="font-medium text-foreground/70">Selection</span>
      <span className="truncate">{targets.map((t) => t.key).join(" · ")}</span>
      <Button
        size="sm"
        variant="ghost"
        className="h-6 px-2 text-xs"
        onClick={() => clearDeskSelection()}
        disabled={!connected}
        title={connected ? undefined : DESK_OFFLINE_LABEL}
      >
        Clear
      </Button>
    </span>
  )
}
