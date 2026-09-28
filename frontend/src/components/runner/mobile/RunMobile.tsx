import { useState, useCallback, useMemo, type ReactNode } from 'react'
import { ArrowLeft, ChevronDown, List, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { SpeedMastersChip } from '../../SpeedMasters'
import { StackPickerSheet } from '../StackPickerSheet'
import { MobileCueListSheet } from '../MobileCueListSheet'
import { RunMobileCueCard, type MobileExpansion } from './RunMobileCueCard'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'

/**
 * The **phone runner**: the takeover layout Show swaps to below 600px, always locked.
 *
 * This directory was `runner/run/` — "the Run view's components" — until session 2b folded Run
 * into Show and everything shared with the desktop view moved up into `runner/`. What is left is
 * only the phone layout, which is what `runner/mobile/` is now named for.
 */
export interface RunnerDisplayState {
  activeCue: CueStackCueEntry | null
  standbyCue: CueStackCueEntry | null
  nextStack: CueStack | null
  activeCueId: number | null
  standbyCueId: number | null
  completedCueIds: number[]
}

interface RunMobileProps {
  stacks: CueStack[]
  selectedStackId: number | null
  stack: CueStack | undefined
  /** When false (single-stack show), the stack name is a plain label — no picker. */
  multiStack: boolean
  display: RunnerDisplayState
  dbo: boolean
  onGo: () => void
  onBack: () => void
  onDbo: () => void
  onSelectStack: (stack: CueStack) => void
  onRequeueCue: (cueId: number) => void
  projectId: number
  /** The live stack id, or null off the playhead — gates the Current card's and the cue-list
   *  sheet's own `useCueFade` subscriptions (see `useCueFade`). */
  fadeStackId: number | null
  /** Prompt-book reading position of the active / next cue, e.g. "top of p. 9". */
  activeLocation: string | null
  standbyLocation: string | null
  /**
   * Drawn on the top strip after the cue-list glyph and before the spacer — the busk view's Show
   * tab puts `ProgrammerIndicator` here, the blind report and value count the header it can hide
   * used to carry (busk-chrome plan D1, D10). The phone passes nothing.
   */
  strip?: ReactNode
  /**
   * Which card opens on mount, and in which mode. The phone opens Current in Stage, because there
   * the card *is* the stage; the busk view's Show tab passes `null` — collapsed — because there the
   * rig band is, and two mini-stages a column apart would read as two answers (busk-chrome plan
   * D2). Read once: the toggle on the card owns it from then on.
   */
  defaultExpansion?: MobileExpansion | null
}

/** The phone's own default: Current, in Stage. */
const PHONE_EXPANSION: MobileExpansion = { card: 'cur', mode: 'stage' }

/**
 * Mobile takeover for the Run view. Replaces the previous `ShowRunnerMobile`
 * hero-and-standby layout with the Run-redesign's stacked Current + Next cards
 * (each with an internal Stage / Details toggle), a bottom-sheet picker for
 * re-queueing, and a fixed BACK + GO transport.
 *
 * **Two hosts since the busk-chrome plan's session A**: the phone branch of `ShowPage`, and the
 * busk view's side-sheet Show tab (`components/busking/ShowTab.tsx`), which mounts this same
 * component in a 320px column — one component, not a copy, so the two cannot disagree about what
 * the runner shows. The two differ only through [strip] and [defaultExpansion]; the cue list is
 * the same Radix bottom sheet over the whole window in both, since a 320px column is not the place
 * to draw a list of a hundred cues beside two cards.
 */
export function RunMobile({
  stacks,
  selectedStackId,
  stack,
  multiStack,
  display,
  dbo,
  onGo,
  onBack,
  onDbo,
  onSelectStack,
  onRequeueCue,
  projectId,
  fadeStackId,
  activeLocation,
  standbyLocation,
  strip,
  defaultExpansion = PHONE_EXPANSION,
}: RunMobileProps) {
  const cues = useMemo<CueStackCueEntry[]>(() => stack?.cues ?? [], [stack?.cues])
  const [stackPickerOpen, setStackPickerOpen] = useState(false)
  const [cueListOpen, setCueListOpen] = useState(false)
  const [expansion, setExpansion] = useState<MobileExpansion | null>(defaultExpansion)

  const { activeCue, standbyCue, nextStack } = display

  const playable = useMemo(
    () => cues.filter((c) => c.cueType === 'STANDARD'),
    [cues],
  )
  const curIdx = activeCue ? playable.findIndex((c) => c.id === activeCue.id) : -1
  const counter =
    activeCue && curIdx >= 0 ? `${curIdx + 1} / ${playable.length}` : null

  const handleSelectCueFromList = useCallback(
    (cueId: number) => {
      onRequeueCue(cueId)
      setCueListOpen(false)
    },
    [onRequeueCue],
  )

  const goLabel = standbyCue || nextStack ? 'GO' : 'END'
  const goDisabled = !standbyCue && !nextStack

  return (
    <div className="flex flex-1 flex-col min-w-0 overflow-hidden">
      {/* Top strip. **The stack name is what gives.** In the busk sheet's 320px column (and the
          overlay's 288) the strip's fixed items — the list glyph, the programmer chip, the tempo
          chip, DBO — come to ~250px with the gutters, so the name button is `shrink` (the button
          base is `shrink-0`) down to `min-w-10`, truncating; everything else keeps its width and
          the last item, DBO, is never clipped. Found by mounting this in the sheet (busk-chrome
          plan §10). */}
      <div className="flex h-12 min-w-0 shrink-0 items-center gap-1 border-b bg-card px-2">
        {multiStack ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setStackPickerOpen(true)}
            className="flex items-center gap-1 h-9 px-2 min-w-10 max-w-[45%] shrink"
            aria-label="Switch stack"
          >
            <span className="truncate text-sm font-medium">
              {stack?.name ?? 'No stack'}
            </span>
            {stack?.loop && (
              <RotateCcw className="size-3 shrink-0 text-muted-foreground" />
            )}
            <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" />
          </Button>
        ) : (
          <div className="flex items-center gap-1 h-9 px-2 min-w-10 max-w-[45%] shrink">
            <span className="truncate text-sm font-medium">
              {stack?.name ?? 'No stack'}
            </span>
            {stack?.loop && (
              <RotateCcw className="size-3 shrink-0 text-muted-foreground" />
            )}
          </div>
        )}

        <Button
          variant="ghost"
          size="icon-sm"
          onClick={() => setCueListOpen(true)}
          aria-label="Open cue list"
        >
          <List className="size-4" />
        </Button>

        {strip}

        <div className="flex-1" />

        {/* Every master, master 1 included. This used to be a hand-rolled M1 readout + TAP beside
            a strip that deliberately excluded M1, so the two disagreed about what "the tempo" meant
            and neither could tap anything but master 1. Asked for by name rather than by a `compact`
            prop because RunMobile has no `@container` ancestor for the chip to choose itself. */}
        <SpeedMastersChip />

        <Button
          variant={dbo ? 'destructive' : 'outline'}
          size="sm"
          onClick={onDbo}
          className={cn(
            'h-8 px-2 text-xs font-bold tracking-wider',
            dbo && 'shadow-[0_0_14px_rgba(200,32,32,0.55)]',
          )}
        >
          DBO
        </Button>
      </div>

      {/* Stacked Current + Next cards */}
      <div className="flex-1 min-h-0 overflow-y-auto p-3 flex flex-col gap-2.5 bg-background">
        <RunMobileCueCard
          kind="cur"
          cue={activeCue}
          projectId={projectId}
          expansion={expansion}
          onSetExpansion={setExpansion}
          counter={counter}
          fadeStackId={fadeStackId}
          location={activeLocation}
        />
        <RunMobileCueCard
          kind="nxt"
          cue={standbyCue}
          projectId={projectId}
          expansion={expansion}
          onSetExpansion={setExpansion}
          onChange={() => setCueListOpen(true)}
          location={standbyLocation}
        />

        {!standbyCue && nextStack && (
          <div className="rounded-lg border border-blue-900/60 bg-blue-950/20 px-3 py-2 text-sm text-blue-300 italic">
            End of stack — next stack: {nextStack.name}
          </div>
        )}
      </div>

      {/* GO / BACK footer */}
      <div
        className="grid grid-cols-[1fr_2fr] gap-2 border-t p-3 shrink-0 bg-background"
        style={{ paddingBottom: 'calc(0.75rem + env(safe-area-inset-bottom, 0px))' }}
      >
        <Button
          variant="outline"
          onClick={onBack}
          className="h-14 text-base font-bold tracking-wider uppercase"
        >
          <ArrowLeft className="size-5" />
          Back
        </Button>
        <Button
          onClick={onGo}
          disabled={goDisabled}
          className="h-14 text-2xl font-bold tracking-[0.16em] uppercase"
        >
          {goLabel}
        </Button>
      </div>

      {/* Sheets */}
      {multiStack && (
        <StackPickerSheet
          open={stackPickerOpen}
          onOpenChange={setStackPickerOpen}
          stacks={stacks}
          selectedStackId={selectedStackId}
          onSelectStack={onSelectStack}
        />
      )}
      <MobileCueListSheet
        open={cueListOpen}
        onOpenChange={setCueListOpen}
        stackName={stack?.name ?? ''}
        cues={cues}
        activeCueId={display.activeCueId}
        standbyCueId={display.standbyCueId}
        completedCueIds={display.completedCueIds}
        fadeStackId={fadeStackId}
        // Q-numbers and notes always render now that the Theatre/Band toggle
        // is gone — pass true so MobileCueListSheet doesn't hide them.
        isTheatre={true}
        onSelectCue={handleSelectCueFromList}
      />
    </div>
  )
}
