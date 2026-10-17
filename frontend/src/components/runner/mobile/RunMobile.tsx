import { useState, useCallback, useMemo, type ReactNode } from 'react'
import { ArrowLeft, ChevronDown, List, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SpeedMastersChip } from '../../SpeedMasters'
import { StackPickerSheet } from '../StackPickerSheet'
import { MobileCueListSheet } from '../MobileCueListSheet'
import { RunMobileCueCard, type MobileExpansion } from './RunMobileCueCard'
import { CueActions } from '../CueActions'
import { MobileStackEffectSize } from '../StackEffectSizeControl'
import type { CueStack, CueStackCueEntry, CueStackCueRow } from '@/api/cueStacksApi'

/**
 * The **phone runner**: the takeover layout Show swaps to below 600px, always locked.
 *
 * This directory was `runner/run/` — "the Run view's components" — until session 2b folded Run
 * into Show and everything shared with the desktop view moved up into `runner/`. What is left is
 * only the phone layout, which is what `runner/mobile/` is now named for.
 */
export interface RunnerDisplayState {
  activeCue: CueStackCueRow | null
  standbyCue: CueStackCueRow | null
  nextStack: CueStack | null
  activeCueId: number | null
  standbyCueId: number | null
  /** The entries behind the two cursors: a list row is an entry, and a cue may be twice in a stack. */
  activeEntryId: number | null
  standbyEntryId: number | null
  completedCueIds: number[]
}

interface RunMobileProps {
  stacks: CueStack[]
  selectedStackId: number | null
  stack: CueStack | undefined
  /** When false (single-stack show), the stack name is a plain label — no picker. */
  multiStack: boolean
  display: RunnerDisplayState
  onGo: () => void
  onBack: () => void
  onSelectStack: (stack: CueStack) => void
  onRequeueCue: (cueId: number, entryId?: number) => void
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
   * The two cards' ⋯ action menus (show-cue-editing plan D9) and the lock they follow: locked, only
   * *Blind edit* works, and the rest ask to unlock through `onRequestUnlock`, or are disabled with
   * `lockedReason` without it. Absent draws no menu.
   */
  cueActions?: { locked: boolean; onRequestUnlock?: () => void; lockedReason?: string; fromShowView?: boolean }
}

/** The stack holding [entryId], for a card's actions — the cards follow the playhead, not the stack shown. */
function stackOfEntry(stacks: readonly CueStack[], entryId: number | null): number | null {
  if (entryId == null) return null
  return stacks.find((s) => s.cues.some((c) => c.entryId === entryId))?.id ?? null
}

/**
 * Mobile takeover for the Run view. Replaces the previous `ShowRunnerMobile`
 * hero-and-standby layout with the Run-redesign's stacked Current + Next cards
 * (each with an internal Details toggle), a bottom-sheet picker for
 * re-queueing, and a fixed BACK + GO transport.
 *
 * **Two hosts since the busk-chrome plan's session A**: the phone branch of `ShowPage`, and the
 * busk view's side-sheet Show tab (`components/busking/ShowTab.tsx`), which mounts this same
 * component in a 320px column — one component, not a copy, so the two cannot disagree about what
 * the runner shows. The two differ only through [strip]; the cue list is
 * the same Radix bottom sheet over the whole window in both, since a 320px column is not the place
 * to draw a list of a hundred cues beside two cards.
 */
export function RunMobile({
  stacks,
  selectedStackId,
  stack,
  multiStack,
  display,
  onGo,
  onBack,
  onSelectStack,
  onRequeueCue,
  projectId,
  fadeStackId,
  activeLocation,
  standbyLocation,
  strip,
  cueActions,
}: RunMobileProps) {
  const cues = useMemo<CueStackCueEntry[]>(() => stack?.cues ?? [], [stack?.cues])
  const [stackPickerOpen, setStackPickerOpen] = useState(false)
  const [cueListOpen, setCueListOpen] = useState(false)
  // Both cards open collapsed, in both hosts. The phone opened Current in Stage until venue-visit
  // plan S3 took the cards' `MiniStage` away — the card *was* the stage there — and the busk view's
  // Show tab already opened collapsed, the rig band being its stage (busk-chrome plan D2).
  const [expansion, setExpansion] = useState<MobileExpansion | null>(null)

  const { activeCue, standbyCue, nextStack } = display

  /** A card's ⋯ menu, for the cue and the entry it shows. */
  const actionsFor = (cue: CueStackCueRow | null, entryId: number | null) =>
    cueActions == null || cue == null ? undefined : (
      <CueActions
        projectId={projectId}
        host="menu"
        rows={[
          {
            cueId: cue.id,
            name: cue.name,
            cueNumber: cue.cueNumber ?? null,
            entryId: entryId ?? cue.entryId,
            stackId: stackOfEntry(stacks, entryId ?? cue.entryId),
          },
        ]}
        locked={cueActions.locked}
        onRequestUnlock={cueActions.onRequestUnlock}
        lockedReason={cueActions.lockedReason}
        fromShowView={cueActions.fromShowView}
      />
    )

  const playable = useMemo(
    () => cues.filter((c) => c.cueType === 'STANDARD'),
    [cues],
  )
  const curIdx = activeCue ? playable.findIndex((c) => c.id === activeCue.id) : -1
  const counter =
    activeCue && curIdx >= 0 ? `${curIdx + 1} / ${playable.length}` : null

  const handleSelectCueFromList = useCallback(
    (cueId: number, entryId: number) => {
      onRequeueCue(cueId, entryId)
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
          chip — take most of it with the gutters, so the name button is `shrink` (the button base
          is `shrink-0`) down to `min-w-10`, truncating; everything else keeps its width and the
          last item is never clipped. Found by mounting this in the sheet (busk-chrome plan §10). */}
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
      </div>

      {stack && stack.type === 'STACK' && <MobileStackEffectSize key={stack.id} projectId={projectId} stackId={stack.id} stackName={stack.name} />}

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
          actions={actionsFor(activeCue, display.activeEntryId)}
        />
        <RunMobileCueCard
          kind="nxt"
          cue={standbyCue}
          projectId={projectId}
          expansion={expansion}
          onSetExpansion={setExpansion}
          onChange={() => setCueListOpen(true)}
          location={standbyLocation}
          actions={actionsFor(standbyCue, display.standbyEntryId)}
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
        activeEntryId={display.activeEntryId}
        standbyEntryId={display.standbyEntryId}
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
