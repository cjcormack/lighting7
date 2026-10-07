// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { StageElementDto } from '@/api/stageElementApi'
import type { SceneryChange } from '@/api/sceneryApi'

function element(over: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...over,
  }
}

const ELEMENTS = [
  element({ id: 1, uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', params: { role: 'TABS', operation: 'DRAW' } }),
  element({ id: 2, uuid: 'moon', name: 'Moon', positionZ: 3, params: { flies: true, states: { trimM: 7 } } }),
]
vi.mock('@/store/stageElements', () => ({
  useStageElementListQuery: () => ({ data: ELEMENTS }),
}))

import { SceneryEditor } from './SceneryEditor'

const change = (over: Partial<SceneryChange>): SceneryChange => ({
  uuid: 'c', elementUuid: 'tabs', elementName: 'House tabs', elementKind: 'DRAPE', state: { open: 0 }, sortOrder: 0, ...over,
})

beforeEach(() => {
  vi.useFakeTimers()
  // The control's slider (Radix) measures its thumb.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/** A preset in one row's `SceneryControl`, by its word, and whether it reads as the one chosen. */
const preset = (row: string, word: string) =>
  within(document.querySelector(`[data-scenery-row="${row}"]`) as HTMLElement).getByRole('radio', { name: word })
const isOn = (el: HTMLElement) => el.getAttribute('data-state') === 'on'

describe('SceneryEditor', () => {
  it('adds a row for the first element not yet listed, and saves the whole list', () => {
    const onSave = vi.fn(() => Promise.resolve())
    render(<SceneryEditor projectId={1} scenery={[change({})]} withTime addLabel="Add change" idPrefix="t" onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: /Add change/ }))
    expect(onSave).toHaveBeenCalledWith([
      { elementUuid: 'tabs', state: { open: 0 } },
      { elementUuid: 'moon', state: { trimM: 3 } },
    ])
  })

  it('saves a typed time once, on blur, in milliseconds', () => {
    const onSave = vi.fn(() => Promise.resolve())
    render(<SceneryEditor projectId={1} scenery={[change({})]} withTime addLabel="Add change" idPrefix="t" onSave={onSave} />)
    const time = screen.getByLabelText('Time (ms)')
    fireEvent.focus(time)
    fireEvent.change(time, { target: { value: '4000' } })
    fireEvent.blur(time)
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave).toHaveBeenLastCalledWith([{ elementUuid: 'tabs', state: { open: 0 }, transitionMs: 4000 }])
    // The autosave pause after it finds nothing new to send.
    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(onSave).toHaveBeenCalledTimes(1)
  })

  it("keeps its own list over a refetch that lands while a save is in flight", () => {
    let release: () => void = () => {}
    const onSave = vi.fn(() => new Promise<void>((resolve) => { release = resolve }))
    const { rerender } = render(
      <SceneryEditor projectId={1} scenery={[change({})]} withTime={false} addLabel="Add state" idPrefix="t" onSave={onSave} />,
    )
    fireEvent.click(screen.getByRole('button', { name: /Remove House tabs/ }))
    expect(screen.queryByRole('button', { name: /Remove House tabs/ })).toBeNull()
    // A read from before the save comes back first: the row must not reappear under the operator.
    rerender(<SceneryEditor projectId={1} scenery={[change({ uuid: 'c2' })]} withTime={false} addLabel="Add state" idPrefix="t" onSave={onSave} />)
    expect(screen.queryByRole('button', { name: /Remove House tabs/ })).toBeNull()
    act(() => release())
  })

  it('draws each row through the SceneryControl: its presets, and a write that merges into the row', () => {
    const onSave = vi.fn(() => Promise.resolve())
    render(
      <SceneryEditor
        projectId={1}
        scenery={[change({}), change({ uuid: 'c2', elementUuid: 'moon', elementName: 'Moon', elementKind: 'OBJECT', state: { trimM: 7 }, sortOrder: 1 })]}
        withTime={false}
        addLabel="Add state"
        idPrefix="t"
        onSave={onSave}
      />,
    )
    expect(isOn(preset('tabs', 'Closed'))).toBe(true)
    expect(isOn(preset('moon', 'Out'))).toBe(true)
    fireEvent.click(preset('tabs', 'Drawn'))
    expect(onSave).toHaveBeenLastCalledWith([
      { elementUuid: 'tabs', state: { open: 1 } },
      { elementUuid: 'moon', state: { trimM: 7 } },
    ])
    // Visibility is its own row, and merges beside the travel rather than replacing it.
    fireEvent.click(within(document.querySelector('[data-scenery-row="moon"]') as HTMLElement).getByRole('radio', { name: 'Hidden' }))
    expect(onSave).toHaveBeenLastCalledWith([
      { elementUuid: 'tabs', state: { open: 1 } },
      { elementUuid: 'moon', state: { trimM: 7, visible: false } },
    ])
  })

  it('keeps the control’s draft over a refetch that lands while its save is in flight', () => {
    let release: () => void = () => {}
    const onSave = vi.fn(() => new Promise<void>((resolve) => { release = resolve }))
    const { rerender } = render(
      <SceneryEditor projectId={1} scenery={[change({})]} withTime={false} addLabel="Add state" idPrefix="t" onSave={onSave} />,
    )
    fireEvent.click(preset('tabs', 'Half'))
    expect(isOn(preset('tabs', 'Half'))).toBe(true)
    // A read from before the save comes back first, saying the tabs are still closed.
    rerender(<SceneryEditor projectId={1} scenery={[change({ uuid: 'c2' })]} withTime={false} addLabel="Add state" idPrefix="t" onSave={onSave} />)
    expect(isOn(preset('tabs', 'Half'))).toBe(true)
    // …and outlasts the control's own hold on the value, since the row's draft is what it draws.
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(isOn(preset('tabs', 'Half'))).toBe(true)
    act(() => release())
  })

  it('lets a stated key go, so it tracks again, without deleting the row', () => {
    const onSave = vi.fn(() => Promise.resolve())
    render(
      <SceneryEditor projectId={1} scenery={[change({ state: { open: 1, visible: false } })]} withTime={false} addLabel="Add state" idPrefix="t" onSave={onSave} />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Unset House tabs visibility' }))
    expect(onSave).toHaveBeenLastCalledWith([{ elementUuid: 'tabs', state: { open: 1 } }])
    // Now stating one key, the row offers no Unset: a change of nothing is refused by the desk.
    expect(screen.queryByRole('button', { name: /^Unset/ })).toBeNull()
  })

  it('shows a refusal and goes back to what the desk holds', async () => {
    const onSave = vi.fn(() => Promise.reject({ status: 400, data: { error: 'scenery[0] (\'Moon\'): open is a drawn drape\'s' } }))
    render(<SceneryEditor projectId={1} scenery={[change({})]} withTime={false} addLabel="Add state" idPrefix="t" onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: /Remove House tabs/ }))
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(screen.getByText(/open is a drawn drape's/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Remove House tabs/ })).toBeTruthy()
  })
})
