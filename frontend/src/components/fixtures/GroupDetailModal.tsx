import { useState, useEffect } from 'react'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Loader2 } from 'lucide-react'
import { useGroupListQuery, useGroupQuery, useGroupPropertiesQuery } from '../../store/groups'
import { GroupPropertiesSection } from '../groups/GroupCard'
import { GroupMembersSection } from '../groups/GroupMembersSection'
import { useVisibleGroupMembers } from '../groups/useVisibleGroupMembers'
import { FixtureDetailModal } from '../groups/FixtureDetailModal'
import { LocateButton } from './LocateButton'

interface GroupDetailModalProps {
  groupName: string | null
  onClose: () => void
}

export function GroupDetailModal({
  groupName,
  onClose,
}: GroupDetailModalProps) {
  const { data: groupList } = useGroupListQuery()
  const group = groupName ? groupList?.find((g) => g.name === groupName) : null
  const { data: groupDetail, isLoading: membersLoading } = useGroupQuery(groupName ?? '', {
    skip: !groupName,
  })
  const { data: properties, isLoading: propertiesLoading } = useGroupPropertiesQuery(
    groupName ?? '',
    { skip: !groupName }
  )
  // What the member list shows — infrastructure members left out — so the header agrees with it.
  const memberCount = useVisibleGroupMembers(groupDetail?.members)?.length ?? group?.memberCount
  const [isEditing, setIsEditing] = useState(false)
  const [selectedFixture, setSelectedFixture] = useState<string | null>(null)

  // Reset edit mode when modal closes or group changes
  useEffect(() => {
    setIsEditing(false)
    setSelectedFixture(null)
  }, [groupName])

  return (
    <Sheet open={groupName !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        <SheetHeader>
          <div className="flex items-center justify-between pr-8">
            <div>
              <SheetTitle>{group?.name ?? 'Group'}</SheetTitle>
              {group && memberCount != null && (
                <p className="text-sm text-muted-foreground">
                  {memberCount} fixture{memberCount !== 1 ? 's' : ''}
                </p>
              )}
            </div>
            <div className="flex items-center gap-1 shrink-0">
              {groupName && (
                <LocateButton type="group" targetKey={groupName} name={groupName} iconOnly />
              )}
              <Button
                variant={isEditing ? 'default' : 'outline'}
                size="sm"
                onClick={() => setIsEditing(!isEditing)}
              >
                {isEditing ? 'Done' : 'Edit'}
              </Button>
            </div>
          </div>

          {/* Capability badges */}
          {group && group.capabilities.length > 0 && (
            <div className="flex flex-wrap gap-1 mt-2">
              {group.capabilities.map((cap) => (
                <Badge key={cap} variant="outline" className="capitalize">
                  {cap}
                </Badge>
              ))}
            </div>
          )}
        </SheetHeader>

        <SheetBody>
        {groupName && (
          <div className="space-y-4">
            {/* Inline properties section */}
            <GroupPropertiesSection
              properties={properties}
              isLoading={propertiesLoading}
              isEditing={isEditing}
            />

            {/* Compact fixture member grid */}
            {membersLoading ? (
              <div className="flex justify-center py-4">
                <Loader2 className="size-6 animate-spin" />
              </div>
            ) : (
              <GroupMembersSection
                members={groupDetail?.members}
                isLoading={false}
                onFixtureClick={setSelectedFixture}
              />
            )}
          </div>
        )}
        </SheetBody>
      </SheetContent>

      {/* Nested fixture modal */}
      <FixtureDetailModal
        fixtureKey={selectedFixture}
        onClose={() => setSelectedFixture(null)}
      />
    </Sheet>
  )
}
