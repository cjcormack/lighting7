import { useState } from 'react'
import { Timer, TriangleAlert } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { formatError } from '@/lib/formatError'
import { clockLabel, coolingLeft, coolsAt, lampsCoolingLeft } from '@/lib/rigSafety'
import { useQuitDeskMutation, useRigSafety } from '@/store/rigSafety'
import { markDeskQuit } from './deskQuit'
import { useNow } from './useNow'

/**
 * *Quit desk* (venue-visit plan S6, D9, D13): an admin's, on the desk's own listener only — the hosts
 * offer it only where the frame's `quitAllowed` says so, and the desk refuses it otherwise. It asks
 * first, and says plainly when the rig is not marked safe to switch off; the operator may quit anyway
 * (`force`). A rig made safe whose lamps are still cooling may be quit without it — quitting cuts no
 * fixture's power — and the dialog says how long to leave the mains on. Quitting never lamps off or
 * blacks out: that is *Make rig safe*'s.
 */
export function QuitDeskDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const rig = useRigSafety()
  const [quitDesk, { isLoading }] = useQuitDeskMutation()
  const [error, setError] = useState<string | null>(null)
  const cooling = !rig.ready && rig.coolingRemainingMs != null
  const safe = rig.ready || cooling
  const lampsCooling = cooling || rig.lampsOff.some((l) => (l.coolRemainingMs ?? 0) > 0)
  const now = useNow(open && lampsCooling)
  const coolLeft = Math.max(coolingLeft(rig, now), lampsCoolingLeft(rig, now))

  const quit = () => {
    setError(null)
    quitDesk({ force: !safe })
      .unwrap()
      .then(() => {
        onOpenChange(false)
        const at = Date.now()
        const until = coolsAt(rig, at)
        markDeskQuit(until > at ? until : null)
      })
      .catch((e: unknown) => setError(formatError(e)))
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setError(null)
        onOpenChange(next)
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Quit the desk?</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-3">
              <p>
                The lighting desk stops on this computer, and every window and control surface loses it. The rig
                keeps whatever it was last sent.
              </p>
              {!safe && (
                <p
                  data-testid="quit-not-safe"
                  className="flex gap-2 rounded-md border border-amber-600/40 bg-amber-500/10 p-2 text-amber-800 dark:text-amber-200"
                >
                  <TriangleAlert className="mt-0.5 size-4 shrink-0" />
                  <span>
                    The rig is not marked safe to switch off.
                    {rig.blackout
                      ? ' It is blacked out, but not every lamp is known to be off, or output has changed since.'
                      : ' Make rig safe lamps it off and blacks it out first.'}
                  </span>
                </p>
              )}
              {lampsCooling && coolLeft > 0 && (
                <p data-testid="quit-cooling" className="flex gap-2 rounded-md border p-2">
                  <Timer className="mt-0.5 size-4 shrink-0" />
                  <span>
                    The lamps are still cooling: leave the rig&apos;s power on for another{' '}
                    <span className="tabular-nums">{clockLabel(coolLeft)}</span>.
                  </span>
                </p>
              )}
              {error && <p className="text-destructive">{error}</p>}
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isLoading}>Cancel</AlertDialogCancel>
          <Button variant="destructive" disabled={isLoading} onClick={quit}>
            {safe ? 'Quit desk' : 'Quit anyway'}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
