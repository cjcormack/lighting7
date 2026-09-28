import type { IncludedTarget } from '@/api/programmerWsApi'

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
