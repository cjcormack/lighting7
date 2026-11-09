import { InternalApiConnection } from "./internalApi"
import type { BuskFocus } from "../lib/buskWindow"
import { Subscription } from "./subscription"
import { createWsSubscribable } from "./wsSubscriptionFactory"
import { sendGesture } from "./wsGesture"

// Types mirror the desk's `midi/` binding grammar, its served map and its function catalogue.
// Keep field names in sync with kotlinx.serialization.

/**
 * Which axis of a **colour** property a continuous control drives — HSV, hue by default. Mirrors
 * `midi/ColourAxis.kt`. A binding carries no `colourAxis` for hue (the field is omitted on the wire, on both sides), and `| null`
 * only because a hand-built payload may spell the absence out.
 */
export type ColourAxis = "hue" | "hueFine" | "saturation" | "brightness"

/**
 * What a device's strip encoders drive: a property and, for a colour, which axis (absent is hue).
 * Mirrors `midi/EncoderBankSelection`; carried by `surfaceEncoderBank.state`.
 */
export interface EncoderBankSelection {
  attribute: string
  colourAxis?: ColourAxis | null
}

/**
 * The axis a fan encoder fans: pan or tilt, or the beams' landing points across the stage
 * (`ACROSS`, SR↔SL) or up it (`DEPTH`).
 */
export type FanAxis = "PAN" | "TILT" | "ACROSS" | "DEPTH"

// ─── The binding grammar  ──────────────────────────────────
// Mirrors `midi/Binding.kt` and `midi/SurfaceFunctions.kt`: a binding is a function (`does`), the
// subject it acts on and how a button drives it, at a control part.

/** A control's part: a fader's move, an encoder's turn or a button's press, or an encoder's push. */
export type ControlPart = "MAIN" | "PUSH"

export type How = "TOGGLE" | "WHILE_HELD" | "ON" | "OFF" | "TOGGLE_HOLD_REPLACES" | "REPLACE"

export type RecordKind = "STACK" | "ENTRY" | "CUE" | "LOOK" | "PALETTE" | "PAD" | "BANK" | "PAGE" | "MASTER"

export type SurfaceSubject =
  | { type: "none" }
  | { type: "selection" }
  | { type: "slot" }
  | { type: "group"; key: string }
  | { type: "fixture"; key: string }
  | { type: "record"; kind: RecordKind; uuid: string }

export type UndoKind = "UNDO" | "REDO" | "PROGRAMMER"

export type SelectionMove =
  | "CLEAR" | "NEXT" | "PREV" | "ALL" | "ODD" | "EVEN" | "FIRST_HALF" | "SECOND_HALF" | "INVERT" | "MASTERS"

/** A function and its own parameters; `type` is its id. */
export type SurfaceDoes =
  | { type: "level"; attribute: string; axis?: ColourAxis | null }
  | { type: "pageLevel" }
  | { type: "flash"; attribute: string; percent?: number }
  | { type: "select" }
  | { type: "locate" }
  | { type: "fan"; axis: FanAxis }
  | { type: "fxSpeed" }
  | { type: "clear"; fade?: number }
  | { type: "blind"; fade?: number }
  | { type: "release"; fade?: number }
  | { type: "record" }
  | { type: "update" }
  | { type: "palette"; palette: string }
  | { type: "look" }
  | { type: "cue" }
  | { type: "pad" }
  | { type: "pickUp" }
  | { type: "place" }
  | { type: "drop" }
  | { type: "go" }
  | { type: "back" }
  | { type: "pause" }
  | { type: "stop" }
  | { type: "goTo" }
  | { type: "standby" }
  | { type: "fxAmount" }
  | { type: "fire"; trigger: string }
  | { type: "blackout" }
  | { type: "grandMaster"; level?: number | null }
  | { type: "tempo"; minBpm?: number; maxBpm?: number; bpm?: number | null }
  | { type: "tap" }
  | { type: "tempoScale"; factor: number }
  | { type: "layer"; layer: string }
  | { type: "encoderPage"; attribute: string; axis?: ColourAxis | null }
  | { type: "undo"; kind?: UndoKind }
  | { type: "buskPage"; step?: number | null }
  | { type: "windowFocus"; window: string; focus: BuskFocus }
  | { type: "windowSheet"; window: string }
  | { type: "selection"; step: SelectionMove }
  | { type: "strip" }
  | { type: "unknown"; storedType: string; rawPayload: string }

export interface SurfaceBinding {
  does: SurfaceDoes
  /** Absent is `none`. */
  on?: SurfaceSubject
  /** Absent is the function's own default. */
  how?: How | null
}


export type TakeoverPolicy = "IMMEDIATE" | "PICKUP"

/**
 * Dead-reference diagnostics. `ok` is the happy path; every other variant means the
 * binding's target no longer resolves against the current project (fixture renamed,
 * stack / cue deleted, bank removed from the device profile, etc.) and the router
 * drops inbound events rather than silently no-op.
 *
 * Mirrors `uk.me.cormack.lighting7.fx.AssignmentHealth` on the backend — the fixture /
 * group / property variants are shared with cue-authoring; stack / cue / bank variants
 * are surface-only.
 */
export type BindingHealth =
  | { type: "ok" }
  | { type: "missingFixture"; fixtureKey: string }
  | { type: "missingGroup"; groupName: string }
  | { type: "missingAttribute"; targetKey: string; attribute: string }
  // Two `ref:` variants used to sit here — carried not because a control surface could hold a
  // reference value, but because this type is the superset `describeHealth` switches over. They
  // retired with the grammar in session 4.
  // A binding names a stack or cue by uuid; the id is 0 and stays for the screens session 3 replaces.
  | { type: "missingStack"; stackId: number; stackUuid?: string | null }
  | { type: "missingCue"; cueId: number; cueUuid?: string | null }
  | { type: "unknownBank"; deviceTypeKey: string; bankId: string }
  | { type: "missingSpeedMaster"; masterUuid: string }
  // A selection or encoder-bank property no fixture in the patch declares. It names the property
  // and no target, because there is no target: what makes it dead is the patch, not a reference.
  | { type: "unknownAttribute"; attribute: string }
  // The record variants. Keyed by uuid because that is what the binding carries.
  | { type: "missingLook"; lookUuid: string }
  | { type: "missingPalette"; paletteUuid: string }
  | { type: "missingPad"; padUuid: string }
  // A `handPlaceInBank` binding naming a **busk** bank that is gone. Not `unknownBank`, which is a
  // *device profile's* bank (an X-Touch layer): one is a row on a busk page, the other a mode of a
  // control surface, and they share only the word.
  | { type: "missingBank"; bankUuid: string }
  | { type: "missingPage"; pageUuid: string }
  // A `buskFocusSet` / `buskSheetToggle` binding names a desk window by its registry name and no
  // connected window has it. Unlike every other arm it is **transient**: it clears the moment a
  // window of that name announces, which is why the desk re-evaluates health on every registry
  // change (busk-further plan D14).
  | { type: "missingWindow"; windowName: string }
  // The row the tolerant decode kept (see `UnknownTarget`). Dead, drawn, rebindable.
  | { type: "unknownTarget"; targetType: string }
/** A single persisted binding. */
export interface ControlSurfaceBinding {
  id: number
  projectId: number
  deviceTypeKey: string
  controlId: string
  part: ControlPart
  bank: string | null
  binding: SurfaceBinding
  takeoverPolicy: TakeoverPolicy | null
  sortOrder: number
  health: BindingHealth
}
// Device-type profile — discriminated union for the descriptor sub-types.

export type FaderResolution = "SEVEN_BIT" | "FOURTEEN_BIT"
export type EncoderRingStyle = "NONE" | "SINGLE_DOT" | "FAN" | "PAN"
export type LedFeedback = "NONE" | "ON_OFF" | "BRIGHTNESS" | "COLOUR"

export interface FaderControl {
  type: "fader"
  controlId: string
  label: string
  cc: number
  channel: number
  hasMotor: boolean
  motorCc: number | null
  /**
   * Touch-sense, in the two spellings the wire allows — at most one is set. A note-style fader
   * sends note-on/note-off; a CC-style one (the shipped X-Touch Compact profile) sends a CC whose
   * value > 0 means down.
   */
  touchNote: number | null
  touchCc: number | null
  resolution: FaderResolution
}

export interface EncoderControl {
  type: "encoder"
  controlId: string
  label: string
  cc: number
  channel: number
  ringCc: number | null
  ringStyle: EncoderRingStyle
  pushNote: number | null
  pushLed: LedFeedback
}

export interface ButtonControl {
  type: "button"
  controlId: string
  label: string
  note: number
  channel: number
  ledFeedback: LedFeedback
}

export interface BankButtonControl {
  type: "bankButton"
  controlId: string
  label: string
  /**
   * Exactly one of `note` / `programChange` is non-null — the backend's `BankButtonDescriptor`
   * requires it. Most bank buttons are notes; the X-Touch Compact's A/B Layer button is a program
   * change, which is why `note` is nullable here rather than merely optional.
   */
  note: number | null
  programChange: number | null
  channel: number
  bankId: string
}

export type ControlDescriptor =
  | FaderControl
  | EncoderControl
  | ButtonControl
  | BankButtonControl

export interface BankDefinition {
  id: string
  name: string
}

/**
 * A channel strip: the controls one `strip` binding row covers. `id` is what that row's
 * `controlId` holds — a strip id and a control id share one column and one namespace, which is
 * why the backend registry refuses a strip whose id collides with a control's.
 *
 * `encoder` and `flash` are optional because the master strip has neither.
 */
export interface StripDefinition {
  id: string
  fader: string
  select: string
  encoder?: string | null
  flash?: string | null
}

/** One control's place in the panel picture. */
export interface LayoutCell {
  controlId: string
  col: number
  row: number
}

export interface LayoutRegion {
  name: string
  columns: number
  cells: LayoutCell[]
}

/**
 * The panel picture, as profile data rather than a React component per device (plan D8). Absent
 * when the profile declares none, and then the view falls back to the grouped table. A declared
 * layout is complete — the backend registry refuses one with a control missing a cell, because a
 * control absent from the picture would be unreachable with nothing saying so.
 */
export interface SurfaceLayout {
  regions: LayoutRegion[]
}

export interface ControlSurfaceType {
  typeKey: string
  vendor: string | null
  product: string | null
  portPattern: string | null
  className: string
  controls: ControlDescriptor[]
  banks: BankDefinition[]
  /**
   * Both optional, and not as a convenience: a desk running a build from before the strip
   * session serves neither field, and this client talks to whatever desk it is pointed at. Left
   * required, every reader would compile against a shape an older server does not send and throw
   * on the first dereference — which is exactly what a `/control-surface-types` response from
   * such a desk produces. Absent `layout` is also the documented fallback signal: no picture,
   * the grouped table instead.
   */
  strips?: StripDefinition[]
  layout?: SurfaceLayout | null
}

export interface SurfaceDeviceInfo {
  displayKey: string
  displayName: string
  typeKey: string | null
  isMatched: boolean
  hasInputPort: boolean
  hasOutputPort: boolean
  /** This device's own active bank; two devices of one type each keep their own. */
  activeBank: string | null
}

// ─── The control-state stream ─────────────────────────────────────────

/** What a button's LED has been told. `none` when the control has no LED-bearing binding. */
export type LedState = "on" | "off" | "none"

/**
 * What an encoder's ring shows: a value (`on`), its off state for mixed / unbound / no selection
 * (`off`), or nothing because the control has no ring or no binding (`none`).
 */
export type RingState = "on" | "off" | "none"

/**
 * One control's state **as the hardware has been told it**. The picture draws exactly this and
 * never recomputes a control from DMX: if the screen and the desk disagree, the publisher is
 * wrong, which is the bug worth finding (plan D7).
 *
 * `value` is the fed-back 7-bit position and is null for mixed, unbound or no selection — the
 * three cases the legend draws differently and which a `0` would flatten into "at the bottom".
 * `physical` is the last inbound position of a fader, which the hardware is never told, and which
 * a non-motor fader's operator wants to see beside its pickup target.
 */
export interface ControlState {
  value: number | null
  physical: number | null
  touched: boolean
  led: LedState
  ring: RingState
}

/** Every attached device's controls, keyed `displayKey` → `controlId` → state. */
export type SurfaceControlStates = Readonly<
  Record<string, Readonly<Record<string, ControlState>>>
>

export type PickupState = "ENGAGED" | "AWAITING_PICKUP"

export interface PickupChange {
  displayKey: string
  controlId: string
  state: PickupState
  target: number | null
}

export type BindingChangeType = "added" | "updated" | "removed" | "reloaded"

export interface BindingsChangeEvent {
  projectId: number
  changeType: BindingChangeType
  bindingId: number | null
}

// ─── The served picture ────────────────────────────────────────────────────────────────────
// Mirrors `midi/SurfaceMap.kt` and `midi/SurfaceCatalogue.kt`. The desk resolves, labels and judges
// every control part; this side draws what it is sent and keeps no copy of the rules.

/** Where a part's binding comes from: its own row, the strip it is on, or nothing. */
export type PartSource = "DIRECT" | "STRIP" | "NONE"

/** Where a function writes, in one word (D8). */
export type WritesTo = "LOCAL" | "PLAYBACK" | "MASTER" | "TEMPO" | "DESK"

/** One control part on one layer, as the desk resolved it. */
export interface ResolvedPart {
  from: PartSource
  /** The row's id; on a strip control, the strip's row. */
  bindingId?: number | null
  /** The layer the row is stored on; absent for both. */
  bank?: string | null
  stripId?: string | null
  does?: SurfaceDoes | null
  on?: SurfaceSubject | null
  how?: How | null
  /** The catalogue entry `does` is; absent for an unreadable row. */
  entry?: string | null
  takeoverPolicy?: TakeoverPolicy | null
  label?: string | null
  subjectLabel?: string | null
  colour?: string | null
  writesTo?: WritesTo | null
  /** What the control shows, in a sentence. */
  shows?: string | null
  health?: BindingHealth
  healthText?: string | null
}

/** A part's key in a map frame: `"btn-36:MAIN"`. */
export function partKey(controlId: string, part: ControlPart): string {
  return `${controlId}:${part}`
}

/** One device type's map: layer (`""` for a profile with none) → part key → part. */
export interface SurfaceMapFrame {
  projectId: number
  deviceTypeKey: string
  layers: Record<string, Record<string, ResolvedPart>>
}

export type ControlKind = "CONTINUOUS" | "BUTTON"

export type SubjectKind =
  | "NONE" | "SELECTION" | "GROUP" | "FIXTURE" | "SLOT"
  | "STACK" | "ENTRY" | "CUE" | "LOOK" | "PALETTE" | "PAD" | "BANK" | "PAGE" | "MASTER"

export type ParamKind = "ATTRIBUTE" | "CHOICE" | "NUMBER" | "TEXT" | "RECORD" | "LAYER"

export interface CatalogueParam {
  key: string
  label: string
  kind: ParamKind
  required?: boolean
  default?: string | number | boolean | null
  options?: { value: string | number | boolean; label: string }[]
  min?: number | null
  max?: number | null
  record?: RecordKind | null
}

/** One function an editor offers (`GET /surface-functions`). */
export interface CatalogueEntry {
  id: string
  /** The `does.type` a binding built from this entry carries. */
  does: string
  label: string
  group: string
  help: string
  inputs: ControlKind[]
  strip?: boolean
  subjects: SubjectKind[]
  hows: How[]
  defaultHow: How | null
  writesTo: WritesTo
  params: CatalogueParam[]
}

/** The input a control's part feeds, or null for a part it does not have. Profile data, not a rule. */
export function inputOf(control: ControlDescriptor, part: ControlPart): ControlKind | null {
  switch (control.type) {
    case "fader":
      return part === "MAIN" ? "CONTINUOUS" : null
    case "encoder":
      return part === "MAIN" ? "CONTINUOUS" : control.pushNote != null ? "BUTTON" : null
    case "button":
      return part === "MAIN" ? "BUTTON" : null
    case "bankButton":
      return null
  }
}

/** The parts a control has, main first. */
export function partsOf(control: ControlDescriptor): ControlPart[] {
  return (["MAIN", "PUSH"] as const).filter((part) => inputOf(control, part) != null)
}

/** This window's identify wait: what its banner draws. */
export interface IdentifyState {
  sessionId: string
  waiting: boolean
  deviceTypeKey?: string | null
  deadlineMs?: number | null
  /** How long the wait had left when the desk sent this; counted down on this browser's clock. */
  remainingMs?: number | null
  /** Why a wait ended without a capture: `cancelled` or `timeout`. */
  reason?: string | null
}

/** The control part a touch named, sent to the window that waited for it only. */
export interface IdentifyCapture {
  sessionId: string
  deviceTypeKey: string
  displayKey: string
  controlId: string
  part: ControlPart
}

export type IdentifyEvent = ({ type: "state" } & IdentifyState) | ({ type: "captured" } & IdentifyCapture)

export interface SurfacesWsApi {
  /** List of all attached MIDI devices (matched + unmatched). */
  subscribeDevices(fn: (devices: SurfaceDeviceInfo[]) => void): Subscription
  /** Active bank per attached device, keyed by its `displayKey`. */
  subscribeBanks(fn: (banks: Record<string, string>) => void): Subscription
  /** Pickup-state transitions (non-motor fader soft takeover). */
  subscribePickup(fn: (change: PickupChange) => void): Subscription
  /**
   * Every attached device's control state, already folded: `surfaceControls.state` replaces one
   * device's map wholesale, `surfaceControls.changed` merges its partial delta into it. Merging is
   * what keeps an untouched control's object identity stable across a 20 Hz delta, which is what
   * lets a memoized control skip the render.
   */
  subscribeControls(fn: (controls: SurfaceControlStates) => void): Subscription
  /** What each device's strip encoders drive, `displayKey` → selection; absent is `dimmer`. */
  subscribeEncoderBanks(fn: (attributes: Record<string, EncoderBankSelection>) => void): Subscription
  /** Every device type's served map, keyed by its type; each frame replaces its type's. */
  subscribeMaps(fn: (maps: Readonly<Record<string, SurfaceMapFrame>>) => void): Subscription

  // The last snapshot of each cached stream, or null before its first frame, for a reader that is
  // not a subscriber — an RTK Query `queryFn` seeding its cache entry.
  getDevices(): SurfaceDeviceInfo[] | null
  getBanks(): Record<string, string> | null
  getScaler(): ScalerState | null
  getControls(): SurfaceControlStates | null
  getEncoderBanks(): Record<string, EncoderBankSelection> | null
  getMaps(): Readonly<Record<string, SurfaceMapFrame>> | null

  /** Binding list membership changed (added / updated / removed / reloaded). */
  subscribeBindingsChanged(fn: (event: BindingsChangeEvent) => void): Subscription
  /** This connection's identify waits and captures. */
  subscribeIdentify(fn: (event: IdentifyEvent) => void): Subscription

  /**
   * Wait for a touch on the desk (D14): the next press or move is captured and not dispatched. False
   * when the socket was down, so no banner frame is coming.
   */
  beginIdentify(deviceTypeKey?: string | null): boolean
  cancelIdentify(sessionId: string): void

  /** Scaler state (blackout / grand master). */
  subscribeScaler(fn: (state: ScalerState) => void): Subscription
  setBlackout(enabled: boolean): void
  setGrandMaster(enabled: boolean): void
}

export interface ScalerState {
  blackoutEnabled: boolean
  grandMasterEnabled: boolean
}

type InboundMessage =
  | { type: "surfaceDevices.state"; devices: SurfaceDeviceInfo[] }
  | { type: "surfaceBank.state"; activeBanks: Record<string, string> }
  | {
      type: "surfaceBank.changed"
      displayKey: string
      /** The device's profile; null once it has detached. */
      deviceTypeKey: string | null
      previousBank: string | null
      newBank: string | null
    }
  | ({ type: "surfacePickup.changed" } & PickupChange)
  | { type: "surfaceControls.state"; displayKey: string; controls: Record<string, ControlState> }
  | { type: "surfaceControls.changed"; displayKey: string; controls: Record<string, ControlState> }
  | { type: "surfaceEncoderBank.state"; attributes: Record<string, EncoderBankSelection> }
  | ({ type: "surfaceBank.bindingsChanged" } & BindingsChangeEvent)
  | ({ type: "surfaceScaler.state" } & ScalerState)
  | ({ type: "surfaceMap.state" } & SurfaceMapFrame)
  | ({ type: "surfaceIdentify.state" } & IdentifyState)
  | ({ type: "surfaceIdentify.captured" } & IdentifyCapture)

export function createSurfacesWsApi(conn: InternalApiConnection): SurfacesWsApi {
  const devices = createWsSubscribable<SurfaceDeviceInfo[]>()
  const banks = createWsSubscribable<Record<string, string>>()
  const pickup = createWsSubscribable<PickupChange>()
  const bindings = createWsSubscribable<BindingsChangeEvent>()
  const identify = createWsSubscribable<IdentifyEvent>()
  const scaler = createWsSubscribable<ScalerState>()
  const controls = createWsSubscribable<SurfaceControlStates>()
  const encoderBanks = createWsSubscribable<Record<string, EncoderBankSelection>>()
  const maps = createWsSubscribable<Readonly<Record<string, SurfaceMapFrame>>>()

  let lastDevices: SurfaceDeviceInfo[] | null = null
  let lastBanks: Record<string, string> | null = null
  let lastScaler: ScalerState | null = null
  let lastControls: SurfaceControlStates | null = null
  let lastEncoderBanks: Record<string, EncoderBankSelection> | null = null
  let lastMaps: Readonly<Record<string, SurfaceMapFrame>> | null = null

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== "message") return

    const parsed = frame as InboundMessage | null
    if (!parsed) return

    switch (parsed.type) {
      case "surfaceDevices.state":
        lastDevices = parsed.devices
        devices.notify(parsed.devices)
        break
      case "surfaceBank.state":
        lastBanks = parsed.activeBanks
        banks.notify(parsed.activeBanks)
        break
      case "surfaceBank.changed": {
        const next: Record<string, string> = { ...(lastBanks ?? {}) }
        if (parsed.newBank == null) delete next[parsed.displayKey]
        else next[parsed.displayKey] = parsed.newBank
        lastBanks = next
        banks.notify(next)
        break
      }
      // A whole device: the connect frame, and the frame after every full resync. An empty
      // `controls` map means the device detached, so the key goes.
      case "surfaceControls.state": {
        const next = { ...(lastControls ?? {}) }
        if (Object.keys(parsed.controls).length === 0) delete next[parsed.displayKey]
        else next[parsed.displayKey] = parsed.controls
        lastControls = next
        controls.notify(next)
        break
      }
      case "surfaceControls.changed": {
        const device = lastControls?.[parsed.displayKey]
        const next = {
          ...(lastControls ?? {}),
          [parsed.displayKey]: { ...(device ?? {}), ...parsed.controls },
        }
        lastControls = next
        controls.notify(next)
        break
      }
      case "surfaceEncoderBank.state":
        lastEncoderBanks = parsed.attributes
        encoderBanks.notify(parsed.attributes)
        break
      case "surfacePickup.changed":
        pickup.notify({
          displayKey: parsed.displayKey,
          controlId: parsed.controlId,
          state: parsed.state,
          target: parsed.target,
        })
        break
      case "surfaceBank.bindingsChanged":
        bindings.notify({
          projectId: parsed.projectId,
          changeType: parsed.changeType,
          bindingId: parsed.bindingId,
        })
        break
      case "surfaceScaler.state":
        lastScaler = {
          blackoutEnabled: parsed.blackoutEnabled,
          grandMasterEnabled: parsed.grandMasterEnabled,
        }
        scaler.notify(lastScaler)
        break
      case "surfaceMap.state": {
        const { type: _type, ...frame } = parsed
        lastMaps = { ...(lastMaps ?? {}), [frame.deviceTypeKey]: frame }
        maps.notify(lastMaps)
        break
      }
      case "surfaceIdentify.state": {
        const { type: _type, ...rest } = parsed
        identify.notify({ type: "state", ...rest })
        break
      }
      case "surfaceIdentify.captured": {
        const { type: _type, ...rest } = parsed
        identify.notify({ type: "captured", ...rest })
        break
      }
    }
  })

  const subscribeCached = <T>(
    api: { subscribe(fn: (v: T) => void): Subscription },
    snapshot: () => T | null,
  ) => (fn: (v: T) => void): Subscription => {
    const sub = api.subscribe(fn)
    const s = snapshot()
    if (s != null) fn(s)
    return sub
  }

  return {
    subscribeDevices: subscribeCached(devices.api, () => lastDevices),
    subscribeBanks: subscribeCached(banks.api, () => lastBanks),
    subscribePickup: pickup.api.subscribe,
    subscribeControls: subscribeCached(controls.api, () => lastControls),
    subscribeEncoderBanks: subscribeCached(encoderBanks.api, () => lastEncoderBanks),
    subscribeMaps: subscribeCached(maps.api, () => lastMaps),
    getDevices: () => lastDevices,
    getBanks: () => lastBanks,
    getScaler: () => lastScaler,
    getControls: () => lastControls,
    getEncoderBanks: () => lastEncoderBanks,
    getMaps: () => lastMaps,
    subscribeBindingsChanged: bindings.api.subscribe,
    subscribeIdentify: identify.api.subscribe,
    subscribeScaler: subscribeCached(scaler.api, () => lastScaler),
    beginIdentify: (deviceTypeKey) =>
      sendGesture(conn, { type: "surfaceIdentify.begin", deviceTypeKey: deviceTypeKey ?? null }),
    cancelIdentify: (sessionId) =>
      sendGesture(conn, { type: "surfaceIdentify.cancel", sessionId }),
    setBlackout: (enabled) =>
      sendGesture(conn, { type: "surfaceScaler.setBlackout", enabled }),
    setGrandMaster: (enabled) =>
      sendGesture(conn, { type: "surfaceScaler.setGrandMaster", enabled }),
  }
}
