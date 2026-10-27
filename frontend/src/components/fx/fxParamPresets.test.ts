import { describe, expect, it } from 'vitest'
import { paramPresetsOf } from './fxParamPresets'

describe('paramPresetsOf', () => {
  it('finds a built-in’s presets by its folded name', () => {
    expect(paramPresetsOf({ name: 'Colour Strobe' }, 'width')?.values).toEqual([0.1, 0.25, 0.5])
    expect(paramPresetsOf({ name: 'Wave' }, 'width')?.alongside?.value).toBe('HEAD')
    expect(paramPresetsOf({ name: 'Wave' }, 'low')).toBeNull()
  })

  it('answers nothing for a name the table holds only through Object.prototype', () => {
    expect(paramPresetsOf({ name: 'Constructor' }, 'length')).toBeNull()
    expect(paramPresetsOf({ name: 'Wave' }, 'constructor')).toBeNull()
    expect(paramPresetsOf({ name: '__proto__' }, 'width')).toBeNull()
  })
})
