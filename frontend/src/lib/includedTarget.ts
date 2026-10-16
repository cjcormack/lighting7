import type { IncludedTarget } from '@/api/programmerWsApi'
import { isCueRow, type CueStack, type CueStackCueRow } from '@/api/cueStacksApi'

/**
 * Operator-facing label for whatever Include last loaded.
 *
 * One helper rather than three call sites formatting it themselves: the toolbar tooltip, the Update
 * dialog title and the collapsed programmer pane all name the same thing, and they drifted the
 * moment a second kind was added.
 */
export function describeIncludedTarget(target: IncludedTarget): string {
  const { number, name } = includedTargetParts(target)
  return [number, name].filter(Boolean).join(' · ')
}

/**
 * The same target, split, for surfaces that lay the parts out themselves.
 *
 * The source strip sets a cue's number in mono beside its name in body text, so it needs the halves
 * rather than the joined string — but it must not format them itself, or the two spellings drift
 * the way the three call sites above drifted before `describeIncludedTarget` existed. One
 * implementation, two shapes: the joined form is built from this one.
 */
export function includedTargetParts(target: IncludedTarget): {
  number?: string
  /** Absent for a cue that has a number but no name — the number alone identifies it. */
  name?: string
} {
  if (target.kind === 'LOOK') {
    return { name: target.lookName ?? `Look ${target.lookId}` }
  }
  return {
    number: target.cueNumber || undefined,
    // The id fallback only stands in when there is no number either: "Q3 · Cue 5" would name the
    // same cue twice, and worse, in two different vocabularies.
    name: target.cueName || (target.cueNumber ? undefined : `Cue ${target.cueId}`),
  }
}

/**
 * A stable key for the include target, used by `UpdateDialog`'s once-per-run guard.
 *
 * One shape per kind — `B`, `A:C:{id}`, `A:L:{id}` — so flipping between a cue target, a Look
 * target and the checklist each re-arms the dialog. Keying on `open` alone previously left Mode B
 * showing no checklist and a permanently disabled button; the same class of bug is reachable when a
 * target is cleared from another tab. Any kind added later needs its own prefix here, or two
 * targets of different kinds sharing an id would look like one.
 */
export function includedTargetKey(target: IncludedTarget | null): string {
  if (target == null) return 'B'
  if (target.kind === 'LOOK') return `A:L:${target.lookId}`
  return `A:C:${target.cueId}`
}

/** The cue id when a cue is included, else null. Narrows the union at read sites. */
export function includedCueId(target: IncludedTarget | null): number | null {
  return target?.kind === 'CUE' ? target.cueId : null
}

/**
 * Where an included cue sits in the stack list: the entry it was included from when the include
 * named one, else the first row placing the cue. `undefined` while there is nothing to look in or
 * no cue is included; `null` when the cue is in no stack the list holds.
 */
export function includedCueEntry(
  target: IncludedTarget | null,
  stacks: readonly CueStack[] | undefined,
): { stack: CueStack; index: number; row: CueStackCueRow } | null | undefined {
  if (target?.kind !== 'CUE' || stacks == null) return undefined
  const entryId = target.entryId
  if (entryId != null) {
    for (const stack of stacks) {
      const index = stack.cues.findIndex((c) => c.entryId === entryId)
      const row = stack.cues[index]
      if (row != null && isCueRow(row)) return { stack, index, row }
    }
  }
  for (const stack of stacks) {
    const index = stack.cues.findIndex((c) => isCueRow(c) && c.id === target.cueId)
    const row = stack.cues[index]
    if (row != null && isCueRow(row)) return { stack, index, row }
  }
  return null
}
