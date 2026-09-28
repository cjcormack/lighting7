import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Slider } from '@/components/ui/slider'
import type { TemplateIntent } from '@/lib/templateIntent'

/** One percentage of each head's own range — a level, a strobe, a beam role. */
export function PercentControl({
  label,
  hint,
  value,
  onChange,
}: {
  label: string
  hint?: string
  value: TemplateIntent | undefined
  onChange: (intent: TemplateIntent | null) => void
}) {
  const current = value?.kind === 'percent' ? value.value : null

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-2">
        <Label>{label}</Label>
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs tabular-nums text-muted-foreground">
            {current == null ? '—' : `${current}%`}
          </span>
          {current != null && (
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange(null)}>
              Clear
            </Button>
          )}
        </div>
      </div>
      <Slider
        min={0}
        max={100}
        step={1}
        value={[current ?? 0]}
        onValueChange={([next]) => onChange({ kind: 'percent', value: next })}
      />
      {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  )
}
