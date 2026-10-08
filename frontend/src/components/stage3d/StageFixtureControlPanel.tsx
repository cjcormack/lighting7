import { Loader2, X } from 'lucide-react'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { FixtureSheet } from '@/components/fixtureSheet/FixtureSheet'
import { StageAimControls, isAimable } from './StageAimControls'
import { StageFocusPanel } from './StageFocusPanel'
import { useVisiblePatchListQuery } from '@/store/patches'
import { useLanternIndex } from '@/hooks/useLanternIndex'

interface StageFixtureControlPanelProps {
  /** Selected patch key — equals the fixture key (see useFixtureLookup). */
  patchKey: string
  /** The project the fixture is patched in — what an aim is addressed to. */
  projectId: number
  /** Whether that project is the live one; aiming and *Focus here* write the programmer, so only then. */
  canAim: boolean
  onClose: () => void
}

/**
 * The fixture sheet's **Stage** host (fixture-fx-sheets plan §4): docked at 380px beside the canvas
 * when a fixture is selected in view mode. The same body as the pop-up — live while the desk is
 * connected, no Edit / Done — with the Stage's two extras: a **Focus** tab (`StageFocusPanel`: a
 * conventional's lanterns focused on the stage, or what a DMX head's own channels drive), and
 * *Aim at point* as an **Aim…** popover on the Position row (D14's half), where it used to be a
 * block pinned under the panel. The panel's foot is the FX tray's alone.
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
        <div className="flex min-h-0 flex-1 flex-col">
          <FixtureSheet
            key={patchKey}
            fixture={fixture}
            host="stage"
            focus={
              patch ? (
                <StageFocusPanel
                  key={patch.id}
                  projectId={projectId}
                  patch={patch}
                  fixture={fixture}
                  fixtureType={fixtureType}
                  lanterns={lanterns}
                  canFocus={canAim}
                />
              ) : undefined
            }
            aim={
              canAim && isAimable(fixture) ? (
                <StageAimControls
                  key={patchKey}
                  projectId={projectId}
                  fixtureKeys={[patchKey]}
                />
              ) : undefined
            }
          />
        </div>
      ) : (
        <div className="flex flex-1 items-center justify-center">
          <Loader2 className="size-5 animate-spin text-muted-foreground" />
        </div>
      )}
    </aside>
  )
}
