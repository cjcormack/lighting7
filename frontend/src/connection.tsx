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
    <div className="flex items-center gap-2">
      <Tooltip>
        <TooltipTrigger asChild>
          <div>
            {/* Compact: icon-only pill on narrow viewports */}
            <div className={cn(
              "@[760px]:hidden flex items-center justify-center rounded-lg border p-1.5 bg-card",
              config.variant === "destructive" && "text-destructive",
              config.iconClassName,
              config.alertClassName?.replace(/\[&>svg\][^ ]*/g, ''),
            )}>
              {config.icon}
            </div>
            {/* Full: Alert with label on wider viewports */}
            <Alert
              variant={config.variant}
              className={cn("hidden @[760px]:grid w-fit py-1 px-3 gap-x-2!", config.alertClassName)}
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
