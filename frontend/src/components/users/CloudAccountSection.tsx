import { useEffect, useState } from "react"
import { AlertTriangle, Cloud, Loader2, Mail } from "lucide-react"
import { toast } from "sonner"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { formatError } from "@/lib/formatError"
import { useCloudStateQuery } from "@/store/cloud"
import {
  CLOUD_LINK_VIA_LABELS,
  useCancelCloudInviteMutation,
  useInviteCloudAccountMutation,
  useUnlinkCloudAccountMutation,
} from "@/store/cloudLinks"
import type { DeskUser } from "@/store/users"

/** What the Users table's *Cloud account* column says for a user. */
export function CloudAccountCell({ user }: { user: DeskUser }) {
  if (user.cloudLink) return <span className="text-sm">{user.cloudLink.email}</span>
  if (user.cloudInvite) {
    return (
      <span className="flex items-center gap-2 text-sm text-muted-foreground">
        {user.cloudInvite.email}
        <Badge variant="outline">Invited</Badge>
      </span>
    )
  }
  return <span className="text-sm text-muted-foreground">—</span>
}

/**
 * A user's Lighting7 Cloud account, in their sheet (lighting7 cloud-p1 plan D12): the account they
 * are linked to with *Unlink*, the invite waiting for one with *Cancel invite*, or *Invite a cloud
 * account…*. An invite is held by the cloud until an owner or admin of the desk's organisation
 * approves it in the portal; only then is it mailed.
 */
export function CloudAccountSection({ user }: { user: DeskUser }) {
  const { data: cloud } = useCloudStateQuery()
  const [inviting, setInviting] = useState(false)
  const [email, setEmail] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [confirmUnlink, setConfirmUnlink] = useState(false)
  const [invite, { isLoading: isInviting }] = useInviteCloudAccountMutation()
  const [cancelInvite, { isLoading: isCancelling }] = useCancelCloudInviteMutation()
  const [unlink, { isLoading: isUnlinking }] = useUnlinkCloudAccountMutation()

  useEffect(() => {
    setInviting(false)
    setEmail("")
    setError(null)
  }, [user.id])

  const adopted = cloud?.adopted != null
  const online = cloud?.connection === "ONLINE"
  const link = user.cloudLink
  const pending = user.cloudInvite
  if (!adopted && !link) return null

  const handleInvite = async (event: React.FormEvent) => {
    event.preventDefault()
    const address = email.trim()
    if (address === "") return
    setError(null)
    try {
      await invite({ userId: user.id, email: address }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    setInviting(false)
    setEmail("")
    toast.success(`Invite for ${address} sent to the cloud`, {
      description: "An owner or admin of the organisation approves it before it is mailed.",
    })
  }

  const handleCancel = async () => {
    setError(null)
    try {
      await cancelInvite({ userId: user.id }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    toast.success("Invite cancelled")
  }

  const handleUnlink = async () => {
    setError(null)
    try {
      await unlink({ userId: user.id }).unwrap()
    } catch (err) {
      setConfirmUnlink(false)
      setError(formatError(err))
      return
    }
    setConfirmUnlink(false)
    toast.success(`${user.displayName} is no longer linked to ${link?.email ?? "a cloud account"}`)
  }

  return (
    <div className="space-y-2 border-t pt-4">
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <Cloud className="size-4" />
        Cloud account
      </h3>
      {error && (
        <Alert variant="destructive">
          <AlertTriangle className="size-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {link ? (
        <>
          <div className="text-sm">
            <span>{link.email}</span>
            <span className="text-muted-foreground"> · {CLOUD_LINK_VIA_LABELS[link.linkedVia]}</span>
          </div>
          <p className="text-xs text-muted-foreground">
            They can open this desk through Lighting7 Cloud as {user.displayName}.
          </p>
          <Button
            variant="outline"
            className="w-full"
            disabled={isUnlinking}
            onClick={() => setConfirmUnlink(true)}
          >
            Unlink
          </Button>
        </>
      ) : pending ? (
        <>
          <div className="flex items-center gap-2 text-sm">
            <Mail className="size-4 text-muted-foreground" />
            <span>{pending.email}</span>
            <Badge variant="outline">Invited</Badge>
          </div>
          <p className="text-xs text-muted-foreground">
            Waiting for an owner or admin of {cloud?.adopted?.orgName ?? "the organisation"} to approve
            it in the portal, then for {pending.email} to accept. Expires{" "}
            {new Date(pending.expiresAt).toLocaleDateString()}.
          </p>
          <Button variant="outline" className="w-full" disabled={isCancelling} onClick={() => void handleCancel()}>
            {isCancelling && <Loader2 className="size-4 animate-spin" />}
            Cancel invite
          </Button>
        </>
      ) : inviting ? (
        <form className="space-y-2" onSubmit={(event) => void handleInvite(event)}>
          <Label htmlFor="cloud-invite-email">Their Lighting7 Cloud email</Label>
          <Input
            id="cloud-invite-email"
            type="email"
            autoFocus
            autoComplete="off"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            An owner or admin of {cloud?.adopted?.orgName ?? "the organisation"} approves the invite in
            the portal before it is mailed.
          </p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setInviting(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={email.trim() === "" || isInviting || !online}>
              {isInviting && <Loader2 className="size-4 animate-spin" />}
              Send invite
            </Button>
          </div>
        </form>
      ) : (
        <>
          <p className="text-xs text-muted-foreground">
            Not linked. Linked, they can open this desk from anywhere through Lighting7 Cloud, as this
            user and with this user&apos;s role.
          </p>
          <Button variant="outline" className="w-full" disabled={!online} onClick={() => setInviting(true)}>
            Invite a cloud account…
          </Button>
          {!online && (
            <p className="text-xs text-muted-foreground">This desk isn&apos;t online to the cloud right now.</p>
          )}
        </>
      )}

      <AlertDialog open={confirmUnlink} onOpenChange={(next) => !next && setConfirmUnlink(false)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Unlink {link?.email}?</AlertDialogTitle>
            <AlertDialogDescription>
              Their remote sessions end now, and they can&apos;t open this desk through Lighting7 Cloud
              until they are linked again.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void handleUnlink()}>Unlink</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
