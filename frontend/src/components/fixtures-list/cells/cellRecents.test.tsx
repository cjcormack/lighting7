// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('@/components/ui/slider', async () => ({ Slider: (await import('@/test/rangeSlider')).RangeSlider }))

/** The heads' channels, as the position editor reads the axis a commit does not say. */
const wire = vi.hoisted(() => ({ values: new Map<string, number>() }))
vi.mock('@/hooks/useAttributeValues', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useAttributeValues')>()),
  getChannelValue: (ref: { universe: number; channelNo: number }) => wire.values.get(`${ref.universe}:${ref.channelNo}`) ?? 0,
}))

import { lightingApi } from '@/api/lightingApi'
import type { RecentValuesState } from '@/api/recentValuesApi'
import { chan, sliderProp, whiteMixProp } from '@/test/fixtureFactories'
import { resetPositionPadModeStore } from '@/lib/positionPadMode'
import { whiteLevelsForKelvin, whiteMixColour } from '@/lib/whiteMix'
import type { CellResolution } from '../columns'
import type { CellBatch } from '../rowModel'
import { PositionCell } from './PositionCell'
import { SliderCell } from './SliderCell'
import { WarmthCell } from './WarmthCell'

/**
 * The cell editors' Recent bands (programmer-fixes plan D17–D20): each records a gesture once, at its
 * end, in its list's spelling, and a tap commits the value as the editor's own typed value would.
 */

let desk: RecentValuesState
let record: ReturnType<typeof vi.spyOn>
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  desk = { projectId: 1, lists: {} }
  vi.spyOn(lightingApi.recentValues, 'get').mockImplementation(() => desk)
  record = vi.spyOn(lightingApi.recentValues, 'record').mockImplementation(() => {})
  wire.values = new Map()
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
  resetPositionPadModeStore()
})

const range = () => document.querySelector('input[type="range"]') as HTMLInputElement
const band = (key: string) => document.querySelector(`[data-recent-values="${key}"]`) as HTMLElement | null

describe('the level cell', () => {
  const ZOOM: NonNullable<CellResolution> = { kind: 'slider', attribute: sliderProp('zoom', 'zoom', chan(1)) }
  const THREE: CellBatch = { count: 3, skipped: 0, resolutions: [ZOOM, ZOOM, ZOOM] }
  const draw = (byte = 0) => {
    const onCommit = vi.fn()
    render(
      <SliderCell
        value={{ kind: 'slider', min: byte, max: byte, isUniform: true }}
        resolutions={[ZOOM]}
        batch={THREE}
        label="Zoom"
        autoOpen
        keyboardSeed={null}
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    return onCommit
  }

  it('records a drag once, on its commit, as the percent the grid reads — never per frame', async () => {
    const onCommit = draw()
    await screen.findByRole('spinbutton', { name: 'Zoom' })
    fireEvent.change(range(), { target: { value: '60' } })
    fireEvent.change(range(), { target: { value: '128' } })
    expect(onCommit).toHaveBeenCalledTimes(2)
    expect(record).not.toHaveBeenCalled()
    fireEvent.pointerUp(range())
    await act(async () => {})
    expect(record).toHaveBeenCalledExactlyOnceWith('level:zoom', 'pct:50')
  })

  it('ends a drag on the window’s release where the slider sends no commit (a drag back to its start)', async () => {
    draw()
    await screen.findByRole('spinbutton', { name: 'Zoom' })
    fireEvent.pointerDown(range(), { button: 0 })
    fireEvent.change(range(), { target: { value: '60' } })
    fireEvent.mouseUp(document.body)
    await act(async () => {})
    expect(record).toHaveBeenCalledExactlyOnceWith('level:zoom', 'pct:24')
  })

  it('records a typed percent when its field is left, not per keystroke', async () => {
    draw()
    const field = await screen.findByRole('spinbutton', { name: 'Zoom' })
    fireEvent.change(field, { target: { value: '6' } })
    fireEvent.change(field, { target: { value: '62' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.blur(field)
    expect(record).toHaveBeenCalledExactlyOnceWith('level:zoom', 'pct:62')
  })

  it('draws the list, and a tap commits the value as a typed percent would — once, for the batch to land on each head', async () => {
    desk = { projectId: 1, lists: { 'level:zoom': ['pct:50', 'pct:20'] } }
    const onCommit = draw(10)
    const shown = await waitForBand('level:zoom')
    expect(within(shown).getAllByRole('button').map((b) => b.textContent)).toEqual(['50%', '20%'])
    fireEvent.click(within(shown).getByRole('button', { name: 'Recent 50%' }))
    // The container lands one commit on every selected cell, as it does a typed value's.
    expect(onCommit).toHaveBeenCalledExactlyOnceWith({ kind: 'slider', value: 128 })
    expect(record).toHaveBeenCalledExactlyOnceWith('level:zoom', 'pct:50')
  })

  it('keeps no list for a pan or a tilt, which are no level', async () => {
    desk = { projectId: 1, lists: { 'level:pan': ['pct:50'] } }
    const onCommit = vi.fn()
    const pan: NonNullable<CellResolution> = { kind: 'slider', attribute: sliderProp('pan', 'pan', chan(2), { axis: 'PAN' }) }
    render(
      <SliderCell
        value={{ kind: 'slider', min: 0, max: 0, isUniform: true }}
        resolutions={[pan]}
        label="Pan"
        autoOpen
        keyboardSeed={null}
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    await screen.findByRole('spinbutton', { name: 'Pan' })
    expect(document.querySelector('[data-recent-values]')).toBeNull()
    fireEvent.change(range(), { target: { value: '60' } })
    fireEvent.pointerUp(range())
    await act(async () => {})
    expect(record).not.toHaveBeenCalled()
  })
})

describe('the position cell', () => {
  const head = (pan: number, tilt: number): NonNullable<CellResolution> => ({
    kind: 'position',
    pan: chan(pan),
    tilt: chan(tilt),
    panMin: 0,
    panMax: 255,
    tiltMin: 0,
    tiltMax: 255,
    panAttribute: sliderProp('pan', 'pan', chan(pan), { axis: 'PAN' }),
    tiltAttribute: sliderProp('tilt', 'tilt', chan(tilt), { axis: 'TILT' }),
  })
  const TWO = [head(1, 2), head(3, 4)]
  /** The cell's scoped value over its own two heads: uniform at pan 0, tilt +12, or mixed. */
  const draw = (isUniform: boolean) => {
    const onCommit = vi.fn()
    render(
      <PositionCell
        value={{ kind: 'position', isUniform, pan: 128, tilt: 140, panNormalized: 128 / 255, tiltNormalized: 140 / 255 }}
        resolutions={TWO}
        batch={{ count: 2, skipped: 0, resolutions: TWO }}
        label="Position"
        autoOpen
        keyboardSeed={null}
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    return onCommit
  }

  it('records nothing for an axis set over heads whose scope differs', async () => {
    draw(false)
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    fireEvent.change(pan, { target: { value: '-20' } })
    fireEvent.blur(pan)
    expect(record).not.toHaveBeenCalled()
  })

  it('records the pair with the scope’s other axis, on the field being left — never the wire', async () => {
    // The output says something else; the scope being edited holds tilt +12 on both heads.
    wire.values = new Map([
      ['0:2', 30],
      ['0:4', 200],
    ])
    draw(true)
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    fireEvent.change(pan, { target: { value: '-2' } })
    fireEvent.change(pan, { target: { value: '-20' } })
    fireEvent.blur(pan)
    expect(record).toHaveBeenCalledExactlyOnceWith('position', 'pos:-20,12')
  })

  it('records the pair once this editor has set both axes, whatever the scope held', async () => {
    draw(false)
    const pan = await screen.findByRole('spinbutton', { name: 'Pan' })
    const tilt = screen.getByRole('spinbutton', { name: 'Tilt' })
    fireEvent.change(tilt, { target: { value: '5' } })
    fireEvent.blur(tilt)
    expect(record).not.toHaveBeenCalled()
    fireEvent.change(pan, { target: { value: '-20' } })
    fireEvent.blur(pan)
    expect(record).toHaveBeenCalledExactlyOnceWith('position', 'pos:-20,5')
  })

  it('records a pad drag once, on its release, both axes said', async () => {
    const onCommit = draw(false)
    await screen.findByRole('spinbutton', { name: 'Pan' })
    const pad = document.querySelector('[data-editor-xy-pad]') as HTMLElement
    pad.getBoundingClientRect = () => ({ left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100, x: 0, y: 0, toJSON: () => ({}) })
    fireEvent.pointerDown(pad, { clientX: 10, clientY: 10, pointerId: 1 })
    fireEvent.pointerMove(pad, { clientX: 100, clientY: 0, pointerId: 1 })
    expect(onCommit).toHaveBeenCalledTimes(2)
    expect(record).not.toHaveBeenCalled()
    fireEvent.pointerUp(pad, { pointerId: 1 })
    expect(record).toHaveBeenCalledExactlyOnceWith('position', 'pos:127,127')
  })

  it('a tap writes both axes centre-relative, as the fields would', async () => {
    desk = { projectId: 1, lists: { position: ['pos:-20,12'] } }
    const onCommit = draw(true)
    const shown = await waitForBand('position')
    fireEvent.click(within(shown).getByRole('button', { name: 'Recent −20, 12' }))
    expect(onCommit).toHaveBeenCalledExactlyOnceWith({ kind: 'position', panCentre: -20, tiltCentre: 12 })
    expect(record).toHaveBeenCalledExactlyOnceWith('position', 'pos:-20,12')
  })
})

describe('the warmth cell', () => {
  const mix = whiteMixProp(chan(5), chan(6))
  const draw = () => {
    const onCommit = vi.fn()
    const [r, g, b] = whiteMixColour(mix, 255, 0)
    render(
      <WarmthCell
        value={{ kind: 'colour', isUniform: true, r, g, b, combinedCss: `rgb(${r},${g},${b})` }}
        resolutions={[{ kind: 'white-mix', attribute: mix }]}
        autoOpen
        keyboardSeed={null}
        onCommit={onCommit}
        onBeginEdit={() => {}}
      />,
    )
    return onCommit
  }

  it('records a level drag once, on its commit, as the kelvin and the level it set', async () => {
    draw()
    await screen.findByRole('spinbutton', { name: 'Warmth' })
    const level = document.querySelectorAll('input[type="range"]')[1] as HTMLInputElement
    fireEvent.change(level, { target: { value: '30' } })
    fireEvent.change(level, { target: { value: '80' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.pointerUp(level)
    await act(async () => {})
    expect(record).toHaveBeenCalledExactlyOnceWith('warmth', '3000K;80')
  })

  it('records a typed kelvin held to the pair’s range, as the mix it made', async () => {
    const onCommit = draw()
    const warmth = await screen.findByRole('spinbutton', { name: 'Warmth' })
    fireEvent.change(warmth, { target: { value: '9000' } })
    fireEvent.blur(warmth)
    const levels = whiteLevelsForKelvin(mix, 6500, 255)
    const [r, g, b] = whiteMixColour(mix, levels.warm, levels.cold)
    expect(onCommit).toHaveBeenLastCalledWith({ kind: 'colour', r, g, b })
    expect(record).toHaveBeenCalledExactlyOnceWith('warmth', '6500K;100')
  })

  it('a tap writes the colour the recent’s mix makes', async () => {
    desk = { projectId: 1, lists: { warmth: ['3200K;50'] } }
    const onCommit = draw()
    const shown = await waitForBand('warmth')
    fireEvent.click(within(shown).getByRole('button', { name: 'Recent 3200 K · 50%' }))
    const levels = whiteLevelsForKelvin(mix, 3200, 128)
    const [r, g, b] = whiteMixColour(mix, levels.warm, levels.cold)
    expect(onCommit).toHaveBeenCalledExactlyOnceWith({ kind: 'colour', r, g, b })
    expect(record).toHaveBeenCalledExactlyOnceWith('warmth', '3200K;50')
  })
})

async function waitForBand(key: string): Promise<HTMLElement> {
  await screen.findAllByRole('spinbutton')
  const shown = band(key)
  expect(shown).not.toBeNull()
  return shown!
}
