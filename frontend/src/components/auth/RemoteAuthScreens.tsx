import { useEffect } from "react"
import { reportUnlinked } from "@/lib/remoteMode"
import { AuthScreenLayout } from "./AuthScreenLayout"

/**
 * Remote mode's stand-ins for the sign-in screens (cloud-p1 plan D9). A remote session is the
 * cloud's to start and end, so there is no password form here: an account not linked to a user on
 * this desk is told so, and the shell is told with `auth.unlinked`.
 */
export function RemoteUnlinkedScreen({ email }: { email?: string | null }) {
  useEffect(() => {
    reportUnlinked()
  }, [])
  return (
    <AuthScreenLayout title="Not linked to this desk">
      <p className="text-sm text-muted-foreground">
        {email ? <>Your Lighting7 Cloud account, {email}, is</> : <>Your Lighting7 Cloud account is</>} not
        linked to a user on this desk. An admin can invite it from Users on the desk.
      </p>
    </AuthScreenLayout>
  )
}

/** A remote session the desk no longer accepts: ended, revoked, or its token lapsed. */
export function RemoteSessionEndedScreen() {
  return (
    <AuthScreenLayout title="Remote session ended">
      <p className="text-sm text-muted-foreground">
        This remote session has ended. Open the desk again from Lighting7 Cloud.
      </p>
    </AuthScreenLayout>
  )
}
