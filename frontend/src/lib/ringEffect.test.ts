import { describe, expect, it } from 'vitest'
import type { SettingOption } from '../store/fixtures'
import {
  makeRingPaint,
  RING_CHASE_MAX_RPS,
  RING_CHASE_MIN_RPS,
  RING_SEGMENTS,
  ringBandAt,
  ringChaseRps,
  ringFrame,
} from './ringEffect'

/** The Show-Sound LEDBEAMRING's ring channel as the desk sends it. */
const OPTIONS: SettingOption[] = [
  { name: 'OFF', level: 0, displayName: 'Off' },
  { name: 'COLOUR', level: 5, displayName: 'Colour', ringColour: '#FF0000' },
  { name: 'CHASE', level: 75, displayName: 'Chase', ringChase: true },
  { name: 'CHASE_2', level: 249, displayName: 'Chase 2', ringChase: true },
]

function lit(level: Float32Array): number {
  return level.filter((l) => l > 0).length
}

describe('the LED ring', () => {
  it('reads the band a level sits in by its start', () => {
    expect(ringBandAt(OPTIONS, 0)?.name).toBe('OFF')
    expect(ringBandAt(OPTIONS, 4)?.name).toBe('OFF')
    expect(ringBandAt(OPTIONS, 74)?.name).toBe('COLOUR')
    expect(ringBandAt(OPTIONS, 200)?.name).toBe('CHASE')
    expect(ringBandAt(OPTIONS, 255)?.name).toBe('CHASE_2')
  })

  it('is dark when off, and on no band at all, and asks for no frames', () => {
    const paint = makeRingPaint()
    expect(ringFrame(ringBandAt(OPTIONS, 0), 128, 3, paint)).toBe(false)
    expect(lit(paint.level)).toBe(0)
    expect(ringFrame(undefined, null, 3, paint)).toBe(false)
    expect(lit(paint.level)).toBe(0)
  })

  it('shows a colour band on every segment, still', () => {
    const paint = makeRingPaint()
    expect(ringFrame(ringBandAt(OPTIONS, 40), 0, 1, paint)).toBe(false)
    expect(lit(paint.level)).toBe(RING_SEGMENTS)
    for (let s = 0; s < RING_SEGMENTS; s++) {
      expect([paint.rgb[s * 3], paint.rgb[s * 3 + 1], paint.rgb[s * 3 + 2]]).toEqual([1, 0, 0])
    }
  })

  it('chases a quarter of the ring round it, moving with time', () => {
    const paint = makeRingPaint()
    expect(ringFrame(ringBandAt(OPTIONS, 100), 0, 0.5, paint)).toBe(true)
    expect(lit(paint.level)).toBe(RING_SEGMENTS / 4)
    const before = Array.from(paint.level)
    ringFrame(ringBandAt(OPTIONS, 100), 0, 1.5, paint)
    expect(Array.from(paint.level)).not.toEqual(before)
    // Every segment's hue is its place on the ring, so the arc changes colour as it goes.
    expect([paint.rgb[0], paint.rgb[1], paint.rgb[2]]).not.toEqual([
      paint.rgb[(RING_SEGMENTS / 2) * 3],
      paint.rgb[(RING_SEGMENTS / 2) * 3 + 1],
      paint.rgb[(RING_SEGMENTS / 2) * 3 + 2],
    ])
  })

  it('chases in the band’s colour where it declares one', () => {
    const paint = makeRingPaint()
    const red: SettingOption = { name: 'RED_CHASE', level: 0, displayName: 'Red chase', ringColour: '#FF0000', ringChase: true }
    expect(ringFrame(red, 0, 0.5, paint)).toBe(true)
    expect(lit(paint.level)).toBe(RING_SEGMENTS / 4)
    for (let s = 0; s < RING_SEGMENTS; s++) {
      expect([paint.rgb[s * 3], paint.rgb[s * 3 + 1], paint.rgb[s * 3 + 2]]).toEqual([1, 0, 0])
    }
  })

  it('chases faster as the ring speed rises, and mid-range with no speed channel', () => {
    expect(ringChaseRps(0)).toBe(RING_CHASE_MIN_RPS)
    expect(ringChaseRps(255)).toBe(RING_CHASE_MAX_RPS)
    expect(ringChaseRps(400)).toBe(RING_CHASE_MAX_RPS)
    expect(ringChaseRps(null)).toBeCloseTo((RING_CHASE_MIN_RPS + RING_CHASE_MAX_RPS) / 2, 9)
  })
})
