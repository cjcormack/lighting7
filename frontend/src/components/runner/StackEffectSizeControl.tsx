import { useMemo, type SyntheticEvent } from 'react'
import { Slider } from '@/components/ui/slider'
import { cn } from '@/lib/utils'
import { useLiveFader } from '@/components/editor/useLiveFader'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'
import { levelPercent } from '@/lib/levelPercent'
import { setStackEffectSize, useStackEffectSize } from '@/store/stackEffectSize'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useCueHolds } from '@/store/cueHolds'
import { useIsDeskConnected } from '@/store/status'

/** The slider's scale: the desk's 0–1 size as a percent. */
const FULL_PCT = 100

/**
 * A press inside the control is the control's, never the row's it sits on (a stack row drills).
 * Keys are left to bubble: the row binds none, and the window's transport keys (L, GO, BACK) must
 * still answer with the thumb focused.
 */
const keep = (e: SyntheticEvent) => e.stopPropagation()

/**
 * A cue stack's **FX amount** (lighting7 effects plan D10, §4.3): a slider over the desk's runtime
 * weight on that stack's FX, 0–100 %, reading the desk's value. It writes as it moves
 * (`useLiveFader`), and a MIDI `stackFxAmount` fader or another window may move it too. A runtime
 * gesture, so it works while the show is locked, as GO does; it greys only offline. Amber below
 * full, as the grand master's chip is, so a stack left at 60 % does not read as a quiet chase.
 */
export function StackEffectSizeControl({
  stackId,
  stackName,
  className,
  sliderClassName = 'w-24',
}: {
  stackId: number
  stackName: string
  className?: string
  sliderClassName?: string
}) {
  const size = useStackEffectSize(stackId)
  const connected = useIsDeskConnected()
  const fader = useLiveFader(size * FULL_PCT, (pct) => setStackEffectSize(stackId, pct / FULL_PCT))
  const pct = levelPercent(fader.shown)

  return (
    <div
      className={cn('flex items-center gap-2', className)}
      title={connected ? `FX amount — how much of ${stackName}'s FX reach the stage` : DESK_OFFLINE_LABEL}
      onClick={keep}
      onPointerDown={keep}
      data-stack-effect-size={stackId}
    >
      <span className="shrink-0 text-[9px] font-bold uppercase tracking-wider text-muted-foreground">FX amount</span>
      <Slider
        className={sliderClassName}
        min={0}
        max={FULL_PCT}
        step={1}
        value={[fader.shown]}
        disabled={!connected}
        onValueChange={fader.onValueChange}
        onValueCommit={fader.onValueCommit}
        aria-label={`FX amount, ${stackName}`}
      />
      <span
        className={cn(
          'w-9 shrink-0 text-right text-xs font-semibold tabular-nums',
          pct < FULL_PCT && 'text-amber-600 dark:text-amber-400',
        )}
      >
        {pct}%
      </span>
    </div>
  )
}

/**
 * The phone runner's FX amount row (§4.3): drawn while the stack is below full, and at full only
 * while one of its effects is running — a stack of plain cues has nothing for it to move, and the
 * phone has no room to spare. An effect is the stack's when its cue's highest hold is this stack's
 * (`cueHolds.state`'s `stackId`, the stack that weighs it — Q21); the effect list and the holds are
 * read only at full, where the answer matters.
 */
export function MobileStackEffectSize({
  projectId,
  stackId,
  stackName,
}: {
  projectId: number
  stackId: number
  stackName: string
}) {
  const size = useStackEffectSize(stackId)
  if (size < 1) return <MobileStackEffectSizeRow stackId={stackId} stackName={stackName} />
  return <MobileStackEffectSizeWhenRunning projectId={projectId} stackId={stackId} stackName={stackName} />
}

function MobileStackEffectSizeWhenRunning({
  projectId,
  stackId,
  stackName,
}: {
  projectId: number
  stackId: number
  stackName: string
}) {
  const holds = useCueHolds(projectId)
  const weighed = useMemo(
    () => new Set(holds.cues.filter((cue) => cue.topStackId === stackId).map((cue) => cue.cueId)),
    [holds, stackId],
  )
  const { data: effects } = useActiveEffectsQuery()
  const running = effects?.some((effect) => effect.cueId != null && weighed.has(effect.cueId)) ?? false
  if (!running) return null
  return <MobileStackEffectSizeRow stackId={stackId} stackName={stackName} />
}

function MobileStackEffectSizeRow({ stackId, stackName }: { stackId: number; stackName: string }) {
  return (
    <div className="flex h-10 shrink-0 items-center border-b bg-card px-3">
      <StackEffectSizeControl
        stackId={stackId}
        stackName={stackName}
        className="w-full"
        sliderClassName="flex-1"
      />
    </div>
  )
}
