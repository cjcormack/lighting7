import { Loader2, X } from 'lucide-react'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { FixtureDetailView } from '@/components/fixtures/FixtureDetailView'

interface StageFixtureControlPanelProps {
  /** Selected patch key — equals the fixture key (see useFixtureLookup). */
  patchKey: string
  onClose: () => void
}

/**
 * Docked, sheet-styled fixture control panel shown when a fixture is selected
 * on the stage in view mode. Reuses the same live-control view as the
 * `FixtureDetailModal` (colour, dimmer, position, channels…) — always editable,
 * no edit button — but docked inline rather than overlaying the page.
 */
export function StageFixtureControlPanel({ patchKey, onClose }: StageFixtureControlPanelProps) {
  const { fixtureByKey } = useFixtureLookup()
  const fixture = fixtureByKey.get(patchKey)

  return (
    <aside className="relative flex w-full flex-col border-l bg-background shadow-lg sm:w-[380px]">
      <button
        type="button"
        onClick={onClose}
        aria-label="Close fixture controls"
        className="absolute right-4 top-4 z-10 rounded-xs opacity-70 transition-opacity hover:opacity-100"
      >
        <X className="size-4" />
      </button>
      {fixture ? (
        <FixtureDetailView key={patchKey} fixture={fixture} isEditing />
      ) : (
        <div className="flex flex-1 items-center justify-center">
          <Loader2 className="size-5 animate-spin text-muted-foreground" />
        </div>
      )}
    </aside>
  )
}
