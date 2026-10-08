import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet'
import { useFixtureListQuery } from '../../store/fixtures'
import { FixtureSheet } from '../fixtureSheet/FixtureSheet'

interface FixtureDetailModalProps {
  fixtureKey: string | null
  onClose: () => void
}

/**
 * The fixture sheet's **pop-up** host: the 512px Radix sheet the fixture list, the programmer
 * grid's Info, the overview panel and Channels open. It guards nothing — every change on it is
 * already on stage — and has no Edit / Done (fixture-fx-sheets plan D2): it is live while the desk
 * is connected, read-only only offline.
 */
export function FixtureDetailModal({ fixtureKey, onClose }: FixtureDetailModalProps) {
  const { data: fixtureList } = useFixtureListQuery()
  const fixture = fixtureKey ? fixtureList?.find((f) => f.key === fixtureKey) : null

  return (
    <Sheet open={fixtureKey !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="flex flex-col gap-0 p-0 sm:max-w-lg">
        <SheetDescription className="sr-only">The fixture&apos;s values, its channels and its effects</SheetDescription>
        {fixture ? (
          // Keyed by fixture so the open row and the view reset when the sheet moves to another.
          <FixtureSheet key={fixture.key} fixture={fixture} host="popup" titleComponent={SheetTitle} />
        ) : (
          <SheetTitle className="p-4">Fixture</SheetTitle>
        )}
      </SheetContent>
    </Sheet>
  )
}
