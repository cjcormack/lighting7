import { describe, expect, it } from 'vitest'
import { acceptsLength, bodyEndsLighting, drawnLengthM, longAxisLighting } from './fixtureLength'

const strip = { acceptsLength: true, lengthM: 1 }
const bar = { acceptsLength: false, lengthM: 1 }

describe('drawnLengthM', () => {
  it("takes a segment's own length, then the patch's, then the type's default", () => {
    expect(drawnLengthM(strip, { lengthM: 10 }, { lengthM: 6 })).toBe(6)
    expect(drawnLengthM(strip, { lengthM: 10 }, { lengthM: null })).toBe(10)
    expect(drawnLengthM(strip, { lengthM: 10 }, {})).toBe(10)
    expect(drawnLengthM(strip, { lengthM: null })).toBe(1)
    expect(drawnLengthM(strip, {})).toBe(1)
  })

  it('ignores any stored length on a fixed-length type — a pixel bar is the bar it is', () => {
    expect(drawnLengthM(bar, { lengthM: 10 }, { lengthM: 6 })).toBe(1)
    // An older desk sends no flag at all: read as fixed.
    expect(drawnLengthM({ lengthM: 0.3 }, { lengthM: 10 })).toBe(0.3)
  })

  it('is null when the type is unknown or declares no length', () => {
    expect(drawnLengthM(undefined, { lengthM: 10 })).toBeNull()
    expect(drawnLengthM({ acceptsLength: true, lengthM: null }, {})).toBeNull()
  })
})

describe('acceptsLength', () => {
  it('is true only for a type that says so', () => {
    expect(acceptsLength(strip)).toBe(true)
    expect(acceptsLength(bar)).toBe(false)
    expect(acceptsLength({})).toBe(false)
    expect(acceptsLength(undefined)).toBe(false)
  })
})

describe('longAxisLighting', () => {
  const close = (a: { x: number; y: number; z: number }, e: { x: number; y: number; z: number }) => {
    expect(a.x).toBeCloseTo(e.x, 9)
    expect(a.y).toBeCloseTo(e.y, 9)
    expect(a.z).toBeCloseTo(e.z, 9)
  }

  it('runs across the stage at yaw 0 and turns with the yaw', () => {
    close(longAxisLighting(null, null), { x: 1, y: 0, z: 0 })
    close(longAxisLighting(90, 0), { x: 0, y: 1, z: 0 })
    close(longAxisLighting(180, 0), { x: -1, y: 0, z: 0 })
    close(longAxisLighting(-90, 0), { x: 0, y: -1, z: 0 })
  })

  it('is not moved by pitch, which turns the body about its own long axis', () => {
    close(longAxisLighting(90, 45), longAxisLighting(90, 0))
    close(longAxisLighting(0, -30), { x: 1, y: 0, z: 0 })
  })

  it('stands on end under roll, whatever the yaw — the only way off level', () => {
    close(longAxisLighting(0, 0, 90), { x: 0, y: 0, z: 1 })
    close(longAxisLighting(90, 0, 90), { x: 0, y: 0, z: 1 })
    close(longAxisLighting(0, 0, 30), { x: Math.cos(Math.PI / 6), y: 0, z: 0.5 })
    close(longAxisLighting(90, 45, null), longAxisLighting(90, 45))
  })
})

describe('bodyEndsLighting', () => {
  it('centres the body on its position', () => {
    const [a, b] = bodyEndsLighting({ x: -5, y: 4, z: 0.1 }, 6, 90, 0)
    expect(a.x).toBeCloseTo(-5, 9)
    expect(a.y).toBeCloseTo(1, 9)
    expect(b.y).toBeCloseTo(7, 9)
    expect(a.z).toBeCloseTo(0.1, 9)
    expect(b.z).toBeCloseTo(0.1, 9)
  })

  it('runs a rolled body up and down from its position', () => {
    const [a, b] = bodyEndsLighting({ x: -4, y: 2, z: 1 }, 2, 90, 0, 90)
    expect(a.x).toBeCloseTo(-4, 9)
    expect(b.y).toBeCloseTo(2, 9)
    expect(a.z).toBeCloseTo(0, 9)
    expect(b.z).toBeCloseTo(2, 9)
  })
})
