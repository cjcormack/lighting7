import { useState, type ReactNode } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dmxToDegrees, degreesToDmx } from '@/lib/axisDegrees'
import type { EffectLibraryEntry, EffectParameterDef } from '@/store/fixtureFx'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import type { SettingPropertyDescriptor } from '@/store/fixtures'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { SheetField } from '../fixtureSheet/SheetField'
import { fromPct, toPct } from '../fixtures-list/cells/SliderCell'
import { FxColourPicker } from './FxColourPicker'
import { FxColourListPicker } from './FxColourListPicker'
import type { detectExtendedChannels } from './colourUtils'
import { BLEND_MODE_OPTIONS, DISTRIBUTION_STRATEGY_OPTIONS, ELEMENT_FILTER_OPTIONS, ELEMENT_MODE_OPTIONS } from './fxConstants'
import {
  blendForLevelMode,
  centreModeOf,
  clampByte,
  defaultParameters,
  degreesToSize,
  hasCentre,
  isLevelEffect,
  isRatioParam,
  levelModeOf,
  paramAxis,
  paramRole,
  sizeToDegrees,
  withCentreMode,
  type CentreMode,
  type FxDraft,
  type FxScope,
  type LevelMode,
  type PositionAxes,
} from './fxEditorModel'

/**
 * The speed segment, in beats a cycle (Fx board) — ⅛ to 16. A stored division outside the eight
 * (a triplet) presses none of them and the read-out still says what it is.
 */
const SPEED_BEATS: readonly { value: number; label: string }[] = [
  { value: 0.125, label: '⅛' },
  { value: 0.25, label: '¼' },
  { value: 0.5, label: '½' },
  { value: 1, label: '1' },
  { value: 2, label: '2' },
  { value: 4, label: '4' },
  { value: 8, label: '8' },
  { value: 16, label: '16' },
]

const CURVES = [
  'LINEAR',
  'SINE_IN',
  'SINE_OUT',
  'SINE_IN_OUT',
  'QUAD_IN',
  'QUAD_OUT',
  'QUAD_IN_OUT',
  'EXPO_IN',
  'EXPO_OUT',
  'EXPO_IN_OUT',
] as const

/** The board's words for the built-ins' parameters; anything else is its name as words. */
const PARAM_LABELS: Record<string, string> = {
  panCenter: 'Pan',
  tiltCenter: 'Tilt',
  panRadius: 'Pan size',
  tiltRadius: 'Tilt size',
  panRange: 'Pan range',
  tiltRange: 'Tilt range',
  min: 'Low',
  max: 'High',
  attackRatio: 'Attack',
  holdRatio: 'Hold',
  fadeRatio: 'Fade',
  dutyCycle: 'Duty',
  onRatio: 'On',
}

export function paramLabel(name: string): string {
  return (
    PARAM_LABELS[name] ??
    name
      .replace(/([A-Z])/g, ' $1')
      .replace(/^./, (s) => s.toUpperCase())
      .trim()
  )
}


/**
 * The editor's fields over one draft — speed, the two questions, the parameters in their roles,
 * Shape and Advanced — with no write of their own: [set] and [setParam] are the host's. `FxEditor`
 * writes them live into a running effect; `TemplateEditor` (fixture-fx-sheets plan D16) keeps them
 * as the template's draft until Save, so a movement template asks the same *Centre* question
 * through the same rules (`fxEditorModel.ts`) and there is no second copy of either. It replaced
 * `EffectParameterForm`, which was the template editor's draft form until session 5.
 */
export function FxEffectFields({
  entry,
  timingSource,
  draft,
  scope,
  axes,
  extendedChannels,
  settingProperty,
  readOnly,
  allowUnscaledRate = false,
  set,
  setParam,
}: {
  entry: EffectLibraryEntry
  /** The instance's or the template's timing source — the library entry's wins where it says. */
  timingSource: string | null | undefined
  draft: FxDraft
  scope: FxScope
  axes: PositionAxes | null
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingProperty: SettingPropertyDescriptor | undefined
  readOnly: boolean
  /**
   * The rate master's chip offers *Unscaled* — a draft can hold null where a live frame cannot say
   * it (session 3's amendment), so only a draft host turns this on.
   */
  allowUnscaledRate?: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
  setParam: (name: string, value: string, release: boolean) => void
}) {
  const centred = hasCentre(entry)
  // An absent centre is the library's default (128 on every built-in) — a script or a cue may store a
  // Circle without spelling it, and that is still Around.
  const centreMode = centred ? centreModeOf(draft.blendMode, { ...defaultParameters(entry), ...draft.parameters }) : null
  const level = isLevelEffect(entry)
  const levelMode = level ? levelModeOf(draft.blendMode) : null

  const stepTiming = scope.stepTiming ?? scope.heads
  const roles = entry.parameters.map((param) => ({ param, role: paramRole(entry, param) }))
  // Around hides the centre: it is pinned at 128, and showing it would offer a knob that turns the
  // orbit into an offset nobody asked for.
  const main = roles.filter(({ role }) => role !== 'shape' && !(role === 'centre' && centreMode === 'around'))
  const shape = roles.filter(({ role }) => role === 'shape')

  const paramControl = ({ param, role }: { param: EffectParameterDef; role: ReturnType<typeof paramRole> }) => (
    <ParamControl
      key={param.name}
      param={param}
      role={role}
      value={draft.parameters[param.name] ?? param.defaultValue}
      axes={axes}
      extendedChannels={extendedChannels}
      settingProperty={param.name === 'level' ? settingProperty : undefined}
      readOnly={readOnly}
      onChange={(value, release) => setParam(param.name, value, release)}
    />
  )

  return (
    <>
      <SpeedSection
        timingSource={timingSource}
        entry={entry}
        draft={draft}
        readOnly={readOnly}
        allowUnscaledRate={allowUnscaledRate}
        set={set}
      />

      {centred && (
        <Question
          label="Centre"
          value={centreMode}
          readOnly={readOnly}
          options={[
            { value: 'around', label: 'Around current position' },
            { value: 'absolute', label: 'Absolute' },
          ]}
          onChange={(mode) => set(withCentreMode(draft, mode as CentreMode))}
          readout={
            centreMode === 'around'
              ? 'Orbits whatever holds the position underneath — your value, else the cue’s, else the base.'
              : centreMode === 'absolute'
                ? 'Moves about the centre set here, whatever is underneath.'
                : `Blended ${draft.blendMode.toLowerCase()} — see Advanced.`
          }
        />
      )}

      {level && (
        <Question
          label="Over the level underneath"
          value={levelMode}
          readOnly={readOnly}
          options={[
            { value: 'replace', label: 'Replace it' },
            { value: 'within', label: 'Within it' },
          ]}
          onChange={(mode) => set({ blendMode: blendForLevelMode(mode as LevelMode) })}
          readout={
            levelMode === 'replace'
              ? 'Runs between its low and high whatever the level underneath.'
              : levelMode === 'within'
                ? 'Runs inside the level underneath — scaled by it.'
                : `Blended ${draft.blendMode.toLowerCase()} — see Advanced.`
          }
        />
      )}

      {main.length > 0 && <div className="flex flex-col gap-2.5">{main.map(paramControl)}</div>}

      {shape.length > 0 && (
        <Disclosure
          label="Shape"
          summary={shape.map(({ param }) => shapeSummary(param, draft.parameters[param.name] ?? param.defaultValue))}
        >
          {shape.map(paramControl)}
        </Disclosure>
      )}

      <Disclosure
        label="Advanced"
        summary={[
          `phase ${Math.round(draft.phaseOffset * 360)}°`,
          draft.blendMode.toLowerCase(),
          ...(stepTiming ? [`step timing ${draft.stepTiming ? 'on' : 'off'}`] : []),
        ]}
      >
        <SliderRow
          label="Phase"
          unit="°"
          value={draft.phaseOffset}
          min={0}
          max={1}
          step={0.01}
          display={(v) => Math.round(v * 360)}
          fromDisplay={(d) => Math.max(0, Math.min(1, d / 360))}
          displayMax={360}
          readOnly={readOnly}
          onChange={(v, release) => set({ phaseOffset: v }, release)}
        />
        <SelectRow
          label="Blend"
          value={draft.blendMode}
          readOnly={readOnly}
          options={BLEND_MODE_OPTIONS}
          onChange={(blendMode) => set({ blendMode })}
        />
        {stepTiming && (
          <label className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={draft.stepTiming}
              disabled={readOnly}
              onChange={(e) => set({ stepTiming: e.target.checked })}
              className="rounded border-input"
            />
            <span>Step timing</span>
            <span className="text-muted-foreground">the speed steps from head to head</span>
          </label>
        )}
        {scope.heads && (
          <SelectRow
            label="Distribution"
            value={draft.distributionStrategy}
            readOnly={readOnly}
            options={DISTRIBUTION_STRATEGY_OPTIONS}
            onChange={(distributionStrategy) => set({ distributionStrategy })}
          />
        )}
        {scope.elementMode && (
          <SelectRow
            label="Element mode"
            value={draft.elementMode}
            readOnly={readOnly}
            options={ELEMENT_MODE_OPTIONS}
            onChange={(elementMode) => set({ elementMode })}
          />
        )}
        {scope.elementFilter && (
          <SelectRow
            label="Heads"
            value={draft.elementFilter}
            readOnly={readOnly}
            options={ELEMENT_FILTER_OPTIONS}
            onChange={(elementFilter) => set({ elementFilter })}
          />
        )}
      </Disclosure>
    </>
  )
}

// ─── Speed ────────────────────────────────────────────────────────────────────────────────────

/**
 * Speed is a segment, with the master as a chip (Fx board). A beat effect's segment is beats a
 * cycle and its chip the speed master; a wall-clock effect's speed is seconds a cycle (typed — its
 * division is seconds, not beats) and its chip the **rate** master, which scales the cycle. The
 * chip names a concrete master: the frame cannot say null, so a pick of M1 sends master 1's uuid,
 * which the desk treats as the default (`SpeedMasterSelect`'s rule).
 */
function SpeedSection({
  timingSource,
  entry,
  draft,
  readOnly,
  allowUnscaledRate,
  set,
}: {
  timingSource: string | null | undefined
  entry: EffectLibraryEntry
  draft: FxDraft
  readOnly: boolean
  allowUnscaledRate: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const wallClock = (entry.timingSource ?? timingSource) === 'WALL_CLOCK'
  const masterUuid = wallClock ? draft.rateSpeedMasterUuid : draft.speedMasterUuid
  const chip = (
    <MasterChip
      kind={wallClock ? 'rate' : 'speed'}
      value={masterUuid}
      readOnly={readOnly}
      allowUnscaled={wallClock && allowUnscaledRate}
      onChange={(uuid) => set(wallClock ? { rateSpeedMasterUuid: uuid } : { speedMasterUuid: uuid })}
    />
  )
  const pressed = SPEED_BEATS.find((o) => Math.abs(o.value - draft.beatDivision) < 0.001)

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <EditorLabel>Speed</EditorLabel>
        <span className="flex-1" />
        {chip}
      </div>
      {wallClock ? (
        <div className="flex items-center gap-2">
          <SheetField
            label="Cycle seconds"
            unit="s"
            value={draft.beatDivision}
            min={0.05}
            max={600}
            step={0.1}
            disabled={readOnly}
            onEnter={(n) => n > 0 && set({ beatDivision: n })}
          />
          <EditorReadout>seconds a cycle</EditorReadout>
        </div>
      ) : (
        <>
          <ToggleGroup
            type="single"
            size="sm"
            value={pressed ? String(pressed.value) : ''}
            disabled={readOnly}
            onValueChange={(v) => v && set({ beatDivision: Number(v) })}
            aria-label="Speed"
            className="flex w-full flex-wrap justify-start"
          >
            {SPEED_BEATS.map((option) => (
              <ToggleGroupItem
                key={option.value}
                value={String(option.value)}
                aria-label={`${option.label} ${option.value === 1 ? 'beat' : 'beats'} a cycle`}
                className="min-w-8 flex-1 px-1.5 text-xs"
              >
                {option.label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          {/* Any division the segment does not name — a triplet's ⅓ — is typed here, as the old
              form's note values reached it; the segment is the common eight. */}
          <div className="flex items-center gap-2">
            <SheetField
              label="Beats a cycle"
              value={Math.round(draft.beatDivision * 1000) / 1000}
              min={0.01}
              max={256}
              step={0.125}
              disabled={readOnly}
              onEnter={(n) => n > 0 && set({ beatDivision: n })}
            />
            <EditorReadout>{pressed ? 'beats a cycle' : 'beats a cycle — not one of the eight'}</EditorReadout>
          </div>
        </>
      )}
    </div>
  )
}

/** The chip menu's value for a null rate master. Never on the wire. */
const UNSCALED_VALUE = '__unscaled__'

/** The master as a chip — `M2 · 96`, `Unscaled` for a wall-clock effect with no rate master. */
function MasterChip({
  kind,
  value,
  readOnly,
  allowUnscaled = false,
  onChange,
}: {
  kind: 'speed' | 'rate'
  value: string | null
  readOnly: boolean
  /** Offer *Unscaled* (null) — a draft's rate master only. */
  allowUnscaled?: boolean
  onChange: (uuid: string | null) => void
}) {
  const { data: masters } = useSpeedMasterLiveQuery(undefined)
  const listable = (masters ?? []).filter((m) => m.uuid != null)
  const current =
    value != null ? listable.find((m) => m.uuid === value) : kind === 'speed' ? listable.find((m) => m.index === 1) : undefined
  // A stored master the bank does not hold (not loaded yet, or a uuid a sync left dangling) runs on
  // master 1 — the desk's `SpeedMasterBank.slotFor` — for a rate master as much as a speed one;
  // only a null rate master is *Unscaled*.
  const label = current
    ? `M${current.index} · ${Math.round(current.bpm)}`
    : kind === 'rate' && value == null
      ? 'Unscaled'
      : 'M1'
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          disabled={readOnly || listable.length === 0}
          aria-label={kind === 'rate' ? `Rate master: ${label}` : `Speed master: ${label}`}
          className="inline-flex h-5 shrink-0 items-center gap-1 rounded-full border px-2 font-mono text-[10px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          {kind === 'rate' && <span className="font-sans">Rate</span>}
          {label}
          <ChevronDown className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup
          value={current?.uuid ?? (allowUnscaled ? UNSCALED_VALUE : '')}
          onValueChange={(v) => onChange(v === UNSCALED_VALUE ? null : v)}
        >
          {allowUnscaled && (
            <DropdownMenuRadioItem value={UNSCALED_VALUE} className="text-xs">
              Unscaled
            </DropdownMenuRadioItem>
          )}
          {listable.map((m) => (
            <DropdownMenuRadioItem key={m.uuid} value={m.uuid!} className="text-xs">
              M{m.index} · {m.name}
              <span className="ml-2 tabular-nums text-muted-foreground">{Math.round(m.bpm * 10) / 10} bpm</span>
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// ─── Questions, disclosures, rows ─────────────────────────────────────────────────────────────

function Question({
  label,
  value,
  options,
  readOnly,
  onChange,
  readout,
}: {
  label: string
  value: string | null
  options: { value: string; label: string }[]
  readOnly: boolean
  onChange: (value: string) => void
  readout: ReactNode
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <EditorLabel>{label}</EditorLabel>
      <ToggleGroup
        type="single"
        size="sm"
        value={value ?? ''}
        disabled={readOnly}
        aria-label={label}
        onValueChange={(v) => v && onChange(v)}
        className="flex w-full"
      >
        {options.map((option) => (
          <ToggleGroupItem key={option.value} value={option.value} className="min-w-0 flex-1 truncate px-2 text-xs">
            {option.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      <EditorReadout>{readout}</EditorReadout>
    </div>
  )
}

/** A folded section whose summary line says what is inside — *Shape · attack 10% · quad out*. */
function Disclosure({ label, summary, children }: { label: string; summary: string[]; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="flex flex-col gap-2.5">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex min-w-0 items-center gap-1 text-left text-[11px] text-muted-foreground hover:text-foreground"
      >
        {open ? <ChevronDown className="size-3.5 shrink-0" /> : <ChevronRight className="size-3.5 shrink-0" />}
        <span className="font-medium">{label}</span>
        {!open && summary.length > 0 && <span className="truncate">· {summary.join(' · ')}</span>}
      </button>
      {open && <div className="flex flex-col gap-2.5 border-l-2 border-muted pl-2.5">{children}</div>}
    </div>
  )
}

function shapeSummary(param: EffectParameterDef, value: string): string {
  const type = param.type.toLowerCase()
  const label = paramLabel(param.name).toLowerCase()
  if (type === 'easingcurve') return value.toLowerCase().replace(/_/g, ' ')
  if (type === 'boolean') return `${label} ${value === 'true' ? 'on' : 'off'}`
  if (isRatioParam(param)) return `${label} ${Math.round(Number(value) * 100)}%`
  return `${label} ${value}`
}

/**
 * A slider beside an Enter-committing field. The slider works in the stored value and writes as it
 * moves (`push`), landing on release (`flush`); the field shows [display] of it in [unit].
 */
function SliderRow({
  label,
  unit,
  value,
  min,
  max,
  step,
  display = (v) => v,
  fromDisplay = (d) => d,
  displayMin,
  displayMax,
  readOnly,
  description,
  onChange,
}: {
  label: string
  /** The parameter's own sentence, under the control — what the old form drew, kept (session 5). */
  description?: string
  unit?: string
  value: number
  min: number
  max: number
  step: number
  display?: (value: number) => number
  fromDisplay?: (display: number) => number
  displayMin?: number
  displayMax?: number
  readOnly: boolean
  onChange: (value: number, release: boolean) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <div className="flex items-center gap-2">
        <Slider
          aria-label={label}
          value={[Math.max(min, Math.min(max, value))]}
          min={min}
          max={max}
          step={step}
          disabled={readOnly}
          onValueChange={([v]) => v !== undefined && onChange(v, false)}
          onValueCommit={([v]) => v !== undefined && onChange(v, true)}
          className="min-w-[60px] flex-1"
        />
        <SheetField
          label={`${label}${unit ? ` ${unitWord(unit)}` : ''}`}
          unit={unit}
          value={Math.round(display(value) * 100) / 100}
          min={displayMin ?? display(min)}
          max={displayMax ?? display(max)}
          disabled={readOnly}
          onEnter={(d) => onChange(Math.max(min, Math.min(max, fromDisplay(d))), true)}
        />
      </div>
      {description && <EditorReadout>{description}</EditorReadout>}
    </div>
  )
}

function unitWord(unit: string): string {
  if (unit === '%') return 'percent'
  if (unit === '°') return 'degrees'
  if (unit === 's') return 'seconds'
  return unit
}

function SelectRow({
  label,
  value,
  options,
  readOnly,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string; description?: string }[]
  readOnly: boolean
  onChange: (value: string) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <Select value={value} onValueChange={onChange} disabled={readOnly}>
        <SelectTrigger aria-label={label} className="h-7 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value} className="text-xs">
              <span>{option.label}</span>
              {option.description && <span className="ml-2 text-muted-foreground">{option.description}</span>}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/**
 * One parameter, in its role's unit (`paramRole`): a level as a percent, a size in degrees of travel
 * where the head annotates and bytes elsewhere, a centre or a sweep's end as a position on its axis,
 * a ratio as a percent, a curve as a list, a colour through the FX colour pickers.
 */
function ParamControl({
  param,
  role,
  value,
  axes,
  extendedChannels,
  settingProperty,
  readOnly,
  onChange,
}: {
  param: EffectParameterDef
  role: ReturnType<typeof paramRole>
  value: string
  axes: PositionAxes | null
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingProperty: SettingPropertyDescriptor | undefined
  readOnly: boolean
  onChange: (value: string, release: boolean) => void
}) {
  const type = param.type.toLowerCase()
  const label = paramLabel(param.name)
  const num = Number(value) || 0

  if (settingProperty != null && settingProperty.options.length > 0) {
    return (
      <SelectRow
        label="Setting"
        value={value}
        readOnly={readOnly}
        options={settingProperty.options.map((o) => ({ value: String(o.level), label: o.displayName }))}
        onChange={(v) => onChange(v, true)}
      />
    )
  }

  if (type === 'ubyte') {
    const axisName = paramAxis(param.name)
    const axis = axes != null && axisName != null ? axes[axisName] : null
    if ((role === 'size') && axis != null) {
      return (
        <SliderRow
          label={label}
          description={param.description || undefined}
          unit="°"
          value={num}
          min={0}
          max={255}
          step={1}
          display={(v) => Math.round(sizeToDegrees(v, axis))}
          fromDisplay={(d) => degreesToSize(d, axis)}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(clampByte(v)), release)}
        />
      )
    }
    if ((role === 'centre' || role === 'axis') && axis != null) {
      return (
        <SliderRow
          label={label}
          description={param.description || undefined}
          unit="°"
          value={num}
          min={0}
          max={255}
          step={1}
          display={(v) => Math.round(dmxToDegrees(v, axis) ?? 0)}
          fromDisplay={(d) => degreesToDmx(d, axis) ?? 0}
          displayMin={Math.min(axis.degMin, axis.degMax)}
          displayMax={Math.max(axis.degMin, axis.degMax)}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(clampByte(v)), release)}
        />
      )
    }
    if (role === 'level') {
      return (
        <SliderRow
          label={label}
          description={param.description || undefined}
          unit="%"
          value={num}
          min={0}
          max={255}
          step={1}
          display={toPct}
          fromDisplay={(d) => fromPct(Math.max(0, Math.min(100, d)))}
          displayMin={0}
          displayMax={100}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(clampByte(v)), release)}
        />
      )
    }
    return (
      <SliderRow
        label={label}
        description={param.description || undefined}
        value={num}
        min={0}
        max={255}
        step={1}
        readOnly={readOnly}
        onChange={(v, release) => onChange(String(clampByte(v)), release)}
      />
    )
  }

  if (type === 'double' || type === 'float') {
    if (isRatioParam(param)) {
      return (
        <SliderRow
          label={label}
          description={param.description || undefined}
          unit="%"
          value={num}
          min={0}
          max={1}
          step={0.01}
          display={(v) => Math.round(v * 100)}
          fromDisplay={(d) => Math.max(0, Math.min(1, d / 100))}
          displayMin={0}
          displayMax={100}
          readOnly={readOnly}
          onChange={(v, release) => onChange(String(v), release)}
        />
      )
    }
    // The wire declares no range (`FU-FE-FX-PARAM-RANGE`); a double defaulting above 1 is a guess at
    // 0–10, which only a script's effect reaches. The field takes anything typed.
    return (
      <SliderRow
        label={label}
        description={param.description || undefined}
        value={num}
        min={0}
        max={10}
        step={0.1}
        displayMax={1_000_000}
        readOnly={readOnly}
        onChange={(v, release) => onChange(String(v), release)}
      />
    )
  }

  if (type === 'int') {
    // From the declared default and nothing else, so the range never moves under a drag
    // (the rule the old `EffectParameterForm` kept); the field reaches a value outside it.
    const max = Math.max(255, (Number(param.defaultValue) || 0) * 2)
    return (
      <SliderRow
        label={label}
        description={param.description || undefined}
        unit={/Ms$/.test(param.name) ? 'ms' : undefined}
        value={num}
        min={0}
        max={max}
        step={1}
        displayMax={1_000_000}
        readOnly={readOnly}
        onChange={(v, release) => onChange(String(Math.round(v)), release)}
      />
    )
  }

  if (type === 'boolean') {
    return (
      <label className="flex items-center gap-2 text-xs">
        <input
          type="checkbox"
          checked={value === 'true'}
          disabled={readOnly}
          onChange={(e) => onChange(String(e.target.checked), true)}
          className="rounded border-input"
        />
        <span>{label}</span>
        {param.description && <span className="text-muted-foreground">{param.description}</span>}
      </label>
    )
  }

  if (type === 'easingcurve') {
    return (
      <SelectRow
        label={label}
        value={value}
        readOnly={readOnly}
        options={CURVES.map((c) => ({ value: c, label: c.toLowerCase().replace(/_/g, ' ') }))}
        onChange={(v) => onChange(v, true)}
      />
    )
  }

  if (type === 'colour') {
    return (
      <FxColourPicker
        value={value}
        onChange={(v) => onChange(v, false)}
        label={label}
        description={param.description}
        extendedChannels={extendedChannels}
      />
    )
  }

  if (type === 'colourlist') {
    return (
      <FxColourListPicker
        value={value}
        onChange={(v) => onChange(v, true)}
        label={label}
        description={param.description}
        extendedChannels={extendedChannels}
      />
    )
  }

  return <TextParam label={label} value={value} readOnly={readOnly} onEnter={(v) => onChange(v, true)} />
}

/** A parameter the editor has no control for: a text field, committed on Enter. */
function TextParam({
  label,
  value,
  readOnly,
  onEnter,
}: {
  label: string
  value: string
  readOnly: boolean
  onEnter: (value: string) => void
}) {
  const [text, setText] = useState<string | null>(null)
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <input
        aria-label={label}
        disabled={readOnly}
        value={text ?? value}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => setText(null)}
        onKeyDown={(e) => {
          if (e.key !== 'Enter' || text == null) return
          e.preventDefault()
          onEnter(text)
          setText(null)
        }}
        className="h-7 rounded-md border bg-transparent px-2 text-xs"
      />
    </div>
  )
}
