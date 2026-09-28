import { useMemo, type ReactNode } from 'react'
import {
  findColourSource,
  findDimmerProperty,
  findGroupColourSource,
  type ColourPropertyDescriptor,
  type Fixture,
  type FixtureTypeInfo,
  type SettingPropertyDescriptor,
  type SliderPropertyDescriptor,
} from '../../store/fixtures'
import type { GroupColourPropertyDescriptor } from '../../api/groupsApi'
import type { FixturePatch } from '../../api/patchApi'
import { findGel } from '../../data/gels'
import {
  useColourValue,
  useSettingColourPreview,
} from '../../hooks/usePropertyValues'
import { useGroupColourValues } from '../../hooks/useGroupPropertyValues'
import { colourFactor, useNormalizedIntensity } from '../../hooks/useNormalizedIntensity'
import { computeNormalizedHueCss } from '../../lib/colourMath'

/**
 * How lit a fixture is and in what colour, independent of how any one surface draws it.
 *
 * Both numbers are raw, not display-ready: `color` is the hue at **full** brightness and
 * `intensity` is the **linear** 0..1 level. Each medium applies its own curve — the DOM marker
 * folds `perceptualBrightness` into a box-shadow and an opacity, the 3D scene splits it (perceptual
 * on the lens, linear on the cone so it can double as the beam cull), and the SVG plot uses it for
 * a fill. A pre-baked CSS string would take that choice away, which is why `useColourAppearance`
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
 * across leaf components gives each a fixed hook set, which is the trick `StageMarker` already used
 * internally and the reason the 2D plot went without live colour for so long.
 */
export function FixtureAppearanceSource({
  patch,
  fixture,
  fixtureType,
  children,
}: FixtureAppearanceProps) {
  // The discriminator is pure, so it resolves here rather than through hooks.
  const colourSource = useMemo(
    () => (fixture?.properties ? findColourSource(fixture.properties) : undefined),
    [fixture?.properties],
  )
  const groupColour = useMemo(() => findGroupColourSource(fixture), [fixture])
  const dimmerProp = useMemo(
    () => findDimmerProperty(fixture?.properties),
    [fixture?.properties],
  )
  // `acceptsGel` matters: a gel code on a fixture whose type doesn't take gel is stale data, and
  // colouring by it would contradict both other surfaces.
  const gel =
    !colourSource && fixtureType?.acceptsGel && patch.gelCode ? findGel(patch.gelCode) : null

  if (!fixture) return <PlaceholderAppearance>{children}</PlaceholderAppearance>

  if (groupColour && groupColour.memberColourChannels.length > 1) {
    return (
      <MultiPixelAppearance groupColourProp={groupColour} dimmerProp={dimmerProp}>
        {children}
      </MultiPixelAppearance>
    )
  }

  if (colourSource?.type === 'colour') {
    return (
      <ColourAppearance colourProp={colourSource.property} dimmerProp={dimmerProp}>
        {children}
      </ColourAppearance>
    )
  }

  if (colourSource?.type === 'setting') {
    return (
      <SettingColourAppearance settingProp={colourSource.property} dimmerProp={dimmerProp}>
        {children}
      </SettingColourAppearance>
    )
  }

  if (gel) {
    return (
      <FixedColourAppearance hex={gel.color} dimmerProp={dimmerProp}>
        {children}
      </FixedColourAppearance>
    )
  }

  return (
    <FixedColourAppearance hex={DEFAULT_FIXTURE_COLOUR} dimmerProp={dimmerProp}>
      {children}
    </FixedColourAppearance>
  )
}

type LeafProps = { children: (appearance: FixtureAppearance) => ReactNode }

function ColourAppearance({
  colourProp,
  dimmerProp,
  children,
}: LeafProps & {
  colourProp: ColourPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
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
  return <>{children({ color, intensity })}</>
}

function SettingColourAppearance({
  settingProp,
  dimmerProp,
  children,
}: LeafProps & {
  settingProp: SettingPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
}) {
  const preview = useSettingColourPreview(settingProp)
  // A selected colour preset reads as fully on; no selection ⇒ dark. A separate dimmer at 0
  // still wins through the dimmer factor.
  const intensity = useNormalizedIntensity(dimmerProp) * (preview ? 1 : 0)
  return <>{children({ color: preview ?? '#888888', intensity })}</>
}

function FixedColourAppearance({
  hex,
  dimmerProp,
  children,
}: LeafProps & { hex: string; dimmerProp?: SliderPropertyDescriptor }) {
  // No colour channels (gel or dimmer-only), so the colour magnitude is implicitly 1 and the
  // dimmer alone is the level. A gel fixture with no dimmer reads full on by design — there is
  // no brightness signal to gate it on.
  return <>{children({ color: hex, intensity: useNormalizedIntensity(dimmerProp) })}</>
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
