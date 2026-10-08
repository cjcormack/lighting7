import React from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { useGroupQuery } from '../../store/groups'
import { LocateButton } from '../fixtures/LocateButton'
import { FxBadge } from '../fx/FxBadge'
import { FixtureSheet } from '../fixtureSheet/FixtureSheet'
import { OpenSheetButton } from '../fixtureSheet/OpenSheetButton'
import type { GroupMember, GroupSummary } from '../../api/groupsApi'
import { useVisibleGroupMembers } from './useVisibleGroupMembers'

interface GroupCardProps {
  group: GroupSummary
  onFixtureClick: (fixtureKey: string) => void
  /** The card's corner: open the group sheet (`GroupDetailModal`). */
  onOpenSheet: (groupName: string) => void
}

/**
 * One card per group on the Groups page: the group sheet's **card** host (fixture-fx-sheets plan §4,
 * session 4) under the card's own header — the head strip of members, rows that write the group, the
 * members' grid, and the FX tray at the foot. There is no Edit toggle any more: a card is live while
 * the desk is connected (D2), and a slider writes one group entry, never a raw channel per member.
 * Its corner opens the group sheet, as a fixture card's opens the fixture's pop-up (session 6): the
 * group sheet is the one place a group has Park, Release and the scope line.
 */
function GroupCardInner({ group, onFixtureClick, onOpenSheet }: GroupCardProps) {
  const { data: groupDetail } = useGroupQuery(group.name)
  return (
    <Card className="gap-0 overflow-hidden pb-0">
      <GroupCardHeader group={group} members={groupDetail?.members} onOpenSheet={onOpenSheet} />
      <CardContent className="px-0">
        <FixtureSheet group={group} host="card" onOpenMember={onFixtureClick} />
      </CardContent>
    </Card>
  )
}

export const GroupCard = React.memo(GroupCardInner)

function GroupCardHeader({
  group,
  members,
  onOpenSheet,
}: {
  group: GroupSummary
  members: GroupMember[] | undefined
  onOpenSheet: (groupName: string) => void
}) {
  // The count the member list below shows — infrastructure members left out of both — and the
  // summary's own until the detail lands.
  const count = useVisibleGroupMembers(members)?.length ?? group.memberCount
  return (
    <CardHeader className="pb-2">
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <CardTitle className="text-lg truncate">{group.name}</CardTitle>
          <p className="text-xs text-muted-foreground truncate">
            {count} fixture{count !== 1 ? 's' : ''}
          </p>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <LocateButton type="group" targetKey={group.name} name={group.name} iconOnly />
          <OpenSheetButton name={group.name} onOpen={() => onOpenSheet(group.name)} />
        </div>
      </div>
      <div className="flex flex-wrap gap-1 mt-1">
        {group.capabilities.map((cap) => (
          <Badge key={cap} variant="outline" className="text-xs capitalize">
            {cap}
          </Badge>
        ))}
        <FxBadge groupName={group.name} />
      </div>
    </CardHeader>
  )
}
