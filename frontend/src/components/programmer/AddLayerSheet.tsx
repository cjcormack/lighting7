import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { LayerPicker } from './LayerPicker'
import type { CueLayer, CueTarget } from '@/api/cuesApi'

interface AddLayerSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  defaultTarget?: CueTarget | null
  /** Several targets at once — the cue editor's Values selection. Wins over [defaultTarget]. */
  defaultTargets?: readonly CueTarget[]
  onAdd: (layer: CueLayer) => void
  /**
   * Passed straight to [LayerPicker]. The programmer sets it false — it has no playback to delay
   * against, and it drops the timing fields on the way out.
   */
  allowTiming?: boolean
  /** Passed straight to [LayerPicker]: open on one library. */
  kind?: 'look' | 'template'
}

/**
 * Right-hand sheet wrapping `LayerPicker`.
 *
 * The add-layer sheet for a cue's layers, unmounted until the cue editor's *+ Look* and *+ Palette*
 * host it (show-cue-editing plan session 6). The programmer's door is the one picker, which presses
 * onto the selection instead (`ApplyPicker`, apply-model plan §4.6).
 */
export function AddLayerSheet({
  open,
  onOpenChange,
  projectId,
  defaultTarget,
  defaultTargets,
  onAdd,
  allowTiming = true,
  kind,
}: AddLayerSheetProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-lg flex flex-col p-0">
        <SheetHeader className="sr-only">
          <SheetTitle>{kind === 'template' ? 'Add palette layer' : 'Add layer'}</SheetTitle>
        </SheetHeader>
        {open && (
          <LayerPicker
            projectId={projectId}
            preselectedTargets={defaultTargets ?? (defaultTarget ? [defaultTarget] : undefined)}
            onConfirm={onAdd}
            onCancel={() => onOpenChange(false)}
            allowTiming={allowTiming}
            kind={kind}
          />
        )}
      </SheetContent>
    </Sheet>
  )
}
