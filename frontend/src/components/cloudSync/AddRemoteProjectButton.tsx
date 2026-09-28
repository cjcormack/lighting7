import { CloudDownload } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"

/** Hub toolbar action opening the import-from-remote dialog; disabled without usable OAuth. */
export function AddRemoteProjectButton({
  oauthConnected,
  onClick,
}: {
  oauthConnected: boolean
  onClick: () => void
}) {
  const button = (
    <Button
      variant="outline"
      size="sm"
      className="gap-1.5"
      disabled={!oauthConnected}
      onClick={oauthConnected ? onClick : undefined}
    >
      <CloudDownload className="size-3.5" />
      Add remote project
    </Button>
  )
  if (oauthConnected) return button
  // tabIndex on the wrapper is the documented Radix workaround for tooltips on disabled
  // controls — disabled buttons don't dispatch the pointer events the tooltip listens for.
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0}>{button}</span>
      </TooltipTrigger>
      <TooltipContent side="left">
        Connect GitHub above to add a synced project from a remote repository.
      </TooltipContent>
    </Tooltip>
  )
}
