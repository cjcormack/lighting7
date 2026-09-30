import { describe, expect, it } from 'vitest'
import standing from '../../../../../src/test/resources/stage/standingRiggingKinds.json'
import { hangerLengthM, mountFor, MIN_HANGER_M, STANDING_RIGGING_KINDS } from './mount'

describe('mounts', () => {
  it("stands a body on the desk's standing kinds — the same list the desk's describe_rig reads", () => {
    // `src/test/resources/stage/standingRiggingKinds.json` is the file StandingRiggingKindsTest
    // checks the desk's STANDING_RIGGING_KINDS against.
    expect([...STANDING_RIGGING_KINDS].sort()).toEqual([...standing.standing].sort())
  })

  it('stands on a LEDGE or a FLOOR_STAND, and hangs from a bar, a pipe, a truss or anything else', () => {
    expect(mountFor({ kind: 'LEDGE' })).toBe('stand')
    expect(mountFor({ kind: 'FLOOR_STAND' })).toBe('stand')
    expect(mountFor({ kind: 'ledge' })).toBe('stand')
    for (const kind of ['BAR', 'PIPE', 'TRUSS', 'BOOM', 'OTHER', null]) expect(mountFor({ kind })).toBe('hang')
    // On no rigging: hung from nothing, drawn with no hanger.
    expect(mountFor(null)).toBe('hang')
  })

  it('runs a hanger up to a bar above the body, and none for a standing one', () => {
    expect(hangerLengthM('hang', 3.4, 4)).toBeCloseTo(0.6, 9)
    expect(hangerLengthM('stand', 3.4, 4)).toBe(0)
    // The body already at, or above, the bar: nothing to draw.
    expect(hangerLengthM('hang', 4, 4)).toBe(0)
    expect(hangerLengthM('hang', 4 - MIN_HANGER_M / 2, 4)).toBe(0)
    expect(hangerLengthM('hang', 3, null)).toBe(0)
  })
})
