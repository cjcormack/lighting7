import { useMemo } from 'react'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { MarkerRow } from './MarkerRow'
import { MobileCueRow } from './MobileCueRow'
import type { CueStackCueEntry } from '@/api/cueStacksApi'

interface MobileCueListSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  stackName: string
  cues: CueStackCueEntry[]
  activeCueId: number | null
  standbyCueId: number | null
  completedCueIds: number[]
  /** The live stack id, or null when this sheet's stack isn't it — passed straight through to
   *  each row's own `useCueFade`/`useCueAutoProgress` subscription. */
  fadeStackId: number | null
  isTheatre: boolean
  onSelectCue: (cueId: number) => void
}

export function MobileCueListSheet({
  open,
  onOpenChange,
  stackName,
  cues,
  activeCueId,
  standbyCueId,
  completedCueIds,
  fadeStackId,
  isTheatre,
  onSelectCue,
}: MobileCueListSheetProps) {
  // Hoisted once instead of `completedCueIds.includes(cue.id)` per row — O(n) → O(1) per row,
  // which is what turned a 200-cue stack's per-frame cost quadratic.
  const completedSet = useMemo(() => new Set(completedCueIds), [completedCueIds])
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="bottom"
        className="max-h-[70dvh] p-0 gap-0"
      >
        <SheetHeader className="border-b">
          <SheetTitle className="truncate pr-8">
            {stackName || 'Cue list'}
          </SheetTitle>
        </SheetHeader>
        <div className="overflow-y-auto">
          {cues.length === 0 ? (
            <div className="p-6 text-center text-sm text-muted-foreground">
              This stack has no cues.
            </div>
          ) : (
            cues.map((cue) => {
              if (cue.cueType === 'MARKER') {
                return <MarkerRow key={cue.id} name={cue.name} />
              }
              const isActive = cue.id === activeCueId
              const isStandby = cue.id === standbyCueId
              const isDone = completedSet.has(cue.id)
              return (
                <MobileCueRow
                  key={cue.id}
                  cueId={cue.id}
                  cueNumber={cue.cueNumber}
                  cueNumberAuto={cue.cueNumberAuto}
                  name={cue.name}
                  fadeDurationMs={cue.fadeDurationMs}
                  fadeCurve={cue.fadeCurve}
                  autoAdvance={cue.autoAdvance}
                  isActive={isActive}
                  isStandby={isStandby}
                  isDone={isDone}
                  isTheatre={isTheatre}
                  fadeStackId={isActive ? fadeStackId : null}
                  onClick={onSelectCue}
                />
              )
            })
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
