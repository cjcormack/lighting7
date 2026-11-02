import type { MoveInDarkHeadState, MoveInDarkState } from '../../api/moveInDarkApi'
import type { VisSource } from '../../hooks/useVisSource'
import { remainingLabel } from '../../lib/moveInDark'
import type { MovesInDark } from './scene/movesInDark'

/**
 * What the Stage view draws for **Move in dark** (lighting7 move-in-dark plan D11, design §5.5): a
 * dark head draws no beam, so for each head the desk is moving in the dark it draws a dashed aim
 * line along where the head points now, a ring where the move lands, and a badge naming the cue it
 * is for. The desk owns the plan (P2): this reads `moveInDark.state` and works out nothing but where
 * the target's pan and tilt land, which the beam's own landing does (`MoveInDarkMarker.tsx`). Pure.
 */

/** One head's mark: the cue it moves for, its target's pan and tilt bytes, and its countdown. */
export interface MoveInDarkMark {
  /** The cue as a GO labels it (`Q12`). */
  label: string
  state: Exclude<MoveInDarkHeadState, 'LIT_EARLY'>
  /** When the delay (`WAITING`) or the move (`MOVING`) ends, on this browser's clock. */
  endsAtMs: number | null
  pan: number
  tilt: number
}

/**
 * Whether a window draws the overlay: its switch on and its source one of the two Output sources,
 * which are what the desk moves. Next GO, the programmer and a cue in edit draw another picture,
 * and a `render_view` capture never draws it (`Stage3D` holds that).
 */
export function drawsMovesInDark(source: VisSource, on: MovesInDark): boolean {
  return on === 'on' && (source === 'output' || source === 'outputProgrammer')
}

/** [key]'s mark from [marks], or none — and none in a `render_view` capture, which draws the room as lit. */
export function markFor(marks: ReadonlyMap<string, MoveInDarkMark> | null, key: string, capturing: boolean): MoveInDarkMark | null {
  return capturing ? null : (marks?.get(key) ?? null)
}

/**
 * Each head's mark in [state], keyed by the head's key, for the live show [projectId] only. A head
 * whose move carries no Position has nowhere to ring; one lit early is let go (D8) and draws its own
 * beam. Where two stacks move one head, the lower stack id's is drawn.
 */
export function moveInDarkMarks(state: MoveInDarkState, projectId: number): ReadonlyMap<string, MoveInDarkMark> {
  const marks = new Map<string, MoveInDarkMark>()
  if (state.projectId !== projectId) return marks
  for (const heads of Object.values(state.stacks)) {
    for (const head of heads) {
      if (head.position == null || head.state === 'LIT_EARLY' || marks.has(head.target)) continue
      marks.set(head.target, {
        label: head.label,
        state: head.state,
        endsAtMs: head.endsAtMs,
        pan: head.position.pan,
        tilt: head.position.tilt,
      })
    }
  }
  return marks
}

/**
 * The badge (design §5.5): `☾ Q12` set, `☾ Q12 · 1.4s` while it travels, `☾ Q12 · in 0.6s` while it
 * waits out the delay; the time drops once it has run out, until the next frame says what follows.
 */
export function markBadge(mark: MoveInDarkMark, nowMs: number): string {
  const label = mark.label || 'next cue'
  const left = mark.state === 'SET' ? null : remainingLabel(mark.endsAtMs, nowMs)
  if (left == null) return `☾ ${label}`
  return mark.state === 'WAITING' ? `☾ ${label} · in ${left}` : `☾ ${label} · ${left}`
}

/** Whether [mark]'s badge still counts down at [nowMs], so its host keeps a timer. */
export function markCounting(mark: MoveInDarkMark, nowMs: number): boolean {
  return mark.state !== 'SET' && mark.endsAtMs != null && mark.endsAtMs > nowMs
}

/** The landing ring's radius: the beam's pool at that throw, kept readable near and far. */
export function markRingRadiusM(throwM: number, beamDeg: number): number {
  const r = throwM * Math.tan(((beamDeg > 0 ? beamDeg : 15) * Math.PI) / 360)
  return Math.min(1.5, Math.max(0.15, r))
}
