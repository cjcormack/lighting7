/**
 * The show's **running order** and the two questions the Show view's GO asks of it (lighting7
 * busk-stack plan D2, D3).
 *
 * The running order is the project's stacks less separators and **busk stacks** — a stack with
 * *In the running order* off, which runs from its own GO, a pad or a binding and which the show's
 * playhead never reaches. `/show/activate` and `/show/advance` walk the same list on the desk, so a
 * client reading whether and where the show starts reads it here.
 */
import type { CueStack } from '../api/cueStacksApi'

type StackKind = Pick<CueStack, 'type' | 'inRunningOrder'>

/** A runnable stack in the running order. A desk before busk stacks sends no flag: every stack is in. */
export function isInRunningOrder(stack: StackKind): boolean {
  return stack.type === 'STACK' && stack.inRunningOrder !== false
}

/** A runnable stack out of the running order. */
export function isBuskStack(stack: StackKind): boolean {
  return stack.type === 'STACK' && stack.inRunningOrder === false
}

/** The stacks in the running order, in the order given. */
export function runningOrder<T extends StackKind>(stacks: readonly T[] | undefined): T[] {
  return (stacks ?? []).filter(isInRunningOrder)
}

/** The stack the show's GO crosses into after [activeStackId], or null at the end of the running order. */
export function nextInRunningOrder<T extends StackKind & { id: number }>(
  stacks: readonly T[] | undefined,
  activeStackId: number | null,
): T | null {
  if (activeStackId == null) return null
  const order = runningOrder(stacks)
  const i = order.findIndex((s) => s.id === activeStackId)
  return i >= 0 ? (order[i + 1] ?? null) : null
}

/**
 * *Nothing on deck*: the playhead stack's GO has no cue of its own to fire, so the Show view's GO is
 * the boundary GO. Either no next cue, or the stack released at its end — the desk still names its
 * first cue as next then, which is what a restart would fire, not what the show does next.
 */
export function isNothingOnDeck(standbyCueId: number | null, releasedAtEnd: boolean): boolean {
  return standbyCueId == null || releasedAtEnd
}

/**
 * *GO releases*: the playhead stack is set to Release and is on its last cue with nothing armed, so
 * its own advance releases it, and only the GO after that crosses into the next stack.
 */
export function isGoReleases(
  stack: Pick<CueStack, 'releaseAtEnd' | 'loop'> | undefined,
  serverActiveCueId: number | null,
  standbyCueId: number | null,
): boolean {
  return stack?.releaseAtEnd === true && !stack.loop && serverActiveCueId != null && standbyCueId == null
}

/**
 * The project's stack list as the Show view draws it (D4): the running order's stacks and the
 * separators between them, then the busk stacks, each part in the order given.
 */
export function splitBuskStacks<T extends StackKind>(stacks: readonly T[]): { show: T[]; busk: T[] } {
  const show: T[] = []
  const busk: T[] = []
  for (const s of stacks) (isBuskStack(s) ? busk : show).push(s)
  return { show, busk }
}

/**
 * The whole list's new order after dragging [activeId] onto [overId] in the Show view's list, or
 * null for a drop that moves nothing. [ordered] is [splitBuskStacks]'s two parts end to end; a drop
 * across the divider moves nothing, since which side a stack is on is its setting's.
 */
export function reorderWithinGroup<T extends StackKind & { id: number }>(
  ordered: readonly T[],
  activeId: number,
  overId: number,
): T[] | null {
  const from = ordered.findIndex((s) => s.id === activeId)
  const to = ordered.findIndex((s) => s.id === overId)
  if (from === -1 || to === -1 || from === to) return null
  if (isBuskStack(ordered[from]) !== isBuskStack(ordered[to])) return null
  const next = [...ordered]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}

/** The stacks in show order (D15): the running order's, then the busk stacks, each in the order given. */
export function showOrderStacks<T extends StackKind>(stacks: readonly T[] | undefined): T[] {
  return [...runningOrder(stacks), ...(stacks ?? []).filter(isBuskStack)]
}

/** What [cuesInShowOrder] reads of a cue: its places, each a stack and a position in it. */
export interface ShowOrderCue {
  id: number
  name: string
  entries?: readonly { stackId: number; sortOrder: number }[]
}

/** One stack's cues in show order, or the loose cues (`stack` null). */
export interface ShowOrderSection<C, S> {
  stack: S | null
  cues: C[]
}

/**
 * Cues in show order (lighting7 venue-rig-fixes plan D15): the running order's stacks, then the busk
 * stacks, each in the order given, each cue once, under the stack of its first place in that order
 * and at its position there; then the loose cues by name. A cue placed only in stacks [stacks] does
 * not hold counts as loose. A stack no cue lands in has no section.
 */
export function cuesInShowOrder<C extends ShowOrderCue, S extends StackKind & { id: number }>(
  cues: readonly C[],
  stacks: readonly S[] | undefined,
): ShowOrderSection<C, S>[] {
  const ordered = showOrderStacks(stacks)
  const rank = new Map(ordered.map((stack, i) => [stack.id, i]))
  const placed = ordered.map(() => [] as { cue: C; at: number }[])
  const loose: C[] = []
  for (const cue of cues) {
    let first: { stack: number; at: number } | null = null
    for (const entry of cue.entries ?? []) {
      const stack = rank.get(entry.stackId)
      if (stack == null) continue
      if (first == null || stack < first.stack || (stack === first.stack && entry.sortOrder < first.at)) {
        first = { stack, at: entry.sortOrder }
      }
    }
    if (first == null) loose.push(cue)
    else placed[first.stack].push({ cue, at: first.at })
  }
  const sections: ShowOrderSection<C, S>[] = []
  ordered.forEach((stack, i) => {
    if (placed[i].length === 0) return
    sections.push({ stack, cues: placed[i].sort((a, b) => a.at - b.at || a.cue.id - b.cue.id).map((p) => p.cue) })
  })
  if (loose.length > 0) {
    loose.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) || a.id - b.id)
    sections.push({ stack: null, cues: loose })
  }
  return sections
}
