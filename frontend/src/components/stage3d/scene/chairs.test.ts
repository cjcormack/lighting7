import { describe, expect, it } from 'vitest'
import { Box3, type BufferGeometry } from 'three'
import { chairGeometry } from './chairs'

function bounds(...geometries: BufferGeometry[]): Box3 {
  const box = new Box3()
  for (const g of geometries) {
    g.computeBoundingBox()
    box.union(g.boundingBox!)
  }
  return box
}

describe('chair geometry', () => {
  it('draws a banquet chair at a stacking chair’s size, standing on its base and facing the stage', () => {
    const { pads, frame } = chairGeometry('BANQUET', 0.5)
    const all = bounds(pads, frame)
    expect(all.min.y).toBeCloseTo(0, 2)
    expect(all.max.y).toBeGreaterThan(0.88)
    expect(all.max.y).toBeLessThan(0.96)
    expect(all.max.x - all.min.x).toBeCloseTo(0.45, 1)
    // The back is behind the seat, away from the stage (+z here).
    const seatPad = bounds(pads)
    expect(seatPad.max.z).toBeGreaterThan(0.2)
    expect(seatPad.min.z).toBeLessThan(-0.2)
  })

  it('draws banquet chairs set tighter than they are wide narrower, not through each other', () => {
    const { pads, frame } = chairGeometry('BANQUET', 0.4)
    const all = bounds(pads, frame)
    expect(all.max.x - all.min.x).toBeLessThanOrEqual(0.4)
  })

  it('keeps the theatre seat sized to its pitch', () => {
    const narrow = bounds(...Object.values(chairGeometry('THEATRE', 0.4)))
    const wide = bounds(...Object.values(chairGeometry('THEATRE', 0.55)))
    expect(narrow.max.x - narrow.min.x).toBeCloseTo(0.36, 5)
    expect(wide.max.x - wide.min.x).toBeCloseTo(0.495, 5)
  })
})
