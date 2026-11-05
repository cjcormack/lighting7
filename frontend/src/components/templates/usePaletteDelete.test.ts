// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { describePaletteInUse } from './usePaletteDelete'

describe('describePaletteInUse', () => {
  it('names the values that follow a palette, which a forced delete keeps', () => {
    const line = describePaletteInUse({
      body: {
        error: 'in use',
        code: 'PALETTE_IN_USE',
        layerCount: 0,
        cueIds: [],
        cueNames: [],
        cueReferenceCount: 1,
        lookReferenceCount: 2,
        programmerReferenceCount: 1,
      },
      buskPageCount: 0,
    })
    expect(line).toBe('4 values following it, kept as they are now')
  })

  it('says nothing about values when none follow it', () => {
    const line = describePaletteInUse({
      body: { error: 'in use', code: 'PALETTE_IN_USE', layerCount: 1, cueIds: [3], cueNames: ['Q3'] },
      buskPageCount: 0,
    })
    expect(line).toBe('1 cue layer — Q3')
  })
})
