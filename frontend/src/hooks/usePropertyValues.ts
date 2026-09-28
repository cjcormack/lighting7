import { useRef, useMemo, useSyncExternalStore, useCallback } from 'react'
import { lightingApi } from '../api/lightingApi'
import { computeCombinedCss } from '../lib/colourMath'
import { serializeLevel } from '../lib/programmerValue'
import { outputChannelSource, type ChannelSource } from '../api/channelSource'
import { useChannelSource } from './useChannelSource'
import type {
  ChannelRef,
  SliderPropertyDescriptor,
  ColourPropertyDescriptor,
  PositionPropertyDescriptor,
  SettingPropertyDescriptor,
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
 * This and [subscribeToChannels] are the single home for the two operations: `useGroupPropertyValues`
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
export function useSliderValue(property: SliderPropertyDescriptor): number {
  const source = useChannelSource()
  // ChannelRef is exactly {universe, channelNo}, so these two fully identify the
  // channel. Keying off them rather than the enclosing descriptor avoids
  // resubscribing every time a caller hands over a fresh property object.
  const { universe, channelNo } = property.channel

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
export function useColourValue(property: ColourPropertyDescriptor): ColourValueResult {
  const source = useChannelSource()
  const cachedRef = useRef<ColourValueResult | null>(null)

  const channels = useMemo(() => {
    const result: ChannelRef[] = [
      property.redChannel,
      property.greenChannel,
      property.blueChannel,
    ]
    if (property.whiteChannel) result.push(property.whiteChannel)
    if (property.amberChannel) result.push(property.amberChannel)
    if (property.uvChannel) result.push(property.uvChannel)
    return result
  }, [property])

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels(channels, callback, source),
    [channels, source]
  )

  const getSnapshot = useCallback((): ColourValueResult => {
    const r = getChannelValue(property.redChannel, source)
    const g = getChannelValue(property.greenChannel, source)
    const b = getChannelValue(property.blueChannel, source)
    const w = property.whiteChannel ? getChannelValue(property.whiteChannel, source) : undefined
    const a = property.amberChannel ? getChannelValue(property.amberChannel, source) : undefined
    const uv = property.uvChannel ? getChannelValue(property.uvChannel, source) : undefined

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
  }, [property, source])

  const liveResult = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return liveResult
}

type PositionValueResult = {
  pan: number
  tilt: number
  panNormalized: number
  tiltNormalized: number
}

/**
 * Hook to get a position property's pan/tilt values
 */
export function usePositionValue(property: PositionPropertyDescriptor): PositionValueResult {
  const source = useChannelSource()
  const cachedRef = useRef<PositionValueResult | null>(null)

  const channels = useMemo(
    () => [property.panChannel, property.tiltChannel],
    [property.panChannel, property.tiltChannel]
  )

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels(channels, callback, source),
    [channels, source]
  )

  const getSnapshot = useCallback((): PositionValueResult => {
    const pan = getChannelValue(property.panChannel, source)
    const tilt = getChannelValue(property.tiltChannel, source)

    // Check if values changed
    const cached = cachedRef.current
    if (cached && cached.pan === pan && cached.tilt === tilt) {
      return cached
    }

    // Normalize to 0-1 range for display
    const panRange = property.panMax - property.panMin
    const tiltRange = property.tiltMax - property.tiltMin
    const panNormalized = panRange > 0 ? (pan - property.panMin) / panRange : 0.5
    const tiltNormalized = tiltRange > 0 ? (tilt - property.tiltMin) / tiltRange : 0.5

    const result = { pan, tilt, panNormalized, tiltNormalized }
    cachedRef.current = result
    return result
  }, [property, source])

  const liveResult = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return liveResult
}

type SettingValueResult = {
  level: number
  option?: SettingPropertyDescriptor['options'][number]
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
export function useSettingValue(property: SettingPropertyDescriptor): SettingValueResult {
  const source = useChannelSource()
  const cachedRef = useRef<SettingValueResult | null>(null)
  // See useSliderValue: the two ChannelRef fields fully identify the channel.
  const { universe, channelNo } = property.channel

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels([{ universe, channelNo }], callback, source),
    [universe, channelNo, source]
  )

  const getSnapshot = useCallback((): SettingValueResult => {
    const level = getChannelValue(property.channel, source)

    // Check if value changed
    const cached = cachedRef.current
    if (cached && cached.level === level) {
      return cached
    }

    const result = { level, option: resolveSettingOption(property.options, level) }
    cachedRef.current = result
    return result
  }, [property, source])

  const liveResult = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return liveResult
}

/**
 * Names the property a channel write really belongs to, so the live branch can write the
 * programmer at property level instead of channel level.
 */
export interface PropertyWriteTarget {
  fixtureKey: string
  propertyName: string
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
    (channel: ChannelRef, value: number, target?: PropertyWriteTarget) => {
      if (target) {
        lightingApi.programmer.set(
          'fixture',
          target.fixtureKey,
          target.propertyName,
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
 * Update a fixture's position as one programmer entry rather than two channel writes.
 *
 * Only valid for a real `position` descriptor. A fixture whose movement is two independent
 * pan/tilt *sliders* must keep writing them separately (via [useUpdateChannel] with each
 * slider's own property name) — lifting one axis into a `position` entry would freeze the
 * other, which is exactly why the backend routes raw pan/tilt writes to its channel
 * sideband.
 */
export function useUpdateFixturePosition(
  property: PositionPropertyDescriptor,
  fixtureKey: string | undefined,
) {
  return useCallback(
    (pan: number, tilt: number) => {
      if (!fixtureKey) {
        lightingApi.channels.update(property.panChannel.universe, property.panChannel.channelNo, pan)
        lightingApi.channels.update(property.tiltChannel.universe, property.tiltChannel.channelNo, tilt)
        return
      }
      lightingApi.programmer.setPosition('fixture', fixtureKey, Math.round(pan), Math.round(tilt))
    },
    [fixtureKey, property]
  )
}

/**
 * Update all colour channels of a fixture-level colour property. In look mode writes go to the
 * local draft keyed by `property.name`, with W/A/UV serialised into the extended-colour suffix.
 * Mirrors [useUpdateGroupColour].
 */
export function useUpdateFixtureColour(
  property: ColourPropertyDescriptor,
  fixtureKey: string | undefined,
) {
  return useCallback(
    (r: number, g: number, b: number, w?: number, a?: number, uv?: number) => {
      if (fixtureKey) {
        // One programmer entry for the whole colour, extended channels included. Writing
        // the components separately would make each one a distinct write that freezes its
        // siblings, which is what the raw-channel shim has to do and what we're avoiding.
        lightingApi.programmer.setColour('fixture', fixtureKey, property.name, {
          r,
          g,
          b,
          w: property.whiteChannel ? w : undefined,
          a: property.amberChannel ? a : undefined,
          uv: property.uvChannel ? uv : undefined,
        })
        return
      }
      lightingApi.channels.update(property.redChannel.universe, property.redChannel.channelNo, r)
      lightingApi.channels.update(property.greenChannel.universe, property.greenChannel.channelNo, g)
      lightingApi.channels.update(property.blueChannel.universe, property.blueChannel.channelNo, b)
      if (property.whiteChannel && w !== undefined) {
        lightingApi.channels.update(property.whiteChannel.universe, property.whiteChannel.channelNo, w)
      }
      if (property.amberChannel && a !== undefined) {
        lightingApi.channels.update(property.amberChannel.universe, property.amberChannel.channelNo, a)
      }
      if (property.uvChannel && uv !== undefined) {
        lightingApi.channels.update(property.uvChannel.universe, property.uvChannel.channelNo, uv)
      }
    },
    [fixtureKey, property]
  )
}

/**
 * Hook to get the colourPreview from a setting's current option (if it has one)
 */
export function useSettingColourPreview(property: SettingPropertyDescriptor): string | undefined {
  const { option } = useSettingValue(property)
  return option?.colourPreview
}
