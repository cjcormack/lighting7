import React, { Suspense } from "react"
import { FullScreenSpinner } from "@/components/FullScreenSpinner"
import { useAuthStatusQuery } from "@/store/auth"
import { useInstallQuery } from "@/store/installs"

// Lazy: only a new desk's first admin ever sees it, so the cloud-sync and telemetry pieces it
// mounts stay out of the shell every other boot parses.
const OnboardingScreen = React.lazy(() => import("./OnboardingScreen"))

/**
 * Between `BootGate` and the router: while the install owes its first admin onboarding
 * (`GET /install`'s `onboardingPending`), an admin gets the onboarding screen instead of the app.
 * An operator never does — telemetry and cloud sync are an admin's — and a failed read lets the
 * desk through rather than holding it behind a question it cannot ask.
 */
export function OnboardingGate({
  children,
  bypass = false,
}: {
  children: React.ReactNode
  bypass?: boolean
}) {
  const { data: auth } = useAuthStatusQuery(undefined, { skip: bypass })
  const isAdmin = auth?.user?.role === "ADMIN"
  const { data: install, isLoading } = useInstallQuery(undefined, { skip: bypass || !isAdmin })

  if (bypass || !isAdmin) return <>{children}</>
  if (isLoading) return <FullScreenSpinner />
  if (install?.onboardingPending === true) {
    return (
      <Suspense fallback={<FullScreenSpinner />}>
        <OnboardingScreen />
      </Suspense>
    )
  }
  return <>{children}</>
}
