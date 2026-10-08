import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
  type KeyboardEventHandler,
  type ReactNode,
  type Ref,
} from 'react'
import { RgbColorPicker, type RgbColor } from 'react-colorful'
import { Pipette, Save, Waves } from 'lucide-react'
import { toast } from 'sonner'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'
import { Button } from '@/components/ui/button'
import { Slider } from '@/components/ui/slider'
import { templateSwatch } from '@/components/busking/padFace'
import { ExtendedChannelSlider } from '@/components/fixtures/ExtendedChannelSlider'
import { FixtureAppearanceSource } from '@/components/fixtures/fixtureAppearance'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import { hexToRgb, rgbToHex } from '@/components/fx/colourUtils'
import { useTemplatePress } from '@/components/programmer/useTemplatePress'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { computeCombinedCss, parseCssRgb } from '@/lib/colourMath'
import { LiveAppearanceReporter, pickSelectionColour, targetHeads, type PickedColour } from '@/lib/liveAppearance'
import { recentTemplates } from '@/lib/templateRecents'
import { cn } from '@/lib/utils'
import type { ColourPropertyDescriptor } from '@/store/fixtures'
import { usePatchListQuery } from '@/store/patches'
import { usePressFamilies } from '@/store/selection'
import { useTemplateListQuery } from '@/store/templates'
import { EditorField } from './EditorField'
import { EditorFooter } from './EditorFooter'
import { EditorLabel } from './EditorLabel'
import { EditorReadout } from './EditorReadout'
import { useEditorForm, type EditorForm } from './EditorSurface'
import { numericSeed } from './useEditorKeyboard'

/** The whole of what this editor writes: three colour bytes and the three bundled emitters. */
export interface ColourChannels {
  r: number
  g: number
  b: number
  w: number
  a: number
  uv: number
}

/**
 * What the editor *believes*, which is [ColourChannels] with the emitters allowed to be **unstated**.
 *
 * The emitter rows are the union over the heads being edited, but the bytes the host hands in are
 * one row's — and a row with no white channel has no white byte (`aggregateCellValue` leaves it
 * `undefined`). An emitter the host does not hold must stay `undefined` in the write, because
 * `useCellWriters.writeColour` samples the wire for an undefined component and writes a defined 0:
 * folding it to 0 here drove every RGBW head in a marquee to white 0 the moment a byte was typed
 * from an RGB row's cell. It becomes stated only when the operator states it — its own field or
 * slider — and a gesture that replaces the colour (the picker) zeroes only what was held.
 */
export interface ColourBuffer {
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
}

/** The buffer as the six numbers a host takes: an unstated emitter reads 0. */
function toChannels(buffer: ColourBuffer): ColourChannels {
  return { r: buffer.r, g: buffer.g, b: buffer.b, w: buffer.w ?? 0, a: buffer.a ?? 0, uv: buffer.uv ?? 0 }
}

/** A replacing gesture's emitter: 0 where the buffer held one, still unstated where it did not. */
function zeroIfHeld(value: number | undefined): number | undefined {
  return value === undefined ? undefined : 0
}

/**
 * Where the **Recent** chips come from, and where a press lands (editor-kit plan D10, D11). A
 * host that draws none passes nothing.
 */
export interface ColourRecentSource {
  projectId: number
  /** Where a chip press lands — the template route's targets, so a fixture and never a cell. */
  targets: readonly TemplateTarget[]
  /**
   * The host's own attribute mask for the press; the desk's wins while this tab follows the desk
   * (`usePressFamilies`). The programmer's colour cell says *Colour*, the busk tab its pair.
   */
  localFamilies: readonly AttributeFamily[] | null
  /**
   * Draw the chips only in these forms. The programmer's cell passes the bottom sheet alone: row
   * C's strip is on screen one row up in the popover and a chip set drawn twice is the double D11
   * refuses, while below 600px that strip is folded away. Absent, the chips are always drawn — the
   * busk tab has no strip on its screen.
   */
  forms?: readonly EditorForm[]
}

export interface ColourEditorProps {
  /** Current RGB channel values */
  r: number
  g: number
  b: number
  /** Current extended channel values (undefined when the head lacks the channel) */
  w?: number
  a?: number
  uv?: number
  /**
   * Combined preview CSS colour (includes W/A/UV effect): what the picker's knob is seeded from on
   * open, and re-seeded from whenever it changes while open.
   *
   * **A host must not feed its own writes straight back in here.** `react-colorful` keeps its
   * state as HSV and reconciles the `color` prop in one effect and reports the state in another;
   * when the prop changes twice before its own state update has landed — two typed bytes in one
   * task, two desk echoes in one batch — the second effect sees the *previous* HSV against the
   * *new* cache and fires `onChange` with the old colour. Fed back into the prop, that is a
   * ping-pong that never settles: the busk view's Colour tab did exactly this at 20 writes a second
   * until it passed a colour that moves only on *Pick* — which is the editor's own now, so that
   * host passes a constant. The popover is fed the desk's echo, which follows one write with one
   * echo.
   */
  combinedCss: string
  /**
   * Re-seed the knob from [combinedCss] when this changes, even to the same colour — the Spread
   * tab's endpoint picker bumps it on Swap and on switching ends, so two seeds of one colour both
   * move the knob back.
   */
  seedKey?: number
  /**
   * Which emitters the editor offers rows for — the **union** over the heads it is editing, read
   * off their colour descriptors. The hosts with targets derive it with [emitterHeadCounts], the
   * same probe the read-out's count line runs, so the rows and the line cannot disagree; the
   * fixture sheet's colour rows read their heads' own descriptors.
   */
  hasWhiteChannel: boolean
  hasAmberChannel: boolean
  hasUvChannel: boolean
  /** Callback when colour is picked */
  onColourChange: (r: number, g: number, b: number, w?: number, a?: number, uv?: number) => void
  /** Draw the typed R/G/B and per-emitter fields. See `ColourPickerPopover`. */
  channelFields?: boolean
  /** Spend less height: a shorter picker and the emitter rows beside it. See `ColourPickerPopover`. */
  compact?: boolean
  /** The character a keyboard-opened editor lands in the R box. See `ColourPickerPopover`. */
  keyboardOpen?: string | null
  /**
   * Whether the editor is showing. The picker re-seeds from [combinedCss] and drops its pending
   * write on every open — a popover passes its open state, a sheet that mounts the body only
   * while showing passes `true`.
   */
  open: boolean
  /** The host's keyboard wiring (`useEditorKeyboard`), when it has one. */
  contentRef?: Ref<HTMLDivElement>
  onKeyDown?: KeyboardEventHandler<HTMLDivElement>
  /**
   * The heads this editor is editing, in the host's order — the busk selection expanded to its
   * write targets in rig order, the programmer's marquee column in row order (D12). Three things
   * read them: the count line, the hidden appearance leaves (one per parent fixture) and *Pick*,
   * which reads the first head's colour and says *mixed* where the rest disagree. Absent or empty
   * for the fixture sheet's colour rows and the Spread endpoint, which have none of the three.
   */
  targets?: readonly WriteTarget[]
  /**
   * Fixture keys in the order Pick reads them — the busk tab's rig order. Absent, the targets'
   * own order stands, which on the programmer is the marquee's visible row order.
   */
  headOrder?: readonly string[]
  /**
   * The project whose patch list the hidden leaves read. Absent, no leaf is mounted and Pick
   * answers that nothing is on screen — the fixture sheet's and a read-only cell's case.
   */
  projectId?: number
  /**
   * Pick once on mount and again whenever [targets] change, retried until a head has reported —
   * the busk tab's seed from the rig, so a single-channel edit there leaves the other bytes where
   * the rig has them. The programmer leaves it off: its editor opens at the cell's value already.
   */
  pickOnTargets?: boolean
  /** The Recent chips. Absent, none are drawn. */
  recent?: ColourRecentSource
  /** Draw the footer — *Save as template… · Pick · Spread…*. Default true; the fixture sheet passes false. */
  footer?: boolean
  /**
   * Draw the read-out line — the emitter counts, *mixed*, the swatch, the hex. Default true; the
   * fixture sheet's colour rows pass false and keep their own swatch line above the editor.
   */
  counts?: boolean
  /**
   * The label line (`EditorLabelLine`), drawn first — the popover's, and the programmer rail's
   * docked tab's (editor-kit plan session 4, call 11). The sheets and the busk tab pass none.
   */
  labelLine?: ReactNode
  /**
   * The scope takes no value — the programmer rail's tab in Output or on a focused template layer.
   * **A whole guarantee, not a part one**: every control that writes is drawn but takes no input —
   * the picker, the fields and the emitter rows, and the Recent chips (a template press) — and
   * *Spread…* is disabled, since it hands a value on to be written. Pick stays live, since it reads
   * and writes nothing, and so does *Save as template…*, which records from the selection rather
   * than writing through the scope.
   */
  readOnly?: boolean
  /**
   * The busk tab's frame: the body, the read-out and Recent in one scroller, the footer static
   * under it. Off, everything stacks in one column, which is the popover's shape.
   */
  docked?: boolean
  /**
   * Told what Pick read — for a host that keeps a buffer of its own beside the editor's (the busk
   * tab's release flush and its Spread hand-over). The editor has already moved its knob, its
   * fields and the *mixed* marker; nothing is written.
   */
  onPick?: (channels: ColourChannels, picked: PickedColour) => void
  /** *Save as template…*. Absent, the button is disabled. */
  onSave?: () => void
  /** *Spread…*, handed the current channels. Absent, the button is drawn inert — session 3 wires the programmer's. */
  onSpread?: (channels: ColourChannels) => void
}

/** The two toasts Pick can raise, said once. */
const PICK_NOTHING_SELECTED = 'Nothing selected to read a colour from'
const PICK_NOTHING_ON_SCREEN = 'No selected head is on screen to read'

/**
 * The docked footer's lesser verbs keep their words only where the footer is wide enough for all
 * three: measured, *Save as template… · Pick · Spread…* is 305px of buttons at the busk sheet's
 * 320px default (295px inside the gutters), so below this the two fold to their icons and the save
 * keeps its word. **The docked host's alone**: the popover's footer is a 328px content box at the
 * popover's 352 — under this rung, though the three worded buttons fit it — and the two sheets are
 * wider still, so there the words are simply drawn (measured in the app on 2026-09-22, where the
 * popover folded under the sheet's rung and the board draws it worded).
 */
const FOOTER_WORDS = 'hidden @[340px]:inline'

function isExactWhite(color: RgbColor): boolean {
  return color.r === 255 && color.g === 255 && color.b === 255
}

/** An rgb() or #rrggbb CSS colour as RGB bytes, black for anything else. */
export function parseCssColour(css: string): RgbColor {
  return parseCssRgb(css) ?? { r: 0, g: 0, b: 0 }
}

/** The clamp a channel byte gets — the caller's, never the field's (editor-kit plan D9). */
function channelByte(value: number): number {
  return Math.max(0, Math.min(255, Math.round(value)))
}

function colourDescriptorOf(properties: readonly { type: string }[]): ColourPropertyDescriptor | null {
  return (properties.find((p) => p.type === 'colour') as ColourPropertyDescriptor | undefined) ?? null
}

export type ColourEmitter = 'white' | 'amber' | 'uv'

/**
 * How many of the targets take each emitter **through their colour descriptor**, and how many
 * take any — the one probe behind both the emitter rows and the *Emitters on 6 of 14 heads*
 * line, so the two cannot disagree. Deliberately narrower than `targetEmitters`' probe, which also
 * counts a plain slider in an emitter category for the template offer: a row for an emitter a
 * colour write cannot reach would be a slider that moves nothing.
 */
export function emitterHeadCounts(targets: readonly WriteTarget[]): Record<ColourEmitter | 'any', number> {
  const counts = { white: 0, amber: 0, uv: 0, any: 0 }
  for (const target of targets) {
    const descriptors = [target.properties, ...(target.elements ?? []).map((e) => e.properties)]
      .map(colourDescriptorOf)
      .filter((d): d is ColourPropertyDescriptor => d != null)
    const white = descriptors.some((d) => d.whiteChannel != null)
    const amber = descriptors.some((d) => d.amberChannel != null)
    const uv = descriptors.some((d) => d.uvChannel != null)
    if (white) counts.white += 1
    if (amber) counts.amber += 1
    if (uv) counts.uv += 1
    if (white || amber || uv) counts.any += 1
  }
  return counts
}

/**
 * The colour editor — one body, one read-out, one footer, in every host (editor-kit plan D10).
 *
 * It was `fixtures/ColourPickerBody`, extracted from the popover so the busk view's Colour tab
 * could host the same picker, fields and six-channel buffer; the tab then grew everything around
 * the body — the read-out line, Pick, Recent, the footer, the hidden appearance leaves — and the
 * popover did not get them back. They are the editor's now, and the hosts say only which pieces
 * they draw: the programmer's colour cell (a 352px popover and both sheets), the busk tab
 * (docked), the Spread tab's colour endpoint (the picker and R/G/B alone — a colour intent has no
 * emitter component) and the fixture sheet's colour rows (docked in the row: the picker and the
 * typed channels, no footer, no read-out).
 *
 * **Pick reads the heads this editor is editing** (D12), through one hidden
 * `FixtureAppearanceSource` leaf per parent fixture mounted here, reporting into
 * `lib/liveAppearance.ts` — the grid's rows do not report into that store and are not made to. It
 * reads the first head in [headOrder] (the targets' own order otherwise), says *mixed* where the
 * rest disagree, moves the knob and the fields to what it read, and **writes nothing**: the picked
 * colour becomes the buffer a single-channel edit then builds on. Only heads with a colour
 * descriptor are read; a dimmer-only par is neither a colour nor a reason to say *mixed*.
 *
 * **The guidance is the picker's title** in every host — *Pure white in the picker drives the
 * white LED; the boxes set one channel each* — as the compact layout already made it; the paragraph
 * under the picker is gone. The keyboard is unchanged: R focused on open in the popover, comma
 * R → G → B → W → A → UV, Enter closes, a typed character seeds R (`useEditorKeyboard`, wired by
 * the host).
 *
 * **The picker is fluid in every host**: `.colour-picker-fluid` in `index.css` lets the square take
 * what the R/G/B column leaves, and the 200px pin that used to size every `react-colorful` on the
 * desk is gone with the last fixed host. The compact heights stay beside it as
 * `.colour-picker-compact`, because a Tailwind utility cannot size this picker (`index.css` says
 * why).
 *
 * **This kit module reaches two feature trees and three stores** — `busking/padFace` for a chip's
 * swatch, `programmer/useTemplatePress` for a chip's press, and the templates, selection and patch
 * stores — because Recent and the leaves are the editor's own (D10, D12) rather than a host's.
 * Through `ColourPickerPopover` that graph now sits under the fixture and group detail sheets too;
 * `import/no-cycle` holds, and those two hosts pass neither `recent` nor `projectId`, so neither
 * store-bound child mounts there. Said here so the widening is a decision and not a surprise.
 */
export function ColourEditor({
  r,
  g,
  b,
  w,
  a,
  uv,
  combinedCss,
  seedKey = 0,
  hasWhiteChannel,
  hasAmberChannel,
  hasUvChannel,
  onColourChange,
  channelFields = false,
  compact = false,
  keyboardOpen = null,
  open,
  contentRef,
  onKeyDown,
  targets = EMPTY_TARGETS,
  headOrder,
  projectId,
  pickOnTargets = false,
  recent,
  footer = true,
  counts = true,
  labelLine,
  readOnly = false,
  docked = false,
  onPick,
  onSave,
  onSpread,
}: ColourEditorProps) {
  // Track the picker's internal colour state (initialized from combined preview)
  const [pickerColor, setPickerColor] = useState<RgbColor>(() => parseCssColour(combinedCss))

  /**
   * The six channels this editor has most recently *asked* for — or, after a Pick, read — or null
   * once the desk has answered.
   *
   * A commit here sets the **whole** colour — `onColourChange` takes all six — so every handler has
   * to fill the channels it is not changing from somewhere. Reading them straight off the props is
   * what made two quick edits lose one: the props are the desk's echo, which lags the write by a
   * commit throttle (~33 ms), so typing R and then G inside that window sent G beside R's *old*
   * value and silently reverted it. Buffering what we sent closes it, and the effect below is what
   * stops the buffer going stale: any movement in the props — our echo, a clamp, another client, an
   * effect driving the head — means the desk has spoken more recently than we have, so the buffer
   * is spent. The open effect drops it too, which bounds a write the desk refused outright (props
   * never move, so the effect never fires) to a single editing session.
   *
   * Pick fills it the same way, without sending: the picked colour is what the fields show and
   * what the next single-channel edit builds on, until the desk moves the props.
   */
  const pendingRef = useRef<ColourBuffer | null>(null)
  useEffect(() => {
    pendingRef.current = null
  }, [r, g, b, w, a, uv])
  // The props as a ref, so a handler memoised on nothing (`applyPick`) still reads this render's.
  const propsRef = useRef<ColourBuffer>({ r, g, b, w, a, uv })
  propsRef.current = { r, g, b, w, a, uv }

  // Reset picker colour to combined preview when the editor opens, and whenever the preview or
  // the seed moves while it is open. See [combinedCss] for why a host must not route its own
  // writes through here. Declared before the Pick-on-targets effect below, so on a mount that
  // does both the seed from the rig is what the knob ends up on.
  useEffect(() => {
    if (open) {
      setPickerColor(parseCssColour(combinedCss))
      pendingRef.current = null
    }
  }, [open, combinedCss, seedKey])

  /**
   * What the editor believes the six channels are: our own outstanding write (or Pick) if there is
   * one, the props otherwise.
   *
   * **A function, called at commit time, never a value computed during render.** Two edits can
   * land in one task with no re-render between them — two `input` events dispatched together, or
   * a keystroke arriving while React is still batching — and a handler closing over a render-time
   * copy would read the props for the second one and revert the first, which is the whole defect
   * the buffer exists to close. Found in a browser after a version that read the render-time copy
   * passed its own unit test, because `fireEvent` flushes a render between the two changes and the
   * browser does not.
   */
  const currentChannels = (): ColourBuffer => pendingRef.current ?? propsRef.current
  // The render-time reading, for what the fields, the sliders and the read-out display — an
  // emitter the host does not hold shows 0 in its row, and stays unstated in the write until the
  // operator states it (see `ColourBuffer`).
  const channels = toChannels(currentChannels())

  /** What Pick last read, for the *mixed* marker; cleared by the next write. */
  const [picked, setPicked] = useState<PickedColour | null>(null)

  /**
   * The one write. Every handler builds a whole six-channel value and hands it here, so the
   * "an emitter the head hasn't got is `undefined`, not 0" mapping is stated once instead of once
   * per handler — it is the same three lines that used to sit in four places.
   */
  const send = useCallback(
    (next: ColourBuffer) => {
      pendingRef.current = next
      setPicked(null)
      onColourChange(
        next.r,
        next.g,
        next.b,
        hasWhiteChannel ? next.w : undefined,
        hasAmberChannel ? next.a : undefined,
        hasUvChannel ? next.uv : undefined,
      )
    },
    [onColourChange, hasWhiteChannel, hasAmberChannel, hasUvChannel],
  )

  /**
   * The picker is a *gesture* — "make it this colour" — so it replaces the whole output: the
   * emitters the editor holds go to 0, and pure white becomes the white LED on a head that has
   * one. An emitter the host never held stays unstated, so the heads that have it keep it.
   */
  const handleColourChange = useCallback(
    (color: RgbColor) => {
      setPickerColor(color)
      const held = pendingRef.current ?? propsRef.current
      send(
        isExactWhite(color) && hasWhiteChannel
          ? { r: 0, g: 0, b: 0, w: 255, a: zeroIfHeld(held.a), uv: zeroIfHeld(held.uv) }
          : { r: color.r, g: color.g, b: color.b, w: zeroIfHeld(held.w), a: zeroIfHeld(held.a), uv: zeroIfHeld(held.uv) },
      )
    },
    [send, hasWhiteChannel],
  )

  /**
   * A typed or dragged byte is a *statement about one channel*, and leaves the other five where
   * they are — which is the only way "R 200 over the amber I already set" can be said at all, and
   * why the emitters have controls of their own rather than being folded into the picker's
   * substitution. So typing 255 into all three does **not** flip to the white LED: that would show
   * the operator 0 in the box they had just typed 255 into.
   */
  // Not a `useCallback`, deliberately: it reads the props through `currentChannels`, so it would
  // rebuild on every prop change anyway — and no consumer is memoised, so a stable identity buys
  // nothing. Its callers are inline arrows.
  const setChannel = (channel: keyof ColourChannels, value: number) => {
    // **The knob is deliberately not moved to follow the number.** `RgbColorPicker` keeps its state
    // as HSV and converts back on the way out, and that round trip is lossy for most colours — so
    // pushing a typed RGB in as its `color` makes it fire `onChange` straight back with a value a
    // point or two off (200,20,30 comes back 199,…). That echo is indistinguishable from a drag, so
    // it lands in `handleColourChange`, which is the whole-output gesture: it would zero every
    // emitter the operator had set, on some colours and not others. The knob staying put until the
    // editor is reopened is the cheaper wrong thing; the cell's own swatch tracks the value.
    send({ ...currentChannels(), [channel]: channelByte(value) })
  }

  // ─── The heads, the leaves and Pick ─────────────────────────────────────

  const heads = useMemo(() => targetHeads(targets, headOrder), [targets, headOrder])
  const leafKeys = useMemo(() => [...new Set(heads.map((head) => head.fixtureKey))], [heads])
  // Bumped by the leaves when their patch list lands, so a seed that found nothing on the first
  // render is retried once the heads exist to report.
  const [leavesVersion, bumpLeaves] = useReducer((v: number) => v + 1, 0)

  // Through refs so the Pick-on-targets effect depends on the targets alone: a host's `onPick` is
  // an inline arrow, and depending on it would re-seed on every host render.
  const onPickRef = useRef(onPick)
  onPickRef.current = onPick

  const applyPick = useCallback((result: PickedColour) => {
    const rgb = hexToRgb(result.hex)
    // The emitters the editor holds start at 0 whatever the rig holds: the appearance store exposes
    // one folded colour and nothing per emitter. An unheld one stays unstated (`ColourBuffer`).
    const held = pendingRef.current ?? propsRef.current
    const next: ColourBuffer = { r: rgb.r, g: rgb.g, b: rgb.b, w: zeroIfHeld(held.w), a: zeroIfHeld(held.a), uv: zeroIfHeld(held.uv) }
    pendingRef.current = next
    setPickerColor({ r: rgb.r, g: rgb.g, b: rgb.b })
    setPicked(result)
    onPickRef.current?.(toChannels(next), result)
  }, [])

  const pick = useCallback(() => {
    const result = pickSelectionColour(heads)
    if (result == null) {
      toast.info(targets.length === 0 ? PICK_NOTHING_SELECTED : PICK_NOTHING_ON_SCREEN)
      return
    }
    applyPick(result)
  }, [heads, targets.length, applyPick])

  /**
   * The seed from the rig (the busk tab's): a fresh **set** of heads is picked once, and a seed that
   * found nothing — the heads may not have reported yet on the first render — is retried as the
   * heads or the leaves change, while a seed that found something is not repeated for those heads:
   * a later refetch must not snap the fields back under a colour the operator has since set. The
   * key is the set and not the ordered list, because `heads` is sorted by [headOrder] and the rig
   * order can move on its own — the rig query landing after first paint, a row re-laid in Edit
   * layout — which is a change to nothing the seed is about.
   */
  const headsKey = useMemo(
    () =>
      heads
        .map((head) => `${head.fixtureKey}#${head.cellIndex ?? ''}`)
        .sort()
        .join('|'),
    [heads],
  )
  const seededRef = useRef<{ key: string; done: boolean }>({ key: '', done: false })
  useEffect(() => {
    if (!pickOnTargets) return
    if (seededRef.current.key !== headsKey) seededRef.current = { key: headsKey, done: false }
    if (seededRef.current.done) return
    const result = pickSelectionColour(heads)
    if (result == null) return
    seededRef.current.done = true
    applyPick(result)
  }, [pickOnTargets, headsKey, heads, leavesVersion, applyPick])

  // ─── What is drawn ──────────────────────────────────────────────────────

  const hasExtendedChannels = hasWhiteChannel || hasAmberChannel || hasUvChannel
  // Which emitters this selection has, and what each is at — one lookup, so the row list, the value
  // and the write can't disagree by a transposed ternary. Presence comes from the caller's **colour
  // descriptors** (`whiteChannel` / `amberChannel` / `uvChannel`): the bundled emitters are left out
  // of the flat descriptor list, so there is no category to scan for them.
  const emitters: Record<EmitterKey, { has: boolean; value: number }> = {
    w: { has: hasWhiteChannel, value: channels.w },
    a: { has: hasAmberChannel, value: channels.a },
    uv: { has: hasUvChannel, value: channels.uv },
  }
  const available = useMemo<readonly ColourEmitter[]>(
    () => [...(hasWhiteChannel ? ['white' as const] : []), ...(hasAmberChannel ? ['amber' as const] : []), ...(hasUvChannel ? ['uv' as const] : [])],
    [hasWhiteChannel, hasAmberChannel, hasUvChannel],
  )
  const emitterCounts = useMemo(() => emitterHeadCounts(targets), [targets])
  const headCount = targets.length
  const swatchCss = computeCombinedCss(channels.r, channels.g, channels.b, channels.w, channels.a, channels.uv)
  const hex = rgbToHex(channels.r, channels.g, channels.b)

  // Compact turns the column into a **wrapping row**, so the emitter sliders sit beside the
  // picker instead of under it. That is the change that makes it fit: stacked, the editor is
  // ~260px in the ~285px a landscape iPhone has left after Safari, and every trim that got it
  // under was taking something away. Side by side it is the height of the picker alone. The
  // wrap is what makes it one layout rather than two — a narrow sheet stacks it again, which is
  // the portrait arrangement unchanged.
  const body = (
    <div data-colour-editor-body={compact ? 'compact' : 'full'} className={compact ? 'flex flex-wrap items-start gap-4' : 'space-y-3'}>
      {/* The picker leads and the numbers sit beside it: nobody thinks in bytes when they are
          choosing a colour, and nobody wants a picker when they already know the number. The
          numbers are opt-in — see `channelFields`. */}
      <div
        // Fluid *and* compact: the compact wrapper above is a wrapping flex row, so this row is a
        // content-sized flex item there, and a picker told to take the row's width had a row the
        // width of the R/G/B boxes to take — 0px of square. `flex-1` with a 16rem basis gives the
        // row the wrapper's width to fill (the emitter column wraps under it when there is not
        // room for both), and is inert in the non-compact block layout.
        //
        // The compact **heights** are `index.css`'s `.colour-picker-compact`, not utilities on this
        // row: Tailwind's utilities are layered and react-colorful's stylesheet is not, so a
        // layered `h-44` loses to the library's 200px however specific it is, and an arbitrary
        // variant reads the `__` in `__hue` as a space. Measured, not reasoned — see the note in
        // `index.css`.
        className={cn('colour-picker-fluid flex min-w-0 flex-1 basis-[16rem] items-start gap-3', compact && 'colour-picker-compact')}
        title={
          channelFields && hasWhiteChannel
            ? 'Pure white in the picker drives the white LED; the boxes set one channel each.'
            : undefined
        }
      >
        <RgbColorPicker color={pickerColor} onChange={handleColourChange} />
        {channelFields && (
          <div className={cn('w-20 shrink-0', compact ? 'space-y-2.5' : 'space-y-1.5')}>
            <ChannelField label="R" value={channels.r} onChange={(v) => setChannel('r', v)} seed={numericSeed(keyboardOpen)} />
            <ChannelField label="G" value={channels.g} onChange={(v) => setChannel('g', v)} />
            <ChannelField label="B" value={channels.b} onChange={(v) => setChannel('b', v)} />
          </div>
        )}
      </div>
      {!channelFields && (
        // The readout row of a host that types no channels here — the property visualisers', until
        // fixture-fx-sheets session 4: a host with its own channel bank outside would otherwise have
        // a second live editor for the same byte — see `channelFields`.
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span className="font-mono">
            R:{r} G:{g} B:{b}
          </span>
          {hasWhiteChannel && <span className="text-muted-foreground/60">White = use white LED</span>}
        </div>
      )}
      {hasExtendedChannels && (
        <div
          className={cn(
            compact
              ? // `min-w` is the wrap threshold: below it the emitters drop under the picker rather
                // than being squeezed to a slider nothing could drag. No rule, either — a border
                // above a column that is *beside* its neighbour separates nothing.
                'min-w-[13rem] flex-1 space-y-3'
              : 'space-y-2 border-t border-border pt-2',
          )}
        >
          {EMITTERS.filter(({ key }) => emitters[key].has).map(({ key, label, tint }) =>
            channelFields ? (
              <EmitterRow key={key} label={label} tint={tint} value={emitters[key].value} onChange={(v) => setChannel(key, v)} />
            ) : (
              <ExtendedChannelSlider key={key} label={label} value={emitters[key].value} onChange={(v) => setChannel(key, v)} color={tint} />
            ),
          )}
        </div>
      )}
    </div>
  )

  // Read-only: drawn, dimmed, and out of reach of pointer and keyboard alike — `inert` rather than
  // per-control `disabled`, because the picker and the emitter sliders have no `disabled` of their
  // own to set, and a scope that takes no value must not be written by any of them.
  const controls = readOnly ? (
    <div inert data-colour-editor-readonly className="opacity-50">
      {body}
    </div>
  ) : (
    body
  )

  // The read-out: what the picker holds, and where the emitters land. The count sentence is drawn
  // only where the union has an emitter and there are heads to count.
  const readout = counts ? (
    <EditorReadout className={docked ? 'mt-2' : undefined}>
      {hasExtendedChannels && headCount > 0 && (
        <span data-colour-emitters>
          Emitters on {emitterCounts.any} of {headCount} {headCount === 1 ? 'head' : 'heads'}
          {emitterCounts.any < headCount ? ' · the rest take RGB only' : ''}
        </span>
      )}
      <span className="ml-auto inline-flex items-center gap-1.5">
        {picked?.mixed && (
          <span data-colour-picked="mixed" className="text-amber-500" title="The selected heads disagree; the first in rig order is shown">
            mixed
          </span>
        )}
        <span aria-hidden className="size-3.5 shrink-0 rounded-full border border-border" style={{ background: swatchCss }} />
        <span data-colour-editor-hex className="font-mono text-[11px] tabular-nums">{hex}</span>
      </span>
    </EditorReadout>
  ) : null

  // Recent is *mounted* only where its chips are drawn (D11): the form question is asked here, so
  // the popover form on the programmer mounts no template-list or selection subscription for chips
  // it would not show. One shared-media subscription per open editor.
  const form = useEditorForm()
  const recentShown = recent != null && (recent.forms == null || recent.forms.includes(form))
  const recentChips = recentShown ? (
    <RecentTemplates projectId={recent.projectId} targets={recent.targets} localFamilies={recent.localFamilies} available={available} className={docked ? 'px-3 pb-2' : undefined} />
  ) : null
  // A chip is a template press — a write — so read-only takes it out of reach with the controls.
  const recentSection =
    recentChips != null && readOnly ? (
      <div inert className="opacity-50">
        {recentChips}
      </div>
    ) : (
      recentChips
    )

  // The footer: the save first, then Pick and Spread…, the busk Colour tab's shape. **One line,
  // never a wrap**: docked, it is its own container and the two lesser verbs fold to their icons
  // below `FOOTER_WORDS`.
  const wordClass = docked ? FOOTER_WORDS : undefined
  const footerRow = footer ? (
    <EditorFooter
      className={cn(docked && '@container shrink-0 px-3 py-2')}
      save={
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="h-7 min-w-0 text-xs"
          disabled={onSave == null || headCount === 0}
          title="Record the selection’s colour as a template — the route to something a layer can track"
          onClick={onSave}
        >
          <Save className="size-3.5" /> <span className="truncate">Save as template…</span>
        </Button>
      }
    >
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="h-7 text-xs"
        aria-label="Pick"
        title="Read the selection’s current colour into the picker without writing"
        onClick={pick}
      >
        <Pipette className="size-3.5" /> <span className={wordClass}>Pick</span>
      </Button>
      {/* A plain button, not a switch: it opens the Spread panel and holds no state of its own, so a
          `role="switch"` that never read checked promised a toggle it could not be. */}
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="h-7 text-xs"
        disabled={onSpread == null || readOnly}
        aria-label="Spread to a second colour…"
        title={onSpread == null ? 'No Spread panel to open from here' : readOnly ? 'This scope takes no value to spread' : 'Open Spread with this colour as From'}
        onClick={() => onSpread?.(toChannels(currentChannels()))}
      >
        <Waves className="size-3.5" /> <span className={wordClass}>Spread…</span>
      </Button>
    </EditorFooter>
  ) : null

  // Hidden leaves, one per parent fixture of the heads, so Pick can read a selection whose tiles or
  // rows are not reporting — the busk tab in Pads focus, the programmer's grid always. They draw
  // nothing; the store is the output.
  const leaves = projectId != null && leafKeys.length > 0 ? <AppearanceLeaves projectId={projectId} keys={leafKeys} onChange={bumpLeaves} /> : null

  if (docked) {
    return (
      <div ref={contentRef} onKeyDown={onKeyDown} data-colour-editor={compact ? 'compact' : 'full'} className="flex min-h-0 flex-1 flex-col">
        {leaves}
        {/* The tab's one scroller: the picker, its read-out, Recent. The picker row is padded to the
            knob's half-width on **every** side a scroller clips — the knob is react-colorful's own
            28px (its injected stylesheet lands after `index.css`, so the 20px rule there loses),
            and at 0% it hangs 14px past the square. The top was `pt-3`, 2px short, so a knob at
            the top of the square was cut by the tab strip above; `pt-4` is the half-width plus
            the 2px its shadow reaches upward. The label line, where a host draws one, is spaced
            by the same measure, or the knob would sit over its words. */}
        <div data-colour-editor-scroller className="min-h-0 flex-1 overflow-y-auto">
          <div className={cn('px-3.5 pt-4', compact ? 'pb-2' : 'pb-3')}>
            {labelLine != null && <div className="mb-4">{labelLine}</div>}
            {controls}
            {readout}
          </div>
          {recentSection}
        </div>
        {footerRow}
      </div>
    )
  }

  return (
    <div ref={contentRef} onKeyDown={onKeyDown} data-colour-editor={compact ? 'compact' : 'full'} className="space-y-3">
      {leaves}
      {labelLine}
      {controls}
      {readout}
      {recentSection}
      {footerRow}
    </div>
  )
}

const EMPTY_TARGETS: readonly WriteTarget[] = []

type EmitterKey = 'w' | 'a' | 'uv'

const EMITTERS = [
  { key: 'w', label: 'W', tint: '#fffbe6' },
  { key: 'a', label: 'A', tint: '#ffbf00' },
  { key: 'uv', label: 'UV', tint: '#7f00ff' },
] as const satisfies readonly { key: EmitterKey; label: string; tint: string }[]

/**
 * One typed channel byte — `R`, `G`, `B` — the kit's field with the byte's clamp on the way out.
 * The field parses; this clamps, because a channel byte is 0–255 whatever the head.
 */
function ChannelField({
  label,
  value,
  onChange,
  seed,
}: {
  label: string
  value: number
  onChange: (next: number) => void
  seed?: string | null
}) {
  return (
    <EditorField label={label} prefix={label} value={Math.round(value)} onCommit={onChange} min={0} max={255} seed={seed} fieldClassName="px-1.5" />
  )
}

/** One emitter: tinted dot, slider, and the byte as a field rather than a readout. */
function EmitterRow({
  label,
  tint,
  value,
  onChange,
}: {
  label: string
  tint: string
  value: number
  onChange: (next: number) => void
}) {
  return (
    <div className="flex items-center gap-2">
      <span className="flex w-9 shrink-0 items-center gap-1 text-[11px]">
        <span className="inline-block size-2 shrink-0 rounded-full border border-border" style={{ backgroundColor: tint }} aria-hidden />
        {label}
      </span>
      <Slider
        value={[value]}
        min={0}
        max={255}
        step={1}
        aria-label={label}
        onValueChange={([v]) => onChange(v)}
        className="min-w-0 flex-1"
      />
      {/* The row already names the channel with its dot and letter, so the field's name is for a
          screen reader only — a visible prefix put a second "W" a few pixels from the first. */}
      <EditorField
        label={`${label} value`}
        value={Math.round(value)}
        onCommit={onChange}
        min={0}
        max={255}
        className="w-14 shrink-0"
        fieldClassName="px-1.5"
      />
    </div>
  )
}

/**
 * The hidden appearance leaves: one `FixtureAppearanceSource` per parent fixture, each reporting
 * into `lib/liveAppearance.ts` for as long as it is mounted. Its own component so the editor
 * itself subscribes to no store — a cell mounted with no project, and every test that renders one,
 * pays nothing for the leaves it does not mount. **Memoised**, and the patch lookup is a map built
 * once per list: the editor re-renders on every desk echo, and a marquee over a few hundred heads
 * must not cost heads × patches string compares per frame for a store only Pick reads.
 */
const AppearanceLeaves = memo(function AppearanceLeaves({ projectId, keys, onChange }: { projectId: number; keys: readonly string[]; onChange: () => void }) {
  const { data: patches } = usePatchListQuery(projectId)
  const { fixtureByKey, typeByKey } = useFixtureLookup()
  const patchByKey = useMemo(() => new Map((patches ?? []).map((patch) => [patch.key, patch])), [patches])
  // The patch list may still be arriving on the first render; once it has, the editor's seed from
  // the rig gets one more try. Keyed on its *arrival* and not on the list, so a refetch — or a
  // reader handing back a fresh array per render — cannot bump the editor into a render loop.
  const ready = patches != null
  useEffect(() => {
    if (ready) onChange()
  }, [ready, onChange])
  return (
    <div hidden data-colour-editor-leaves>
      {keys.map((key) => {
        const patch = patchByKey.get(key)
        if (patch == null) return null
        const fixture = fixtureByKey.get(key)
        return (
          <FixtureAppearanceSource key={key} patch={patch} fixture={fixture} fixtureType={fixture == null ? undefined : typeByKey.get(fixture.typeKey)}>
            {(appearance) => <LiveAppearanceReporter fixtureKey={key} appearance={appearance} />}
          </FixtureAppearanceSource>
        )
      })}
    </div>
  )
})

/**
 * **Recent** — the template recents row (`lib/templateRecents.ts`) over the colour-family, generic,
 * value templates the selection can take: a tap is a template **apply** through
 * `useTemplatePress`, so it stamps `lastPressedAt` and lands as literals like the strip's chip,
 * under the mask like any press. No new list, no new order. Its own component so its store hooks
 * run only where the chips are drawn — the editor mounts it only in a form the host allows, and
 * never per cell.
 */
function RecentTemplates({
  projectId,
  targets,
  localFamilies,
  available,
  className,
}: Omit<ColourRecentSource, 'forms'> & { available: readonly ColourEmitter[]; className?: string }) {
  const { data: templates } = useTemplateListQuery({ projectId })
  const families = usePressFamilies(localFamilies)
  const press = useTemplatePress(projectId, targets, families)
  const shown = useMemo(() => {
    const offerable = (templates ?? []).filter(
      (t) =>
        t.family === 'COLOUR' &&
        t.kind === 'value' &&
        t.isGeneric &&
        (t.requiredEmitters ?? []).every((emitter) => (available as readonly string[]).includes(emitter)),
    )
    return recentTemplates(offerable)
  }, [templates, available])

  return (
    <div data-colour-editor-recent className={cn('border-t pt-2', className)}>
      <EditorLabel>Recent from templates</EditorLabel>
      {shown.length === 0 ? (
        <p className="mt-1 text-[10px] text-muted-foreground">Colour templates you press show up here</p>
      ) : (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {shown.map((template) => (
            <RecentChip key={template.id} template={template} onPress={() => press(template, false)} />
          ))}
        </div>
      )}
    </div>
  )
}

function RecentChip({ template, onPress }: { template: TemplateSummary; onPress: () => void }) {
  const swatch = templateSwatch(template)
  return (
    <button
      type="button"
      data-recent-template={template.id}
      onClick={onPress}
      title={`Set “${template.name}” on the selection`}
      className="inline-flex h-7 items-center gap-1.5 rounded-md border bg-card px-2 text-xs hover:bg-accent"
    >
      {swatch != null && <span aria-hidden className="size-3 rounded-full border border-border" style={{ background: swatch }} />}
      <span className="max-w-[9rem] truncate">{template.name}</span>
    </button>
  )
}
