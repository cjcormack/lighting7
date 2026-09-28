import { useState } from 'react'
import { CollapsiblePanel, Button } from 'lighting-desk-ui'
import { ChevronDown, ChevronRight } from 'lucide-react'

const fixtures = [
  { name: 'Spot 1', colour: '#ffb347' },
  { name: 'Spot 2', colour: '#ffb347' },
  { name: 'Wash L', colour: '#2b5fd9' },
  { name: 'Wash R', colour: '#2b5fd9' },
  { name: 'Cyc 1', colour: '#8a2be2' },
  { name: 'Cyc 2', colour: '#8a2be2' },
]

const StageOverviewBody = () => (
  <div className="border-t p-3">
    <div className="flex justify-between gap-2">
      {fixtures.map((f) => (
        <div key={f.name} className="flex flex-col items-center gap-1">
          <span
            className="size-6 rounded-full border border-black/20"
            style={{ backgroundColor: f.colour }}
          />
          <span className="text-[10px] text-muted-foreground">{f.name}</span>
        </div>
      ))}
    </div>
    <p className="mt-2 text-xs text-muted-foreground">Cue 12 · Warm Wash on stage · 3.0s fade</p>
  </div>
)

const PanelShell = ({
  title,
  open,
  onToggle,
  children,
}: {
  title: string
  open: boolean
  onToggle?: () => void
  children: React.ReactNode
}) => (
  <div className="rounded-md border bg-card text-card-foreground">
    <button
      type="button"
      onClick={onToggle}
      className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium"
    >
      {open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
      {title}
    </button>
    {children}
  </div>
)

export const Open = () => (
  <PanelShell title="Stage overview" open>
    <CollapsiblePanel isVisible>
      <StageOverviewBody />
    </CollapsiblePanel>
  </PanelShell>
)

export const Collapsed = () => (
  <div className="space-y-2">
    <PanelShell title="Stage overview" open={false}>
      <CollapsiblePanel isVisible={false}>
        <StageOverviewBody />
      </CollapsiblePanel>
    </PanelShell>
    <p className="text-xs text-muted-foreground">
      Collapsed to zero rows; the body unmounts 200 ms after closing.
    </p>
  </div>
)

const ToggleDemo = () => {
  const [open, setOpen] = useState(true)
  return (
    <div className="space-y-2">
      <Button variant="outline" size="sm" onClick={() => setOpen((o) => !o)}>
        {open ? 'Hide fixture panel' : 'Show fixture panel'}
      </Button>
      <CollapsiblePanel isVisible={open} className="mt-1">
        <div className="rounded-md border bg-card p-3 text-sm">
          <p className="font-medium">Spot 3</p>
          <p className="text-xs text-muted-foreground">Intensity 72% · Colour #FF9D4A · Pan 12° Tilt −8°</p>
        </div>
      </CollapsiblePanel>
    </div>
  )
}

export const Toggleable = () => <ToggleDemo />
