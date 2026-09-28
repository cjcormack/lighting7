import React, { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Loader2 } from 'lucide-react'
import { useGroupQuery, useGroupPropertiesQuery } from '../../store/groups'
import { GroupPropertyVisualizer, GroupVirtualDimmerSlider } from '../fixtures/GroupPropertyVisualizers'
import { GroupMembersSection } from './GroupMembersSection'
import { LocateButton } from '../fixtures/LocateButton'
import { FxBadge } from '../fx/FxBadge'
import { FxSection } from '../fx/FxSection'
import { BoundControlBadge } from '../surfaces/BoundControlBadge'
import { categoriseProperties } from '@/hooks/useTargetProperties'
import type { GroupMember, GroupSummary, GroupPropertyDescriptor, GroupColourPropertyDescriptor } from '../../api/groupsApi'
import { useVisibleGroupMembers } from './useVisibleGroupMembers'

interface GroupCardProps {
  group: GroupSummary
  onFixtureClick: (fixtureKey: string) => void
}

function GroupCardInner({ group, onFixtureClick }: GroupCardProps) {
  const [isEditing, setIsEditing] = useState(false)
  const { data: groupDetail, isLoading: membersLoading } = useGroupQuery(group.name)
  const { data: properties, isLoading: propertiesLoading } = useGroupPropertiesQuery(group.name)

  return (
    <Card>
      <GroupCardHeader
        group={group}
        members={groupDetail?.members}
        isEditing={isEditing}
        onToggleEdit={() => setIsEditing(!isEditing)}
      />
      <CardContent className="space-y-4">
        {/* Inline properties section */}
        <GroupPropertiesSection
          groupName={group.name}
          properties={properties}
          isLoading={propertiesLoading}
          isEditing={isEditing}
        />

        {/* Effects section */}
        <FxSection group={group} />

        {/* Compact fixture member grid */}
        <GroupMembersSection
          members={groupDetail?.members}
          isLoading={membersLoading}
          onFixtureClick={onFixtureClick}
        />
      </CardContent>
    </Card>
  )
}

export const GroupCard = React.memo(GroupCardInner)

function GroupCardHeader({
  group,
  members,
  isEditing,
  onToggleEdit,
}: {
  group: GroupSummary
  members: GroupMember[] | undefined
  isEditing: boolean
  onToggleEdit: () => void
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
          <Button
            variant={isEditing ? 'default' : 'outline'}
            size="sm"
            onClick={onToggleEdit}
          >
            {isEditing ? 'Done' : 'Edit'}
          </Button>
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

export function GroupPropertiesSection({
  groupName,
  properties,
  isLoading,
  isEditing,
}: {
  groupName?: string
  properties: GroupPropertyDescriptor[] | undefined
  isLoading: boolean
  isEditing: boolean
}) {
  if (isLoading) {
    return (
      <div className="flex justify-center py-2">
        <Loader2 className="size-4 animate-spin" />
      </div>
    )
  }

  if (!properties || properties.length === 0) {
    return null
  }

  const grouped = categoriseProperties(properties)

  // Virtual dimmer: group has colour but no dimmer
  const hasRealDimmer = grouped.dimmer.length > 0
  const virtualDimmerColourProp = !hasRealDimmer
    ? grouped.colour.find((p) => p.type === 'colour') as GroupColourPropertyDescriptor | undefined
    : undefined

  const virtualBadge = virtualDimmerColourProp ? (
    <Badge
      variant="outline"
      className="ml-1 text-[10px] leading-tight px-1 py-0 text-muted-foreground align-middle"
    >
      Virtual
    </Badge>
  ) : null

  const badgeFor = (name: string) =>
    groupName ? (
      <BoundControlBadge
        className="inline-flex ml-1 align-middle"
        match={{ type: "groupProperty", groupName, propertyName: name }}
      />
    ) : null

  return (
    <div className="space-y-1">
      {/* Colour properties first (most visually prominent) */}
      {grouped.colour.map((prop) => (
        <GroupPropertyVisualizer
          key={prop.name}
          property={prop}
          groupName={groupName}
          isEditing={isEditing}
          nameExtra={badgeFor(prop.name)}
        />
      ))}

      {/* Position properties */}
      {grouped.position.map((prop) => (
        <GroupPropertyVisualizer
          key={prop.name}
          property={prop}
          groupName={groupName}
          isEditing={isEditing}
          nameExtra={badgeFor(prop.name)}
        />
      ))}

      {/* Dimmer properties */}
      {grouped.dimmer.map((prop) => (
        <GroupPropertyVisualizer
          key={prop.name}
          property={prop}
          groupName={groupName}
          isEditing={isEditing}
          nameExtra={badgeFor(prop.name)}
        />
      ))}

      {/* Virtual dimmer (colour but no real dimmer) */}
      {virtualDimmerColourProp && (
        <GroupVirtualDimmerSlider
          colourProp={virtualDimmerColourProp}
          isEditing={isEditing}
          nameExtra={virtualBadge}
        />
      )}

      {/* Other slider properties */}
      {grouped.slider.map((prop) => (
        <GroupPropertyVisualizer
          key={prop.name}
          property={prop}
          groupName={groupName}
          isEditing={isEditing}
          nameExtra={badgeFor(prop.name)}
        />
      ))}

      {/* Setting properties */}
      {grouped.setting.map((prop) => (
        <GroupPropertyVisualizer
          key={prop.name}
          property={prop}
          groupName={groupName}
          isEditing={isEditing}
          nameExtra={badgeFor(prop.name)}
        />
      ))}
    </div>
  )
}

