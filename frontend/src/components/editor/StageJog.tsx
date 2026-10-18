import { useCallback, useMemo, useRef, useState } from 'react'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { setPositionPadMode, type PositionPadMode } from '@/lib/positionPadMode'
import { JOG_METRES_PER_PX, JOG_NUDGE_M, JogQueue, type JogSend } from '@/lib/stageJog'
import { cn } from '@/lib/utils'
import { useAimMutation, type AimBase, type AimResponse, type SpreadSkip } from '@/store/programmerOps'
import { useCurrentProjectQuery } from '@/store/projects'
import { skipsByReason } from '../stage3d/planAim'
import { isAimable } from '../stage3d/StageAimControls'
import { skippedLine } from './editorCopy'

/**
 * The stage-relative jog on a position pad: the *Pan/Tilt | Stage* switch, the jog pad itself, and
 * the hook that sends its drags to `POST /programmer/aim` as `mode: JOG`. The desk solves every
 * head's pan and tilt; nothing here does.
 */

/** *Pan/Tilt · Stage* — which of the two a pad's drag means, remembered for this viewer. */
export function PadModeSwitch({ mode, className }: { mode: PositionPadMode; className?: string }) {
  return (
    <ToggleGroup
      type="single"
      size="sm"
      value={mode}
      onValueChange={(value) => {
        if (value === 'PAN_TILT' || value === 'STAGE') setPositionPadMode(value)
      }}
      aria-label="Pad"
      className={cn('h-7 p-0.5', className)}
    >
      <ToggleGroupItem value="PAN_TILT" className="h-6 px-2 text-xs" title="A point on the pad is a pan and a tilt">
        Pan/Tilt
      </ToggleGroupItem>
      <ToggleGroupItem value="STAGE" className="h-6 px-2 text-xs" title="A drag moves every head's beam across the stage">
        Stage
      </ToggleGroupItem>
    </ToggleGroup>
  )
}

/** The four edges, as the Stage view's Plan draws the stage: upstage at the top, SR on the left. */
export const JOG_EDGES = { top: 'Upstage', bottom: 'Downstage', left: 'SR', right: 'SL' } as const
const JOG_EDGES_SHORT = { top: 'US', bottom: 'DS', left: 'SR', right: 'SL' } as const

/** A key run ends this long after its last press: the next press starts from where the heads are. */
const KEY_RUN_MS = 1200

const EDGE_CLASS = 'text-[9px] font-bold tracking-wider text-muted-foreground uppercase select-none'

const ARROWS: Record<string, readonly [number, number]> = {
  ArrowLeft: [-1, 0],
  ArrowRight: [1, 0],
  ArrowUp: [0, 1],
  ArrowDown: [0, -1],
}

/**
 * The jog pad: a drag reports its whole offset from where it began, in stage metres (+x towards SL,
 * +y upstage), and the dot springs back to the centre when it is let go. The arrow keys nudge by
 * [JOG_NUDGE_M], a run of presses one gesture so each is measured from where the run began.
 */
export function StageJogPad({
  size,
  refusal,
  onBegin,
  onMove,
}: {
  size: number
  /** Why the pad cannot jog here, or null. */
  refusal: string | null
  onBegin: () => void
  onMove: (dx: number, dy: number) => void
}) {
  const [offset, setOffset] = useState<{ x: number; y: number } | null>(null)
  const drag = useRef<{ id: number; x: number; y: number } | null>(null)
  const keyRun = useRef<{ dx: number; dy: number; at: number } | null>(null)
  const edges = size < 100 ? JOG_EDGES_SHORT : JOG_EDGES
  const disabled = refusal != null
  const half = size / 2

  const end = (e: React.PointerEvent) => {
    if (drag.current?.id !== e.pointerId) return
    drag.current = null
    setOffset(null)
  }

  return (
    <div className="grid shrink-0 grid-cols-[auto_auto_auto] items-center justify-items-center gap-0.5" data-stage-jog>
      <span className={cn(EDGE_CLASS, 'col-start-2')} data-jog-edge="top">
        {edges.top}
      </span>
      <span className={cn(EDGE_CLASS, 'col-start-1 row-start-2')} data-jog-edge="left">
        {edges.left}
      </span>
      <div
        role="group"
        tabIndex={disabled ? -1 : 0}
        aria-label="Stage jog — drag, or use the arrow keys"
        aria-disabled={disabled || undefined}
        title={refusal ?? 'Drag to move every head across the stage; the arrow keys nudge a quarter metre'}
        data-testid="stage-jog-pad"
        className={cn(
          'relative col-start-2 row-start-2 touch-none rounded-md border bg-background outline-none select-none',
          'bg-[linear-gradient(var(--color-border),var(--color-border)),linear-gradient(var(--color-border),var(--color-border))] bg-[size:1px_100%,100%_1px] bg-center bg-no-repeat',
          'focus-visible:ring-2 focus-visible:ring-ring',
          disabled ? 'cursor-not-allowed opacity-50' : offset ? 'cursor-grabbing' : 'cursor-grab',
        )}
        style={{ width: size, height: size }}
        onPointerDown={(e) => {
          if (disabled || e.button !== 0) return
          e.preventDefault()
          // Taken by hand, since the default just prevented would have taken it: the arrow keys are the pad's next.
          e.currentTarget.focus({ preventScroll: true })
          e.currentTarget.setPointerCapture?.(e.pointerId)
          drag.current = { id: e.pointerId, x: e.clientX, y: e.clientY }
          keyRun.current = null
          setOffset({ x: 0, y: 0 })
          onBegin()
        }}
        onPointerMove={(e) => {
          const start = drag.current
          if (start?.id !== e.pointerId) return
          if (disabled) {
            drag.current = null
            setOffset(null)
            return
          }
          const x = e.clientX - start.x
          const y = e.clientY - start.y
          setOffset({ x, y })
          onMove(x * JOG_METRES_PER_PX, -y * JOG_METRES_PER_PX)
        }}
        onPointerUp={end}
        onPointerCancel={end}
        onKeyDown={(e) => {
          const step = ARROWS[e.key]
          if (step == null || e.altKey || e.metaKey || e.ctrlKey) return
          e.preventDefault()
          e.stopPropagation()
          if (disabled) return
          const now = performance.now()
          const run = keyRun.current
          const from = run != null && now - run.at <= KEY_RUN_MS ? run : null
          if (from == null) onBegin()
          const dx = (from?.dx ?? 0) + step[0] * JOG_NUDGE_M
          const dy = (from?.dy ?? 0) + step[1] * JOG_NUDGE_M
          keyRun.current = { dx, dy, at: now }
          onMove(dx, dy)
        }}
      >
        <span
          className={cn(
            'pointer-events-none absolute size-3 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary shadow-[0_0_0_2px_var(--background)]',
            offset == null && 'transition-[left,top] duration-150 ease-out motion-reduce:transition-none',
          )}
          style={{
            left: half + Math.max(-half, Math.min(half, offset?.x ?? 0)),
            top: half + Math.max(-half, Math.min(half, offset?.y ?? 0)),
          }}
        />
      </div>
      <span className={cn(EDGE_CLASS, 'col-start-3 row-start-2')} data-jog-edge="right">
        {edges.right}
      </span>
      <span className={cn(EDGE_CLASS, 'col-start-2 row-start-3')} data-jog-edge="bottom">
        {edges.bottom}
      </span>
    </div>
  )
}

/** What a host of the jog says about itself. */
export interface StageJogHost {
  /** The project on the rig, whose programmer the desk solves against. */
  projectId: number | null
  /** The heads, by key. */
  keys: readonly string[]
  /** The groups the heads were picked from, so the writes carry one as their source group as the pad's own do. */
  groups?: readonly string[]
  /** A draft host: the desk answers without writing, and [onAnswer] lands the values. */
  draft?: {
    /** Each head's row being edited, as its starting position; a head the row lacks is read by the desk. */
    bases: () => AimBase[]
    onAnswer: (answer: AimResponse) => void
  }
}

/** The jog's gesture over [host]: `begin` at a press, `move` with the whole offset, the desk's last skips. */
export function useStageJog(host: StageJogHost) {
  const [aim] = useAimMutation()
  const hostRef = useRef(host)
  hostRef.current = host
  const [skipped, setSkipped] = useState<readonly SpreadSkip[]>(NO_SKIPS)

  const queueRef = useRef<JogQueue | null>(null)
  if (queueRef.current == null) {
    queueRef.current = new JogQueue(
      (step: JogSend) => {
        const { projectId, keys, groups, draft } = hostRef.current
        if (projectId == null || keys.length === 0) return Promise.reject(new Error('nothing to jog'))
        return aim({
          projectId,
          targets: keys.map((key) => ({ type: 'fixture', key })),
          mode: 'JOG',
          ...step,
          ...(groups != null && groups.length > 0 ? { groups: [...groups] } : {}),
          ...(draft ? { write: false as const } : {}),
        }).unwrap()
      },
      () => hostRef.current.draft?.bases() ?? [],
      (answer) => {
        hostRef.current.draft?.onAnswer(answer)
        const next = answer.skipped ?? NO_SKIPS
        setSkipped((prev) => (sameSkips(prev, next) ? prev : next))
      },
    )
  }
  const queue = queueRef.current
  const begin = useCallback(() => queue.begin(), [queue])
  const move = useCallback((dx: number, dy: number) => queue.move(dx, dy), [queue])
  return { begin, move, skipped }
}

const NO_SKIPS: readonly SpreadSkip[] = []

function sameSkips(a: readonly SpreadSkip[], b: readonly SpreadSkip[]): boolean {
  return a.length === b.length && a.every((s, i) => s.target.key === b[i].target.key && s.reason === b[i].reason)
}

/**
 * Why a jog cannot write [keys] live, or null: a jog writes the project on the rig, so it needs one,
 * the same project as the editor when the editor names one, and a head the desk can aim — the
 * refusals *Aim…* makes.
 */
export function useLiveJogRefusal(
  keys: readonly string[],
  viewedProjectId?: number,
): { projectId: number | null; refusal: string | null } {
  const { data: current } = useCurrentProjectQuery()
  const { fixtureByKey } = useFixtureLookup()
  const aimable = useMemo(() => keys.some((key) => isAimable(fixtureByKey.get(key))), [keys, fixtureByKey])
  const projectId = current?.id ?? null
  const refusal =
    projectId == null || (viewedProjectId != null && viewedProjectId !== projectId)
      ? 'A stage jog writes the live show, and this project is not on the rig'
      : aimable
        ? null
        : 'No head here annotates its pan and tilt in degrees'
  return { projectId, refusal }
}

/**
 * The desk's skips as the read-out says them: a head that cannot jog at all — *1 head has no stage
 * jog · skipped* — apart from one that is only out of reach this step, *1 head out of reach*; the
 * heads and reasons on the hover.
 */
export function JogSkips({ skipped }: { skipped: readonly SpreadSkip[] }) {
  const { fixtureByKey } = useFixtureLookup()
  if (skipped.length === 0) return null
  const outOfReach = skipped.filter((skip) => skip.reason.startsWith(OUT_OF_REACH)).length
  const line = [
    skippedLine(skipped.length - outOfReach, 'stage jog'),
    outOfReach > 0 ? `${outOfReach} ${outOfReach === 1 ? 'head' : 'heads'} out of reach` : null,
  ]
    .filter((part) => part != null)
    .join(' · ')
  const nameOf = (key: string) => fixtureByKey.get(key)?.name ?? key
  const title = skipsByReason(skipped, nameOf)
    .map(({ reason, names }) => `${names.join(', ')} — ${reason}`)
    .join('\n')
  return (
    <span data-jog-skips title={title}>
      {line}
    </span>
  )
}

/** The desk's word for a point outside a head's travel (`programmerAim.kt`'s skip). */
const OUT_OF_REACH = 'out of reach'

/**
 * A jog pad that writes Local live — the fixture sheet's and the busk Position tab's — with the
 * desk's skips under it. [readOnly] is the host's own refusal (offline, parked), said before the jog's;
 * [groups] the groups the pick is from.
 */
export function LiveJogPad({
  keys,
  groups,
  size,
  readOnly,
}: {
  keys: readonly string[]
  groups?: readonly string[]
  size: number
  readOnly: string | null
}) {
  const { projectId, refusal } = useLiveJogRefusal(keys)
  const jog = useStageJog({ projectId, keys, groups })
  return (
    <div className="flex shrink-0 flex-col items-center gap-1">
      <StageJogPad size={size} refusal={readOnly ?? refusal} onBegin={jog.begin} onMove={jog.move} />
      <span className="text-[10px] text-muted-foreground">
        <JogSkips skipped={jog.skipped} />
      </span>
    </div>
  )
}
