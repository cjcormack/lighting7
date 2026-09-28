// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { ColourPickerPopover } from './ColourPickerPopover'

/**
 * The colour editor's typed fields (`PD-COLOUR-EDITOR-INPUTS`).
 *
 * Two rules are pinned, and they are the two a later edit is most likely to "tidy" into each other:
 *
 *  - **Which fields exist comes from the caller's descriptor flags**, which every call site reads
 *    off the head's *colour descriptor* (`whiteChannel` / `amberChannel` / `uvChannel`). The
 *    bundled emitters are omitted from the flat descriptor list, so there is no category to scan
 *    for them and a head without one must show no field for it.
 *  - **A typed byte is a statement about one channel**, not a colour gesture: it leaves the other
 *    two and every emitter alone. Folding it into the picker's handler would zero the emitters on
 *    every keystroke.
 *
 * And the fields are **opt-in**: this popover has three callers, and only the grid cell's editor
 * asks for them — the second block pins that, with the visualisers' picker-only form (no footer,
 * no read-out) unchanged by the editor kit.
 */
function open(props: Partial<React.ComponentProps<typeof ColourPickerPopover>> = {}) {
  const onColourChange = vi.fn()
  render(
    <ColourPickerPopover
      open
      onOpenChange={() => {}}
      r={10}
      g={20}
      b={30}
      combinedCss="rgb(10, 20, 30)"
      hasWhiteChannel={false}
      hasAmberChannel={false}
      hasUvChannel={false}
      onColourChange={onColourChange}
      channelFields
      {...props}
    >
      <button type="button">swatch</button>
    </ColourPickerPopover>,
  )
  return { onColourChange }
}

describe('ColourPickerPopover fields', () => {
  // Radix's Slider measures its thumb; jsdom has no ResizeObserver, so stub an inert one.
  // Stubbed per file rather than in `src/test/setup.ts` on purpose: two components branch on
  // `typeof ResizeObserver === 'undefined'` to stay inert under jsdom, and a global would take
  // their other arm in every existing test.
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  it('always offers R, G and B, seeded from the channel values', () => {
    open()
    expect((screen.getByLabelText('R') as HTMLInputElement).value).toBe('10')
    expect((screen.getByLabelText('G') as HTMLInputElement).value).toBe('20')
    expect((screen.getByLabelText('B') as HTMLInputElement).value).toBe('30')
  })

  it('offers an emitter field only where the descriptor has that channel', () => {
    open({ hasWhiteChannel: true, w: 40, hasAmberChannel: false, hasUvChannel: false })
    expect(screen.getByLabelText('W value')).toBeTruthy()
    expect(screen.queryByLabelText('A value')).toBeNull()
    expect(screen.queryByLabelText('UV value')).toBeNull()
  })

  it('offers all three when the head carries all three', () => {
    open({ hasWhiteChannel: true, hasAmberChannel: true, hasUvChannel: true, w: 1, a: 2, uv: 3 })
    expect((screen.getByLabelText('W value') as HTMLInputElement).value).toBe('1')
    expect((screen.getByLabelText('A value') as HTMLInputElement).value).toBe('2')
    expect((screen.getByLabelText('UV value') as HTMLInputElement).value).toBe('3')
  })

  it('writes one RGB channel and leaves the other two and every emitter where they are', () => {
    const { onColourChange } = open({
      hasWhiteChannel: true,
      hasAmberChannel: true,
      hasUvChannel: true,
      w: 40,
      a: 100,
      uv: 7,
    })
    fireEvent.change(screen.getByLabelText('R'), { target: { value: '200' } })
    expect(onColourChange).toHaveBeenCalledWith(200, 20, 30, 40, 100, 7)
  })

  it('does not flip to the white LED when 255 is typed into all three', () => {
    const { onColourChange } = open({ r: 255, g: 255, b: 0, hasWhiteChannel: true, w: 0 })
    fireEvent.change(screen.getByLabelText('B'), { target: { value: '255' } })
    expect(onColourChange).toHaveBeenCalledWith(255, 255, 255, 0, undefined, undefined)
  })

  it('writes one emitter and leaves RGB and the other emitters where they are', () => {
    const { onColourChange } = open({
      hasWhiteChannel: true,
      hasAmberChannel: true,
      hasUvChannel: true,
      w: 40,
      a: 100,
      uv: 7,
    })
    fireEvent.change(screen.getByLabelText('A value'), { target: { value: '128' } })
    expect(onColourChange).toHaveBeenCalledWith(10, 20, 30, 40, 128, 7)
  })
})

describe('ColourPickerPopover without channelFields', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  /**
   * `PropertyVisualizers` and `GroupPropertyVisualizers` draw their own always-visible
   * `ColourChannelSlider` bank for every channel beside the same swatch. A typed field in here
   * would be a second live editor for the same byte, opened over the first — so the default is the
   * shape those two have always had: the `R:… G:… B:…` readout and the emitter sliders.
   */
  it('draws the readout and no typed fields for a caller that does not ask for them', () => {
    open({ channelFields: false, footer: false, counts: false, hasWhiteChannel: true, w: 40 })
    // The picker-only form: no footer verbs, no read-out line — the visualisers' bank is both.
    expect(document.querySelector('[data-editor-footer]')).toBeNull()
    expect(document.querySelector('[data-editor-readout]')).toBeNull()
    expect(screen.queryByLabelText('R')).toBeNull()
    expect(screen.queryByLabelText('G')).toBeNull()
    expect(screen.queryByLabelText('B')).toBeNull()
    expect(screen.queryByLabelText('W value')).toBeNull()
    expect(screen.getByText('R:10 G:20 B:30')).toBeTruthy()
  })

  it('still writes one emitter without touching RGB or the other emitters', () => {
    const { onColourChange } = open({
      channelFields: false,
      footer: false,
      counts: false,
      hasWhiteChannel: true,
      hasAmberChannel: true,
      hasUvChannel: true,
      w: 40,
      a: 100,
      uv: 7,
    })
    // `ExtendedChannelSlider` names no control, so the emitter is addressed by position: the rows
    // are W, A, UV in `EMITTERS` order. That namelessness is the shape this arm is pinning.
    // react-colorful's own two pointers are sliders too and come first in the DOM, so the emitter
    // rows are the last three.
    const emitterSliders = screen.getAllByRole('slider').slice(-3)
    fireEvent.keyDown(emitterSliders[1], { key: 'ArrowRight' })
    expect(onColourChange).toHaveBeenCalledWith(10, 20, 30, 40, 101, 7)
  })
})

/**
 * The buffered write (`pendingRef`). A commit sets the whole colour, so a handler has to fill the
 * channels it is not changing — and the props it would read them from are the desk's echo, which
 * lags. Two edits inside that window used to send the second beside the first's *old* value.
 */
describe('ColourPickerPopover pending writes', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  it('carries an un-echoed edit into the next one instead of reverting it', () => {
    const { onColourChange } = open({ hasWhiteChannel: true, w: 40 })
    // Props stay at r=10 g=20 b=30 throughout: the desk has not echoed either write yet.
    fireEvent.change(screen.getByLabelText('R'), { target: { value: '200' } })
    fireEvent.change(screen.getByLabelText('G'), { target: { value: '150' } })
    expect(onColourChange.mock.calls).toEqual([
      [200, 20, 30, 40, undefined, undefined],
      [200, 150, 30, 40, undefined, undefined],
    ])
  })

  it('carries it even when the two edits land in one task, with no render between', () => {
    // The browser's case, and the one a plain `fireEvent` pair cannot reach: RTL flushes a render
    // between two `fireEvent`s, so a handler reading a *render-time* copy of the channels passes
    // that test and still reverts the first edit on a real desk. Batching them in one `act` is
    // what pins the handler to reading the buffer at commit time.
    const { onColourChange } = open({ hasWhiteChannel: true, w: 40 })
    const rField = screen.getByLabelText('R')
    const gField = screen.getByLabelText('G')
    act(() => {
      fireEvent.change(rField, { target: { value: '200' } })
      fireEvent.change(gField, { target: { value: '150' } })
    })
    expect(onColourChange).toHaveBeenLastCalledWith(200, 150, 30, 40, undefined, undefined)
  })

  it('drops the buffer once the props move, so the desk has the last word', () => {
    const onColourChange = vi.fn()
    const view = render(
      <ColourPickerPopover
        open
        onOpenChange={() => {}}
        r={10}
        g={20}
        b={30}
        combinedCss="rgb(10, 20, 30)"
        hasWhiteChannel={false}
        hasAmberChannel={false}
        hasUvChannel={false}
        onColourChange={onColourChange}
        channelFields
      >
        <button type="button">swatch</button>
      </ColourPickerPopover>,
    )
    fireEvent.change(screen.getByLabelText('R'), { target: { value: '200' } })
    // The desk answers, and clamps: it took 180, not 200.
    view.rerender(
      <ColourPickerPopover
        open
        onOpenChange={() => {}}
        r={180}
        g={20}
        b={30}
        combinedCss="rgb(180, 20, 30)"
        hasWhiteChannel={false}
        hasAmberChannel={false}
        hasUvChannel={false}
        onColourChange={onColourChange}
        channelFields
      >
        <button type="button">swatch</button>
      </ColourPickerPopover>,
    )
    fireEvent.change(screen.getByLabelText('G'), { target: { value: '150' } })
    // 180, not the 200 we asked for: the buffer is spent the moment the props move.
    expect(onColourChange).toHaveBeenLastCalledWith(180, 150, 30, undefined, undefined, undefined)
  })
})

/**
 * The picker echo. `RgbColorPicker` holds HSV and converts back on the way out, so any RGB pushed
 * in as its `color` prop can come back out of `onChange` a point or two off — and `onChange` is the
 * whole-output gesture, which zeroes every emitter. A typed field must therefore never drive the
 * picker's colour.
 */
describe('ColourPickerPopover picker echo', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  it('never lets a typed channel come back as a colour gesture that clears the emitters', () => {
    // 200,20,30 is one of the values whose RGB→HSV→RGB round trip does not land back on itself.
    const { onColourChange } = open({
      r: 0,
      g: 20,
      b: 30,
      combinedCss: 'rgb(0, 20, 30)',
      hasWhiteChannel: true,
      hasAmberChannel: true,
      w: 40,
      a: 100,
    })
    fireEvent.change(screen.getByLabelText('R'), { target: { value: '200' } })
    for (const call of onColourChange.mock.calls) {
      expect(call).toEqual([200, 20, 30, 40, 100, undefined])
    }
  })
})
