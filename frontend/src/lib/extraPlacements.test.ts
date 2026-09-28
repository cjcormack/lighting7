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
    })
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

describe('normalisedLabel', () => {
  it('trims and reads blank as none', () => {
    expect(normalisedLabel(' SR ')).toBe('SR')
    expect(normalisedLabel('')).toBeNull()
    expect(normalisedLabel(null)).toBeNull()
  })
})
