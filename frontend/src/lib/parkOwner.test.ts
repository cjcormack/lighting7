import { describe, expect, it } from 'vitest'
import { overVenuePark, parkLabel, unparkConfirm, unparkLabel } from './parkOwner'

describe('parkOwner', () => {
  it('names whose a park is', () => {
    expect(parkLabel({ value: 200, owner: 'SHOW' })).toBe('Parked at 200')
    expect(parkLabel({ value: 255, owner: 'VENUE' })).toBe('Parked by the venue at 255')
    expect(parkLabel({ value: 200, owner: 'SHOW', venueValue: 255 })).toBe("Parked at 200 · over the venue's 255")
  })

  it('says what unparking does: unpark, release for this show, or back to the venue’s', () => {
    expect(unparkLabel({ owner: 'SHOW' })).toBe('Unpark')
    expect(unparkLabel({ owner: 'VENUE' })).toBe('Release for this show')
    expect(unparkLabel({ owner: 'SHOW', venueValue: 255 })).toBe("Back to the venue's park (255)")
  })

  it('names the venue’s parks in an unpark confirm, and says nothing of them when there are none', () => {
    expect(unparkConfirm([{ owner: 'SHOW' }, { owner: 'SHOW' }], 'on Front wash')).toBe('Unpark 2 channel(s) on Front wash?')
    const mixed = unparkConfirm(
      [{ owner: 'SHOW' }, { owner: 'VENUE' }, { owner: 'VENUE' }, { owner: 'SHOW', venueValue: 255 }],
      'in universe 0',
    )
    expect(mixed).toContain('Unpark 4 channel(s) in universe 0?')
    expect(mixed).toContain("2 are the venue's: this releases them for this show — the venue keeps them.")
    expect(mixed).toContain("1 goes back to the venue's park.")
    expect(unparkConfirm([{ owner: 'VENUE' }], '')).toContain("1 is the venue's: this releases it for this show")
  })

  it('knows a show park over a venue park', () => {
    expect(overVenuePark({ owner: 'SHOW', venueValue: 255 })).toBe(true)
    expect(overVenuePark({ owner: 'SHOW' })).toBe(false)
    expect(overVenuePark({ owner: 'VENUE', venueValue: 255 })).toBe(false)
    expect(overVenuePark(undefined)).toBe(false)
  })
})
