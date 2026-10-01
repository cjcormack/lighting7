// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
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
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

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
