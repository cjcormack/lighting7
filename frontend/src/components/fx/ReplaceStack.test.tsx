// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { ActiveFx } from '@/store/fixtureFx'
import { chan, colourProp, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'
import { fxLanes } from '@/components/fxEditor/fxEditorList'

/**
 * *Replace · Stack* (programmer-toolbar plan D8): one component and one rule for the FX editor's lane
 * and the picker's FX arm — asked only where a Local FX of the family already reaches a selected
 * head, other than the one the session started; the picker asks by the attribute a tap lands on.
 */
const HEX = [1, 2, 3, 4].map((n) =>
  makeFixture(`hex-${n}`, [sliderProp('dimmer', 'dimmer', chan(n * 10)), colourProp('rgbColour', chan(n * 10 + 1), chan(n * 10 + 2), chan(n * 10 + 3))]),
)
const wire = vi.hoisted(() => ({ active: [] as ActiveFx[] }))
const fixtures = vi.hoisted(() => ({ list: null as unknown }))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: fixtures.list ?? HEX, fixtureByKey: new Map() }) }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveFxQuery: () => ({ data: wire.active }),
  useFxLibraryQuery: () => ({ data: [{ name: 'Rainbow Cycle' }] }),
}))

const { ReplaceStack, holderWords, localHolderIn, useLocalFxHolder } = await import('./ReplaceStack')
const { useReplaceStack } = await import('./useReplaceStack')

const SELECTED = HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))
const colourFx = (id: number, over: Partial<ActiveFx> = {}) =>
  makeActiveFx({ id, fxType: 'RainbowCycle', attribute: 'rgbColour', targetType: 'selection', targets: SELECTED, ...over })
const lanesOf = (effects: ActiveFx[]) => fxLanes({ effects, targets: SELECTED, fixtures: HEX, layerPosition: new Map(), answer: null })

afterEach(() => {
  cleanup()
  wire.active = []
  fixtures.list = null
})

describe('localHolderIn', () => {
  it('finds a Local FX of the family on the selection', () => {
    expect(localHolderIn(lanesOf([colourFx(1)]), 'COLOUR')?.effect.id).toBe(1)
    expect(localHolderIn(lanesOf([colourFx(1)]), 'INTENSITY')).toBeNull()
  })

  it('does not count a layer’s or a cue’s FX, which a press never takes heads off', () => {
    expect(localHolderIn(lanesOf([colourFx(1, { programmerLayerId: 4, paletteId: 2 })]), 'COLOUR')).toBeNull()
    expect(localHolderIn(lanesOf([colourFx(1, { programmerOwned: false, cueId: 9 })]), 'COLOUR')).toBeNull()
  })

  it('leaves out the FX the session started itself', () => {
    expect(localHolderIn(lanesOf([colourFx(1)]), 'COLOUR', 1)).toBeNull()
    expect(localHolderIn(lanesOf([colourFx(1), colourFx(2)]), 'COLOUR', 2)?.effect.id).toBe(1)
  })
})

describe('the words', () => {
  it('says what already runs and on how many of the heads', () => {
    expect(holderWords({ reached: 6, of: 6 }, 'Rainbow Cycle')).toBe('Rainbow Cycle on all 6 heads already')
    expect(holderWords({ reached: 4, of: 6 }, 'Pulse')).toBe('Pulse on 4 of 6 heads already')
    expect(holderWords({ reached: 1, of: 1 }, 'Pulse')).toBe('Pulse on this head already')
    expect(holderWords({ reached: 2, of: 2 }, 'Pulse')).toBe('Pulse on both heads already')
  })
})

describe('useLocalFxHolder', () => {
  it('reads the running list over the targets, by the family of the attribute a tap lands on', () => {
    wire.active = [colourFx(5, { targets: SELECTED.slice(0, 2) })]
    const { result } = renderHook(() => useLocalFxHolder(SELECTED))
    expect(result.current('rgbColour')).toBe('Rainbow Cycle on 2 of 4 heads already')
    expect(result.current('rgbColour', 5)).toBeNull()
    expect(result.current('dimmer')).toBeNull()
  })

  it('asks of uv as Colour, where the desk files it, whichever tab the FX sits under', () => {
    const UV = [makeFixture('uv-1', [sliderProp('uv', 'uv', chan(1))])]
    const onUv = [{ type: 'fixture' as const, key: 'uv-1' }]
    fixtures.list = UV
    wire.active = [makeActiveFx({ id: 6, fxType: 'Pulse', attribute: 'uv', targetType: 'selection', targets: onUv })]
    const { result } = renderHook(() => useLocalFxHolder(onUv))
    expect(result.current('uv')).toBe('Pulse on this head already')
  })

  it('asks nothing over no targets', () => {
    wire.active = [colourFx(5)]
    const { result } = renderHook(() => useLocalFxHolder([]))
    expect(result.current('rgbColour')).toBeNull()
  })
})

describe('useReplaceStack', () => {
  const useDrawn = (current: { fxId: number } | null, asks = true) => useReplaceStack(SELECTED, asks, current)
  /** The segment drawn on its own, so its radios can be read and pressed. */
  const draw = (segment: ReactNode) => render(<>{segment}</>)

  it('asks of a landing a held FX reaches, starting on Replace, and Stack answers yes only there', () => {
    wire.active = [colourFx(5)]
    const { result } = renderHook(({ current }) => useDrawn(current), { initialProps: { current: null as { fxId: number } | null } })
    expect(result.current.segment).toBeNull()
    act(() => result.current.onLanding(['dimmer']))
    expect(result.current.segment).toBeNull()
    act(() => result.current.onLanding(['dimmer', 'rgbColour']))
    draw(result.current.segment)
    expect(screen.getByText('Rainbow Cycle on all 4 heads already')).toBeTruthy()
    expect(screen.getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(result.current.stackOn('rgbColour')).toBe(false)
    fireEvent.click(screen.getByRole('radio', { name: 'Stack' }))
    expect(result.current.stackOn('rgbColour')).toBe(true)
    expect(result.current.stackOn('dimmer')).toBe(false)
  })

  it('never counts the session’s own FX as already here', () => {
    wire.active = [colourFx(5)]
    const { result } = renderHook(() => useDrawn({ fxId: 5 }))
    act(() => result.current.onLanding(['rgbColour']))
    expect(result.current.segment).toBeNull()
    expect(result.current.stackOn('rgbColour')).toBe(false)
  })

  it('puts the question back on Replace at every start, and on reset', () => {
    wire.active = [colourFx(5)]
    const { result, rerender } = renderHook(({ current }) => useDrawn(current), { initialProps: { current: null as { fxId: number } | null } })
    act(() => result.current.onLanding(['rgbColour']))
    const { unmount } = draw(result.current.segment)
    fireEvent.click(screen.getByRole('radio', { name: 'Stack' }))
    expect(result.current.stackOn('rgbColour')).toBe(true)
    unmount()
    rerender({ current: { fxId: 8 } })
    expect(result.current.stackOn('rgbColour')).toBe(false)
    draw(result.current.segment)
    expect(screen.getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('radio', { name: 'Stack' }))
    expect(result.current.stackOn('rgbColour')).toBe(true)
    act(() => result.current.reset())
    expect(result.current.stackOn('rgbColour')).toBe(false)
  })

  it('asks nothing where the host does not ask', () => {
    wire.active = [colourFx(5)]
    const { result } = renderHook(() => useDrawn(null, false))
    act(() => result.current.onLanding(['rgbColour']))
    expect(result.current.segment).toBeNull()
  })
})

describe('ReplaceStack', () => {
  it('starts on Replace and turns to Stack, saying what each does', () => {
    const onChange = vi.fn()
    const { rerender } = render(<ReplaceStack stack={false} onChange={onChange} holder="Pulse on 4 of 6 heads already" />)
    expect(screen.getByText('Pulse on 4 of 6 heads already')).toBeTruthy()
    expect(screen.getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(document.querySelector('[data-replace-stack-note]')?.textContent).toMatch(/Takes these heads off it/)
    fireEvent.click(screen.getByRole('radio', { name: 'Stack' }))
    expect(onChange).toHaveBeenCalledWith(true)
    rerender(<ReplaceStack stack onChange={onChange} />)
    expect(screen.getByRole('radio', { name: 'Stack' })).toHaveAttribute('aria-checked', 'true')
    expect(document.querySelector('[data-replace-stack-note]')?.textContent).toMatch(/takes nothing off/)
  })
})
