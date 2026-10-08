// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { lightingApi } from '@/api/lightingApi'
import type { KeyStackAnswer, KeyStackLayer } from '@/api/programmerWsApi'
import { FixtureSheetContext, type FixtureSheetContextValue } from './sheetContext'
import { describeStackLayer, formatAge, LayerStack } from './LayerStack'

const cueLabel = (id: number) => (id === 12 ? 'Q12' : undefined)

describe('describeStackLayer', () => {
  it('reads each kind in the Layers board words', () => {
    const layer = (over: Partial<KeyStackLayer>): KeyStackLayer => ({ kind: 'BASE', onStage: false, ...over })
    expect(describeStackLayer(layer({ kind: 'PROGRAMMER', owner: 'web', value: '204', ageMs: 120_000 }), cueLabel)).toEqual({
      title: 'You',
      detail: 'programmer · set 2 min ago',
      value: '204',
    })
    expect(
      describeStackLayer(layer({ kind: 'EFFECT', effectType: 'Pulse', cueId: 12, beatDivision: 0.25, heldBack: true }), cueLabel).detail,
    ).toContain("Q12's effect")
    expect(describeStackLayer(layer({ kind: 'CUE', cueId: 12, value: '153', layerSource: { kind: 'LOOK', id: 1, name: 'Warm' } as never }), cueLabel)).toEqual({
      title: 'Q12',
      detail: 'the Look layer Warm',
      value: '153',
    })
    expect(describeStackLayer(layer({ kind: 'BASE', value: '0' }), cueLabel).title).toBe('Base')
  })

  it('says a slot age in words', () => {
    expect(formatAge(1_000)).toBe('just now')
    expect(formatAge(40_000)).toBe('40 s ago')
    expect(formatAge(null)).toBeNull()
  })
})

describe('LayerStack', () => {
  it('asks the desk on open, draws held back, and asks again when the key moves', async () => {
    const answer: KeyStackAnswer = {
      targetType: 'fixture',
      targetKey: 'wash-2',
      propertyName: 'dimmer',
      blind: false,
      stacks: [
        {
          targetKey: 'wash-2',
          layers: [
            { kind: 'PROGRAMMER', onStage: true, owner: 'web', value: '204', ageMs: 0 },
            { kind: 'EFFECT', onStage: false, effectType: 'Pulse', cueId: 12, heldBack: true },
            { kind: 'BASE', onStage: false, value: '0' },
          ],
        },
      ],
    }
    const keyStack = vi.spyOn(lightingApi.programmer, 'keyStack').mockResolvedValue(answer)
    let moved: (() => void) | null = null
    vi.spyOn(lightingApi.programmer, 'subscribeToKey').mockImplementation((_k, _p, fn) => {
      moved = () => fn({})
      return { unsubscribe: () => {} }
    })
    const context = { cueLabel, connected: true } as unknown as FixtureSheetContextValue
    await act(async () => {
      render(
        <FixtureSheetContext.Provider value={context}>
          <LayerStack open onOpenChange={() => {}} headKey="wash-2" propertyName="dimmer" label="Dimmer" trigger={<button type="button">chip</button>} />
        </FixtureSheetContext.Provider>,
      )
    })
    expect(keyStack).toHaveBeenCalledWith('fixture', 'wash-2', 'dimmer')
    expect(await screen.findByText('held back')).toBeTruthy()
    expect(screen.getByText('on stage')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Clear yours — let it run' })).toBeTruthy()
    await act(async () => moved?.())
    expect(keyStack).toHaveBeenCalledTimes(2)
  })
})
