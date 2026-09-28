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

/**
 * The colour swatches, in order, by rendered background.
 *
 * A **template reference** shows as `rgb(0, 0, 0)` here: the swatch is painted from the template's
 * own colour, and these tests run with no project in the route, so the template query is skipped and
 * nothing resolves. That is the right shape to assert on — what matters below is that the reference
 * survives as a *list entry* with a stable identity, not what colour it happens to paint.
 */
const swatchLabels = (container: HTMLElement) =>
  Array.from(container.querySelectorAll('button[style*="background-color"]')).map(
    (b) => (b as HTMLElement).style.backgroundColor,
  )

const addColour = () => fireEvent.click(screen.getByTitle('Add colour'))

// A Router is required: `useColourTemplates` reads `projectId` from the route. No project here, so
// the template list query is skipped — see the note on `swatchLabels`.
const withStore = (ui: React.ReactNode) => (
  <Provider store={store}>
    <MemoryRouter>{ui}</MemoryRouter>
  </Provider>
)

const HAND_OVER = 'hand over a different list'
const handOver = () => fireEvent.click(screen.getByTitle(HAND_OVER))

const WARM = 'tmpl:2f1c8a3e-0000-4000-8000-000000000001'
const COLD = 'tmpl:2f1c8a3e-0000-4000-8000-000000000002'

/**
 * Mirrors how EffectParameterForm drives the picker: whatever comes out of
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

  // A list is a mix of literals and references, and an edit to one entry must leave the other's
  // *text* alone. Serialising a reference back as a literal is the failure this guards: it would
  // silently sever the effect from the template it was told to follow.
  it('keeps a template reference verbatim through an edit elsewhere in the list', () => {
    const onEmit = vi.fn()
    render(withStore(<ControlledPicker initial={`${WARM},#00ff00`} onEmit={onEmit} />))

    addColour()

    expect(onEmit).toHaveBeenCalledWith(`${WARM},#00ff00,#ffffff`)
  })

  // There is no successor to the old "use entire palette" (`P*`) wildcard, and nothing should
  // reintroduce a checkbox here: a template holds one colour, so there is no set to expand.
  it('offers no all-of-them toggle', () => {
    const { container } = render(withStore(<FxColourListPicker value={WARM} onChange={() => {}} />))
    expect(container.querySelector('input[type="checkbox"]')).toBeNull()
  })

  // Regression guard: the sync must compare against the value we last emitted,
  // not against a re-serialisation of `items`. emitChange keeps a reference as
  // `tmpl:…` while the parsed item carries a #000000 placeholder, so an
  // items-vs-value comparison never matches on a list holding one and re-parses
  // on every edit. Content survives that (parse/serialize round-trips), but fresh
  // makeId()s do not: SortableColourSwatch is keyed on item.id and owns the
  // hex field's state, so the swatch the user is typing into gets remounted.
  it('keeps swatch identity when the parent echoes our own edit back', () => {
    const onEmit = vi.fn()
    const { container } = render(
      withStore(<ControlledPicker initial={`${WARM},${COLD}`} onEmit={onEmit} />),
    )
    expect(swatchLabels(container)).toHaveLength(2)
    const firstSwatchBefore = container.querySelector('button[style*="background-color"]')

    addColour()

    expect(onEmit).toHaveBeenCalledWith(`${WARM},${COLD},#ffffff`)
    expect(swatchLabels(container)).toHaveLength(3)
    // Same DOM node — the existing items kept their ids rather than being
    // re-parsed into new ones behind the edit.
    expect(container.querySelector('button[style*="background-color"]')).toBe(firstSwatchBefore)

    addColour()
    expect(onEmit).toHaveBeenLastCalledWith(`${WARM},${COLD},#ffffff,#ffffff`)
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
