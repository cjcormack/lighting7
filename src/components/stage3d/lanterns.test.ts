import { describe, expect, it } from 'vitest'
import type { FixturePatch, PatchPlacement } from '../../api/patchApi'
import { lanternsFor, patchAtPlacement } from './lanterns'

const base = {
  id: 7,
  key: 'pair',
  displayName: 'Pair',
  fixtureTypeKey: 'generic-dimmer',
  startChannel: 1,
  channelCount: 1,
  manufacturer: null,
  model: null,
  modeName: null,
  universe: 0,
  subnet: 0,
  sortOrder: 0,
  groups: [],
  stageX: -3,
  stageY: 0,
  stageZ: -0.3,
  baseYawDeg: 15,
  basePitchDeg: 45,
  riggingUuid: 'rig-1',
  beamAngleDeg: 26,
  gelCode: 'L201',
  kindOverride: 'PROFILE',
  stageHidden: false,
} satisfies FixturePatch

const sr: PatchPlacement = {
  uuid: 'sr',
  label: 'SR',
  riggingUuid: 'rig-2',
  stageX: 3,
  stageY: 0.5,
  stageZ: null,
  baseYawDeg: -15,
  basePitchDeg: null,
}

describe('patchAtPlacement', () => {
  it("lays the placement's six geometry fields over the patch and keeps its own facts", () => {
    const drawn = patchAtPlacement(base, sr)
    expect(drawn).toMatchObject({
      riggingUuid: 'rig-2',
      stageX: 3,
      stageY: 0.5,
      stageZ: null,
      baseYawDeg: -15,
      basePitchDeg: null,
      // The label the 3D view draws, as the 2D plot and the overview panel draw it.
      displayName: 'Pair · SR',
      // The fixture's own: a lantern is the same unit in the same colour, lit by the same key.
      key: 'pair',
      beamAngleDeg: 26,
      gelCode: 'L201',
      kindOverride: 'PROFILE',
    })
  })
})

describe('patchAtPlacement labels', () => {
  it('keeps the fixture name for an unlabelled lantern', () => {
    expect(patchAtPlacement(base, { ...sr, label: null }).displayName).toBe('Pair')
    expect(patchAtPlacement(base, { ...sr, label: '  ' }).displayName).toBe('Pair')
  })
})

describe('lanternsFor', () => {
  it('draws every positioned lantern after the fixture it belongs to, keyed per placement', () => {
    const unplaced: PatchPlacement = { ...sr, uuid: 'nowhere', stageX: null }
    const other = { ...base, id: 8, key: 'single' } satisfies FixturePatch
    const lanterns = lanternsFor([{ ...base, extraPlacements: [sr, unplaced] }, other])
    expect(lanterns.map((l) => l.id)).toEqual(['7:sr'])
    expect(lanterns[0].source.key).toBe('pair')
    expect(lanterns[0].patch.stageX).toBe(3)
  })

  it('draws nothing for a desk that predates the field', () => {
    expect(lanternsFor([base])).toEqual([])
  })
})
