import { describe, expect, it } from 'vitest'
import { withoutInfrastructure } from './infrastructure'

describe('withoutInfrastructure', () => {
  it('drops every infrastructure entry and keeps the rest in order', () => {
    const list = [
      { key: 'par-1' },
      { key: 'hazer-power', infrastructure: true },
      { key: 'par-2', infrastructure: false },
    ]
    expect(withoutInfrastructure(list).map((f) => f.key)).toEqual(['par-1', 'par-2'])
  })

  it('hands back the same array when there is nothing to drop, so a memo keyed on it holds', () => {
    const list = [{ key: 'par-1' }, { key: 'par-2', infrastructure: false }]
    expect(withoutInfrastructure(list)).toBe(list)
    expect(withoutInfrastructure(undefined)).toBeUndefined()
  })
})
