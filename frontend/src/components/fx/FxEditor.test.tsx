// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useMemo, useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
import { FxGraphLinkContext, createHoveredRowStore, type FxGraphLink, type LinkedEditor } from './fxGraphLink'
import { FX_SHAPES } from './fxShapes'
import { previewFx, row } from '@/test/fxPreviewAnswers'
import type { ActiveFx } from '@/store/fixtureFx'

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

describe('FxEditor in the FX editor’s inspector (FX editor plan D9, D11–D13, §4.5)', () => {
  /** A Pulse over a group: the Spread section is drawn, and an Intensity FX with a min and a max. */
  const groupPulse = (over: Partial<ActiveFx> = {}) =>
    makeActiveFx({
      id: 41,
      fxType: 'Pulse',
      targetKey: 'front',
      isGroupTarget: true,
      attribute: 'dimmer',
      order: 'NORMAL',
      parameters: { min: '0', max: '255', attackRatio: '0.1', curve: 'QUAD_OUT' },
      ...over,
    })

  /** The link the FX editor provides, its setters recorded and the lent editor kept. */
  function linkOf(over: Partial<FxGraphLink> = {}) {
    const held: { editor: LinkedEditor | null } = { editor: null }
    const link: FxGraphLink = {
      ladder: null,
      hoveredRow: createHoveredRowStore(),
      setLadderRow: vi.fn(),
      setHighlight: vi.fn(),
      others: [],
      attach: vi.fn((editor: LinkedEditor) => {
        held.editor = editor
      }),
      detach: vi.fn((fxId: number) => {
        if (held.editor?.fxId === fxId) held.editor = null
      }),
      preview: vi.fn(),
      ...over,
    }
    return { link, held }
  }

  const inspector = (link: FxGraphLink, effect: ActiveFx = groupPulse()) =>
    render(
      <FxGraphLinkContext.Provider value={link}>
        <FxEditor effect={effect} onDone={() => {}} onStop={() => {}} layout="inspector" />
      </FxGraphLinkContext.Provider>,
    )
  const openSpread = () => fireEvent.click(screen.getByRole('button', { name: /^Spread/ }))
  const shapeChip = (name: string) => within(screen.getByRole('group', { name: 'Shapes' })).getByRole('button', { name })

  it('writes each Shape’s five fields in one updateFx, and lights the Shape the fields match', () => {
    inspector(linkOf().link)
    openSpread()
    // The FX opens on Chase: Normal, every other field at its default.
    expect(shapeChip('Chase').getAttribute('aria-pressed')).toBe('true')
    for (const shape of FX_SHAPES) {
      updateFx.mockClear()
      fireEvent.click(shapeChip(shape.name))
      expect(updateFx).toHaveBeenCalledTimes(1)
      expect(lastFrame()).toMatchObject(shape.fields)
      for (const other of FX_SHAPES) expect(shapeChip(other.name).getAttribute('aria-pressed')).toBe(String(other === shape))
      expect(document.querySelector('[data-shape-custom]')).toBeNull()
    }
  })

  it('says Custom while the fields match no Shape', () => {
    inspector(linkOf().link)
    openSpread()
    const spread = screen.getByRole('spinbutton', { name: /Spread percent/ })
    fireEvent.change(spread, { target: { value: '50' } })
    fireEvent.keyDown(spread, { key: 'Enter' })
    expect(document.querySelector('[data-shape-custom]')?.textContent).toBe('Custom')
    for (const shape of FX_SHAPES) expect(shapeChip(shape.name).getAttribute('aria-pressed')).toBe('false')
  })

  it('draws each Shape’s offsets over eight notional heads', () => {
    inspector(linkOf().link)
    openSpread()
    const drawing = (name: string) => [...shapeChip(name).querySelectorAll('[data-shape-drawing] rect')].map((r) => Number(r.getAttribute('y')))
    expect(drawing('Unison')).toHaveLength(8)
    expect(new Set(drawing('Unison')).size).toBe(1)
    // Pairs: neighbours share a height.
    const pairs = drawing('Pairs')
    expect(pairs[0]).toBe(pairs[1])
    expect(pairs[1]).not.toBe(pairs[2])
  })

  it('copies the cycle and the spread of another FX once, and follows it no further', () => {
    const source = makeActiveFx({ id: 50, fxType: 'Pulse', attribute: 'uv', cycle: 8, order: 'REVERSE', spread: 0.5, segments: 3 })
    const { link } = linkOf({ others: [{ effect: source, name: 'Pulse', wallClock: false }] })
    const { rerender } = inspector(link)
    const pick = (label: string) => {
      fireEvent.pointerDown(screen.getByRole('button', { name: `${label}…` }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: /Pulse/ }))
    }

    updateFx.mockClear()
    pick('Same cycle as')
    expect(updateFx).toHaveBeenCalledTimes(1)
    // Master 1 is named by its uuid: the frame's absent master means "keep".
    expect(lastFrame()).toMatchObject({ cycle: 8, seconds: false, speedMasterUuid: 'master-1' })

    openSpread()
    updateFx.mockClear()
    pick('Same spread as')
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(lastFrame()).toMatchObject({ order: 'REVERSE', spread: 0.5, wings: 1, segments: 3, parts: 0 })

    // The source changes afterwards: nothing follows it.
    updateFx.mockClear()
    const moved = { ...source, cycle: 2, spread: 1.5 }
    rerender(
      <FxGraphLinkContext.Provider value={{ ...link, others: [{ effect: moved, name: 'Pulse', wallClock: false }] }}>
        <FxEditor effect={groupPulse()} onDone={() => {}} onStop={() => {}} layout="inspector" />
      </FxGraphLinkContext.Provider>,
    )
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('offers no cycle from an FX on a master of the other clock, but one on Seconds onto either', () => {
    const wall = makeActiveFx({ id: 51, fxType: 'Flicker', timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: 'master-1', cycle: 3 })
    // A beat FX on Seconds reports the wall clock as the loop it runs on; its library entry is the beat's.
    const onSeconds = makeActiveFx({ id: 52, fxType: 'Pulse', timingSource: 'WALL_CLOCK', seconds: true, cycle: 2.5 })
    inspector(
      linkOf({
        others: [
          { effect: wall, name: 'Flicker', wallClock: true },
          { effect: onSeconds, name: 'Pulse on Seconds', wallClock: false },
        ],
      }).link,
    )
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Same cycle as…' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    expect(screen.getByRole('menuitem', { name: /Flicker/ }).getAttribute('aria-disabled')).toBe('true')
    updateFx.mockClear()
    fireEvent.click(screen.getByRole('menuitem', { name: /Pulse on Seconds/ }))
    expect(lastFrame()).toMatchObject({ cycle: 2.5, seconds: true })
  })

  it('offers no spread from an FX on one head, which has none to copy', () => {
    const single = makeActiveFx({ id: 53, fxType: 'Pulse', attribute: 'uv', order: null })
    inspector(linkOf({ others: [{ effect: single, name: 'Pulse', wallClock: false }] }).link)
    openSpread()
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Same spread as…' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    expect(screen.getByRole('menuitem', { name: /Pulse/ }).getAttribute('aria-disabled')).toBe('true')
  })

  it('puts out a light whose control goes from under the pointer', () => {
    const { link } = linkOf()
    const { unmount } = inspector(link)
    fireEvent.pointerEnter(screen.getByRole('radiogroup', { name: 'Cycle' }).closest('[data-lights]')!)
    expect(link.setHighlight).toHaveBeenLastCalledWith('cycle')
    unmount()
    expect(link.setHighlight).toHaveBeenLastCalledWith(null)
  })

  it('keeps the unsent move through a refetch mid-gesture, and lets it go once the release lands', () => {
    const { link, held } = linkOf()
    const effect = groupPulse()
    const { rerender } = inspector(link, effect)
    const again = (e: ActiveFx) =>
      rerender(
        <FxGraphLinkContext.Provider value={link}>
          <FxEditor effect={e} onDone={() => {}} onStop={() => {}} layout="inspector" />
        </FxGraphLinkContext.Provider>,
      )
    act(() => held.editor!.set({ spread: 0.6 }, false))
    vi.mocked(link.preview).mockClear()
    // The desk answers an earlier push: the running list refetches mid-drag.
    again({ ...effect, spread: 0.5 })
    expect(link.preview).not.toHaveBeenCalledWith(41, null)
    act(() => held.editor!.set({ spread: 0.7 }, true))
    vi.mocked(link.preview).mockClear()
    again({ ...effect, spread: 0.7 })
    expect(link.preview).toHaveBeenLastCalledWith(41, null)
  })

  it('lends the handles the library’s defaults for parameters the FX was not given, and none while a split edit is held', () => {
    const { link, held } = linkOf()
    const bare = groupPulse({ parameters: { min: '40' } })
    render(
      <FxGraphLinkContext.Provider value={link}>
        <FxEditor effect={bare} onDone={() => {}} onStop={() => {}} layout="inspector" />
      </FxGraphLinkContext.Provider>,
    )
    expect(held.editor?.draft.parameters).toMatchObject({ min: '40', max: '255' })
    expect(held.editor?.readOnly).toBe(false)
    cleanup()
    render(
      <FxGraphLinkContext.Provider value={link}>
        <FxEditor effect={bare} onDone={() => {}} onStop={() => {}} layout="inspector" hold={vi.fn()} />
      </FxGraphLinkContext.Provider>,
    )
    expect(held.editor?.readOnly).toBe(true)
  })

  it('settles when the host keeps the lent editor in its own state, as the FX editor does', () => {
    let renders = 0
    function Host() {
      // A loop fails here rather than hanging the run.
      if (++renders > 50) throw new Error('the editor re-attaches on every render')
      const [, setEditor] = useState<LinkedEditor | null>(null)
      const link = useMemo(() => ({ ...linkOf().link, attach: setEditor }), [])
      return (
        <FxGraphLinkContext.Provider value={link}>
          <FxEditor effect={groupPulse()} onDone={() => {}} onStop={() => {}} layout="inspector" />
        </FxGraphLinkContext.Provider>
      )
    }
    render(<Host />)
    expect(renders).toBeLessThan(10)
  })

  it('takes back only its own editor when it goes', () => {
    const { link } = linkOf()
    const { unmount } = inspector(link)
    unmount()
    expect(link.detach).toHaveBeenCalledWith(41)
  })

  it('lights the mark each control changes while the pointer rests on it', () => {
    const { link } = linkOf()
    inspector(link)
    openSpread()
    const lights = (control: HTMLElement) => control.closest('[data-lights]') as HTMLElement
    const cases: [HTMLElement, string][] = [
      [screen.getByRole('radiogroup', { name: 'Cycle' }), 'cycle'],
      [screen.getByRole('slider', { name: 'Amount' }), 'amount'],
      [screen.getByRole('combobox', { name: 'Order' }), 'starts'],
      [screen.getByRole('slider', { name: 'Spread' }), 'starts'],
      [screen.getByRole('spinbutton', { name: 'Wings' }), 'mirror'],
      [screen.getByRole('spinbutton', { name: 'Segments' }), 'brackets'],
      [screen.getByRole('spinbutton', { name: 'Parts' }), 'brackets'],
    ]
    for (const [control, mark] of cases) {
      fireEvent.pointerEnter(lights(control))
      expect(link.setHighlight).toHaveBeenLastCalledWith(mark)
      fireEvent.pointerLeave(lights(control))
      expect(link.setHighlight).toHaveBeenLastCalledWith(null)
    }
    fireEvent.click(screen.getByRole('button', { name: /^Advanced/ }))
    fireEvent.pointerEnter(lights(screen.getByRole('radiogroup', { name: 'Runs' })))
    expect(link.setHighlight).toHaveBeenLastCalledWith('holding')
  })

  it('draws the offset ladder from the desk’s answer in place of the dials, a dot linking to its row', () => {
    const rows = [row('hex-1', 0, { label: 'Hex 1' }), row('hex-2', 1, { label: 'Hex 2' }), row('hex-3', 2, { label: 'Hex 3' }), row('hex-4', 3, { label: 'Hex 4' })]
    const fx = previewFx(4, {
      offsets: [0, 0.5, 0, 0.5],
      ladder: {
        order: { ranks: [0, 1, 2, 3], counts: [4, 4, 4, 4] },
        segments: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
        wings: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
        parts: { ranks: [0, 0, 1, 1], counts: [2, 2, 2, 2] },
        spread: [0, 0, 0.5, 0.5],
      },
    })
    const { link } = linkOf({ ladder: { fx, rows } })
    inspector(link)
    openSpread()
    expect(screen.queryByRole('img', { name: /^Phase of/ })).toBeNull()
    const at = (step: string) => [...document.querySelectorAll(`[data-ladder-step="${step}"] [data-ladder-dot]`)].map((d) => Number(d.getAttribute('data-at')))
    expect(at('order')).toEqual([0, 0.25, 0.5, 0.75])
    expect(at('segments')).toEqual([0, 0, 0.5, 0.5])
    expect(at('spread')).toEqual([0, 0, 0.5, 0.5])

    fireEvent.pointerEnter(document.querySelector('[data-ladder-step="order"] [data-ladder-dot="hex-3"]')!)
    expect(link.setLadderRow).toHaveBeenLastCalledWith('hex-3')
  })

  it('lends the graph’s handles a write through the 50 ms floor, the release landing, and tells the preview each move', () => {
    vi.useFakeTimers()
    vi.setSystemTime(1000)
    const sentAt: number[] = []
    updateFx.mockImplementation(() => {
      sentAt.push(Date.now())
      return true
    })
    const { link, held } = linkOf()
    inspector(link)
    expect(held.editor?.fxId).toBe(41)
    expect(held.editor?.levels).toBe(true)

    for (let i = 1; i <= 12; i++) {
      act(() => {
        vi.advanceTimersByTime(5)
        held.editor!.setParam('max', String(255 - i * 10), false)
      })
    }
    for (let i = 1; i < sentAt.length; i++) expect(sentAt[i] - sentAt[i - 1]).toBeGreaterThanOrEqual(50)
    const during = updateFx.mock.calls.length
    expect(during).toBeLessThan(12)
    // The preview hears every move, the floor or not.
    expect(link.preview).toHaveBeenLastCalledWith(41, expect.objectContaining({ parameters: expect.objectContaining({ max: '135' }) }))

    act(() => held.editor!.setParam('max', '135', true))
    expect(updateFx).toHaveBeenCalledTimes(during + 1)
    expect((lastFrame().parameters as Record<string, string>).max).toBe('135')
    act(() => held.editor!.set({ cycle: 8 }, true))
    expect(lastFrame()).toMatchObject({ cycle: 8, parameters: expect.objectContaining({ max: '135' }) })
  })
})
