// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import spreadPanelSrc from './SpreadPanel.tsx?raw'
import { resetEditorSurfaceMedia } from './EditorSurface'
import {
  SpreadPanel,
  type AddressSpreadPlan,
  type DurationSpreadPlan,
  type IntentSpreadPlan,
  type RawSpreadPlan,
  type SpreadRequestBody,
} from './SpreadPanel'
import type { SpreadResponse } from '@/store/programmerOps'
import { getSpreadOver, resetSpreadOverStore } from '@/lib/spreadOver'
import { getSpreadFanPrefs, resetSpreadFanStore } from '@/lib/spreadFan'

/**
 * The Spread panel (editor-kit plan D2–D7): one component in a popover host and a docked host. What
 * is pinned here is the panel's own — the family segment drawn answered and checked in every host,
 * the Property row only where the family holds more than one, the four kinds drawing their rows,
 * the request `write` grammar, Live's floor and release, Send again while Live, the trigger's
 * refusals — and that it reaches no interpolation of an intent. What a host builds its plans from
 * is the host's test: `SpreadSheet.test.tsx` and `fixtures-list/SpreadPopover.test.tsx`.
 */

const toast = vi.hoisted(() => ({ error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() }))
vi.mock('sonner', () => ({ toast }))

let pending: { resolve: (value: SpreadResponse) => void; reject: (reason: unknown) => void }[] = []
const send = vi.fn(
  (_body: SpreadRequestBody) =>
    new Promise<SpreadResponse>((resolve, reject) => {
      pending.push({ resolve, reject })
    }),
)
const lastBody = () => send.mock.calls.at(-1)![0]

function intentPlan(over: Partial<IntentSpreadPlan> = {}): IntentSpreadPlan {
  return {
    kind: 'intent',
    col: 'intent',
    label: 'Dimmer',
    targets: [
      { type: 'fixture', key: 'par-1' },
      { type: 'fixture', key: 'par-2' },
    ],
    count: 2,
    cellCount: 0,
    families: ['INTENSITY', 'COLOUR'],
    mask: null,
    send,
    ...over,
  }
}

const radio = (group: string, name: string | RegExp) => within(screen.getByRole('radiogroup', { name: group })).getByRole('radio', { name })
const open = () => fireEvent.click(screen.getByRole('button', { name: 'Spread' }))
const apply = () => fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
async function answer(value: SpreadResponse, index = pending.length - 1) {
  await act(async () => {
    pending[index]?.resolve(value)
    await Promise.resolve()
  })
}

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
  pending = []
})

afterEach(() => {
  resetSpreadOverStore()
  resetSpreadFanStore()
  window.sessionStorage.clear()
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  resetEditorSurfaceMedia()
})

describe('the popover host', () => {
  it('offers the trigger where a plan has two steps, and says why not otherwise', () => {
    const { unmount } = render(<SpreadPanel host="popover" plans={[intentPlan()]} />)
    expect(screen.getByRole('button', { name: 'Spread' })).not.toBeDisabled()
    unmount()
    render(<SpreadPanel host="popover" plans={[]} />)
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('Select cells'))
    cleanup()
    // One point is a set, not a spread — unless the one head is a bar spread over its cells.
    render(<SpreadPanel host="popover" plans={[intentPlan({ count: 1, targets: [{ type: 'fixture', key: 'par-1' }] })]} drivableHint="dimmer" />)
    const one = screen.getByRole('button', { name: 'Spread' })
    expect(one).toBeDisabled()
    expect(one).toHaveAttribute('title', expect.stringContaining('in a column it can drive — dimmer'))
    cleanup()
    render(<SpreadPanel host="popover" plans={[intentPlan({ count: 1, cellCount: 12, targets: [{ type: 'fixture', key: 'bar' }] })]} />)
    expect(screen.getByRole('button', { name: 'Spread' })).not.toBeDisabled()
  })

  it('names the columns it can drive when the marquee sits in one it cannot — not "select cells"', () => {
    render(<SpreadPanel host="popover" plans={[]} noSelection={false} drivableHint="dimmer, colour" />)
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('in a column it can drive'))
    expect(button).not.toHaveAttribute('title', expect.stringContaining('Select cells'))
  })

  it('carries the surface’s refusal on the trigger, and a way past it where one is offered', () => {
    const onRefused = vi.fn()
    render(<SpreadPanel host="popover" plans={[intentPlan()]} disabledReason="Locked — unlock the show to edit" onRefused={onRefused} />)
    const button = screen.getByRole('button', { name: 'Spread' })
    expect(button).not.toBeDisabled()
    expect(button).toHaveAttribute('title', 'Locked — unlock the show to edit')
    fireEvent.click(button)
    expect(onRefused).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[data-spread-panel]')).toBeNull()
  })

  it('opens in the shared cell-editor surface, so it takes the sheet forms with the other editors', () => {
    render(<SpreadPanel host="popover" plans={[intentPlan()]} />)
    open()
    expect(document.querySelector('[data-cell-editor-surface="popover"]')).not.toBeNull()
  })

  it('draws a chooser only when the selection spans more than one plan', () => {
    const raw: RawSpreadPlan = { kind: 'raw', col: 'speed', label: 'Speed', count: 2, cellCount: 0, apply: vi.fn() }
    const { unmount } = render(<SpreadPanel host="popover" plans={[intentPlan()]} />)
    open()
    expect(screen.queryByRole('combobox', { name: 'Column to spread' })).toBeNull()
    unmount()
    render(<SpreadPanel host="popover" plans={[intentPlan(), raw]} />)
    open()
    expect(screen.getByRole('combobox', { name: 'Column to spread' })).toBeInTheDocument()
  })
})

describe('the family segment and the Property row', () => {
  it('draws the family segment answered and checked in both hosts, with the marquee’s families live and the rest disabled with the reason', () => {
    const plan = intentPlan({
      families: ['INTENSITY', 'COLOUR', 'POSITION'],
      offered: ['COLOUR'],
      familyRefusal: 'Not in the selection',
      initial: { family: 'COLOUR' },
    })
    const { unmount } = render(<SpreadPanel host="popover" plans={[plan]} />)
    open()
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    expect(radio('Family', 'Intensity')).toBeDisabled()
    expect(radio('Family', 'Intensity')).toHaveAttribute('title', 'Not in the selection')
    expect(radio('Family', 'Position')).toBeDisabled()
    unmount()
    render(<SpreadPanel host="docked" plans={[intentPlan({ initial: { family: 'COLOUR' } })]} />)
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
    expect(radio('Family', 'Intensity')).toBeEnabled()
  })

  it('draws the Property row only where the family holds more than one property the heads can take', () => {
    const plan = intentPlan({
      families: ['INTENSITY', 'COLOUR'],
      initial: { family: 'COLOUR' },
      propertiesFor: (family) =>
        family === 'COLOUR'
          ? [
              { propertyName: 'rgbColour', family: 'COLOUR', label: 'Colour', intent: 'colour' },
              { propertyName: 'white', family: 'COLOUR', label: 'White', intent: 'level' },
            ]
          : [{ propertyName: 'dimmer', family: 'INTENSITY', label: 'Level', intent: 'percent' }],
    })
    render(<SpreadPanel host="docked" plans={[plan]} />)
    const properties = within(screen.getByRole('radiogroup', { name: 'Property' })).getAllByRole('radio')
    expect(properties.map((r) => r.textContent)).toEqual(['Colour', 'White'])
    fireEvent.click(radio('Family', 'Intensity'))
    expect(screen.queryByRole('radiogroup', { name: 'Property' })).toBeNull()
  })

  it('re-answers the family when the host moves the marquee to another column', () => {
    const { rerender } = render(<SpreadPanel host="popover" plans={[intentPlan({ initial: { family: 'INTENSITY' } })]} />)
    open()
    expect(radio('Family', 'Intensity')).toHaveAttribute('aria-checked', 'true')
    rerender(<SpreadPanel host="popover" plans={[intentPlan({ initial: { family: 'COLOUR' } })]} />)
    expect(radio('Family', 'Colour')).toHaveAttribute('aria-checked', 'true')
  })
})

describe('the request', () => {
  it('sends the intents over the plan’s targets with the mask, and omits `write` entirely for Local', () => {
    render(<SpreadPanel host="docked" plans={[intentPlan({ mask: ['INTENSITY'], initial: { family: 'INTENSITY' } })]} />)
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '60' } })
    apply()
    expect(send).toHaveBeenCalledTimes(1)
    expect(lastBody()).toEqual({
      targets: [
        { type: 'fixture', key: 'par-1' },
        { type: 'fixture', key: 'par-2' },
      ],
      families: ['INTENSITY'],
      property: 'dimmer',
      from: 'pct:0',
      to: 'pct:60',
      curve: 'LINE',
      order: 'LINEAR',
      parts: 1,
      over: 'HEADS',
      seed: 0,
    })
    expect('write' in lastBody()).toBe(false)
  })

  it('sends `write: false` for a plan that says so, and hands the latest answer back after toasting its skipped families', async () => {
    const onAnswer = vi.fn()
    render(<SpreadPanel host="docked" plans={[intentPlan({ write: false, onAnswer, mask: ['POSITION'], initial: { family: 'INTENSITY' } })]} />)
    apply()
    expect(lastBody().write).toBe(false)
    await answer({ written: [{ target: { type: 'fixture', key: 'par-1' }, propertyName: 'dimmer', value: '128' }], skippedFamilies: ['INTENSITY'] })
    expect(toast.warning).toHaveBeenCalledWith('Intensity rows skipped — the selection is Position', { id: expect.any(String) })
    expect(onAnswer).toHaveBeenCalledTimes(1)
    expect(onAnswer.mock.calls[0][0].written[0].value).toBe('128')
  })

  it('reads the latest request’s answer only', async () => {
    const onAnswer = vi.fn()
    render(<SpreadPanel host="docked" plans={[intentPlan({ onAnswer, initial: { family: 'INTENSITY' } })]} />)
    apply()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '80' } })
    apply()
    expect(pending).toHaveLength(2)
    await answer({ written: [] }, 1)
    await answer({ written: [], skippedFamilies: ['INTENSITY'] }, 0)
    expect(toast.warning).not.toHaveBeenCalled()
    expect(onAnswer).toHaveBeenCalledTimes(1)
  })

  it('sends nothing under an empty selection, and toasts its own sentence once', () => {
    render(<SpreadPanel host="docked" plans={[intentPlan({ targets: [], count: 0, families: [] })]} />)
    apply()
    expect(send).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('Select the fixtures this should land on first', expect.objectContaining({ id: expect.any(String) }))
  })

  it('names the curve, order, parts and over — and Stage L→R is not on offer', () => {
    render(<SpreadPanel host="docked" plans={[intentPlan({ cellCount: 4, initial: { family: 'INTENSITY' } })]} />)
    fireEvent.click(radio('Curve', 'Wings'))
    fireEvent.click(radio('Order', 'Centre out'))
    fireEvent.click(radio('Parts', '3'))
    fireEvent.click(radio('Over', /^Cells/))
    expect(within(screen.getByRole('radiogroup', { name: 'Order' })).queryByRole('radio', { name: 'Stage L→R' })).toBeNull()
    expect(document.querySelector('[data-spread-order-unavailable="Stage L→R"]')).toHaveTextContent('not on the desk yet')
    apply()
    expect(lastBody()).toMatchObject({ curve: 'WINGS', order: 'CENTER_OUT', parts: 3, over: 'CELLS' })
    fireEvent.click(radio('Order', 'Random'))
    apply()
    expect(lastBody()).toMatchObject({ order: 'RANDOM', seed: 0 })
    fireEvent.click(radio('Order', 'Random'))
    apply()
    expect(lastBody()).toMatchObject({ order: 'RANDOM', seed: 1 })
  })

  it('offers Over: Cells everywhere, with the count only where a plan has cells', () => {
    const { unmount } = render(<SpreadPanel host="docked" plans={[intentPlan()]} />)
    expect(radio('Over', /^Cells/)).toBeEnabled()
    expect(radio('Over', /^Cells/)).toHaveTextContent(/^Cells$/)
    expect(radio('Over', 'Heads')).toHaveAttribute('aria-checked', 'true')
    unmount()
    render(<SpreadPanel host="docked" plans={[intentPlan({ cellCount: 12 })]} />)
    const cells = radio('Over', /^Cells/)
    expect(cells).toBeEnabled()
    expect(cells).toHaveTextContent('Cells12')
  })

  it('keeps Over: Cells through a selection with no cells, and sends Heads there', () => {
    const { rerender } = render(<SpreadPanel host="docked" plans={[intentPlan({ cellCount: 12 })]} />)
    fireEvent.click(radio('Over', /^Cells/))
    apply()
    expect(lastBody()).toMatchObject({ over: 'CELLS' })
    rerender(<SpreadPanel host="docked" plans={[intentPlan()]} />)
    expect(radio('Over', /^Cells/)).toHaveAttribute('aria-checked', 'true')
    apply()
    expect(lastBody()).toMatchObject({ over: 'HEADS' })
    rerender(<SpreadPanel host="docked" plans={[intentPlan({ cellCount: 4 })]} />)
    expect(radio('Over', /^Cells/)).toHaveAttribute('aria-checked', 'true')
    apply()
    expect(lastBody()).toMatchObject({ over: 'CELLS' })
  })

  it('a second mounted panel follows Over without a Live write of its own', () => {
    render(
      <>
        <div data-testid="a">
          <SpreadPanel host="docked" plans={[intentPlan({ cellCount: 12 })]} />
        </div>
        <div data-testid="b">
          <SpreadPanel host="docked" plans={[intentPlan({ cellCount: 12 })]} />
        </div>
      </>,
    )
    const inB = within(screen.getByTestId('b'))
    fireEvent.click(inB.getByRole('switch', { name: 'Live — apply as I adjust' }))
    send.mockClear()
    const inA = within(screen.getByTestId('a'))
    fireEvent.click(within(inA.getByRole('radiogroup', { name: 'Over' })).getByRole('radio', { name: /^Cells/ }))
    expect(within(inB.getByRole('radiogroup', { name: 'Over' })).getByRole('radio', { name: /^Cells/ })).toHaveAttribute('aria-checked', 'true')
    // A, which moved it, has Live off; B, which only followed, must not write over its selection.
    expect(send).not.toHaveBeenCalled()
  })

  it('keeps Over through the panel unmounting — an empty selection — and mounts again on it', () => {
    const { unmount } = render(<SpreadPanel host="docked" plans={[intentPlan({ cellCount: 12 })]} />)
    fireEvent.click(radio('Over', /^Cells/))
    unmount()
    render(<SpreadPanel host="docked" plans={[intentPlan({ cellCount: 4 })]} />)
    expect(radio('Over', /^Cells/)).toHaveAttribute('aria-checked', 'true')
    apply()
    expect(lastBody()).toMatchObject({ over: 'CELLS' })
    fireEvent.click(radio('Over', 'Heads'))
    expect(getSpreadOver()).toBe('HEADS')
  })
})

describe('the chosen plan', () => {
  it('applies the chosen plan only where it has two points in the popover, while the docked host keeps the busk rule', () => {
    // A Dimmer + Speed marquee where one head has a speed channel: the trigger opens for the intent
    // plan, and Apply on the raw plan must not write its one head to From.
    const apply = vi.fn()
    const raw: RawSpreadPlan = { kind: 'raw', col: 'speed', label: 'Speed', count: 1, cellCount: 0, apply }
    const { unmount } = render(<SpreadPanel host="popover" plans={[intentPlan(), raw]} />)
    open()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled()
    fireEvent.click(screen.getByRole('combobox', { name: 'Column to spread' }))
    fireEvent.click(screen.getByRole('option', { name: 'Speed' }))
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    unmount()
    // One head over its cells is many points.
    render(<SpreadPanel host="popover" plans={[intentPlan({ count: 1, cellCount: 12, targets: [{ type: 'fixture', key: 'bar' }] })]} />)
    open()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled()
    cleanup()
    // The busk tab: one head applies at From, as it always did.
    render(<SpreadPanel host="docked" plans={[intentPlan({ count: 1, targets: [{ type: 'fixture', key: 'par-1' }] })]} />)
    expect(screen.getByRole('button', { name: 'Apply' })).toBeEnabled()
  })

  it('draws a palette endpoint at the palette’s colour, so a nudge builds on it rather than on 0/0/0', () => {
    const seen: { r: number; g: number; b: number; css: string }[] = []
    const Stub = (props: { r: number; g: number; b: number; combinedCss: string }) => {
      seen.push({ r: props.r, g: props.g, b: props.b, css: props.combinedCss })
      return <div data-testid="endpoint-editor" />
    }
    const plan = intentPlan({
      families: ['COLOUR'],
      initial: { family: 'COLOUR' },
      colourTemplates: [
        { id: 1, uuid: 'u-amber', name: 'Amber', family: 'COLOUR', kind: 'value', isGeneric: true, rows: [{ propertyName: 'rgbColour', value: '#FFBF00;policy=extract' }], requiredEmitters: [] } as never,
      ],
    })
    render(<SpreadPanel host="docked" plans={[plan]} colourEditor={Stub as never} />)
    fireEvent.change(screen.getByRole('combobox', { name: 'From palette' }), { target: { value: 'u-amber' } })
    const last = seen.at(-1)!
    expect([last.r, last.g, last.b]).toEqual([255, 191, 0])
    expect(last.css).toBe('rgb(255, 191, 0)')
  })

  it('keeps the popover’s footer out of the scroller, so Apply is never below the fold', () => {
    render(<SpreadPanel host="popover" plans={[intentPlan()]} />)
    open()
    const panel = document.querySelector('[data-spread-panel]')!
    const body = panel.querySelector('[data-spread-panel-body]')!
    expect(body.className).toContain('overflow-y-auto')
    expect(panel.className).not.toContain('overflow-y-auto')
    expect(panel.className).toContain('-1.5rem-2px')
    expect(body.contains(screen.getByRole('button', { name: 'Apply' }))).toBe(false)
    expect(panel.contains(screen.getByRole('button', { name: 'Apply' }))).toBe(true)
  })
})

describe('Live', () => {
  it('writes every adjustment through the live push — floored, deduplicated — and the release lands', () => {
    vi.useFakeTimers()
    render(<SpreadPanel host="docked" plans={[intentPlan({ initial: { family: 'INTENSITY' } })]} />)
    expect(send).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('switch', { name: 'Live — apply as I adjust' }))
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '80' } })
    expect(send).toHaveBeenCalledTimes(1)
    expect(lastBody()).toMatchObject({ to: 'pct:80' })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '70' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '65' } })
    expect(send).toHaveBeenCalledTimes(1)
    fireEvent.pointerUp(window)
    expect(send).toHaveBeenCalledTimes(2)
    expect(lastBody()).toMatchObject({ to: 'pct:65' })
    fireEvent.pointerUp(window)
    expect(send).toHaveBeenCalledTimes(2)
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '50' } })
    expect(send).toHaveBeenCalledTimes(2)
    act(() => {
      vi.advanceTimersByTime(60)
    })
    expect(send).toHaveBeenCalledTimes(3)
    expect(lastBody()).toMatchObject({ to: 'pct:50' })
  })

  it('reads Send again while Live is on, and that press is the one un-deduped resend', () => {
    render(<SpreadPanel host="docked" plans={[intentPlan({ initial: { family: 'INTENSITY' } })]} />)
    fireEvent.click(screen.getByRole('switch', { name: 'Live — apply as I adjust' }))
    expect(send).not.toHaveBeenCalled()
    const resend = screen.getByRole('button', { name: 'Send again' })
    expect(resend).toBeEnabled()
    fireEvent.click(resend)
    fireEvent.click(resend)
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('does not flush on a release once Live is off', () => {
    vi.useFakeTimers()
    render(<SpreadPanel host="docked" plans={[intentPlan({ initial: { family: 'INTENSITY' } })]} />)
    const live = screen.getByRole('switch', { name: 'Live — apply as I adjust' })
    fireEvent.click(live)
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '80' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '70' } })
    expect(send).toHaveBeenCalledTimes(1)
    fireEvent.click(live)
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, percent' }), { target: { value: '60' } })
    fireEvent.pointerUp(window)
    act(() => {
      vi.advanceTimersByTime(100)
    })
    expect(send).toHaveBeenCalledTimes(1)
  })
})

describe('the four kinds', () => {
  it('raw: From · To bytes with Curve · Order · Parts · Over, walked here and applied in step order', () => {
    const apply = vi.fn()
    const raw: RawSpreadPlan = { kind: 'raw', col: 'speed', label: 'Speed', count: 3, cellCount: 0, apply }
    render(<SpreadPanel host="popover" plans={[raw]} />)
    open()
    expect(screen.getByRole('spinbutton', { name: 'From, 0–255' })).toBeInTheDocument()
    expect(screen.getByRole('radiogroup', { name: 'Curve' })).toBeInTheDocument()
    expect(screen.getByRole('radiogroup', { name: 'Order' })).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup', { name: 'Family' })).toBeNull()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'To, 0–255' }), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(apply).toHaveBeenCalledWith([0, 50, 100], 'HEADS')
    // Reverse is an order, not a checkbox.
    expect(screen.queryByRole('checkbox', { name: 'Reverse' })).toBeNull()
    // Cells with no cells to split steps over the heads, and the plan is told so.
    open()
    fireEvent.click(radio('Over', /^Cells/))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(apply).toHaveBeenLastCalledWith([0, 50, 100], 'HEADS')
  })

  it('address: From · Step in visible order with the landing line, and the collision named before Apply', () => {
    const apply = vi.fn()
    const plan: AddressSpreadPlan = {
      kind: 'address',
      col: 'address',
      label: 'Address',
      count: 3,
      universe: 1,
      footprints: [6, 18, 6],
      check: (channels) => (channels[0] === 13 ? 'Overlaps PAR 9' : null),
      apply,
    }
    render(<SpreadPanel host="popover" plans={[plan]} />)
    open()
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '13' } })
    expect(screen.getByText('Overlaps PAR 9')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '300' } })
    expect(screen.getByText(/1-300, 1-306, 1-324/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Step'), { target: { value: '20' } })
    expect(screen.getByText(/1-300, 1-320, 1-340/)).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup', { name: 'Curve' })).toBeNull()
    expect(screen.queryByRole('switch', { name: /Live/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(apply).toHaveBeenCalledWith([300, 320, 340])
    // Apply closed the popover, as Enter does.
    expect(document.querySelector('[data-spread-panel]')).toBeNull()
  })

  it('duration: From · To with the curve row where the one-option select was, named per point', () => {
    const apply = vi.fn()
    const plan: DurationSpreadPlan = { kind: 'duration', col: 'fade', label: 'Fade', count: 4, names: ['Q1', 'Q2', 'Q4', 'Q5'], apply }
    render(<SpreadPanel host="popover" plans={[plan]} />)
    open()
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '1s' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '4s' } })
    expect(screen.getByText(/Q1 1.0s · Q2 2.0s · Q4 3.0s · Q5 4.0s/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Spread' })).toBeNull()
    fireEvent.click(radio('Curve', 'Mirror'))
    expect(screen.getByText(/Q1 4.0s · Q2 2.0s · Q4 2.0s · Q5 4.0s/)).toBeInTheDocument()
    fireEvent.click(radio('Curve', 'Line'))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(apply).toHaveBeenCalledWith([1000, 2000, 3000, 4000])
  })

  it('intent: a position spreads centre-relative and a level in percent; Enter applies and comma steps From → To', () => {
    const plan = intentPlan({
      families: ['INTENSITY', 'POSITION'],
      initial: { family: 'POSITION' },
      propertiesFor: (family) =>
        family === 'POSITION'
          ? [{ propertyName: 'position', family: 'POSITION', label: 'Position', intent: 'position' }]
          : [{ propertyName: 'dimmer', family: 'INTENSITY', label: 'Level', intent: 'percent' }],
    })
    render(<SpreadPanel host="popover" plans={[plan]} />)
    open()
    // Centre-relative (venue-visit plan D3): the default swings ±16 about the centre of travel.
    const pan = screen.getByRole('spinbutton', { name: 'From pan' })
    expect(pan).toHaveValue(-16)
    fireEvent.change(pan, { target: { value: '-40' } })
    fireEvent.keyDown(pan, { key: 'Enter' })
    expect(lastBody()).toMatchObject({ property: 'position', from: 'pos:-40,0', to: 'pos:16,0' })
    // Enter applied and closed the popover.
    expect(document.querySelector('[data-spread-panel]')).toBeNull()
  })
})

describe('Fan (venue-visit plan S12)', () => {
  const positionPlan = (over: Partial<IntentSpreadPlan> = {}) =>
    intentPlan({
      label: 'Position',
      targets: [
        { type: 'fixture', key: 'spot-1' },
        { type: 'fixture', key: 'spot-2' },
      ],
      families: ['POSITION'],
      initial: { family: 'POSITION' },
      propertiesFor: () => [{ propertyName: 'position', family: 'POSITION', label: 'Position', intent: 'position' }],
      ...over,
    })
  const toFan = () => fireEvent.click(radio('Spread mode', 'Fan'))
  const amount = (axis: 'Pan' | 'Tilt') => screen.getByRole('spinbutton', { name: `${axis} amount` })
  const base = (key: string, value: string) => ({ target: { type: 'fixture' as const, key }, value })

  it('offers Ends · Fan on Position alone, and Fan swaps From · To for an amount on the chosen axes', () => {
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    expect(screen.getByRole('spinbutton', { name: 'From pan' })).toBeTruthy()
    toFan()
    expect(screen.queryByRole('spinbutton', { name: 'From pan' })).toBeNull()
    expect(screen.getByRole('slider', { name: 'Pan fan' })).toBeTruthy()
    expect(screen.queryByRole('slider', { name: 'Tilt fan' })).toBeNull()
    fireEvent.click(radio('Fan axes', 'Both'))
    expect(screen.getByRole('slider', { name: 'Tilt fan' })).toBeTruthy()
    // The curve pictures read against the midline, each head's own position.
    expect(document.querySelector('[data-curve-picture="LINE"] [data-curve-midline]')).not.toBeNull()
    expect(getSpreadFanPrefs()).toEqual({ mode: 'FAN', axes: 'BOTH' })
    cleanup()
    render(<SpreadPanel host="docked" plans={[intentPlan({ initial: { family: 'INTENSITY' } })]} />)
    expect(screen.queryByRole('radiogroup', { name: 'Spread mode' })).toBeNull()
  })

  it('opens a fresh panel in the mode and axes it was last left in', () => {
    const { unmount } = render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    fireEvent.click(radio('Fan axes', 'Tilt'))
    unmount()
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    expect(radio('Spread mode', 'Fan')).toHaveAttribute('aria-checked', 'true')
    expect(radio('Fan axes', 'Tilt')).toHaveAttribute('aria-checked', 'true')
  })

  it('sends a fan with no endpoints, holds the desk’s base as the anchor and sends it back', async () => {
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    fireEvent.change(amount('Pan'), { target: { value: '20' } })
    apply()
    expect(lastBody()).toMatchObject({ property: 'position', mode: 'FAN', fan: { pan: 20 } })
    expect(lastBody()).not.toHaveProperty('from')
    expect(lastBody()).not.toHaveProperty('to')
    expect(lastBody()).not.toHaveProperty('base')
    await answer({ written: [], base: [base('spot-1', '128,128'), base('spot-2', '60,90')], clamped: [{ type: 'fixture', key: 'spot-2' }] })
    expect(document.querySelector('[data-fan-readout]')).toHaveTextContent('From where they are · 1 head at the end of travel')

    fireEvent.change(amount('Pan'), { target: { value: '30' } })
    apply()
    expect(lastBody()).toMatchObject({ fan: { pan: 30 }, base: [base('spot-1', '128,128'), base('spot-2', '60,90')] })

    // Re-read: no anchor and no fan — the next send reads the heads again.
    fireEvent.click(screen.getByRole('button', { name: 'Re-read' }))
    expect(screen.getByRole('slider', { name: 'Pan fan' })).toHaveAttribute('aria-valuenow', '0')
    fireEvent.change(amount('Pan'), { target: { value: '5' } })
    apply()
    expect(lastBody()).toMatchObject({ fan: { pan: 5 } })
    expect(lastBody()).not.toHaveProperty('base')
  })

  it('holds Live sends while the anchor is read, then sends the latest with it — never a fan of a fan', async () => {
    vi.useFakeTimers()
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    fireEvent.click(screen.getByRole('switch', { name: 'Live — apply as I adjust' }))
    const strip = screen.getByRole('slider', { name: 'Pan fan' })
    fireEvent.keyDown(strip, { key: 'ArrowRight' })
    expect(send).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(strip, { key: 'ArrowRight', shiftKey: true })
    act(() => {
      vi.advanceTimersByTime(60)
    })
    expect(send).toHaveBeenCalledTimes(1)
    await answer({ written: [], base: [base('spot-1', '128,128'), base('spot-2', '128,128')] }, 0)
    expect(send).toHaveBeenCalledTimes(2)
    expect(lastBody()).toMatchObject({ fan: { pan: 9 }, base: [base('spot-1', '128,128'), base('spot-2', '128,128')] })
  })

  it('a host that edits rows starts the fan from them, and says how many heads came from the stage', async () => {
    render(
      <SpreadPanel
        host="docked"
        plans={[positionPlan({ write: false, fanBases: () => [base('spot-1', '100,100')], fanRowsLabel: 'the layer’s rows' })]}
      />,
    )
    toFan()
    fireEvent.change(amount('Pan'), { target: { value: '10' } })
    apply()
    expect(lastBody()).toMatchObject({ write: false, base: [base('spot-1', '100,100')] })
    await answer({ written: [], base: [base('spot-1', '100,100'), base('spot-2', '128,128')] })
    expect(document.querySelector('[data-fan-readout]')).toHaveTextContent('From the layer’s rows · 1 head from the stage')
  })

  it('sends no fan at 0 before one is set — so turning Fan on with Live writes nothing', () => {
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    fireEvent.click(screen.getByRole('switch', { name: 'Live — apply as I adjust' }))
    toFan()
    fireEvent.click(radio('Fan axes', 'Both'))
    expect(send).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Send again' }))
    expect(send).not.toHaveBeenCalled()
  })

  it('an answer with no base is no anchor: the next send reads the heads again rather than send `base: []`', async () => {
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    fireEvent.change(amount('Pan'), { target: { value: '10' } })
    apply()
    await answer({ skippedFamilies: ['POSITION'] })
    fireEvent.change(amount('Pan'), { target: { value: '12' } })
    apply()
    expect(lastBody()).not.toHaveProperty('base')
  })

  it('a new scope over the same heads drops the anchor, so the layer’s rows are read', async () => {
    const { rerender } = render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    fireEvent.change(amount('Pan'), { target: { value: '10' } })
    apply()
    await answer({ written: [], base: [base('spot-1', '128,128'), base('spot-2', '128,128')] })
    rerender(
      <SpreadPanel
        host="docked"
        plans={[positionPlan({ write: false, fanScope: 'layer:4', fanBases: () => [base('spot-1', '90,90')], fanRowsLabel: 'the layer’s rows' })]}
      />,
    )
    expect(screen.getByRole('slider', { name: 'Pan fan' })).toHaveAttribute('aria-valuenow', '0')
    fireEvent.change(amount('Pan'), { target: { value: '12' } })
    apply()
    expect(lastBody()).toMatchObject({ write: false, base: [base('spot-1', '90,90')] })
  })

  it('a send waiting on the first answer still lands after the popover closes', async () => {
    render(<SpreadPanel host="popover" plans={[positionPlan()]} />)
    open()
    toFan()
    fireEvent.click(screen.getByRole('switch', { name: 'Live — apply as I adjust' }))
    fireEvent.change(amount('Pan'), { target: { value: '10' } })
    expect(send).toHaveBeenCalledTimes(1)
    // The release: Send again while the first is in flight waits for its anchor; then Escape closes.
    fireEvent.change(amount('Pan'), { target: { value: '40' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send again' }))
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(send).toHaveBeenCalledTimes(1)
    await answer({ written: [], base: [base('spot-1', '128,128'), base('spot-2', '128,128')] }, 0)
    expect(send).toHaveBeenCalledTimes(2)
    expect(lastBody()).toMatchObject({ fan: { pan: 40 }, base: [base('spot-1', '128,128'), base('spot-2', '128,128')] })
  })

  it('a send waiting on a first send that failed is sent instead of being lost', async () => {
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    fireEvent.change(amount('Pan'), { target: { value: '10' } })
    apply()
    fireEvent.change(amount('Pan'), { target: { value: '30' } })
    apply()
    expect(send).toHaveBeenCalledTimes(1)
    await act(async () => {
      pending[0].reject(new Error('503'))
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(send).toHaveBeenCalledTimes(2)
    expect(lastBody()).toMatchObject({ fan: { pan: 30 } })
    expect(lastBody()).not.toHaveProperty('base')
  })

  it('drags from the amount as it stands, ⇧ a quarter as far, and a double click is no fan', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 255 } as DOMRect)
    render(<SpreadPanel host="docked" plans={[positionPlan()]} />)
    toFan()
    const strip = screen.getByRole('slider', { name: 'Pan fan' })
    fireEvent.pointerDown(strip, { clientX: 100, pointerId: 1 })
    fireEvent.pointerMove(strip, { clientX: 120, pointerId: 1 })
    expect(strip).toHaveAttribute('aria-valuenow', '20')
    fireEvent.pointerMove(strip, { clientX: 160, pointerId: 1, shiftKey: true })
    expect(strip).toHaveAttribute('aria-valuenow', '30')
    fireEvent.pointerUp(strip, { pointerId: 1 })
    fireEvent.doubleClick(strip)
    expect(strip).toHaveAttribute('aria-valuenow', '0')
  })

  it('a fan seed opens the popover on Position, in Fan, and is spent', () => {
    const onSeedConsumed = vi.fn()
    const plan = positionPlan({ families: ['INTENSITY', 'POSITION'], initial: undefined, mask: ['POSITION'] })
    render(<SpreadPanel host="popover" plans={[plan]} seed={{ fan: true, key: 1 }} onSeedConsumed={onSeedConsumed} />)
    expect(document.querySelector('[data-spread-panel]')).not.toBeNull()
    expect(radio('Spread mode', 'Fan')).toHaveAttribute('aria-checked', 'true')
    expect(onSeedConsumed).toHaveBeenCalled()
  })
})

describe('what it reaches', () => {
  it('imports the client walk for the raw arm alone, and never an intent lerp', () => {
    const imports = [...spreadPanelSrc.matchAll(/from '([^']+)'/g)].map((m) => m[1])
    expect(imports).toContain('./spreadPlans')
    expect(imports.some((name) => /colourMath\b.*fan|fanMath|[Rr]esolver|store\/templates|store\/fixtures/.test(name))).toBe(false)
    // Prose may *name* the desk's functions; nothing here calls one.
    expect(spreadPanelSrc).not.toMatch(/\bmixLab\(|\binterpolateIntent\(|\bfanColours\(|\bfanValues\(/)
    // `rawValues` is called once, inside the raw arm's send.
    expect(spreadPanelSrc.match(/rawValues\(/g)).toHaveLength(1)
  })

  it('draws Wings as two fans meeting at the centre, so it is not Mirror’s V', () => {
    render(<SpreadPanel host="docked" plans={[intentPlan()]} />)
    const wings = document.querySelector('[data-curve-picture="WINGS"]')!
    const mirror = document.querySelector('[data-curve-picture="MIRROR"]')!
    expect(wings.querySelectorAll('polyline')).toHaveLength(2)
    expect(wings.querySelector('line')).not.toBeNull()
    expect(mirror.querySelectorAll('polyline')).toHaveLength(1)
  })
})
