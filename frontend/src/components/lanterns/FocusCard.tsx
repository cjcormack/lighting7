import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Slider } from '@/components/ui/slider'
import { cn } from '@/lib/utils'
import {
  BLADE_LABELS,
  focusFeatures,
  lanternFieldDeg,
  MAX_BLADE_ANGLE_DEG,
  OPEN_BLADES,
  type Lantern,
  type LanternFocus,
  type ShutterBlade,
} from '@/lib/lanterns'
import { GatePreview } from './GatePreview'

/** One lantern the card focuses: a fixture's own, or one of its extra placements. */
export interface FocusUnit {
  key: string
  /** How the pair switch names it — *Lantern 1 · SR*. */
  label: string
  /** The lantern it is drawn as (named, inherited or its kind's default); null for none. */
  lantern: Lantern | null
  focus: LanternFocus
}

interface FocusCardProps {
  units: readonly FocusUnit[]
  active: number
  onActiveChange: (index: number) => void
  /** The whole focus of the unit at [index], as it now stands. */
  onChange: (index: number, next: LanternFocus) => void
  disabled?: boolean
  /** Said under the controls — where the focus is stored, what a pair does. */
  note?: ReactNode
}

/** The gate's turn a slider offers, degrees — a Source Four's barrel turns about ±25°. */
const GATE_TURN_DEG = 90
/** A PAR lamp turns all the way round; ±90 covers every oval once. */
const LAMP_TURN_DEG = 90
/** Below this the iris is shut enough that a stage pool vanishes; the slider stops here. */
const IRIS_MIN = 0.05

/**
 * The **focus card** (stage-view plan session 7; the design record's item 10): a live
 * cross-section of the gate, then what the lantern can take — depth and angle per blade (shutters,
 * or barn doors in the same four slots), the gate's turn, the iris, sharp ↔ soft, zoom in the
 * lantern's range, a PAR lamp's turn — and a switch between the lanterns of a pair, which are
 * focused separately. The Stage view's Focus tab and the patch editor both mount it; each decides
 * when a change is written.
 */
export function FocusCard({ units, active, onActiveChange, onChange, disabled, note }: FocusCardProps) {
  const index = Math.min(active, Math.max(0, units.length - 1))
  const unit = units[index]
  if (!unit) return null
  const { lantern, focus } = unit
  const features = focusFeatures(lantern)
  const blades: readonly ShutterBlade[] = focus.shutters?.length === 4 ? focus.shutters : OPEN_BLADES
  const set = (patch: Partial<LanternFocus>) => onChange(index, { ...focus, ...patch })
  const setBlade = (i: number, patch: Partial<ShutterBlade>) =>
    set({ shutters: blades.map((b, j) => (j === i ? { ...b, ...patch } : { ...b })) })
  const bladeWord = features.blades === 'barnDoors' ? 'Barn doors' : 'Shutters'
  const anyBladeIn = blades.some((b) => b.depth > 0)
  const ovalRatio =
    lantern?.oval != null
      ? Math.tan((lantern.oval.narrowDeg * Math.PI) / 360) / Math.tan((lantern.oval.wideDeg * Math.PI) / 360)
      : null
  const turnDeg =
    (features.blades ? focus.gateRotationDeg ?? 0 : 0) + (features.oval ? focus.lampRotationDeg ?? 0 : 0)
  const fieldDeg = lantern ? lanternFieldDeg(lantern, focus.zoomDeg) : null

  return (
    <div className="space-y-3" data-focus-card>
      {units.length > 1 && (
        <div className="flex flex-wrap gap-1" role="tablist" aria-label="Lanterns of this fixture">
          {units.map((u, i) => (
            <button
              key={u.key}
              type="button"
              role="tab"
              aria-selected={i === index}
              onClick={() => onActiveChange(i)}
              className={cn(
                'rounded-md border px-2 py-1 text-xs',
                i === index ? 'border-primary bg-primary/10 text-foreground' : 'border-border text-muted-foreground hover:text-foreground',
              )}
            >
              {u.label}
            </button>
          ))}
        </div>
      )}

      <div>
        <p className="text-sm font-medium">{lantern?.name ?? 'No lantern'}</p>
        {lantern && (
          <p className="text-xs text-muted-foreground">
            {lantern.beamDeg != null ? `${fmt(lantern.beamDeg)}° beam · ` : ''}
            {fieldDeg != null ? `${fmt(fieldDeg)}° field` : ''}
            {lantern.oval ? ` × ${fmt(lantern.oval.narrowDeg)}° (oval)` : ''}
          </p>
        )}
      </div>

      <GatePreview
        className="mx-auto block h-36 w-36"
        blades={features.blades ? blades : null}
        turnDeg={turnDeg}
        iris={features.iris ? focus.iris ?? 1 : 1}
        ovalRatio={ovalRatio}
      />

      {features.blades && (
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <p className="flex-1 text-xs font-medium text-muted-foreground">{bladeWord} · depth · angle</p>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-xs"
              disabled={disabled || !anyBladeIn}
              onClick={() => set({ shutters: null })}
            >
              All out
            </Button>
          </div>
          {BLADE_LABELS.map((name, i) => (
            <div key={name} className="grid grid-cols-[3.5rem_1fr_2.5rem_1fr_2.5rem] items-center gap-2">
              <span className="text-xs">{name}</span>
              <Slider
                aria-label={`${name} ${bladeWord.toLowerCase()} depth`}
                min={0}
                max={1}
                step={0.01}
                value={[blades[i].depth]}
                disabled={disabled}
                onValueChange={([v]) => setBlade(i, { depth: v })}
              />
              <span className="text-right font-mono text-xs tabular-nums">{Math.round(blades[i].depth * 100)}%</span>
              <Slider
                aria-label={`${name} ${bladeWord.toLowerCase()} angle`}
                min={-MAX_BLADE_ANGLE_DEG}
                max={MAX_BLADE_ANGLE_DEG}
                step={1}
                value={[blades[i].angleDeg]}
                disabled={disabled}
                onValueChange={([v]) => setBlade(i, { angleDeg: v })}
              />
              <span className="text-right font-mono text-xs tabular-nums">{fmt(blades[i].angleDeg)}°</span>
            </div>
          ))}
          <FocusRow
            label="Rotate"
            ariaLabel={features.blades === 'barnDoors' ? 'Barn door rotation' : 'Gate rotation'}
            min={-GATE_TURN_DEG}
            max={GATE_TURN_DEG}
            step={1}
            value={focus.gateRotationDeg ?? 0}
            show={(v) => `${fmt(v)}°`}
            disabled={disabled}
            onChange={(v) => set({ gateRotationDeg: v === 0 ? null : v })}
          />
        </div>
      )}

      {features.iris && (
        <FocusRow
          label="Iris"
          ariaLabel="Iris"
          min={IRIS_MIN}
          max={1}
          step={0.01}
          value={focus.iris ?? 1}
          show={(v) => (v >= 1 ? 'open' : `${Math.round(v * 100)}%`)}
          disabled={disabled}
          onChange={(v) => set({ iris: v >= 1 ? null : v })}
        />
      )}

      {features.softness && (
        <FocusRow
          label="Focus"
          ariaLabel="Focus, sharp to soft"
          min={0}
          max={1}
          step={0.01}
          value={focus.focusSoftness ?? defaultSoftness(lantern)}
          show={(v) => (focus.focusSoftness == null ? 'as built' : v < 0.2 ? 'sharp' : v > 0.7 ? 'soft' : 'medium')}
          disabled={disabled}
          onChange={(v) => set({ focusSoftness: v })}
        />
      )}

      {features.zoom && (
        <FocusRow
          label={lantern?.family === 'PROFILE' ? 'Zoom' : 'Spot–flood'}
          ariaLabel="Zoom"
          min={features.zoom.minDeg}
          max={features.zoom.maxDeg}
          step={1}
          value={fieldDeg ?? features.zoom.minDeg}
          show={(v) => (focus.zoomDeg == null ? `${fmt(v)}° · default` : `${fmt(v)}°`)}
          disabled={disabled}
          onChange={(v) => set({ zoomDeg: v })}
        />
      )}

      {features.oval && (
        <FocusRow
          label="Lamp"
          ariaLabel="Lamp rotation"
          min={-LAMP_TURN_DEG}
          max={LAMP_TURN_DEG}
          step={1}
          value={focus.lampRotationDeg ?? 0}
          show={(v) => `${fmt(v)}°`}
          disabled={disabled}
          onChange={(v) => set({ lampRotationDeg: v === 0 ? null : v })}
        />
      )}

      {!features.blades && !features.iris && !features.softness && !features.zoom && !features.oval && (
        <p className="text-xs text-muted-foreground">
          {lantern ? `${lantern.name} has nothing to focus: it is hung, aimed and gelled.` : 'Pick a lantern to focus it.'}
        </p>
      )}

      {note && <p className="text-xs text-muted-foreground">{note}</p>}
    </div>
  )
}

function FocusRow({
  label,
  ariaLabel,
  min,
  max,
  step,
  value,
  show,
  disabled,
  onChange,
}: {
  label: string
  ariaLabel: string
  min: number
  max: number
  step: number
  value: number
  show: (v: number) => string
  disabled?: boolean
  onChange: (v: number) => void
}) {
  const clamped = Math.min(max, Math.max(min, value))
  return (
    // Wide enough for "Spot–flood" and "45° · default" on one line each at text-xs.
    <div className="grid grid-cols-[4.5rem_1fr_6.5rem] items-center gap-2">
      <span className="whitespace-nowrap text-xs">{label}</span>
      <Slider
        aria-label={ariaLabel}
        min={min}
        max={max}
        step={step}
        value={[clamped]}
        disabled={disabled}
        onValueChange={([v]) => onChange(v)}
      />
      <span className="whitespace-nowrap text-right font-mono text-xs tabular-nums">{show(clamped)}</span>
    </div>
  )
}

/** Where the focus knob rests for a lantern that has not been focused: sharp for a profile. */
function defaultSoftness(lantern: Lantern | null): number {
  return lantern?.family === 'PROFILE' ? 0 : 0.5
}

function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}
