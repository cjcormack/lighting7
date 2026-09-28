import { BuskLabel } from 'lighting-desk-ui'

const Pad = ({ name, colour }: { name: string; colour?: string }) => (
  <div className="flex h-12 flex-col justify-between rounded-md border bg-card p-1.5 text-[11px] leading-tight">
    {colour ? <span className="size-3 rounded-sm border border-black/20" style={{ backgroundColor: colour }} /> : <span />}
    <span className="truncate">{name}</span>
  </div>
)

export const Regions = () => (
  <div className="space-y-3">
    <div>
      <BuskLabel className="mb-1">Targets</BuskLabel>
      <div className="grid grid-cols-4 gap-1.5">
        <Pad name="Front wash" />
        <Pad name="Spots" />
        <Pad name="Cyc" />
        <Pad name="Movers" />
      </div>
    </div>
    <div>
      <BuskLabel className="mb-1">Looks</BuskLabel>
      <div className="grid grid-cols-4 gap-1.5">
        <Pad name="Warm Wash" />
        <Pad name="Blue Night" />
        <Pad name="Strobe Hit" />
        <Pad name="Ballyhoo" />
      </div>
    </div>
  </div>
)

export const AbovePads = () => (
  <div>
    <BuskLabel className="mb-1">Colour</BuskLabel>
    <div className="grid grid-cols-4 gap-1.5">
      <Pad name="Warm Amber" colour="#ff9d4a" />
      <Pad name="Congo Blue" colour="#2b1fd9" />
      <Pad name="Bastard Amber" colour="#ffc98a" />
      <Pad name="Lavender" colour="#b48ae6" />
    </div>
  </div>
)

export const RailCardTitle = () => (
  <div className="w-56 rounded-md border bg-card p-2">
    <div className="flex items-center gap-2">
      <span className="size-2 rounded-full bg-primary" />
      <BuskLabel className="flex-1">Master 2 · Colour</BuskLabel>
      <span className="rounded bg-muted px-1 text-[9px] uppercase text-muted-foreground">×1/2</span>
    </div>
    <p className="mt-1 text-2xl font-semibold tabular-nums">64</p>
    <p className="text-[10px] text-muted-foreground">bpm · follows Master 1</p>
  </div>
)
