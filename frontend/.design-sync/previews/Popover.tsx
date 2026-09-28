import { Button, Input, Label, Popover, PopoverContent, PopoverTrigger } from 'lighting-desk-ui'

// A popover is a small anchored editor — the desk uses it for a speed master's
// tempo, a cue's fade time, a snapshot picker. Rendered open, uncontrolled, so
// the card shows the content anchored below its trigger.
export const TempoEditor = () => (
  <div className="flex h-[400px] w-full items-start justify-center pt-10">
    <Popover defaultOpen>
      <PopoverTrigger asChild>
        <Button variant="outline">Master 2 · 124 BPM</Button>
      </PopoverTrigger>
      <PopoverContent className="w-72" onOpenAutoFocus={(e) => e.preventDefault()}>
        <div className="grid gap-4">
          <div className="space-y-1">
            <h4 className="text-sm font-medium">Speed master 2</h4>
            <p className="text-muted-foreground text-sm">
              Colour effects follow this master. Type a tempo or tap it in.
            </p>
          </div>
          <div className="grid gap-2">
            <div className="grid grid-cols-3 items-center gap-4">
              <Label htmlFor="tempo-bpm">Tempo</Label>
              <Input id="tempo-bpm" type="number" defaultValue="124" className="col-span-2 h-8" />
            </div>
            <div className="grid grid-cols-3 items-center gap-4">
              <Label htmlFor="tempo-default">Boot at</Label>
              <Input id="tempo-default" type="number" defaultValue="120" className="col-span-2 h-8" />
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <Button size="sm" variant="outline">
              Tap
            </Button>
            <Button size="sm">Apply</Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  </div>
)
