import { describe, expect, it } from 'vitest'
import { parseCueHoldsFrame } from '@/api/cueHoldsApi'
import { updateStageLines } from './updateStage'

describe('updateStageLines', () => {
  it('names the values and the effects, leaving out a count of 0', () => {
    expect(updateStageLines({ rows: 3, effects: { edited: 1, stopped: 2, started: 0 }, fadeMs: 2000, curve: 'LINEAR' })).toEqual([
      '3 values change, over its 2.0s fade',
      '1 effect edited in place · 2 stopped',
    ])
    expect(updateStageLines({ rows: 0, effects: { edited: 0, stopped: 0, started: 1 }, fadeMs: 2000, curve: 'LINEAR' })).toEqual(['1 started'])
  })

  it('says a snap cue lands at once', () => {
    expect(updateStageLines({ rows: 1, effects: { edited: 0, stopped: 0, started: 0 }, fadeMs: 0, curve: 'LINEAR' })).toEqual([
      '1 value change, at once — it is a snap cue',
    ])
  })

  it('says when the stage already shows the cue', () => {
    expect(updateStageLines({ rows: 0, effects: { edited: 0, stopped: 0, started: 0 }, fadeMs: 2000, curve: 'LINEAR' })).toEqual([
      'The stage already shows the cue as stored.',
    ])
  })
})

describe('cueHolds.state edited', () => {
  it('is read off a held cue the desk marks, and absent otherwise', () => {
    const frame = parseCueHoldsFrame({
      projectId: 3,
      cues: [
        { cueId: 11, priority: 5, holds: [{ kind: 'LOOSE', seq: 1 }], edited: true },
        { cueId: 12, priority: 4, holds: [{ kind: 'STACK', stackId: 4, entryId: 22 }], stackId: 4 },
      ],
    })
    expect(frame.cues.map((c) => [c.cueId, c.edited])).toEqual([
      [11, true],
      [12, undefined],
    ])
  })
})
