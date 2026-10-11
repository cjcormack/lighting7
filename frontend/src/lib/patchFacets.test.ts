import { describe, expect, it } from 'vitest'
import type { FixturePatch, PatchFacetState } from '@/api/patchApi'
import { anyDrifted, describeFacet, driftLine, facetChips, facetState, facetValueOf } from './patchFacets'

/**
 * The fixture sheet's and the Patch list's reading of a venue patch's facets (venue plan session 5):
 * a facet's value built from the patch as the rig shows it — whole, its placements named by uuid —
 * the chips' three states, and the drift line. Nothing here resolves a rule; the desk does.
 */
function patch(over: Partial<FixturePatch> = {}): FixturePatch {
  return {
    id: 3, uuid: 'p-3', key: 'lx2-c-fres', displayName: 'LX2 C FRES', fixtureTypeKey: 'generic-dimmer',
    startChannel: 21, channelCount: 1, manufacturer: null, model: null, modeName: null,
    universe: 1, subnet: 0, sortOrder: 0, groups: [],
    stageX: 0, stageY: 4.5, stageZ: 6, baseYawDeg: 0, basePitchDeg: null, riggingUuid: 'rig-lx2',
    beamAngleDeg: null, gelCode: 'L201', kindOverride: null, stageHidden: false, owner: 'VENUE',
    lanternType: 'cantata-f', zoomDeg: 32,
    extraPlacements: [{ uuid: 'pl-1', label: 'SR', riggingUuid: null, stageX: 3, stageY: 1, stageZ: 0, baseYawDeg: 90, basePitchDeg: null, iris: 0.6 }],
    ...over,
  }
}

const media = (gel: string) => ({ gelCode: gel, media: null, placements: [] })

describe('facetValueOf', () => {
  it('builds each facet whole from the patch, its placements by uuid', () => {
    const p = patch()
    expect(facetValueOf(p, 'ADDRESS')).toEqual({ universe: 1, subnet: 0, startChannel: 21 })
    const position = facetValueOf(p, 'POSITION')
    expect(position).toMatchObject({ riggingUuid: 'rig-lx2', stageY: 4.5, stageZ: 6, baseRollDeg: null, lengthM: null })
    expect((position as { placements: unknown[] }).placements).toEqual([
      { placementUuid: 'pl-1', riggingUuid: null, stageX: 3, stageY: 1, stageZ: 0, baseYawDeg: 90, basePitchDeg: null, baseRollDeg: null, lengthM: null },
    ])
    const focus = facetValueOf(p, 'FOCUS') as { lanternType: string; zoomDeg: number; placements: { placementUuid: string; iris: number }[] }
    expect(focus.lanternType).toBe('cantata-f')
    expect(focus.zoomDeg).toBe(32)
    expect(focus.placements[0]).toMatchObject({ placementUuid: 'pl-1', iris: 0.6, lanternType: null })
    expect(facetValueOf(p, 'MEDIA')).toEqual({ gelCode: 'L201', media: null, placements: [{ placementUuid: 'pl-1', media: null }] })
  })
})

describe('facet chips', () => {
  it('reads each facet as the venue’s, the show’s or drifted', () => {
    const p = patch({
      facets: [
        { facet: 'POSITION', value: {}, venue: {} },
        { facet: 'MEDIA', value: media('L202'), venue: media('L203'), drifted: true, base: media('L201') },
      ],
    })
    expect(facetChips(p)).toEqual([
      { facet: 'ADDRESS', state: 'venue' },
      { facet: 'POSITION', state: 'show' },
      { facet: 'FOCUS', state: 'venue' },
      { facet: 'MEDIA', state: 'drifted' },
    ])
    expect(anyDrifted(p)).toBe(true)
    expect(facetState(p, 'ADDRESS')).toBeNull()
    expect(facetChips(patch()).every((c) => c.state === 'venue')).toBe(true)
  })
})

describe('describeFacet and driftLine', () => {
  it('says a facet in a line, naming universes, riggings and lanterns', () => {
    const names = {
      universeOf: (uuid: string) => (uuid === 'u-1' ? 1 : undefined),
      riggingName: (uuid: string) => (uuid === 'rig-lx2' ? 'LX2' : undefined),
      lanternName: (id: string) => (id === 'cantata-f' ? 'Cantata F' : undefined),
    }
    expect(describeFacet('ADDRESS', { universeConfigUuid: 'u-1', startChannel: 21 }, names)).toBe('1-021')
    expect(describeFacet('POSITION', { riggingUuid: 'rig-lx2', stageX: 0, baseYawDeg: 0 }, names)).toBe('LX2 · x 0.00 m · yaw 0°')
    expect(describeFacet('FOCUS', { lanternType: 'cantata-f', zoomDeg: 32 }, names)).toBe('Cantata F · zoom 32°')
    expect(describeFacet('MEDIA', media('L201'))).toBe('Gel L201')
  })

  it('draws what the venue moved under an override, and nothing when it has not', () => {
    const drifted: PatchFacetState = { facet: 'MEDIA', value: media('L203'), venue: media('L202'), drifted: true, base: media('L200') }
    expect(driftLine(drifted)).toBe('Gel L200 → Gel L202')
    expect(driftLine({ ...drifted, drifted: false })).toBeNull()
  })
})
