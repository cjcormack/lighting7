import {
  Button,
  Input,
  Label,
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  Textarea,
} from 'lighting-desk-ui'

// A sheet is the desk's editing surface: header, scrollable body, footer with
// Cancel + Save. Rendered open, uncontrolled, so the card shows the composed form.
export const EditForm = () => (
  <div className="relative h-[600px] w-full">
    <Sheet defaultOpen modal={false}>
      <SheetContent className="flex flex-col sm:max-w-md" onInteractOutside={(e) => e.preventDefault()}>
        <SheetHeader>
          <SheetTitle>Cue properties</SheetTitle>
          <SheetDescription>Cue 12 · Act 1 — Opening</SheetDescription>
        </SheetHeader>
        <SheetBody>
          <div className="space-y-2">
            <Label htmlFor="cue-name">Name</Label>
            <Input id="cue-name" defaultValue="Band walk-on" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="cue-number">Cue #</Label>
              <Input id="cue-number" defaultValue="12" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="cue-fade">Fade (s)</Label>
              <Input id="cue-fade" type="number" defaultValue="8" />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="cue-notes">Notes</Label>
            <Textarea
              id="cue-notes"
              defaultValue="Hold until the first downbeat, then GO on the drummer's count."
            />
          </div>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline">Cancel</Button>
          <Button>Save</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  </div>
)
