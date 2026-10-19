import type { FixtureColour } from './fixtureColours'

/** A fixture as *Colour from group* reads it. */
export interface ColourFromGroupHead {
  patchId: number
  name: string
  colour: FixtureColour | null
  groupIds: readonly number[]
}

export interface ColourFromGroupPlan {
  /** The colours to write, only where one changes. */
  writes: { patchId: number; colour: FixtureColour }[]
  /** The groups a colour was taken from, by name. */
  from: string[]
  /** Heads left alone because they have a colour (and ⌥ was not held). */
  kept: string[]
  /** Heads already in their group's colour. */
  same: string[]
  /** Heads whose coloured groups disagree — a head in three groups has no single answer (D9). */
  ambiguous: string[]
  /** Heads in no coloured group. */
  none: string[]
}

/**
 * *Colour from group* (tables-colours plan D9): a group's colour never reaches its members, so this
 * is the explicit copy — each head takes the colour of the coloured groups it is in, onto those with
 * none, or onto all of them when [all] (⌥). A head whose coloured groups name different colours is
 * left alone and named.
 */
export function planColourFromGroup(
  heads: readonly ColourFromGroupHead[],
  groups: ReadonlyMap<number, { name: string; colour: FixtureColour | null }>,
  all: boolean,
): ColourFromGroupPlan {
  const plan: ColourFromGroupPlan = { writes: [], from: [], kept: [], same: [], ambiguous: [], none: [] }
  const from = new Set<string>()
  for (const head of heads) {
    const coloured = head.groupIds.flatMap((id) => {
      const group = groups.get(id)
      return group?.colour != null ? [{ name: group.name, colour: group.colour }] : []
    })
    if (coloured.length === 0) {
      plan.none.push(head.name)
      continue
    }
    const colours = new Set(coloured.map((g) => g.colour))
    if (colours.size > 1) {
      plan.ambiguous.push(head.name)
      continue
    }
    const colour = coloured[0].colour
    for (const g of coloured) from.add(g.name)
    if (colour === head.colour) plan.same.push(head.name)
    else if (head.colour != null && !all) plan.kept.push(head.name)
    else plan.writes.push({ patchId: head.patchId, colour })
  }
  plan.from = [...from]
  return plan
}

/** The press's label: *From group · Front wash* when one group answers for the whole batch. */
export function colourFromGroupLabel(plan: ColourFromGroupPlan): string {
  return plan.from.length === 1 ? `From group · ${plan.from[0]}` : 'From group'
}

/** What a press did, for its toast; null when it wrote everything it was asked to. */
export function colourFromGroupNote(plan: ColourFromGroupPlan): string | null {
  const parts: string[] = []
  if (plan.kept.length > 0) parts.push(`${countOf(plan.kept)} already had a colour — ⌥ for all of them`)
  if (plan.ambiguous.length > 0) parts.push(`${countOf(plan.ambiguous)} in groups of different colours`)
  if (plan.none.length > 0) parts.push(`${countOf(plan.none)} in no coloured group`)
  return parts.length === 0 ? null : parts.join(' · ')
}

/** The title on a press that has nothing to copy. */
export function colourFromGroupRefusal(plan: ColourFromGroupPlan): string | null {
  if (plan.writes.length > 0 || plan.kept.length > 0) return null
  if (plan.ambiguous.length > 0) return 'Their groups have different colours'
  return plan.same.length > 0 ? 'They already have their group’s colour' : 'None of them is in a coloured group'
}

function countOf(names: readonly string[]): string {
  return names.length === 1 ? names[0] : `${names.length} fixtures`
}
