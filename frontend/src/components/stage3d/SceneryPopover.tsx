import type { RefObject } from 'react'
import { Pencil, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { useSceneryScope } from '@/components/programmer/ProgrammerSceneryList'
import { MovesWithList } from '@/components/scenery/MovesWithList'
import { SceneryControl } from '@/components/scenery/SceneryControl'
import { ElementIcon } from '@/components/scenery/SceneryReadout'
import { sceneryKindLabel } from '@/lib/scenery'
import { useProjectQuery } from '@/store/projects'
import type { StageElementDto } from '@/api/stageElementApi'

/** Why a piece on another project's stage cannot be moved from here: the programmer is the live show's. */
export const NOT_LIVE_REASON = 'Scenery moves on the live project only'

/**
 * A clicked piece of scenery, moved from the Stage view (scenery-programmer plan D11, D17): the
 * piece's `SceneryControl` writing the programmer's scenery — `programmer.setScenery` at this
 * window's programmer fade, the same overlay the rail's Scenery band writes (`useSceneryScope`'s
 * Local arm; there is no programmer scope on the Stage view) — *Release* while the programmer holds
 * it, *Edit element…* where the route offers Edit, and *Moves with*, what else moves it. The control
 * says what holds the piece (`scenery.state`'s `source`, the top tier only), and in Blind shows the
 * staged state, as the band does.
 *
 * **It opens through `EditorSurface`**: a `w-72` popover on a desk or an iPad, anchored at the
 * piece's projected box (beside it, not over it) and following it as the camera orbits or the piece flies
 * ([anchorRef], a virtual element `Stage3D` feeds from the label layer's projection, and
 * `followAnchor`), and a bottom sheet on an upright phone. A drag on the canvas orbits rather than
 * closes it ([keepOpenWithin]); the canvas's own click retargets or closes it.
 *
 * Never mounted by a `render_view` capture: `Stage3D` draws it only on screen.
 */
export function SceneryPopover({
  projectId,
  element,
  open,
  onOpenChange,
  anchorRef,
  keepOpenWithin,
  onEditElement,
}: {
  projectId: number
  /** The element as stored — its presets and range are its own Z and trim, never a moved state. */
  element: StageElementDto
  open: boolean
  onOpenChange: (open: boolean) => void
  anchorRef: RefObject<{ getBoundingClientRect(): DOMRect } | null>
  keepOpenWithin: RefObject<HTMLElement | null>
  /** Open the element's form in Edit; absent where the window has no Edit (below tablet width). */
  onEditElement?: () => void
}) {
  return (
    <EditorSurface
      open={open}
      onOpenChange={onOpenChange}
      title={element.name}
      align="center"
      anchorRef={anchorRef}
      followAnchor
      keepOpenWithin={keepOpenWithin}
      contentClassName="w-72 p-3"
    >
      <SceneryPopoverBody projectId={projectId} element={element} onEditElement={onEditElement} onClose={() => onOpenChange(false)} />
    </EditorSurface>
  )
}

function SceneryPopoverBody({
  projectId,
  element,
  onEditElement,
  onClose,
}: {
  projectId: number
  element: StageElementDto
  onEditElement?: () => void
  onClose: () => void
}) {
  const scope = useSceneryScope(projectId)
  const { data: project } = useProjectQuery(projectId)
  const live = project?.isCurrent === true
  const row = scope.rowOf(element)
  const canWrite = live && scope.arm.kind === 'local'
  return (
    <div className="space-y-3" data-scenery-popover={element.uuid}>
      <SceneryControl
        element={element}
        state={row.state}
        disabled={!canWrite}
        readout={row.readout}
        onWrite={(patch) => scope.write(element.uuid, patch)}
        labelEnd={
          <>
            <ElementIcon element={element} />
            <span className="min-w-0 truncate text-[10px] text-muted-foreground">{sceneryKindLabel(element)}</span>
          </>
        }
      />
      {!live && <EditorReadout>{NOT_LIVE_REASON}.</EditorReadout>}
      {(row.held && canWrite) || onEditElement != null ? (
        <div className="flex items-center gap-2">
          {row.held && canWrite && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-7"
              title={`Let ${element.name} go: it goes back to what the show holds it at`}
              onClick={() => scope.release(element.uuid)}
            >
              <X className="mr-1 size-3.5" />
              Release
            </Button>
          )}
          <span className="flex-1" />
          {onEditElement != null && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-7"
              onClick={() => {
                onClose()
                onEditElement()
              }}
            >
              <Pencil className="mr-1 size-3.5" />
              Edit element…
            </Button>
          )}
        </div>
      ) : null}
      <div className="space-y-1 border-t pt-2">
        <EditorLabel>Moves with</EditorLabel>
        <MovesWithList projectId={projectId} element={element} />
      </div>
    </div>
  )
}
