import { useEffect, useRef, useState, type ReactElement, type ReactNode } from 'react'
import { ArrowLeft, ArrowLeftRight, ArrowRight, ChevronDown, ChevronRight } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { cn } from '@/lib/utils'
import type { FxDirection } from '@/api/fxApi'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Sheet, SheetBody, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { centreToDmx, dmxToCentre } from '@/lib/positionScale'
import type { FxLibraryEntry, FxParamUnit, FxParameterDef } from '@/store/fixtureFx'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import type { SettingAttributeDescriptor } from '@/store/fixtures'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { SheetField } from '../fixtureSheet/SheetField'
import { fromPct, toPct } from '../editor/cellPercent'
import { CENTRE_PARAMS } from './centreMode'
import { FxColourPicker } from './FxColourPicker'
import { FxColourListPicker } from './FxColourListPicker'
import { FxPaletteListPicker, FxSlotListPicker } from './FxStepListPickers'
import { HeadLadder, previewLadderHeads } from './HeadLadder'
import { groupingSentence, laterByReadout, neighbourGap } from './acrossHeads'
import { useFxChipSheet } from './fxChipSheet'
import { paramPresetsOf, type ParamPresets } from './fxParamPresets'
import { SHAPE_ICON_BOX, shapeIconPath } from './fxShapeIcons'
import { FX_SHAPES, matchesShape, shapeOf, spreadFieldsOf, type FxShape } from './fxShapes'
import { useFingerSized } from '../fixtureSheet/sheetContext'
import { useFxGraphLink, type GraphMark, type MatchSource } from './fxGraphLink'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { detectExtendedChannels } from './colourUtils'
import {
  ORDER_OPTIONS,
  isMirroredOrder,
  orderLabel,
  spreadOffsets,
  MAX_SPREAD,
  MAX_SPREAD_COUNT,
  MAX_STEP_SPREAD,
  stepCountOf,
  type SpreadShape,
} from '@/lib/spreadShape'
import {
  BLEND_MODE_OPTIONS,
  ELEMENT_FILTER_OPTIONS,
  ELEMENT_MODE_OPTIONS,
  NOTIONAL_HEADS,
  SPEED_STEPS,
  getCycleLabel,
} from './fxConstants'
import {
  blendForLevelMode,
  centreFlip,
  centreModeOf,
  clampByte,
  defaultParameters,
  draftOf,
  hasCentre,
  fxSectionName,
  isLevelFx,
  isParamShown,
  levelModeOf,
  orderPatch,
  paramView,
  runsWord,
  timingChoicePatch,
  withCentreMode,
  type CentreMode,
  type FxDraft,
  type FxScope,
  type LevelMode,
  type ParamView,
} from './fxEditorModel'

/** The desk's `EasingCurve`s, for a desk whose library names none on an easing-curve parameter. */
const CURVES = [
  'LINEAR',
  'SINE_IN',
  'SINE_OUT',
  'SINE_IN_OUT',
  'QUAD_IN',
  'QUAD_OUT',
  'QUAD_IN_OUT',
  'CUBIC_IN',
  'CUBIC_OUT',
  'CUBIC_IN_OUT',
  'STEP',
  'STEP_HALF',
] as const

/**
 * A parameter as drawn: alone, a low drawn with its high as one range (its `pair`), or a number
 * drawn as a segment of its common values ending in a typed cell ([ParamPresets]).
 */
type ParamItem =
  | { kind: 'one'; param: FxParameterDef; view: ParamView }
  | { kind: 'pair'; low: FxParameterDef; high: FxParameterDef; lowView: ParamView; highView: ParamView }
  | { kind: 'presets'; param: FxParameterDef; view: ParamView; presets: ParamPresets; alongside?: FxParameterDef }

/**
 * The parameters to draw, in order: each shown one ([isParamShown]) once, a pair at whichever of
 * its two comes first, a preset segment's choice ([ParamPresets.alongside]) inside the segment while
 * both are shown, [hidden] left out.
 */
function paramItems(
  entry: FxLibraryEntry,
  parameters: Record<string, string>,
  hidden: (param: FxParameterDef) => boolean,
): ParamItem[] {
  const shown = entry.parameters.filter((p) => !hidden(p) && isParamShown(entry, p, parameters))
  const byName = new Map(shown.map((p) => [p.name, p]))
  const placed = new Set<string>()
  const items: ParamItem[] = []
  for (const param of shown) {
    if (placed.has(param.name)) continue
    const presets = isNumeric(param) ? paramPresetsOf(entry, param.name) : null
    if (presets != null) {
      const alongside = presets.alongside != null ? byName.get(presets.alongside.param) : undefined
      placed.add(param.name)
      if (alongside != null) placed.add(alongside.name)
      items.push({ kind: 'presets', param, view: paramView(entry, param), presets, alongside })
      continue
    }
    if (shown.some((p) => p !== param && paramPresetsOf(entry, p.name)?.alongside?.param === param.name && isNumeric(p))) {
      // Drawn inside its number's segment, which comes later in the list.
      continue
    }
    const low = param.pair != null && byName.has(param.pair) ? param : shown.find((p) => p.pair === param.name && p !== param)
    const high = low === param ? byName.get(param.pair ?? '') : low != null ? param : undefined
    if (low != null && high != null && !placed.has(low.name) && !placed.has(high.name)) {
      placed.add(low.name).add(high.name)
      items.push({ kind: 'pair', low, high, lowView: paramView(entry, low), highView: paramView(entry, high) })
    } else {
      placed.add(param.name)
      items.push({ kind: 'one', param, view: paramView(entry, param) })
    }
  }
  return items
}

function isNumeric(param: FxParameterDef): boolean {
  return ['ubyte', 'double', 'float', 'int'].includes(param.type.toLowerCase())
}

const itemFold = (item: ParamItem) => (item.kind === 'pair' ? item.lowView.fold : item.view.fold)
const itemKey = (item: ParamItem) => (item.kind === 'pair' ? item.low.name : item.param.name)

/** The FX a header can swap this one for, and the swap (fx-settings plan D9). */
export interface FxTypeSwap {
  options: readonly FxLibraryEntry[]
  onSwap: (entry: FxLibraryEntry) => void
}

/**
 * The editor's fields over one draft, in four questions and Advanced (fx-settings plan D9):
 * **Timing**, one strip of chips — Cycle, the master or Seconds, Direction, Runs; **the FX's own
 * section**, named for what it holds (*Levels*, *Colours*, *Path*, *Steps*, *Flicker*, a script
 * FX's *Parameters*), its header's FX name the type switcher ([swap]), `detail` parameters folded
 * under *More*; **Across the heads** (D10), where there are heads; **Mix**, Amount
 * beside the family's question; **Advanced**, Phase, *One cycle for*, the head filter and the raw
 * blend. No write of its own: [set] and [setParam] are the host's. `FxEditor` writes them live into
 * a running FX; `PaletteEditor` and the cue's FX tab keep them as a draft until it is stored, so
 * every host asks the same questions through the same rules (`fxEditorModel.ts`).
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
  swap,
  layout = 'editor',
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
  /** The header's type switcher. Absent, the FX's name is drawn alone. */
  swap?: FxTypeSwap
  /**
   * `inspector` is the FX editor's, with its wider spacing and the head ladder from the desk's
   * preview; `editor` is every other host's, whose ladder is `lib/spreadShape.ts`'s.
   */
  layout?: 'editor' | 'inspector'
}) {
  const centred = hasCentre(entry, draft.parameters)
  // An absent centre is the library's default (128 on every built-in) — a script or a cue may store a
  // Circle without spelling it, and that is still Around.
  const centreMode = centred ? centreModeOf(draft.blendMode, { ...defaultParameters(entry), ...draft.parameters }) : null
  const level = isLevelFx(entry, attribute)
  const levelMode = level ? levelModeOf(draft.blendMode) : null

  const showTiming = scope.timing ?? scope.heads
  // A whole cycle in the phase offset is Bounce's parity (a migrated `pingPong` FX keeps one): the
  // Phase control edits the part within the cycle and leaves it be.
  const phaseCycles = Math.floor(draft.phaseOffset)
  // Around hides the centre: it is pinned at 128, and showing it would offer a knob that turns the
  // orbit into an offset nobody asked for.
  const items = paramItems(
    entry,
    draft.parameters,
    (param) => centreMode === 'around' && (CENTRE_PARAMS as readonly string[]).includes(param.name),
  )
  const main = items.filter((item) => itemFold(item) === 'main')
  const more = items.filter((item) => itemFold(item) === 'detail')

  // Blank is the default, as the desk reads it (`TypedParams.raw`).
  const valueOf = (param: FxParameterDef) => draft.parameters[param.name] || param.defaultValue
  const setParams = (changes: Record<string, string>, release: boolean) =>
    set({ parameters: { ...draft.parameters, ...changes } }, release)
  const paramControl = (item: ParamItem) => {
    if (item.kind === 'pair') {
      return (
        <RangeRow
          key={itemKey(item)}
          low={item.low}
          high={item.high}
          lowView={item.lowView}
          highView={item.highView}
          lowValue={valueOf(item.low)}
          highValue={valueOf(item.high)}
          readOnly={readOnly}
          onChange={setParams}
        />
      )
    }
    if (item.kind === 'presets') {
      return (
        <PresetRow
          key={itemKey(item)}
          param={item.param}
          view={item.view}
          presets={item.presets}
          value={valueOf(item.param)}
          alongside={item.alongside}
          alongsideValue={item.alongside != null ? valueOf(item.alongside) : undefined}
          readOnly={readOnly}
          onChange={setParams}
        />
      )
    }
    return (
      <ParamControl
        key={itemKey(item)}
        param={item.param}
        view={item.view}
        value={valueOf(item.param)}
        extendedChannels={extendedChannels}
        settingAttribute={item.param.name === 'level' ? settingAttribute : undefined}
        slotSetting={settingAttribute}
        attribute={attribute}
        paletteFamily={paletteFamily}
        slotsFromRig={slotsFromRig}
        readOnly={readOnly}
        onChange={(value, release) => {
          const flip = centreFlip(entry, draft, item.param.name, value)
          if (flip != null) set(flip, release)
          else setParam(item.param.name, value, release)
        }}
      />
    )
  }

  const gap = layout === 'inspector' ? 'gap-2.5' : 'gap-2'
  const centreQuestion = centred && (
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
  )
  const levelQuestion = level && (
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
  )
  const across = (scope.heads || scope.elementMode) && (
    <Section label="Across the heads" gap={gap}>
      {scope.elementMode && (
        <div className="flex items-center gap-2">
          <EditorLabel>Over</EditorLabel>
          <span className="flex-1" />
          <ToggleGroup
            type="single"
            size="sm"
            value={draft.elementMode}
            disabled={readOnly}
            onValueChange={(v) => v && set({ elementMode: v })}
            aria-label="Over"
          >
            {ELEMENT_MODE_OPTIONS.map(({ value, label, description }) => (
              <ToggleGroupItem key={value} value={value} title={description} className="px-2 text-xs">
                {label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        </div>
      )}
      {scope.heads && (
        <AcrossHeads
          draft={draft}
          entry={entry}
          headCount={scope.headCount}
          inSeconds={draft.seconds || (entry.timingSource ?? timingSource) === 'WALL_CLOCK'}
          readOnly={readOnly}
          set={set}
          fromPreview={layout === 'inspector'}
        />
      )}
    </Section>
  )
  const moreSummaries = more.map((item) =>
    item.kind === 'pair'
      ? `${foldSummary(item.low, item.lowView, valueOf(item.low))}–${foldSummary(item.high, item.highView, valueOf(item.high))}`
      : foldSummary(item.param, item.view, valueOf(item.param)),
  )
  const filterLabel = ELEMENT_FILTER_OPTIONS.find((o) => o.value === draft.elementFilter)?.label ?? draft.elementFilter
  const advancedSummary = [
    `phase ${Math.round((draft.phaseOffset - phaseCycles) * 360)}°`,
    ...(showTiming ? [`one cycle for ${draft.timing === 'HEAD' ? 'each head' : 'all heads'}`] : []),
    ...(scope.elementFilter ? [`${filterLabel.toLowerCase()} heads`] : []),
    draft.blendMode.toLowerCase(),
  ]

  return (
    <>
      <Section label="Timing" gap={gap}>
        <TimingStrip timingSource={timingSource} entry={entry} draft={draft} readOnly={readOnly} set={set} />
      </Section>

      <Section label={fxSectionName(entry, attribute)} gap={gap} aside={<TypeSwitcher entry={entry} swap={swap} readOnly={readOnly} />}>
        {main.map(paramControl)}
        {more.length > 0 && (
          <Disclosure label="More" summary={moreSummaries}>
            {more.map(paramControl)}
          </Disclosure>
        )}
        {main.length === 0 && more.length === 0 && <EditorReadout>Nothing to set but its timing.</EditorReadout>}
      </Section>

      {across}

      <Section label="Mix" gap={gap}>
        <Lights mark="amount">
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
        </Lights>
        {centreQuestion}
        {levelQuestion}
        {!centred && !level && <EditorReadout>{blendReadout(draft.blendMode)} · Blend in Advanced</EditorReadout>}
      </Section>

      <Disclosure label="Advanced" summary={advancedSummary}>
        <Lights mark="starts">
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
        </Lights>
        {showTiming && (
          <div className="flex items-center gap-2">
            <EditorLabel>One cycle for</EditorLabel>
            <span className="flex-1" />
            <ToggleGroup
              type="single"
              size="sm"
              value={draft.timing === 'HEAD' ? 'head' : 'cycle'}
              disabled={readOnly}
              onValueChange={(v) => v && set({ timing: v === 'head' ? 'HEAD' : 'CYCLE' })}
              aria-label="One cycle for"
            >
              <ToggleGroupItem value="cycle" className="px-2 text-xs">
                All heads
              </ToggleGroupItem>
              <ToggleGroupItem value="head" className="px-2 text-xs">
                Each head
              </ToggleGroupItem>
            </ToggleGroup>
          </div>
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
        <SelectRow
          label="Blend"
          value={draft.blendMode}
          readOnly={readOnly}
          options={BLEND_MODE_OPTIONS}
          onChange={(blendMode) => set({ blendMode })}
        />
      </Disclosure>
    </>
  )
}

/** What a blend does to what is underneath, for an FX that asks no question of its own. */
function blendReadout(blendMode: string): string {
  switch (blendMode.toUpperCase()) {
    case 'OVERRIDE':
      return 'Replaces what is underneath'
    case 'ADDITIVE':
      return 'Adds to what is underneath'
    case 'MULTIPLY':
      return 'Scales what is underneath'
    case 'MAX':
      return 'Takes the higher of it and what is underneath'
    case 'MIN':
      return 'Takes the lower of it and what is underneath'
    default:
      return `Blended ${blendMode.toLowerCase()}`
  }
}

/** One of the editor's questions: its heading, with [aside] at the heading's end. */
function Section({ label, aside, gap, children }: { label: string; aside?: ReactNode; gap: string; children: ReactNode }) {
  return (
    <section aria-label={label} className={cn('flex flex-col', gap)}>
      <div className="flex min-w-0 items-center gap-2">
        <span className="text-[11px] font-medium text-muted-foreground">{label}</span>
        {aside != null && (
          <>
            <span className="flex-1" />
            {aside}
          </>
        )}
      </div>
      {children}
    </section>
  )
}

/**
 * The FX's name at the head of its section, and the type switcher where the host can swap it
 * (fx-settings plan D9): a pick becomes that type with its own defaults, the picker's swap.
 */
function TypeSwitcher({ entry, swap, readOnly }: { entry: FxLibraryEntry; swap: FxTypeSwap | undefined; readOnly: boolean }) {
  const others = swap?.options.filter((o) => o.name !== entry.name) ?? []
  if (swap == null || others.length === 0) {
    return <span className="min-w-0 truncate text-[11px] text-muted-foreground">{entry.name}</span>
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          disabled={readOnly}
          aria-label={`FX type: ${entry.name}`}
          title="Swap for another FX — its own settings, the phase kept"
          className="inline-flex h-6 min-w-0 items-center gap-1 rounded-md border px-2 text-[11px] hover:bg-accent disabled:opacity-50"
        >
          <span className="truncate">{entry.name}</span>
          <ChevronDown className="size-3 shrink-0" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup
          value={entry.name}
          onValueChange={(name) => {
            const next = swap.options.find((o) => o.name === name)
            if (next != null && next.name !== entry.name) swap.onSwap(next)
          }}
        >
          {swap.options.map((option) => (
            <DropdownMenuRadioItem key={option.name} value={option.name} className="text-xs">
              {option.name}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// ─── Timing ───────────────────────────────────────────────────────────────────────────────────

/** The three directions, in the segment's order, each its glyph and its word (on the hover). */
const DIRECTIONS: readonly { value: FxDirection; label: string; Icon: typeof ArrowRight }[] = [
  { value: 'FORWARD', label: 'Forward', Icon: ArrowRight },
  { value: 'BACKWARD', label: 'Backward', Icon: ArrowLeft },
  { value: 'BOUNCE', label: 'Bounce', Icon: ArrowLeftRight },
]

/** The chip every Timing control is drawn as. */
const CHIP_CLASS =
  'inline-flex h-7 shrink-0 items-center gap-1 rounded-md border px-2 font-mono text-[11px] hover:bg-accent disabled:opacity-50'

/**
 * Timing, one strip (fx-settings plan D9): **Cycle** as a chip opening its eight steps, a typed
 * field and *Same cycle as…*; the **master** or *Seconds* ([TimingChip]); **Direction**, three
 * glyphs; **Runs** as a chip opening ×1 to ×4 and a count.
 *
 * Moving a beat effect between a master and Seconds keeps its cycle: the division is converted at
 * the master's live tempo (4 beats at 120 BPM is 2 s), so the light does not jump. A wall-clock
 * effect's number is seconds either way and is left alone. The chip names a concrete master — a
 * pick of M1 sends master 1's uuid, which the desk treats as the default (`SpeedMasterSelect`'s rule).
 */
function TimingStrip({
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

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Lights mark="cycle" className="flex items-center gap-1.5">
        <CycleChip draft={draft} wallClock={wallClock} seconds={seconds} master1={listable.find((m) => m.index === 1)?.uuid ?? null} readOnly={readOnly} set={set} />
        <TimingChip
          wallClock={wallClock}
          seconds={seconds}
          value={masterUuid}
          readOnly={readOnly}
          onChange={(uuid) => set(timingChoicePatch({ ...draft, seconds }, wallClock, uuid, bpmOf), true)}
        />
      </Lights>
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
      <Lights mark="holding" className="flex">
        <RunsChip draft={draft} readOnly={readOnly} set={set} />
      </Lights>
    </div>
  )
}

/**
 * A chip's choices: a popover on a desk, a bottom sheet with finger-sized rows on the phone host or
 * below the FX editor's 760 arm (§4). Either takes focus when it opens and gives it back to the
 * chip on Escape.
 */
function ChipMenu({ title, trigger, children }: { title: string; trigger: ReactElement; children: ReactNode }) {
  const sheet = useFxChipSheet()
  if (sheet) {
    return (
      <Sheet>
        <SheetTrigger asChild>{trigger}</SheetTrigger>
        <SheetContent side="bottom" aria-describedby={undefined}>
          <SheetHeader>
            <SheetTitle>{title}</SheetTitle>
          </SheetHeader>
          <SheetBody data-chip-sheet className="[&_[role=radio]]:min-h-11 [&_input]:min-h-11">
            {children}
          </SheetBody>
        </SheetContent>
      </Sheet>
    )
  }
  return (
    <Popover>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent align="start" aria-label={title} className="flex w-72 flex-col gap-2 p-3">
        {children}
      </PopoverContent>
    </Popover>
  )
}

/**
 * Cycle as a chip — `1 bar ▾`, `2 s ▾` (fx-settings plan D9). It opens a beat effect's eight steps
 * ([SPEED_STEPS], the one spelling every chip reads), with any other division typed after them and
 * lit while the cycle is none of the eight; on Seconds or the wall clock the cycle in seconds,
 * typed; and *Same cycle as…* where the FX editor has other FX to copy from.
 */
function CycleChip({
  draft,
  wallClock,
  seconds,
  master1,
  readOnly,
  set,
}: {
  draft: FxDraft
  wallClock: boolean
  seconds: boolean
  master1: string | null
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const link = useFxGraphLink()
  const inSeconds = seconds || wallClock
  const pressed = SPEED_STEPS.find((o) => Math.abs(o.value - draft.cycle) < 0.001)
  const label = inSeconds ? `${Math.round(draft.cycle * 100) / 100} s` : getCycleLabel(draft.cycle)
  return (
    <ChipMenu
      title="Cycle"
      trigger={
        <button type="button" disabled={readOnly} aria-label={`Cycle: ${label}`} className={CHIP_CLASS}>
          {label}
          <ChevronDown className="size-3 text-muted-foreground" />
        </button>
      }
    >
      <div className="flex items-center gap-2">
        <EditorLabel>Cycle</EditorLabel>
        <span className="flex-1" />
        {link != null && (
          <SameAs
            label="Same cycle as"
            others={link.others}
            readOnly={readOnly}
            // A cycle in seconds copies onto either clock; one on a master only onto the same clock.
            refuse={(source) =>
              !draftOf(source.effect).seconds && source.wallClock !== wallClock
                ? `${wallClock ? 'This FX runs on the wall clock' : 'This FX runs on the beat'}, and that one does not`
                : null
            }
            onPick={(effect) => set(cyclePatchFrom(draftOf(effect), wallClock, master1), true)}
          />
        )}
      </div>
      {inSeconds ? (
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
          <div className="flex items-center gap-2">
            <TypedCell lit={pressed == null}>
              <SheetField
                label="Beats a cycle"
                value={Math.round(draft.cycle * 1000) / 1000}
                min={0.01}
                max={256}
                step={0.125}
                disabled={readOnly}
                onEnter={(n) => n > 0 && set({ cycle: n })}
              />
            </TypedCell>
            <EditorReadout>{pressed ? 'beats a cycle' : `${getCycleLabel(draft.cycle)} a cycle`}</EditorReadout>
          </div>
        </>
      )}
    </ChipMenu>
  )
}

/** The typed cell a segment of presets ends in, lit while the value matches none of them. */
function TypedCell({ lit, children }: { lit: boolean; children: ReactNode }) {
  return (
    <span data-typed-cell data-lit={lit || undefined} className={cn('shrink-0 rounded-md', lit && 'ring-1 ring-primary/60')}>
      {children}
    </span>
  )
}

/**
 * *Same cycle as…* (FX editor plan D13): [from]'s cycle and what it follows — Seconds, or its master
 * — written once. Each patch names the timing and the master fields, as the timing chip's does, so
 * Revert can put them back; master 1 is named by its uuid, since the frame's absent field keeps.
 */
function cyclePatchFrom(from: FxDraft, wallClock: boolean, master1: string | null): Partial<FxDraft> {
  if (from.seconds) return { cycle: from.cycle, seconds: true, speedMasterUuid: null, rateSpeedMasterUuid: null }
  return wallClock
    ? { cycle: from.cycle, seconds: false, rateSpeedMasterUuid: from.rateSpeedMasterUuid }
    : { cycle: from.cycle, seconds: false, speedMasterUuid: from.speedMasterUuid ?? master1 }
}

/** The run counts Runs offers before its typed count. */
const RUN_PRESETS = [1, 2, 3, 4] as const

/**
 * Runs as a chip — `∞ ▾`, `×2 ▾` (effects plan D9; fx-settings plan D9): forever, ×1 to ×4, or a
 * count typed after them, lit while it is none of the four. After its runs each head hands back to
 * what is underneath; the desk counts them on the FX's clock from when the count is set.
 */
function RunsChip({
  draft,
  readOnly,
  set,
}: {
  draft: FxDraft
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const runs = draft.runs
  const label = runs == null ? '∞' : `×${runs}`
  const preset = runs == null ? 'forever' : (RUN_PRESETS as readonly number[]).includes(runs) ? String(runs) : ''
  return (
    <ChipMenu
      title="Runs"
      trigger={
        <button type="button" disabled={readOnly} aria-label={`Runs: ${runs == null ? 'forever' : runsWord(runs)}`} className={CHIP_CLASS}>
          {label}
          <ChevronDown className="size-3 text-muted-foreground" />
        </button>
      }
    >
      <EditorLabel>Runs</EditorLabel>
      <div className="flex items-center gap-2">
        <ToggleGroup
          type="single"
          size="sm"
          value={preset}
          disabled={readOnly}
          onValueChange={(v) => v && set({ runs: v === 'forever' ? null : Number(v) })}
          aria-label="Runs"
        >
          <ToggleGroupItem value="forever" aria-label="Forever" title="Forever" className="px-2 text-xs">
            ∞
          </ToggleGroupItem>
          {RUN_PRESETS.map((n) => (
            <ToggleGroupItem key={n} value={String(n)} aria-label={runsWord(n)} className="px-2 font-mono text-xs">
              ×{n}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        <TypedCell lit={preset === ''}>
          <SheetField
            label="Runs count"
            value={runs ?? 5}
            min={1}
            max={999}
            step={1}
            disabled={readOnly}
            className="w-[60px]"
            onEnter={(n) => n >= 1 && set({ runs: Math.round(n) })}
          />
        </TypedCell>
      </div>
      <EditorReadout>
        {runs == null ? 'runs until it is stopped' : 'cycles, then each head hands back to what is underneath'}
      </EditorReadout>
    </ChipMenu>
  )
}

/** The chip menu's value for *Seconds*. Never on the wire — the wire says `seconds`. */
const SECONDS_VALUE = '__seconds__'

/**
 * The timing as a chip — `M2 · 96`, `Rate M2 · 96` on a wall-clock effect, or `Seconds` (D7). It
 * opens *Seconds* and then every master: one question for every effect, where a wall-clock
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
    <ChipMenu
      title="Timing"
      trigger={
        <button
          type="button"
          // Disabled until the bank has loaded too: a move onto or off Seconds converts the division
          // at the master's tempo, and with no tempo to read it would keep the number and change the
          // speed.
          disabled={readOnly || listable.length === 0}
          aria-label={`Timing: ${wallClock && !seconds ? 'rate ' : ''}${label}`}
          className={CHIP_CLASS}
        >
          {wallClock && !seconds && <span className="font-sans">Rate</span>}
          {label}
          <ChevronDown className="size-3 text-muted-foreground" />
        </button>
      }
    >
      <EditorLabel>{wallClock ? 'Rate master' : 'Master'}</EditorLabel>
      <ToggleGroup
        type="single"
        size="sm"
        value={seconds ? SECONDS_VALUE : (current?.uuid ?? '')}
        disabled={readOnly}
        onValueChange={(v) => v && onChange(v === SECONDS_VALUE ? null : v)}
        aria-label="Timing"
        className="flex h-auto w-full flex-col items-stretch gap-px"
      >
        <ToggleGroupItem value={SECONDS_VALUE} aria-label="Seconds" className="h-8 justify-start px-2 text-xs">
          Seconds
          <span className="ml-auto pl-3 text-muted-foreground">its own cycle, no master</span>
        </ToggleGroupItem>
        {listable.map((m) => (
          <ToggleGroupItem key={m.uuid} value={m.uuid!} aria-label={`M${m.index} · ${m.name}`} className="h-8 justify-start px-2 text-xs">
            M{m.index} · {m.name}
            <span className="ml-auto pl-3 tabular-nums text-muted-foreground">{Math.round(m.bpm * 10) / 10} bpm</span>
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </ChipMenu>
  )
}

// ─── Across the heads ─────────────────────────────────────────────────────────────────────────

/** The heads a pattern's drawing spreads at most; with no selection, eight notional. */
const MAX_PATTERN_HEADS = 16

/**
 * **Across the heads** (fx-settings plan D10), on a group, a selection or a fixture with heads, and
 * always on an FX palette, after *Over*: the **head ladder**, each head numbered by when it starts;
 * the **patterns**, each drawn on the selection's own heads, the Order select under *Custom*;
 * **Each head later by** — *Fit the cycle*, *1 step* on an FX with steps, or *Free* — with what it
 * comes to between neighbours; and **Segments · Parts · Wings** with a line saying what they do.
 *
 * The desk shapes the heads (P2): this writes the fields. The ladder in the FX editor's inspector
 * ([fromPreview]) is the desk's preview; elsewhere it, the drawings and the words come from
 * `lib/spreadShape.ts`, the one client copy of the rule, pinned to the desk's by a shared vector.
 */
function AcrossHeads({
  draft,
  entry,
  headCount,
  inSeconds,
  readOnly,
  set,
  fromPreview,
}: {
  draft: FxDraft
  entry: FxLibraryEntry
  headCount: number | undefined
  /** The cycle is seconds — on *Seconds*, or a wall-clock FX — so the readout says seconds, not beats. */
  inSeconds: boolean
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
  /** The FX editor's inspector: the ladder from the desk's preview. */
  fromPreview: boolean
}) {
  const link = useFxGraphLink()
  const order = draft.order
  const shape: SpreadShape = {
    spread: draft.spread,
    wings: draft.wings,
    segments: draft.segments,
    parts: draft.parts,
    spreadUnit: draft.spreadUnit,
  }
  const steps = stepCountOf(entry, draft.parameters)
  // A group whose members have not loaded counts none: drawn on notional heads until it has.
  const known = headCount != null && headCount > 0 ? headCount : null
  const heads = known ?? NOTIONAL_HEADS
  const { offsets, slots } = spreadOffsets(order, heads, shape, steps)
  const ladder =
    fromPreview && link != null ? (
      <HeadLadder
        heads={link.ladder == null ? null : previewLadderHeads(link.ladder.fx, link.ladder.rows)}
        hoveredRow={link.hoveredRow}
        onHoverRow={link.setLadderRow}
      />
    ) : (
      <HeadLadder
        heads={offsets.map((start, i) => ({ key: String(i), label: String(i + 1), start }))}
        caption={known == null ? 'Drawn on eight heads' : undefined}
      />
    )
  const counts = (
    <>
      <Lights mark="brackets">
        <CountSegment label="Segments" value={draft.segments} min={1} readOnly={readOnly} onChange={(segments) => set({ segments })} />
      </Lights>
      <Lights mark="brackets">
        <CountSegment label="Parts" value={draft.parts} min={0} off={0} readOnly={readOnly} onChange={(parts) => set({ parts })} />
      </Lights>
      {!isMirroredOrder(order) && (
        <Lights mark="mirror">
          <CountSegment label="Wings" value={draft.wings} min={1} off={1} readOnly={readOnly} onChange={(wings) => set({ wings })} />
        </Lights>
      )}
      <EditorReadout>{groupingSentence(order, shape, known)}</EditorReadout>
    </>
  )
  return (
    <>
      {ladder}
      <Patterns draft={draft} entry={entry} heads={Math.min(heads, MAX_PATTERN_HEADS)} readOnly={readOnly} set={set} />
      {order === 'UNIFIED' ? (
        <EditorReadout>Every head starts together, so there is nothing to spread.</EditorReadout>
      ) : (
        <>
          <LaterBy
            draft={draft}
            entry={entry}
            shape={shape}
            steps={steps}
            gap={neighbourGap(offsets)}
            slots={slots}
            inSeconds={inSeconds}
            selectionKnown={known != null}
            readOnly={readOnly}
            set={set}
          />
          {counts}
        </>
      )}
    </>
  )
}

/**
 * The nine patterns (FX editor plan D11) as chips, each with a 40 × 16 drawing of its starts over
 * [heads], lit while its four fields match, and *Custom*, lit when none does, which opens the Order
 * select. A tap writes the four fields at once and leaves Spread to *Each head later by*.
 */
function Patterns({
  draft,
  entry,
  heads,
  readOnly,
  set,
}: {
  draft: FxDraft
  entry: FxLibraryEntry
  heads: number
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const link = useFxGraphLink()
  // *Custom* pressed holds for the fields it was pressed on, and those its own Order select writes: a
  // change from outside — Revert, *Same spread as*, another window — lights whichever pattern matches.
  const [customAt, setCustomAt] = useState<string | null>(null)
  const customOpen = customAt === patternKey(draft)
  const custom = customOpen || shapeOf(draft) == null
  const order = draft.order
  const orderOptions = ORDER_OPTIONS.map((value) => ({ value, label: orderLabel(value) }))
  // An Order this editor does not offer (a script's, a newer desk's) is still drawn, as itself.
  if (!ORDER_OPTIONS.includes(order)) orderOptions.push({ value: order, label: orderLabel(order) })
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <EditorLabel>Pattern</EditorLabel>
        <span className="flex-1" />
        {link != null && (
          <SameAs
            label="Same spread as"
            others={link.others}
            readOnly={readOnly}
            // An FX on one head says no Order, so its spread is the defaults and copying it would say Chase.
            refuse={({ effect }) =>
              effect.order == null
                ? 'That FX runs on one head, so it has no spread to copy'
                : effect.spreadUnit === 'STEP' && !entry.stepsFrom
                  ? 'That FX starts each head some of its steps later, and this FX has no steps'
                  : null
            }
            onPick={(effect) => {
              setCustomAt(null)
              set(spreadFieldsOf(draftOf(effect)))
            }}
          />
        )}
      </div>
      <Lights mark="starts">
        <div role="group" aria-label="Patterns" className="grid grid-cols-3 gap-1">
          {FX_SHAPES.map((shape) => {
            const lit = !customOpen && matchesShape(draft, shape)
            return (
              <button
                key={shape.name}
                type="button"
                aria-pressed={lit}
                disabled={readOnly}
                title={`${shape.name}: sets Order, Segments, Parts and Wings`}
                onClick={() => {
                  setCustomAt(null)
                  set({ ...shape.fields })
                }}
                className={cn(PATTERN_CLASS, lit && PATTERN_LIT_CLASS)}
              >
                <PatternDrawing shape={shape} heads={heads} />
                <span className="truncate">{shape.name}</span>
              </button>
            )
          })}
          <button
            type="button"
            aria-pressed={custom}
            disabled={readOnly}
            data-shape-custom
            title="Any Order, with Segments, Parts and Wings set by hand"
            onClick={() => setCustomAt(patternKey(draft))}
            className={cn(PATTERN_CLASS, 'justify-center', custom && PATTERN_LIT_CLASS)}
          >
            Custom
          </button>
        </div>
      </Lights>
      {custom && (
        <Lights mark="starts">
          <SelectRow
            label="Order"
            value={order}
            readOnly={readOnly}
            options={orderOptions}
            onChange={(next) => {
              const patch = orderPatch(draft, next)
              setCustomAt(patternKey({ ...draft, ...patch }))
              set(patch)
            }}
          />
        </Lights>
      )}
    </div>
  )
}

/** The four fields a pattern sets, as one key. */
function patternKey(d: Pick<FxDraft, 'order' | 'wings' | 'segments' | 'parts'>): string {
  return `${d.order}|${d.wings}|${d.segments}|${d.parts}`
}

const PATTERN_CLASS =
  'flex min-w-0 items-center gap-1 rounded-md border px-1 py-0.5 text-[10.5px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50'
const PATTERN_LIT_CLASS = 'border-primary/60 bg-primary/10 font-medium text-foreground'

/** A pattern's starts over [heads]: a dot a head, left to right, higher the later in the cycle. */
function PatternDrawing({ shape, heads }: { shape: FxShape; heads: number }) {
  const { offsets } = spreadOffsets(shape.fields.order, heads, { ...shape.fields, spread: 1, spreadUnit: 'CYCLE' })
  const pitch = heads > 1 ? 33.5 / (heads - 1) : 0
  const size = Math.min(2.5, Math.max(1.2, pitch * 0.8))
  return (
    <svg viewBox="0 0 40 16" width={40} height={16} aria-hidden data-shape-drawing className="shrink-0 text-primary">
      {offsets.map((o, i) => (
        <rect
          key={i}
          x={heads > 1 ? 2 + i * pitch : 18.75}
          y={12.5 - (((o % 1) + 1) % 1) * 11}
          width={size}
          height={2.5}
          rx={0.5}
          fill="currentColor"
        />
      ))}
    </svg>
  )
}

/**
 * **Each head later by** (fx-settings plan D6, D10): *Fit the cycle* (`CYCLE`, 1 — the heads share
 * one cycle), *1 step* (`STEP`, 1 — each head one of the FX's steps behind, on an FX with steps), or
 * *Free*, a slider in the unit the FX is on: steps under `STEP`, a share of the cycle under `CYCLE`.
 * Under it, what that comes to between neighbours (`laterByReadout`).
 */
function LaterBy({
  draft,
  entry,
  shape,
  steps,
  gap,
  slots,
  inSeconds,
  selectionKnown,
  readOnly,
  set,
}: {
  draft: FxDraft
  entry: FxLibraryEntry
  shape: SpreadShape
  steps: number | null
  /** Between neighbouring starts, as a share of the cycle (`neighbourGap`). */
  gap: number
  slots: number
  inSeconds: boolean
  selectionKnown: boolean
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const finger = useFingerSized()
  const stepped = !!entry.stepsFrom
  const inSteps = draft.spreadUnit === 'STEP'
  // *Free* holds for the spread it was pressed on and every value its slider writes, so a drag through
  // 1 never swaps the slider out from under the pointer; a spread written from outside reads afresh.
  const spreadKey = (spread: number) => `${spread}|${draft.spreadUnit}`
  const [freeAt, setFreeAt] = useState<string | null>(null)
  const snapped = draft.spread === 1 ? (inSteps ? (stepped ? 'step' : null) : 'fit') : null
  const mode = freeAt === spreadKey(draft.spread) || snapped == null ? 'free' : snapped
  const setFree = (spread: number, release: boolean) => {
    setFreeAt(spreadKey(spread))
    set({ spread }, release)
  }
  // Under *One cycle for: Each head* a head's cycle is the FX's times its slots (`FxPhase.memberDivision`).
  const headCycle = draft.timing === 'HEAD' ? draft.cycle * Math.max(slots, 1) : draft.cycle
  return (
    <div className="flex flex-col gap-1.5">
      <Lights mark="starts" className="flex flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <EditorLabel>Each head later by</EditorLabel>
          <span className="flex-1" />
          <ToggleGroup
            type="single"
            size="sm"
            value={mode}
            disabled={readOnly}
            aria-label="Each head later by"
            onValueChange={(v) => {
              if (v === 'free') setFreeAt(spreadKey(draft.spread))
              else if (v === 'fit' || v === 'step') {
                setFreeAt(null)
                set({ spread: 1, spreadUnit: v === 'step' ? 'STEP' : 'CYCLE' }, true)
              }
            }}
          >
            <ToggleGroupItem value="fit" title="The heads share one cycle between them" className={cn('px-2 text-xs', finger && 'min-h-8')}>
              Fit the cycle
            </ToggleGroupItem>
            {stepped && (
              <ToggleGroupItem value="step" title="Each head one of the FX's steps behind the one before" className={cn('px-2 text-xs', finger && 'min-h-8')}>
                1 step
              </ToggleGroupItem>
            )}
            <ToggleGroupItem value="free" title="Any spread" className={cn('px-2 text-xs', finger && 'min-h-8')}>
              Free
            </ToggleGroupItem>
          </ToggleGroup>
        </div>
        {mode === 'free' &&
          (inSteps ? (
            <SliderRow
              label="Spread"
              unit="steps"
              value={draft.spread}
              min={0}
              max={MAX_STEP_SPREAD}
              step={0.05}
              display={(v) => Math.round(v * 100) / 100}
              readOnly={readOnly}
              onChange={setFree}
            />
          ) : (
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
              onChange={setFree}
            />
          ))}
      </Lights>
      <EditorReadout>
        {laterByReadout(shape, gap, slots, steps, { value: headCycle, seconds: inSeconds }, selectionKnown)}
      </EditorReadout>
    </div>
  )
}

/** The counts Segments, Parts and Wings offer before their typed count. */
const COUNT_PRESETS = [1, 2, 3, 4] as const

/**
 * Segments, Parts or Wings as a segment — `1 · 2 · 3 · 4`, its [off] value said *off* — ending in a
 * typed count, lit while the count is none of them (fx-settings plan D10). Keeps 32px cells on the
 * phone host (§4).
 */
function CountSegment({
  label,
  value,
  min,
  off,
  readOnly,
  onChange,
}: {
  label: string
  value: number
  min: number
  /** The value that turns it off — 0 for Parts, 1 for Wings — drawn as the segment's first. */
  off?: number
  readOnly: boolean
  onChange: (value: number) => void
}) {
  const finger = useFingerSized()
  const presets = off == null ? [...COUNT_PRESETS] : [off, ...COUNT_PRESETS.filter((n) => n > 1)]
  const pressed = presets.includes(value) ? String(value) : ''
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <EditorLabel className="w-[64px]">{label}</EditorLabel>
      <ToggleGroup
        type="single"
        size="sm"
        value={pressed}
        disabled={readOnly}
        aria-label={label}
        onValueChange={(v) => v && onChange(Number(v))}
      >
        {presets.map((n) => (
          <ToggleGroupItem
            key={n}
            value={String(n)}
            aria-label={n === off ? `${label} off` : `${label} ${n}`}
            className={cn('min-w-7 px-1.5 font-mono text-xs', finger && 'min-h-8 min-w-8')}
          >
            {n === off ? 'off' : n}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      <TypedCell lit={pressed === ''}>
        <SheetField
          label={`${label} count`}
          value={value}
          min={min}
          max={MAX_SPREAD_COUNT}
          step={1}
          disabled={readOnly}
          className="w-[56px]"
          onEnter={(n) => {
            const next = Math.round(n)
            if (next >= min && next <= MAX_SPREAD_COUNT) onChange(next)
          }}
        />
      </TypedCell>
    </div>
  )
}

// ─── The graph's links ────────────────────────────────────────────────────────────────────────

/**
 * Lights [mark] on the FX editor's graph while the pointer rests on what it wraps (FX editor plan
 * §4.5) — what that control changes. Outside the FX editor there is no graph, and it only wraps.
 */
function Lights({ mark, className, children }: { mark: GraphMark; className?: string; children: ReactNode }) {
  const link = useFxGraphLink()
  const linkRef = useRef(link)
  linkRef.current = link
  const lit = useRef(false)
  // Gone under the pointer — the FX stopped, the inspector closed — it puts the light out itself.
  useEffect(
    () => () => {
      if (lit.current) linkRef.current?.setHighlight(null)
    },
    [],
  )
  const light = (on: boolean) => {
    lit.current = on
    link?.setHighlight(on ? mark : null)
  }
  return (
    <div
      data-lights={mark}
      className={className}
      onPointerEnter={link == null ? undefined : () => light(true)}
      onPointerLeave={link == null ? undefined : () => light(false)}
    >
      {children}
    </div>
  )
}

/**
 * *Same as…* (FX editor plan D13): the other FX in the list, a pick copying their fields once.
 * Nothing links the two afterwards (`FU-FX-LINKED-TIMING`). [refuse] names why one cannot be copied.
 */
function SameAs({
  label,
  others,
  readOnly,
  refuse,
  onPick,
}: {
  label: string
  others: readonly MatchSource[]
  readOnly: boolean
  refuse?: (source: MatchSource) => string | null
  onPick: (effect: MatchSource['effect']) => void
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          disabled={readOnly || others.length === 0}
          aria-label={`${label}…`}
          title={others.length === 0 ? 'No other FX in the list' : `${label} another FX in the list, copied once`}
          className="inline-flex h-5 shrink-0 items-center gap-0.5 self-start rounded-full border px-2 text-[10px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          Same as…
          <ChevronDown className="size-3" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">{label}</DropdownMenuLabel>
        {others.map((source) => {
          const { effect, name } = source
          const reason = refuse?.(source) ?? null
          return (
            <DropdownMenuItem key={effect.id} disabled={reason != null} title={reason ?? undefined} className="text-xs" onClick={() => onPick(effect)}>
              {name}
              <span className="ml-auto pl-3 font-mono text-[10px] text-muted-foreground">{effect.attribute}</span>
            </DropdownMenuItem>
          )
        })}
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

function foldSummary(param: FxParameterDef, view: ParamView, value: string): string {
  const type = param.type.toLowerCase()
  const label = view.label.toLowerCase()
  const option = param.options?.find((o) => o.value.toUpperCase() === value.trim().toUpperCase())
  if (type === 'easingcurve') return (option?.label ?? value).toLowerCase().replace(/_/g, ' ')
  if (type === 'choice') return `${label} ${(option?.label ?? value).toLowerCase()}`
  if (type === 'boolean') return `${label} ${value === 'true' ? 'on' : 'off'}`
  const { display, suffix } = unitDisplay(view.unit)
  if (view.unit != null) return `${label} ${Math.round(display(Number(value) || 0))}${suffix ?? ''}`
  return `${label} ${value}`
}

/** A number as [param]'s type stores it: a byte clamped, an int whole, a double as it is. */
function writeNumber(param: FxParameterDef, v: number): string {
  const type = param.type.toLowerCase()
  return type === 'ubyte' ? String(clampByte(v)) : type === 'int' ? String(Math.round(v)) : String(v)
}

/** How a stored value reads in its unit (`FxParamUnit`), and the suffix its field carries. */
function unitDisplay(unit: FxParamUnit | null): {
  display: (value: number) => number
  fromDisplay: (display: number) => number
  suffix?: string
} {
  switch (unit) {
    case '%':
      return { display: toPct, fromDisplay: (d) => fromPct(Math.max(0, Math.min(100, d))), suffix: '%' }
    case 'ratio':
      return { display: (v) => Math.round(v * 100), fromDisplay: (d) => d / 100, suffix: '%' }
    case 'position':
      // A centre or a sweep's end is a point on its axis, read centre-relative on every head
      // (venue-visit plan D3): `-128..127`, 0 the centre of travel (DMX 128, where Around pins it).
      // The value is raw DMX either way, so no head's range is consulted.
      return { display: dmxToCentre, fromDisplay: centreToDmx }
    case 'ms':
      return { display: (v) => v, fromDisplay: (d) => d, suffix: 'ms' }
    default:
      return { display: (v) => v, fromDisplay: (d) => d }
  }
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
 * One parameter, in its unit (`paramView`): a level or a ratio as a percent, a size in DMX steps, a
 * centre or a sweep's end centre-relative on its axis, a choice as a segment, a curve as a list, a
 * colour through the FX colour pickers.
 */
function ParamControl({
  param,
  view,
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
  view: ParamView
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
  const label = view.label
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

  if (isNumeric(param)) {
    const { display, fromDisplay, suffix } = unitDisplay(view.unit)
    const write = (v: number) => writeNumber(param, v)
    return (
      <SliderRow
        label={label}
        description={param.description || undefined}
        unit={suffix}
        value={num}
        min={view.min}
        max={view.max}
        step={view.step}
        display={display}
        fromDisplay={fromDisplay}
        displayMin={display(view.min)}
        displayMax={view.open ? 1_000_000 : display(view.max)}
        readOnly={readOnly}
        onChange={(v, release) => onChange(write(v), release)}
      />
    )
  }

  if (type === 'choice' && param.options != null && param.options.length > 0) {
    return (
      <ChoiceRow
        label={label}
        value={value}
        options={param.options}
        description={param.description || undefined}
        pictures={param.name === 'shape'}
        readOnly={readOnly}
        onChange={(v) => onChange(v, true)}
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
        options={param.options ?? CURVES.map((c) => ({ value: c, label: c.toLowerCase().replace(/_/g, ' ') }))}
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

/**
 * A `choice` parameter: a segment of its options, landing on the press. A shape's ([pictures]) draws
 * each option's small picture over its word (`fxShapeIcons.ts`), where every option has one.
 */
function ChoiceRow({
  label,
  value,
  options,
  description,
  pictures = false,
  readOnly,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string }[]
  description?: string
  pictures?: boolean
  readOnly: boolean
  onChange: (value: string) => void
}) {
  const current = options.find((o) => o.value.toUpperCase() === value.trim().toUpperCase())?.value ?? ''
  const drawn = pictures && options.every((o) => shapeIconPath(o.value) != null)
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <ToggleGroup
        type="single"
        size="sm"
        value={current}
        disabled={readOnly}
        aria-label={label}
        onValueChange={(v) => v && onChange(v)}
        className={cn('flex w-full flex-wrap', drawn && 'grid h-auto grid-cols-3 gap-px')}
      >
        {options.map((option) => (
          <ToggleGroupItem
            key={option.value}
            value={option.value}
            className={cn(
              'min-w-0 px-2 text-xs',
              drawn ? 'h-auto flex-col gap-0.5 py-1 text-[10.5px] whitespace-nowrap' : 'flex-1 truncate',
            )}
          >
            {drawn && <ShapeIcon option={option.value} />}
            <span className={drawn ? undefined : 'truncate'}>{option.label}</span>
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {description && <EditorReadout>{description}</EditorReadout>}
    </div>
  )
}

/** A shape option's small picture: its idea, not the FX's output. */
function ShapeIcon({ option }: { option: string }) {
  const d = shapeIconPath(option)
  if (d == null) return null
  return (
    <svg
      viewBox={`0 0 ${SHAPE_ICON_BOX.width} ${SHAPE_ICON_BOX.height}`}
      width={SHAPE_ICON_BOX.width}
      height={SHAPE_ICON_BOX.height}
      aria-hidden
      data-shape-icon={option}
      className="shrink-0 text-primary"
    >
      <path d={d} fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  )
}

/**
 * A number as a segment of its common values ([ParamPresets]) ending in a typed cell, lit while the
 * value is none of them (fx-settings plan D9) — Wave's Width *10 % · 50 % · One head · 35 %*. A
 * choice that stands for a value of its own ([alongside], Wave's `widthUnit`) is one more preset, and
 * every number written sets it back off. One write carries both, as [RangeRow]'s does.
 */
function PresetRow({
  param,
  view,
  presets,
  value,
  alongside,
  alongsideValue,
  readOnly,
  onChange,
}: {
  param: FxParameterDef
  view: ParamView
  presets: ParamPresets
  value: string
  alongside: FxParameterDef | undefined
  alongsideValue: string | undefined
  readOnly: boolean
  onChange: (changes: Record<string, string>, release: boolean) => void
}) {
  const { display, fromDisplay, suffix } = unitDisplay(view.unit)
  const choice = alongside != null ? presets.alongside : undefined
  const onChoice = choice != null && (alongsideValue ?? '').trim().toUpperCase() === choice.value
  const num = Number(value) || 0
  const matched = onChoice ? undefined : presets.values.find((v) => Math.abs(v - num) < 1e-6)
  const pressed = onChoice ? 'choice' : matched != null ? String(matched) : ''
  const off = choice != null ? { [choice.param]: choice.off } : {}
  const write = (v: number) => ({ [param.name]: writeNumber(param, Math.max(view.min, Math.min(view.max, v))), ...off })
  const word = (v: number) => `${Math.round(display(v) * 100) / 100}${suffix != null ? ` ${suffix}` : ''}`
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{view.label}</EditorLabel>
      <div className="flex flex-wrap items-center gap-1.5">
        <ToggleGroup
          type="single"
          size="sm"
          value={pressed}
          disabled={readOnly}
          aria-label={view.label}
          onValueChange={(v) => {
            if (!v) return
            if (v === 'choice' && choice != null) onChange({ [choice.param]: choice.value }, true)
            else onChange(write(Number(v)), true)
          }}
        >
          {presets.values.map((v) => (
            <ToggleGroupItem key={v} value={String(v)} className="px-2 text-xs">
              {word(v)}
            </ToggleGroupItem>
          ))}
          {choice != null && (
            <ToggleGroupItem value="choice" title={alongside?.description || undefined} className="px-2 text-xs">
              {choice.label}
            </ToggleGroupItem>
          )}
        </ToggleGroup>
        <TypedCell lit={pressed === ''}>
          <SheetField
            label={`${view.label}${suffix ? ` ${unitWord(suffix)}` : ''}`}
            unit={suffix}
            value={Math.round(display(num) * 100) / 100}
            min={display(view.min)}
            max={display(view.max)}
            disabled={readOnly}
            className="w-[64px]"
            onEnter={(d) => onChange(write(fromDisplay(d)), true)}
          />
        </TypedCell>
      </div>
      {param.description && <EditorReadout>{param.description}</EditorReadout>}
    </div>
  )
}

/**
 * A low and its high (the low's `pair`) as one two-thumb range, each with its own field. A stored
 * pair whose low is above its high keeps that way round: the thumbs are drawn in order, and a drag
 * writes the larger back to the low. Both ends go in one [onChange], since a host builds each write
 * from the draft it last rendered and a second would undo the first.
 */
function RangeRow({
  low,
  high,
  lowView,
  highView,
  lowValue,
  highValue,
  readOnly,
  onChange,
}: {
  low: FxParameterDef
  high: FxParameterDef
  lowView: ParamView
  highView: ParamView
  lowValue: string
  highValue: string
  readOnly: boolean
  onChange: (changes: Record<string, string>, release: boolean) => void
}) {
  const { display, fromDisplay, suffix } = unitDisplay(lowView.unit)
  const min = Math.min(lowView.min, highView.min)
  const max = Math.max(lowView.max, highView.max)
  const clamp = (v: number) => Math.max(min, Math.min(max, v))
  const lo = clamp(Number(lowValue) || 0)
  const hi = clamp(Number(highValue) || 0)
  const inverted = lo > hi
  const write = (v: number) => writeNumber(low, v)
  const move = ([a, b]: number[], release: boolean) => {
    if (a === undefined || b === undefined) return
    const [nextLow, nextHigh] = inverted ? [Math.max(a, b), Math.min(a, b)] : [Math.min(a, b), Math.max(a, b)]
    const changes: Record<string, string> = {}
    if (nextLow !== lo) changes[low.name] = write(nextLow)
    if (nextHigh !== hi) changes[high.name] = write(nextHigh)
    // A release lands even when the drag's last move already did, so the host's floor flushes it.
    if (Object.keys(changes).length === 0) {
      if (!release) return
      changes[low.name] = write(nextLow)
      changes[high.name] = write(nextHigh)
    }
    onChange(changes, release)
  }
  const descriptions = [low.description, high.description].filter((d) => d)
  const field = (param: FxParameterDef, view: ParamView, value: number) => (
    <SheetField
      label={`${view.label}${suffix ? ` ${unitWord(suffix)}` : ''}`}
      unit={suffix}
      value={Math.round(display(value) * 100) / 100}
      min={display(min)}
      max={display(max)}
      disabled={readOnly}
      onEnter={(d) => onChange({ [param.name]: write(clamp(fromDisplay(d))) }, true)}
    />
  )
  return (
    <div className="flex flex-col gap-1">
      <EditorLabel>{`${lowView.label} – ${highView.label}`}</EditorLabel>
      <div className="flex items-center gap-2">
        {field(low, lowView, lo)}
        <Slider
          aria-label={`${lowView.label} – ${highView.label}`}
          value={inverted ? [hi, lo] : [lo, hi]}
          min={min}
          max={max}
          step={lowView.step}
          disabled={readOnly}
          onValueChange={(v) => move(v, false)}
          onValueCommit={(v) => move(v, true)}
          className="min-w-[60px] flex-1"
        />
        {field(high, highView, hi)}
      </div>
      {descriptions.length > 0 && <EditorReadout>{descriptions.join(' · ')}</EditorReadout>}
    </div>
  )
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
