import { GroupMembershipSection } from 'lighting-desk-ui'

// A fixture's group membership as clickable badges, under a top rule — the
// section sits at the foot of the fixture detail sheet.
export const FewGroups = () => (
  <div className="w-72">
    <GroupMembershipSection groups={['Front wash', 'Warm']} onGroupClick={() => {}} />
  </div>
)

export const ManyGroups = () => (
  <div className="w-80">
    <GroupMembershipSection
      groups={['Front wash', 'Back wash', 'Stage left', 'Warm', 'Movers', 'Band', 'Specials', 'FOH']}
      onGroupClick={() => {}}
    />
  </div>
)

export const UnderFixtureDetails = () => (
  <div className="w-72 space-y-3">
    <div>
      <div className="text-sm font-medium">Front wash 3</div>
      <div className="text-xs text-muted-foreground">RGBWA Par · Universe 1 · DMX 41–48</div>
    </div>
    <GroupMembershipSection groups={['Front wash', 'Warm', 'FOH']} onGroupClick={() => {}} />
  </div>
)
