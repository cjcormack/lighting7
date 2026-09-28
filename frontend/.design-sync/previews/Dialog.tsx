import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  Input,
  Label,
} from 'lighting-desk-ui'

// A dialog is a centred modal. The desk uses it for confirmations and short
// forms (Record into a stack, rename a group). Rendered open, uncontrolled, so
// the card shows the overlay, the content panel with its close glyph, and the
// trigger button it belongs to underneath.
export const RecordCue = () => (
  <div className="relative h-[520px] w-full p-6">
    <Dialog defaultOpen>
      <DialogTrigger asChild>
        <Button>Record cue…</Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Record into Act 1</DialogTitle>
          <DialogDescription>
            Captures the programmer's 14 fixtures and 2 running effects as a new cue after cue 12.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="record-name">Cue name</Label>
            <Input id="record-name" defaultValue="Band walk-on" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="grid gap-2">
              <Label htmlFor="record-number">Cue #</Label>
              <Input id="record-number" defaultValue="12.5" />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="record-fade">Fade (s)</Label>
              <Input id="record-fade" type="number" defaultValue="3" />
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline">Cancel</Button>
          <Button>Record</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
)
