import { describe, expect, it, vi } from 'vitest'
import type { KeyStackLayer } from '@/api/programmerWsApi'

vi.mock('@/api/lightingApi', () => ({ lightingApi: {} }))

const { topValue, underneathLabel } = await import('./useUnderneath')

/** A lane's *Underneath* (FX editor plan D3): `programmer.keyStack`'s top value, as the desk folds the lane over it. */
const stack: KeyStackLayer[] = [
  { kind: 'PROGRAMMER_FX', onStage: true, fxId: 4 },
  { kind: 'PROGRAMMER', onStage: true, owner: 'web', value: '204' },
  { kind: 'FX', onStage: false, fxId: 1 },
  { kind: 'CUE', onStage: false, cueId: 12, value: '153' },
  { kind: 'BASE', onStage: false, value: '0' },
]
const cueLabel = (id: number) => (id === 12 ? 'Q12' : undefined)

describe('Underneath', () => {
  it('is the top value under the lane’s FX: your value over the cue’s over the base', () => {
    expect(underneathLabel(topValue(stack), cueLabel)).toBe('Local 80 %')
    expect(underneathLabel(topValue(stack.slice(2)), cueLabel)).toBe('Q12 60 %')
    expect(underneathLabel(topValue(stack.slice(4)), cueLabel)).toBe('Base 0 %')
  })

  it('passes over the programmer while it is blind: its slots hold nothing on stage', () => {
    expect(underneathLabel(topValue(stack, true), cueLabel)).toBe('Q12 60 %')
  })

  it('names a pressed layer’s slot by its layer', () => {
    const layer: KeyStackLayer = { kind: 'PROGRAMMER', onStage: true, owner: 'layers', value: '102', layerSource: { name: 'Night' } as never }
    expect(underneathLabel(topValue([layer]), cueLabel)).toBe('Night 40 %')
  })
})
