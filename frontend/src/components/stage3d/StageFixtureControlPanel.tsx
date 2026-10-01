import { Loader2, X } from 'lucide-react'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { FixtureDetailView } from '@/components/fixtures/FixtureDetailView'
import { StageAimControls, isAimable } from './StageAimControls'
import { StageFocusPanel } from './StageFocusPanel'
import { useVisiblePatchListQuery } from '@/store/patches'
import { useLanternIndex } from '@/hooks/useLanternIndex'

interface StageFixtureControlPanelProps {
  /** Selected patch key — equals the fixture key (see useFixtureLookup). */
  patchKey: string
  /** The project the fixture is patched in — what an aim is addressed to. */
  projectId: number
  /** Whether that project is the live one; aiming writes the programmer, so only then. */
  canAim: boolean
  onClose: () => void
}

/**
 * Docked, sheet-styled fixture control panel shown when a fixture is selected
 * on the stage in view mode. Reuses the same live-control view as the
 * `FixtureDetailModal` (colour, dimmer, position, channels…) — always editable,
 * no edit button — but docked inline rather than overlaying the page. A moving head also gets
 * "Aim at point" underneath, pinned to the panel's foot, and every fixture a **Focus** view
 * (`StageFocusPanel`): a conventional's lanterns focused on the stage, or what a DMX fixture's own
 * channels drive.
 */
export function StageFixtureControlPanel({ patchKey, projectId, canAim, onClose }: StageFixtureControlPanelProps) {
  const { fixtureByKey, typeByKey } = useFixtureLookup()
  const fixture = fixtureByKey.get(patchKey)
  const { data: patches } = useVisiblePatchListQuery(projectId)
  const patch = patches?.find((p) => p.key === patchKey)
  const lanterns = useLanternIndex()
  const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined

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
        <>
          <div className="flex min-h-0 flex-1 flex-col">
            <FixtureDetailView
              key={patchKey}
              fixture={fixture}
              isEditing
              focus={
                patch ? (
                  <StageFocusPanel
                    key={patch.id}
                    projectId={projectId}
                    patch={patch}
                    fixture={fixture}
                    fixtureType={fixtureType}
                    lanterns={lanterns}
                  />
                ) : undefined
              }
            />
          </div>
          {canAim && isAimable(fixture) && (
            <div className="border-t p-4">
              <StageAimControls key={patchKey} projectId={projectId} fixtureKeys={[patchKey]} />
            </div>
          )}
        </>
      ) : (
        <div className="flex flex-1 items-center justify-center">
          <Loader2 className="size-5 animate-spin text-muted-foreground" />
        </div>
      )}
    </aside>
  )
}
