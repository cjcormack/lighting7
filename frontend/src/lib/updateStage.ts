import type { UpdateStageResponse } from '@/api/cuesApi'
import { formatMs } from '@/lib/formatMs'

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/**
 * *Update stage*'s confirm, as lines (show-cue-editing plan D11): what the dry run says the press
 * would move — the values whose level changes, then the effects edited in place, stopped and
 * started — and over what, the cue's own fade the dry run answers. A count of 0 is left out; a dry run that would move nothing says so, and the
 * press still clears the mark.
 */
export function updateStageLines(counts: UpdateStageResponse): string[] {
  const { rows, fx, fadeMs } = counts
  const fxParts = [
    fx.edited > 0 ? `${fx.edited} FX edited in place` : null,
    fx.stopped > 0 ? `${fx.stopped} stopped` : null,
    fx.started > 0 ? `${fx.started} started` : null,
  ].filter((p): p is string => p != null)
  if (rows === 0 && fxParts.length === 0) return ['The stage already shows the cue as stored.']
  const fade = fadeMs > 0 ? `over its ${formatMs(fadeMs)} fade` : 'at once — it is a snap cue'
  return [
    rows > 0 ? `${plural(rows, 'value')} change, ${fade}` : null,
    fxParts.length > 0 ? fxParts.join(' · ') : null,
  ].filter((l): l is string => l != null)
}
