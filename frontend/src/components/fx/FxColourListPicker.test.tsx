// @vitest-environment jsdom
import { useState } from 'react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import time and jsdom has none; the
// store slices this component pulls in subscribe at module load.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/**
 * react-colorful and Radix's slider read the pointer against a box jsdom lays out at zero, so each is
 * a stand-in with its callbacks: the picker's `change` is a move, the slider's `pointerUp` its commit.
 */
vi.mock('react-colorful', async () => (await import('@/test/colourPickerMock')).colourPickerMock())
/** dnd-kit's sensors measure the swatches, so a test drives the context's own callbacks instead. */
const dnd = vi.hoisted(() => ({
  props: null as null | {
    onDragStart?: (event: unknown) => void
    onDragEnd?: (event: unknown) => void
    onDragCancel?: (event: unknown) => void
  },
  items: [] as string[],
}))
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: (props: NonNullable<typeof dnd.props> & { children: React.ReactNode }) => {
      dnd.props = props
      return <actual.DndContext>{props.children}</actual.DndContext>
    },
  }
})
vi.mock('@dnd-kit/sortable', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/sortable')>()
  return {
    ...actual,
    SortableContext: (props: { items: string[]; children: React.ReactNode }) => {
      dnd.items = props.items
      return <actual.SortableContext {...props} />
    },
  }
})
vi.mock('@/components/ui/slider', () => ({
  Slider: ({
    value,
    onValueChange,
    onValueCommit,
  }: {
    value: number[]
    onValueChange?: (v: number[]) => void
    onValueCommit?: (v: number[]) => void
  }) => (
    <input
      type="range"
      aria-label="Emitter"
      min={0}
      max={255}
      value={value[0]}
      onChange={(e) => onValueChange?.([Number(e.target.value)])}
      onPointerUp={(e) => onValueCommit?.([Number((e.target as HTMLInputElement).value)])}
    />
  ),
}))

import { FxColourListPicker } from './FxColourListPicker'
import { store } from '@/store'
import { lightingApi } from '@/api/lightingApi'
import type { RecentValuesState } from '@/api/recentValuesApi'

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
  extendedChannels,
}: {
  initial: string
  external?: string
  onEmit?: (v: string, release: boolean) => void
  extendedChannels?: { white?: boolean }
}) {
  const [value, setValue] = useState(initial)
  return (
    <>
      {external !== undefined && (
        <button title={HAND_OVER} onClick={() => setValue(external)} />
      )}
      <FxColourListPicker
        value={value}
        extendedChannels={extendedChannels}
        onChange={(v, release) => {
          setValue(v)
          onEmit?.(v, release)
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
    expect(onEmit).toHaveBeenCalledWith('#00ff00,#ffffff', true)
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

    expect(onEmit).toHaveBeenCalledWith('#ff0000,#0000ff,#ffffff', true)
    expect(swatchLabels(container)).toHaveLength(3)
    // Same DOM node — the existing items kept their ids rather than being
    // re-parsed into new ones behind the edit.
    expect(container.querySelector('button[style*="background-color"]')).toBe(firstSwatchBefore)

    addColour()
    expect(onEmit).toHaveBeenLastCalledWith('#ff0000,#0000ff,#ffffff,#ffffff', true)
    expect(container.querySelector('button[style*="background-color"]')).toBe(firstSwatchBefore)
  })

  // D24: a value arriving while a swatch is open — a late copy of an earlier frame, or any other —
  // leaves the swatch open on its own colours, and is read once it closes.
  it('keeps an open swatch through a different value, and takes the value once it closes', () => {
    const { container } = render(
      withStore(<ControlledPicker initial="#ff0000,#00ff00" external="#111111,#222222" />),
    )

    fireEvent.click(container.querySelector('button[style*="background-color"]')!)
    expect(screen.queryByRole('dialog')).not.toBeNull()

    handOver()
    expect(screen.queryByRole('dialog')).not.toBeNull()
    expect(swatchLabels(container).slice(0, 2)).toEqual(['rgb(255, 0, 0)', 'rgb(0, 255, 0)'])

    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(swatchLabels(container)).toEqual(['rgb(17, 17, 17)', 'rgb(34, 34, 34)'])
  })

  it('resets to a different list handed over while no swatch is open', () => {
    const { container } = render(
      withStore(<ControlledPicker initial="#ff0000,#00ff00" external="#111111,#222222" />),
    )
    handOver()
    expect(swatchLabels(container)).toEqual(['rgb(17, 17, 17)', 'rgb(34, 34, 34)'])
  })

  it('keeps emitting from live state across repeated edits', () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))

    addColour()
    addColour()

    expect(onEmit).toHaveBeenLastCalledWith('#ff0000,#ffffff,#ffffff', true)
    expect(swatchLabels(container)).toHaveLength(3)
  })
})

describe('FxColourListPicker releases (programmer-fixes plan D23)', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  const openFirstSwatch = (container: HTMLElement) =>
    fireEvent.click(container.querySelector('button[style*="background-color"]')!)
  const releases = (onEmit: ReturnType<typeof vi.fn>) => onEmit.mock.calls.filter(([, release]) => release)
  /** The frame a release is held past the window's pointer-up, for a late last move to land. */
  const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 30)))

  it('sends a drag as moves, and releases on the window’s pointer-up — inside the popover or out', async () => {
    for (const target of ['picker', 'outside'] as const) {
      const onEmit = vi.fn()
      const { container, unmount } = render(withStore(<ControlledPicker initial="#ff0000,#00ff00" onEmit={onEmit} />))
      openFirstSwatch(container)
      const picker = screen.getByLabelText('Colour picker')
      fireEvent.mouseDown(picker, { button: 0 })
      fireEvent.change(picker, { target: { value: '#ee0000' } })
      fireEvent.change(picker, { target: { value: '#dd0000' } })
      expect(onEmit.mock.calls.map(([, release]) => release)).toEqual([false, false])

      fireEvent.mouseUp(target === 'picker' ? picker : document.body)
      expect(releases(onEmit)).toEqual([])
      await settle()
      expect(releases(onEmit)).toEqual([['#dd0000,#00ff00', true]])
      // The swatch stays open on the colour it was dragged to.
      expect(screen.queryByRole('dialog')).not.toBeNull()
      unmount()
    }
  })

  it('sends nothing for a press let go without a move', async () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))
    openFirstSwatch(container)
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.mouseUp(picker)
    await settle()
    expect(onEmit).not.toHaveBeenCalled()
  })

  // react-colorful reports a move from an effect, so the last one can follow the window's release.
  it('takes a last move reported after the pointer-up into the drag, and releases once, on it', async () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))
    openFirstSwatch(container)
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.change(picker, { target: { value: '#ee0000' } })
    fireEvent.mouseUp(document.body)
    fireEvent.change(picker, { target: { value: '#dd0000' } })
    await settle()
    expect(onEmit.mock.calls).toEqual([
      ['#ee0000', false],
      ['#dd0000', false],
      ['#dd0000', true],
    ])
  })

  it('releases a drag the swatch’s close cuts short', () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))
    openFirstSwatch(container)
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.change(picker, { target: { value: '#ee0000' } })
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(releases(onEmit)).toEqual([['#ee0000', true]])
  })

  it('lands a release still settling when the next press starts', async () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))
    openFirstSwatch(container)
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.change(picker, { target: { value: '#ee0000' } })
    fireEvent.mouseUp(picker)
    fireEvent.mouseDown(picker, { button: 0 })
    expect(releases(onEmit)).toEqual([['#ee0000', true]])
    fireEvent.change(picker, { target: { value: '#cc0000' } })
    expect(onEmit).toHaveBeenLastCalledWith('#cc0000', false)
    fireEvent.mouseUp(picker)
    await settle()
    expect(releases(onEmit)).toEqual([
      ['#ee0000', true],
      ['#cc0000', true],
    ])
  })

  it('never lands a stale drag: an emitter drag with no commit, then a press with no move, sends nothing more', async () => {
    const onEmit = vi.fn()
    const { container } = render(
      withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} extendedChannels={{ white: true }} />),
    )
    openFirstSwatch(container)
    const white = screen.getByLabelText('Emitter')
    fireEvent.change(white, { target: { value: '120' } })
    fireEvent.change(white, { target: { value: '0' } })
    const sent = onEmit.mock.calls.length
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.mouseUp(picker)
    await settle()
    expect(onEmit).toHaveBeenCalledTimes(sent)
  })

  it('releases an emitter slider’s drag once, on its commit', async () => {
    const onEmit = vi.fn()
    const { container } = render(
      withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} extendedChannels={{ white: true }} />),
    )
    openFirstSwatch(container)
    const white = screen.getByLabelText('Emitter')
    fireEvent.change(white, { target: { value: '40' } })
    fireEvent.change(white, { target: { value: '80' } })
    fireEvent.pointerUp(white)
    // A slider's commit is read a microtask on, after Radix has reported a key's step.
    await act(async () => {})
    expect(onEmit.mock.calls.map(([, release]) => release)).toEqual([false, false, true])
    expect(releases(onEmit)).toEqual([['#ff0000;w80', true]])
  })

  it('sends a typed channel as it is typed and releases it once its field is left; an add and a removal release each', () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000,#00ff00" onEmit={onEmit} />))
    addColour()
    expect(onEmit).toHaveBeenLastCalledWith('#ff0000,#00ff00,#ffffff', true)

    fireEvent.click(screen.getAllByTitle('Remove')[2])
    expect(onEmit).toHaveBeenLastCalledWith('#ff0000,#00ff00', true)

    openFirstSwatch(container)
    const red = within(screen.getByRole('dialog')).getByRole('spinbutton', { name: 'R' })
    fireEvent.change(red, { target: { value: '1' } })
    fireEvent.change(red, { target: { value: '18' } })
    expect(onEmit).toHaveBeenLastCalledWith('#120000,#00ff00', false)
    fireEvent.blur(red)
    expect(onEmit).toHaveBeenLastCalledWith('#120000,#00ff00', true)
    expect(releases(onEmit)).toHaveLength(3)
  })

  it('edits a swatch in the programmer’s editor: R, G and B fields, a typed one keeping the rest and an emitter the targets lack', () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#102030;a40" onEmit={onEmit} />))
    openFirstSwatch(container)
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByRole('spinbutton', { name: 'R' })).toHaveValue(16)
    expect(dialog.getByRole('spinbutton', { name: 'G' })).toHaveValue(32)
    expect(dialog.getByRole('spinbutton', { name: 'B' })).toHaveValue(48)
    // No emitter row for amber the targets have none of, but the stored amber stays in the value.
    expect(dialog.queryByRole('spinbutton', { name: 'A value' })).toBeNull()
    fireEvent.change(dialog.getByRole('spinbutton', { name: 'G' }), { target: { value: '200' } })
    expect(onEmit).toHaveBeenLastCalledWith('#10c830;a40', false)
  })
})

describe('FxColourListPicker reorders (programmer-fixes plan D23, D24)', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('releases a reorder', () => {
    const onEmit = vi.fn()
    render(withStore(<ControlledPicker initial="#ff0000,#00ff00" onEmit={onEmit} />))
    const [first, second] = dnd.items
    act(() => dnd.props!.onDragStart!({ active: { id: first } }))
    act(() => dnd.props!.onDragEnd!({ active: { id: first }, over: { id: second } }))
    expect(onEmit).toHaveBeenLastCalledWith('#00ff00,#ff0000', true)
  })

  it('keeps its swatches through a different value while a reorder is live, and takes it at the drop', () => {
    const { container } = render(
      withStore(<ControlledPicker initial="#ff0000,#00ff00" external="#111111,#222222" />),
    )
    const [first] = dnd.items
    act(() => dnd.props!.onDragStart!({ active: { id: first } }))
    handOver()
    expect(swatchLabels(container)).toEqual(['rgb(255, 0, 0)', 'rgb(0, 255, 0)'])
    act(() => dnd.props!.onDragEnd!({ active: { id: first }, over: null }))
    expect(swatchLabels(container)).toEqual(['rgb(17, 17, 17)', 'rgb(34, 34, 34)'])
  })
})

describe('FxColourListPicker recent values (programmer-fixes plan D20, D23)', () => {
  let desk: RecentValuesState
  let record: ReturnType<typeof vi.spyOn>
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch()
    desk = { projectId: 1, lists: {} }
    vi.spyOn(lightingApi.recentValues, 'get').mockImplementation(() => desk)
    record = vi.spyOn(lightingApi.recentValues, 'record').mockImplementation(() => {})
  })
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  const openFirstSwatch = (container: HTMLElement) =>
    fireEvent.click(container.querySelector('button[style*="background-color"]')!)
  /** The recorder holds a picker's release past its last report (`RECENT_RELEASE_SETTLE_MS`). */
  const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 130)))

  it('records a swatch’s drag once, on its release, with the emitters the targets have', async () => {
    const { container } = render(withStore(<ControlledPicker initial="#ff0000,#00ff00" extendedChannels={{ white: true }} />))
    openFirstSwatch(container)
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.change(picker, { target: { value: '#ee0000' } })
    fireEvent.change(picker, { target: { value: '#dd0000' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.mouseUp(document.body)
    await settle()
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#dd0000;w0')
  })

  it('records a picker’s key steps once, on the key-up, however many the key repeated, and releases them there', async () => {
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" onEmit={onEmit} />))
    openFirstSwatch(container)
    const picker = screen.getByLabelText('Colour picker')
    // react-colorful reads the numeric code; a move with no press held is a key's step.
    fireEvent.keyDown(picker, { key: 'ArrowLeft', keyCode: 37 })
    fireEvent.change(picker, { target: { value: '#ee0000' } })
    fireEvent.keyDown(picker, { key: 'ArrowLeft', keyCode: 37 })
    fireEvent.change(picker, { target: { value: '#dd0000' } })
    expect(record).not.toHaveBeenCalled()
    expect(onEmit.mock.calls.map(([, release]) => release)).toEqual([false, false])
    fireEvent.keyUp(picker, { key: 'ArrowLeft', keyCode: 37 })
    expect(onEmit).toHaveBeenLastCalledWith('#dd0000', true)
    await settle()
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#dd0000')
  })

  it('records an emitter slider’s drag once, on its commit', async () => {
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" extendedChannels={{ white: true }} />))
    openFirstSwatch(container)
    const white = screen.getByLabelText('Emitter')
    fireEvent.change(white, { target: { value: '40' } })
    fireEvent.change(white, { target: { value: '80' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.pointerUp(white)
    await act(async () => {})
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#ff0000;w80')
    // Nothing is left staged to go again when the swatch closes.
    cleanup()
    expect(record).toHaveBeenCalledTimes(1)
  })

  it('records a typed channel when its field is left, not on the way to it', () => {
    const { container } = render(withStore(<ControlledPicker initial="#ff0000" />))
    openFirstSwatch(container)
    const red = within(screen.getByRole('dialog')).getByRole('spinbutton', { name: 'R' })
    fireEvent.change(red, { target: { value: '1' } })
    fireEvent.change(red, { target: { value: '18' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.blur(red)
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#120000')
  })

  it('a tap sets the swatch’s colour as a release, and moves the recent to the front', () => {
    desk = { projectId: 1, lists: { colour: ['#102030;w50'] } }
    const onEmit = vi.fn()
    const { container } = render(withStore(<ControlledPicker initial="#ff0000,#00ff00" onEmit={onEmit} extendedChannels={{ white: true }} />))
    openFirstSwatch(container)
    const band = screen.getByRole('dialog').querySelector('[data-recent-values="colour"]') as HTMLElement
    fireEvent.click(within(band).getByRole('button', { name: 'Recent #102030 · W 50' }))
    expect(onEmit).toHaveBeenLastCalledWith('#102030;w50,#00ff00', true)
    expect(onEmit.mock.calls.every(([value]) => value === '#102030;w50,#00ff00')).toBe(true)
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#102030;w50')
  })
})
