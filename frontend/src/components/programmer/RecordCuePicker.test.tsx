// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** The cue picker Merge, Replace and Remove draw: the show's cues in show order (D15). */
const store = vi.hoisted(() => ({
  stacks: [] as unknown[],
  cues: [] as unknown[],
}))
vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: store.stacks }) }))
vi.mock('@/store/cues', () => ({ useProjectCueListQuery: () => ({ data: store.cues, isLoading: false }) }))

import { RecordCuePicker } from './RecordCuePicker'

const place = (stackId: number, stackName: string, sortOrder: number, cueNumber: string | null) => ({
  stackId,
  stackName,
  sortOrder,
  cueNumber,
  cueNumberAuto: false,
})

beforeEach(() => {
  store.stacks = [
    { id: 9, name: 'Thunder', type: 'STACK', inRunningOrder: false },
    { id: 1, name: 'Act 1', type: 'STACK', inRunningOrder: true },
    { id: 8, name: 'Interval', type: 'SEPARATOR' },
    { id: 2, name: 'Act 2', type: 'STACK', inRunningOrder: true },
  ]
  store.cues = [
    { id: 30, name: 'Bows', entries: [place(2, 'Act 2', 1, '30')], cueType: 'STANDARD' },
    { id: 31, name: 'Crack', entries: [place(9, 'Thunder', 0, null)], cueType: 'STANDARD' },
    { id: 20, name: 'Storm', entries: [place(2, 'Act 2', 0, '20'), place(1, 'Act 1', 4, '5')], cueType: 'STANDARD' },
    { id: 10, name: 'Preset', entries: [place(1, 'Act 1', 0, '1')], cueType: 'STANDARD' },
    { id: 40, name: 'Wash', entries: [], cueType: 'STANDARD' },
  ]
})
afterEach(cleanup)

const picker = () => screen.getByRole('radiogroup', { name: 'Cue' })
const dividers = () => [...picker().querySelectorAll('[data-picker-divider]')].map((d) => d.textContent)
const rowNames = () => within(picker()).getAllByRole('radio').map((r) => r.closest('label')!.querySelector('span')!.textContent)

describe('RecordCuePicker', () => {
  it('lists the cues in show order under a divider per stack, the busk stacks after, loose last', () => {
    render(<RecordCuePicker projectId={6} value={null} onChange={() => {}} />)
    expect(dividers()).toEqual(['Act 1', 'Act 2', 'Thunder · busk', 'Loose'])
    expect(rowNames()).toEqual(['Preset', 'Storm', 'Bows', 'Crack', 'Wash'])
    // A shared cue is one row, under its first place, naming every place.
    expect(screen.getByRole('radio', { name: /Storm/ }).closest('label')!.textContent).toBe('StormQ5 · Act 1, Q20 · Act 2')
  })

  it('filters, keeping a divider only over a match', () => {
    render(<RecordCuePicker projectId={6} value={null} onChange={() => {}} />)
    fireEvent.change(screen.getByLabelText('Filter cues'), { target: { value: 'act 2' } })
    expect(dividers()).toEqual(['Act 1', 'Act 2'])
    expect(rowNames()).toEqual(['Storm', 'Bows'])
    fireEvent.change(screen.getByLabelText('Filter cues'), { target: { value: 'nothing like it' } })
    expect(dividers()).toEqual([])
    expect(screen.getByText('No cues match.')).toBeTruthy()
  })

  it('checks the cue it is given, and brings it into view once', () => {
    const at = (cueId: string | undefined) => Number(cueId ?? 0)
    const top = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetTop')
    const height = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
    const client = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight')
    Object.defineProperty(HTMLElement.prototype, 'offsetTop', { configurable: true, get() { return at(this.dataset.cueId) * 10 } })
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => 10 })
    Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get: () => 30 })
    try {
      const onChange = vi.fn()
      const { rerender } = render(<RecordCuePicker projectId={6} value={40} onChange={onChange} />)
      expect(screen.getByRole('radio', { name: /Wash/ })).toBeChecked()
      expect(picker().scrollTop).toBe(390)

      picker().scrollTop = 0
      rerender(<RecordCuePicker projectId={6} value={40} onChange={onChange} />)
      expect(picker().scrollTop).toBe(0)
      fireEvent.click(screen.getByRole('radio', { name: /Preset/ }))
      expect(onChange).toHaveBeenCalledWith(10)
    } finally {
      if (top) Object.defineProperty(HTMLElement.prototype, 'offsetTop', top)
      if (height) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', height)
      if (client) Object.defineProperty(Element.prototype, 'clientHeight', client)
    }
  })
})
