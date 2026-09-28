import React from "react"
import { AlertTriangle, Loader2 } from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { LoginScreen } from "./components/auth/LoginScreen"
import { SetupScreen } from "./components/auth/SetupScreen"
import { useAuthStatusQuery } from "./store/auth"

// Full-screen authentication gate. Sits *outside* BootGate: `GET /auth/status` is
// exempt from the backend's warm-up check, so identity resolves while the show is
// still compiling FX. That ordering means a signed-out operator gets the login form
// immediately rather than watching a boot progress bar they can't act on, and after
// signing in they land on the boot overlay — the right sequence. An already-signed-in
// operator passes straight through on cached status, so a backend restart still shows
// them the boot overlay rather than a login form.
export function AuthGate({
  children,
  bypass = false,
}: {
  children: React.ReactNode
  bypass?: boolean
}) {
  const { data, isLoading, isError } = useAuthStatusQuery(undefined, {
    skip: bypass,
    // No polling: a dead session already arrives eagerly, as a 401 on any request
    // (see the base query in store/restApi.ts) or a 4401 socket close (store/auth.ts).
    // These two cover the laptop-sleep case, where neither has fired for a while.
    refetchOnFocus: true,
    refetchOnReconnect: true,
  })

  if (bypass) return <>{children}</>

  // Cold load only. A re-check triggered by a 401 or a 4401 keeps the stale `data`
  // (isFetching, not isLoading), so the whole app must not blank out for it.
  if (isLoading) {
    return (
      <div className="fixed inset-0 z-[100] flex items-center justify-center bg-background">
        <Loader2 className="size-6 animate-spin text-muted-foreground" />
      </div>
    )
  }

  // Nothing cached and the request failed — the backend is down or unreachable.
  // Distinct from "signed out", so nobody types a password into a dead form.
  if (isError && !data) {
    return (
      <div className="fixed inset-0 z-[100] flex items-center justify-center bg-background p-4">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <AlertTriangle className="size-5 text-destructive" />
              Can&apos;t reach the desk
            </CardTitle>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            The lighting controller isn&apos;t responding. Check that it&apos;s running, then
            reload this page.
          </CardContent>
        </Card>
      </div>
    )
  }

  if (!data) return null

  if (data.setupRequired) return <SetupScreen />
  if (!data.authenticated) return <LoginScreen />

  return <>{children}</>
}
