import { useState } from 'react'
import { Loader2, X } from 'lucide-react'
import { SheetTitle } from '@/components/ui/sheet'
import { useEditorForm } from '@/components/editor/EditorSurface'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { FixtureSheet } from '@/components/fixtureSheet/FixtureSheet'
import { PhoneSheet } from '@/components/fixtureSheet/PhoneSheet'
import type { SheetHost } from '@/components/fixtureSheet/sheetContext'
import { StageAimControls, isAimable } from './StageAimControls'
import { StageFocusPanel } from './StageFocusPanel'
import { useVisiblePatchListQuery } from '@/store/patches'
import { useLanternIndex } from '@/hooks/useLanternIndex'

interface StageFixtureControlsProps {
  /** The selected patch key — equals the fixture key (see useFixtureLookup). Null: nothing selected. */
  patchKey: string | null
  /** The project the fixture is patched in — what an aim is addressed to. */
  projectId: number
  /** Whether that project is the live one; aiming and *Focus here* write the programmer, so only then. */
  canAim: boolean
  onClose: () => void
}

/**
 * The Stage view's fixture sheet, in whichever host the screen takes (fixture-fx-sheets plan §4,
 * D14): the **docked 380px panel** where the cell editor would be a popover (a desk or a tablet),
 * and on a phone — `useEditorForm`'s two touch forms — the **phone** host, a bottom sheet held
 * upright and the right-hand sheet held landscape (`PhoneSheet`). Before session 6 a phone drew
 * nothing at all on a fixture tap. The form is the question, never a `sm:` width: a landscape phone
 * is wider than `sm` and is still a finger on glass.
 *
 * Both hosts are the same body (`StageFixtureSheet`) with the Stage's two extras, Focus and *Aim…*.
 */
export function StageFixtureControls({ patchKey, projectId, canAim, onClose }: StageFixtureControlsProps) {
  const form = useEditorForm()
  // What the phone sheet draws: the fixture, held through the close — the selection clears at once,
  // and Radix animates the sheet out for 300ms, which would otherwise draw the bare fallback.
  const [shown, setShown] = useState<string | null>(patchKey)
  if (patchKey != null && patchKey !== shown) setShown(patchKey)

  if (form === 'popover') {
    return patchKey != null ? (
      <StageFixtureControlPanel patchKey={patchKey} projectId={projectId} canAim={canAim} onClose={onClose} />
    ) : null
  }
  return (
    <PhoneSheet
      open={patchKey != null}
      onClose={onClose}
      form={form}
      modal={false}
      description="The fixture's values, its channels, its focus and its effects"
    >
      {shown != null ? (
        <StageFixtureSheet patchKey={shown} projectId={projectId} canAim={canAim} host="phone" />
      ) : (
        <SheetTitle className="p-4">Fixture</SheetTitle>
      )}
    </PhoneSheet>
  )
}

/**
 * The fixture sheet's **Stage** host (fixture-fx-sheets plan §4): docked at 380px beside the canvas
 * when a fixture is selected in view mode. The same body as the pop-up — live while the desk is
 * connected, no Edit / Done — with the Stage's two extras: a **Focus** tab (`StageFocusPanel`: a
 * conventional's lanterns focused on the stage, or what a DMX head's own channels drive), and
 * *Aim at point* as an **Aim…** popover on the Position row (D14's half), where it used to be a
 * block pinned under the panel. The panel's foot is the FX tray's alone.
 */
export function StageFixtureControlPanel({
  patchKey,
  projectId,
  canAim,
  onClose,
}: StageFixtureControlsProps & { patchKey: string }) {
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
      <div className="flex min-h-0 flex-1 flex-col">
        <StageFixtureSheet patchKey={patchKey} projectId={projectId} canAim={canAim} host="stage" />
      </div>
    </aside>
  )
}

/**
 * One fixture's sheet with the Stage view's extras, in either Stage host. The phone keeps both: the
 * Focus tab lands a beam on the canvas the sheet sits over, and *Aim…* opens its popover over the
 * sheet — only on the live project, as on the panel.
 */
function StageFixtureSheet({
  patchKey,
  projectId,
  canAim,
  host,
}: {
  patchKey: string
  projectId: number
  canAim: boolean
  host: Extract<SheetHost, 'stage' | 'phone'>
}) {
  const { fixtureByKey, typeByKey } = useFixtureLookup()
  const fixture = fixtureByKey.get(patchKey)
  const { data: patches } = useVisiblePatchListQuery(projectId)
  const patch = patches?.find((p) => p.key === patchKey)
  const lanterns = useLanternIndex()
  const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined

  if (fixture == null) {
    return (
      <div className="flex flex-1 items-center justify-center p-4">
        {/* A Radix sheet needs its title even while the fixture loads. */}
        {host === 'phone' && <SheetTitle className="sr-only">Fixture</SheetTitle>}
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    )
  }
  return (
    <FixtureSheet
      key={patchKey}
      fixture={fixture}
      host={host}
      titleComponent={host === 'phone' ? SheetTitle : undefined}
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
          <StageAimControls key={patchKey} projectId={projectId} fixtureKeys={[patchKey]} />
        ) : undefined
      }
    />
  )
}
