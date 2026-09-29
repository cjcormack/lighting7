import type { FixturePatch } from '../../api/patchApi'
import {
  findGroupColourSource,
  findPrismProperty,
  resolveFixtureKind,
  type Fixture,
  type FixtureKind,
  type FixtureTypeInfo,
} from '../../store/fixtures'
import { MAX_PRISM_LOBES, MAX_WASH_PIXELS, type SlotNeeds } from './emitterLayout'

/**
 * How many independently coloured pixels a fixture has — its element-group colour's members,
 * or 0 for a fixture without one.
 */
export function pixelCountOf(fixture: Fixture | undefined): number {
  const groupColour = findGroupColourSource(fixture)
  return groupColour ? groupColour.memberColourChannels.length : 0
}

/**
 * Whether a fixture washes per pixel. Only the STRIP body lays its pixels out along a line
 * (PixelStrip), so that is the one shape a per-pixel wash makes sense for.
 */
export function isPixelStrip(kind: FixtureKind, pixelCount: number): boolean {
  return kind === 'STRIP' && pixelCount > 1
}

/**
 * What a fixture slot needs from the shared emitters — the one statement of it, read both by
 * `Stage3D` to size the buffers and by `FixtureModel` to decide what it draws. Two copies would
 * drift, and a fixture that drew more lobes than its slot was given would write into the next
 * fixture's block.
 *
 * A beam comes from `acceptsBeamAngle` (FixtureModel's `showCone`); six lobes only where there
 * is a prism to split it; a wash block only for a pixel strip.
 */
export function emitterNeedsFor(
  patch: Pick<FixturePatch, 'kindOverride'>,
  fixture: Fixture | undefined,
  fixtureType: FixtureTypeInfo | undefined,
): SlotNeeds {
  const beam = !!fixtureType?.acceptsBeamAngle
  const lobes = beam ? (findPrismProperty(fixture?.properties) ? MAX_PRISM_LOBES : 1) : 0
  const kind = resolveFixtureKind(patch.kindOverride, fixtureType?.kind)
  const pixels = pixelCountOf(fixture)
  const washPixels = isPixelStrip(kind, pixels) ? Math.min(pixels, MAX_WASH_PIXELS) : 0
  return { lobes, washPixels }
}
