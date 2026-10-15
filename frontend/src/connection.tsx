import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { CheckCircle, AlertTriangle, XCircle, Loader2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { Status } from "./api/statusApi"
import { useReconnectMutation, useStatusQuery } from "./store/status"

interface StatusConfig {
  icon: React.ReactNode
  label: string
  variant?: "destructive"
  alertClassName?: string
  iconClassName?: string
}

function getStatusConfig(status: Status): StatusConfig | null {
  switch (status) {
    case Status.CONNECTING:
      return { icon: <Loader2 className="size-4 animate-spin" />, label: "Connecting..." }
    case Status.OPEN:
      return {
        icon: <CheckCircle className="size-4" />,
        label: "Connected",
        alertClassName: "border-green-500/50 text-green-600 dark:text-green-400 [&>svg]:text-green-600 dark:[&>svg]:text-green-400",
        iconClassName: "text-green-600 dark:text-green-400",
      }
    case Status.CLOSING:
      return { icon: <AlertTriangle className="size-4" />, label: "Disconnecting...", variant: "destructive" }
    case Status.CLOSED:
      return { icon: <XCircle className="size-4" />, label: "Disconnected", variant: "destructive" }
    default:
      return null
  }
}

export const ConnectionStatus = () => {
  const { data: readyState } = useStatusQuery()
  const [runReconnectMutation] = useReconnectMutation()

  if (readyState === undefined) return null

  const config = getStatusConfig(readyState)
  if (!config) throw new Error("Unknown ReadyState")

  return (
    // Connected says nothing on a phone's header, which has no room for it beside the grand master's
    // chip and the programmer chip; anything but Connected still draws.
    <div className={cn("flex items-center gap-2", readyState === Status.OPEN && "@max-[619px]:hidden")}>
      <Tooltip>
        <TooltipTrigger asChild>
          <div>
            {/* Compact: icon-only pill on narrow viewports */}
            <div className={cn(
              "@[760px]:hidden flex size-7 items-center justify-center rounded-lg border bg-card",
              config.variant === "destructive" && "text-destructive",
              config.iconClassName,
              config.alertClassName?.replace(/\[&>svg\][^ ]*/g, ''),
            )}>
              {config.icon}
            </div>
            {/* Full: Alert with label on wider viewports */}
            <Alert
              variant={config.variant}
              className={cn("hidden @[760px]:grid h-7 w-fit items-center py-0 px-3 gap-x-2! [&>svg]:translate-y-0", config.alertClassName)}
            >
              {config.icon}
              <AlertDescription>{config.label}</AlertDescription>
            </Alert>
          </div>
        </TooltipTrigger>
        <TooltipContent className="@[760px]:hidden">{config.label}</TooltipContent>
      </Tooltip>
      {readyState === Status.CLOSED && (
        <Button
          variant="destructive"
          size="sm"
          onClick={() => runReconnectMutation()}
        >
          Reconnect
        </Button>
      )}
    </div>
  )
}
