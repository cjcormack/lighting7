import { useCallback, useMemo, useSyncExternalStore, type ReactNode } from 'react'
import {
  findColourSource,
  findDimmerProperty,
  findGroupColourSource,
  findStrobeProperties,
  type ColourPropertyDescriptor,
  type Fixture,
  type FixtureTypeInfo,
  type SettingPropertyDescriptor,
  type SliderPropertyDescriptor,
} from '../../store/fixtures'
import type { GroupColourPropertyDescriptor } from '../../api/groupsApi'
import type { FixturePatch } from '../../api/patchApi'
import { findGel } from '../../lib/gels'
import { colourFilters, filterColour, fittedProperties } from '../../lib/fittedMedia'
import { useGelIndex } from '../../hooks/useGelIndex'
import {
  getChannelValue,
  subscribeToChannels,
  useColourValue,
  useSettingValue,
} from '../../hooks/usePropertyValues'
import { useChannelSource } from '../../hooks/useChannelSource'
import { strobeAnimates, strobeFactor } from '../../lib/strobeBands'
import {
  isAnimatedBand,
  settingColourAt,
  sourceBandColour,
  sourceBandLevel,
  useColourBandTime,
} from '../../lib/colourBands'
import { useGroupColourValues } from '../../hooks/useGroupPropertyValues'
import { colourFactor, useNormalizedIntensity } from '../../hooks/useNormalizedIntensity'
import { computeNormalizedHueCss } from '../../lib/colourMath'

/**
 * How lit a fixture is and in what colour, independent of how any one surface draws it.
 *
 * Both numbers are raw, not display-ready: `color` is the hue at **full** brightness and
 * `intensity` is the **linear** 0..1 level. Each medium applies its own curve — the DOM marker
 * folds `perceptualBrightness` into a box-shadow and an opacity, the 3D scene splits it (perceptual
 * on the lens, linear on the cone so it can double as the beam cull), and the busk rig's tiles draw
 * a live bar from it. A pre-baked CSS string would take that choice away, which is why `useColourAppearance`
 * is not the shape this needs: it returns only the baked string and drops the level.
 */
export interface FixtureAppearance {
  /** Full-brightness hue, either `#rrggbb` or `rgb(r, g, b)`. */
  color: string
  /** Linear 0..1 — dimmer × colour magnitude. */
  intensity: number
  /** Per-element colours for a multi-element fixture (pixel bars); absent otherwise. */
  segments?: PixelSegment[]
}

export interface PixelSegment {
  css: string
  intensity: number
}

/** The warm tungsten every surface falls back to for a colourless fixture. */
export const DEFAULT_FIXTURE_COLOUR = '#fff8d5'

/** Grey and barely lit — how every surface draws a patch with no matching fixture, so it reads
 *  as visibly *not* a live light rather than as a lamp at full. Shared with the 3D scene, which
 *  has its own dispatch over the same colour sources and must agree with the plot and markers. */
export const PLACEHOLDER_FIXTURE_COLOUR = '#666'
export const PLACEHOLDER_FIXTURE_INTENSITY = 0.2

interface FixtureAppearanceProps {
  patch: FixturePatch
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  children: (appearance: FixtureAppearance) => ReactNode
}

/**
 * Resolve a fixture's colour source and hand the resulting [FixtureAppearance] to `children`.
 *
 * A render prop rather than a hook, and that is forced rather than chosen. Each colour source needs
 * a *different* set of value hooks — `useColourValue` wants a `ColourPropertyDescriptor`,
 * `useGroupColourValues` subscribes to a variable-length channel list, and a gel fixture needs
 * neither — so they cannot be collapsed behind one hook without breaking hook order. Splitting them
 * across leaf components gives each a fixed hook set, which is the trick the old `StageMarker` already used
 * internally and the reason the 2D plot went without live colour for so long.
 */
export function FixtureAppearanceSource({
  patch,
  fixture,
  fixtureType,
  children,
}: FixtureAppearanceProps) {
  // The type's descriptors as this unit holds them: a loadable setting's options overlaid with the
  // patch's fitted media (`lib/fittedMedia.ts`), so a scroller draws the unit's own string. The 3D
  // scene makes the same overlay, per placement.
  const gels = useGelIndex()
  const properties = useMemo(
    () => fittedProperties(fixture?.properties, patch.media, gels),
    [fixture?.properties, patch.media, gels],
  )
  // The discriminator is pure, so it resolves here rather than through hooks.
  const colourSource = useMemo(
    () => (properties ? findColourSource(properties) : undefined),
    [properties],
  )
  const groupColour = useMemo(() => findGroupColourSource(fixture), [fixture])
  const dimmerProp = useMemo(
    () => findDimmerProperty(properties),
    [properties],
  )
  // `acceptsGel` matters: a gel code on a fixture whose type doesn't take gel is stale data, and
  // colouring by it would contradict both other surfaces.
  const gel =
    !colourSource && fixtureType?.acceptsGel && patch.gelCode ? findGel(gels, patch.gelCode) : null
  // The unit's colour filters — a second colour wheel, a media frame's gel, a dichroic in a wheel
  // (`colourFilters`). The 3D scene's `filteredHex` is the other copy of this step.
  const filters = useMemo(
    () => colourFilters(properties, colourSource),
    [properties, colourSource],
  )
  // The strobe channels (fixture-optics plan D12): a level factor on every leaf's answer — a closed
  // band dark, a strobe flashing under the three-flash rule (`lib/strobeBands.ts`). The 3D scene's
  // `liveStrobeFactor` is the other copy.
  const strobes = useMemo(() => findStrobeProperties(properties), [properties])

  if (!fixture) return <PlaceholderAppearance>{children}</PlaceholderAppearance>
  const emit =
    strobes.length === 0
      ? children
      : (appearance: FixtureAppearance) => (
          <StrobeGate strobes={strobes} appearance={appearance}>
            {children}
          </StrobeGate>
        )

  if (groupColour && groupColour.memberColourChannels.length > 1) {
    return (
      <MultiPixelAppearance groupColourProp={groupColour} dimmerProp={dimmerProp}>
        {emit}
      </MultiPixelAppearance>
    )
  }

  if (colourSource?.type === 'colour') {
    return (
      <ColourAppearance colourProp={colourSource.property} dimmerProp={dimmerProp} filters={filters}>
        {emit}
      </ColourAppearance>
    )
  }

  if (colourSource?.type === 'setting') {
    return (
      <SettingColourAppearance settingProp={colourSource.property} dimmerProp={dimmerProp} filters={filters}>
        {emit}
      </SettingColourAppearance>
    )
  }

  if (gel) {
    return (
      <FixedColourAppearance hex={gel.color} dimmerProp={dimmerProp} filters={filters}>
        {emit}
      </FixedColourAppearance>
    )
  }

  return (
    <FixedColourAppearance hex={DEFAULT_FIXTURE_COLOUR} dimmerProp={dimmerProp} filters={filters}>
      {emit}
    </FixedColourAppearance>
  )
}

type LeafProps = { children: (appearance: FixtureAppearance) => ReactNode }

type StrobeProperty = SliderPropertyDescriptor | SettingPropertyDescriptor

/**
 * [appearance] with its level — and every pixel's — multiplied by the strobe channels' factor at the
 * band clock's time, which ticks only while a band flashes (`useColourBandTime`). Its own component
 * so its hook set is fixed whatever leaf answered, and so a flashing strobe re-renders only this.
 */
function StrobeGate({
  strobes,
  appearance,
  children,
}: {
  strobes: readonly StrobeProperty[]
  appearance: FixtureAppearance
  children: (appearance: FixtureAppearance) => ReactNode
}) {
  const levels = useStrobeLevels(strobes)
  const read = useCallback((p: StrobeProperty) => levels[strobes.indexOf(p)] ?? 0, [levels, strobes])
  const timeS = useColourBandTime(strobeAnimates(strobes, read))
  const factor = strobeFactor(strobes, read, timeS)
  if (factor === 1) return <>{children(appearance)}</>
  return (
    <>
      {children({
        ...appearance,
        intensity: appearance.intensity * factor,
        segments: appearance.segments?.map((s) => ({ ...s, intensity: s.intensity * factor })),
      })}
    </>
  )
}

/**
 * The strobe channels' DMX values, re-rendering when any moves. The snapshot is the values joined
 * into a string, which compares by value, so an unchanged batch is the same snapshot and renders
 * nothing.
 */
function useStrobeLevels(strobes: readonly StrobeProperty[]): number[] {
  const source = useChannelSource()
  const channels = useMemo(() => strobes.map((p) => p.channel), [strobes])
  const subscribe = useCallback(
    (onChange: () => void) => subscribeToChannels(channels, onChange, source),
    [channels, source],
  )
  const getSnapshot = useCallback(
    () => channels.map((c) => getChannelValue(c, source)).join(','),
    [channels, source],
  )
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
  return useMemo(() => snapshot.split(',').map(Number), [snapshot])
}

/**
 * `hex` through each filter's current slot colour, one component per filter so each has a fixed
 * hook set (the render-prop reason above). A filter whose slot holds no colour passes it unchanged.
 */
function Filtered({
  hex,
  filters,
  children,
}: {
  hex: string
  filters: readonly SettingPropertyDescriptor[]
  children: (hex: string) => ReactNode
}) {
  if (filters.length === 0) return <>{children(hex)}</>
  return (
    <FilterStep hex={hex} filter={filters[0]} rest={filters.slice(1)}>
      {children}
    </FilterStep>
  )
}

function FilterStep({
  hex,
  filter,
  rest,
  children,
}: {
  hex: string
  filter: SettingPropertyDescriptor
  rest: readonly SettingPropertyDescriptor[]
  children: (hex: string) => ReactNode
}) {
  // The filter's slot colour, or — on a band with no single colour (the Robe's second wheel
  // scrolling) — its wheel's own colours in turn (`lib/colourBands.ts`); the 3D `filteredHex` twin.
  const colour = useSettingBandColour(filter)
  return (
    <Filtered hex={filterColour(hex, [colour])} filters={rest}>
      {children}
    </Filtered>
  )
}

/**
 * A colour setting's current band colour, ticking while the band animates (`useColourBandTime`):
 * its preview, its wheel's palette at the time for a scroll or random band, or undefined where the
 * band says nothing. The 3D dispatch reads `settingColourAt` at the scene clock's time instead.
 */
function useSettingBandColour(setting: SettingPropertyDescriptor): string | undefined {
  const { level, option } = useSettingValue(setting)
  const timeS = useColourBandTime(isAnimatedBand(option))
  return settingColourAt(setting.options, level, timeS)
}

const NO_FILTERS: readonly SettingPropertyDescriptor[] = []

function ColourAppearance({
  colourProp,
  dimmerProp,
  filters = NO_FILTERS,
  children,
}: LeafProps & {
  colourProp: ColourPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
  filters?: readonly SettingPropertyDescriptor[]
}) {
  const colour = useColourValue(colourProp)
  // Effective intensity = dimmer × colour, so a colour-only fixture at RGB 0 reads as dark
  // instead of beaming at full. The hue is normalised to full brightness so a dimmerless
  // fixture at r:20 reads as dim orange rather than near-black.
  const intensity =
    useNormalizedIntensity(dimmerProp) *
    colourFactor(colour.r, colour.g, colour.b, colour.w, colour.a, colour.uv)
  const color = computeNormalizedHueCss(
    colour.r,
    colour.g,
    colour.b,
    colour.w,
    colour.a,
    colour.uv,
  )
  return <Filtered hex={color} filters={filters}>{(hex) => children({ color: hex, intensity })}</Filtered>
}

function SettingColourAppearance({
  settingProp,
  dimmerProp,
  filters = NO_FILTERS,
  children,
}: LeafProps & {
  settingProp: SettingPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
  filters?: readonly SettingPropertyDescriptor[]
}) {
  // The band's colour — a preview, or a scroll or random band's wheel colours in turn — else open
  // white, never black for want of data; a blackout band is dark (`lib/colourBands.ts`). A dimmer at
  // 0 still wins through the dimmer factor. The 3D `SettingColourBeamSync` is the other copy.
  const colour = useSettingBandColour(settingProp)
  const intensity = useNormalizedIntensity(dimmerProp) * sourceBandLevel(colour)
  return (
    <Filtered hex={sourceBandColour(colour)} filters={filters}>
      {(hex) => children({ color: hex, intensity })}
    </Filtered>
  )
}

function FixedColourAppearance({
  hex,
  dimmerProp,
  filters = NO_FILTERS,
  children,
}: LeafProps & { hex: string; dimmerProp?: SliderPropertyDescriptor; filters?: readonly SettingPropertyDescriptor[] }) {
  // No colour channels (gel or dimmer-only), so the colour magnitude is implicitly 1 and the
  // dimmer alone is the level. A gel fixture with no dimmer reads full on by design — there is
  // no brightness signal to gate it on.
  const intensity = useNormalizedIntensity(dimmerProp)
  return <Filtered hex={hex} filters={filters}>{(filtered) => children({ color: filtered, intensity })}</Filtered>
}

function PlaceholderAppearance({ children }: LeafProps) {
  // Patch with no matching fixture — grey and barely lit, so it is visibly *not* a live light.
  return (
    <>
      {children({
        color: PLACEHOLDER_FIXTURE_COLOUR,
        intensity: PLACEHOLDER_FIXTURE_INTENSITY,
      })}
    </>
  )
}

function MultiPixelAppearance({
  groupColourProp,
  dimmerProp,
  children,
}: LeafProps & {
  groupColourProp: GroupColourPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
}) {
  const group = useGroupColourValues(groupColourProp)
  const dimmerFactor = useNormalizedIntensity(dimmerProp)
  const intensity = group.beamIntensity * dimmerFactor
  const color = computeNormalizedHueCss(group.beamR, group.beamG, group.beamB)
  // Per pixel: full-brightness hue plus its own level, so a dim pixel still reads as its colour.
  const segments: PixelSegment[] = group.members.map((m) => ({
    css: computeNormalizedHueCss(m.r, m.g, m.b, m.w, m.a, m.uv),
    intensity: colourFactor(m.r, m.g, m.b, m.w, m.a, m.uv) * dimmerFactor,
  }))
  return <>{children({ color, intensity, segments })}</>
}
