import { describe, expect, it } from 'vitest'
import { FX_SHAPES, shapeOf } from './fxShapes'

/** The nine patterns (FX editor plan D11; fx-settings plan D10): each a name for four fields, lit only while all four match. */
describe('Patterns', () => {
  it('names nine patterns, in the plan’s order', () => {
    expect(FX_SHAPES.map((s) => s.name)).toEqual(['Unison', 'Chase', 'Back', 'Centre out', 'Centre in', 'Pairs', 'Odd/even', 'Mirror', 'Random'])
  })

  it('finds each pattern from its own fields, and none once one field differs', () => {
    for (const shape of FX_SHAPES) expect(shapeOf(shape.fields)?.name).toBe(shape.name)
    expect(shapeOf({ order: 'NORMAL', wings: 2, segments: 2, parts: 0 })).toBeNull()
    expect(shapeOf({ order: 'POSITIONAL', wings: 1, segments: 1, parts: 0 })).toBeNull()
  })

  it('leaves Spread to Each head later by', () => {
    const draft = { order: 'NORMAL', spread: 0.5, spreadUnit: 'STEP', wings: 1, segments: 1, parts: 0 }
    expect(shapeOf(draft)?.name).toBe('Chase')
    for (const shape of FX_SHAPES) expect(shape.fields).not.toHaveProperty('spread')
  })
})
