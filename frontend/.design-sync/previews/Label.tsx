import { Label, Input } from 'lighting-desk-ui'
import { Lock } from 'lucide-react'

export const Default = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Label htmlFor="fixture-name">Fixture name</Label>
    <Input id="fixture-name" placeholder="LED Par 4" />
  </div>
)

export const WithHint = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Label htmlFor="bpm">
      Default tempo
      <span className="text-xs font-normal text-muted-foreground">(BPM the master boots at)</span>
    </Label>
    <Input id="bpm" type="number" defaultValue={120} />
  </div>
)

export const WithIcon = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Label htmlFor="master">
      <Lock className="size-3.5 text-muted-foreground" />
      Master 1 — global tempo
    </Label>
    <Input id="master" defaultValue="Cannot be deleted" disabled />
  </div>
)

export const Disabled = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Input id="follower" className="peer" defaultValue="Follows Master 2 at 1/2" disabled />
    <Label htmlFor="follower">Follower tempo (set by its leader)</Label>
  </div>
)
