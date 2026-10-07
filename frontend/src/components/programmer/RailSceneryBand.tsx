import { forwardRef, memo, useMemo, useState } from 'react'
import { Blinds } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { cn } from '@/lib/utils'
import { useStageElementListQuery } from '@/store/stageElements'
import { ProgrammerSceneryList, SceneryElementRow, useSceneryScope } from './ProgrammerSceneryList'

/** The rail's band label, as `ProgrammerRail`'s `LABEL_CLASS` draws every band's. */
const LABEL_CLASS =
  'inline-flex shrink-0 items-center gap-1 text-[9px] font-bold uppercase tracking-[0.1em] text-muted-foreground'

/**
 * The rail's **Scenery** band (scenery-programmer plan D1, D4; session 2, Chris's call over a
 * docked-only tab and an action-bar chip): the pieces the programmer holds, each its
 * `SceneryElementRow` with a release ×, and *All scenery…*, which opens every element through
 * `EditorSurface`. It is **the top of the rail body**, above the values, because the programmer's
 * scenery is the resolver's top tier (D2) — and because it is a band of the body and not a tab, it
 * is there in every arm the rail has: docked, the overlay, and the phone's bottom sheet, the way
 * the effects band is. Its count rides the strip and the phone handle beside the layers' and the
 * effects' (`ProgrammerRail`).
 *
 * **What it lists follows the scope** (D4): the programmer's own scenery in Local — read-only, with
 * *Output is read-only*, in Output and over a template layer, since it is still what Clear will drop
 * — and a focused Look's own scenery with a Look layer focused, written through the Look's scenery
 * `PUT`.
 *
 * Memoised on `projectId`: the rail body re-renders on every selection change, and the band has its
 * own subscriptions for everything it draws.
 */
export const RailSceneryBand = memo(
  forwardRef<HTMLDivElement, { projectId: number }>(function RailSceneryBand({ projectId }, ref) {
    const { data: elements } = useStageElementListQuery(projectId)
    const scope = useSceneryScope(projectId)
    const [open, setOpen] = useState(false)
    const heldElements = useMemo(
      () =>
        (elements ?? [])
          .filter((e) => scope.held.has(e.uuid))
          .sort((a, b) => (a.layer === b.layer ? a.name.localeCompare(b.name) : a.layer === 'VENUE' ? -1 : 1)),
      [elements, scope.held],
    )
    const readOnly = scope.arm.kind === 'readOnly'
    const lookName = scope.arm.kind === 'look' ? scope.arm.name : null

    return (
      <section ref={ref} data-rail-scenery aria-label="Scenery" className="flex flex-col gap-1.5">
        <div className="flex items-center gap-1.5 px-0.5">
          <span className={LABEL_CLASS}>
            <Blinds className="size-3" />
            Scenery
          </span>
          <span
            className="min-w-0 truncate text-[9.5px] text-muted-foreground"
            title="The programmer's scenery is drawn above every Look, cue and set, and is never output. Clear drops it on the Clear fade."
          >
            {lookName != null ? `in ${lookName}` : `${heldElements.length} held · top wins`}
          </span>
          <span className="flex-1" />
          <EditorSurface
            open={open}
            onOpenChange={setOpen}
            title="Scenery"
            align="end"
            contentClassName="flex max-h-[min(36rem,70vh)] w-72 flex-col p-0"
            trigger={
              <Button
                variant="ghost"
                size="sm"
                className="h-[22px] shrink-0 px-1.5 text-[10px]"
                data-rail-scenery-all
                disabled={(elements ?? []).length === 0}
                title={(elements ?? []).length === 0 ? 'The stage has no scenery yet — place some from the Stage view.' : 'Every scene element, to move or hold'}
              >
                All scenery…
              </Button>
            }
          >
            <ProgrammerSceneryList projectId={projectId} scope={scope} />
          </EditorSurface>
        </div>
        {readOnly && heldElements.length > 0 && (
          <p className="px-0.5 text-[10px] text-muted-foreground">{scope.arm.kind === 'readOnly' && scope.arm.reason}</p>
        )}
        {heldElements.length === 0 ? (
          <p className={cn('px-0.5 text-[10px] text-muted-foreground')}>
            {lookName != null ? `${lookName} holds no scenery.` : 'Nothing held.'}
          </p>
        ) : (
          heldElements.map((element) => <SceneryElementRow key={element.uuid} element={element} scope={scope} />)
        )}
      </section>
    )
  }),
)
