import { ShowMarkerRow } from 'lighting-desk-ui'

const noop = () => {}

/** Unlocked: drag grip, inline rename field, and a delete cross. */
export const Unlocked = () => (
  <div className="rounded-md border bg-card">
    <ShowMarkerRow id={1} name="Act 1 — Opening" onRename={noop} onDelete={noop} />
  </div>
)

/** Locked: the same divider with no affordances, and nothing shifts sideways. */
export const Locked = () => (
  <div className="rounded-md border bg-card">
    <ShowMarkerRow id={2} name="Act 1 — Opening" onRename={noop} onDelete={noop} locked />
  </div>
)

const cue = (num: string, name: string, fade: string) => (
  <div key={num} className="flex items-center gap-3 px-4 py-2 text-sm">
    <span className="w-12 font-mono text-xs text-muted-foreground">{num}</span>
    <span className="flex-1">{name}</span>
    <span className="text-xs text-muted-foreground">{fade}</span>
  </div>
)

/** Between cues in an unlocked show, the way the Show view lays it out. */
export const BetweenCues = () => (
  <div className="rounded-md border bg-card">
    {cue('1', 'House to half', '8s')}
    {cue('2', 'Band walk-on', '3s')}
    <ShowMarkerRow id={3} name="Interval" onRename={noop} onDelete={noop} />
    {cue('S1-1', 'Warm Wash', '5s')}
    {cue('S1-2', 'Blackout', '0s')}
  </div>
)

/** The same list with the show locked. */
export const BetweenCuesLocked = () => (
  <div className="rounded-md border bg-card">
    {cue('1', 'House to half', '8s')}
    {cue('2', 'Band walk-on', '3s')}
    <ShowMarkerRow id={4} name="Interval" onRename={noop} onDelete={noop} locked />
    {cue('S1-1', 'Warm Wash', '5s')}
    {cue('S1-2', 'Blackout', '0s')}
  </div>
)
