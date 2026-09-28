import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { useFixtureListQuery } from '../../store/fixtures'
import { FixtureDetailView } from '../fixtures/FixtureDetailView'

interface FixtureDetailModalProps {
  fixtureKey: string | null
  onClose: () => void
  /** When provided, uses this edit state and hides the Edit button. */
  isEditing?: boolean
}

export function FixtureDetailModal({ fixtureKey, onClose, isEditing }: FixtureDetailModalProps) {
  const { data: fixtureList } = useFixtureListQuery()
  const fixture = fixtureKey ? fixtureList?.find((f) => f.key === fixtureKey) : null

  return (
    <Sheet open={fixtureKey !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        {/* Keyed by fixture so edit/view state resets when switching fixtures. */}
        <FixtureDetailView
          key={fixtureKey ?? 'none'}
          fixture={fixture}
          isEditing={isEditing}
          titleComponent={SheetTitle}
        />
      </SheetContent>
    </Sheet>
  )
}
