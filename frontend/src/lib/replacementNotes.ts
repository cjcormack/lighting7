import type { LayerSource } from '@/api/cuesApi'
import type { ProgrammerAppliedSource } from '@/api/programmerWsApi'

/*
 * A layer row's *lost Hex 3–4 to Amber* (apply-model plan §4.6): the client's reading of two
 * consecutive applied-state frames, never a stored fact.
 *
 * The desk replaces a palette's family on the heads a press lands on (D2); nothing on the wire says
 * so beyond the two frames themselves. So the note is a comparison of what each record covered
 * before and after — a record that lost heads another record gained in the same frame lost them
 * *to* it. A record that lost heads nobody gained (an off press, a removal) gets no note. Read from
 * the desk's resolved applied state rather than the layers' raw targets, so a group the desk
 * narrowed to its members is compared head by head without a client copy of the expansion.
 */

/** A layer source's identity across frames: kind and id, since a Look and a palette can share an id. */
export function sourceKey(source: Pick<LayerSource, 'kind' | 'id'>): string {
  return `${source.kind}:${source.id}`
}

/** Every head a record covers whole — fixture-typed targets, cells by their element key. */
function headsOf(entry: ProgrammerAppliedSource): Set<string> {
  return new Set(entry.targets.filter((t) => t.type === 'fixture' && t.state === 'all').map((t) => t.key))
}

/** A frame's content, for telling a frame that changed nothing from one that did. */
export function appliedSignature(applied: readonly ProgrammerAppliedSource[]): string {
  return applied
    .map((entry) => `${sourceKey(entry.source)}=${[...headsOf(entry)].sort().join(',')}`)
    .sort()
    .join(';')
}

/**
 * The notes for the transition from [before] to [after], keyed by [sourceKey]. [nameOf] names a
 * head key for the operator.
 */
export function replacementNotes(
  before: readonly ProgrammerAppliedSource[],
  after: readonly ProgrammerAppliedSource[],
  nameOf: (headKey: string) => string,
  cellsOf: (headKey: string) => readonly string[] = () => [],
): Map<string, string> {
  const beforeBy = new Map(before.map((entry) => [sourceKey(entry.source), headsOf(entry)]))
  const gained = after.map((entry) => {
    const was = beforeBy.get(sourceKey(entry.source)) ?? new Set<string>()
    return { name: entry.source.name, heads: new Set([...headsOf(entry)].filter((key) => !was.has(key))) }
  })
  const afterBy = new Map(after.map((entry) => [sourceKey(entry.source), headsOf(entry)]))
  const notes = new Map<string, string>()
  for (const entry of before) {
    const key = sourceKey(entry.source)
    const now = afterBy.get(key) ?? new Set<string>()
    const lostAll = [...headsOf(entry)].filter((head) => !now.has(head))
    // The desk reports a whole bar and each of its cells; a bar lost whole is named once.
    const folded = new Set(lostAll.flatMap((head) => cellsOf(head)))
    const lost = lostAll.filter((head) => !folded.has(head))
    if (lost.length === 0) continue
    const parts: string[] = []
    for (const gainer of gained) {
      const taken = lost.filter(
        (head) => gainer.heads.has(head) || cellsOf(head).some((cell) => gainer.heads.has(cell)),
      )
      if (taken.length > 0) parts.push(`${describeHeads(taken.map(nameOf))} to ${gainer.name}`)
    }
    if (parts.length > 0) notes.set(key, `lost ${parts.join(', ')}`)
  }
  return notes
}

/**
 * Heads by name, numbered runs folded: *Hex 3–4, 6*, *Bar · Cell 1–3*. Names keep the order they
 * were given within a prefix's first appearance; past three parts the rest is counted.
 */
export function describeHeads(names: readonly string[]): string {
  // A number keeps the text it was written with, so *Par 01–02* stays as the desk names them.
  const groups = new Map<string, Map<number, string>>()
  const plain: string[] = []
  for (const name of names) {
    const match = /^(.*?)(\d+)$/.exec(name)
    if (match == null) {
      if (!plain.includes(name)) plain.push(name)
      continue
    }
    const numbers = groups.get(match[1]) ?? new Map<number, string>()
    numbers.set(Number(match[2]), match[2])
    groups.set(match[1], numbers)
  }
  const parts: string[] = []
  for (const [prefix, numbers] of groups) {
    const sorted = [...numbers.keys()].sort((a, b) => a - b)
    const runs: string[] = []
    let start = sorted[0]
    let prev = sorted[0]
    for (const n of [...sorted.slice(1), Number.NaN]) {
      if (n === prev + 1) {
        prev = n
        continue
      }
      runs.push(start === prev ? numbers.get(start)! : `${numbers.get(start)}–${numbers.get(prev)}`)
      start = n
      prev = n
    }
    parts.push(`${prefix}${runs.join(', ')}`)
  }
  parts.push(...plain)
  return parts.length <= 3 ? parts.join(', ') : `${parts.slice(0, 3).join(', ')} +${parts.length - 3}`
}
