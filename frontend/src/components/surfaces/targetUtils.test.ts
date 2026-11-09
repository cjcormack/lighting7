import { describe, expect, it } from 'vitest'
import { controlLabel } from './targetUtils'
import type { ControlSurfaceType } from '@/store/surfaces'

describe('controlLabel', () => {
  const profile = {
    typeKey: 'xtc',
    vendor: null,
    product: null,
    portPattern: null,
    className: 'X',
    banks: [],
    layout: null,
    controls: [
      {
        type: 'fader',
        controlId: 'fader-1',
        label: 'Fader 1',
        cc: 1,
        channel: 1,
        hasMotor: true,
        motorCc: null,
        touchNote: null,
        touchCc: null,
        resolution: 'SEVEN_BIT',
      },
    ],
    strips: [{ id: 'strip-1', fader: 'fader-1', select: 'btn-25', encoder: 'enc-1', flash: 'btn-1' }],
  } satisfies ControlSurfaceType

  it('names a control by its own label', () => {
    expect(controlLabel(profile, 'fader-1')).toBe('Fader 1')
  })

  // A strip id shares the `controlId` column with control ids but is not in `controls`, so
  // without the strip arm every strip-bound badge read its raw id.
  it('names a strip by the fader the operator can put a hand on', () => {
    expect(controlLabel(profile, 'strip-1')).toBe('Strip · Fader 1')
  })

  it('falls back to the raw id for anything it does not know', () => {
    expect(controlLabel(profile, 'btn-99')).toBe('btn-99')
  })
})
