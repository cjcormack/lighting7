import { Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useReleaseTargets, type ReleaseTarget } from './useRelease'

/**
 * **Release n** over a selection (fixture-fx-sheets plan D18): the fixture sheet's Release, with
 * the eraser glyph and the count, over every selected target — one `clearTarget` each
 * ([useReleaseTargets]). The count is the targets; the word folds by the host's own rung
 * ([wordClass]) — `WORD_CLASS` on the programmer's row C, `VERB_WORD_CLASS` on the busk rig row —
 * and the count goes with it, kept on the title and the accessible name. Like the verbs beside it,
 * it is not gated on the socket: a send on a closed one is toasted by the send.
 */
export function ReleaseTargetsButton({
  targets,
  wordClass,
  className,
  disabledReason,
}: {
  targets: readonly ReleaseTarget[]
  wordClass: string
  className?: string
  /** The host refuses it here, and says why on the title — the programmer's focused-layer scope. */
  disabledReason?: string
}) {
  const release = useReleaseTargets(targets)
  const n = targets.length
  return (
    <Button
      variant="outline"
      size="sm"
      data-release-selection
      className={className}
      disabled={n === 0 || disabledReason != null}
      aria-label={`Release ${n}`}
      title={
        disabledReason != null
          ? disabledReason
          : n === 0
          ? 'Nothing selected to release'
          : `Release ${n} — every value and local effect the selection holds in the programmer; pads stay lit`
      }
      onClick={() => void release()}
    >
      <Eraser className="size-3.5" />
      <span className={wordClass}>Release</span>
      <span className={`${wordClass} font-mono text-muted-foreground`}>{n}</span>
    </Button>
  )
}
