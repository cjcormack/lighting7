import { isCueRow, type CueStack, type CueStackCueRow } from '@/api/cueStacksApi'

/**
 * What a **stack pad** draws (lighting7 busk-stack plan D9, §4 "Stack pad"), read from the stack as
 * the stack list holds it — which `cueRunStateChanged` patches in place, so the face follows every
 * GO from any door with no subscription of its own.
 *
 * Pure: the busk pad, its tests and the library's row all read it.
 */

/** One cue as a face line names it: `Q2` and `Chorus`. */
export interface StackPadCue {
  number: string | null
  name: string
}

export interface StackPadView {
  /** Solid green: the stack is live. A stack still fading out after a stop reads stopped (D6). */
  running: boolean
  /** No cue entries: a tap answers `empty` and nothing starts. */
  empty: boolean
  /** The live cue, while running. */
  live: StackPadCue | null
  /** Running: the cue the next tap fires. Stopped: the cue a tap starts on (*GO starts Q1 …*). */
  next: StackPadCue | null
  /** Running on the last cue with nothing next: what the next tap does there. */
  atEnd: 'RELEASE' | 'STAY' | null
  /** The fade of the live cue, for the bar under the face; null when it snaps. */
  liveFadeMs: number | null
  /** A tap does something: false for an empty stack, and on a Stay stack's last cue (D9's menu). */
  canGo: boolean
  /** One cue back does something: running, and not on the first cue of a stack that does not loop. */
  canBack: boolean
}

const asCue = (row: CueStackCueRow): StackPadCue => ({ number: row.cueNumber, name: row.name })

/** `Q2 Chorus`, or the name alone for an unnumbered cue. */
export function stackPadCueLabel(cue: StackPadCue): string {
  return cue.number != null && cue.number !== '' ? `Q${cue.number} ${cue.name}` : cue.name
}

/** The face for [stack], or null while the list has not answered for it (or it has gone). */
export function stackPadView(stack: CueStack | undefined): StackPadView | null {
  if (stack == null) return null
  const rows = stack.cues.filter(isCueRow)
  const rowAt = (entryId: number | null | undefined, cueId: number | null | undefined): CueStackCueRow | undefined =>
    (entryId != null ? rows.find((r) => r.entryId === entryId) : undefined) ??
    (cueId != null ? rows.find((r) => r.id === cueId) : undefined)
  const running = stack.activeCueId != null
  const liveRow = running ? rowAt(stack.activeEntryId, stack.activeCueId) : undefined
  const nextRow = rowAt(stack.nextEntryId, stack.nextCueId) ?? (running ? undefined : rows[0])
  const atEnd = running && nextRow == null ? (stack.releaseAtEnd ? 'RELEASE' : 'STAY') : null
  return {
    running,
    empty: rows.length === 0,
    live: liveRow != null ? asCue(liveRow) : null,
    next: nextRow != null ? asCue(nextRow) : null,
    atEnd,
    liveFadeMs: liveRow?.fadeDurationMs ?? null,
    canGo: rows.length > 0 && atEnd !== 'STAY',
    canBack: liveRow != null && (stack.loop || rows.indexOf(liveRow) > 0),
  }
}

/** The library row's detail: `4 cues · release`, its count and what it does after the last cue. */
export function stackLibraryDetail(stack: CueStack): string {
  const count = stack.cues.filter(isCueRow).length
  const atEnd = stack.loop ? 'loop' : stack.releaseAtEnd ? 'release' : 'stay'
  return `${count} ${count === 1 ? 'cue' : 'cues'} · ${atEnd}`
}
