import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Loader2, Copy, ExternalLink } from "lucide-react"
import {
  useStartGithubDeviceFlowMutation,
  usePollGithubDeviceFlowMutation,
  type DeviceFlowStartResponse,
} from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"

interface DeviceFlowModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Device-flow fallback. Used when the user can't access this lighting7 install at
 * the URL the GitHub App's Callback URL is registered for (e.g. browsing the
 * studio rig from an iPad on the LAN). The user copies a short code, opens
 * github.com/login/device on any device, types it in, and authorises — we poll
 * the backend until it observes the new identity.
 *
 * The poll loop fires on the interval the device-code response carried (typically
 * 5s). GitHub's `slow_down` response bumps it; `pending` keeps polling; the
 * terminal states (`done` / `expired` / `denied`) close the loop.
 */
export function DeviceFlowModal({ open, onOpenChange }: DeviceFlowModalProps) {
  const [start, { isLoading: starting }] = useStartGithubDeviceFlowMutation()
  const [poll] = usePollGithubDeviceFlowMutation()
  const [code, setCode] = useState<DeviceFlowStartResponse | null>(null)
  const [status, setStatus] = useState<"idle" | "waiting" | "done" | "expired" | "denied" | "error">("idle")
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const intervalRef = useRef<number | null>(null)

  const stopPolling = () => {
    if (intervalRef.current != null) {
      window.clearInterval(intervalRef.current)
      intervalRef.current = null
    }
  }

  useEffect(() => {
    if (!open) {
      stopPolling()
      setCode(null)
      setStatus("idle")
      setStatusMessage(null)
      return
    }
    void initiate()
    return () => stopPolling()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const initiate = async () => {
    try {
      const response = await start().unwrap()
      setCode(response)
      setStatus("waiting")
      setStatusMessage(null)
      schedulePoll(response.deviceCode, response.interval)
    } catch (err) {
      setStatus("error")
      setStatusMessage(formatError(err))
    }
  }

  const schedulePoll = (deviceCode: string, intervalSeconds: number) => {
    let currentInterval = Math.max(intervalSeconds, 1) * 1000
    const tick = async () => {
      try {
        const response = await poll({ deviceCode }).unwrap()
        switch (response.status) {
          case "PENDING":
            return // keep polling
          case "SLOW_DOWN":
            currentInterval = Math.min(currentInterval + 5000, 30_000)
            stopPolling()
            intervalRef.current = window.setInterval(tick, currentInterval)
            return
          case "DONE":
            stopPolling()
            setStatus("done")
            setStatusMessage(response.login ? `Connected as @${response.login}` : "Connected")
            toast.success(response.login ? `Connected as @${response.login}` : "GitHub connected")
            break
          case "EXPIRED":
            stopPolling()
            setStatus("expired")
            setStatusMessage("Code expired. Click 'Generate new code' to try again.")
            break
          case "DENIED":
            stopPolling()
            setStatus("denied")
            setStatusMessage("Authorization was denied.")
            break
        }
      } catch (err) {
        stopPolling()
        setStatus("error")
        setStatusMessage(formatError(err))
      }
    }
    intervalRef.current = window.setInterval(tick, currentInterval)
  }

  const handleCopy = async () => {
    if (!code) return
    try {
      await navigator.clipboard.writeText(code.userCode)
      toast.success("Code copied to clipboard")
    } catch {
      // clipboard.writeText can be blocked on http:// — fall through silently.
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Connect GitHub via device code</DialogTitle>
          <DialogDescription>
            Open the verification URL on any device, enter the code, and authorise the
            lighting7 GitHub App.
          </DialogDescription>
        </DialogHeader>

        {status === "idle" || starting ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground py-6">
            <Loader2 className="size-4 animate-spin" />
            Requesting code…
          </div>
        ) : null}

        {code && (status === "waiting" || status === "done") && (
          <div className="space-y-3 py-2">
            <div className="rounded-md border bg-muted/30 p-3 text-center">
              <div className="text-[10px] uppercase tracking-wide text-muted-foreground mb-1">
                Your code
              </div>
              <div className="font-mono text-2xl tracking-widest select-all">
                {code.userCode}
              </div>
            </div>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={handleCopy} className="flex-1">
                <Copy className="size-3.5 mr-1.5" />
                Copy code
              </Button>
              <a
                href={code.verificationUri}
                target="_blank"
                rel="noopener noreferrer"
                className="flex-1"
              >
                <Button variant="outline" size="sm" className="w-full">
                  <ExternalLink className="size-3.5 mr-1.5" />
                  Open GitHub
                </Button>
              </a>
            </div>
            {status === "waiting" && (
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Loader2 className="size-3 animate-spin" />
                Waiting for authorisation…
              </div>
            )}
            {status === "done" && statusMessage && (
              <div className="text-xs text-emerald-600">{statusMessage}</div>
            )}
          </div>
        )}

        {(status === "expired" || status === "denied" || status === "error") && (
          <div className="space-y-2 py-2">
            <div className="text-xs text-destructive">{statusMessage}</div>
            <Button variant="outline" size="sm" onClick={initiate}>
              Generate new code
            </Button>
          </div>
        )}

        <DialogFooter>
          <Button variant={status === "done" ? "default" : "outline"} onClick={() => onOpenChange(false)}>
            {status === "done" ? "Done" : "Cancel"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
