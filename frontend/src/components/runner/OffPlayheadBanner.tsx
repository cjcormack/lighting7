import { useState } from 'react'
import { Radio } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

/**
 * Shown while the operator is reading a stack that is **not** the playhead.
 *
 * This exists because browsing and arming were split (desk-simplification session 2b). A tab click
 * used to move the server playhead, so "the stack I am looking at" and "the stack GO will fire"
 * could never disagree — at the cost of making a single unconfirmed click take the live cue off
 * stage. Now they can disagree, and when they do the operator has to be told, or the transport
 * appears to act on the wrong stack.
 *
 * So the banner does two jobs, and they are deliberately different weights:
 *
 *  - **Jump to live** is navigation — free, reversible, and the one most people want.
 *  - **Make this stack live** is the old tab-click behaviour, now labelled and confirm-gated. It is
 *    still available while the show is locked, because moving the playhead is *transport*, not
 *    editing; the lock guards the show's contents, not where it is playing from.
 *
 * The confirmation is not ceremony. `POST /show/go-to` deactivates the stack being left, then calls
 * `activateAtFirstCue` on the target — so the target's first cue genuinely fires, and the desk
 * darkens it again immediately afterwards to arrive armed rather than playing. Mid-show that is a
 * visible blip on stage on top of losing the current cue, which is worth one press to acknowledge.
 * Asked only when there is something to lose: with the show stopped, or the current stack already
 * dark, it goes straight through.
 */
export function OffPlayheadBanner({
  liveStackName,
  selectedStackName,
  liveCueIsOnStage,
  onJumpToLive,
  onMakeLive,
}: {
  /** The playhead stack's name, or null when the show is not running. */
  liveStackName: string | null
  selectedStackName: string
  /** Whether the stack being left currently has a cue on stage — the thing a jump costs. */
  liveCueIsOnStage: boolean
  onJumpToLive: () => void
  onMakeLive: () => void
}) {
  const [confirming, setConfirming] = useState(false)

  return (
    <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1.5 border-b border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs">
      <span className="flex items-center gap-1.5 font-medium text-amber-700 dark:text-amber-300">
        <Radio className="size-3.5" />
        {liveStackName
          ? `You are reading ${selectedStackName}. GO fires ${liveStackName}.`
          : `You are reading ${selectedStackName}. The show is not running.`}
      </span>
      <div className="ml-auto flex items-center gap-2">
        {liveStackName && (
          <Button size="sm" variant="outline" className="h-6 px-2 text-xs" onClick={onJumpToLive}>
            Jump to live
          </Button>
        )}
        <Button
          size="sm"
          variant="outline"
          className="h-6 px-2 text-xs"
          onClick={() => (liveCueIsOnStage ? setConfirming(true) : onMakeLive())}
        >
          Make this stack live
        </Button>
      </div>

      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Move the playhead to {selectedStackName}?</DialogTitle>
            <DialogDescription>
              {liveStackName} is on stage. Moving the playhead takes its current cue down, briefly
              outputs the first cue of {selectedStackName}, then leaves it armed and dark ready for
              GO.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="flex-row justify-end gap-2">
            <Button variant="outline" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setConfirming(false)
                onMakeLive()
              }}
            >
              Move the playhead
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
