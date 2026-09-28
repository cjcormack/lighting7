import { Maximize2, Minimize2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { toggleImmersive } from '@/lib/immersive'

/**
 * The expand glyph (busk-chrome plan D7, D11; `Immersive.dc.html`): the control and the readout in
 * one 32px button — outward arrows with the app drawn, inward arrows lit while immersive, the
 * titles the board's. A per-window fact, so it toggles `lib/immersive.ts` and nothing else; the
 * `Layout` above reads the same store and hides or draws the app around the row this sits on.
 * `aria-pressed` rather than a second element, because it is one control in two states.
 *
 * Drawn by `ShowHeader` on every live view, and by `ImmersiveEscape` on the arms of those routes
 * that render no header (loading, not found), so an immersive window always has the way back on
 * screen — the header is the way out (D11), and where there is no header the glyph is.
 */
export function ImmersiveToggle({ immersive }: { immersive: boolean }) {
  const label = immersive
    ? 'Show the app again — the sidebar and the app header (this window)'
    : 'Expand over the app — hide the sidebar and the app header (this window, every live view)'
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="outline"
          size="icon-sm"
          aria-pressed={immersive}
          aria-label={immersive ? 'Show the app' : 'Expand over the app'}
          data-immersive={immersive ? 'on' : 'off'}
          className={cn(immersive && 'border-primary/50 bg-primary/10 text-primary hover:bg-primary/15 hover:text-primary')}
          onClick={toggleImmersive}
        >
          {immersive ? <Minimize2 className="size-4" /> : <Maximize2 className="size-4" />}
        </Button>
      </TooltipTrigger>
      <TooltipContent side="bottom">{label}</TooltipContent>
    </Tooltip>
  )
}
