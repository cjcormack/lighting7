import { describe, expect, it } from 'vitest'
import { parseArmedFrame, parseFiredFrame, parseSkippedFrame, spentAt } from './effectsApi'

describe('effectsApi', () => {
  it('anchors the arm countdown to this browser clock from the frame remainingMs', () => {
    const state = parseArmedFrame({ type: 'effects.armed', armed: true, remainingMs: 42_000, spent: [{ fixture: 'c', trigger: 'output1', spentAt: '2026-10-01 21:42:00.000Z' }] }, 1000)
    expect(state.armed).toBe(true)
    expect(state.armedUntilMs).toBe(43_000)
    expect(spentAt(state, 'c', 'output1')).toBe('2026-10-01 21:42:00.000Z')
    expect(spentAt(state, 'c', 'output2')).toBeNull()
  })

  it('reads a missing field as its zero, the desk Json dropping defaults', () => {
    const state = parseArmedFrame({ type: 'effects.armed' }, 5)
    expect(state).toEqual({ armed: false, armedUntilMs: null, rehearsal: false, spent: [], projectId: null })
  })

  it('parses a fire and a skip', () => {
    expect(parseFiredFrame({ fixture: 'c', trigger: 'output2', label: 'B', at: 'x', rehearsed: true, source: 'cue', cueId: 5 }))
      .toMatchObject({ fixture: 'c', fixtureName: 'c', trigger: 'output2', label: 'B', rehearsed: true, source: 'cue', cueId: 5 })
    expect(parseFiredFrame({ trigger: 'x' })).toBeNull()
    expect(parseSkippedFrame({ reason: 'UNARMED', message: 'Not armed', tubes: [{ fixture: 'c', trigger: 'output1' }], source: 'cue', cueLabel: 'Q5' }))
      .toMatchObject({ reason: 'UNARMED', tubes: [{ fixture: 'c', trigger: 'output1' }], cueLabel: 'Q5' })
  })
})
