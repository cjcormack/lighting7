import { useEffect, useState } from 'react'
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet'
import { useGroupListQuery } from '../../store/groups'
import { FixtureSheet } from '../fixtureSheet/FixtureSheet'
import { FixtureDetailModal } from '../groups/FixtureDetailModal'

interface GroupDetailModalProps {
  groupName: string | null
  onClose: () => void
}

/**
 * The **group sheet** (fixture-fx-sheets plan D1, session 4): the fixture sheet's body on a group, in
 * the same 512px Radix sheet as a fixture's pop-up — *Values · Members*, Locate, Park and Release,
 * the head strip of members, rows that write the group, and the FX tray. It replaced the group
 * visualisers and their Edit toggle, which wrote one raw channel per member for a slider.
 *
 * A member opens its own sheet over this one, where a group's effect reads *via <group>*.
 */
export function GroupDetailModal({ groupName, onClose }: GroupDetailModalProps) {
  const { data: groupList } = useGroupListQuery()
  const group = groupName ? groupList?.find((g) => g.name === groupName) : null
  const [selectedFixture, setSelectedFixture] = useState<string | null>(null)

  // A member's sheet belongs to the group it was opened from.
  useEffect(() => {
    setSelectedFixture(null)
  }, [groupName])

  return (
    <Sheet open={groupName !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="flex flex-col gap-0 p-0 sm:max-w-lg">
        <SheetDescription className="sr-only">The group&apos;s values, its members and its effects</SheetDescription>
        {group ? (
          // Keyed by group so the view and the open row reset when the sheet moves to another.
          <FixtureSheet key={group.name} group={group} host="popup" titleComponent={SheetTitle} onOpenMember={setSelectedFixture} />
        ) : (
          <SheetTitle className="p-4">{groupName ?? 'Group'}</SheetTitle>
        )}
      </SheetContent>

      {/* A member's own sheet, over the group's. */}
      <FixtureDetailModal fixtureKey={selectedFixture} onClose={() => setSelectedFixture(null)} />
    </Sheet>
  )
}
