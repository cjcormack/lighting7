import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useNumberFieldDraft } from '@/hooks/useNumberFieldDraft'
import { lampHex, lampOutput, type Lamp } from '@/lib/lamp'
import type { Lantern } from '@/lib/lanterns'
import { chipButtonClassName } from './chipButton'

const WATTS_MIN = 1
const WATTS_MAX = 10000
const KELVIN_MIN = 1000
const KELVIN_MAX = 10000

/** Colour temperatures a lamp is commonly sold at: warm house lamps, theatre tungsten, daylight. */
const KELVIN_PRESETS = [2700, 3000, 3200, 5600] as const

interface LampFieldsProps {
  idPrefix: string
  /** The unit's lantern, whose lamp a blank field takes. */
  lantern: Lantern | null
  lampWatts: number | null
  lampKelvin: number | null
  onChange: (next: { lampWatts: number | null; lampKelvin: number | null }) => void
  /** The gel in the frame, which the swatch filters the lamp through. */
  gelHex?: string | null
}

/**
 * A lantern's lamp: its power and its colour temperature at full, each blank for the lantern's own
 * (the field's placeholder). The swatch is the lamp as the Stage view draws it at full, through the
 * gel; the line under it says what the lamp is and how bright it is against a 575 W profile.
 */
export function LampFields({ idPrefix, lantern, lampWatts, lampKelvin, onChange, gelHex }: LampFieldsProps) {
  const lamp: Lamp = {
    watts: lampWatts ?? lantern?.watts ?? 575,
    kelvin: lampKelvin ?? lantern?.kelvin ?? 3200,
    kind: lantern?.lamp ?? 'TUNGSTEN',
  }
  const output = lampOutput(lamp)
  return (
    <div className="space-y-2" data-lamp-fields>
      <Label htmlFor={`${idPrefix}-lamp-watts`}>Lamp</Label>
      <div className="flex items-center gap-2">
        <span
          className="size-7 shrink-0 rounded-md border border-border"
          style={{ background: lampHex(lamp, 1, gelHex) }}
          aria-hidden
          data-lamp-swatch
        />
        <NumberBox
          id={`${idPrefix}-lamp-watts`}
          label="Lamp power"
          unit="W"
          value={lampWatts}
          placeholder={lantern?.watts}
          min={WATTS_MIN}
          max={WATTS_MAX}
          onChange={(n) => onChange({ lampWatts: n, lampKelvin })}
        />
        <NumberBox
          id={`${idPrefix}-lamp-kelvin`}
          label="Colour temperature"
          unit="K"
          value={lampKelvin}
          placeholder={lantern?.kelvin}
          min={KELVIN_MIN}
          max={KELVIN_MAX}
          onChange={(n) => onChange({ lampWatts, lampKelvin: n })}
        />
      </div>
      <div className="flex flex-wrap gap-1">
        {KELVIN_PRESETS.map((k) => (
          <button
            key={k}
            type="button"
            onClick={() => onChange({ lampWatts, lampKelvin: k })}
            className={`px-2 py-0.5 rounded text-[10.5px] font-mono ${chipButtonClassName(lampKelvin === k)}`}
          >
            {k} K
          </button>
        ))}
      </div>
      <p className="text-xs text-muted-foreground" data-lamp-summary>
        {lamp.kind === 'LED' ? 'LED' : 'Tungsten'} · {formatOutput(output)} a 575 W profile&apos;s light
        {lamp.kind === 'TUNGSTEN' ? ' · warms as it dims' : ''}. Blank takes the lantern&apos;s.
      </p>
    </div>
  )
}

function formatOutput(output: number): string {
  const pct = output * 100
  return `${pct >= 10 ? Math.round(pct) : pct.toFixed(1)}% of`
}

function NumberBox({
  id,
  label,
  unit,
  value,
  placeholder,
  min,
  max,
  onChange,
}: {
  id: string
  label: string
  unit: string
  value: number | null
  placeholder: number | null | undefined
  min: number
  max: number
  onChange: (next: number | null) => void
}) {
  // A draft of its own, so a number on its way to a valid one (the 3 of 3200) can be typed; it
  // reaches the form only once it is in range, and a blur puts back what the form holds.
  const draft = useNumberFieldDraft(value == null ? '' : String(value), (n) => {
    const whole = Math.round(n)
    if (whole >= min && whole <= max) onChange(whole)
  })
  return (
    <div className="flex items-center gap-1">
      <Input
        id={id}
        type="number"
        aria-label={label}
        min={min}
        max={max}
        value={draft.value}
        placeholder={placeholder != null ? String(placeholder) : '—'}
        className="w-24 font-mono"
        onChange={(e) => {
          draft.onChange(e.target.value)
          if (e.target.value.trim() === '') onChange(null)
        }}
        onBlur={draft.onBlur}
        onFocus={(e) => e.currentTarget.select()}
      />
      <span className="text-xs font-mono text-muted-foreground/60">{unit}</span>
    </div>
  )
}
