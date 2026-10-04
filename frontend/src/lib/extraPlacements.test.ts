import { describe, expect, it } from 'vitest'
import {
  mirroredPlacement,
  normalisedLabel,
  placementListsEqual,
  toPlacementInput,
} from './extraPlacements'
import type { PatchPlacement } from '../api/patchApi'

const stored: PatchPlacement = {
  uuid: 'u-1',
  label: 'SR',
  riggingUuid: 'rig-1',
  stageX: 3,
  stageY: 0.25,
  stageZ: -0.5,
  baseYawDeg: 20,
  basePitchDeg: 45,
}

describe('mirroredPlacement', () => {
  it('mirrors across the centre line on the same rigging', () => {
    expect(
      mirroredPlacement({
        riggingUuid: 'rig-1',
        stageX: -3,
        stageY: 0.25,
        stageZ: -0.5,
        baseYawDeg: 20,
        basePitchDeg: 45,
      }),
    ).toEqual({
      label: null,
      riggingUuid: 'rig-1',
      stageX: 3,
      stageY: 0.25,
      stageZ: -0.5,
      baseYawDeg: -20,
      basePitchDeg: 45,
      baseRollDeg: null,
    })
  })

  it('turns a rolled body the other way too, as a reflection does', () => {
    const m = mirroredPlacement({
      riggingUuid: null,
      stageX: -4,
      stageY: 2,
      stageZ: 1,
      baseYawDeg: 90,
      basePitchDeg: 10,
      baseRollDeg: 90,
    })
    expect(m.baseRollDeg).toBe(-90)
    expect(m.basePitchDeg).toBe(10)
  })

  it('keeps nulls null and never writes -0', () => {
    const m = mirroredPlacement({
      riggingUuid: null,
      stageX: 0,
      stageY: null,
      stageZ: null,
      baseYawDeg: null,
      basePitchDeg: null,
    })
    expect(Object.is(m.stageX, 0)).toBe(true)
    expect(m.baseYawDeg).toBeNull()
    expect(m.stageY).toBeNull()
  })
})

describe('placementListsEqual', () => {
  it('is true for a stored list against its own inputs', () => {
    expect(placementListsEqual([toPlacementInput(stored)], [toPlacementInput(stored)])).toBe(true)
  })

  it('compares labels as the desk stores them', () => {
    const padded = { ...toPlacementInput(stored), label: '  SR ' }
    expect(placementListsEqual([toPlacementInput(stored)], [padded])).toBe(true)
    const blank = { ...toPlacementInput(stored), label: '   ' }
    const none = { ...toPlacementInput(stored), label: null }
    expect(placementListsEqual([blank], [none])).toBe(true)
  })

  it('sees an edit, a reorder, an addition and a removal', () => {
    const a = toPlacementInput(stored)
    const b = { ...toPlacementInput(stored), uuid: 'u-2', stageX: -3 }
    expect(placementListsEqual([a], [{ ...a, stageX: 4 }])).toBe(false)
    expect(placementListsEqual([a, b], [b, a])).toBe(false)
    expect(placementListsEqual([a], [a, b])).toBe(false)
    expect(placementListsEqual([a, b], [a])).toBe(false)
    // A new entry has no uuid, so it is never equal to a stored one with the same geometry.
    const { uuid: _dropped, ...fresh } = a
    void _dropped
    expect(placementListsEqual([a], [fresh])).toBe(false)
  })
})

describe("a variable-length run's sides", () => {
  it('carry their own length, and absent reads as none', () => {
    expect(toPlacementInput({ ...stored, lengthM: 6 }).lengthM).toBe(6)
    expect(toPlacementInput(stored).lengthM).toBeNull()
    const a = toPlacementInput(stored)
    expect(placementListsEqual([a], [{ ...a, lengthM: undefined }])).toBe(true)
    expect(placementListsEqual([a], [{ ...a, lengthM: 6 }])).toBe(false)
    expect(placementListsEqual([{ ...a, lengthM: 6 }], [{ ...a, lengthM: 6.5 }])).toBe(false)
  })
})

describe('a placement roll', () => {
  it('is carried into the editable entry, so a save never clears one it did not edit', () => {
    expect(toPlacementInput({ ...stored, baseRollDeg: 90 }).baseRollDeg).toBe(90)
    expect(toPlacementInput(stored).baseRollDeg).toBeNull()
    const a = toPlacementInput(stored)
    expect(placementListsEqual([a], [{ ...a, baseRollDeg: undefined }])).toBe(true)
    expect(placementListsEqual([a], [{ ...a, baseRollDeg: 90 }])).toBe(false)
  })
})

describe('normalisedLabel', () => {
  it('trims and reads blank as none', () => {
    expect(normalisedLabel(' SR ')).toBe('SR')
    expect(normalisedLabel('')).toBeNull()
    expect(normalisedLabel(null)).toBeNull()
  })
})

describe("a lantern's focus on a placement", () => {
  const focus = {
    lanternType: 'cantata-f',
    zoomDeg: 20,
    shutters: [
      { depth: 0.2, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0.1, angleDeg: 5 },
    ],
    iris: 0.5,
  }

  it('is carried whole, so a list sent back does not clear it', () => {
    const input = toPlacementInput({ ...stored, ...focus })
    expect(input.lanternType).toBe('cantata-f')
    expect(input.zoomDeg).toBe(20)
    expect(input.shutters?.[3].angleDeg).toBe(5)
    expect(input.iris).toBe(0.5)
    // An unfocused placement sends every focus field as null: the whole entry replaces the stored one.
    expect(toPlacementInput(stored)).toMatchObject({ lanternType: null, shutters: null, focusSoftness: null })
  })

  it('is an edit when any field of it moves', () => {
    const a = toPlacementInput({ ...stored, ...focus })
    expect(placementListsEqual([a], [{ ...a }])).toBe(true)
    expect(placementListsEqual([a], [{ ...a, lanternType: 's4-19' }])).toBe(false)
    expect(placementListsEqual([a], [{ ...a, shutters: a.shutters!.map((b, i) => (i === 0 ? { ...b, depth: 0.3 } : b)) }])).toBe(
      false,
    )
    expect(placementListsEqual([a], [{ ...a, focusSoftness: undefined }])).toBe(true)
  })
})

describe("a unit's fitted media on a placement", () => {
  const media = { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' }, R02_BASTARD_AMBER: {} } } }

  it('is carried whole, so a list sent back does not clear it', () => {
    expect(toPlacementInput({ ...stored, media }).media).toEqual(media)
    // A placement with nothing fitted sends null: the whole entry replaces the stored one.
    expect(toPlacementInput(stored).media).toBeNull()
  })

  it('is an edit when only the media moves, and not when the desk spells it differently', () => {
    const a = toPlacementInput({ ...stored, media })
    expect(placementListsEqual([a], [{ ...a }])).toBe(true)
    expect(
      placementListsEqual([a], [{ ...a, media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'L106' }, R02_BASTARD_AMBER: {} } } } }]),
    ).toBe(false)
    // An empty frame is fitted with nothing — not the same as the stock.
    expect(placementListsEqual([a], [{ ...a, media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } } } } }])).toBe(false)
    expect(placementListsEqual([a], [{ ...a, media: null }])).toBe(false)
    // The desk encodes an unset half as null; that is the same slot.
    expect(
      placementListsEqual(
        [a],
        [{ ...a, media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26', gobo: null }, R02_BASTARD_AMBER: { gel: null, gobo: null } } } } }],
      ),
    ).toBe(true)
  })
})
