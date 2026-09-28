import { InternalApiConnection } from "./internalApi"
import type { SubselectMode } from "./selectionApi"
import type { BuskFocus } from "../lib/buskWindow"
import { Subscription } from "./subscription"
import { createWsSubscribable } from "./wsSubscriptionFactory"
import { sendGesture } from "./wsGesture"
import type { CueTarget } from "./cuesApi"

// Types mirror `uk.me.cormack.lighting7.midi.BindingTarget` and related Kotlin
// classes on the backend. Keep field names in sync with kotlinx.serialization.

/**
 * Which axis of a **colour** property a continuous control drives — HSV, hue by default. Mirrors
 * `midi/ColourAxis.kt`; `lib/colourAxis.ts` owns the labels and the null-is-hue rule. A binding
 * carries no `colourAxis` for hue (the field is omitted on the wire, on both sides), and `| null`
 * only because a hand-built payload may spell the absence out.
 */
export type ColourAxis = "hue" | "hueFine" | "saturation" | "brightness"

/**
 * What a device's strip encoders drive: a property and, for a colour, which axis (absent is hue).
 * Mirrors `midi/EncoderBankSelection`; carried by `surfaceEncoderBank.state` and read back by
 * `lib/surfaceResolve.ts` when it derives a strip's encoder.
 */
export interface EncoderBankSelection {
  propertyName: string
  colourAxis?: ColourAxis | null
}

/** Continuous target: fixture property driven by a fader/encoder. */
export interface FixturePropertyTarget {
  type: "fixtureProperty"
  fixtureKey: string
  propertyName: string
  /** On a colour property, which axis; absent is hue. Refused by name on anything else. */
  colourAxis?: ColourAxis | null
}

/** Continuous target: fixture-group property driven by a fader/encoder. */
export interface GroupPropertyTarget {
  type: "groupProperty"
  groupName: string
  propertyName: string
  /** As on {@link FixturePropertyTarget}; a member on which the property is not a colour is skipped. */
  colourAxis?: ColourAxis | null
}

// The four below carry a uuid *beside* the int id rather than instead of it. The int is what a
// pre-v11 project wrote and is still what dispatch falls back to; the uuid is what survives a
// clone or a cross-install import, and the backend resolves uuid-first where both are present
// (`FU-SYNC-BINDING-PAYLOAD-UUIDS`, first half). Nullable because a row written before format 11
// has never been through the service that fills it in.

export interface CueStackGoTarget {
  type: "cueStackGo"
  stackId: number
  stackUuid?: string | null
}

export interface CueStackBackTarget {
  type: "cueStackBack"
  stackId: number
  stackUuid?: string | null
}

export interface CueStackPauseTarget {
  type: "cueStackPause"
  stackId: number
  stackUuid?: string | null
}

export interface FireCueTarget {
  type: "fireCue"
  cueId: number
  cueUuid?: string | null
}

/** Flash wraps either a FixtureProperty or GroupProperty continuous target. */
export interface FlashTarget {
  type: "flash"
  target: FixturePropertyTarget | GroupPropertyTarget
  max?: number
}

export interface BlackoutTarget {
  type: "blackout"
}

export interface GrandMasterToggleTarget {
  type: "grandMasterToggle"
}

export interface SetBankTarget {
  type: "setBank"
  deviceTypeKey: string
  bank: string
}

/**
 * Drive a speed master's tempo from a fader / encoder. `masterUuid` null means master 1,
 * matching the `speedMasters.*` WS family — and a uuid rather than an int id, so the binding
 * survives clone and cross-install import.
 *
 * The control's 0..127 maps across `minBpm`..`maxBpm` rather than the clock's full 20..300:
 * 128 steps over the whole range is ~2.2 BPM a step, too coarse to trim a tempo with.
 */
export interface SpeedMasterBpmTarget {
  type: "speedMasterBpm"
  masterUuid: string | null
  minBpm: number
  maxBpm: number
}

/** Tap a speed master's tempo on button press (`masterUuid` null → master 1). */
export interface SpeedMasterTapTarget {
  type: "speedMasterTap"
  masterUuid: string | null
}

// ─── Selection-relative targets ───────────────────────────────────────
//
// The layer beside the fixed bindings above: these name no fixture of their own, and what they
// act on is whatever is in the desk selection when the control moves. See
// `docs/plans/completed/midi-surface-plan.md` D3 in lighting7.

/**
 * Write a continuous property on **every selected target** — a fixture directly, a group fanned
 * to its members. An empty selection drops the write rather than widening it to everything, and
 * the control reads as the legend's "no selection" state.
 */
export interface SelectionPropertyTarget {
  type: "selectionProperty"
  propertyName: string
  /** As on {@link FixturePropertyTarget}; judged against the rig's colour vocabulary at bind time. */
  colourAxis?: ColourAxis | null
}

/** How a {@link SelectTargetTarget} press changes the desk selection. */
export type SelectMode = "toggle" | "replace"

/**
 * Put one group or fixture into (or out of) the desk selection on press. A `toggle` also fires a
 * `replace` when held past the backend's `SELECT_HOLD_MS`, so the LED is immediate and the hold
 * narrows; that is a router behaviour, not a second target.
 */
export interface SelectTargetTarget {
  type: "selectTarget"
  target: CueTarget
  mode: SelectMode
}

/** Empty the desk selection on press. */
export interface ClearSelectionTarget {
  type: "clearSelection"
}

/** Locate every selected target on press; a second press releases them. */
export interface LocateSelectionTarget {
  type: "locateSelection"
}

/**
 * One group or fixture on a whole **channel strip**. The row is addressed by the strip id rather
 * than a control id and is never dispatched as it stands: it derives to the target each of the
 * strip's controls behaves as — fader and flash on the dimmer, select, encoder on the device's
 * current encoder bank. `lib/surfaceResolve.ts` is this side's copy of that rule.
 */
export interface StripTarget {
  type: "strip"
  target: CueTarget
}

/**
 * Point the device's strip encoders at `propertyName` on press. Applies to the device the button
 * is on, so the payload names no device; the LED is lit while this is that device's bank.
 */
export interface EncoderBankSetTarget {
  type: "encoderBankSet"
  propertyName: string
  /**
   * Which axis of a colour property the bank points the encoders at; absent is hue. The LED
   * lights when the property **and** the axis match the device's bank.
   */
  colourAxis?: ColourAxis | null
}

// ─── Records on buttons ───────────────────────────────────────────────
//
// A named thing from the library, uuid-addressed, **each with exactly one behaviour** — see D6 in
// `docs/plans/completed/midi-surface-plan.md`. Uuids rather than int ids because a binding has to survive a
// clone or a cross-install import, which is what `FU-SYNC-BINDING-PAYLOAD-UUIDS` records.

/**
 * Press a Look onto **its own fixtures** — never the selection.
 *
 * The opposite choice from {@link PressTemplateTarget}, and for the reason that makes each of them
 * "one behaviour every press": a Look names its fixtures and a template names none. A Look with a
 * deferred effect has no own targets, so it is refused at bind time
 * (`BINDING_LOOK_NEEDS_SELECTION`) and reads as health `lookNeedsSelection` if it gains one later.
 */
export interface ApplyLookTarget {
  type: "applyLook"
  lookUuid: string
}

/**
 * Press a template onto the **desk selection**, as a layer that tracks it — the strip's ⌥click
 * (or touch-hold) gesture.
 *
 * A generic template with nothing selected is dropped: its rows take their targets from the press.
 * A per-fixture one names its own heads. The family mask is derived server-side from the
 * template's rows, so nothing here sends one.
 */
export interface PressTemplateTarget {
  type: "pressTemplate"
  templateUuid: string
}

/**
 * Press a busk pad — its own bank's plan, solo siblings included, on the desk selection.
 *
 * The same press `POST /busk/pads/{id}/press` makes, through one `BuskPressService`, so a hardware
 * press and a screen press of one pad cannot diverge.
 */
export interface PressPadTarget {
  type: "pressPad"
  padUuid: string
}

/**
 * Put the record on a **busk pad** into the desk's hand — the surface door of `hand.pickUp`
 * (multi-screen plan §3.5).
 *
 * A pad, not a record: a pad is the address a button already knows how to carry, and what it picks
 * up is exactly what it would otherwise press. The pick-up **replaces** whatever the hand held;
 * there is no "put it back" gesture on hardware or anywhere else.
 */
export interface PickUpPadTarget {
  type: "pickUpPad"
  padUuid: string
}

/**
 * Place whatever the hand holds as a pad on the bank [bankUuid] names, then let go.
 *
 * The **one** place binding there is. A window places through its own mutation and then sends
 * `hand.drop` (D12); a button has no mutation of its own, so this runs the same append
 * `POST /busk/banks/{bankId}/pads` runs. An empty hand is a dropped press, not an error.
 *
 * There is deliberately no `handPlaceInSlot` and no layer-stack place: neither a cue slot nor the
 * programmer's layer stack has a uuid a binding could carry.
 */
export interface HandPlaceInBankTarget {
  type: "handPlaceInBank"
  bankUuid: string
}

/** Let go of whatever the hand holds. A no-op on an empty hand, like every other drop. */
export interface HandDropTarget {
  type: "handDrop"
}

/** Show the next busk page, wrapping. Page-agnostic, so it names none. */
export interface BuskPageNextTarget {
  type: "buskPageNext"
}

/** Show the previous busk page, wrapping. */
export interface BuskPagePrevTarget {
  type: "buskPagePrev"
}

/** Show one named busk page. Its LED is lit while that page is the one showing. */
export interface BuskPageSetTarget {
  type: "buskPageSet"
  pageUuid: string
}

/**
 * Set a desk window's busk **focus** — `split`, `pads` or `rig` — on press (busk-further plan D14).
 *
 * The window is named by its **registry name** (`windows.state`), since a binding cannot hold a
 * socket-minted row id: the desk sends one `windows.viewOptions {focus}` to **every** connected row
 * of that name (duplicate names are allowed, multi-screen plan D9), carrying the view that row
 * announced, so a window not on the busk view ignores it. A name matching no connected window is a
 * logged no-op with health `missingWindow`, re-evaluated as windows come and go. No LED: focus is
 * that window's own tab fact, not a desk state.
 */
export interface BuskFocusSetTarget {
  type: "buskFocusSet"
  windowName: string
  focus: BuskFocus
}

/** Fold or unfold a named desk window's busk side sheet on press — `{sheet: toggle}`, addressed and judged as [BuskFocusSetTarget]. */
export interface BuskSheetToggleTarget {
  type: "buskSheetToggle"
  windowName: string
}

/** Step the whole desk selection one place along rig order, wrapping — `SubselectMode.NEXT`. */
export interface SelectionNextTarget {
  type: "selectionNext"
}

/** Step the whole desk selection one place back along rig order, wrapping — `SubselectMode.PREV`. */
export interface SelectionPrevTarget {
  type: "selectionPrev"
}

/**
 * Rewrite the desk selection's targets by [mode] on press — the Cells chip on a button, through the
 * same `DeskSelection.subselect` the chip's frame reaches. A sub-selection is not a state the desk
 * keeps, so there is no LED (`FU-SURFACE-SUBSELECT-LED`).
 */
export interface SelectionCellsTarget {
  type: "selectionCells"
  mode: SubselectMode
}

/**
 * A persisted payload whose `type` this build does not know — produced only by the backend's
 * tolerant per-row decode and re-encoded verbatim, so an older desk reading a newer project keeps
 * the row instead of failing the load. It reads as health `unknownTarget`, draws dead, and is
 * rebindable; that is the whole point of it existing rather than the row being dropped.
 */
export interface UnknownTarget {
  type: "unknown"
  targetType: string
  rawPayload: string
}

export type BindingTarget =
  | FixturePropertyTarget
  | GroupPropertyTarget
  | CueStackGoTarget
  | CueStackBackTarget
  | CueStackPauseTarget
  | FireCueTarget
  | FlashTarget
  | BlackoutTarget
  | GrandMasterToggleTarget
  | SetBankTarget
  | SpeedMasterBpmTarget
  | SpeedMasterTapTarget
  | SelectionPropertyTarget
  | SelectTargetTarget
  | ClearSelectionTarget
  | LocateSelectionTarget
  | StripTarget
  | EncoderBankSetTarget
  | ApplyLookTarget
  | PressTemplateTarget
  | PressPadTarget
  | PickUpPadTarget
  | HandPlaceInBankTarget
  | HandDropTarget
  | BuskPageNextTarget
  | BuskPagePrevTarget
  | BuskPageSetTarget
  | BuskFocusSetTarget
  | BuskSheetToggleTarget
  | SelectionNextTarget
  | SelectionPrevTarget
  | SelectionCellsTarget
  | UnknownTarget

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
  | { type: "missingProperty"; targetKey: string; propertyName: string }
  // Two `ref:` variants used to sit here — carried not because a control surface could hold a
  // reference value, but because this type is the superset `describeHealth` switches over. They
  // retired with the grammar in session 4.
  | { type: "missingStack"; stackId: number }
  | { type: "missingCue"; cueId: number }
  | { type: "unknownBank"; deviceTypeKey: string; bankId: string }
  | { type: "missingSpeedMaster"; masterUuid: string }
  // A selection or encoder-bank property no fixture in the patch declares. It names the property
  // and no target, because there is no target: what makes it dead is the patch, not a reference.
  | { type: "unknownProperty"; propertyName: string }
  // The record variants. Keyed by uuid because that is what the binding carries.
  | { type: "missingLook"; lookUuid: string }
  | { type: "missingTemplate"; templateUuid: string }
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
  // Not "missing": the Look is still there, but it has gained a deferred effect, so it has no own
  // targets and a button has no selection to give it. A different state because it has a different
  // fix — bind the effect's targets, rather than rebind the button.
  | { type: "lookNeedsSelection"; lookUuid: string }
  // The row the tolerant decode kept (see `UnknownTarget`). Dead, drawn, rebindable.
  | { type: "unknownTarget"; targetType: string }

/** A single persisted binding. */
export interface ControlSurfaceBinding {
  id: number
  projectId: number
  deviceTypeKey: string
  controlId: string
  bank: string | null
  target: BindingTarget
  targetType: string
  takeoverPolicy: TakeoverPolicy | null
  sortOrder: number
  health: BindingHealth
}

export interface CreateSurfaceBindingRequest {
  deviceTypeKey: string
  controlId: string
  bank?: string | null
  target: BindingTarget
  takeoverPolicy?: TakeoverPolicy | null
  sortOrder?: number
}

export interface UpdateSurfaceBindingRequest {
  deviceTypeKey?: string
  controlId?: string
  /** `bankPresent: true` with `bank: null` clears the bank. */
  bank?: string | null
  bankPresent?: boolean
  target?: BindingTarget
  /** `takeoverPolicyPresent: true` with `takeoverPolicy: null` clears. */
  takeoverPolicy?: TakeoverPolicy | null
  takeoverPolicyPresent?: boolean
  sortOrder?: number
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

export interface LearnCapturedEvent {
  sessionId: string
  projectId: number
  deviceTypeKey: string
  controlId: string
}

export interface LearnStartedEvent {
  sessionId: string
  projectId: number
  deviceTypeKey: string | null
  deadlineMs: number
}

export interface LearnCancelledEvent {
  sessionId: string
  reason: string
}

export interface LearnErrorEvent {
  sessionId: string | null
  message: string
}

export interface LearnCommittedEvent {
  sessionId: string
  bindingId: number
  projectId: number
}

export interface SurfacesWsApi {
  /** List of all attached MIDI devices (matched + unmatched). */
  subscribeDevices(fn: (devices: SurfaceDeviceInfo[]) => void): Subscription
  /** Active bank per `deviceTypeKey`. */
  subscribeBanks(fn: (banks: Record<string, string>) => void): Subscription
  /** Pickup-state transitions (non-motor fader soft takeover). */
  subscribePickup(fn: (change: PickupChange) => void): Subscription
  /**
   * Every attached device's control state, already folded: `surfaceControls.state` replaces one
   * device's map wholesale, `surfaceControls.changed` merges its partial delta into it. The fold
   * lives here rather than in the store for the reason `surfaceBank.changed`'s does — one place
   * that knows how the two frames of a family compose — and because merging is what keeps an
   * untouched control's object identity stable across a 20 Hz delta, which is what lets a
   * memoized control skip the render.
   */
  subscribeControls(fn: (controls: SurfaceControlStates) => void): Subscription
  /** What each device's strip encoders drive, `deviceTypeKey` → selection; absent is `dimmer`. */
  subscribeEncoderBanks(fn: (properties: Record<string, EncoderBankSelection>) => void): Subscription

  // The last snapshot of each cached stream, or null before its first frame. `subscribeX`
  // already replays that snapshot synchronously to a new subscriber; these exist so a reader
  // that is not a subscriber — an RTK Query `queryFn` seeding its cache entry — can have the
  // same value without one. Mirrors `speedMasters.getState()`. There is deliberately no
  // pickup equivalent: pickup is an event stream with no snapshot to replay.
  getDevices(): SurfaceDeviceInfo[] | null
  getBanks(): Record<string, string> | null
  getScaler(): ScalerState | null
  getControls(): SurfaceControlStates | null
  getEncoderBanks(): Record<string, EncoderBankSelection> | null

  /** Binding list membership changed (added / updated / removed / reloaded). */
  subscribeBindingsChanged(fn: (event: BindingsChangeEvent) => void): Subscription
  /** Learn session events scoped to this connection. */
  subscribeLearn(fn: (event: LearnEvent) => void): Subscription

  // Three `request*State()` senders stood here — `surfaceDevices.state`, `surfaceBank.state`,
  // `surfaceScaler.state` — described as "request the initial snapshots when mounted". Nothing
  // ever called them, and nothing needed to: `setupSurfaceSubscriptions` takes each snapshot as a
  // StateFlow subscription, so the server sends all three on connect, and `subscribeCached` below
  // replays the last one to a subscriber that arrives after the frame. The backend still handles
  // the request messages, so add one back if a surface ever needs a re-read the socket doesn't
  // volunteer.

  /** Set the active bank for a device type (pass null to clear). */
  setBank(deviceTypeKey: string, bank: string | null): void
  /**
   * Start a MIDI Learn session; responses come back via `subscribeLearn`. False when the socket
   * was down, so no `started` event is coming — the caller has a spinner to take down.
   */
  beginLearn(projectId: number, deviceTypeKey?: string | null): boolean
  cancelLearn(sessionId: string): void
  /** False when the socket was down — see `beginLearn`, and the same spinner. */
  commitLearn(
    sessionId: string,
    target: BindingTarget,
    bank?: string | null,
    takeoverPolicy?: TakeoverPolicy | null,
  ): boolean

  /** Scaler state (blackout / grand master). */
  subscribeScaler(fn: (state: ScalerState) => void): Subscription
  setBlackout(enabled: boolean): void
  setGrandMaster(enabled: boolean): void
}

export interface ScalerState {
  blackoutEnabled: boolean
  grandMasterEnabled: boolean
}

export type LearnEvent =
  | ({ type: "started" } & LearnStartedEvent)
  | ({ type: "captured" } & LearnCapturedEvent)
  | ({ type: "committed" } & LearnCommittedEvent)
  | ({ type: "cancelled" } & LearnCancelledEvent)
  | ({ type: "error" } & LearnErrorEvent)

type InboundMessage =
  | { type: "surfaceDevices.state"; devices: SurfaceDeviceInfo[] }
  | { type: "surfaceBank.state"; activeBanks: Record<string, string> }
  | { type: "surfaceBank.changed"; deviceTypeKey: string; previousBank: string | null; newBank: string | null }
  | ({ type: "surfacePickup.changed" } & PickupChange)
  | { type: "surfaceControls.state"; displayKey: string; controls: Record<string, ControlState> }
  | { type: "surfaceControls.changed"; displayKey: string; controls: Record<string, ControlState> }
  | { type: "surfaceEncoderBank.state"; properties: Record<string, EncoderBankSelection> }
  | ({ type: "surfaceBank.bindingsChanged" } & BindingsChangeEvent)
  | ({ type: "surfaceScaler.state" } & ScalerState)
  | ({ type: "surfaceLearn.started" } & LearnStartedEvent)
  | ({ type: "surfaceLearn.captured" } & LearnCapturedEvent)
  | ({ type: "surfaceLearn.committed" } & LearnCommittedEvent)
  | ({ type: "surfaceLearn.cancelled" } & LearnCancelledEvent)
  | ({ type: "surfaceLearn.error" } & LearnErrorEvent)

const LEARN_TYPE_MAP: Record<string, LearnEvent["type"]> = {
  "surfaceLearn.started": "started",
  "surfaceLearn.captured": "captured",
  "surfaceLearn.committed": "committed",
  "surfaceLearn.cancelled": "cancelled",
  "surfaceLearn.error": "error",
}

export function createSurfacesWsApi(conn: InternalApiConnection): SurfacesWsApi {
  const devices = createWsSubscribable<SurfaceDeviceInfo[]>()
  const banks = createWsSubscribable<Record<string, string>>()
  const pickup = createWsSubscribable<PickupChange>()
  const bindings = createWsSubscribable<BindingsChangeEvent>()
  const learn = createWsSubscribable<LearnEvent>()
  const scaler = createWsSubscribable<ScalerState>()
  const controls = createWsSubscribable<SurfaceControlStates>()
  const encoderBanks = createWsSubscribable<Record<string, EncoderBankSelection>>()

  // Cached latest snapshots so newly-mounted subscribers can read current
  // state synchronously without a round-trip.
  let lastDevices: SurfaceDeviceInfo[] | null = null
  let lastBanks: Record<string, string> | null = null
  let lastScaler: ScalerState | null = null
  let lastControls: SurfaceControlStates | null = null
  let lastEncoderBanks: Record<string, EncoderBankSelection> | null = null

  // No state requests on open: the server pushes `surfaceDevices.state`,
  // `surfaceBank.state` and `surfaceScaler.state` per connection.
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
        // Apply the delta locally; the server also pushes a fresh
        // `surfaceDevices.state` via its combine() flow so that channel
        // stays consistent independently.
        const next: Record<string, string> = { ...(lastBanks ?? {}) }
        if (parsed.newBank == null) delete next[parsed.deviceTypeKey]
        else next[parsed.deviceTypeKey] = parsed.newBank
        lastBanks = next
        banks.notify(next)
        break
      }
      // A whole device: the connect frame, and the frame after every full resync. An empty
      // `controls` map means the device detached, so the key goes rather than being left behind
      // as an attached-looking device with no controls.
      case "surfaceControls.state": {
        const next = { ...(lastControls ?? {}) }
        if (Object.keys(parsed.controls).length === 0) delete next[parsed.displayKey]
        else next[parsed.displayKey] = parsed.controls
        lastControls = next
        controls.notify(next)
        break
      }
      // Only the controls that moved, at their current state. Merged, so every control the delta
      // does not name keeps the object identity a memoized row is comparing against.
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
        lastEncoderBanks = parsed.properties
        encoderBanks.notify(parsed.properties)
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
      default: {
        const outType = LEARN_TYPE_MAP[parsed.type]
        if (outType) {
          const { type: _ignored, ...rest } = parsed
          learn.notify({ type: outType, ...rest } as LearnEvent)
        }
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
    getDevices: () => lastDevices,
    getBanks: () => lastBanks,
    getScaler: () => lastScaler,
    getControls: () => lastControls,
    getEncoderBanks: () => lastEncoderBanks,
    subscribeBindingsChanged: bindings.api.subscribe,
    subscribeLearn: learn.api.subscribe,
    subscribeScaler: subscribeCached(scaler.api, () => lastScaler),
    setBank: (deviceTypeKey, bank) =>
      sendGesture(conn, { type: "surfaceBank.set", deviceTypeKey, bank }),
    beginLearn: (projectId, deviceTypeKey) =>
      sendGesture(conn, {
        type: "surfaceLearn.begin",
        projectId,
        deviceTypeKey: deviceTypeKey ?? null,
      }),
    cancelLearn: (sessionId) =>
      sendGesture(conn, { type: "surfaceLearn.cancel", sessionId }),
    commitLearn: (sessionId, target, bank, takeoverPolicy) =>
      sendGesture(conn, {
        type: "surfaceLearn.commit",
        sessionId,
        target,
        bank: bank ?? null,
        takeoverPolicy: takeoverPolicy ?? null,
      }),
    setBlackout: (enabled) =>
      sendGesture(conn, { type: "surfaceScaler.setBlackout", enabled }),
    setGrandMaster: (enabled) =>
      sendGesture(conn, { type: "surfaceScaler.setGrandMaster", enabled }),
  }
}
