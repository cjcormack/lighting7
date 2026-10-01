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
      // The label the 3D view draws.
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

describe("patchAtPlacement — a variable-length run's sides", () => {
  it("takes the side's own length, else the fixture's", () => {
    const strip = { ...base, fixtureTypeKey: 'lightstrip', lengthM: 10 }
    expect(patchAtPlacement(strip, { ...sr, lengthM: 6 }).lengthM).toBe(6)
    expect(patchAtPlacement(strip, sr).lengthM).toBe(10)
    expect(patchAtPlacement({ ...strip, lengthM: null }, sr).lengthM).toBeNull()
  })
})

describe("a placement's lantern and focus", () => {
  const blades = [
    { depth: 0.2, angleDeg: 0 },
    { depth: 0, angleDeg: 0 },
    { depth: 0, angleDeg: 0 },
    { depth: 0.4, angleDeg: 8 },
  ]
  const focused = {
    ...base,
    lanternType: 's4-26',
    zoomDeg: null,
    shutters: blades,
    gateRotationDeg: 10,
    iris: 0.5,
    focusSoftness: 0.2,
  } satisfies FixturePatch

  it("takes the patch's lantern where it names none, and never the patch's focus", () => {
    const drawn = patchAtPlacement(focused, sr)
    expect(drawn.lanternType).toBe('s4-26')
    expect(drawn.shutters).toBeNull()
    expect(drawn.gateRotationDeg).toBeNull()
    expect(drawn.iris).toBeNull()
    expect(drawn.focusSoftness).toBeNull()
  })

  it('is its own lantern, focused separately, where it names one', () => {
    const drawn = patchAtPlacement(focused, {
      ...sr,
      lanternType: 'cantata-f',
      zoomDeg: 20,
      shutters: [blades[3], blades[2], blades[1], blades[0]],
      focusSoftness: 0.9,
    })
    expect(drawn.lanternType).toBe('cantata-f')
    expect(drawn.zoomDeg).toBe(20)
    expect(drawn.shutters?.[0].depth).toBe(0.4)
    expect(drawn.focusSoftness).toBe(0.9)
  })
})
