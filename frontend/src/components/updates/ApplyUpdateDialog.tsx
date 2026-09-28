import { useEffect, useRef, useState } from 'react'
import { Loader2, TriangleAlert } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatError } from '@/lib/formatError'
import { useApplyUpdateMutation, type UpdateStatus } from '@/store/updates'
import { useInstallQuery } from '@/store/installs'

export interface ApplyUpdateDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  status: UpdateStatus
}

/**
 * Confirmation for "install this update and restart the desk".
 *
 * A Dialog, not a Sheet: it confirms rather than edits. What it has to communicate is not "are
 * you sure" but *what stopping this machine costs right now* — which is why it reads the live
 * hint off the status rather than asking a generic question.
 */
export function ApplyUpdateDialog({ open, onOpenChange, status }: ApplyUpdateDialogProps) {
  const [applyUpdate, { isLoading, error, reset }] = useApplyUpdateMutation()
  const { data: install } = useInstallQuery()
  const [typed, setTyped] = useState('')

  const version = status.stagedVersion ?? status.latest?.version ?? ''
  const deskName = install?.friendlyName ?? ''
  const effectCount = status.live.activeEffectCount
  const activeStack = status.live.activeStackName

  /**
   * Type-to-confirm, but **only when the rig is actually live**. The asymmetry is deliberate:
   * making every routine update a typing chore is how you train people to type without reading,
   * which destroys the friction at exactly the moment it matters. When nothing is running, a
   * plain click is honest.
   */
  const isLive = effectCount > 0 || activeStack != null
  // `deskName` must be non-empty before a match counts. Without that check, an unresolved or
  // failed `useInstallQuery` leaves `deskName` as '' — and `typed` also starts as '' — so the
  // gate would be satisfied by typing nothing at all, which is precisely the case it exists to
  // prevent. A failing install query would make that bypass permanent rather than a race.
  const confirmed = !isLive || (deskName.length > 0 && typed.trim() === deskName)

  // Clear the typed confirmation and any stale error each time the dialog opens.
  //
  // `reset` is held in a ref rather than listed as a dependency on purpose. RTK Query memoises
  // it today, but if it ever handed back a fresh identity per render this effect would re-run on
  // every render — including every keystroke — and silently wipe the field the user is typing
  // into, leaving the confirm button permanently disabled. Opening is the only thing that should
  // trigger a reset, so `open` is the only real input.
  const resetRef = useRef(reset)
  resetRef.current = reset
  useEffect(() => {
    if (!open) return
    setTyped('')
    resetRef.current()
  }, [open])

  const onConfirm = async () => {
    try {
      await applyUpdate({ confirmVersion: version }).unwrap()
      onOpenChange(false)
    } catch {
      // Rendered inline below; applyUpdate is in SILENT_ENDPOINTS.
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Install lighting7 {version} and restart?</DialogTitle>
          <DialogDescription>
            This stops the lighting desk. DMX output stops, any running effects stop, and every
            connected phone or tablet disconnects. It usually takes one to three minutes, and
            lighting7 reopens by itself when it&apos;s done.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {isLive && (
            <Alert variant="destructive">
              <TriangleAlert className="h-4 w-4" />
              <AlertDescription>
                {effectCount > 0 && (
                  <span className="font-medium">
                    {effectCount} {effectCount === 1 ? 'effect is' : 'effects are'} running right now.
                  </span>
                )}
                {activeStack && (
                  <span className="block">
                    Cue stack <span className="font-medium">{activeStack}</span> is active.
                  </span>
                )}
              </AlertDescription>
            </Alert>
          )}

          <p className="text-sm text-muted-foreground">
            Windows will ask for administrator permission to run the installer. Your settings,
            show database and logs are not touched.
          </p>

          {isLive && deskName.length > 0 && (
            <div className="space-y-2">
              <Label htmlFor="confirm-desk-name">
                Type <span className="font-mono font-medium">{deskName}</span> to confirm
              </Label>
              <Input
                id="confirm-desk-name"
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                autoComplete="off"
                placeholder={deskName}
              />
            </div>
          )}

          {/* The gate needs the desk's name to compare against, so without it the button stays
              disabled. Say why, rather than leaving a dead button with no explanation. */}
          {isLive && deskName.length === 0 && (
            <Alert variant="destructive">
              <AlertDescription>
                Couldn&apos;t read this desk&apos;s name, which is needed to confirm an update
                while the rig is live. Reload the page and try again.
              </AlertDescription>
            </Alert>
          )}

          {error != null && (
            <Alert variant="destructive">
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}
        </div>

        <DialogFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={!confirmed || isLoading || version === ''}>
            {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            Install and restart
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
