// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import { chan, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * Radix's slider computes a value from the pointer against its track's box, which jsdom lays out at
 * zero — so a drag is driven through a stand-in with the same two callbacks, `SceneryControl.test`'s:
 * `change` is a move, `pointerUp` the release (`onValueCommit`).
 */
vi.mock('@/components/ui/slider', () => ({
  Slider: ({
    min,
    max,
    step,
    value,
    disabled,
    onValueChange,
    onValueCommit,
    'aria-label': label,
  }: {
    min: number
    max: number
    step: number
    value: number[]
    disabled?: boolean
    onValueChange?: (v: number[]) => void
    onValueCommit?: (v: number[]) => void
    'aria-label'?: string
  }) => (
    <input
      type="range"
      aria-label={label}
      min={min}
      max={max}
      step={step}
      value={value[0]}
      disabled={disabled}
      onChange={(e) => onValueChange?.([Number(e.target.value)])}
      onPointerUp={(e) => onValueCommit?.([Number((e.target as HTMLInputElement).value)])}
    />
  ),
}))

const updateFx = vi.hoisted(() => vi.fn((_id: number, _req: unknown) => true))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const fx = { updateFx, subscribe: () => ({ unsubscribe: () => {} }), subscribeToErrors: () => ({ unsubscribe: () => {} }) }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'fx' ? fx : target[prop]) }) }
})

const library = vi.hoisted(() => ({ entries: [] as FxLibraryEntry[] }))
const pauseFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve() })))
const resetFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve(arm.resetAnswer) })))
const savePalette = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({}) })))
/** What `useSpawningPalette` answers — null for an effect no pad spawned. */
const arm = vi.hoisted(() => ({ spawning: null as unknown, resetAnswer: null as unknown }))
vi.mock('@/store/fixtureFx', () => ({
  useFxLibraryQuery: () => ({ data: library.entries }),
  usePauseFxMutation: () => [pauseFx],
  useResumeFxMutation: () => [vi.fn()],
  useResetFxToPaletteMutation: () => [resetFx, { isLoading: false }],
}))
vi.mock('@/store/palettes', () => ({ useSavePaletteMutation: () => [savePalette, { isLoading: false }] }))
vi.mock('./useSpawningPalette', () => ({ useSpawningPalette: () => arm.spawning }))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/store/groups', () => ({
  usePauseGroupFxMutation: () => [vi.fn()],
  useResumeGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/store/speedMasters', () => ({
  useMaster1Uuid: () => 'master-1',
  useSpeedMasterLiveQuery: () => ({ data: [{ uuid: 'master-1', index: 1, name: 'Main', bpm: 124 }] }),
}))
const lookup = vi.hoisted(() => ({ fixtures: [] as unknown[] }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: lookup.fixtures,
    fixtureByKey: new Map((lookup.fixtures as { key: string }[]).map((f) => [f.key, f])),
  }),
}))
vi.mock('./FxColourPicker', () => ({ FxColourPicker: () => null }))
vi.mock('./FxColourListPicker', () => ({ FxColourListPicker: () => null }))

import { FxEditor } from './FxEditor'

const CIRCLE: FxLibraryEntry = {
  name: 'Circle',
  family: 'position',
  outputType: 'POSITION',
  fxMode: 'STANDARD',
  timingSource: 'BEAT',
  compatibleAttributes: ['position'],
  parameters: [
    { name: 'panCentre', type: 'ubyte', defaultValue: '128', description: '' },
    { name: 'tiltCentre', type: 'ubyte', defaultValue: '128', description: '' },
    { name: 'panRadius', type: 'ubyte', defaultValue: '64', description: '' },
    { name: 'tiltRadius', type: 'ubyte', defaultValue: '64', description: '' },
  ],
}

const PULSE: FxLibraryEntry = {
  name: 'Pulse',
  family: 'intensity',
  outputType: 'SLIDER',
  fxMode: 'STANDARD',
  timingSource: 'BEAT',
  compatibleAttributes: ['dimmer', 'uv'],
  parameters: [
    { name: 'min', type: 'ubyte', defaultValue: '0', description: '' },
    { name: 'max', type: 'ubyte', defaultValue: '255', description: '' },
    { name: 'attackRatio', type: 'double', defaultValue: '0.1', description: '' },
    { name: 'curve', type: 'easingCurve', defaultValue: 'QUAD_OUT', description: '' },
  ],
}

/** A mover whose pan travels 540° and tilt 270°, as the Robe ColorSpot annotates. */
const SPOT = makeFixture('spot-3', [
  sliderProp('dimmer', 'dimmer', chan(1)),
  sliderProp('pan', 'pan', chan(2), { axis: 'PAN', degMin: 0, degMax: 540 }),
  sliderProp('tilt', 'tilt', chan(3), { axis: 'TILT', degMin: 0, degMax: 270 }),
])

const aroundCircle = () =>
  makeActiveFx({
    id: 21,
    fxType: 'Circle',
    targetKey: 'spot-3',
    attribute: 'position',
    blendMode: 'ADDITIVE',
    parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
  })

const lastFrame = () => updateFx.mock.calls.at(-1)?.[1] as Record<string, unknown>

beforeEach(() => {
  library.entries = [CIRCLE, PULSE]
  lookup.fixtures = [SPOT]
  updateFx.mockClear()
  resetFx.mockClear()
  savePalette.mockClear()
  arm.spawning = null
  arm.resetAnswer = null
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('FxEditor', () => {
  it('sends a drag at most one frame per 50 ms, and the release lands', () => {
    vi.useFakeTimers()
    vi.setSystemTime(1000)
    const sentAt: number[] = []
    updateFx.mockImplementation(() => {
      sentAt.push(Date.now())
      return true
    })
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    const size = screen.getByRole('slider', { name: 'Pan size' })

    // Twelve moves 5 ms apart: 60 ms of drag.
    for (let i = 1; i <= 12; i++) {
      act(() => {
        vi.advanceTimersByTime(5)
        fireEvent.change(size, { target: { value: String(64 + i * 4) } })
      })
    }
    // The first move went at once, the rest were held by the floor: no two frames inside 50 ms.
    const sentDuringDrag = updateFx.mock.calls.length
    expect(sentDuringDrag).toBeGreaterThanOrEqual(1)
    for (let i = 1; i < sentAt.length; i++) expect(sentAt[i] - sentAt[i - 1]).toBeGreaterThanOrEqual(50)

    // The release lands now, whatever the floor says, carrying the value the hand let go on.
    fireEvent.pointerUp(size)
    expect(updateFx).toHaveBeenCalledTimes(sentDuringDrag + 1)
    expect(updateFx.mock.calls.at(-1)?.[0]).toBe(21)
    expect((lastFrame().parameters as Record<string, string>).panRadius).toBe(String(64 + 12 * 4))

    // Nothing held is sent after the release.
    act(() => {
      vi.advanceTimersByTime(200)
    })
    expect(updateFx).toHaveBeenCalledTimes(sentDuringDrag + 1)
  })

  it('Revert sends the snapshot taken when the editor opened, in one write', () => {
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    const size = screen.getByRole('slider', { name: 'Pan size' })
    fireEvent.change(size, { target: { value: '200' } })
    fireEvent.pointerUp(size)
    fireEvent.click(screen.getByRole('radio', { name: '1 bar a cycle' }))
    updateFx.mockClear()

    fireEvent.click(screen.getByRole('button', { name: 'Revert' }))
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(lastFrame()).toEqual({
      parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
      cycle: 1,
      blendMode: 'ADDITIVE',
      phaseOffset: 0,
    })
  })

  it('Revert onto Seconds sends seconds alone and draws no master — so it is no longer dirty', () => {
    render(<FxEditor effect={makeActiveFx({ fxType: 'Pulse', attribute: 'dimmer', cycle: 2, timingSource: 'WALL_CLOCK', seconds: true })} onDone={() => {}} onStop={() => {}} />)
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Timing: Seconds' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    fireEvent.click(screen.getByRole('menuitemradio', { name: /M1 · Main/ }))
    expect(lastFrame()).toMatchObject({ seconds: false, speedMasterUuid: 'master-1' })
    updateFx.mockClear()

    const revert = screen.getByRole('button', { name: 'Revert' })
    fireEvent.click(revert)
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(lastFrame()).toMatchObject({ seconds: true, cycle: 2 })
    expect(lastFrame()).not.toHaveProperty('speedMasterUuid')
    expect(screen.getByRole('button', { name: 'Timing: Seconds' })).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Revert' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('reads a stored Additive with the centre at 128 as Around, and hides the centre', () => {
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    expect(screen.getByRole('radio', { name: 'Around current position' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.queryByRole('slider', { name: 'Pan' })).toBeNull()
    expect(screen.queryByRole('slider', { name: 'Tilt' })).toBeNull()
    // A size is a distance in DMX steps (venue-visit plan D3), never offset to the centre.
    expect((screen.getByRole('spinbutton', { name: 'Pan size' }) as HTMLInputElement).value).toBe('64')
  })

  it('sends Around as Additive with the centre at 128 and hides the centre; Absolute is Override with it shown', () => {
    const absolute = makeActiveFx({
      id: 22,
      fxType: 'Circle',
      targetKey: 'spot-3',
      attribute: 'position',
      blendMode: 'OVERRIDE',
      parameters: { panCentre: '60', tiltCentre: '90', panRadius: '64', tiltRadius: '64' },
    })
    render(<FxEditor effect={absolute} onDone={() => {}} onStop={() => {}} />)
    expect(screen.getByRole('radio', { name: 'Absolute' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('slider', { name: 'Pan' })).toBeTruthy()
    // An absolute centre reads centre-relative (D3): byte 60 is 68 steps left of centre.
    expect((screen.getByRole('spinbutton', { name: 'Pan' }) as HTMLInputElement).value).toBe('-68')

    fireEvent.click(screen.getByRole('radio', { name: 'Around current position' }))
    expect(lastFrame()).toMatchObject({
      blendMode: 'ADDITIVE',
      parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
    })
    expect(screen.queryByRole('slider', { name: 'Pan' })).toBeNull()

    fireEvent.click(screen.getByRole('radio', { name: 'Absolute' }))
    expect(lastFrame()).toMatchObject({ blendMode: 'OVERRIDE' })
    expect(screen.getByRole('slider', { name: 'Pan' })).toBeTruthy()
  })

  it('sends Within as Multiply, and Replace as Override', () => {
    const pulse = makeActiveFx({
      id: 31,
      fxType: 'Pulse',
      targetKey: 'spot-3',
      attribute: 'dimmer',
      parameters: { min: '0', max: '255', attackRatio: '0.1', curve: 'QUAD_OUT' },
    })
    render(<FxEditor effect={pulse} onDone={() => {}} onStop={() => {}} />)
    expect(screen.getByRole('radio', { name: 'Replace it' }).getAttribute('aria-checked')).toBe('true')

    fireEvent.click(screen.getByRole('radio', { name: 'Within it' }))
    expect(lastFrame()).toMatchObject({ blendMode: 'MULTIPLY' })

    fireEvent.click(screen.getByRole('radio', { name: 'Replace it' }))
    expect(lastFrame()).toMatchObject({ blendMode: 'OVERRIDE' })
  })

  it('keeps the shape behind a disclosure that says what is in it', () => {
    const pulse = makeActiveFx({
      id: 31,
      fxType: 'Pulse',
      targetKey: 'spot-3',
      attribute: 'dimmer',
      parameters: { min: '0', max: '255', attackRatio: '0.1', curve: 'QUAD_OUT' },
    })
    render(<FxEditor effect={pulse} onDone={() => {}} onStop={() => {}} />)
    expect(screen.queryByRole('slider', { name: 'Attack' })).toBeNull()
    const shape = screen.getByRole('button', { name: /^Shape/ })
    expect(shape.textContent).toContain('attack 10%')
    expect(shape.textContent).toContain('quad out')
    fireEvent.click(shape)
    expect(screen.getByRole('slider', { name: 'Attack' })).toBeTruthy()
    // Levels read as percents, as the level row does.
    expect((screen.getByRole('spinbutton', { name: 'High percent' }) as HTMLInputElement).value).toBe('100')
  })

  it('resends a write the closed socket dropped, rather than deduplicating it away', () => {
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    const size = screen.getByRole('slider', { name: 'Pan size' })
    updateFx.mockReturnValueOnce(false)
    fireEvent.change(size, { target: { value: '90' } })
    expect(updateFx).toHaveBeenCalledTimes(1)
    // The release at the same value goes out: the dropped move was never the desk's.
    fireEvent.pointerUp(size)
    expect(updateFx).toHaveBeenCalledTimes(2)
    expect((lastFrame().parameters as Record<string, string>).panRadius).toBe('90')
  })

  it('reads a stored Additive Circle with no centres spelled as Around — the defaults are 128', () => {
    const bare = makeActiveFx({ id: 23, fxType: 'Circle', targetKey: 'spot-3', attribute: 'position', blendMode: 'ADDITIVE', parameters: {} })
    render(<FxEditor effect={bare} onDone={() => {}} onStop={() => {}} />)
    expect(screen.getByRole('radio', { name: 'Around current position' }).getAttribute('aria-checked')).toBe('true')
  })

  it('pauses the effect from its header, as every host lost its row button for', () => {
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Pause Circle' }))
    expect(pauseFx).toHaveBeenCalledWith({ id: 21, fixtureKey: 'spot-3' })
  })

  it('types a division the segment does not name — a triplet', () => {
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    const field = screen.getByRole('spinbutton', { name: 'Beats a cycle' })
    fireEvent.change(field, { target: { value: '0.333' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(lastFrame()).toMatchObject({ cycle: 0.333 })
  })

  it('× stops the effect and Done goes back', () => {
    const onStop = vi.fn()
    const onDone = vi.fn()
    render(<FxEditor effect={aroundCircle()} onDone={onDone} onStop={onStop} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stop Circle' }))
    expect(onStop).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(onDone).toHaveBeenCalledTimes(1)
    expect(updateFx).not.toHaveBeenCalled()
  })
})

describe('FxEditor — a pad\'s running instance (fixture-fx-sheets plan §3.3, D20)', () => {
  /** The *Big circle* template a pad pressed: Around, a 64-byte orbit, master 1. */
  const BIG_CIRCLE = {
    id: 7,
    name: 'Big circle',
    kind: 'fx',
    fx: {
      fxType: 'Circle',
      family: 'position',
      cycle: 1,
      blendMode: 'ADDITIVE',
      order: 'NORMAL',
      phaseOffset: 0,
      parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
      speedMasterUuid: null,
      rateSpeedMasterUuid: null,
    },
  }
  const padCircle = () => ({ ...aroundCircle(), paletteId: 7, programmerLayerId: 3, cueId: null, programmerOwned: true })

  beforeEach(() => {
    arm.spawning = { palette: BIG_CIRCLE, projectId: 1 }
  })

  it('is marked edited while it differs from its palette, and Reset to palette calls W5 in place of Revert', async () => {
    arm.resetAnswer = padCircle()
    render(<FxEditor effect={padCircle()} onDone={() => {}} onStop={() => {}} />)
    // As spawned, it is the template: no mark, and nothing to update or reset.
    expect(screen.queryByText('edited')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Revert' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Reset to palette' })).toBeDisabled()

    const size = screen.getByRole('slider', { name: 'Pan size' })
    fireEvent.change(size, { target: { value: '120' } })
    fireEvent.pointerUp(size)
    expect(screen.getByText('edited')).toBeTruthy()
    // The edit is the instance's alone: it went to the instance and nowhere near the template.
    expect(updateFx.mock.calls.at(-1)?.[0]).toBe(21)
    expect(savePalette).not.toHaveBeenCalled()

    updateFx.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Reset to palette' }))
    await waitFor(() => expect(resetFx).toHaveBeenCalledWith({ id: 21 }))
    // The desk put the template back on the instance: the draft reads it, and the mark goes.
    await waitFor(() => expect(screen.queryByText('edited')).toBeNull())
    expect(updateFx).not.toHaveBeenCalled()
  })

  it("Update palette PUTs the instance's settings as the palette's effect, then re-keys the instance", async () => {
    arm.resetAnswer = { ...padCircle(), parameters: { ...padCircle().parameters, panRadius: '120' }, cycle: 2 }
    render(<FxEditor effect={padCircle()} onDone={() => {}} onStop={() => {}} />)
    const size = screen.getByRole('slider', { name: 'Pan size' })
    fireEvent.change(size, { target: { value: '120' } })
    fireEvent.pointerUp(size)
    fireEvent.click(screen.getByRole('radio', { name: '2 beats a cycle' }))

    fireEvent.click(screen.getByRole('button', { name: 'Update palette' }))
    await waitFor(() => expect(savePalette).toHaveBeenCalledTimes(1))
    expect(savePalette.mock.calls[0][0]).toEqual({
      projectId: 1,
      paletteId: 7,
      fx: {
        ...BIG_CIRCLE.fx,
        cycle: 2,
        parameters: { panCentre: '128', tiltCentre: '128', panRadius: '120', tiltRadius: '64' },
      },
    })
    // Then W5 on this instance: the PUT alone would respawn it at the stack's next recook.
    await waitFor(() => expect(resetFx).toHaveBeenCalledWith({ id: 21 }))
  })

  it('keeps Revert for an effect no pad spawned', () => {
    arm.spawning = null
    render(<FxEditor effect={aroundCircle()} onDone={() => {}} onStop={() => {}} />)
    expect(screen.getByRole('button', { name: 'Revert' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Update palette' })).toBeNull()
  })
})
