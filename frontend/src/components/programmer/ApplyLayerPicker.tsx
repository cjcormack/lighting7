import { ApplyPicker, type ApplyLibrary } from '@/components/press/ApplyPicker'
import { useWindowPaletteFit, useWindowPressPair } from '@/hooks/usePress'
import { useMarquee } from '@/components/fixtures-list/marqueeContext'

/**
 * The Stack table's *+ Look or palette*: the one picker on the window's selection (apply-model plan
 * §4.6) — the strip's targets and the marquee's families, an unlinked window's own included — so a
 * press from the table lands where a press from row C would, with the same gestures. Mounted only
 * while open: its pair is a marquee subscription, which a shut picker must not hold.
 */
export function ApplyLayerPicker({
  projectId,
  library,
  onClose,
}: {
  projectId: number
  library: ApplyLibrary
  onClose: () => void
}) {
  const pair = useWindowPressPair()
  const fit = useWindowPaletteFit()
  // The families the marquee asked for, as the strip's New chip takes them: with rows selected and
  // no cells the gesture named no attribute, and the sheet should ask.
  const asked = useMarquee()?.press.askedFamilies ?? null
  return (
    <ApplyPicker
      projectId={projectId}
      open
      onOpenChange={(next) => !next && onClose()}
      library={library}
      pair={pair}
      fit={fit}
      saveFamilies={asked}
    />
  )
}
