import { describe, expect, it } from 'vitest'
import { duplicateName } from './duplicateName'

describe('duplicateName', () => {
  it('mints (Copy), then (Copy n) past the names the library holds', () => {
    expect(duplicateName('Warm Wash', new Set(['Warm Wash']))).toBe('Warm Wash (Copy)')
    expect(duplicateName('Warm Wash', new Set(['Warm Wash (Copy)', 'Warm Wash (Copy 2)']))).toBe('Warm Wash (Copy 3)')
  })

  it('remembers what it minted, so a batch never clashes with itself', () => {
    const taken = new Set(['Amber'])
    expect(duplicateName('Amber', taken)).toBe('Amber (Copy)')
    expect(duplicateName('Amber', taken)).toBe('Amber (Copy 2)')
    expect(taken.has('Amber (Copy 2)')).toBe(true)
  })
})
