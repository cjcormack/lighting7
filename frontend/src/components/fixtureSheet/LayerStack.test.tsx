// @vitest-environment jsdom
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

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
  afterEach(() => vi.restoreAllMocks())

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
          <LayerStack open onOpenChange={() => {}} heads={[{ key: 'wash-2', name: 'Wash 2' }]} propertyName="dimmer" label="Dimmer" trigger={<button type="button">chip</button>} />
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

  it('asks a group once and lays its members out as one section per distinct stack', async () => {
    const programmer: KeyStackLayer = { kind: 'PROGRAMMER', onStage: true, owner: 'web', value: '255', ageMs: 0 }
    const cue: KeyStackLayer = { kind: 'CUE', onStage: true, cueId: 12, value: '153' }
    const answer: KeyStackAnswer = {
      targetType: 'group',
      targetKey: 'front',
      propertyName: 'dimmer',
      blind: false,
      stacks: [
        { targetKey: 'hex-1', layers: [programmer] },
        { targetKey: 'hex-2', layers: [programmer] },
        { targetKey: 'hex-3', layers: [cue] },
      ],
    }
    const keyStack = vi.spyOn(lightingApi.programmer, 'keyStack').mockResolvedValue(answer)
    vi.spyOn(lightingApi.programmer, 'subscribeToKey').mockReturnValue({ unsubscribe: () => {} })
    const clear = vi.spyOn(lightingApi.programmer, 'clearEntry')
    const context = { cueLabel, connected: true } as unknown as FixtureSheetContextValue
    const heads = [
      { key: 'hex-1', name: 'Hex 1' },
      { key: 'hex-2', name: 'Hex 2' },
      { key: 'hex-3', name: 'Hex 3' },
    ]
    await act(async () => {
      render(
        <FixtureSheetContext.Provider value={context}>
          <LayerStack open onOpenChange={() => {}} heads={heads} group="front" propertyName="dimmer" label="Dimmer" trigger={<button type="button">chip</button>} />
        </FixtureSheetContext.Provider>,
      )
    })
    expect(keyStack).toHaveBeenCalledTimes(1)
    expect(keyStack).toHaveBeenCalledWith('group', 'front', 'dimmer')
    const sections = await screen.findAllByTestId('layer-stack').then(() => document.querySelectorAll('[data-stack-heads]'))
    expect([...sections].map((s) => s.textContent)).toEqual(['Hex 1, Hex 2', 'Hex 3'])
    fireEvent.click(screen.getByRole('button', { name: 'Clear yours' }))
    // The group's *All*: one group clear, not one per member.
    expect(clear).toHaveBeenCalledTimes(1)
    expect(clear).toHaveBeenCalledWith('group', 'front', 'dimmer', expect.any(Number))
  })

  it('re-asks once for a move that touches every picked head, not once per head', async () => {
    const keyStack = vi
      .spyOn(lightingApi.programmer, 'keyStack')
      .mockImplementation(async (targetType, targetKey, propertyName) => ({
        targetType,
        targetKey,
        propertyName,
        blind: false,
        stacks: [{ targetKey, layers: [{ kind: 'BASE', onStage: true, value: '0' }] }],
      }))
    const moves: (() => void)[] = []
    vi.spyOn(lightingApi.programmer, 'subscribeToKey').mockImplementation((_k, _p, fn) => {
      moves.push(() => fn({}))
      return { unsubscribe: () => {} }
    })
    const context = { cueLabel, connected: true } as unknown as FixtureSheetContextValue
    const heads = ['p1', 'p2', 'p3'].map((key) => ({ key, name: key }))
    await act(async () => {
      render(
        <FixtureSheetContext.Provider value={context}>
          <LayerStack open onOpenChange={() => {}} heads={heads} propertyName="rgbColour" label="Colour" trigger={<button type="button">chip</button>} />
        </FixtureSheetContext.Provider>,
      )
    })
    expect(keyStack).toHaveBeenCalledTimes(3)
    // One write moves all three keys: one more ask of three heads, not three asks of three.
    await act(async () => moves.forEach((move) => move()))
    expect(keyStack).toHaveBeenCalledTimes(6)
  })

  it('draws the heads the desk answered for when another head’s ask fails', async () => {
    vi.spyOn(lightingApi.programmer, 'keyStack').mockImplementation(async (targetType, targetKey, propertyName) => {
      if (targetKey === 'p2') throw new Error('no such head')
      return { targetType, targetKey, propertyName, blind: false, stacks: [{ targetKey, layers: [{ kind: 'BASE', onStage: true, value: '0' }] }] }
    })
    vi.spyOn(lightingApi.programmer, 'subscribeToKey').mockReturnValue({ unsubscribe: () => {} })
    const context = { cueLabel, connected: true } as unknown as FixtureSheetContextValue
    await act(async () => {
      render(
        <FixtureSheetContext.Provider value={context}>
          <LayerStack
            open
            onOpenChange={() => {}}
            heads={[
              { key: 'p1', name: 'P1' },
              { key: 'p2', name: 'P2' },
            ]}
            propertyName="rgbColour"
            label="Colour"
            trigger={<button type="button">chip</button>}
          />
        </FixtureSheetContext.Provider>,
      )
    })
    expect(document.querySelector('[data-stack-heads]')?.textContent).toBe('P1')
  })
})
