import type { CommandRole, CommandRun, CommandRunUnit, RigSafetyState } from '@/api/rigSafetyApi'

/**
 * How the end-of-night state reads (venue-visit plan S6): which banner a window draws, and the words
 * on it. Pure, so the banner, the immersive chip and their tests say one thing.
 */
export type RigBanner =
  /** A run is going: its progress, and Cancel. */
  | { kind: 'run'; run: CommandRun }
  /** *Ready to switch off*: lamped off, blacked out, nothing sent since. */
  | { kind: 'ready' }
  /** *Make rig safe*'s blackout holds, but the rig is not ready: a lamp not known to be off, or output moved. */
  | { kind: 'blackout' }
  /** A run ended with something not done — said until this window dismisses it. */
  | { kind: 'finished'; run: CommandRun }

/** The banner [state] calls for, given the finished runs this window has dismissed. */
export function rigBanner(state: RigSafetyState, dismissed: ReadonlySet<string>): RigBanner | null {
  const run = state.run
  if (run != null && !run.finished) return { kind: 'run', run }
  if (state.ready) return { kind: 'ready' }
  if (state.blackout) return { kind: 'blackout' }
  if (run != null && !run.cancelled && !dismissed.has(run.id) && run.units.some((u) => u.status !== 'DONE')) {
    return { kind: 'finished', run }
  }
  return null
}

const ROLE_WORDS: Record<CommandRole, string> = { LAMP_ON: 'Lamp on', LAMP_OFF: 'Lamp off', RESET: 'Reset' }

/** What a run is doing, as its banner names it. */
export function runTitle(run: CommandRun): string {
  if (run.purpose === 'MAKE_SAFE') return 'Making the rig safe'
  if (run.purpose === 'UNDO') return 'Undoing Make rig safe'
  return `${ROLE_WORDS[run.role]} · ${run.units.length} ${run.units.length === 1 ? 'unit' : 'units'}`
}

/** `Lamp on`, `Lamp off`, `Reset` — a role as a verb on a button. */
export function roleLabel(role: CommandRole): string {
  return ROLE_WORDS[role]
}

/** How many of a run's units have ended, whatever the outcome. */
export function unitsEnded(run: CommandRun): number {
  return run.units.filter((u) => u.status !== 'QUEUED' && u.status !== 'WAITING' && u.status !== 'RUNNING').length
}

/**
 * A run's progress: how many are done, and the next unit waiting on its restrike, with the time left
 * counted from the frame's arrival. `2 of 6 done · MAC 2 in 4:12 (restrike)`.
 */
export function runProgress(run: CommandRun, receivedAt: number, now: number): string {
  const ended = unitsEnded(run)
  const parts = [`${ended} of ${run.units.length} done`]
  const running = run.units.filter((u) => u.status === 'RUNNING')
  if (running.length > 0) parts.push(running.length === 1 ? `${running[0].fixtureName} running` : `${running.length} running`)
  const waiting = run.units
    .filter((u) => u.status === 'WAITING')
    .map((u) => ({ u, left: remainingAt(u.remainingMs, receivedAt, now) }))
    .sort((a, b) => a.left - b.left)
  if (waiting.length > 0) {
    const next = waiting[0]
    const more = waiting.length > 1 ? ` (+${waiting.length - 1} waiting)` : ''
    parts.push(`${next.u.fixtureName} in ${clockLabel(next.left)} — restrike${more}`)
  }
  return parts.join(' · ')
}

/** A finished run in counts: `4 done · 1 skipped · 1 cut short`. */
export function runOutcome(run: CommandRun): string {
  const count = (s: CommandRunUnit['status']) => run.units.filter((u) => u.status === s).length
  const parts = [`${count('DONE')} done`]
  if (count('SKIPPED') > 0) parts.push(`${count('SKIPPED')} skipped`)
  if (count('CUT_SHORT') > 0) parts.push(`${count('CUT_SHORT')} cut short`)
  if (count('CANCELLED') > 0) parts.push(`${count('CANCELLED')} cancelled`)
  return parts.join(' · ')
}

/** One unit's line in a run's detail list. */
export function unitLine(unit: CommandRunUnit, receivedAt: number, now: number): string {
  const left = remainingAt(unit.remainingMs, receivedAt, now)
  switch (unit.status) {
    case 'QUEUED':
      return 'Queued'
    case 'WAITING':
      return `Restrike in ${clockLabel(left)}`
    case 'RUNNING':
      return `Holding · ${clockLabel(left)}`
    case 'DONE':
      return 'Done'
    case 'CUT_SHORT':
      return 'Cut short'
    case 'CANCELLED':
      return 'Cancelled'
    case 'SKIPPED':
      return unit.message ? `Skipped — ${unit.message}` : `Skipped (${unit.code ?? 'refused'})`
  }
}

/** Why the rig is blacked out but not ready to switch off: a lamp no lamp-off has reached, or output that moved. */
export function blackoutReason(state: RigSafetyState): string {
  if (state.notOff.length > 0) {
    const names = state.notOff.slice(0, 3).join(', ') + (state.notOff.length > 3 ? ` and ${state.notOff.length - 3} more` : '')
    return `${state.notOff.length === 1 ? 'a lamp is' : `${state.notOff.length} lamps are`} not known to be off: ${names}`
  }
  return 'Output has changed since it was made safe'
}

/** A lamp's restrike time left, or 0: what a strike now would meet. */
export function restrikeLeft(state: RigSafetyState, fixtureKey: string, now: number): number {
  const lamp = state.lampsOff.find((l) => l.fixture === fixtureKey)
  return lamp == null ? 0 : remainingAt(lamp.restrikeRemainingMs, state.receivedAt, now)
}

/** [remainingMs] at the frame, counted down from when it arrived; 0 once past. */
export function remainingAt(remainingMs: number | null | undefined, receivedAt: number, now: number): number {
  if (remainingMs == null) return 0
  return Math.max(0, remainingMs - (now - receivedAt))
}

/** `4:12`, `0:05` — a wait as the banner and the menu count it. */
export function clockLabel(ms: number): string {
  const total = Math.ceil(ms / 1000)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}
