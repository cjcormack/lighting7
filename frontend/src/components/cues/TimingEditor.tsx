import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

interface TimingValues {
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
}

interface TimingFieldsProps {
  values: TimingValues
  onChange: (values: TimingValues) => void
}

/**
 * Always-visible timing fields for preset applications, ad-hoc effects, and triggers.
 * All fields are optional — empty means no timing configured for that aspect.
 */
export function TimingFields({ values, onChange }: TimingFieldsProps) {
  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label className="text-xs" htmlFor="timing-delay">Delay (ms)</Label>
        <Input
          id="timing-delay"
          type="number"
          min="0"
          step="100"
          value={values.delayMs ?? ''}
          onChange={(e) => onChange({ ...values, delayMs: e.target.value ? Number(e.target.value) : null })}
          placeholder="e.g. 2000"
          className="h-8 text-xs"
        />
      </div>

      <div className="space-y-1.5">
        <Label className="text-xs" htmlFor="timing-interval">Interval (ms)</Label>
        <p className="text-[10px] text-muted-foreground leading-tight">
          Repeat at this interval. Leave empty for one-shot.
        </p>
        <Input
          id="timing-interval"
          type="number"
          min="100"
          step="100"
          value={values.intervalMs ?? ''}
          onChange={(e) => onChange({ ...values, intervalMs: e.target.value ? Number(e.target.value) : null })}
          placeholder="e.g. 5000"
          className="h-8 text-xs"
        />
      </div>

      <div className="space-y-1.5">
        <Label className="text-xs" htmlFor="timing-random">Random window (ms)</Label>
        <p className="text-[10px] text-muted-foreground leading-tight">
          Each interval varies by ± this amount for organic timing.
        </p>
        <Input
          id="timing-random"
          type="number"
          min="0"
          step="100"
          value={values.randomWindowMs ?? ''}
          onChange={(e) => onChange({ ...values, randomWindowMs: e.target.value ? Number(e.target.value) : null })}
          placeholder="e.g. 5000"
          className="h-8 text-xs"
        />
      </div>
    </div>
  )
}
