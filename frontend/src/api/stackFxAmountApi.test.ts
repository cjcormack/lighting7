import { describe, expect, it } from 'vitest'
import { ALL_FULL, parseStackFxAmountFrame, stackFxAmountOf } from './stackFxAmountApi'

describe('stackFxAmount.state', () => {
  it('reads every stack below full, and a stack absent as full', () => {
    const state = parseStackFxAmountFrame({
      type: 'stackFxAmount.state',
      projectId: 3,
      stacks: [{ stackId: 4, amount: 0.5 }, { stackId: 9, amount: 0 }],
    })
    expect(state).toEqual({ projectId: 3, amounts: { 4: 0.5, 9: 0 } })
    expect(stackFxAmountOf(state, 4)).toBe(0.5)
    expect(stackFxAmountOf(state, 9)).toBe(0)
    expect(stackFxAmountOf(state, 5)).toBe(1)
  })

  it('reads a frame with no list, or nothing at all, as every stack at full', () => {
    expect(parseStackFxAmountFrame({ projectId: 3 })).toEqual({ projectId: 3, amounts: {} })
    expect(parseStackFxAmountFrame(null)).toBe(ALL_FULL)
  })
})
