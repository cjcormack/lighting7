import { describe, expect, it } from 'vitest'
import { FX_SHAPES, shapeOf } from './fxShapes'

/** The nine Shapes (FX editor plan D11): each a name for five fields, lit only while all five match. */
describe('Shapes', () => {
  it('names nine Shapes, in the plan’s order', () => {
    expect(FX_SHAPES.map((s) => s.name)).toEqual(['Unison', 'Chase', 'Back', 'Centre out', 'Centre in', 'Pairs', 'Odd/even', 'Mirror', 'Random'])
  })

  it('finds each Shape from its own fields, and none once one field differs', () => {
    for (const shape of FX_SHAPES) expect(shapeOf(shape.fields)?.name).toBe(shape.name)
    expect(shapeOf({ order: 'NORMAL', spread: 0.5, wings: 1, segments: 1, parts: 0 })).toBeNull()
    expect(shapeOf({ order: 'NORMAL', spread: 1, wings: 2, segments: 2, parts: 0 })).toBeNull()
  })
})
