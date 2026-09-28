import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Slider } from '@/components/ui/slider'
import type { TemplateIntent } from '@/lib/templateIntent'

/**
 * Pan and tilt in **degrees**, over the widest range in this rig (540° pan / 270° tilt).
 *
 * The authoring range is deliberately wider than most heads can reach: a template is not authored
 * against a fixture, so the control cannot know a limit — and the resolver clamps per head and
 * *reports* the clamp, which the panel below shows. A narrower control would silently make some
 * positions unauthorable.
 */
export function PositionControl({
  value,
  onChange,
}: {
  value: TemplateIntent | undefined
  onChange: (intent: TemplateIntent | null) => void
}) {
  const current = value?.kind === 'position' ? value : null
  const pan = current?.panDeg ?? 270
  const tilt = current?.tiltDeg ?? 135

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <Label>Position</Label>
        {current != null && (
          <Button type="button" variant="ghost" size="sm" onClick={() => onChange(null)}>
            Clear
          </Button>
        )}
      </div>
      <div className="space-y-1.5">
        <div className="flex items-center justify-between text-xs">
          <span className="text-muted-foreground">Pan</span>
          <span className="font-mono tabular-nums">{current == null ? '—' : `${pan}°`}</span>
        </div>
        <Slider
          min={0}
          max={540}
          step={1}
          value={[pan]}
          onValueChange={([next]) => onChange({ kind: 'position', panDeg: next, tiltDeg: tilt })}
        />
      </div>
      <div className="space-y-1.5">
        <div className="flex items-center justify-between text-xs">
          <span className="text-muted-foreground">Tilt</span>
          <span className="font-mono tabular-nums">{current == null ? '—' : `${tilt}°`}</span>
        </div>
        <Slider
          min={0}
          max={270}
          step={1}
          value={[tilt]}
          onValueChange={([next]) => onChange({ kind: 'position', panDeg: pan, tiltDeg: next })}
        />
      </div>
      <p className="text-[11px] text-muted-foreground">
        Degrees, not DMX. Each head resolves these through its own range and the panel below reports
        anything it has to clamp.
      </p>
    </div>
  )
}
