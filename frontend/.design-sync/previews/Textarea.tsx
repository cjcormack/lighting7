import { Textarea, Label } from 'lighting-desk-ui'

export const Default = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Label htmlFor="cue-notes">Cue notes</Label>
    <Textarea id="cue-notes" placeholder="What the operator should watch for on this GO…" />
  </div>
)

export const Filled = () => (
  <div className="grid w-full max-w-sm gap-1.5">
    <Label htmlFor="prompt">Prompt Book note</Label>
    <Textarea
      id="prompt"
      defaultValue={
        'GO on "…and the lights came up." Hold the amber wash until the band is fully on stage, then take the follow spot to 60% over 3s.'
      }
    />
  </div>
)

export const States = () => (
  <div className="grid w-full max-w-sm gap-3">
    <div className="grid gap-1.5">
      <Label htmlFor="desc">Look description</Label>
      <Textarea id="desc" defaultValue="Too long — this description exceeds the 200 character limit for a look." aria-invalid="true" />
      <p className="text-xs text-destructive">Keep it under 200 characters.</p>
    </div>
    <div className="grid gap-1.5">
      <Label htmlFor="locked">Show notes (locked)</Label>
      <Textarea id="locked" defaultValue="Show is running — unlock to edit notes." disabled />
    </div>
  </div>
)
