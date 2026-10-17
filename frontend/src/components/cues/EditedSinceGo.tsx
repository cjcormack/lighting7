import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useCueEdited } from '@/store/cueHolds'
import { useSharedCueActionDialogs } from '@/components/runner/cueActionDialogsContext'

const EDITED_TITLE =
  'Edited since GO — the stage still shows this cue as it went live. Its next GO lands the edit, or Update stage sends it now.'

/**
 * *Edited since GO* (show-cue-editing plan D4): drawn while the desk holds this cue live and marks it
 * edited (`cueHolds.state`'s `edited`), on the editor's header, the cue sheet's row and the phone
 * card. Nothing at all otherwise.
 */
export function EditedSinceGoChip({ projectId, cueId, className }: { projectId: number; cueId: number; className?: string }) {
  if (!useCueEdited(projectId, cueId)) return null
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center rounded-full border border-amber-500/55 bg-amber-500/10 px-1.5 py-px text-[10px] font-medium whitespace-nowrap text-amber-600 dark:border-amber-700/60 dark:bg-amber-950/40 dark:text-amber-300 normal-case tracking-normal',
        className,
      )}
      title={EDITED_TITLE}
      data-edited-since-go={cueId}
    >
      Edited since GO
    </span>
  )
}

/**
 * *Update stage…* beside the chip, drawn while the cue is edited since GO. It opens the one confirm
 * (`UpdateStageDialog`, through the action set's provider, `Layout`'s — drawn only under one); locked,
 * it asks to unlock first (§4.6), or is disabled with [readOnlyReason] where the lock is not the
 * operator's to lift.
 */
export function UpdateStageButton({
  projectId,
  cueId,
  name,
  locked,
  onRequestUnlock,
  readOnlyReason,
}: {
  projectId: number
  cueId: number
  /** The cue as the confirm names it — `Q4 Storm`. */
  name: string
  locked: boolean
  onRequestUnlock?: () => void
  readOnlyReason?: string | null
}) {
  const dialogs = useSharedCueActionDialogs()
  const edited = useCueEdited(projectId, cueId)
  if (!edited || dialogs == null) return null
  const reason = locked && onRequestUnlock == null ? readOnlyReason ?? 'Locked' : null
  const press = () => {
    if (locked) {
      if (onRequestUnlock != null) dialogs.openUnlock(onRequestUnlock)
      return
    }
    dialogs.openUpdateStage({ projectId, cueId, name })
  }
  return (
    <Button
      variant="outline"
      size="sm"
      className="h-7 shrink-0 gap-1 border-amber-500/55 text-xs text-amber-600 hover:text-amber-700 dark:border-amber-700/60 dark:text-amber-300 dark:hover:text-amber-200"
      disabled={reason != null}
      title={reason ?? 'Send this cue’s stored values and effects to the stage, over its own fade'}
      onClick={press}
      data-update-stage={cueId}
    >
      <RefreshCw className="size-3.5" />
      Update stage…
    </Button>
  )
}
