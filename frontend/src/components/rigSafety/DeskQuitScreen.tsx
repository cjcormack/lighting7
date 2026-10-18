import { Power } from 'lucide-react'
import { clockLabel } from '@/lib/rigSafety'
import { useDeskQuit } from './deskQuit'
import { useNow } from './useNow'

/**
 * What the window that quit the desk shows from then on (venue-visit plan S6): mounted beside the
 * gates in `App`, so the reconnect screens they draw once the socket closes never cover it. A desk
 * quit while its lamps were cooling counts the cool-down out here, since no window hears the desk now.
 */
export function DeskQuitScreen() {
  const quit = useDeskQuit()
  const coolsAt = quit?.coolsAt ?? null
  const now = useNow(coolsAt != null, coolsAt ?? undefined)
  if (quit == null) return null
  const left = coolsAt == null ? 0 : Math.max(0, coolsAt - now)
  return (
    <div role="alert" className="fixed inset-0 z-[200] flex flex-col items-center justify-center gap-3 bg-background p-6 text-center">
      <Power className="size-10 text-muted-foreground" />
      <h1 className="text-xl font-semibold">The desk has quit</h1>
      <p className="max-w-sm text-sm text-muted-foreground">
        The lighting desk has stopped on this computer. You can close this window; start the desk again to carry on.
      </p>
      {coolsAt != null && (
        <p data-testid="quit-screen-cooling" className="max-w-sm text-sm font-medium">
          {left > 0 ? (
            <>
              The lamps are still cooling: leave the rig&apos;s power on for another{' '}
              <span className="tabular-nums">{clockLabel(left)}</span>.
            </>
          ) : (
            'The lamps have cooled: the rig can be switched off.'
          )}
        </p>
      )}
    </div>
  )
}
