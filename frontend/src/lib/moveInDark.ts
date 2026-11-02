import type { MoveInDarkFamily, MoveInDarkHead } from '@/api/moveInDarkApi'
import type { MoveInDarkLiveReason, MoveInDarkPlanEntry, StackMoveInDark } from '@/api/cueStacksApi'
import type { CueMoveInDark } from '@/api/cuesApi'
import { parseFollowDelay } from '@/lib/cueUtils'
import { FAMILY_LABELS, formatFamilyList } from '@/lib/attributeFamily'

/**
 * The words and counts every Move in dark screen draws (lighting7 move-in-dark plan D11): the stack
 * sheet's block, the cue table's *Dark* column, the Cue tab's section and the stack row's and phone
 * runner's chips. The desk owns the plan (P2); this module only reads it out. Pure.
 */

export const MOVE_IN_DARK_FAMILIES: readonly MoveInDarkFamily[] = ['POSITION', 'COLOUR', 'BEAM']

/** A family's word, from the one table every surface reads (`lib/attributeFamily.ts`). */
export const familyLabel = (family: MoveInDarkFamily): string => FAMILY_LABELS[family].singular

/** The desk's range for a delay or a move time. */
export const MOVE_IN_DARK_MAX_MS = 30_000

export const LIVE_REASON_LABEL: Record<MoveInDarkLiveReason, string> = {
  LIT_BEFORE: 'lit before',
  TOO_QUICK: 'too quick',
  KEPT_LIVE: 'kept live',
  CUE_OFF: 'cue off',
}

/** What a head that moves lit tells the operator, at length: the Cue tab's *Now* and a hover. */
export const LIVE_REASON_SENTENCE: Record<MoveInDarkLiveReason, string> = {
  LIT_BEFORE: 'Lit in every cue before',
  TOO_QUICK: 'Dark too briefly before a follow',
  KEPT_LIVE: 'Kept live',
  CUE_OFF: 'This cue is set Off',
}

/** `Position · Colour`, in the families' own order. */
export function familiesLabel(families: readonly MoveInDarkFamily[]): string {
  return formatFamilyList(
    MOVE_IN_DARK_FAMILIES.filter((f) => families.includes(f)),
    ' · ',
  )
}

/** `1s`, `2.5s`, `0s` — a delay or a move time as the block and the Cue tab write it. */
export function formatSeconds(ms: number): string {
  const s = Math.round(ms / 100) / 10
  return `${s}s`
}

/** A typed delay or move time in ms, or undefined when it is no time in 0–30 s. Blank is undefined. */
export function parseSeconds(draft: string): number | undefined {
  if (draft.trim() === '') return undefined
  const bare = /^\s*\d+(\.\d+)?\s*$/.test(draft) ? `${draft.trim()}s` : draft
  const ms = parseFollowDelay(bare)
  if (ms == null || ms < 0 || ms > MOVE_IN_DARK_MAX_MS) return undefined
  return ms
}

/** A new stack's setting (D2, Q2): on, Early, 1 s, 2 s, every family. */
export const NEW_STACK_MOVE_IN_DARK: StackMoveInDark = {
  on: true,
  when: 'EARLY',
  delayMs: 1000,
  timeMs: 2000,
  families: ['POSITION', 'COLOUR', 'BEAM'],
}

/** A stack the desk sends none for — an older desk — reads as off. */
export const MOVE_IN_DARK_OFF: StackMoveInDark = { ...NEW_STACK_MOVE_IN_DARK, on: false }

export const CUE_MOVE_IN_DARK_DEFAULT: CueMoveInDark = { mode: 'STACK', delayMs: null, timeMs: null, live: [] }

/** What a cue's *Stack* option reads as: `Stack · Early`, or `Stack · Off` on a stack that is off. */
export function stackModeLabel(stack: StackMoveInDark): string {
  return `Stack · ${stack.on ? (stack.when === 'EARLY' ? 'Early' : 'Late') : 'Off'}`
}

/** One cue's *Dark* cell, from its entry of the static plan and its own setting. */
export interface DarkCell {
  /** Heads set in the dark for this cue. */
  set: number
  /** Heads that move lit into it, and the reasons, most common first. */
  lit: number
  reasons: MoveInDarkLiveReason[]
  /** The cue is set *Off*: its heads move in its fade, lit. */
  off: boolean
}

export function darkCellOf(entry: MoveInDarkPlanEntry | undefined): DarkCell {
  if (entry == null) return { set: 0, lit: 0, reasons: [], off: false }
  // An older desk's entry carries no mode; its heads' reasons still say Off.
  const off = entry.mode === 'OFF' || entry.live.some((h) => h.reason === 'CUE_OFF')
  const counts = new Map<MoveInDarkLiveReason, number>()
  for (const h of entry.live) counts.set(h.reason, (counts.get(h.reason) ?? 0) + 1)
  const reasons = [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([r]) => r)
  return { set: entry.heads.length, lit: entry.live.length, reasons, off }
}

/** Whether a cell has anything to say — the *Dark* filter's test. */
export function darkCellShows(cell: DarkCell): boolean {
  return cell.set > 0 || cell.lit > 0 || cell.off
}

/** The cell as words, for a hover and a screen reader. */
export function darkCellSentence(cell: DarkCell): string {
  if (cell.off) return `Off — ${cell.lit} ${cell.lit === 1 ? 'head moves' : 'heads move'} lit in its fade`
  const parts: string[] = []
  if (cell.set > 0) parts.push(`${cell.set} head${cell.set === 1 ? '' : 's'} set in the dark for this cue`)
  if (cell.lit > 0) {
    parts.push(
      `${cell.lit} ${cell.lit === 1 ? 'head moves' : 'heads move'} while lit (${cell.reasons.map((r) => LIVE_REASON_LABEL[r]).join(', ')})`,
    )
  }
  return parts.length > 0 ? parts.join('; ') : 'Moves nothing in the dark'
}

/** What a stack is moving now, in one line: the stack row's chip, the phone's *Up next*, the live row. */
export interface MovingNow {
  /** Heads waiting, moving or set — every head the desk is holding or about to. */
  moving: number
  /** Heads lit before their GO and let go. */
  litEarly: number
  /** The cue the moves are for — the first named, which is the soonest under Early. */
  label: string | null
  /** The soonest a delay or a move ends, on this browser's clock. */
  endsAtMs: number | null
}

export function movingNow(heads: readonly MoveInDarkHead[]): MovingNow {
  let moving = 0
  let litEarly = 0
  let label: string | null = null
  let endsAtMs: number | null = null
  for (const h of heads) {
    if (h.state === 'LIT_EARLY') {
      litEarly++
      continue
    }
    moving++
    if (label == null && h.label) label = h.label
    if (h.endsAtMs != null && (endsAtMs == null || h.endsAtMs < endsAtMs)) endsAtMs = h.endsAtMs
  }
  return { moving, litEarly, label, endsAtMs }
}

/** `1.4s` left from [endsAtMs] at [nowMs], or null once it has passed. */
export function remainingLabel(endsAtMs: number | null, nowMs: number): string | null {
  if (endsAtMs == null) return null
  const left = endsAtMs - nowMs
  return left > 0 ? formatSeconds(left) : null
}

/** The stack sheet's block as typed: the timings are the fields' text until Save. */
export interface StackMoveInDarkDraft {
  on: boolean
  when: StackMoveInDark['when']
  delay: string
  time: string
  families: MoveInDarkFamily[]
}

export function stackDraftOf(value: StackMoveInDark): StackMoveInDarkDraft {
  return {
    on: value.on,
    when: value.when,
    delay: formatSeconds(value.delayMs),
    time: formatSeconds(value.timeMs),
    families: MOVE_IN_DARK_FAMILIES.filter((f) => value.families.includes(f)),
  }
}

/** The draft as the stack write takes it, or every problem with it, each named by its field. */
export function stackDraftResult(
  draft: StackMoveInDarkDraft,
): { value: StackMoveInDark; problems: null } | { value: null; problems: Partial<Record<'delay' | 'time' | 'families', string>> } {
  const problems: Partial<Record<'delay' | 'time' | 'families', string>> = {}
  const delayMs = parseSeconds(draft.delay)
  const timeMs = parseSeconds(draft.time)
  if (delayMs === undefined) problems.delay = 'A delay is 0 to 30 seconds'
  if (timeMs === undefined) problems.time = 'A move time is 0 to 30 seconds'
  if (draft.families.length === 0) problems.families = 'Pick at least one of Position, Colour and Beam'
  if (delayMs === undefined || timeMs === undefined || draft.families.length === 0) return { value: null, problems }
  return { value: { on: draft.on, when: draft.when, delayMs, timeMs, families: draft.families }, problems: null }
}
