// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('@/components/ui/slider', async () => ({ Slider: (await import('@/test/rangeSlider')).RangeSlider }))
/** react-colorful reads the pointer against a box jsdom lays out at zero: its `change` is a move. */
vi.mock('react-colorful', () => ({
  HexColorPicker: ({ color, onChange }: { color: string; onChange: (hex: string) => void }) => (
    <input aria-label="Colour picker" value={color} onChange={(e) => onChange(e.target.value)} />
  ),
}))

import { lightingApi } from '@/api/lightingApi'
import type { RecentValuesState } from '@/api/recentValuesApi'
import type { PaletteIntent } from '@/lib/paletteIntent'
import { RECENT_RELEASE_SETTLE_MS } from '@/store/recentValues'
import { FamilyControls } from './FamilyControls'
import type { PaletteValues } from './paletteRows'

/**
 * The palette editor's controls and their Recent bands (programmer-fixes plan D15, D20, D20a): a
 * level is the palette's own `palette-level:` list, a colour its hex alone, and none is drawn or
 * recorded while the palette's project is not the live one.
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
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function draw(family: 'COLOUR' | 'INTENSITY' | 'POSITION' | 'BEAM', values: PaletteValues = {}, projectId = 1) {
  const onChange = vi.fn<(attribute: string, intent: PaletteIntent | null) => void>()
  render(<FamilyControls family={family} values={values} onChange={onChange} projectId={projectId} />)
  return onChange
}
const band = (key: string) => document.querySelector(`[data-recent-values="${key}"]`) as HTMLElement | null
const ranges = () => Array.from(document.querySelectorAll('input[type="range"]')) as HTMLInputElement[]

describe('a level', () => {
  it('records a drag once, on its commit, in the palette’s own list', async () => {
    const onChange = draw('INTENSITY', { dimmer: { kind: 'percent', value: 10 } })
    const [level] = ranges()
    fireEvent.change(level, { target: { value: '30' } })
    fireEvent.change(level, { target: { value: '62' } })
    expect(onChange).toHaveBeenCalledTimes(2)
    expect(record).not.toHaveBeenCalled()
    fireEvent.pointerUp(level)
    await act(async () => {})
    // Not `level:dimmer`: the palette's percent is of each head's own range (D15).
    expect(record).toHaveBeenCalledExactlyOnceWith('palette-level:dimmer', 'pct:62')
  })

  it('a tap sets the percent', () => {
    desk = { projectId: 1, lists: { 'palette-level:zoom': ['pct:40'] } }
    const onChange = draw('BEAM')
    fireEvent.click(within(band('palette-level:zoom')!).getByRole('button', { name: 'Recent 40%' }))
    expect(onChange).toHaveBeenCalledExactlyOnceWith('zoom', { kind: 'percent', value: 40 })
    expect(record).toHaveBeenCalledExactlyOnceWith('palette-level:zoom', 'pct:40')
  })
})

describe('a position', () => {
  it('records a typed axis as the pair, when the field is left', () => {
    draw('POSITION', { position: { kind: 'position', pan: 0, tilt: 12, unit: 'centre' } })
    const pan = screen.getByRole('spinbutton', { name: 'Pan' })
    fireEvent.change(pan, { target: { value: '-2' } })
    fireEvent.change(pan, { target: { value: '-20' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.blur(pan)
    expect(record).toHaveBeenCalledExactlyOnceWith('position', 'pos:-20,12')
  })

  it('records a slider drag once, on its commit', async () => {
    draw('POSITION', { position: { kind: 'position', pan: 0, tilt: 0, unit: 'centre' } })
    const [, tilt] = ranges()
    fireEvent.change(tilt, { target: { value: '10' } })
    fireEvent.change(tilt, { target: { value: '30' } })
    fireEvent.pointerUp(tilt)
    await act(async () => {})
    expect(record).toHaveBeenCalledExactlyOnceWith('position', 'pos:0,30')
  })
})

describe('a colour (D20a)', () => {
  it('records a picker drag as its hex alone, once, after the window’s release', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    draw('COLOUR', { rgbColour: { kind: 'colour', hex: '#FF9D4A', policy: 'extract' } })
    const picker = screen.getByLabelText('Colour picker')
    fireEvent.mouseDown(picker, { button: 0 })
    fireEvent.change(picker, { target: { value: '#ee0000' } })
    fireEvent.change(picker, { target: { value: '#DD0000' } })
    act(() => vi.advanceTimersByTime(RECENT_RELEASE_SETTLE_MS * 3))
    expect(record).not.toHaveBeenCalled()
    fireEvent.mouseUp(document.body)
    act(() => vi.advanceTimersByTime(RECENT_RELEASE_SETTLE_MS))
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#dd0000')
  })

  it('a tap sets the hex, keeping the policy and leaving the emitter rows alone', () => {
    desk = { projectId: 1, lists: { colour: ['#ff8800;w0;uv40'] } }
    const onChange = draw('COLOUR', { rgbColour: { kind: 'colour', hex: '#FF9D4A', policy: 'extract' } })
    fireEvent.click(within(band('colour')!).getByRole('button', { name: /^Recent #ff8800/ }))
    expect(onChange.mock.calls).toEqual([['rgbColour', { kind: 'colour', hex: '#ff8800', policy: 'extract' }]])
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#ff8800')
  })

  it('records a typed hex when its field is left', () => {
    draw('COLOUR', { rgbColour: { kind: 'colour', hex: '#FF9D4A', policy: 'extract' } })
    const hex = screen.getByLabelText('Hex colour')
    fireEvent.change(hex, { target: { value: '#ff0' } })
    fireEvent.change(hex, { target: { value: '#ff0000' } })
    expect(record).not.toHaveBeenCalled()
    fireEvent.blur(hex)
    expect(record).toHaveBeenCalledExactlyOnceWith('colour', '#ff0000')
  })
})

describe('a palette of a project that is not live', () => {
  it('draws no band and records nothing', async () => {
    desk = { projectId: 2, lists: { 'palette-level:dimmer': ['pct:50'], position: ['pos:0,0'], colour: ['#102030'] } }
    draw('INTENSITY', { dimmer: { kind: 'percent', value: 10 } })
    expect(document.querySelector('[data-recent-values]')).toBeNull()
    const [level] = ranges()
    fireEvent.change(level, { target: { value: '40' } })
    fireEvent.pointerUp(level)
    await act(async () => {})
    cleanup()

    draw('POSITION', { position: { kind: 'position', pan: 0, tilt: 0, unit: 'centre' } })
    expect(document.querySelector('[data-recent-values]')).toBeNull()
    const pan = screen.getByRole('spinbutton', { name: 'Pan' })
    fireEvent.change(pan, { target: { value: '9' } })
    fireEvent.blur(pan)
    cleanup()

    draw('COLOUR', { rgbColour: { kind: 'colour', hex: '#FF9D4A', policy: 'extract' } })
    expect(document.querySelector('[data-recent-values]')).toBeNull()
    expect(record).not.toHaveBeenCalled()
  })
})
