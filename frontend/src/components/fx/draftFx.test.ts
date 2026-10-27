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

  it('stores a step FX one step behind, and says no unit for any other (fx-settings plan D7)', () => {
    const pick = { fxType: 'ColourCycle', family: 'colour', attribute: 'rgbColour', cycle: 4, direction: 'FORWARD' as const, blendMode: 'OVERRIDE', parameters: {} }
    const target = { type: 'fixture' as const, fixture: { key: 'sh-1' } as Fixture }
    expect(storedFxOf({ ...pick, spread: 1, spreadUnit: 'STEP' }, target)).toMatchObject({ spread: 1, spreadUnit: 'STEP' })
    expect(storedFxOf(pick, target)).not.toHaveProperty('spreadUnit')
  })
})
