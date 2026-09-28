import { lightingApi } from './lightingApi'
import {
  buildProgrammerChannelMap,
  type DescriptorsByTarget,
  type ResolvableEntry,
  type SidebandChannel,
} from '../lib/programmerChannels'
import type { Subscription } from './subscription'
import { createKeyedWsSubscribable } from './wsSubscriptionFactory'

/**
 * A read-only source of DMX channel values, so a stage view can be pointed at something other
 * than the wire.
 *
 * The stage renders final merged output, which is the wrong picture in Blind — the programmer is
 * gated out of the merge, so the operator builds a look they cannot see. Rather than teaching every
 * reader about the programmer, the readers take a `ChannelSource` and the *source* decides what a
 * channel is worth. See `docs/stage-vis-engineering.md`.
 *
 * Deliberately no `getAll()`. `FixtureModel`'s per-frame beam director reads ~13 channels by key,
 * and a source that had to compose a merged map would allocate one per fixture per frame;
 * [ChannelSource.getByKey] keeps every source O(1) with no allocation.
 */
export interface ChannelSource {
  get(universe: number, channelNo: number): number
  /** Value for a `"universe:channelNo"` key; 0 when this source doesn't hold the channel. */
  getByKey(key: string): number
  subscribeToChannel(key: string, fn: (value: number) => void): Subscription
}

/**
 * The wire: final merged DMX as the desk is transmitting it.
 *
 * `channelsApi.getAll()` hands back its own live map rather than a copy, so reading through it per
 * key is a plain map lookup.
 */
export const outputChannelSource: ChannelSource = {
  get: (universe, channelNo) => lightingApi.channels.get(universe, channelNo),
  getByKey: (key) => lightingApi.channels.getAll().get(key) ?? 0,
  subscribeToChannel: (key, fn) => lightingApi.channels.subscribeToChannel(key, fn),
}

/** A [ChannelSource] backed by a map this module owns, rather than by the wire. */
export interface DerivedChannelSource extends ChannelSource {
  /**
   * Whether this source has an opinion about the channel at all.
   *
   * Distinct from `getByKey(key) !== 0`, and the distinction is load-bearing: the programmer
   * legitimately holds a dimmer *at* 0, and treating that as "no opinion" would let the wire value
   * show through in exactly the case blind most needs to preview.
   */
  holds(key: string): boolean
  /** Recompute from the underlying state and notify whichever channels changed. */
  refresh(): void
  /** Drop upstream subscriptions. */
  dispose(): void
}

/**
 * Per-key fan-out for the derived sources.
 *
 * Notifying only the channels whose value actually moved is the whole point: a stage may hold
 * hundreds of subscriptions, and waking all of them on every programmer event would make the
 * per-channel split pointless — the same reasoning as `diffSignatures` in `programmerWsApi.ts`.
 *
 * The pooling, the pruning and the identity check that makes a repeated unsubscribe safe all live
 * in [createKeyedWsSubscribable]; what stays here is the only part that is about channels — which
 * keys a pair of value maps says have moved. (`channelsApi.subscribeToChannel` still has its own
 * unpruned map: it is keyed by wire channel rather than by whatever a stage is drawing, so it is
 * bounded by the patch and never grows past it.)
 */
function createFanOut() {
  const byChannel = createKeyedWsSubscribable<number>()

  return {
    subscribe: byChannel.subscribe,
    /**
     * Notify every key whose value *or presence* differs between the two maps.
     *
     * Presence matters as much as value, because the overlay source dispatches on `holds`, not on
     * the value: a channel the programmer newly holds **at 0** reads 0 either way in the
     * programmer-only source, but in the overlay it changes from the wire value to 0. Comparing
     * values alone treats absent as 0, sees no change, and never wakes anyone — so a cue holding a
     * dimmer at full would keep painting full after the operator zeroed it in a blind programmer,
     * which is the exact case this feature exists to preview.
     */
    notifyChanged(before: ReadonlyMap<string, number>, after: ReadonlyMap<string, number>) {
      for (const [key, value] of after) {
        if (!before.has(key) || before.get(key) !== value) byChannel.notify(key, value)
      }
      // A key that vanished has dropped to 0. An entry being cleared is exactly as visible a
      // change as one being set, and a subscriber that never hears about it keeps painting a
      // value the programmer no longer holds.
      for (const key of before.keys()) {
        if (!after.has(key)) byChannel.notify(key, 0)
      }
    },
  }
}

/** The programmer state this module needs — narrowed so tests needn't build a whole api. */
export interface ProgrammerChannelState {
  entries: ReadonlyMap<string, ResolvableEntry>
  channels: readonly SidebandChannel[]
}

export interface ProgrammerLike {
  getState(): ProgrammerChannelState
  subscribe(fn: () => void): Subscription
}

/**
 * What the programmer alone is holding, as channel values.
 *
 * A channel the programmer doesn't hold reads **0**, which is the agreed reading of "programmer
 * only": an empty programmer shows an empty stage, and a fixture given intensity but no position
 * sits at pan/tilt 0 rather than borrowing a position from the wire.
 *
 * `getDescriptors` is a callback rather than a value because the patch can change under a live
 * stage; call [DerivedChannelSource.refresh] once it starts returning something new.
 *
 * Latency: `programmer.state` lands on the backend's 100 ms provenance debounce, but the api echoes
 * our *own* writes into `entries` optimistically (`applyLocalEntry`), so an operator's fader drag
 * previews at input rate.
 */
export function createProgrammerChannelSource(
  programmer: ProgrammerLike,
  getDescriptors: () => DescriptorsByTarget,
): DerivedChannelSource {
  const fanOut = createFanOut()
  let values = new Map<string, number>()
  let lastEntries: ProgrammerChannelState['entries'] | null = null
  let lastChannels: ProgrammerChannelState['channels'] | null = null

  /**
   * `force` is what tells a subscription notification apart from a [DerivedChannelSource.refresh].
   *
   * The programmer notifies on every frame it handles — `includeTarget`, `layerState`,
   * `blindState`, and provenance at up to 10/s — but only ever *reassigns* `entries` and
   * `channels`, never mutates them. So identity on both is an exact test for "nothing here can
   * have changed a channel", and skipping those frames avoids a full parse-and-descriptor-scan
   * per entry plus a walk of old and new in `notifyChanged`. That ran app-wide, because the
   * always-mounted stage overview panel held this source.
   *
   * `refresh` must force: it exists precisely for the case where the *descriptors* changed under
   * an unchanged programmer state, which no identity check here could see.
   */
  const rebuild = (force: boolean) => {
    const state = programmer.getState()
    if (!force && state.entries === lastEntries && state.channels === lastChannels) return
    lastEntries = state.entries
    lastChannels = state.channels
    const next = buildProgrammerChannelMap(state.entries.values(), state.channels, getDescriptors())
    const before = values
    values = next
    fanOut.notifyChanged(before, next)
  }

  const upstream = programmer.subscribe(() => rebuild(false))
  rebuild(true)

  return {
    get: (universe, channelNo) => values.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => values.get(key) ?? 0,
    holds: (key) => values.has(key),
    subscribeToChannel: fanOut.subscribe,
    refresh: () => rebuild(true),
    dispose: () => upstream.unsubscribe(),
  }
}

/** One channel of a pushed look, as the preview endpoint reports it. */
export interface PushedChannel {
  universe: number
  channel: number
  value: number
}

/** A [ChannelSource] whose values are handed to it, rather than derived from a subscription. */
export interface PushChannelSource extends ChannelSource {
  /** Whether this source has an opinion about the channel — see [DerivedChannelSource.holds]. */
  holds(key: string): boolean
  /** Replace the whole map, notifying every channel whose value *or presence* moved. */
  setChannels(channels: Iterable<PushedChannel>): void
}

/**
 * A source fed from outside — the Next GO preview.
 *
 * Not a [DerivedChannelSource]: there is no upstream to subscribe to and nothing to recompute, so
 * `refresh` and `dispose` would be meaningless. It carries `holds` because that is what
 * [createOverlayChannelSource] dispatches on, and the distinction matters more here than anywhere:
 * the preview endpoint omits channels no cue asserts rather than reporting them as 0, so a channel
 * this source doesn't hold must fall back to the wire. Treating absent as 0 would black out every
 * fixture the next cue doesn't touch.
 */
export function createPushChannelSource(): PushChannelSource {
  const fanOut = createFanOut()
  let values = new Map<string, number>()

  return {
    get: (universe, channelNo) => values.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => values.get(key) ?? 0,
    holds: (key) => values.has(key),
    subscribeToChannel: fanOut.subscribe,
    setChannels(channels) {
      const next = new Map<string, number>()
      for (const c of channels) next.set(`${c.universe}:${c.channel}`, c.value)
      const before = values
      values = next
      fanOut.notifyChanged(before, next)
    },
  }
}

/**
 * `overlay` where it holds a channel, `base` everywhere else.
 *
 * This is "Output + Programmer": identical to Output unless Blind is engaged, because outside blind
 * the programmer's values are already *in* the merged output. Which is why the menu says so —
 * otherwise it reads as a setting that does nothing.
 */
export function createOverlayChannelSource(
  base: ChannelSource,
  overlay: Pick<DerivedChannelSource, 'getByKey' | 'holds' | 'subscribeToChannel'>,
): ChannelSource {
  const valueFor = (key: string, fallback: () => number) =>
    overlay.holds(key) ? overlay.getByKey(key) : fallback()

  return {
    get: (universe, channelNo) =>
      valueFor(`${universe}:${channelNo}`, () => base.get(universe, channelNo)),
    getByKey: (key) => valueFor(key, () => base.getByKey(key)),
    // Both upstreams matter: the overlay changing reveals or hides a value, and the base changing
    // shows through wherever the overlay is silent.
    subscribeToChannel: (key, fn) => {
      const notify = () => fn(valueFor(key, () => base.getByKey(key)))
      const overlaySub = overlay.subscribeToChannel(key, notify)
      const baseSub = base.subscribeToChannel(key, notify)
      return {
        unsubscribe: () => {
          overlaySub.unsubscribe()
          baseSub.unsubscribe()
        },
      }
    },
  }
}
