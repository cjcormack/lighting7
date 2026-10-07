// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { StageElementDto } from '@/api/stageElementApi'
import type { SceneryState } from '@/api/sceneryApi'
import { shownSceneryState } from '@/lib/scenery'

/**
 * Radix's slider computes a value from the pointer against its track's box, which jsdom lays out at
 * zero — so the drag is driven through a stand-in with the same two callbacks: `change` is a move,
 * `pointerUp` the release (`onValueCommit`).
 */
vi.mock('@/components/ui/slider', () => ({
  Slider: ({
    min,
    max,
    step,
    value,
    disabled,
    onValueChange,
    onValueCommit,
    'aria-label': label,
  }: {
    min: number
    max: number
    step: number
    value: number[]
    disabled?: boolean
    onValueChange?: (v: number[]) => void
    onValueCommit?: (v: number[]) => void
    'aria-label'?: string
  }) => (
    <input
      type="range"
      aria-label={label}
      min={min}
      max={max}
      step={step}
      value={value[0]}
      disabled={disabled}
      onChange={(e) => onValueChange?.([Number(e.target.value)])}
      onPointerUp={(e) => onValueCommit?.([Number((e.target as HTMLInputElement).value)])}
    />
  ),
}))

import { SceneryControl, SCENERY_PUSH_MS } from './SceneryControl'

function element(over: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...over,
  }
}

const TABS = element({ uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', layer: 'VENUE', params: { role: 'TABS', operation: 'DRAW' } })
const BORDER = element({ uuid: 'border', name: 'Border 2', kind: 'DRAPE', layer: 'VENUE', positionZ: 6, params: { role: 'BORDER', operation: 'FLY', states: { trimM: 9 } } })
const MOON = element({ uuid: 'moon', name: 'Moon', positionZ: 3, params: { flies: true, states: { trimM: 7 } } })
const SOFA = element({ uuid: 'sofa', name: 'Sofa' })

function draw(el: StageElementDto, state: SceneryState = shownSceneryState(el), props: Partial<React.ComponentProps<typeof SceneryControl>> = {}) {
  const onWrite = vi.fn()
  const view = render(<SceneryControl element={el} state={state} onWrite={onWrite} {...props} />)
  return { onWrite, ...view }
}

const group = (name: string) => screen.queryByRole('radiogroup', { name })
const words = (name: string) => within(group(name)!).getAllByRole('radio').map((r) => r.textContent)
const field = (name: RegExp) => screen.getByRole('spinbutton', { name }) as HTMLInputElement
const slider = () => screen.getByRole('slider') as HTMLInputElement

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('presets and ranges, per kind', () => {
  it('a DRAW drape: Closed · Half · Drawn, and open from 0 to 1 shown as a percentage', () => {
    draw(TABS, { visible: true, open: 0.4 })
    expect(words('House tabs preset')).toEqual(['Closed', 'Half', 'Drawn'])
    expect(slider().min).toBe('0')
    expect(slider().max).toBe('1')
    expect(field(/open \(%\)/).value).toBe('40')
    // 0.4 sits on no preset: none reads as chosen.
    expect(within(group('House tabs preset')!).getAllByRole('radio').some((r) => r.getAttribute('data-state') === 'on')).toBe(false)
    expect(document.querySelector('[data-editor-unit]')!.textContent).toBe('%')
  })

  it('a FLY drape: In · Out, and its trim between its Z and its stored trim, in metres', () => {
    draw(BORDER)
    expect(words('Border 2 preset')).toEqual(['In', 'Out'])
    expect(slider().min).toBe('6')
    expect(slider().max).toBe('9')
    // Its base is its stored trim: it rests out.
    expect(within(group('Border 2 preset')!).getByRole('radio', { name: 'Out' }).getAttribute('data-state')).toBe('on')
    expect(field(/trim \(m\)/).value).toBe('9')
    expect(document.querySelector('[data-editor-unit]')!.textContent).toBe('m')
  })

  it('a flown object: In at its Z and Out at its trim; one with no stored trim has In and no slider', () => {
    const { unmount } = draw(MOON, { visible: true, trimM: 5 })
    expect(words('Moon preset')).toEqual(['In', 'Out'])
    expect(slider().min).toBe('3')
    expect(slider().max).toBe('7')
    expect(field(/trim \(m\)/).value).toBe('5')
    unmount()
    draw(element({ uuid: 'lamp', name: 'Lamp', positionZ: 4, params: { flies: true } }))
    expect(words('Lamp preset')).toEqual(['In'])
    expect(screen.queryByRole('slider')).toBeNull()
  })

  it('a piece that only takes visible: Shown · Hidden, and nothing else', () => {
    draw(SOFA)
    expect(group('Sofa preset')).toBeNull()
    expect(screen.queryByRole('slider')).toBeNull()
    expect(words('Sofa visibility')).toEqual(['Shown', 'Hidden'])
    expect(within(group('Sofa visibility')!).getByRole('radio', { name: 'Shown' }).getAttribute('data-state')).toBe('on')
  })

  it('labels the element with the kit’s label, says what holds it, and has no footer', () => {
    draw(MOON, undefined, { readout: 'held by the programmer' })
    expect(screen.getByText('Moon')).toBeTruthy()
    expect(document.querySelector('[data-editor-readout]')!.textContent).toBe('held by the programmer')
    expect(document.querySelector('[data-editor-footer]')).toBeNull()
  })
})

describe('writes', () => {
  it('a preset and a visibility press each land at once, with only their own key', () => {
    const { onWrite } = draw(TABS)
    fireEvent.click(screen.getByRole('radio', { name: 'Drawn' }))
    expect(onWrite).toHaveBeenLastCalledWith({ open: 1 })
    fireEvent.click(screen.getByRole('radio', { name: 'Hidden' }))
    expect(onWrite).toHaveBeenLastCalledWith({ visible: false })
    expect(onWrite).toHaveBeenCalledTimes(2)
  })

  it('writes a drag as it goes at the 33 ms floor, holding the latest move, and the release always lands', () => {
    const { onWrite } = draw(TABS)
    expect(SCENERY_PUSH_MS).toBe(33)
    fireEvent.change(slider(), { target: { value: '0.2' } })
    expect(onWrite).toHaveBeenCalledTimes(1)
    expect(onWrite).toHaveBeenLastCalledWith({ open: 0.2 })
    // Inside the floor: held, not dropped — and the latest move is the one that goes.
    fireEvent.change(slider(), { target: { value: '0.3' } })
    fireEvent.change(slider(), { target: { value: '0.4' } })
    expect(onWrite).toHaveBeenCalledTimes(1)
    act(() => {
      vi.advanceTimersByTime(SCENERY_PUSH_MS)
    })
    expect(onWrite).toHaveBeenCalledTimes(2)
    expect(onWrite).toHaveBeenLastCalledWith({ open: 0.4 })
    // The release goes now, whatever the floor says.
    fireEvent.change(slider(), { target: { value: '0.5' } })
    fireEvent.pointerUp(slider())
    expect(onWrite).toHaveBeenLastCalledWith({ open: 0.5 })
    const sent = onWrite.mock.calls.length
    act(() => {
      vi.advanceTimersByTime(SCENERY_PUSH_MS * 2)
    })
    // Nothing armed behind the release.
    expect(onWrite).toHaveBeenCalledTimes(sent)
  })

  it('a release writes even when it ends on the value the last gesture ended on', () => {
    const { onWrite } = draw(TABS)
    fireEvent.change(slider(), { target: { value: '0.5' } })
    fireEvent.pointerUp(slider())
    const sent = onWrite.mock.calls.length
    // A fresh gesture that lets go where the last one did — a press on the thumb, no move: the piece
    // may have moved by another route in between, so it is not deduplicated against the last end.
    fireEvent.pointerUp(slider())
    expect(onWrite).toHaveBeenCalledTimes(sent + 1)
    expect(onWrite).toHaveBeenLastCalledWith({ open: 0.5 })
  })

  it('draws a moved value until the host catches up, then lets it go', () => {
    const { rerender } = draw(TABS, { visible: true, open: 0 })
    const write = vi.fn()
    rerender(<SceneryControl element={TABS} state={{ visible: true, open: 0 }} onWrite={write} />)
    fireEvent.click(screen.getByRole('radio', { name: 'Half' }))
    // The desk has not echoed yet: the control keeps what the operator chose.
    expect(screen.getByRole('radio', { name: 'Half' }).getAttribute('data-state')).toBe('on')
    // The echo lands; then another route closes the tabs, and the control follows it.
    rerender(<SceneryControl element={TABS} state={{ visible: true, open: 0.5 }} onWrite={write} />)
    rerender(<SceneryControl element={TABS} state={{ visible: true, open: 0 }} onWrite={write} />)
    expect(screen.getByRole('radio', { name: 'Closed' }).getAttribute('data-state')).toBe('on')
  })

  it('a typed field value is clamped into the range and written in the state’s own unit', () => {
    const { onWrite } = draw(MOON)
    const trim = field(/trim \(m\)/)
    fireEvent.change(trim, { target: { value: '12' } })
    expect(onWrite).toHaveBeenLastCalledWith({ trimM: 7 })
    cleanup()
    const tabs = draw(TABS)
    fireEvent.change(field(/open \(%\)/), { target: { value: '25' } })
    expect(tabs.onWrite).toHaveBeenLastCalledWith({ open: 0.25 })
  })

  it('in release mode writes nothing while dragging, and once on release', () => {
    const { onWrite } = draw(TABS, undefined, { commit: 'release' })
    fireEvent.change(slider(), { target: { value: '0.2' } })
    fireEvent.change(slider(), { target: { value: '0.6' } })
    act(() => {
      vi.advanceTimersByTime(SCENERY_PUSH_MS * 3)
    })
    expect(onWrite).not.toHaveBeenCalled()
    fireEvent.pointerUp(slider())
    expect(onWrite).toHaveBeenCalledTimes(1)
    expect(onWrite).toHaveBeenLastCalledWith({ open: 0.6 })
  })

  it('a press on the lit preset or visibility writes it too: holding a piece where it already is', () => {
    // At its base the tabs read Closed and Shown — a press there pins the piece over a later cue.
    const { onWrite } = draw(TABS)
    expect(screen.getByRole('radio', { name: 'Closed' }).getAttribute('data-state')).toBe('on')
    fireEvent.click(screen.getByRole('radio', { name: 'Closed' }))
    expect(onWrite).toHaveBeenLastCalledWith({ open: 0 })
    fireEvent.click(screen.getByRole('radio', { name: 'Shown' }))
    expect(onWrite).toHaveBeenLastCalledWith({ visible: true })
    // Once each: Radix's deselect (`''`) writes nothing beside the press.
    expect(onWrite).toHaveBeenCalledTimes(2)
  })

  it('a host stating part of the state: what it does not state is muted, and each stated key can be unset', () => {
    const onUnstate = vi.fn()
    draw(TABS, shownSceneryState(TABS, { open: 1, visible: false }), { stated: new Set(['open', 'visible']), onUnstate })
    fireEvent.click(screen.getByRole('button', { name: 'Unset House tabs visibility' }))
    expect(onUnstate).toHaveBeenLastCalledWith('visible')
    fireEvent.click(screen.getByRole('button', { name: 'Unset House tabs opening' }))
    expect(onUnstate).toHaveBeenLastCalledWith('open')
    cleanup()
    // Stating one key: it cannot be unset (a change of nothing is refused), and visibility is muted.
    draw(TABS, shownSceneryState(TABS, { open: 1 }), { stated: new Set(['open']), onUnstate })
    expect(screen.queryByRole('button', { name: /^Unset/ })).toBeNull()
    expect(group('House tabs visibility')!.className).toContain('opacity-60')
    expect(group('House tabs visibility')!.getAttribute('title')).toMatch(/Not set by this row/)
    expect(group('House tabs preset')!.className).not.toContain('opacity-60')
  })

  it('writes nothing when disabled', () => {
    const { onWrite } = draw(TABS, undefined, { disabled: true })
    fireEvent.click(screen.getByRole('radio', { name: 'Drawn' }))
    expect(onWrite).not.toHaveBeenCalled()
    expect(slider().disabled).toBe(true)
  })
})
