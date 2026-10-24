// @vitest-environment jsdom
import { useState } from 'react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import time and jsdom has none; the
// store slices this component pulls in subscribe at module load.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { FxColourListPicker } from './FxColourListPicker'
import { store } from '@/store'

/** The colour swatches, in order, by rendered background. */
const swatchLabels = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('button[style*="background-color"]')).map(
    (b) => (b as HTMLElement).style.backgroundColor,
  )

const addColour = () => fireEvent.click(screen.getByTitle('Add colour'))

const withStore = (ui: React.ReactNode) => (
  <Provider store={store}>
    <MemoryRouter>{ui}</MemoryRouter>
  </Provider>
)

const HAND_OVER = 'hand over a different list'
const handOver = () => fireEvent.click(screen.getByTitle(HAND_OVER))

const WARM = 'pal:2f1c8a3e-0000-4000-8000-000000000001'

/**
 * Mirrors how the effect editor (`FxFields`) drives the picker: whatever comes out of
 * onChange is stored verbatim and handed straight back in as `value`. The
 * `external` button stands in for the parent swapping the form onto another
 * target, which changes `value` without the picker having asked for it.
 */
function ControlledPicker({
  initial,
  external,
  onEmit,
}: {
  initial: string
  external?: string
  onEmit?: (v: string) => void
}) {
  const [value, setValue] = useState(initial)
  return (
    <>
      {external !== undefined && (
        <button title={HAND_OVER} onClick={() => setValue(external)} />
      )}
      <FxColourListPicker
        value={value}
        onChange={(v) => {
          setValue(v)
          onEmit?.(v)
        }}
      />
    </>
  )
}

describe('FxColourListPicker', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  // The instance outlives the value it was seeded from: ParameterInput keys on
  // param.name, so editing the same effect on a second target reuses this
  // picker rather than remounting it.
  it('re-parses when the parent hands it a different list', () => {
    const { container, rerender } = render(
      withStore(<FxColourListPicker value="#ff0000,#00ff00" onChange={() => {}} />),
    )
    expect(swatchLabels(container)).toEqual(['rgb(255, 0, 0)', 'rgb(0, 255, 0)'])

    rerender(withStore(<FxColourListPicker value="#0000ff" onChange={() => {}} />))
    expect(swatchLabels(container)).toEqual(['rgb(0, 0, 255)'])
  })

  // Colour Cycle takes literal colours only (terminology plan D14): a reference names no colour in
  // a list, so it is dropped as the desk drops it, and a swatch's editor offers no palette.
  it('takes literal colours only, dropping a palette reference and offering none', () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial={`${WARM},#00ff00`} onEmit={onEmit} />))
    expect(swatchLabels(container)).toEqual(['rgb(0, 255, 0)'])

    addColour()
    expect(onEmit).toHaveBeenCalledWith('#00ff00,#ffffff')
    expect(screen.queryByText('Palettes')).toBeNull()
  })

  // Regression guard: the sync must compare against the value we last emitted,
  // not against a re-serialisation of `items`, which need not match the text it
  // was parsed from (`red` comes back `#ff0000`). Content survives a re-parse,
  // but fresh makeId()s do not: SortableColourSwatch is keyed on item.id and
  // owns the hex field's state, so the swatch the user is typing into gets remounted.
  it('keeps swatch identity when the parent echoes our own edit back', () => {
    const onEmit = vi.fn()
    const { container } = render(
      withStore(<ControlledPicker initial="red,blue" onEmit={onEmit} />),
    )
    expect(swatchLabels(container)).toHaveLength(2)
    const firstSwatchBefore = container.querySelector('button[style*="background-color"]')

    addColour()

    expect(onEmit).toHaveBeenCalledWith('#ff0000,#0000ff,#ffffff')
    expect(swatchLabels(container)).toHaveLength(3)
    // Same DOM node — the existing items kept their ids rather than being
    // re-parsed into new ones behind the edit.
    expect(container.querySelector('button[style*="background-color"]')).toBe(firstSwatchBefore)

    addColour()
    expect(onEmit).toHaveBeenLastCalledWith('#ff0000,#0000ff,#ffffff,#ffffff')
    expect(container.querySelector('button[style*="background-color"]')).toBe(firstSwatchBefore)
  })

  // editingIndex is positional, so leaving it set would reopen the editor on
  // whichever swatch now happens to sit at that index in the new list.
  it('closes an open swatch editor when handed a different list', () => {
    const { container } = render(
      withStore(<ControlledPicker initial="#ff0000,#00ff00" external="#111111,#222222" />),
    )

    fireEvent.click(container.querySelector('button[style*="background-color"]')!)
    expect(screen.queryByRole('dialog')).not.toBeNull()

    handOver()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('keeps emitting from live state across repeated edits', () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))

    addColour()
    addColour()

    expect(onEmit).toHaveBeenLastCalledWith('#ff0000,#ffffff,#ffffff')
    expect(swatchLabels(container)).toHaveLength(3)
  })
})
