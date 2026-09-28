import type { CSSProperties } from 'react'
import { Slider } from '@/components/ui/slider'

interface ColourChannelSliderProps {
  label: string
  value: number
  onChange: (value: number) => void
  /**
   * Applied to the label cell. The caller supplies the width and typography as well as the
   * colour, because the two surfaces sharing this row are tuned to different label metrics
   * (the fixture visualiser labels are wider and heavier than the group ones).
   */
  labelClassName?: string
  /** Label colour for callers whose palette isn't expressible as a Tailwind class. */
  labelStyle?: CSSProperties
}

/**
 * One labelled 0–255 channel row: label cell, slider, numeric readout.
 *
 * The inline counterpart to `ExtendedChannelSlider`, which stacks the same three parts for the
 * colour popovers. The two shapes are deliberately separate components rather than one with a
 * layout branch — no caller varies the layout, and the popover widths are tuned to the stacked
 * markup.
 */
export function ColourChannelSlider({
  label,
  value,
  onChange,
  labelClassName,
  labelStyle,
}: ColourChannelSliderProps) {
  return (
    <div className="flex items-center gap-2">
      <span className={labelClassName} style={labelStyle}>
        {label}
      </span>
      <Slider
        value={[value]}
        min={0}
        max={255}
        step={1}
        onValueChange={([v]) => onChange(v)}
        className="flex-1"
      />
      <span className="w-8 text-xs text-right text-muted-foreground">{value}</span>
    </div>
  )
}
