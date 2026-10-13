import { describe, expect, it } from 'vitest'
import { previewChipsThatFit } from './previewFit'

describe('previewChipsThatFit', () => {
  it('shows every chip when they all fit, with no room kept for a +N', () => {
    expect(previewChipsThatFit([20, 20, 20], 64, 2, 30)).toBe(3)
  })

  it('stops where the next chip and the +N for the rest would overflow', () => {
    // 20 + 2 + 20 = 42, and + 2 + 16 for "+1" = 60 ≤ 60; a third chip needs 64.
    expect(previewChipsThatFit([20, 20, 20], 60, 2, 16)).toBe(2)
    // 20 + 2 + 16 = 38 fits; 20 + 2 + 20 + 2 + 16 = 60 does not.
    expect(previewChipsThatFit([20, 20, 20, 20], 59, 2, 16)).toBe(1)
  })

  it('lets the last chip use the room a +N would have taken', () => {
    // Two chips with a +N need 20 + 2 + 20 + 2 + 16 = 60, but the last chip alone needs only 64.
    expect(previewChipsThatFit([20, 20, 20], 64, 2, 16)).toBe(3)
  })

  it('answers 0 when not even one chip fits beside the +N', () => {
    expect(previewChipsThatFit([50, 20], 40, 2, 16)).toBe(0)
  })

  it('shows every chip when the line has no measured width', () => {
    expect(previewChipsThatFit([20, 20, 20], 0, 2, 16)).toBe(3)
    expect(previewChipsThatFit([], 100, 2, 16)).toBe(0)
  })
})
