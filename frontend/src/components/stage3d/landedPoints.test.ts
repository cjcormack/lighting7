import { afterEach, describe, expect, it } from 'vitest'
import { forgetLanding, landedPoint, recordLanding } from './landedPoints'

/**
 * Where the selected fixture's beam lands, for *Focus here* (fixture-optics plan session 1): per
 * reporting canvas, the newest report wins, and a reporter that stops reporting is forgotten — so a
 * deselected fixture never answers with a point from before it was re-aimed, and the Positions plan
 * closing does not erase what the Stage view still draws.
 */
const stage = {}
const positions = {}

afterEach(() => {
  forgetLanding(stage, 'rev-1')
  forgetLanding(positions, 'rev-1')
})

describe('landedPoints', () => {
  it('answers nothing until a canvas reports, and the newest report after', () => {
    expect(landedPoint('rev-1')).toBeNull()
    recordLanding(stage, 'rev-1', { x: 0, y: 8, z: 2.8 })
    expect(landedPoint('rev-1')).toEqual({ x: 0, y: 8, z: 2.8 })
    recordLanding(positions, 'rev-1', { x: 0, y: 8, z: 2.5 })
    expect(landedPoint('rev-1')).toEqual({ x: 0, y: 8, z: 2.5 })
    recordLanding(stage, 'rev-1', { x: 1, y: 8, z: 2.8 })
    expect(landedPoint('rev-1')).toEqual({ x: 1, y: 8, z: 2.8 })
  })

  it('holds a beam landing on nothing as the newest answer', () => {
    recordLanding(stage, 'rev-1', { x: 0, y: 8, z: 2.8 })
    recordLanding(stage, 'rev-1', null)
    expect(landedPoint('rev-1')).toBeNull()
  })

  it("forgets one canvas's report and keeps the other's", () => {
    recordLanding(stage, 'rev-1', { x: 0, y: 8, z: 2.8 })
    recordLanding(positions, 'rev-1', { x: 0, y: 8, z: 2.5 })
    forgetLanding(positions, 'rev-1')
    expect(landedPoint('rev-1')).toEqual({ x: 0, y: 8, z: 2.8 })
    forgetLanding(stage, 'rev-1')
    expect(landedPoint('rev-1')).toBeNull()
  })
})
