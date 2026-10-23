import { useMemo } from 'react'
import { Crosshair } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { templateTargetsOf, type WriteTarget } from '@/components/fixtures-list/rowModel'
import { useProgrammerScope } from '@/components/programmer/ProgrammerScope'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useCurrentProjectQuery } from '@/store/projects'
import { StageAimControls, isAimable } from './StageAimControls'

/**
 * *Aim…* in a position cell's popup footer (venue-visit plan S13; the `tools` slot apply-model plan
 * §4.3 reserved for it): the Stage view's aim controls over the cell batch's heads — a point typed,
 * or a region's centre at head height — sent to `POST /programmer/aim`, where the desk solves each
 * head's pan and tilt. A popover over the cell's own popup.
 *
 * Aim writes the live show's Local, so the door is refused, with the reason, on a project that is not
 * on the rig, in Output, in a focused layer (whose edits go to the layer) and where no head here
 * annotates its pan and tilt in degrees.
 */
export function PositionAimTool({ projectId, targets }: { projectId: number; targets: readonly WriteTarget[] }) {
  const scope = useProgrammerScope()
  const { data: current } = useCurrentProjectQuery()
  const { fixtureByKey } = useFixtureLookup()
  // The heads the desk can aim, as the Stage view offers them — a par or a cell in the batch is not.
  const keys = useMemo(
    () => templateTargetsOf(targets).map((t) => t.key).filter((key) => isAimable(fixtureByKey.get(key))),
    [targets, fixtureByKey],
  )

  const refusal =
    current?.id !== projectId
      ? 'Aim writes the live show, and this project is not on the rig'
      : scope?.kind === 'output'
        ? 'Output is a read of the cook — switch to Local to aim these heads'
        : scope?.kind === 'layer'
          ? 'Aim writes Local — switch to Local to aim these heads'
          : keys.length > 0
            ? null
            : 'No head here annotates its pan and tilt in degrees'

  const trigger = (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="h-7 text-xs"
      disabled={refusal != null}
      title={refusal ?? 'Aim these heads at a point on the stage'}
    >
      {/* Its word on the name alone: at the popup's 288px the footer folds Aim… and Fan… to
          their icons and keeps Save as palette…'s, the colour footer's rule (venue-visit plan S12). */}
      <Crosshair className="size-3.5" />
      <span className="sr-only">Aim…</span>
    </Button>
  )
  if (refusal != null) return trigger
  return (
    <Popover>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      {/* Its keys stay here: the cell's editor round it would take Enter as done and close. */}
      <PopoverContent align="end" className="w-80" data-testid="position-aim-popover" onKeyDown={(e) => e.stopPropagation()}>
        <StageAimControls projectId={projectId} fixtureKeys={keys} />
      </PopoverContent>
    </Popover>
  )
}
