import { describe, expect, it } from 'vitest'
import { ALL_FULL, parseStackEffectSizeFrame, stackEffectSizeOf } from './stackEffectSizeApi'

describe('stackEffectSize.state', () => {
  it('reads every stack below full, and a stack absent as full', () => {
    const state = parseStackEffectSizeFrame({
      type: 'stackEffectSize.state',
      projectId: 3,
      stacks: [{ stackId: 4, size: 0.5 }, { stackId: 9, size: 0 }],
    })
    expect(state).toEqual({ projectId: 3, sizes: { 4: 0.5, 9: 0 } })
    expect(stackEffectSizeOf(state, 4)).toBe(0.5)
    expect(stackEffectSizeOf(state, 9)).toBe(0)
    expect(stackEffectSizeOf(state, 5)).toBe(1)
  })

  it('reads a frame with no list, or nothing at all, as every stack at full', () => {
    expect(parseStackEffectSizeFrame({ projectId: 3 })).toEqual({ projectId: 3, sizes: {} })
    expect(parseStackEffectSizeFrame(null)).toBe(ALL_FULL)
  })
})
