import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { CuePropsPane } from './CuePropsPane'
import type { Cue } from '@/api/cuesApi'

/**
 * A cue's metadata, in a drawer.
 *
 * Everything about a cue that a *value grid* cannot express: its number, name, notes, fade, curve,
 * auto-advance, palette and script hooks. Session 2a made the cue surface the same grid the
 * programmer uses, which answers "what does this cue do to the rig" — and left these homeless,
 * because none of them is a value on a head.
 *
 * **`CuePropsPane` is reused rather than rebuilt**, which is why this file is thin. The three-pane
 * cue editor went, but that pane was not the problem with it: the problem was that Targets and
 * Layers restated the same state a value grid and a layer stack already express. A per-field
 * autosaving form over a dozen properties is exactly right for those properties, and deleting a
 * working one to retype the same fields into a sheet would have been churn dressed as progress.
 * It keeps its own per-cue `SaveStatusIndicator`, so the footer here is only Close.
 */
export function CuePropertiesSheet({
  cue,
  projectId,
  open,
  onOpenChange,
}: {
  cue: Cue | null
  projectId: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>
            Cue properties
            {cue?.name ? ` — ${cue.name}` : ''}
          </SheetTitle>
        </SheetHeader>
        {/* `space-y-0 p-0`: the pane manages its own padding and section spacing, per the sheet
            conventions in CLAUDE.md. */}
        <SheetBody className="space-y-0 p-0">
          {cue && <CuePropsPane cue={cue} projectId={projectId} />}
        </SheetBody>
        {/* Close, not Save. Every field in here autosaves as it is left — a Save button would
            imply the others had not landed. */}
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
