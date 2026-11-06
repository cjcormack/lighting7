import { useEffect, useRef, useSyncExternalStore } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { useNow } from '@/components/rigSafety/useNow'
import { lightingApi } from '@/api/lightingApi'
import type { PendingRemoteSession } from '@/api/cloudWsApi'
import { useAllowRemoteSessionMutation, useDenyRemoteSessionMutation } from '@/store/cloud'

const toastId = (sid: string) => `remote-session-pending:${sid}`

function subscribe(onChange: () => void) {
  const sub = lightingApi.cloud.subscribePending(onChange)
  return () => sub.unsubscribe()
}

/** The remote sessions waiting for an admin at the desk, as the last `remoteSession.pending` said. */
export function usePendingRemoteSessions(): PendingRemoteSession[] {
  return useSyncExternalStore(subscribe, lightingApi.cloud.pending)
}

/**
 * *Approve each new session on this desk* (lighting7 cloud-p1 plan D13): a toast on every window for
 * each remote session waiting for an admin, with *Allow* and *Deny*, gone once any window answers it
 * or its wait runs out. The desk sends the frame only to admin sockets on its own listener, so an
 * operator's window, or one opened through the cloud, never draws one. Mounted once by `Layout`;
 * renders nothing itself.
 */
export function RemoteSessionApprovals() {
  const pending = usePendingRemoteSessions()
  const shown = useRef(new Set<string>())

  useEffect(() => {
    const shownNow = shown.current
    const waiting = new Set(pending.map((p) => p.sid))
    for (const sid of [...shownNow]) {
      if (!waiting.has(sid)) {
        toast.dismiss(toastId(sid))
        shownNow.delete(sid)
      }
    }
    for (const session of pending) {
      toast.custom(() => <PendingSessionToast session={session} />, {
        id: toastId(session.sid),
        duration: Infinity,
        dismissible: false,
      })
      shownNow.add(session.sid)
    }
  }, [pending])

  useEffect(() => {
    const ids = shown.current
    return () => ids.forEach((sid) => toast.dismiss(toastId(sid)))
  }, [])

  return null
}

function PendingSessionToast({ session }: { session: PendingRemoteSession }) {
  const [allow, allowing] = useAllowRemoteSessionMutation()
  const [deny, denying] = useDenyRemoteSessionMutation()
  const expiresMs = Date.parse(session.expiresAt)
  const now = useNow(true, expiresMs, 1000)
  const seconds = Math.max(0, Math.ceil((expiresMs - now) / 1000))
  const busy = allowing.isLoading || denying.isLoading
  const who = session.displayName.trim() !== '' && session.displayName !== session.email
    ? `${session.displayName} (${session.email})`
    : session.email
  return (
    <div
      role="alertdialog"
      aria-label="Remote session waiting"
      className="w-[356px] space-y-2 rounded-md border bg-popover p-4 text-sm text-popover-foreground shadow-lg"
      data-testid="remote-session-pending"
    >
      <p className="font-medium">Remote session waiting</p>
      <p>
        {who} wants to open this desk through Lighting7 Cloud
        {session.linkedUser != null ? `, as ${session.linkedUser}.` : ', with no desk account linked.'}
      </p>
      <p className="text-xs text-muted-foreground">Denied in {seconds} s unless someone answers.</p>
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void deny(session.sid)}>
          Deny
        </Button>
        <Button size="sm" disabled={busy} onClick={() => void allow(session.sid)}>
          Allow
        </Button>
      </div>
    </div>
  )
}
