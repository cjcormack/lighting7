import { Slider } from '@/components/ui/slider'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useLiveFader } from '@/components/editor/useLiveFader'
import { levelPercent } from '@/lib/levelPercent'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'
import { GRAND_MASTER_FULL } from '@/api/grandMasterApi'
import { setGrandMasterLevel, useGrandMasterLevel } from '@/store/grandMaster'
import { useIsDeskConnected } from '@/store/status'
import { CollapsiblePanel } from './CollapsiblePanel'
import { RigSafetyControls } from './rigSafety/RigSafetyControls'

/**
 * The grand master (venue-visit plan S5, D5) as an overview panel: the live project's output level,
 * 0–100 %, summoned from the header's chip like the other four panels from their toggles rather
 * than resident on it, because a fader always under the pointer is too easy to move by accident. It scales every dimmer and UV channel,
 * and the colour mix of a head with no dimmer (D12); a strobe is cut only at 0.
 *
 * The fader writes as it moves and holds its own value over the desk's until the release settles
 * (`useLiveFader`) — a fader is judged against the rig, and a MIDI fader or another window may move
 * it too.
 *
 * Under the fader, the **Rig** row (venue-visit plan S6): *Make rig safe…*, bulk lamp commands and
 * *Undo* — the panel is where the rig's output as a whole is set.
 */
export function GrandMasterOverviewPanel({ isVisible }: { isVisible: boolean }) {
  return (
    <CollapsiblePanel isVisible={isVisible}>
      <GrandMasterControl />
      <RigSafetyControls />
    </CollapsiblePanel>
  )
}

export function GrandMasterControl() {
  const level = useGrandMasterLevel()
  const connected = useIsDeskConnected()
  const fader = useLiveFader(level, setGrandMasterLevel)
  const shown = fader.shown

  return (
    <div
      className="flex items-center gap-3 border-b bg-background px-4 py-3"
      title={connected ? undefined : DESK_OFFLINE_LABEL}
    >
      <div className="shrink-0 text-[9px] font-bold uppercase tracking-wider text-muted-foreground">Grand master</div>
      <Slider
        className="max-w-md flex-1"
        min={0}
        max={GRAND_MASTER_FULL}
        step={1}
        value={[shown]}
        disabled={!connected}
        onValueChange={fader.onValueChange}
        onValueCommit={fader.onValueCommit}
        aria-label="Grand master"
      />
      <div className={cn('w-10 shrink-0 text-right text-sm font-semibold tabular-nums', levelClass(shown))}>
        {levelPercent(shown)}%
      </div>
      <Button size="sm" variant="outline" disabled={!connected} onClick={() => fader.set(0)}>
        Out
      </Button>
      <Button size="sm" variant="outline" disabled={!connected} onClick={() => fader.set(GRAND_MASTER_FULL)}>
        Full
      </Button>
    </div>
  )
}

/** Amber below full, red at 0: a desk left at 60 % looks like a dim show. */
function levelClass(level: number): string | undefined {
  if (level <= 0) return 'text-red-600 dark:text-red-400'
  if (level < GRAND_MASTER_FULL) return 'text-amber-600 dark:text-amber-400'
  return undefined
}

/**
 * The panel's header toggle: a chip naming the level (`GM 100%`, the percentage alone below 620px of
 * header), amber below full and red at 0, so the level reads without opening anything. A press opens
 * or closes the panel and never moves the level.
 */
export function GrandMasterChip({ isVisible, onToggle }: { isVisible: boolean; onToggle: () => void }) {
  const level = useGrandMasterLevel()
  const pct = levelPercent(level)
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={isVisible}
      aria-label={`Grand master, ${pct}%`}
      title={`${isVisible ? 'Hide' : 'Show'} grand master — ${pct}%`}
      data-level={pct}
      className={cn(
        'inline-flex h-7 shrink-0 items-center gap-1 rounded-md border bg-card px-1.5 text-[11px] font-semibold tabular-nums text-foreground shadow-sm @[620px]:px-2',
        isVisible && 'ring-2 ring-primary-foreground/60',
        levelChipClass(level),
      )}
    >
      <span className="hidden text-[9px] font-bold uppercase tracking-wider opacity-70 @[620px]:inline">GM</span>
      {pct}%
    </button>
  )
}

function levelChipClass(level: number): string | undefined {
  if (level <= 0) return 'border-red-500/70 text-red-600 dark:text-red-400'
  if (level < GRAND_MASTER_FULL) return 'border-amber-500/70 text-amber-600 dark:text-amber-400'
  return undefined
}

/**
 * The level on the `ShowHeader` while immersive, below full only: the app header that carries the
 * toggle is not drawn then, and a dimmed rig must still say why. A readout, never a control.
 */
export function GrandMasterImmersiveChip() {
  const level = useGrandMasterLevel()
  if (level >= GRAND_MASTER_FULL) return null
  const pct = levelPercent(level)
  return (
    <span
      title="Grand master below full"
      className={cn(
        'inline-flex h-6 shrink-0 items-center gap-1 rounded-md border px-1.5 text-[11px] font-semibold tabular-nums',
        levelChipClass(level),
      )}
    >
      <span className="text-[9px] font-bold uppercase tracking-wider opacity-70">GM</span>
      {pct}%
    </span>
  )
}
