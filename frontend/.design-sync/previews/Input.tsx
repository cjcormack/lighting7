import { Input, Label } from 'lighting-desk-ui'

export const Default = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Label htmlFor="cue-name">Cue name</Label>
    <Input id="cue-name" placeholder="e.g. Warm wash, house half" />
  </div>
)

export const Types = () => (
  <div className="grid w-full max-w-sm gap-3">
    <Input type="text" defaultValue="Opening look" aria-label="Cue name" />
    <Input type="number" defaultValue={3.5} min={0} step={0.1} aria-label="Fade time (s)" />
    <Input type="search" placeholder="Search fixtures, groups, looks…" aria-label="Search" />
    <Input type="password" defaultValue="desk-operator" aria-label="Password" />
  </div>
)

export const States = () => (
  <div className="grid w-full max-w-sm gap-3">
    <div className="grid gap-1.5">
      <Label htmlFor="stack-name">Stack name</Label>
      <Input id="stack-name" defaultValue="Act 1" />
    </div>
    <div className="grid gap-1.5">
      <Label htmlFor="dmx-address">DMX address</Label>
      <Input id="dmx-address" defaultValue="513" aria-invalid="true" />
      <p className="text-xs text-destructive">Must be between 1 and 512.</p>
    </div>
    <div className="grid gap-1.5">
      <Label htmlFor="universe">Universe</Label>
      <Input id="universe" defaultValue="Universe 2 (Art-Net 0.1)" disabled />
    </div>
  </div>
)

export const Compact = () => (
  <div className="flex w-full max-w-sm items-center gap-2">
    <Input className="h-8 w-16 text-center font-mono" defaultValue="12.5" aria-label="Cue number" />
    <Input className="h-8" defaultValue="Blackout to walk-in" aria-label="Cue label" />
    <Input className="h-8 w-16 text-right font-mono" defaultValue="0.0" aria-label="Fade" />
  </div>
)
