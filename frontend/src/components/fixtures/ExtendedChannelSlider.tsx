import { Slider } from '@/components/ui/slider'

interface ExtendedChannelSliderProps {
  label: string
  value: number
  onChange: (v: number) => void
  /** Swatch colour for the channel (W, A, or UV) */
  color: string
}

export function ExtendedChannelSlider({
  label,
  value,
  onChange,
  color,
}: ExtendedChannelSliderProps) {
  return (
    <div>
      <div className="flex items-center justify-between mb-0.5">
        <span className="text-[11px] flex items-center gap-1">
          <span
            className="inline-block w-2 h-2 rounded-full border border-border"
            style={{ backgroundColor: color }}
          />
          {label}
        </span>
        <span className="text-[11px] text-muted-foreground font-mono">{value}</span>
      </div>
      <Slider
        value={[value]}
        min={0}
        max={255}
        step={1}
        onValueChange={([v]) => onChange(v)}
      />
    </div>
  )
}
