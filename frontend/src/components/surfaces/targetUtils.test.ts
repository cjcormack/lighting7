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
  } satisfies ControlSurfaceType

  it('names a control by its own label', () => {
    expect(controlLabel(profile, 'fader-1')).toBe('Fader 1')
  })

  it('falls back to the raw id for anything it does not know', () => {
    expect(controlLabel(profile, 'btn-99')).toBe('btn-99')
  })
})
