import { describe, expect, it } from 'vitest'
import { riggingShape } from './riggingShape'

describe('riggingShape', () => {
  it('draws a bar, pipe, boom and a position of no kind as a tube', () => {
    for (const kind of ['BAR', 'PIPE', 'BOOM', 'bar', null, undefined]) expect(riggingShape(kind)).toBe('tube')
  })

  it('draws a truss as a truss', () => {
    expect(riggingShape('TRUSS')).toBe('truss')
  })

  it('draws nothing for a ledge, a floor stand or another mount', () => {
    for (const kind of ['LEDGE', 'FLOOR_STAND', 'OTHER', 'ledge']) expect(riggingShape(kind)).toBe('none')
  })
})
