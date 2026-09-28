import { Badge } from '@/components/ui/badge'

interface GroupMembershipSectionProps {
  groups: string[]
  onGroupClick: (groupName: string) => void
}

export function GroupMembershipSection({
  groups,
  onGroupClick,
}: GroupMembershipSectionProps) {
  if (groups.length === 0) {
    return null
  }

  return (
    <div className="pt-3 border-t">
      <h4 className="text-sm font-medium text-muted-foreground mb-2">Groups</h4>
      <div className="flex flex-wrap gap-1">
        {groups.map((groupName) => (
          <Badge
            key={groupName}
            variant="secondary"
            className="cursor-pointer hover:bg-secondary/80"
            onClick={() => onGroupClick(groupName)}
          >
            {groupName}
          </Badge>
        ))}
      </div>
    </div>
  )
}
