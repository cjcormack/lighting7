import { isCueRow, type CueStack } from '@/api/cueStacksApi'

/**
 * One entry naming a cue: a place it sits in a stack's list. A cue may be in several stacks, twice
 * in one, or in none (lighting7 cue model plan D1, D7), so a cue's places are a list.
 */
export interface CuePlace {
  stackId: number
  stackName: string
  entryId: number
  cueNumber: string | null
}

/**
 * Every cue's places across the show, read off the stack list — each stack's rows carry the cue's
 * id and the entry's — in show order: stacks by `sortOrder`, then each stack's list order.
 */
export function cuePlacesByCue(stacks: readonly CueStack[] | undefined): Map<number, CuePlace[]> {
  const out = new Map<number, CuePlace[]>()
  if (stacks == null) return out
  const ordered = [...stacks].filter((s) => s.type === 'STACK').sort((a, b) => a.sortOrder - b.sortOrder)
  for (const stack of ordered) {
    for (const row of stack.cues) {
      if (!isCueRow(row)) continue
      const place = { stackId: stack.id, stackName: stack.name, entryId: row.entryId, cueNumber: row.cueNumber }
      const places = out.get(row.id)
      if (places) places.push(place)
      else out.set(row.id, [place])
    }
  }
  return out
}

/**
 * The shared chip's text, or null when the cue sits in one place only: `in 2 stacks` when its
 * entries span two or more stacks, else `×2 in stack` for a cue twice in one.
 */
export function sharedCueLabel(places: readonly CuePlace[] | undefined): string | null {
  if (places == null || places.length < 2) return null
  const stacks = new Set(places.map((p) => p.stackId)).size
  return stacks >= 2 ? `in ${stacks} stacks` : `×${places.length} in stack`
}

/** A place as a reader names it: `Act 2 · Q14`, or the stack alone for an unnumbered entry. */
export function cuePlaceLabel(place: CuePlace): string {
  return place.cueNumber ? `${place.stackName} · Q${place.cueNumber}` : place.stackName
}
