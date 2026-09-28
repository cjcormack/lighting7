import { useEffect, useState } from "react"
import { AlertTriangle, Loader2, Smartphone } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { formatError } from "@/lib/formatError"
import {
  useChangePasswordMutation,
  useConnectedAppsQuery,
  useRevokeConnectedAppMutation,
  useRevokeOtherSessionsMutation,
  useSessionsQuery,
  useUpdateProfileMutation,
  type AuthUser,
  type ConnectedApp,
  type SessionInfo,
} from "@/store/auth"
import { MIN_PASSWORD_LENGTH } from "@/lib/passwordPolicy"
import { MAX_DISPLAY_NAME_LENGTH } from "@/lib/userPolicy"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { RoleBadge } from "@/components/users/RoleBadge"
import { DeviceLoginSection } from "./DeviceLoginSection"

// The raw User-Agent is unreadable in a list. Pull out the browser and platform,
// which is all anyone needs to recognise "that's my laptop" vs "that's the desk".
function describeUserAgent(userAgent: string | null): string {
  if (!userAgent) return "Unknown device"
  const browser =
    /Firefox\/[\d.]+/.test(userAgent) ? "Firefox"
    : /Edg\//.test(userAgent) ? "Edge"
    : /OPR\//.test(userAgent) ? "Opera"
    : /Chrome\//.test(userAgent) ? "Chrome"
    : /Safari\//.test(userAgent) ? "Safari"
    : null
  const platform =
    /iPhone|iPad/.test(userAgent) ? "iOS"
    : /Android/.test(userAgent) ? "Android"
    : /Mac OS X/.test(userAgent) ? "macOS"
    : /Windows/.test(userAgent) ? "Windows"
    : /Linux/.test(userAgent) ? "Linux"
    : null
  if (browser && platform) return `${browser} on ${platform}`
  return browser ?? platform ?? "Unknown device"
}

function SessionRow({ session }: { session: SessionInfo }) {
  return (
    <li className="flex items-center justify-between gap-2 rounded-md border px-3 py-2">
      <div className="min-w-0">
        <p className="truncate text-sm">{describeUserAgent(session.userAgent)}</p>
        <p className="text-xs text-muted-foreground">
          Last active {new Date(session.lastSeenAt).toLocaleString()}
          {/* Named because a QR sign-in is the one entry here nobody typed a password for:
              if a device you don't recognise came in that way, this row is where you find
              out. */}
          {session.createdVia === "QR" && " · signed in by QR from the desk"}
        </p>
      </div>
      {session.current && <Badge variant="secondary">This device</Badge>}
    </li>
  )
}

function ConnectedAppRow({ app }: { app: ConnectedApp }) {
  const [revoke, { isLoading }] = useRevokeConnectedAppMutation()
  const handleRevoke = async () => {
    try {
      await revoke({ id: app.id }).unwrap()
    } catch {
      // Reported by errorToastMiddleware.
      return
    }
    toast.success(`${app.clientName} disconnected`)
  }
  return (
    <li className="flex items-center justify-between gap-2 rounded-md border px-3 py-2">
      <div className="min-w-0">
        <p className="truncate text-sm">{app.clientName}</p>
        <p className="text-xs text-muted-foreground">
          Connected {new Date(app.createdAt).toLocaleDateString()} · last used{" "}
          {new Date(app.lastUsedAt).toLocaleString()}
        </p>
      </div>
      <Button
        variant="outline"
        size="sm"
        disabled={isLoading}
        onClick={() => void handleRevoke()}
        aria-label={`Revoke ${app.clientName}`}
      >
        {isLoading && <Loader2 className="size-4 animate-spin" />}
        Revoke
      </Button>
    </li>
  )
}

type ProfileTab = "profile" | "password" | "devices" | "signin"

/**
 * Everything a signed-in user can do to their own account, in one sheet: rename themselves,
 * change their password, see where else they are signed in, sign those devices out, and sign a
 * phone in by QR. It is the only self-service surface — the user menu holds nothing else but
 * Log out.
 *
 * Four tabs — Profile / Password / Devices / Sign-in — each owning its own action.
 *
 * **Sign-in has no button: arriving on the tab mints the code, and leaving cancels it.** That
 * keeps the property the QR is built around ("a live code must not outlive the screen showing
 * it") while dropping a redundant press — navigating to a tab named for the thing is exactly as
 * deliberate as pressing a button labelled the same, and the tab bar above the code is a more
 * obvious way out than a Hide button inside it. Opening Profile lands on the Profile tab, so
 * nothing is minted by opening the sheet; that half is what must not regress.
 *
 * The display name and the password are **separate saves**, and must stay that way. The
 * password submit needs `currentPassword`; a rename must not. One shared button would either
 * demand a password to change a name, or fire two requests whose partial failure ("name saved,
 * password rejected") a single alert cannot honestly report. They also differ in consequence:
 * the password change revokes every other session, a rename revokes nothing.
 */
export function ProfileSheet({
  user,
  open,
  onOpenChange,
}: {
  user: AuthUser
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [displayName, setDisplayName] = useState(user.displayName)
  const [currentPassword, setCurrentPassword] = useState("")
  const [newPassword, setNewPassword] = useState("")
  const [confirm, setConfirm] = useState("")
  // One error per tab rather than one for the sheet: a shared alert would follow you to
  // another tab and blame the wrong form for a failure you can no longer see the cause of.
  const [nameError, setNameError] = useState<string | null>(null)
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [tab, setTab] = useState<ProfileTab>("profile")

  const [changePassword, { isLoading }] = useChangePasswordMutation()
  const [updateProfile, { isLoading: isSaving }] = useUpdateProfileMutation()
  const [revokeOthers, { isLoading: isRevoking }] = useRevokeOtherSessionsMutation()
  const {
    data: sessions,
    isLoading: isLoadingSessions,
    isError: sessionsFailed,
  } = useSessionsQuery(undefined, { skip: !open })
  const { data: connectedApps, isError: appsFailed } = useConnectedAppsQuery(undefined, {
    skip: !open,
  })

  // Re-seeded per opening, keyed on the identity rather than on `user` itself: an `Auth`
  // refetch — which happens on every 401 and on a socket rejection — would otherwise clobber
  // a half-typed name mid-edit.
  useEffect(() => {
    setDisplayName(user.displayName)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user.uuid, open])

  // Deliberately `undefined` until the list actually arrives: a count of 0 read off
  // a missing list would render "No other devices signed in" — a claim we can't make
  // while loading or after a failure — and disable the one control that acts on it.
  const otherSessionCount = sessions?.filter((s) => !s.current).length
  const mismatch = confirm !== "" && newPassword !== confirm
  const canSubmit =
    currentPassword !== "" &&
    newPassword.length >= MIN_PASSWORD_LENGTH &&
    newPassword === confirm &&
    !isLoading

  const trimmedName = displayName.trim()
  const nameTooLong = trimmedName.length > MAX_DISPLAY_NAME_LENGTH
  const canSave = trimmedName !== user.displayName && trimmedName !== "" && !nameTooLong && !isSaving

  // Keyed on the sheet being *closed* rather than hung off a close handler, because there is no
  // single handler to hang it off: Esc, the overlay, the X and the footer's Close all call
  // `onOpenChange` straight through, and anything that later closes the sheet from code would
  // bypass a wrapper too. For `tab` that distinction is the difference between a bug and not —
  // this sheet stays mounted for the whole session, so a `"signin"` left behind here would mint
  // a live sign-in code the next time somebody opened Profile to change a password, with nobody
  // having asked for one.
  useEffect(() => {
    if (open) return
    setCurrentPassword("")
    setNewPassword("")
    setConfirm("")
    setNameError(null)
    setPasswordError(null)
    // Back to the first tab, which is also what guarantees reopening the sheet mints nothing:
    // the Sign-in tab has to be *navigated to*, and that is the whole gesture now.
    setTab("profile")
  }, [open])

  const handleSaveName = async () => {
    if (!canSave) return
    setNameError(null)
    try {
      await updateProfile({ displayName: trimmedName }).unwrap()
    } catch (err) {
      setNameError(formatError(err))
      return
    }
    // Stays open, like the password save beside it. Now that each tab owns its own button, one
    // of them dismissing the whole sheet and the other not would be arbitrary — and the button
    // disabling itself as `canSave` goes false is already the confirmation that it worked.
    toast.success("Name updated")
  }

  const handleChangePassword = async () => {
    if (!canSubmit) return
    setPasswordError(null)
    try {
      await changePassword({ currentPassword, newPassword }).unwrap()
    } catch (err) {
      setPasswordError(formatError(err))
      return
    }
    // The backend revokes every *other* session on a password change, keeping this
    // one — so the operator who just changed it isn't logged out of the desk.
    toast.success("Password changed", {
      description: "You've been signed out on any other device.",
    })
    // Clears the three fields but leaves the sheet open: the password is one section of this
    // sheet now, not its purpose, so changing it is no longer a reason to dismiss the rest.
    setCurrentPassword("")
    setNewPassword("")
    setConfirm("")
  }

  const handleRevokeOthers = async () => {
    try {
      await revokeOthers().unwrap()
    } catch {
      // Reported by errorToastMiddleware.
      return
    }
    toast.success("Signed out everywhere else")
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Profile</SheetTitle>
        </SheetHeader>
        <SheetBody>
          {/* Three concerns, three tabs, and each tab owns its own action button. A footer
              Save would have to mean "save the display name" while you were looking at the
              devices list, which is why the actions moved inline when the sections became
              tabs. Controlled rather than `defaultValue` so closing the sheet can reset it to
              Profile — see the effect above for why that reset is a security property, not
              tidiness. */}
          <Tabs value={tab} onValueChange={(next) => setTab(next as ProfileTab)}>
            <TabsList className="w-full">
              <TabsTrigger value="profile">Profile</TabsTrigger>
              <TabsTrigger value="password">Password</TabsTrigger>
              <TabsTrigger value="devices">Devices</TabsTrigger>
              <TabsTrigger value="signin">
                <Smartphone className="size-4" />
                Sign-in
              </TabsTrigger>
            </TabsList>

            <TabsContent value="profile" className="space-y-4">
              {nameError && (
                <Alert variant="destructive">
                  <AlertTriangle className="size-4" />
                  <AlertDescription>{nameError}</AlertDescription>
                </Alert>
              )}

              <div className="flex items-center gap-2">
                <span className="min-w-0 truncate font-mono text-sm">{user.username}</span>
                <RoleBadge role={user.role} />
              </div>

              <div className="space-y-2">
                <Label htmlFor="profile-display-name">Display name</Label>
                <Input
                  id="profile-display-name"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                />
                {nameTooLong && (
                  <p className="text-sm text-destructive">
                    {MAX_DISPLAY_NAME_LENGTH} characters or fewer.
                  </p>
                )}
              </div>

              <Button className="w-full" disabled={!canSave} onClick={() => void handleSaveName()}>
                {isSaving && <Loader2 className="size-4 animate-spin" />}
                Save name
              </Button>
            </TabsContent>

            <TabsContent value="password" className="space-y-4">
              {passwordError && (
                <Alert variant="destructive">
                  <AlertTriangle className="size-4" />
                  <AlertDescription>{passwordError}</AlertDescription>
                </Alert>
              )}
            <div className="space-y-2">
              <Label htmlFor="current-password">Current password</Label>
              <Input
                id="current-password"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="new-password">New password</Label>
              <Input
                id="new-password"
                type="password"
                autoComplete="new-password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                At least {MIN_PASSWORD_LENGTH} characters.
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="confirm-password">Confirm new password</Label>
              <Input
                id="confirm-password"
                type="password"
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
              />
              {mismatch && (
                <p className="text-sm text-destructive">Passwords don&apos;t match.</p>
              )}
            </div>
              <Button
                className="w-full"
                disabled={!canSubmit}
                onClick={() => void handleChangePassword()}
              >
                {isLoading && <Loader2 className="size-4 animate-spin" />}
                Change password
              </Button>
            </TabsContent>

            <TabsContent value="devices" className="space-y-4">
              <div className="space-y-2">
                <h3 className="text-sm font-medium">Signed in on</h3>
                {isLoadingSessions && <p className="text-sm text-muted-foreground">Loading…</p>}
                {sessionsFailed && (
                  <p className="text-sm text-destructive">
                    Couldn&apos;t load your signed-in devices.
                  </p>
                )}
                {sessions && (
                  <ul className="space-y-2">
                    {sessions.map((session) => (
                      <SessionRow key={session.id} session={session} />
                    ))}
                  </ul>
                )}
                <Button
                  variant="outline"
                  className="w-full"
                  // Enabled whenever the count is unknown: refusing to act on a list we
                  // failed to load would strand an admin who came here precisely to kick
                  // another device off. The backend is the real authority either way.
                  disabled={otherSessionCount === 0 || isRevoking}
                  onClick={handleRevokeOthers}
                >
                  {isRevoking && <Loader2 className="size-4 animate-spin" />}
                  {otherSessionCount === 0
                    ? "No other devices signed in"
                    : otherSessionCount === undefined
                      ? "Sign out everywhere else"
                      : `Sign out everywhere else (${otherSessionCount})`}
                </Button>
              </div>

              {/* Apps signed in through the desk's MCP listener (Claude on a phone, say). They
                  sit beside the devices because they are the same question — what can act as
                  me — and "Sign out everywhere else" ends them too. Drawn only once there is
                  something to say, so a desk that never connected an app shows nothing new. */}
              {(appsFailed || (connectedApps && connectedApps.length > 0)) && (
                <div className="space-y-2">
                  <h3 className="text-sm font-medium">Connected apps</h3>
                  {appsFailed && (
                    <p className="text-sm text-destructive">
                      Couldn&apos;t load your connected apps.
                    </p>
                  )}
                  {connectedApps && (
                    <ul className="space-y-2">
                      {connectedApps.map((app) => (
                        <ConnectedAppRow key={app.id} app={app} />
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </TabsContent>

            <TabsContent value="signin" className="space-y-4">
              {/* Mounting the section is what mints, and Radix mounts this only while the tab is
                  active — so arriving here mints and leaving cancels, with no button in between.
                  See the doc comments on this component and on DeviceLoginSection. */}
              <DeviceLoginSection active={open} onDone={() => setTab("devices")} />
            </TabsContent>
          </Tabs>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          {/* Just Close. Every action lives on the tab that owns it, so there is nothing here
              that could be mistaken for "save all three tabs". */}
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
