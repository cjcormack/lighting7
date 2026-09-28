import { MarkerRow } from 'lighting-desk-ui'

export const Default = () => (
  <div className="rounded-md border bg-card">
    <MarkerRow name="Act 1 — Opening" />
  </div>
)

const cue = (num: string, name: string) => (
  <div key={num} className="flex items-center gap-3 px-3.5 py-2 text-sm">
    <span className="w-12 font-mono text-xs text-muted-foreground">{num}</span>
    <span className="flex-1">{name}</span>
  </div>
)

/** A labelled divider between two runs of cues, as the phone cue list and Prompt Book rail draw it. */
export const BetweenCues = () => (
  <div className="rounded-md border bg-card">
    {cue('11', 'Blackout')}
    {cue('12', 'Band walk-on')}
    <MarkerRow name="Interval" />
    {cue('S1-1', 'Warm Wash')}
    {cue('S1-2', 'Vocal special')}
  </div>
)

export const LongName = () => (
  <div className="rounded-md border bg-card">
    <MarkerRow name="Act 3 — Finale, curtain call and walk-out music" />
  </div>
)
