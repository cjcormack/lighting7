import { useMemo } from 'react'
import type { GroupMember } from '@/api/groupsApi'
import { useFixtureListQuery } from '@/store/fixtures'

/** The head a member belongs to: an element member's parent fixture, or the member itself. */
function headKeyOf(member: GroupMember): string {
  if (!member.tags.includes('element')) return member.fixtureKey
  const dot = member.fixtureKey.lastIndexOf('.element-')
  return dot > 0 ? member.fixtureKey.substring(0, dot) : member.fixtureKey
}

/**
 * A group's members as the Groups views show them: every member but one on an infrastructure head.
 * The group still contains (and drives) it — it is only not shown, so the list and the header's
 * count both come from here and cannot disagree. Reads the raw fixture list, because resolving the
 * flag of a key the group already holds is a lookup, not an offer.
 */
export function useVisibleGroupMembers(members: GroupMember[] | undefined): GroupMember[] | undefined {
  const { data: fixtures } = useFixtureListQuery()
  return useMemo(() => {
    if (members == null) return members
    const infrastructure = new Set((fixtures ?? []).filter((f) => f.infrastructure).map((f) => f.key))
    if (infrastructure.size === 0) return members
    return members.filter((member) => !infrastructure.has(headKeyOf(member)))
  }, [members, fixtures])
}
