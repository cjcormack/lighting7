// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Command } from 'cmdk'

const setDeskSelection = vi.fn()
vi.mock('@/store/selection', () => ({
  setDeskSelection: (...args: unknown[]) => setDeskSelection(...args),
  useDeskSelectionQuery: () => ({
    data: { targets: [{ type: 'fixture', key: 'spot-1' }], families: ['POSITION'], source: null },
  }),
}))
vi.mock('@/hooks/useFixtureColourIndex', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useFixtureColourIndex')>()),
  useFixtureColourIndex: () =>
    new Map([
      ['hex-1', 'red'],
      ['hex-2', 'red'],
      ['infra', 'red'],
      ['spot-1', 'lime'],
    ]),
}))

import { SelectByColourItems } from './SelectByColour'

beforeEach(() => {
  // cmdk measures its list and scrolls its active item into view; jsdom has neither.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  Element.prototype.scrollIntoView = () => {}
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  setDeskSelection.mockClear()
})

function draw() {
  // The palette's own `runAction`: close, then run.
  const onRun = (run: () => void) => run()
  // `infra` is an infrastructure head: not on the visible list, so never selected.
  render(
    <Command>
      <Command.List>
        <SelectByColourItems
          projectId={1}
          visibleKeys={new Set(['hex-1', 'hex-2', 'spot-1'])}
          itemClassName=""
          groupClassName=""
          onRun={onRun}
        />
      </Command.List>
    </Command>,
  )
}

describe('⌘K Select colour (tables-colours plan D11)', () => {
  it('offers each colour some visible fixture carries, with its count', () => {
    draw()
    expect(screen.getByText('Select colour · Red')).toBeInTheDocument()
    expect(screen.getByText('2 fixtures')).toBeInTheDocument()
    expect(screen.getByText('Select colour · Lime')).toBeInTheDocument()
    expect(screen.queryByText(/· Pink/)).toBeNull()
  })

  it('writes the desk selection — those fixtures alone, the mask cleared', () => {
    draw()
    fireEvent.click(screen.getByText('Select colour · Red'))
    expect(setDeskSelection).toHaveBeenCalledWith(
      [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'hex-2' },
      ],
      null,
    )
  })

  it('adds to the desk selection while ⌥ is held, keeping what it holds', () => {
    draw()
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Alt', altKey: true }))
    })
    const add = screen.getByText('Add to selection · Red')
    fireEvent.click(add)
    expect(setDeskSelection).toHaveBeenCalledWith(
      [
        { type: 'fixture', key: 'spot-1' },
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'hex-2' },
      ],
      ['POSITION'],
    )
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keyup', { key: 'Alt', altKey: false }))
    })
    expect(screen.getByText('Select colour · Red')).toBeInTheDocument()
  })
})
