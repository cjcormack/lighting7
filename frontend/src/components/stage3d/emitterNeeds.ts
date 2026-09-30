import type { FixturePatch } from '../../api/patchApi'
import { drawnLengthM } from '../../lib/fixtureLength'
import { findPrismProperty, type Fixture, type FixtureTypeInfo } from '../../store/fixtures'
import { bodyInputFor, bodySpecFor, lightRuns, MAX_LIGHTS_PER_FIXTURE, type BodySpec } from './bodies/archetype'
import { MAX_PRISM_LOBES, type SlotNeeds } from './emitterLayout'

/**
 * The body a patch is drawn as — its archetype, size and cells (`bodies/archetype.ts`). The one
 * statement of it, read by `Stage3D` to size the emitters and the body instances and by
 * `FixtureModel` to draw, so the two cannot disagree about how many cells a fixture has.
 */
export function bodySpecOf(
  patch: Pick<FixturePatch, 'kindOverride' | 'lengthM'>,
  fixture: Fixture | undefined,
  fixtureType: FixtureTypeInfo | undefined,
): BodySpec {
  const lengthM = drawnLengthM(
    fixtureType ? { acceptsLength: fixtureType.acceptsLength, lengthM: fixtureType.lengthM } : undefined,
    { lengthM: patch.lengthM ?? null },
  )
  return bodySpecFor(bodyInputFor(patch, fixture, fixtureType, lengthM))
}

/**
 * What a fixture slot needs from the shared emitters, for its body — the one statement of it, read
 * by `Stage3D` to size the buffers, and so the bound `FixtureModel` draws within. Two copies would
 * drift, and a fixture that drew more lobes than its slot was given would write into the next
 * fixture's block.
 *
 * A body that does not emit needs nothing. One cell is one lobe — six where there is a prism to
 * split it — each landing as its own light; several cells are a lobe each and a light per run of
 * cells, at most [MAX_LIGHTS_PER_FIXTURE].
 */
export function emitterNeedsForSpec(spec: BodySpec, fixture: Fixture | undefined): SlotNeeds {
  const cells = spec.cells.length
  if (!spec.emits || cells === 0) return { lobes: 0, lights: 0 }
  if (cells === 1) {
    const lobes = findPrismProperty(fixture?.properties) ? MAX_PRISM_LOBES : 1
    return { lobes, lights: lobes }
  }
  return { lobes: cells, lights: lightRuns(cells, MAX_LIGHTS_PER_FIXTURE).length }
}
