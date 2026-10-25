import { useRef, useMemo, useSyncExternalStore, useCallback } from 'react'
import { lightingApi } from '../api/lightingApi'
import { computeCombinedCss } from '../lib/colourMath'
import { serializeLevel } from '../lib/programmerValue'
import { outputChannelSource, type ChannelSource } from '../api/channelSource'
import { useChannelSource } from './useChannelSource'
import type {
  ChannelRef,
  SliderAttributeDescriptor,
  ColourAttributeDescriptor,
  SettingAttributeDescriptor,
} from '../store/fixtures'

// Helper to create channel key — must match the store's Map keys.
export function channelKey(ref: ChannelRef): string {
  return `${ref.universe}:${ref.channelNo}`
}

/**
 * Read one channel from `source`.
 *
 * The default is the wire, so every caller with no interest in vis sources — the fixtures sheet,
 * the busking pads, the cue editor — keeps reading real output unchanged. The stage views pass
 * whatever their [useChannelSource] context gives them. See `docs/stage-vis-engineering.md`.
 *
 * This and [subscribeToChannels] are the single home for the two operations: `useGroupAttributeValues`
 * and `useVirtualDimmer` used to keep private copies, which would have meant three places to thread
 * a source through.
 */
export function getChannelValue(
  channel: ChannelRef,
  source: ChannelSource = outputChannelSource
): number {
  return source.get(channel.universe, channel.channelNo)
}

/**
 * Subscribe to a *set* of channels, waking the caller at most once per batch.
 *
 * Every source notifies per channel — `channelsApi` walks its 33 ms debounced batch key by key,
 * and `createFanOut.notifyChanged` walks its changed keys — so a naive per-channel registration
 * fires the same callback k times for a batch that moved k of the set's channels. Each of those
 * wake-ups reruns a `useSyncExternalStore` snapshot that reads *all* k channels, so a row's cost
 * is O(k²) per batch even though nothing renders more than once (the snapshot compare absorbs it).
 * That is cheap for an ordinary fixture row and expensive for the collapsed multi-head bars and
 * group rows, where k runs to the hundreds.
 *
 * Coalescing on a microtask fixes it above every source rather than in each one: a source's fan-out
 * is synchronous within one batch, so a microtask queued by the first notification runs once the
 * whole batch has drained. Per-channel granularity is untouched — the set still hears only about
 * its own channels, and `getSnapshot` reads current values, so a coalesced wake-up sees everything
 * the batch did.
 *
 * A single-channel set takes the direct path: there is nothing to coalesce (one channel can notify
 * at most once per batch), and it keeps the common `useSliderValue` / `useSettingValue` case
 * synchronous and allocation-free.
 */
export function subscribeToChannels(
  channels: ChannelRef[],
  callback: () => void,
  source: ChannelSource = outputChannelSource
): () => void {
  if (channels.length <= 1) {
    const subscriptions = channels.map((ch) => source.subscribeToChannel(channelKey(ch), callback))
    return () => subscriptions.forEach((sub) => sub.unsubscribe())
  }

  let unsubscribed = false
  let scheduled = false
  const notify = () => {
    if (scheduled) return
    scheduled = true
    queueMicrotask(() => {
      scheduled = false
      // A batch can land in the same task as the unsubscribe — during a React commit that swaps
      // one subscription for another, say — and calling back after teardown would wake a store
      // the caller has already stopped listening to.
      if (!unsubscribed) callback()
    })
  }

  const subscriptions = channels.map((ch) => source.subscribeToChannel(channelKey(ch), notify))

  return () => {
    unsubscribed = true
    subscriptions.forEach((sub) => sub.unsubscribe())
  }
}

/**
 * One raw DMX channel's live value, straight off the wire.
 *
 * The rest of this module reads channels through a fixture's property descriptor. Three surfaces
 * hold a bare `{universe, channelNo}` instead — the /channels debug grid, the fixture sheet's
 * per-channel sliders and the channel dialog — and they used to read it through an RTK Query cache
 * entry per channel, whose `onCacheEntryAdded` turned every frame into a Redux dispatch. That put
 * the store in a path that moves at up to 30 Hz per mounted slider for a value nothing else in the
 * tree consumes; reading the same subscription directly keeps the per-channel split and takes
 * Redux out of it. `channelsApi`'s batching and the write path are unchanged.
 *
 * `null` stands in for the query's `skip` — a hook can't be skipped — and reads 0 while
 * subscribing to nothing, for a caller whose channel isn't a number yet.
 *
 * Deliberately at the wire source rather than `useChannelSource()`: every caller is a live editing
 * control, so it must show the channel it writes even while a stage view previews something else.
 * Same reasoning as `useVirtualDimmer`.
 */
export function useChannelValue(channel: ChannelRef | null): number {
  const universe = channel?.universe ?? null
  const channelNo = channel?.channelNo ?? null

  const subscribe = useCallback(
    (callback: () => void) =>
      universe === null || channelNo === null
        ? () => {}
        : subscribeToChannels([{ universe, channelNo }], callback),
    [universe, channelNo]
  )

  const getSnapshot = useCallback(
    () => (universe === null || channelNo === null ? 0 : getChannelValue({ universe, channelNo })),
    [universe, channelNo]
  )

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

/**
 * Hook to get a slider property's current value
 */
export function useSliderValue(attribute: SliderAttributeDescriptor): number {
  const source = useChannelSource()
  // ChannelRef is exactly {universe, channelNo}, so these two fully identify the
  // channel. Keying off them rather than the enclosing descriptor avoids
  // resubscribing every time a caller hands over a fresh property object.
  const { universe, channelNo } = attribute.channel

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels([{ universe, channelNo }], callback, source),
    [universe, channelNo, source]
  )

  const getSnapshot = useCallback(
    () => getChannelValue({ universe, channelNo }, source),
    [universe, channelNo, source]
  )

  const liveValue = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return liveValue
}

type ColourValueResult = {
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
  css: string
  combinedCss: string
}


/**
 * Hook to get a colour property's RGB values
 */
export function useColourValue(attribute: ColourAttributeDescriptor): ColourValueResult {
  const source = useChannelSource()
  const cachedRef = useRef<ColourValueResult | null>(null)

  const channels = useMemo(() => {
    const result: ChannelRef[] = [
      attribute.redChannel,
      attribute.greenChannel,
      attribute.blueChannel,
    ]
    if (attribute.whiteChannel) result.push(attribute.whiteChannel)
    if (attribute.amberChannel) result.push(attribute.amberChannel)
    if (attribute.uvChannel) result.push(attribute.uvChannel)
    return result
  }, [attribute])

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels(channels, callback, source),
    [channels, source]
  )

  const getSnapshot = useCallback((): ColourValueResult => {
    const r = getChannelValue(attribute.redChannel, source)
    const g = getChannelValue(attribute.greenChannel, source)
    const b = getChannelValue(attribute.blueChannel, source)
    const w = attribute.whiteChannel ? getChannelValue(attribute.whiteChannel, source) : undefined
    const a = attribute.amberChannel ? getChannelValue(attribute.amberChannel, source) : undefined
    const uv = attribute.uvChannel ? getChannelValue(attribute.uvChannel, source) : undefined

    // Check if values changed
    const cached = cachedRef.current
    if (
      cached &&
      cached.r === r &&
      cached.g === g &&
      cached.b === b &&
      cached.w === w &&
      cached.a === a &&
      cached.uv === uv
    ) {
      return cached
    }

    const css = `rgb(${r}, ${g}, ${b})`
    const combinedCss = computeCombinedCss(r, g, b, w, a, uv)

    const result = { r, g, b, w, a, uv, css, combinedCss }
    cachedRef.current = result
    return result
  }, [attribute, source])

  const liveResult = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return liveResult
}

type SettingValueResult = {
  level: number
  option?: SettingAttributeDescriptor['options'][number]
}

/**
 * Match a DMX level to its display option — scans from the highest-level option down
 * and picks the first one the level clears. Shared between fixture and group setting hooks.
 */
export function resolveSettingOption<O extends { level: number }>(
  options: O[],
  level: number,
): O {
  let matchedOption = options[0]
  for (let i = options.length - 1; i >= 0; i--) {
    if (level >= options[i].level) {
      matchedOption = options[i]
      break
    }
  }
  return matchedOption
}

/**
 * Hook to get a setting property's current option
 */
export function useSettingValue(attribute: SettingAttributeDescriptor): SettingValueResult {
  const source = useChannelSource()
  const cachedRef = useRef<SettingValueResult | null>(null)
  // See useSliderValue: the two ChannelRef fields fully identify the channel.
  const { universe, channelNo } = attribute.channel

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels([{ universe, channelNo }], callback, source),
    [universe, channelNo, source]
  )

  const getSnapshot = useCallback((): SettingValueResult => {
    const level = getChannelValue(attribute.channel, source)

    // Check if value changed
    const cached = cachedRef.current
    if (cached && cached.level === level) {
      return cached
    }

    const result = { level, option: resolveSettingOption(attribute.options, level) }
    cachedRef.current = result
    return result
  }, [attribute, source])

  const liveResult = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return liveResult
}

/**
 * Names the property a channel write really belongs to, so the live branch can write the
 * programmer at property level instead of channel level.
 */
export interface AttributeWriteTarget {
  fixtureKey: string
  attribute: string
}

/**
 * Hook to update a channel value. In `kind: 'look'` mode channel-level writes are a
 * no-op — Look rows are property-keyed, not channel-keyed, and the synthetic
 * fixture's channel refs don't map to real DMX anyway.
 *
 * Live writes go to the **programmer** (Layer 2). Pass [target] whenever the caller knows
 * which property the channel backs and the write covers that property outright: the value
 * then lands as a property entry, which is what suppresses effects, carries a fade, and
 * shows up in the programmer sheet as a first-class entry.
 *
 * Without [target] the write falls back to a raw `updateChannel`. That still reaches the
 * programmer — the backend's compatibility shim lifts property-backed channels — but pays
 * the shim's coarser semantics (a colour sub-channel freezes its siblings into a whole
 * `rgbColour` entry; a pan/tilt axis lands in the channel sideband). Use the fallback only
 * where there genuinely is no single owning property, such as the raw Channels view.
 */
export function useUpdateChannel() {
  return useCallback(
    (channel: ChannelRef, value: number, target?: AttributeWriteTarget) => {
      if (target) {
        lightingApi.programmer.set(
          'fixture',
          target.fixtureKey,
          target.attribute,
          serializeLevel(value),
        )
        return
      }
      lightingApi.channels.update(channel.universe, channel.channelNo, value)
    },
    []
  )
}

/**
 * Hook to get the colourPreview from a setting's current option (if it has one)
 */
export function useSettingColourPreview(attribute: SettingAttributeDescriptor): string | undefined {
  const { option } = useSettingValue(attribute)
  return option?.colourPreview
}
