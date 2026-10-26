import { describe, expect, it } from 'vitest'
import type { Fixture } from '@/store/fixtures'
import { storedFxOf } from './draftFx'

describe('storedFxOf', () => {
  it('stores a draft pick on Fixtures, as every door that creates an FX does (FX editor follow-ups D8)', () => {
    const stored = storedFxOf(
      { fxType: 'ColourCycle', family: 'colour', attribute: 'rgbColour', cycle: 4, direction: 'FORWARD', blendMode: 'OVERRIDE', parameters: {} },
      { type: 'fixture', fixture: { key: 'sh-1' } as Fixture },
    )
    expect(stored).toMatchObject({ targetType: 'fixture', targetKey: 'sh-1', elementMode: 'WHOLE' })
  })
})
