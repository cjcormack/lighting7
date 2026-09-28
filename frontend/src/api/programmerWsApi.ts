import type { CueTarget, LayerSource } from './cuesApi'
import type { InternalApiConnection } from './internalApi'
import type { Subscription } from './subscription'
import { sendGesture } from './wsGesture'

/**
 * Client for the backend's Layer-2 programmer (`programmer.*` WS ops plus the
 * `provenanceState` broadcast). See `lighting7/docs/lighting-composition-model.md`
 * §"Layer 2 — Programmer" for the semantics this mirrors.
 *
 * Two things about the wire shape drive the design here:
 *
 * 1. **Programmer replies are unicast.** `entryChanged` / `entryCleared` / `cleared` /
 *    `blindState` / `state` come back only to the connection that asked. Writes made by
 *    MIDI surfaces, locate, preset toggles or another browser tab never produce a reply
 *    we can see.
 * 2. **Three frames are broadcast**: `provenanceState`, which fires on every layer event
 *    including every programmer mutation whoever made it, plus `programmer.includeTarget`
 *    and `programmer.layerState`. All three are shared state — the programmer is one
 *    object, so a second tab must not show a stale include target or a stale layer order.
 *
 * So provenance doubles as the invalidation signal: on receipt we debounce briefly and
 * re-request `programmer.state`. Own-connection replies are additionally applied straight
 * to the cache so a local edit paints without waiting for the round trip.
 *
 * The one exception: a crossfade's weight ticks are layer events too, so a running fade
 * republishes provenance at up to ~20 Hz with nothing in it changed. Each frame carries
 * `programmerRevision` — a monotonic count of the triggers that could have moved the
 * programmer's value set, which weight-only republishes don't bump — and we refetch only
 * when it moved. Without that, every tab answered a fade with ~10 `programmer.state`
 * requests/s. A revision on every frame rather than a per-frame flag because the server's
 * broadcast flow drops frames past a slow tab: whatever frame arrives carries the latest
 * revision, so a MIDI write, locate, template apply or second-tab edit mid-fade always
 * refetches. A server that omits the field degrades to refetch-on-every-frame.
 */

export type ProgrammerTargetType = 'fixture' | 'group'

/** Which layer produced the current winning value for a (targetKey, propertyName). */
export type ProvenanceSource = 'PARKED' | 'PROGRAMMER' | 'EFFECT' | 'CUE'

export interface ProgrammerEntry {
  targetKey: string
  propertyName: string
  /**
   * Canonical assignment string. See `src/lib/programmerValue.ts`.
   *
   * Until session 4 this could instead be `ref:{paletteUuid}`, with `resolvedValue`,
   * `paletteUuid`, `paletteId`, `paletteName` and `paletteResolved` beside it describing the
   * referenced Look and whether it still covered this target. The `ref:` value grammar retired; the
   * programmer reports its Look **layers** separately (`programmer.layerState`), and a layer's
   * `propertyMask` is what says "this property comes from that Look".
   */
  value: string
  /** Winning slot's owner: `web` | `surface` | `flash` | `locate` | `unpark` | `layers`. */
  owner: string
  /** Sticky operator-edit flag. False marks a mechanical hand-down (unpark). */
  touched: boolean
  /** Group name when the write came through a group control. */
  sourceGroup?: string
  /** Every owner holding this property, most recent first. */
  owners: string[]
}

/**
 * One entry in the backend's channel **sideband** — *not* the programmer's channel output.
 *
 * The sideband holds only what the property model can't lift: raw `updateChannel` writes on
 * channels with no backing property, raw pan/tilt axis writes, and unpark hand-downs. A dimmer or
 * colour set through `programmer.set` lands in a property entry (`ProgrammerState.entries`) and
 * never appears here — and `FxEngine.absorbSidebandUnder` actively drops sideband slots beneath a
 * deliberate property write.
 *
 * So `ProgrammerState.channels` is not "what the programmer is holding, as channels". To get that,
 * resolve the property entries with `lib/programmerChannels.ts` and treat these as a supplement.
 * The backend DTO says "channel-sideband entry"; the word went missing on the way over here, and
 * a follow-up was written on the assumption that this field was the whole picture.
 */
export interface ProgrammerChannelEntry {
  universe: number
  channel: number
  value: number
  owner: string
  touched: boolean
}

export interface ProvenanceEntry {
  targetKey: string
  propertyName: string
  source: ProvenanceSource
  cueId?: number
  cueStackId?: number
  effectId?: number
  /**
   * The Look layer that won this key, when one did.
   *
   * Deliberately **not** a new `source` value on the backend either, so every existing read of
   * `source` is untouched: this is the extra sentence, not a different answer. It is what lets the
   * sheet answer "why is this fixture this colour?" with *Warm Wash* rather than *a cue*, and it is
   * present for a cue's layers as well as the programmer's.
   */
  layerId?: number
  /** What that layer applies — a Look or a template, named. */
  layerSource?: LayerSource
  /**
   * For a cue-won colour whose W / A / UV came from a bundled emitter's own row (a Hex's `white`),
   * that emitter's winner — one per emitter. The backend lets the emitter's row replace the
   * colour's copy of it, so the colour's bytes can come from two contributors; this names the
   * second. Absent when empty.
   */
  bundled?: BundledProvenance[]
}

/** One entry of `ProvenanceEntry.bundled`. */
export interface BundledProvenance {
  /** The emitter property (`white`, `amber`, `uv`) whose own row supplied the component. */
  propertyName: string
  cueId?: number
  cueStackId?: number
  layerId?: number
  layerSource?: LayerSource
}

/**
 * One layer of the programmer's stack, in `sortOrder` — most significant last.
 *
 * The programmer holds the same structure a cue does (see `CueLayer`), which is what makes
 * `LookStack` render both. It has no timing fields: a programmer layer fires now, by definition.
 */
export interface ProgrammerLayer {
  /** The stack's own id for this layer. Every mutation op addresses a layer by it. */
  layerId: number
  /** What this layer applies — a Look or a template. */
  source: LayerSource
  sortOrder: number
  enabled: boolean
  targets: CueTarget[]
  propertyMask?: string | null
  blendMode: string
  amount: number
  stomp: boolean
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  /** Set when Include minted this from a cue's layer — Update's diff key. */
  sourceCueLayerId?: number | null
}

/** How much of one target a record is applied to. `some` only ever describes a group. */
export type AppliedExtent = 'all' | 'some'

/** One target a Look or template is applied to, and how much of it. */
export interface AppliedTarget {
  type: string
  key: string
  state: AppliedExtent
}

/**
 * One Look or template and every target it is currently applied to — the **resolved** view of
 * [ProgrammerState.layers], as the desk computes it (`ProgrammerLayerStack.appliedState`).
 *
 * Groups arrive already expanded and folded back up: a layer on `{group: wash}` reports the wash
 * *and* each of its heads, and a layer on one head reports that head plus the wash as `some`. A
 * pad's ring is therefore a lookup, not a coverage calculation — see `lookPresence.ts`. Nothing in
 * the client expands a group or matches a target: the desk owns that rule, and a second copy here
 * is one that drifts and that no test on the rig can reach.
 */
export interface ProgrammerAppliedSource {
  source: LayerSource
  targets: AppliedTarget[]
}

/**
 * What Include last pulled into the programmer, and therefore what a bare Update writes back to.
 * Null means nothing is staged, so Update falls through to the Mode B checklist.
 *
 * A discriminated union rather than one shape with nullable halves: a cue target has no Look id and
 * vice versa, and the compiler should say so at every read site. Use `describeIncludedTarget` in
 * `src/lib/includedTarget.ts` for anything user-facing so the wording can't drift between the
 * toolbar, the Update dialog and the collapsed pane.
 *
 * There was a third `PALETTE` arm, kept on the grounds that a stale target from an older client
 * would still need naming. `IncludedTargetDto` has since dropped all three `palette*` fields with
 * the palette tables, so the arm could only ever have rendered "Palette undefined" — the server
 * cannot describe a palette target even if one somehow arrived.
 */
export type IncludedTarget =
  | {
      kind: 'CUE'
      cueId: number
      cueStackId?: number
      cueName?: string
      cueNumber?: string
    }
  | {
      /**
       * A Look was included, and Update writes back into it — `updateIncludedLook` MERGEs whatever
       * changed since Include, leaving the Look's untouched rows alone. It was one-way until the
       * record rewrite landed, because the only write-back path led into the retired palette tables.
       */
      kind: 'LOOK'
      lookId: number
      lookName?: string
    }

/** The client-side view of the programmer, rebuilt from each `programmer.state` snapshot. */
export interface ProgrammerState {
  blind: boolean
  /** Keyed by [programmerKey]. */
  entries: ReadonlyMap<string, ProgrammerEntry>
  channels: readonly ProgrammerChannelEntry[]
  /** Keyed by [programmerKey]. A key absent here is baseline-owned. */
  provenance: ReadonlyMap<string, ProvenanceEntry>
  lastIncluded: IncludedTarget | null
  /** The Look-layer stack, most significant last. */
  layers: readonly ProgrammerLayer[]
  /** The same stack resolved to per-target applied state — what a busk pad's ring reads. */
  applied: readonly ProgrammerAppliedSource[]
}

/** Per-cell lookup result: what the programmer and the cascade say about one property. */
export interface ProgrammerKeyState {
  entry?: ProgrammerEntry
  provenance?: ProvenanceEntry
}

// ── Outgoing ────────────────────────────────────────────────────────────────

interface ProgrammerSetOutgoing {
  type: 'programmer.set'
  targetType: ProgrammerTargetType
  targetKey: string
  propertyName: string
  value: string
  fadeMs?: number
  sourceGroup?: string
}

interface ProgrammerSetColourOutgoing {
  type: 'programmer.setColour'
  targetType: ProgrammerTargetType
  targetKey: string
  propertyName?: string
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
  fadeMs?: number
  sourceGroup?: string
}

interface ProgrammerSetPositionOutgoing {
  type: 'programmer.setPosition'
  targetType: ProgrammerTargetType
  targetKey: string
  pan: number
  tilt: number
  fadeMs?: number
  sourceGroup?: string
}

interface ProgrammerClearEntryOutgoing {
  type: 'programmer.clearEntry'
  targetType: ProgrammerTargetType
  targetKey: string
  propertyName: string
  fadeMs?: number
}

interface ProgrammerClearAllOutgoing {
  type: 'programmer.clearAll'
  fadeMs?: number
}

interface ProgrammerSetBlindOutgoing {
  type: 'programmer.setBlind'
  blind: boolean
  fadeMs?: number
}

interface ProgrammerStateOutgoing {
  type: 'programmer.state'
}

/**
 * Put a Look **or a template** on the stack as a layer, on top.
 *
 * No uuid: the backend resolves the record itself from the id, and reads its name and uuid in the
 * same breath — a client that supplied both could make the two disagree. Unlike the retired preset
 * toggle this carries the whole source, so a Look's **bound** row lands on the fixture it names — a
 * layer has somewhere to put a target set, which is what the old path lacked.
 */
interface ProgrammerAddLayerOutgoing {
  type: 'programmer.addLayer'
  /** Exactly one of these. The server refuses both and neither. */
  lookId?: number
  templateId?: number
  targets: CueTarget[]
  propertyMask?: string
  blendMode?: string
  amount?: number
  speedMasterUuid?: string
  rateSpeedMasterUuid?: string
  fadeMs?: number
}

interface ProgrammerRemoveLayerOutgoing {
  type: 'programmer.removeLayer'
  layerId: number
  fadeMs?: number
}

/** Move a layer to [toIndex] among the non-preview layers. The server renumbers the whole list. */
interface ProgrammerMoveLayerOutgoing {
  type: 'programmer.moveLayer'
  layerId: number
  toIndex: number
}

/** Change one layer's fields. An omitted field means "leave alone", not "clear". */
interface ProgrammerPatchLayerOutgoing {
  type: 'programmer.patchLayer'
  layerId: number
  enabled?: boolean
  amount?: number
  propertyMask?: string
  blendMode?: string
  targets?: CueTarget[]
  stomp?: boolean
  fadeMs?: number
}

export type ProgrammerOutgoingMessage =
  | ProgrammerSetOutgoing
  | ProgrammerSetColourOutgoing
  | ProgrammerSetPositionOutgoing
  | ProgrammerClearEntryOutgoing
  | ProgrammerClearAllOutgoing
  | ProgrammerSetBlindOutgoing
  | ProgrammerStateOutgoing
  | ProgrammerAddLayerOutgoing
  | ProgrammerRemoveLayerOutgoing
  | ProgrammerMoveLayerOutgoing
  | ProgrammerPatchLayerOutgoing

// ── Incoming ────────────────────────────────────────────────────────────────

interface ProgrammerStateIncoming {
  type: 'programmer.state'
  blind: boolean
  entries: ProgrammerEntry[]
  channels: ProgrammerChannelEntry[]
  lastIncluded?: IncludedTarget | null
  /**
   * Additive and defaulted on the wire, so a fresh connection gets the stack without a second
   * round trip. The layers' *values* already arrive through [entries], attributed to the `layers`
   * owner; this is the structure behind them.
   */
  layers?: ProgrammerLayer[]
  /** The same stack resolved, so a fresh connection can light its pads without a round trip. */
  applied?: ProgrammerAppliedSource[]
}

interface ProgrammerIncludeTargetIncoming {
  type: 'programmer.includeTarget'
  target?: IncludedTarget | null
}

interface ProgrammerLayerStateIncoming {
  type: 'programmer.layerState'
  layers: ProgrammerLayer[]
  /** Resolved from the very list `layers` carries, so the two halves of a frame always agree. */
  applied?: ProgrammerAppliedSource[]
}

interface ProgrammerEntryChangedIncoming {
  type: 'programmer.entryChanged'
  targetType: ProgrammerTargetType
  targetKey: string
  propertyName: string
  value: string
}

interface ProgrammerEntryClearedIncoming {
  type: 'programmer.entryCleared'
  targetType: ProgrammerTargetType
  targetKey: string
  propertyName: string
}

interface ProgrammerClearedIncoming {
  type: 'programmer.cleared'
  cleared: number
  /** Programmer-band FX swept alongside the values. Omitted by the server when 0. */
  effectsCleared?: number
}

interface ProgrammerBlindStateIncoming {
  type: 'programmer.blindState'
  blind: boolean
}

interface ProgrammerErrorIncoming {
  type: 'programmer.error'
  message: string
}

interface ProvenanceStateIncoming {
  type: 'provenanceState'
  entries: ProvenanceEntry[]
  /**
   * Monotonic count of the triggers that could have changed the programmer's value set.
   * Unchanged across crossfade weight ticks, so the `programmer.state` refetch runs only
   * when it moved. Optional: an older server omits it, and we then refetch on every frame.
   */
  programmerRevision?: number
}

type ProgrammerIncomingMessage =
  | ProgrammerStateIncoming
  | ProgrammerIncludeTargetIncoming
  | ProgrammerLayerStateIncoming
  | ProgrammerEntryChangedIncoming
  | ProgrammerEntryClearedIncoming
  | ProgrammerClearedIncoming
  | ProgrammerBlindStateIncoming
  | ProgrammerErrorIncoming
  | ProvenanceStateIncoming

// ── Public surface ──────────────────────────────────────────────────────────

export interface ProgrammerApi {
  getState(): ProgrammerState
  /** Programmer entry + provenance for one property. Allocation-free on the hot path. */
  getKeyState(targetKey: string, propertyName: string): ProgrammerKeyState
  isBlind(): boolean
  /** Number of stored property entries (the "programmer holds data" count). */
  entryCount(): number
  /** What Include last loaded, or null. Drives the Update button's label and target. */
  lastIncluded(): IncludedTarget | null
  /** The Look-layer stack, most significant last. */
  layers(): readonly ProgrammerLayer[]
  /** The same stack resolved to per-target applied state — a busk pad's ring, already answered. */
  applied(): readonly ProgrammerAppliedSource[]

  set(
    targetType: ProgrammerTargetType,
    targetKey: string,
    propertyName: string,
    value: string,
    fadeMs?: number,
    /**
     * Names the group control this write came from, for fan-outs that can't send
     * `targetType: 'group'` (a group virtual dimmer over heterogeneous members, a Highlight
     * release restoring per-fixture values). It only widens the shape Record can emit, and
     * the server drops it unless the group really contains this fixture.
     */
    sourceGroup?: string,
  ): void
  setColour(
    targetType: ProgrammerTargetType,
    targetKey: string,
    propertyName: string,
    rgb: { r: number; g: number; b: number; w?: number; a?: number; uv?: number },
    fadeMs?: number,
    sourceGroup?: string,
  ): void
  setPosition(
    targetType: ProgrammerTargetType,
    targetKey: string,
    pan: number,
    tilt: number,
    fadeMs?: number,
    sourceGroup?: string,
  ): void
  clearEntry(
    targetType: ProgrammerTargetType,
    targetKey: string,
    propertyName: string,
    fadeMs?: number,
  ): void
  clearAll(fadeMs?: number): void
  setBlind(blind: boolean, fadeMs?: number): void
  requestState(): void

  /**
   * Add a layer. Answered by the whole `programmer.layerState` broadcast, so there is no reply and
   * no layer id — but the **send itself** has a synchronous verdict, which this returns: false
   * means `sendGesture` found the socket closed, toasted, and put nothing on the wire.
   *
   * Its one reader is the hand's place (`HandProgrammerLayerStrip`), which must not let go of the
   * held record for a frame that never left. Every other caller ignores it, as they always did.
   */
  addLayer(input: {
    /** Exactly one of these — a layer applies a Look or a template. */
    lookId?: number
    templateId?: number
    targets?: CueTarget[]
    propertyMask?: string
    blendMode?: string
    amount?: number
    speedMasterUuid?: string
    rateSpeedMasterUuid?: string
    fadeMs?: number
  }): boolean
  removeLayer(layerId: number, fadeMs?: number): void
  /** [toIndex] counts non-preview layers, which is what the server's own move does. */
  moveLayer(layerId: number, toIndex: number): void
  patchLayer(
    layerId: number,
    patch: {
      enabled?: boolean
      amount?: number
      propertyMask?: string
      blendMode?: string
      targets?: CueTarget[]
      stomp?: boolean
      fadeMs?: number
    },
  ): void

  /** Fires on any change to the programmer or provenance. Drives coarse consumers. */
  subscribe(fn: (state: ProgrammerState) => void): Subscription
  /**
   * Fires only when this one property's entry or provenance changed. This is what keeps a
   * few-hundred-row sheet from re-rendering every cell on each provenance push — the same
   * split `channelsApi.subscribeToChannel` makes for DMX values.
   */
  subscribeToKey(
    targetKey: string,
    propertyName: string,
    fn: (state: ProgrammerKeyState) => void,
  ): Subscription
  /**
   * Errors from `programmer.*` ops. The server unicasts these to the acting socket, so the
   * subscriber can toast unconditionally; `store/programmerErrors.ts` is the one that does.
   */
  subscribeToErrors(fn: (message: string) => void): Subscription
}

/** Cache/subscription key for a (target, property) pair. */
export function programmerKey(targetKey: string, propertyName: string): string {
  return `${targetKey}|${propertyName}`
}

/**
 * How long to wait after a `provenanceState` push before re-requesting `programmer.state`.
 * Provenance is already coalesced ~50 ms server-side; this second stage collapses the
 * bursts a cue change or an effect starting produces into a single refetch.
 */
const STATE_REFETCH_DEBOUNCE_MS = 100

const EMPTY_KEY_STATE: ProgrammerKeyState = {}

// JSON, not a delimiter-joined string: group names and palette-ref values are free text,
// so any separator character could also occur inside a field and make two different
// entries compare equal.
//
// Every field a cell renders has to be in here. The palette fields especially: without them a
// rename, a re-record, or a reference going unresolved changes nothing observable, the diff
// reports no change, and the cell keeps painting the old colour indefinitely — a stale cell that
// looks perfectly fine.
const entrySignature = (e: ProgrammerEntry) =>
  JSON.stringify([e.value, e.owner, e.touched, e.sourceGroup ?? null, e.owners])

// The layer fields belong here: a key can move from "the cue" to "the cue's Warm Wash layer"
// with `source` unchanged, and leaving them out would leave the cell showing the old answer.
const provenanceSignature = (p: ProvenanceEntry) =>
  JSON.stringify([
    p.source,
    p.cueId ?? null,
    p.cueStackId ?? null,
    p.effectId ?? null,
    p.layerId ?? null,
    // The whole source object, not just its id: a key can move between a Look layer and a
    // template layer that happen to share an int PK, and the cell would keep naming the old one.
    p.layerSource?.kind ?? null,
    p.layerSource?.id ?? null,
    p.layerSource?.name ?? null,
  ])

/**
 * A map of values paired with the signatures of those values, kept in step by construction.
 *
 * The per-key channel diffs by **content**, not identity: every snapshot is rebuilt from
 * `JSON.parse`, so each frame hands us structurally-equal-but-freshly-allocated objects, and an
 * identity check would call every key changed on every frame — an unrelated cue firing would
 * wake every cell in the sheet, exactly what the channel exists to prevent.
 *
 * Signing *both* sides per key per frame is what that used to cost, at 10–20 Hz under load. Only
 * the incoming side is signed now; the resident side was signed when it was installed and is held
 * here. That trades the second `JSON.stringify` pass for one `Map` of N short strings per install,
 * which the old shape did not allocate — not profiled, but the two sides are not close, and the
 * strings exist either way.
 *
 * The pairing is the reason this is a type rather than two variables. A signature left behind for
 * a key whose value has moved makes the diff call that key unchanged, and the cell keeps painting
 * the old value until something unrelated wakes it — a stale cell that looks perfectly fine. Every
 * mutator here moves both maps, so no caller can produce one.
 *
 * [values] is handed straight to the state snapshot and so is copy-on-write. The signatures never
 * leave this closure and are mutated in place: a drag echo allocating a fresh N-entry map per
 * frame is the cost this cache exists to avoid.
 */
function signedMap<T>(signature: (value: T) => string) {
  let values = new Map<string, T>()
  let signatures = new Map<string, string>()

  return {
    get values(): ReadonlyMap<string, T> {
      return values
    },
    get size(): number {
      return values.size
    },
    get: (key: string): T | undefined => values.get(key),
    has: (key: string): boolean => values.has(key),

    /** Replace the whole map, returning the keys whose value changed. */
    install(next: Map<string, T>): string[] {
      const nextSignatures = new Map<string, string>()
      const touched: string[] = []
      for (const [key, value] of next) {
        const sig = signature(value)
        nextSignatures.set(key, sig)
        // An absent key reads back `undefined`, which no signature ever equals, so a key that
        // wasn't there before is reported changed — as it was when this compared the values.
        if (signatures.get(key) !== sig) touched.push(key)
      }
      for (const key of signatures.keys()) {
        if (!next.has(key)) touched.push(key)
      }
      values = next
      signatures = nextSignatures
      return touched
    },

    /** Set one key. The caller has already decided this is a change; nothing is diffed. */
    put(key: string, value: T): void {
      values = new Map(values).set(key, value)
      signatures.set(key, signature(value))
    },

    /** Remove one key. */
    drop(key: string): void {
      const next = new Map(values)
      next.delete(key)
      values = next
      signatures.delete(key)
    },

    /** Empty the map, returning every key that was in it. */
    clear(): string[] {
      const touched = [...values.keys()]
      values = new Map()
      signatures = new Map()
      return touched
    },
  }
}

export function createProgrammerApi(conn: InternalApiConnection): ProgrammerApi {
  let blind = false
  const entries = signedMap(entrySignature)
  let channels: ProgrammerChannelEntry[] = []
  const provenance = signedMap(provenanceSignature)
  let lastIncluded: IncludedTarget | null = null
  let layers: readonly ProgrammerLayer[] = []
  let applied: readonly ProgrammerAppliedSource[] = []

  const buildSnapshot = (): ProgrammerState => ({
    blind,
    entries: entries.values,
    channels,
    provenance: provenance.values,
    lastIncluded,
    layers,
    applied,
  })
  let snapshot: ProgrammerState = buildSnapshot()
  const rebuildSnapshot = () => {
    snapshot = buildSnapshot()
  }

  let nextSubscriptionId = 1
  const stateSubscriptions = new Map<number, (state: ProgrammerState) => void>()
  const keySubscriptions = new Map<string, Map<number, (state: ProgrammerKeyState) => void>>()
  const errorSubscriptions = new Map<number, (message: string) => void>()

  const keyStateFor = (key: string): ProgrammerKeyState => {
    const entry = entries.get(key)
    const prov = provenance.get(key)
    if (!entry && !prov) return EMPTY_KEY_STATE
    return { entry, provenance: prov }
  }

  const notifyKeys = (keys: Iterable<string>) => {
    for (const key of keys) {
      const subs = keySubscriptions.get(key)
      if (!subs) continue
      const state = keyStateFor(key)
      subs.forEach((fn) => fn(state))
    }
  }

  const notifyState = () => {
    rebuildSnapshot()
    stateSubscriptions.forEach((fn) => fn(snapshot))
  }

  // Bare `setTimeout`, not `window.setTimeout`: this module is exercised by unit tests that
  // run without a DOM, and nothing here needs the window-typed handle.
  let refetchTimer: ReturnType<typeof setTimeout> | undefined
  const scheduleStateRefetch = () => {
    if (refetchTimer !== undefined) return
    refetchTimer = setTimeout(() => {
      refetchTimer = undefined
      conn.send(JSON.stringify({ type: 'programmer.state' }))
    }, STATE_REFETCH_DEBOUNCE_MS)
  }

  const applyStateSnapshot = (message: ProgrammerStateIncoming) => {
    const nextEntries = new Map<string, ProgrammerEntry>()
    for (const entry of message.entries) {
      nextEntries.set(programmerKey(entry.targetKey, entry.propertyName), entry)
    }
    const touched = entries.install(nextEntries)
    blind = message.blind
    channels = message.channels
    // The include target isn't per-key, so it rides `notifyState` only and is deliberately
    // *not* part of `entrySignature` — including it there would wake every cell whenever the
    // operator included a different cue.
    lastIncluded = message.lastIncluded ?? null
    // Same argument for the layer stack: it is per-cue-composition, not per-cell. An absent
    // `layers` is an older server, so leave what we have rather than blanking the pane.
    if (message.layers) layers = message.layers
    // `?? []`, not the truthiness guard `layers` uses, and the asymmetry is the point: this is the
    // **resync** frame, so what it says about coverage has to win outright. A server sends this
    // field always (`@EncodeDefault(ALWAYS)`, since an empty stack must not read as an absent
    // field), so absent means a server too old to have the concept — and dark pads are the right
    // answer there. Keeping the previous value instead would leave every pad ringed against an
    // empty programmer after a desk restart, with the press meant to clear one adding a layer.
    applied = message.applied ?? []
    notifyKeys(touched)
    notifyState()
  }

  // The last programmerRevision a provenance frame delivered. Starts undefined so the first
  // frame of a connection always refetches, whatever revision it carries.
  let lastProgrammerRevision: number | undefined

  const applyProvenance = (message: ProvenanceStateIncoming) => {
    const next = new Map<string, ProvenanceEntry>()
    for (const entry of message.entries) {
      next.set(programmerKey(entry.targetKey, entry.propertyName), entry)
    }
    const touched = provenance.install(next)
    // A frame that changed nothing wakes nobody: a fade republishes content-identical
    // provenance ~20×/s, and an ungated notifyState would rebuild the snapshot and re-render
    // every whole-state subscriber per frame for the whole fade. (This also absorbs the
    // duplicated connect frame a warm desk delivers.) The refetch decision below is
    // deliberately independent — identical provenance does NOT mean identical values, so it
    // rides the revision, never `touched`.
    if (touched.length > 0) {
      notifyKeys(touched)
      notifyState()
    }
    // Provenance is the only broadcast the server sends for programmer activity, so it is
    // also our cue to re-read the values — including writes we didn't make. Except when the
    // revision says nothing since the last frame could have moved the programmer (a
    // crossfade's weight ticks don't bump it): refetching on those turned every fade into
    // ~10 requests/s per tab. An older server omits the field and we refetch every frame.
    const revision = message.programmerRevision
    if (revision === undefined || revision !== lastProgrammerRevision) {
      lastProgrammerRevision = revision
      scheduleStateRefetch()
    }
  }

  /**
   * Optimistic local echo of our own write, so a drag paints at input rate instead of at
   * round-trip rate. The authoritative `programmer.state` refetch that provenance triggers
   * lands ~100 ms later and overwrites this with the server's view.
   */
  const applyLocalEntry = (message: ProgrammerEntryChangedIncoming) => {
    const key = programmerKey(message.targetKey, message.propertyName)
    const existing = entries.get(key)
    // This reply is only ever our own write (the op is unicast), so the winning slot is now
    // `web` — even if some other owner held the property a moment ago. Carrying the previous
    // owner forward would make a fader drag over a located fixture report "locate" as the
    // thing driving it until the refetch lands. `sourceGroup` is dropped for the same reason:
    // a direct write did not come through a group control.
    const owners = ['web', ...(existing?.owners ?? []).filter((o) => o !== 'web')]
    // There was a reference-recovery step here: the echo carries only the value, so a write of a
    // `ref:` string landed locally as a reference-*less* entry until the refetch corrected it, and
    // every cell Apply Palette touched dropped its badge for ~100 ms. Nothing writes a `ref:` any
    // more, so the echo is the whole entry.
    entries.put(key, {
      targetKey: message.targetKey,
      propertyName: message.propertyName,
      value: message.value,
      owner: 'web',
      touched: true,
      owners,
    })
    notifyKeys([key])
    notifyState()
  }

  const applyLocalClear = (message: ProgrammerEntryClearedIncoming) => {
    const key = programmerKey(message.targetKey, message.propertyName)
    if (!entries.has(key)) return
    entries.drop(key)
    notifyKeys([key])
    notifyState()
  }

  const handleMessage = (message: ProgrammerIncomingMessage) => {
    switch (message.type) {
      case 'programmer.state':
        applyStateSnapshot(message)
        break
      case 'programmer.includeTarget':
        // Broadcast, unlike the other programmer replies: the programmer is shared, so a
        // second tab's Update button must offer the same target.
        lastIncluded = message.target ?? null
        notifyState()
        break
      case 'programmer.layerState':
        // Broadcast for the same reason, and handled the same way: a second tab reordering the
        // stack must not leave this one showing a stale order.
        //
        // `notifyState` only — the stack is not per-cell state, and waking every cell on a
        // reorder is exactly what the key/state split exists to avoid. Deliberately no
        // `scheduleStateRefetch()` either: every layer mutation also emits `provenanceState`,
        // which already schedules the value re-read. A second call here would be redundant
        // rather than wrong (the timer guard swallows it), but it would read as the mechanism.
        layers = message.layers
        // Only ever assigned together with `layers`, from the same frame: the resolved view and
        // the list it resolves are one answer, and a pad reading one against the other's stack
        // would light for a layer that had already gone.
        applied = message.applied ?? []
        notifyState()
        break
      case 'provenanceState':
        applyProvenance(message)
        break
      case 'programmer.entryChanged':
        applyLocalEntry(message)
        break
      case 'programmer.entryCleared':
        applyLocalClear(message)
        break
      case 'programmer.cleared': {
        const touched = entries.clear()
        channels = []
        // Clear releases everything Include staged, so the server drops the target too.
        lastIncluded = null
        notifyKeys(touched)
        notifyState()
        break
      }
      case 'programmer.blindState':
        blind = message.blind
        notifyState()
        break
      case 'programmer.error':
        errorSubscriptions.forEach((fn) => fn(message.message))
        break
    }
  }

  // No `programmer.state` request on open: the server pushes one per connection.
  // `requestState()` and `scheduleStateRefetch()` remain, as explicit resync.
  conn.subscribe((evType, _ev, parsed) => {
    if (evType !== 'message') return
    // The connection parses each frame once for every bridge, so the substring
    // pre-filter this used to run against the channelState / fxState firehose has
    // nothing left to save: discriminate on the parsed `type` instead.
    if (!parsed || typeof parsed !== 'object') return
    const t = (parsed as Record<string, unknown>).type
    if (typeof t !== 'string') return
    if (t !== 'provenanceState' && !t.startsWith('programmer.')) return
    handleMessage(parsed as ProgrammerIncomingMessage)
  })

  // Every outgoing frame below is an operator gesture — a set, a Blind press, a layer move — so
  // a write that lands on a dead socket is announced rather than dropped. The one exception is
  // `programmer.state`, idempotent catch-up that goes out through `conn.send` directly.
  const send = (message: ProgrammerOutgoingMessage) => sendGesture(conn, message)

  return {
    getState: () => snapshot,
    getKeyState: (targetKey, propertyName) => keyStateFor(programmerKey(targetKey, propertyName)),
    isBlind: () => blind,
    entryCount: () => entries.size,
    lastIncluded: () => lastIncluded,
    layers: () => layers,
    applied: () => applied,

    set(targetType, targetKey, propertyName, value, fadeMs, sourceGroup) {
      send({
        type: 'programmer.set',
        targetType,
        targetKey,
        propertyName,
        value,
        fadeMs,
        sourceGroup,
      })
    },
    setColour(targetType, targetKey, propertyName, rgb, fadeMs, sourceGroup) {
      send({
        type: 'programmer.setColour',
        targetType,
        targetKey,
        propertyName,
        ...rgb,
        fadeMs,
        sourceGroup,
      })
    },
    setPosition(targetType, targetKey, pan, tilt, fadeMs, sourceGroup) {
      send({ type: 'programmer.setPosition', targetType, targetKey, pan, tilt, fadeMs, sourceGroup })
    },
    clearEntry(targetType, targetKey, propertyName, fadeMs) {
      send({ type: 'programmer.clearEntry', targetType, targetKey, propertyName, fadeMs })
    },
    clearAll(fadeMs) {
      send({ type: 'programmer.clearAll', fadeMs })
    },
    setBlind(blindOn, fadeMs) {
      send({ type: 'programmer.setBlind', blind: blindOn, fadeMs })
    },
    requestState() {
      conn.send(JSON.stringify({ type: 'programmer.state' }))
    },

    addLayer({ lookId, templateId, targets, propertyMask, blendMode, amount, speedMasterUuid, rateSpeedMasterUuid, fadeMs }) {
      return send({
        type: 'programmer.addLayer',
        lookId,
        templateId,
        targets: targets ?? [],
        propertyMask,
        blendMode,
        amount,
        speedMasterUuid,
        rateSpeedMasterUuid,
        fadeMs,
      })
    },
    removeLayer(layerId, fadeMs) {
      send({ type: 'programmer.removeLayer', layerId, fadeMs })
    },
    moveLayer(layerId, toIndex) {
      send({ type: 'programmer.moveLayer', layerId, toIndex })
    },
    patchLayer(layerId, patch) {
      send({ type: 'programmer.patchLayer', layerId, ...patch })
    },

    subscribe(fn) {
      const id = nextSubscriptionId++
      stateSubscriptions.set(id, fn)
      return { unsubscribe: () => { stateSubscriptions.delete(id) } }
    },
    subscribeToKey(targetKey, propertyName, fn) {
      const key = programmerKey(targetKey, propertyName)
      const id = nextSubscriptionId++
      let subs = keySubscriptions.get(key)
      if (!subs) {
        subs = new Map()
        keySubscriptions.set(key, subs)
      }
      subs.set(id, fn)
      return {
        unsubscribe: () => {
          const current = keySubscriptions.get(key)
          if (!current) return
          current.delete(id)
          // Drop the bucket once empty — a long session drags the selection across the
          // whole rig, and leaking one Map per (fixture, property) adds up.
          if (current.size === 0) keySubscriptions.delete(key)
        },
      }
    },
    subscribeToErrors(fn) {
      const id = nextSubscriptionId++
      errorSubscriptions.set(id, fn)
      return { unsubscribe: () => { errorSubscriptions.delete(id) } }
    },
  }
}
