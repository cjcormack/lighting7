// Follow sequences in the prompt book. A cue the desk fires by itself after the one before has no
// line of its own to be called on, so the book attaches it to the cue that starts its sequence.

import type { CueStack } from '../../api/cueStacksApi'
import type { CueRunStatus } from '../../components/promptbook/AnchorOverlay'
import { cueLabel } from '../cueUtils'
import { formatMs } from '../formatMs'

/** How one entry follows the cue before it. */
export interface FollowLink {
  /** The entry that starts the sequence: the one the operator calls, and the one anchored. */
  headEntryId: number
  /** The cue immediately before, which the delay runs from. */
  afterLabel: string
  delayMs: number
}

/** A sequence's followers, on its head. */
export interface FollowTail {
  entryIds: number[]
  /** One line per follower, in order: `Q12.5 · +3.0s`. */
  summary: string
}

export function formatFollowDelay(delayMs: number): string {
  return `+${formatMs(delayMs)}`
}

/**
 * Every follower in the show, keyed by entry. A cue follows when the cue before it in its stack has
 * a follow with a delay: without one the desk schedules nothing. Markers play no part, and the list
 * is read top to bottom, so a looping stack's first cue is always a head.
 */
export function followLinks(stacks: CueStack[] | undefined): Map<number, FollowLink> {
  const links = new Map<number, FollowLink>()
  for (const stack of stacks ?? []) {
    if (stack.type !== 'STACK') continue
    let before: CueStack['cues'][number] | null = null
    for (const cue of stack.cues) {
      if (cue.cueType !== 'STANDARD') continue
      if (before?.autoAdvance && before.autoAdvanceDelayMs != null) {
        links.set(cue.entryId, {
          headEntryId: links.get(before.entryId)?.headEntryId ?? before.entryId,
          afterLabel: cueLabel(before),
          delayMs: before.autoAdvanceDelayMs,
        })
      }
      before = cue
    }
  }
  return links
}

/** The same links read from the head's side, each tail in play order. */
export function followTails(
  links: Map<number, FollowLink>,
  labelByEntry: Map<number, string>,
): Map<number, FollowTail> {
  const tails = new Map<number, FollowTail>()
  for (const [entryId, { headEntryId, delayMs }] of links) {
    const line = `${labelByEntry.get(entryId) ?? 'Cue'} · ${formatFollowDelay(delayMs)}`
    const tail = tails.get(headEntryId)
    if (tail) {
      tail.entryIds.push(entryId)
      tail.summary += `\n${line}`
    } else {
      tails.set(headEntryId, { entryIds: [entryId], summary: line })
    }
  }
  return tails
}

/**
 * What one anchor's glyph says for its sequence, one change per line, null when nothing moves: the
 * head's own changes as its card lists them, each follower's after them, named by its cue.
 */
export function sequenceScenery(
  entryId: number,
  tail: FollowTail | undefined,
  linesOf: (entryId: number) => readonly string[],
  labelByEntry: Map<number, string>,
): string | null {
  const followers = (tail?.entryIds ?? []).flatMap((follower) =>
    linesOf(follower).map((line) => `${labelByEntry.get(follower) ?? 'Cue'}: ${line}`),
  )
  const lines = [...linesOf(entryId), ...followers]
  return lines.length > 0 ? lines.join('\n') : null
}

/** The status one anchor wears for its whole sequence, head first in [statuses]. */
export function sequenceStatus(statuses: CueRunStatus[]): CueRunStatus {
  if (statuses.includes('live')) return 'live'
  if (statuses.includes('next')) return 'next'
  return statuses.every((s) => s === 'done') ? 'done' : 'standby'
}
