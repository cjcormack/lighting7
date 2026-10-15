import { describe, expect, it } from 'vitest'
import { locateBytesLabel, locateUnitLabel, locateValueLabel, storedLocateMessage } from './storedLocate'

describe('storedLocate', () => {
  it('reads a unit and its pan and tilt on the centre-relative scale, the bytes on the hover', () => {
    expect(locateUnitLabel({ head: null })).toBe('Fixture')
    expect(locateUnitLabel({ head: 2 })).toBe('Head 3')
    expect(locateValueLabel({ pan: 140, tilt: 96 })).toBe('Pan +12 · Tilt -32')
    expect(locateValueLabel({ pan: 128, tilt: 0 })).toBe('Pan 0 · Tilt -128')
    expect(locateBytesLabel({ pan: 140, tilt: 96, panFine: 12, tiltFine: 200 })).toBe('pan 140 (fine 12), tilt 96 (fine 200)')
    expect(locateBytesLabel({ pan: 140, tilt: 96 })).toBe('pan 140, tilt 96')
  })

  it('words a store or clear by fixtures changed and every skip by the desk’s reason', () => {
    const one = { patchId: 1, key: 'mac-1', locate: [] }
    expect(storedLocateMessage({ stored: [one] }, 'stored')).toEqual({ ok: true, message: 'Locate stored on 1 fixture' })
    expect(storedLocateMessage({ stored: [one, { ...one, patchId: 2 }] }, 'cleared').message).toBe('Locate cleared on 2 fixtures')
    expect(
      storedLocateMessage({ stored: [], skipped: [{ target: { type: 'fixture', key: 'hex-1' }, reason: 'does not pan and tilt' }] }, 'stored'),
    ).toEqual({ ok: false, message: 'No locate stored — hex-1 does not pan and tilt' })
    expect(storedLocateMessage({}, 'stored')).toEqual({ ok: false, message: 'No locate stored' })
  })

  it('groups a whole rig’s skips by reason and names only the first few keys', () => {
    const skip = (key: string, reason = 'does not pan and tilt') => ({ target: { type: 'fixture' as const, key }, reason })
    const lanterns = Array.from({ length: 40 }, (_, i) => skip(`par-${i + 1}`))
    expect(storedLocateMessage({ stored: [{ patchId: 1, key: 'mac-1', locate: [] }], skipped: [...lanterns, skip('mac-2', 'no output for this unit')] }, 'stored'))
      .toEqual({ ok: true, message: 'Locate stored on 1 fixture — par-1, par-2, par-3 +37 more do not pan and tilt; mac-2 no output for this unit' })
  })
})
