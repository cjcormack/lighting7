import { Button, Tooltip, TooltipContent, TooltipTrigger } from 'lighting-desk-ui'
import { Lock } from 'lucide-react'

// A tooltip names an icon-only control — the show lock, the theme toggle, the
// programmer chip. Rendered open, uncontrolled, above its trigger with the arrow.
// The card leaves room above the trigger for the bubble; the TooltipProvider is
// supplied by the card wrapper.
export const LockedShow = () => (
  <div className="flex h-[280px] w-full items-center justify-center">
    <Tooltip defaultOpen>
      <TooltipTrigger asChild>
        <Button variant="outline" size="icon" aria-label="Show locked">
          <Lock />
        </Button>
      </TooltipTrigger>
      <TooltipContent side="top" sideOffset={6}>
        Show locked — press L to unlock editing
      </TooltipContent>
    </Tooltip>
  </div>
)
