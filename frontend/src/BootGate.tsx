import React, { useEffect, useState } from "react"
import { Loader2, AlertTriangle } from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Alert, AlertTitle, AlertDescription } from "@/components/ui/alert"
import { useBootStatusQuery } from "./store/bootStatus"

const POLL_MS = 400

// Full-screen loading gate. The backend serves this frontend before the lighting
// show is initialised, and show-dependent REST routes return 503 until it's
// ready. Gating the router here means no domain query fires until the show is up.
export function BootGate({
  children,
  bypass = false,
}: {
  children: React.ReactNode
  // Public pages (the phone-facing password reset) are served by this same SPA but
  // don't touch the show, so they render without waiting on — or polling for — its
  // readiness.
  bypass?: boolean
}) {
  // Poll the (readiness-exempt) status endpoint until ready, then stop. If the
  // show re-enters warm-up (e.g. a runtime project switch), a pushed
  // `bootProgressState` frame flips `ready` back to false and polling resumes.
  const [pollingInterval, setPollingInterval] = useState(POLL_MS)
  const { data } = useBootStatusQuery(undefined, { pollingInterval, skip: bypass })

  const ready = data?.ready ?? false
  const failed = data?.phase === "FAILED"

  // Stop polling once we reach a terminal state (ready, or a failed boot). A
  // WS `bootProgressState` frame or a reconnect re-triggers a refetch if the
  // backend re-enters warm-up, so we don't need to keep hammering `/status`.
  const settled = ready || failed
  useEffect(() => {
    setPollingInterval(settled ? 0 : POLL_MS)
  }, [settled])

  if (bypass || ready) return <>{children}</>

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            {failed ? (
              <AlertTriangle className="size-5 text-destructive" />
            ) : (
              <Loader2 className="size-5 animate-spin" />
            )}
            {failed ? "Startup failed" : "Starting up…"}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {failed ? (
            <Alert variant="destructive">
              <AlertTriangle className="size-4" />
              <AlertTitle>Show initialisation failed</AlertTitle>
              <AlertDescription>
                {data?.error ?? "The lighting show failed to start."}
              </AlertDescription>
            </Alert>
          ) : (
            <>
              <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full bg-primary transition-all duration-300 ease-out"
                  style={{ width: `${data?.percent ?? 0}%` }}
                />
              </div>
              <p className="text-sm text-muted-foreground">
                {data?.message ?? "Connecting to the lighting controller…"}
              </p>
              {data?.phase && (
                <p className="text-xs uppercase tracking-wide text-muted-foreground/70">
                  {data.phase.replace(/_/g, " ")}
                </p>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
