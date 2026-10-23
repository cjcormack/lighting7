import { useCallback, useEffect, useMemo, useRef, useState, type ComponentType, type ReactNode } from 'react'
import { ArrowLeftRight, Waves } from 'lucide-react'
import { toast } from 'sonner'
import type { CueTarget } from '@/api/cuesApi'
import type { TemplateSummary } from '@/api/templatesApi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { hexToRgb } from '@/components/fx/colourUtils'
import { templateSwatch } from '@/components/busking/padFace'
import { WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { computeCombinedCss } from '@/lib/colourMath'
import { skippedRowsMessage } from '@/lib/selectionMask'
import { getSpreadOver, setSpreadOver, useSpreadOver } from '@/lib/spreadOver'
import { getSpreadFanPrefs, setSpreadFanPrefs, useSpreadFanPrefs, type FanAxes, type SpreadMode } from '@/lib/spreadFan'
import {
  SPREAD_CURVES,
  SPREAD_ORDERS,
  SPREAD_PARTS_PRESETS,
  colourEndpointOf,
  defaultSpreadEndpoints,
  isCompleteSpreadEndpoint,
  serializeSpreadEndpoint,
  spreadEditorKind,
  spreadPropertiesFor,
  type SpreadEndpoint,
} from '@/lib/spreadIntent'
import { WHITE_POLICIES, WHITE_POLICY_LABELS, type TemplateProperty, type WhitePolicy } from '@/lib/templateIntent'
import { cn } from '@/lib/utils'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import type { AimNear, SpreadBase, SpreadCurve, SpreadOrder, SpreadOver, SpreadRequest, SpreadResponse } from '@/store/programmerOps'
import type { ColourEditorProps } from './ColourEditor'
import { EditorField } from './EditorField'
import { EditorFooter } from './EditorFooter'
import { EditorLabel } from './EditorLabel'
import { EditorSurface } from './EditorSurface'
import { rawValues, spreadDurations, spreadFractions, walkAddresses } from './spreadPlans'
import { useEditorKeyboard } from './useEditorKeyboard'
import { useLivePush } from './useLivePush'
import { CENTRE_MAX, CENTRE_MIN, clampCentre, formatCentre } from '@/lib/positionScale'
import { getPositionPadMode, setPositionPadMode, usePositionPadMode, type PositionPadMode } from '@/lib/positionPadMode'

/**
 * **Fan** — one panel, four plan kinds, every host (editor-kit plan D2–D7; `Spread.dc.html` is
 * the authority on layout; the shipped busk tab on any measurement they disagree on).
 *
 * It is the busk view's Spread tab's body and the old fan popover's surface, chooser and keyboard, made
 * one component: the programmer's row C hosts it as a popover in the cell editor's three forms
 * (`fixtures-list/SpreadPopover.tsx` builds its plans from the marquee), the busk view docks it as
 * the tab (`busking/SpreadSheet.tsx`), and the patch list, the cue sheet and the DMX sheet host it
 * with plans of their own. The kinds:
 *
 * - **`intent`** — desk-resolved, the template vocabulary: two intents of one property's shape, a
 *   curve, an order, parts and an over-switch go to `POST /programmer/spread`, and the desk
 *   interpolates in the intent's own space, resolves one literal per head through the same
 *   `TemplateResolver` *Apply as values* uses, and answers what it wrote. **The client never
 *   interpolates an intent** — only the desk knows a group's member order, each head's range, which
 *   cells a fixture has and what a colour means on a head with amber — and `spreadIntent.test.ts`
 *   pins that this file reaches `spreadPlans.ts` only for the raw arm below.
 * - **`raw`** — bytes on a column outside that vocabulary, Speed today (D15): the client's own walk,
 *   under the same Curve · Order · Parts · Over controls.
 * - **`address`** — From · Step in visible order, the patch list's walk and its landing line.
 * - **`duration`** — From · To with the curve row, the cue sheet's fade times.
 *
 * **The family segment is answered and drawn checked, never hidden** (D4): the panel is one
 * component, and a row that appears only in some hosts is how two hosts drift. The host says which
 * families the heads can take and which of those the gesture offers — the busk tab offers every
 * one, the programmer the marquee's — and the Property row appears wherever the family holds more
 * than one property the heads can take. Over defaults to Heads on both sides (D5), and once chosen it
 * survives a change of selection, an empty one included (`lib/spreadOver.ts`): Cells over heads with
 * no cells is drawn chosen and spreads as Heads.
 *
 * **Live** applies every adjustment as it is made, through {@link useLivePush} with an equality
 * over the whole request — the tempo fader's discipline, because a spread is judged by eye against
 * the rig. The release is read from the **window**, because the picker binds its own release to the
 * document. Off, only *Apply* writes; while Live is on Apply reads *Send again* and stays pressable
 * — the one un-deduped resend after a write the desk refused (D7). Only the **latest** request's
 * answer is reported: Live keeps several in flight and their answers can land out of order, so a
 * property or selection change disowns whatever is in flight. Under an empty selection nothing is
 * sent and the panel's own sentence is toasted; the desk would answer `SPREAD_NEEDS_SELECTION`
 * otherwise, which `errorToastMiddleware` renders, and the panel must not say it twice.
 *
 * **The mask is honoured by the desk, not pre-refused here.** A property outside the selection's
 * families writes nothing and answers `skippedFamilies` — a 200, the Look press's shape — toasted
 * in `skippedRowsMessage`'s vocabulary, keyed so a Live drag under a mask replaces one toast.
 *
 * **About each position** (venue-visit plan S12) is the intent arm's second mode on Position, beside
 * *Between two values*: one
 * amount per axis (*Pan · Tilt · Both*, the outer heads' offset in centre steps) about **each
 * head's own position**, symmetric about the middle of the order, under the same Curve · Order ·
 * Parts · Over. The desk does the arithmetic (`mode: FAN` on the same route) and reads where each
 * head starts — Local's value, else the stage — or takes the row being edited, which a
 * `write: false` host hands in as [IntentSpreadPlan.fanBases]. **The panel holds the gesture's
 * anchor**: the first send's answer says where each head started (`base`), and every later send in
 * the gesture carries it back, so a Live drag never fans a fan; until that answer is in, later sends
 * wait for it rather than read heads it is moving. The anchor, and the amounts with it, go when the
 * selection changes, the popover closes, the mode or property changes, or *Re-read* is pressed.
 *
 * **The colour picker is seeded, never fed its own writes.** `ColourEditor`'s `combinedCss` moves
 * only when the panel means the knob to move — switching the endpoint being edited, Swap, the
 * Colour editor's *Spread…* hand-over — for the ping-pong reason documented on that prop. The
 * endpoint picker is the busk tab's shipped measurement in every host: `ColourEditor` compact, the
 * picker and R/G/B alone (a colour intent has no emitter component), sized by `index.css`'s
 * `.colour-picker-compact` rule and never by a utility (a Tailwind utility cannot size
 * `.react-colorful`; `index.css` says why).
 *
 * **The keyboard reaches both hosts**: Enter applies, comma steps From → To, and the first field is
 * focused on open in the popover alone — both sheet forms are reached by a finger. Enter *applies*
 * rather than merely closing, unlike a cell editor's: this is the one value panel that does not
 * write as it is edited (until Live is on), so there is a press to stand in for.
 */

/** A hand-over into the panel: the Colour editor's colour as *From*, or the position cell's *Fan…* opening About each position. */
export type SpreadSeed = ColourSpreadSeed | FanSpreadSeed

/** The Colour editor's *Fan…*: its colour as *From*. */
export interface ColourSpreadSeed {
  /**
   * The colour, handed over as *From* — its **RGB** only. A colour intent has no emitter component
   * (white, amber and UV are rows of their own in the template grammar), so a white or amber the
   * colour editor was driving is dropped here and *From* is the RGB part with policy `extract`,
   * which re-derives a white per head.
   */
  from: { r: number; g: number; b: number }
  /** A fresh identity per hand-over, so two hand-overs of one colour both land. */
  key: number
}

/** The position cell's *Fan…*: the panel on Position, About each position, at no fan, over where the heads are. */
export interface FanSpreadSeed {
  fan: true
  key: number
}

/** The request the panel builds; the host adds `projectId` and the fade. `write` is present only when false (§6 of the plan). */
export type SpreadRequestBody = Omit<SpreadRequest, 'projectId' | 'fadeMs'>

interface SpreadPlanBase {
  col: string
  label: string
  /** Steps over Heads. A plan with fewer than two is offered but the trigger does not open for it alone. */
  count: number
}

/** A desk-resolved spread over a property in the template vocabulary. */
export interface IntentSpreadPlan extends SpreadPlanBase {
  kind: 'intent'
  /** The heads, as the request names them — a group as a group, a cell by its element key. */
  targets: readonly CueTarget[]
  /** Steps over Cells; 0 leaves the switch pressable, and the desk spreads it as Heads. */
  cellCount: number
  /** The families the segment draws, in `ATTRIBUTE_FAMILIES` order — what the heads can take. Empty draws all four. */
  families: readonly AttributeFamily[]
  /** Which of [families] may be pressed; absent, all of them. The rest are drawn disabled with [familyRefusal]. */
  offered?: readonly AttributeFamily[]
  familyRefusal?: string
  /** The properties the row offers per family; absent, `spreadPropertiesFor`. The first is the default. */
  propertiesFor?: (family: AttributeFamily) => TemplateProperty[]
  /**
   * The family and property the panel opens on — the programmer's marquee column. Absent, the
   * panel lands on the first family [mask] names that the heads can take, and re-derives that as
   * the heads arrive (the busk tab's rule).
   */
  initial?: { family: AttributeFamily; property?: TemplateProperty }
  /** The selection's attribute mask, sent as `families`. Null is every attribute. */
  mask: readonly AttributeFamily[] | null
  /**
   * The colour templates an endpoint may name, already filtered by the host through
   * `isSpreadColourTemplate` (`fx/FxColourTemplates.tsx`). Absent, none are offered. The host reads
   * the library because this panel is store-free: the patch list, the cue sheet and the DMX sheet
   * mount it without a store, and a query here would reach one on their behalf.
   */
  colourTemplates?: readonly TemplateSummary[]
  /** `write: false` on every request — a focused Look layer (D6). Absent, the key is omitted. */
  write?: false
  /**
   * Fan: each head's starting position **in the row being edited** — the `write: false` hosts' own
   * `pan,tilt` per head that holds one, sent with the gesture's first request. Absent, the desk reads
   * every head (Local, else the stage).
   */
  fanBases?: () => SpreadBase[]
  /** What the read-out calls the rows [fanBases] reads — *the layer’s rows*, *Q12’s values*. */
  fanRowsLabel?: string
  /**
   * Which rows a fan lands in — a focused layer's or a cue's id, absent for Local. A change drops
   * the anchor as a new selection does: an anchor read in one scope is no start in another.
   */
  fanScope?: string
  /** Send one request. A refusal is already toasted by `errorToastMiddleware`; the panel ignores it. */
  send: (request: SpreadRequestBody) => Promise<SpreadResponse>
  /** The latest request's answer, after its `skippedFamilies` have been toasted — the Look-layer arm reads `written[]`. */
  onAnswer?: (answer: SpreadResponse, request: SpreadRequestBody) => void
}

/** A client-walked byte spread on a column the desk has no intent for (D15). */
export interface RawSpreadPlan extends SpreadPlanBase {
  kind: 'raw'
  /** Steps over Cells; 0 leaves the switch pressable, and [apply] is handed Heads. */
  cellCount: number
  /** Values in step order: one per head over Heads, one per cell over Cells. */
  apply: (values: number[], over: SpreadOver) => void
}

/** From · Step in visible-row order — the patch list's Address column. */
export interface AddressSpreadPlan extends SpreadPlanBase {
  kind: 'address'
  /** Names the universe the walk is on; the PUT cannot move a head to another. */
  universe: number
  /** One channel count per head, in plan order; a blank step lands each head after the previous by its own footprint. */
  footprints: readonly number[]
  /** What the landing line says about a walk — the heads that would collide, or nothing. */
  check?: (channels: number[]) => string | null
  apply: (channels: number[]) => void
}

/** From · To in milliseconds along a curve — the cue sheet's Fade column. */
export interface DurationSpreadPlan extends SpreadPlanBase {
  kind: 'duration'
  /** Names each point for the landing line, in plan order: `Q6`, `Q7`, … */
  names?: readonly string[]
  apply: (ms: number[]) => void
}

export type SpreadPlan = IntentSpreadPlan | RawSpreadPlan | AddressSpreadPlan | DurationSpreadPlan

export interface SpreadForm {
  family: AttributeFamily
  property: TemplateProperty
  from: SpreadEndpoint
  to: SpreadEndpoint
  curve: SpreadCurve
  order: SpreadOrder
  parts: number
  over: SpreadOver
  seed: number
  /** Position only: *Ends* or *Fan*. */
  mode: SpreadMode
  axes: FanAxes
  /** Fan only: in pan and tilt, or across the stage — the position pad's mode (`lib/positionPadMode.ts`). */
  space: PositionPadMode
  /** The fan's amounts, centre steps on each axis; meaningful only against the gesture's anchor. */
  fanPan: number
  fanTilt: number
  /** A stage fan's amounts, metres towards SL and upstage; likewise. */
  fanX: number
  fanY: number
}

interface SpreadPanelProps {
  /** One per column the selection can spread. Empty when nothing selected spreads. */
  plans: readonly SpreadPlan[]
  /**
   * **popover**: a trigger on the selection bar opening `EditorSurface` — the programmer's row C
   * and the three kit sheets. **docked**: the busk tab's frame — the body as the tab's one scroller,
   * the footer static under it.
   */
  host: 'popover' | 'docked'
  /** The popover's first line — the programmer's *4 heads · Local* and the column (`EditorLabelLine`). */
  labelLine?: ReactNode
  /** A note in the footer beside the save slot — the Look-layer arm's sentence. */
  footerNote?: ReactNode
  /** The footer's save slot — the busk tab's *Save as Look…*. The programmer passes none (D11). */
  save?: ReactNode
  /** A *From* handed over by a colour editor's *Fan…*; applied once, on change, and it opens the popover host. */
  seed?: SpreadSeed | null
  /** Called once a seed has been applied, so the host can drop it. */
  onSeedConsumed?: () => void
  /** Docked: force the tighter layout. */
  compact?: boolean
  /**
   * Popover: the surface refuses the gesture as a whole — a read-only scope, a locked show, an
   * offline desk — and this is the reason the disabled button carries. Wins over every other title.
   */
  disabledReason?: string | null
  /** Popover: the columns this surface *can* spread, for the title when the selection has none of them. */
  drivableHint?: string
  /**
   * Popover: nothing is selected at all. A surface that filters its plans down before handing them
   * in must say so itself, or a marquee over columns Spread cannot drive would read as no selection.
   */
  noSelection?: boolean
  /** Popover: a way past [disabledReason] — the trigger stays live and a press calls this instead of opening. */
  onRefused?: () => void
  /**
   * Popover: another host has this verb while it is open — the programmer rail's Spread tab. The
   * trigger stays live and a press calls this instead of opening, so one marquee never has two
   * Spread panels (editor-kit plan session 4).
   */
  onClaimed?: () => void
  /** Popover: on the trigger — the selection bar folds it away at phone widths. */
  className?: string
  /**
   * The colour endpoint's editor — `ColourEditor`, handed in by the two hosts that draw a colour
   * endpoint (the busk tab and the programmer's row C). A component rather than an import because
   * this panel is **store-free** and the editor is not: its hidden appearance leaves and its Palettes
   * band reach the store, and the patch list, the cue sheet and the DMX sheet mount this panel
   * without one. A host with no colour plan passes nothing and never draws the branch.
   */
  colourEditor?: ComponentType<ColourEditorProps>
}

const EMPTY_SELECTION_TOAST = 'spread-panel-empty-selection'
/**
 * The compact curve row's fold — below 300px of programmer rail (call 12; the docked panel is the
 * rail's width less its 1px edge, and Tailwind's `@max-[…]` is a strict `<`). Only the docked host
 * declares the container, so in the popover and the kit sheets the class never matches. The busk
 * Spread tab is docked too and carries the container, but its sheet's floor is 320, so it never
 * crosses the fold there either: the programmer rail is the one host that reaches it.
 */
export const SPREAD_COMPACT_WORD_CLASS = '@max-[299px]:sr-only'
const COMPACT_WRAP_CLASS = '@max-[299px]:flex-wrap'
/** Keyed like the endpoint's error toast: a Live drag under a mask answers `skippedFamilies` on every write. */
const SKIPPED_FAMILIES_TOAST = 'spread-panel-skipped-families'

/** Where a fresh panel lands: the first family the mask names that the heads can take, else the first they can take. */
/**
 * An Order's words on the order row: the first word always, the rest folded under the compact rung
 * (`SPREAD_COMPACT_WORD_CLASS`, `sr-only`), so *Centre out* reads *Centre* on a panel below 300px.
 * Its item carries the whole word as `aria-label`, so its name is the effect editor's word at every
 * width (`lib/orderLabels.ts`, one vocabulary) — the name computation drops the folded span's space.
 */
function OrderWord({ label }: { label: string }) {
  const space = label.indexOf(' ')
  if (space < 0) return <>{label}</>
  return (
    <>
      {label.slice(0, space)}
      <span className={SPREAD_COMPACT_WORD_CLASS}>{label.slice(space)}</span>
    </>
  )
}

function initialFamily(available: readonly AttributeFamily[], mask: readonly AttributeFamily[] | null): AttributeFamily {
  return mask?.find((family) => available.includes(family)) ?? available[0] ?? 'INTENSITY'
}

function formFor(property: TemplateProperty, base?: SpreadForm): SpreadForm {
  const prefs = getSpreadFanPrefs()
  return {
    family: property.family,
    property,
    ...defaultSpreadEndpoints(property),
    curve: base?.curve ?? 'LINE',
    order: base?.order ?? 'LINEAR',
    parts: base?.parts ?? 1,
    over: base?.over ?? 'HEADS',
    seed: base?.seed ?? 0,
    mode: base?.mode ?? prefs.mode,
    axes: base?.axes ?? prefs.axes,
    space: base?.space ?? getPositionPadMode(),
    fanPan: 0,
    fanTilt: 0,
    fanX: 0,
    fanY: 0,
  }
}

/** Whether a form spreads as a fan — Fan chosen, on a position property. */
export function isFanForm(form: SpreadForm): boolean {
  return form.mode === 'FAN' && spreadEditorKind(form.property) === 'position'
}

/** Whether a fan form fans across the stage, in metres, rather than in pan and tilt. */
export function isStageFanForm(form: SpreadForm): boolean {
  return isFanForm(form) && form.space === 'STAGE'
}

/**
 * The amounts a fan form sends: an axis it does not move is absent, which the desk leaves where it
 * was. On the stage *Pan* is across (x) and *Tilt* is depth (y).
 */
function fanAmountsOf(form: SpreadForm): NonNullable<SpreadRequestBody['fan']> {
  if (form.space === 'STAGE') {
    return {
      ...(form.axes !== 'TILT' ? { x: form.fanX } : {}),
      ...(form.axes !== 'PAN' ? { y: form.fanY } : {}),
    }
  }
  return {
    ...(form.axes !== 'TILT' ? { pan: form.fanPan } : {}),
    ...(form.axes !== 'PAN' ? { tilt: form.fanTilt } : {}),
  }
}

/**
 * The Over a spread is actually made over. Cells with no cells to split is Heads — the operator's
 * choice is kept (`lib/spreadOver.ts`) and drawn, but a request never says Cells for a selection
 * where the plan counts none. One rule for both kinds: the raw arm steps by it, and the desk is sent
 * it rather than trusted to make the same substitution (it would: `programmerSpread.kt` makes each
 * fixture without elements its own unit, and a plan counts 0 only when no target has elements).
 */
export function effectiveSpreadOver(over: SpreadOver, cellCount: number): SpreadOver {
  return over === 'CELLS' && cellCount > 0 ? 'CELLS' : 'HEADS'
}

/** The request two forms would send, compared as strings: the live push's dedupe. */
const sameSend = (a: Send, b: Send) => JSON.stringify(a) === JSON.stringify(b)

/**
 * The request a form sends over a selection, or null when an endpoint is not yet something the desk
 * can take (a half-typed number). The fade is not part of it: the host reads it at send time.
 * `write` is present **only when false** — the REST Json refuses an unknown key, so a desk
 * mid-upgrade must never see it on a Local spread (plan §6).
 */
export function spreadRequestOf(plan: IntentSpreadPlan, form: SpreadForm): SpreadRequestBody | null {
  const fan = isFanForm(form)
  if (!fan && (!isCompleteSpreadEndpoint(form.from) || !isCompleteSpreadEndpoint(form.to))) return null
  const body: SpreadRequestBody = {
    targets: [...plan.targets],
    families: plan.mask == null ? undefined : [...plan.mask],
    property: form.property.propertyName,
    // A fan's base is not part of the form: the panel adds the gesture's anchor at send time.
    ...(fan ? { mode: 'FAN' as const, fan: fanAmountsOf(form) } : { from: serializeSpreadEndpoint(form.from), to: serializeSpreadEndpoint(form.to) }),
    curve: form.curve,
    order: form.order,
    parts: form.parts,
    // A stage fan aims heads, and a cell has no placement of its own.
    over: isStageFanForm(form) ? 'HEADS' : effectiveSpreadOver(form.over, plan.cellCount),
    seed: form.seed,
  }
  if (plan.write === false) body.write = false
  return body
}

/** One fan gesture's anchor: where each head started, once the desk has answered the first send. */
interface FanGesture {
  anchor: SpreadBase[] | null
  /** The first send is in flight: later sends wait for it rather than read heads it is moving. */
  anchoring: boolean
  /** The latest send that is waiting for the anchor. */
  waiting: SpreadRequestBody | null
  /** A stage fan's latest degrees by head, so each head's next solve stays on the side it is on. */
  near: Map<string, AimNear>
  /** The request whose answer last updated [near]: an older answer landing later is not newer degrees. */
  nearSeq: number
}

const newFanGesture = (): FanGesture => ({ anchor: null, anchoring: false, waiting: null, near: new Map(), nearSeq: 0 })

/** The heads a stage fan's answer left where they were because the point was outside their travel. */
function outOfReachCount(answer: SpreadResponse): number {
  return (answer.skipped ?? []).filter((skip) => skip.reason.startsWith('out of reach')).length
}

interface FanReadout {
  fromStage: number
  clamped: number
  outOfReach: number
}

/** One live write: a desk request, or the raw kind's values. */
type Send = { kind: 'intent'; body: SpreadRequestBody } | { kind: 'raw'; values: number[]; over: SpreadOver }

const NO_TEMPLATES: readonly TemplateSummary[] = []

export function SpreadPanel({
  plans,
  host,
  labelLine,
  footerNote,
  save,
  seed,
  onSeedConsumed,
  compact,
  disabledReason,
  drivableHint = 'a value',
  noSelection,
  onRefused,
  onClaimed,
  className,
  colourEditor: ColourEndpointEditor,
}: SpreadPanelProps) {
  const [isOpen, setIsOpen] = useState(false)
  const [column, setColumn] = useState<string | null>(null)

  // The docked host keeps a plan with nothing selected: the tab still draws its rows and Apply
  // toasts the empty-selection sentence. The popover host has a trigger to disable instead.
  const spreadable = useMemo(
    () => plans.filter((plan) => host === 'docked' || plan.count > 0 || ('cellCount' in plan && plan.cellCount > 0)),
    [plans, host],
  )
  // The chooser's pick may not be in this selection any more (the marquee moved to another column
  // since the panel was last open); fall back to the first plan rather than rendering a blank
  // select over an empty plan.
  const activePlan = spreadable.find((plan) => plan.col === column) ?? spreadable[0]
  const intentPlan = activePlan?.kind === 'intent' ? activePlan : null

  // ── The intent and raw form ─────────────────────────────────────────────────────────────────
  /** What the heads can take — empty until they have answered (a cold busk mount). */
  const headFamilies = useMemo<readonly AttributeFamily[]>(() => intentPlan?.families ?? [], [intentPlan])
  /** What the segment draws: the heads' families, or all four while nothing has answered. */
  const available = headFamilies.length > 0 ? headFamilies : ATTRIBUTE_FAMILIES
  const propertiesFor = useCallback(
    (family: AttributeFamily): TemplateProperty[] => {
      const offered = intentPlan?.propertiesFor?.(family) ?? spreadPropertiesFor(family)
      return offered.length > 0 ? offered : spreadPropertiesFor(family)
    },
    [intentPlan],
  )
  const propertiesForRef = useRef(propertiesFor)
  propertiesForRef.current = propertiesFor

  const [form, setForm] = useState<SpreadForm>(() => {
    const initial = intentPlan?.initial
    const family = initial?.family ?? initialFamily(headFamilies, intentPlan?.mask ?? null)
    // Over is the window's, not this mount's (`lib/spreadOver.ts`): a panel mounted again after an
    // empty selection opens on the operator's last choice.
    return { ...formFor(initial?.property ?? propertiesFor(family)[0]), over: getSpreadOver() }
  })
  // Written by the handlers that move `form`, never at render time — the Colour tab's
  // `channelsRef` rule: a fast drag can dispatch its last move and its release inside one task.
  const formRef = useRef(form)
  const [rawEnds, setRawEnds] = useState({ from: 0, to: 255 })
  const rawEndsRef = useRef(rawEnds)
  const [live, setLive] = useState(false)
  // Read by the window release handler, which must not flush once Live is off: the switch can be
  // toggled from the keyboard, which dispatches a click and no `pointerup` to clear the gesture.
  const liveRef = useRef(live)
  liveRef.current = live
  const [editing, setEditing] = useState<'from' | 'to'>('from')
  /** Only the latest request's answer is reported; a property or selection change disowns the rest. */
  const requestSeq = useRef(0)
  const gestureRef = useRef(false)
  /** The picker's knob is seeded from this and only this — see the docblock. */
  const [pickerSeed, setPickerSeed] = useState<{ css: string; key: number }>(() => ({ css: '#000000', key: 0 }))
  const activePlanRef = useRef(activePlan)
  activePlanRef.current = activePlan

  const colourTemplates = intentPlan?.colourTemplates ?? NO_TEMPLATES

  const colourTemplatesRef = useRef(colourTemplates)
  colourTemplatesRef.current = colourTemplates
  // A template endpoint is drawn at the template's own colour (its swatch): the knob and the
  // R/G/B fields say what the desk will interpolate from, and a nudge converts the endpoint to a
  // literal built from that colour rather than from 0/0/0 (session 3 review, finding 4 — the busk
  // tab's body had it since it was written).
  const reseedPicker = useCallback((endpoint: SpreadEndpoint) => {
    const hex = endpointHex(endpoint, colourTemplatesRef.current)
    if (hex == null) return
    const rgb = hexToRgb(hex)
    setPickerSeed((prev) => ({ css: computeCombinedCss(rgb.r, rgb.g, rgb.b, 0, 0, 0), key: prev.key + 1 }))
  }, [])

  // ── The fan's anchor (see the docblock) ──
  /**
   * The fan gesture under way. A dropped anchor is a new gesture **object**, so the old one still
   * finishes on its own: its first answer still lands its anchor and sends the send that waited
   * for it — the amount a drag was let go on — even after the popover closed.
   */
  const fanGestureRef = useRef<FanGesture>(newFanGesture())
  const [fanReadout, setFanReadout] = useState<FanReadout | null>(null)

  const sendIntent = useCallback((plan: IntentSpreadPlan, outgoing: SpreadRequestBody, gesture: FanGesture) => {
    let body = outgoing
    const fan = body.mode === 'FAN'
    let given: ReadonlySet<string> | null = null
    if (fan) {
      if (gesture.anchor != null) {
        body = { ...body, base: gesture.anchor, ...(gesture.near.size > 0 ? { near: [...gesture.near.values()] } : {}) }
      } else if (gesture.anchoring) {
        gesture.waiting = body
        return
      } else if (Object.values(body.fan ?? {}).every((amount) => amount === 0)) {
        // No fan yet and none asked for: sending it would only write every head where it stands.
        return
      } else {
        gesture.anchoring = true
        const rows = plan.fanBases?.() ?? []
        given = new Set(rows.map((row) => row.target.key))
        if (rows.length > 0) body = { ...body, base: rows }
      }
    }
    const seq = ++requestSeq.current
    /** The gesture's first send has settled: send what waited for it, against the anchor if one landed. */
    const settle = () => {
      gesture.anchoring = false
      const waiting = gesture.waiting
      gesture.waiting = null
      if (waiting != null) sendIntent(plan, waiting, gesture)
    }
    void plan
      .send(body)
      .then((answer: SpreadResponse) => {
        if (fan && seq > gesture.nearSeq && (answer.near?.length ?? 0) > 0) {
          gesture.nearSeq = seq
          for (const near of answer.near ?? []) gesture.near.set(near.target.key, near)
        }
        if (given != null) {
          const bases = answer.base ?? []
          // No base — the mask kept Position out, say — is no anchor: the next send reads the heads again.
          if (bases.length > 0) {
            gesture.anchor = bases
            if (gesture === fanGestureRef.current) {
              setFanReadout({
                fromStage: bases.filter((b) => !given.has(b.target.key)).length,
                clamped: answer.clamped?.length ?? 0,
                outOfReach: outOfReachCount(answer),
              })
            }
          }
          settle()
        }
        if (seq !== requestSeq.current) return
        const message = skippedRowsMessage(answer.skippedFamilies ?? [], plan.mask)
        if (message != null) toast.warning(message, { id: SKIPPED_FAMILIES_TOAST })
        if (fan && given == null && gesture === fanGestureRef.current) {
          setFanReadout((prev) => (prev == null ? prev : { ...prev, clamped: answer.clamped?.length ?? 0, outOfReach: outOfReachCount(answer) }))
        }
        plan.onAnswer?.(answer, body)
      })
      // The refusal is toasted by `errorToastMiddleware`; nothing landed, so there is nothing here to
      // undo — but a send that waited on it is tried as the gesture's first instead of being lost.
      .catch(() => {
        if (given != null) settle()
        ignoreReportedError()
      })
  }, [])

  const { push, flush, reset } = useLivePush<Send>(
    (send) => {
      const plan = activePlanRef.current
      if (send.kind === 'raw') {
        if (plan?.kind === 'raw') plan.apply(send.values, send.over)
        return
      }
      if (plan?.kind !== 'intent') return
      sendIntent(plan, send.body, fanGestureRef.current)
    },
    { equals: sameSend },
  )

  /** Drop the fan's anchor: the next fan send starts a new gesture and reads the heads again. */
  const dropAnchor = useCallback(() => {
    fanGestureRef.current = newFanGesture()
    setFanReadout(null)
  }, [])

  /** A fresh fan gesture: no anchor, and no fan — the amounts back at 0 over wherever the heads now are. */
  const resetFan = useCallback(() => {
    dropAnchor()
    reset()
    const { fanPan, fanTilt, fanX, fanY } = formRef.current
    if (fanPan === 0 && fanTilt === 0 && fanX === 0 && fanY === 0) return
    const next = { ...formRef.current, fanPan: 0, fanTilt: 0, fanX: 0, fanY: 0 }
    formRef.current = next
    setForm(next)
  }, [dropAnchor, reset])

  /** Disown any answer still in flight. */
  const clearAnswer = useCallback(() => {
    requestSeq.current += 1
  }, [])

  /** What the form as it stands would send, or null — with the empty-selection refusal said once. */
  const sendFor = useCallback((next: SpreadForm, ends: { from: number; to: number }, say: boolean): Send | null => {
    const plan = activePlanRef.current
    if (plan?.kind === 'raw') {
      // The plan is handed the over its values were built for.
      const over = effectiveSpreadOver(next.over, plan.cellCount)
      const steps = over === 'CELLS' ? plan.cellCount : plan.count
      const values = rawValues(ends.from, ends.to, spreadFractions(steps, next.curve, next.order, next.parts, next.seed))
      return { kind: 'raw', values, over }
    }
    if (plan?.kind !== 'intent') return null
    if (plan.targets.length === 0) {
      if (say) toast.error('Select the fixtures this should land on first', { id: EMPTY_SELECTION_TOAST })
      return null
    }
    const body = spreadRequestOf(plan, next)
    return body == null ? null : { kind: 'intent', body }
  }, [])

  /** Move the form; while Live, every move is a write. */
  const commit = useCallback(
    (patch: Partial<SpreadForm>) => {
      // Over is the window's (`lib/spreadOver.ts`): written through, so a panel mounted later — or
      // one mounted beside this one — opens on it.
      if (patch.over != null) setSpreadOver(patch.over)
      if (patch.mode != null || patch.axes != null) {
        setSpreadFanPrefs({ mode: patch.mode ?? formRef.current.mode, axes: patch.axes ?? formRef.current.axes })
      }
      const next = { ...formRef.current, ...patch }
      formRef.current = next
      setForm(next)
      if (!liveRef.current) return
      const send = sendFor(next, rawEndsRef.current, true)
      if (send == null) return
      gestureRef.current = true
      push(send)
    },
    [sendFor, push],
  )

  const commitRaw = useCallback(
    (patch: Partial<{ from: number; to: number }>) => {
      const next = { ...rawEndsRef.current, ...patch }
      rawEndsRef.current = next
      setRawEnds(next)
      if (!liveRef.current) return
      const send = sendFor(formRef.current, next, true)
      if (send == null) return
      gestureRef.current = true
      push(send)
    },
    [sendFor, push],
  )

  // The release: whatever the finger let go on lands, past the floor — read from the window, as
  // the Colour tab and the tempo fader read theirs, because the picker binds its own release to
  // the document and a drag let go outside the panel never reaches a handler on it.
  useEffect(() => {
    const onRelease = () => {
      if (!gestureRef.current) return
      gestureRef.current = false
      if (!liveRef.current) return
      const send = sendFor(formRef.current, rawEndsRef.current, false)
      if (send != null) flush(send)
    }
    window.addEventListener('pointerup', onRelease)
    window.addEventListener('pointercancel', onRelease)
    return () => {
      window.removeEventListener('pointerup', onRelease)
      window.removeEventListener('pointercancel', onRelease)
    }
  }, [sendFor, flush])

  // A fresh selection is a fresh gesture: nothing the last one sent says where these heads are,
  // and an answer in flight describes heads that are no longer selected.
  const selectionKey = useMemo(
    () =>
      intentPlan == null
        ? `${activePlan?.kind ?? ''}:${activePlan?.count ?? 0}`
        : `${intentPlan.fanScope ?? 'local'}#${intentPlan.targets.map((t) => `${t.type}:${t.key}`).join('|')}`,
    [intentPlan, activePlan],
  )
  useEffect(() => {
    gestureRef.current = false
    reset()
    clearAnswer()
    resetFan()
  }, [selectionKey, reset, clearAnswer, resetFan])

  /** Change the property (and family): fresh endpoints, and an answer in flight no longer describes this property. */
  const chooseProperty = useCallback(
    (property: TemplateProperty) => {
      const next = formFor(property, formRef.current)
      formRef.current = next
      setForm(next)
      clearAnswer()
      dropAnchor()
      setEditing('from')
      reseedPicker(next.from)
      reset()
    },
    [reseedPicker, reset, clearAnswer, dropAnchor],
  )

  /**
   * Whether the family is settled — by the operator choosing one, by the host answering it
   * ([IntentSpreadPlan.initial]), or by the panel having once resolved its default against real
   * heads. Until then the family is the panel's own default, the first the mask names that the
   * heads can take, re-derived as the heads and the mask arrive: on a cold mount the fixture list
   * may not have answered yet, so the initialiser fell back to Intensity. Settled, the family
   * stands as long as the heads can still take it — a head added mid-edit must not reset the
   * endpoints the operator has typed — and a family that left the selection takes its property
   * with it. An empty selection keeps whatever is showing.
   */
  const chosenRef = useRef(false)
  const initialKey = intentPlan?.initial == null ? null : `${intentPlan.initial.family}:${intentPlan.initial.property?.propertyName ?? ''}`
  const appliedInitialRef = useRef<string | null>(null)
  useEffect(() => {
    if (intentPlan == null) return
    if (initialKey != null) {
      // The host answered: the marquee's column, re-answered whenever it moves.
      if (appliedInitialRef.current === initialKey) return
      appliedInitialRef.current = initialKey
      chosenRef.current = true
      const initial = intentPlan.initial!
      const property = initial.property ?? propertiesForRef.current(initial.family)[0]
      if (property.propertyName !== formRef.current.property.propertyName) chooseProperty(property)
      return
    }
    if (headFamilies.length === 0) return
    const current = formRef.current.family
    const wanted = chosenRef.current && headFamilies.includes(current) ? current : initialFamily(headFamilies, intentPlan.mask)
    chosenRef.current = true
    if (wanted !== current) chooseProperty(propertiesForRef.current(wanted)[0])
  }, [intentPlan, initialKey, headFamilies, form.family, chooseProperty])

  const cellCount = activePlan != null && 'cellCount' in activePlan ? activePlan.cellCount : 0
  // Over is the operator's choice and survives a change of selection, an empty one included — it
  // is kept in `lib/spreadOver.ts`, which `commit` writes through. Cells over heads with no cells is
  // not refused or reset, it spreads as Heads (`effectiveSpreadOver`), so a marquee moved from a
  // pixel bar to a par and back keeps Cells.
  //
  // A panel mounted beside the one that moved it (row C's popover and the rail's Spread tab are
  // both mounted on the programmer) follows the store here — the form only, **never a push**: that
  // panel's Live may still be on over a selection the operator is not touching.
  const storedOver = useSpreadOver()
  useEffect(() => {
    if (formRef.current.over === storedOver) return
    const next = { ...formRef.current, over: storedOver }
    formRef.current = next
    setForm(next)
  }, [storedOver])
  // The mode and axes the same way (`lib/spreadFan.ts`). Another panel leaving Fan drops this one's
  // anchor too: its amounts no longer describe anything it is showing.
  const storedFan = useSpreadFanPrefs()
  useEffect(() => {
    const current = formRef.current
    if (current.mode === storedFan.mode && current.axes === storedFan.axes) return
    const next = { ...current, mode: storedFan.mode, axes: storedFan.axes }
    formRef.current = next
    setForm(next)
    if (current.mode !== storedFan.mode) resetFan()
  }, [storedFan, resetFan])
  // The fan's space is the position pad's mode, one fact for both; a change is a new gesture.
  const storedSpace = usePositionPadMode()
  useEffect(() => {
    if (formRef.current.space === storedSpace) return
    resetFan()
    const next = { ...formRef.current, space: storedSpace }
    formRef.current = next
    setForm(next)
  }, [storedSpace, resetFan])

  // The colour editor's hand-over: Colour, From set to its colour, the knob seeded to it, applied
  // once per seed and then dropped by the host. In the popover host it opens the panel.
  useEffect(() => {
    if (seed == null) return
    if ('fan' in seed) {
      // The position cell's *Spread…*: Position, Fan, no fan yet — a fan starts where the heads are.
      if (intentPlan == null || (headFamilies.length > 0 && !headFamilies.includes('POSITION'))) {
        onSeedConsumed?.()
        return
      }
      chosenRef.current = true
      const property = propertiesForRef.current('POSITION').find((p) => p.intent === 'position') ?? spreadPropertiesFor('POSITION')[0]
      if (property.propertyName !== formRef.current.property.propertyName) chooseProperty(property)
      setSpreadFanPrefs({ mode: 'FAN', axes: formRef.current.axes })
      const next = { ...formRef.current, mode: 'FAN' as const }
      formRef.current = next
      setForm(next)
      resetFan()
      clearAnswer()
      if (host === 'popover') setIsOpen(true)
      onSeedConsumed?.()
      return
    }
    // A selection that cannot take colour has nothing for the hand-over to set; the seed is spent
    // rather than left to re-apply, and the family segment keeps a checked item.
    if (intentPlan == null || (headFamilies.length > 0 && !headFamilies.includes('COLOUR'))) {
      onSeedConsumed?.()
      return
    }
    chosenRef.current = true
    const property = propertiesForRef.current('COLOUR').find((p) => p.intent === 'colour') ?? spreadPropertiesFor('COLOUR')[0]
    const from = colourEndpointOf(seed.from)
    const current = formRef.current
    const next: SpreadForm = {
      ...current,
      family: 'COLOUR',
      property,
      from,
      to: current.property.propertyName === property.propertyName && current.to.kind !== 'level' ? current.to : defaultSpreadEndpoints(property).to,
    }
    formRef.current = next
    setForm(next)
    clearAnswer()
    setEditing('from')
    reseedPicker(from)
    reset()
    if (host === 'popover') setIsOpen(true)
    onSeedConsumed?.()
  }, [seed, onSeedConsumed, reseedPicker, reset, clearAnswer, headFamilies, intentPlan, host, chooseProperty, resetFan])

  // First paint of a colour editor: seed the knob from *From* (the initial state is a placeholder).
  const seededRef = useRef(false)
  useEffect(() => {
    if (seededRef.current) return
    seededRef.current = true
    reseedPicker(formRef.current.from)
  }, [reseedPicker])

  const swap = () => {
    if (activePlan?.kind === 'raw') {
      commitRaw({ from: rawEndsRef.current.to, to: rawEndsRef.current.from })
      return
    }
    const { from, to } = formRef.current
    commit({ from: to, to: from })
    reseedPicker(editing === 'from' ? to : from)
  }

  // ── The address and duration forms ──────────────────────────────────────────────────────────
  const [fromAddress, setFromAddress] = useState('1')
  const [step, setStep] = useState('')
  const [fromDuration, setFromDuration] = useState('1s')
  const [toDuration, setToDuration] = useState('4s')

  // The address arm's landing, computed as it is typed so the line can name a collision before
  // Apply — the rule every desk surveyed makes visible, and the one the server cannot be relied on
  // for (the patch PUT has no overlap check; see CLAUDE.md §Sheet kit).
  const addressWalk = useMemo(() => {
    if (activePlan?.kind !== 'address') return null
    const from = Number(fromAddress)
    const stepValue = step.trim() === '' ? null : Number(step)
    if (!Number.isInteger(from) || from < 1) return { error: 'From is a channel, 1–512', channels: null }
    if (stepValue !== null && (!Number.isInteger(stepValue) || stepValue < 1)) {
      return { error: 'Step is a whole number of channels, or blank for each fixture’s footprint', channels: null }
    }
    const channels = walkAddresses(from, stepValue, activePlan.footprints)
    const last = channels[channels.length - 1] ?? from
    const lastFootprint = activePlan.footprints[activePlan.footprints.length - 1] ?? 1
    if (last + Math.max(1, lastFootprint) - 1 > 512) {
      return { error: `Runs past channel 512 on universe ${activePlan.universe}`, channels: null }
    }
    return { error: activePlan.check?.(channels) ?? null, channels }
  }, [activePlan, fromAddress, step])

  const durationWalk = useMemo(() => {
    if (activePlan?.kind !== 'duration') return null
    const from = parseDurationMs(fromDuration)
    const to = parseDurationMs(toDuration)
    if (from === undefined || to === undefined) {
      return { error: 'A fade is seconds, or a number with ms, s or m', ms: null }
    }
    return { error: null, ms: spreadDurations(from, to, activePlan.count, form.curve) }
  }, [activePlan, fromDuration, toDuration, form.curve])

  // ── Apply ───────────────────────────────────────────────────────────────────────────────────
  const plannedCount = activePlan?.count ?? 0
  // A spread needs at least two points on SOME plan to be worth opening — one point is a set, not
  // a spread — and a bar spread over its cells is many points on one head.
  const canSpread = !disabledReason && spreadable.some((plan) => plan.count >= 2 || ('cellCount' in plan && plan.cellCount >= 2))
  // The popover host applies the **chosen** plan only where it has two points: `canSpread` opens
  // the trigger for *some* plan, so a Dimmer + Speed marquee can open on the intent plan and land
  // on a raw plan with one head, whose whole walk is *from*. The docked host keeps the busk tab's
  // rule — one head is a set at *from*, and an empty selection is the toast — since that tab has
  // no trigger to refuse at.
  const chosenHasTwo = plannedCount >= 2 || cellCount >= 2
  const canApply =
    activePlan?.kind === 'address'
      ? plannedCount >= 2 && addressWalk?.channels != null && addressWalk.error == null
      : activePlan?.kind === 'duration'
        ? plannedCount >= 2 && durationWalk?.ms != null
        : activePlan != null && (host === 'docked' || chosenHasTwo)

  const apply = useCallback(() => {
    const plan = activePlanRef.current
    if (!plan) return
    switch (plan.kind) {
      case 'intent':
      case 'raw': {
        const send = sendFor(formRef.current, rawEndsRef.current, true)
        if (send == null) return
        // An explicit press always sends, even the request Live sent a moment ago.
        reset()
        flush(send)
        return
      }
      case 'address':
        if (addressWalk?.channels && addressWalk.error == null) plan.apply(addressWalk.channels)
        return
      case 'duration':
        if (durationWalk?.ms) plan.apply(durationWalk.ms)
        return
    }
  }, [sendFor, reset, flush, addressWalk, durationWalk])

  const showsLive = activePlan?.kind === 'intent' || activePlan?.kind === 'raw'

  /**
   * Focus is taken only when the selection has settled the question the first field answers, and
   * only in the popover (both sheet forms are reached by a finger — `useEditorKeyboard`'s rule).
   * With one plan whose family is answered, the first question is From and it is focused the way a
   * cell editor's first field is; with a chooser to draw — several plans, or a marquee spanning two
   * families — the first question is still *which*, and jumping to a field would skip it.
   */
  const { contentRef, onKeyDown, onOpenAutoFocus } = useEditorKeyboard({
    autoFocus: host === 'popover' && spreadable.length === 1 && (intentPlan == null || (intentPlan.offered ?? intentPlan.families).length <= 1),
    onDone: () => {
      if (!canApply) return
      apply()
      if (host === 'popover') {
        setIsOpen(false)
        resetFan()
      }
    },
  })

  // A surface's own refusal is the one the operator can answer; "no two cells to spread" is not, so
  // the offer is made only where [disabledReason] is what closed the button.
  const refusable = disabledReason != null && onRefused != null
  // The docked host has no trigger to disable, so a surface's refusal — the programmer rail's tab
  // in Output or on a focused template layer — is drawn on the panel itself: the reason above a body
  // that takes no input, and no Live or Apply. The busk tab never passes one.
  const dockedRefusal = host === 'docked' ? (disabledReason ?? null) : null
  const title =
    disabledReason ??
    ((noSelection ?? plans.length === 0)
      ? 'Select cells to fan a value across'
      : !canSpread
        ? `Fan needs two or more cells in a column it can drive — ${drivableHint}`
        : 'Fan first→last across the selected cells')

  // ── The controls ────────────────────────────────────────────────────────────────────────────
  const editorKind = spreadEditorKind(form.property)
  const fanForm = intentPlan != null && isFanForm(form)
  const stageFan = fanForm && isStageFanForm(form)
  const properties = intentPlan == null ? [] : propertiesFor(form.family)
  const offeredFamilies = intentPlan?.offered ?? available
  const editingEndpoint = editing === 'from' ? form.from : form.to
  const editingRgb = hexToRgb(endpointHex(editingEndpoint, colourTemplates) ?? '#000000')
  const setEndpoint = (which: 'from' | 'to', endpoint: SpreadEndpoint) => commit({ [which]: endpoint })

  const curveRow = (
    <div>
      <EditorLabel>Curve</EditorLabel>
      <ToggleGroup
        type="single"
        aria-label="Curve"
        value={form.curve}
        onValueChange={(next) => next && commit({ curve: next as SpreadCurve })}
        className="mt-1 h-auto w-full justify-start"
      >
        {SPREAD_CURVES.map((curve) => (
          <ToggleGroupItem
            key={curve.id}
            value={curve.id}
            title={curve.hint}
            aria-label={curve.label}
            className="h-auto flex-1 flex-col gap-0.5 px-1 py-1 text-[10px]"
          >
            <CurvePicture curve={curve.id} fan={fanForm} />
            {/* The compact curve row: below 300px of rail the pictures stand alone and the word is
                the item's name (editor-kit plan session 4, call 12). A container query on the
                docked panel — the popover and the kit sheets have no container above them, and the
                busk sheet never goes under its 320 floor, so only the programmer rail reaches it. */}
            <span className={SPREAD_COMPACT_WORD_CLASS}>{curve.label}</span>
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  )

  const orderPartsOver = (
    <>
      <div>
        <EditorLabel>Order</EditorLabel>
        <ToggleGroup
          type="single"
          size="sm"
          aria-label="Order"
          value={form.order}
          onValueChange={(next) => {
            if (!next) {
              // Radix reports a re-press as an empty value; on Random that is a reshuffle.
              if (formRef.current.order === 'RANDOM') commit({ seed: formRef.current.seed + 1 })
              return
            }
            commit({ order: next as SpreadOrder })
          }}
          className="mt-1 w-full justify-start"
        >
          {SPREAD_ORDERS.filter((order) => order.id != null).map((order) => (
            <ToggleGroupItem
              key={order.label}
              value={order.id!}
              title={order.hint}
              aria-label={order.label}
              className="flex-1 text-xs"
            >
              <OrderWord label={order.label} />
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
        {/* The design's fifth order, said rather than offered: a disabled item in the row wrapped
            onto a second line at 288px and read as a control that was merely off. */}
        {SPREAD_ORDERS.filter((order) => order.id == null).map((order) => (
          <p key={order.label} data-spread-order-unavailable={order.label} className="mt-1 text-[10px] text-muted-foreground" title={order.hint}>
            {order.label}: not on the desk yet
          </p>
        ))}
      </div>

      {/* Runs beside Over is ~280px of row; under the compact width Over wraps beneath rather than
          running past the scroller's edge. */}
      <div className={cn('flex items-end gap-3', COMPACT_WRAP_CLASS)}>
        <div className="min-w-0 flex-1">
          <EditorLabel>Runs</EditorLabel>
          <div className="mt-1 flex items-center gap-1">
            <ToggleGroup
              type="single"
              size="sm"
              aria-label="Runs"
              value={SPREAD_PARTS_PRESETS.includes(form.parts) ? String(form.parts) : ''}
              onValueChange={(next) => next && commit({ parts: Number(next) })}
            >
              {SPREAD_PARTS_PRESETS.map((n) => (
                <ToggleGroupItem key={n} value={String(n)} className="text-xs">
                  {n}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
            <EditorField label="Runs, N" value={form.parts} min={1} onCommit={(n) => commit({ parts: Math.max(1, Math.round(n)) })} className="w-14" />
          </div>
        </div>
        <div>
          <EditorLabel>Over</EditorLabel>
          <ToggleGroup
            type="single"
            size="sm"
            aria-label="Over"
            value={stageFan ? 'HEADS' : form.over}
            onValueChange={(next) => next && commit({ over: next as SpreadOver })}
            className="mt-1"
          >
            <ToggleGroupItem value="HEADS" className="text-xs" title="Each fixture is one step">
              Heads
            </ToggleGroupItem>
            <ToggleGroupItem
              value="CELLS"
              className="text-xs"
              disabled={stageFan}
              title={
                stageFan
                  ? 'A stage fan aims whole fixtures — an element has no placement of its own'
                  : cellCount === 0
                    ? 'Each element is one step — no selected fixture has elements, so each fixture is one'
                    : `Each element is one step — ${cellCount} elements`
              }
            >
              Elements{cellCount > 0 && <span className="ml-1 text-muted-foreground tabular-nums">{cellCount}</span>}
            </ToggleGroupItem>
          </ToggleGroup>
        </div>
      </div>
    </>
  )

  const body = (
    <>
      {labelLine}
      {/* The chooser only where there is a choice between plans. A one-column marquee has already
          said which column, and a select with one option is a control that cannot be used. */}
      {spreadable.length > 1 && (
        <Select value={activePlan?.col ?? ''} onValueChange={(v) => setColumn(v)}>
          <SelectTrigger size="sm" className="w-32" aria-label="Column to fan">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {spreadable.map(({ col, label }) => (
              <SelectItem key={col} value={col}>
                {label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}

      {intentPlan != null && (
        <>
          <ToggleGroup
            type="single"
            size="sm"
            aria-label="Family"
            value={form.family}
            onValueChange={(next) => {
              if (!next) return
              chosenRef.current = true
              chooseProperty(propertiesFor(next as AttributeFamily)[0])
            }}
            className="w-full justify-start"
          >
            {available.map((family) => (
              <ToggleGroupItem
                key={family}
                value={family}
                className="flex-1 text-xs"
                data-spread-family={family}
                disabled={!offeredFamilies.includes(family)}
                title={offeredFamilies.includes(family) ? undefined : intentPlan.familyRefusal}
              >
                {FAMILY_LABELS[family].singular}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          {properties.length > 1 && (
            <ToggleGroup
              type="single"
              size="sm"
              aria-label="Attribute"
              value={form.property.propertyName}
              onValueChange={(next) => {
                const property = properties.find((p) => p.propertyName === next)
                if (property == null) return
                chosenRef.current = true
                chooseProperty(property)
              }}
              className="w-full justify-start"
            >
              {properties.map((property) => (
                <ToggleGroupItem key={property.propertyName} value={property.propertyName} className="flex-1 text-xs">
                  {property.label}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          )}

          {/* Position's two modes: From → To, or an amount about each head's own position. */}
          {editorKind === 'position' && (
            <ToggleGroup
              type="single"
              size="sm"
              aria-label="Fan mode"
              value={form.mode}
              onValueChange={(next) => {
                if (!next || next === formRef.current.mode) return
                resetFan()
                commit({ mode: next as SpreadMode })
              }}
              className="h-auto w-full"
            >
              <ToggleGroupItem value="ENDS" className="h-auto min-h-6 min-w-0 flex-1 py-0.5 text-xs leading-tight whitespace-normal" title="From one position to another across the heads">
                Between two values
              </ToggleGroupItem>
              <ToggleGroupItem value="FAN" className="h-auto min-h-6 min-w-0 flex-1 py-0.5 text-xs leading-tight whitespace-normal" title="An amount around each head’s own position — fan out, fan in">
                About each position
              </ToggleGroupItem>
            </ToggleGroup>
          )}
          {fanForm && (
            <ToggleGroup
              type="single"
              size="sm"
              aria-label="Fan axes"
              value={form.axes}
              onValueChange={(next) => next && commit({ axes: next as FanAxes })}
              className="w-full"
            >
              <ToggleGroupItem value="PAN" className="flex-1 text-xs" title={stageFan ? 'SR ↔ SL' : undefined}>
                {stageFan ? 'Across' : 'Pan'}
              </ToggleGroupItem>
              <ToggleGroupItem value="TILT" className="flex-1 text-xs" title={stageFan ? 'Downstage ↔ upstage' : undefined}>
                {stageFan ? 'Depth' : 'Tilt'}
              </ToggleGroupItem>
              <ToggleGroupItem value="BOTH" className="flex-1 text-xs">
                Both
              </ToggleGroupItem>
            </ToggleGroup>
          )}
          {fanForm && (
            <ToggleGroup
              type="single"
              size="sm"
              aria-label="Fan in"
              value={form.space}
              onValueChange={(value) => {
                if (value === 'PAN_TILT' || value === 'STAGE') setPositionPadMode(value)
              }}
              className="w-full"
            >
              <ToggleGroupItem value="PAN_TILT" className="flex-1 text-xs" title="Fan each head's pan and tilt by centre steps">
                Pan/Tilt
              </ToggleGroupItem>
              <ToggleGroupItem value="STAGE" className="flex-1 text-xs" title="Fan the beams across the stage by metres, each head aimed by the desk">
                Stage
              </ToggleGroupItem>
            </ToggleGroup>
          )}

          {/* From / To. A colour has one picker for whichever end is being edited; the other three
              shapes are typed side by side. */}
          {editorKind === 'colour' ? (
            <ColourEndpoints
              from={form.from}
              to={form.to}
              editing={editing}
              onEdit={(which) => {
                setEditing(which)
                reseedPicker(which === 'from' ? formRef.current.from : formRef.current.to)
              }}
              onSwap={swap}
              templates={colourTemplates}
              onTemplate={(uuid) => {
                // Back to a colour is back to the default for that end; the knob follows it.
                const endpoint: SpreadEndpoint = uuid == null ? defaultSpreadEndpoints(form.property)[editing] : { kind: 'template', uuid }
                setEndpoint(editing, endpoint)
                reseedPicker(endpoint)
              }}
              policy={form.from.kind === 'colour' ? form.from.policy : 'extract'}
              policyFromTemplate={form.from.kind === 'template'}
              onPolicy={(policy) =>
                commit({
                  from: form.from.kind === 'colour' ? { ...form.from, policy } : form.from,
                  to: form.to.kind === 'colour' ? { ...form.to, policy } : form.to,
                })
              }
            >
              {/* The busk tab's shipped endpoint picker, in every host: compact, the picker and
                  R/G/B alone — a colour intent has no emitter component — no read-out, no footer. */}
              {ColourEndpointEditor != null && (
              <ColourEndpointEditor
                r={editingRgb.r}
                g={editingRgb.g}
                b={editingRgb.b}
                combinedCss={pickerSeed.css}
                seedKey={pickerSeed.key}
                hasWhiteChannel={false}
                hasAmberChannel={false}
                hasUvChannel={false}
                onColourChange={(r, g, b) => {
                  const policy = editingEndpoint.kind === 'colour' ? editingEndpoint.policy : 'extract'
                  setEndpoint(editing, colourEndpointOf({ r, g, b }, policy))
                }}
                channelFields
                compact
                footer={false}
                counts={false}
                open
              />
              )}
            </ColourEndpoints>
          ) : fanForm ? (
            <FanAmounts
              axes={form.axes}
              stage={stageFan}
              first={stageFan ? form.fanX : form.fanPan}
              second={stageFan ? form.fanY : form.fanTilt}
              onChange={(first, second) =>
                commit(stageFan ? { ...(first != null ? { fanX: first } : {}), ...(second != null ? { fanY: second } : {}) } : { ...(first != null ? { fanPan: first } : {}), ...(second != null ? { fanTilt: second } : {}) })
              }
              readout={fanReadoutLine(intentPlan, fanReadout)}
              onReread={resetFan}
            />
          ) : (
            <NumericEndpoints kind={editorKind} from={form.from} to={form.to} onChange={setEndpoint} onSwap={swap} />
          )}
          {curveRow}
          {orderPartsOver}
        </>
      )}

      {activePlan?.kind === 'raw' && (
        <>
          {/* A raw spread's ends are bytes held here until Apply (or sent as they move, under
              Live); the field owns the retype trap and the clamp is this panel's, a byte being a byte. */}
          <div className="flex items-end gap-1.5">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <EditorLabel>From</EditorLabel>
              <EditorField label="From, 0–255" value={rawEnds.from} min={0} max={255} onCommit={(n) => commitRaw({ from: clampByte(n) })} />
            </div>
            <Button type="button" variant="ghost" size="sm" className="h-7 px-1.5" onClick={swap} aria-label="Swap From and To" title="Swap From and To">
              <ArrowLeftRight className="size-3.5" />
            </Button>
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <EditorLabel>To</EditorLabel>
              <EditorField label="To, 0–255" value={rawEnds.to} min={0} max={255} onCommit={(n) => commitRaw({ to: clampByte(n) })} />
            </div>
          </div>
          {curveRow}
          {orderPartsOver}
        </>
      )}

      {activePlan?.kind === 'address' && (
        <>
          <div className="flex items-end gap-2">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <EditorLabel>
                From <span className="font-mono normal-case tracking-normal opacity-70">u{activePlan.universe}</span>
              </EditorLabel>
              <Input
                type="number"
                min={1}
                max={512}
                step={1}
                aria-label="From"
                className="h-7 tabular-nums"
                value={fromAddress}
                onChange={(e) => setFromAddress(e.target.value)}
              />
            </div>
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <EditorLabel>Step</EditorLabel>
              <Input
                type="number"
                min={1}
                max={512}
                step={1}
                aria-label="Step"
                placeholder="footprint"
                className="h-7 tabular-nums"
                value={step}
                onChange={(e) => setStep(e.target.value)}
              />
            </div>
          </div>
          <p className={cn('text-[10px]', addressWalk?.error ? 'text-destructive' : 'text-muted-foreground')}>
            {addressWalk?.error ?? `${describeChannels(activePlan.universe, addressWalk?.channels ?? [])} · a fixed gap, or each head’s own footprint`}
          </p>
        </>
      )}

      {activePlan?.kind === 'duration' && (
        <>
          <div className="flex items-end gap-2">
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <EditorLabel>From</EditorLabel>
              <Input type="text" aria-label="From" className="h-7 tabular-nums" value={fromDuration} onChange={(e) => setFromDuration(e.target.value)} />
            </div>
            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <EditorLabel>To</EditorLabel>
              <Input type="text" aria-label="To" className="h-7 tabular-nums" value={toDuration} onChange={(e) => setToDuration(e.target.value)} />
            </div>
          </div>
          {/* The curve row where the one-option Spread select was: the second shape than linear
              was always going to go here (`FU-SPREAD-DURATION-CURVES`). */}
          {curveRow}
          <p className={cn('text-[10px] tabular-nums', durationWalk?.error ? 'text-destructive' : 'text-muted-foreground')}>
            {durationWalk?.error ??
              (durationWalk?.ms ?? []).map((ms, i) => `${activePlan.names?.[i] ?? i + 1} ${formatDurationMs(ms)}`).join(' · ')}
          </p>
        </>
      )}
    </>
  )

  const footer = (
    <EditorFooter save={save} note={footerNote} className={host === 'docked' ? 'shrink-0 px-3 pb-2' : undefined}>
      {showsLive && (
        <Button
          type="button"
          role="switch"
          aria-checked={live}
          aria-label="Live — apply as I adjust"
          variant={live ? 'default' : 'outline'}
          size="sm"
          disabled={dockedRefusal != null}
          className="h-7 gap-1.5 text-xs"
          title="Send every adjustment to the desk as it is made; off, only Apply writes"
          onClick={() => {
            setLive((on) => !on)
            gestureRef.current = false
            reset()
          }}
        >
          <span aria-hidden className={cn('size-2 rounded-full', live ? 'bg-primary-foreground' : 'bg-muted-foreground/60')} />
          Live
        </Button>
      )}
      <Button
        type="button"
        size="sm"
        variant={live && showsLive ? 'outline' : 'default'}
        className="h-7 text-xs"
        disabled={!canApply || dockedRefusal != null}
        onClick={() => {
          apply()
          if (host === 'popover' && !(live && showsLive)) {
            setIsOpen(false)
            resetFan()
          }
        }}
        title={live && showsLive ? 'Live is on — every adjustment is sent as it is made; press to send the fan again as it stands' : 'Send the fan to the desk'}
      >
        {live && showsLive ? 'Send again' : 'Apply'}
      </Button>
    </EditorFooter>
  )

  if (host === 'docked') {
    return (
      // `@container`: the compact curve row's query (`SPREAD_COMPACT_WORD_CLASS`) measures this panel.
      <div data-spread-sheet={compact ? 'compact' : 'full'} className="@container flex min-h-0 flex-1 flex-col border-l">
        {dockedRefusal != null && (
          <p data-spread-refusal className="shrink-0 border-b px-3.5 py-2 text-[11px] text-muted-foreground">
            {dockedRefusal}
          </p>
        )}
        {/* The tab's one scroller. `px-3.5`, the knob's half-width, so a picker knob at 0% is not
            clipped at the scroller's edge — the Colour tab's reason. */}
        <div
          ref={contentRef}
          onKeyDown={onKeyDown}
          data-spread-sheet-body
          inert={dockedRefusal != null}
          className={cn(
            'flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-3.5 pt-3',
            compact ? 'pb-2' : 'pb-3',
            dockedRefusal != null && 'opacity-50',
          )}
        >
          {body}
        </div>
        {/* The footer, static at the bottom; the Colour tab's has the same shape with its save
            first. **Live sits beside Apply**: they are the two ways a spread reaches the desk. One
            line at the sheet's 320px floor without folding: *Save as Look… · Live · Apply* fits. */}
        <div data-spread-sheet-footer className="shrink-0">
          {footer}
        </div>
      </div>
    )
  }

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={(open) => {
        setIsOpen(open)
        if (!open) resetFan()
      }}
      title="Fan"
      contentClassName="w-[336px]"
      align="end"
      onOpenAutoFocus={onOpenAutoFocus}
      trigger={
        <Button
          variant="outline"
          size="sm"
          className={className}
          disabled={!canSpread && !refusable && onClaimed == null}
          onClick={
            onClaimed != null
              ? (e) => {
                  e.preventDefault()
                  onClaimed()
                }
              : refusable
                ? (e) => {
                    e.preventDefault()
                    onRefused?.()
                  }
                : undefined
          }
          title={onClaimed != null ? 'Fan is open in the rail — press to go to it' : title}
          aria-label="Fan"
        >
          <Waves className="size-3.5" />
          {/* The same fold as Set and Clear beside it — one rule for the three verbs. */}
          <span className={WORD_CLASS}>Fan</span>
        </Button>
      }
    >
      {/* The popover's body scrolls under the viewport's height rather than clipping, and the
          footer stays out of the scroller as the docked host keeps it: the colour arm is ~600px
          tall, and a desk window between the short-viewport fold and ~700px would otherwise put
          Live · Apply below the fold. Radix publishes the room it has; the sum takes off the
          surface's 12px padding and its 1px border at each end, or the box hangs 2px below the
          viewport (measured). */}
      <div
        ref={contentRef}
        onKeyDown={onKeyDown}
        data-spread-panel
        className="flex max-h-[calc(var(--radix-popover-content-available-height)-1.5rem-2px)] flex-col gap-2"
      >
        <div data-spread-panel-body className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto">
          {body}
        </div>
        <div className="shrink-0">{footer}</div>
      </div>
    </EditorSurface>
  )
}

// ─── The endpoint editors ───────────────────────────────────────────────────

/** A colour endpoint's hex, or a template endpoint's swatch — what the picker is seeded from and what a nudge builds on. Null for the numeric shapes. */
function endpointHex(endpoint: SpreadEndpoint, templates: readonly TemplateSummary[]): string | null {
  if (endpoint.kind === 'colour') return endpoint.hex
  if (endpoint.kind === 'template') {
    const template = templates.find((t) => t.uuid === endpoint.uuid)
    const swatch = template == null ? null : templateSwatch(template)
    return swatch != null && /^#[0-9a-fA-F]{6}$/.test(swatch) ? swatch : null
  }
  return null
}

function endpointSwatch(endpoint: SpreadEndpoint, templates: readonly TemplateSummary[]): string | null {
  if (endpoint.kind === 'colour') return endpoint.hex
  if (endpoint.kind === 'template') {
    const template = templates.find((t) => t.uuid === endpoint.uuid)
    return template == null ? null : templateSwatch(template)
  }
  return null
}

function endpointLabel(endpoint: SpreadEndpoint, templates: readonly TemplateSummary[]): string {
  switch (endpoint.kind) {
    case 'colour':
      return endpoint.hex.toUpperCase()
    case 'template':
      return templates.find((t) => t.uuid === endpoint.uuid)?.name ?? 'template'
    case 'percent':
      return `${endpoint.value}%`
    case 'level':
      return String(endpoint.value)
    case 'position':
      return `${formatCentre(endpoint.pan)} / ${formatCentre(endpoint.tilt)}`
  }
}

function ColourEndpoints({
  from,
  to,
  editing,
  onEdit,
  onSwap,
  templates,
  onTemplate,
  policy,
  policyFromTemplate,
  onPolicy,
  children,
}: {
  from: SpreadEndpoint
  to: SpreadEndpoint
  editing: 'from' | 'to'
  onEdit: (which: 'from' | 'to') => void
  onSwap: () => void
  templates: readonly TemplateSummary[]
  onTemplate: (uuid: string | null) => void
  policy: WhitePolicy
  /** From is a `tmpl:` reference: the desk reads the policy off the template's own row, so the control is inert and says so. */
  policyFromTemplate: boolean
  onPolicy: (policy: WhitePolicy) => void
  children: ReactNode
}) {
  const current = editing === 'from' ? from : to
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-1.5">
        <EndpointButton which="from" endpoint={from} active={editing === 'from'} templates={templates} onClick={() => onEdit('from')} />
        <Button type="button" variant="ghost" size="sm" className="h-7 px-1.5" onClick={onSwap} aria-label="Swap From and To" title="Swap From and To">
          <ArrowLeftRight className="size-3.5" />
        </Button>
        <EndpointButton which="to" endpoint={to} active={editing === 'to'} templates={templates} onClick={() => onEdit('to')} />
      </div>
      {children}
      <div className="flex flex-wrap items-center gap-2">
        {/* The library's colours, offered only where the host names a project. */}
        {templates.length > 0 && (
          <label className="flex min-w-0 flex-1 items-center gap-1.5 text-[10px] text-muted-foreground">
            <span className="shrink-0">or a palette</span>
            <select
              aria-label={`${editing === 'from' ? 'From' : 'To'} palette`}
              className="h-6 min-w-0 flex-1 rounded-md border bg-background px-1 text-xs text-foreground"
              value={current.kind === 'template' ? current.uuid : ''}
              onChange={(e) => onTemplate(e.target.value === '' ? null : e.target.value)}
            >
              <option value="">— a colour —</option>
              {templates.map((template) => (
                <option key={template.uuid} value={template.uuid}>
                  {template.name}
                </option>
              ))}
            </select>
          </label>
        )}
        {/* The desk interpolates with *From*'s policy alone (`interpolateIntent` keeps `from.policy`),
            so the control edits From's — and while From is a template the policy is the template
            row's, which nothing here can move. */}
        <ToggleGroup
          type="single"
          size="sm"
          aria-label="White policy"
          value={policyFromTemplate ? '' : policy}
          disabled={policyFromTemplate}
          title={policyFromTemplate ? 'From is a palette: the desk takes the white policy from the palette’s own colour row' : 'How From’s colour uses a head’s white and amber emitters — the desk interpolates with this policy'}
          onValueChange={(next) => next && onPolicy(next as WhitePolicy)}
        >
          {WHITE_POLICIES.map((p) => (
            <ToggleGroupItem key={p} value={p} className="text-[10px]" title={WHITE_POLICY_LABELS[p].hint}>
              {WHITE_POLICY_LABELS[p].label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      </div>
    </div>
  )
}

function EndpointButton({
  which,
  endpoint,
  active,
  templates,
  onClick,
}: {
  which: 'from' | 'to'
  endpoint: SpreadEndpoint
  active: boolean
  templates: readonly TemplateSummary[]
  onClick: () => void
}) {
  const swatch = endpointSwatch(endpoint, templates)
  return (
    <button
      type="button"
      data-spread-endpoint={which}
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'flex h-8 min-w-0 flex-1 items-center gap-1.5 rounded-md border px-2 text-left text-xs',
        active ? 'border-primary bg-primary/10' : 'bg-card hover:bg-accent',
      )}
      title={`Edit ${which === 'from' ? 'From' : 'To'}`}
    >
      <span className="text-[10px] font-semibold uppercase text-muted-foreground">{which === 'from' ? 'From' : 'To'}</span>
      {swatch != null && <span aria-hidden className="size-3.5 shrink-0 rounded-full border border-border" style={{ background: swatch }} />}
      <span className="min-w-0 flex-1 truncate font-mono tabular-nums">{endpointLabel(endpoint, templates)}</span>
    </button>
  )
}

function NumericEndpoints({
  kind,
  from,
  to,
  onChange,
  onSwap,
}: {
  kind: 'percent' | 'position' | 'level'
  from: SpreadEndpoint
  to: SpreadEndpoint
  onChange: (which: 'from' | 'to', endpoint: SpreadEndpoint) => void
  onSwap: () => void
}) {
  return (
    <div className="flex items-end gap-1.5">
      <NumericEndpoint which="from" kind={kind} endpoint={from} onChange={(next) => onChange('from', next)} />
      <Button type="button" variant="ghost" size="sm" className="h-7 px-1.5" onClick={onSwap} aria-label="Swap From and To" title="Swap From and To">
        <ArrowLeftRight className="size-3.5" />
      </Button>
      <NumericEndpoint which="to" kind={kind} endpoint={to} onChange={(next) => onChange('to', next)} />
    </div>
  )
}

function NumericEndpoint({
  which,
  kind,
  endpoint,
  onChange,
}: {
  which: 'from' | 'to'
  kind: 'percent' | 'position' | 'level'
  endpoint: SpreadEndpoint
  onChange: (endpoint: SpreadEndpoint) => void
}) {
  const label = which === 'from' ? 'From' : 'To'
  if (kind === 'position') {
    // Centre-relative (venue-visit plan D3): -128..127 with 0 the centre of travel on every head.
    const pan = endpoint.kind === 'position' ? endpoint.pan : 0
    const tilt = endpoint.kind === 'position' ? endpoint.tilt : 0
    const set = (next: { pan: number; tilt: number }) =>
      onChange({ kind: 'position', pan: clampCentre(next.pan), tilt: clampCentre(next.tilt), unit: 'centre' })
    return (
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <EditorLabel>{label}</EditorLabel>
        <div className="flex items-center gap-1">
          <EditorField label={`${label} pan`} value={pan} min={CENTRE_MIN} max={CENTRE_MAX} onCommit={(next) => set({ pan: next, tilt })} />
          <span className="text-[10px] text-muted-foreground">/</span>
          <EditorField label={`${label} tilt`} value={tilt} min={CENTRE_MIN} max={CENTRE_MAX} onCommit={(next) => set({ pan, tilt: next })} />
        </div>
      </div>
    )
  }
  const max = kind === 'percent' ? 100 : 255
  const value = endpoint.kind === 'percent' || endpoint.kind === 'level' ? endpoint.value : 0
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <EditorLabel>{label}</EditorLabel>
      <div className="flex items-center gap-1">
        {/* The clamp is the endpoint's own — `EditorField` parses and leaves the range to its caller. */}
        <EditorField
          label={kind === 'percent' ? `${label}, percent` : `${label}, 0–255`}
          unit={kind === 'percent' ? '%' : undefined}
          value={value}
          min={0}
          max={max}
          onCommit={(raw) => {
            const next = Math.min(max, Math.max(0, raw))
            onChange(kind === 'percent' ? { kind: 'percent', value: next } : { kind: 'level', value: Math.round(next) })
          }}
        />
      </div>
    </div>
  )
}

function clampByte(raw: number): number {
  return Math.max(0, Math.min(255, Math.round(raw)))
}

/** `1-001, 1-021 … 1-141` — the first three and the last, or all of them when there are few. */
function describeChannels(universe: number, channels: readonly number[]): string {
  if (channels.length === 0) return ''
  const fmt = (c: number) => `${universe}-${String(c).padStart(3, '0')}`
  if (channels.length <= 4) return channels.map(fmt).join(', ')
  return `${channels.slice(0, 3).map(fmt).join(', ')} … ${fmt(channels[channels.length - 1])}`
}

/**
 * A fade time typed by an operator, in milliseconds — the cue sheet's own grammar
 * (`lib/cueUtils.parseFadeDuration`), restated here without the snap arm because a spread's end is
 * a duration and not "no fade". Undefined for text that is not one.
 */
export function parseDurationMs(raw: string): number | undefined {
  const text = raw.trim()
  const match = /^(\d*\.?\d+)\s*(ms|msec|s|sec|secs|m|min)?$/i.exec(text)
  if (!match) return undefined
  const value = Number(match[1])
  if (!Number.isFinite(value) || value < 0) return undefined
  const unit = (match[2] ?? 's').toLowerCase()
  const ms =
    unit === 'ms' || unit === 'msec' ? value : unit === 'm' || unit === 'min' ? value * 60_000 : value * 1000
  return Math.round(ms)
}

function formatDurationMs(ms: number): string {
  if (ms >= 60_000) return `${(ms / 60_000).toFixed(1)}m`
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`
  return `${ms}ms`
}

// ─── The fan's amounts ──────────────────────────────────────────────────────

/** The read-out under a fan's amounts: where the heads started, and any the fan held at the end of travel. */
function fanReadoutLine(plan: IntentSpreadPlan | null, readout: FanReadout | null): string {
  const rows = plan?.fanBases != null
  const parts = [rows ? `From ${plan?.fanRowsLabel ?? 'the rows'}` : 'From where they are']
  if (rows && readout != null && readout.fromStage > 0) {
    parts.push(`${readout.fromStage} ${readout.fromStage === 1 ? 'head' : 'heads'} from the stage`)
  }
  if (readout != null && readout.clamped > 0) {
    parts.push(`${readout.clamped} ${readout.clamped === 1 ? 'head' : 'heads'} at the end of travel`)
  }
  if (readout != null && readout.outOfReach > 0) {
    parts.push(`${readout.outOfReach} ${readout.outOfReach === 1 ? 'head' : 'heads'} out of reach`)
  }
  return parts.join(' · ')
}

/** How a fan amount strip and field read and step: centre steps, or stage metres. */
interface FanScale {
  min: number
  max: number
  /** An arrow key's step, and ⇧'s. */
  step: number
  bigStep: number
  round: (value: number) => number
  format: (value: number) => string
}

const CENTRE_SCALE: FanScale = {
  min: CENTRE_MIN,
  max: CENTRE_MAX,
  step: 1,
  bigStep: 8,
  round: (value) => clampCentre(Math.round(value)),
  format: formatCentre,
}

/** The furthest a stage fan's strip moves the outer heads, metres either way. */
export const STAGE_FAN_MAX_M = 10

const STAGE_SCALE: FanScale = {
  min: -STAGE_FAN_MAX_M,
  max: STAGE_FAN_MAX_M,
  step: 0.1,
  bigStep: 1,
  round: (value) => Math.max(-STAGE_FAN_MAX_M, Math.min(STAGE_FAN_MAX_M, Math.round(value * 10) / 10)),
  format: (value) => `${value > 0 ? '+' : ''}${value.toFixed(1)} m`,
}

function FanAmounts({
  axes,
  stage,
  first,
  second,
  onChange,
  readout,
  onReread,
}: {
  axes: FanAxes
  /** Metres across and up the stage, rather than pan and tilt in centre steps. */
  stage: boolean
  /** Pan, or across. */
  first: number
  /** Tilt, or depth. */
  second: number
  onChange: (first: number | undefined, second: number | undefined) => void
  readout: string
  onReread: () => void
}) {
  const scale = stage ? STAGE_SCALE : CENTRE_SCALE
  return (
    <div className="flex flex-col gap-1.5">
      <EditorLabel>{stage ? 'Amount · the outer heads, metres on stage' : 'Amount · the outer heads, centre steps'}</EditorLabel>
      {axes !== 'TILT' && (
        <FanAmountRow axis={stage ? 'Across' : 'Pan'} scale={scale} value={first} onChange={(value) => onChange(value, undefined)} />
      )}
      {axes !== 'PAN' && (
        <FanAmountRow axis={stage ? 'Depth' : 'Tilt'} scale={scale} value={second} onChange={(value) => onChange(undefined, value)} />
      )}
      <p data-fan-readout className="flex items-center gap-2 text-[10px] text-muted-foreground">
        <span className="min-w-0 flex-1">{readout}</span>
        <button
          type="button"
          className="shrink-0 text-foreground underline underline-offset-2 hover:text-primary focus-visible:outline-2"
          title="Read where the heads are now, and start the fan again from there"
          onClick={onReread}
        >
          Re-read
        </button>
      </p>
    </div>
  )
}

type FanAxisLabel = 'Pan' | 'Tilt' | 'Across' | 'Depth'

function FanAmountRow({ axis, scale, value, onChange }: { axis: FanAxisLabel; scale: FanScale; value: number; onChange: (value: number) => void }) {
  return (
    <div className="flex items-center gap-1.5">
      <span className="w-9 shrink-0 text-[9px] font-bold tracking-[0.08em] text-muted-foreground uppercase">{axis}</span>
      <FanAmountStrip axis={axis} scale={scale} value={value} onChange={onChange} />
      <EditorField
        label={`${axis} amount`}
        value={value}
        min={scale.min}
        max={scale.max}
        step={scale.step}
        onCommit={(n) => onChange(scale.round(n))}
        className="w-14 shrink-0"
      />
    </div>
  )
}

/**
 * The fan's drag strip: 0 in the middle, the scale's whole range across it. A press anywhere starts
 * a drag from the value as it stands — the strip is a relative control, like the fan — with ⇧ for
 * a quarter of the speed; a double click is 0, and the arrow keys step it (⇧ by the big step).
 */
function FanAmountStrip({ axis, scale, value, onChange }: { axis: FanAxisLabel; scale: FanScale; value: number; onChange: (value: number) => void }) {
  const drag = useRef<{ lastX: number; acc: number; width: number } | null>(null)
  const span = scale.max - scale.min
  const at = (v: number) => ((v - scale.min) / span) * 100
  const mid = at(0)
  const knob = at(value)
  return (
    <div
      role="slider"
      tabIndex={0}
      aria-label={`${axis} fan`}
      aria-valuemin={scale.min}
      aria-valuemax={scale.max}
      aria-valuenow={value}
      aria-valuetext={scale.format(value)}
      data-fan-strip={axis.toLowerCase()}
      title="Drag to fan out or in — ⇧ for fine, double-click for no fan"
      className="relative h-7 min-w-0 flex-1 cursor-ew-resize touch-none overflow-hidden rounded-md border bg-input/30 outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture?.(e.pointerId)
        drag.current = { lastX: e.clientX, acc: value, width: e.currentTarget.getBoundingClientRect().width || 1 }
      }}
      onPointerMove={(e) => {
        const held = drag.current
        if (held == null) return
        held.acc += ((e.clientX - held.lastX) * span * (e.shiftKey ? 0.25 : 1)) / held.width
        held.lastX = e.clientX
        held.acc = Math.max(scale.min, Math.min(scale.max, held.acc))
        const next = scale.round(held.acc)
        if (next !== value) onChange(next)
      }}
      onPointerUp={() => {
        drag.current = null
      }}
      onPointerCancel={() => {
        drag.current = null
      }}
      onDoubleClick={() => onChange(0)}
      onKeyDown={(e) => {
        const step = e.shiftKey ? scale.bigStep : scale.step
        const delta = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? step : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? -step : 0
        if (delta === 0) return
        e.preventDefault()
        onChange(scale.round(value + delta))
      }}
    >
      <span aria-hidden className="absolute inset-y-1 w-px bg-muted-foreground/60" style={{ left: `${mid}%` }} />
      <span
        aria-hidden
        className="absolute top-1/2 h-2 -translate-y-1/2 rounded-full bg-primary/80"
        style={{ left: `${Math.min(mid, knob)}%`, width: `${Math.abs(knob - mid)}%` }}
      />
      <span aria-hidden className="absolute top-1 bottom-1 w-1.5 -translate-x-1/2 rounded-sm bg-foreground shadow" style={{ left: `${knob}%` }} />
    </div>
  )
}

// ─── The curve pictures ─────────────────────────────────────────────────────

/**
 * The four shapes drawn as the panel draws them: eight heads, From at the baseline, To at the top.
 *
 * **Wings is two lines, not a V** (2026-09-21). Its fractions are Mirror's over eight heads — To
 * at each outer end, From at the centre — so one polyline through them *is* Mirror's V, and the
 * two pictures were told apart by nothing. What differs is the mechanism (`SpreadPlan.wings`): two
 * fans, each from the centre to its own outer end, an odd run's centre head in both. So Wings is
 * drawn as its two wings — two strokes meeting at a marked centre, each fanning outward — where
 * Mirror stays one stroke reflected about the middle.
 */
function CurvePicture({ curve, fan = false }: { curve: SpreadCurve; fan?: boolean }) {
  const heads = 8
  const x = (i: number): number => 2 + i * 4
  const y = (t: number): number => 13 - t * 11
  // A fan reads each curve against the midline — each head's own position, −A below and +A above.
  const midline = fan ? (
    <line data-curve-midline x1={1} y1={y(0.5)} x2={31} y2={y(0.5)} stroke="currentColor" strokeWidth="0.75" strokeDasharray="1.5 2" opacity={0.6} />
  ) : null
  if (curve === 'WINGS') {
    const half = heads / 2
    const left = Array.from({ length: half }, (_, i) => `${x(i)},${y(1 - i / (half - 1))}`).join(' ')
    const right = Array.from({ length: half }, (_, i) => `${x(half + i)},${y(i / (half - 1))}`).join(' ')
    const centre = (x(half - 1) + x(half)) / 2
    return (
      <svg aria-hidden data-curve-picture="WINGS" viewBox="0 0 32 14" className="h-3.5 w-8">
        {midline}
        <polyline points={left} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
        <polyline points={right} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
        <line x1={centre} y1={y(0) - 1} x2={centre} y2={y(0) + 1} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      </svg>
    )
  }
  const t = (i: number): number => {
    const p = i / (heads - 1)
    switch (curve) {
      case 'LINE':
        return p
      case 'MIRROR':
        return Math.abs(2 * p - 1)
      case 'ARROW':
        return 1 - Math.abs(2 * p - 1)
    }
  }
  const points = Array.from({ length: heads }, (_, i) => `${x(i)},${y(t(i))}`).join(' ')
  return (
    <svg aria-hidden data-curve-picture={curve} viewBox="0 0 32 14" className="h-3.5 w-8">
      {midline}
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  )
}
