import { useMemo } from 'react'
import { useFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import { readColour, type FixtureColour } from '@/lib/fixtureColours'
import type { TargetColours } from '@/lib/targetColours'
import { useFixtureColourIndex } from './useFixtureColourIndex'

const parentSignatures = new WeakMap<readonly Fixture[], string>()

/**
 * Every head the fixture list declares with its fixture's key, as JSON so a selector compares it by
 * value; element keys are opaque, never parsed.
 */
function parentSignature(fixtures: readonly Fixture[] | undefined): string {
  if (fixtures == null) return '[]'
  let signature = parentSignatures.get(fixtures)
  if (signature == null) {
    signature = JSON.stringify(fixtures.flatMap((f) => (f.elements ?? []).map((e) => [e.key, f.key])))
    parentSignatures.set(fixtures, signature)
  }
  return signature
}

const groupSignatures = new WeakMap<readonly GroupSummary[], string>()

/** Every coloured group's name and colour, as JSON: a name is any string the desk took. */
function groupSignature(groups: readonly GroupSummary[] | undefined): string {
  if (groups == null) return '[]'
  let signature = groupSignatures.get(groups)
  if (signature == null) {
    signature = JSON.stringify(
      groups.flatMap((g) => {
        const colour = readColour(g.colour)
        return colour == null ? [] : [[g.name, colour]]
      }),
    )
    groupSignatures.set(groups, signature)
  }
  return signature
}

/**
 * The live rig's [TargetColours], resolved once (plan §3) from the cached patch, fixture and group
 * lists. Its identity changes only when a colour, a head's parent or a group's colour does, so a
 * memoised control handed it re-renders on a recolour and on nothing else.
 */
export function useTargetColours(projectId: number | null | undefined): TargetColours {
  const index = useFixtureColourIndex(projectId)
  const { parents } = useFixtureListQuery(undefined, {
    selectFromResult: ({ data }) => ({ parents: parentSignature(data) }),
  })
  const { groupColours } = useGroupListQuery(undefined, {
    selectFromResult: ({ data }) => ({ groupColours: groupSignature(data) }),
  })
  return useMemo(() => {
    const fixtures = new Map(index)
    for (const [head, parent] of JSON.parse(parents) as [string, string][]) {
      const colour = index.get(parent)
      if (colour != null) fixtures.set(head, colour)
    }
    const groups = new Map(JSON.parse(groupColours) as [string, FixtureColour][])
    return { fixtures, groups }
  }, [index, parents, groupColours])
}
