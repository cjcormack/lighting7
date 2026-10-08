import { useRef, useMemo, useSyncExternalStore, useCallback } from 'react'
import { getChannelValue, subscribeToChannels } from './usePropertyValues'
import { useChannelSource } from './useChannelSource'
import { colourFactor } from './useNormalizedIntensity'
import { foldChannels } from '../lib/colourMath'
import { aggregateCellValue } from '../components/fixtures-list/useRowValues'
import { outputChannelSource, type ChannelSource } from '../api/channelSource'
import type { CellResolution } from '../components/fixtures-list/columns'
import type { ChannelRef, PropertyCategory } from '../store/fixtures'
import type {
  GroupSliderPropertyDescriptor,
  GroupColourPropertyDescriptor,
} from '../api/groupsApi'

// `channelKey` / `getChannelValue` / `subscribeToChannels` used to be private copies here.
// They now come from usePropertyValues, so there is one place to thread a ChannelSource
// through rather than three.

// === Aggregation ===

// The min/max, component averaging, uniformity, swatch and pad-axis maths used to be written a
// second time in this file, and the two copies had already diverged over extended emitters.
// There is now one: `aggregateCellValue`, which the fixtures table also uses. These hooks
// project their group descriptors into its `CellResolution` shape — one resolution per member,
// which is exactly what a *group row* in that table already resolves to — and layer their own
// presentation (display text, per-member arrays, the stage beam) on top of its verdict.

type Resolutions = NonNullable<CellResolution>[]

/**
 * Resolutions cached on the descriptor object. Descriptors arrive as a fresh parse per fetch,
 * so this is keyed by identity rather than by content, and it keeps the imperative stage path
 * ([computeGroupColourValues], re-run on every channel batch) from rebuilding them per frame.
 */
const resolutionCache = new WeakMap<object, Resolutions>()

function cachedResolutions(key: object, build: () => Resolutions): Resolutions {
  const hit = resolutionCache.get(key)
  if (hit) return hit
  const built = build()
  resolutionCache.set(key, built)
  return built
}

// The group descriptors type `category` as a plain `string`, but it carries the same backend
// vocabulary as the per-fixture descriptors — the widened type is an accident of the group DTO,
// not a different domain.
const asCategory = (category: string) => category as PropertyCategory

function sliderResolutions(property: GroupSliderPropertyDescriptor): Resolutions {
  return cachedResolutions(property, () =>
    property.memberChannels.map((channel) => ({
      kind: 'slider',
      property: {
        type: 'slider',
        name: property.name,
        displayName: property.displayName,
        category: asCategory(property.category),
        channel,
        min: property.min,
        max: property.max,
      },
    })),
  )
}

function colourResolutions(property: GroupColourPropertyDescriptor): Resolutions {
  return cachedResolutions(property, () =>
    property.memberColourChannels.map((m) => ({
      kind: 'colour',
      property: {
        type: 'colour',
        name: property.name,
        displayName: property.displayName,
        category: 'colour',
        redChannel: m.redChannel,
        greenChannel: m.greenChannel,
        blueChannel: m.blueChannel,
        whiteChannel: m.whiteChannel,
        amberChannel: m.amberChannel,
        uvChannel: m.uvChannel,
      },
    })),
  )
}

/**
 * A reader bound to one channel source, in the shape [aggregateCellValue] takes, that consults
 * the source at most once per channel.
 *
 * The memo is the point. These hooks need the raw per-member values *as well as* the aggregate —
 * the display arrays, and the stage's per-pixel colours — and `aggregateCellValue` pulls through
 * a callback rather than reading a table, so both passes ask for the same channels. Reading
 * twice would be correct (one synchronous call, one source) but wasteful on the path that most
 * needs not to be: [computeGroupColourValues] runs on the stage's per-channel-batch path, where
 * `outputChannelSource.get` mints a lookup key per call.
 */
function readerFor(source: ChannelSource): (ref: ChannelRef) => number {
  const seen = new Map<number, number>()
  return (ref) => {
    // DMX channel numbers are 1..512, so (universe, channelNo) packs into one number and the
    // memo itself allocates no keys.
    const key = ref.universe * 1024 + ref.channelNo
    const hit = seen.get(key)
    if (hit !== undefined) return hit
    const value = getChannelValue(ref, source)
    seen.set(key, value)
    return value
  }
}

// === Slider Group Values ===

export type GroupSliderValueResult = {
  min: number
  max: number
  isUniform: boolean
  displayText: string
}

// Empty-group results are module constants rather than fresh literals: `useSyncExternalStore`
// compares snapshots by identity, and a memberless group would otherwise hand it a new object
// on every read.
const EMPTY_SLIDER_RESULT: GroupSliderValueResult = {
  min: 0,
  max: 0,
  isUniform: true,
  displayText: '0%',
}

/**
 * Hook to get aggregated slider values from all group members.
 * Returns min, max, and whether all values are uniform.
 */
export function useGroupSliderValues(
  property: GroupSliderPropertyDescriptor
): GroupSliderValueResult {
  const cachedRef = useRef<GroupSliderValueResult | null>(null)
  const source = useChannelSource()

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels(property.memberChannels, callback, source),
    [property.memberChannels, source]
  )

  const getSnapshot = useCallback((): GroupSliderValueResult => {
    const read = readerFor(source)
    const aggregate = aggregateCellValue(sliderResolutions(property), read)
    if (aggregate?.kind !== 'slider') return EMPTY_SLIDER_RESULT
    const { min, max, isUniform } = aggregate

    // Check if values changed
    const cached = cachedRef.current
    if (cached && cached.min === min && cached.max === max && cached.isUniform === isUniform) {
      return cached
    }

    // Format display text
    const minPct = Math.round((min / 255) * 100)
    const maxPct = Math.round((max / 255) * 100)
    const displayText = isUniform ? `${minPct}%` : `${minPct}-${maxPct}%`

    const result = { min, max, isUniform, displayText }
    cachedRef.current = result
    return result
  }, [property, source])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

// === Colour Group Values ===

export type GroupColourValueResult = {
  isUniform: boolean
  displayText: string
  // Average/representative values for display
  avgR: number
  avgG: number
  avgB: number
  avgW?: number
  avgA?: number
  avgUv?: number
  combinedCss: string
  // Aggregate beam representation for the single stage beam over all elements:
  // intensity-weighted hue (saturation-preserving — a plain RGB average of a
  // red+blue bar muddies to grey) + peak-blended level (a plain mean makes one
  // bright pixel on a dark bar near-invisible). 0..255 hue, 0..1 level.
  beamR: number
  beamG: number
  beamB: number
  beamIntensity: number
  // Individual member values
  members: Array<{
    fixtureKey: string
    r: number
    g: number
    b: number
    w?: number
    a?: number
    uv?: number
  }>
}

const EMPTY_COLOUR_RESULT: GroupColourValueResult = {
  isUniform: true,
  displayText: 'No members',
  avgR: 0,
  avgG: 0,
  avgB: 0,
  combinedCss: 'rgb(0, 0, 0)',
  beamR: 0,
  beamG: 0,
  beamB: 0,
  beamIntensity: 0,
  members: [],
}

/**
 * Pure computation behind [useGroupColourValues] — reads live DMX for every
 * member and returns per-member colours plus the aggregate beam hue/level.
 * Exported so the 3D stage can recompute the same values imperatively from its
 * channel subscription, bypassing React render/effect on the hot path.
 *
 * `source` defaults to the wire; the stage views pass the source their vis-source
 * selection resolved to.
 *
 * The swatch half (`avg*`, `isUniform`, `combinedCss`) is [aggregateCellValue]'s verdict, so
 * the group card and the fixtures table agree. **The beam half below is deliberately not** —
 * it is not an average at all, and unifying it with the swatch would change what the stage
 * paints. See the comment on the loop.
 */
export function computeGroupColourValues(
  property: GroupColourPropertyDescriptor,
  source: ChannelSource = outputChannelSource
): GroupColourValueResult {
  const read = readerFor(source)
  const members = property.memberColourChannels.map((m) => ({
    fixtureKey: m.fixtureKey,
    r: read(m.redChannel),
    g: read(m.greenChannel),
    b: read(m.blueChannel),
    w: m.whiteChannel ? read(m.whiteChannel) : undefined,
    a: m.amberChannel ? read(m.amberChannel) : undefined,
    uv: m.uvChannel ? read(m.uvChannel) : undefined,
  }))

  const aggregate = aggregateCellValue(colourResolutions(property), read)
  if (aggregate?.kind !== 'colour') return EMPTY_COLOUR_RESULT

  const { r: avgR, g: avgG, b: avgB, w: avgW, a: avgA, uv: avgUv, isUniform } = aggregate
  const displayText = isUniform ? `R:${avgR} G:${avgG} B:${avgB}` : 'Mixed'
  const combinedCss = aggregate.combinedCss

  // Aggregate beam: intensity-weight each pixel's hue by its own brightness
  // (iₖ = brightest emitter / 255, counting white/amber/UV) so bright pixels
  // dominate and dim ones don't drag toward grey. The hue is folded first so an
  // amber/UV-only bar contributes its warm/violet colour to the beam instead of
  // reading as black. Level blends mean with peak so a sparse-but-bright bar
  // still throws a visible beam.
  //
  // This stays here rather than moving into the shared aggregation: the swatch answers "what
  // are these heads set to", and a per-emitter mean is the honest answer to that, while the
  // beam answers "what does this bar throw", where a mean is the wrong shape in both terms —
  // it muddies a red+blue bar to grey and makes one bright pixel on a dark bar invisible. Two
  // questions, two derivations, deliberately.
  let weight = 0
  let peak = 0
  let wr = 0
  let wg = 0
  let wb = 0
  for (const m of members) {
    const ik = colourFactor(m.r, m.g, m.b, m.w, m.a, m.uv)
    weight += ik
    if (ik > peak) peak = ik
    const f = foldChannels(m.r, m.g, m.b, m.w, m.a, m.uv)
    wr += ik * f.r
    wg += ik * f.g
    wb += ik * f.b
  }
  const lit = weight > 1e-4
  const beamR = lit ? Math.round(wr / weight) : 0
  const beamG = lit ? Math.round(wg / weight) : 0
  const beamB = lit ? Math.round(wb / weight) : 0
  const beamIntensity = lit ? Math.max(weight / members.length, peak * 0.6) : 0

  return {
    isUniform,
    displayText,
    avgR,
    avgG,
    avgB,
    avgW,
    avgA,
    avgUv,
    combinedCss,
    beamR,
    beamG,
    beamB,
    beamIntensity,
    members,
  }
}

// The aggregates above are permutation-invariant over `members` (two heads swapping colours
// leaves every avg/beam field unchanged), but `members` is exactly what `MultiPixelAppearance`
// maps into per-pixel stage segments — so a colour chase needs its own, elementwise, compare.
function colourMembersEqual(
  a: GroupColourValueResult['members'],
  b: GroupColourValueResult['members'],
): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    const x = a[i]
    const y = b[i]
    if (x.r !== y.r || x.g !== y.g || x.b !== y.b || x.w !== y.w || x.a !== y.a || x.uv !== y.uv) {
      return false
    }
  }
  return true
}

/**
 * Hook to get aggregated colour values from all group members.
 */
export function useGroupColourValues(
  property: GroupColourPropertyDescriptor
): GroupColourValueResult {
  const cachedRef = useRef<GroupColourValueResult | null>(null)
  const source = useChannelSource()

  const allChannels = useMemo(() => {
    const channels: ChannelRef[] = []
    property.memberColourChannels.forEach((m) => {
      channels.push(m.redChannel, m.greenChannel, m.blueChannel)
      if (m.whiteChannel) channels.push(m.whiteChannel)
      if (m.amberChannel) channels.push(m.amberChannel)
      if (m.uvChannel) channels.push(m.uvChannel)
    })
    return channels
  }, [property.memberColourChannels])

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels(allChannels, callback, source),
    [allChannels, source]
  )

  const getSnapshot = useCallback((): GroupColourValueResult => {
    const result = computeGroupColourValues(property, source)

    // Return the cached object identity when nothing observable changed, so
    // useSyncExternalStore doesn't re-render on equal-but-fresh snapshots.
    const cached = cachedRef.current
    if (
      cached &&
      cached.avgR === result.avgR &&
      cached.avgG === result.avgG &&
      cached.avgB === result.avgB &&
      cached.avgW === result.avgW &&
      cached.avgA === result.avgA &&
      cached.avgUv === result.avgUv &&
      cached.isUniform === result.isUniform &&
      cached.beamR === result.beamR &&
      cached.beamG === result.beamG &&
      cached.beamB === result.beamB &&
      cached.beamIntensity === result.beamIntensity &&
      colourMembersEqual(cached.members, result.members)
    ) {
      return cached
    }

    cachedRef.current = result
    return result
  }, [property, source])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
