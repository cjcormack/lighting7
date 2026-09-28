import type { ProgrammerEntry } from '@/api/programmerWsApi'

/** What the programmer held at one moment: `programmerKey` → canonical value string. */
export type ValueSnapshot = ReadonlyMap<string, string>

/** Flatten the programmer's entry map down to just what a diff cares about. */
export function snapshotEntries(entries: ReadonlyMap<string, ProgrammerEntry>): ValueSnapshot {
  const out = new Map<string, string>()
  for (const [key, entry] of entries) out.set(key, entry.value)
  return out
}

/**
 * How many values have moved since Include, or `null` if this tab cannot say.
 *
 * **`null` is not `0`, and the difference is the whole point.** The server owns the real answer —
 * `POST /programmer/update` writes only what changed since Include — and there is no client-side
 * field carrying it: `ProgrammerSummary` has `entryCount` (how much the programmer holds, not how
 * much of it is new), and `ProgrammerEntry.touched` means "an operator set this" rather than
 * "changed since Include", so an Included cue's own values are touched too.
 *
 * So this diffs against a snapshot taken when Include fired. That works for the normal flow and
 * fails honestly outside it: reload the page, or open a second tab, and there is no baseline. The
 * caller must then render **no change badge at all** and leave Update enabled — never "in sync",
 * which would be a claim this cannot support and would cost an operator a cue.
 *
 * Two further limits, both acceptable and neither hideable: another tab's edits count as yours
 * (the programmer is shared — single-author programming is the standing tradeoff), and this counts
 * *entries*, not the properties the server will ultimately write.
 */
export function diffAgainstBaseline(
  baseline: ValueSnapshot | null,
  current: ValueSnapshot,
): number | null {
  if (baseline == null) return null

  let changed = 0
  for (const [key, value] of current) {
    if (baseline.get(key) !== value) changed++
  }
  for (const key of baseline.keys()) {
    if (!current.has(key)) changed++
  }
  return changed
}
