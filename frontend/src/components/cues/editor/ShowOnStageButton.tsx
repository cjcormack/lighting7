import { MonitorPlay } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  sameEditTarget,
  setVisSource,
  showCueOnStage,
  useEditTarget,
  useVisSource,
  type EditTarget,
} from '@/hooks/useVisSource'

/** Why the toggle is withheld for a cue the editor reads as another project's. */
export const SHOW_ON_STAGE_OFF_RIG = 'This cue is another project’s — the Stage view draws only the show on the rig'

/**
 * *Show on stage* (show-cue-editing plan D13, §3.7): points **this window's** Stage view and
 * Positions plan at the cue in this editor — its entry, or the cue itself where it was opened from
 * none — through the `edit` vis source, which draws the stored cue and sends nothing. Lit while the
 * window shows this cue; another editor's press moves the window to that one, and a press while lit
 * puts it back on Output. Not withheld by the show's lock: it writes nothing.
 */
export function ShowOnStageButton({
  cueId,
  entryId,
  withheldReason,
}: {
  cueId: number
  /** The place it was opened from; null previews the cue as a loose press. */
  entryId: number | null
  withheldReason?: string | null
}) {
  const source = useVisSource()
  const current = useEditTarget()
  const target: EditTarget = entryId != null ? { kind: 'entry', entryId } : { kind: 'cue', cueId }
  const lit = source === 'edit' && sameEditTarget(current, target)
  const press = () => (lit ? setVisSource('output') : showCueOnStage(target))
  return (
    <Button
      variant="outline"
      size="sm"
      className={cn('h-7 shrink-0 gap-1 text-xs', lit && 'border-primary bg-primary/10 text-primary hover:text-primary')}
      aria-pressed={lit}
      disabled={withheldReason != null}
      title={
        withheldReason ??
        (lit
          ? 'This window draws this cue as stored — press to go back to Output'
          : 'Draw this cue as stored on this window’s Stage view and Positions plan — nothing is sent')
      }
      onClick={press}
      data-show-on-stage={cueId}
    >
      <MonitorPlay className="size-3.5" />
      Show on stage
    </Button>
  )
}
