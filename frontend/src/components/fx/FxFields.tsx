import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ArrowLeft, ArrowLeftRight, ArrowRight, ChevronDown, ChevronRight } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { cn } from '@/lib/utils'
import type { FxDirection } from '@/api/fxApi'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
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
import { OffsetLadder } from './OffsetLadder'
import { FX_SHAPES, matchesShape, shapeOf, spreadFieldsOf, type FxShape } from './fxShapes'
import { useFxGraphLink, type GraphMark, type MatchSource } from './fxGraphLink'
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
  isLevelFx,
  declaresMetadata,
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

/** A parameter as drawn: alone, or a low drawn with its high as one range (its `pair`). */
type ParamItem =
  | { kind: 'one'; param: FxParameterDef; view: ParamView }
  | { kind: 'pair'; low: FxParameterDef; high: FxParameterDef; lowView: ParamView; highView: ParamView }

/**
 * The parameters to draw, in order: each shown one ([isParamShown]) once, a pair at whichever of
 * its two comes first, [hidden] left out.
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

const itemFold = (item: ParamItem) => (item.kind === 'pair' ? item.lowView.fold : item.view.fold)
const itemKey = (item: ParamItem) => (item.kind === 'pair' ? item.low.name : item.param.name)
const itemDeclares = (item: ParamItem) => declaresMetadata(item.kind === 'pair' ? item.low : item.param)

/**
 * The editor's fields over one draft — Cycle, Amount, Direction, Order and Over, the two questions, the
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
  /**
   * `inspector` is the FX editor's order (FX editor plan §4.5): Cycle, Amount, Direction, Order and
   * Over; **Shape**, every parameter; Spread; **Over what's underneath**, the family's question and
   * the raw blend; **Advanced**, Phase, Timing, Runs and the head filter. `editor` is every other
   * host's.
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
  const shape = items.filter((item) => itemFold(item) === 'detail')
  // A desk that declares its parameters folds its `detail` ones under *More*; a guessed fold is *Shape*.
  const foldLabel = shape.some(itemDeclares) ? 'More' : 'Shape'

  // Blank is the default, as the desk reads it (`TypedParams.raw`).
  const valueOf = (param: FxParameterDef) => draft.parameters[param.name] || param.defaultValue
  const paramControl = (item: ParamItem) =>
    item.kind === 'pair' ? (
      <RangeRow
        key={itemKey(item)}
        low={item.low}
        high={item.high}
        lowView={item.lowView}
        highView={item.highView}
        lowValue={valueOf(item.low)}
        highValue={valueOf(item.high)}
        readOnly={readOnly}
        onChange={(changes, release) => set({ parameters: { ...draft.parameters, ...changes } }, release)}
      />
    ) : (
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

  // Where the raw blend is drawn, for a question whose answer is neither of its two.
  const blendHome = layout === 'inspector' ? 'Blend' : 'Advanced'
  const speed = (
    <Lights mark="cycle">
      <SpeedSection timingSource={timingSource} entry={entry} draft={draft} readOnly={readOnly} set={set} />
    </Lights>
  )
  const amount = (
    <AmountSection draft={draft} ordered={scope.heads} over={scope.elementMode} readOnly={readOnly} set={set} />
  )
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
            : `Blended ${draft.blendMode.toLowerCase()} — see ${blendHome}.`
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
            : `Blended ${draft.blendMode.toLowerCase()} — see ${blendHome}.`
      }
    />
  )
  const spread = scope.heads && (
    <SpreadSection draft={draft} headCount={scope.headCount} readOnly={readOnly} set={set} ladder={layout === 'inspector'} />
  )
  const shapeSummaries = shape.map((item) =>
    item.kind === 'pair'
      ? `${shapeSummary(item.low, item.lowView, valueOf(item.low))}–${shapeSummary(item.high, item.highView, valueOf(item.high))}`
      : shapeSummary(item.param, item.view, valueOf(item.param)),
  )
  const phase = (
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
  )
  const runs = (
    <Lights mark="holding">
      <RunsRow draft={draft} readOnly={readOnly} set={set} />
    </Lights>
  )
  const blend = (
    <SelectRow
      label="Blend"
      value={draft.blendMode}
      readOnly={readOnly}
      options={BLEND_MODE_OPTIONS}
      onChange={(blendMode) => set({ blendMode })}
    />
  )
  const timingRow = showTiming && (
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
  )
  const elementRows = (
    <>
      {scope.elementFilter && (
        <SelectRow
          label="Heads"
          value={draft.elementFilter}
          readOnly={readOnly}
          options={ELEMENT_FILTER_OPTIONS}
          onChange={(elementFilter) => set({ elementFilter })}
        />
      )}
    </>
  )
  const timingSummary = showTiming ? [`timing per ${draft.timing === 'HEAD' ? 'head' : 'cycle'}`] : []
  const runsSummary = draft.runs != null ? [runsWord(draft.runs)] : []
  const phaseSummary = `phase ${Math.round((draft.phaseOffset - phaseCycles) * 360)}°`

  if (layout === 'inspector') {
    return (
      <>
        {speed}
        {amount}
        {(main.length > 0 || shape.length > 0) && (
          <section aria-label="Shape" className="flex flex-col gap-2.5">
            <SectionLabel>Shape</SectionLabel>
            {main.map(paramControl)}
            {foldLabel === 'More' ? (
              shape.length > 0 && (
                <Disclosure label="More" summary={shapeSummaries}>
                  {shape.map(paramControl)}
                </Disclosure>
              )
            ) : (
              shape.map(paramControl)
            )}
          </section>
        )}
        {spread}
        <section aria-label="Over what’s underneath" className="flex flex-col gap-2.5">
          <SectionLabel>Over what’s underneath</SectionLabel>
          {centreQuestion}
          {levelQuestion}
          {blend}
        </section>
        <Disclosure label="Advanced" summary={[phaseSummary, ...timingSummary, ...runsSummary]}>
          {phase}
          {timingRow}
          {runs}
          {elementRows}
        </Disclosure>
      </>
    )
  }

  return (
    <>
      {speed}
      {amount}
      {centreQuestion}
      {levelQuestion}

      {main.length > 0 && <div className="flex flex-col gap-2.5">{main.map(paramControl)}</div>}

      {spread}

      {shape.length > 0 && (
        <Disclosure label={foldLabel} summary={shapeSummaries}>
          {shape.map(paramControl)}
        </Disclosure>
      )}

      <Disclosure label="Advanced" summary={[phaseSummary, draft.blendMode.toLowerCase(), ...runsSummary, ...timingSummary]}>
        {phase}
        {runs}
        {blend}
        {timingRow}
        {elementRows}
      </Disclosure>
    </>
  )
}

/** A section's heading in the inspector layout. */
function SectionLabel({ children }: { children: ReactNode }) {
  return <span className="text-[11px] font-medium text-muted-foreground">{children}</span>
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
 * offsets run in, drawn only where there are heads to order ([ordered]). *Over* (FX editor follow-ups
 * plan D7) says what the Order counts where a fixture has elements ([over]). All are the desk's to
 * apply (P2): this only writes the fields, and edits them in place, so the phase is kept.
 */
function AmountSection({
  draft,
  ordered,
  over,
  readOnly,
  set,
}: {
  draft: FxDraft
  ordered: boolean
  over: boolean
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
}) {
  const order = draft.order
  const options = ORDER_OPTIONS.map((value) => ({ value, label: orderLabel(value) }))
  // An Order this editor does not offer (a script's, a newer desk's) is still drawn, as itself.
  if (!ORDER_OPTIONS.includes(order)) options.push({ value: order, label: orderLabel(order) })
  return (
    <div className="flex flex-col gap-2.5">
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
          <Lights mark="starts" className="min-w-0 flex-1">
            <SelectRow
              label="Order"
              value={order}
              readOnly={readOnly}
              options={options}
              onChange={(next) => set(orderPatch(draft, next))}
            />
          </Lights>
        )}
      </div>
      {over && (
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
    </div>
  )
}

// ─── Spread ───────────────────────────────────────────────────────────────────────────────────

/** The dials a live effect draws before the rest of its heads fold into *+n* (effects plan §4.1). */
const MAX_DIALS = 16

/**
 * The Spread section (effects plan D11, §4.1; FX editor plan D11, §4.5), on a group, a selection or
 * a fixture with heads, and always on an FX palette: the **Shapes**, *Spread* %, *Wings*, *Segments*
 * and *Parts*, and what they do to the heads under the Order chosen above. Its summary line says the
 * Shape and the non-defaults: *Custom · 50 % · 2 wings*.
 *
 * The desk shapes the heads (P2): this writes the four fields and the Order. What it draws of them
 * is the **offset ladder** in the FX editor's inspector ([ladder]), read from the desk's preview, and
 * elsewhere the phase dials, drawn by `lib/spreadShape.ts`, the one client copy of the rule, pinned to
 * the desk's by a shared vector. Wings are hidden under a mirrored Order (Centre out, Centre in — two
 * wings already).
 */
function SpreadSection({
  draft,
  headCount,
  readOnly,
  set,
  ladder,
}: {
  draft: FxDraft
  headCount: number | undefined
  readOnly: boolean
  set: (patch: Partial<FxDraft>, release?: boolean) => void
  /** The FX editor's inspector: the offset ladder in place of the dials. */
  ladder: boolean
}) {
  const link = useFxGraphLink()
  const order = draft.order
  const shape: SpreadShape = { spread: draft.spread, wings: draft.wings, segments: draft.segments, parts: draft.parts }
  const mirrored = isMirroredOrder(order)
  const unison = order === 'UNIFIED'
  const named = shapeOf(draft)
  return (
    <Disclosure label="Spread" summary={[named?.name ?? 'Custom', ...spreadSummary(order, shape)]}>
      <Lights mark="starts">
        <ShapeChips draft={draft} readOnly={readOnly} onPick={(picked) => set({ ...picked.fields })} />
      </Lights>
      {link != null && (
        <SameAs
          label="Same spread as"
          others={link.others}
          readOnly={readOnly}
          // An FX on one head says no Order, so its spread is the defaults and copying it would say Chase.
          refuse={({ effect }) => (effect.order == null ? 'That FX runs on one head, so it has no spread to copy' : null)}
          onPick={(effect) => set(spreadFieldsOf(draftOf(effect)))}
        />
      )}
      {unison ? (
        <EditorReadout>Every head runs in step, so there is no spread to shape.</EditorReadout>
      ) : (
        <>
          <Lights mark="starts">
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
          </Lights>
          <div className="grid grid-cols-3 gap-2">
            {mirrored ? (
              <div className="flex flex-col gap-1">
                <EditorLabel>Wings</EditorLabel>
                <span className="text-[11px] text-muted-foreground" title={`${orderLabel(order)} is two wings already`}>
                  two already
                </span>
              </div>
            ) : (
              <Lights mark="mirror">
                <CountRow label="Wings" value={draft.wings} min={1} readOnly={readOnly} onChange={(wings) => set({ wings })} />
              </Lights>
            )}
            <Lights mark="brackets">
              <CountRow label="Segments" value={draft.segments} min={1} readOnly={readOnly} onChange={(segments) => set({ segments })} />
            </Lights>
            <Lights mark="brackets">
              <CountRow label="Parts" value={draft.parts} min={0} readOnly={readOnly} onChange={(parts) => set({ parts })} />
            </Lights>
          </div>
          <EditorReadout>
            {mirrored ? '' : 'Wings mirror alternate runs · '}Segments move neighbours as one · Parts repeat the spread (0 is off)
          </EditorReadout>
        </>
      )}
      {ladder && link != null ? (
        <OffsetLadder ladder={link.ladder} spread={draft.spread} hoveredRow={link.hoveredRow} onHoverRow={link.setLadderRow} />
      ) : (
        <SpreadDials order={order} shape={shape} headCount={headCount} />
      )}
    </Disclosure>
  )
}

/** The heads a Shape's drawing spreads — notional, since the Shape is not running yet. */
const SHAPE_HEADS = 8

/**
 * The nine Shapes (FX editor plan D11) as chips, each with a 40 × 16 drawing of its offsets over
 * eight notional heads, lit while every field matches it — else a lit *Custom* after them. A tap
 * writes all five fields at once.
 *
 * The drawings are `lib/spreadShape.ts`'s, the client's one copy of the desk's rule, used where the
 * desk has not answered (P2): a Shape is drawn before it is picked, so no preview of it exists.
 */
function ShapeChips({ draft, readOnly, onPick }: { draft: FxDraft; readOnly: boolean; onPick: (shape: FxShape) => void }) {
  const custom = shapeOf(draft) == null
  return (
    <div role="group" aria-label="Shapes" className="grid grid-cols-3 gap-1">
      {FX_SHAPES.map((shape) => {
        const lit = matchesShape(draft, shape)
        return (
          <button
            key={shape.name}
            type="button"
            aria-pressed={lit}
            disabled={readOnly}
            title={`${shape.name}: sets Order, Spread, Wings, Segments and Parts`}
            onClick={() => onPick(shape)}
            className={cn(
              'flex min-w-0 items-center gap-1 rounded-md border px-1 py-0.5 text-[10.5px] text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50',
              lit && 'border-primary/60 bg-primary/10 font-medium text-foreground',
            )}
          >
            <ShapeDrawing shape={shape} />
            <span className="truncate">{shape.name}</span>
          </button>
        )
      })}
      {custom && (
        <span
          data-shape-custom
          title="The fields match no Shape"
          className="flex items-center justify-center rounded-md border border-primary/60 bg-primary/10 px-1 py-0.5 text-[10.5px] font-medium"
        >
          Custom
        </span>
      )}
    </div>
  )
}

/** A Shape's offsets over eight notional heads: a dot a head, left to right, higher the later in the cycle. */
function ShapeDrawing({ shape }: { shape: FxShape }) {
  const { offsets } = spreadOffsets(shape.fields.order, SHAPE_HEADS, shape.fields)
  return (
    <svg viewBox="0 0 40 16" width={40} height={16} aria-hidden data-shape-drawing className="shrink-0 text-primary">
      {offsets.map((o, i) => (
        <rect key={i} x={2 + i * 4.75} y={12.5 - (((o % 1) + 1) % 1) * 11} width={2.5} height={2.5} rx={0.5} fill="currentColor" />
      ))}
    </svg>
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
  const link = useFxGraphLink()
  const master1 = listable.find((m) => m.index === 1)?.uuid ?? null

  return (
    <div className="flex flex-col gap-1.5">
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

function shapeSummary(param: FxParameterDef, view: ParamView, value: string): string {
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

  if (type === 'ubyte' || type === 'double' || type === 'float' || type === 'int') {
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

/** A `choice` parameter: a segment of its options, landing on the press. */
function ChoiceRow({
  label,
  value,
  options,
  description,
  readOnly,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string }[]
  description?: string
  readOnly: boolean
  onChange: (value: string) => void
}) {
  const current = options.find((o) => o.value.toUpperCase() === value.trim().toUpperCase())?.value ?? ''
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
        className="flex w-full flex-wrap"
      >
        {options.map((option) => (
          <ToggleGroupItem key={option.value} value={option.value} className="min-w-0 flex-1 truncate px-2 text-xs">
            {option.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {description && <EditorReadout>{description}</EditorReadout>}
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
