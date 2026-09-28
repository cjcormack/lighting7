// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CellResolution } from '../columns'
import type { ChannelRef, SettingOption } from '../../../store/fixtures'
import { ColourCell } from './ColourCell'
import { PositionCell } from './PositionCell'
import { SettingCell } from './SettingCell'
import { SliderCell } from './SliderCell'

/**
 * The keyboard that replaced the second editor.
 *
 * Until this change, Enter over a cell selection opened a *different* panel from the one a click
 * opens — a single line of text with a grammar of its own. There is one panel now, so the things
 * that grammar could say have to be sayable in it: a number, a pan and a tilt, a wheel position by
 * name. Each of those is a case here, and each of them is a way the deletion could have been a
 * loss rather than a simplification.
 *
 * All of them are driven through the real components rather than the hook, because every one is a
 * composition: the hook focuses whatever the editor's first field turns out to be, and it is the
 * editor that decides what that is and what a seeded character means in it. The focus itself is
 * asserted for all three ways in — a keystroke, a released marquee and a click — because "the same
 * panel however you got here" is the whole point and the drag arm is the one that was got wrong.
 */

// Radix's Slider measures its thumb; jsdom has no ResizeObserver, so stub an inert one. Stubbed
// per file rather than in `src/test/setup.ts` for the reason `ColourPickerPopover.test.tsx` gives:
// two components branch on `typeof ResizeObserver === 'undefined'` to stay inert under jsdom.
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

const CHANNEL: ChannelRef = { universe: 1, channelNo: 1 }

const SLIDER_RESOLUTION: NonNullable<CellResolution> = {
  kind: 'slider',
  property: {
    type: 'slider',
    name: 'dimmer',
    displayName: 'Dimmer',
    category: 'dimmer',
    channel: CHANNEL,
    min: 0,
    max: 255,
  },
}

const COLOUR_RESOLUTION: NonNullable<CellResolution> = {
  kind: 'colour',
  property: {
    type: 'colour',
    name: 'colour',
    displayName: 'Colour',
    category: 'colour',
    redChannel: CHANNEL,
    greenChannel: { universe: 1, channelNo: 2 },
    blueChannel: { universe: 1, channelNo: 3 },
  },
}

const POSITION_RESOLUTION: NonNullable<CellResolution> = {
  kind: 'position',
  pan: CHANNEL,
  tilt: { universe: 1, channelNo: 2 },
  panMin: 0,
  panMax: 255,
  tiltMin: 0,
  tiltMax: 255,
}

function settingResolution(options: SettingOption[]): NonNullable<CellResolution> {
  return {
    kind: 'setting',
    property: {
      type: 'setting',
      name: 'gobo',
      displayName: 'Gobo',
      category: 'gobo',
      channel: CHANNEL,
      options,
    },
  }
}

const GOBOS: SettingOption[] = [
  { name: 'open', level: 0, displayName: 'Open' },
  { name: 'dots', level: 20, displayName: 'Dots' },
  { name: 'breakup', level: 40, displayName: 'Breakup' },
  { name: 'bars', level: 60, displayName: 'Bars' },
]

describe('a keyboard-opened slider editor', () => {
  it('focuses its number field and takes the character that opened it', async () => {
    const onCommit = vi.fn()
    render(
      <SliderCell
        value={{ kind: 'slider', min: 128, max: 128, isUniform: true }}
        resolutions={[SLIDER_RESOLUTION]}
        label="Dimmer"
        autoOpen
        keyboardSeed="5"
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const field = await screen.findByRole('spinbutton', { name: 'Dimmer' })
    expect(field).toHaveFocus()
    // Seeded *and* committed: every field here writes as it is typed, so a character typed at the
    // grid has to behave as though it had been typed in the box. The box is a **percent** (editor-kit
    // plan D13), so a typed 5 is byte 13 — round(5 / 100 × 255) — on the wire.
    expect(field).toHaveValue(5)
    expect(onCommit).toHaveBeenCalledWith({ kind: 'slider', value: 13 })
  })

  it('closes on Enter', async () => {
    render(
      <SliderCell
        value={{ kind: 'slider', min: 0, max: 0, isUniform: true }}
        resolutions={[SLIDER_RESOLUTION]}
        label="Dimmer"
        autoOpen
        keyboardSeed=""
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    const field = await screen.findByRole('spinbutton', { name: 'Dimmer' })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(screen.queryByRole('spinbutton', { name: 'Dimmer' })).not.toBeInTheDocument()
  })

  it('focuses the field when the editor was opened without a keystroke and with no character', async () => {
    // The hook's contract: focus does not depend on how the editor was opened. `autoOpen` with a
    // null seed was a released marquee's open (`PD-POPUP-AFTER-DRAG`, since deleted — the bar's
    // Set and Enter both seed `''`); it is kept as the contract's own case, because a first cut
    // focused the field only when a *keystroke* had opened it, and that is the regression to pin.
    const onCommit = vi.fn()
    render(
      <SliderCell
        value={{ kind: 'slider', min: 0, max: 0, isUniform: true }}
        resolutions={[SLIDER_RESOLUTION]}
        label="Dimmer"
        autoOpen
        keyboardSeed={null}
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const field = await screen.findByRole('spinbutton', { name: 'Dimmer' })
    expect(field).toHaveFocus()
    // Nothing is seeded — no character opened it — so the field still reads the desk's value.
    expect(onCommit).not.toHaveBeenCalled()
    expect(field).toHaveValue(0)

    // A percent field: 50 lands as byte 128 — round(50 / 100 × 255).
    fireEvent.change(field, { target: { value: '50' } })
    expect(onCommit).toHaveBeenCalledWith({ kind: 'slider', value: 128 })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(screen.queryByRole('spinbutton', { name: 'Dimmer' })).not.toBeInTheDocument()
  })

  it('focuses the field on a plain click too', async () => {
    // "Independent of how the panel was opened" is the rule; a click is the third way in.
    render(
      <SliderCell
        value={{ kind: 'slider', min: 0, max: 0, isUniform: true }}
        resolutions={[SLIDER_RESOLUTION]}
        label="Dimmer"
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    fireEvent.click(screen.getByRole('button'))
    expect(await screen.findByRole('spinbutton', { name: 'Dimmer' })).toHaveFocus()
  })
})

describe('the position editor', () => {
  it('takes a pan and a tilt, with comma between them — what `pan,tilt` used to say', async () => {
    const onCommit = vi.fn()
    render(
      <PositionCell
        value={{
          kind: 'position',
          isUniform: true,
          pan: 10,
          tilt: 20,
          panNormalized: 0.04,
          tiltNormalized: 0.08,
        }}
        resolutions={[POSITION_RESOLUTION]}
        label="Position"
        autoOpen
        keyboardSeed="6"
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(pan).toHaveFocus()
    expect(onCommit).toHaveBeenCalledWith({ kind: 'position', pan: 6 })

    onCommit.mockClear()
    // `fireEvent` reports a default-prevented event as false: the comma is consumed by the step
    // rather than typed into the box it left.
    expect(fireEvent.keyDown(pan, { key: ',' })).toBe(false)
    const tilt = screen.getByRole('spinbutton', { name: 'Tilt' })
    expect(tilt).toHaveFocus()

    fireEvent.change(tilt, { target: { value: '9' } })
    // Per-axis: the tilt write carries no pan, or every batch target's pan would be flattened to
    // this row's aggregate.
    expect(onCommit).toHaveBeenCalledWith({ kind: 'position', tilt: 9 })
  })
})

describe('the wheel editor', () => {
  it('filters as you type and takes the top match on Enter', async () => {
    const onCommit = vi.fn()
    render(
      <SettingCell
        value={{ kind: 'setting', isUniform: true, level: 0, option: GOBOS[0] }}
        resolutions={[settingResolution(GOBOS)]}
        label="Gobo"
        autoOpen
        keyboardSeed="b"
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const filter = await screen.findByRole('combobox', { name: 'Filter Gobo options' })
    expect(filter).toHaveFocus()
    expect(filter).toHaveValue('b')
    // Two matches, in library order; the top one is what Enter takes.
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Breakup', 'Bars'])

    fireEvent.keyDown(filter, { key: 'Enter' })
    expect(onCommit).toHaveBeenCalledWith({ kind: 'setting', level: 40 })
  })

  it('moves the highlight with the arrows', async () => {
    const onCommit = vi.fn()
    render(
      <SettingCell
        value={{ kind: 'setting', isUniform: true, level: 0, option: GOBOS[0] }}
        resolutions={[settingResolution(GOBOS)]}
        label="Gobo"
        autoOpen
        keyboardSeed=""
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const filter = await screen.findByRole('combobox', { name: 'Filter Gobo options' })
    fireEvent.keyDown(filter, { key: 'ArrowDown' })
    fireEvent.keyDown(filter, { key: 'ArrowDown' })
    fireEvent.keyDown(filter, { key: 'Enter' })
    expect(onCommit).toHaveBeenCalledWith({ kind: 'setting', level: 40 })
  })

  it('keeps a search that matches nothing rather than closing on it', async () => {
    const onCommit = vi.fn()
    render(
      <SettingCell
        value={{ kind: 'setting', isUniform: true, level: 0, option: GOBOS[0] }}
        resolutions={[settingResolution(GOBOS)]}
        label="Gobo"
        autoOpen
        keyboardSeed="z"
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const filter = await screen.findByRole('combobox', { name: 'Filter Gobo options' })
    fireEvent.keyDown(filter, { key: 'Enter' })
    expect(onCommit).not.toHaveBeenCalled()
    expect(filter).toBeInTheDocument()
  })

  it('draws no filter over a list too short to need one', async () => {
    render(
      <SettingCell
        value={{ kind: 'setting', isUniform: true, level: 0, option: GOBOS[0] }}
        resolutions={[settingResolution(GOBOS.slice(0, 2))]}
        label="Gobo"
        autoOpen
        keyboardSeed=""
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(await screen.findAllByRole('option')).toHaveLength(2)
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})

describe('the colour editor', () => {
  it('focuses R, seeds it, and steps R → G on comma — what `r,g,b` used to say', () => {
    const onCommit = vi.fn()
    render(
      <ColourCell
        value={{
          kind: 'colour',
          isUniform: true,
          r: 10,
          g: 20,
          b: 30,
          combinedCss: 'rgb(10, 20, 30)',
        }}
        resolutions={[COLOUR_RESOLUTION]}
        label="Colour"
        autoOpen
        keyboardSeed="7"
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    const red = screen.getByRole('spinbutton', { name: 'R' })
    expect(red).toHaveFocus()
    expect(red).toHaveValue(7)
    // A typed byte is a statement about *one* channel; the other five stand.
    expect(onCommit).toHaveBeenCalledWith({
      kind: 'colour',
      r: 7,
      g: 20,
      b: 30,
      w: undefined,
      a: undefined,
      uv: undefined,
    })

    expect(fireEvent.keyDown(red, { key: ',' })).toBe(false)
    expect(screen.getByRole('spinbutton', { name: 'G' })).toHaveFocus()
  })
})
