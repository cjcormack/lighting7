import { walkAddresses } from '@/components/editor/spreadPlans'

/** A patched head as the address arithmetic sees it. */
export interface AddressedHead {
  id: number
  name: string
  universe: number
  /** The start channel, 1–512. */
  channel: number
  /** How many channels it occupies from `channel`; at least 1. */
  footprint: number
}

/** The last channel a head occupies. */
export function lastChannel(head: Pick<AddressedHead, 'channel' | 'footprint'>): number {
  return head.channel + Math.max(1, head.footprint) - 1
}

/**
 * The address Set's batch rule: N heads land **consecutively by footprint from the typed start**,
 * in the order given (visible-row order), each on its own universe.
 *
 * Per universe rather than one walk, because the patch PUT cannot move a head to another universe:
 * a selection spanning two universes lands each universe's heads from the same start, which is the
 * one reading that leaves every head where the operator can see it. Setting four fixtures to one
 * address is never what was meant — every desk surveyed patches a range this way (CLAUDE.md
 * §Sheet kit).
 */
export function consecutiveLanding(
  heads: readonly AddressedHead[],
  start: number,
): Map<number, number> {
  const byUniverse = new Map<number, AddressedHead[]>()
  for (const head of heads) {
    const list = byUniverse.get(head.universe)
    if (list) list.push(head)
    else byUniverse.set(head.universe, [head])
  }
  const landing = new Map<number, number>()
  for (const run of byUniverse.values()) {
    const channels = walkAddresses(
      start,
      null,
      run.map((head) => head.footprint),
    )
    run.forEach((head, i) => landing.set(head.id, channels[i]))
  }
  return landing
}

/**
 * For every head, the first *other* head whose channels overlap its own on the same universe —
 * the overlap ring on the Address cell, and the legend line under the sheet.
 */
export function findOverlaps(heads: readonly AddressedHead[]): Map<number, AddressedHead> {
  const out = new Map<number, AddressedHead>()
  for (const head of heads) {
    const other = heads.find(
      (candidate) =>
        candidate.id !== head.id &&
        candidate.universe === head.universe &&
        candidate.channel <= lastChannel(head) &&
        lastChannel(candidate) >= head.channel,
    )
    if (other) out.set(head.id, other)
  }
  return out
}

/** The preview of a landing: one line per head, and the first problem with the set. */
export interface LandingReport {
  /**
   * `Front PAR 1 → 1-007`, one entry per head that moves — **a list, not a joined string**, so the
   * editor can put each head on its own line. Joined with `·` it was one wrapped paragraph that an
   * operator had to parse before Apply, which is the moment it least wants reading twice.
   */
  lines: string[]
  /** Names the collision or the overflow, or null when every head lands clear. */
  error: string | null
}

export function formatPatchAddress(universe: number, channel: number): string {
  return `${universe}-${String(channel).padStart(3, '0')}`
}

/**
 * Check a proposed landing of some heads against every head on the rig, naming the first problem
 * — the address editor's "names the collision before Apply", and the Spread panel's landing line.
 *
 * Every head is checked against the rig *as it would be*: the moved heads at their new channels,
 * the rest where they are. So a batch can land on channels its own members are vacating, and two
 * members of the batch landing on each other is caught the same way a member landing on an
 * outsider is.
 */
export function checkLanding(
  all: readonly AddressedHead[],
  landing: ReadonlyMap<number, number>,
): LandingReport {
  const moved = all.map((head) =>
    landing.has(head.id) ? { ...head, channel: landing.get(head.id)! } : head,
  )
  const movedHeads = moved.filter((head) => landing.has(head.id))
  const lines = movedHeads.map(
    (head) => `${head.name} → ${formatPatchAddress(head.universe, head.channel)}`,
  )
  for (const head of movedHeads) {
    if (head.channel < 1 || lastChannel(head) > 512) {
      return { lines, error: `${head.name} runs past channel 512 on universe ${head.universe}` }
    }
  }
  const overlaps = findOverlaps(moved)
  for (const head of movedHeads) {
    const other = overlaps.get(head.id)
    if (other) {
      return {
        lines,
        error: `${formatPatchAddress(head.universe, head.channel)} overlaps ${other.name} (${formatPatchAddress(other.universe, other.channel)} to ${formatPatchAddress(other.universe, lastChannel(other))})`,
      }
    }
  }
  return { lines, error: null }
}

/** How many of a universe's 512 addresses its heads occupy — the chip's fill bar. */
export function universeFill(heads: readonly AddressedHead[], universe: number): number {
  const used = new Set<number>()
  for (const head of heads) {
    if (head.universe !== universe) continue
    for (let c = head.channel; c <= Math.min(512, lastChannel(head)); c++) used.add(c)
  }
  return used.size
}
