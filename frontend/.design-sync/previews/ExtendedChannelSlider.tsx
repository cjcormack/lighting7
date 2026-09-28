import { useState } from 'react'
import { ExtendedChannelSlider } from 'lighting-desk-ui'

// The stacked W / A / UV row the colour popover uses under its picker.
// Each cell is controlled by a small wrapper so the thumb can be dragged.
function Channel({ label, initial, color }: { label: string; initial: number; color: string }) {
  const [value, setValue] = useState(initial)
  return (
    <div className="w-56">
      <ExtendedChannelSlider label={label} value={value} onChange={setValue} color={color} />
    </div>
  )
}

export const WhiteFull = () => <Channel label="W" initial={255} color="#fffbe6" />

export const AmberHalf = () => <Channel label="A" initial={128} color="#ffbf00" />

export const UvLow = () => <Channel label="UV" initial={40} color="#7f00ff" />

export const StackedInPopover = () => (
  <div className="w-64 space-y-2 rounded-md border border-border bg-popover p-3 text-popover-foreground shadow-md">
    <div className="text-xs text-muted-foreground">Front wash 1 · extended emitters</div>
    <div className="space-y-2 border-t border-border pt-2">
      <Channel label="W" initial={0} color="#fffbe6" />
      <Channel label="A" initial={200} color="#ffbf00" />
      <Channel label="UV" initial={12} color="#7f00ff" />
    </div>
  </div>
)
