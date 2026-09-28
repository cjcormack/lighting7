import { Separator } from 'lighting-desk-ui'

export const Horizontal = () => (
  <div className="w-full max-w-sm">
    <div className="space-y-1">
      <h4 className="text-sm font-medium leading-none">Act 1</h4>
      <p className="text-sm text-muted-foreground">12 cues · runs 48 min</p>
    </div>
    <Separator className="my-4" />
    <div className="space-y-1">
      <h4 className="text-sm font-medium leading-none">Interval</h4>
      <p className="text-sm text-muted-foreground">Separator row — house lights to full</p>
    </div>
  </div>
)

export const Vertical = () => (
  <div className="flex h-5 items-center space-x-4 text-sm">
    <span>Cue 12.5</span>
    <Separator orientation="vertical" />
    <span className="text-muted-foreground">Fade 3.0s</span>
    <Separator orientation="vertical" />
    <span className="text-muted-foreground">Follow +2s</span>
    <Separator orientation="vertical" />
    <span className="text-muted-foreground">Universe 1</span>
  </div>
)

export const InList = () => (
  <div className="w-full max-w-xs rounded-md border text-sm">
    <div className="px-3 py-2">Front wash</div>
    <Separator />
    <div className="px-3 py-2">Cyc — Warm Amber</div>
    <Separator />
    <div className="px-3 py-2">Movers — Ballyhoo</div>
    <Separator />
    <div className="px-3 py-2 text-muted-foreground">Follow spot (parked)</div>
  </div>
)
