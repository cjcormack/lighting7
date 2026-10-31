import { useEffect, useMemo, useRef, useState } from 'react'
import { Input } from '@/components/ui/input'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { useNow } from '@/components/rigSafety/useNow'
import { DarkChip } from '@/components/cues/moveInDarkMarks'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { headName } from '@/lib/headName'
import {
  CUE_MOVE_IN_DARK_DEFAULT,
  LIVE_REASON_SENTENCE,
  MOVE_IN_DARK_OFF,
  familiesLabel,
  formatSeconds,
  parseSeconds,
  remainingLabel,
  stackModeLabel,
} from '@/lib/moveInDark'
import { cn } from '@/lib/utils'
import { useCueStackMoveInDarkQuery, useProjectCueStackListQuery } from '@/store/cueStacks'
import { useMoveInDarkHeads } from '@/store/moveInDark'
import { usePatchProjectCueMutation } from '@/store/cues'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { isCueRow, type MoveInDarkLiveReason, type StackMoveInDark } from '@/api/cueStacksApi'
import type { Cue, CueMoveInDark } from '@/api/cuesApi'
import type { MoveInDarkHead } from '@/api/moveInDarkApi'

type Mode = CueMoveInDark['mode']

/**
 * The Cue tab's *Move in dark* section (lighting7 move-in-dark design §5.3, D3): the cue's choice —
 * a segment whose first option reads its stack's setting — its own delay and move time, which show
 * the stack's until typed over, and the heads it lights, from the desk's static plan for the stack it
 * was opened from, merged with what the stack is doing now (`moveInDark.state`). *In dark · Live*
 * keeps one head as a fly-in. Each gesture is one cue `PATCH` of the field it moves. Locked, the
 * controls go and the list stays.
 */
export function CueMoveInDarkSection({
  cue,
  projectId,
  locked,
  idBase,
  scrollRequest,
}: {
  cue: Cue
  projectId: number
  locked: boolean
  idBase: string
  /** Bring the section into view, once per `seq` — the cue sheet's *Dark* cell asks. */
  scrollRequest?: { seq: number } | null
}) {
  const [patchCue] = usePatchProjectCueMutation()
  const setting = cue.moveInDark ?? CUE_MOVE_IN_DARK_DEFAULT
  const write = (moveInDark: Partial<Omit<CueMoveInDark, 'unknownLive'>>) =>
    patchCue({ projectId, cueId: cue.id, moveInDark }).unwrap().catch(ignoreReportedError)

  // The stack it was opened from, else the cue's first place: a shared cue's heads are moved by each
  // stack it is in, and this reads one of them.
  const stackId = cue.cueStackId ?? cue.entries?.[0]?.stackId ?? null
  const entryId = cue.entryId ?? cue.entries?.find((e) => e.stackId === stackId)?.entryId ?? null
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const stack = stackId == null ? null : (stacks?.find((s) => s.id === stackId) ?? null)
  const stackSetting: StackMoveInDark = stack?.moveInDark ?? MOVE_IN_DARK_OFF
  const { data: plan } = useCueStackMoveInDarkQuery({ projectId, stackId: stackId ?? 0 }, { skip: stackId == null })
  const planEntry = plan?.entries.find((e) => e.entryId === entryId)
  const stackHeads = useMoveInDarkHeads(stackId ?? -1)
  const nowByHead = useMemo(() => {
    const out = new Map<string, MoveInDarkHead>()
    for (const h of stackHeads) if (h.entryId === entryId) out.set(h.target, h)
    return out
  }, [entryId, stackHeads])
  const ticking = [...nowByHead.values()].some((h) => h.endsAtMs != null)
  const nowMs = useNow(ticking, undefined, 200)

  const { fixtures } = useFixtureLookup()
  const nameOf = (key: string) => headName(fixtures ?? [], key)
  /** `Q10` for an entry of the stack, the From column's word. */
  const entryLabel = (id: number) => {
    const row = stack?.cues.find((c) => c.entryId === id)
    if (row == null) return '—'
    return row.cueNumber ? `Q${row.cueNumber}` : row.name
  }
  /** The cue entry played before this one — round to the last on a loop — where *lit before* lights it. */
  const previousEntryId = (() => {
    const cues = stack?.cues.filter(isCueRow) ?? []
    const at = cues.findIndex((c) => c.entryId === entryId)
    if (at > 0) return cues[at - 1].entryId
    return at === 0 && stack?.loop && cues.length > 1 ? cues[cues.length - 1].entryId : null
  })()
  const litNow = (reason: MoveInDarkLiveReason) =>
    reason === 'LIT_BEFORE' && previousEntryId != null ? `Lit in ${entryLabel(previousEntryId)}` : LIVE_REASON_SENTENCE[reason]

  const sectionRef = useRef<HTMLDivElement>(null)
  const seq = scrollRequest?.seq
  useEffect(() => {
    if (seq != null) sectionRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [seq])

  // The list as last written, until the cue read back says it (or the write fails): each toggle
  // writes the whole list, so one made before the last one's refetch must build on it.
  const [pendingLive, setPendingLive] = useState<string[] | null>(null)
  const storedLive = setting.live
  useEffect(() => {
    if (pendingLive != null && sameKeys(pendingLive, storedLive)) setPendingLive(null)
  }, [pendingLive, storedLive])
  const liveKeys = pendingLive ?? storedLive

  /** A head is live through its own key, or its fixture's, which keeps every element of it live. */
  const parentOf = (key: string) => fixtures?.find((f) => f.elements?.some((e) => e.key === key))
  const isLive = (key: string) => liveKeys.includes(key) || (parentOf(key) != null && liveKeys.includes(parentOf(key)!.key))
  const writeLive = (next: string[]) => {
    setPendingLive(next)
    patchCue({ projectId, cueId: cue.id, moveInDark: { live: next } })
      .unwrap()
      .catch(() => {
        setPendingLive(null)
        ignoreReportedError()
      })
  }
  const setLive = (key: string, live: boolean) => {
    if (live) {
      if (!isLive(key)) writeLive([...liveKeys, key])
      return
    }
    const parent = parentOf(key)
    let next = liveKeys.filter((k) => k !== key)
    // Kept live with its whole fixture: the fixture's other heads stay live, this one goes dark.
    if (parent != null && next.includes(parent.key)) {
      next = [...next.filter((k) => k !== parent.key), ...(parent.elements ?? []).map((e) => e.key).filter((k) => k !== key)]
    }
    writeLive(next)
  }

  const effectiveTimeMs = setting.timeMs ?? stackSetting.timeMs
  const moving = planEntry?.heads ?? []
  const lit = planEntry?.live ?? []

  return (
    <div ref={sectionRef} className="space-y-2" data-move-in-dark-section>
      <EditorLabel>Move in dark</EditorLabel>
      {locked ? (
        <p className="text-xs text-muted-foreground">
          {modeLabel(setting.mode, stackSetting)} · delay {timeLabel(setting.delayMs, stackSetting.delayMs)} · move{' '}
          {timeLabel(setting.timeMs, stackSetting.timeMs)}
        </p>
      ) : (
        <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-2">
          <ToggleGroup
            type="single"
            size="sm"
            aria-label="Move in dark"
            value={setting.mode}
            onValueChange={(next) => next && next !== setting.mode && write({ mode: next as Mode })}
            className="col-span-2 justify-self-start"
          >
            <ToggleGroupItem value="STACK" title="Follow the stack's setting">
              {stack == null ? 'Stack' : stackModeLabel(stackSetting)}
            </ToggleGroupItem>
            <ToggleGroupItem value="EARLY" title="Move this cue's heads as soon as they are dark, even on a stack that is off">
              Early
            </ToggleGroupItem>
            <ToggleGroupItem value="LATE" title="Move this cue's heads only once it is the next GO">
              Late
            </ToggleGroupItem>
            <ToggleGroupItem value="OFF" title="Move this cue's heads in its fade, lit">
              Off
            </ToggleGroupItem>
          </ToggleGroup>
          <label htmlFor={`${idBase}-mid-delay`} className="text-xs text-muted-foreground">
            Delay
          </label>
          <OverrideField
            id={`${idBase}-mid-delay`}
            value={setting.delayMs ?? null}
            stackMs={stackSetting.delayMs}
            onCommit={(delayMs) => write({ delayMs })}
            noun="delay"
          />
          <label htmlFor={`${idBase}-mid-time`} className="text-xs text-muted-foreground">
            Move time
          </label>
          <OverrideField
            id={`${idBase}-mid-time`}
            value={setting.timeMs ?? null}
            stackMs={stackSetting.timeMs}
            onCommit={(timeMs) => write({ timeMs })}
            noun="move time"
          />
        </div>
      )}

      {stackId == null ? (
        <p className="text-[11px] text-muted-foreground">This cue is in no stack, so nothing moves its heads in the dark.</p>
      ) : plan != null && !plan.rigLoaded ? (
        <p className="text-[11px] text-muted-foreground">The heads this cue lights are listed once its project is current.</p>
      ) : moving.length === 0 && lit.length === 0 ? (
        <p className="text-[11px] text-muted-foreground">
          No head this cue lights moves in the dark
          {stack ? ` in ${stack.name}` : ''}.
        </p>
      ) : (
        <div className="space-y-1">
          <p className="text-[11px] font-medium text-muted-foreground">Heads this cue lights</p>
          <table className="w-full text-xs" data-move-in-dark-heads>
            <thead className="text-[10px] uppercase tracking-wide text-muted-foreground">
              <tr>
                <th className="py-1 text-left font-medium">Head</th>
                <th className="py-1 text-left font-medium">From</th>
                <th className="py-1 text-left font-medium">Moves</th>
                <th className="py-1 text-left font-medium">Now</th>
                {!locked && <th className="py-1 text-right font-medium">In dark</th>}
              </tr>
            </thead>
            <tbody>
              {moving.map((h) => (
                <tr key={h.target} data-head={h.target} className="border-t border-border/50">
                  <td className="py-1 pr-2">{nameOf(h.target)}</td>
                  <td className="py-1 pr-2 font-mono">{entryLabel(h.fromEntryId)}</td>
                  <td className="py-1 pr-2">{familiesLabel(h.families)}</td>
                  <td className="py-1 pr-2">
                    <NowCell head={nowByHead.get(h.target)} nowMs={nowMs} timeMs={effectiveTimeMs} />
                  </td>
                  {!locked && (
                    <td className="py-1 text-right">
                      <LiveToggle live={false} name={nameOf(h.target)} onChange={(live) => setLive(h.target, live)} />
                    </td>
                  )}
                </tr>
              ))}
              {lit.length > 0 && (
                <tr>
                  <td colSpan={locked ? 4 : 5} className="pt-2 pb-1 text-[10px] uppercase tracking-wide text-muted-foreground">
                    Move while lit
                  </td>
                </tr>
              )}
              {lit.map((h) => (
                <tr key={h.target} data-head={h.target} data-lit-reason={h.reason} className="border-t border-border/50">
                  <td className="py-1 pr-2">{nameOf(h.target)}</td>
                  <td className="py-1 pr-2 text-muted-foreground">—</td>
                  <td className="py-1 pr-2">{familiesLabel(h.families ?? [])}</td>
                  <td className="py-1 pr-2">
                    <DarkChip lit>{litNow(h.reason)}</DarkChip>
                  </td>
                  {!locked && (
                    <td className="py-1 text-right">
                      {/* A cue set Off moves every head lit; that is the segment's to change, not a head's. */}
                      {h.reason !== 'CUE_OFF' && (
                        <LiveToggle
                          live={isLive(h.target)}
                          name={nameOf(h.target)}
                          onChange={(live) => setLive(h.target, live)}
                        />
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-[11px] text-muted-foreground">
            From: the cue the move happens in. Now: Set, a bar while moving, Waiting while the delay counts, Lit early when
            something lit it first.
          </p>
        </div>
      )}
      {(setting.unknownLive?.length ?? 0) > 0 && (
        <p className="text-[11px] text-muted-foreground">Kept live, but not in this rig: {setting.unknownLive!.join(', ')}</p>
      )}
    </div>
  )
}

function sameKeys(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((k) => b.includes(k))
}

function modeLabel(mode: Mode, stack: StackMoveInDark): string {
  switch (mode) {
    case 'STACK':
      return stackModeLabel(stack)
    case 'EARLY':
      return 'Early'
    case 'LATE':
      return 'Late'
    case 'OFF':
      return 'Off'
  }
}

function timeLabel(own: number | null | undefined, stackMs: number): string {
  return own == null ? `Stack · ${formatSeconds(stackMs)}` : formatSeconds(own)
}

/** A delay or a move time: blank shows the stack's, typed over is the cue's own; blank again gives it back. */
function OverrideField({
  id,
  value,
  stackMs,
  onCommit,
  noun,
}: {
  id: string
  value: number | null
  stackMs: number
  onCommit: (ms: number | null) => void
  noun: string
}) {
  const shown = value == null ? '' : formatSeconds(value)
  const [draft, setDraft] = useState(shown)
  const [problem, setProblem] = useState<string | null>(null)
  const ref = useRef<HTMLInputElement>(null)
  // The draft last sent, until the cue reads back or the field is typed in: a blur before then sends nothing again.
  const sent = useRef<string | null>(null)
  useEffect(() => {
    sent.current = null
    if (document.activeElement !== ref.current) setDraft(shown)
  }, [shown])
  const commit = () => {
    // Untouched: the field shows tenths, and writing them back would round a stored 1.25 s.
    if (draft === shown || draft === sent.current) {
      setProblem(null)
      return
    }
    if (draft.trim() === '') {
      setProblem(null)
      if (value != null) {
        sent.current = draft
        onCommit(null)
      }
      return
    }
    const ms = parseSeconds(draft)
    if (ms === undefined) {
      setProblem(`A ${noun} is 0 to 30 seconds, or blank for the stack's`)
      return
    }
    setProblem(null)
    if (ms !== value) {
      sent.current = draft
      onCommit(ms)
    }
  }
  return (
    <div className="space-y-1">
      <Input
        ref={ref}
        id={id}
        value={draft}
        placeholder={`Stack · ${formatSeconds(stackMs)}`}
        onChange={(e) => {
          sent.current = null
          setDraft(e.target.value)
        }}
        onBlur={commit}
        // Enter leaves the field, which commits once, on its blur.
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur()
        }}
        aria-invalid={problem != null}
        inputMode="decimal"
        className="h-8 w-28 font-mono text-xs"
      />
      {problem && <p className="text-xs text-destructive">{problem}</p>}
    </div>
  )
}

function LiveToggle({ live, name, onChange }: { live: boolean; name: string; onChange: (live: boolean) => void }) {
  return (
    <ToggleGroup
      type="single"
      size="sm"
      aria-label={`${name}: in dark or live`}
      value={live ? 'live' : 'dark'}
      onValueChange={(next) => next && onChange(next === 'live')}
      className="h-7"
    >
      <ToggleGroupItem value="dark" className="h-5 px-1.5 text-[11px]">
        In dark
      </ToggleGroupItem>
      <ToggleGroupItem value="live" className="h-5 px-1.5 text-[11px]">
        Live
      </ToggleGroupItem>
    </ToggleGroup>
  )
}

/** *Set*, a bar while moving, *Waiting* while the delay counts, *Lit early*, or a dash before the stack runs. */
function NowCell({ head, nowMs, timeMs }: { head: MoveInDarkHead | undefined; nowMs: number; timeMs: number }) {
  if (head == null) return <span className="text-muted-foreground">—</span>
  switch (head.state) {
    case 'SET':
      return <span className="text-indigo-300">Set</span>
    case 'WAITING':
      return <span className="text-muted-foreground">Waiting</span>
    case 'LIT_EARLY':
      return <DarkChip lit>Lit early</DarkChip>
    case 'MOVING': {
      const left = head.endsAtMs == null ? 0 : Math.max(0, head.endsAtMs - nowMs)
      const done = timeMs > 0 ? Math.min(1, Math.max(0, 1 - left / timeMs)) : 1
      return (
        <span className="inline-flex items-center gap-1.5" title="Moving">
          <span
            className="relative h-1.5 w-12 overflow-hidden rounded bg-indigo-500/20"
            role="progressbar"
            aria-valuenow={Math.round(done * 100)}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label="Moving"
          >
            <span className={cn('absolute inset-y-0 left-0 bg-indigo-400')} style={{ width: `${done * 100}%` }} />
          </span>
          <span className="font-mono text-[10px] text-indigo-300">{remainingLabel(head.endsAtMs, nowMs) ?? ''}</span>
        </span>
      )
    }
  }
}
