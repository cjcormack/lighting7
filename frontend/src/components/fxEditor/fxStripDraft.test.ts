// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { previewDraftOf } from './fxStripDraft'

const stored = { fxType: 'ColourCycle', parameters: {}, cycle: 4, blendMode: 'OVERRIDE', order: 'NORMAL' }

describe('previewDraftOf', () => {
  it('previews a stored FX naming no element mode as Each fixture, not the add route’s Fixtures', () => {
    expect(previewDraftOf(stored, 'rgbColour').elementMode).toBe('PER_FIXTURE')
    expect(previewDraftOf({ ...stored, elementMode: 'WHOLE' }, 'rgbColour').elementMode).toBe('WHOLE')
  })
})
