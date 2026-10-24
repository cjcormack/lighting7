import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Slider } from '@/components/ui/slider'
import { EditorField } from '@/components/editor/EditorField'
import { CENTRE_MAX, CENTRE_MIN, clampCentre, formatCentre, positionOnScale } from '@/lib/positionScale'
import type { PaletteIntent } from '@/lib/paletteIntent'

/**
 * Pan and tilt **centre-relative** (venue-visit plan D3): `-128..127` on each axis, 0 at the centre
 * of travel (DMX 128), so a generic palette is the same fraction of travel on every head. The desk
 * resolves it through each head's own range and the panel below reports anything it clamps.
 *
 * **A palette stored in degrees reads on this scale and stays in degrees until it is moved** (P3).
 * The control shows a `deg:` intent through the old editor's 540° / 270° range
 * (`positionOnScale`), and only a change here writes a `pos:` intent — so opening and saving an old
 * palette for its name leaves its position exactly as it was stored, and the desk keeps resolving
 * it as angles.
 */
export function PositionControl({
  value,
  onChange,
}: {
  value: PaletteIntent | undefined
  onChange: (intent: PaletteIntent | null) => void
}) {
  const current = value?.kind === 'position' ? value : null
  const { pan, tilt } = current != null ? positionOnScale(current) : { pan: 0, tilt: 0 }
  const set = (next: { pan: number; tilt: number }) =>
    onChange({ kind: 'position', pan: clampCentre(next.pan), tilt: clampCentre(next.tilt), unit: 'centre' })

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
      {(['pan', 'tilt'] as const).map((axis) => {
        const label = axis === 'pan' ? 'Pan' : 'Tilt'
        const shown = axis === 'pan' ? pan : tilt
        return (
          <div key={axis} className="space-y-1.5">
            <div className="flex items-center justify-between gap-2 text-xs">
              <span className="text-muted-foreground">{label}</span>
              {current == null ? (
                <span className="font-mono tabular-nums">—</span>
              ) : (
                <EditorField
                  label={label}
                  value={shown}
                  min={CENTRE_MIN}
                  max={CENTRE_MAX}
                  fieldClassName="w-16"
                  onCommit={(next) => set({ pan, tilt, [axis]: next })}
                />
              )}
            </div>
            <Slider
              aria-label={label}
              min={CENTRE_MIN}
              max={CENTRE_MAX}
              step={1}
              value={[shown]}
              onValueChange={([next]) => next !== undefined && set({ pan, tilt, [axis]: next })}
            />
          </div>
        )
      })}
      <p className="text-[11px] text-muted-foreground">
        {current?.unit === 'degrees'
          ? `Saved in degrees — shown here as ${formatCentre(pan)} / ${formatCentre(tilt)}, and saved centre-relative once you move it.`
          : 'Centre-relative DMX: 0 is the centre of travel, ±127 its ends. Each head takes the same share of its own travel, and the panel below reports anything it has to clamp.'}
      </p>
    </div>
  )
}
