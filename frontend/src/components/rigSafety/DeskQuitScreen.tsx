import { Power } from 'lucide-react'
import { useDeskQuit } from './deskQuit'

/**
 * What the window that quit the desk shows from then on (venue-visit plan S6): mounted beside the
 * gates in `App`, so the reconnect screens they draw once the socket closes never cover it.
 */
export function DeskQuitScreen() {
  const quit = useDeskQuit()
  if (!quit) return null
  return (
    <div role="alert" className="fixed inset-0 z-[200] flex flex-col items-center justify-center gap-3 bg-background p-6 text-center">
      <Power className="size-10 text-muted-foreground" />
      <h1 className="text-xl font-semibold">The desk has quit</h1>
      <p className="max-w-sm text-sm text-muted-foreground">
        The lighting desk has stopped on this computer. You can close this window; start the desk again to carry on.
      </p>
    </div>
  )
}
