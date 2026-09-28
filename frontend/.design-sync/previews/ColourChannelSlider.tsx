import { useState } from 'react'
import { ColourChannelSlider } from 'lighting-desk-ui'

// One inline 0-255 channel row: label cell, slider, readout. The label metrics
// come from the caller; these mirror the group visualiser's `w-4 text-xs font-mono`.
const LABEL = 'w-4 text-xs font-mono'

function Row({ label, initial, color }: { label: string; initial: number; color: string }) {
  const [value, setValue] = useState(initial)
  return (
    <ColourChannelSlider
      label={label}
      value={value}
      onChange={setValue}
      labelClassName={LABEL}
      labelStyle={{ color }}
    />
  )
}

// #FF9D4A on an RGB head
export const RgbStack = () => (
  <div className="w-72 space-y-1.5">
    <Row label="R" initial={255} color="rgb(239, 68, 68)" />
    <Row label="G" initial={157} color="rgb(34, 197, 94)" />
    <Row label="B" initial={74} color="rgb(59, 130, 246)" />
  </div>
)

export const WithWhite = () => (
  <div className="w-72 space-y-1.5">
    <Row label="R" initial={0} color="rgb(239, 68, 68)" />
    <Row label="G" initial={0} color="rgb(34, 197, 94)" />
    <Row label="B" initial={0} color="rgb(59, 130, 246)" />
    <Row label="W" initial={255} color="rgb(156, 163, 175)" />
  </div>
)

// The fixture visualiser's heavier label
export const WideLabel = () => {
  const [value, setValue] = useState(203)
  return (
    <div className="w-72">
      <ColourChannelSlider
        label="Amber"
        value={value}
        onChange={setValue}
        labelClassName="w-12 text-sm font-semibold text-amber-500"
      />
    </div>
  )
}
