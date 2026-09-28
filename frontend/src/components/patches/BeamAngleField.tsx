import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { BEAM_PRESETS } from '@/data/patchPresets'
import { clamp } from '@/lib/utils'
import { chipButtonClassName } from './chipButton'

const BEAM_MIN = 2
const BEAM_MAX = 120

interface BeamAngleFieldProps {
  id?: string
  value: number | null
  onChange: (next: number | null) => void
}

export function BeamAngleField({ id, value, onChange }: BeamAngleFieldProps) {
  const matchedPreset = BEAM_PRESETS.find((p) => p.deg === value)
  const halfDeg = value != null ? Math.max(BEAM_MIN, value / 2) : 0

  return (
    <div className="space-y-2">
      <Label htmlFor={id}>Beam Angle</Label>
      <div className="flex items-stretch gap-3">
        <div
          className="relative w-[84px] h-[70px] rounded-md border border-border overflow-hidden shrink-0"
          style={{
            background:
              'radial-gradient(ellipse at top, rgba(127,127,127,0.18) 0%, rgba(0,0,0,0.55) 100%)',
          }}
          aria-hidden
        >
          <div
            className="absolute left-1/2 top-1 w-2 h-1.5 rounded-t-sm bg-muted-foreground/80 -translate-x-1/2"
          />
          {value != null && (
            <div
              className="absolute left-1/2 top-2 -translate-x-1/2 blur-[1.5px]"
              style={{
                width: 0,
                height: 0,
                borderLeft: `calc(56px * tan(${halfDeg}deg)) solid transparent`,
                borderRight: `calc(56px * tan(${halfDeg}deg)) solid transparent`,
                borderBottom: '56px solid rgba(255, 220, 140, 0.42)',
              }}
            />
          )}
        </div>
        <div className="flex flex-col gap-1.5 flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <Input
              id={id}
              type="number"
              min={BEAM_MIN}
              max={BEAM_MAX}
              value={value ?? ''}
              placeholder="—"
              className="w-20 font-mono"
              onChange={(e) => {
                const raw = e.target.value
                if (raw === '') {
                  onChange(null)
                  return
                }
                const n = Number(raw)
                if (Number.isFinite(n)) onChange(clamp(Math.round(n), BEAM_MIN, BEAM_MAX))
              }}
              onFocus={(e) => e.currentTarget.select()}
            />
            <span className="text-xs font-mono text-muted-foreground/60">°</span>
          </div>
          <div className="flex flex-wrap gap-1">
            {BEAM_PRESETS.map((p) => (
              <button
                key={p.name}
                type="button"
                onClick={() => onChange(p.deg)}
                className={`px-2 py-0.5 rounded text-[10.5px] ${chipButtonClassName(matchedPreset?.deg === p.deg)}`}
              >
                {p.name}
                <span className="ml-1 font-mono text-muted-foreground/60">{p.deg}°</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
