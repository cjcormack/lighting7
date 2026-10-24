import { useState, type ReactNode } from 'react'
import { ArrowLeft, ArrowLeftRight, ArrowRight, ChevronDown, ChevronRight } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import type { FxDirection } from '@/api/fxApi'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { CENTRE_MAX, CENTRE_MIN, centreToDmx, dmxToCentre } from '@/lib/positionScale'
import type { FxLibraryEntry, FxParameterDef } from '@/store/fixtureFx'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import type { SettingAttributeDescriptor } from '@/store/fixtures'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { SheetField } from '../fixtureSheet/SheetField'
import { fromPct, toPct } from '../editor/cellPercent'
import { FxColourPicker } from './FxColourPicker'
import { FxColourListPicker } from './FxColourListPicker'
import { FxPaletteListPicker, FxSlotListPicker } from './FxStepListPickers'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { detectExtendedChannels } from './colourUtils'
import {
  ORDER_OPTIONS,
  isMirroredOrder,
  orderLabel,
  spreadOffsets,
  spreadSummary,
  MAX_SPREAD,
  MAX_SPREAD_COUNT,
  type SpreadShape,
} from '@/lib/spreadShape'
import {
  BLEND_MODE_OPTIONS,
  ELEMENT_FILTER_OPTIONS,
  ELEMENT_MODE_OPTIONS,
  SPEED_STEPS,
  getCycleLabel,
} from './fxConstants'
import {
  blendForLevelMode,
  centreModeOf,
  clampByte,
  defaultParameters,
  hasCentre,
  isLevelFx,
  isRatioParam,
  levelModeOf,
  orderPatch,
  paramRole,
  runsWord,
  timingChoicePatch,
  withCentreMode,
  type CentreMode,
  type FxDraft,
  type FxScope,
  type LevelMode,
} from './fxEditorModel'

const CURVES = [
  'NORMAL',
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
  panCentre: 'Pan',
  tiltCentre: 'Tilt',
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
 * The editor's fields over one draft — Cycle, Amount, Direction and Order, the two questions, the
 * parameters in their roles, Spread, Shape and Advanced — with no write of their own: [set] and [setParam] are the host's. `FxEditor`
 * writes them live into a running effect; `PaletteEditor` (fixture-fx-sheets plan D16) keeps them
 * as the template's draft until Save, so a movement template asks the same *Centre* question
 * through the same rules (`fxEditorModel.ts`) and there is no second copy of either. It replaced
 * `EffectParameterForm`, which was the template editor's draft form until session 5.
 */
export function FxFields({
  entry,
  timingSource,
  draft,
  scope,
  extendedChannels,
  settingAttribute,
  attribute,
  paletteFamily,
  slotsFromRig = false,
  readOnly,
  set,
  setParam,
}: {
  entry: FxLibraryEntry
  /** The instance's or the template's timing source — the library entry's wins where it says. */
  timingSource: string | null | undefined
  draft: FxDraft
  scope: FxScope
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  /** The setting the effect drives, where it drives one — a setting effect's `level`, Slot Steps' slots. */
  settingAttribute: SettingAttributeDescriptor | undefined
  /** The property the effect drives, where it has one — Palette Steps offers palettes naming it. */
  attribute?: string | null
  /** An effect palette's family: Palette Steps offers only that family's palettes. */
  paletteFamily?: AttributeFamily | null
  /**
   * Whether [settingAttribute] stands for the whole rig rather than one head (an effect palette's):
   * Slot Steps then takes typed names too, and a name no option matches is on no patched head.
   */
  slotsFromRig?: boolean
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
  setParam: (name: string, value: string, release: boolean) => void
}) {
  const centred = hasCentre(entry)
  // An absent centre is the library's default (128 on every built-in) — a script or a cue may store a
  // Circle without spelling it, and that is still Around.
  const centreMode = centred ? centreModeOf(draft.blendMode, { ...defaultParameters(entry), ...draft.parameters }) : null
  const level = isLevelFx(entry)
  const levelMode = level ? levelModeOf(draft.blendMode) : null

  const showTiming = scope.timing ?? scope.heads
  // A whole cycle in the phase offset is Bounce's parity (a migrated `pingPong` FX keeps one): the
  // Phase control edits the part within the cycle and leaves it be.
  const phaseCycles = Math.floor(draft.phaseOffset)
  const roles = entry.parameters.map((param) => ({ param, role: paramRole(entry, param) }))
  // Around hides the centre: it is pinned at 128, and showing it would offer a knob that turns the
  // orbit into an offset nobody asked for.
  const main = roles.filter(({ role }) => role !== 'shape' && !(role === 'centre' && centreMode === 'around'))
  const shape = roles.filter(({ role }) => role === 'shape')

  const paramControl = ({ param, role }: { param: FxParameterDef; role: ReturnType<typeof paramRole> }) => (
    <ParamControl
      key={param.name}
      param={param}
      role={role}
      value={draft.parameters[param.name] ?? param.defaultValue}
      extendedChannels={extendedChannels}
      settingAttribute={param.name === 'level' ? settingAttribute : undefined}
      slotSetting={settingAttribute}
      attribute={attribute}
      paletteFamily={paletteFamily}
      slotsFromRig={slotsFromRig}
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
        set={set}
      />

      <AmountSection draft={draft} ordered={scope.heads} readOnly={readOnly} set={set} />

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

      {scope.heads && <SpreadSection draft={draft} headCount={scope.headCount} readOnly={readOnly} set={set} />}

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
          `phase ${Math.round((draft.phaseOffset - phaseCycles) * 360)}°`,
          draft.blendMode.toLowerCase(),
          ...(draft.runs != null ? [runsWord(draft.runs)] : []),
          ...(showTiming ? [`timing per ${draft.timing === 'HEAD' ? 'head' : 'cycle'}`] : []),
        ]}
      >
        <SliderRow
          label="Phase"
          unit="°"
          value={draft.phaseOffset - phaseCycles}
          min={0}
          max={1}
          step={0.01}
          display={(v) => Math.round(v * 360)}
          fromDisplay={(d) => Math.max(0, Math.min(1, d / 360))}
          displayMax={360}
          readOnly={readOnly}
          onChange={(v, release) => set({ phaseOffset: phaseCycles + v }, release)}
        />
        <RunsRow draft={draft} readOnly={readOnly} set={set} />
        <SelectRow
          label="Blend"
          value={draft.blendMode}
          readOnly={readOnly}
          options={BLEND_MODE_OPTIONS}
          onChange={(blendMode) => set({ blendMode })}
        />
        {showTiming && (
          <div className="flex items-center gap-2">
            <EditorLabel>Timing</EditorLabel>
            <span className="flex-1" />
            <ToggleGroup
              type="single"
              size="sm"
              value={draft.timing === 'HEAD' ? 'head' : 'cycle'}
              disabled={readOnly}
              onValueChange={(v) => v && set({ timing: v === 'head' ? 'HEAD' : 'CYCLE' })}
              aria-label="Timing"
            >
              <ToggleGroupItem value="cycle" className="px-2 text-xs">
                Per cycle
              </ToggleGroupItem>
              <ToggleGroupItem value="head" className="px-2 text-xs">
                Per head
              </ToggleGroupItem>
            </ToggleGroup>
          </div>
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

// ─── Amount, direction and order ──────────────────────────────────────────────────────────────

/** The three directions, in the segment's order, each its glyph and its word (on the hover). */
const DIRECTIONS: readonly { value: FxDirection; label: string; Icon: typeof ArrowRight }[] = [
  { value: 'FORWARD', label: 'Forward', Icon: ArrowRight },
  { value: 'BACKWARD', label: 'Backward', Icon: ArrowLeft },
  { value: 'BOUNCE', label: 'Bounce', Icon: ArrowLeftRight },
]

/**
 * Amount, Direction and Order, under Cycle (effects plan D11, §4.1; terminology plan D3). Amount is
 * a percent of the FX that reaches the stage over what is underneath — a Replace at 50 % sits
 * halfway between the level underneath and the FX, an Around movement's radius halves about the
 * position underneath — Direction is how each head runs its cycle, and Order the order the heads'
 * offsets run in, drawn only where there are heads to order ([ordered]). All three are the desk's to
 * apply (P2): this only writes the fields, and edits them in place, so the phase is kept.
 */
function AmountSection({
  draft,
  ordered,
  readOnly,
  set,
}: {
  draft: FxDraft
  ordered: boolean
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const order = draft.order
  const options = ORDER_OPTIONS.map((value) => ({ value, label: orderLabel(value) }))
  // An Order this editor does not offer (a script's, a newer desk's) is still drawn, as itself.
  if (!ORDER_OPTIONS.includes(order)) options.push({ value: order, label: orderLabel(order) })
  return (
    <div className="flex flex-col gap-2.5">
      <SliderRow
        label="Amount"
        unit="%"
        value={draft.amount}
        min={0}
        max={1}
        step={0.01}
        display={(v) => Math.round(v * 100)}
        fromDisplay={(d) => d / 100}
        displayMin={0}
        displayMax={100}
        readOnly={readOnly}
        onChange={(amount, release) => set({ amount }, release)}
      />
      <div className="flex items-end gap-2">
        <div className="flex shrink-0 flex-col gap-1">
          <EditorLabel>Direction</EditorLabel>
          <ToggleGroup
            type="single"
            size="sm"
            value={draft.direction}
            disabled={readOnly}
            onValueChange={(v) => v && set({ direction: v as FxDirection })}
            aria-label="Direction"
          >
            {DIRECTIONS.map(({ value, label, Icon }) => (
              <ToggleGroupItem key={value} value={value} aria-label={label} title={label} className="px-2">
                <Icon className="size-3.5" />
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        </div>
        {ordered && (
          <div className="min-w-0 flex-1">
            <SelectRow
              label="Order"
              value={order}
              readOnly={readOnly}
              options={options}
              onChange={(next) => set(orderPatch(draft, next))}
            />
          </div>
        )}
      </div>
    </div>
  )
}

// ─── Spread ───────────────────────────────────────────────────────────────────────────────────

/** The dials a live effect draws before the rest of its heads fold into *+n* (effects plan §4.1). */
const MAX_DIALS = 16

/** The notional heads an effect palette's dials draw — it has no target to count. */
const NOTIONAL_HEADS = 8

/**
 * The Spread section (effects plan D11, §4.1), on a group, a selection or a fixture with heads, and
 * always on an FX palette: *Spread* %, *Wings*, *Segments* and *Parts*, and a row of phase dials
 * previewing them under the Order chosen above. Its summary line says the non-defaults:
 * *50 % · 2 wings*.
 *
 * The desk shapes the heads (P2): this writes the four fields and draws the dials from
 * `lib/spreadShape.ts`, the one client copy of the rule, pinned to the desk's by a shared vector.
 * Wings are hidden under a mirrored Order (Centre out, Centre in — two wings already).
 */
function SpreadSection({
  draft,
  headCount,
  readOnly,
  set,
}: {
  draft: FxDraft
  headCount: number | undefined
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const order = draft.order
  const shape: SpreadShape = { spread: draft.spread, wings: draft.wings, segments: draft.segments, parts: draft.parts }
  const mirrored = isMirroredOrder(order)
  const unison = order === 'UNIFIED'
  return (
    <Disclosure label="Spread" summary={spreadSummary(order, shape)}>
      {unison ? (
        <EditorReadout>Every head runs in step, so there is no spread to shape.</EditorReadout>
      ) : (
        <>
          <SliderRow
            label="Spread"
            unit="%"
            value={draft.spread}
            min={0}
            max={MAX_SPREAD}
            step={0.01}
            display={(v) => Math.round(v * 100)}
            fromDisplay={(d) => d / 100}
            displayMin={0}
            displayMax={MAX_SPREAD * 100}
            readOnly={readOnly}
            onChange={(spread, release) => set({ spread }, release)}
          />
          <div className="grid grid-cols-3 gap-2">
            {mirrored ? (
              <div className="flex flex-col gap-1">
                <EditorLabel>Wings</EditorLabel>
                <span className="text-[11px] text-muted-foreground" title={`${orderLabel(order)} is two wings already`}>
                  two already
                </span>
              </div>
            ) : (
              <CountRow label="Wings" value={draft.wings} min={1} readOnly={readOnly} onChange={(wings) => set({ wings })} />
            )}
            <CountRow label="Segments" value={draft.segments} min={1} readOnly={readOnly} onChange={(segments) => set({ segments })} />
            <CountRow label="Parts" value={draft.parts} min={0} readOnly={readOnly} onChange={(parts) => set({ parts })} />
          </div>
          <EditorReadout>
            {mirrored ? '' : 'Wings mirror alternate runs · '}Segments move neighbours as one · Parts repeat the spread (0 is off)
          </EditorReadout>
        </>
      )}
      <SpreadDials order={order} shape={shape} headCount={headCount} />
    </Disclosure>
  )
}

/** A whole-number field under its label, committing on Enter — Wings, Segments, Parts. */
function CountRow({
  label,
  value,
  min,
  readOnly,
  onChange,
}: {
  label: string
  value: number
  min: number
  readOnly: boolean
  onChange: (value: number) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <SheetField
        label={label}
        value={value}
        min={min}
        max={MAX_SPREAD_COUNT}
        step={1}
        disabled={readOnly}
        className="w-full"
        onEnter={(n) => {
          const next = Math.round(n)
          if (next >= min && next <= MAX_SPREAD_COUNT) onChange(next)
        }}
      />
    </div>
  )
}

/**
 * The row of phase dials (effects plan §4.1): one per head — eight notional heads on an FX
 * palette, the FX's own heads live, up to sixteen and then *+n* — each hand at the head's offset
 * through the cycle, as the desk will run it. A preview: nothing here is sent.
 */
function SpreadDials({ order, shape, headCount }: { order: string; shape: SpreadShape; headCount: number | undefined }) {
  const heads = headCount ?? NOTIONAL_HEADS
  const { offsets } = spreadOffsets(order, heads, shape)
  const shown = offsets.slice(0, MAX_DIALS)
  const more = offsets.length - shown.length
  const degrees = shown.map((o) => Math.round((((o % 1) + 1) % 1) * 360))
  return (
    <div className="flex flex-col gap-1">
      <div
        role="img"
        aria-label={`Phase of ${heads} head${heads === 1 ? '' : 's'}: ${degrees.map((d) => `${d}°`).join(', ')}${more > 0 ? ` and ${more} more` : ''}`}
        data-spread-dials
        className="flex flex-wrap items-center gap-1"
      >
        {degrees.map((deg, i) => (
          <PhaseDial key={i} degrees={deg} />
        ))}
        {more > 0 && <span className="text-[11px] tabular-nums text-muted-foreground">+{more}</span>}
      </div>
      <EditorReadout>{headCount == null ? 'Previewed over eight heads' : `Over its ${heads} head${heads === 1 ? '' : 's'}`}</EditorReadout>
    </div>
  )
}

/** One head's phase: a ring and a hand from the top, clockwise through the cycle. */
function PhaseDial({ degrees }: { degrees: number }) {
  const radians = (degrees * Math.PI) / 180
  const x = 9 + 6 * Math.sin(radians)
  const y = 9 - 6 * Math.cos(radians)
  return (
    <svg viewBox="0 0 18 18" className="size-[18px] shrink-0 text-muted-foreground" aria-hidden data-phase={degrees}>
      <circle cx="9" cy="9" r="7.5" fill="none" stroke="currentColor" strokeWidth="1" opacity="0.5" />
      <line x1="9" y1="9" x2={x} y2={y} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" className="text-primary" />
    </svg>
  )
}

/**
 * *Runs* under Advanced (effects plan D9, §4.1): *Forever*, or a count of cycles after which the
 * effect stops, each head handing back to what is underneath after its own. The desk counts them
 * on the effect's clock from when the count is set.
 */
function RunsRow({
  draft,
  readOnly,
  set,
}: {
  draft: FxDraft
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>Runs</EditorLabel>
      <div className="flex items-center gap-2">
        <ToggleGroup
          type="single"
          size="sm"
          value={draft.runs == null ? 'forever' : 'count'}
          disabled={readOnly}
          onValueChange={(v) => {
            if (v === 'forever') set({ runs: null })
            else if (v === 'count' && draft.runs == null) set({ runs: 1 })
          }}
          aria-label="Runs"
        >
          <ToggleGroupItem value="forever" className="px-2 text-xs">
            Forever
          </ToggleGroupItem>
          <ToggleGroupItem value="count" className="px-2 text-xs">
            Count
          </ToggleGroupItem>
        </ToggleGroup>
        {draft.runs != null && (
          <SheetField
            label="Runs"
            value={draft.runs}
            min={1}
            max={999}
            step={1}
            disabled={readOnly}
            onEnter={(n) => n >= 1 && set({ runs: Math.round(n) })}
          />
        )}
      </div>
      <EditorReadout>
        {draft.runs == null ? 'runs until it is stopped' : 'cycles, then each head hands back to what is underneath'}
      </EditorReadout>
    </div>
  )
}

// ─── Cycle ────────────────────────────────────────────────────────────────────────────────────

/** The chip menu's value for *Seconds*. Never on the wire — the wire says `seconds`. */
const SECONDS_VALUE = '__seconds__'

/**
 * Cycle is a segment with the timing as a chip (Fx board; venue-visit plan D7). The chip is one
 * question for every effect — *Seconds*, or a master — and the body follows it:
 *
 * - **A master**: a beat effect's segment is beats a cycle ([SPEED_STEPS], the one spelling the
 *   chips read too), with any other division typed beside it. A wall-clock effect's master is its
 *   rate master, which scales the seconds it types by `bpm / 120`.
 * - **Seconds**: the cycle in seconds, typed, with no master.
 *
 * Moving a beat effect between the two keeps its cycle: the division is converted at the master's
 * live tempo (4 beats at 120 BPM is 2 s), so the light does not jump. A wall-clock effect's number
 * is seconds either way and is left alone. The chip names a concrete master — a pick of M1 sends
 * master 1's uuid, which the desk treats as the default (`SpeedMasterSelect`'s rule).
 */
function SpeedSection({
  timingSource,
  entry,
  draft,
  readOnly,
  set,
}: {
  timingSource: string | null | undefined
  entry: FxLibraryEntry
  draft: FxDraft
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const { data: masters } = useSpeedMasterLiveQuery(undefined)
  const listable = (masters ?? []).filter((m) => m.uuid != null)
  const wallClock = (entry.timingSource ?? timingSource) === 'WALL_CLOCK'
  // A draft built by hand may leave the flag out for a wall-clock effect's Seconds; read it as D7 does.
  const seconds = draft.seconds || (wallClock && draft.rateSpeedMasterUuid == null)
  const masterUuid = wallClock ? draft.rateSpeedMasterUuid : draft.speedMasterUuid
  const bpmOf = (uuid: string | null) =>
    (uuid != null ? listable.find((m) => m.uuid === uuid) : listable.find((m) => m.index === 1))?.bpm ?? 0

  const chip = (
    <TimingChip
      wallClock={wallClock}
      seconds={seconds}
      value={masterUuid}
      readOnly={readOnly}
      onChange={(uuid) => set(timingChoicePatch({ ...draft, seconds }, wallClock, uuid, bpmOf), true)}
    />
  )
  const pressed = SPEED_STEPS.find((o) => Math.abs(o.value - draft.cycle) < 0.001)

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <EditorLabel>Cycle</EditorLabel>
        <span className="flex-1" />
        {chip}
      </div>
      {seconds || wallClock ? (
        <div className="flex items-center gap-2">
          <SheetField
            label="Cycle seconds"
            unit="s"
            value={draft.cycle}
            min={0.05}
            max={600}
            step={0.1}
            disabled={readOnly}
            onEnter={(n) => n > 0 && set({ cycle: n })}
          />
          <EditorReadout>
            {seconds ? 'seconds a cycle · follows no master' : 'seconds a cycle at 120 BPM, scaled by the master'}
          </EditorReadout>
        </div>
      ) : (
        <>
          <ToggleGroup
            type="single"
            size="sm"
            value={pressed ? String(pressed.value) : ''}
            disabled={readOnly}
            onValueChange={(v) => v && set({ cycle: Number(v) })}
            aria-label="Cycle"
            // Two rows of four: `h-auto` lifts the group's one-row `h-8`, which the second row overran.
            className="grid h-auto w-full grid-cols-4 gap-px"
          >
            {SPEED_STEPS.map((option) => (
              <ToggleGroupItem
                key={option.value}
                value={String(option.value)}
                aria-label={`${option.label} a cycle`}
                className="min-w-0 px-1 text-[11px] whitespace-nowrap"
              >
                {option.label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          {/* Any division the segment does not name — a triplet's ⅓ — is typed here; the segment is
              the common eight. */}
          <div className="flex items-center gap-2">
            <SheetField
              label="Beats a cycle"
              value={Math.round(draft.cycle * 1000) / 1000}
              min={0.01}
              max={256}
              step={0.125}
              disabled={readOnly}
              onEnter={(n) => n > 0 && set({ cycle: n })}
            />
            <EditorReadout>{pressed ? 'beats a cycle' : `${getCycleLabel(draft.cycle)} a cycle`}</EditorReadout>
          </div>
        </>
      )}
    </div>
  )
}

/**
 * The timing as a chip — `M2 · 96`, `Rate M2 · 96` on a wall-clock effect, or `Seconds` (D7). Its
 * menu is *Seconds* and then every master: one question for every effect, where a wall-clock
 * effect's master is its rate master.
 */
function TimingChip({
  wallClock,
  seconds,
  value,
  readOnly,
  onChange,
}: {
  wallClock: boolean
  seconds: boolean
  value: string | null
  readOnly: boolean
  onChange: (uuid: string | null) => void
}) {
  const { data: masters } = useSpeedMasterLiveQuery(undefined)
  const listable = (masters ?? []).filter((m) => m.uuid != null)
  // A beat effect's null master is master 1. A stored master the bank does not hold (not loaded
  // yet, or a uuid a sync left dangling) runs on master 1 — the desk's `SpeedMasterBank.slotFor`.
  const current = seconds
    ? undefined
    : (value != null ? listable.find((m) => m.uuid === value) : undefined) ?? listable.find((m) => m.index === 1)
  const label = seconds ? 'Seconds' : current ? `M${current.index} · ${Math.round(current.bpm)}` : 'M1'
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          // Disabled until the bank has loaded too: a move onto or off Seconds converts the division
          // at the master's tempo, and with no tempo to read it would keep the number and change the
          // speed.
          disabled={readOnly || listable.length === 0}
          aria-label={`Timing: ${wallClock && !seconds ? 'rate ' : ''}${label}`}
          className="inline-flex h-5 shrink-0 items-center gap-1 rounded-full border px-2 font-mono text-[10px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          {wallClock && !seconds && <span className="font-sans">Rate</span>}
          {label}
          <ChevronDown className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup
          value={seconds ? SECONDS_VALUE : (current?.uuid ?? '')}
          onValueChange={(v) => onChange(v === SECONDS_VALUE ? null : v)}
        >
          <DropdownMenuRadioItem value={SECONDS_VALUE} className="text-xs">
            Seconds
            <span className="ml-2 text-muted-foreground">its own cycle, no master</span>
          </DropdownMenuRadioItem>
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

function shapeSummary(param: FxParameterDef, value: string): string {
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
 * One parameter, in its role's unit (`paramRole`): a level as a percent, a size in DMX steps, a
 * centre or a sweep's end centre-relative on its axis,
 * a ratio as a percent, a curve as a list, a colour through the FX colour pickers.
 */
function ParamControl({
  param,
  role,
  value,
  extendedChannels,
  settingAttribute,
  slotSetting,
  attribute,
  paletteFamily,
  slotsFromRig = false,
  readOnly,
  onChange,
}: {
  param: FxParameterDef
  role: ReturnType<typeof paramRole>
  value: string
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingAttribute: SettingAttributeDescriptor | undefined
  /** The setting a slot list names options of, where the effect drives one. */
  slotSetting?: SettingAttributeDescriptor
  attribute?: string | null
  paletteFamily?: AttributeFamily | null
  slotsFromRig?: boolean
  readOnly: boolean
  onChange: (value: string, release: boolean) => void
}) {
  const type = param.type.toLowerCase()
  const label = paramLabel(param.name)
  const num = Number(value) || 0

  if (settingAttribute != null && settingAttribute.options.length > 0) {
    return (
      <SelectRow
        label="Setting"
        value={value}
        readOnly={readOnly}
        options={settingAttribute.options.map((o) => ({ value: String(o.level), label: o.displayName }))}
        onChange={(v) => onChange(v, true)}
      />
    )
  }

  if (type === 'ubyte') {
    // A centre or a sweep's end is a point on its axis, read centre-relative on every head
    // (venue-visit plan D3): `-128..127`, 0 the centre of travel (DMX 128, where Around pins it).
    // A size (a radius, a range) is a distance and stays in DMX steps — never offset. The value is
    // raw DMX either way, so no head's range is consulted.
    if (role === 'centre' || role === 'axis') {
      return (
        <SliderRow
          label={label}
          description={param.description || undefined}
          value={num}
          min={0}
          max={255}
          step={1}
          display={(v) => dmxToCentre(v)}
          fromDisplay={(d) => centreToDmx(d)}
          displayMin={CENTRE_MIN}
          displayMax={CENTRE_MAX}
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

  // The step lists (effects plan §3.6): each change is a whole list, so it lands on release.
  if (type === 'palettelist') {
    return (
      <FxPaletteListPicker
        label={label}
        description={undefined}
        value={value}
        attribute={attribute}
        family={paletteFamily}
        readOnly={readOnly}
        onChange={(v) => onChange(v, true)}
      />
    )
  }

  if (type === 'slotlist') {
    return (
      <FxSlotListPicker
        label={label}
        value={value}
        setting={slotSetting}
        fromRig={slotsFromRig}
        readOnly={readOnly}
        onChange={(v) => onChange(v, true)}
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
