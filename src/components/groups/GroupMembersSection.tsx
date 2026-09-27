import { useMemo } from 'react'
import { Loader2 } from 'lucide-react'
import { CompactFixtureCard, MultiElementCompactCard } from './CompactFixtureCard'
import { useFixtureListQuery, type Fixture } from '../../store/fixtures'
import type { GroupMember } from '../../api/groupsApi'
import { useVisibleGroupMembers } from './useVisibleGroupMembers'

interface GroupMembersSectionProps {
  members: GroupMember[] | undefined
  isLoading: boolean
  onFixtureClick: (fixtureKey: string) => void
}

export function GroupMembersSection({
  members: allMembers,
  isLoading,
  onFixtureClick,
}: GroupMembersSectionProps) {
  const { data: fixtureList } = useFixtureListQuery()
  // An infrastructure head stays in the group (the group still drives it) but is not shown.
  const members = useVisibleGroupMembers(allMembers)

  // Build a key→fixture lookup (single subscription shared across all member cards)
  const fixtureMap = useMemo(() => {
    const map = new Map<string, Fixture>()
    fixtureList?.forEach((f) => map.set(f.key, f))
    return map
  }, [fixtureList])

  // Group elements by their parent fixture
  const { regularFixtures, multiElementFixtures } = useMemo(() => {
    if (!members) {
      return { regularFixtures: [], multiElementFixtures: new Map<string, GroupMember[]>() }
    }

    const regular: GroupMember[] = []
    const multiElement = new Map<string, GroupMember[]>()

    for (const member of members) {
      const isElement = member.tags.includes('element')

      if (isElement) {
        // Extract parent fixture key (before ".element-N")
        const dotIndex = member.fixtureKey.lastIndexOf('.element-')
        const parentKey = dotIndex > 0 ? member.fixtureKey.substring(0, dotIndex) : member.fixtureKey
        const existing = multiElement.get(parentKey) ?? []
        multiElement.set(parentKey, [...existing, member])
      } else {
        regular.push(member)
      }
    }

    return {
      regularFixtures: regular,
      multiElementFixtures: multiElement,
    }
  }, [members])

  if (isLoading) {
    return (
      <div className="pt-3 border-t">
        <div className="flex justify-center py-4">
          <Loader2 className="size-4 animate-spin" />
        </div>
      </div>
    )
  }

  if (!members || members.length === 0) {
    return null
  }

  const hasContent = regularFixtures.length > 0 || multiElementFixtures.size > 0

  if (!hasContent) {
    return null
  }

  return (
    <div className="pt-3 border-t">
      <h4 className="text-sm font-medium text-muted-foreground mb-2">Members</h4>
      <div className="flex flex-wrap gap-2">
        {/* Regular fixtures */}
        {regularFixtures.map((member) => (
          <CompactFixtureCard
            key={member.fixtureKey}
            fixtureKey={member.fixtureKey}
            fixtureName={member.fixtureName}
            tags={member.tags}
            fixture={fixtureMap.get(member.fixtureKey)}
            onClick={() => onFixtureClick(member.fixtureKey)}
          />
        ))}

        {/* Multi-element fixtures (show parent with element count) */}
        {Array.from(multiElementFixtures.entries()).map(([parentKey, elements]) => (
          <MultiElementCompactCard
            key={parentKey}
            parentKey={parentKey}
            elementCount={elements.length}
            fixture={fixtureMap.get(parentKey)}
            onClick={() => onFixtureClick(parentKey)}
          />
        ))}
      </div>
    </div>
  )
}
